import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import com.arda.smell_detector.rules.ConstantsInComposableDetector
import com.arda.smell_detector.rules.ConstantsInComposableIssue
import stubs.ANDROIDX_STUBS
import stubs.COLOR_STUBS
import stubs.COMPOSITION_LOCAL_STUBS
import stubs.ROUNDED_CORNER_SHAPE_STUBS

// 1. Define the necessary Compose/Kotlin stubs for the test environment
// Since the environment doesn't know about Compose, we mock the necessary annotations/functions.
//private val KOTLIN_STUBS = LintDetectorTest.kotlin(
//    """
//    package androidx.compose.runtime
//    annotation class Composable
//    fun <T> remember(calculation: @Composable () -> T): T = calculation()
//    """
//).indented()


class ConstantsInComposableDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = ConstantsInComposableDetector()

    override fun getIssues(): List<Issue> = listOf(ConstantsInComposableIssue.ISSUE)

    /**
     * Helper to run lint tests with the necessary Compose stubs
     */
    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(ANDROIDX_STUBS, COLOR_STUBS, ROUNDED_CORNER_SHAPE_STUBS, COMPOSITION_LOCAL_STUBS, *files)
            .allowMissingSdk(true)
            .run()
    }

    // -----------------------------------------------------
    // 1. Tests for flagging constants
    // -----------------------------------------------------

    //    fun `test_compositionlocal_current_is_ignored`() {
//        val code = kotlin(
//            """
//        import androidx.compose.runtime.Composable
//        import androidx.compose.runtime.LocalContext
//        import androidx.compose.runtime.LocalDensity
//
//        @Composable
//        fun MyComposable() {
//            val td = LocalContext // NOT a constant
//            val ctx = LocalContext.current // NOT a constant
//            val dens = LocalDensity.current // NOT a constant
//        }
//        """
//        ).indented()
//
//        lintCheck(code).expectClean()
//    }
//    fun `test_string_assignment_based_on_constant_val_is_flagged`() {
//        val code = kotlin(
//            """
//        import androidx.compose.runtime.Composable
//
//        @Composable
//        fun MyComposable() {
//            val t = "abc"
//            val s = "value=${'$'}t" // FLAG ME
//        }
//        """
//        ).indented()
//
//        lintCheck(code)
//            .expect(
//                """
//            src/test.kt:7: Warning: Constant declared inside a composable; move it outside or wrap in remember. [ConstantsInComposable]
//                val s = "value=${'$'}t" // FLAG ME
//                    ~
//            0 errors, 1 warnings
//            """
//            )
//    }
    fun `test_expressions_using_var_are_not_flagged`() {
        val code = kotlin(
            """
        import androidx.compose.runtime.Composable

        @Composable
        fun MyComposable() {
            var k = 5 + 5
            val kf = 5 + 5 + k   // should NOT be flagged
            val akf = 5 + k      // should NOT be flagged
            val fg = 5 + k       // should NOT be flagged
        }
        """
        ).indented()

        lintCheck(code)
            .expectClean()
    }

    fun `test_constant_expression_is_flagged`() {
        val code = kotlin(
            """
        import androidx.compose.runtime.Composable

        @Composable
        fun MyComposable() {
            val k = 5 + 5 // FLAG ME
        }
        """
        ).indented()

        lintCheck(code)
            .expect(
                """
            src/test.kt:5: Warning: Constant declared inside a composable; move it outside or wrap in remember. [ConstantsInComposable]
                val k = 5 + 5 // FLAG ME
                    ~
            0 errors, 1 warnings
            """
            )
    }

    //    fun `test_constant_interpolation_vs_mutable_interpolation`() {
//        val code = kotlin(
//            """
//            import androidx.compose.runtime.Composable
//
//            @Composable
//            fun Test(mey: String) {
//                val b1 = "asf${'$'}{f}"
//                val b = "asf${'$'}{mey}" // FLAG ME
//            }
//            """
//        ).indented()
//
//        lintCheck(code)
//            .expect(
//                """
//                src/test.kt:6: Warning: Constant declared inside a composable; move it outside or wrap in remember. [ConstantsInComposable]
//                    val b = "asf${'$'}{mey}" // FLAG ME
//                        ~
//                0 errors, 1 warnings
//                """
//            )
//    }
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
                src/test.kt:4: Warning: Constant declared inside a composable; move it outside or wrap in remember. [ConstantsInComposable]
                    val myNumber = 42 // FLAG ME
                        ~~~~~~~~
                src/test.kt:5: Warning: Constant declared inside a composable; move it outside or wrap in remember. [ConstantsInComposable]
                    val myString = "Hello" // FLAG ME
                        ~~~~~~~~
                0 errors, 2 warnings
                """
            )
    }

    fun `test_constructor_with_parameter_argument_is_not_flagged`() {
        val code = kotlin(
            """
        import androidx.compose.runtime.Composable
        import androidx.compose.foundation.shape.RoundedCornerShape
        import androidx.compose.ui.unit.Dp
        
        @Composable
        fun MyComposable(size: Dp) {
            val shape = RoundedCornerShape(size) // SHOULD NOT be flagged
        }
        """
        ).indented()

        lintCheck(code).expectClean()
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
                val myColor = Color.Red // FLAG ME (UQualifiedReferenceExpression)
            }
            """
        ).indented()

        lintCheck(code)
            .expect(
                """
                src/test.kt:6: Warning: Constant declared inside a composable; move it outside or wrap in remember. [ConstantsInComposable]
                    val myDp = Dp(10) // FLAG ME (UCallExpression)
                        ~~~~
                0 errors, 1 warnings
                """
            )
    }

    // -----------------------------------------------------
    // 2. Tests for non-flagging (false negatives)
    // -----------------------------------------------------
    fun `test_var_and_param_string_are_ignored`() {
        val code = kotlin(
            """
        import androidx.compose.runtime.Composable
        
        fun NotComposable() {
            val outsideVal = 10 // IGNORE: Not Composable
        }
        
        @Composable
        fun MyComposable(someParam: String) {
            var myVar = "mutable" // IGNORE: Is a 'var'
            val computedVal = someParam + " world" // IGNORE: Not a constant
            val greeting = "Hello ${'$'}someParam" // IGNORE: Param in template, not constant
            val lazyVal by lazy { "abc" } // IGNORE: Lazy delegates not constants
        }
        """
        ).indented()

        lintCheck(code).expectClean()
    }

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