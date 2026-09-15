package com.gd.rain.persistence.sql

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.dao.DataAccessResourceFailureException
import java.sql.SQLException

class SqlStatesTest {
    @Test
    fun `the state is found through the wrapping Spring puts around the driver exception`() {
        assertThat(SqlStates.of(DataAccessResourceFailureException("wrapped", SQLException("driver", "40P01")))).isEqualTo("40P01")
    }

    @Test
    fun `a batch failure's state is found through the next exception`() {
        val batch = SQLException("batch aborted", null as String?)
        batch.nextException = SQLException("the real one", "23505")

        assertThat(SqlStates.of(DataAccessResourceFailureException("wrapped", batch))).isEqualTo("23505")
    }

    @Test
    fun `a chain that loops is walked once`() {
        val first = SQLException("first", null as String?)
        val second = SQLException("second", null as String?, first)
        first.nextException = second

        assertThat(SqlStates.of(first)).isNull()
    }

    @Test
    fun `a failure that is not from the database has no state`() {
        assertThat(SqlStates.of(IllegalStateException("elsewhere"))).isNull()
    }

    @ParameterizedTest(name = "{0} retryable: {1}")
    @CsvSource("40P01, true", "55P03, true", "40001, true", "23505, false", "57014, false", "42601, false")
    fun `only the states that repetition fixes are retryable`(
        state: String,
        retryable: Boolean,
    ) {
        assertThat(SqlStates.retryable(SQLException("x", state))).isEqualTo(retryable)
    }
}
