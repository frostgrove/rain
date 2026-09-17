package com.gd.rain.tenancy.settings

import com.gd.rain.tenancy.CompositeTenantResolver
import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.TenantRuntime
import com.gd.rain.tenancy.TenantRuntimeManager
import com.gd.rain.test.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Instant

class TenantSettingsTest {
    private val ref = TenantRef.of("acme")
    private val scope =
        HmacTenantAuthority(
            "test",
            CompositeTenantResolver(emptyList()) { TenantResolution(ref, TenantLifecycle.ACTIVE, TenantEpoch(1), 1) },
            TenantAdmission.DEFAULT,
            ByteArray(32) { 1 },
            clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z")),
            random = SecureRandom(),
        ).let { authority -> authority to authority.lookup(ref, TenantOperation.READ) }

    @Test
    fun `declared setting decodes a defensive canonical versioned snapshot`() {
        val retries = TenantSettingSpec("orders.retries", IntCodec, default = 3) { require(it in 0..10) }
        val raw = IntCodec.encode(5)
        val source = TenantSettingsSource { TenantSettingsSnapshot(TenantSettingsVersion(4), mapOf(retries.key to raw)) }
        val settings = TenantSettingsFactory(scope.first, source, TenantSettingsRegistry(listOf(retries))).forScope(scope.second)
        raw[0] = 0

        assertThat(settings.version).isEqualTo(TenantSettingsVersion(4))
        assertThat(settings.get(retries)).isEqualTo(5)
    }

    @Test
    fun `undeclared and noncanonical setting values are refused`() {
        val declared = TenantSettingSpec("orders.label", LowerCaseCodec, default = "default")
        val undeclared = TenantSettingSpec("orders.other", LowerCaseCodec, default = "default")
        val factory =
            TenantSettingsFactory(
                scope.first,
                TenantSettingsSource { TenantSettingsSnapshot(TenantSettingsVersion(1), mapOf(declared.key to "UP".toByteArray())) },
                TenantSettingsRegistry(listOf(declared)),
            )
        val settings = factory.forScope(scope.second)

        assertThatThrownBy { settings.get(undeclared) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { settings.get(declared) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `runtime task exposes one scoped immutable snapshot and removes its capability on exit`() {
        val retries = TenantSettingSpec("orders.retries", IntCodec, default = 3)
        val factory =
            TenantSettingsFactory(
                scope.first,
                TenantSettingsSource { TenantSettingsSnapshot(TenantSettingsVersion(1), mapOf(retries.key to IntCodec.encode(4))) },
                TenantSettingsRegistry(listOf(retries)),
            )
        val manager = TenantRuntimeManager(scope.first, listOf(TenantSettingsRuntimeTask(factory)))
        lateinit var runtime: TenantRuntime

        manager.with(scope.second, TenantOperation.READ) { entered ->
            runtime = entered
            assertThat(TenantRuntime.current()).isSameAs(entered)
            assertThat(entered.settings().get(retries)).isEqualTo(4)
        }

        assertThatThrownBy { TenantRuntime.current() }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { runtime.settings() }.isInstanceOf(IllegalStateException::class.java)
    }

    private object IntCodec : TenantSettingCodec<Int> {
        override val maximumBytes: Int = 4

        override fun encode(value: Int): ByteArray = byteArrayOf(0, 0, 0, value.toByte())

        override fun decode(value: ByteArray): Int {
            require(value.size == 4)
            return value[3].toInt()
        }
    }

    private object LowerCaseCodec : TenantSettingCodec<String> {
        override val maximumBytes: Int = 32

        override fun encode(value: String): ByteArray = value.lowercase().toByteArray()

        override fun decode(value: ByteArray): String = value.toString(Charsets.UTF_8).lowercase()
    }
}
