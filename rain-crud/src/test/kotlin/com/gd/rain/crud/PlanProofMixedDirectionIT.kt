package com.gd.rain.crud

import com.gd.rain.crud.proof.CrudPlanProof
import com.gd.rain.crud.proof.ProofScope
import com.gd.rain.crud.proof.StatementKind
import com.gd.rain.crud.query.QueryShape
import com.gd.rain.crud.query.SortKey
import com.gd.rain.crud.web.CrudOperation
import com.gd.rain.crud.web.MountedResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * A mixed-direction order (`title` ascending, `pages` descending, identifier descending) with an index in
 * exactly that order. PostgreSQL 18 serves the first page, the offset page and the count through the index,
 * but a row comparison cannot mix directions: the seek is the expanded comparison, of which the index bounds
 * only the leading `title` and the rest is a Filter. The rows with the cursor's title before it are read and
 * discarded, however many there are, so the cursor pages of this shape are unbounded.
 */
@Tag("integration")
class PlanProofMixedDirectionIT {
    private val database =
        BookDatabase("crud_proof_mixed").also {
            it.jdbc.execute("CREATE INDEX books_title_pages_desc_id_desc ON public.books (title, pages DESC, id DESC)")
        }

    @Test
    fun `the index serves the first page, the offset page and the count, and the cursor seeks are filtered`() {
        val shape = QueryShape.of(SortKey.parse("title,-pages"))
        val resource = CrudResource(Books.rules(shapes = listOf(shape)), Books.policy(), database.store, SwitchableCallers(), emptyList())

        val result =
            CrudPlanProof.verify(
                database.store,
                MountedResource("/books", setOf(CrudOperation.LIST, CrudOperation.COUNT), resource),
                listOf(ProofScope("everything", RowScope.Everything)),
                database.dataSource,
            )

        assertThat(result.statements.map { it.kind }).containsExactly(*SHAPE_KINDS)
        assertThat(result.findings.map { it.statement.kind }).containsExactly(StatementKind.SEEK_FORWARD, StatementKind.SEEK_BACKWARD)
        result.findings.forEach { finding ->
            assertThat(finding.planJson).contains("books_title_pages_desc_id_desc")
            assertThat(finding.reasons).describedAs("%s", finding).anyMatch { it.contains("carries a Filter") }
        }
    }
}

/** The statements a list and a count run for one shape value variant, in the order the proof explains them. */
val SHAPE_KINDS: Array<StatementKind> =
    arrayOf(
        StatementKind.FIRST_PAGE,
        StatementKind.SEEK_FORWARD,
        StatementKind.SEEK_BACKWARD,
        StatementKind.OFFSET_PAGE,
        StatementKind.CAPPED_COUNT,
    )
