package com.gd.rain.event.projection.postgres

import com.gd.rain.event.projection.ProjectionDestinationContext
import com.gd.rain.event.projection.SameUnitProjectionDestinationSupport
import com.gd.rain.event.projection.SameUnitProjectionSavepoint
import com.gd.rain.persistence.tx.TransactionPlacement
import org.jooq.DSLContext
import org.springframework.transaction.PlatformTransactionManager

/** PostgreSQL destination proof for a caller-owned Spring transaction; it never begins or retries one. */
public class PostgresSameUnitProjectionDestination(
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
) : SameUnitProjectionDestinationSupport(),
    SameUnitProjectionSavepoint {
    override fun inspectPlacement(): TransactionPlacement = TransactionPlacement.inspect(dsl, transactionManager)

    override fun <T> inCallerTransaction(block: (ProjectionDestinationContext) -> T): T {
        val placement = inspectPlacement()
        return block(context(placement))
    }

    override fun <T> inHandlerSavepoint(block: () -> T): T =
        dsl.connectionResult { connection ->
            val savepoint = connection.setSavepoint()
            try {
                block()
            } catch (failure: Throwable) {
                connection.rollback(savepoint)
                throw failure
            } finally {
                connection.releaseSavepoint(savepoint)
            }
        }
}
