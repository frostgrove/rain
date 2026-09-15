package com.gd.rain.persistence.schema

import com.gd.rain.core.config.ProblemCode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.support.ResourcePatternResolver

class SchemaDescriptorTest {
    private fun resolver(vararg files: Pair<String, String>): ResourcePatternResolver =
        object : ResourcePatternResolver, org.springframework.core.io.ResourceLoader by DefaultResourceLoader() {
            override fun getResources(locationPattern: String): Array<Resource> =
                files.map { (name, text) -> ByteArrayResource(text.toByteArray(), name) }.toTypedArray()
        }

    private fun descriptor(module: String) = "module=$module\nschema=rain_${module.replace('-', '_')}\nlocation=classpath:db/rain/$module\n"

    @Test
    fun `well formed descriptors load in module order`() {
        val loaded =
            SchemaDescriptor.load(
                resolver(
                    "jobs" to descriptor("jobs"),
                    "access" to descriptor("access"),
                    "dead-letters" to descriptor("dead-letters"),
                ),
            )

        assertThat(loaded.problems).isEmpty()
        assertThat(loaded.descriptors.map { it.module }).containsExactly("access", "dead-letters", "jobs")
        assertThat(loaded.descriptors.first { it.module == "dead-letters" }.schema).isEqualTo("rain_dead_letters")
    }

    @Test
    fun `a module cannot claim another schema or another location`() {
        val loaded =
            SchemaDescriptor.load(
                resolver(
                    "a" to "module=access\nschema=public\nlocation=classpath:db/rain/access\n",
                    "b" to "module=jobs\nschema=rain_jobs\nlocation=classpath:db/migration\n",
                ),
            )

        assertThat(loaded.descriptors).isEmpty()
        assertThat(loaded.problems.map { it.code }).containsExactly(ProblemCode.INVALID, ProblemCode.INVALID)
    }

    @Test
    fun `a malformed module name is refused`() {
        assertThat(
            SchemaDescriptor.load(resolver("x" to "module=Jobs\nschema=rain_Jobs\nlocation=classpath:db/rain/Jobs\n")).problems,
        ).hasSize(1)
    }

    @Test
    fun `a module described twice is a contradiction`() {
        val loaded = SchemaDescriptor.load(resolver("one" to descriptor("jobs"), "two" to descriptor("jobs")))

        assertThat(loaded.problems.single().code).isEqualTo(ProblemCode.CONTRADICTS)
        assertThat(loaded.descriptors.map { it.module }).containsExactly("jobs")
    }
}
