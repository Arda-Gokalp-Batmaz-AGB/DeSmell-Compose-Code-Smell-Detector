package com.arda.smell_detector.rules

import com.android.tools.lint.detector.api.*
import com.arda.smell_detector.helpers.ComposableFunctionDetector
import com.arda.smell_detector.helpers.IMMUTABLE_FACTORIES
import com.arda.smell_detector.helpers.isLocallyConstantOrImmutable
import com.intellij.psi.PsiType
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.findDescendantOfType
import org.jetbrains.uast.*
import slack.lint.compose.util.findChildrenByClass
import slack.lint.compose.util.isComposable

/**
 * Issue definition for: Non-Savable Type inside rememberSaveable
 */
object NonSavableRememberSaveableIssue {

    private const val ID = "NonSavableTypeInRememberSaveable"
    private const val DESCRIPTION =
        "Using rememberSaveable with a non-savable type."
    private val EXPLANATION = """
        rememberSaveable persists values across configuration changes using Bundle.
        Only primitive types, String, Enum, Parcelable, and Serializable types are
        automatically savable. Storing complex objects, collections, lambdas, or
        custom classes without providing a custom Saver causes the state to reset
        silently after recreation.

        How to fix:
        • Use Parcelable or Serializable
        • OR provide a custom Saver implementation
        • OR store only IDs / primitive fields and restore via ViewModel
    """.trimIndent()

    val ISSUE: Issue =
        Issue.create(
            ID,
            DESCRIPTION,
            EXPLANATION,
            Category.CORRECTNESS,
            6,
            Severity.WARNING,
            Implementation(
                NonSavableRememberSaveableDetector::class.java,
                Scope.JAVA_FILE_SCOPE
            )
        )
}

/**
 * Detector Implementation
 */
class NonSavableRememberSaveableDetector :
    ComposableFunctionDetector(),
    SourceCodeScanner {
    override fun visitComposable(context: JavaContext, method: UMethod, function: KtFunction) {

        function.findChildrenByClass<KtCallExpression>()
            .filter { it.calleeExpression?.text == "rememberSaveable" }
            .forEach { callExpr ->

                // Skip if user provided a custom Saver
                if (callExpr.valueArguments.any { it.getArgumentName()?.asName?.identifier == "stateSaver" }) {
                    return@forEach
                }

                // rememberSaveable { ... }
                val lambda = callExpr.lambdaArguments.firstOrNull()?.getLambdaExpression()
                    ?: return@forEach

                // We only support the simple, very common pattern:
                //   rememberSaveable { mutableStateOf(X) }
                val statement = lambda.bodyExpression?.statements?.singleOrNull()
                    ?: return@forEach

                val mutableStateCall = statement as? KtCallExpression ?: return@forEach
                if (mutableStateCall.calleeExpression?.text != "mutableStateOf") return@forEach

                // Extract X
                val valueExpr: KtExpression =
                    mutableStateCall.valueArguments.firstOrNull()?.getArgumentExpression()
                        ?: return@forEach

                // Check the type of X
                if (valueExpr.isNonSavableValueType(context)) {
                    context.report(
                        NonSavableRememberSaveableIssue.ISSUE,
                        callExpr,
                        context.getLocation(callExpr),
                        "Using rememberSaveable with a non-savable type. Provide a custom Saver or make the type Parcelable/Serializable."
                    )
                }
            }
    }

    /**
     * True if the *type of this expression* is not savable into a Bundle.
     *
     * We don't try to recurse on PSI; we let UAST give us the real type:
     *
     *   - For parameters: we get the parameter's type (Int, List<Int>, User, etc.)
     *   - For locals:     we get the inferred type
     *   - For literals:   we get kotlin.Int, kotlin.String, etc.
     */
    private fun KtExpression.isNonSavableValueType(context: JavaContext): Boolean {
        val uExpr = toUElementOfType<UExpression>() ?: return false // no type info -> don't warn
        val psiType = uExpr.getExpressionType() ?: return false
        return psiType.isNonSavable(context)
    }

    /**
     * Determines if a type cannot be saved into a Bundle by rememberSaveable.
     *
     * Savable:
     *  - primitives (int, long, boolean, etc. and their Kotlin/Java wrappers)
     *  - String (kotlin.String / java.lang.String)
     *  - Enums
     *  - Parcelable
     *  - Serializable
     *
     * Everything else is considered non-savable.
     */
    private fun PsiType.isNonSavable(context: JavaContext): Boolean {
        if (isSavablePrimitiveOrString) return false

        val evaluator = context.evaluator
        val cls = evaluator.getTypeClass(this) ?: return true

        // 1. Enums are always savable
        if (cls.isEnum) return false

        // 2. Detect KotlinX @Serializable annotation
        val hasKotlinxSerializable = cls.annotations.any { ann ->
            val qname = ann.qualifiedName ?: return@any false
            qname == "kotlinx.serialization.Serializable" ||
                    qname.endsWith(".Serializable")
        }
        if (hasKotlinxSerializable) return false

        // 2.5. Detect @Parcelize annotation (makes class Parcelable)
        val hasParcelize = cls.annotations.any { ann ->
            val qname = ann.qualifiedName ?: return@any false
            qname == "kotlinx.parcelize.Parcelize"
        }
        if (hasParcelize) return false

        // 3. Detect Parcelable/Serializable interfaces via Kotlin PSI
        val implementsSavableInterface = cls.interfaces.any { intf ->
            val name = intf.qualifiedName ?: return@any false
            name.contains("Parcelable") || name.contains("Serializable")
        }
        if (implementsSavableInterface) return false

        // 4. Detect Java inheritance
        if (evaluator.inheritsFrom(cls, "android.os.Parcelable", false)) return false
        if (evaluator.inheritsFrom(cls, "java.io.Serializable", false)) return false

        return true
    }    /**
     * Primitive & String detection that works for Kotlin + Java types.
     */
    private val PsiType.isSavablePrimitiveOrString: Boolean
        get() {
            val text = canonicalText

            // Strings
            if (text == "java.lang.String" || text == "kotlin.String") return true

            // Java primitives
            val javaPrimitives = setOf(
                "int", "long", "short", "byte", "boolean", "char", "float", "double"
            )
            if (text in javaPrimitives) return true

            // Kotlin primitive types
            val kotlinPrimitives = setOf(
                "kotlin.Int",
                "kotlin.Long",
                "kotlin.Short",
                "kotlin.Byte",
                "kotlin.Boolean",
                "kotlin.Char",
                "kotlin.Float",
                "kotlin.Double",
            )
            if (text in kotlinPrimitives) return true

            // Boxed Java primitives
            val boxedJava = setOf(
                "java.lang.Integer",
                "java.lang.Long",
                "java.lang.Short",
                "java.lang.Byte",
                "java.lang.Boolean",
                "java.lang.Character",
                "java.lang.Float",
                "java.lang.Double",
            )
            if (text in boxedJava) return true

            return false
        }
}