package com.gd.rain.crud.web

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.crud.Books
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.MemoryStore
import com.gd.rain.crud.SwitchableCallers
import com.gd.rain.crud.TestCaller
import com.gd.rain.crud.faultOf
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** A resource that mounts its list or its count declares at least one query shape. */
class ListWithoutShapeRefusedTest {
    private val store = MemoryStore(Books.SCHEMA)

    private fun shapeless() =
        CrudResource(
            Books.rules(shapes = emptyList()),
            Books.policy(),
            store,
            SwitchableCallers(TestCaller.of("a", Books.READ)),
            emptyList(),
        )

    @Test
    fun `mounting the list or the count of a resource without shapes is refused, each named`() {
        assertThatThrownBy { MountedResource("/books", setOf(CrudOperation.LIST, CrudOperation.COUNT, CrudOperation.GET), shapeless()) }
            .isInstanceOf(ConfigurationProblemsException::class.java)
            .matches({ (it as ConfigurationProblemsException).problems.size == 2 }, "names two problems")
            .hasMessageContaining("mounts COUNT, but the resource declares no query shape")
            .hasMessageContaining("mounts LIST, but the resource declares no query shape")
    }

    @Test
    fun `a resource without shapes still mounts its one-item and write routes`() {
        val mounted = MountedResource("/books", setOf(CrudOperation.GET, CrudOperation.DELETE), shapeless())

        assertThat(mounted.routes().map { "${it.method} ${it.path}" }).containsExactly("GET /books/{id}", "DELETE /books/{id}")
    }

    @Test
    fun `listing or counting a resource without shapes is 400 not_offered and reaches no row`() {
        val resource = shapeless()

        assertThat(faultOf { resource.list(emptyMap()) }.code.value).isEqualTo("not_offered")
        assertThat(faultOf { resource.count(emptyMap()) }.code.value).isEqualTo("not_offered")
        assertThat(store.operations).isEmpty()
    }
}
