package com.arda.smell_detector.rules

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.arda.smell_detector.helpers.ComposableFunctionDetector
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.uast.UMethod
import slack.lint.compose.util.findChildrenByClass

/**
 * Multiple Flow Collections per Composable
 */
object MultipleFlowCollectionsPerComposableIssue {

    private const val ID = "MultipleFlowCollectionsPerComposable"
    private const val DESCRIPTION = "Composable collects multiple Flow instances via collectAsState"
    private val EXPLANATION = """
        This composable collects multiple Flow instances using collectAsState() or
        collectAsStateWithLifecycle() within the same function. Each collection introduces
        its own observer and recomposition trigger, increasing subscription overhead and
        recomposition frequency.

        This often signals fragmented or uncoordinated state handling and can indicate that
        state aggregation is missing in the ViewModel, violating the single source of truth
        principle. Prefer combining flows at the ViewModel layer and exposing a single
        UI state model to the composable.
    """.trimIndent()

    private val CATEGORY = Category.PERFORMANCE
    private const val PRIORITY = 6
    private val SEVERITY = Severity.WARNING

    val ISSUE: Issue =
        Issue.create(
            id = ID,
            briefDescription = DESCRIPTION,
            explanation = EXPLANATION,
            category = CATEGORY,
            priority = PRIORITY,
            severity = SEVERITY,
            implementation =
            Implementation(
                MultipleFlowCollectionsPerComposableDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
}

/**
 * Detector Implementation: Multiple Flow Collections per Composable
 *
 * Detection logic:
 *  - For each @Composable function, count all KtCallExpression whose callee name is
 *    `collectAsState` or `collectAsStateWithLifecycle`.
 *  - If the total count > MAX_ALLOWED (2), report a warning on the composable.
 *
 * This is a threshold-based approximation of "too many independent Flow collections",
 * aligned with your definition.
 */
class MultipleFlowCollectionsPerComposableDetector :
    ComposableFunctionDetector(), SourceCodeScanner {

    companion object {
        private const val MAX_ALLOWED = 2

        private val FLOW_COLLECTION_NAMES = setOf(
            "collectAsState",
            "collectAsStateWithLifecycle",
        )
    }

    override fun visitComposable(
        context: JavaContext,
        method: UMethod,
        function: KtFunction,
    ) {
        val calls =
            function
                .findChildrenByClass<KtCallExpression>()
                .filter { it.isFlowCollectionCall() }
                .toList()

        if (calls.size <= MAX_ALLOWED) return

        val functionName = function.name ?: method.name
        val message =
            "Composable '$functionName' collects ${calls.size} flows " +
                    "via collectAsState()/collectAsStateWithLifecycle(). " +
                    "Consider aggregating these flows in the ViewModel and exposing a single UI state."

        // Flag the composable as a whole (one warning per composable)
        context.report(
            MultipleFlowCollectionsPerComposableIssue.ISSUE,
            function,
            context.getLocation(function),
            message,
        )
    }

    private fun KtCallExpression.isFlowCollectionCall(): Boolean {
        val name = calleeExpression?.text ?: return false
        return name in FLOW_COLLECTION_NAMES
    }
}