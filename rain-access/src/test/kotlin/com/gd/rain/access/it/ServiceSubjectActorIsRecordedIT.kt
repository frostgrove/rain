package com.gd.rain.access.it

import com.gd.rain.access.internal.audit.AccessAuditTypes
import com.gd.rain.access.support.AccessDatabase
import com.gd.rain.access.support.DatabaseKit
import com.gd.rain.core.error.Fault
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Gap 17: a service subject is an actor like any other, and its evidence needs no row of its own anywhere. */
@Tag("integration")
class ServiceSubjectActorIsRecordedIT {
    private val db = AccessDatabase.fresh("access_service_actor")
    private val kit = DatabaseKit(db)

    @Test
    fun `a service's sign-in is recorded with the service as its actor, and a failed one is recorded on its own`() {
        val service = kit.enrol(kit.services, "reporting-bot")

        val issued = kit.signIn(service, "reporting-bot")
        assertThatThrownBy { kit.signIn(service, "reporting-bot", "not the password") }.isInstanceOf(Fault::class.java)

        val signedIn =
            db.jdbc.queryForMap(
                "SELECT actor_type, actor_id, resource_kind, resource_id FROM rain_audit.audit_log WHERE module = ? AND action = ?",
                AccessAuditTypes.SIGNED_IN.module,
                AccessAuditTypes.SIGNED_IN.action,
            )
        assertThat(signedIn)
            .containsEntry("actor_type", "service")
            .containsEntry("actor_id", service.id.toString())
            .containsEntry("resource_kind", "session")
            .containsEntry("resource_id", issued.session.toString())
        val failed =
            db.count(
                "SELECT count(*) FROM rain_audit.audit_log WHERE module = '${AccessAuditTypes.MODULE}' " +
                    "AND action = '${AccessAuditTypes.SIGN_IN_FAILED.action}'",
            )
        assertThat(failed).isEqualTo(1)
        assertThat(db.count("SELECT count(*) FROM rain_access.sessions WHERE subject_type = 'service'")).isEqualTo(1)
    }
}
