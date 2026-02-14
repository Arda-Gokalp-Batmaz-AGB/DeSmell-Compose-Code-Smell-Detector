package com.arda.smell_detector.rules

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.Context
import com.arda.smell_detector.helpers.ComposableFunctionDetector
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import slack.lint.compose.util.findChildrenByClass
import slack.lint.compose.util.isComposableFunction

/**
 * Reactive State Pass-Through Issue
 *
 * Flags composables that receive reactive state (State<T>, StateFlow<T>, Flow<T>) or values
 * derived from local reactive state (e.g. Int/String from `var x by remember { mutableStateOf(0) }`)
 * as parameters but do not consume or transform them, only passing unchanged to exactly one child.
 */
object ReactiveStatePassThroughIssue {

    private const val ID = "ReactiveStatePassThrough"
    private const val DESCRIPTION = "Reactive state passed through composable without being used"
    private val EXPLANATION = """
        This composable receives reactive state (or a value from reactive state) as a parameter
        but does not consume, transform, or share it—only passing it unchanged to a single child composable.
        
        Passing reactive objects or state-derived values through intermediate composables without use
        increases recomposition scope and violates proper state ownership principles.
        
        Consider: move state collection closer to usage, pass immutable snapshot (.value), or derive specific state.
    """.trimIndent()

    private val CATEGORY = Category.CORRECTNESS
    private const val PRIORITY = 6
    private val SEVERITY = Severity.WARNING

    val ISSUE: Issue = Issue.create(
        id = ID,
        briefDescription = DESCRIPTION,
        explanation = EXPLANATION,
        category = CATEGORY,
        priority = PRIORITY,
        severity = SEVERITY,
        implementation = Implementation(
            ReactiveStatePassThroughDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )
    )
}

/**
 * Detects reactive state (or state-derived value) parameters that are passed through without being consumed.
 */
class ReactiveStatePassThroughDetector : ComposableFunctionDetector(), SourceCodeScanner {

    companion object {
        private val REACTIVE_TYPE_PATTERNS = listOf(
            "State<", "MutableState<", "StateFlow<", "MutableStateFlow<", "Flow<", "LiveData<",
            "androidx.compose.runtime.State", "androidx.compose.runtime.MutableState",
            "kotlinx.coroutines.flow.StateFlow", "kotlinx.coroutines.flow.MutableStateFlow",
            "kotlinx.coroutines.flow.Flow", "androidx.lifecycle.LiveData",
        )
        private val CONSUMPTION_METHODS = setOf(
            "value", "collect", "collectAsState", "collectAsStateWithLifecycle", "observeAsState",
        )
        private val TRANSFORMATION_FUNCTIONS = setOf(
            "derivedStateOf", "map", "filter", "flatMap", "combine", "zip",
        )
        private const val MUTABLE_STATE_OF = "mutableStateOf"
    }

    // ─── Per-file state: accumulated during visitComposable, processed in afterCheckFile ───

    /** Stored JavaContext for reporting in afterCheckFile */
    private var savedContext: JavaContext? = null

    /** Collected composable info: (name, paramNames, localReactiveVars, callSites) */
    private data class CollectedComposable(
        val name: String,
        val paramNames: List<String>,
        val localReactiveVars: Set<String>,
        /** (calleeName, paramIndex, argSimpleName?) */
        val callSites: List<Triple<String, Int, String?>>,
        /** UMethod parameters for reporting */
        val uastParams: List<UParameter>,
    )

    private var collected: MutableList<CollectedComposable>? = null

    override fun beforeCheckFile(context: Context) {
        savedContext = null
        collected = mutableListOf()
    }

    override fun visitComposable(
        context: JavaContext,
        method: UMethod,
        function: KtFunction,
    ) {
        savedContext = context

        // 1) Reactive-type params (State<T>, Flow<T>): report immediately (per-composable)
        val reactiveParams = findReactiveParameters(method)
        for ((paramName, paramType) in reactiveParams) {
            val consumedLocally = isParameterConsumedLocally(function, paramName)
            val childCalls = findChildComposableCalls(function, paramName)
            if (!consumedLocally && childCalls.size == 1) {
                val param = method.uastParameters.find { it.name == paramName } ?: continue
                context.report(
                    ReactiveStatePassThroughIssue.ISSUE,
                    param as UElement,
                    context.getLocation(param as UElement),
                    "Reactive state parameter '$paramName' of type '$paramType' is passed through " +
                        "without being used. Consider moving state closer to usage or passing immutable value."
                )
            }
        }

        // 2) Collect info for file-level reactive-derived analysis (processed in afterCheckFile)
        val name = method.name
        val paramNames = method.uastParameters.map { it.name ?: "" }
        val localReactiveVars = findLocalReactiveVars(function)
        val callSites = findCallSites(function)
        collected?.add(CollectedComposable(name, paramNames, localReactiveVars, callSites, method.uastParameters.toList()))
    }

    override fun afterCheckFile(context: Context) {
        val javaCtx = savedContext ?: return
        val infos = collected ?: return
        if (infos.isEmpty()) return

        val composableNames = infos.map { it.name }.toSet()
        val infoByName = infos.associateBy { it.name }

        // Fixpoint: compute reactive-derived (methodName, paramIndex) pairs
        val derived = mutableSetOf<Pair<String, Int>>()
        var changed = true
        while (changed) {
            changed = false
            for (info in infos) {
                for ((calleeName, paramIdx, argName) in info.callSites) {
                    if (calleeName !in composableNames) continue
                    val calleeInfo = infoByName[calleeName] ?: continue
                    if (paramIdx >= calleeInfo.paramNames.size) continue
                    if (argName == null) continue

                    val isReactive = argName in info.localReactiveVars ||
                        run {
                            val callerParamIdx = info.paramNames.indexOf(argName)
                            callerParamIdx >= 0 && (info.name to callerParamIdx) in derived
                        }
                    if (isReactive) {
                        if (derived.add(calleeName to paramIdx)) changed = true
                    }
                }
            }
        }

        // Report derived pass-through params
        for (info in infos) {
            for ((idx, paramName) in info.paramNames.withIndex()) {
                if ((info.name to idx) !in derived) continue
                if (!isPassThrough(info, paramName, composableNames)) continue
                val param = info.uastParams.getOrNull(idx) ?: continue
                javaCtx.report(
                    ReactiveStatePassThroughIssue.ISSUE,
                    param as UElement,
                    javaCtx.getLocation(param as UElement),
                    "Reactive state or state-derived parameter '$paramName' is passed through " +
                        "without being used. Consider moving state closer to usage or passing immutable value."
                )
            }
        }

        // Cleanup
        savedContext = null
        collected = null
    }

    // ─── Data collection helpers (using Kotlin PSI, which works reliably in visitComposable) ───

    /**
     * Find local variable names whose value comes from mutableStateOf (via delegation).
     * e.g. `var den by remember { mutableStateOf(0) }`
     */
    private fun findLocalReactiveVars(function: KtFunction): Set<String> {
        val names = mutableSetOf<String>()
        for (prop in function.findChildrenByClass<KtProperty>()) {
            val name = prop.nameAsName?.asString() ?: continue
            val delegate = prop.delegate ?: continue
            val expr = delegate.expression ?: continue
            val text = expr.text ?: ""
            if (text.contains(MUTABLE_STATE_OF)) {
                names.add(name)
            }
        }
        return names
    }

    /**
     * Find call sites from this composable to others:
     * (calleeName, paramIndex, argSimpleName).
     */
    private fun findCallSites(function: KtFunction): List<Triple<String, Int, String?>> {
        val sites = mutableListOf<Triple<String, Int, String?>>()
        // Only look at DIRECT call expressions in the function body (not nested in lambdas of remember etc.)
        val body = function.bodyBlockExpression ?: return sites
        for (call in body.findChildrenByClass<KtCallExpression>()) {
            val calleeName = call.calleeExpression?.text ?: continue
            for ((idx, arg) in call.valueArguments.withIndex()) {
                val argExpr = arg.getArgumentExpression()?.unwrapParens()
                val argName = when (argExpr) {
                    is KtNameReferenceExpression -> argExpr.getReferencedName()
                    else -> null
                }
                sites.add(Triple(calleeName, idx, argName))
            }
        }
        return sites
    }

    /**
     * Check if a parameter is pass-through: appears exactly once in call sites as an argument
     * to a single composable, and nowhere else.
     */
    private fun isPassThrough(info: CollectedComposable, paramName: String, composableNames: Set<String>): Boolean {
        val composableArgUses = info.callSites.count { (calleeName, _, argName) ->
            argName == paramName && calleeName in composableNames
        }
        if (composableArgUses != 1) return false

        // Also check there's no OTHER use of the param (e.g. in non-composable calls, expressions, etc.)
        val totalArgUses = info.callSites.count { it.third == paramName }
        return totalArgUses == 1
    }

    // ─── Reactive-type parameter detection (State<T>, Flow<T>, etc.) ──

    private fun isReactiveType(type: String): Boolean =
        REACTIVE_TYPE_PATTERNS.any { type.contains(it) }

    private fun findReactiveParameters(method: UMethod): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        for (param in method.uastParameters) {
            val type = param.type.canonicalText
            if (isReactiveType(type)) {
                param.name?.let { name -> list.add(name to type) }
            }
        }
        return list
    }

    private fun isParameterConsumedLocally(function: KtFunction, paramName: String): Boolean {
        for (dot in function.findChildrenByClass<KtDotQualifiedExpression>()) {
            val receiver = dot.receiverExpression.unwrapParens()
            if (receiver is KtNameReferenceExpression && receiver.getReferencedName() == paramName) {
                val selector = dot.selectorExpression
                if (selector is KtNameReferenceExpression && selector.getReferencedName() in CONSUMPTION_METHODS) return true
                if (selector is KtCallExpression && selector.calleeExpression?.text in CONSUMPTION_METHODS) return true
            }
        }
        for (call in function.findChildrenByClass<KtCallExpression>()) {
            if (call.calleeExpression?.text in TRANSFORMATION_FUNCTIONS && callMentionsParameter(call, paramName)) return true
        }
        for (property in function.findChildrenByClass<KtProperty>()) {
            if (property.delegate?.expression?.let { delegateMentionsParameter(it, paramName) } == true) return true
        }
        return false
    }

    private fun callMentionsParameter(call: KtCallExpression, paramName: String): Boolean =
        call.findChildrenByClass<KtNameReferenceExpression>().any { it.getReferencedName() == paramName }

    private fun delegateMentionsParameter(expr: KtExpression?, paramName: String): Boolean {
        expr ?: return false
        return expr.findChildrenByClass<KtNameReferenceExpression>().any { it.getReferencedName() == paramName }
    }

    private fun findChildComposableCalls(function: KtFunction, paramName: String): List<KtCallExpression> {
        val list = mutableListOf<KtCallExpression>()
        for (call in function.findChildrenByClass<KtCallExpression>()) {
            if (call.calleeExpression?.text?.firstOrNull()?.isUpperCase() != true) continue
            for (arg in call.valueArguments) {
                val e = arg.getArgumentExpression()?.unwrapParens()
                if (e is KtNameReferenceExpression && e.getReferencedName() == paramName) {
                    list.add(call)
                    break
                }
            }
        }
        return list
    }

    private fun KtExpression?.unwrapParens(): KtExpression? {
        var e: KtExpression? = this
        while (e != null && e is KtParenthesizedExpression) {
            e = (e as KtParenthesizedExpression).expression
        }
        return e
    }
}
