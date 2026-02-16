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
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UParameter
import slack.lint.compose.util.findChildrenByClass

/**
 * Reactive State Pass-Through Issue
 *
 * Flags composables that receive reactive state (State<T>, StateFlow<T>, Flow<T>) or values
 * derived from local reactive state (e.g. Int/String from `var x by remember { mutableStateOf(0) }`)
 * as parameters but do not consume or transform them, only passing unchanged to a child.
 *
 * Only reports when the same parameter is passed through at least two consecutive composables
 * without being consumed (chain of >= 2 pass-throughs).
 */
object ReactiveStatePassThroughIssue {

    private const val ID = "ReactiveStatePassThrough"
    private const val DESCRIPTION = "Reactive state passed through composable without being used"
    private val EXPLANATION = """
        This composable receives reactive state (or a value from reactive state) as a parameter
        but does not consume, transform, or share it—only passing it unchanged through at least two
        consecutive composable layers.
        
        Passing reactive objects or state-derived values through multiple intermediate composables
        without use increases recomposition scope and violates proper state ownership principles.
        
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
 * Detects reactive state (or state-derived value) parameters that are passed through
 * at least two consecutive composables without being consumed (chain >= 2).
 */
class ReactiveStatePassThroughDetector : ComposableFunctionDetector(), SourceCodeScanner {

    companion object {
        private val REACTIVE_TYPE_PATTERNS = listOf(
            "State<", "MutableState<", "StateFlow<", "MutableStateFlow<", "Flow<", "LiveData<",
            "androidx.compose.runtime.State", "androidx.compose.runtime.MutableState",
            "kotlinx.coroutines.flow.StateFlow", "kotlinx.coroutines.flow.MutableStateFlow",
            "kotlinx.coroutines.flow.Flow", "androidx.lifecycle.LiveData",
        )
        private const val MUTABLE_STATE_OF = "mutableStateOf"
    }

    // ─── Per-file state: accumulated during visitComposable, processed in afterCheckFile ───

    /** Stored JavaContext for reporting in afterCheckFile */
    private var savedContext: JavaContext? = null

    /** A single argument at a call site */
    private data class CallSiteArg(
        /** Raw callee function name (e.g. "LayerB") */
        val calleeName: String,
        /** Number of value arguments at the call site (for overload disambiguation) */
        val callArgCount: Int,
        /** Positional index of the argument at the call site */
        val positionalIdx: Int,
        /** Named argument name (e.g. "state" in `state = state`), null for positional args */
        val namedArgName: String?,
        /** Simple name of the argument value expression (e.g. "flow" in `LayerB(flow)`), null if complex */
        val argValueName: String?,
    ) {
        /** Resolve the callee's parameter index, using named argument if available */
        fun resolveParamIdx(calleeParamNames: List<String>): Int {
            if (namedArgName != null) {
                val idx = calleeParamNames.indexOf(namedArgName)
                if (idx >= 0) return idx
            }
            return positionalIdx
        }
    }

    /** Collected composable info */
    private data class CollectedComposable(
        /** Unique identifier for this composable (counter-based, collision-free) */
        val uniqueId: String,
        /** Original function name for display in reports and call-site matching */
        val displayName: String,
        val paramNames: List<String>,
        val localReactiveVars: Set<String>,
        val callSites: List<CallSiteArg>,
        /** UMethod parameters for reporting */
        val uastParams: List<UParameter>,
        /** paramIdx -> type string for reactive-type params (State<T>, Flow<T>, etc.) */
        val reactiveParamTypes: Map<Int, String>,
        /** param indices of reactive-type params that are pass-through candidates */
        val reactivePassThroughIndices: Set<Int>,
        /** local reactive var name -> KtProperty PSI element (for creation-point reporting) */
        val localReactiveVarProperties: Map<String, KtProperty>,
        /** Parameter names that are consumed locally (used beyond just being a function argument) */
        val consumedParams: Set<String>,
    )

    private var collected: MutableList<CollectedComposable>? = null
    private var composableCounter = 0

    override fun beforeCheckFile(context: Context) {
        savedContext = null
        collected = mutableListOf()
        composableCounter = 0
    }

    override fun visitComposable(
        context: JavaContext,
        method: UMethod,
        function: KtFunction,
    ) {
        savedContext = context

        val displayName = method.name
        val paramNames = method.uastParameters.map { it.name ?: "" }
        val uniqueId = "${displayName}__${composableCounter++}"

        // 1) Compute consumed params: any param used beyond being a direct function argument
        val consumedParams = mutableSetOf<String>()
        for (pName in paramNames) {
            if (pName.isNotEmpty() && isParameterConsumedLocally(function, pName)) {
                consumedParams.add(pName)
            }
        }

        // 2) Analyze reactive-type params (State<T>, Flow<T>) — do NOT report yet, collect for chain analysis
        val reactiveParams = findReactiveParameters(method)
        val reactiveParamTypes = mutableMapOf<Int, String>()
        val reactivePassThroughIndices = mutableSetOf<Int>()
        for ((paramName, paramType) in reactiveParams) {
            val paramIdx = method.uastParameters.indexOfFirst { it.name == paramName }
            if (paramIdx < 0) continue
            reactiveParamTypes[paramIdx] = paramType
            val consumedLocally = paramName in consumedParams
            val childCalls = findChildComposableCalls(function, paramName)
            if (!consumedLocally && childCalls.size == 1) {
                reactivePassThroughIndices.add(paramIdx)
            }
        }

        // 3) Collect info for file-level analysis (both reactive-type and derived, processed in afterCheckFile)
        val localReactiveVarProperties = findLocalReactiveVarProperties(function)
        val localReactiveVars = localReactiveVarProperties.keys
        val callSites = findCallSites(function)
        collected?.add(CollectedComposable(
            uniqueId, displayName, paramNames, localReactiveVars, callSites,
            method.uastParameters.toList(),
            reactiveParamTypes, reactivePassThroughIndices,
            localReactiveVarProperties,
            consumedParams
        ))
    }

    override fun afterCheckFile(context: Context) {
        val javaCtx = savedContext ?: return
        val infos = collected ?: return
        if (infos.isEmpty()) return

        // ── Build overload resolution infrastructure ──
        // Group composables by display name for call-site resolution
        val infosByDisplayName: Map<String, List<CollectedComposable>> = infos.groupBy { it.displayName }
        val composableDisplayNames: Set<String> = infosByDisplayName.keys
        val infoByUniqueId: Map<String, CollectedComposable> = infos.associateBy { it.uniqueId }

        // Resolve a callee name + arg count to the matching composable (handles overloads)
        fun resolveCallee(rawCalleeName: String, callArgCount: Int): CollectedComposable? {
            val candidates = infosByDisplayName[rawCalleeName] ?: return null
            if (candidates.size == 1) return candidates[0]
            // Multiple overloads: prefer exact param count match, fall back to closest
            return candidates.firstOrNull { it.paramNames.size == callArgCount }
                ?: candidates.minByOrNull { kotlin.math.abs(it.paramNames.size - callArgCount) }
        }

        // ── Fixpoint: compute reactive-derived (uniqueId, paramIndex) pairs ──
        // Also track origin: (originUniqueId, originVariableName)
        val derived = mutableSetOf<Pair<String, Int>>()
        val derivedOrigins = mutableMapOf<Pair<String, Int>, Pair<String, String>>()
        var changed = true
        while (changed) {
            changed = false
            for (info in infos) {
                for (cs in info.callSites) {
                    if (cs.calleeName !in composableDisplayNames) continue
                    val calleeInfo = resolveCallee(cs.calleeName, cs.callArgCount) ?: continue
                    val resolvedIdx = cs.resolveParamIdx(calleeInfo.paramNames)
                    if (resolvedIdx >= calleeInfo.paramNames.size) continue
                    val argName = cs.argValueName ?: continue

                    val isReactive = argName in info.localReactiveVars ||
                        run {
                            val callerParamIdx = info.paramNames.indexOf(argName)
                            callerParamIdx >= 0 && (info.uniqueId to callerParamIdx) in derived
                        }
                    if (isReactive) {
                        val target = calleeInfo.uniqueId to resolvedIdx
                        if (derived.add(target)) {
                            changed = true
                            // Track origin of the reactive state
                            val origin = if (argName in info.localReactiveVars) {
                                info.uniqueId to argName
                            } else {
                                val callerParamIdx = info.paramNames.indexOf(argName)
                                if (callerParamIdx >= 0) derivedOrigins[info.uniqueId to callerParamIdx] else null
                            }
                            if (origin != null) {
                                derivedOrigins[target] = origin
                            }
                        }
                    }
                }
            }
        }

        // ── Build pass-through nodes and edges for chain detection ──
        val passThroughNodes = mutableSetOf<Pair<String, Int>>()
        val passThroughEdges = mutableMapOf<Pair<String, Int>, Pair<String, Int>>()

        // Reactive-type pass-throughs (State<T>, Flow<T>, etc.)
        for (info in infos) {
            for (paramIdx in info.reactivePassThroughIndices) {
                val node = info.uniqueId to paramIdx
                passThroughNodes.add(node)
                val paramName = info.paramNames[paramIdx]
                val target = findPassThroughTarget(info, paramName, composableDisplayNames, ::resolveCallee)
                if (target != null) {
                    passThroughEdges[node] = target
                }
            }
        }

        // Derived reactive pass-throughs (values from mutableStateOf, etc.)
        for (info in infos) {
            for ((idx, paramName) in info.paramNames.withIndex()) {
                val node = info.uniqueId to idx
                if (node in passThroughNodes) continue  // Already handled as reactive-type
                if (node !in derived) continue
                if (!isPassThrough(info, paramName, composableDisplayNames)) continue
                passThroughNodes.add(node)
                val target = findPassThroughTarget(info, paramName, composableDisplayNames, ::resolveCallee)
                if (target != null) {
                    passThroughEdges[node] = target
                }
            }
        }

        // ── Find chains of length >= 2: flag nodes whose successor is also a pass-through ──
        val toFlag = mutableSetOf<Pair<String, Int>>()
        for ((source, target) in passThroughEdges) {
            if (target in passThroughNodes) {
                toFlag.add(source)
                toFlag.add(target)
            }
        }

        // ── Build unified origin map: (uniqueId, paramIdx) -> (originUniqueId, originVar) ──
        val origins = mutableMapOf<Pair<String, Int>, Pair<String, String>>()

        // Origins from derived analysis (mutableStateOf-based values)
        for ((node, origin) in derivedOrigins) {
            if (node in toFlag) {
                origins[node] = origin
            }
        }

        // Origins for reactive-type chains: search for caller with a local reactive var
        val reverseEdges = mutableMapOf<Pair<String, Int>, Pair<String, Int>>()
        for ((source, target) in passThroughEdges) {
            reverseEdges[target] = source
        }
        for (info in infos) {
            for (paramIdx in info.reactivePassThroughIndices) {
                val node = info.uniqueId to paramIdx
                if (node !in toFlag || node in origins) continue
                // Trace backward to find the root of this chain
                var root = node
                while (reverseEdges.containsKey(root)) {
                    root = reverseEdges[root]!!
                }
                // Search composables for who calls the root function with a local reactive var
                val rootUniqueId = root.first
                val rootParamIdx = root.second
                val rootInfo = infoByUniqueId[rootUniqueId] ?: continue
                for (callerInfo in infos) {
                    for (cs in callerInfo.callSites) {
                        if (cs.calleeName != rootInfo.displayName) continue
                        val resolved = resolveCallee(cs.calleeName, cs.callArgCount) ?: continue
                        if (resolved.uniqueId != rootUniqueId) continue
                        val resolvedIdx = cs.resolveParamIdx(resolved.paramNames)
                        if (resolvedIdx == rootParamIdx && cs.argValueName != null) {
                            if (cs.argValueName in callerInfo.localReactiveVars) {
                                val origin = callerInfo.uniqueId to cs.argValueName
                                // Set origin for all nodes in the chain from root onward
                                var chainNode: Pair<String, Int>? = root
                                while (chainNode != null && chainNode in toFlag) {
                                    origins[chainNode] = origin
                                    chainNode = passThroughEdges[chainNode]
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Report only flagged pass-throughs (part of a chain >= 2) ──
        for (info in infos) {
            for ((idx, paramName) in info.paramNames.withIndex()) {
                val node = info.uniqueId to idx
                if (node !in toFlag) continue
                val param = info.uastParams.getOrNull(idx) ?: continue

                val origin = origins[node]
                val originInfo = if (origin != null) infoByUniqueId[origin.first] else null
                val originDisplayName = originInfo?.displayName

                val originSuffix = if (origin != null && originDisplayName != null) {
                    " (originated from variable '${origin.second}' in function '$originDisplayName')"
                } else ""

                val message = if (idx in info.reactiveParamTypes) {
                    val paramType = info.reactiveParamTypes[idx]!!
                    "Reactive state parameter '$paramName' of type '$paramType' in function '${info.displayName}' " +
                        "is passed through without being used$originSuffix. " +
                        "Consider moving state closer to usage or passing immutable value."
                } else {
                    "Reactive state or state-derived parameter '$paramName' in function '${info.displayName}' " +
                        "is passed through without being used$originSuffix. " +
                        "Consider moving state closer to usage or passing immutable value."
                }

                // Primary location: the pass-through parameter
                var location = javaCtx.getLocation(param as UElement)

                // Secondary location: the creation point of the reactive state variable
                if (origin != null && originInfo != null) {
                    val prop = originInfo.localReactiveVarProperties[origin.second]
                    if (prop != null) {
                        location = location.withSecondary(
                            javaCtx.getLocation(prop),
                            "Reactive state variable '${origin.second}' created here in function '${originInfo.displayName}'"
                        )
                    }
                }

                javaCtx.report(
                    ReactiveStatePassThroughIssue.ISSUE,
                    param as UElement,
                    location,
                    message
                )
            }
        }

        // Cleanup
        savedContext = null
        collected = null
    }

    // ─── Data collection helpers (using Kotlin PSI, which works reliably in visitComposable) ───

    /**
     * Find local variables whose value comes from mutableStateOf (via delegation).
     * e.g. `var den by remember { mutableStateOf(0) }`
     * Returns name -> KtProperty PSI element for creation-point reporting.
     */
    private fun findLocalReactiveVarProperties(function: KtFunction): Map<String, KtProperty> {
        val map = mutableMapOf<String, KtProperty>()
        for (prop in function.findChildrenByClass<KtProperty>()) {
            val name = prop.nameAsName?.asString() ?: continue
            val delegate = prop.delegate ?: continue
            val expr = delegate.expression ?: continue
            val text = expr.text ?: ""
            if (text.contains(MUTABLE_STATE_OF)) {
                map[name] = prop
            }
        }
        return map
    }

    /**
     * Find call sites from this composable to others.
     * Named argument names are captured to correctly resolve parameter indices
     * regardless of argument order.
     */
    private fun findCallSites(function: KtFunction): List<CallSiteArg> {
        val sites = mutableListOf<CallSiteArg>()
        // Only look at DIRECT call expressions in the function body (not nested in lambdas of remember etc.)
        val body = function.bodyBlockExpression ?: return sites
        for (call in body.findChildrenByClass<KtCallExpression>()) {
            val calleeName = call.calleeExpression?.text ?: continue
            val callArgCount = call.valueArguments.size
            for ((idx, arg) in call.valueArguments.withIndex()) {
                val argExpr = arg.getArgumentExpression()?.unwrapParens()
                val argValueName = when (argExpr) {
                    is KtNameReferenceExpression -> argExpr.getReferencedName()
                    else -> null
                }
                val namedArgName = arg.getArgumentName()?.asName?.asString()
                sites.add(CallSiteArg(calleeName, callArgCount, idx, namedArgName, argValueName))
            }
        }
        return sites
    }

    /**
     * Find the target composable and param index that a pass-through parameter is forwarded to.
     * Returns (childUniqueId, childParamIdx) or null if not found in known composables.
     * Uses named argument resolution and overload resolution.
     */
    private fun findPassThroughTarget(
        info: CollectedComposable,
        paramName: String,
        composableDisplayNames: Set<String>,
        resolveCallee: (String, Int) -> CollectedComposable?,
    ): Pair<String, Int>? {
        for (cs in info.callSites) {
            if (cs.argValueName == paramName && cs.calleeName in composableDisplayNames) {
                val calleeInfo = resolveCallee(cs.calleeName, cs.callArgCount) ?: continue
                val resolvedIdx = cs.resolveParamIdx(calleeInfo.paramNames)
                return calleeInfo.uniqueId to resolvedIdx
            }
        }
        return null
    }

    /**
     * Check if a parameter is pass-through: not consumed locally (used beyond being a function
     * argument), appears exactly once in call sites as an argument to a single composable,
     * and nowhere else.
     */
    private fun isPassThrough(info: CollectedComposable, paramName: String, composableDisplayNames: Set<String>): Boolean {
        // If the param is consumed locally (used beyond just being a function argument), not pass-through
        if (paramName in info.consumedParams) return false

        val composableArgUses = info.callSites.count { cs ->
            cs.argValueName == paramName && cs.calleeName in composableDisplayNames
        }
        if (composableArgUses != 1) return false

        // Also check there's no OTHER use of the param (e.g. in non-composable calls)
        val totalArgUses = info.callSites.count { it.argValueName == paramName }
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

    /**
     * A parameter is considered "consumed locally" if it is used ANYWHERE in the function
     * other than being passed as a direct argument to another function call.
     *
     * Examples of consumption: state.toString(), state.value, state.let { ... },
     * val x = state, val v by state, state + 1, etc.
     *
     * NOT consumption: Child(state), someFunction(state) — just forwarding the reference.
     */
    private fun isParameterConsumedLocally(function: KtFunction, paramName: String): Boolean {
        for (ref in function.findChildrenByClass<KtNameReferenceExpression>()) {
            if (ref.getReferencedName() != paramName) continue
            // If this reference is just a direct argument to a function call, skip it
            if (isDirectFunctionArgument(ref)) continue
            // Any other usage = consumed
            return true
        }
        return false
    }

    /**
     * Check if a name reference is used only as a direct argument to a function call,
     * i.e. it appears (possibly wrapped in parentheses) as a KtValueArgument value,
     * OR if it appears as a named argument label (e.g., "state" in "state = value").
     */
    private fun isDirectFunctionArgument(ref: KtNameReferenceExpression): Boolean {
        var node = ref.parent ?: return false
        while (node is KtParenthesizedExpression) {
            node = node.parent ?: return false
        }
        // Direct value argument (e.g., `Child(state)`)
        if (node is KtValueArgument) return true
        // Named argument label: ref is inside the label part of a value argument.
        // The parent is some intermediate node (KtValueArgumentName or similar),
        // and the grandparent is KtValueArgument. In this case, the reference
        // is not a consumption — it's just the parameter name label.
        val grandparent = node.parent
        if (grandparent is KtValueArgument) {
            val argExpr = grandparent.getArgumentExpression()
            if (argExpr !== ref) return true
        }
        return false
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
