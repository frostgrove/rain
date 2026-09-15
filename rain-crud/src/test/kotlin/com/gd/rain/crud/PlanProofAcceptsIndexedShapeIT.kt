package com.gd.rain.crud

import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.proof.CrudPlanProof
import com.gd.rain.crud.proof.PlanProofNotEvaluatedException
import com.gd.rain.crud.proof.ProofScope
import com.gd.rain.crud.proof.StatementKind
import com.gd.rain.crud.query.Predicate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * The books fixture declares twelve shapes and a composite index for each, unscoped and under the shelf
 * scope. The plan proof explains every statement the resource can run for them and finds none unbounded. It
 * evaluates only an empty, never-analysed table, where the plan depends on the indexes alone.
 */
@Tag("integration")
class PlanProofAcceptsIndexedShapeIT {
    private val database = BookDatabase("crud_proof_accepts")
    private val resource = CrudResource(Books.rules(), Books.policy(Books.SHELF_SCOPE), database.store, SwitchableCallers(), emptyList())
    private val scopes =
        listOf(
            ProofScope("everything", RowScope.Everything),
            ProofScope("shelf a", RowScope.Matching(Predicate.eq(Books.SHELF, "a"))),
        )

    @Test
    fun `every statement of every declared shape is bounded on an empty table`() {
        val result = CrudPlanProof.verify(database.store, resource, scopes, database.dataSource)

        result.assertBounded()
        // 12 shapes; `title in` and `price isnull` have two value variants each: 14 per scope, 5 kinds, 2 scopes.
        assertThat(result.statements).hasSize(140)
        assertThat(result.statements.map { it.kind }.toSet()).containsExactlyInAnyOrder(*StatementKind.entries.toTypedArray())
        assertThat(result.statements.map { it.scope }.distinct()).containsExactly("everything", "shelf a")
    }

    @Test
    fun `a table that holds rows or statistics is not evaluated, and every reason is named`() {
        database.insert(book("dune", "a", 412, T0))

        assertThatThrownBy { CrudPlanProof.verify(database.store, resource, scopes, database.dataSource) }
            .isInstanceOf(PlanProofNotEvaluatedException::class.java)
            .hasMessage(
                "the plan proof was not evaluated: table public.books holds rows; " +
                    "the proof explains against an empty, never-analysed table so that the plans depend on the indexes alone",
            )

        database.jdbc.execute("ANALYZE public.books")

        assertThatThrownBy { CrudPlanProof.verify(database.store, resource, scopes, database.dataSource) }
            .isInstanceOf(PlanProofNotEvaluatedException::class.java)
            .hasMessageContaining("table public.books has been vacuumed or analysed (reltuples = 1.0); has column statistics; holds rows;")
    }

    @Test
    fun `a filter on the field the scope pins is rendered with the scope's value, so the statement is one a request runs`() {
        val result = CrudPlanProof.verify(database.store, resource, scopes, database.dataSource)

        val pinned =
            result.statements.filter { it.scope == "shelf a" && it.kind == StatementKind.FIRST_PAGE && "filter[shelf][eq]" in it.values }
        assertThat(pinned).isNotEmpty().allMatch { it.values.contains("filter[shelf][eq]=a") && it.sql.contains("\"shelf\" = 'a'") }
    }
}
