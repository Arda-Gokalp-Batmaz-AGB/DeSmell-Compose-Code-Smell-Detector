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
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.uast.UMethod
import slack.lint.compose.util.findChildrenByClass

/**
 * Smell #16 — Non-Snapshot-Aware Collections in State
 *
 * Detects when a standard Kotlin collection (MutableList, Map, Set, etc.) is stored inside
 * Compose state (mutableStateOf, remember { mutableStateOf(...) }) and then mutated in-place.
 * In-place mutations (add, remove, put, clear, etc.) are not observed by the snapshot system,
 * so recomposition will not occur. Snapshot-aware alternatives are mutableStateListOf(),
 * mutableStateMapOf(), or reassigning a new collection (state.value = state.value + x).
 */
object NonSnapshotAwareCollectionInStateIssue {

    private const val ID = "NonSnapshotAwareCollectionInState"
    private const val DESCRIPTION = "In-place mutation of non-snapshot-aware collection stored in state"
    private val EXPLANATION = """
        A standard Kotlin collection (List, MutableList, Map, Set, etc.) is stored in Compose state
        and mutated in-place (e.g. .add(), .remove(), .put()). Compose observes the state holder
        reference, not internal mutations of non-snapshot collections, so recomposition will not occur.

        Use mutableStateListOf() / mutableStateMapOf() for observable list/map state, or replace the
        value instead of mutating (e.g. state.value = state.value + newItem).
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
            NonSnapshotAwareCollectionInStateDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )
    )
}

/**
 * Two-stage detection: (1) find state variables holding non-snapshot collections,
 * (2) find in-place mutation calls on those variables; report at mutation site.
 */
class NonSnapshotAwareCollectionInStateDetector : ComposableFunctionDetector(), SourceCodeScanner {

    companion object {
        /** Initializer/delegate must contain mutableStateOf( and one of these (non-snapshot). */
        private val NON_SNAPSHOT_COLLECTION_PATTERNS = listOf(
            "mutableListOf(",
            "arrayListOf(",
            "mutableMapOf(",
            "hashMapOf(",
            "mutableSetOf(",
            "hashSetOf(",
        )

        /** Exclude snapshot-aware; if present in same expression, not a candidate. */
        private val SNAPSHOT_AWARE_PATTERNS = listOf(
            "mutableStateListOf(",
            "mutableStateMapOf(",
        )

        private const val MUTABLE_STATE_OF = "mutableStateOf("

        /** In-place mutation methods (not replacement). */
        private val LIST_MUTATION_METHODS = setOf(
            "add", "addAll", "remove", "removeAll", "removeAt", "clear",
            "retainAll", "set", "sort", "shuffle", "replaceAll",
        )
        private val MAP_MUTATION_METHODS = setOf(
            "put", "putAll", "remove", "clear", "replace",
            "compute", "computeIfAbsent", "computeIfPresent", "merge",
        )
        private val SET_MUTATION_METHODS = setOf(
            "add", "addAll", "remove", "removeAll", "clear", "retainAll",
        )

        private val ALL_MUTATION_METHODS = LIST_MUTATION_METHODS +
            MAP_MUTATION_METHODS + SET_MUTATION_METHODS
    }

    override fun visitComposable(
        context: JavaContext,
        method: UMethod,
        function: KtFunction,
    ) {
        val (stateHolderNames, delegatedValueNames) = collectCandidates(function)

        if (stateHolderNames.isEmpty() && delegatedValueNames.isEmpty()) return

        // Match qualified calls: receiver.method(...) where receiver is stateVar.value or delegatedVar.
        // Search the whole function (like SnapshotStateListConcurrentAccessIssue) so we don't depend on body.
        for (dot in function.findChildrenByClass<KtDotQualifiedExpression>()) {
            val selector = dot.selectorExpression as? KtCallExpression ?: continue
            val methodName = (selector.calleeExpression as? KtNameReferenceExpression)?.getReferencedName()
                ?: selector.calleeExpression?.text ?: continue
            if (methodName !in ALL_MUTATION_METHODS) continue

            val receiverText = normalizeReceiverText(dot.receiverExpression.text)

            // S1: state holder — "itemsState.value" -> report if itemsState in stateHolderNames
            for (stateName in stateHolderNames) {
                if (receiverText == "$stateName.value") {
                    context.report(
                        NonSnapshotAwareCollectionInStateIssue.ISSUE,
                        selector,
                        context.getLocation(selector),
                        "In-place mutation of non-snapshot-aware collection stored in state '$stateName'. " +
                            "Use mutableStateListOf()/mutableStateMapOf() or reassign a copied collection.",
                    )
                    break
                }
            }
            // S2: delegated value — "items" -> report if items in delegatedValueNames
            if (receiverText in delegatedValueNames) {
                context.report(
                    NonSnapshotAwareCollectionInStateIssue.ISSUE,
                    selector,
                    context.getLocation(selector),
                    "In-place mutation of non-snapshot-aware collection stored in state '$receiverText'. " +
                        "Use mutableStateListOf()/mutableStateMapOf() or reassign a copied collection.",
                )
            }
        }
    }

    /** Trim, remove outer parentheses, and normalize whitespace so "list . value " matches "list.value". */
    private fun normalizeReceiverText(text: String): String {
        var t = text.trim()
        while (t.startsWith("(") && t.endsWith(")")) {
            t = t.removeSurrounding("(", ")").trim()
        }
        return t.replace(" ", "").replace("\n", "").replace("\t", "")
    }

    /**
     * Returns (stateHolderNames, delegatedValueNames).
     * State holder: val itemsState = remember { mutableStateOf(mutableListOf()) } or parameter items: MutableState<List<Int>>.
     * Delegated: var items by remember { mutableStateOf(mutableListOf()) }
     */
    private fun collectCandidates(function: KtFunction): Pair<Set<String>, Set<String>> {
        val stateHolderNames = mutableSetOf<String>()
        val delegatedValueNames = mutableSetOf<String>()

        for (prop in function.findChildrenByClass<KtProperty>()) {
            val name = prop.nameAsName?.asString() ?: continue

            val rootExpr = prop.delegate?.expression ?: prop.initializer ?: continue
            if (!isNonSnapshotCollectionState(rootExpr)) continue

            if (prop.delegate != null) {
                delegatedValueNames.add(name)
            } else {
                stateHolderNames.add(name)
            }
        }

        // Parameters: items: MutableState<List<Int>> etc. — treat as state holder for in-place mutation checks
        for (param in function.valueParameters) {
            val name = param.nameAsName?.asString() ?: continue
            val typeText = param.typeReference?.text ?: continue
            if (isParameterTypeNonSnapshotCollectionState(typeText)) {
                stateHolderNames.add(name)
            }
        }

        return stateHolderNames to delegatedValueNames
    }

    /**
     * True if parameter type is MutableState<...> where the inner type is a non-snapshot collection
     * (List, MutableList, Map, Set, etc.), e.g. MutableState<List<Int>>, MutableState<MutableMap<K,V>>.
     */
    private fun isParameterTypeNonSnapshotCollectionState(typeText: String): Boolean {
        val t = typeText.replace(" ", "")
        if (!t.contains("MutableState<") && !t.contains("State<")) return false
        if (t.contains("SnapshotStateList") || t.contains("SnapshotStateMap")) return false
        return t.contains("List<") || t.contains("MutableList<") ||
            t.contains("Map<") || t.contains("MutableMap<") ||
            t.contains("Set<") || t.contains("MutableSet<") ||
            t.contains("ArrayList<") || t.contains("HashMap<") || t.contains("HashSet<")
    }

    /**
     * True if rootExpr (initializer or delegate) creates state holding a non-snapshot collection.
     * E.g. remember { mutableStateOf(mutableListOf()) } or mutableStateOf(mutableListOf<Int>()).
     * Normalize whitespace so lint "Extra whitespace" test mode still matches.
     * Match both mutableListOf( and mutableListOf< for generics.
     */
    private fun isNonSnapshotCollectionState(rootExpr: KtExpression): Boolean {
        val exprText = rootExpr.text.replace(" ", "").replace("\n", "").replace("\t", "")
        if (!exprText.contains("mutableStateOf(")) return false
        if (SNAPSHOT_AWARE_PATTERNS.any { exprText.contains(it.replace(" ", "")) }) return false
        return NON_SNAPSHOT_COLLECTION_PATTERNS_ANY.any { exprText.contains(it) }
    }

    /** Patterns that match generics too: mutableListOf( or mutableListOf<, etc. (no space in normalized text). */
    private val NON_SNAPSHOT_COLLECTION_PATTERNS_ANY = listOf(
        "mutableListOf(", "mutableListOf<",
        "arrayListOf(", "arrayListOf<",
        "mutableMapOf(", "mutableMapOf<",
        "hashMapOf(", "hashMapOf<",
        "mutableSetOf(", "mutableSetOf<",
        "hashSetOf(", "hashSetOf<",
    )

}
