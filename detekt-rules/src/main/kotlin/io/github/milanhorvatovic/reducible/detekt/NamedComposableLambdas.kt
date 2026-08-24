package io.github.milanhorvatovic.reducible.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import io.gitlab.arturbosch.detekt.api.config
import io.gitlab.arturbosch.detekt.api.internal.Configuration
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.util.getResolvedCall

/**
 * Reports a trailing lambda on a call to a composable, or to a Compose builder whose lambda
 * is composable content: the slot is passed by its parameter name instead —
 * `Button(onClick = { … }, content = { Text("Retry") })`, `items(items = rows, itemContent = { row -> … })`.
 *
 * With type resolution the callee and the parameter the trailing lambda binds to are checked
 * for `@Composable`, which is exact. Without it the rule falls back to what Compose code looks
 * like: a PascalCase callee, or one of [additionalCallees], the lowercase builders and
 * runtime helpers that take composable content. That fallback is right for a Compose module
 * and wrong for one full of `Reducer { … }`-style SAM constructors, which is why the rule is
 * activated per module (`config/detekt/compose.yml`), not in the shared config.
 */
public class NamedComposableLambdas(
    config: Config,
) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = javaClass.simpleName,
            severity = Severity.Style,
            description = "Lambdas passed to composables go by parameter name, not as trailing lambdas.",
            debt = Debt.FIVE_MINS,
        )

    @Configuration("callees that take composable content without a PascalCase name")
    private val additionalCallees: List<String> by config(
        listOf("items", "itemsIndexed", "item", "composable", "navigation", "viewModel", "remember", "rememberSaveable", "navArgument"),
    )

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val trailing = expression.lambdaArguments.firstOrNull() ?: return
        val callee = expression.calleeExpression?.text ?: return
        if (!expression.takesComposableContent(callee)) return
        report(
            CodeSmell(
                issue,
                Entity.from(trailing),
                "Pass the lambda to `$callee` by its parameter name instead of as a trailing lambda.",
            ),
        )
    }

    private fun KtCallExpression.takesComposableContent(callee: String): Boolean {
        if (bindingContext != BindingContext.EMPTY) {
            val resolved = getResolvedCall(bindingContext) ?: return false
            val descriptor = resolved.resultingDescriptor
            if (descriptor.annotations.hasAnnotation(COMPOSABLE)) return true
            val lastParameter = descriptor.valueParameters.lastOrNull() ?: return false
            return lastParameter.type.annotations.hasAnnotation(COMPOSABLE)
        }
        return callee.first().isUpperCase() || callee in additionalCallees
    }

    private companion object {
        val COMPOSABLE = FqName("androidx.compose.runtime.Composable")
    }
}
