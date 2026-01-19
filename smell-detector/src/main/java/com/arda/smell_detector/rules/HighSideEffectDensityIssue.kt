package com.arda.smell_detector.rules


import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.arda.smell_detector.helpers.ComposableFunctionDetector
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.toUElementOfType
import slack.lint.compose.util.findChildrenByClass
import slack.lint.compose.util.isRestartableEffect

/**
 * High Side-Effect Density (SED)
 *
 * Flags composable functions whose bodies contain a disproportionately high number
 * of side-effect constructs (LaunchedEffect, DisposableEffect, SideEffect, produceState,
 * etc.) compared to total statements, indicating that the composable is taking on
 * excessive reactive / lifecycle responsibilities instead of being declarative UI.
 */
object HighSideEffectDensityIssue {

    private const val ID = "HighSideEffectDensity"
    private const val DESCRIPTION =
        "Composable has high side-effect density (too many LaunchedEffect/DisposableEffect/SideEffect blocks)"

    private val EXPLANATION =
        """
        Measures the proportion of side-effect constructs (LaunchedEffect, DisposableEffect,
        SideEffect, produceState, produceRetainedState, etc.) relative to the total number of
        statements inside a composable. A high side-effect density indicates that the composable
        performs too much reactive or lifecycle work instead of remaining a mostly declarative
        UI description.

        Composables overloaded with side-effects tend to take on ViewModel or controller
        responsibilities, making lifecycle behavior and recomposition triggers harder to
        reason about. High SED values often signal architectural imbalance, where effect
        coordination and business logic leak into the UI layer, increasing recomposition
        risk and reducing maintainability. Prefer consolidating related side-effects,
        moving lifecycle coordination into ViewModels, and relying on derived/remembered
        state transformations where possible.
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
                HighSideEffectDensityDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
}

/**
 * Detector Implementation: High Side-Effect Density (SED)
 *
 * For each @Composable function:
 *
 *   SED = (# side-effect calls) / (uiNodeCount)
 *
 * We treat as side-effects:
 *   - LaunchedEffect(...)
 *   - DisposableEffect(...)
 *   - SideEffect { ... }
 *   - produceState { ... }, produceRetainedState { ... }
 *   - Any known restartable effect via isRestartableEffect
 *
 * A smell is reported when:
 *   - The composable has at least MIN_SIDE_EFFECTS side-effect calls, and
 *   - SED >= SED_THRESHOLD
 *
 * This matches the intent that:
 *   - A single lightweight side-effect in a rich UI is fine (low SED),
 *   - Many effects in a small body (high SED) is suspicious.
 */
class HighSideEffectDensityDetector :
    ComposableFunctionDetector(),
    SourceCodeScanner {

    companion object {
        // Effect names to analyze as "side effects"
        private val EFFECT_NAMES =
            setOf(
                "LaunchedEffect",
                "DisposableEffect",
                "SideEffect",
                "produceState",
                "produceRetainedState",
            )
        private val COMMON_UI_NAMES =
            setOf("Text", "Column", "Row", "Box", "Image", "Button")

        // Heuristic parameters
        private const val SED_THRESHOLD = 0.3
        private const val MIN_SIDE_EFFECTS = 2   // require at least 2 effects to flag
    }

    override fun visitComposable(
        context: JavaContext,
        method: UMethod,
        function: KtFunction,
    ) {
        val uiNodeCount =
            function
                .findChildrenByClass<KtCallExpression>()
                .filter { !it.isSideEffectCall() }
                .count { it.isComposableUiCall() }
        // uiNodeCount uses composable calls instead of statements to reflect UI
        // surface area; avoids sensitivity to formatting or non-UI logic.
        if (uiNodeCount == 0) return

        // Collect side-effect calls with their information
        val effectCalls =
            function
                .findChildrenByClass<KtCallExpression>()
                .filter { it.isSideEffectCall() }
                .map { call ->
                    val name = call.calleeExpression?.text ?: ""
                    val keys = extractEffectKeys(call)
                    EffectInfo(
                        call = call,
                        name = name,
                        keys = keys,
                        isLaunchedEffect = name == "LaunchedEffect"
                    )
                }
                .toList()

        val sideEffectCount = effectCalls.size

        val duplicateKeysMessage = checkForDuplicateEffectKeys(effectCalls)
        val hasDuplicateKeys = duplicateKeysMessage != null

        if (sideEffectCount < MIN_SIDE_EFFECTS) return

        val sed = sideEffectCount.toDouble() / uiNodeCount.toDouble()
        if (sed < SED_THRESHOLD) return

        // Analyze effect grouping - detect LaunchedEffect calls with same keys
        val consolidationSuggestions = analyzeEffectGrouping(effectCalls)

        val functionName = function.name ?: method.name
        val message =
            buildString {
                append(
                    "Composable '$functionName' has high side-effect density (SED=%.2f). "
                        .format(sed),
                )
                append(
                    "Side-effects=$sideEffectCount, uiNodes=$uiNodeCount. "
                )

                if (consolidationSuggestions.isNotEmpty()) {
                    append(consolidationSuggestions)
                    append(" ")
                }
                if (duplicateKeysMessage != null) {
                    append(duplicateKeysMessage)
                    append(" ")
                }

                append(
                    "Consider consolidating related effects and moving lifecycle/business logic " +
                            "to ViewModels or state producers.",
                )
            }

        context.report(
            HighSideEffectDensityIssue.ISSUE,
            function,
            context.getLocation(function),
            message,
        )
    }

    private fun KtCallExpression.isSideEffectCall(): Boolean {
        val name = calleeExpression?.text ?: return false
        if (name in EFFECT_NAMES) return true
        // Also treat known restartable effects (LaunchedEffect, DisposableEffect, etc.) from Slack util.
        return isRestartableEffect
    }

    /**
     * Extracts keys from a LaunchedEffect or DisposableEffect call.
     * Handles patterns like:
     * - LaunchedEffect(key1 = value1, key2 = value2) { ... }
     * - LaunchedEffect(value1, value2) { ... }
     */
    private fun extractEffectKeys(call: KtCallExpression): Set<String> {
        val keys = mutableSetOf<String>()
        val name = call.calleeExpression?.text ?: return keys

        // Only extract keys for LaunchedEffect and DisposableEffect
        if (name != "LaunchedEffect" && name != "DisposableEffect") {
            return keys
        }

        // Get value arguments (keys come before the lambda)
        val valueArguments = call.valueArguments.filter { it !is KtLambdaArgument }

        valueArguments.forEach { arg ->
            val argName = arg.getArgumentName()?.asName?.asString()
            val argExpression = arg.getArgumentExpression()
            
            when {
                // Named argument: key1 = value
                argName != null -> {
                    val valueText = argExpression?.text?.trim() ?: ""
                    if (valueText.isNotEmpty() && valueText != "Unit") {
                        keys.add("$argName=$valueText")
                    }
                }
                // Positional argument: LaunchedEffect(value)
                argExpression != null -> {
                    val valueText = argExpression.text?.trim()
                    if (valueText != null && valueText.isNotEmpty() && valueText != "Unit") {
                        keys.add(valueText)
                    }
                }
            }
        }

        return keys
    }

    /**
     * Information about a side-effect call, including its keys for grouping analysis.
     */
    private data class EffectInfo(
        val call: KtCallExpression,
        val name: String,
        val keys: Set<String>,
        val isLaunchedEffect: Boolean
    )

    /**
     * Checks for duplicate effects with the same keys and returns a message if found.
     * Returns null if no duplicates are found.
     */
    private fun checkForDuplicateEffectKeys(effects: List<EffectInfo>): String? {
        if (effects.size < 2) return null
        
        // Group effects by name and keys
        val groupedByTypeAndKeys = effects.groupBy { effect ->
            Pair(effect.name, effect.keys)
        }
        
        // Find groups with same effect type and same keys (duplicates)
        val duplicateGroups = groupedByTypeAndKeys.filter { it.value.size > 1 }
        
        if (duplicateGroups.isEmpty()) return null
        
        val messages = mutableListOf<String>()
        duplicateGroups.forEach { (typeAndKeys, groupEffects) ->
            val effectName = typeAndKeys.first
            val keys = typeAndKeys.second
            
            if (groupEffects.size >= 2) {
                val keyDescription = if (keys.isEmpty()) {
                    "no keys"
                } else {
                    "keys: ${keys.joinToString(", ")}"
                }
                
                // Extract line numbers from PSI
                val lineNumbers = groupEffects.mapNotNull { effect ->
                    effect.call.containingFile?.let { file ->
                        file.text.substring(0, effect.call.textOffset).lines().size + 1
                    }
                }
                
                val lineInfo = if (lineNumbers.isNotEmpty()) {
                    " (lines ${lineNumbers.joinToString(", ")})"
                } else {
                    ""
                }
                
                messages.add(
                    "${groupEffects.size} $effectName calls with same $keyDescription$lineInfo"
                )
            }
        }
        
        return if (messages.isNotEmpty()) {
            "Found duplicate effects: ${messages.joinToString("; ")}."
        } else {
            null
        }
    }

    /**
     * Analyzes effect grouping to detect LaunchedEffect calls with same/similar keys
     * that could be consolidated.
     */
    private fun analyzeEffectGrouping(effects: List<EffectInfo>): String {
        val launchedEffects = effects.filter { it.isLaunchedEffect }
        
        if (launchedEffects.size < 2) {
            return ""
        }

        // Group LaunchedEffect calls by their keys
        val groupedByKeys = launchedEffects.groupBy { it.keys }
        
        // Find groups with same keys (potential consolidation candidates)
        val consolidationGroups = groupedByKeys.filter { it.value.size > 1 }
        
        if (consolidationGroups.isEmpty()) {
            return ""
        }

        val suggestions = mutableListOf<String>()
        consolidationGroups.forEach { (keys, groupEffects) ->
            if (groupEffects.size >= 2) {
                val keyDescription = if (keys.isEmpty()) {
                    "no keys"
                } else {
                    "keys: ${keys.joinToString(", ")}"
                }
                // Extract line numbers from PSI
                val lineNumbers = groupEffects.mapNotNull { effect ->
                    effect.call.containingFile?.let { file ->
                        file.text.substring(0, effect.call.textOffset).lines().size
                    }
                }
                val lineInfo = if (lineNumbers.isNotEmpty()) {
                    " (lines ${lineNumbers.joinToString(", ")})"
                } else {
                    ""
                }
                suggestions.add(
                    "${groupEffects.size} LaunchedEffect calls with same $keyDescription$lineInfo"
                )
            }
        }

        return if (suggestions.isNotEmpty()) {
            "Found consolidatable effects: ${suggestions.joinToString("; ")}. "
        } else {
            ""
        }
    }

    private fun KtCallExpression.isComposableUiCall(): Boolean {
        val uCall = this.toUElementOfType<UCallExpression>()
        if (uCall != null && uCall.isComposableCall()) return true

        val name = calleeExpression?.text ?: return false
        if (name in EFFECT_NAMES) return false
        return name in COMMON_UI_NAMES
    }

    private fun UCallExpression.isComposableCall(): Boolean {
        val resolved = resolve()
        if (resolved != null) {
            if (resolved.hasAnnotation("androidx.compose.runtime.Composable")) return true
            if (resolved.annotations.any { it.qualifiedName?.endsWith(".Composable") == true }) return true
        }

        // Heuristic fallback for when resolution is missing in tests/stubs.
        val name = methodName ?: return false
        return name in COMMON_UI_NAMES
    }
}