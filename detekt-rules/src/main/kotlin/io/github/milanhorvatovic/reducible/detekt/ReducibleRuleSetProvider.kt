package io.github.milanhorvatovic.reducible.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

/**
 * The repository's own detekt rules: the explicit-style decisions no shipped rule expresses.
 * Loaded by every module's detekt task through `detektPlugins`; which rules are active is the
 * config's decision (`config/detekt/detekt.yml`, `config/detekt/compose.yml`).
 */
public class ReducibleRuleSetProvider : RuleSetProvider {
    override val ruleSetId: String = "reducible"

    override fun instance(config: Config): RuleSet =
        RuleSet(
            ruleSetId,
            listOf(
                NoImplicitIt(config),
                NamedComposableLambdas(config),
            ),
        )
}
