package com.arda.smell_detector.rules

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.*
import org.jetbrains.uast.*
import slack.lint.compose.util.isComposableFunction

object SlotCountInComposableIssue {

    val ISSUE: Issue = Issue.create(
        id = "SlotCountInComposable",
        briefDescription = "Composable has too many slots",
        explanation = """
            A composable function exposes too many composable lambda parameters (slots). 
            Each slot represents a customizable UI region. Slot counts larger than 2 indicate 
            overly complex APIs, increased cognitive load, and compromised reusability.
        """.trimIndent(),
        category = Category.CORRECTNESS,
        priority = 4,
        androidSpecific = true,
        severity = Severity.WARNING,
        implementation = Implementation(
            SlotCountInComposableDetector::class.java,
            Scope.JAVA_FILE_SCOPE
        )
    )
}

class SlotCountInComposableDetector : Detector(), SourceCodeScanner {

    override fun getApplicableUastTypes() = listOf(UMethod::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler {
        return SlotCountVisitor(context)
    }

    private class SlotCountVisitor(
        private val context: JavaContext
    ) : UElementHandler() {
        val maxSlotCount = 3

        override fun visitMethod(node: UMethod) {
            if (!node.isComposableFunction()) return
//            val logString = node.asRecursiveLogString()

            // Concatenate your prefix:
//            println("deneme: $logString")
            val slotCount = node.uastParameters.count { param ->
                isComposableLambda(param)
            }

            if (slotCount > maxSlotCount) {
                context.report(
                    SlotCountInComposableIssue.ISSUE,
                    node.nameIdentifier ?: node,
                    context.getNameLocation(node),
                    "Composable exposes $slotCount slots (max allowed is 3)"
                )
            }
        }

        /** A parameter is a slot if: type is function type AND annotated with @Composable */
        private fun isComposableLambda(param: UParameter): Boolean {
            val psiText = param.sourcePsi?.text ?: return false
            val type = param.type
//            print("sourcepsi tree:${param.sourcePsi?.asPsiRecursiveLogString()}")
            // 1. Must be a lambda/function type
            val isFunctionType =
                "->" in psiText ||
                        "Function" in type.canonicalText

            if (!isFunctionType) return false

            // 2. Must contain @Composable in the parameter declaration
            val hasComposableAnnotation =
                psiText.contains("@Composable") ||
                        psiText.contains("androidx.compose.runtime.Composable")

            return hasComposableAnnotation
        }
    }
}