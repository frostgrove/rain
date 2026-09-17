package com.gd.rain.tenancy.settings

import com.gd.rain.tenancy.TenantAuthority
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRuntime
import com.gd.rain.tenancy.TenantRuntimeLease
import com.gd.rain.tenancy.TenantRuntimeTask
import com.gd.rain.tenancy.TenantScope

/** One declared setting codec with a bounded canonical byte representation. */
public interface TenantSettingCodec<T : Any> {
    public val maximumBytes: Int

    public fun encode(value: T): ByteArray

    public fun decode(value: ByteArray): T
}

/** A stable setting declaration. Secret settings encode only a secret reference, never secret material. */
public class TenantSettingSpec<T : Any>(
    public val key: String,
    public val codec: TenantSettingCodec<T>,
    public val default: T,
    public val secretReference: Boolean = false,
    private val validator: (T) -> Unit = {},
) {
    init {
        require(KEY.matches(key)) { "tenant setting key is not stable" }
        require(codec.maximumBytes in 1..MAX_SETTING_BYTES) { "tenant setting codec byte limit is out of bounds" }
        validate(default)
    }

    internal fun validate(value: T) {
        val encoded = codec.encode(value)
        require(encoded.size <= codec.maximumBytes) { "tenant setting codec exceeds its declared byte limit" }
        validator(value)
    }

    private companion object {
        const val MAX_SETTING_BYTES: Int = 65_536
        val KEY: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/** A positive immutable version of one tenant runtime-settings snapshot. */
@JvmInline
public value class TenantSettingsVersion(
    public val value: Long,
) {
    init {
        require(value > 0) { "tenant settings version is positive" }
    }
}

/** Raw persisted bytes, deliberately copied at every boundary. */
public class TenantSettingsSnapshot(
    public val version: TenantSettingsVersion,
    values: Map<String, ByteArray>,
) {
    private val values: Map<String, ByteArray> = values.mapValues { (_, value) -> value.copyOf() }

    init {
        require(values.keys.all(KEY::matches)) { "tenant settings snapshot contains an invalid key" }
        require(values.values.all { it.size <= MAX_SETTING_BYTES }) { "tenant settings snapshot contains an oversized value" }
    }

    internal fun raw(key: String): ByteArray? = values[key]?.copyOf()

    private companion object {
        const val MAX_SETTING_BYTES: Int = 65_536
        val KEY: Regex = Regex("^[a-z][a-z0-9_.-]{0,127}$")
    }
}

/** Loads one versioned snapshot. Implementations decide where control-plane settings are persisted. */
public fun interface TenantSettingsSource {
    public fun load(scope: TenantScope): TenantSettingsSnapshot
}

/** Validates the complete declared setting catalogue and refuses arbitrary-map lookup. */
public class TenantSettingsRegistry(
    specs: Collection<TenantSettingSpec<*>>,
) {
    private val byKey: Map<String, TenantSettingSpec<*>> = specs.associateBy(TenantSettingSpec<*>::key)

    init {
        require(byKey.size == specs.size) { "tenant setting key is declared more than once" }
    }

    internal fun <T : Any> requireDeclared(spec: TenantSettingSpec<T>) {
        require(byKey[spec.key] === spec) { "tenant setting is not declared by this registry" }
    }

    /** Builds a typed, validated control-plane patch without exposing an arbitrary raw settings map. */
    public fun patch(block: TenantSettingsPatchBuilder.() -> Unit): TenantSettingsPatch =
        TenantSettingsPatchBuilder(this).apply(block).build()
}

/** One atomic update over declared settings, constructed only through [TenantSettingsRegistry.patch]. */
public class TenantSettingsPatch internal constructor(
    internal val changes: Map<String, TenantSettingMutation>,
)

/** Typed builder for a bounded runtime-settings mutation. */
public class TenantSettingsPatchBuilder internal constructor(
    private val registry: TenantSettingsRegistry,
) {
    private val changes = linkedMapOf<String, TenantSettingMutation>()

    /** Stores a canonical non-secret setting value. */
    public fun <T : Any> set(
        spec: TenantSettingSpec<T>,
        value: T,
    ) {
        registry.requireDeclared(spec)
        require(!spec.secretReference) { "secret tenant settings accept only a secret reference" }
        spec.validate(value)
        put(spec.key, TenantSettingMutation.Value(spec.codec.encode(value)))
    }

    /** Stores a bounded reference that the owning integration, not this generic settings layer, resolves. */
    public fun <T : Any> setSecretReference(
        spec: TenantSettingSpec<T>,
        reference: String,
    ) {
        registry.requireDeclared(spec)
        require(spec.secretReference) { "non-secret tenant settings cannot store a secret reference" }
        require(reference.isNotBlank() && reference.toByteArray(Charsets.UTF_8).size <= MAX_SECRET_REFERENCE_BYTES) {
            "tenant secret reference is out of bounds"
        }
        require(reference.none(Char::isISOControl)) { "tenant secret reference contains a control character" }
        put(spec.key, TenantSettingMutation.SecretReference(reference))
    }

    /** Removes an override so reads return the spec's declared default. */
    public fun <T : Any> clear(spec: TenantSettingSpec<T>) {
        registry.requireDeclared(spec)
        put(spec.key, TenantSettingMutation.Remove)
    }

    internal fun build(): TenantSettingsPatch {
        require(changes.isNotEmpty()) { "tenant settings patch is empty" }
        return TenantSettingsPatch(changes.toMap())
    }

    private fun put(
        key: String,
        change: TenantSettingMutation,
    ) {
        check(changes.put(key, change) == null) { "tenant settings patch changes $key more than once" }
    }

    private companion object {
        const val MAX_SECRET_REFERENCE_BYTES: Int = 512
    }
}

/** Internal persistence form; public callers cannot construct raw setting bytes. */
internal sealed interface TenantSettingMutation {
    class Value(
        value: ByteArray,
    ) : TenantSettingMutation {
        val bytes: ByteArray = value.copyOf()
    }

    data class SecretReference(
        val value: String,
    ) : TenantSettingMutation

    data object Remove : TenantSettingMutation
}

/** Immutable typed view of the current tenant's declared settings. */
public class TenantSettings internal constructor(
    public val version: TenantSettingsVersion,
    private val registry: TenantSettingsRegistry,
    private val snapshot: TenantSettingsSnapshot,
) {
    public fun <T : Any> get(spec: TenantSettingSpec<T>): T {
        registry.requireDeclared(spec)
        val encoded = snapshot.raw(spec.key) ?: return spec.default
        require(encoded.size <= spec.codec.maximumBytes) { "tenant setting exceeds its declared byte limit" }
        val decoded = spec.codec.decode(encoded.copyOf())
        val canonical = spec.codec.encode(decoded)
        require(canonical.contentEquals(encoded)) { "tenant setting is not canonically encoded" }
        spec.validate(decoded)
        return decoded
    }
}

/** Explicit factory for a scope-bound immutable snapshot; it is also suitable for a runtime task. */
public class TenantSettingsFactory(
    private val authority: TenantAuthority,
    private val source: TenantSettingsSource,
    private val registry: TenantSettingsRegistry,
) {
    public fun forScope(scope: TenantScope): TenantSettings {
        authority.current(scope, TenantOperation.READ)
        val snapshot = source.load(scope)
        return TenantSettings(snapshot.version, registry, snapshot)
    }
}

/** Installs one immutable typed snapshot for the runtime's lexical lifetime. */
public class TenantSettingsRuntimeTask(
    private val factory: TenantSettingsFactory,
) : TenantRuntimeTask {
    override val id: String = "tenant-settings"

    override fun enter(runtime: TenantRuntime): TenantRuntimeLease {
        val settings = factory.forScope(runtime.scope)
        runtime.installSettings(settings)
        return TenantRuntimeLease { runtime.uninstallSettings(settings) }
    }
}
