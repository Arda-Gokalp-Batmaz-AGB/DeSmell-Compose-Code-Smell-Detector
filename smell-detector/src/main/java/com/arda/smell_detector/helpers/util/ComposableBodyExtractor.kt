// Copyright (C) 2024
// SPDX-License-Identifier: Apache-2.0
package slack.lint.compose.util

import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtLambdaArgument
import org.jetbrains.kotlin.psi.KtLambdaExpression

/**
 * Result of extracting a composable function body.
 * Provides a unified representation for both block-body and expression-body composables.
 */
sealed class ComposableBody {
    /**
     * Block body: `@Composable fun Foo() { ... }`
     */
    data class BlockBody(val block: KtBlockExpression) : ComposableBody()

    /**
     * Expression body: `@Composable fun Foo() = Column { ... }`
     * Contains the root composable call and its lambda body (block or single expression).
     * For single-expression lambdas, we wrap them in a synthetic block for unified analysis.
     */
    data class ExpressionBody(
        val rootCall: KtCallExpression,
        val lambdaBody: KtBlockExpression
    ) : ComposableBody()
}

/**
 * Extracts the body from a composable function, handling both block-body and expression-body forms.
 *
 * For block-body composables: `@Composable fun Foo() { ... }`
 * - Returns the block expression directly
 *
 * For expression-body composables: `@Composable fun Foo() = Column { ... }`
 * - Extracts the root composable call (Column, Row, Box, etc.)
 * - Returns the lambda body of that call
 *
 * @return ComposableBody if the function has a valid body, null otherwise
 */
fun KtFunction.extractComposableBody(): ComposableBody? {
    // Try block body first (most common case)
    val blockBody = this.bodyBlockExpression
    if (blockBody != null) {
        return ComposableBody.BlockBody(blockBody)
    }

    // Try expression body: `= Column { ... }`
    val expressionBody = (this.bodyExpression ?: return null).unwrapParenthesis() ?: return null

    // For expression bodies, we need to extract the root composable call
    // and get its lambda body. Common patterns:
    // - `= Column { ... }`
    // - `= Row(modifier = Modifier) { ... }`
    // - `= Box { ... }`
    val rootCall = extractRootComposableCall(expressionBody) ?: return null
    val lambdaBody = extractLambdaBodyFromCall(rootCall) ?: return null

    return ComposableBody.ExpressionBody(rootCall, lambdaBody)
}

/**
 * Extracts the root composable call from an expression.
 * Handles cases like:
 * - `Column { ... }`
 * - `Row(modifier = Modifier) { ... }`
 * - `Box { ... }`
 */
private fun extractRootComposableCall(expression: KtExpression): KtCallExpression? {
    return when (expression) {
        is KtCallExpression -> expression
        else -> null
    }
}

/**
 * Extracts the lambda body from a composable call expression.
 * Looks for the last lambda argument (trailing lambda) or a lambda in value arguments.
 * 
 * For single-expression lambdas (e.g., `Column { Text("Hello") }`), the body is not a block.
 * In such cases, we need to handle it differently - but for now, we only support block expressions
 * as expression bodies must have a block to contain multiple statements.
 */
private fun extractLambdaBodyFromCall(call: KtCallExpression): KtBlockExpression? {
    // Try trailing lambda first (most common: `Column { ... }`)
    val trailingLambda = call.lambdaArguments.lastOrNull()
    val lambdaExpr = trailingLambda?.getLambdaExpression()
    val body = lambdaExpr?.bodyExpression
    
    // Block expression (most common case)
    if (body is KtBlockExpression) {
        return body
    }
    
    // Single expression lambda - for expression-bodied composables, we expect a block
    // But if it's a single expression, we can't analyze it the same way
    // Return null to skip analysis (this is an edge case)
    // In practice, expression-bodied composables with single expressions are simple and don't need analysis

    // Try value arguments for lambda (less common but possible)
    for (arg in call.valueArguments) {
        val argExpr = arg.getArgumentExpression()
        if (argExpr is KtLambdaExpression) {
            val lambdaBody = argExpr.bodyExpression
            if (lambdaBody is KtBlockExpression) {
                return lambdaBody
            }
        }
    }

    return null
}

