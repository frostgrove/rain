# rain-crud

Declarative resources over an application's own PostgreSQL tables: query dialect v1 on the wire, answered only for the
query shapes a resource declares; row-level policy on every read and write; keyset-first pagination; counts that stop
at a declared cap; problem+json refusals. A plan proof in its test fixtures shows, against the application's database,
that an index serves every statement a declared shape can run.

Add it when an application exposes tables as list, count, item, create, update, replace and delete endpoints and wants
the query and write surface declared, not grown.

## Dependency

```kotlin
dependencies {
    implementation(platform("com.gd.rain:rain-dependencies:0.1.0-SNAPSHOT"))
    implementation("com.gd.rain:rain-crud")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("tools.jackson.module:jackson-module-kotlin")

    testImplementation(testFixtures("com.gd.rain:rain-crud"))
}
```

rain-crud brings [rain-web](web.md) and [rain-persistence](persistence.md), and with them jOOQ, Flyway and the PostgreSQL
driver. The servlet API is only compiled against; the container is the application's choice. The test fixtures bring
[rain-test](test.md).

The query model (`com.gd.rain.crud.query`), the resource (`com.gd.rain.crud`) and the store
(`com.gd.rain.crud.persistence`) are plain Kotlin; only `com.gd.rain.crud.web` knows about servlets.

## What it contributes

`RainCrudAutoConfiguration`, in every role, without conditions:

| Bean | What it is |
|---|---|
| `rainCrudErrorCodes` | the catalog `RainCrudErrorCodes` |

rain-crud declares no resource and mounts no route. A resource is the application's bean, declared where its table is,
and its routes are the application's request mappings.

## Configuration, health checks, schema, commands

None. rain-crud owns no table: every resource reads a table the application migrates.

## Declaring a resource

The examples on this page follow rain-crud's own test fixture, the `books` resource (`Books.kt`), whose shapes and indexes
`PlanProofAcceptsIndexedShapeIT` proves.

### Schema

```kotlin
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

    val SCHEMA =
        ResourceSchema(
            name = "books",
            table = TableName("public", "books"),
            id = ID,
            fields = listOf(TITLE, SHELF, PAGES, PRICE, PUBLISHED_ON, CREATED_AT, AVAILABLE, ISBN, COPIES),
            version = null,
        )
}
```

| Type | Rules |
|---|---|
| `SchemaField(name, column, kind, nullable)` | `name` is the wire name, matched exactly, `^[A-Za-z][A-Za-z0-9_]{0,62}$` — it cannot contain the dialect's `[`, `]`, `,` or a leading `-`; `column` matches `^[A-Za-z_][A-Za-z0-9_]{0,62}$`; nullability is stated, never read from the database |
| `FieldKind` | decides how a wire value is read, which operators apply, and the one Kotlin type a value is carried as (below) |
| `TableName(schema, name)` | both parts stated, each matching the column pattern |
| `ResourceSchema(name, table, id, fields, version)` | `name` matches the field-name pattern; `id` is a non-nullable `UUID` field, which makes it a total tie-break for every order; no field name and no column appears twice; `fields` lists the identifier first; `field(name)` is a map lookup; `version` is `null`, or one of `fields` other than the identifier: a non-nullable `LONG` the store writes — 1 on insert, one more on every update — and a write by identifier states |

| `FieldKind` | Column | Carried as | Ordered (`gt`, `gte`, `lt`, `lte`, sorting) |
|---|---|---|---|
| `TEXT` | `text` | `String` | yes |
| `BOOLEAN` | `boolean` | `Boolean` | no |
| `INT` | `integer` | `Int` | yes |
| `LONG` | `bigint` | `Long` | yes |
| `DECIMAL` | `numeric` | `BigDecimal` | yes |
| `UUID` | `uuid` | `UUID` | no |
| `TIMESTAMP` | `timestamptz` | `Instant` | yes |
| `DATE` | `date` | `LocalDate` | yes |

### Query rules

```kotlin
val RULES =
    QueryRules(
        shapes =
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
            ),
        selectable = FieldGrant.only("id", "title", "shelf", "pages", "price", "publishedOn", "createdAt", "available", "copies"),
        includable = FieldGrant.only("reviews"),
        pagination = Pagination(defaultLimit = 10, maxLimit = 50, maxOffset = 1_000, countCap = 50),
    )
```

`QueryRules(shapes, selectable, includable, pagination, limits = QueryLimits())`:

| Member | What it declares |
|---|---|
| `shapes` | the list queries the resource answers; see [the exact-shape rule](#the-exact-shape-rule) |
| `selectable` | the fields `fields` may name, the identifier included (every item carries the identifier, whatever `fields` names); every sort field of every shape is one of them |
| `includable` | the relations `include` may name |
| `pagination` | how the resource pages and whether it counts |
| `limits` | the bounds of one query |

A `QueryShape(filters, sort)` is a set of `ShapeFilter(field, operator)` and one `SortKey`.
`QueryShape.of(sort, vararg field to operator)` refuses a filter named twice. `SortKey.parse("-createdAt,title")` reads
the `sort` grammar; `SortKey.NONE` is the request without `sort`, whose order is the identifier ascending; an empty text
is refused, and so is a field named twice.

`FieldGrant` has exactly three forms: `None`, `All`, and `Only(names)` with at least one name (`FieldGrant.only(...)`).
An `Only` naming nothing is refused where it is written, because it could not be told apart from either of the other
two.

`Pagination(defaultLimit, maxLimit, maxOffset, countCap)` has no defaults:

| Member | Meaning | Rule |
|---|---|---|
| `defaultLimit` | the page size of a request without `limit` | within `1..maxLimit` |
| `maxLimit` | the largest `limit` served; a larger one is refused, never clamped | within `1..2147483646` |
| `maxOffset` | offset pages are served while `offset + limit ≤ maxOffset`; `0` serves no offset page | not negative |
| `countCap` | `null`: nothing is counted; otherwise a count reads at most `countCap + 1` rows | `null` or within `1..Long.MAX_VALUE − 1` |

`QueryLimits` holds declared tuning numbers, each at least 1:

| Member | Default | Bounds |
|---|---|---|
| `maxFilterTerms` | `16` | `filter[…][…]` parameters in one request, and filters in one shape |
| `maxInValues` | `100` | values of one `in` filter |
| `maxSortTerms` | `4` | terms of one `sort`, in a request and in a shape |
| `maxFields` | `64` | names in `fields` |
| `maxIncludes` | `8` | names in `include` |
| `maxBulkIds` | `500` | identifiers in one bulk update or bulk delete |

### Policy and callers

```kotlin
val POLICY =
    ResourcePolicy(
        access =
            mapOf(
                Action.READ to ActionAccess.permissions("book.read"),
                Action.CREATE to ActionAccess.permissions("book.write"),
                Action.UPDATE to ActionAccess.permissions("book.write"),
                Action.DELETE to ActionAccess.permissions("book.delete"),
            ),
        scope = ScopeRule.Rows { caller -> Predicate.eq(SHELF, caller.actor.id) },
        writable = FieldGrant.only("id", "title", "shelf", "pages", "price", "publishedOn", "createdAt", "available", "isbn", "copies"),
    )
```

| Type | What it is |
|---|---|
| `Action` | `READ` (list, count, item), `CREATE`, `UPDATE` (update, bulk update), `DELETE` (delete, bulk delete) |
| `ActionAccess.Permissions(permissions)` | the caller holds every one; at least one, none empty or containing whitespace; `ActionAccess.permissions(vararg)` |
| `ActionAccess.Authenticated(why)` | any authenticated caller; `why` is not blank |
| `ResourcePolicy(access, scope, writable)` | an action without an entry in `access` is refused to everybody; `writable` is the fields a write may name — never the version, which the store writes |
| `ScopeRule.Unrestricted` | every row |
| `ScopeRule.Rows { caller -> predicate }` | the rows matching the predicate for this caller; it may read only fields the schema declares; every read is confined to them, and every write leaves its row matching the predicate or writes nothing |
| `ScopeRule.EveryRowWhenHolding(permissions, otherwise)` | every row for a caller holding every one of `permissions`; for any other caller, the rows `otherwise` (a `Rows`) answers. Decided per caller, on every operation, by one `holdsAll` question about exactly `permissions`, so one resource and one set of query shapes serve both; `permissions` is at least one, none empty or containing whitespace |
| `CrudResource.scopeOf(caller)` | the `RowScope` the policy gives an authenticated caller: `Everything` or `Matching(predicate)` |
| `Predicate` | `Predicate.eq`, `isNull`, `isNotNull`, `Compare(field, operator, values)`, `AllOf`, `AnyOf` (each of at least two), `allOf`, `anyOf` |
| `CallerLookup` | `current(): Caller` — the caller of the operation on the current thread |
| `Caller.Anonymous`, `Caller.Authenticated` | an authenticated caller has an `actor` and answers `holdsAll(permissions)` |

rain-crud reads no security context. The module that authenticates requests provides the `CallerLookup`. `holdsAll` is
asked about the permissions one operation needs, never for everything a caller holds, so it can be answered with one
bounded lookup.

Every operation of a resource resolves the caller and checks the action's access first — before its identifier, its
query, its body or an empty id list is read — then confines itself to the scope:

| Caller | Answer |
|---|---|
| `Caller.Anonymous` | `401 unauthenticated` |
| authenticated, the action has no entry | `403 forbidden`, detail `this resource declares no access for <action>` |
| authenticated, a permission missing | `403 forbidden` |

Reads, counts, items, updates and deletes, single and bulk, carry the scope in their statement: a row outside it is
`404 not_found`, and a bulk operation neither writes nor counts it. A write that leaves a row behind — a create, an update,
a replacement, a bulk update — leaves it inside the scope: the database compares the row as stored with the scope, in the
write's transaction, and a row outside it is `403 outside_scope` with nothing written (`ScopedCreateIT`).

### Store

`CrudStore<T>` is storage for one resource. It enforces no permission, but every method takes the `RowScope` it is
confined to (`RowScope.Everything` or `RowScope.Matching(predicate)`), writes included, so an unscoped operation has to
say so. A write that leaves a row behind leaves it inside the scope, or writes nothing.

| Member | Contract |
|---|---|
| `itemFields` | the fields every row must carry for the store to make an item; a query whose projection leaves one out is refused before any statement runs |
| `read(RowRead)` | at most `read.limit` rows in `read.order`, each a `KeyedRow` with its item and the values of the order fields |
| `countUpTo(scope, filter, cap)` | `min(count, cap + 1)`, reading at most `cap + 1` rows |
| `find(id, scope, projection)` | the row, or `null` |
| `insert(scope, values)` | `InsertOutcome.Inserted(item)`, or `OutsideScope` with nothing inserted; mints the identifier when `values` does not name one; writes version 1 on a versioned schema |
| `update(id, scope, values, expectedVersion)` | `UpdateOutcome.Updated(item)`, `NotFound`, `StaleVersion` or `OutsideScope`; writes non-empty `values` and, on a versioned schema, the version one higher, only when the row is at `expectedVersion` (`null` exactly when the schema has no version) |
| `updateMany(ids, scope, values)` | `BulkUpdateOutcome.Updated(count)`, or `OutsideScope` with nothing written; no version check, but a versioned row's version still rises |
| `delete(id, scope)`, `deleteMany(ids, scope)` | delete within scope; answer how many rows |

`RowRead(scope, filter, seek, order, limit, offset, projection)` is one bounded read: a non-empty order, `limit` at least
1, `offset` not negative, and a keyset seek only with offset 0 and over exactly the order's fields.

`JooqResourceStore(schema, dsl, ids, reader)` is the store over a table, in typed jOOQ. The table is
`DSL.name(schema, table)`, every column a named field and every value a typed bind, so no text a caller sent becomes
SQL. `readQuery`, `countQuery`, `findQuery`, `insertQuery`, `updateQuery`, `updateManyQuery`, `inScopeQuery`,
`inScopeCountQuery`, `deleteQuery` and `deleteManyQuery` answer the statements it runs:

| Operation | Statements |
|---|---|
| read | `SELECT` the projected and order columns `WHERE` scope, filter and seek `ORDER BY` the order `LIMIT`, and `OFFSET` when it is not 0 |
| count | `SELECT count(*) FROM (SELECT 1 … WHERE scope AND filter LIMIT cap + 1)` |
| find | `SELECT` the projected columns `WHERE id = ? AND` scope |
| insert | `INSERT … RETURNING` every column; under a scope, then `SELECT id WHERE id = ? AND` scope |
| update | `UPDATE … SET` the values (and `version = version + 1`) `WHERE id = ? AND` scope (`AND version = ?`) `RETURNING` every column; under a scope or on a versioned schema, then `SELECT id WHERE id = ? AND` scope — after a write, whether the row is still in the scope; after none, whether it is there at another version |
| bulk update | `UPDATE … WHERE id IN (…) AND` scope `RETURNING id`; under a scope, then `SELECT count(*) WHERE id IN (`the ids written`) AND` scope |
| delete | `DELETE … WHERE id = ? AND` scope |
| bulk delete | `DELETE … WHERE id IN (…) AND` scope |

A write and its check run in one transaction (jOOQ's `transactionResult` on the store's `DSLContext`, a savepoint inside
an application transaction). The check is the database's: the row as stored — with its defaults, and whatever a trigger
wrote — is compared with the same condition a read applies, and a row outside the scope rolls the transaction back. A scope
of every row needs no check, and an unversioned update or an insert under it opens no transaction.

A keyset seek is a row-value comparison, `(created_at, id) < (?, ?)`, when every term of the order has one direction. With
mixed directions it is the expanded comparison led by a non-strict bound on the first column; PostgreSQL bounds such a
scan by the first column only, and the plan proof refuses those cursor pages.

A timestamp is bound as the `OffsetDateTime` in UTC a `timestamptz` takes and read back as an `Instant`.

`RowReader<T>` turns a `Row` into an item and declares `reads`, every field it asks a row for; `RowReader.of(reads) { row ->
… }` builds one. `RowReader.fields(schema)` reads the identifier and answers a map from field name to value holding exactly
the fields the row carries, so a request with `fields` answers those fields and the identifier.

`Row` is read by exact column label:

| Member | Behaviour |
|---|---|
| `string`, `boolean`, `int`, `long`, `decimal`, `uuid`, `instant`, `date` | the value, or `null` for a SQL NULL |
| `requiredString`, … `requiredDate` | the value; `RowShapeException` for a NULL |
| `valueOf(field)` | the value of the field's column as its kind's type |
| `columns`, `has(column)` | the labels the row carries |

A column the statement did not select, a label in another case, and a value of another type all throw
`RowShapeException`; nothing reads as a zero value and nothing is converted. A projection always holds the fields the
reader reads — a query whose `fields` leaves one out is `400 bad_query`, `/fields` `required` — so a request never meets
a reader that throws for a column it left out (`ProjectionFollowsOneRuleTest`).

### Relations

```kotlin
fun reviews(lookup: ReviewLookup): ResourceRelation<Map<String, Any?>> =
    ResourceRelation("reviews") { items ->
        val byBook = lookup.latestFor(items.map { it["id"] as UUID })
        items.map { it + ("reviews" to byBook[it["id"]].orEmpty()) }
    }
```

`ResourceRelation(name, attach)` is a relation `include` may name; the name matches the field-name pattern. `attach`
receives the items of one page — at most its limit — or the one item, and answers them in the same order; an answer of
another size is an `IllegalStateException`. It runs once per page, never per item, and the relations named in `include`
are attached in the order they are named. Keeping the lookup itself bounded is the relation's work.

### The resource

`CrudResource(rules, policy, store, callers, relations)`:

| Member | Behaviour |
|---|---|
| `list(parameters)` | one page, and a capped count when asked |
| `count(parameters)` | a `CappedCount` |
| `get(id, parameters)` | the item; `id` as the wire spells it |
| `create(values)`, `create { input }` | the inserted item |
| `update(id, values)`, `update(id) { input }` | the written item; the values name at least one field |
| `replace(id, values)`, `replace(id) { input }` | the written item; the values state every field of `replaceable` |
| `updateMany(ids, values)` | how many rows were written |
| `delete(id)` | `id` as a `UUID` or as the wire spells it |
| `deleteMany(ids)`, `bulkDelete { input }` | how many rows were deleted; `bulkDelete` reads the ids as the wire spells them |
| `replaceable` | the fields a write by identifier may state: every field `writable` grants except the identifier and the version |

`parameters` is the request's query parameters, each with every value it was given (`CrudMvc.parameters(request)`).
Write values are keyed by field name and carried as their kind's type. An operation that takes its input as a function —
a `WriteInput` (the values and the violations met while reading them from a request) or the bulk ids — calls it only once
the caller is authorized, and reads a wire identifier after the caller too, so a transport hands the resource its request
unread (`UnauthenticatedMalformedBodyIs401Test`).

On a versioned schema the version is one of the values: an update and a replacement state the version they read; a create
and a bulk update never state it.

| Write problem | Answer |
|---|---|
| a name that is not a field | `422 validation_failed`, violation `unknown_field` at `/<name>` |
| a value in a body that does not read | `422`, `invalid_format` at `/<name>` |
| a field `writable` does not grant | `422`, `field_not_granted` at `/<name>`, `is not writable` |
| the identifier in an update or a replacement | `422`, `field_not_granted` at `/id` |
| the version in a create or a bulk update | `422`, `field_not_granted` at the version's name |
| no version, or `null`, in an update or a replacement on a versioned schema | `422`, `required` at the version's name |
| `null` for a field that is not nullable | `422`, `required` at `/<name>` |
| a replacement without a field of `replaceable` | `422`, `required` at `/<name>`; never written as NULL |
| an update or a replacement that states no field | `422`, `required` at the root |
| several of the above | one `422` naming every one |
| a typed value of another type than its kind's | `IllegalArgumentException`: a programming error, `500 internal` |
| no row with the identifier in the caller's scope | `404 not_found` |
| the row is at another version | `409 stale_version` |
| the row as stored would be outside the caller's scope | `403 outside_scope`; nothing is written |
| more ids than `maxBulkIds` | `400 bad_query`, violation `out_of_range` at `/ids`, like every other bound |
| an id in `bulkDelete` that is not canonical | `400 invalid_id`, one violation at `/ids/<index>` each |
| a database refusal | classified by rain-persistence, e.g. `409 unique` ([errors](../concepts/errors.md#data-access)) |

`CrudIds.parse(raw, at)` reads a wire identifier — canonical 8-4-4-4-12 hexadecimal in either case — or refuses it as
`400 invalid_id` at the given path.

### Mounting over HTTP

```kotlin
@Configuration(proxyBeanMethods = false)
class BookResources {
    @Bean
    fun bookStore(
        dsl: DSLContext,
        ids: IdGenerator,
    ): JooqResourceStore<Map<String, Any?>> = JooqResourceStore(Books.SCHEMA, dsl, ids, RowReader.fields(Books.SCHEMA))

    @Bean
    fun mountedBooks(
        store: JooqResourceStore<Map<String, Any?>>,
        callers: CallerLookup,
        lookup: ReviewLookup,
    ): MountedResource<Map<String, Any?>> =
        MountedResource("/books", CrudOperation.entries.toSet(), CrudResource(Books.RULES, Books.POLICY, store, callers, listOf(reviews(lookup))))
}

@RestController
@RequestMapping("/books")
class BookController(
    private val books: MountedResource<Map<String, Any?>>,
) : DeclaresItsOwnAccess {
    @GetMapping
    fun list(request: HttpServletRequest): PageBody<Map<String, Any?>> = CrudMvc.list(books.resource, request)

    @GetMapping("/count")
    fun count(request: HttpServletRequest): CountBody = CrudMvc.count(books.resource, request)

    @GetMapping("/{id}")
    fun get(
        @PathVariable("id") id: String,
        request: HttpServletRequest,
    ): Map<String, Any?> = CrudMvc.get(books.resource, id, request)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody(required = false) body: String?,
    ): Map<String, Any?> = CrudMvc.create(books.resource, body)

    @PatchMapping("/{id}")
    fun update(
        @PathVariable("id") id: String,
        @RequestBody(required = false) body: String?,
    ): Map<String, Any?> = CrudMvc.update(books.resource, id, body)

    @PutMapping("/{id}")
    fun replace(
        @PathVariable("id") id: String,
        @RequestBody(required = false) body: String?,
    ): Map<String, Any?> = CrudMvc.replace(books.resource, id, body)

    @DeleteMapping("/{id}")
    fun delete(
        @PathVariable("id") id: String,
    ): DeletedBody = CrudMvc.delete(books.resource, id)

    @PostMapping("/bulk-delete")
    fun bulkDelete(
        @RequestBody(required = false) body: String?,
    ): DeletedBody = CrudMvc.bulkDelete(books.resource, body)

    override fun accessDeclarations(): List<EndpointDeclaration> = books.declarations()
}
```

`MountedResource(prefix, operations, resource)` is the one table a resource's routes and their access declarations come
from. `routes()` lists them in `CrudOperation` order, which puts `/count` before `/{id}`. `declarations()` derives each
route's `EndpointDeclaration` from the policy the resource enforces — the permissions, or authenticated with the policy's
`why` — so a permission is written once.

| `CrudOperation` | Route | Action | Handler |
|---|---|---|---|
| `CREATE` | `POST <prefix>` | create | `CrudMvc.create(resource, body)`: the inserted item; the controller states `201` |
| `BULK_DELETE` | `POST <prefix>/bulk-delete` | delete | `CrudMvc.bulkDelete(resource, body)`: the body is exactly `{"ids":[…]}` with string ids, duplicate keys refused; anything else is `400 malformed_body` |
| `COUNT` | `GET <prefix>/count` | read | `CrudMvc.count(resource, request)` |
| `LIST` | `GET <prefix>` | read | `CrudMvc.list(resource, request)` |
| `GET` | `GET <prefix>/{id}` | read | `CrudMvc.get(resource, id, request)` |
| `UPDATE` | `PATCH <prefix>/{id}` | update | `CrudMvc.update(resource, id, body)`: writes the fields the body names |
| `REPLACE` | `PUT <prefix>/{id}` | update | `CrudMvc.replace(resource, id, body)`: states every field of `replaceable`; an absent one is refused, never written as NULL |
| `DELETE` | `DELETE <prefix>/{id}` | delete | `CrudMvc.delete(resource, id)` |

A write body (`CrudBodies.write`) is one JSON object whose members are field names. A body that is not JSON, not one
object, names a member twice or has anything after the object is `400 malformed_body`. Each value is JSON `null` or the
JSON type of its field's kind, spelled as [query dialect v1](#values) spells the kind:

| Kind | JSON value |
|---|---|
| `TEXT` | a string |
| `BOOLEAN` | `true` or `false` |
| `INT`, `LONG` | a number without fraction or exponent, within the kind's range: `1.0` is refused |
| `DECIMAL` | a number with an optional fraction and no exponent: `1e3` is refused |
| `UUID`, `TIMESTAMP`, `DATE` | a string in the dialect's spelling |

Refusals are `Fault`s and render as problem+json through rain-web's exception handler. Every `CrudMvc` helper hands the
resource its input unread, so an anonymous caller is `401` and a caller without the permission `403` whatever body or
identifier it sent.

## Declaration problems

A `CrudResource` and a `MountedResource` check their declaration when they are constructed — for a bean, when the context
starts — and throw `ConfigurationProblemsException` naming every problem at once:

| Path | Code | When |
|---|---|---|
| `crud:<resource>.relations` | `contradicts` | a relation name declared more than once |
| `crud:<resource>.writable` | `invalid` | `writable` grants a name that is not a field |
| `crud:<resource>.writable` | `contradicts` | `writable` grants the version, which the store writes |
| `crud:<resource>.reader` | `invalid` | the store's reader reads a name that is not a field |
| `crud:<resource>.reader` | `contradicts` | `selectable` is not `None` and does not grant a field other than the identifier the reader reads, so no `fields` selection could be answered |
| `crud:<resource>.selectable` | `invalid` | `selectable` grants a name that is not a field |
| `crud:<resource>.includable` | `invalid` | `includable` grants a name that is not a relation |
| `crud:<resource>.shapes` | `contradicts` | a shape declared more than once; the same filters in another order are the same shape |
| `crud:<resource>.shapes` | `invalid` | a shape that filters by a name that is not a field, applies an operator the field does not take, has more filters than `maxFilterTerms`, sorts by more terms than `maxSortTerms`, or sorts by a name that is not a field, by a nullable field (a cursor cannot page by it), or by a field `selectable` does not grant (a cursor carries its values) |
| `crud:<resource>.mount` | `invalid` | a prefix that does not match `^(/[A-Za-z0-9._~-]+)+$`, or no operation |
| `crud:<resource>.mount` | `contradicts` | an operation whose action the policy declares no access for; `LIST` or `COUNT` on a resource without shapes; `COUNT` without a count cap; `UPDATE` or `REPLACE` on a resource whose `replaceable` is empty |

A malformed single value — a field or column name, a `Pagination` or `QueryLimits` number outside its range, an empty
`FieldGrant.Only`, a repeated sort term, a version that is not a non-nullable `LONG` field of the schema, a
`JooqResourceStore` reader reading a field its schema does not declare — is an `IllegalArgumentException` where it is
written. A scope predicate that
reads a field the schema does not declare is an `IllegalStateException` on the first operation that applies it.

Which indexes a shape needs is not a declaration problem: the [plan proof](#the-plan-proof) decides it against the
database.

## The exact-shape rule

A list request is served by the declared shape whose filters are exactly the request's `filter[<field>][<op>]`
parameters and whose sort is exactly the request's `sort`. More filters, another operator, a subset of a shape's filters,
or another sort is not that shape. There is no nearest shape: any other request is `400 not_offered`, and the detail names
what was asked, never a shape that would have been served:

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"no query shape of this resource is filters [filter[shelf][eq]], sort title","code":"not_offered"}
```

A count — `count=capped` on a list, or `GET /count` — is served when a declared shape has exactly the request's filters,
whatever that shape's sort: a count has no order, so every such shape states the same count. Otherwise:

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"no query shape of this resource has the filters [filter[isbn][eq]]","code":"not_offered"}
```

A shape states no scope; every statement carries the caller's. Declaring a shape is declaring that an index serves it
under the scopes the application uses. Finding a request's shape is one hash lookup, whatever the number of shapes.

## Query dialect v1

The query parameters every resource reads, `DialectV1.VERSION` 1. Names are compared exactly: no aliases, no near-miss
matching, no case folding.

| Parameter | List | Count | Item | Meaning |
|---|---|---|---|---|
| `filter[<field>][<op>]` | yes | yes | refused | one filter; repeated only to give `in` several values |
| `sort` | yes | refused | refused | `name` ascending or `-name` descending, comma-separated, each field once |
| `limit` | yes | refused | refused | the page size, `1..maxLimit`; absent serves `defaultLimit` |
| `offset` | yes | refused | refused | selects an offset page; not with `cursor` |
| `cursor` | yes | refused | refused | continues a cursor page |
| `fields` | yes | refused | yes | comma-separated names `selectable` grants; the identifier is always returned |
| `include` | yes | refused | yes | comma-separated relation names |
| `count` | yes | yes | refused | the one value `capped` |

Any other parameter — `search`, `page`, `Limit`, `filter[title]` — is `400 unknown_parameter`, with one violation per
name. A scalar parameter given more than once is `400 bad_query`.

### Operators

| Operator | Values | Applies to |
|---|---|---|
| `eq` | one | every kind |
| `gt`, `gte`, `lt`, `lte` | one | the ordered kinds: `TEXT`, `INT`, `LONG`, `DECIMAL`, `TIMESTAMP`, `DATE` |
| `in` | one per repetition of the parameter, at most `maxInValues`; a comma is part of a value | every kind |
| `isnull` | one: `true` matches NULL, `false` matches a value | nullable fields only; on a column that cannot hold NULL it would be a constant |

A comparison follows SQL: it never matches a NULL column value; only `isnull` does. A request's filters are conjoined.

### Values

Each kind has exactly one accepted spelling; nothing is tried in turn and no zone is assumed.

| Kind | Spelling | Refused, for example |
|---|---|---|
| `TEXT` | the value as sent, the empty text included | — |
| `BOOLEAN` | `true`, `false` | `TRUE`, `1`, `yes` |
| `INT`, `LONG` | an optional `-` and decimal digits without leading zeros, within the kind's range | `01`, `+1`, `1.0`, `1e3`, `2147483648` for `INT` |
| `DECIMAL` | the same, with an optional fraction; no exponent | `.5`, `5.`, `01.5`, `1e3`, `NaN` |
| `UUID` | canonical 8-4-4-4-12 hexadecimal, either case | `{…}`, 32 digits without hyphens |
| `TIMESTAMP` | RFC 3339 `date-time`: seconds, an optional fraction of up to nine digits, and `Z` or `±hh:mm`; `T` and `Z` in either case | `2026-09-15` (a date), `2026-09-15T10:00:00` (no offset), `2026-09-15 10:00:00Z`, `2026-09-15T10:00Z`, `+0200` |
| `DATE` | `yyyy-mm-dd` | `1965-8-1`, `19650801`, a date-time |

`isnull` reads its value as `BOOLEAN`.

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"the query could not be read","code":"bad_query","errors":[{"pointer":"/filter/createdAt/gte","code":"invalid_format","message":"the value has no offset; a timestamp ends in Z or ±hh:mm"}]}
```

### Order

The effective order of a page is the shape's sort followed by the identifier in the direction of the sort's last term —
ascending when there is no sort — unless the sort already names the identifier: `-createdAt` pages by
`createdAt DESC, id DESC`, `title,-pages` by `title ASC, pages DESC, id DESC`. Rows that share every sort value still page
once each, and a row written between two pages is neither skipped nor repeated.

### Pages

Without `offset` a page is a cursor page.

| Page | Reads | `next` | `prev` |
|---|---|---|---|
| first cursor page | `limit + 1` rows in the effective order | from the last row kept, when a row was left over | none |
| after a `next` cursor | `limit + 1` rows after the cursor's row | from the last row kept, when a row was left over | from the first row kept |
| before a `prev` cursor | `limit + 1` rows after the cursor's row in the inverted order, nearest first; the `limit` nearest are kept and put back in the page's order | from the last row kept | from the first row kept, when a row was left over |
| offset page | `limit + 1` rows past `offset` in the effective order | `hasNext` when a row was left over | — |

An empty page past either end offers neither cursor. Offset pages are served while `offset + limit ≤ maxOffset`, computed
in `Long`; the default limit counts toward it, and there is no `page` parameter to reach further.

Cursor format v1 is unpadded base64url of `{"v":1,"sig":…,"dir":"next"|"prev","k":[…]}`. `sig` is the unpadded base64url
SHA-256 of the effective order written as `field:asc|desc` terms joined by `,`; `k` holds the boundary row's order values,
each as its kind's canonical string (an `Instant` or `LocalDate` in ISO form, a decimal in plain notation). Decoding is
strict: exactly those four members, no duplicate member, and every key re-encodes to the text it was read from. A cursor
presented with another sort is refused, because it was made for other columns.

A cursor is readable, and a caller can rewrite its keys. Its keys are values of sort fields, and every sort field of every
shape is selectable, so a cursor shows nothing the caller could not select. A rewritten cursor only moves where a page
starts: the page is still read within the caller's scope, the request's filters and its `fields`
(`TamperedCursorNeverWidensReadIT`).

### Count

`count=capped` on a list adds a count to the page; `GET /count` answers only the count. Both need a declared `countCap`.
The count reads at most `cap + 1` rows of the scope and the filters: up to the cap it is exact, beyond it the answer is the
cap with `exact: false`, which reads "more than". A list without `count` runs no count statement.

| Rows matching | `countCap` | Answer |
|---|---|---|
| 7 | 10 | `{"value":7,"exact":true}` |
| 10 | 10 | `{"value":10,"exact":true}` |
| 11 | 10 | `{"value":10,"exact":false}` |

### Refusals

A request is refused in this order, and each step names every problem it finds: the caller's access; parameter names
(`unknown_parameter`); the query (`bad_query`, every violation together); its shape (`not_offered`); its cursor
(`invalid_cursor`). All are `400` except the caller's.

| Problem | Fault code | Violation pointer and code |
|---|---|---|
| a parameter outside the dialect | `unknown_parameter` | `/<name>` `unknown_parameter` |
| a scalar parameter given more than once | `bad_query` | `/<name>` `bad_query` |
| `limit` is not digits | `bad_query` | `/limit` `invalid_format` |
| `limit` is 0, above `maxLimit` or beyond `Int` | `bad_query` | `/limit` `out_of_range` |
| `offset` with `cursor` | `bad_query` | `/offset` `bad_query` |
| `offset` is not digits | `bad_query` | `/offset` `invalid_format` |
| `offset + limit` above `maxOffset` | `bad_query` | `/offset` `out_of_range` |
| more filter parameters than `maxFilterTerms` | `bad_query` | `/filter` `out_of_range` |
| a filter on a name that is not a field | `bad_query` | `/filter/<field>/<op>` `unknown_field` |
| an operator the dialect does not have, `EQ` and `ne` included | `bad_query` | `/filter/<field>/<op>` `unknown_operator` |
| an operator the field does not take | `bad_query` | `/filter/<field>/<op>` `bad_query` |
| `in` with more than `maxInValues` values | `bad_query` | `/filter/<field>/in` `out_of_range` |
| another operator with more than one value | `bad_query` | `/filter/<field>/<op>` `bad_query` |
| a value that does not read | `bad_query` | `/filter/<field>/<op>` `invalid_format`; `/filter/<field>/in/<index>` for `in` |
| `sort`, `fields` or `include` malformed | `bad_query` | `/sort`, `/fields`, `/include` `bad_query` |
| more than `maxSortTerms`, `maxFields` or `maxIncludes` | `bad_query` | the parameter, `out_of_range` |
| `sort` or `fields` names no field; `include` names no relation | `bad_query` | the parameter, `unknown_field` |
| `fields` names a field `selectable` does not grant, the identifier included; `include` a relation `includable` does not | `bad_query` | the parameter, `field_not_granted` |
| `fields` leaves out a field the store's reader reads (`CrudStore.itemFields`) | `bad_query` | `/fields` `required`, one per field |
| `count` other than `capped` | `bad_query` | `/count` `invalid_format` |
| `count=capped`, or the count route, without a count cap | `bad_query` | `/count` `not_offered` |
| a parameter the operation has no use for (see the table above) | `bad_query` | the parameter, `bad_query` |
| no declared shape is the request | `not_offered` | — |
| a cursor that cannot be read or was made for another order | `invalid_cursor` | `/cursor` `invalid_cursor` |
| an identifier in a path that is not canonical | `invalid_id` | `/id` `invalid_id` |

```json
{"type":"about:blank","title":"Bad Request","status":400,"detail":"the request names a parameter that does not exist","code":"unknown_parameter","errors":[{"pointer":"/page","code":"unknown_parameter","message":"is not a parameter of query dialect v1"},{"pointer":"/utm_source","code":"unknown_parameter","message":"is not a parameter of query dialect v1"}]}
```

### What v1 does not have

The operators are exactly `eq`, `gt`, `gte`, `lt`, `lte`, `in` and `isnull`: each is one a b-tree index can serve as a
bound on an ordered scan, so every shape can be backed by an index that the plan proof accepts. Operators PostgreSQL 18
can never serve that way are not part of the dialect:

| Not in v1 | Why no index bounds it |
|---|---|
| `ne`, `nin` | a b-tree has no index condition for an inequality, so it stays a `Filter`; a GiST condition on btree_gist gives no order a page needs |
| `contains`, `icontains`, `startswith`, `istartswith`, `endswith`, `iendswith` | a b-tree keeps a pattern as a `Filter`, even over `text_pattern_ops`; a trigram GIN index is read through a bitmap and a trigram GiST index is not a b-tree and gives no order |
| the `search` parameter | a disjunction of those patterns over several fields, with the same plans |

`DroppedOperatorsNeverBoundedIT` holds the evidence. It renders each of these conditions over a table that carries every
index that could serve it — b-trees, `text_pattern_ops` b-trees (plain and lower-cased), trigram GIN and GiST indexes
(plain and lower-cased) and a btree_gist index — and explains a page in identifier order, a page in the filtered field's
order and a capped count on PostgreSQL 18: plan criterion v3 accepts none of them, while `eq`, `gte` and `in` over the same
table are bounded. A request with one of these operators is `unknown_operator`; `search` is `unknown_parameter`.

## Responses

The bodies below are the ones `CrudHttpTest` asserts, rendered by the application's HTTP mapper. The page and count
bodies are `PageBody`, `CursorPageBody`, `OffsetPageBody`, `CountValueBody`, `CountBody` and `DeletedBody`, with their
keys in this order.

A first cursor page, `GET /books?sort=-createdAt&limit=2`, over three rows:

```json
{
  "items": [
    {"id":"0192f1c0-0000-7000-8000-000000000003","title":"t2","shelf":"a","pages":2,"price":null,"publishedOn":null,"createdAt":"2026-09-15T10:00:02Z","available":true,"isbn":null,"copies":1},
    {"id":"0192f1c0-0000-7000-8000-000000000002","title":"t1","shelf":"a","pages":1,"price":null,"publishedOn":null,"createdAt":"2026-09-15T10:00:01Z","available":true,"isbn":null,"copies":1}
  ],
  "page": {
    "limit": 2,
    "next": "eyJ2IjoxLCJzaWciOiJmdE5jVVhybURPaVBKcnliUTRlUGlreHpWMlUxYkZVbmF4R2dGd3FsN3FBIiwiZGlyIjoibmV4dCIsImsiOlsiMjAyNi0wOS0xNVQxMDowMDowMVoiLCIwMTkyZjFjMC0wMDAwLTcwMDAtODAwMC0wMDAwMDAwMDAwMDIiXX0"
  }
}
```

The `next` cursor decodes to
`{"v":1,"sig":"ftNcUXrmDOiPJrybQ4ePikxzV2U1bFUnaxGgFwql7qA","dir":"next","k":["2026-09-15T10:00:01Z","0192f1c0-0000-7000-8000-000000000002"]}`.
The page it leads to holds `t0` and no `next`:

```json
{
  "items": [
    {"id":"0192f1c0-0000-7000-8000-000000000001","title":"t0","shelf":"a","pages":0,"price":null,"publishedOn":null,"createdAt":"2026-09-15T10:00:00Z","available":true,"isbn":null,"copies":1}
  ],
  "page": {
    "limit": 2,
    "prev": "eyJ2IjoxLCJzaWciOiJmdE5jVVhybURPaVBKcnliUTRlUGlreHpWMlUxYkZVbmF4R2dGd3FsN3FBIiwiZGlyIjoicHJldiIsImsiOlsiMjAyNi0wOS0xNVQxMDowMDowMFoiLCIwMTkyZjFjMC0wMDAwLTcwMDAtODAwMC0wMDAwMDAwMDAwMDEiXX0"
  }
}
```

An offset page, `GET /books?sort=pages&offset=1&limit=1`:

```json
{"items":[{"id":"0192f1c0-0000-7000-8000-000000000002","title":"t1","shelf":"a","pages":1,"price":null,"publishedOn":null,"createdAt":"2026-09-15T10:00:01Z","available":true,"isbn":null,"copies":1}],"page":{"limit":1,"offset":1,"hasNext":true}}
```

A counted page, `GET /books?filter[shelf][eq]=a&count=capped`, adds `count` after `page`:

```json
{"items":[…],"page":{"limit":10},"count":{"value":3,"exact":true}}
```

The count route, `GET /books/count?filter[shelf][eq]=a`, and `GET /books/count` over 63 rows with a cap of 50:

```json
{"count":{"value":3,"exact":true}}
{"count":{"value":50,"exact":false}}
```

An item, `GET /books/{id}?include=reviews`:

```json
{"id":"0192f1c0-0000-7000-8000-000000000001","title":"dune","shelf":"a","pages":412,"price":12.50,"publishedOn":"1965-08-01","createdAt":"2026-09-15T10:00:00Z","available":true,"isbn":null,"copies":1,"reviews":["fine"]}
```

`DELETE /books/{id}` answers `{"deleted":1}`, and the same delete again is `404 not_found`.
`POST /books` with a write body answers `201` and the inserted item; `PATCH /books/{id}` with `{"pages":500}` answers the
whole written item, and `PUT /books/{id}` the same for a body stating every writable field. A refused write body names
every problem, e.g. `422 validation_failed` with `/colour` `unknown_field`, `/copies` `invalid_format` and `/shelf`
`required` together (`CrudWriteHttpTest`).

`POST /books/bulk-delete` with `{"ids":["…","…"]}` answers how many rows in the caller's scope were deleted:

```json
{"deleted":2}
```

## The plan proof

The pagination bounds the rows a statement returns; the shapes and the application's indexes bound the rows it examines.
`CrudPlanProof`, version 2, in rain-crud's test fixtures, explains every statement the store runs for each mounted
operation of a resource — its declared shapes' pages and counts, and its statements by identifier — against the
application's database under every stated scope, and judges each plan by criterion v3 of rain-test's
[`QueryPlan.boundedScan`](test.md#plan-criterion-v3) over the resource's schema-qualified table.

### Running it

An application runs the proof in its own integration tests, against a fresh database migrated with its real migrations,
before any row is written:

```kotlin
@Tag("integration")
class BooksPlanProofIT {
    @Test
    fun `every declared shape of books is bounded by its indexes`() {
        val dataSource = RainPostgres.freshDatabase("books_proof").dataSource()
        Flyway.configure().dataSource(dataSource).load().migrate()
        val store = JooqResourceStore(Books.SCHEMA, DSL.using(dataSource, SQLDialect.POSTGRES), UuidV7Ids, RowReader.fields(Books.SCHEMA))
        val books = CrudResource(Books.RULES, Books.POLICY, store, CallerLookup { Caller.Anonymous }, emptyList())

        val result =
            CrudPlanProof.verify(
                store,
                MountedResource("/books", CrudOperation.entries.toSet(), books),
                listOf(
                    ProofScope("everything", RowScope.Everything),
                    ProofScope("shelf a", RowScope.Matching(Predicate.eq(Books.SHELF, "a"))),
                ),
                dataSource,
            )

        result.assertBounded()
    }
}
```

`verify(store, mounted, scopes, dataSource)` takes the resource's own `JooqResourceStore`, the `MountedResource` the
application serves — the proof explains exactly the statements of its mounted operations — and at least one
`ProofScope(name, scope)` — typically one per scope rule, for a
representative caller — each named once. The caller lookup is never asked. `resource.scopeOf(caller)` answers the scope the policy gives a caller, so a
proof states exactly the scopes its policy yields: for `EveryRowWhenHolding`, one for a caller holding the permissions and
one for a caller who does not (`PlanProofUnderEveryRowWhenHoldingIT`).

| Result | What it holds |
|---|---|
| `PlanProofResult.statements` | every `ProvenStatement`: shape (`null` for a statement by identifier), scope name, `StatementKind`, the values it was rendered with, the SQL |
| `PlanProofResult.findings` | every `PlanFinding`: the statement, every reason criterion v3 gives, the plan JSON |
| `assertBounded()` | fails with `<n> of <m> statements are not bounded by their plans:` and every finding |

```
shape filters [], sort copies | scope everything | FIRST_PAGE | no filter
  - Sort is between … and any Limit above it
  sql: select …
  plan: […]
```

### Precondition

The proof is evaluated only when the resource's table exists, holds no row and carries no planner statistics: never
vacuumed or analysed (`reltuples` is negative), no column statistics, no extended statistics. Otherwise it throws
`PlanProofNotEvaluatedException` naming every reason:

```
the plan proof was not evaluated: table public.books has been vacuumed or analysed (reltuples = 1.0); has column statistics; holds rows; the proof explains against an empty, never-analysed table so that the plans depend on the indexes alone
```

With statistics PostgreSQL chooses among the paths the indexes offer by the data and each statement's constants, so the
same indexes would be judged differently on different data. Without them the chosen plan shows whether the indexes offer
a bounded path — which is what the proof states. It does not state which plan the server chooses for a given request on
production data. A migration that seeds rows into the table makes the proof not evaluated.

### What it enumerates

First, for every declared shape (declaration order) × stated scope (stated order) × `StatementKind` (enum order) × value
variant, the proof renders the statement the resource runs — through `PageReads`, `JooqResourceStore.readQuery` and
`countQuery`, which `CrudResource` uses too — with its values inlined, and explains it with `QueryPlans.explain`. Then,
for every stated scope × `StatementKind` × variant, it renders the statements addressed by identifier through the store's
`findQuery`, `insertQuery`, `updateQuery`, `inScopeQuery`, `deleteQuery` and `deleteManyQuery`, keyed by the
representative `UUID` at ordinal 0 (a bulk list by ordinals 0, 1, …).

| `StatementKind` | Statement | When |
|---|---|---|
| `FIRST_PAGE` | the first cursor page of `maxLimit` rows | `LIST` mounted |
| `SEEK_FORWARD` | the cursor page after a row | `LIST` mounted |
| `SEEK_BACKWARD` | the cursor page before a row, read nearest-first in the inverted order | `LIST` mounted |
| `OFFSET_PAGE` | the page of `min(maxLimit, maxOffset)` rows ending at `maxOffset` | `LIST` mounted, `maxOffset ≥ 1` |
| `CAPPED_COUNT` | the capped count | `LIST` or `COUNT` mounted, a count cap declared |
| `ITEM` | one item by identifier | `GET` mounted |
| `INSERT` | the insert of the identifier and every other field `writable` grants | `CREATE` mounted |
| `UPDATE` | the update by identifier of every field of `replaceable`, stating version 1 on a versioned resource | `UPDATE` or `REPLACE` mounted |
| `ROW_IN_SCOPE` | `SELECT id WHERE id = ? AND` scope | `CREATE` mounted under a scope; `UPDATE` or `REPLACE` mounted under a scope or on a versioned resource |
| `DELETE` | the delete by identifier | `DELETE` mounted |
| `BULK_DELETE` | the delete of one identifier and of `maxBulkIds` identifiers | `BULK_DELETE` mounted |

The value variants of a shape are every combination of each filter's variants:

| Filter | Variants |
|---|---|
| `eq`, `gt`, `gte`, `lt`, `lte` | one value |
| `in` | one value (PostgreSQL plans a one-element list as an equality) and, when `maxInValues > 1`, `maxInValues` values |
| `isnull` | `true` and `false`, its whole domain |

A value is `RepresentativeValues.of(kind, ordinal)` at ordinal 0, then 1, 2 … along an `in` list; a seek is keyed by the
ordinal-0 value of each order field. An `eq` filter, the first value of an `in` filter and a written field instead take
the value the stated scope pins the same field to — an `eq`, or an `in` with one value, among the scope's top-level conjuncts — because
two constants equated to one column make PostgreSQL plan a statement that reads nothing, which no request runs.

| Kind | Value at ordinal *n* |
|---|---|
| `TEXT` | `rain-proof-<n>` |
| `BOOLEAN` | `true` at even ordinals, `false` at odd |
| `INT`, `LONG`, `DECIMAL` | `1 + n` (scale 0) |
| `UUID` | `00000000-0000-7000-8000-` followed by `n + 1` as 12 hexadecimal digits |
| `TIMESTAMP` | `2000-01-01T00:00:00Z` plus *n* seconds |
| `DATE` | `2000-01-01` plus *n* days |

The books resource declares 12 shapes; `filter[title][in]` and `filter[price][isnull]` have two variants each, so each
scope has 14 variants × 5 kinds, and the two scopes 140 statements. Mounted with every operation, `everything` adds six
statements by identifier and `shelf a` seven — its create and update also check the row — 153 in all
(`PlanProofCoversEveryMountedStatementIT`).

### What index a shape needs

Criterion v3 accepts a page when the table is read by a b-tree index scan with no `Filter`, every index condition bounds
the scanned range, and the `Limit` sits directly above the scan with no `Sort` between them. For a shape under a scope that
means one index whose key is:

1. **the equality columns first** — the fields the scope pins and the shape's `eq` filters, in any order among
   themselves;
2. **then the order columns** — the shape's sort terms in order; a range filter (`gt`, `gte`, `lt`, `lte`) is on the
   first of them, because a range on a later key column, after one without an equality, is checked against every entry
   instead of ending the scan;
3. **the identifier last**, since the effective order ends with it.

A b-tree scans backward, so an ascending index serves a sort whose terms are all descending. A sort whose terms mix
directions has cursor pages no index bounds: the seek is the expanded comparison, of which the index bounds only the
first column (`PlanProofMixedDirectionIT` finds `SEEK_FORWARD` and `SEEK_BACKWARD` even over an index in exactly that
order). `IS NULL` is an equality for the criterion and `IS NOT NULL` is not, so the fixture serves an `isnull` filter
with a pair of partial indexes, one per value. A filter on a column no index holds is a `Filter` in every statement, and
an order no index serves is a `Sort` under the `Limit` of every page (`PlanProofRejectsUnindexedFilterIT`).

The books table and its indexes:

```sql
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
```

| Shape | Key for `everything` | Key for `shelf a` |
|---|---|---|
| no filter, no sort | `(id)`, the primary key | `(shelf, id)` |
| `sort=-createdAt` | `(created_at, id)` | `(shelf, created_at, id)` |
| `sort=title` | `(title, id)` | `(shelf, title, id)` |
| `sort=pages` | `(pages, id)` | `(shelf, pages, id)` |
| `filter[shelf][eq]` | `(shelf, id)` | `(shelf, id)` |
| `filter[shelf][eq]`, `sort=-createdAt` | `(shelf, created_at, id)` | `(shelf, created_at, id)` |
| `filter[createdAt][gte]` or `[gt]`, `sort=-createdAt` | `(created_at, id)` | `(shelf, created_at, id)` |
| `filter[title][eq]` | `(title, id)` | `(shelf, title, id)` |
| `filter[title][in]`, `sort=title` | `(title, id)` | `(shelf, title, id)` |
| `filter[publishedOn][eq]` | `(published_on, id)` | `(shelf, published_on, id)` |
| `filter[price][isnull]` | `(id) WHERE price IS NULL` and `(id) WHERE price IS NOT NULL` | `(shelf, id)` with the same two predicates |

Statements by identifier need no index beyond the primary key. Criterion v3 bounds a scan whose conditions pin a unique
key: `WHERE id = ? AND shelf = 'a'` reads at most one entry even through `books_shelf_id (shelf, id)`, which PostgreSQL
prefers to the primary key, and `id IN (…)` of `maxBulkIds` identifiers reads at most that many. A table whose identifier
has no unique index makes every statement by identifier that reads it a finding.

Worked through for `filter[shelf][eq]` with `sort=-createdAt` under the scope `shelf a`, where `maxLimit` is 50,
`maxOffset` 1000 and the count cap 50. The effective order is `created_at DESC, id DESC`; the scope and the pinned filter
both compare `shelf = 'a'`:

| Statement | Conditions besides `shelf = 'a'` | Order | Rows |
|---|---|---|---|
| `FIRST_PAGE` | — | `created_at DESC, id DESC` | `LIMIT 51` |
| `SEEK_FORWARD` | `(created_at, id) < ('2000-01-01T00:00:00Z', '00000000-0000-7000-8000-000000000001')` | `created_at DESC, id DESC` | `LIMIT 51` |
| `SEEK_BACKWARD` | `(created_at, id) > ('2000-01-01T00:00:00Z', '00000000-0000-7000-8000-000000000001')` | `created_at ASC, id ASC` | `LIMIT 51` |
| `OFFSET_PAGE` | — | `created_at DESC, id DESC` | `OFFSET 950 LIMIT 51` |
| `CAPPED_COUNT` | — | — | `count(*)` over `LIMIT 51` |

Over `books_shelf_created_at_id (shelf, created_at, id)` the index condition is `shelf = 'a'`, an equality on the first
key column, and the row comparison, which covers the next two consecutive key columns after that equality: every clause
bounds the range (rule 3). The scan returns rows in the page's order, forward or backward, so nothing sorts between it and
the `Limit` (rule 4), and the `Limit` runs once (rule 5). The rows a page examines are at most 51, or 1001 for the deepest
offset page — whether the table holds ten rows or ten million. Without `books_shelf_created_at_id` the plan either filters
`shelf` or sorts by `created_at`, and the proof names the statement, the node and the SQL.

## Error codes

`RainCrudErrorCodes` (owner `rain-crud`):

| Code | Default message | Answered for |
|---|---|---|
| `field_not_granted` | this field may not be used this way | a violation: `fields`, `include` or a write naming what the resource does not grant |
| `unknown_operator` | this is not an operator of the query dialect | a violation at `/filter/<field>/<op>` |
| `invalid_cursor` (rain-core) | the page cursor could not be read | `400`, with a violation at `/cursor` naming why |
| `not_offered` | this resource does not offer that | `400` for a request no declared shape is; a violation at `/count` when no count cap is declared |
| `outside_scope` | the row would be outside the rows you may reach | `403` for a write whose row, as stored, would be outside the caller's scope |

rain-crud also answers with rain-core's `unauthenticated`, `forbidden`, `not_found`, `unknown_parameter`, `bad_query`,
`unknown_field`, `invalid_format`, `out_of_range`, `required`, `invalid_id`, `malformed_body`, `validation_failed` and
`stale_version`
([rain-core](core.md#error-codes)).

## Scale guarantees

- Every list page reads at most `limit + 1` rows, `limit` at most `maxLimit`; an offset page examines at most
  `maxOffset + 1`.
- A count reads at most `countCap + 1` rows; nothing counts a total. A list runs a count statement only when it asks for
  one (`CountNotRequestedNoQueryTest`).
- A resource answers only declared shapes, and `CrudPlanProof` proves each one bounded by an index under the stated
  scopes; a shape with no bounded path is a finding, not a slow query.
- Finding a request's shape, a field and a relation is a map lookup, whatever the number of shapes, fields or relations.
- A relation is attached once per page, over at most `limit` items.
- A permission check asks only about the permissions the operation needs.
- A write by identifier is one statement addressed by the identifier within the scope, and at most one more by identifier
  to check the row against the scope or to tell a stale version; a bulk write is one statement over at most `maxBulkIds`
  identifiers, and a bulk update at most one more over the identifiers it wrote. The plan proof proves the statements of
  every mounted operation bounded.
- A query is at most `maxFilterTerms` filters, `maxInValues` values per `in`, `maxSortTerms` sort terms, `maxFields`
  fields and `maxIncludes` relations.

`CursorPagingIT`, `KeysetTieBreakIT`, `CappedCountIT`, `ScopedUpdateDeleteIT`, `ScopedCreateIT` and the plan-proof tests
hold these against PostgreSQL.

## What it does not do

- It has no substring, pattern, negation or full-text operator, no `search`, and no disjunction of request filters.
- It never serves the nearest shape, clamps a limit, assumes a zone or tries a second spelling of a value.
- It creates no index and no migration, and it does not run the plan proof at start-up; the application's tests do.
- It does not state which plan PostgreSQL chooses on production data.
- It mounts no route: the routes are the application's request mappings, each one line over `CrudMvc`.
- It does not check a version on a bulk update.
- It does not encrypt or authenticate cursors: a cursor is readable JSON carrying its boundary row's values of sort
  fields, all of them selectable; only a cursor made for the request's order is accepted, and a rewritten one never reads
  outside the scope, the filters or `fields`.
- It reads no security context; the caller comes from `CallerLookup`.
