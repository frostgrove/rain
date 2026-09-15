package com.gd.rain.crud

import com.gd.rain.crud.persistence.JooqResourceStore
import com.gd.rain.crud.persistence.RowReader
import com.gd.rain.crud.proof.CrudPlanProof
import com.gd.rain.crud.proof.ProofScope
import com.gd.rain.crud.proof.StatementKind
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.web.CrudOperation
import com.gd.rain.crud.web.MountedResource
import com.gd.rain.persistence.id.UuidV7Ids
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The plan proof used to explain lists and counts only. It now explains every statement the store runs for every
 * mounted operation under every stated scope — the item read, the insert, the update by identifier, the scope check
 * of a write, the delete and the bulk delete of `maxBulkIds` identifiers — and judges each by criterion v3, where a
 * lookup by primary key is bounded by the key even through a scope's composite index.
 */
@Tag("integration")
class PlanProofCoversEveryMountedStatementIT {
    private val database = BookDatabase("crud_proof_mounted")
    private val scopes =
        listOf(
            ProofScope("everything", RowScope.Everything),
            ProofScope("shelf a", RowScope.Matching(Predicate.eq(Books.SHELF, "a"))),
        )
    private val id = "00000000-0000-7000-8000-000000000001"

    private fun mounted(
        operations: Set<CrudOperation>,
        store: CrudStore<Map<String, Any?>> = database.store,
    ) = MountedResource(
        "/books",
        operations,
        CrudResource(Books.rules(), Books.policy(Books.SHELF_SCOPE), store, SwitchableCallers(), emptyList()),
    )

    @Test
    fun `every statement of every mounted operation is explained and bounded under every scope`() {
        val result = CrudPlanProof.verify(database.store, mounted(CrudOperation.entries.toSet()), scopes, database.dataSource)

        result.assertBounded()
        val byIdentifier = result.statements.filter { it.shape == null }.map { "${it.scope} | ${it.kind} | ${it.values}" }
        assertThat(byIdentifier).containsExactly(
            "everything | ITEM | id=$id",
            "everything | INSERT | id=$id, 9 fields",
            "everything | UPDATE | id=$id, 9 fields",
            "everything | DELETE | id=$id",
            "everything | BULK_DELETE | 1 ids",
            "everything | BULK_DELETE | 500 ids",
            "shelf a | ITEM | id=$id",
            "shelf a | INSERT | id=$id, 9 fields",
            "shelf a | UPDATE | id=$id, 9 fields",
            "shelf a | ROW_IN_SCOPE | id=$id",
            "shelf a | DELETE | id=$id",
            "shelf a | BULK_DELETE | 1 ids",
            "shelf a | BULK_DELETE | 500 ids",
        )
        assertThat(result.statements).hasSize(140 + byIdentifier.size)
        val bulk = result.statements.single { it.scope == "shelf a" && it.values == "500 ids" }
        assertThat(bulk.sql).contains("\"shelf\" = 'a'").contains("00000000-0000-7000-8000-0000000001f4")
    }

    @Test
    fun `a versioned update states its version and is checked under every scope`() {
        val versioned = JooqResourceStore(Books.VERSIONED, database.dsl, UuidV7Ids, RowReader.fields(Books.VERSIONED))

        val result = CrudPlanProof.verify(versioned, mounted(setOf(CrudOperation.REPLACE), versioned), scopes, database.dataSource)

        result.assertBounded()
        assertThat(result.statements.map { "${it.scope} | ${it.kind}" }).containsExactly(
            "everything | UPDATE",
            "everything | ROW_IN_SCOPE",
            "shelf a | UPDATE",
            "shelf a | ROW_IN_SCOPE",
        )
        assertThat(result.statements.first().sql).contains("\"version\" = 1").contains("\"version\" = (\"version\" + 1)")
    }

    @Test
    fun `only the statements of the mounted operations are explained`() {
        val result =
            CrudPlanProof.verify(
                database.store,
                mounted(setOf(CrudOperation.GET, CrudOperation.DELETE)),
                scopes,
                database.dataSource,
            )

        assertThat(result.statements.map { it.kind }).containsExactly(
            StatementKind.ITEM,
            StatementKind.DELETE,
            StatementKind.ITEM,
            StatementKind.DELETE,
        )
    }

    @Test
    fun `without a unique identifier every read of a statement by identifier is a finding`() {
        database.jdbc.execute("ALTER TABLE public.books DROP CONSTRAINT books_pkey")
        val operations =
            setOf(CrudOperation.CREATE, CrudOperation.GET, CrudOperation.UPDATE, CrudOperation.DELETE, CrudOperation.BULK_DELETE)

        val result = CrudPlanProof.verify(database.store, mounted(operations), scopes.take(1), database.dataSource)

        assertThat(result.findings.map { "${it.statement.kind} | ${it.statement.values}" }).containsExactly(
            "ITEM | id=$id",
            "UPDATE | id=$id, 9 fields",
            "DELETE | id=$id",
            "BULK_DELETE | 1 ids",
            "BULK_DELETE | 500 ids",
        )
    }
}
