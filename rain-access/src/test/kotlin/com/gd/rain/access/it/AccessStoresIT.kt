package com.gd.rain.access.it

import com.gd.rain.access.ModuleGrants
import com.gd.rain.access.PermissionDef
import com.gd.rain.access.SubjectRef
import com.gd.rain.access.SystemRoleDeclaration
import com.gd.rain.access.internal.store.CredentialInsert
import com.gd.rain.access.internal.store.SubjectCutoff
import com.gd.rain.access.internal.token.RefreshCredential
import com.gd.rain.access.internal.usecase.AccessTransactions
import com.gd.rain.access.internal.usecase.CatalogueSynchronizer
import com.gd.rain.access.internal.usecase.RevocationReasons
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.AccessDatabase
import com.gd.rain.access.support.SERVICE
import com.gd.rain.access.support.START
import com.gd.rain.access.support.openSession
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The password credential store on PostgreSQL: one credential per subject, one subject per identifier, version-guarded writes. */
@Tag("integration")
class CredentialStoreIT {
    private val db = AccessDatabase.fresh("access_credentials")
    private val ada = SubjectRef(AGENT, UUID.randomUUID())

    private fun insert(
        subject: SubjectRef,
        identifier: String,
    ): CredentialInsert = db.credentials.insert(db.ids.next(), subject, identifier, "fake:secret", START)

    @Test
    fun `a subject enrols once, an identifier belongs to one subject of a type, and an insert overwrites nothing`() {
        val bob = SubjectRef(AGENT, UUID.randomUUID())
        val service = SubjectRef(SERVICE, UUID.randomUUID())

        assertThat(insert(ada, "ada@example.test")).isEqualTo(CredentialInsert.INSERTED)
        assertThat(insert(ada, "ada.other@example.test")).isEqualTo(CredentialInsert.SUBJECT_ENROLLED)
        assertThat(insert(bob, "ada@example.test")).isEqualTo(CredentialInsert.IDENTIFIER_TAKEN)
        assertThat(insert(service, "ada@example.test")).isEqualTo(CredentialInsert.INSERTED)

        assertThat(db.count("SELECT count(*) FROM rain_access.credentials")).isEqualTo(2)
        assertThat(db.credentials.findByIdentifier(AGENT, "ada@example.test")?.subject).isEqualTo(ada)
        assertThat(db.credentials.findBySubject(bob)).isNull()
    }

    @Test
    fun `a secret is replaced only at the version it was read at`() {
        insert(ada, "ada@example.test")
        val read = requireNotNull(db.credentials.findBySubject(ada))

        assertThat(db.credentials.replaceSecret(read.id, read.version, "fake:next", START)).isEqualTo(1)
        assertThat(db.credentials.replaceSecret(read.id, read.version, "fake:stale", START)).isZero()
        assertThat(
            db.credentials.replaceIdentifierAndSecret(read.id, read.version + 1, "ada@new.example", "fake:moved", START),
        ).isEqualTo(1)

        val now = requireNotNull(db.credentials.findBySubject(ada))
        assertThat(now.version).isEqualTo(read.version + 2)
        assertThat(now.identifier to now.secretHash).isEqualTo("ada@new.example" to "fake:moved")
        assertThat(db.credentials.findByIdentifier(AGENT, "ada@example.test")).isNull()
    }

    @Test
    fun `which subjects have a password is answered for exactly the ids asked`() {
        insert(ada, "ada@example.test")

        assertThat(db.credentials.withPassword(AGENT, listOf(ada.id, UUID.randomUUID()))).containsExactly(ada.id)
        assertThat(db.credentials.withPassword(SERVICE, listOf(ada.id))).isEmpty()
    }

    @Test
    fun `a credential locked by one transaction waits for it`() {
        insert(ada, "ada@example.test")
        val id = requireNotNull(db.credentials.findBySubject(ada)).id
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val holder =
                executor.submit(
                    Callable {
                        db.inTransaction {
                            db.credentials.lockById(id)
                            locked.countDown()
                            check(release.await(30, TimeUnit.SECONDS)) { "never released" }
                        }
                    },
                )
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue()

            assertThatThrownBy {
                db.inTransaction {
                    db.jdbc.execute("SET LOCAL lock_timeout = '200ms'")
                    db.credentials.lockById(id)
                }
            }.hasMessageContaining("lock timeout")

            release.countDown()
            holder.get(30, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}

/** The session store on PostgreSQL: the rotation's compare-and-set, closing, bounded revoke-all batches and cutoffs. */
@Tag("integration")
class SessionStoreIT {
    private val db = AccessDatabase.fresh("access_sessions")
    private val subject = SubjectRef(AGENT, UUID.randomUUID())

    @Test
    fun `a swap moves one generation while the session is at the generation and digest read, and never once it is closed`() {
        val id = db.openSession(subject, START)
        val first = requireNotNull(db.sessions.findById(id))
        val next = RefreshCredential.digest("2.$id.next")

        assertThat(db.sessions.swap(id, first.generation, first.tokenHash, next, START.plusSeconds(5))).isEqualTo(1)
        assertThat(db.sessions.swap(id, first.generation, first.tokenHash, "lost", START.plusSeconds(6))).isZero()
        assertThat(db.sessions.swap(id, first.generation + 1, first.tokenHash, "lost", START.plusSeconds(6))).isZero()

        val swapped = requireNotNull(db.sessions.findByTokenHash(next))
        assertThat(swapped.generation).isEqualTo(first.generation + 1)
        assertThat(swapped.previousTokenHash).isEqualTo(first.tokenHash)
        assertThat(swapped.rotatedAt).isEqualTo(START.plusSeconds(5))
        assertThat(db.sessions.findByPreviousTokenHash(first.tokenHash)?.id).isEqualTo(id)

        assertThat(db.sessions.close(id, START.plusSeconds(10), RevocationReasons.SIGNED_OUT)).isEqualTo(1)
        assertThat(db.sessions.close(id, START.plusSeconds(11), RevocationReasons.SIGNED_OUT)).isZero()
        assertThat(db.sessions.swap(id, swapped.generation, next, "later", START.plusSeconds(12))).isZero()
        assertThat(requireNotNull(db.sessions.findById(id)).revokedReason).isEqualTo(RevocationReasons.SIGNED_OUT)
    }

    @Test
    fun `closing one session answers when it was closed, and only to its own subject`() {
        val id = db.openSession(subject, START)
        val stranger = SubjectRef(AGENT, UUID.randomUUID())

        assertThat(db.sessions.revokeOne(stranger, id, START, RevocationReasons.CLOSED_BY_SUBJECT)).isNull()
        assertThat(
            db.sessions.revokeOne(subject, id, START.plusSeconds(1), RevocationReasons.CLOSED_BY_SUBJECT),
        ).isEqualTo(START.plusSeconds(1))
        assertThat(
            db.sessions.revokeOne(subject, id, START.plusSeconds(9), RevocationReasons.CLOSED_BY_SUBJECT),
        ).isEqualTo(START.plusSeconds(1))
    }

    @Test
    fun `closing everywhere takes bounded batches of what was issued up to the cutoff and keeps the named session`() {
        val issued = List(5) { db.openSession(subject, START.minusSeconds(10L - it)) }
        val kept = issued[2]
        val later = db.openSession(subject, START.plusSeconds(1))
        val stranger = db.openSession(SubjectRef(AGENT, UUID.randomUUID()), START.minusSeconds(5))

        val batches =
            generateSequence { db.sessions.revokeBatch(subject, START, kept, START, RevocationReasons.SIGNED_OUT_EVERYWHERE, 2) }
                .takeWhile { it > 0 }
                .toList()

        assertThat(batches).containsExactly(2, 2)
        issued.filter { it != kept }.forEach { assertThat(requireNotNull(db.sessions.findById(it)).revokedAt).isEqualTo(START) }
        listOf(kept, later, stranger).forEach { assertThat(requireNotNull(db.sessions.findById(it)).revokedAt).isNull() }
    }

    @Test
    fun `a subject's cutoff never moves back`() {
        val cutoff = SubjectCutoff(subject, START, UUID.randomUUID())
        val later = SubjectCutoff(subject, START.plusSeconds(60), null)

        db.sessions.upsertCutoff(cutoff)
        db.sessions.upsertCutoff(SubjectCutoff(subject, START.minusSeconds(60), null))
        assertThat(db.sessions.cutoffOf(subject)).isEqualTo(cutoff)

        db.sessions.upsertCutoff(later)
        assertThat(db.sessions.cutoffOf(subject)).isEqualTo(later)
    }
}

/** The grants aggregate on PostgreSQL: the declared catalogue, roles, grants and the one question asked of them. */
@Tag("integration")
class AccessAggregateRoundTripIT {
    private val db = AccessDatabase.fresh("access_round_trip")
    private val subject = SubjectRef(AGENT, UUID.randomUUID())
    private val helpdesk =
        ModuleGrants(
            "helpdesk",
            listOf(PermissionDef("ticket.read", "Read tickets"), PermissionDef("ticket.close", "Close tickets")),
            mapOf("triage" to setOf("ticket.read")),
        )
    private val systemRoles =
        listOf(SystemRoleDeclaration("administrator", "Administrator", true), SystemRoleDeclaration("triage", "Triage", false))

    private fun synchronizer() =
        CatalogueSynchronizer(db.catalogue, AccessTransactions(db.transactions), listOf(helpdesk), systemRoles, db.ids, 1, db.clock)

    @Test
    fun `the declared catalogue is written once however often and however concurrently it is synchronised`() {
        val barrier = CyclicBarrier(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            List(2) {
                executor.submit(
                    Callable {
                        barrier.await(30, TimeUnit.SECONDS)
                        synchronizer().synchronize()
                    },
                )
            }.forEach { it.get(60, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
        synchronizer().synchronize()

        assertThat(db.count("SELECT count(*) FROM rain_access.permissions")).isEqualTo(2)
        assertThat(db.count("SELECT count(*) FROM rain_access.roles")).isEqualTo(2)
        assertThat(db.count("SELECT count(*) FROM rain_access.role_permissions")).isEqualTo(1)
        val triage = requireNotNull(db.grants.roleBySlug("triage"))
        assertThat(triage.isSystem).isTrue()
        assertThat(triage.grantsEveryPermission).isFalse()
        assertThat(db.grants.permissionByCode("ticket.close")?.module).isEqualTo("helpdesk")
    }

    @Test
    fun `a permission is held directly, through a role, through a role granting everything, and no longer once revoked`() {
        synchronizer().synchronize()
        val leadId = db.ids.next()
        assertThat(db.grants.createRole(leadId, "support-lead", "Support lead", START)).isTrue()
        val close = requireNotNull(db.grants.permissionByCode("ticket.close"))
        val read = requireNotNull(db.grants.permissionByCode("ticket.read"))
        val asked = setOf("ticket.read", "ticket.close", "not.declared")

        assertThat(db.grants.heldCodes(subject, asked)).isEmpty()
        db.grants.attach(leadId, close.id, START)
        db.grants.grantRole(subject, leadId, START)
        assertThat(db.grants.heldCodes(subject, asked)).containsExactly("ticket.close")
        db.grants.grantPermission(subject, read.id, START)
        assertThat(db.grants.heldCodes(subject, asked)).containsExactlyInAnyOrder("ticket.read", "ticket.close")
        assertThat(db.grants.revokeRole(subject, leadId)).isEqualTo(1)
        assertThat(db.grants.heldCodes(subject, asked)).containsExactly("ticket.read")
        db.grants.grantRole(subject, requireNotNull(db.grants.roleBySlug("administrator")).id, START)
        assertThat(db.grants.heldCodes(subject, asked)).containsExactlyInAnyOrder("ticket.read", "ticket.close")
        assertThat(db.grants.heldCodes(SubjectRef(AGENT, UUID.randomUUID()), asked)).isEmpty()
    }

    @Test
    fun `a system role is neither renamed nor deleted, roles are counted up to the bound, and a default role is rebound`() {
        synchronizer().synchronize()
        val administrator = requireNotNull(db.grants.roleBySlug("administrator"))
        assertThat(db.grants.renameRole(administrator.id, "Root")).isZero()
        assertThat(db.grants.deleteRole(administrator.id)).isZero()

        listOf("a-role", "b-role", "c-role").forEach { slug ->
            val id = db.ids.next()
            db.grants.createRole(id, slug, "Role $slug", START)
            db.grants.grantRole(subject, id, START)
        }
        assertThat(db.grants.createRole(db.ids.next(), "a-role", "Again", START)).isFalse()
        assertThat(db.grants.rolesHeldUpTo(subject, 2)).isEqualTo(2)
        assertThat(db.grants.rolesHeldUpTo(subject, 10)).isEqualTo(3)
        assertThat(db.grants.holdsRole(subject, requireNotNull(db.grants.roleBySlug("b-role")).id)).isTrue()

        db.grants.bindDefaultRole(AGENT, requireNotNull(db.grants.roleBySlug("a-role")).id, START)
        db.grants.bindDefaultRole(AGENT, requireNotNull(db.grants.roleBySlug("c-role")).id, START)
        assertThat(db.grants.defaultRoleOf(AGENT)?.slug).isEqualTo("c-role")
        assertThat(db.grants.defaultRoleOf(SERVICE)).isNull()
    }
}
