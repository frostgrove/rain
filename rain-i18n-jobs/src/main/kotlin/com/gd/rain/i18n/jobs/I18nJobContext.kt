package com.gd.rain.i18n.jobs

import com.gd.rain.i18n.CatalogOverlayRef
import com.gd.rain.i18n.CatalogRef
import com.gd.rain.i18n.Digest
import com.gd.rain.i18n.I18nView
import com.gd.rain.i18n.LocaleTag
import com.gd.rain.i18n.OverlayLayer
import com.gd.rain.i18n.Presentation
import com.gd.rain.jobs.context.DurableJobContextBinding
import com.gd.rain.jobs.context.DurableJobContextCapture
import com.gd.rain.jobs.context.DurableJobContextFragment
import com.gd.rain.jobs.context.DurableJobContextPermanentException
import com.gd.rain.jobs.context.DurableJobContextProvider
import com.gd.rain.jobs.context.DurableJobContextRequest
import com.gd.rain.jobs.context.DurableJobContextRestoreRequest
import com.gd.rain.jobs.context.DurableJobContextTerminalRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.ZoneId
import java.util.UUID

/**
 * Explicit enqueue scope for i18n-aware jobs.
 *
 * It is a small, nestable convenience around a typed [I18nJobDeliveryContext], not a process
 * default. `CurrentAtRender` and `PinnedSnapshot` remain an application-visible delivery choice.
 */
public object I18nJobContext {
    private val current: ThreadLocal<I18nJobDeliveryContext?> = ThreadLocal.withInitial { null }

    /** Returns the context explicitly scoped around the current enqueue operation, if any. */
    public fun current(): I18nJobDeliveryContext? = current.get()

    /** Captures the recipient presentation from an existing explicit view with current-at-render wording. */
    public fun currentAtRender(view: I18nView): I18nJobDeliveryContext =
        I18nJobDeliveryContext(recipient(view), I18nDeliverySelection.CurrentAtRender)

    /** Captures an exact catalog plus the complete validated overlay stack from an existing explicit view. */
    public fun pinnedSnapshot(view: I18nView): I18nJobDeliveryContext =
        I18nJobDeliveryContext(
            recipient(view),
            I18nDeliverySelection.PinnedSnapshot(view.catalog, view.spec.overlays.map { overlay -> overlay.reference }),
        )

    /** Runs [block] with one context and restores the enclosing enqueue scope on every exit path. */
    public fun <T> with(
        context: I18nJobDeliveryContext,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(context)
        return try {
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    private fun recipient(view: I18nView): I18nRecipientPresentation =
        I18nRecipientPresentation(view.spec.resolution.locale, view.spec.zone, view.spec.presentation)
}

/** The magic-first worker facade installed only while a successfully restored job handler executes. */
public data class I18nJobRuntimeContext(
    public val context: I18nJobDeliveryContext,
    public val view: I18nView,
)

public object I18nJobRuntime {
    private val current: ThreadLocal<I18nJobRuntimeContext?> = ThreadLocal.withInitial { null }

    /** Returns the i18n runtime for this job handler, or null outside an i18n-aware durable job. */
    public fun current(): I18nJobRuntimeContext? = current.get()

    /** Refuses an accidental ambient fallback outside the jobs execution scope. */
    public fun require(): I18nJobRuntimeContext = checkNotNull(current()) { "no i18n job runtime is installed on this thread" }

    internal fun install(context: I18nJobRuntimeContext): DurableJobContextBinding {
        val previous = current.get()
        current.set(context)
        return DurableJobContextBinding {
            if (previous == null) current.remove() else current.set(previous)
        }
    }
}

/**
 * The standalone `jobs ↔ i18n` durable-context provider.
 *
 * It serializes no message values, tenant identity, event, request or authentication data. The
 * normal jobs envelope supplies the invocation/payload digest to every provider; this provider
 * restores only a presentation/catalog choice and turns unavailable pinned data into a closed job
 * failure.
 */
public class I18nJobContextProvider(
    private val views: I18nDeliveryViews,
    private val durablePins: I18nDurableDeliveryPins? = null,
    private val pinPolicy: I18nDurablePinPolicy? = null,
) : DurableJobContextProvider {
    override val id: String = "i18n"

    override fun capture(request: DurableJobContextRequest): DurableJobContextCapture =
        I18nJobContext.current()?.let { context ->
            DurableJobContextCapture.Captured(
                DurableJobContextFragment.of(
                    FORMAT_VERSION,
                    capture(context, request),
                ),
            )
        } ?: DurableJobContextCapture.Absent

    override fun restore(request: DurableJobContextRestoreRequest): DurableJobContextBinding {
        val decoded = decode(request)
        val context = decoded.context
        if (decoded.pin != null && durablePins == null) {
            throw DurableJobContextPermanentException("the durable i18n pin authority is not installed")
        }
        decoded.pin?.let { pin ->
            if (pin.invocation != request.request.invocation) {
                throw DurableJobContextPermanentException("the durable i18n pin belongs to another job invocation")
            }
        }
        return when (val resolved = views.resolve(context)) {
            is I18nDeliveryView.Resolved -> {
                I18nJobRuntime.install(I18nJobRuntimeContext(context, resolved.view))
            }

            is I18nDeliveryView.SnapshotUnavailable -> {
                throw DurableJobContextPermanentException("the pinned i18n snapshot is unavailable")
            }

            is I18nDeliveryView.LocaleUnavailable -> {
                throw DurableJobContextPermanentException("the durable i18n locale is unavailable")
            }
        }
    }

    override fun onTerminal(request: DurableJobContextTerminalRequest) {
        val decoded = decode(DurableJobContextRestoreRequest(request.request, request.fragment, null))
        decoded.pin?.let { pin ->
            require(pin.invocation == request.request.invocation) { "the durable i18n pin belongs to another job invocation" }
            val authority = durablePins ?: throw DurableJobContextPermanentException("the durable i18n pin authority is not installed")
            authority.release(pin)
        }
    }

    private fun capture(
        context: I18nJobDeliveryContext,
        request: DurableJobContextRequest,
    ): ByteArray {
        val selection =
            context.selection as? I18nDeliverySelection.PinnedSnapshot
                ?: return I18nJobContextCodec.encode(context, null)
        val authority = durablePins ?: throw DurableJobContextPermanentException("a pinned i18n job requires a durable pin authority")
        val policy = pinPolicy ?: throw DurableJobContextPermanentException("a pinned i18n job requires a durable pin policy")
        val pin =
            when (
                val result =
                    authority.acquire(
                        I18nDurableDeliveryPinRequest(request.invocation, selection.catalog, policy.retention),
                    )
            ) {
                is I18nDurableDeliveryPinResult.Pinned -> {
                    result.pin
                }

                is I18nDurableDeliveryPinResult.Missing -> {
                    throw DurableJobContextPermanentException("the pinned i18n snapshot is unavailable")
                }

                is I18nDurableDeliveryPinResult.Limit -> {
                    throw DurableJobContextPermanentException("the durable i18n pin limit was reached: ${result.reason.name}")
                }
            }
        require(pin.catalog == selection.catalog) { "a durable i18n pin identifies another catalog" }
        return try {
            I18nJobContextCodec.encode(context, pin)
        } catch (failure: Throwable) {
            authority.release(pin)
            throw failure
        }
    }

    private fun decode(request: DurableJobContextRestoreRequest): DecodedI18nJobContext =
        try {
            when (request.fragment.version) {
                LEGACY_FORMAT_VERSION -> DecodedI18nJobContext(I18nJobContextCodec.decode(request.fragment.copy()), null)
                FORMAT_VERSION -> I18nJobContextCodec.decodeDurable(request.fragment.copy())
                else -> throw DurableJobContextPermanentException("the durable i18n job context version is unsupported")
            }
        } catch (failure: DurableJobContextPermanentException) {
            throw failure
        } catch (failure: IllegalArgumentException) {
            throw DurableJobContextPermanentException("the durable i18n job context is invalid", failure)
        }

    private companion object {
        const val LEGACY_FORMAT_VERSION: Int = 1
        const val FORMAT_VERSION: Int = 2
    }
}

/** Provider-private decoded context, including the optional reconstructible terminal-release lease. */
internal data class DecodedI18nJobContext(
    val context: I18nJobDeliveryContext,
    val pin: I18nDurableDeliveryPin?,
)

/** Strict bounded binary form for the provider-owned fragment, versioned separately from jobs' envelope. */
internal object I18nJobContextCodec {
    private const val MAGIC: Int = 0x52494A31
    private const val CURRENT_AT_RENDER: Int = 0
    private const val PINNED_SNAPSHOT: Int = 1
    private const val MAX_LOCALE_BYTES: Int = 128
    private const val MAX_ZONE_BYTES: Int = 256
    private const val MAX_REVISION_BYTES: Int = 128

    /** Legacy v1 encoding, retained only to decode already queued jobs. */
    fun encode(context: I18nJobDeliveryContext): ByteArray = encode(context, null, durablePinRequired = false)

    /** v2 encoding carries a reconstructible durable pin for every pinned delivery. */
    fun encode(
        context: I18nJobDeliveryContext,
        pin: I18nDurableDeliveryPin?,
    ): ByteArray = encode(context, pin, durablePinRequired = true)

    private fun encode(
        context: I18nJobDeliveryContext,
        pin: I18nDurableDeliveryPin?,
        durablePinRequired: Boolean,
    ): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeInt(MAGIC)
                writeText(output, context.recipient.locale.value, MAX_LOCALE_BYTES)
                writeText(output, context.recipient.zone.id, MAX_ZONE_BYTES)
                output.writeByte(context.recipient.presentation.ordinal)
                when (val selection = context.selection) {
                    I18nDeliverySelection.CurrentAtRender -> {
                        output.writeByte(CURRENT_AT_RENDER)
                    }

                    is I18nDeliverySelection.PinnedSnapshot -> {
                        if (durablePinRequired) {
                            requireNotNull(pin) { "a pinned durable i18n job context requires a pin" }
                            require(pin.catalog == selection.catalog) { "a durable i18n job pin identifies another catalog" }
                        } else {
                            require(pin == null) { "a legacy i18n job context does not carry a pin" }
                        }
                        output.writeByte(PINNED_SNAPSHOT)
                        writeReference(output, selection.catalog)
                        val overlays = selection.overlays.sortedBy { overlay -> overlay.layer.ordinal }
                        output.writeByte(overlays.size)
                        overlays.forEach { overlay ->
                            output.writeByte(overlay.layer.ordinal)
                            writeText(output, overlay.revision, MAX_REVISION_BYTES)
                            output.write(Digest.parse(overlay.digest.hex).hex.hexBytes())
                        }
                        if (durablePinRequired) {
                            output.writeLong(checkNotNull(pin).id.mostSignificantBits)
                            output.writeLong(pin.id.leastSignificantBits)
                            output.writeLong(pin.invocation.mostSignificantBits)
                            output.writeLong(pin.invocation.leastSignificantBits)
                            output.writeLong(pin.expiresAt.epochSecond)
                            output.writeInt(pin.expiresAt.nano)
                        }
                    }
                }
            }
            bytes.toByteArray().also { encoded ->
                require(encoded.size <= DurableJobContextFragment.MAX_BYTES) { "a durable i18n job context is too large" }
            }
        }

    /** Decodes the v1 fragment shape before the durable-pin field existed. */
    fun decode(bytes: ByteArray): I18nJobDeliveryContext = decode(bytes, durablePinRequired = false).context

    /** Decodes the v2 fragment shape and proves a pinned context has its exact release lease. */
    fun decodeDurable(bytes: ByteArray): DecodedI18nJobContext = decode(bytes, durablePinRequired = true)

    private fun decode(
        bytes: ByteArray,
        durablePinRequired: Boolean,
    ): DecodedI18nJobContext =
        try {
            require(bytes.isNotEmpty() && bytes.size <= DurableJobContextFragment.MAX_BYTES) {
                "a durable i18n job context has invalid size"
            }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readInt() == MAGIC) { "a durable i18n job context has an unknown format" }
                val localeText = readText(input, MAX_LOCALE_BYTES)
                val locale =
                    LocaleTag.parse(localeText).also { parsed ->
                        require(parsed.value == localeText) { "a durable i18n job locale is not canonical" }
                    }
                val zone = ZoneId.of(readText(input, MAX_ZONE_BYTES))
                val presentation =
                    Presentation.entries.getOrNull(input.readUnsignedByte())
                        ?: throw IllegalArgumentException("a durable i18n job presentation is invalid")
                val decoded =
                    when (input.readUnsignedByte()) {
                        CURRENT_AT_RENDER -> {
                            DecodedI18nJobContext(
                                I18nJobDeliveryContext(
                                    I18nRecipientPresentation(locale, zone, presentation),
                                    I18nDeliverySelection.CurrentAtRender,
                                ),
                                null,
                            )
                        }

                        PINNED_SNAPSHOT -> {
                            val catalog = readReference(input)
                            val count = input.readUnsignedByte()
                            require(count <= OverlayLayer.entries.size) { "a durable i18n job has too many overlays" }
                            val overlays =
                                buildList(count) {
                                    repeat(count) {
                                        val layer =
                                            OverlayLayer.entries.getOrNull(input.readUnsignedByte())
                                                ?: throw IllegalArgumentException("a durable i18n job overlay layer is invalid")
                                        val revision = readText(input, MAX_REVISION_BYTES)
                                        val digest = Digest.parse(input.readNBytes(DIGEST_BYTES).hex())
                                        add(CatalogOverlayRef(layer, revision, digest))
                                    }
                                }
                            val pin =
                                if (durablePinRequired) {
                                    I18nDurableDeliveryPin(
                                        UUID(input.readLong(), input.readLong()),
                                        UUID(input.readLong(), input.readLong()),
                                        catalog,
                                        java.time.Instant.ofEpochSecond(input.readLong(), input.readInt().toLong()),
                                    )
                                } else {
                                    null
                                }
                            DecodedI18nJobContext(
                                I18nJobDeliveryContext(
                                    I18nRecipientPresentation(locale, zone, presentation),
                                    I18nDeliverySelection.PinnedSnapshot(catalog, overlays),
                                ),
                                pin,
                            )
                        }

                        else -> {
                            throw IllegalArgumentException("a durable i18n job selection is invalid")
                        }
                    }
                require(input.available() == 0) { "a durable i18n job context has trailing bytes" }
                decoded
            }
        } catch (failure: IllegalArgumentException) {
            throw failure
        } catch (failure: Exception) {
            throw IllegalArgumentException("a durable i18n job context is malformed", failure)
        }

    private fun writeReference(
        output: DataOutputStream,
        reference: CatalogRef,
    ) {
        writeText(output, reference.revision, MAX_REVISION_BYTES)
        output.write(reference.digest.hex.hexBytes())
    }

    private fun readReference(input: DataInputStream): CatalogRef {
        val revision = readText(input, MAX_REVISION_BYTES)
        require(revision.all { character -> character.code in 0x21..0x7e }) {
            "a durable i18n job catalog revision is not printable ASCII"
        }
        return CatalogRef(revision, Digest.parse(input.readNBytes(DIGEST_BYTES).hex()))
    }

    private fun writeText(
        output: DataOutputStream,
        value: String,
        maximumBytes: Int,
    ) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= maximumBytes) { "a durable i18n job string has invalid size" }
        output.writeShort(bytes.size)
        output.write(bytes)
    }

    private fun readText(
        input: DataInputStream,
        maximumBytes: Int,
    ): String {
        val size = input.readUnsignedShort()
        require(size in 1..maximumBytes) { "a durable i18n job string has invalid size" }
        return input
            .readNBytes(size)
            .also { value -> require(value.size == size) { "a durable i18n job context is truncated" } }
            .toString(Charsets.UTF_8)
    }

    private fun String.hexBytes(): ByteArray {
        require(length == DIGEST_BYTES * 2) { "a digest has invalid hexadecimal size" }
        return ByteArray(DIGEST_BYTES) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun ByteArray.hex(): String {
        val digits = "0123456789abcdef"
        return buildString(size * 2) {
            this@hex.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(digits[unsigned ushr 4])
                append(digits[unsigned and 0x0f])
            }
        }
    }

    private const val DIGEST_BYTES: Int = 32
}
