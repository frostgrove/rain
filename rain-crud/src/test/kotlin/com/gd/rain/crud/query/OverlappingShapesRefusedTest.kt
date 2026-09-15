package com.gd.rain.crud.query

import com.gd.rain.core.config.ConfigurationProblemsException
import com.gd.rain.crud.Books
import com.gd.rain.crud.CrudResource
import com.gd.rain.crud.MemoryStore
import com.gd.rain.crud.SwitchableCallers
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Shapes match exactly, so two shapes accept the same request only when they are the same shape; declaring
 * one twice is refused with every other declaration problem at once.
 */
class OverlappingShapesRefusedTest {
    @Test
    fun `a shape declared twice is refused together with every other problem`() {
        val shapes =
            listOf(
                QueryShape.of(SortKey.parse("-createdAt"), "shelf" to Operator.EQ),
                QueryShape.of(SortKey.parse("title")),
                QueryShape(setOf(ShapeFilter("shelf", Operator.EQ)), SortKey.parse("-createdAt")),
                QueryShape.of(SortKey.parse("price")),
            )

        assertThatThrownBy {
            CrudResource(Books.rules(shapes = shapes), Books.policy(), MemoryStore(Books.SCHEMA), SwitchableCallers(), emptyList())
        }.isInstanceOf(ConfigurationProblemsException::class.java)
            .matches({ (it as ConfigurationProblemsException).problems.size == 2 }, "names two problems")
            .hasMessageContaining("declares the shape filters [filter[shelf][eq]], sort -createdAt 2 times")
            .hasMessageContaining("shape filters [], sort price sorts by price, which is nullable; a cursor cannot page by it")
    }

    @Test
    fun `shapes that differ in a filter, an operator or the sort do not overlap, and each serves only its own request`() {
        val byShelf = QueryShape.of(SortKey.NONE, "shelf" to Operator.EQ)
        val amongShelves = QueryShape.of(SortKey.NONE, "shelf" to Operator.IN)
        val newestOnShelf = QueryShape.of(SortKey.parse("-createdAt"), "shelf" to Operator.EQ)
        val recentOnShelf = QueryShape.of(SortKey.parse("-createdAt"), "shelf" to Operator.EQ, "createdAt" to Operator.GTE)
        val rules = Books.rules(shapes = listOf(byShelf, amongShelves, newestOnShelf, recentOnShelf))
        val compiler = QueryCompiler(Books.SCHEMA, rules, emptySet(), setOf(Books.ID))

        fun shapeOf(vararg parameters: Pair<String, String>) =
            compiler.list(DialectV1.parse(parameters.associate { (name, value) -> name to listOf(value) })).shape

        assertThat(rules.problems(Books.SCHEMA, emptySet())).isEmpty()
        assertThat(shapeOf("filter[shelf][eq]" to "a")).isSameAs(byShelf)
        assertThat(shapeOf("filter[shelf][in]" to "a")).isSameAs(amongShelves)
        assertThat(shapeOf("filter[shelf][eq]" to "a", "sort" to "-createdAt")).isSameAs(newestOnShelf)
        assertThat(shapeOf("filter[shelf][eq]" to "a", "filter[createdAt][gte]" to "2026-09-15T10:00:00Z", "sort" to "-createdAt"))
            .isSameAs(recentOnShelf)
    }

    @Test
    fun `the same filters stated in another order are the same shape`() {
        val one = QueryShape.of(SortKey.NONE, "shelf" to Operator.EQ, "pages" to Operator.GT)
        val other = QueryShape.of(SortKey.NONE, "pages" to Operator.GT, "shelf" to Operator.EQ)

        assertThat(one).isEqualTo(other).hasSameHashCodeAs(other)
        assertThat(one.toString()).isEqualTo("filters [filter[pages][gt], filter[shelf][eq]], sort (none)")
        assertThat(Books.rules(shapes = listOf(one, other)).problems(Books.SCHEMA, emptySet()).map { it.message })
            .containsExactly("declares the shape filters [filter[pages][gt], filter[shelf][eq]], sort (none) 2 times")
    }
}
