package com.gd.rain.crud.web

import com.gd.rain.core.config.ConfigurationProblem
import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.core.config.ProblemCode
import com.gd.rain.crud.Action
import com.gd.rain.crud.ActionAccess
import com.gd.rain.crud.CrudResource
import com.gd.rain.web.route.EndpointDeclaration

/**
 * The HTTP operations of a resource, in the order they are declared. `/count` comes before `/{id}` so
 * a router that matches in declaration order never reads `count` as an identifier.
 */
public enum class CrudOperation(
    public val method: String,
    public val suffix: String,
    public val action: Action,
) {
    CREATE("POST", "", Action.CREATE),
    BULK_DELETE("POST", "/bulk-delete", Action.DELETE),
    COUNT("GET", "/count", Action.READ),
    LIST("GET", "", Action.READ),
    GET("GET", "/{id}", Action.READ),
    UPDATE("PATCH", "/{id}", Action.UPDATE),
    REPLACE("PUT", "/{id}", Action.UPDATE),
    DELETE("DELETE", "/{id}", Action.DELETE),
}

public data class CrudRoute(
    public val method: String,
    public val path: String,
    public val operation: CrudOperation,
)

/**
 * A resource mounted under [prefix] with exactly the stated [operations] — the one table both its routes
 * and their access declarations come from.
 *
 * The declaration of each route is derived from the policy the resource enforces, so the permission is
 * written once. Mounting an operation whose action the policy declares no access for, the list or `/count`
 * on a resource that declares no query shape, or `/count` on a resource that declares no count cap, is
 * refused when this is constructed.
 */
public class MountedResource<T>(
    public val prefix: String,
    operations: Set<CrudOperation>,
    public val resource: CrudResource<T>,
) {
    public val operations: List<CrudOperation> = CrudOperation.entries.filter(operations::contains)

    init {
        val where = "crud:${resource.schema.name}.mount"
        val problems = mutableListOf<ConfigurationProblem>()
        if (!PREFIX.matches(prefix)) {
            problems +=
                ConfigurationProblem(where, ProblemCode.INVALID, "prefix \"$prefix\" does not match ${PREFIX.pattern}")
        }
        if (this.operations.isEmpty()) problems += ConfigurationProblem(where, ProblemCode.INVALID, "mounts no operation")
        this.operations.filter { resource.policy.access[it.action] == null }.forEach {
            problems +=
                ConfigurationProblem(where, ProblemCode.CONTRADICTS, "mounts $it, but the policy declares no access for ${it.action.wire}")
        }
        if (resource.rules.shapes.isEmpty()) {
            this.operations.filter { it == CrudOperation.LIST || it == CrudOperation.COUNT }.forEach {
                problems += ConfigurationProblem(where, ProblemCode.CONTRADICTS, "mounts $it, but the resource declares no query shape")
            }
        }
        if (CrudOperation.COUNT in this.operations && resource.rules.pagination.countCap == null) {
            problems += ConfigurationProblem(where, ProblemCode.CONTRADICTS, "mounts COUNT, but the resource declares no count cap")
        }
        if (problems.isNotEmpty()) throw ConfigurationProblemsException(problems)
    }

    public fun routes(): List<CrudRoute> = operations.map { CrudRoute(it.method, prefix + it.suffix, it) }

    /** What each mounted route requires, for the surface verification of a controller that `DeclaresItsOwnAccess`. */
    public fun declarations(): List<EndpointDeclaration> =
        routes().map { route ->
            when (val access = checkNotNull(resource.policy.access[route.operation.action])) {
                is ActionAccess.Permissions -> EndpointDeclaration(route.method, route.path, permissions = access.permissions.toList())
                is ActionAccess.Authenticated -> EndpointDeclaration(route.method, route.path, authenticated = true, why = access.why)
            }
        }

    private companion object {
        val PREFIX = Regex("^(/[A-Za-z0-9._~-]+)+$")
    }
}
