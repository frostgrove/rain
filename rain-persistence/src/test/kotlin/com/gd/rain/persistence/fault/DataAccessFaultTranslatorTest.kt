package com.gd.rain.persistence.fault

import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.postgresql.util.PSQLException
import org.postgresql.util.PSQLState
import org.postgresql.util.ServerErrorMessage
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
    fun `a mapper receives structured PostgreSQL provenance and replaces only the public violation decision`() {
        var offered: DatabaseError? = null
        val mapper =
            DatabaseViolationMapper { error ->
                offered = error
                Violation.at(path("email"), error.defaultCode)
            }

        val fault =
            DataAccessFaultTranslator.translate(
                postgres("23502", column = "email", constraint = "users_email_not_null"),
                listOf(mapper),
            )!!

        assertThat(offered!!.source)
            .isEqualTo(DatabaseErrorSource(schema = "public", table = "users", column = "email", constraint = "users_email_not_null"))
        assertThat(fault.kind).isEqualTo(FaultKind.VALIDATION)
        assertThat(fault.violations.single().pointer).isEqualTo("/email")
        assertThat(fault.cause).isInstanceOf(PSQLException::class.java)
    }

    @Test
    fun `a failed database mapper cannot suppress a later mapping or the safe default`() {
        val broken = DatabaseViolationMapper { throw IllegalStateException("mapper failed") }
        val working = DatabaseViolationMapper { Violation.at(path("email"), it.defaultCode) }

        val fault = DataAccessFaultTranslator.translate(postgres("23505", constraint = "users_email_key"), listOf(broken, working))!!

        assertThat(fault.violations.single().pointer).isEqualTo("/email")
    }

    @Test
    fun `declining mappers retain the safe general validation refusal`() {
        val fault =
            DataAccessFaultTranslator.translate(
                SQLException("not null", "23502"),
                listOf(DatabaseViolationMapper { null }),
            )!!

        assertThat(fault.code).isEqualTo(RainErrorCodes.REQUIRED)
        assertThat(fault.violations.single().pointer).isEmpty()
    }

    @Test
    fun `PostgreSQL provenance tolerates absent and blank diagnostics`() {
        val offered = mutableListOf<DatabaseError>()
        val mapper =
            DatabaseViolationMapper { error ->
                offered += error
                null
            }

        DataAccessFaultTranslator.translate(PSQLException("not null", PSQLState.NOT_NULL_VIOLATION), listOf(mapper))
        DataAccessFaultTranslator.translate(
            postgres("23502", schema = "", table = " ", constraint = "users_value_not_null"),
            listOf(mapper),
        )
        DataAccessFaultTranslator.translate(
            postgres("23502", schema = "", table = "", column = "", constraint = ""),
            listOf(mapper),
        )

        assertThat(offered.map(DatabaseError::source))
            .containsExactly(
                null,
                DatabaseErrorSource(schema = null, table = null, column = null, constraint = "users_value_not_null"),
                null,
            )
    }

    @Test
    fun `provenance traversal follows next exceptions once and ignores a different PostgreSQL state`() {
        val outer = SQLException("not null", "23502")
        val nested = postgres("23505", constraint = "users_email_key")
        outer.setNextException(nested)
        nested.setNextException(outer)
        var source: DatabaseErrorSource? = DatabaseErrorSource(null, null, null, "sentinel")

        DataAccessFaultTranslator.translate(
            outer,
            listOf(
                DatabaseViolationMapper { error ->
                    source = error.source
                    null
                },
            ),
        )

        assertThat(source).isNull()
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
        assertThat(DataAccessFaultTranslator.translate(DataAccessResourceFailureException("unknown database failure"))!!.kind)
            .isEqualTo(FaultKind.INTERNAL)
    }

    @Test
    fun `a failure that has nothing to do with the database is not translated`() {
        assertThat(DataAccessFaultTranslator.translate(IllegalArgumentException("no"))).isNull()
    }

    @Test
    fun `database mapper inputs reject missing provenance and malformed SQLSTATE`() {
        assertThatThrownBy { DatabaseErrorSource(null, null, null, null) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DatabaseErrorSource(" ", "", null, "\t") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy {
            DatabaseError("2350x", FaultKind.VALIDATION, RainErrorCodes.CHECK, null)
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun postgres(
        state: String,
        schema: String? = "public",
        table: String? = "users",
        column: String? = null,
        constraint: String? = null,
    ): PSQLException {
        val fields =
            buildString {
                append("SERROR\u0000C").append(state).append("\u0000Mrefused\u0000")
                schema?.let { append('s').append(it).append('\u0000') }
                table?.let { append('t').append(it).append('\u0000') }
                column?.let { append('c').append(it).append('\u0000') }
                constraint?.let { append('n').append(it).append('\u0000') }
                append('\u0000')
            }
        return PSQLException(ServerErrorMessage(fields))
    }
}
