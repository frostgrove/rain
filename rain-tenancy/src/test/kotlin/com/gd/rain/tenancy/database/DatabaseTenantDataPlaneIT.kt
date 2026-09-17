package com.gd.rain.tenancy.database

import com.gd.rain.tenancy.CompositeTenantResolver
import com.gd.rain.tenancy.HmacTenantAuthority
import com.gd.rain.tenancy.TenantAdmission
import com.gd.rain.tenancy.TenantEpoch
import com.gd.rain.tenancy.TenantGrantVerifier
import com.gd.rain.tenancy.TenantLifecycle
import com.gd.rain.tenancy.TenantOperation
import com.gd.rain.tenancy.TenantRef
import com.gd.rain.tenancy.TenantResolution
import com.gd.rain.tenancy.control.HmacTenantReferenceDigest
import com.gd.rain.test.MutableClock
import com.gd.rain.test.RainDatabase
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant

/** Exercises two tenant databases through credentials, bounded pools, and their source fences. */
@Tag("integration")
class DatabaseTenantDataPlaneIT {
    @Test
    fun `two tenant units use different fenced backings and leave no shared data plane`() {
        Fixture("database_plane").use { fixture ->
            val acme = fixture.scope("acme")
            val other = fixture.scope("other")
            lateinit var acmeBacking: Any
            lateinit var otherBacking: Any

            fixture.plane.write(acme) { unit ->
                acmeBacking = unit.backing
                unit.dsl.execute("INSERT INTO note (id, body) VALUES (1, 'acme')")
            }
            fixture.plane.write(other) { unit ->
                otherBacking = unit.backing
                unit.dsl.execute("INSERT INTO note (id, body) VALUES (1, 'other')")
            }

            assertThat(acmeBacking).isNotEqualTo(otherBacking)
            assertThat(fixture.count("acme")).isEqualTo(1)
            assertThat(fixture.count("other")).isEqualTo(1)
        }
    }

    @Test
    fun `capacity is reserved before a second tenant pool opens and active lease stays valid`() {
        Fixture("database_capacity", maxOpenPools = 1).use { fixture ->
            val held = fixture.directory.borrow(fixture.scope("acme"))
            try {
                assertThatThrownBy { fixture.directory.borrow(fixture.scope("other")) }
                    .isInstanceOf(TenantDatabaseCapacityException::class.java)
                assertThat(held.dsl.fetchValue("SELECT 1", Int::class.java)).isEqualTo(1)
            } finally {
                held.close()
            }
        }
    }

    @Test
    fun `expired idle backing gives its reserved capacity to the next tenant`() {
        Fixture("database_idle", maxOpenPools = 1, idleTtl = Duration.ofMillis(1)).use { fixture ->
            fixture.directory.borrow(fixture.scope("acme")).close()
            fixture.advance(Duration.ofMillis(1))

            fixture.directory.borrow(fixture.scope("other")).close()
        }
    }

    @Test
    fun `a tenant placement whose local source fence names another epoch is rejected before unit work`() {
        Fixture("database_fence", acmeEpochInDatabase = 2).use { fixture ->
            assertThatThrownBy { fixture.plane.write(fixture.scope("acme")) { error("must not enter tenant unit") } }
                .isInstanceOf(TenantSourceMismatchException::class.java)
        }
    }

    private class Fixture(
        prefix: String,
        maxOpenPools: Int = 2,
        acmeEpochInDatabase: Long = 1,
        private val idleTtl: Duration = Duration.ofMinutes(5),
    ) : AutoCloseable {
        private val clock = MutableClock(Instant.parse("2026-09-15T10:00:00Z"))
        private val index = HmacTenantReferenceDigest(ByteArray(32) { 9 })
        private val refs =
            listOf("acme", "other").associate { name ->
                TenantRef.of(name) to TenantResolution(TenantRef.of(name), TenantLifecycle.ACTIVE, TenantEpoch(1), 1)
            }
        private val authority =
            HmacTenantAuthority(
                "test",
                CompositeTenantResolver(emptyList()) { refs[it] },
                TenantAdmission.DEFAULT,
                ByteArray(32) { 3 },
                clock = clock,
                random = SecureRandom(),
            )
        private val databases =
            mapOf(
                TenantRef.of("acme") to RainPostgres.freshDatabase("${prefix}_acme"),
                TenantRef.of("other") to RainPostgres.freshDatabase("${prefix}_other"),
            )
        private val placements =
            databases.mapValues { (ref, database) ->
                TenantDatabasePlacement(
                    database.url,
                    "${prefix}_${if (ref == TenantRef.of("acme")) "acme" else "other"}",
                    "secret:${if (ref == TenantRef.of("acme")) "acme" else "other"}",
                    placementVersion = 1,
                    credentialVersion = 1,
                    catalogueFingerprint = FINGERPRINT,
                )
            }
        val directory =
            TenantDatabaseDirectory(
                TenantPlacementDirectory { scope -> placements.getValue(scope.ref) },
                TenantSecretResolver { secretRef, _ ->
                    val database =
                        when (secretRef) {
                            "secret:acme" -> databases.getValue(TenantRef.of("acme"))
                            "secret:other" -> databases.getValue(TenantRef.of("other"))
                            else -> error("unknown test secret")
                        }
                    TenantDatabaseCredential(database.username, database.password.toCharArray())
                },
                PostgresTenantSourceFenceVerifier("test", index),
                index,
                TenantDatabaseDirectorySettings(
                    maxOpenPools = maxOpenPools,
                    maxTotalConnections = maxOpenPools,
                    perTenantMaxPool = 1,
                    idleTtl = idleTtl,
                    openTimeout = Duration.ofSeconds(5),
                    shutdownDrainTimeout = Duration.ofSeconds(1),
                ),
                clock,
            )
        val plane =
            DatabaseTenantDataPlane(
                directory,
                authority,
                TenantGrantVerifier { _, _, _ -> error("this test has no fleet grant") },
                clock,
            )

        init {
            databases.forEach { (ref, database) ->
                bootstrap(ref, database, if (ref == TenantRef.of("acme")) acmeEpochInDatabase else 1)
            }
        }

        fun scope(ref: String) = authority.lookup(TenantRef.of(ref), TenantOperation.WRITE)

        fun count(ref: String): Int =
            DSL.using(databases.getValue(TenantRef.of(ref)).dataSource(), SQLDialect.POSTGRES).fetchCount(DSL.table("note"))

        fun advance(by: Duration) {
            clock.advance(by)
        }

        override fun close() {
            directory.close()
        }

        private fun bootstrap(
            ref: TenantRef,
            database: RainDatabase,
            epoch: Long,
        ) {
            val dsl = DSL.using(database.dataSource(), SQLDialect.POSTGRES)
            dsl.execute(
                """
                CREATE TABLE rain_tenancy_binding (
                  origin TEXT NOT NULL,
                  tenant_ref_digest BYTEA NOT NULL,
                  tenant_epoch BIGINT NOT NULL,
                  database_id TEXT NOT NULL,
                  catalogue_fingerprint BYTEA NOT NULL
                )
                """.trimIndent(),
            )
            dsl.execute("CREATE TABLE note (id BIGINT PRIMARY KEY, body TEXT NOT NULL)")
            val placement = placements.getValue(ref)
            dsl.execute(
                """
                INSERT INTO rain_tenancy_binding (
                  origin, tenant_ref_digest, tenant_epoch, database_id, catalogue_fingerprint
                ) VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                "test",
                index.digest(ref).copy(),
                epoch,
                placement.databaseId,
                placement.catalogueFingerprint(),
            )
        }
    }

    private companion object {
        val FINGERPRINT: ByteArray = ByteArray(32) { 4 }
    }
}
