package com.gd.rain.crud

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.core.error.Fault
import com.gd.rain.core.error.FaultKind
import com.gd.rain.core.error.PathStep
import com.gd.rain.core.error.RainErrorCodes
import com.gd.rain.core.error.Violation
import com.gd.rain.core.error.path
import com.gd.rain.crud.error.RainCrudErrorCodes
import com.gd.rain.crud.query.CursorCodec
import com.gd.rain.crud.query.CursorDirection
import com.gd.rain.crud.query.CursorPosition
import com.gd.rain.crud.query.DialectV1
import com.gd.rain.crud.query.FieldGrant
import com.gd.rain.crud.query.ListPlan
import com.gd.rain.crud.query.Predicate
import com.gd.rain.crud.query.Projection
import com.gd.rain.crud.query.QueryCompiler
import com.gd.rain.crud.query.QueryRules
import com.gd.rain.crud.query.ResourceSchema
import com.gd.rain.crud.query.SchemaField
import com.gd.rain.crud.query.Window
import java.util.UUID

/** One page of a list: its items, where it sits, and — when asked for and offered — a capped count. */
public class Page<T>(
    items: List<T>,
    public val window: PageWindow,
    public val count: CappedCount?,
) {
    public val items: List<T> = items.toList()
}

public sealed interface PageWindow {
    public val limit: Int

    /** A keyset page; [next] and [prev] are `null` when there is no page that way. */
    public data class Cursor(
        override val limit: Int,
        public val next: String?,
        public val prev: String?,
    ) : PageWindow

    public data class Offset(
        override val limit: Int,
        public val offset: Long,
        public val hasNext: Boolean,
    ) : PageWindow
}

/** A count that read at most `cap + 1` rows: exact when it found no more than the cap, otherwise "more than [value]". */
public data class CappedCount(
    public val value: Long,
    public val exact: Boolean,
) {
    public companion object {
        public fun of(
            counted: Long,
            cap: Long,
        ): CappedCount {
            require(cap >= 1 && counted in 0..cap + 1) { "a capped count is within 0..cap + 1, got $counted for cap $cap" }
            return if (counted <= cap) CappedCount(counted, exact = true) else CappedCount(cap, exact = false)
        }
    }
}

/**
 * A relation `include` may name. [attach] receives the items of one page (at most the page's limit) and
 * answers them with the relation attached, in the same order — one bounded lookup per page, never per item.
 */
public class ResourceRelation<T>(
    public val name: String,
    private val attach: (List<T>) -> List<T>,
) {
    init {
        require(SchemaField.NAME.matches(name)) { "relation name \"$name\" does not match ${SchemaField.NAME.pattern}" }
    }

    public fun attachTo(items: List<T>): List<T> {
        val attached = attach(items)
        check(attached.size == items.size) { "relation $name answered ${attached.size} items for ${items.size}" }
        return attached
    }
}

/** Identifiers as the wire spells them: canonical 8-4-4-4-12, or `400 invalid_id` pointing at where it was read. */
public object CrudIds {
    public fun parse(
        raw: String,
        at: List<PathStep>,
    ): UUID =
        ResourceSchema.canonicalUuid(raw)
            ?: throw Fault(
                FaultKind.BAD_REQUEST,
                RainErrorCodes.INVALID_ID,
                violations = listOf(Violation.at(at, RainErrorCodes.INVALID_ID)),
            )
}

/**
 * The values of one write, keyed by field name and carried as their kinds' types, and the [violations] found while
 * reading them from a request (a name that is no field, a value that does not read), which the resource refuses
 * together with its own.
 */
public class WriteInput(
    values: Map<String, Any?>,
    violations: List<Violation>,
) {
    public val values: Map<String, Any?> = LinkedHashMap(values)
    public val violations: List<Violation> = violations.toList()

    public companion object {
        /** Values an application states itself, with nothing refused while reading them. */
        public fun of(values: Map<String, Any?>): WriteInput = WriteInput(values, emptyList())
    }
}

/**
 * A declared resource: its rules, its policy and its store, one layer above any transport.
 *
 * Every operation first authenticates and authorizes the caller against the policy — before it reads any input:
 * an identifier, a query, a write body, an empty id list — then confines itself to the policy's scope for that
 * caller. Reads, counts, updates and deletes all carry the scope: a row outside it is not found, and a bulk
 * operation does not count it. A write that leaves a row behind — a create, an update, a replacement, a bulk update —
 * leaves it inside the scope, as the database decides it in the write's transaction, or it writes nothing and is
 * `403 outside_scope`.
 *
 * The operations that take their input as a function (`create { … }`, `update(id) { … }`, `bulkDelete { … }`) call
 * it only once the caller is authorized, so a transport reads a request body after the caller is known.
 *
 * A list or a count is answered only for a query shape the rules declare ([com.gd.rain.crud.query.QueryShape]).
 * Lists page by keyset first. A cursor page reads `limit + 1` rows in the effective order (inverted for a
 * page before a cursor), keeps the `limit` nearest to the cursor and reports whether there is more that
 * way; an offset page reads `limit + 1` rows past its offset ([PageReads]). A count is taken only when the
 * query asks for one and the resource declares a cap, and reads at most `cap + 1` rows.
 *
 * The declaration is checked when the resource is constructed; every problem is refused together.
 */
public class CrudResource<T>(
    public val rules: QueryRules,
    public val policy: ResourcePolicy,
    private val store: CrudStore<T>,
    private val callers: CallerLookup,
    relations: List<ResourceRelation<T>>,
) {
    public val schema: ResourceSchema = store.schema

    private val relations: Map<String, ResourceRelation<T>> = relations.associateBy(ResourceRelation<T>::name)

    /**
     * The fields a write by identifier may state: every field `writable` grants except the identifier and the version.
     * A replacement states every one of them.
     */
    public val replaceable: List<SchemaField> =
        schema.fields.filter { it != schema.id && it != schema.version && policy.writable.grants(it.name) }

    private val compiler: QueryCompiler

    init {
        val problems = mutableListOf<ConfigurationProblem>()
        relations.groupBy { it.name }.filterValues { it.size > 1 }.keys.sorted().forEach {
            problems +=
                ConfigurationProblem("crud:${schema.name}.relations", ProblemCode.CONTRADICTS, "declares relation $it more than once")
        }
        val writable = policy.writable
        if (writable is FieldGrant.Only) {
            writable.names.filter { schema.field(it) == null }.sorted().forEach {
                problems += ConfigurationProblem("crud:${schema.name}.writable", ProblemCode.INVALID, "grants $it, which is not a field")
            }
            schema.version?.takeIf { it.name in writable.names }?.let {
                problems +=
                    ConfigurationProblem(
                        "crud:${schema.name}.writable",
                        ProblemCode.CONTRADICTS,
                        "grants ${it.name}, the version, which the store writes",
                    )
            }
        }
        store.itemFields.filterNot(schema::owns).map(SchemaField::name).sorted().forEach {
            problems += ConfigurationProblem("crud:${schema.name}.reader", ProblemCode.INVALID, "reads $it, which is not a field")
        }
        if (rules.selectable != FieldGrant.None) {
            store.itemFields
                .filter { schema.owns(it) && it != schema.id && !rules.selectable.grants(it.name) }
                .map(SchemaField::name)
                .sorted()
                .forEach {
                    problems +=
                        ConfigurationProblem(
                            "crud:${schema.name}.reader",
                            ProblemCode.CONTRADICTS,
                            "reads $it, which selectable does not grant, so no fields selection could be answered",
                        )
                }
        }
        problems += rules.problems(schema, this.relations.keys)
        if (problems.isNotEmpty()) throw ConfigurationProblemsException(problems)
        compiler = QueryCompiler(schema, rules, this.relations.keys, store.itemFields)
    }

    public val relationNames: Set<String> get() = relations.keys

    public fun list(parameters: Map<String, List<String>>): Page<T> {
        val caller = authorize(Action.READ)
        val plan = compiler.list(DialectV1.parse(parameters))
        val scope = scopeFor(caller)
        val (items, window) =
            when (val requested = plan.window) {
                is Window.Offset -> offsetPage(plan, requested, scope)
                is Window.Cursor -> cursorPage(plan, requested, scope)
            }
        val count = if (plan.counted) countOf(scope, plan.filter) else null
        return Page(attach(items, plan.includes), window, count)
    }

    public fun count(parameters: Map<String, List<String>>): CappedCount {
        val caller = authorize(Action.READ)
        val plan = compiler.count(DialectV1.parse(parameters))
        return countOf(scopeFor(caller), plan.filter)
    }

    public fun get(
        id: String,
        parameters: Map<String, List<String>>,
    ): T {
        val caller = authorize(Action.READ)
        val identifier = CrudIds.parse(id, ID_PATH)
        val plan = compiler.item(DialectV1.parse(parameters))
        val item = store.find(identifier, scopeFor(caller), plan.projection) ?: throw Fault.notFound()
        return attach(listOf(item), plan.includes).single()
    }

    /** Inserts [values]; see [create] with an input function. */
    public fun create(values: Map<String, Any?>): T = create { WriteInput.of(values) }

    /**
     * Inserts the values [input] answers, read once the caller is authorized. The values name only writable fields and
     * never the version; the inserted row is in the caller's scope or nothing is inserted (`403 outside_scope`).
     */
    public fun create(input: () -> WriteInput): T {
        val caller = authorize(Action.CREATE)
        val values = checkedWrite(input(), WriteKind.CREATE)
        return when (val outcome = store.insert(scopeFor(caller), values)) {
            is InsertOutcome.Inserted -> outcome.item
            is InsertOutcome.OutsideScope -> throw outsideScope()
        }
    }

    /** Writes [values] to the row [id]; see [update] with an input function. */
    public fun update(
        id: UUID,
        values: Map<String, Any?>,
    ): T = updateAs(authorize(Action.UPDATE), id, WriteInput.of(values), WriteKind.UPDATE)

    /**
     * Writes the values [input] answers to the row [id] (as the wire spells it) within the caller's scope: at least one
     * writable field, never the identifier, and the version the row was read at when the schema declares one. `404` when
     * no such row is in the scope, `409 stale_version` when its version is another, `403 outside_scope` when the row
     * written would leave the scope. The identifier is read, and then [input], once the caller is authorized.
     */
    public fun update(
        id: String,
        input: () -> WriteInput,
    ): T {
        val caller = authorize(Action.UPDATE)
        val identifier = CrudIds.parse(id, ID_PATH)
        return updateAs(caller, identifier, input(), WriteKind.UPDATE)
    }

    /** Replaces the row [id] with [values]; see [replace] with an input function. */
    public fun replace(
        id: UUID,
        values: Map<String, Any?>,
    ): T = updateAs(authorize(Action.UPDATE), id, WriteInput.of(values), WriteKind.REPLACE)

    /**
     * Replaces the row [id] with the values [input] answers: an [update] that states every field of [replaceable] — an
     * absent one is `422 required`, never written as NULL. Fields `writable` does not grant keep their values.
     */
    public fun replace(
        id: String,
        input: () -> WriteInput,
    ): T {
        val caller = authorize(Action.UPDATE)
        val identifier = CrudIds.parse(id, ID_PATH)
        return updateAs(caller, identifier, input(), WriteKind.REPLACE)
    }

    /**
     * Writes the values to the rows among [ids] within the caller's scope, without a version check (a versioned row's
     * version still rises); answers how many were written. `403 outside_scope`, writing nothing, when a row written
     * would leave the scope.
     */
    public fun updateMany(
        ids: Set<UUID>,
        values: Map<String, Any?>,
    ): Long {
        val caller = authorize(Action.UPDATE)
        val checked = checkedWrite(WriteInput.of(values), WriteKind.BULK_UPDATE)
        checkBulk(ids.size)
        if (ids.isEmpty()) return 0
        return when (val outcome = store.updateMany(ids, scopeFor(caller), checked)) {
            is BulkUpdateOutcome.Updated -> outcome.count
            is BulkUpdateOutcome.OutsideScope -> throw outsideScope()
        }
    }

    /** Deletes the row [id] within the caller's scope; `404` when there is no such row. */
    public fun delete(id: UUID) {
        deleteAs(authorize(Action.DELETE), id)
    }

    /** [delete] for an identifier as the wire spells it; the caller is authorized before the identifier is read. */
    public fun delete(id: String) {
        val caller = authorize(Action.DELETE)
        deleteAs(caller, CrudIds.parse(id, ID_PATH))
    }

    /** Deletes the rows among [ids] within the caller's scope; answers how many were deleted. */
    public fun deleteMany(ids: Set<UUID>): Long {
        val caller = authorize(Action.DELETE)
        checkBulk(ids.size)
        return deleteManyAs(caller, ids)
    }

    /**
     * [deleteMany] for the ids [input] answers as the wire spells them, read once the caller is authorized: at most
     * `maxBulkIds` (`400 bad_query` at `/ids`), each refused at `/ids/<index>` when it is not canonical.
     */
    public fun bulkDelete(input: () -> List<String>): Long {
        val caller = authorize(Action.DELETE)
        val ids = input()
        checkBulk(ids.size)
        val refused =
            ids.indices
                .filter { ResourceSchema.canonicalUuid(ids[it]) == null }
                .map { Violation.at(path(BULK_IDS, it), RainErrorCodes.INVALID_ID) }
        if (refused.isNotEmpty()) throw Fault(FaultKind.BAD_REQUEST, RainErrorCodes.INVALID_ID, violations = refused)
        return deleteManyAs(caller, ids.mapTo(LinkedHashSet()) { checkNotNull(ResourceSchema.canonicalUuid(it)) })
    }

    private fun updateAs(
        caller: Caller.Authenticated,
        id: UUID,
        input: WriteInput,
        kind: WriteKind,
    ): T {
        val values = checkedWrite(input, kind)
        val version = schema.version
        val expected = version?.let { values.getValue(it.name) as Long }
        val written = if (version == null) values else values - version.name
        return when (val outcome = store.update(id, scopeFor(caller), written, expected)) {
            is UpdateOutcome.Updated -> outcome.item
            is UpdateOutcome.NotFound -> throw Fault.notFound()
            is UpdateOutcome.StaleVersion -> throw Fault.conflict(RainErrorCodes.STALE_VERSION)
            is UpdateOutcome.OutsideScope -> throw outsideScope()
        }
    }

    private fun deleteAs(
        caller: Caller.Authenticated,
        id: UUID,
    ) {
        if (store.delete(id, scopeFor(caller)) == 0L) throw Fault.notFound()
    }

    private fun deleteManyAs(
        caller: Caller.Authenticated,
        ids: Set<UUID>,
    ): Long {
        if (ids.isEmpty()) return 0
        return store.deleteMany(ids, scopeFor(caller))
    }

    private fun offsetPage(
        plan: ListPlan,
        window: Window.Offset,
        scope: RowScope,
    ): Pair<List<T>, PageWindow> {
        val rows = read(PageReads.offset(scope, plan.filter, plan.order, window.offset, window.limit, plan.projection))
        return rows.take(window.limit).map(KeyedRow<T>::item) to PageWindow.Offset(window.limit, window.offset, rows.size > window.limit)
    }

    private fun cursorPage(
        plan: ListPlan,
        window: Window.Cursor,
        scope: RowScope,
    ): Pair<List<T>, PageWindow> {
        val limit = window.limit
        val position = window.position

        fun mint(
            direction: CursorDirection,
            row: KeyedRow<T>,
        ): String = CursorCodec.encode(plan.order, CursorPosition(direction, row.keys))

        return when (position?.direction) {
            null -> {
                val rows = read(PageReads.first(scope, plan.filter, plan.order, limit, plan.projection))
                val kept = rows.take(limit)
                val next = if (rows.size > limit) mint(CursorDirection.NEXT, kept.last()) else null
                kept.map(KeyedRow<T>::item) to PageWindow.Cursor(limit, next, prev = null)
            }

            CursorDirection.NEXT -> {
                val rows = read(PageReads.after(scope, plan.filter, plan.order, position.keys, limit, plan.projection))
                val kept = rows.take(limit)
                val next = if (rows.size > limit) mint(CursorDirection.NEXT, kept.last()) else null
                val prev = kept.firstOrNull()?.let { mint(CursorDirection.PREV, it) }
                kept.map(KeyedRow<T>::item) to PageWindow.Cursor(limit, next, prev)
            }

            CursorDirection.PREV -> {
                val rows = read(PageReads.before(scope, plan.filter, plan.order, position.keys, limit, plan.projection))
                // Read nearest-first before the cursor: keep the `limit` nearest, then put them back in the page's order.
                val kept = rows.take(limit).asReversed()
                val prev = if (rows.size > limit) mint(CursorDirection.PREV, kept.first()) else null
                val next = kept.lastOrNull()?.let { mint(CursorDirection.NEXT, it) }
                kept.map(KeyedRow<T>::item) to PageWindow.Cursor(limit, next, prev)
            }
        }
    }

    private fun read(read: RowRead): List<KeyedRow<T>> {
        val rows = store.read(read)
        check(rows.size <= read.limit) { "the store answered ${rows.size} rows for a read of at most ${read.limit}" }
        return rows
    }

    private fun countOf(
        scope: RowScope,
        filter: Predicate?,
    ): CappedCount {
        val cap = checkNotNull(rules.pagination.countCap) { "a count is compiled only for a resource that declares a count cap" }
        return CappedCount.of(store.countUpTo(scope, filter, cap), cap)
    }

    private fun attach(
        items: List<T>,
        includes: List<String>,
    ): List<T> {
        if (items.isEmpty()) return items
        return includes.fold(
            items,
        ) { current, name -> checkNotNull(relations[name]) { "relation $name is not declared" }.attachTo(current) }
    }

    private fun authorize(action: Action): Caller.Authenticated {
        val caller = callers.current()
        if (caller !is Caller.Authenticated) throw Fault.unauthorized()
        when (val access = policy.access[action]) {
            null -> throw Fault.forbidden(message = "this resource declares no access for ${action.wire}")
            is ActionAccess.Permissions -> if (!caller.holdsAll(access.permissions)) throw Fault.forbidden()
            is ActionAccess.Authenticated -> Unit
        }
        return caller
    }

    /**
     * The rows the policy's scope gives [caller] — what every operation of this caller is confined to. A plan proof states
     * the scopes it explains with it, so it proves the scopes the policy yields rather than predicates written again.
     */
    public fun scopeOf(caller: Caller.Authenticated): RowScope =
        when (val rule = policy.scope) {
            ScopeRule.Unrestricted -> {
                RowScope.Everything
            }

            is ScopeRule.Rows -> {
                rowsOf(rule, caller)
            }

            is ScopeRule.EveryRowWhenHolding -> {
                if (caller.holdsAll(
                        rule.permissions,
                    )
                ) {
                    RowScope.Everything
                } else {
                    rowsOf(rule.otherwise, caller)
                }
            }
        }

    private fun scopeFor(caller: Caller.Authenticated): RowScope = scopeOf(caller)

    private fun rowsOf(
        rule: ScopeRule.Rows,
        caller: Caller.Authenticated,
    ): RowScope {
        val predicate = rule.of(caller)
        val foreign =
            predicate
                .fields()
                .filterNot(schema::owns)
                .map(SchemaField::name)
                .toSet()
        check(foreign.isEmpty()) { "the scope of ${schema.name} reads fields it does not declare: ${foreign.sorted()}" }
        return RowScope.Matching(predicate)
    }

    /**
     * Every problem of a write, refused together as `422 validation_failed` with the violations its input carried: a
     * name that is no field; the identifier in a write by identifier; the version in a create or a bulk update, or its
     * absence (or NULL) in an update or a replacement; a field `writable` does not grant; NULL for a field that is not
     * nullable; a replacement without a field of [replaceable]; an update that states no field.
     */
    private fun checkedWrite(
        input: WriteInput,
        kind: WriteKind,
    ): Map<String, Any?> {
        val values = input.values
        val violations = input.violations.toMutableList()
        val version = schema.version
        values.keys.sorted().forEach { name ->
            val field = schema.field(name)
            val value = values[name]
            val problem =
                when {
                    field == null -> {
                        RainErrorCodes.UNKNOWN_FIELD to "is not a field of this resource"
                    }

                    field == version -> {
                        when {
                            kind == WriteKind.CREATE || kind == WriteKind.BULK_UPDATE -> {
                                RainCrudErrorCodes.FIELD_NOT_GRANTED to "is the version, which the store writes"
                            }

                            value == null -> {
                                RainErrorCodes.REQUIRED to VERSION_REQUIRED
                            }

                            else -> {
                                null
                            }
                        }
                    }

                    field == schema.id && kind != WriteKind.CREATE -> {
                        RainCrudErrorCodes.FIELD_NOT_GRANTED to "is the identifier, which a write by identifier does not change"
                    }

                    !policy.writable.grants(name) -> {
                        RainCrudErrorCodes.FIELD_NOT_GRANTED to "is not writable"
                    }

                    value == null && !field.nullable -> {
                        RainErrorCodes.REQUIRED to "is not nullable"
                    }

                    else -> {
                        null
                    }
                }
            problem?.let { (code, message) -> violations += Violation.at(path(name), code, message) }
        }
        val pointed = violations.map(Violation::path).toSet()
        val byIdentifier = kind == WriteKind.UPDATE || kind == WriteKind.REPLACE
        if (version != null && byIdentifier && version.name !in values && path(version.name) !in pointed) {
            violations += Violation.at(path(version.name), RainErrorCodes.REQUIRED, VERSION_REQUIRED)
        }
        if (kind == WriteKind.REPLACE) {
            replaceable.filter { it.name !in values && path(it.name) !in pointed }.forEach {
                violations +=
                    Violation.at(path(it.name), RainErrorCodes.REQUIRED, "is not stated; a replacement states every writable field")
            }
        }
        val stated = values.keys.any { name -> schema.field(name)?.let { it != version && it != schema.id } == true }
        if (kind != WriteKind.CREATE && !stated && input.violations.isEmpty() && violations.isEmpty()) {
            violations += Violation.at(emptyList(), RainErrorCodes.REQUIRED, "a write by identifier states at least one field")
        }
        if (violations.isNotEmpty()) throw Fault.validation(violations)
        WriteValues.check(schema, values)
        return values
    }

    private fun outsideScope(): Fault =
        Fault(FaultKind.FORBIDDEN, RainCrudErrorCodes.OUTSIDE_SCOPE, "the row written would be outside the rows this caller may reach")

    private fun checkBulk(size: Int) {
        if (size > rules.limits.maxBulkIds) {
            throw Fault(
                FaultKind.BAD_REQUEST,
                RainErrorCodes.BAD_QUERY,
                violations =
                    listOf(
                        Violation.at(path(BULK_IDS), RainErrorCodes.OUT_OF_RANGE, "names more than ${rules.limits.maxBulkIds} ids"),
                    ),
            )
        }
    }

    private enum class WriteKind { CREATE, UPDATE, REPLACE, BULK_UPDATE }

    private companion object {
        const val BULK_IDS = "ids"
        const val VERSION_REQUIRED = "states the version the row was read at"
        val ID_PATH: List<PathStep> = path("id")
    }
}
