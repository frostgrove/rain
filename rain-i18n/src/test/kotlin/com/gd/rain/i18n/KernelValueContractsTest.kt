package com.gd.rain.i18n

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.junit.jupiter.api.Test
import java.math.BigInteger

class KernelValueContractsTest {
    @Test
    fun `digest is canonical sha256`() {
        assertThat(Digest.sha256("rain".toByteArray()).hex)
            .isEqualTo("319b44c570a417ff3444896cd4aa77f052b6781773fc2f9aa1f1180ac745005c")
        assertThat(Digest.parse("0".repeat(64)).hex).isEqualTo("0".repeat(64))
    }

    @Test
    fun `digest rejects noncanonical spelling`() {
        assertThatIllegalArgumentException().isThrownBy { Digest.parse("A".repeat(64)) }
        assertThatIllegalArgumentException().isThrownBy { Digest.parse("0".repeat(63)) }
    }

    @Test
    fun `message key is exact module plus name`() {
        val key = MessageKey.parse("tickets.pending_count")

        assertThat(key.module).isEqualTo("tickets")
        assertThat(key.name).isEqualTo("pending_count")
        assertThat(key.value).isEqualTo("tickets.pending_count")
        assertThatIllegalArgumentException().isThrownBy { MessageKey.parse("tickets.pending.count") }
        assertThatIllegalArgumentException().isThrownBy { MessageKey("Tickets", "pending") }
    }

    @Test
    fun `unsigned long preserves the full unsigned range`() {
        assertThat(UnsignedLong.of(ULong.MAX_VALUE).toString()).isEqualTo("18446744073709551615")
        assertThat(UnsignedLong.parse("42")).isEqualTo(UnsignedLong.fromBigInteger(BigInteger.valueOf(42)))
        assertThatIllegalArgumentException().isThrownBy { UnsignedLong.parse("-1") }
        assertThatIllegalArgumentException().isThrownBy { UnsignedLong.fromBigInteger(BigInteger.ONE.shiftLeft(64)) }
    }

    @Test
    fun `text rejects malformed utf16 and optional state is explicit`() {
        assertThatIllegalArgumentException().isThrownBy { MessageValue.Text("bad\uD800") }
        assertThat(OptionalValue.Absent).isNotEqualTo(OptionalValue.Null)
        assertThat(OptionalValue.Present("text").value).isEqualTo("text")
    }

    @Test
    fun `limits refuse unsupported operational ceilings`() {
        assertThatIllegalArgumentException().isThrownBy { I18nLimits(maxLocales = 0) }
        assertThatIllegalArgumentException().isThrownBy { I18nLimits(maxOutputBytes = I18nLimits.MAX_OUTPUT_BYTES + 1) }
    }
}
