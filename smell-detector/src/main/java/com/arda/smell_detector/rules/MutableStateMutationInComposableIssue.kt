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
import com.intellij.psi.PsiVariable
import org.jetbrains.uast.UBinaryExpression
import org.jetbrains.uast.UBlockExpression
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.ULocalVariable
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.USimpleNameReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.asRecursiveLogString
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.util.isAssignment
import org.jetbrains.uast.visitor.AbstractUastVisitor
import com.android.tools.lint.detector.api.*
import com.arda.smell_detector.helpers.asPsiRecursiveLogString
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiType
import org.jetbrains.uast.*
import org.jetbrains.kotlin.psi.*
import slack.lint.compose.util.isComposableFunction

object MutableStateMutationInComposableIssue {

    private const val ID = "MutableStateMutationInComposable"
    private const val DESCRIPTION = "MutableState mutated directly inside a composable"
    private val EXPLANATION = """
        Occurs when a MutableState is directly modified (.value = ...) inside a composable function
        rather than through a ViewModel or controlled side-effect. Composables should observe state,
        not mutate it, to preserve unidirectional data flow and avoid unpredictable recompositions.
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
            MutableStateMutationInComposableDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )
    )
}

class MutableStateMutationInComposableDetector : Detector(), SourceCodeScanner {

    /** Tracks variable names that hold delegated MutableState instances (var x by remember { ... }). */
    private val delegatedStateVars = mutableSetOf<String>()

    override fun beforeCheckFile(context: Context) {
        delegatedStateVars.clear()
    }

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(ULocalVariable::class.java, UBinaryExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {

            // ----------------------------------------------------------
            // 1) Detect local delegated MutableState vars
            // ----------------------------------------------------------
            override fun visitLocalVariable(node: ULocalVariable) {
                val ktProperty = node.sourcePsi as? KtProperty ?: return
                val method = node.getParentOfType<UMethod>() ?: return
                if (!method.isComposable()) return

                // Track delegated properties: var x by remember { mutableStateOf(...) }
                if (ktProperty.delegate != null && ktProperty.isDelegatedMutableState()) {
                    delegatedStateVars.add(node.name)
                }
            }

            // ----------------------------------------------------------
            // 2) Detect assignments inside composable bodies
            // ----------------------------------------------------------
            override fun visitBinaryExpression(node: UBinaryExpression) {
                if (node.operator != UastBinaryOperator.ASSIGN) return

                val method = node.getParentOfType<UMethod>() ?: return
                if (!method.isComposable()) return

                // 🔴 NEW: skip if assignment is inside ANY lambda (callback/effect)
                if (isInsideNonComposableLambda(node)) return

                // Case A: delegated mutable state var: mt = ""
                val lhs = node.leftOperand as? UReferenceExpression
                if (lhs != null) {
                    val varName = lhs.resolvedName ?: return
                    if (varName in delegatedStateVars) {
                        context.report(
                            MutableStateMutationInComposableIssue.ISSUE,
                            node,
                            context.getLocation(node),
                            "MutableState '$varName' is mutated directly inside a @Composable. " +
                                    "Move this mutation into a ViewModel, LaunchedEffect, or an event callback.",
                        )
                        return
                    }
                    // Fallback: resolve property delegate on the fly
                    val resolved = lhs.resolve() as? PsiVariable
                    val uVar = resolved?.toUElementOfType<UVariable>()
                    val ktProp = uVar?.sourcePsi as? KtProperty
                    if (ktProp?.delegate != null && ktProp.isDelegatedMutableState()) {
                        context.report(
                            MutableStateMutationInComposableIssue.ISSUE,
                            node,
                            context.getLocation(node),
                            "MutableState '$varName' is mutated directly inside a @Composable. " +
                                    "Move this mutation into a ViewModel, LaunchedEffect, or an event callback.",
                        )
                        return
                    }
                }

                // Case B: explicit .value assignment: state.value = 10
                val qualified = node.leftOperand as? UQualifiedReferenceExpression
                if (qualified != null &&
                    qualified.selector is USimpleNameReferenceExpression &&
                    (qualified.selector as USimpleNameReferenceExpression).identifier == "value"
                ) {
                    val receiver = qualified.receiver
                    if (isMutableStateReceiver(receiver)) {
                        context.report(
                            MutableStateMutationInComposableIssue.ISSUE,
                            node,
                            context.getLocation(node),
                            "MutableState '.value' is mutated directly inside a @Composable. " +
                                    "Move this mutation into a ViewModel, LaunchedEffect, or an event callback.",
                        )
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------

    private fun UMethod.isComposable(): Boolean {
        if (annotations.any { it.qualifiedName == "androidx.compose.runtime.Composable" }) return true
        val ktFun = this.sourcePsi as? KtFunction
        if (ktFun != null) {
            if (ktFun.annotationEntries.any { it.shortName?.asString() == "Composable" }) return true
        }
        return false
    }

    /**
     * Returns true if the given receiver expression is a MutableState-like object.
     *
     * Works for:
     *  - Local vals: val s = mutableStateOf(...)
     *  - Parameters: s: MutableState<T>
     *  - Any expression whose type is MutableState<*>, MutableIntState, etc.
     */
    private fun isMutableStateReceiver(receiver: UExpression?): Boolean {
        receiver ?: return false

        val type = receiver.getExpressionType()?.canonicalText
        if (type != null && isMutableStateTypeName(type)) {
            return true
        }

        // Fallback: initializer-based detection for simple name references
        if (receiver is USimpleNameReferenceExpression) {
            val resolved = receiver.resolve() as? PsiVariable ?: return false
            val uVar = resolved.toUElementOfType<UVariable>() ?: return false
            val init = uVar.uastInitializer ?: return false
            return initializerCreatesMutableState(init)
        }

        return false
    }

    private fun isMutableStateTypeName(text: String): Boolean {
        return text.startsWith("androidx.compose.runtime.MutableState") ||
                text.endsWith("MutableIntState") ||
                text.endsWith("MutableFloatState") ||
                text.endsWith("MutableLongState") ||
                text.endsWith("MutableDoubleState")
    }

    private fun initializerCreatesMutableState(expr: UExpression): Boolean {
        val call = expr as? UCallExpression ?: return false
        val name = call.methodName ?: return false
        return name in stateFactoryNames
    }

    private fun isMutableStateFactory(call: KtCallExpression): Boolean {
        val callee = call.calleeExpression?.text ?: return false
        return callee in stateFactoryNames
    }

    private fun callsFunctionReturningMutableState(
        call: KtCallExpression,
        visited: MutableSet<KtNamedFunction> = mutableSetOf()
    ): Boolean {
        val uCall = call.toUElementOfType<UCallExpression>() ?: return false
        val resolved = uCall.resolve() ?: return false
        val ktFun = resolved.navigationElement as? KtNamedFunction ?: return false

        if (!visited.add(ktFun)) return false // avoid recursion loops

        val body: KtExpression = ktFun.bodyExpression ?: return false
        var found = false

        body.accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expr: KtCallExpression) {
                    if (isMutableStateFactory(expr)) {
                        found = true
                        return
                    }
                    if (!found && callsFunctionReturningMutableState(expr, visited)) {
                        found = true
                        return
                    }
                    if (!found) {
                        super.visitCallExpression(expr)
                    }
                }
            },
        )

        return found
    }

    /**
     * Detects `var x by mutableStateOf(...)` or via nested helpers in the delegate.
     */
    private fun KtProperty.isDelegatedMutableState(): Boolean {
        val delegateExpr = this.delegate ?: return false
        var found = false

        delegateExpr.accept(
            object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    if (isMutableStateFactory(expression)) {
                        found = true
                        return
                    }

                    if (!found && callsFunctionReturningMutableState(expression)) {
                        found = true
                        return
                    }

                    if (!found) {
                        super.visitCallExpression(expression)
                    }
                }
            },
        )

        return found
    }

    private val stateFactoryNames = setOf(
        "mutableStateOf",
        "mutableIntStateOf",
        "mutableFloatStateOf",
        "mutableLongStateOf",
        "mutableDoubleStateOf",
        "mutableStateListOf",
        "mutableStateMapOf",
    )

    /**
     * NEW: returns true if the given node is inside ANY lambda body (non-composable).
     *
     * We treat **any** ULambdaExpression as a "callback/effect context" and do NOT
     * flag mutations inside it. Only mutations in the direct composable body
     * (no lambda between the assignment and the UMethod) are flagged.
     */
    fun getParameterName(arg: UElement): String? {
        val call = arg.getUCallExpression() ?: return null

        val index = call.valueArguments.indexOf(arg)
        if (index == -1) return null

        val method = call.resolve() as? PsiMethod ?: return null
        val psiParam = method.parameterList.parameters.getOrNull(index) ?: return null

        return psiParam.name
    }

    private fun isInsideNonComposableLambda(node: UElement): Boolean {
        var current: UElement? = node.uastParent

        while (current != null) {
            if (current is ULambdaExpression) {
                val lambda = current as ULambdaExpression
                val call = lambda.getParentOfType<UCallExpression>(true) ?: return false

                // Find which parameter this lambda corresponds to
                val resolved = call.resolve() ?: return false
                val uMethod = resolved.toUElementOfType<UMethod>() ?: return false

                // Try to match by argument name from the PSI call
                val ktCall = call.sourcePsi as? KtCallExpression
                val valueArgs = ktCall?.valueArguments ?: emptyList()

                var foundParamName: String? = null
                var foundArgIndex = -1

                // Find which named argument contains our lambda by checking PSI
                for ((index, valueArg) in valueArgs.withIndex()) {
                    val argName = valueArg.getArgumentName()?.asName?.identifier
                    if (argName != null) {
                        // This is a named argument - check if its expression matches our lambda
                        val argExpr = valueArg.getArgumentExpression()
                        if (argExpr == lambda.sourcePsi) {
                            foundParamName = argName
                            foundArgIndex = index
                            break
                        }
                    }
                }

                // If no named argument found, try positional
                if (foundParamName == null) {
                    for ((index, arg) in call.valueArguments.withIndex()) {
                        if (arg == lambda || arg.sourcePsi == lambda.sourcePsi) {
                            foundArgIndex = index
                            break
                        }
                    }
                }

                if (foundArgIndex == -1) return false

                // Find the parameter
                var finalParamIndex = foundArgIndex
                if (foundParamName != null) {
                    val namedIdx = uMethod.uastParameters.indexOfFirst { it.name == foundParamName }
                    if (namedIdx >= 0) finalParamIndex = namedIdx
                }


                if (finalParamIndex >= uMethod.uastParameters.size) return false
                val param = uMethod.uastParameters[finalParamIndex]
                // Check if the parameter type has @Composable annotation
                val ktParam = param.sourcePsi as? KtParameter
                val isComposableParam = ktParam?.typeReference?.annotationEntries
                    ?.any { it.shortName?.asString() == "Composable" } == true ||
                    param.annotations.any { it.qualifiedName == "androidx.compose.runtime.Composable" }

                // If parameter is composable -> do NOT skip (we want to flag)
                if (isComposableParam) return false

                // Otherwise → callback / side-effect lambda → skip flagging
                return true
            }

            if (current is UMethod) {
                return false // in direct composable body → FLAG
            }

            current = current.uastParent
        }

        return false
    }}