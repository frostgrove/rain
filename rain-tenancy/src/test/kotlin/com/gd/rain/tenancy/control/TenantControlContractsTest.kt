package com.gd.rain.tenancy.control

import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

class TenantControlContractsTest {
    @Test
    fun `the lifecycle graph is closed and every admitted source has one deliberate target`() {
        val expected =
            mapOf(
                TenantControlAction.BEGIN_PROVISION to mapOf(TenantLifecycle.PROVISIONING to TenantLifecycle.PROVISIONING),
                TenantControlAction.ACTIVATE to mapOf(TenantLifecycle.PROVISIONING to TenantLifecycle.ACTIVE),
                TenantControlAction.MAKE_READ_ONLY to mapOf(TenantLifecycle.ACTIVE to TenantLifecycle.READ_ONLY),
                TenantControlAction.RESUME_WRITES to mapOf(TenantLifecycle.READ_ONLY to TenantLifecycle.ACTIVE),
                TenantControlAction.SUSPEND to
                    mapOf(
                        TenantLifecycle.PROVISIONING to TenantLifecycle.SUSPENDED,
                        TenantLifecycle.ACTIVE to TenantLifecycle.SUSPENDED,
                        TenantLifecycle.READ_ONLY to TenantLifecycle.SUSPENDED,
                        TenantLifecycle.MIGRATING to TenantLifecycle.SUSPENDED,
                    ),
                TenantControlAction.BEGIN_MIGRATION to
                    mapOf(TenantLifecycle.ACTIVE to TenantLifecycle.MIGRATING, TenantLifecycle.READ_ONLY to TenantLifecycle.MIGRATING),
                TenantControlAction.FINISH_MIGRATION to mapOf(TenantLifecycle.MIGRATING to TenantLifecycle.ACTIVE),
                TenantControlAction.BEGIN_DELETION to
                    mapOf(
                        TenantLifecycle.PROVISIONING to TenantLifecycle.DELETING,
                        TenantLifecycle.ACTIVE to TenantLifecycle.DELETING,
                        TenantLifecycle.READ_ONLY to TenantLifecycle.DELETING,
                        TenantLifecycle.SUSPENDED to TenantLifecycle.DELETING,
                        TenantLifecycle.MIGRATING to TenantLifecycle.DELETING,
                    ),
                TenantControlAction.TOMBSTONE to mapOf(TenantLifecycle.DELETING to TenantLifecycle.DELETED),
                TenantControlAction.RESTORE_NEW_EPOCH to mapOf(TenantLifecycle.DELETED to TenantLifecycle.PROVISIONING),
                TenantControlAction.ROTATE_PLACEMENT to
                    mapOf(TenantLifecycle.ACTIVE to TenantLifecycle.ACTIVE, TenantLifecycle.READ_ONLY to TenantLifecycle.READ_ONLY),
                TenantControlAction.CREATE_DRAFT to emptyMap(),
            )

        TenantControlAction.entries.forEach { action ->
            TenantLifecycle.entries.forEach { lifecycle ->
                assertThat(TenantLifecycleGraph.target(action, lifecycle)).isEqualTo(expected.getValue(action)[lifecycle])
            }
        }
    }

    @Test
    fun `reference indexing is deterministic over normalized references and uses a dedicated keyed domain`() {
        val index = HmacTenantReferenceDigest(ByteArray(32) { 7 })
        val normalized = index.digest(TenantRef.of("café"))

        assertThat(index.digest(TenantRef.of("cafe\u0301"))).isEqualTo(normalized)
        assertThat(index.digest(TenantRef.of("other"))).isNotEqualTo(normalized)
        assertThat(normalized.toString()).contains("tenant-digest:").doesNotContain("café")
        assertThatThrownBy { HmacTenantReferenceDigest(ByteArray(31)) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `control versions and operation ids reject sentinel values`() {
        assertThatThrownBy { TenantControlVersion(0) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { ExpectedTenantControlVersion(-1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { TenantOperationId(UUID(0, 0)) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
