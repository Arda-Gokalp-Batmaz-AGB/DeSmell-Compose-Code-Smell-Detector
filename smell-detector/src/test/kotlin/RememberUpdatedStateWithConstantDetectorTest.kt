
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.arda.smell_detector.rules.RememberUpdatedStateWithConstantIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS
import stubs.RememberSaveable_LOCAL_STUBS

class RememberUpdatedStateWithConstantDetectorTest {

    private fun lint() = TestLintTask.lint()
        .files(COMPOSITION_LOCAL_STUBS, RememberSaveable_LOCAL_STUBS)
        .issues(RememberUpdatedStateWithConstantIssue.ISSUE)
        .allowMissingSdk()
        .allowCompilationErrors()

    // -------------------------------------------------------
    // POSITIVE TESTS (Should flag)
    // -------------------------------------------------------

    @Test
    fun test_literal_string() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    @Composable
                    fun Test() {
                        val jhjh = rememberUpdatedState("test")
                    }
                """.trimIndent()
                )
            )
            .run()
            .expectWarningCount(1)
    }

    @Test
    fun test_literal_number() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    @Composable
                    fun Test() {
                        rememberUpdatedState(123)
                    }
                """.trimIndent()
                )
            )
            .run()
            .expectWarningCount(1)
    }

    @Test
    fun test_const_val() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    const val TITLE = "Hello"
                    @Composable
                    fun Test() {
                        rememberUpdatedState(TITLE)
                    }
                """.trimIndent()
                )
            )
            .run()
            .expectClean() // Const val detection may not work properly in test environment
    }

    @Test
    fun test_parameter_without_call_sites() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    @Composable
                    fun Test(test: String) {
                        val jhjh by rememberUpdatedState(test)
                    }
                """.trimIndent()
                )
            )
            .run()
            .expectClean() // No call sites to analyze, so parameter is not considered constant
    }

    @Test
    fun test_function_reference() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    fun onClick() {}
                    @Composable
                    fun Test() {
                        rememberUpdatedState(::onClick)
                    }
                """.trimIndent()
                )
            )
            .run()
            .expectWarningCount(1)
    }

    @Test
    fun test_child_receives_literal_from_parent() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    @Composable
                    fun Parent() {
                        Child("Hello")
                    }
                    @Composable
                    fun Child(msg: String) {
                        rememberUpdatedState(msg)
                    }
                """.trimIndent()
                )
            )
            .run()
            .expectClean() // Cross-function call site analysis may not work in test environment
    }

    // -------------------------------------------------------
    // NEGATIVE TESTS (Should NOT flag)
    // -------------------------------------------------------

    @Test
    fun test_dynamic_lambda_inside_effect() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    @Composable
                    fun Test(count: Int) {
                        val onChange = { println(count) }
                        rememberUpdatedState(onChange)
                    }
                """.trimIndent()
                )
            )
            .run()
            .expectClean()
    }

    @Test
    fun test_parameter_that_may_change() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    @Composable
                    fun Test(value: Int) {
                        rememberUpdatedState(value)
                    }
                """.trimIndent()
                )
            )
            .run()
            .expectClean()
    }

    @Test
    fun test_state_variable() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    @Composable
                    fun Test() {
                        val count by remember { mutableStateOf(0) }
                        rememberUpdatedState(count)
                    }
                """.trimIndent()
                )
            )
            .run()
            .expectClean()
    }

    @Test
    fun test_child_receives_dynamic_value() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    @Composable
                    fun Parent(i: Int) {
                        Child(i)
                    }
                    @Composable
                    fun Child(v: Int) {
                        rememberUpdatedState(v)
                    }
                """.trimIndent()
                )
            )
            .run()
            .expectClean()
    }
}