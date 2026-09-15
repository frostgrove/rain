package com.gd.rain.audit

import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AuditDetailTest {
    private val changed = AuditEventType("tickets", "changed", "ticket", setOf("field", "count", "urgent"))

    @Test
    fun `detail serialises with keys in lexical order and escapes strings`() {
        val detail = AuditDetail.of("urgent" to true, "field" to "title \"new\"\n", "count" to 3)

        assertThat(detail.json()).isEqualTo("{\"count\":3,\"field\":\"title \\\"new\\\"\\u000a\",\"urgent\":true}")
        assertThat(AuditDetail.of("count" to 3L)).isEqualTo(AuditDetail.of("count" to 3))
    }

    @Test
    fun `a key the event type does not declare is refused, whatever it is called`() {
        assertThatThrownBy { AuditEvent(changed, AuditOutcome.OK, "t1", AuditDetail.of("note" to "harmless")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("[note]")
        assertThat(AuditEvent(changed, AuditOutcome.OK, "t1", AuditDetail.of("field" to "password")).detail["field"]).isEqualTo("password")
    }

    @Test
    fun `values are bounded scalars under well formed keys`() {
        assertThatThrownBy { AuditDetail.of("Bad-Key" to "x") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            AuditDetail.of("field" to "x".repeat(AuditDetail.MAX_STRING_LENGTH + 1))
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AuditDetail.of("field" to listOf("x")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AuditDetail.of("field" to "a", "field" to "b") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            AuditDetail.of(*Array(AuditDetail.MAX_ENTRIES + 1) { "k$it" to 1 })
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `an event type declared twice is a problem`() {
        val problems = AuditEventTypesCheck(listOf(changed, changed.copy(detailKeys = emptySet()))).problems()

        assertThat(problems.map { it.path to it.code }).containsExactly("audit:tickets.changed" to ProblemCode.CONTRADICTS)
    }
}
