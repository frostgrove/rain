package com.gd.rain.persistence.fault

import com.gd.rain.core.error.ErrorCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.FaultTranslator
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.persistence.sql.SqlStates
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DuplicateKeyException
import org.springframework.dao.OptimisticLockingFailureException
import java.sql.SQLException

/**
 * Database failures, classified by SQLState from a declared table. A state not in the table is an
 * internal failure, never a guess at what it might mean.
 */
public object DataAccessFaultTranslator : FaultTranslator {
    override fun translate(failure: Throwable): Fault? = translate(failure, emptyList())

    public fun translate(
        failure: Throwable,
        mappers: List<DatabaseViolationMapper>,
    ): Fault? {
        if (failure !is DataAccessException && generateSequence(failure, Throwable::cause).none { it is SQLException }) return null
        val state = SqlStates.of(failure)
        return when {
            state != null -> forState(state, failure, mappers)
            failure is OptimisticLockingFailureException -> Fault(FaultKind.CONFLICT, RainErrorCodes.STALE_VERSION, cause = failure)
            failure is DuplicateKeyException -> Fault(FaultKind.CONFLICT, RainErrorCodes.UNIQUE, cause = failure)
            else -> Fault(FaultKind.INTERNAL, cause = failure)
        }
    }

    private fun forState(
        state: String,
        failure: Throwable,
        mappers: List<DatabaseViolationMapper>,
    ): Fault {
        val classification = classificationOf(state) ?: return Fault(FaultKind.INTERNAL, cause = failure)
        val mapped =
            if (classification.mappable) {
                mapped(
                    DatabaseError(state, classification.kind, classification.code, postgresSource(failure, state)),
                    mappers,
                )
            } else {
                null
            }
        val violation = mapped ?: classification.defaultViolation?.let { Violation.general(it) }
        return Fault(
            classification.kind,
            code = mapped?.code ?: classification.code,
            violations = listOfNotNull(violation),
            cause = failure,
        )
    }

    private fun mapped(
        error: DatabaseError,
        mappers: List<DatabaseViolationMapper>,
    ): Violation? {
        mappers.forEach { mapper ->
            try {
                mapper.map(error)?.let { return it }
            } catch (failure: RuntimeException) {
                log
                    .atWarn()
                    .setMessage("a database violation mapper failed; the next mapper or safe general refusal is used")
                    .addKeyValue("mapper", mapper.javaClass.name)
                    .addKeyValue("sqlstate", error.sqlState)
                    .setCause(failure)
                    .log()
            }
        }
        return null
    }

    private fun postgresSource(
        failure: Throwable,
        state: String,
    ): DatabaseErrorSource? {
        val pending = ArrayDeque<Throwable>().apply { add(failure) }
        val seen = HashSet<Throwable>()
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is PSQLException && current.sqlState == state) {
                val message = current.serverErrorMessage
                if (message != null) {
                    val schema: String? = message.schema?.takeUnless(String::isBlank)
                    val table: String? = message.table?.takeUnless(String::isBlank)
                    val column: String? = message.column?.takeUnless(String::isBlank)
                    val constraint: String? = message.constraint?.takeUnless(String::isBlank)
                    if (listOf(schema, table, column, constraint).any { !it.isNullOrBlank() }) {
                        return DatabaseErrorSource(schema, table, column, constraint)
                    }
                }
            }
            if (current is SQLException) current.nextException?.let(pending::add)
            current.cause?.let(pending::add)
        }
        return null
    }

    private fun classificationOf(state: String): Classification? = classifications[state]

    private data class Classification(
        val kind: FaultKind,
        val code: ErrorCode,
        val defaultViolation: ErrorCode? = null,
        val mappable: Boolean = false,
    )

    private val classifications: Map<String, Classification> =
        mapOf(
            SqlStates.UNIQUE_VIOLATION to Classification(FaultKind.CONFLICT, RainErrorCodes.UNIQUE, mappable = true),
            SqlStates.FOREIGN_KEY_VIOLATION to Classification(FaultKind.CONFLICT, RainErrorCodes.FOREIGN_KEY, mappable = true),
            SqlStates.RESTRICT_VIOLATION to Classification(FaultKind.CONFLICT, RainErrorCodes.RESTRICT, mappable = true),
            SqlStates.EXCLUSION_VIOLATION to Classification(FaultKind.CONFLICT, RainErrorCodes.EXCLUSION, mappable = true),
            SqlStates.NOT_NULL_VIOLATION to
                Classification(FaultKind.VALIDATION, RainErrorCodes.REQUIRED, RainErrorCodes.REQUIRED, mappable = true),
            SqlStates.CHECK_VIOLATION to
                Classification(FaultKind.VALIDATION, RainErrorCodes.CHECK, RainErrorCodes.CHECK, mappable = true),
            SqlStates.STRING_DATA_RIGHT_TRUNCATION to
                Classification(FaultKind.VALIDATION, RainErrorCodes.TOO_LONG, RainErrorCodes.TOO_LONG, mappable = true),
            SqlStates.NUMERIC_VALUE_OUT_OF_RANGE to
                Classification(FaultKind.VALIDATION, RainErrorCodes.OUT_OF_RANGE, RainErrorCodes.OUT_OF_RANGE, mappable = true),
            SqlStates.INVALID_TEXT_REPRESENTATION to
                Classification(FaultKind.BAD_REQUEST, RainErrorCodes.INVALID_FORMAT, mappable = true),
            SqlStates.DEADLOCK_DETECTED to Classification(FaultKind.RETRYABLE, RainErrorCodes.DEADLOCK),
            SqlStates.SERIALIZATION_FAILURE to Classification(FaultKind.RETRYABLE, RainErrorCodes.SERIALIZATION_FAILURE),
            SqlStates.LOCK_NOT_AVAILABLE to Classification(FaultKind.RETRYABLE, RainErrorCodes.LOCK_TIMEOUT),
            SqlStates.QUERY_CANCELED to Classification(FaultKind.RETRYABLE, RainErrorCodes.STATEMENT_TIMEOUT),
            SqlStates.IN_FAILED_SQL_TRANSACTION to Classification(FaultKind.RETRYABLE, RainErrorCodes.TRANSACTION_ABORTED),
        )

    private val log = LoggerFactory.getLogger(DataAccessFaultTranslator::class.java)
}
