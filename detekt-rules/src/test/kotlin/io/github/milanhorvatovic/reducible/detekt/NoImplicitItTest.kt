package io.github.milanhorvatovic.reducible.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class NoImplicitItTest {
    private val rule = NoImplicitIt(Config.empty)

    @Test
    fun an_implicit_it_is_reported_at_every_use() {
        val findings =
            rule.lint(
                """
                fun names(rows: List<String>) = rows.map { it.uppercase() + it }
                """.trimIndent(),
            )
        assertEquals(2, findings.size)
    }

    @Test
    fun a_named_parameter_is_clean() {
        val findings =
            rule.lint(
                """
                fun names(rows: List<String>) = rows.map { row -> row.uppercase() }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }

    @Test
    fun it_inside_a_named_lambda_belongs_to_the_implicit_lambda_around_it() {
        val findings =
            rule.lint(
                """
                fun pairs(rows: List<List<Int>>) = rows.map { it.filter { value -> value > 0 } }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun a_string_template_use_counts() {
        val findings =
            rule.lint(
                """
                fun labels(rows: List<Int>) = rows.map { "row ${'$'}it" }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun a_member_named_it_is_not_the_implicit_parameter() {
        val findings =
            rule.lint(
                """
                class Holder(val it: Int)
                fun read(holder: Holder) = holder.it
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }
}
