
import com.android.tools.lint.checks.infrastructure.LintDetectorTest.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestMode
import com.arda.smell_detector.rules.MutableStateInConditionIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS
import stubs.TEXT_STUBS

class MutableStateInConditionDetectorTest {

    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, TEXT_STUBS, *files)
            .issues(MutableStateInConditionIssue.ISSUE)
            .allowMissingSdk(true)
            .skipTestModes(TestMode.IF_TO_WHEN, TestMode.PARENTHESIZED)
            .run()
    }

    @Test
    fun `flags state_value_used_in_if`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun Test() {
                val state by remember { mutableStateOf(10) }
                if (state > 5) { }
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `flags delegated_state_in_if`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun Test() {
                var statet by remember { mutableStateOf(0) }
                if (statet > 20) { }   // delegated mutable state → should warn
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `flags state_value_in_when`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun Test() {
                val st by remember { mutableStateOf(3) }
                when (st) {
                    1 -> Text("One")
                    3 -> Text("Three")
                }
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `flags delegated_state_with_and_condition`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun Test() {
                var a by remember { mutableStateOf(true) }
                var b by remember { mutableStateOf(false) }
                if (a && b) { } // both delegated → should warn twice
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(2)
    }

    @Test
    fun `does_not_flag_derivedStateOf`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun Test() {
                val x = remember { mutableStateOf(5) }
                val cond = remember { derivedStateOf { x.value > 10 } }
                val shouldShow = cond.value
                if (shouldShow) { }  // OK - using derived state
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }
    @Test
    fun `does_not_flag_derivedStateOf_direct_value_access_shorten`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun Testz() {
                val cond = remember { derivedStateOf { true } }
                if (cond.value) { }  // OK - derivedStateOf result accessed via .value
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `does_not_flag_derivedStateOf_direct_value_access`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun Testz() {
                val x = remember { mutableStateOf(5) }
                val cond = remember { derivedStateOf { x.value > 10 } }
                if (cond.value) { }  // OK - derivedStateOf result accessed via .value
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `does_not_flag_constant_conditions`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*

            @Composable
            fun Test() {
                if (true) { }
                if (5 > 3) { }
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }
}