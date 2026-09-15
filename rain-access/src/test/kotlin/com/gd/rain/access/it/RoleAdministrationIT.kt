package com.gd.rain.access.it

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.internal.store.DeclaredPermission
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.AccessDatabase
import com.gd.rain.access.support.DatabaseKit
import com.gd.rain.access.support.START
import com.gd.rain.core.error.Fault
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID

/** Role administration over PostgreSQL (the service half of the old directory store test): bounded drains, per-role evidence. */
@Tag("integration")
class RoleAdministrationIT {
    private val db = AccessDatabase.fresh("access_role_administration")
    private val kit = DatabaseKit(db)

    @Test
    fun `deleting several roles drains their holders and permissions in batches and audits each role once`() {
        db.catalogue.declarePermissions(listOf(DeclaredPermission(db.ids.next(), "ticket.read", "Read tickets", "helpdesk")), START)
        val lead = kit.roles.create("support-lead", "Support lead")
        val night = kit.roles.create("night-shift", "Night shift")
        kit.roles.attach(lead.id, "ticket.read")
        repeat(5) { db.grants.grantRole(SubjectRef(AGENT, UUID.randomUUID()), lead.id, START) }
        repeat(3) { db.grants.grantRole(SubjectRef(AGENT, UUID.randomUUID()), night.id, START) }

        kit.roles.deleteAll(listOf(lead.id, night.id))

        assertThat(db.grants.roleById(lead.id)).isNull()
        assertThat(db.grants.roleById(night.id)).isNull()
        assertThat(db.count("SELECT count(*) FROM rain_access.subject_roles")).isZero()
        assertThat(db.count("SELECT count(*) FROM rain_access.role_permissions")).isZero()
        val deleted =
            db.jdbc.queryForList(
                "SELECT resource_id FROM rain_audit.audit_log WHERE module = ? AND action = ? AND detail ->> 'change' = 'deleted'",
                String::class.java,
                AccessAuditTypes.ROLE_CHANGED.module,
                AccessAuditTypes.ROLE_CHANGED.action,
            )
        assertThat(deleted).containsExactlyInAnyOrder(lead.id.toString(), night.id.toString())
    }

    @Test
    fun `a bulk delete naming a system role or an unknown id deletes nothing`() {
        val administrator = db.catalogue.declareSystemRole(db.ids.next(), "administrator", "Administrator", true, START)
        val lead = kit.roles.create("support-lead", "Support lead")

        assertThatThrownBy { kit.roles.deleteAll(listOf(lead.id, administrator)) }.isInstanceOf(Fault::class.java)
        assertThatThrownBy { kit.roles.deleteAll(listOf(lead.id, UUID.randomUUID())) }.isInstanceOf(Fault::class.java)

        assertThat(db.grants.roleById(lead.id)).isNotNull()
        assertThat(db.grants.roleById(administrator)).isNotNull()
    }
}
