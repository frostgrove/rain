package com.gd.rain.audit.scope

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AuditScopeTest {
    @Test
    fun `an audit scope owns an opaque defensive digest and compares its complete fence`() {
        val supplied = byteArrayOf(1, 2, 3)
        val scope = AuditScope.of("tenant", supplied, 4)
        supplied[0] = 9

        assertThat(scope.digest()).containsExactly(1, 2, 3)
        assertThat(scope).isEqualTo(AuditScope.of("tenant", byteArrayOf(1, 2, 3), 4))
        assertThat(scope.hashCode()).isEqualTo(AuditScope.of("tenant", byteArrayOf(1, 2, 3), 4).hashCode())
        assertThat(scope)
            .isNotEqualTo(AuditScope.of("other", byteArrayOf(1, 2, 3), 4))
            .isNotEqualTo(AuditScope.of("tenant", byteArrayOf(3, 2, 1), 4))
            .isNotEqualTo(AuditScope.of("tenant", byteArrayOf(1, 2, 3), 5))
            .isNotEqualTo("tenant")
        assertThat(scope.toString()).contains("redacted").doesNotContain("1, 2, 3")
    }

    @Test
    fun `an audit scope refuses unstable or unbounded fences`() {
        assertThatThrownBy { AuditScope.of("Tenant", byteArrayOf(1), 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AuditScope.of("tenant", byteArrayOf(), 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            AuditScope.of("tenant", ByteArray(AuditScope.MAX_DIGEST_BYTES + 1), 1)
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AuditScope.of("tenant", byteArrayOf(1), 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
