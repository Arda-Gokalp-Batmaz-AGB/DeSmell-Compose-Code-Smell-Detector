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
import org.jetbrains.uast.*
import org.jetbrains.uast.util.isMethodCall
import com.intellij.psi.PsiVariable
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtPropertyDelegate
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
import org.jetbrains.uast.visitor.AbstractUastVisitor
import com.android.tools.lint.detector.api.*
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import slack.lint.compose.util.findChildrenByClass

/**
 * Mutable State Used Directly in Boolean Conditions
 */
object MutableStateInConditionIssue {

    private const val ID = "MutableStateInCondition"
    private const val DESCRIPTION = "MutableState used directly inside boolean conditions"
    private val EXPLANATION = """
        Using mutable-state directly inside conditions (if/when/&&/||) causes the condition to be
        re-evaluated on every recomposition. Wrap this logic inside derivedStateOf to avoid redundant
        recompositions and improve performance.
    """.trimIndent()

    private val CATEGORY = Category.PERFORMANCE
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
            MutableStateInConditionDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )
    )
}

class MutableStateInConditionDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UIfExpression::class.java, USwitchExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {

            override fun visitIfExpression(node: UIfExpression) {
                val condition = node.condition ?: return
                if (!isInsideComposable(node)) return

                // Do NOT flag: if (myBoolState)
                if (isDirectBooleanState(condition)) return

                // Collect all mutable state reads in the condition and report separately for each
                val mutableStateReads = collectMutableStateReads(condition)
                for (read in mutableStateReads) {
                    context.report(
                        MutableStateInConditionIssue.ISSUE,
                        read,
                        context.getLocation(read),
                        "Avoid using MutableState value directly in boolean/comparison conditions."
                    )
                }
            }

            override fun visitSwitchExpression(node: USwitchExpression) {
                val subject = node.expression ?: return
                if (!isInsideComposable(node)) return

                // Flag only when non-enum MutableState value used in when()
                if (isEnumType(subject.getExpressionType())) return
                if (isDirectBooleanState(subject)) return

                if (containsMutableStateValueRead(subject)) {
                    context.report(
                        MutableStateInConditionIssue.ISSUE,
                        subject,
                        context.getLocation(subject),
                        "Avoid using MutableState value directly in `when` subject. Hoist or wrap it."
                    )
                }
            }
        }

    // ════════════════════════════════════════════
    //  COMPOSABLE SCOPE DETECTION
    // ════════════════════════════════════════════

    private fun isInsideComposable(node: UElement): Boolean {
        val method = node.getParentOfType<UMethod>(true) ?: return false
        return method.annotations.any { it.qualifiedName == "androidx.compose.runtime.Composable" }
    }

    // ════════════════════════════════════════════
    //  DIRECT BOOLEAN STATE: allow
    // ════════════════════════════════════════════

    private fun isReadOfDelegatedMutableState(ref: USimpleNameReferenceExpression): Boolean {
        val resolved = ref.resolve() as? PsiVariable ?: return false
        val uVar = resolved.toUElement() as? UVariable ?: return false
        val ktProperty = uVar.sourcePsi as? KtProperty ?: return false
        
        // Check if this is a delegated property: var x by mutableStateOf(...) or var x by remember { mutableStateOf(...) }
        if (ktProperty.delegate != null) {
            // Check if the delegate expression contains mutableStateOf
            val delegateExpr = ktProperty.delegate?.expression
            if (delegateExpr != null) {
                // Check if delegate is a call to mutableStateOf or remember
                if (delegateExpr is KtCallExpression) {
                    val callee = delegateExpr.calleeExpression?.text
                    if (callee == "mutableStateOf" || callee == "remember") {
                        return true
                    }
                }
                // Also check nested calls (e.g., remember { mutableStateOf(...) })
                val callExpressions = delegateExpr.findChildrenByClass<KtCallExpression>()
                for (call in callExpressions) {
                    val callee = call.calleeExpression?.text
                    if (callee == "mutableStateOf") {
                        return true
                    }
                }
            }
        }
        
        // Fallback: check initializer for non-delegated cases
        val initializer = uVar.uastInitializer ?: return false

        // Delegated mutable state: var x by remember { mutableStateOf(...) }
        if (initializer is UCallExpression) {
            if (initializer.methodName == "remember") return true
            if (initializer.methodName == "mutableStateOf") return true
        }

        return false
    }


    /** Allows: if (myBoolState) or when(myBoolState) */
    private fun isDirectBooleanState(expr: UExpression): Boolean {

        // Must be a plain reference: myBoolState
        val ref = when (expr) {
            is USimpleNameReferenceExpression -> expr
            else -> return false
        }

        // Resolve variable
        val resolved = ref.resolve() as? PsiVariable ?: return false
        val type = resolved.type.canonicalText ?: return false

        // Must be MutableState<Boolean>
        val isMutableBooleanState =
            type.startsWith("androidx.compose.runtime.MutableState") &&
                    type.contains("kotlin.Boolean")

        if (!isMutableBooleanState) return false

        // Also ensure it is not something like myBoolState.value (UQualifiedReference)
        if (expr is UQualifiedReferenceExpression) return false

        return true
    }
    // ════════════════════════════════════════════
    //  COMPARISON / BOOLEAN OPERATOR CHECK
    // ════════════════════════════════════════════

    private fun containsMutableStateComparison(expr: UExpression): Boolean {
        return when (expr) {

            is UBinaryExpression -> {
                val op = expr.operator

                if (isBooleanOrComparisonOperator(op)) {
                    val left = containsMutableStateValueRead(expr.leftOperand)
                    val right = containsMutableStateValueRead(expr.rightOperand)
                    left || right
                } else {
                    containsMutableStateComparison(expr.leftOperand) ||
                            containsMutableStateComparison(expr.rightOperand)
                }
            }

            is UParenthesizedExpression ->
                containsMutableStateComparison(expr.expression)

            is UPrefixExpression ->
                containsMutableStateComparison(expr.operand)

            else -> false
        }
    }

    /**
     * Collects all mutable state reads in an expression, returning the actual expression nodes
     * that represent mutable state reads (for reporting purposes).
     */
    private fun collectMutableStateReads(expr: UExpression?): List<UExpression> {
        if (expr == null) return emptyList()

        val results = mutableListOf<UExpression>()

        when (expr) {
            is USimpleNameReferenceExpression -> {
                // Allow derivedStateOf-backed variables (with or without delegation)
                if (isRememberedDerivedStateReceiver(expr)) {
                    return results
                }
                if (isReadOfDelegatedMutableState(expr)) {
                    results.add(expr)
                }
            }

            is UQualifiedReferenceExpression -> {
                val selectorName = expr.selector.asSimpleName()
                if (selectorName in VALUE_PROPERTIES) {
                    if (!isRememberedDerivedStateReceiver(expr.receiver) &&
                        isMutableStateReceiver(expr.receiver)
                    ) {
                        results.add(expr)
                    }
                }
                results.addAll(collectMutableStateReads(expr.receiver))
                results.addAll(collectMutableStateReads(expr.selector as? UExpression))
            }

            is UBinaryExpression -> {
                results.addAll(collectMutableStateReads(expr.leftOperand))
                results.addAll(collectMutableStateReads(expr.rightOperand))
            }

            is UParenthesizedExpression -> {
                results.addAll(collectMutableStateReads(expr.expression))
            }

            is UPrefixExpression -> {
                results.addAll(collectMutableStateReads(expr.operand))
            }
        }

        return results
    }

    private fun isBooleanOrComparisonOperator(op: UastBinaryOperator): Boolean {
        return op == UastBinaryOperator.LOGICAL_AND ||
                op == UastBinaryOperator.LOGICAL_OR ||
                op == UastBinaryOperator.EQUALS ||
                op == UastBinaryOperator.NOT_EQUALS ||
                op == UastBinaryOperator.GREATER ||
                op == UastBinaryOperator.GREATER_OR_EQUALS ||
                op == UastBinaryOperator.LESS ||
                op == UastBinaryOperator.LESS_OR_EQUALS
    }

    // ════════════════════════════════════════════
    //  MUTABLE STATE VALUE READ DETECTION
    // ════════════════════════════════════════════

    private fun containsMutableStateValueRead(expr: UExpression?): Boolean {
        if (expr == null) return false

        return when (expr) {

            is USimpleNameReferenceExpression -> {
                // Allow derivedStateOf-backed variables (with or without delegation)
                if (isRememberedDerivedStateReceiver(expr)) return false
                // Check if this is a delegated mutable state property
                isReadOfDelegatedMutableState(expr)
            }

            is UQualifiedReferenceExpression -> {
                val selectorName = expr.selector.asSimpleName()

                if (selectorName in VALUE_PROPERTIES) {
                    if (isRememberedDerivedStateReceiver(expr.receiver)) {
                        return false
                    }
                    if (isMutableStateReceiver(expr.receiver)) {
                        return true
                    }
                }

                containsMutableStateValueRead(expr.receiver) ||
                        containsMutableStateValueRead(expr.selector as? UExpression)
            }

            is UParenthesizedExpression ->
                containsMutableStateValueRead(expr.expression)

            is UBinaryExpression ->
                containsMutableStateValueRead(expr.leftOperand) ||
                        containsMutableStateValueRead(expr.rightOperand)

            is UPrefixExpression ->
                containsMutableStateValueRead(expr.operand)

            else -> false
        }
    }

    /**
     * Unwraps parenthesized expressions to get the inner expression.
     * This prevents false negatives when lint inserts parentheses.
     */
    private fun UExpression.unwrapParens(): UExpression {
        var e: UExpression = this
        while (e is UParenthesizedExpression) {
            val inner = e.expression ?: break
            e = inner
        }
        return e
    }

    private fun isMutableStateReceiver(receiver: UExpression?): Boolean {
        val type = receiver?.getExpressionType()?.canonicalText ?: return false
        // Only flag MutableState, not State<T> (which includes DerivedState)
        return type.startsWith("androidx.compose.runtime.MutableState<") ||
                type.contains("SnapshotState")
    }

    /**
     * Checks if a UElement contains a call expression with the given method name.
     */
    private fun UElement.containsCallNamed(name: String): Boolean {
        var found = false
        accept(object : AbstractUastVisitor() {
            override fun visitCallExpression(node: UCallExpression): Boolean {
                if (node.methodName == name) {
                    found = true
                    return true
                }
                return super.visitCallExpression(node)
            }
            override fun visitElement(node: UElement): Boolean {
                return found || super.visitElement(node)
            }
        })
        return found
    }

    /**
     * Checks if the receiver expression is a variable initialized from derivedStateOf
     * (directly or inside remember { ... }).
     * 
     * This function answers: "Is this receiver a variable whose initializer contains 
     * derivedStateOf (optionally inside remember)?"
     * 
     * Never flag `.value` reads when the receiver variable is initialized from derivedStateOf.
     */
    private fun isRememberedDerivedStateReceiver(receiver: UExpression?): Boolean {
        val r = (receiver ?: return false).unwrapParens()

        // If the type system gives us DerivedState, accept fast
        val t = r.getExpressionType()
        val canon = t?.canonicalText.orEmpty()
        val pres = t?.presentableText.orEmpty()
        if ("DerivedState" in canon || "DerivedState" in pres) return true

        // We only handle `cond`-like references here
        val ref = r as? USimpleNameReferenceExpression ?: return false
        val resolved = ref.resolve() as? PsiVariable ?: return false
        val uVar = resolved.toUElement() as? UVariable ?: return false

        // Try UAST initializer first (most reliable when available)
        val init = uVar.uastInitializer?.unwrapParens()
        if (init != null) {
            // val cond = derivedStateOf { ... }
            if (init is UCallExpression && init.methodName == "derivedStateOf") return true

            // val cond = remember { derivedStateOf { ... } }
            if (init is UCallExpression && init.methodName == "remember") {
                if (init.containsCallNamed("derivedStateOf")) return true
            }
        }

        // Fallback to PSI if UAST initializer is not available (common in test environments)
        val ktProperty = uVar.sourcePsi as? KtProperty ?: return false
        val ktInitializer = ktProperty.initializer
        if (ktInitializer != null) {
            // Check if initializer contains derivedStateOf call
            val hasDerivedState = ktInitializer.findChildrenByClass<KtCallExpression>()
                .any { it.calleeExpression?.text == "derivedStateOf" }
            if (hasDerivedState) return true
        }

        // val cond by remember { derivedStateOf { ... } }  (delegated)
        // Check delegate expression for delegated properties
        val delegateExpr = ktProperty.delegate?.expression
        if (delegateExpr != null) {
            val hasDerivedStateInDelegate = delegateExpr.findChildrenByClass<KtCallExpression>()
                .any { it.calleeExpression?.text == "derivedStateOf" }
            if (hasDerivedStateInDelegate) return true
        }

        return false
    }

    private fun isEnumType(type: PsiType?): Boolean {
        val psiClass = (type as? PsiClassType)?.resolve() ?: return false
        return psiClass.isEnum
    }

    private fun UExpression.asSimpleName(): String? =
        when (this) {
            is USimpleNameReferenceExpression -> identifier
            is UQualifiedReferenceExpression -> selector.asSimpleName()
            else -> null
        }

    companion object {
        private val VALUE_PROPERTIES = setOf(
            "value", "intValue", "floatValue",
            "doubleValue", "longValue", "booleanValue"
        )
    }
}