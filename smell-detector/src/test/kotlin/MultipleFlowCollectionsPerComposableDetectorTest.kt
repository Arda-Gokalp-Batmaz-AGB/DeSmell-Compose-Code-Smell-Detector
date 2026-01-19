import com.android.tools.lint.checks.infrastructure.LintDetectorTest.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import com.arda.smell_detector.rules.MultipleFlowCollectionsPerComposableIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS
import stubs.FLOW_AND_COLLECT_STUBS
import stubs.COLLECT_AS_STATE_STUBS
import stubs.COLLECT_AS_STATE_WITH_LIFECYCLE_STUBS
import stubs.TEXT_STUBS

class MultipleFlowCollectionsPerComposableDetectorTest {

    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, FLOW_AND_COLLECT_STUBS, COLLECT_AS_STATE_STUBS, COLLECT_AS_STATE_WITH_LIFECYCLE_STUBS, TEXT_STUBS, *files)
            .issues(MultipleFlowCollectionsPerComposableIssue.ISSUE)
            .allowMissingSdk(true)
            .skipTestModes(TestMode.IF_TO_WHEN)
            .run()
    }

    @Test
    fun `does_not_flag_two_collectAsState_calls`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.Flow

            @Composable
            fun TwoCollections(flow1: Flow<Int>, flow2: Flow<String>) {
                val s1 = flow1.collectAsState(initial = 0)
                val s2 = flow2.collectAsState(initial = "")
                Text(text = "${'$'}{s1.value} - ${'$'}{s2.value}")
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `flags_three_collectAsState_calls`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.Flow

            @Composable
            fun ThreeCollections(flow1: Flow<Int>, flow2: Flow<String>, flow3: Flow<Boolean>) {
                val s1 = flow1.collectAsState(initial = 0)
                val s2 = flow2.collectAsState(initial = "")
                val s3 = flow3.collectAsState(initial = false)
                Text(text = "${'$'}{s1.value} - ${'$'}{s2.value} - ${'$'}{s3.value}")
            }
            """
        ).indented()

        // Only one composable, but with 3 flow collections → 1 warning
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `flags_mixed_collectAsState_and_collectAsStateWithLifecycle`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            import androidx.lifecycle.compose.collectAsStateWithLifecycle
            import kotlinx.coroutines.flow.Flow

            @Composable
            fun MixedCollections(
                flow1: Flow<Int>,
                flow2: Flow<String>,
                flow3: Flow<Boolean>
            ) {
                val s1 = flow1.collectAsState(initial = 0)
                val s2 = flow2.collectAsStateWithLifecycle(initialValue = "")
                val s3 = flow3.collectAsStateWithLifecycle(initialValue = false)
                Text(text = "${'$'}{s1.value} - ${'$'}{s2.value} - ${'$'}{s3.value}")
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `separate_composables_are_counted_independently`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            import androidx.lifecycle.compose.collectAsStateWithLifecycle
            import kotlinx.coroutines.flow.Flow

            @Composable
            fun LightComposable(flow: Flow<Int>) {
                val s1 = flow.collectAsState(initial = 0)
                Text(text = "${'$'}{s1.value}")
            }

            @Composable
            fun HeavyComposable(
                flow1: Flow<Int>,
                flow2: Flow<String>,
                flow3: Flow<Boolean>
            ) {
                val s1 = flow1.collectAsState(initial = 0)
                val s2 = flow2.collectAsStateWithLifecycle(initialValue = "")
                val s3 = flow3.collectAsState(initial = false)
                Text(text = "${'$'}{s1.value} - ${'$'}{s2.value} - ${'$'}{s3.value}")
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
            import kotlinx.coroutines.flow.Flow

            fun NotComposable(flow1: Flow<Int>, flow2: Flow<Int>, flow3: Flow<Int>, flow4: Flow<Int>) {
                val s1 = flow1.collectAsState(initial = 0)
                val s2 = flow2.collectAsState(initial = 0)
                val s3 = flow3.collectAsState(initial = 0)
                val s4 = flow4.collectAsState(initial = 0)
                println(s1.value + s2.value + s3.value + s4.value)
            }
            """
        ).indented()

        // No @Composable, so rule should not apply
        lintCheck(code).expectClean()
    }

    @Test
    fun `nested_helper_functions_inside_composable_do_not_break_detection`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.Flow

            @Composable
            fun ParentComposable(flow1: Flow<Int>, flow2: Flow<Int>, flow3: Flow<Int>) {
                val s1 = flow1.collectAsState(initial = 0)
                val s2 = flow2.collectAsState(initial = 0)
                val s3 = flow3.collectAsState(initial = 0)

                fun localHelper(f: Flow<Int>): State<Int> {
                    // This call is nested inside a local function; still part of this composable body.
                    return f.collectAsState(initial = 0)
                }

                val extra = localHelper(flow1)
                Text(text = "${'$'}{s1.value + s2.value + s3.value + extra.value}")
            }
            """
        ).indented()

        // Total 4 collections inside ParentComposable → 1 warning
        lintCheck(code).expectWarningCount(1)
    }
}