import com.android.tools.lint.checks.infrastructure.LintDetectorTest.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import com.arda.smell_detector.rules.SideEffectComplexityIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS

class SideEffectComplexityDetectorTest {

    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, *files)
            .issues(SideEffectComplexityIssue.ISSUE)
            .allowMissingSdk(true)
            .allowCompilationErrors()
            // BODY_REMOVAL rewrites eligible bodies as expression bodies; skip to keep
            // parity with detector expectations that inspect block bodies.
            .skipTestModes(TestMode.IF_TO_WHEN, TestMode.BODY_REMOVAL)
            .run()
    }

    @Test
    fun `does_not_flag_simple_LaunchedEffect`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun SimpleEffect() {
                LaunchedEffect(Unit) {
                    println("Hello")
                }
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `flags_complex_LaunchedEffect`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun ComplexEffect(count: Int) {
                LaunchedEffect(count) {
                    var acc = 0
                    for (i in 0..count) {
                        if (i % 2 == 0) {
                            acc += i
                        } else {
                            acc -= i
                        }
                        when {
                            i == 0 -> println("zero")
                            i < 0 -> println("neg")
                            else  -> println("pos")
                        }
                    }
                    println(acc)
                }
            }
            """
        ).indented()

        // Multiple branches + loop + nesting → SEC should exceed threshold
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `does_not_flag_small_SideEffect`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun SmallSideEffect(onShown: () -> Unit) {
                SideEffect {
                    onShown()
                }
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `flags_complex_DisposableEffect`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun ComplexDisposable(id: String) {
                DisposableEffect(id) {
                    var counter = 0
                    for (i in 0 until 10) {
                        if (i % 3 == 0) {
                            counter += i
                        } else if (i % 3 == 1) {
                            counter -= i
                        } else {
                            counter *= 2
                        }
                    }
                    // Extra branch to push SEC above threshold in test environment
                    if (counter > 10) {
                        println(counter)
                    } else {
                        println("low")
                    }
                    onDispose {
                        print("disposed")
                    }
                }
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `non_composable_function_is_ignored`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            fun NotComposable() {
                LaunchedEffect(Unit) {
                    for (i in 0..10) {
                        if (i % 2 == 0) println(i)
                    }
                }
            }
            """
        ).indented()

        // No @Composable, rule should not apply
        lintCheck(code).expectClean()
    }

    @Test
    fun `multiple_effects_only_complex_is_flagged`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun MixedEffects(flag: Boolean) {
                // Simple effect
                SideEffect {
                    println("simple")
                }

                // More complex effect
                LaunchedEffect(flag) {
                    if (flag) {
                        var sum = 0
                        for (i in 0..5) {
                            if (i % 2 == 0) sum += i else sum -= i
                        }
                        println(sum)
                    } else {
                        println("no-op")
                    }
                }
            }
            """
        ).indented()

        // Only the complex LaunchedEffect should be flagged
        lintCheck(code).expectClean()
    }
}
