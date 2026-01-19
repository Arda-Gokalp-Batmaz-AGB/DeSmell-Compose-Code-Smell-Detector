import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.arda.smell_detector.rules.MutableStateMutationInComposableIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS
import stubs.RememberSaveable_LOCAL_STUBS

class MutableStateMutationInComposableDetectorTest {

    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, RememberSaveable_LOCAL_STUBS, *files)
            .issues(MutableStateMutationInComposableIssue.ISSUE)
            .allowMissingSdk(true)
            .run()
    }
    @Test
    fun `does NOT flag mutation inside non-composable lambda but DOES flag inside composable lambda`() {
        val code =  kotlin(
                    """
                package test
                
                import androidx.compose.runtime.*
                import androidx.compose.material3.*
                
                @Composable
                fun Test() {
                    var mt by remember { mutableStateOf("") }

                    Column {
                        uzass(
                            ttt = { 
                                mt = 3
                            },
                            onItemSelected = { 
                                mt = "6"   // NO FLAG: non-composable lambda
                            },
                            f = ""
                        )
                    }
                }

                @Composable
                fun <T> uzass(
                    f: T,
                    ttt: @Composable () -> Unit,
                    onItemSelected: (T) -> Unit,
                ) {
                    ttt()
                }
                """
                ).indented()
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `delegated mutableIntState inside remember and mutated is reported`() {
        val code = kotlin(
            """
        package test

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember

        @Composable
        fun Screen() {
            var statet by remember { mutableIntStateOf(0) }
            statet = 20   // should be flagged
        }
        """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `delegated mutableState inside remember and mutated is reported`() {
        val code = kotlin(
            """
        package test

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember

        @Composable
        fun Screen() {
            var statet by remember { mutableStateOf(0) }
            statet = 20   // should be flagged
        }
        """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `rememberSaveable mutableState mutation is reported`() {
        val code = kotlin(
            """
        package test

        import androidx.compose.runtime.*
        import androidx.compose.runtime.saveable.rememberSaveable

        @Composable
        fun Screen() {
            val s = rememberSaveable { mutableStateOf(0) }
            s.value = 99   // should be flagged
        }
        """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `mutableState returned from helper function and mutated is reported`() {
        val code = kotlin(
            """
        package test

        import androidx.compose.runtime.*

        fun createState() = mutableStateOf(3)

        @Composable
        fun Screen() {
            val s = createState()
            s.value = 7    // should be flagged
        }
        """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `mutableState inside remember block with transformation is reported`() {
        val code = kotlin(
            """
        package test

        import androidx.compose.runtime.*

        @Composable
        fun Screen() {
            val s = remember { mutableStateOf("hi").apply { } }
            s.value = "bye"   // should be flagged
        }
        """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `mutableState inside remember and mutated is reported`() {
        val code = kotlin(
            """
        package test

        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.mutableStateOf
        import androidx.compose.runtime.remember

        @Composable
        fun Screen() {
            val state = remember { mutableStateOf(0) }
            state.value = 10   // should be flagged
        }
        """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `mutableState value assignment in composable body is reported`() {
        val code = kotlin(
            """
            package test

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.mutableStateOf

            @Composable
            fun MyScreen() {
                val state = mutableStateOf(0)
                state.value = 5   // should be flagged
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `mutableState value assignment inside LaunchedEffect is ignored`() {
        val code = kotlin(
            """
            package test

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.mutableStateOf
            import androidx.compose.runtime.LaunchedEffect

            @Composable
            fun MyScreen() {
                val state = mutableStateOf(0)
                LaunchedEffect(Unit) {
                    state.value = 5   // allowed in side-effect → no warning
                }
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `non MutableState value assignment is not reported`() {
        val code = kotlin(
            """
            package test

            import androidx.compose.runtime.Composable

            class Holder(var value: Int)

            @Composable
            fun MyScreen(holder: Holder) {
                holder.value = 10   // not a MutableState → no warning
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `mutation outside composable is not reported`() {
        val code = kotlin(
            """
            package test

            import androidx.compose.runtime.MutableState

            fun update(state: MutableState<Int>) {
                state.value = 42   // not inside @Composable → no warning
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }
}