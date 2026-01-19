package com.arda.smell_detector.rules

import com.android.tools.lint.detector.api.*
import com.android.tools.lint.client.api.UElementHandler
import org.jetbrains.kotlin.psi.*
import org.jetbrains.uast.*
import org.jetbrains.uast.expressions.UInjectionHost
import org.jetbrains.uast.getContainingUMethod
import org.jetbrains.uast.toUElementOfType
import slack.lint.compose.util.isComposableFunction

object ConstantsInComposableIssue {

    private const val ID = "ConstantsInComposable"
    private const val DESCRIPTION = "Constant declared inside a composable"
    private const val EXPLANATION = """
        Immutable constants declared inside composable functions run on every recomposition.
        Move them outside the composable or wrap them in remember {} if needed.
    """

    val ISSUE: Issue = Issue.create(
        ID,
        DESCRIPTION,
        EXPLANATION.trimIndent(),
        Category.PERFORMANCE,
        6,
        Severity.WARNING,
        Implementation(
            ConstantsInComposableDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )
    )
}

class ConstantsInComposableDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(ULocalVariable::class.java)

    override fun createUastHandler(context: JavaContext) =
        ConstantsInComposableVisitor(context)

}

class ConstantsInComposableVisitor(
    private val context: JavaContext
) : UElementHandler() {

    companion object {
        // Collection factory functions that create constant collections when all arguments are constant
        private val CONSTANT_COLLECTION_FACTORIES = setOf(
            "listOf",
            "emptyList",
            "mutableListOf",
            "setOf",
            "emptySet",
            "mutableSetOf",
            "mapOf",
            "emptyMap",
            "mutableMapOf",
            "arrayOf",
            "arrayOfNulls",
            "intArrayOf",
            "longArrayOf",
            "floatArrayOf",
            "doubleArrayOf",
            "booleanArrayOf",
            "charArrayOf",
            "byteArrayOf",
            "shortArrayOf",
            "pairOf",
            "tripleOf"
        )
    }

    override fun visitLocalVariable(node: ULocalVariable) {

        // Skip vars — only val constants matter
        val psi = node.sourcePsi
        if (psi is KtProperty && psi.isVar) return

        // Must be inside a @Composable (including extension functions)
        val containing = node.getContainingUMethod() ?: return
        if (!containing.isComposableFunction()) return

        val init = node.uastInitializer ?: return

        // Check if constant using UAST
        val isConstantUast = isConstantExpression(init)
        
        // Also check PSI directly as fallback (more reliable for constructor calls)
        // This ensures we catch cases like Home(t = 5) even if UAST doesn't handle it correctly
        val isConstantPsi = if (psi is KtProperty) {
            val initializerPsi = psi.initializer
            if (initializerPsi is KtCallExpression) {
                val calleeName = initializerPsi.calleeExpression?.text
                val isConstructor = calleeName?.firstOrNull()?.isUpperCase() == true
                
                if (isConstructor || calleeName in CONSTANT_COLLECTION_FACTORIES) {
                    val valueArgs = initializerPsi.valueArguments.filter { it !is KtLambdaArgument }
                    if (valueArgs.isEmpty()) {
                        true // Empty constructor/collection
                    } else {
                        // Check if all arguments are constant
                        val allArgsConstant = valueArgs.all { ktArg ->
                            val argExpr = ktArg.getArgumentExpression()
                            argExpr != null && (
                                argExpr is KtConstantExpression ||
                                (argExpr is KtStringTemplateExpression && argExpr.entries.all { 
                                    it is KtLiteralStringTemplateEntry || it is KtEscapeStringTemplateEntry 
                                }) ||
                                isConstantPsiExpression(argExpr)
                            )
                        }
                        
                        if (allArgsConstant) {
                            true
                        } else if (calleeName in CONSTANT_COLLECTION_FACTORIES) {
                            // For collection factories, also flag if all arguments are constructor calls
                            // This catches: listOf(Category(0, ...), Category(1, ...))
                            // where the list structure is constant even if constructor args have function calls
                            val allArgsAreConstructors = valueArgs.all { ktArg ->
                                val argExpr = ktArg.getArgumentExpression()
                                argExpr is KtCallExpression && 
                                argExpr.calleeExpression?.text?.firstOrNull()?.isUpperCase() == true
                            }
                            allArgsAreConstructors
                        } else {
                            false
                        }
                    }
                } else {
                    false
                }
            } else {
                false
            }
        } else {
            false
        }

        // If not constant (neither UAST nor PSI check) → skip
        if (!isConstantUast && !isConstantPsi) return

        // Ignore remember {} or remember(...)
        if (isInsideRemember(node, containing)) return

        // Report
        report(node)
    }

    // ============================================================
    // ============ HELPER: PSI-BASED CONSTANT CHECK ==============
    // ============================================================
    /**
     * Checks if a Kotlin PSI expression is a constant (direct PSI check, no UAST conversion).
     * This is more reliable for simple cases like literals.
     */
    private fun isConstantPsiExpression(psiExpr: KtExpression?): Boolean {
        if (psiExpr == null) return false
        
        return when (psiExpr) {
            // Literal constants (numbers, booleans, etc.)
            is KtConstantExpression -> true
            
            // String templates - check if constant
            is KtStringTemplateExpression -> {
                // Check if string template is constant (no variable interpolation)
                psiExpr.entries.all { entry ->
                    entry is KtLiteralStringTemplateEntry || entry is KtEscapeStringTemplateEntry
                }
            }
            
            // Simple name reference - convert to UAST to check if it's constant
            is KtNameReferenceExpression -> {
                // Convert to UAST first, then use UAST's resolve() method
                val uastExpr = psiExpr.toUElementOfType<UExpression>()
                if (uastExpr is USimpleNameReferenceExpression) {
                    // Use UAST's resolve() method
                    val resolved = uastExpr.resolve()
                    // Parameters and variables are not constants
                    if (resolved != null) {
                        // Check UAST types first, then get PSI
                        val resolvedParameter = resolved.toUElementOfType<UParameter>()
                        val resolvedVariable = resolved.toUElementOfType<ULocalVariable>()
                        
                        if (resolvedParameter != null) {
                            // Resolved to a parameter - not constant
                            false
                        } else if (resolvedVariable != null) {
                            // Resolved to a variable - check if it's var (not constant) or val (might be constant)
                            val ktProp = resolvedVariable.sourcePsi as? KtProperty
                            if (ktProp?.isVar == true) {
                                false // var is not constant
                            } else {
                                // val - check if its initializer is constant
                                isConstantExpression(resolvedVariable.uastInitializer)
                            }
                        } else {
                            // For other references, check if they're constant
                            isConstantExpression(uastExpr)
                        }
                    } else {
                        // Can't resolve - treat as non-constant to be safe
                        false
                    }
                } else {
                    // Fallback: use UAST constant check
                    uastExpr != null && isConstantExpression(uastExpr)
                }
            }
            
            // For other expressions, convert to UAST and check
            else -> {
                val uastExpr = psiExpr.toUElementOfType<UExpression>()
                uastExpr != null && isConstantExpression(uastExpr)
            }
        }
    }

    // ============================================================
    // ============ CONSTANT DETECTION ENTRYPOINT ==================
    // ============================================================
    private fun isConstantExpression(expr: UExpression?): Boolean {
        if (expr == null) return false

        return when (expr) {

            // Literal constants (numbers, booleans, strings without templates)
            is ULiteralExpression -> true

            // String templates
            is UInjectionHost -> {
                val psi = expr.sourcePsi as? KtStringTemplateExpression ?: return false
                return isConstantStringTemplate(expr)
            }

            // Reference to variable or parameter
            is USimpleNameReferenceExpression -> resolveReferenceConstant(expr)

            // Binary arithmetic or string concatenation
            is UBinaryExpression ->
                isConstantExpression(expr.leftOperand) &&
                        isConstantExpression(expr.rightOperand)

            // a + b + c + d
            is UPolyadicExpression -> expr.operands.all { isConstantExpression(it) }

            // Extension properties like Int.dp, Float.dp, etc.
            is UQualifiedReferenceExpression -> {
                val receiver = expr.receiver
                val selector = expr.selector

                // Only constant if receiver is constant
                if (!isConstantExpression(receiver))
                    return false

                // Treat dp/sp/px as constant-like computed values
                val selectorName = selector.sourcePsi?.text
                return selectorName == "dp" || selectorName == "sp" || selectorName == "px"
            }

            // Constructor-like: Color(), Dp(), PaddingValues()
            // Collection factories: listOf(), setOf(), mapOf(), etc.
            // Developer-defined objects: Home(t = 5), MyDataClass(1, "test"), etc.
            is UCallExpression -> {
                val psi = expr.sourcePsi

                // ❌ Never treat calls inside string templates as constant
                if (psi is KtStringTemplateExpression || psi?.parent is KtStringTemplateExpression)
                    return false

                // Get method name - for constructors, this might be the class name
                var actualName = expr.methodName
                
                // For constructors, methodName might be null in UAST, check the PSI structure directly
                val ktCall = psi as? KtCallExpression
                if (actualName == null && ktCall != null) {
                    // For constructor calls, the callee is the class name (e.g., "Home" in Home(t = 5))
                    actualName = ktCall.calleeExpression?.text
                }
                
                // Also try getting from receiver if it's a qualified call
                if (actualName == null) {
                    val receiver = expr.receiver
                    if (receiver is USimpleNameReferenceExpression) {
                        actualName = receiver.identifier
                    }
                }
                
                if (actualName == null) return false

                // Check if it's a collection factory function (listOf, setOf, etc.)
                if (actualName in CONSTANT_COLLECTION_FACTORIES) {
                    // Filter out lambda arguments (they're not constants)
                    val nonLambdaArgs = expr.valueArguments.filter { it !is ULambdaExpression }
                    
                    // Empty collections (like emptyList()) are constants
                    if (nonLambdaArgs.isEmpty()) {
                        return true
                    }
                    
                    // Check if all arguments are constant expressions
                    val allArgsConstant = nonLambdaArgs.all { arg ->
                        isConstantExpression(arg)
                    }
                    
                    if (allArgsConstant) {
                        return true
                    }
                    
                    // Also flag if arguments are constructor calls (even with some non-constant arguments)
                    // This catches cases like: listOf(Category(0, ...), Category(1, ...))
                    // where the list structure is constant but some constructor args are computed
                    // Creating such lists on every recomposition is still a performance issue
                    if (ktCall != null) {
                        val psiValueArgs = ktCall.valueArguments.filter { it !is KtLambdaArgument }
                        val allArgsAreConstructors = psiValueArgs.all { ktArg ->
                            val argExpr = ktArg.getArgumentExpression()
                            argExpr is KtCallExpression && 
                            argExpr.calleeExpression?.text?.firstOrNull()?.isUpperCase() == true
                        }
                        
                        if (allArgsAreConstructors && psiValueArgs.isNotEmpty()) {
                            // All arguments are constructor calls - flag as constant list structure
                            return true
                        }
                    }
                    
                    return false
                }

                // Constructor-like: starts with uppercase
                // This includes:
                // - Built-in types: Color(), Dp(), PaddingValues()
                // - Developer-defined classes: Home(t = 5), MyDataClass(1, "test"), User(name = "John")
                // - Data classes: Person(age = 25, name = "Alice")
                val isConstructorLike = actualName.firstOrNull()?.isUpperCase() == true
                
                // Also check PSI directly - sometimes UAST doesn't provide the name correctly
                // This is CRITICAL for detecting developer-defined objects like Home(t = 5)
                val isConstructorFromPsi = ktCall?.let { call ->
                    val calleeName = call.calleeExpression?.text
                    calleeName?.firstOrNull()?.isUpperCase() == true
                } ?: false
                
                // If we have a PSI call with uppercase callee, ALWAYS treat as constructor
                // This ensures we catch cases like Home(t = 5) even if UAST name extraction fails
                if (isConstructorLike || isConstructorFromPsi || (ktCall != null && ktCall.calleeExpression?.text?.firstOrNull()?.isUpperCase() == true)) {
                    // For constructor calls, ALWAYS use PSI structure directly for reliable argument extraction
                    // UAST might not handle named arguments correctly, so PSI is more reliable
                    if (ktCall != null) {
                        // Get value arguments from PSI (excludes lambda arguments)
                        val psiValueArgs = ktCall.valueArguments.filter { it !is KtLambdaArgument }
                        
                        // Empty constructors create constant objects
                        if (psiValueArgs.isEmpty()) {
                            return true
                        }
                        
                        // Check if all PSI arguments are constant
                        // This properly handles named arguments like t = 5
                        val allPsiArgsConstant = psiValueArgs.all { ktArg ->
                            // For named arguments (t = 5), getArgumentExpression() returns the value (5)
                            // For positional arguments (5), getArgumentExpression() returns the expression (5)
                            val argExpr = ktArg.getArgumentExpression()
                            
                            if (argExpr == null) {
                                // No argument expression - not constant
                                false
                            } else {
                                // Direct check for common constant patterns in PSI
                                val isConstant = when {
                                    // Literal constant (5, "text", true, etc.) - MOST COMMON CASE
                                    // This should catch: Home(t = 5) where 5 is KtConstantExpression
                                    argExpr is KtConstantExpression -> {
                                        true
                                    }
                                    
                                    // Simple string without interpolation
                                    argExpr is KtStringTemplateExpression -> {
                                        argExpr.entries.all { 
                                            it is KtLiteralStringTemplateEntry || it is KtEscapeStringTemplateEntry 
                                        }
                                    }
                                    
                                    // Try PSI-based constant check (handles more complex cases)
                                    else -> {
                                        // First try direct PSI check
                                        val psiCheck = isConstantPsiExpression(argExpr)
                                        if (psiCheck) {
                                            true
                                        } else {
                                            // Fallback: Convert PSI expression to UAST expression for constant checking
                                            val uastExpr = argExpr.toUElementOfType<UExpression>()
                                            uastExpr != null && isConstantExpression(uastExpr)
                                        }
                                    }
                                }
                                
                                isConstant
                            }
                        }
                        
                        // Return the result - if all arguments are constant, the object is constant
                        // This should catch: val home = Home(t = 5) where 5 is constant
                        return allPsiArgsConstant
                        
                        // If PSI check failed, fall through to UAST check below
                    }
                    
                    // Fallback: UAST-based checking (only if PSI check wasn't available or failed)
                    // Filter out lambda arguments (they're not constants)
                    val nonLambdaArgs = expr.valueArguments.filter { it !is ULambdaExpression }
                    
                    // Also check if there are no arguments (empty constructor)
                    if (nonLambdaArgs.isEmpty()) {
                        return true
                    }
                    
                    // Ensure ALL non-lambda arguments are constant expressions
                    // This handles both positional and named arguments:
                    // - Home(5) -> checks if 5 is constant
                    // - Home(t = 5) -> checks if 5 is constant (named argument)
                    // - Home(t = userId) -> returns false (userId is not constant)
                    // - Home(t = 5, callback = { }) -> checks if 5 is constant, ignores lambda
                    return nonLambdaArgs.all { arg ->
                        isConstantExpression(arg)
                    }
                }

                // Not a constant expression
                false
            }

            // Parenthesized
            is UParenthesizedExpression -> isConstantExpression(expr.expression)

            else -> false
        }
    }

    // ============================================================
    // ============ CONSTANT STRING TEMPLATE CHECK =================
    // ============================================================
    private fun isConstantStringTemplate(expr: UInjectionHost): Boolean {
        val psi = expr.sourcePsi as? KtStringTemplateExpression ?: return false

        return psi.entries.all { entry ->
            when (entry) {
                // plain text segments → OK
                is KtLiteralStringTemplateEntry,
                is KtEscapeStringTemplateEntry -> true

                // interpolated ${ ... }
                is KtStringTemplateEntryWithExpression -> {
                    val innerExpr = entry.expression?.toUElementOfType<UExpression>()
                    if (innerExpr is USimpleNameReferenceExpression) {
                        val resolved = innerExpr.resolve()?.toUElementOfType<UParameter>()
                        if (resolved != null) return false
                    }
                    return isConstantExpression(innerExpr)
                }

                else -> false
            }
        }
    }
    // ============================================================
    // ============ VARIABLE / PARAMETER CONSTANT CHECK ============
    // ============================================================
    private fun resolveReferenceConstant(reference: USimpleNameReferenceExpression): Boolean {
        val resolvedPsi = reference.resolve() ?: return false

        val local = resolvedPsi.toUElementOfType<ULocalVariable>()
        if (local != null) {
            // ❗ Check if it's val or var
            val ktProp = local.sourcePsi as? KtProperty
            if (ktProp?.isVar == true) {
                return false   // <-- THIS FIXES YOUR ISSUE
            }
            return isConstantExpression(local.uastInitializer)
        }

        // Parameters are stable
        resolvedPsi.toUElementOfType<UParameter>()?.let {
            return false
        }

        return false
    }
    // ============================================================
    // ============ IGNORE REMEMBER {} =============================
    // ============================================================
    private fun isInsideRemember(node: UElement, stopAt: UElement): Boolean {
        var current = node.uastParent
        while (current != null && current != stopAt) {
            if (current is UCallExpression && current.methodName == "remember") {
                return true
            }
            current = current.uastParent
        }
        return false
    }

    // ============================================================
    // ============ REPORT =========================================
    // ============================================================
    private fun report(node: ULocalVariable) {
        context.report(
            ConstantsInComposableIssue.ISSUE,
            context.getNameLocation(node),
            "Constant declared inside a composable; move it outside or wrap in remember."
        )
    }
}
