package com.gd.rain.access.usecase

import com.gd.rain.access.SubjectRef
import com.gd.rain.access.internal.usecase.RoleAdministration
import com.gd.rain.access.support.AGENT
import com.gd.rain.access.support.MemoryGrants
import com.gd.rain.access.support.START
import com.gd.rain.access.support.accessWebRunner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Deleting a role removed its holders and permissions `rain.access.session.revoke-batch` rows per transaction, a setting
 * named and documented for closing sessions. Role deletion has `rain.access.grants.role-deletion-batch` of its own.
 */
class RoleDeletionBatchIsItsOwnSettingTest {
    @Test
    fun `a role's holders and permissions are removed role-deletion-batch rows at a time, whatever session revoke-batch is`() {
        accessWebRunner()
            .withPropertyValues("rain.access.session.revoke-batch=7", "rain.access.grants.role-deletion-batch=2")
            .run { context ->
                assertThat(context).hasNotFailed()
                val grants = context.getBean(MemoryGrants::class.java)
                val roles = context.getBean(RoleAdministration::class.java)
                val role = roles.create("night-shift", "Night shift")
                repeat(5) { grants.grantRole(SubjectRef(AGENT, UUID.randomUUID()), role.id, START) }

                roles.delete(role.id)

                assertThat(grants.holders(role.id)).isZero()
                assertThat(grants.roleById(role.id)).isNull()
                assertThat(grants.removalBatches).isNotEmpty().containsOnly(2)
            }
    }
}
