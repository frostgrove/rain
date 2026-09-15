package com.gd.rain.persistence.fault

import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.persistence.sql.SqlStates
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import java.sql.SQLException

/**
 * Database failures, classified by SQLState from a declared table. A state not in the table is an
 * internal failure, never a guess at what it might mean.
 */
public object DataAccessFaultTranslator : FaultTranslator {
    override fun translate(failure: Throwable): Fault? {
        if (failure !is DataAccessException && generateSequence(failure, Throwable::cause).none { it is SQLException }) return null
        val state = SqlStates.of(failure)
        return when {
            state != null -> forState(state, failure)
            failure is OptimisticLockingFailureException -> Fault(FaultKind.CONFLICT, RainErrorCodes.STALE_VERSION, cause = failure)
            failure is DuplicateKeyException -> Fault(FaultKind.CONFLICT, RainErrorCodes.UNIQUE, cause = failure)
            else -> Fault(FaultKind.INTERNAL, cause = failure)
        }
    }

    private fun forState(
        state: String,
        failure: Throwable,
    ): Fault =
        when (state) {
            SqlStates.UNIQUE_VIOLATION -> Fault(FaultKind.CONFLICT, RainErrorCodes.UNIQUE, cause = failure)
            SqlStates.FOREIGN_KEY_VIOLATION -> Fault(FaultKind.CONFLICT, RainErrorCodes.FOREIGN_KEY, cause = failure)
            SqlStates.RESTRICT_VIOLATION -> Fault(FaultKind.CONFLICT, RainErrorCodes.RESTRICT, cause = failure)
            SqlStates.EXCLUSION_VIOLATION -> Fault(FaultKind.CONFLICT, RainErrorCodes.EXCLUSION, cause = failure)
            SqlStates.NOT_NULL_VIOLATION -> invalid(Violation.general(RainErrorCodes.REQUIRED), failure)
            SqlStates.CHECK_VIOLATION -> invalid(Violation.general(RainErrorCodes.CHECK), failure)
            SqlStates.STRING_DATA_RIGHT_TRUNCATION -> invalid(Violation.general(RainErrorCodes.TOO_LONG), failure)
            SqlStates.NUMERIC_VALUE_OUT_OF_RANGE -> invalid(Violation.general(RainErrorCodes.OUT_OF_RANGE), failure)
            SqlStates.INVALID_TEXT_REPRESENTATION -> Fault(FaultKind.BAD_REQUEST, RainErrorCodes.INVALID_FORMAT, cause = failure)
            SqlStates.DEADLOCK_DETECTED -> Fault(FaultKind.RETRYABLE, RainErrorCodes.DEADLOCK, cause = failure)
            SqlStates.SERIALIZATION_FAILURE -> Fault(FaultKind.RETRYABLE, RainErrorCodes.SERIALIZATION_FAILURE, cause = failure)
            SqlStates.LOCK_NOT_AVAILABLE -> Fault(FaultKind.RETRYABLE, RainErrorCodes.LOCK_TIMEOUT, cause = failure)
            SqlStates.QUERY_CANCELED -> Fault(FaultKind.RETRYABLE, RainErrorCodes.STATEMENT_TIMEOUT, cause = failure)
            SqlStates.IN_FAILED_SQL_TRANSACTION -> Fault(FaultKind.RETRYABLE, RainErrorCodes.TRANSACTION_ABORTED, cause = failure)
            else -> Fault(FaultKind.INTERNAL, cause = failure)
        }

    private fun invalid(
        violation: Violation,
        failure: Throwable,
    ): Fault = Fault(FaultKind.VALIDATION, code = violation.code, violations = listOf(violation), cause = failure)
}
