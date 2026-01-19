package com.arda.smell_detector.rules


import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.arda.smell_detector.helpers.ComposableFunctionDetector
import com.arda.smell_detector.rules.SideEffectComplexityDetector.Companion.computeSecScore
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDoWhileExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtIfExpression
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtWhenExpression
import org.jetbrains.kotlin.psi.KtWhileExpression
import org.jetbrains.uast.UMethod
import slack.lint.compose.util.ComposableBody
import slack.lint.compose.util.extractComposableBody
import slack.lint.compose.util.findChildrenByClass

/**
 * Composable Function Complexity (CFC)
 */
object ComposableFunctionComplexityIssue {

    private const val ID = "ComposableFunctionComplexity"
    private const val DESCRIPTION = "Composable function has high structural/logical complexity"
    private val EXPLANATION =
        """
        Measures the structural and logical complexity of a composable function by combining size
        (lines of code) with control-flow constructs, nesting depth, side-effect usage, ViewModel
        access, and parameter count. High CFC indicates that a composable blends UI structure with
        behavioral logic, violating declarative composition principles.

        Composable functions with excessive complexity are harder to maintain, test, and reason about.
        They increase recomposition costs by expanding invalidation scope and often reveal business or
        state logic leakage into the UI layer, contradicting guidance for modular, single-purpose
        composables.
        """.trimIndent()

    private val CATEGORY = Category.PERFORMANCE
    private const val PRIORITY = 6
    private val SEVERITY = Severity.WARNING

    // Lint option name for configurable threshold
    internal const val CFC_THRESHOLD_OPTION_NAME = "cfcThreshold"

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
                ComposableFunctionComplexityDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            )
        )
}

/**
 * Detector Implementation: Composable Function Complexity (CFC)
 *
 * For each @Composable function we compute:
 *
 *   - Statement count: total number of statements (including nested ones)
 *   - Branches: # of if + when expressions
 *   - Loops: # of for/while/do-while/forEach loops
 *   - Max nesting depth of control constructs
 *   - Parameter count: number of function parameters (excluding design-related)
 *   - ViewModel count: number of ViewModels accessed
 *   - Side-effect metrics:
 *       * SEC_total: sum of side-effect complexities in LaunchedEffect/DisposableEffect/SideEffect/produceState
 *       * SED: number of side-effect blocks
 *
 * CFC is a weighted combination:
 *
 *   CFC = 2 * branches
 *       + 3 * loops
 *       + 2 * depth
 *       + 1 * SEC_total
 *       + 2 * SED
 *       + 1 * parameters
 *       + 3 * viewModels
 *
 * If CFC >= THRESHOLD (default: 25, configurable), the composable is flagged.
 */
class ComposableFunctionComplexityDetector :
    ComposableFunctionDetector(),
    SourceCodeScanner {

    companion object {
        private const val BRANCH_WEIGHT = 2
        private const val LOOP_WEIGHT = 3
        private const val DEPTH_WEIGHT = 2
        private const val SEC_WEIGHT = 1
        private const val SED_WEIGHT = 2
        private const val PARAM_WEIGHT = 1
        private const val VIEWMODEL_WEIGHT = 3

        private const val DEFAULT_CFC_THRESHOLD = 25

        private val SIDE_EFFECT_NAMES =
            setOf(
                "LaunchedEffect",
                "DisposableEffect",
                "SideEffect",
                "produceState",
                "produceRetainedState",
            )

        // ViewModel access patterns
        private val VIEWMODEL_PATTERNS =
            listOf(
                "ViewModelProvider",
                "viewModel()",
                "hiltViewModel()",
                "androidViewModel()",
                ".viewModel",
                "ViewModel()",
            )
    }

    private var cfcThreshold: Int = DEFAULT_CFC_THRESHOLD

    override fun beforeCheckRootProject(context: com.android.tools.lint.detector.api.Context) {
        super.beforeCheckRootProject(context)
        // Load threshold from configuration
        val config = context.findConfiguration(context.file)
        val thresholdString = config.getOption(
            ComposableFunctionComplexityIssue.ISSUE,
            ComposableFunctionComplexityIssue.CFC_THRESHOLD_OPTION_NAME
        )
        cfcThreshold = thresholdString?.toIntOrNull() ?: DEFAULT_CFC_THRESHOLD
        if (cfcThreshold <= 0) {
            cfcThreshold = DEFAULT_CFC_THRESHOLD
        }
    }

    override fun visitComposable(
        context: JavaContext,
        method: UMethod,
        function: KtFunction,
    ) {
        // Extract body (handles both block-body and expression-body composables)
        val composableBody = function.extractComposableBody() ?: return
        
        // Get the actual block to analyze (unified for both body types)
        val bodyBlock = when (composableBody) {
            is ComposableBody.BlockBody -> composableBody.block
            is ComposableBody.ExpressionBody -> composableBody.lambdaBody
        }

        // --- base structural metrics ---
        // Count if expressions (includes if and else-if)
        val ifCount = function.findChildrenByClass<KtIfExpression>().count()
        
        // Count when entries (each entry except else counts as a branch)
        val whenExpressions = function.findChildrenByClass<KtWhenExpression>()
        val whenEntryCount = whenExpressions.sumOf { whenExpr ->
            // Count entries that are not else (else entry has null condition)
            whenExpr.entries.count { entry -> entry.conditions.isNotEmpty() }
        }

        // Count traditional loops (for/while/do-while) in the entire function
        val traditionalLoops =
            function.findChildrenByClass<KtForExpression>().count() +
                    function.findChildrenByClass<KtWhileExpression>().count() +
                    function.findChildrenByClass<KtDoWhileExpression>().count()

        // Count functional iteration calls (forEach, map, filter, etc.) as loops
        val functionalLoops = function
            .findChildrenByClass<KtCallExpression>()
            .count { it.isIterationCall() }
        
        val loops = traditionalLoops + functionalLoops
        
        // Total branches = if/else-if + when entries (excluding else) + loops
        // Loops are counted as branches because they represent decision points in control flow
        // (else blocks are not counted as branches)
        val branches = ifCount + whenEntryCount + loops

        // Statement count calculation
        val statementCount = bodyBlock.computeLinesOfCode()

        val maxDepth = bodyBlock.computeControlNestingDepth()

        // --- parameter count (excluding design-related parameters) ---
        val parameterCount = function.valueParameters.count { param ->
            !isDesignRelatedParameter(param)
        }

        // --- ViewModel count ---
        val viewModelCount = function.countViewModels()

        // --- side-effect metrics (SEC + SED) ---
        val effectCalls =
            function
                .findChildrenByClass<KtCallExpression>()
                .filter { it.isSideEffectCall() }
                .toList()

        // Align SEC contribution with SideEffectComplexityIssue to keep metrics consistent.
        val secTotal = effectCalls.sumOf { computeSecScore(it) }
        val sed = effectCalls.size

        // Updated CFC formula (LOC removed)
        val cfcScore =
            BRANCH_WEIGHT * branches +
                    LOOP_WEIGHT * loops +
                    DEPTH_WEIGHT * maxDepth +
                    SEC_WEIGHT * secTotal +
                    SED_WEIGHT * sed +
                    PARAM_WEIGHT * parameterCount +
                    VIEWMODEL_WEIGHT * viewModelCount

        val functionName = function.name ?: method.name

        // Always flag if there are more than 4 loops (regardless of CFC score)
        if (loops > 4) {
            val message =
                buildString {
                    append(
                        "Composable '$functionName' has too many loops: $loops (maximum allowed: 4). " +
                                "statements=$statementCount, branches=$branches, depth=$maxDepth, " +
                                "params=$parameterCount, viewModels=$viewModelCount, sideEffects=$sed, sideEffectSEC=$secTotal. ",
                    )
                    append(
                        "Refactor into smaller UI-focused composables and move business/data logic " +
                                "into ViewModels or helpers.",
                    )
                }

            context.report(
                ComposableFunctionComplexityIssue.ISSUE,
                function,
                context.getLocation(function),
                message,
            )
            return
        }

        // Check CFC threshold for other complexity issues
        if (cfcScore < cfcThreshold) return

        val message =
            buildString {
                append(
                    "Composable '$functionName' has high complexity (CFC=$cfcScore, threshold=$cfcThreshold). " +
                            "statements=$statementCount, branches=$branches, loops=$loops, depth=$maxDepth, " +
                            "params=$parameterCount, viewModels=$viewModelCount, sideEffects=$sed, sideEffectSEC=$secTotal. ",
                )
                append(
                    "Refactor into smaller UI-focused composables and move business/data logic " +
                            "into ViewModels or helpers.",
                )
            }

        context.report(
            ComposableFunctionComplexityIssue.ISSUE,
            function,
            context.getLocation(function),
            message,
        )
    }

    private fun KtCallExpression.isSideEffectCall(): Boolean {
        val name = calleeExpression?.text ?: return false
        return name in SIDE_EFFECT_NAMES
    }

    private fun KtCallExpression.isIterationCall(): Boolean {
        val name = calleeExpression?.text ?: return false
        val iterationFunctions = setOf(
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
        return name in iterationFunctions
    }

    // --- Statement count calculation ---

    /**
     * Counts statements in a block expression (used for reporting, not CFC calculation).
     * - Recursively counts all statements including nested ones
     * - Counts variable declarations, expressions, and control flow
     * - Properly handles nested blocks, lambdas, and control flow statements
     */
    private fun KtBlockExpression.computeLinesOfCode(): Int {
        return computeStatementsRecursively(this)
    }

    /**
     * Recursively counts statements in an if-else-if chain.
     * Handles nested else-if expressions properly.
     */
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

    /**
     * Recursively counts statements in a block expression, including nested blocks.
     * This ensures we count all statements, not just top-level ones.
     */
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
                    // Count statements in lambda body
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
                    // This ensures statements inside lambda bodies are counted
                    stmt.lambdaArguments.forEach { lambdaArg ->
                        val lambdaExpr = lambdaArg.getLambdaExpression()
                        lambdaExpr?.bodyExpression?.let { body ->
                            if (body is KtBlockExpression) {
                                // Recursively count statements in the lambda body block
                                count += computeStatementsRecursively(body)
                            } else if (body != null) {
                                // Single expression in lambda counts as 1 statement
                                count++
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

    // --- Design parameter detection ---

    /**
     * Checks if a parameter is design-related and should be excluded from complexity calculation.
     * Design parameters include styling (TextStyle, Color, Modifier), theming (MaterialTheme.*),
     * and visual configuration that doesn't contribute to logical complexity.
     */
    private fun isDesignRelatedParameter(param: KtParameter): Boolean {
        val paramName = param.name ?: return false
        val paramType = param.typeReference?.text ?: ""
        val defaultValue = param.defaultValue?.text ?: ""
        
        // Design-related parameter types
        val designTypes = setOf(
            "TextStyle",
            "Color",
            "Modifier",
            "Dp",
            "TextAlign",
            "TextDecoration",
            "FontWeight",
            "FontFamily",
            "FontStyle",
            "TextUnit",
            "Brush",
            "Painter",
            "ContentScale",
            "Alignment",
            "Arrangement",
            "PaddingValues",
            "Shape",
            "BorderStroke",
            "Shadow",
            "Gradient",
        )
        
        // Design-related parameter name patterns
        val designNamePatterns = listOf(
            "textStyle",
            "textColor",
            "hintColor",
            "backgroundColor",
            "contentColor",
            "tint",
            "modifier",
            "style",
            "color",
            "padding",
            "spacing",
            "alignment",
            "arrangement",
            "shape",
            "border",
            "shadow",
            "gradient",
            "font",
            "typography",
        )
        
        // Check if type is design-related
        if (designTypes.any { type -> paramType.contains(type) }) {
            return true
        }
        
        // Check if parameter name matches design patterns
        val lowerName = paramName.lowercase()
        if (designNamePatterns.any { pattern -> lowerName.contains(pattern) }) {
            return true
        }
        
        // Check if default value references MaterialTheme (common in design parameters)
        if (defaultValue.contains("MaterialTheme", ignoreCase = true) ||
            defaultValue.contains("LocalContent", ignoreCase = true) ||
            defaultValue.contains("LocalTextStyle", ignoreCase = true) ||
            defaultValue.contains("LocalColor", ignoreCase = true)) {
            return true
        }
        
        return false
    }

    // --- ViewModel detection ---

    /**
     * Counts the number of ViewModels accessed in the composable function.
     * Detects patterns like:
     * - ViewModelProvider.xxx
     * - viewModel()
     * - hiltViewModel()
     * - androidViewModel()
     * - xxx.viewModel
     */
    private fun KtFunction.countViewModels(): Int {
        val functionText = this.text
        val viewModelAccesses = mutableSetOf<String>()
        
        // Find all call expressions
        val callExpressions = this.findChildrenByClass<KtCallExpression>()
        
        for (call in callExpressions) {
            val callText = call.text
            
            // Check for ViewModel access patterns
            for (pattern in VIEWMODEL_PATTERNS) {
                if (callText.contains(pattern, ignoreCase = true)) {
                    // Try to extract unique ViewModel identifier
                    // This is a heuristic - we count unique access points
                    val identifier = extractViewModelIdentifier(callText, pattern)
                    if (identifier != null) {
                        viewModelAccesses.add(identifier)
                    } else {
                        // If we can't extract identifier, count the pattern occurrence
                        viewModelAccesses.add(pattern)
                    }
                }
            }
        }
        
        // Also check property declarations for ViewModel types
        val properties = this.findChildrenByClass<KtProperty>()
        for (property in properties) {
            val typeText = property.typeReference?.text ?: ""
            if (typeText.contains("ViewModel", ignoreCase = true) && 
                !typeText.contains("ViewModelProvider")) {
                viewModelAccesses.add(property.name ?: typeText)
            }
        }
        
        return viewModelAccesses.size
    }

    /**
     * Extracts a unique identifier for ViewModel access to avoid double-counting.
     */
    private fun extractViewModelIdentifier(callText: String, pattern: String): String? {
        // Try to extract ViewModel name from common patterns
        // Example: "ViewModelProvider.podcastSearch" -> "podcastSearch"
        // Example: "viewModel<MyViewModel>()" -> "MyViewModel"
        
        when {
            callText.contains("ViewModelProvider.") -> {
                val match = Regex("ViewModelProvider\\.(\\w+)").find(callText)
                return match?.groupValues?.get(1)
            }
            callText.contains("viewModel<") -> {
                val match = Regex("viewModel<([^>]+)>").find(callText)
                return match?.groupValues?.get(1)
            }
            callText.contains("hiltViewModel<") -> {
                val match = Regex("hiltViewModel<([^>]+)>").find(callText)
                return match?.groupValues?.get(1)
            }
            else -> return null
        }
    }

    // --- nesting depth helpers ---

    private fun KtBlockExpression.computeControlNestingDepth(): Int {
        return computeNestingDepthInternal(this, 0)
    }

    private fun computeNestingDepthInternal(
        element: org.jetbrains.kotlin.psi.KtElement,
        current: Int,
    ): Int {
        var maxDepth = current

        val isControl =
            element is KtIfExpression ||
                    element is KtWhenExpression ||
                    element is KtForExpression ||
                    element is KtWhileExpression ||
                    element is KtDoWhileExpression ||
                    (element is KtCallExpression && element.isIterationCall())

        val nextDepth = if (isControl) current + 1 else current
        if (nextDepth > maxDepth) {
            maxDepth = nextDepth
        }

        element.children
            .filterIsInstance<org.jetbrains.kotlin.psi.KtElement>()
            .forEach { child ->
                val childDepth = computeNestingDepthInternal(child, nextDepth)
                if (childDepth > maxDepth) {
                    maxDepth = childDepth
                }
            }

        return maxDepth
    }
}