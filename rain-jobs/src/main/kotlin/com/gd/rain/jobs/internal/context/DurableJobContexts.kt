package com.gd.rain.jobs.internal.context

import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.context.DurableJobContextBinding
import com.gd.rain.jobs.context.DurableJobContextCapture
import com.gd.rain.jobs.context.DurableJobContextFragment
import com.gd.rain.jobs.context.DurableJobContextPermanentException
import com.gd.rain.jobs.context.DurableJobContextProvider
import com.gd.rain.jobs.context.DurableJobContextRequest
import com.gd.rain.jobs.context.DurableJobContextRequiredException
import com.gd.rain.jobs.context.DurableJobContextRestoreRequest
import com.gd.rain.jobs.context.DurableJobContextTerminalRequest
import com.gd.rain.jobs.context.JobPayloadDigest
import com.gd.rain.jobs.context.JobProducerPartition
import com.gd.rain.jobs.context.TenantBindingMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/** Stored fields owned by the jobs ledger; every byte array is copied at the persistence boundary. */
internal data class StoredJobContext(
    val version: Short?,
    val bytes: ByteArray?,
    val producerPartition: ByteArray?,
    val payloadDigest: ByteArray?,
)

/** One envelope is independent from any provider's wire format. */
internal class DurableJobContexts(
    providers: Collection<DurableJobContextProvider>,
) {
    private val providers: Map<String, DurableJobContextProvider> = providers.associateBy(DurableJobContextProvider::id)
    private val ordered: List<DurableJobContextProvider>

    init {
        require(providers.map(DurableJobContextProvider::id).all(ID::matches)) { "a durable job context provider id is not stable" }
        require(this.providers.size == providers.size) { "a durable job context provider id is declared more than once" }
        ordered =
            this.providers
                .toSortedMap()
                .values
                .toList()
    }

    fun capture(
        definition: String,
        invocation: UUID,
        payloadJson: String,
        mode: TenantBindingMode,
    ): StoredJobContext {
        val digest = JobPayloadDigest.of(payloadJson.toByteArray(Charsets.UTF_8))
        if (mode == TenantBindingMode.CENTRAL) {
            return StoredJobContext(null, null, null, digest.copy())
        }
        val request = DurableJobContextRequest(DurableJobContextRequest.JOBS_NAMESPACE, definition, invocation, digest, mode)
        val captures = ordered.map { provider -> provider to provider.capture(request) }
        val requiredCaptured =
            captures.any { (provider, capture) ->
                provider.providesRequiredBinding &&
                    capture is DurableJobContextCapture.Captured
            }
        if (mode == TenantBindingMode.REQUIRED && !requiredCaptured) throw DurableJobContextRequiredException()
        val fragments =
            captures.mapNotNull { (provider, capture) ->
                (capture as? DurableJobContextCapture.Captured)?.let { CapturedFragment(provider.id, it.fragment) }
            }
        val partitions = captures.mapNotNull { (_, capture) -> (capture as? DurableJobContextCapture.Captured)?.producerPartition }
        require(partitions.size <= 1) { "at most one durable job context provider may declare a producer partition" }
        if (fragments.isEmpty()) return StoredJobContext(null, null, null, digest.copy())
        return StoredJobContext(ENVELOPE_VERSION, encode(fragments), partitions.singleOrNull()?.copy(), digest.copy())
    }

    fun restore(
        definition: String,
        invocation: UUID,
        mode: TenantBindingMode,
        stored: StoredJobContext,
    ): DurableJobContextBinding {
        val hasContext = stored.version != null || stored.bytes != null || stored.producerPartition != null
        if (mode == TenantBindingMode.CENTRAL) {
            if (hasContext) throw DurableJobContextPermanentException("a central job contains a durable context")
            return DurableJobContextBinding.NONE
        }
        if (!hasContext) {
            if (mode == TenantBindingMode.REQUIRED) throw DurableJobContextPermanentException("a required job has no durable context")
            return DurableJobContextBinding.NONE
        }
        val version = stored.version ?: throw DurableJobContextPermanentException("a durable job context has no version")
        val bytes = stored.bytes ?: throw DurableJobContextPermanentException("a durable job context has no bytes")
        if (version !=
            ENVELOPE_VERSION
        ) {
            throw DurableJobContextPermanentException("durable job context envelope version $version is unsupported")
        }
        val digest = stored.payloadDigest ?: throw DurableJobContextPermanentException("a durable job context has no payload digest")
        val request =
            DurableJobContextRequest(
                DurableJobContextRequest.JOBS_NAMESPACE,
                definition,
                invocation,
                payloadDigest(digest),
                mode,
            )
        val partition = stored.producerPartition?.let(JobProducerPartition::of)
        val fragments = decode(bytes)
        if (mode == TenantBindingMode.REQUIRED && fragments.none { providers[it.id]?.providesRequiredBinding == true }) {
            throw DurableJobContextPermanentException("a required job has no restorable durable binding")
        }
        val bindings = mutableListOf<DurableJobContextBinding>()
        try {
            fragments.forEach { fragment ->
                val provider =
                    providers[fragment.id]
                        ?: throw DurableJobContextPermanentException("durable job context provider ${fragment.id} is not installed")
                bindings += provider.restore(DurableJobContextRestoreRequest(request, fragment.value, partition))
            }
        } catch (failure: Throwable) {
            close(bindings, failure)
            throw failure
        }
        return DurableJobContextBinding { close(bindings, null) }
    }

    /** Delivers post-receipt cleanup to installed providers; a stale/missing provider cannot reopen a terminal job. */
    fun terminal(
        definition: String,
        invocation: UUID,
        mode: TenantBindingMode,
        stored: StoredJobContext,
        state: JobState,
    ) {
        check(state.terminal) { "a durable context terminal callback requires a terminal job state" }
        val hasContext = stored.version != null || stored.bytes != null || stored.producerPartition != null
        if (!hasContext || mode == TenantBindingMode.CENTRAL) return
        val version = stored.version ?: return
        val bytes = stored.bytes ?: return
        if (version != ENVELOPE_VERSION) return
        val digest = stored.payloadDigest ?: return
        val request =
            DurableJobContextRequest(
                DurableJobContextRequest.JOBS_NAMESPACE,
                definition,
                invocation,
                payloadDigest(digest),
                mode,
            )
        val partition = stored.producerPartition?.let(JobProducerPartition::of)
        decode(bytes).forEach { fragment ->
            providers[fragment.id]?.onTerminal(DurableJobContextTerminalRequest(request, fragment.value, state))
        }
    }

    private fun payloadDigest(value: ByteArray): JobPayloadDigest =
        try {
            JobPayloadDigest.stored(value)
        } catch (failure: IllegalArgumentException) {
            throw DurableJobContextPermanentException("a durable job context has an invalid payload digest", failure)
        }

    private fun encode(fragments: List<CapturedFragment>): ByteArray {
        require(fragments.size <= MAX_FRAGMENTS) { "a durable job context has more than $MAX_FRAGMENTS providers" }
        val value =
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(MAGIC)
                    output.writeShort(fragments.size)
                    fragments.forEach { fragment ->
                        val id = fragment.id.toByteArray(Charsets.UTF_8)
                        output.writeByte(id.size)
                        output.write(id)
                        output.writeShort(fragment.value.version)
                        val context = fragment.value.copy()
                        output.writeShort(context.size)
                        output.write(context)
                    }
                }
                bytes.toByteArray()
            }
        require(value.size <= MAX_ENVELOPE_BYTES) { "a durable job context envelope is at most $MAX_ENVELOPE_BYTES bytes" }
        return value
    }

    private fun decode(value: ByteArray): List<DecodedFragment> {
        if (value.size !in
            1..MAX_ENVELOPE_BYTES
        ) {
            throw DurableJobContextPermanentException("a durable job context envelope has invalid size")
        }
        return try {
            DataInputStream(ByteArrayInputStream(value)).use { input ->
                require(input.readInt() == MAGIC) { "a durable job context envelope has an unknown format" }
                val count = input.readUnsignedShort()
                require(count <= MAX_FRAGMENTS) { "a durable job context envelope has too many fragments" }
                val fragments =
                    buildList(count) {
                        repeat(count) {
                            val idSize = input.readUnsignedByte()
                            require(idSize in 1..MAX_ID_BYTES) { "a durable job context provider id has invalid size" }
                            val id = ByteArray(idSize).also(input::readFully).toString(Charsets.UTF_8)
                            require(ID.matches(id)) { "a durable job context provider id is not stable" }
                            val version = input.readUnsignedShort()
                            val contextSize = input.readUnsignedShort()
                            require(contextSize <= DurableJobContextFragment.MAX_BYTES) { "a durable job context fragment is too large" }
                            add(DecodedFragment(id, DurableJobContextFragment.of(version, ByteArray(contextSize).also(input::readFully))))
                        }
                    }
                require(input.available() == 0) { "a durable job context envelope has trailing bytes" }
                require(
                    fragments.map(DecodedFragment::id).distinct().size == fragments.size,
                ) { "a durable job context envelope repeats a provider" }
                fragments
            }
        } catch (failure: DurableJobContextPermanentException) {
            throw failure
        } catch (failure: Exception) {
            throw DurableJobContextPermanentException("a durable job context envelope is malformed", failure)
        }
    }

    private fun close(
        bindings: List<DurableJobContextBinding>,
        primary: Throwable?,
    ) {
        var failure: Throwable? = primary
        bindings.asReversed().forEach { binding ->
            try {
                binding.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
            }
        }
        if (primary == null) failure?.let { throw it }
    }

    private data class CapturedFragment(
        val id: String,
        val value: DurableJobContextFragment,
    )

    private data class DecodedFragment(
        val id: String,
        val value: DurableJobContextFragment,
    )

    private companion object {
        const val MAGIC: Int = 0x524A4331
        const val ENVELOPE_VERSION: Short = 1
        const val MAX_FRAGMENTS: Int = 16
        const val MAX_ENVELOPE_BYTES: Int = 16 * 1024
        const val MAX_ID_BYTES: Int = 64
        val ID: Regex = Regex("^[a-z][a-z0-9_.-]{0,63}$")
    }
}
