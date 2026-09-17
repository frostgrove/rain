package com.gd.rain.audit

import com.gd.rain.audit.jooq.Tables.AUDIT_LOG
import com.gd.rain.audit.scope.AuditScope
import com.gd.rain.audit.scope.AuditScopeContributor
import com.gd.rain.boot.config.ConfigurationCheck
import com.gd.rain.core.actor.Actor
import com.gd.rain.core.actor.CurrentActor
import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.id.IdGenerator
import com.gd.rain.core.log.Correlation
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.MDC
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Where a keyset page of the trail continues. */
public data class AuditCursor(
    public val occurredAt: Instant,
    public val id: UUID,
)

public data class AuditEntry(
    public val id: UUID,
    public val occurredAt: Instant,
    public val actor: Actor?,
    public val requestId: String?,
    public val module: String,
    public val action: String,
    public val resourceKind: String,
    public val resourceId: String?,
    public val outcome: AuditOutcome,
    public val detailJson: String,
    /** Null for legacy and central evidence. */
    public val scope: AuditScope? = null,
)

public data class AuditPage(
    public val entries: List<AuditEntry>,
    public val next: AuditCursor?,
)

public interface AuditRecorder {
    /** Records inside the caller's transaction, so the evidence commits or rolls back with the change. Refused outside a transaction. */
    public fun record(event: AuditEvent)

    /** Records in a transaction of its own — for an outcome the caller's transaction will not commit, such as a refusal. */
    public fun recordIndependently(event: AuditEvent)

    /** Newest first; [limit] is 1..[MAX_PAGE]. */
    public fun ofResource(
        resourceKind: String,
        resourceId: String?,
        after: AuditCursor?,
        limit: Int,
    ): AuditPage

    public fun ofActor(
        actor: Actor,
        after: AuditCursor?,
        limit: Int,
    ): AuditPage

    public companion object {
        public const val MAX_PAGE: Int = 500
    }
}

public class JooqAuditRecorder(
    private val dsl: DSLContext,
    transactions: PlatformTransactionManager,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val currentActor: CurrentActor?,
    types: List<AuditEventType>,
    private val scopeContributors: List<AuditScopeContributor> = emptyList(),
) : AuditRecorder {
    private val declared: Set<String> = types.map { it.id }.toSet()

    private val independent =
        TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    override fun record(event: AuditEvent) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "audit event ${event.type.id} is recorded inside the transaction that makes the change; there is none"
        }
        insert(event)
    }

    override fun recordIndependently(event: AuditEvent) {
        independent.executeWithoutResult { insert(event) }
    }

    override fun ofResource(
        resourceKind: String,
        resourceId: String?,
        after: AuditCursor?,
        limit: Int,
    ): AuditPage {
        val sameResource = if (resourceId == null) AUDIT_LOG.RESOURCE_ID.isNull else AUDIT_LOG.RESOURCE_ID.eq(resourceId)
        return page(currentScopeCondition().and(AUDIT_LOG.RESOURCE_KIND.eq(resourceKind)).and(sameResource), after, limit)
    }

    override fun ofActor(
        actor: Actor,
        after: AuditCursor?,
        limit: Int,
    ): AuditPage = page(currentScopeCondition().and(AUDIT_LOG.ACTOR_TYPE.eq(actor.type)).and(AUDIT_LOG.ACTOR_ID.eq(actor.id)), after, limit)

    private fun insert(event: AuditEvent) {
        require(event.type.id in declared) { "audit event type ${event.type.id} is not declared as a bean" }
        val actor = event.actor ?: currentActor?.actor()
        val scope = currentScope()
        dsl
            .insertInto(AUDIT_LOG)
            .set(AUDIT_LOG.ID, ids.next())
            .set(AUDIT_LOG.OCCURRED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(AUDIT_LOG.ACTOR_TYPE, actor?.type)
            .set(AUDIT_LOG.ACTOR_ID, actor?.id)
            .set(AUDIT_LOG.REQUEST_ID, MDC.get(Correlation.MDC_REQUEST_ID))
            .set(AUDIT_LOG.MODULE, event.type.module)
            .set(AUDIT_LOG.ACTION, event.type.action)
            .set(AUDIT_LOG.RESOURCE_KIND, event.type.resourceKind)
            .set(AUDIT_LOG.RESOURCE_ID, event.resourceId)
            .set(AUDIT_LOG.OUTCOME, event.outcome.wire)
            .set(AUDIT_LOG.DETAIL, JSONB.valueOf(event.detail.json()))
            .set(AUDIT_LOG.SCOPE_KIND, scope?.kind)
            .set(AUDIT_LOG.SCOPE_DIGEST, scope?.digest())
            .set(AUDIT_LOG.SCOPE_EPOCH, scope?.epoch)
            .execute()
    }

    /** One statement over the covering index: the scope, the seek past [after], newest first, one row beyond the page. */
    public fun pageQuery(
        scope: Condition,
        after: AuditCursor?,
        limit: Int,
    ): org.jooq.Select<*> {
        require(limit in 1..AuditRecorder.MAX_PAGE) { "an audit page holds 1..${AuditRecorder.MAX_PAGE} entries, got $limit" }
        val seek =
            after?.let { DSL.row(AUDIT_LOG.OCCURRED_AT, AUDIT_LOG.ID).lessThan(it.occurredAt.atOffset(ZoneOffset.UTC), it.id) }
                ?: DSL.noCondition()
        return dsl
            .selectFrom(AUDIT_LOG)
            .where(scope)
            .and(seek)
            .orderBy(AUDIT_LOG.OCCURRED_AT.desc(), AUDIT_LOG.ID.desc())
            .limit(limit + 1)
    }

    private fun page(
        scope: Condition,
        after: AuditCursor?,
        limit: Int,
    ): AuditPage {
        val rows = dsl.fetch(pageQuery(scope, after, limit)).into(AUDIT_LOG)
        val entries =
            rows.take(limit).map { row ->
                AuditEntry(
                    id = row.id,
                    occurredAt = row.occurredAt.toInstant(),
                    actor = row.actorType?.let { Actor(it, requireNotNull(row.actorId)) },
                    requestId = row.requestId,
                    module = row.module,
                    action = row.action,
                    resourceKind = row.resourceKind,
                    resourceId = row.resourceId,
                    outcome = AuditOutcome.entries.single { it.wire == row.outcome },
                    detailJson = row.detail.data(),
                    scope =
                        row.scopeKind?.let {
                            AuditScope.of(it, requireNotNull(row.scopeDigest), requireNotNull(row.scopeEpoch))
                        },
                )
            }
        val next = if (rows.size > limit) entries.last().let { AuditCursor(it.occurredAt, it.id) } else null
        return AuditPage(entries, next)
    }

    private fun currentScope(): AuditScope? {
        val scopes = scopeContributors.mapNotNull(AuditScopeContributor::current).distinct()
        require(scopes.size <= 1) { "more than one audit scope is active" }
        return scopes.singleOrNull()
    }

    private fun currentScopeCondition(): Condition =
        currentScope()?.let { scope ->
            AUDIT_LOG.SCOPE_KIND
                .eq(scope.kind)
                .and(AUDIT_LOG.SCOPE_DIGEST.eq(scope.digest()))
                .and(AUDIT_LOG.SCOPE_EPOCH.eq(scope.epoch))
        } ?: AUDIT_LOG.SCOPE_KIND.isNull
}

/** Audit event types are declared once each. */
public class AuditEventTypesCheck(
    private val types: List<AuditEventType>,
) : ConfigurationCheck {
    override fun problems(): List<ConfigurationProblem> =
        types.groupBy { it.id }.filterValues { it.size > 1 }.keys.sorted().map {
            ConfigurationProblem("audit:$it", ProblemCode.CONTRADICTS, "audit event type $it is declared more than once")
        }
}
