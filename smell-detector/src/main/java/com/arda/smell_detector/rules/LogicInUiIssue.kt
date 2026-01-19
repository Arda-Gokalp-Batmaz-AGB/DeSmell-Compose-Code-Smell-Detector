package com.arda.smell_detector.rules

import com.android.tools.lint.client.api.UElementHandler

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UBreakExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UContinueExpression
import org.jetbrains.uast.UDoWhileExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.UForEachExpression
import org.jetbrains.uast.UForExpression
import org.jetbrains.uast.UIfExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UReturnExpression
import org.jetbrains.uast.USwitchClauseExpression
import org.jetbrains.uast.USwitchExpression
import org.jetbrains.uast.UThrowExpression
import org.jetbrains.uast.UTryExpression
import org.jetbrains.uast.UWhileExpression
import org.jetbrains.uast.visitor.AbstractUastVisitor
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.kotlin.psi.KtFunction
import slack.lint.compose.util.ComposableBody
import slack.lint.compose.util.extractComposableBody
import slack.lint.compose.util.isComposable
import slack.lint.compose.util.isComposableFunction

/**
 * Logic in UI (LIU) – too much imperative control-flow inside a composable.
 * 
 * LIU measures the density of control-flow logic that pushes a composable toward procedural orchestration
 * rather than declarative composition. The detection differentiates between render-oriented control-flow
 * (which maps state to UI structure) and behavioral control-flow (which orchestrates interaction logic
 * inside callbacks/modifiers). Both forms contribute to LIU, but behavioral control-flow is weighted
 * lower to reflect its auxiliary role in UI behavior rather than UI structure.
 */
object LogicInUiIssue {

    private const val ID = "LogicInUiDensity"
    private const val DESCRIPTION = "Too much control-flow logic inside a composable UI"
    private val EXPLANATION = """
        Measures the density of control-flow logic inside a composable body. High LIU suggests 
        imperative decision-making embedded in UI instead of ViewModel, derived state, or slot-based composition.
        
        Control flow counted:
        - Conditional branching: if, else-if (each counts as one), when (number of entries - 1, excluding else)
        - Iteration/looping: for, while, do-while, functional iteration (forEach, map, filter, etc. when containing lambdas)
        - Non-linear flow interruptions: break, continue, throw
        - Exception handling: try, catch (each catch counts), finally
        
        The detection does not exclude control-flow inside callback or modifier lambdas. Instead, it 
        differentiates between render-oriented control-flow (which maps state to UI structure) and 
        behavioral control-flow (which orchestrates interaction logic). Both forms contribute to LIU, 
        but behavioral control-flow is weighted lower (0.5x) to reflect its auxiliary role.
        
        LIU = (CF_render + 0.5 × CF_behavior) / statements
        where CF_render = control-flow in composable body & layout lambdas
        and CF_behavior = control-flow inside callbacks / modifier lambdas
    """.trimIndent()

    // Tuned to reduce false positives for simple UI conditionals
    internal const val MIN_STATEMENTS_FOR_ANALYSIS = 5   // Ignore very small composables
    internal const val LIU_THRESHOLD = 0.3f               // LIU > 0.3 → likely logic-heavy UI (increased from 0.2)
    internal const val MIN_CONTROL_FLOW_COUNT = 2         // Require at least 2 control flow statements
    internal const val MAX_CONTROL_FLOW_COUNT = 6         // Maximum allowed control flow statements

    private val CATEGORY = Category.CORRECTNESS
    private const val PRIORITY = 5
    private val SEVERITY = Severity.WARNING

    val ISSUE: Issue = Issue.create(
        id = ID,
        briefDescription = DESCRIPTION,
        explanation = EXPLANATION,
        category = CATEGORY,
        priority = PRIORITY,
        severity = SEVERITY,
        implementation = Implementation(
            LogicInUiDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )
    )
}

/**
 * Detector for Logic in UI density.
 *
 * LIU = (CF_render + 0.5 × CF_behavior) / statements
 * 
 * Control flow counted:
 * - Conditional branching: if, else-if (each counts as one), when (number of entries - 1, excluding else)
 * - Iteration/looping: for, while, do-while, functional iteration (forEach, map, filter, etc. when containing lambdas)
 * - Non-linear flow interruptions: break, continue, throw
 * - Exception handling: try, catch (each catch counts), finally
 * 
 * Control flow categories:
 * - Render control flow: above constructs in composable body & layout lambdas (full weight = 1.0)
 * - Behavioral control flow: same constructs inside callbacks/modifiers (reduced weight = 0.5x)
 * 
 * statements = all expressions that appear as statements in blocks.
 * 
 * The detection differentiates by semantic role (render vs behavioral), not by location exclusion.
 * This ensures that procedural orchestration in callbacks/modifiers is still flagged, but with
 * appropriate weighting to avoid false positives for simple interaction logic.
 */
class LogicInUiDetector : Detector(), SourceCodeScanner {
    
    companion object {
        // Weight for behavioral control flow (inside callbacks/modifiers)
        private const val BEHAVIORAL_CONTROL_FLOW_WEIGHT = 0.5f
        
        // UI state types that are acceptable to check in when/if (not considered business logic)
        private val ACCEPTABLE_UI_STATE_TYPES = setOf(
            "ImageLoadState",
            "LoadingState",
            "AsyncImagePainter.State",
            "LazyListState",
            "ScrollState",
            "SwipeableState",
            "AnimatedVisibilityScope",
            "DisposableEffectResult"
        )
        
        // Callback parameter names that should NOT count IF statements
        private val CALLBACK_PARAMETER_NAMES = setOf(
            "onClick", "onValueChange", "onCheckedChange", "onDismissRequest",
            "onKeyEvent", "onKey", "onFocus", "onFocusChange",
            "onDrag", "onScroll", "onClickOutside", "onBackPressed",
            "onRequestDismiss", "onConfirm", "onDismiss",
            "onLongClick", "onDoubleClick", "onPress", "onRelease",
            "onStart", "onStop", "onPause", "onResume",
            "onError", "onSuccess", "onFailure", "onComplete",
            "onCancel", "onAccept", "onReject",
            "validator", "onValidate", "onChange", "onUpdate"
        )
        
        // Modifier extension functions that accept lambdas (should NOT count IFs)
        private val MODIFIER_LAMBDA_FUNCTIONS = setOf(
            "pointerInput", "draggable", "scrollable", "swipeable",
            "detectTapGestures", "detectDragGestures", "detectTransformGestures",
            "onSizeChanged", "onGloballyPositioned", "onPlaced",
            "onKeyEvent", "onFocusEvent", "onRotate", "onScale"
        )
        
        // Functions that accept non-UI lambdas (should NOT count IFs)
        private val NON_UI_LAMBDA_FUNCTIONS = setOf(
            "remember", "rememberUpdatedState", "rememberSaveable",
            "LaunchedEffect", "DisposableEffect", "SideEffect",
            "produceState", "collectAsState", "collectAsStateWithLifecycle"
        )
    }

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {

            override fun visitMethod(node: UMethod) {
                // Only analyze @Composable functions (including extension functions)
                if (!node.isComposableFunction()) return

                // Extract body using PSI (handles both block-body and expression-body)
                val ktFunction = node.sourcePsi as? KtFunction ?: return
                val composableBody = ktFunction.extractComposableBody() ?: return
                
                // Get the UAST representation of the body block
                // For block bodies, use the UAST body directly
                // For expression bodies, convert the PSI lambda body to UAST
                val body = when (composableBody) {
                    is ComposableBody.BlockBody -> {
                        // Use UAST body directly for block bodies (more reliable)
                        node.uastBody as? UBlockExpression
                    }
                    is ComposableBody.ExpressionBody -> {
                        // Convert PSI lambda body to UAST
                        composableBody.lambdaBody.toUElementOfType<UBlockExpression>()
                    }
                } ?: return

                val counters = LogicCounters()
                val visitedBlocks = mutableSetOf<UBlockExpression>()
                
                // Count all statements recursively according to Kotlin compiler-level rules:
                // - Each val/var declaration counts as 1 statement
                // - Each composable or function call counts as 1 statement
                // - Each control-flow structure counts as 1 statement (even if empty)
                // - Branch contents may contain additional statements
                // 
                // Control flow is categorized as:
                // - Render: in composable body & layout lambdas (full weight = 1.0)
                // - Behavioral: in callbacks/modifiers (reduced weight = 0.5)
                body.accept(object : AbstractUastVisitor() {
                    // Count all expressions in blocks as statements (recursively)
                    override fun visitBlockExpression(node: UBlockExpression): Boolean {
                        // Count all expressions in this block as statements (only once)
                        if (visitedBlocks.add(node)) {
                            counters.totalStatements += node.expressions.size
                        }
                        return super.visitBlockExpression(node)
                    }

                    // Count control flow structures for LIU calculation
                    // (they're already counted as statements via visitBlockExpression)
                    override fun visitIfExpression(node: UIfExpression): Boolean {
                        // Skip acceptable UI state checks (these are baseline declarative logic)
                        if (isAcceptableUIStateCheck(node)) {
                            // Still count statements inside, but don't count the control flow itself
                            node.thenExpression?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                            node.elseExpression?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                            return super.visitIfExpression(node)
                        }
                        
                        // Determine if this is behavioral (inside callback/modifier) or render control flow
                        val isBehavioral = isBehavioralControlFlow(node)
                        
                        // Count if/else-if (each if counts as one decision point)
                        // else-if is represented as another UIfExpression, which will be visited separately
                        // Do NOT count else blocks separately
                        if (isBehavioral) {
                            counters.behavioralControlFlow++
                        } else {
                            counters.renderControlFlow++
                        }
                        
                        // Explicitly count statements inside if branches
                        node.thenExpression?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                        node.elseExpression?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                        
                        return super.visitIfExpression(node)
                    }

                    override fun visitSwitchExpression(node: USwitchExpression): Boolean {
                        // Skip acceptable UI state checks (these are baseline declarative logic)
                        if (isAcceptableUIStateCheck(node)) {
                            return super.visitSwitchExpression(node)
                        }
                        
                        // Determine if this is behavioral (inside callback/modifier) or render control flow
                        val isBehavioral = isBehavioralControlFlow(node)
                        
                        // Count when entries: (number of entries - 1), excluding else
                        // else clauses typically have empty conditions
                        val clauses = node.body?.expressions?.filterIsInstance<USwitchClauseExpression>() ?: emptyList()
                        val entriesWithConditions = clauses.count { clause -> clause.caseValues.isNotEmpty() }
                        
                        // Count as (number of entries - 1) - this represents decision points
                        // For example: when with 3 cases = 2 decision points, when with 1 case = 0 decision points
                        if (entriesWithConditions > 0) {
                            val decisionPoints = entriesWithConditions - 1
                            if (decisionPoints > 0) {
                                if (isBehavioral) {
                                    counters.behavioralControlFlow += decisionPoints
                                } else {
                                    counters.renderControlFlow += decisionPoints
                                }
                            }
                        }
                        
                        // Statements in clause bodies will be counted when visitSwitchClauseExpression
                        // visits them, or when visitBlockExpression visits blocks inside clauses
                        return super.visitSwitchExpression(node)
                    }

                    override fun visitSwitchClauseExpression(node: USwitchClauseExpression): Boolean {
                        // Don't count here - already counted in visitSwitchExpression
                        // This prevents double-counting. Blocks inside clauses will be visited
                        // by the natural visitor traversal when we call super.visitSwitchClauseExpression
                        return super.visitSwitchClauseExpression(node)
                    }

                    override fun visitForExpression(node: UForExpression): Boolean {
                        val isBehavioral = isBehavioralControlFlow(node)
                        if (isBehavioral) {
                            counters.behavioralControlFlow++
                        } else {
                            counters.renderControlFlow++
                        }
                        // Count statements inside for loop body
                        node.body?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                        return super.visitForExpression(node)
                    }

                    override fun visitForEachExpression(node: UForEachExpression): Boolean {
                        val isBehavioral = isBehavioralControlFlow(node)
                        if (isBehavioral) {
                            counters.behavioralControlFlow++
                        } else {
                            counters.renderControlFlow++
                        }
                        // Count statements inside forEach loop body
                        node.body?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                        return super.visitForEachExpression(node)
                    }

                    override fun visitWhileExpression(node: UWhileExpression): Boolean {
                        val isBehavioral = isBehavioralControlFlow(node)
                        if (isBehavioral) {
                            counters.behavioralControlFlow++
                        } else {
                            counters.renderControlFlow++
                        }
                        // Count statements inside while loop body
                        node.body?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                        return super.visitWhileExpression(node)
                    }

                    override fun visitDoWhileExpression(node: UDoWhileExpression): Boolean {
                        val isBehavioral = isBehavioralControlFlow(node)
                        if (isBehavioral) {
                            counters.behavioralControlFlow++
                        } else {
                            counters.renderControlFlow++
                        }
                        // Count statements inside do-while loop body
                        node.body?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                        return super.visitDoWhileExpression(node)
                    }

                    override fun visitLambdaExpression(node: ULambdaExpression): Boolean {
                        // Count statements in lambda body (for composables like Column { ... }, forEach { ... }, etc.)
                        // This ensures statements inside lambda bodies are counted
                        node.body?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                        return super.visitLambdaExpression(node)
                    }
                    
                    override fun visitCallExpression(node: UCallExpression): Boolean {
                        // Detect functional iteration methods (forEach, map, filter, etc.)
                        // Only count when they contain lambdas (imperative usage)
                        val methodName = node.methodName
                        if (methodName != null && isIterationMethod(methodName)) {
                            // Check if any argument is a lambda (imperative usage)
                            val hasLambda = node.valueArguments.any { arg -> arg is ULambdaExpression }
                            if (hasLambda) {
                                val isBehavioral = isBehavioralControlFlow(node)
                                if (isBehavioral) {
                                    counters.behavioralControlFlow++
                                } else {
                                    counters.renderControlFlow++
                                }
                                // Count statements inside the lambda argument (forEach { ... })
                                node.valueArguments.forEach { arg ->
                                    if (arg is ULambdaExpression) {
                                        arg.body?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                                    }
                                }
                            }
                        }
                        return super.visitCallExpression(node)
                    }
                    
                    // Non-linear flow interruptions: break, continue, throw
                    override fun visitBreakExpression(node: UBreakExpression): Boolean {
                        val isBehavioral = isBehavioralControlFlow(node)
                        if (isBehavioral) {
                            counters.behavioralControlFlow++
                        } else {
                            counters.renderControlFlow++
                        }
                        return super.visitBreakExpression(node)
                    }
                    
                    override fun visitContinueExpression(node: UContinueExpression): Boolean {
                        val isBehavioral = isBehavioralControlFlow(node)
                        if (isBehavioral) {
                            counters.behavioralControlFlow++
                        } else {
                            counters.renderControlFlow++
                        }
                        return super.visitContinueExpression(node)
                    }
                    
                    // Exception handling: throw, try-catch-finally
                    override fun visitThrowExpression(node: UThrowExpression): Boolean {
                        val isBehavioral = isBehavioralControlFlow(node)
                        if (isBehavioral) {
                            counters.behavioralControlFlow++
                        } else {
                            counters.renderControlFlow++
                        }
                        return super.visitThrowExpression(node)
                    }
                    
                    override fun visitTryExpression(node: UTryExpression): Boolean {
                        val isBehavioral = isBehavioralControlFlow(node)
                        // Count try
                        if (isBehavioral) {
                            counters.behavioralControlFlow++
                        } else {
                            counters.renderControlFlow++
                        }
                        // Count each catch clause (each catch introduces an alternative execution path)
                        node.catchClauses.forEach { _ ->
                            if (isBehavioral) {
                                counters.behavioralControlFlow++
                            } else {
                                counters.renderControlFlow++
                            }
                        }
                        // Count finally if present
                        if (node.finallyClause != null) {
                            if (isBehavioral) {
                                counters.behavioralControlFlow++
                            } else {
                                counters.renderControlFlow++
                            }
                        }
                        // Count statements in try, catch, and finally blocks
                        node.tryClause?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                        node.catchClauses.forEach { catchClause ->
                            catchClause.body?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                        }
                        node.finallyClause?.let { countStatementsInExpression(it, counters, visitedBlocks) }
                        return super.visitTryExpression(node)
                    }
                })

                val total = counters.totalStatements
                val cfRender = counters.renderControlFlow
                val cfBehavior = counters.behavioralControlFlow
                
                // Calculate weighted control flow count: CF_render + 0.5 × CF_behavior
                val weightedControlFlow = cfRender + (cfBehavior * BEHAVIORAL_CONTROL_FLOW_WEIGHT)
                val totalControlFlow = cfRender + cfBehavior

                // Guard: ignore tiny or trivial composables
                if (total < LogicInUiIssue.MIN_STATEMENTS_FOR_ANALYSIS) return
                if (totalControlFlow < LogicInUiIssue.MIN_CONTROL_FLOW_COUNT) return
                if (totalControlFlow > LogicInUiIssue.MAX_CONTROL_FLOW_COUNT) {
                    // Report if control flow count exceeds maximum
                    val location = context.getNameLocation(node)
                    val msg = String.format(
                        "Composable has too many control-flow statements: %d (maximum allowed: %d). " +
                                "Render control flow: %d, Behavioral control flow: %d. " +
                                "Consider refactoring to reduce complexity.",
                        totalControlFlow,
                        LogicInUiIssue.MAX_CONTROL_FLOW_COUNT,
                        cfRender,
                        cfBehavior
                    )
                    context.report(
                        LogicInUiIssue.ISSUE,
                        node.nameIdentifier ?: node,
                        location,
                        msg
                    )
                    return
                }

                // Calculate LIU using weighted formula: LIU = (CF_render + 0.5 × CF_behavior) / statements
                val liu = weightedControlFlow / total.toFloat()

                if (liu <= LogicInUiIssue.LIU_THRESHOLD) return

                // Report once per composable, on the function name
                val location = context.getNameLocation(node)
                val msg = String.format(
                    "Composable has high Logic-in-UI density: LIU=%.2f (threshold=%.2f). " +
                            "Weighted control flow: %.1f (render: %d, behavioral: %d) / Total statements: %d. " +
                            "Consider moving logic to ViewModel, derived state, or slot-based composition.",
                    liu,
                    LogicInUiIssue.LIU_THRESHOLD,
                    weightedControlFlow,
                    cfRender,
                    cfBehavior,
                    total
                )

                context.report(
                    LogicInUiIssue.ISSUE,
                    node.nameIdentifier ?: node,
                    location,
                    msg
                )
            }
        }
    }

    /**
     * Recursively counts statements in an expression.
     * Handles UBlockExpression (counts all expressions in the block) and recursively processes nested blocks.
     * Also handles single expressions (non-block) which count as 1 statement, but only if they're top-level
     * (not already counted as part of a parent block).
     * This ensures statements inside if/when branches are counted even if they're not visited by visitBlockExpression.
     * Uses visitedBlocks set to avoid double-counting blocks that are visited both explicitly and via the visitor.
     */
    private fun countStatementsInExpression(
        expr: UExpression, 
        counters: LogicCounters, 
        visitedBlocks: MutableSet<UBlockExpression>
    ) {
        when (expr) {
            is UBlockExpression -> {
                // Count all expressions in this block as statements (only if not already counted)
                if (visitedBlocks.add(expr)) {
                    counters.totalStatements += expr.expressions.size
                }
                // Don't recursively process nested blocks here - they'll be visited by visitBlockExpression
                // or counted when we explicitly visit them. This prevents double-counting.
            }
            else -> {
                // Single expression (not a block) - counts as 1 statement
                // This handles cases like: if (condition) Text("Hello") (single expression, not a block)
                // Only count if it's a top-level branch expression, not nested inside a block
                counters.totalStatements++
            }
        }
    }

    /**
     * Checks if a method name is an iteration method (forEach, map, filter, etc.)
     * These should be counted as control flow in LIU calculation.
     */
    private fun isIterationMethod(methodName: String): Boolean {
        val iterationMethods = setOf(
            "forEach",
            "forEachIndexed",
            "map",
            "mapIndexed",
            "filter",
            "filterIndexed",
            "flatMap",
            "fold",
            "reduce",
            "any",
            "all",
            "none",
            "count",
            "sum",
            "max",
            "min",
            "groupBy",
            "partition",
            "associate",
            "associateBy",
            "associateWith",
        )
        return methodName in iterationMethods
    }

    /**
     * Determines if a control flow expression is behavioral (inside callbacks/modifiers)
     * vs render-oriented (in composable body & layout lambdas).
     * 
     * Behavioral control flow is weighted lower (0.5x) in LIU calculation, but still contributes
     * to the metric to catch procedural orchestration in UI interaction logic.
     */
    private fun isBehavioralControlFlow(expr: UExpression): Boolean {
        return isInsideCallbackLambda(expr) || isInsideModifierLambda(expr)
    }
    
    /**
     * Checks if an expression is inside a modifier lambda (pointerInput, draggable, etc.)
     */
    private fun isInsideModifierLambda(expr: UExpression): Boolean {
        var current: UElement? = expr.uastParent
        
        while (current != null) {
            if (current is ULambdaExpression) {
                val lambda = current
                var parentCall: UCallExpression? = null
                var searchCurrent: UElement? = lambda.uastParent
                
                while (searchCurrent != null && parentCall == null) {
                    if (searchCurrent is UCallExpression) {
                        parentCall = searchCurrent
                    } else {
                        searchCurrent = searchCurrent.uastParent
                    }
                }
                
                if (parentCall != null) {
                    val methodName = parentCall.methodName
                    if (methodName != null && methodName in MODIFIER_LAMBDA_FUNCTIONS) {
                        return true
                    }
                }
            }
            current = current.uastParent
        }
        
        return false
    }
    
    /**
     * Checks if a lambda expression is a @Composable lambda.
     * This helps distinguish between UI-building lambdas and business logic lambdas.
     */
    private fun isComposableLambda(lambda: ULambdaExpression): Boolean {
        // Check if lambda is passed to a composable function as a @Composable parameter
        var current: UElement? = lambda.uastParent
        var parentCall: UCallExpression? = null
        
        while (current != null && parentCall == null) {
            if (current is UCallExpression) {
                parentCall = current
            } else {
                current = current.uastParent
            }
        }
        
        if (parentCall == null) return false

        // Try to resolve the called method
        val resolved = parentCall.resolve() ?: return false
        val uMethod = resolved.toUElementOfType<UMethod>() ?: return false

        // Check if the method is composable
        val isComposableMethod = uMethod.isComposable || 
                                  uMethod.hasAnnotation("androidx.compose.runtime.Composable") ||
                                  uMethod.sourcePsi?.let { 
                                      (it as? org.jetbrains.kotlin.psi.KtFunction)?.annotationEntries?.any { entry ->
                                          entry.text.contains("@Composable") || 
                                          entry.text.contains("androidx.compose.runtime.Composable")
                                      } == true
                                  } == true

        if (!isComposableMethod) return false

        // Check if this lambda is passed as a @Composable parameter
        val valueArgs = parentCall.valueArguments
        val lambdaArgIndex = valueArgs.indexOfFirst { it == lambda }
        
        // Check if it's a trailing lambda (common pattern for composable content)
        val isTrailingLambda = lambda == valueArgs.lastOrNull()
        
        if (lambdaArgIndex >= 0 && lambdaArgIndex < uMethod.uastParameters.size) {
            val param = uMethod.uastParameters[lambdaArgIndex]
            val paramName = param.name
            val typeText = param.typeReference?.type?.canonicalText ?: ""
            val psiText = param.sourcePsi?.text ?: ""
            
            // Check for @Composable annotation in type or parameter declaration
            val isComposableParam = typeText.contains("Composable") || 
                   typeText.contains("@Composable") ||
                   psiText.contains("@Composable") ||
                   psiText.contains("androidx.compose.runtime.Composable") ||
                   param.typeReference?.type?.hasAnnotation("androidx.compose.runtime.Composable") == true
            
            if (isComposableParam) {
                // Additional check: make sure it's not a callback parameter name
                // Even if it's composable, if it's named like a callback, it might be a slot parameter
                // But for now, if it's explicitly composable, we treat it as UI-building
                if (paramName == null || paramName !in CALLBACK_PARAMETER_NAMES) {
                    return true
                }
            }
        }
        
        // If it's a trailing lambda to a composable function, it's likely a composable lambda
        // (e.g., Column { ... }, Row { ... })
        // But only if it's not explicitly a named callback parameter
        if (isTrailingLambda && isComposableMethod) {
            // Check if the trailing lambda is explicitly named as a callback
            val callText = parentCall.sourcePsi?.text ?: ""
            val isNamedCallback = CALLBACK_PARAMETER_NAMES.any { callbackName ->
                val pattern = "$callbackName\\s*=\\s*\\{"
                Regex(pattern, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(callText)
            }
            
            // If it's a trailing lambda and not a named callback, it's likely composable content
            if (!isNamedCallback) {
                return true
            }
        }

        return false
    }

    /**
     * Checks if an expression (e.g., IF statement) is inside a callback lambda
     * by traversing up the parent chain. This is more reliable than scope stack
     * because the visitor might visit the IF before the lambda sets the scope.
     * 
     * IMPORTANT: This should return false (not a callback) if the lambda is a composable lambda,
     * because IF statements inside composable lambdas SHOULD be counted.
     */
    private fun isInsideCallbackLambda(expr: UExpression): Boolean {
        var current: UElement? = expr.uastParent
        
        while (current != null) {
            if (current is ULambdaExpression) {
                // Found a lambda - first check if it's a composable lambda
                val lambda = current
                
                // If this is a composable lambda, it's NOT a callback - continue searching up
                // (or return false if we're sure it's composable and not a callback)
                if (isComposableLambda(lambda)) {
                    // This is a composable lambda, so IFs inside it should be counted
                    // Continue searching up the chain in case we're nested
                    current = current.uastParent
                    continue
                }
                
                // Not a composable lambda - check if it's a callback
                // Traverse up to find the parent call expression
                var parentCall: UCallExpression? = null
                var searchCurrent: UElement? = lambda.uastParent
                
                while (searchCurrent != null && parentCall == null) {
                    if (searchCurrent is UCallExpression) {
                        parentCall = searchCurrent
                    } else {
                        searchCurrent = searchCurrent.uastParent
                    }
                }
                
                if (parentCall != null) {
                    // Check if parent is a modifier extension function
                    val methodName = parentCall.methodName
                    if (methodName != null && methodName in MODIFIER_LAMBDA_FUNCTIONS) {
                        return true
                    }
                    
                    // Check if parent is a non-UI lambda function
                    if (methodName != null && methodName in NON_UI_LAMBDA_FUNCTIONS) {
                        return true
                    }
                    
                    // Check if lambda is passed as a callback parameter
                    val valueArgs = parentCall.valueArguments
                    val lambdaArgIndex = valueArgs.indexOfFirst { it == lambda }
                    
                    if (lambdaArgIndex >= 0) {
                        // Try to resolve the method to get parameter names
                        val resolved = parentCall.resolve()
                        if (resolved != null) {
                            val uMethod = resolved.toUElementOfType<UMethod>()
                            if (uMethod != null && lambdaArgIndex < uMethod.uastParameters.size) {
                                val param = uMethod.uastParameters[lambdaArgIndex]
                                val paramName = param.name
                                
                                // Check if parameter name is a callback
                                if (paramName != null && paramName in CALLBACK_PARAMETER_NAMES) {
                                    return true
                                }
                                
                                // Check if parameter name starts with "on" (common callback pattern)
                                if (paramName != null && paramName.startsWith("on") && paramName.length > 2) {
                                    // Check if it's NOT a composable lambda
                                    val typeText = param.typeReference?.type?.canonicalText ?: ""
                                    val psiText = param.sourcePsi?.text ?: ""
                                    val isComposableParam = typeText.contains("Composable") || 
                                                            typeText.contains("@Composable") ||
                                                            psiText.contains("@Composable") ||
                                                            psiText.contains("androidx.compose.runtime.Composable")
                                    
                                    // If parameter starts with "on" and is NOT composable, it's a callback
                                    if (!isComposableParam) {
                                        return true
                                    }
                                }
                            }
                        }
                        
                        // Fallback: check PSI text for callback parameter names
                        val callText = parentCall.sourcePsi?.text ?: ""
                        for (callbackName in CALLBACK_PARAMETER_NAMES) {
                            // Look for patterns like "onClick = { ... }" or "onClick={...}"
                            val pattern = "$callbackName\\s*=\\s*\\{"
                            if (Regex(pattern, setOf(RegexOption.IGNORE_CASE)).containsMatchIn(callText)) {
                                // Verify this lambda is the one for this callback
                                val lambdaText = lambda.sourcePsi?.text ?: ""
                                val fullPattern = "$callbackName\\s*=\\s*\\{.*?\\}"
                                if (Regex(fullPattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).containsMatchIn(callText)) {
                                    return true
                                }
                            }
                        }
                    }
                    
                    // Check if the parent function is NOT composable (definitely business logic)
                    val resolved = parentCall.resolve()
                    if (resolved != null) {
                        val uMethod = resolved.toUElementOfType<UMethod>()
                        if (uMethod != null) {
                            val isComposableMethod = uMethod.isComposable || 
                                                      uMethod.hasAnnotation("androidx.compose.runtime.Composable") ||
                                                      uMethod.sourcePsi?.let { 
                                                          (it as? org.jetbrains.kotlin.psi.KtFunction)?.annotationEntries?.any { entry ->
                                                              entry.text.contains("@Composable") || 
                                                              entry.text.contains("androidx.compose.runtime.Composable")
                                                          } == true
                                                      } == true
                            
                            // If parent is NOT composable, this lambda is definitely business logic/callback
                            if (!isComposableMethod) {
                                return true
                            }
                        }
                    }
                }
                
                // Continue searching up the chain
            }
            current = current.uastParent
        }
        
        return false
    }

    /**
     * Checks if a control flow expression is checking an acceptable UI state
     * (like ImageLoadState, LoadingState, etc.) which is legitimate UI logic,
     * not business logic that should be in ViewModel.
     */
    private fun isAcceptableUIStateCheck(node: UExpression): Boolean {
        val text = node.sourcePsi?.text ?: return false
        
        // Check if the expression references acceptable UI state types
        return ACCEPTABLE_UI_STATE_TYPES.any { stateType ->
            text.contains(stateType, ignoreCase = true) ||
            // Also check for common patterns like .loadState, .state, etc.
            (text.contains(".loadState", ignoreCase = true) && 
             (text.contains("Image") || text.contains("Async"))) ||
            (text.contains(".state", ignoreCase = true) && 
             (text.contains("Loading") || text.contains("Lazy") || text.contains("Scroll")))
        }
    }

    private data class LogicCounters(
        var totalStatements: Int = 0,
        var renderControlFlow: Int = 0,      // Control flow in composable body & layout lambdas (full weight)
        var behavioralControlFlow: Int = 0   // Control flow in callbacks/modifiers (reduced weight = 0.5x)
    )
}