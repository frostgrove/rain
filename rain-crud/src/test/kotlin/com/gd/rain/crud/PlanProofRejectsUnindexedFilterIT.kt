package com.gd.rain.crud

import com.gd.rain.crud.proof.CrudPlanProof
import com.gd.rain.crud.proof.PlanProofResult
import com.gd.rain.crud.proof.ProofScope
import com.gd.rain.crud.proof.StatementKind
import com.gd.rain.crud.query.Operator
import com.gd.rain.crud.query.QueryShape
import com.gd.rain.crud.query.SortKey
import com.gd.rain.crud.web.CrudOperation
import com.gd.rain.crud.web.MountedResource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** A declared shape no index serves is found, with the plan node that makes each statement unbounded. */
@Tag("integration")
class PlanProofRejectsUnindexedFilterIT {
    private val database = BookDatabase("crud_proof_rejects")

    private fun proof(vararg shapes: QueryShape): PlanProofResult =
        CrudPlanProof.verify(
            database.store,
            MountedResource(
                "/books",
                setOf(CrudOperation.LIST, CrudOperation.COUNT),
                CrudResource(Books.rules(shapes = shapes.toList()), Books.policy(), database.store, SwitchableCallers(), emptyList()),
            ),
            listOf(ProofScope("everything", RowScope.Everything)),
            database.dataSource,
        )

    @Test
    fun `a filter on a column no index holds is a Filter in every statement`() {
        val result = proof(QueryShape.of(SortKey.parse("-createdAt"), "copies" to Operator.GTE))

        assertThat(result.findings.map { it.statement.kind }).containsExactly(*SHAPE_KINDS)
        result.findings.forEach { finding ->
            assertThat(finding.reasons).describedAs("%s", finding).anyMatch { it.contains("carries a Filter") && it.contains("copies") }
        }
    }

    @Test
    fun `an order no index serves is a Sort under the Limit of every page`() {
        val result = proof(QueryShape.of(SortKey.parse("copies")))

        assertThat(result.findings.map { it.statement.kind })
            .containsExactly(StatementKind.FIRST_PAGE, StatementKind.SEEK_FORWARD, StatementKind.SEEK_BACKWARD, StatementKind.OFFSET_PAGE)
        result.findings.forEach { finding ->
            assertThat(finding.reasons).describedAs("%s", finding).anyMatch { it.startsWith("Sort is between") }
        }
    }

    @Test
    fun `the assertion fails listing every finding with its shape, statement, scope, reasons, SQL and plan`() {
        val result = proof(QueryShape.of(SortKey.parse("copies")), QueryShape.of(SortKey.parse("-createdAt")))

        assertThatThrownBy { result.assertBounded() }
            .isInstanceOf(AssertionError::class.java)
            .hasMessageStartingWith("4 of 10 statements are not bounded by their plans:")
            .matches({ error -> result.findings.all { error.message!!.contains(it.toString()) } }, "lists every finding")
        assertThat(result.findings.first().toString())
            .startsWith("shape filters [], sort copies | scope everything | FIRST_PAGE | no filter\n")
            .contains("  - Sort is between", "  sql: select", "  plan: [")
    }
}
