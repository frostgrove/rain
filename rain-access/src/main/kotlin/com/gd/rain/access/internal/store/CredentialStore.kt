package com.gd.rain.access.internal.store

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SubjectType
import com.gd.rain.access.jooq.Tables.CREDENTIALS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Select
import java.time.Instant
import java.util.UUID

/** Password credentials. Reads run on their own; every lock and write joins the caller's transaction. */
public interface CredentialStore {
    public fun findByIdentifier(
        type: SubjectType,
        identifier: String,
    ): StoredCredential?

    public fun findBySubject(subject: SubjectRef): StoredCredential?

    /** The credential row, locked `FOR UPDATE` until the caller's transaction ends. */
    public fun lockById(id: UUID): StoredCredential?

    public fun lockBySubject(subject: SubjectRef): StoredCredential?

    public fun insert(
        id: UUID,
        subject: SubjectRef,
        identifier: String,
        hash: String,
        now: Instant,
    ): CredentialInsert

    /** Writes a new hash when the row is still at [expectedVersion]; answers the rows changed. */
    public fun replaceSecret(
        id: UUID,
        expectedVersion: Long,
        hash: String,
        now: Instant,
    ): Int

    public fun replaceIdentifierAndSecret(
        id: UUID,
        expectedVersion: Long,
        identifier: String,
        hash: String,
        now: Instant,
    ): Int

    /** Which of [ids] (subjects of [type]) have a password credential; a read of `uq_credentials_subject_password` limited to the ids. */
    public fun withPassword(
        type: SubjectType,
        ids: Collection<UUID>,
    ): Set<UUID>
}

public class JooqCredentialStore(
    private val dsl: DSLContext,
) : CredentialStore {
    override fun findByIdentifier(
        type: SubjectType,
        identifier: String,
    ): StoredCredential? =
        dsl
            .selectFrom(CREDENTIALS)
            .where(CREDENTIALS.SUBJECT_TYPE.eq(type.name))
            .and(CREDENTIALS.PROVIDER.eq(PASSWORD))
            .and(CREDENTIALS.IDENTIFIER.eq(identifier))
            .fetchOne(::credentialOf)

    override fun findBySubject(subject: SubjectRef): StoredCredential? =
        dsl.selectFrom(CREDENTIALS).where(ofSubject(subject)).fetchOne(::credentialOf)

    override fun lockById(id: UUID): StoredCredential? =
        dsl
            .selectFrom(CREDENTIALS)
            .where(CREDENTIALS.ID.eq(id))
            .forUpdate()
            .fetchOne(::credentialOf)

    override fun lockBySubject(subject: SubjectRef): StoredCredential? =
        dsl
            .selectFrom(CREDENTIALS)
            .where(ofSubject(subject))
            .forUpdate()
            .fetchOne(::credentialOf)

    override fun insert(
        id: UUID,
        subject: SubjectRef,
        identifier: String,
        hash: String,
        now: Instant,
    ): CredentialInsert {
        // No conflict target: either unique index absorbs the insert, so the transaction is never aborted by a unique
        // violation, and which one absorbed it is read back rather than parsed out of an error.
        val inserted =
            dsl
                .insertInto(CREDENTIALS)
                .set(CREDENTIALS.ID, id)
                .set(CREDENTIALS.SUBJECT_TYPE, subject.type.name)
                .set(CREDENTIALS.SUBJECT_ID, subject.id)
                .set(CREDENTIALS.PROVIDER, PASSWORD)
                .set(CREDENTIALS.IDENTIFIER, identifier)
                .set(CREDENTIALS.SECRET_HASH, hash)
                .set(CREDENTIALS.CREATED_AT, now.utc())
                .set(CREDENTIALS.UPDATED_AT, now.utc())
                .set(CREDENTIALS.VERSION, 0L)
                .onConflictDoNothing()
                .execute()
        return when {
            inserted == 1 -> CredentialInsert.INSERTED
            findBySubject(subject) != null -> CredentialInsert.SUBJECT_ENROLLED
            else -> CredentialInsert.IDENTIFIER_TAKEN
        }
    }

    override fun replaceSecret(
        id: UUID,
        expectedVersion: Long,
        hash: String,
        now: Instant,
    ): Int =
        dsl
            .update(CREDENTIALS)
            .set(CREDENTIALS.SECRET_HASH, hash)
            .set(CREDENTIALS.UPDATED_AT, now.utc())
            .set(CREDENTIALS.VERSION, CREDENTIALS.VERSION.plus(1L))
            .where(CREDENTIALS.ID.eq(id))
            .and(CREDENTIALS.VERSION.eq(expectedVersion))
            .execute()

    override fun replaceIdentifierAndSecret(
        id: UUID,
        expectedVersion: Long,
        identifier: String,
        hash: String,
        now: Instant,
    ): Int =
        dsl
            .update(CREDENTIALS)
            .set(CREDENTIALS.IDENTIFIER, identifier)
            .set(CREDENTIALS.SECRET_HASH, hash)
            .set(CREDENTIALS.UPDATED_AT, now.utc())
            .set(CREDENTIALS.VERSION, CREDENTIALS.VERSION.plus(1L))
            .where(CREDENTIALS.ID.eq(id))
            .and(CREDENTIALS.VERSION.eq(expectedVersion))
            .execute()

    override fun withPassword(
        type: SubjectType,
        ids: Collection<UUID>,
    ): Set<UUID> {
        if (ids.isEmpty()) return emptySet()
        return dsl.fetch(withPasswordQuery(type, ids)).map { it[CREDENTIALS.SUBJECT_ID] }.toSet()
    }

    /**
     * The statement [withPassword] runs. A subject has at most one password credential (`uq_credentials_subject_password`),
     * so no more rows than distinct ids can match: the `LIMIT` of that many changes no answer and bounds the read.
     */
    public fun withPasswordQuery(
        type: SubjectType,
        ids: Collection<UUID>,
    ): Select<*> {
        val distinct = ids.toSet()
        require(distinct.isNotEmpty()) { "a question about passwords names at least one subject" }
        return dsl
            .select(CREDENTIALS.SUBJECT_ID)
            .from(CREDENTIALS)
            .where(CREDENTIALS.SUBJECT_TYPE.eq(type.name))
            .and(CREDENTIALS.SUBJECT_ID.`in`(distinct))
            .and(CREDENTIALS.PROVIDER.eq(PASSWORD))
            .limit(distinct.size)
    }

    private fun ofSubject(subject: SubjectRef): Condition =
        CREDENTIALS.SUBJECT_TYPE
            .eq(subject.type.name)
            .and(CREDENTIALS.SUBJECT_ID.eq(subject.id))
            .and(CREDENTIALS.PROVIDER.eq(PASSWORD))

    private fun credentialOf(record: Record): StoredCredential =
        StoredCredential(
            id = record[CREDENTIALS.ID],
            subject = SubjectRef(SubjectType(record[CREDENTIALS.SUBJECT_TYPE]), record[CREDENTIALS.SUBJECT_ID]),
            identifier = record[CREDENTIALS.IDENTIFIER],
            secretHash = record[CREDENTIALS.SECRET_HASH],
            version = record[CREDENTIALS.VERSION],
        )

    public companion object {
        public const val PASSWORD: String = "password"
    }
}
