package io.github.milanhorvatovic.reducible.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.TestConfig
import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

/** Without type resolution the rule runs on shape: PascalCase callees and the configured builders. */
class NamedComposableLambdasTest {
    private val rule = NamedComposableLambdas(Config.empty)

    @Test
    fun a_trailing_lambda_on_a_composable_is_reported() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Column(modifier = Modifier) {
                        Text("a")
                    }
                }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun a_named_slot_is_clean() {
        val findings =
            rule.lint(
                """
                fun screen() {
                    Column(modifier = Modifier, content = { Text("a") })
                    Button(onClick = { send() }, content = { Text("Retry") })
                }
                """.trimIndent(),
            )
        assertEquals(0, findings.size)
    }

    @Test
    fun configured_lowercase_builders_are_covered_and_other_calls_are_not() {
        val findings =
            rule.lint(
                """
                fun list(rows: List<String>) {
                    items(rows) { row -> Text(row) }
                    rows.forEach { row -> println(row) }
                    lazy { 1 }
                }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }

    @Test
    fun the_builder_list_is_configurable() {
        val configured = NamedComposableLambdas(TestConfig("additionalCallees" to listOf("slot")))
        val findings =
            configured.lint(
                """
                fun list() {
                    slot { Text("a") }
                    items(listOf(1)) { row -> Text("b") }
                }
                """.trimIndent(),
            )
        assertEquals(1, findings.size)
    }
}
