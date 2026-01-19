package com.arda.smell_detector.rules
import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

// 1. Define the necessary Compose/Kotlin stubs for the test environment
// Since the environment doesn't know about Compose, we mock the necessary annotations/functions.
private val KOTLIN_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.compose.runtime
    annotation class Composable
    fun <T> remember(calculation: @Composable () -> T): T = calculation()
    """
).indented()

private val ANDROIDX_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.compose.ui.unit
    class Dp
    fun Dp(value: Int): Dp = Dp()
    """
).indented()

private val COLOR_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.compose.ui.graphics
    object Color {
        fun Red(): Color = Color()
        class Color
    }
    """
).indented()


class ConstantsInComposableDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = ConstantsInComposableDetector()

    override fun getIssues(): List<Issue> = listOf(ConstantsInComposableIssue.ISSUE)

    /**
     * Helper to run lint tests with the necessary Compose stubs
     */
    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(KOTLIN_STUBS, ANDROIDX_STUBS, COLOR_STUBS, *files)
            .allowMissingSdk(true)
            .run()
    }

    // -----------------------------------------------------
    // 1. Tests for flagging constants
    // -----------------------------------------------------

    fun `test_literal_constant_is_flagged`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.Composable
            @Composable
            fun MyComposable() {
                val myNumber = 42 // FLAG ME
                val myString = "Hello" // FLAG ME
            }
            """
        ).indented()

        lintCheck(code)
            .expect(
                """
                src/MyComposable.kt:4:17: Warning: Constant declared inside a composable; move it outside or wrap in remember. [ConstantsInComposable]
                val myNumber = 42 // FLAG ME
                    ~~~~~~~~
                src/MyComposable.kt:5:17: Warning: Constant declared inside a composable; move it outside or wrap in remember. [ConstantsInComposable]
                val myString = "Hello" // FLAG ME
                    ~~~~~~~~
                2 errors, 0 warnings
                """
            )
    }

    fun `test_constructor_call_constant_is_flagged`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.unit.Dp
            import androidx.compose.ui.graphics.Color
            @Composable
            fun MyComposable() {
                val myDp = Dp(10) // FLAG ME (UCallExpression)
                val myColor = Color.Red() // FLAG ME (UQualifiedReferenceExpression)
            }
            """
        ).indented()

        lintCheck(code)
            .expect(
                """
                src/MyComposable.kt:6:16: Warning: Constant declared inside a composable; move it outside or wrap in remember. [ConstantsInComposable]
                val myDp = Dp(10) // FLAG ME (UCallExpression)
                    ~~~~
                src/MyComposable.kt:7:19: Warning: Constant declared inside a composable; move it outside or wrap in remember. [ConstantsInComposable]
                val myColor = Color.Red() // FLAG ME (UQualifiedReferenceExpression)
                      ~~~~~~~
                2 errors, 0 warnings
                """
            )
    }

    // -----------------------------------------------------
    // 2. Tests for non-flagging (false negatives)
    // -----------------------------------------------------


    fun `test_var_and_non_composable_are_ignored`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.Composable
            
            fun NotComposable() {
                val outsideVal = 10 // IGNORE: Not Composable
            }
            
            @Composable
            fun MyComposable(someParam: Int) {
                var myVar = 10 // IGNORE: Is a 'var' (not final)
                val computedVal = someParam + 5 // IGNORE: Not a literal
                val lazyVal by lazy { 10 } // IGNORE: Not a literal/call
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }


    fun `test_remember_wrapper_is_ignored`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.remember
            @Composable
            fun MyComposable(param: Int) {
                // Should not flag constants inside remember()
                val rememberedVal = remember { 
                    val innerVal = 10 // IGNORE
                    val computed = param * 2 // IGNORE
                    innerVal + computed
                }
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }
}