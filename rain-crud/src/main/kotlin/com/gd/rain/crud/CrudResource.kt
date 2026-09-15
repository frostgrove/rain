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
import com.gd.rain.crud.query.Order
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
 * A declared resource: its rules, its policy and its store, one layer above any transport.
 *
 * Every operation first authenticates and authorizes the caller against the policy — before any
 * shortcut, so an empty id list still needs the permission — then confines itself to the policy's scope
 * for that caller. Reads, counts, updates and deletes all carry the scope: a row outside it is not found,
 * and a bulk operation does not count it.
 *
 * Lists page by keyset first. A cursor page reads `limit + 1` rows in the effective order (inverted for a
 * page before a cursor), keeps the `limit` nearest to the cursor and reports whether there is more that
 * way; an offset page reads `limit + 1` rows past its offset. A count is taken only when the query asks
 * for one and the resource declares a cap, and reads at most `cap + 1` rows.
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
        }
        problems += rules.problems(schema, this.relations.keys)
        if (problems.isNotEmpty()) throw ConfigurationProblemsException(problems)
        compiler = QueryCompiler(schema, rules, this.relations.keys)
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

    public fun create(values: Map<String, Any?>): T {
        authorize(Action.CREATE)
        checkWritable(values)
        return store.insert(values)
    }

    /** Writes [values] to the row [id] within the caller's scope; `404` when there is no such row. An empty write reads the row. */
    public fun update(
        id: UUID,
        values: Map<String, Any?>,
    ): T {
        val caller = authorize(Action.UPDATE)
        checkWritable(values)
        val scope = scopeFor(caller)
        val written = if (values.isEmpty()) store.find(id, scope, Projection.Full) else store.update(id, scope, values)
        return written ?: throw Fault.notFound()
    }

    /** Writes the non-empty [values] to the rows among [ids] within the caller's scope; answers how many were written. */
    public fun updateMany(
        ids: Set<UUID>,
        values: Map<String, Any?>,
    ): Long {
        val caller = authorize(Action.UPDATE)
        require(values.isNotEmpty()) { "a bulk update writes at least one field" }
        checkWritable(values)
        checkBulk(ids.size)
        if (ids.isEmpty()) return 0
        return store.updateMany(ids, scopeFor(caller), values)
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

    /** [deleteMany] for ids as the wire spells them, each refused at `/ids/<index>` when it is not canonical. */
    public fun bulkDelete(ids: List<String>): Long {
        val caller = authorize(Action.DELETE)
        checkBulk(ids.size)
        val refused =
            ids.indices
                .filter { ResourceSchema.canonicalUuid(ids[it]) == null }
                .map { Violation.at(path(BULK_IDS, it), RainErrorCodes.INVALID_ID) }
        if (refused.isNotEmpty()) throw Fault(FaultKind.BAD_REQUEST, RainErrorCodes.INVALID_ID, violations = refused)
        return deleteManyAs(caller, ids.mapTo(LinkedHashSet()) { checkNotNull(ResourceSchema.canonicalUuid(it)) })
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
        val rows = read(RowRead(scope, plan.filter, null, plan.order, window.limit + 1, window.offset, plan.projection))
        return rows.take(window.limit).map(KeyedRow<T>::item) to PageWindow.Offset(window.limit, window.offset, rows.size > window.limit)
    }

    private fun cursorPage(
        plan: ListPlan,
        window: Window.Cursor,
        scope: RowScope,
    ): Pair<List<T>, PageWindow> {
        val limit = window.limit
        val position = window.position
        val probe = limit + 1

        fun mint(
            direction: CursorDirection,
            row: KeyedRow<T>,
        ): String = CursorCodec.encode(plan.order, CursorPosition(direction, row.keys))

        return when (position?.direction) {
            null -> {
                val rows = read(RowRead(scope, plan.filter, null, plan.order, probe, 0, plan.projection))
                val kept = rows.take(limit)
                val next = if (rows.size > limit) mint(CursorDirection.NEXT, kept.last()) else null
                kept.map(KeyedRow<T>::item) to PageWindow.Cursor(limit, next, prev = null)
            }

            CursorDirection.NEXT -> {
                val seek = Predicate.Keyset(plan.order, position.keys, Predicate.Side.AFTER)
                val rows = read(RowRead(scope, plan.filter, seek, plan.order, probe, 0, plan.projection))
                val kept = rows.take(limit)
                val next = if (rows.size > limit) mint(CursorDirection.NEXT, kept.last()) else null
                val prev = kept.firstOrNull()?.let { mint(CursorDirection.PREV, it) }
                kept.map(KeyedRow<T>::item) to PageWindow.Cursor(limit, next, prev)
            }

            CursorDirection.PREV -> {
                val inverted = plan.order.map { Order(it.field, it.direction.inverted()) }
                val seek = Predicate.Keyset(inverted, position.keys, Predicate.Side.AFTER)
                val rows = read(RowRead(scope, plan.filter, seek, inverted, probe, 0, plan.projection))
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
        val cap =
            rules.pagination.countCap ?: throw Fault(FaultKind.BAD_REQUEST, RainCrudErrorCodes.NOT_OFFERED, "this resource offers no count")
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

    private fun scopeFor(caller: Caller.Authenticated): RowScope =
        when (val rule = policy.scope) {
            ScopeRule.Unrestricted -> {
                RowScope.Everything
            }

            is ScopeRule.Rows -> {
                val predicate = rule.of(caller)
                val foreign =
                    predicate
                        .fields()
                        .filterNot(schema::owns)
                        .map(SchemaField::name)
                        .toSet()
                check(foreign.isEmpty()) { "the scope of ${schema.name} reads fields it does not declare: ${foreign.sorted()}" }
                RowScope.Matching(predicate)
            }
        }

    private fun checkWritable(values: Map<String, Any?>) {
        val violations =
            values.keys.sorted().mapNotNull { name ->
                when {
                    schema.field(name) == null -> Violation.at(path(name), RainErrorCodes.UNKNOWN_FIELD, "is not a field of this resource")
                    !policy.writable.grants(name) -> Violation.at(path(name), RainCrudErrorCodes.FIELD_NOT_GRANTED, "is not writable")
                    else -> null
                }
            }
        if (violations.isNotEmpty()) throw Fault.validation(violations)
        WriteValues.check(schema, values)
    }

    private fun checkBulk(size: Int) {
        if (size > rules.limits.maxBulkIds) {
            throw Fault(
                FaultKind.BAD_REQUEST,
                RainErrorCodes.BAD_REQUEST,
                violations =
                    listOf(
                        Violation.at(path(BULK_IDS), RainErrorCodes.OUT_OF_RANGE, "names more than ${rules.limits.maxBulkIds} ids"),
                    ),
            )
        }
    }

    private companion object {
        const val BULK_IDS = "ids"
        val ID_PATH: List<PathStep> = path("id")
    }
}
