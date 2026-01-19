package com.arda.smell_detector.rules

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import com.arda.smell_detector.helpers.ComposableFunctionDetector
import com.arda.smell_detector.helpers.IMMUTABLE_FACTORIES
import com.arda.smell_detector.helpers.asPsiRecursiveLogString
import com.arda.smell_detector.helpers.isLocallyConstantOrImmutable
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCallableReferenceExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.uast.*
import org.jetbrains.uast.util.isMethodCall
import slack.lint.compose.util.findChildrenByClass

/**
 * Issue Definition
 */
object RememberUpdatedStateWithConstantIssue {

    private const val ID = "RememberUpdatedStateWithConstant"
    private const val DESCRIPTION = "Using rememberUpdatedState with constant or immutable values."
    private val EXPLANATION = """
        Using rememberUpdatedState() for constant or immutable values is unnecessary.
        It is intended for dynamic values that change inside side-effects (LaunchedEffect, DisposableEffect).

        Wrapping constants creates useless snapshot reads and misleads readers about mutability.
        Use the constant directly, or use remember { } if caching is needed.
    """.trimIndent()

    val ISSUE: Issue = Issue.create(
        id = ID,
        briefDescription = DESCRIPTION,
        explanation = EXPLANATION,
        severity = Severity.WARNING,
        category = Category.PERFORMANCE,
        priority = 6,
        implementation = Implementation(
            RememberUpdatedStateWithConstantDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )
    )
}

/**
 * Detector Implementation
 */
class RememberUpdatedStateWithConstantDetector :
    ComposableFunctionDetector(), SourceCodeScanner {

    override fun visitComposable(context: JavaContext, method: UMethod, function: KtFunction) {
        // Look for rememberUpdatedState(...) calls inside this composable function.
        function
            .findChildrenByClass<KtCallExpression>()
            .filter { it.calleeExpression?.text == "rememberUpdatedState" }
            .forEach { callExpression ->
                val argumentExpression =
                    callExpression.valueArguments.firstOrNull()?.getArgumentExpression()
                        ?: return@forEach

                // Exclude obvious lambdas (changing callbacks, event handlers, etc.)
                if (argumentExpression.isLambdaLike()) return@forEach

                // Flag only when the argument is clearly constant / immutable.
                // This now includes the case where the argument is a *parameter* whose call-sites
                // (within this file) all pass constant/immutable values.
                if (argumentExpression.isConstantOrImmutable(function)) {
                    context.report(
                        RememberUpdatedStateWithConstantIssue.ISSUE,
                        callExpression,
                        context.getLocation(callExpression),
                        "rememberUpdatedState used with constant or immutable value.",
                    )
                }
            }
    }

    /**
     * Top-level constant/immutability check that is aware of:
     *  - local/field properties
     *  - literals
     *  - immutable factory calls
     *  - parameters that are *effectively constant* across all call-sites in this file
     */
    private fun KtExpression.isConstantOrImmutable(owner: KtFunction): Boolean {
        return when (this) {
            is KtNameReferenceExpression -> {
                val resolved = references.firstOrNull()?.resolve()
                when (resolved) {
                    is KtProperty -> isLocallyConstantOrImmutable()
                    is KtParameter -> isParameterConstantAcrossCalls(owner, resolved)
                    else -> false
                }
            }
            is KtCallableReferenceExpression -> true // Function references like ::onClick are constants
            else -> isLocallyConstantOrImmutable()
        }
    }

    /**
     * Local view of "constant or immutable":
     *  - literals (numbers, booleans, chars, etc.)
     *  - simple string literals
     *  - const val or val with a constant initializer
     *  - calls to known immutable factory functions (listOf, setOf, etc.) whose args are constant
     *
     * This does *not* attempt to reason about parameters.
     */

    /**
     * Determines whether a [parameter] of [owner] is "effectively constant":
     *
     * We look at all call-sites of [owner] in the same Kotlin file and verify that:
     *  - the corresponding argument is present, and
     *  - the argument is locally constant/immutable (literal, const, val with constant initializer,
     *    immutable factory call, etc.).
     *
     * If *every* call-site we can see passes a constant/immutable argument and we find at least one
     * such call-site, then we treat this parameter as constant.
     *
     * This enables detection of cases like:
     *
     *   @Composable
     *   fun Parent() {
     *     Child("Hello")
     *   }
     *
     *   @Composable
     *   fun Child(msg: String) {
     *     rememberUpdatedState(msg) // <- flagged, msg is always passed a constant here
     *   }
     */
    private fun isParameterConstantAcrossCalls(
        owner: KtFunction,
        parameter: KtParameter,
    ): Boolean {
        val parameterIndex = owner.valueParameters.indexOfFirst { it == parameter }
        if (parameterIndex == -1) return false

        val containingFile = owner.containingKtFile ?: return false

        val callSites =
            containingFile
                .findChildrenByClass<KtCallExpression>()
                .filter { call ->
                    // Ensure we're resolving to this exact function, not just any same-named symbol.
                    val calleeRef = call.calleeExpression?.references?.firstOrNull()
                    calleeRef?.resolve() == owner
                }
                .toList()

        if (callSites.isEmpty()) return false

        var sawAtLeastOneArgument = false

        for (call in callSites) {
            val argExpression = call.valueArguments.getOrNull(parameterIndex)?.getArgumentExpression()
                ?: return false // missing argument → can't guarantee const-ness

            sawAtLeastOneArgument = true

            // For arguments we only use the local constant logic to avoid recursive parameter
            // reasoning (e.g., recursive calls).
            if (!argExpression.isLocallyConstantOrImmutable()) {
                return false
            }
        }

        return sawAtLeastOneArgument
    }

    /** Excludes interpolated strings; only treats `"literal"` as constant. */


    /** Simple check for lambda-ish arguments we want to allow. */
    private fun KtExpression.isLambdaLike(): Boolean {
        return this is KtLambdaExpression || this is KtNamedFunction
    }
}