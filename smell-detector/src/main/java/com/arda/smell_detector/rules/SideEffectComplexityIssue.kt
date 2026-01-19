package com.arda.smell_detector.rules


import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.arda.smell_detector.helpers.ComposableFunctionDetector
import com.intellij.psi.PsiType
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.toUElementOfType
import slack.lint.compose.util.findChildrenByClass
import slack.lint.compose.util.isRestartableEffect

/**
 * SideEffect Complexity (SEC)
 *
 * Flags LaunchedEffect / DisposableEffect / SideEffect blocks whose internal structure
 * is overly complex: many branches, loops, deep nesting, or many nested launched scopes.
 */
object SideEffectComplexityIssue {

    private const val ID = "SideEffectComplexity"
    private const val DESCRIPTION = "Side-effect block inside composable is too complex."
    private val EXPLANATION = """
        Measures the structural and logical complexity of LaunchedEffect, DisposableEffect,
        produceState or SideEffect blocks in composables. High SEC values indicate that the
        side effect is performing excessive computation, control flow, or state orchestration,
        rather than short, declarative UI synchronization.

        Side effects in Compose should handle lightweight, lifecycle-bound synchronization tasks,
        not host business or data logic. Excessive complexity harms testability, hides logic flow,
        and makes recompositions unpredictable, since these blocks may re-run under subtle key
        changes or recomposition triggers.

        Refactor heavy computation, data handling, or event orchestration out of the side effect
        into the ViewModel or repository layer. Keep side effects minimal and focused.
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
                SideEffectComplexityDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
}

/**
 * Detector Implementation: SideEffect Complexity (SEC)
 *
 * We look for:
 *   LaunchedEffect(...)
 *   DisposableEffect(...)
 *   SideEffect { ... }
 *   produceState { ... }, produceRetainedState { ... }
 *
 * For each of their lambda blocks, we compute a simple SEC score:
 *
 *   SEC = BRANCH_WEIGHT * (# if/when)
 *       + LOOP_WEIGHT   * (# loops)
 *       + DEPTH_WEIGHT  * (max nesting depth)
 *       + SCOPE_WEIGHT  * (# nested launched scopes/effects)
 *       + LOC_WEIGHT    * approxLOC
 *
 * If SEC >= THRESHOLD, we report a warning on the specific side-effect call.
 */
class SideEffectComplexityDetector :
    ComposableFunctionDetector(),
    SourceCodeScanner {

    companion object {
        // Effect names to analyze
        private val EFFECT_NAMES =
            setOf(
                "LaunchedEffect",
                "DisposableEffect",
                "SideEffect",
                "produceState",
                "produceRetainedState",
            )

        // Weights & threshold for SEC (heuristic, tune as needed)
        private const val BRANCH_WEIGHT = 2
        private const val LOOP_WEIGHT = 3
        private const val DEPTH_WEIGHT = 2
        private const val SCOPE_WEIGHT = 3
        private const val LOC_DIVISOR = 10 // 1 point per ~10 LOC
        private const val SEC_THRESHOLD = 10

        /**
         * Exposes SEC score computation so other detectors (e.g., CFC) can
         * reuse the exact same logic and stay in sync.
         */
        fun computeSecScore(call: KtCallExpression): Int {
            val lambda = call.lambdaArguments.firstOrNull()?.getLambdaExpression() ?: return 0
            val body = lambda.bodyExpression as? KtBlockExpression ?: return 0

            fun computeDepth(expr: KtExpression, current: Int): Int {
                var maxDepth = current
                val isControl =
                    expr is KtIfExpression ||
                            expr is KtWhenExpression ||
                            expr is KtForExpression ||
                            expr is KtWhileExpression ||
                            expr is KtDoWhileExpression
                val next = if (isControl) current + 1 else current
                if (next > maxDepth) maxDepth = next
                expr.children.filterIsInstance<KtExpression>().forEach { child ->
                    val d = computeDepth(child, next)
                    if (d > maxDepth) maxDepth = d
                }
                return maxDepth
            }

            fun countStatements(block: KtBlockExpression): Int {
                fun countIfElseChainLocal(ifExpr: KtIfExpression): Int {
                    var cnt = 1
                    ifExpr.then?.let {
                        cnt += if (it is KtBlockExpression) countStatements(it) else 1
                    }
                    ifExpr.`else`?.let { elseExpr ->
                        cnt += when (elseExpr) {
                            is KtIfExpression -> countIfElseChainLocal(elseExpr)
                            is KtBlockExpression -> countStatements(elseExpr)
                            else -> 1
                        }
                    }
                    return cnt
                }

                val stmts = block.statements ?: return 0
                var count = 0
                for (raw in stmts) {
                    val stmt = (raw as? KtParenthesizedExpression)?.expression ?: raw
                    when (stmt) {
                        is KtBlockExpression -> count += countStatements(stmt)
                        is KtIfExpression -> {
                            count++ // the if itself
                            stmt.then?.let {
                                count += if (it is KtBlockExpression) countStatements(it) else 1
                            }
                            stmt.`else`?.let { elseExpr ->
                                count += when (elseExpr) {
                                    is KtIfExpression -> countIfElseChainLocal(elseExpr)
                                    is KtBlockExpression -> countStatements(elseExpr)
                                    else -> 1
                                }
                            }
                        }
                        is KtWhenExpression -> {
                            count++
                            stmt.entries.forEach { entry ->
                                entry.expression?.let { expr ->
                                    count += if (expr is KtBlockExpression) countStatements(expr) else 1
                                }
                            }
                        }
                        is KtForExpression -> {
                            count++
                            stmt.body?.let { bodyStmt ->
                                count += if (bodyStmt is KtBlockExpression) countStatements(bodyStmt) else 1
                            }
                        }
                        is KtWhileExpression -> {
                            count++
                            stmt.body?.let { bodyStmt ->
                                count += if (bodyStmt is KtBlockExpression) countStatements(bodyStmt) else 1
                            }
                        }
                        is KtDoWhileExpression -> {
                            count++
                            stmt.body?.let { bodyStmt ->
                                count += if (bodyStmt is KtBlockExpression) countStatements(bodyStmt) else 1
                            }
                        }
                        is KtLambdaExpression -> {
                            count++
                            val lb = stmt.bodyExpression
                            if (lb is KtBlockExpression) count += countStatements(lb) else if (lb != null) count++
                        }
                        is KtCallExpression -> {
                            count++
                            stmt.lambdaArguments.forEach { lambdaArg ->
                                val l = lambdaArg.getLambdaExpression()
                                l?.bodyExpression?.let { bodyExpr ->
                                    if (bodyExpr is KtBlockExpression) count += countStatements(bodyExpr) else count++
                                }
                            }
                        }
                        else -> count++
                    }
                }
                return count
            }

            val branches =
                body.findChildrenByClass<KtIfExpression>().count() +
                        body.findChildrenByClass<KtWhenExpression>().count()

            val loops =
                body.findChildrenByClass<KtForExpression>().count() +
                        body.findChildrenByClass<KtWhileExpression>().count() +
                        body.findChildrenByClass<KtDoWhileExpression>().count()

            val statements = countStatements(body)

            val launchedScopes =
                body.findChildrenByClass<KtCallExpression>()
                    .count { call ->
                        val name = call.calleeExpression?.text ?: ""
                        name in setOf("launch", "async") || call.isRestartableEffect || name == "SideEffect"
                    }

            val maxDepth = computeDepth(body, 0)

            return BRANCH_WEIGHT * branches +
                    LOOP_WEIGHT * loops +
                    DEPTH_WEIGHT * maxDepth +
                    SCOPE_WEIGHT * launchedScopes +
                    (statements / LOC_DIVISOR)
        }
    }

    override fun visitComposable(
        context: JavaContext,
        method: UMethod,
        function: KtFunction,
    ) {
        function
            .findChildrenByClass<KtCallExpression>()
            .filter { it.isSideEffectCall() }
            .forEach { effectCall ->
                val lambda = effectCall.lambdaArguments.firstOrNull()?.getLambdaExpression()
                    ?: return@forEach

                val complexity = lambda.computeSideEffectComplexity()

                if (complexity.score >= SEC_THRESHOLD) {
                    val name = effectCall.calleeExpression?.text ?: "side-effect"
                    val msg =
                        buildString {
                            append(
                                "Side-effect '$name' has high structural complexity (SEC=${complexity.score}). " +
                                        "Branches=${complexity.branches}, loops=${complexity.loops}, " +
                                "depth=${complexity.maxNestingDepth}, nestedScopes=${complexity.launchedScopes}, " +
                                        "statements=${complexity.statements}"
                            )
                            append(" Move business/data logic to ViewModel or helpers and keep side-effects minimal.")
                        }

                    context.report(
                        SideEffectComplexityIssue.ISSUE,
                        effectCall,
                        context.getLocation(effectCall),
                        msg,
                    )
                }
            }
    }

    private fun KtCallExpression.isSideEffectCall(): Boolean {
        val name = calleeExpression?.text ?: return false
        if (name in EFFECT_NAMES) return true
        // Also treat known restartable effects (LaunchedEffect, DisposableEffect, etc.)
        return isRestartableEffect
    }

    // Data class to keep track of components of SEC.
    private data class Complexity(
        val score: Int,
        val branches: Int,
        val loops: Int,
        val maxNestingDepth: Int,
        val launchedScopes: Int,
        val statements: Int,
    )

    /**
     * Compute SEC for the body of a side-effect lambda.
     */
    private fun KtLambdaExpression.computeSideEffectComplexity(): Complexity {
        val body =
            bodyExpression as? KtBlockExpression ?: return Complexity(0, 0, 0, 0, 0, 0)

        val branches =
            body.findChildrenByClass<KtIfExpression>().count() +
                    body.findChildrenByClass<KtWhenExpression>().count()

        val loops =
            body.findChildrenByClass<KtForExpression>().count() +
                    body.findChildrenByClass<KtWhileExpression>().count() +
                    body.findChildrenByClass<KtDoWhileExpression>().count()

        // Statement counting aligned with ComposableFunctionComplexityIssue
        val statements = body.computeStatementCount()

        val launchedScopes =
            body.findChildrenByClass<KtCallExpression>()
                .count { it.isLaunchedScopeOrEffect() }

        val maxDepth = body.computeControlNestingDepth()

        val score =
            BRANCH_WEIGHT * branches +
                    LOOP_WEIGHT * loops +
                    DEPTH_WEIGHT * maxDepth +
                    SCOPE_WEIGHT * launchedScopes +
                    (statements / LOC_DIVISOR)

        return Complexity(
            score = score,
            branches = branches,
            loops = loops,
            maxNestingDepth = maxDepth,
            launchedScopes = launchedScopes,
            statements = statements,
        )
    }

    /**
     * Approximate nesting depth of control structures (if/when/loops) in this subtree.
     */
    private fun KtBlockExpression.computeControlNestingDepth(): Int {
        return computeNestingDepthInternal(this, 0)
    }

    private fun computeNestingDepthInternal(element: KtExpression, current: Int): Int {
        var maxDepth = current

        val isControl =
            element is KtIfExpression ||
                    element is KtWhenExpression ||
                    element is KtForExpression ||
                    element is KtWhileExpression ||
                    element is KtDoWhileExpression

        val nextDepth = if (isControl) current + 1 else current

        if (nextDepth > maxDepth) {
            maxDepth = nextDepth
        }

        element.children
            .filterIsInstance<KtExpression>()
            .forEach { child ->
                val childDepth = computeNestingDepthInternal(child, nextDepth)
                if (childDepth > maxDepth) {
                    maxDepth = childDepth
                }
            }

        return maxDepth
    }

    // --- Statement counting (mirrors ComposableFunctionComplexityIssue) ---
    private fun KtBlockExpression.computeStatementCount(): Int {
        return computeStatementsRecursively(this)
    }

    private fun countIfElseChain(ifExpr: KtIfExpression): Int {
        var count = 1 // Count the if/else-if statement itself

        // Count then block
        ifExpr.then?.let {
            if (it is KtBlockExpression) {
                count += computeStatementsRecursively(it)
            } else {
                count++ // Single expression counts as 1
            }
        }

        // Count else block - recursively handle else-if chains
        ifExpr.`else`?.let { elseExpr ->
            when (elseExpr) {
                is KtIfExpression -> {
                    // else if - recursively count the nested if-else chain
                    count += countIfElseChain(elseExpr)
                }
                is KtBlockExpression -> {
                    count += computeStatementsRecursively(elseExpr)
                }
                else -> {
                    count++ // Single expression counts as 1
                }
            }
        }

        return count
    }

    private fun computeStatementsRecursively(block: KtBlockExpression): Int {
        val statements = block.statements ?: return 0
        var count = 0

        for (raw in statements) {
            // Count the statement itself (except for block expressions which are containers)
            val stmt = (raw as? KtParenthesizedExpression)?.expression ?: raw
            when (stmt) {
                is KtBlockExpression -> {
                    // Block expression itself doesn't count, only its contents
                    count += computeStatementsRecursively(stmt)
                }
                is KtIfExpression -> {
                    count++ // Count the if statement itself
                    // Count then block
                    stmt.then?.let {
                        if (it is KtBlockExpression) {
                            count += computeStatementsRecursively(it)
                        } else {
                            count++ // Single expression counts as 1
                        }
                    }
                    // Count else block - handle else if chains recursively
                    stmt.`else`?.let { elseExpr ->
                        when (elseExpr) {
                            is KtIfExpression -> {
                                // else if - recursively count the nested if-else chain
                                count += countIfElseChain(elseExpr)
                            }
                            is KtBlockExpression -> {
                                count += computeStatementsRecursively(elseExpr)
                            }
                            else -> {
                                count++ // Single expression counts as 1
                            }
                        }
                    }
                }
                is KtWhenExpression -> {
                    count++ // Count the when statement itself
                    // Count all when entries
                    stmt.entries.forEach { entry ->
                        entry.expression?.let { expr ->
                            if (expr is KtBlockExpression) {
                                count += computeStatementsRecursively(expr)
                            } else {
                                count++ // Single expression counts as 1
                            }
                        }
                    }
                }
                is KtForExpression -> {
                    count++ // Count the for statement itself
                    stmt.body?.let { body ->
                        if (body is KtBlockExpression) {
                            count += computeStatementsRecursively(body)
                        } else {
                            count++ // Single expression counts as 1
                        }
                    }
                }
                is KtWhileExpression -> {
                    count++ // Count the while statement itself
                    stmt.body?.let { body ->
                        if (body is KtBlockExpression) {
                            count += computeStatementsRecursively(body)
                        } else {
                            count++ // Single expression counts as 1
                        }
                    }
                }
                is KtDoWhileExpression -> {
                    count++ // Count the do-while statement itself
                    stmt.body?.let { body ->
                        if (body is KtBlockExpression) {
                            count += computeStatementsRecursively(body)
                        } else {
                            count++ // Single expression counts as 1
                        }
                    }
                }
                is KtLambdaExpression -> {
                    count++ // Count the lambda expression itself
                    val lambdaBody = stmt.bodyExpression
                    if (lambdaBody is KtBlockExpression) {
                        count += computeStatementsRecursively(lambdaBody)
                    } else if (lambdaBody != null) {
                        count++ // Single expression counts as 1
                    }
                }
                is KtCallExpression -> {
                    // Count the call expression itself
                    count++

                    // Process all lambda arguments (for composables like Column { ... }, forEach { ... }, etc.)
                    stmt.lambdaArguments.forEach { lambdaArg ->
                        val lambdaExpr = lambdaArg.getLambdaExpression()
                        lambdaExpr?.bodyExpression?.let { body ->
                            if (body is KtBlockExpression) {
                                count += computeStatementsRecursively(body)
                            } else if (body != null) {
                                count++ // Single expression in lambda counts as 1
                            }
                        }
                    }
                }
                else -> {
                    // All other statements count as 1
                    count++
                }
            }
        }

        return count
    }

    private fun KtCallExpression.isLaunchedScopeOrEffect(): Boolean {
        val name = calleeExpression?.text ?: return false
        return name in setOf("launch", "async") || isRestartableEffect || name == "SideEffect"
    }

    // If you ever need it: convenience to inspect type of an expression.
    @Suppress("unused")
    private fun KtExpression.expressionPsiType(): PsiType? {
        val uExpr = toUElementOfType<UExpression>() ?: return null
        return uExpr.getExpressionType()
    }
}