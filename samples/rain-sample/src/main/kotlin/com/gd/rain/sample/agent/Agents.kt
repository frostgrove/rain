package com.gd.rain.sample.agent

import com.gd.rain.access.IdentifierNormalization
import com.gd.rain.access.Profile
import com.gd.rain.access.SubjectDirectory
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectType
import com.gd.rain.core.id.IdGenerator
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.repository.CrudRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID
import org.springframework.data.relational.core.mapping.Table as RelationalTable

/** The one kind of subject this helpdesk serves. */
object Agents {
    val TYPE: SubjectType = SubjectType("agent")

    /** An agent's identifier is an address, stored and compared without surrounding space and in lower case. */
    val NORMALIZATION: IdentifierNormalization = IdentifierNormalization { presented -> presented.trim().lowercase(Locale.ROOT) }

    fun ref(id: UUID): SubjectRef = SubjectRef(TYPE, id)
}

internal object AgentsTable {
    val TABLE: Table<Record> = DSL.table(DSL.name("public", "agents"))
    val ID: Field<UUID> = DSL.field(DSL.name("id"), SQLDataType.UUID)
    val IDENTIFIER: Field<String> = DSL.field(DSL.name("identifier"), SQLDataType.CLOB)
    val ACTIVE: Field<Boolean> = DSL.field(DSL.name("active"), SQLDataType.BOOLEAN)
    val CREATED_AT: Field<Instant> = DSL.field(DSL.name("created_at"), SQLDataType.INSTANT)
    val LAST_SIGNED_IN_AT: Field<Instant> = DSL.field(DSL.name("last_signed_in_at"), SQLDataType.INSTANT)
}

/** How an agent is shown. A Spring Data JDBC aggregate: the application's own repositories register next to rain. */
@RelationalTable("agent_profiles")
data class AgentProfile(
    @Id val agentId: UUID,
    val displayName: String,
    @Version val version: Long? = null,
)

interface AgentProfileRepository : CrudRepository<AgentProfile, UUID>

data class AgentRecord(
    val id: UUID,
    val identifier: String,
    val active: Boolean,
)

/** The agents of this helpdesk: a row in `agents` and its profile, always written together. */
@Component
class AgentRegistry(
    private val dsl: DSLContext,
    private val profiles: AgentProfileRepository,
    private val ids: IdGenerator,
    private val clock: Clock,
    transactions: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactions)

    /**
     * The agent with [identifier], created active together with its profile when there is none. An agent that exists
     * is never changed, so running this again — the seed command does, on every deployment — changes nothing.
     */
    fun ensure(
        identifier: String,
        displayName: String,
    ): UUID {
        val normalized = Agents.NORMALIZATION.normalize(identifier)
        require(normalized.isNotEmpty()) { "an agent's identifier is not blank" }
        require(displayName.isNotBlank()) { "an agent's display name is not blank" }
        return checkNotNull(
            transaction.execute {
                dsl
                    .insertInto(AgentsTable.TABLE)
                    .set(AgentsTable.ID, ids.next())
                    .set(AgentsTable.IDENTIFIER, normalized)
                    .set(AgentsTable.ACTIVE, true)
                    .set(AgentsTable.CREATED_AT, clock.instant())
                    .onConflict(AgentsTable.IDENTIFIER)
                    .doNothing()
                    .execute()
                val id =
                    checkNotNull(
                        dsl
                            .select(
                                AgentsTable.ID,
                            ).from(AgentsTable.TABLE)
                            .where(AgentsTable.IDENTIFIER.eq(normalized))
                            .fetchOne(AgentsTable.ID),
                    ) {
                        "agent $normalized was written in this transaction and reads as absent"
                    }
                if (!profiles.existsById(id)) profiles.save(AgentProfile(id, displayName))
                id
            },
        ) { "ensuring agent $normalized answered nothing" }
    }

    fun find(id: UUID): AgentRecord? =
        dsl
            .select(AgentsTable.ID, AgentsTable.IDENTIFIER, AgentsTable.ACTIVE)
            .from(AgentsTable.TABLE)
            .where(AgentsTable.ID.eq(id))
            .fetchOne()
            ?.let { AgentRecord(it.value1(), it.value2(), it.value3()) }

    /** Deactivating keeps the row and every reference to it; the agent stops authenticating at its next request. */
    fun setActive(
        id: UUID,
        active: Boolean,
    ): Boolean =
        dsl
            .update(AgentsTable.TABLE)
            .set(AgentsTable.ACTIVE, active)
            .where(AgentsTable.ID.eq(id))
            .execute() == 1
}

/** What rain-access asks about an agent. Each answer is one lookup by primary key, inside rain-access's transaction when it has one. */
@Component
class AgentDirectory(
    private val dsl: DSLContext,
    private val profiles: AgentProfileRepository,
) : SubjectDirectory {
    override val subjectType: SubjectType = Agents.TYPE

    override fun isActive(id: UUID): Boolean =
        dsl
            .select(AgentsTable.ACTIVE)
            .from(AgentsTable.TABLE)
            .where(AgentsTable.ID.eq(id))
            .fetchOne(AgentsTable.ACTIVE) == true

    override fun describe(id: UUID): Profile? {
        val identifier =
            dsl
                .select(AgentsTable.IDENTIFIER)
                .from(AgentsTable.TABLE)
                .where(AgentsTable.ID.eq(id))
                .fetchOne(AgentsTable.IDENTIFIER)
                ?: return null
        val profile =
            profiles.findById(id).orElseThrow {
                IllegalStateException("agent $id has no profile; an agent and its profile are written in one transaction")
            }
        return Profile(displayName = profile.displayName, identifier = identifier)
    }

    override fun signedIn(
        id: UUID,
        at: Instant,
    ) {
        dsl
            .update(AgentsTable.TABLE)
            .set(AgentsTable.LAST_SIGNED_IN_AT, at)
            .where(AgentsTable.ID.eq(id))
            .execute()
    }
}
