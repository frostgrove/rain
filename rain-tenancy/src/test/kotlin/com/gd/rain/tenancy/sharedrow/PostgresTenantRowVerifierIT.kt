package com.gd.rain.tenancy.sharedrow

import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.SQLDialect
import org.jooq.exception.DataAccessException
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Catalog-level proof that an application table cannot merely claim shared-row isolation. */
@Tag("integration")
class PostgresTenantRowVerifierIT {
    @Test
    fun `a fully tenant-owned table proves its RLS role policy key and index contract`() {
        val fixture = Fixture("tenant_row_valid")
        fixture.safeTable("note")

        val problems = fixture.verifier.verify(fixture.dsl, fixture.spec("note"))

        assertThat(problems).isEmpty()
        fixture.verifier.require(fixture.dsl, fixture.spec("note"))
    }

    @Test
    fun `a table with global keys absent policies and disabled RLS is rejected by distinct contract failures`() {
        val fixture = Fixture("tenant_row_invalid")
        fixture.dsl.execute(
            """
            CREATE TABLE app.unsafe_note (
              tenant_ref_digest BYTEA,
              tenant_epoch BIGINT,
              id UUID PRIMARY KEY
            )
            """.trimIndent(),
        )
        fixture.dsl.execute("ALTER TABLE app.unsafe_note OWNER TO ${fixture.ownerRole}")

        val problems = fixture.verifier.verify(fixture.dsl, fixture.spec("unsafe_note"))

        assertThat(problems.map(TenantRowProblem::code)).contains(
            TenantRowProblemCode.OWNER_COLUMNS,
            TenantRowProblemCode.RLS_DISABLED,
            TenantRowProblemCode.POLICY_MISSING,
            TenantRowProblemCode.OWNERSHIP_KEY,
            TenantRowProblemCode.TENANT_INDEX,
        )
        assertThatThrownBy { fixture.verifier.require(fixture.dsl, fixture.spec("unsafe_note")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unsafe_note")
    }

    @Test
    fun `application role without transaction-local tenant settings cannot read or mutate a verified table`() {
        val fixture = Fixture("tenant_row_probe")
        fixture.safeTable("note")

        fixture.dsl.transaction { configuration ->
            val applicationDsl = DSL.using(configuration)
            applicationDsl.execute("SET LOCAL ROLE ${fixture.applicationRole}")

            assertThat(applicationDsl.fetchCount(DSL.table(DSL.name("app", "note")))).isZero()
        }
        assertThatThrownBy {
            fixture.dsl.transaction { configuration ->
                val applicationDsl = DSL.using(configuration)
                applicationDsl.execute("SET LOCAL ROLE ${fixture.applicationRole}")
                applicationDsl.execute(
                    """
                    INSERT INTO app.note (tenant_ref_digest, tenant_epoch, id, body)
                    VALUES (decode(repeat('00', 32), 'hex'), 1, '00000000-0000-0000-0000-000000000001', 'forbidden')
                    """.trimIndent(),
                )
            }
        }.isInstanceOf(DataAccessException::class.java)
    }

    private class Fixture(
        prefix: String,
    ) {
        private val database = RainPostgres.freshDatabase(prefix)
        val dsl = DSL.using(database.dataSource(), SQLDialect.POSTGRES)
        val verifier = PostgresTenantRowVerifier()
        val ownerRole = "${prefix}_owner"
        val applicationRole = "${prefix}_app"

        init {
            dsl.execute("CREATE ROLE $ownerRole NOLOGIN NOBYPASSRLS")
            dsl.execute("CREATE ROLE $applicationRole NOLOGIN NOBYPASSRLS")
            dsl.execute("CREATE SCHEMA app AUTHORIZATION $ownerRole")
            dsl.execute("GRANT USAGE ON SCHEMA app TO $applicationRole")
        }

        fun spec(table: String): TenantRowSpec = TenantRowSpec("app", table, applicationRole)

        fun safeTable(table: String) {
            dsl.execute(
                """
                CREATE TABLE app.$table (
                  tenant_ref_digest BYTEA NOT NULL,
                  tenant_epoch BIGINT NOT NULL,
                  id UUID NOT NULL,
                  body TEXT NOT NULL,
                  CHECK (octet_length(tenant_ref_digest) = 32),
                  PRIMARY KEY (tenant_ref_digest, tenant_epoch, id)
                )
                """.trimIndent(),
            )
            dsl.execute("CREATE INDEX ix_${table}_tenant_body ON app.$table (tenant_ref_digest, tenant_epoch, body)")
            dsl.execute("ALTER TABLE app.$table OWNER TO $ownerRole")
            dsl.execute("ALTER TABLE app.$table ENABLE ROW LEVEL SECURITY")
            dsl.execute("ALTER TABLE app.$table FORCE ROW LEVEL SECURITY")
            dsl.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON app.$table TO $applicationRole")
            val predicate =
                "tenant_ref_digest = decode(current_setting('rain.tenant_ref_digest', true), 'base64') " +
                    "AND tenant_epoch = current_setting('rain.tenant_epoch', true)::bigint"
            dsl.execute("CREATE POLICY ${table}_select ON app.$table FOR SELECT TO $applicationRole USING ($predicate)")
            dsl.execute("CREATE POLICY ${table}_insert ON app.$table FOR INSERT TO $applicationRole WITH CHECK ($predicate)")
            dsl.execute(
                "CREATE POLICY ${table}_update ON app.$table FOR UPDATE TO $applicationRole USING ($predicate) WITH CHECK ($predicate)",
            )
            dsl.execute("CREATE POLICY ${table}_delete ON app.$table FOR DELETE TO $applicationRole USING ($predicate)")
        }
    }
}
