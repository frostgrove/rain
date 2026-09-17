package com.gd.rain.tenancy.sharedrow

import org.jooq.DSLContext

/** A declarative ownership contract for one application table stored in the shared tenant plane. */
public data class TenantRowSpec(
    public val schema: String,
    public val table: String,
    /** The unprivileged role through which the application reaches this table. */
    public val applicationRole: String,
    public val referenceDigestColumn: String = REFERENCE_DIGEST_COLUMN,
    public val epochColumn: String = EPOCH_COLUMN,
) {
    init {
        listOf(schema, table, applicationRole, referenceDigestColumn, epochColumn).forEach {
            require(IDENTIFIER.matches(it)) { "tenant row specification identifier is not stable" }
        }
        require(referenceDigestColumn != epochColumn) { "tenant ownership columns differ" }
    }

    public companion object {
        public const val REFERENCE_DIGEST_COLUMN: String = "tenant_ref_digest"
        public const val EPOCH_COLUMN: String = "tenant_epoch"

        private val IDENTIFIER: Regex = Regex("^[a-z][a-z0-9_]{0,62}$")
    }
}

/** A closed, actionable failure category from [PostgresTenantRowVerifier]. */
public enum class TenantRowProblemCode {
    TABLE_MISSING,
    OWNER_COLUMNS,
    RLS_DISABLED,
    APPLICATION_ROLE,
    POLICY_MISSING,
    POLICY_UNSAFE,
    OWNERSHIP_KEY,
    TENANT_INDEX,
    TENANT_FOREIGN_KEY,
}

public data class TenantRowProblem(
    public val code: TenantRowProblemCode,
    public val message: String,
)

/**
 * PostgreSQL catalog verifier for the shared-row contract. It never mutates an application schema:
 * migrations stay owned by the module that owns the table, while this proof can run in integration
 * tests and startup validation.
 */
public class PostgresTenantRowVerifier {
    public fun verify(
        dsl: DSLContext,
        spec: TenantRowSpec,
    ): List<TenantRowProblem> {
        val relation = relation(dsl, spec) ?: return listOf(problem(TenantRowProblemCode.TABLE_MISSING, spec, "does not exist"))
        val problems = mutableListOf<TenantRowProblem>()
        verifyOwnerColumns(dsl, spec, problems)
        verifyRls(dsl, spec, relation, problems)
        verifyApplicationRole(dsl, spec, relation, problems)
        verifyPolicies(dsl, spec, relation, problems)
        verifyIndexes(dsl, spec, relation, problems)
        verifyForeignKeys(dsl, spec, relation, problems)
        return problems
    }

    /** Refuses a process from starting with a table that can bypass tenant isolation. */
    public fun require(
        dsl: DSLContext,
        spec: TenantRowSpec,
    ) {
        val problems = verify(dsl, spec)
        require(problems.isEmpty()) {
            problems.joinToString(prefix = "tenant row ${spec.schema}.${spec.table} is unsafe: ", separator = "; ") { it.message }
        }
    }

    private fun relation(
        dsl: DSLContext,
        spec: TenantRowSpec,
    ): Long? =
        dsl
            .fetchOne(
                """
                SELECT c.oid::bigint
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')
                """.trimIndent(),
                spec.schema,
                spec.table,
            )?.get(0, Long::class.java)

    private fun verifyOwnerColumns(
        dsl: DSLContext,
        spec: TenantRowSpec,
        problems: MutableList<TenantRowProblem>,
    ) {
        val columns =
            dsl
                .fetch(
                    """
                    SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS type, a.attnotnull
                    FROM pg_catalog.pg_attribute a
                    JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                    """.trimIndent(),
                    spec.schema,
                    spec.table,
                ).associate { row ->
                    row.get("attname", String::class.java) to
                        (row.get("type", String::class.java) to row.get("attnotnull", Boolean::class.java))
                }
        val hasRequiredColumns =
            columns[spec.referenceDigestColumn] == ("bytea" to true) &&
                columns[spec.epochColumn] == ("bigint" to true)
        val hasDigestLengthConstraint =
            dsl
                .fetch(
                    """
                    SELECT pg_get_constraintdef(co.oid) AS definition
                    FROM pg_catalog.pg_constraint co
                    JOIN pg_catalog.pg_class c ON c.oid = co.conrelid
                    JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = ? AND c.relname = ? AND co.contype = 'c'
                    """.trimIndent(),
                    spec.schema,
                    spec.table,
                ).any { row ->
                    constrainsDigestToThirtyTwoBytes(row.get("definition", String::class.java), spec.referenceDigestColumn)
                }
        if (!hasRequiredColumns || !hasDigestLengthConstraint) {
            problems +=
                problem(
                    TenantRowProblemCode.OWNER_COLUMNS,
                    spec,
                    "requires NOT NULL bytea ${spec.referenceDigestColumn} constrained to 32 bytes and bigint ${spec.epochColumn}",
                )
        }
    }

    private fun verifyRls(
        dsl: DSLContext,
        spec: TenantRowSpec,
        relation: Long,
        problems: MutableList<TenantRowProblem>,
    ) {
        val row = dsl.fetchOne("SELECT relrowsecurity, relforcerowsecurity FROM pg_catalog.pg_class WHERE oid = ?", relation)
        if (row?.get(0, Boolean::class.java) != true || row.get(1, Boolean::class.java) != true) {
            problems += problem(TenantRowProblemCode.RLS_DISABLED, spec, "must enable and force row-level security")
        }
    }

    private fun verifyApplicationRole(
        dsl: DSLContext,
        spec: TenantRowSpec,
        relation: Long,
        problems: MutableList<TenantRowProblem>,
    ) {
        val row =
            dsl.fetchOne(
                """
                SELECT r.rolbypassrls, c.relowner = r.oid AS owns_table
                FROM pg_catalog.pg_roles r
                CROSS JOIN pg_catalog.pg_class c
                WHERE r.rolname = ? AND c.oid = ?
                """.trimIndent(),
                spec.applicationRole,
                relation,
            )
        if (row == null || row.get(0, Boolean::class.java) == true || row.get(1, Boolean::class.java) == true) {
            problems +=
                problem(TenantRowProblemCode.APPLICATION_ROLE, spec, "application role must exist, lack BYPASSRLS, and not own the table")
        }
    }

    private fun verifyPolicies(
        dsl: DSLContext,
        spec: TenantRowSpec,
        relation: Long,
        problems: MutableList<TenantRowProblem>,
    ) {
        val policies =
            dsl.fetch(
                """
                SELECT polcmd, pg_get_expr(polqual, polrelid) AS using_expression, pg_get_expr(polwithcheck, polrelid) AS check_expression
                FROM pg_catalog.pg_policy
                WHERE polrelid = ?
                """.trimIndent(),
                relation,
            )
        POLICY_OPERATIONS.forEach { (command, needsUsing, needsCheck) ->
            val matching = policies.filter { row -> row.get("polcmd", String::class.java) in setOf(command, "*") }
            if (matching.isEmpty()) {
                problems += problem(TenantRowProblemCode.POLICY_MISSING, spec, "has no ${commandName(command)} policy")
            } else if (matching.none { row ->
                    (!needsUsing || safeExpression(row.get("using_expression", String::class.java), spec)) &&
                        (!needsCheck || safeExpression(row.get("check_expression", String::class.java), spec))
                }
            ) {
                problems +=
                    problem(
                        TenantRowProblemCode.POLICY_UNSAFE,
                        spec,
                        "${commandName(command)} policy does not fence both tenant owner columns from transaction-local settings",
                    )
            }
        }
    }

    private fun verifyIndexes(
        dsl: DSLContext,
        spec: TenantRowSpec,
        relation: Long,
        problems: MutableList<TenantRowProblem>,
    ) {
        val indexes =
            dsl
                .fetch(
                    """
                    SELECT ix.indisunique, ix.indisprimary, string_agg(a.attname, ',' ORDER BY keys.ordinality) AS columns
                    FROM pg_catalog.pg_index ix
                    JOIN LATERAL unnest(ix.indkey::int2[]) WITH ORDINALITY AS keys(attnum, ordinality) ON true
                    LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = keys.attnum
                    WHERE ix.indrelid = ? AND keys.ordinality <= ix.indnkeyatts
                    GROUP BY ix.indexrelid, ix.indisunique, ix.indisprimary
                    """.trimIndent(),
                    relation,
                ).map { row ->
                    IndexShape(
                        row.get("indisunique", Boolean::class.java) == true,
                        row.get("indisprimary", Boolean::class.java) == true,
                        row.get("columns", String::class.java)?.split(',').orEmpty(),
                    )
                }
        if (indexes.none { it.startsWithOwner(spec) }) {
            problems += problem(TenantRowProblemCode.TENANT_INDEX, spec, "requires an index beginning with both tenant owner columns")
        }
        if (indexes.any { (it.unique || it.primary) && !it.startsWithOwner(spec) }) {
            problems +=
                problem(
                    TenantRowProblemCode.OWNERSHIP_KEY,
                    spec,
                    "every primary or unique ownership key begins with both tenant owner columns",
                )
        }
    }

    private fun verifyForeignKeys(
        dsl: DSLContext,
        spec: TenantRowSpec,
        relation: Long,
        problems: MutableList<TenantRowProblem>,
    ) {
        val foreignKeys =
            dsl
                .fetch(
                    """
                    SELECT string_agg(a.attname, ',' ORDER BY keys.ordinality) AS columns
                    FROM pg_catalog.pg_constraint co
                    JOIN LATERAL unnest(co.conkey::int2[]) WITH ORDINALITY AS keys(attnum, ordinality) ON true
                    JOIN pg_catalog.pg_attribute a ON a.attrelid = co.conrelid AND a.attnum = keys.attnum
                    WHERE co.conrelid = ? AND co.contype = 'f'
                    GROUP BY co.oid
                    """.trimIndent(),
                    relation,
                ).map { it.get("columns", String::class.java)?.split(',').orEmpty() }
        if (foreignKeys.any { spec.referenceDigestColumn !in it || spec.epochColumn !in it }) {
            problems += problem(TenantRowProblemCode.TENANT_FOREIGN_KEY, spec, "every child foreign key includes both tenant owner columns")
        }
    }

    private fun safeExpression(
        expression: String?,
        spec: TenantRowSpec,
    ): Boolean =
        expression != null &&
            spec.referenceDigestColumn in expression &&
            spec.epochColumn in expression &&
            SharedRowTenantDataPlane.REF_DIGEST_GUC in expression &&
            SharedRowTenantDataPlane.EPOCH_GUC in expression

    private fun constrainsDigestToThirtyTwoBytes(
        definition: String?,
        digestColumn: String,
    ): Boolean =
        definition != null &&
            Regex("""octet_length\(\(?$digestColumn\)?\)\s*=\s*32""").containsMatchIn(definition.lowercase())

    private fun problem(
        code: TenantRowProblemCode,
        spec: TenantRowSpec,
        message: String,
    ): TenantRowProblem = TenantRowProblem(code, "${spec.schema}.${spec.table} $message")

    private data class IndexShape(
        val unique: Boolean,
        val primary: Boolean,
        val columns: List<String>,
    ) {
        fun startsWithOwner(spec: TenantRowSpec): Boolean =
            columns.size >= 2 && columns[0] == spec.referenceDigestColumn && columns[1] == spec.epochColumn
    }

    private companion object {
        val POLICY_OPERATIONS: List<Triple<String, Boolean, Boolean>> =
            listOf(
                Triple("r", true, false),
                Triple("a", false, true),
                Triple("w", true, true),
                Triple("d", true, false),
            )

        fun commandName(command: String): String =
            mapOf("r" to "SELECT", "a" to "INSERT", "w" to "UPDATE", "d" to "DELETE").getValue(command)
    }
}
