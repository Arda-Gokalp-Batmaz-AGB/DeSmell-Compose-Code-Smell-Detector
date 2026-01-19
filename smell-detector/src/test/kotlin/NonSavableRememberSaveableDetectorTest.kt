
import com.android.tools.lint.checks.infrastructure.TestFiles
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.arda.smell_detector.rules.NonSavableRememberSaveableIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS
import stubs.RememberSaveable_LOCAL_STUBS
import stubs.PARCELABLE_STUBS
import stubs.PARCELIZE_STUBS

class NonSavableRememberSaveableDetectorTest {

    private fun lint() = TestLintTask.lint()
        .files(COMPOSITION_LOCAL_STUBS, RememberSaveable_LOCAL_STUBS)
        .issues(NonSavableRememberSaveableIssue.ISSUE)
        .allowMissingSdk()
        .allowCompilationErrors()

    // ------------------------------
    // POSITIVE TESTS (should flag)
    // ------------------------------
    @Test
    fun test_custom_class_unsavable() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    data class User(val id: Int)

                    @Composable
                    fun Test() {
                        val numbers = User(1)
                        val state by rememberSaveable { mutableStateOf(numbers) }
                    }
                    """
                )
            )
            .run()
            .expectWarningCount(1)
    }

    @Test
    fun test_lambda_unsavable() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*

                    @Composable
                    fun Test() {
                        val k = { println("Hello") }
                        val state by rememberSaveable { mutableStateOf(k) }
                    }
                    """
                )
            )
            .run()
            .expectWarningCount(1)
    }

    @Test
    fun test_list_unsavable() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*

                    @Composable
                    fun Test() {
                        val numbers = listOf(1, 2, 3)
                        val state by rememberSaveable { mutableStateOf(numbers) }
                    }
                    """
                )
            )
            .run()
            .expectWarningCount(1)
    }

    // -------------------------------------------------------
    // NEGATIVE TESTS — Should NOT WARN
    // -------------------------------------------------------

    @Test
    fun test_int_parameter_savable() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*

                    @Composable
                    fun Test(value: Int) {
                        val state by rememberSaveable { mutableStateOf(value) }
                    }
                    """
                )
            )
            .run()
            .expectClean()
    }

    @Test
    fun test_int_savable() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*

                    @Composable
                    fun Test() {
                        val num = 123
                        val state by rememberSaveable { mutableStateOf(num) }
                    }
                    """
                )
            )
            .run()
            .expectClean()
    }

    @Test
    fun test_string_savable() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*

                    @Composable
                    fun Test() {
                        val txt = "hello"
                        val state by rememberSaveable { mutableStateOf(txt) }
                    }
                    """
                )
            )
            .run()
            .expectClean()
    }

    @Test
    fun test_enum_savable() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    enum class Mode { A, B }

                    @Composable
                    fun Test() {
                        val state by rememberSaveable { mutableStateOf(Mode.A) }
                    }
                    """
                )
            )
            .run()
            .expectClean()
    }

    @Test
    fun test_parcelable_savable() {
        lint()
            .files(
                PARCELABLE_STUBS,
                PARCELIZE_STUBS,
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*
                    import android.os.Parcelable
                    import kotlinx.parcelize.Parcelize

                    @Parcelize
                    data class User(val id: Int) : Parcelable

                    @Composable
                    fun Test() {
                        val state by rememberSaveable { mutableStateOf(User(1)) }
                    }
                    """
                )
            )
            .run()
            .expectClean()
    }

    @Test
    fun test_custom_saver_skips_warning() {
        lint()
            .files(
                TestFiles.kotlin(
                    """
                    import androidx.compose.runtime.*

                    @Composable
                    fun Test() {
                        rememberSaveable(
                            stateSaver = Saver(
                                save = { "" },
                                restore = { Any() }
                            )
                        ) {
                            mutableStateOf(Any())
                        }
                    }
                    """
                )
            )
            .run()
            .expectClean()
    }
}