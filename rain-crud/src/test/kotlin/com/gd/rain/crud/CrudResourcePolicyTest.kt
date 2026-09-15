package com.gd.rain.crud

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.error.FaultKind
import com.gd.rain.crud.Books.T0
import com.gd.rain.crud.Books.book
import com.gd.rain.crud.query.FieldGrant
import com.gd.rain.crud.query.FieldKind
import com.gd.rain.crud.query.Pagination
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.QueryRules
import com.gd.rain.crud.query.QueryShape
import com.gd.rain.crud.query.SchemaField
import com.gd.rain.crud.query.SortKey
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The policy in front of the store: who may, and which rows any operation can reach. */
class CrudResourcePolicyTest {
    private val store = MemoryStore(Books.SCHEMA)
    private val callers = SwitchableCallers()

    private fun resource(policy: ResourcePolicy = Books.policy()) =
        CrudResource(Books.rules(), policy, store, callers, listOf(Books.REVIEWS))

    private fun signIn(
        shelf: String,
        vararg permissions: String,
    ): TestCaller = TestCaller.of(shelf, *permissions).also { callers.caller = it }

    @Test
    fun `no caller is 401 and reaches no row`() {
        val resource = resource()

        assertThat(faultOf { resource.list(emptyMap()) }.kind).isEqualTo(FaultKind.UNAUTHORIZED)
        assertThat(faultOf { resource.get("not-even-an-id", emptyMap()) }.kind).isEqualTo(FaultKind.UNAUTHORIZED)
        assertThat(store.operations).isEmpty()
    }

    @Test
    fun `a caller without the permission is 403 and reaches no row`() {
        signIn("a", Books.WRITE)

        assertThat(faultOf { resource().list(emptyMap()) }.kind).isEqualTo(FaultKind.FORBIDDEN)
        assertThat(store.operations).isEmpty()
    }

    @Test
    fun `the permission is asked before an empty id list is short-cut`() {
        signIn("a", Books.READ)
        val resource = resource()

        assertThat(faultOf { resource.deleteMany(emptySet()) }.kind).isEqualTo(FaultKind.FORBIDDEN)
        assertThat(faultOf { resource.bulkDelete(emptyList()) }.kind).isEqualTo(FaultKind.FORBIDDEN)
        assertThat(faultOf { resource.updateMany(emptySet(), mapOf("pages" to 1)) }.kind).isEqualTo(FaultKind.FORBIDDEN)

        val deleter = signIn("a", Books.DELETE)
        assertThat(resource.bulkDelete(emptyList())).isZero()
        assertThat(deleter.asked).containsExactly(setOf(Books.DELETE))
        assertThat(store.operations).isEmpty()
    }

    @Test
    fun `an action the policy declares no access for is refused to everybody`() {
        signIn("a", *Books.EVERY_PERMISSION.toTypedArray())
        val readOnly = ResourcePolicy(mapOf(Action.READ to ActionAccess.permissions(Books.READ)), ScopeRule.Unrestricted, FieldGrant.All)

        val refused = faultOf { resource(readOnly).create(book("dune", "a", 1, T0)) }

        assertThat(refused.kind).isEqualTo(FaultKind.FORBIDDEN)
        assertThat(refused.message).isEqualTo("this resource declares no access for create")
    }

    @Test
    fun `an action open to every authenticated caller asks for no permission`() {
        val caller = signIn("a")
        val open =
            ResourcePolicy(
                mapOf(Action.READ to ActionAccess.Authenticated("the catalogue is for every member")),
                ScopeRule.Unrestricted,
                FieldGrant.None,
            )
        store.add(book("dune", "a", 1, T0))

        assertThat(resource(open).list(emptyMap()).items).hasSize(1)
        assertThat(caller.asked).isEmpty()
    }

    @Test
    fun `the scope confines every read and every write, bulk ones included`() {
        signIn("a", *Books.EVERY_PERMISSION.toTypedArray())
        val (mine, theirs) = store.add(book("mine", "a", 1, T0), book("theirs", "b", 1, T0))
        val resource = resource(Books.policy(Books.SHELF_SCOPE))

        assertThat(resource.list(emptyMap()).items.map { it["title"] }).containsExactly("mine")
        assertThat(resource.count(emptyMap())).isEqualTo(CappedCount(1, exact = true))
        assertThat(faultOf { resource.get(theirs.toString(), emptyMap()) }.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(faultOf { resource.update(theirs, mapOf("pages" to 9)) }.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(faultOf { resource.update(theirs, emptyMap()) }.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(resource.updateMany(setOf(mine, theirs), mapOf("pages" to 7))).isEqualTo(1)
        assertThat(faultOf { resource.delete(theirs) }.kind).isEqualTo(FaultKind.NOT_FOUND)
        assertThat(resource.deleteMany(setOf(theirs))).isZero()
        assertThat(resource.bulkDelete(listOf(theirs.toString()))).isZero()

        assertThat(store.stored(theirs)).containsEntry("pages", 1)
        assertThat(store.stored(mine)).containsEntry("pages", 7)
        assertThat(store.reads.map { it.scope }).allMatch { it is RowScope.Matching }
    }

    @Test
    fun `a scope that reads a field the resource does not declare is refused`() {
        signIn("a", Books.READ)
        val stranger = SchemaField("shelf", "shelf_code", FieldKind.TEXT, nullable = false)
        val policy = Books.policy(ScopeRule.Rows { Predicate.eq(stranger, "a") })

        assertThatThrownBy { resource(policy).list(emptyMap()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("the scope of books reads fields it does not declare: [shelf]")
    }

    @Test
    fun `a write naming a field that is not writable or not declared is 422, and writes nothing`() {
        signIn("a", *Books.EVERY_PERMISSION.toTypedArray())
        val policy = ResourcePolicy(Books.policy().access, ScopeRule.Unrestricted, FieldGrant.only("title"))

        val refused = faultOf { resource(policy).create(mapOf("title" to "dune", "pages" to 1, "colour" to "red")) }

        assertThat(refused.kind).isEqualTo(FaultKind.VALIDATION)
        assertThat(refused.pointedCodes()).containsExactly("/colour unknown_field", "/pages field_not_granted")
        assertThat(store.operations).isEmpty()
    }

    @Test
    fun `a write value of another kind is a programming error`() {
        signIn("a", *Books.EVERY_PERMISSION.toTypedArray())

        assertThatThrownBy { resource().create(book("dune", "a", 1, T0) + ("pages" to "many")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("a value for books.pages is a Integer, not a String")
    }

    @Test
    fun `a bulk operation names at most maxBulkIds ids, and every malformed id is refused at its index`() {
        signIn("a", *Books.EVERY_PERMISSION.toTypedArray())
        val resource = resource()

        assertThat(faultOf { resource.bulkDelete(List(501) { "x" }) }.pointedCodes()).containsExactly("/ids out_of_range")
        val malformed = faultOf { resource.bulkDelete(listOf("0192f1c0-0000-7000-8000-000000000001", "nope", "1-1-1-1-1")) }
        assertThat(malformed.code.value).isEqualTo("invalid_id")
        assertThat(malformed.pointedCodes()).containsExactly("/ids/1 invalid_id", "/ids/2 invalid_id")
        assertThat(
            faultOf { resource.get("{0192f1c0-0000-7000-8000-000000000001}", emptyMap()) }.pointedCodes(),
        ).containsExactly("/id invalid_id")
    }

    @Test
    fun `a declaration with problems is refused with all of them at once`() {
        val nullableCursor =
            QueryRules(
                listOf(QueryShape.of(SortKey.parse("price"))),
                Books.rules().selectable,
                FieldGrant.only("reviews", "authors"),
                Pagination(10, 50, 100, 10),
            )
        val policy = ResourcePolicy(Books.policy().access, ScopeRule.Unrestricted, FieldGrant.only("title", "colour"))

        assertThatThrownBy { CrudResource(nullableCursor, policy, store, callers, listOf(Books.REVIEWS, Books.REVIEWS)) }
            .isInstanceOf(ConfigurationProblemsException::class.java)
            .matches({ (it as ConfigurationProblemsException).problems.size == 4 }, "names four problems")
            .hasMessageContaining("declares relation reviews more than once")
            .hasMessageContaining("grants colour, which is not a field")
            .hasMessageContaining("grants authors, which is not a relation")
            .hasMessageContaining("shape filters [], sort price sorts by price, which is nullable; a cursor cannot page by it")
    }
}
