package io.github.milanhorvatovic.reducible.detekt

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.psiUtil.parents

/**
 * Reports every use of the implicit lambda parameter `it`. A lambda names what it receives —
 * `{ envelope -> envelope.effect }` — so the reader learns the type of the thing from its
 * name rather than from the receiver two calls back. Needs no type resolution: `it` inside a
 * lambda that declares no parameters is the implicit parameter by the language's definition,
 * and a lambda that declares parameters has no `it` of its own.
 */
public class NoImplicitIt(
    config: Config,
) : Rule(config) {
    override val issue: Issue =
        Issue(
            id = javaClass.simpleName,
            severity = Severity.Style,
            description = "Lambda parameters are named; the implicit `it` is not used.",
            debt = Debt.FIVE_MINS,
        )

    override fun visitReferenceExpression(expression: KtReferenceExpression) {
        super.visitReferenceExpression(expression)
        val reference = expression as? KtNameReferenceExpression ?: return
        if (reference.getReferencedName() != "it" || reference.isSelectorOfQualifiedExpression()) return
        val owner = reference.implicitLambdaOwner() ?: return
        report(
            CodeSmell(
                issue,
                Entity.from(reference),
                "Name the parameter of the lambda starting at line ${owner.lineNumber()} instead of using `it`.",
            ),
        )
    }

    /** `foo.it` is a member access, not the implicit parameter. */
    private fun KtNameReferenceExpression.isSelectorOfQualifiedExpression(): Boolean =
        (parent as? KtDotQualifiedExpression)?.selectorExpression == this

    /**
     * The lambda whose implicit parameter this `it` is: the nearest enclosing lambda that
     * declares none. A lambda with declared parameters is skipped, since `it` cannot be its
     * parameter and must belong to a lambda further out.
     */
    private fun KtNameReferenceExpression.implicitLambdaOwner(): KtLambdaExpression? =
        parents.filterIsInstance<KtLambdaExpression>().firstOrNull { lambda -> lambda.valueParameters.isEmpty() }

    private fun KtLambdaExpression.lineNumber(): Int {
        val document = containingFile.viewProvider.document ?: return 0
        return document.getLineNumber(textOffset) + 1
    }
}
