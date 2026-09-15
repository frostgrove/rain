package com.gd.rain.persistence.fault

import com.gd.rain.core.error.FaultKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import java.sql.SQLException

class DataAccessFaultTranslatorTest {
    @ParameterizedTest(name = "{0} is {1} {2}")
    @CsvSource(
        "23505, CONFLICT,    unique",
        "23503, CONFLICT,    foreign_key",
        "23001, CONFLICT,    restrict",
        "23P01, CONFLICT,    exclusion",
        "23502, VALIDATION,  required",
        "23514, VALIDATION,  check",
        "22001, VALIDATION,  too_long",
        "22003, VALIDATION,  out_of_range",
        "22P02, BAD_REQUEST, invalid_format",
        "40P01, RETRYABLE,   deadlock",
        "40001, RETRYABLE,   serialization_failure",
        "55P03, RETRYABLE,   lock_timeout",
        "57014, RETRYABLE,   statement_timeout",
        "25P02, RETRYABLE,   transaction_aborted",
        "42P01, INTERNAL,    internal",
        "42703, INTERNAL,    internal",
        "XX000, INTERNAL,    internal",
    )
    fun `a SQLState maps to its declared kind and code`(
        state: String,
        kind: FaultKind,
        code: String,
    ) {
        val fault = DataAccessFaultTranslator.translate(DataAccessResourceFailureException("wrapped", SQLException("driver", state)))

        assertThat(fault).isNotNull
        assertThat(fault!!.kind).isEqualTo(kind)
        assertThat(fault.code.value).isEqualTo(code)
    }

    @Test
    fun `a constraint validation fault names its violation so it is a coherent validation fault`() {
        val fault = DataAccessFaultTranslator.translate(SQLException("not null", "23502"))!!

        assertThat(fault.violations.map { it.code.value }).containsExactly("required")
    }

    @Test
    fun `a statement cancelled by a timeout is not reported as a lock timeout`() {
        assertThat(DataAccessFaultTranslator.translate(SQLException("canceling statement due to statement timeout", "57014"))!!.code.value)
            .isEqualTo("statement_timeout")
    }

    @Test
    fun `stateless Spring data access failures are classified by type`() {
        assertThat(DataAccessFaultTranslator.translate(OptimisticLockingFailureException("stale"))!!.code.value).isEqualTo("stale_version")
        assertThat(DataAccessFaultTranslator.translate(DuplicateKeyException("dup"))!!.code.value).isEqualTo("unique")
    }

    @Test
    fun `a failure that has nothing to do with the database is not translated`() {
        assertThat(DataAccessFaultTranslator.translate(IllegalArgumentException("no"))).isNull()
    }
}
