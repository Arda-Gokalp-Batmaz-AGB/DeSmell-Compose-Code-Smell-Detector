package com.arda.smell_detector.helpers


import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.SourceCodeScanner
import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.kotlin.isKotlin
import slack.lint.compose.util.LintOption
import slack.lint.compose.util.OptionLoadingDetector
import slack.lint.compose.util.isComposableFunction

abstract class ComposableFunctionDetector(options: List<Pair<LintOption, Issue>>) :
    OptionLoadingDetector(options), SourceCodeScanner {

    constructor(vararg options: Pair<LintOption, Issue>) : this(options.toList())

    final override fun getApplicableUastTypes() = listOf(UMethod::class.java)

    final override fun createUastHandler(context: JavaContext): UElementHandler? {
        if (!isKotlin(context.uastFile?.lang)) return null
        return object : UElementHandler() {
            override fun visitMethod(node: UMethod) {
                if (node.isComposableFunction()) {
                    visitComposable(context, node)
                    when (val sourcePsi = node.sourcePsi ?: return) {
                        is KtPropertyAccessor -> {
                            visitComposable(context, node, sourcePsi)
                        }
                        is KtFunction -> {
                            visitComposable(context, node, sourcePsi)
                        }
                    }
                }
            }
        }
    }


    open fun visitComposable(context: JavaContext, method: UMethod) {}

    open fun visitComposable(context: JavaContext, method: UMethod, property: KtPropertyAccessor) {}

    open fun visitComposable(context: JavaContext, method: UMethod, function: KtFunction) {}
}