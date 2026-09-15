package com.gd.rain.crud

import com.gd.rain.core.actor.Actor
import com.gd.rain.core.error.Fault
import com.gd.rain.crud.query.FieldGrant
import com.gd.rain.crud.query.FieldKind
import com.gd.rain.crud.query.Operator
import com.gd.rain.crud.query.Pagination
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.QueryLimits
import com.gd.rain.crud.query.QueryRules
import com.gd.rain.crud.query.QueryShape
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.crud.query.SchemaField
import com.gd.rain.crud.query.SortKey
import com.gd.rain.crud.query.TableName
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The neutral fixture resource: a `books` table with a field of every kind, a nullable pair and a shelf to scope by. */
object Books {
    val ID = SchemaField("id", "id", FieldKind.UUID, nullable = false)
    val TITLE = SchemaField("title", "title", FieldKind.TEXT, nullable = false)
    val SHELF = SchemaField("shelf", "shelf", FieldKind.TEXT, nullable = false)
    val PAGES = SchemaField("pages", "pages", FieldKind.INT, nullable = false)
    val PRICE = SchemaField("price", "price", FieldKind.DECIMAL, nullable = true)
    val PUBLISHED_ON = SchemaField("publishedOn", "published_on", FieldKind.DATE, nullable = true)
    val CREATED_AT = SchemaField("createdAt", "created_at", FieldKind.TIMESTAMP, nullable = false)
    val AVAILABLE = SchemaField("available", "available", FieldKind.BOOLEAN, nullable = false)
    val ISBN = SchemaField("isbn", "isbn", FieldKind.TEXT, nullable = true)
    val COPIES = SchemaField("copies", "copies", FieldKind.LONG, nullable = false)

    val SCHEMA: ResourceSchema =
        ResourceSchema(
            name = "books",
            table = TableName("public", "books"),
            id = ID,
            fields = listOf(TITLE, SHELF, PAGES, PRICE, PUBLISHED_ON, CREATED_AT, AVAILABLE, ISBN, COPIES),
        )

    const val DDL: String = """
        CREATE TABLE public.books (
            id uuid PRIMARY KEY,
            title text NOT NULL,
            shelf text NOT NULL,
            pages integer NOT NULL,
            price numeric,
            published_on date,
            created_at timestamptz NOT NULL,
            available boolean NOT NULL,
            isbn text,
            copies bigint NOT NULL
        );
        CREATE INDEX books_created_at_id ON public.books (created_at, id);
        CREATE INDEX books_title_id ON public.books (title, id);
        CREATE INDEX books_pages_id ON public.books (pages, id);
        CREATE INDEX books_published_on_id ON public.books (published_on, id);
        CREATE INDEX books_price_null_id ON public.books (id) WHERE price IS NULL;
        CREATE INDEX books_price_not_null_id ON public.books (id) WHERE price IS NOT NULL;
        CREATE INDEX books_shelf_id ON public.books (shelf, id);
        CREATE INDEX books_shelf_created_at_id ON public.books (shelf, created_at, id);
        CREATE INDEX books_shelf_title_id ON public.books (shelf, title, id);
        CREATE INDEX books_shelf_pages_id ON public.books (shelf, pages, id);
        CREATE INDEX books_shelf_published_on_id ON public.books (shelf, published_on, id);
        CREATE INDEX books_shelf_price_null_id ON public.books (shelf, id) WHERE price IS NULL;
        CREATE INDEX books_shelf_price_not_null_id ON public.books (shelf, id) WHERE price IS NOT NULL;
    """

    /**
     * The shapes the books resource answers. [DDL] has an index serving each of them, unscoped and under
     * [SHELF_SCOPE], which `PlanProofAcceptsIndexedShapeIT` proves.
     */
    val SHAPES: List<QueryShape> =
        listOf(
            QueryShape.of(SortKey.NONE),
            QueryShape.of(SortKey.parse("-createdAt")),
            QueryShape.of(SortKey.parse("title")),
            QueryShape.of(SortKey.parse("pages")),
            QueryShape.of(SortKey.NONE, "shelf" to Operator.EQ),
            QueryShape.of(SortKey.parse("-createdAt"), "shelf" to Operator.EQ),
            QueryShape.of(SortKey.parse("-createdAt"), "createdAt" to Operator.GTE),
            QueryShape.of(SortKey.parse("-createdAt"), "createdAt" to Operator.GT),
            QueryShape.of(SortKey.NONE, "title" to Operator.EQ),
            QueryShape.of(SortKey.parse("title"), "title" to Operator.IN),
            QueryShape.of(SortKey.NONE, "publishedOn" to Operator.EQ),
            QueryShape.of(SortKey.NONE, "price" to Operator.IS_NULL),
        )

    val T0: Instant = Instant.parse("2026-09-15T10:00:00Z")

    const val READ = "book.read"
    const val WRITE = "book.write"
    const val DELETE = "book.delete"

    val EVERY_PERMISSION: Set<String> = setOf(READ, WRITE, DELETE)

    /** isbn is not selectable; every other field is. Pass [includable] with the relations the resource declares. */
    fun rules(
        countCap: Long? = 50,
        maxOffset: Long = 1_000,
        limits: QueryLimits = QueryLimits(),
        includable: FieldGrant = FieldGrant.None,
        shapes: List<QueryShape> = SHAPES,
    ): QueryRules =
        QueryRules(
            shapes = shapes,
            selectable = FieldGrant.only("title", "shelf", "pages", "price", "publishedOn", "createdAt", "available", "copies"),
            includable = includable,
            pagination = Pagination(defaultLimit = 10, maxLimit = 50, maxOffset = maxOffset, countCap = countCap),
            limits = limits,
        )

    fun policy(scope: ScopeRule = ScopeRule.Unrestricted): ResourcePolicy =
        ResourcePolicy(
            access =
                mapOf(
                    Action.READ to ActionAccess.permissions(READ),
                    Action.CREATE to ActionAccess.permissions(WRITE),
                    Action.UPDATE to ActionAccess.permissions(WRITE),
                    Action.DELETE to ActionAccess.permissions(DELETE),
                ),
            scope = scope,
            writable = FieldGrant.only("id", "title", "shelf", "pages", "price", "publishedOn", "createdAt", "available", "isbn", "copies"),
        )

    /** Each caller has the shelf named by its actor id. */
    val SHELF_SCOPE: ScopeRule = ScopeRule.Rows { caller -> Predicate.eq(SHELF, caller.actor.id) }

    val REVIEWS: ResourceRelation<Map<String, Any?>> =
        ResourceRelation("reviews") { items -> items.map { it + ("reviews" to listOf("fine")) } }

    fun book(
        title: String,
        shelf: String,
        pages: Int,
        createdAt: Instant,
        price: BigDecimal? = null,
        publishedOn: LocalDate? = null,
        available: Boolean = true,
        isbn: String? = null,
        copies: Long = 1,
        id: UUID? = null,
    ): Map<String, Any?> =
        buildMap {
            id?.let { put("id", it) }
            put("title", title)
            put("shelf", shelf)
            put("pages", pages)
            put("price", price)
            put("publishedOn", publishedOn)
            put("createdAt", createdAt)
            put("available", available)
            put("isbn", isbn)
            put("copies", copies)
        }
}

class TestCaller(
    override val actor: Actor,
    private val permissions: Set<String>,
) : Caller.Authenticated {
    val asked: MutableList<Set<String>> = mutableListOf()

    override fun holdsAll(permissions: Set<String>): Boolean {
        asked += permissions
        return this.permissions.containsAll(permissions)
    }

    companion object {
        fun of(
            shelf: String,
            vararg permissions: String,
        ): TestCaller = TestCaller(Actor("user", shelf), permissions.toSet())
    }
}

class SwitchableCallers(
    @Volatile var caller: Caller = Caller.Anonymous,
) : CallerLookup {
    override fun current(): Caller = caller
}

/** The fault [block] throws; fails when it throws nothing or something else. */
fun faultOf(block: () -> Unit): Fault {
    try {
        block()
    } catch (fault: Fault) {
        return fault
    }
    throw AssertionError("expected a fault, but nothing was thrown")
}

/** `pointer code` of each violation, in the order the fault holds them. */
fun Fault.pointedCodes(): List<String> = violations.map { "${it.pointer} ${it.code.value}" }
