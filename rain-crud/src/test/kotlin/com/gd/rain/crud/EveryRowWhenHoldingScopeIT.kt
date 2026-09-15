package com.gd.rain.crud

import com.gd.rain.core.error.FaultKind
import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.proof.CrudPlanProof
import com.gd.rain.crud.proof.ProofScope
import com.gd.rain.crud.proof.StatementKind
import com.gd.rain.crud.web.CrudOperation
import com.gd.rain.crud.web.MountedResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** `ScopeRule.EveryRowWhenHolding` over PostgreSQL: the jOOQ store confines each caller as the policy decides for it. */
@Tag("integration")
class EveryRowWhenHoldingScopeIT {
    private val database = BookDatabase("crud_every_row_when_holding")
    private val callers = SwitchableCallers()
    private val resource = CrudResource(Books.rules(), Books.policy(EVERY_SHELF_OR_OWN), database.store, callers, emptyList())

    @Test
    fun `a holder reads and writes every shelf, a caller without the permission only its own, through one resource`() {
        val (mine, theirs) = database.insert(book("mine", "a", 1, T0), book("theirs", "b", 2, T0))

        callers.caller = TestCaller.of("a", *Books.EVERY_PERMISSION.toTypedArray(), EVERY_SHELF)
        val everything = resource.list(emptyMap()).ids()
        val widened = resource.update(theirs, mapOf("pages" to 9))
        val elsewhere = resource.create(book("elsewhere", "c", 3, T0))

        callers.caller = TestCaller.of("a", *Books.EVERY_PERMISSION.toTypedArray())
        val own = resource.list(emptyMap()).ids()
        val hidden = faultOf { resource.get(theirs.toString(), emptyMap()) }
        val strayCreate = faultOf { resource.create(book("stray", "b", 1, T0)) }
        val strayUpdate = faultOf { resource.update(theirs, mapOf("pages" to 1)) }

        assertThat(everything).containsExactlyInAnyOrder(mine, theirs)
        assertThat(widened).containsEntry("pages", 9)
        assertThat(elsewhere).containsEntry("shelf", "c")
        assertThat(own).containsExactly(mine)
        assertThat(hidden.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(strayCreate.code.value).isEqualTo("outside_scope")
        assertThat(strayUpdate.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(database.stored(theirs)).containsEntry("pages", 9)
    }
}

/**
 * The plan proof of a resource whose scope widens for a holder, under both scopes its policy yields — every row for a
 * holder, the caller's shelf for anybody else — taken from the resource itself with `scopeOf`, so the statements proven
 * are exactly the ones each kind of caller runs.
 */
@Tag("integration")
class PlanProofUnderEveryRowWhenHoldingIT {
    private val database = BookDatabase("crud_proof_every_row_when_holding")
    private val resource = CrudResource(Books.rules(), Books.policy(EVERY_SHELF_OR_OWN), database.store, SwitchableCallers(), emptyList())

    @Test
    fun `every statement of every mounted operation is bounded for a holder and for a caller confined to its shelf`() {
        val holder = TestCaller.of("a", EVERY_SHELF)
        val confined = TestCaller.of("a")
        val mounted = MountedResource("/books", CrudOperation.entries.toSet(), resource)

        val result =
            CrudPlanProof.verify(
                database.store,
                mounted,
                listOf(ProofScope("holding book.every-shelf", resource.scopeOf(holder)), ProofScope("shelf a", resource.scopeOf(confined))),
                database.dataSource,
            )

        result.assertBounded()
        val byScope = result.statements.groupBy { it.scope }
        assertThat(byScope.keys).containsExactly("holding book.every-shelf", "shelf a")
        assertThat(byScope.getValue("holding book.every-shelf").filter { it.kind == StatementKind.ITEM }.map { it.sql })
            .singleElement()
            .matches({ !it.contains("\"shelf\" = 'a'") }, "reads by identifier alone")
        assertThat(byScope.getValue("shelf a").filter { it.kind == StatementKind.ITEM }.map { it.sql })
            .singleElement()
            .matches({ it.contains("\"shelf\" = 'a'") }, "reads within the shelf")
        assertThat(byScope.getValue("shelf a").map { it.kind }).contains(StatementKind.ROW_IN_SCOPE)
    }
}
