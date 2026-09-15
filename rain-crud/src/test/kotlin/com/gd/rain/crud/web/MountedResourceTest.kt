package com.gd.rain.crud.web

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.crud.Action
import com.gd.rain.crud.ActionAccess
import com.gd.rain.crud.Books
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.MemoryStore
import com.gd.rain.crud.ResourcePolicy
import com.gd.rain.crud.ScopeRule
import com.gd.rain.crud.SwitchableCallers
import com.gd.rain.crud.query.FieldGrant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** The table a resource's routes and their access declarations both come from. */
class MountedResourceTest {
    private fun resource(
        policy: ResourcePolicy = Books.policy(),
        countCap: Long? = 50,
    ) = CrudResource(Books.rules(countCap = countCap), policy, MemoryStore(Books.SCHEMA), SwitchableCallers(), emptyList())

    @Test
    fun `every operation mounts in declaration order, count before the by-id route`() {
        val routes = MountedResource("/api/books", CrudOperation.entries.toSet(), resource()).routes()

        assertThat(routes.map { "${it.method} ${it.path}" }).containsExactly(
            "POST /api/books",
            "POST /api/books/bulk-delete",
            "GET /api/books/count",
            "GET /api/books",
            "GET /api/books/{id}",
            "PATCH /api/books/{id}",
            "PUT /api/books/{id}",
            "DELETE /api/books/{id}",
        )
    }

    @Test
    fun `a table mounts exactly the operations it states`() {
        val routes = MountedResource("/books", setOf(CrudOperation.GET, CrudOperation.LIST), resource()).routes()

        assertThat(routes.map(CrudRoute::operation)).containsExactly(CrudOperation.LIST, CrudOperation.GET)
    }

    @Test
    fun `declarations are derived from the policy the resource enforces, and each is valid`() {
        val declarations = MountedResource("/books", CrudOperation.entries.toSet(), resource()).declarations()

        assertThat(declarations.associate { it.key to it.permissions })
            .containsEntry("GET /books", listOf(Books.READ))
            .containsEntry("PATCH /books/{id}", listOf(Books.WRITE))
            .containsEntry("POST /books/bulk-delete", listOf(Books.DELETE))
            .containsEntry("DELETE /books/{id}", listOf(Books.DELETE))
        declarations.forEach { assertThat(it.problems()).describedAs(it.key).isEmpty() }
    }

    @Test
    fun `an action open to authenticated callers is declared authenticated with its reason`() {
        val policy =
            ResourcePolicy(
                mapOf(Action.READ to ActionAccess.Authenticated("members read the catalogue")),
                ScopeRule.Unrestricted,
                FieldGrant.None,
            )

        val declaration = MountedResource("/books", setOf(CrudOperation.LIST), resource(policy)).declarations().single()

        assertThat(declaration.authenticated).isTrue()
        assertThat(declaration.why).isEqualTo("members read the catalogue")
        assertThat(declaration.problems()).isEmpty()
    }

    @Test
    fun `mounting an operation whose action the policy declares nothing for is refused`() {
        val readOnly = ResourcePolicy(mapOf(Action.READ to ActionAccess.permissions(Books.READ)), ScopeRule.Unrestricted, FieldGrant.None)

        assertThatThrownBy {
            MountedResource(
                "/books",
                setOf(CrudOperation.LIST, CrudOperation.CREATE, CrudOperation.DELETE),
                resource(readOnly),
            )
        }.isInstanceOf(ConfigurationProblemsException::class.java)
            .hasMessageContaining("mounts CREATE, but the policy declares no access for create")
            .hasMessageContaining("mounts DELETE, but the policy declares no access for delete")
    }

    @Test
    fun `mounting an update or a replacement on a resource that grants no field to write by identifier is refused`() {
        val identifierOnly = ResourcePolicy(Books.policy().access, ScopeRule.Unrestricted, FieldGrant.only("id"))

        assertThatThrownBy { MountedResource("/books", setOf(CrudOperation.UPDATE, CrudOperation.REPLACE), resource(identifierOnly)) }
            .isInstanceOf(ConfigurationProblemsException::class.java)
            .matches({ (it as ConfigurationProblemsException).problems.size == 2 }, "names two problems")
            .hasMessageContaining("mounts UPDATE, but writable grants no field a write by identifier states")
            .hasMessageContaining("mounts REPLACE, but writable grants no field a write by identifier states")
        assertThat(MountedResource("/books", setOf(CrudOperation.CREATE), resource(identifierOnly)).routes()).hasSize(1)
    }

    @Test
    fun `mounting count on a resource without a count cap is refused`() {
        assertThatThrownBy { MountedResource("/books", setOf(CrudOperation.COUNT), resource(countCap = null)) }
            .isInstanceOf(ConfigurationProblemsException::class.java)
            .hasMessageContaining("mounts COUNT, but the resource declares no count cap")
    }

    @Test
    fun `a prefix is slash-led without a trailing slash, and something is mounted`() {
        listOf("books", "/books/", "", "/bo oks").forEach { prefix ->
            assertThatThrownBy { MountedResource(prefix, setOf(CrudOperation.LIST), resource()) }
                .describedAs(prefix)
                .isInstanceOf(ConfigurationProblemsException::class.java)
        }
        assertThatThrownBy { MountedResource("/books", emptySet(), resource()) }.hasMessageContaining("mounts no operation")
    }

    @Test
    fun `an application controller declares the routes of its mounted resource`() {
        booksRunner().run { context ->
            val declared = context.getBean(BookController::class.java).accessDeclarations().map { it.key }

            assertThat(declared).containsExactly(
                "POST /books",
                "POST /books/bulk-delete",
                "GET /books/count",
                "GET /books",
                "GET /books/{id}",
                "PATCH /books/{id}",
                "PUT /books/{id}",
                "DELETE /books/{id}",
            )
        }
    }
}
