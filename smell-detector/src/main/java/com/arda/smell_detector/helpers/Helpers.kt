package com.arda.smell_detector.helpers

import com.intellij.psi.PsiElement
import com.intellij.psi.impl.source.tree.LeafPsiElement
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtConstantExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

val IMMUTABLE_FACTORIES =
    setOf(
        "listOf",
        "setOf",
        "mapOf",
        "emptyList",
        "emptySet",
        "emptyMap",
        "Pair",
        "Triple",
    )
fun PsiElement.asPsiRecursiveLogString(
    indentation: String = "",
    builder: StringBuilder = StringBuilder()
): String {
    // 1. Append the current node's information
    builder.append(indentation)
    builder.append(this.javaClass.simpleName)
    if (this is LeafPsiElement) {
        // For leaf nodes (tokens/text), show the text content
        builder.append(" ('${this.text.trim().replace('\n', ' ').take(60)}...')")
    } else {
        // For parent nodes, show a brief description
        builder.append(" [${this.text.trim().replace('\n', ' ').take(60)}...]")
    }
    builder.appendLine()

    // 2. Recursively visit children
    for (child in this.children) {
        child.asPsiRecursiveLogString("$indentation  ", builder)
    }
    return builder.toString()
}

fun KtStringTemplateExpression.isSimpleStringLiteral(): Boolean {
    val singleEntry = entries.singleOrNull() ?: return false
    return singleEntry is KtLiteralStringTemplateEntry
}

 fun KtExpression.isLocallyConstantOrImmutable(): Boolean {
    return when (this) {
        is KtConstantExpression -> true

        is KtStringTemplateExpression -> isSimpleStringLiteral()

        is KtNameReferenceExpression -> {
            val resolved = references.firstOrNull()?.resolve() as? KtProperty ?: return false

            // const val FOO = ...
            if (resolved.hasModifier(KtTokens.CONST_KEYWORD)) return true

            // val FOO = <constant>
            if (!resolved.isVar) {
                val initializer = resolved.initializer
                if (initializer != null && initializer.isLocallyConstantOrImmutable()) return true
            }

            false
        }

        is KtCallExpression -> {
            val name = calleeExpression?.text
            if (name in IMMUTABLE_FACTORIES) {
                valueArguments
                    .mapNotNull { it.getArgumentExpression() }
                    .all { it.isLocallyConstantOrImmutable() }
            } else {
                false
            }
        }

        is KtBinaryExpression -> {
            val op = operationReference.getReferencedName()
            if (op in setOf("+", "-", "*", "/", "%")) {
                val leftConst = left?.isLocallyConstantOrImmutable() ?: false
                val rightConst = right?.isLocallyConstantOrImmutable() ?: false
                leftConst && rightConst
            } else {
                false
            }
        }

        else -> false
    }
}
