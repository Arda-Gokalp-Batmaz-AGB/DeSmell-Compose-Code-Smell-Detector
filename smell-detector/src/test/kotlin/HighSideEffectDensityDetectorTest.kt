import com.android.tools.lint.checks.infrastructure.LintDetectorTest.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import com.arda.smell_detector.rules.HighSideEffectDensityIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS
import stubs.LAYOUT_STUBS
import stubs.TEXT_STUBS

class HighSideEffectDensityDetectorTest {

    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, TEXT_STUBS, LAYOUT_STUBS, *files)
            .issues(HighSideEffectDensityIssue.ISSUE)
            .allowMissingSdk(true)
            .skipTestModes(TestMode.IF_TO_WHEN)
            .run()
    }

    // -------------------------------------------------------
    // POSITIVE CASES – SHOULD WARN (HIGH SED)
    // -------------------------------------------------------

    @Test
    fun `flags_composable_with_many_side_effects_and_few_ui_statements`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun HighDensityEffects(flag: Boolean) {
                LaunchedEffect(flag) {
                    println("effect1")
                }
                DisposableEffect(flag) {
                    println("effect2")
                    onDispose { println("dispose") }
                }
                SideEffect {
                    println("effect3")
                }

                // Only one UI statement vs many effects → high SED
                Text("Hello")
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `flags_composable_where_effects_dominate_statements`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun EffectHeavyComposable(value: Int) {
                LaunchedEffect(value) {
                    println("E1")
                }
                LaunchedEffect(value + 1) {
                    println("E2")
                }
                SideEffect {
                    println("E3")
                }
                DisposableEffect(Unit) {
                    println("E4")
                    onDispose { println("dispose") }
                }

                // Very little structural UI work compared to effect count
                if (value > 0) {
                    Text("Positive")
                }
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `flags_multiple_effects_sharing_mutable_state`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun InteractingEffects() {
                val counter = remember { mutableStateOf(0) }

                LaunchedEffect(Unit) {
                    counter.value++
                }
                LaunchedEffect(counter.value) {
                    println("counter is ${'$'}{counter.value}")
                }
                SideEffect {
                    if (counter.value > 10) {
                        println("High!")
                    }
                }

                Text("Counter: ${'$'}{counter.value}")
            }
            """
        ).indented()

        // Multiple effects coordinating via shared mutable state → should be flagged
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `flags_duplicate_keys_in_multiple_LaunchedEffect_blocks`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun DuplicateKeys(key: Int) {
                LaunchedEffect(key) {
                    println("first")
                }
                LaunchedEffect(key) {
                    println("second")
                }

                Text("Key: ${'$'}key")
            }
            """
        ).indented()

        // Two LaunchedEffect with the same key → high coordination overhead
        lintCheck(code).expectWarningCount(1)
    }

    // -------------------------------------------------------
    // NEGATIVE CASES – SHOULD NOT WARN (LOW SED)
    // -------------------------------------------------------

    @Test
    fun `does_not_flag_composable_with_single_light_side_effect`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun SingleEffectWithRichUI(value: Int) {
                LaunchedEffect(value) {
                    println("log: ${'$'}value")
                }

                // Many UI statements relative to 1 effect → low SED
                Text("Header")
                if (value > 0) {
                    Text("Positive")
                } else {
                    Text("Non-positive")
                }
                Column {
                    Text("Footer 1")
                    Text("Footer 2")
                }
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `does_not_flag_composable_without_side_effects`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun DeclarativeOnly(text: String) {
                Column {
                    Text(text)
                    Text(text.uppercase())
                    Text("length = ${'$'}{text.length}")
                }
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    // -------------------------------------------------------
    // STRUCTURAL BEHAVIOR
    // -------------------------------------------------------

    @Test
    fun `separate_composables_are_evaluated_independently`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun LightComposable(flag: Boolean) {
                // Only one effect and multiple UI statements → low SED
                SideEffect {
                    println("log: ${'$'}flag")
                }
                Text("Flag: ${'$'}flag")
                Text("Static")
            }

            @Composable
            fun HeavyComposable(flag: Boolean) {
                LaunchedEffect(flag) {
                    println("e1")
                }
                LaunchedEffect(Unit) {
                    println("e2")
                }
                DisposableEffect(flag) {
                    println("e3")
                    onDispose {}
                }
                SideEffect {
                    println("e4")
                }

                Text("Heavy")
            }
            """
        ).indented()

        // Only HeavyComposable should be flagged
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `non_composable_function_is_ignored`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            fun NotComposable() {
                LaunchedEffect(Unit) {
                    println("effect outside composable")
                }
                SideEffect {
                    println("also outside composable")
                }
            }
            """
        ).indented()

        // No @Composable → rule should not apply
        lintCheck(code).expectClean()
    }
}
