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
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UExpression
import org.jetbrains.uast.ULambdaExpression
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.UReferenceExpression
import org.jetbrains.uast.getParentOfType
import org.jetbrains.uast.toUElementOfType
import org.jetbrains.uast.visitor.AbstractUastVisitor
import slack.lint.compose.util.isComposableFunction

object ReusedKeyInNestedScopeIssue {

    private const val ID = "ReusedKeyInNestedScope"
    private const val DESCRIPTION = "Same key reused in nested recomposition scopes"
    private val EXPLANATION = """
        The same key is reused in nested key/LaunchedEffect/DisposableEffect scopes.
        When a parent key changes, all children are already invalidated; reusing the same key
        deeper in the hierarchy adds redundant recomposition work and complicates reasoning.
    """.trimIndent()

    private val CATEGORY = Category.PERFORMANCE
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
            ReusedKeyInNestedScopeDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )
    )
}

class ReusedKeyInNestedScopeDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes() = listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) {
                // 1. Check scope
                val containingMethod = node.getParentOfType<UMethod>()
                if (containingMethod == null || !isComposable(containingMethod)) {
                    return
                }

                if (!isKeyOrEffect(node)) return

                val currentKeys = extractKeys(node)
                if (currentKeys.isEmpty()) return

                // Traverse up the tree to find parent scopes
                var parent = node.uastParent
                while (parent != null) {
                    if (parent is UCallExpression && isKeyOrEffect(parent)) {
                        val parentKeys = extractKeys(parent)

                        // Check for intersection
                        val commonKey = findCommonKey(currentKeys, parentKeys)
                        if (commonKey != null) {
                            context.report(
                                ReusedKeyInNestedScopeIssue.ISSUE,
                                node,
                                context.getLocation(node),
                                "Redundant key '$commonKey' reused from parent scope. " +
                                        "Child scopes are already invalidated when parent key changes."
                            )
                            // Break after finding the nearest violation to avoid spamming
                            break
                        }
                    }
                    parent = parent.uastParent
                }


                // 2. Extract keys
                val parentKeyVariables = extractKeyVariableNames(node)
                if (parentKeyVariables.isEmpty()) return

                // 3. Inspect the BODY of this key/effect (the trailing lambda)
                val lambdaArg =
                    node.valueArguments.find { it is ULambdaExpression } as? ULambdaExpression
                val body = lambdaArg?.body ?: return

                // 4. Visit calls inside the Parent's body to find Child Composables
                body.accept(object : AbstractUastVisitor() {
                    override fun visitCallExpression(node: UCallExpression): Boolean {
                        // Check if any argument passed to this child matches a Parent Key
                        for ((index, arg) in node.valueArguments.withIndex()) {
                            val argName = extractSimpleVariableName(arg)

                            if (argName in parentKeyVariables) {
                                // We found a Parent Key variable being passed to a Child.
                                checkChildDefinitionForRedundancy(context, node, index, argName)
                            }
                        }
                        return super.visitCallExpression(node)
                    }
                })
            }
        }
    }

    private fun extractKeys(node: UCallExpression): List<String> {
        val keys = mutableListOf<String>()
        val args = node.valueArguments

        for (arg in args) {
            // We skip the trailing lambda which is usually the content/block
            if (isLambda(arg)) continue

            // Use source PSI text to compare the "variable name" or "expression"
            val text = arg.sourcePsi?.text?.trim()
            if (!text.isNullOrEmpty() && text != "Unit" && text != "true") {
                keys.add(text)
            }
        }
        return keys
    }

    // Simple check if an argument is a lambda expression
    private fun isLambda(expr: UExpression): Boolean {
        return expr.sourcePsi?.text?.trim()?.startsWith("{") == true
    }

    private fun findCommonKey(listA: List<String>, listB: List<String>): String? {
        val setB = listB.toSet()
        return listA.firstOrNull { it in setB }
    }

    // --- START OF CORRECTED INTER-PROCEDURAL LOGIC ---
    private fun checkChildDefinitionForRedundancy(
        context: JavaContext,
        childCall: UCallExpression,
        paramIndex: Int,
        variableName: String
    ) {
        val resolvedMethod = childCall.resolve() ?: return

        // FIX: Use context.uastContext.getDeclaration() to retrieve the UAST element
        // for a resolved method (PsiMethod). This resolves the 'uastContext' issue
        // and correctly retrieves the Child's definition (UMethod).
        val uastMethod = resolvedMethod.toUElementOfType<UMethod>() ?: return

        // Get the name of the parameter in the child definition (e.g., "id" if passed "mey")
        val parameters = uastMethod.uastParameters
        if (paramIndex >= parameters.size) return
        val targetParamName = parameters[paramIndex].name

        // Scan the CHILD body for key(targetParamName)
        if (findKeyReuseInComposableChain(context, targetParamName, resolvedMethod)) {
            reportIssue(context, childCall, variableName)
        }
//        uastMethod.uastBody?.accept(object : AbstractUastVisitor() {
//            override fun visitCallExpression(node: UCallExpression): Boolean {
//                if (isKeyOrEffect(node)) {
//                    val internalKeys = extractKeyVariableNames(node)
//                    if (targetParamName in internalKeys) {
//                        reportIssue(context, childCall, variableName)
//                        return true // Stop scanning this child's body once found
//                    }
//                }
//                return super.visitCallExpression(node)
//            }
//        })
    }

    private fun findKeyReuseInComposableChain(
        context: JavaContext,
        startingParamName: String,
        resolvedMethod: PsiMethod
    ): Boolean {
        val uMethod = resolvedMethod.toUElementOfType<UMethod>() ?: return false

        var found = false

        uMethod.uastBody?.accept(object : AbstractUastVisitor() {

            override fun visitCallExpression(node: UCallExpression): Boolean {

                // 1. Direct local key/LaunchedEffect reuse
                if (isKeyOrEffect(node)) {
                    val internalKeys = extractKeyVariableNames(node)
                    if (startingParamName in internalKeys) {
                        found = true
                        return true
                    }
                }

                // 2. Check deeper calls (child composables)
                val childMethod = node.resolve()
                if (childMethod != null) {

                    // detect param mapping: parentParamName → childParamName
                    val mappedParam = mapParameterName(startingParamName, node, childMethod)

                    if (mappedParam != null) {
                        // continue scanning with *new* param name
                        if (findKeyReuseInComposableChain(context, mappedParam, childMethod)) {
                            found = true
                            return true
                        }
                    }
                }

                return super.visitCallExpression(node)
            }
        })

        return found
    }

    private fun mapParameterName(
        parentName: String,
        call: UCallExpression,
        childMethod: PsiMethod
    ): String? {
        val childU = childMethod.toUElementOfType<UMethod>() ?: return null

        childU.uastParameters.forEachIndexed { index, param ->
            val arg = call.valueArguments.getOrNull(index) ?: return@forEachIndexed

            val argName = extractSimpleVariableName(arg)

            if (argName == parentName) {
                return param.name // mapped param name in the child
            }
        }
        return null
    }


    private fun reportIssue(
        context: JavaContext,
        childCall: UCallExpression,
        varName: String
    ) {
        context.report(
            ReusedKeyInNestedScopeIssue.ISSUE,
            childCall,
            context.getLocation(childCall),
            "Redundant Key Reuse: The variable '$varName' is used as a key in the outer scope, " +
                    "passed to this call, and redundantly reused as a key inside '${childCall.methodName}'. " +
                    "This causes redundant recomposition checks."
        )
    }
    // --- END OF CORRECTED INTER-PROCEDURAL LOGIC ---


    private fun isComposable(node: UMethod): Boolean {
        return node.isComposableFunction()
    }

    private val targetNames = setOf("key", "LaunchedEffect", "DisposableEffect", "produceState")

    private fun isKeyOrEffect(node: UCallExpression): Boolean {
        return node.methodName in targetNames
    }

    private fun extractKeyVariableNames(node: UCallExpression): Set<String> {
        val keys = mutableSetOf<String>()
        for (arg in node.valueArguments) {
            val text = extractSimpleVariableName(arg)
            if (text.isNotEmpty()) {
                keys.add(text)
            }
        }
        return keys
    }

    private fun extractSimpleVariableName(arg: UExpression): String {
        return (arg as? UReferenceExpression)?.resolvedName // Gets resolved name for single variables
            ?: arg.sourcePsi?.text?.trim()
                ?.takeIf { it.matches(Regex("^[a-zA-Z0-9_]+$")) } // Fallback for simple identifiers
            ?: ""
    }
}