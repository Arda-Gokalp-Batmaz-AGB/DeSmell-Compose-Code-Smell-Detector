import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.arda.smell_detector.rules.NonSnapshotAwareCollectionInStateIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS

class NonSnapshotAwareCollectionInStateDetectorTest {

    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, *files)
            .issues(NonSnapshotAwareCollectionInStateIssue.ISSUE)
            .allowMissingSdk(true)
            .run()
    }

    @Test
    fun `state holder with mutableListOf and in-place add is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                val items = remember { mutableStateOf(mutableListOf<Int>()) }
                items.value.add(1)
            }
            """
        ).indented()

        lintCheck(code)
            .expectWarningCount(1)
            .expectContains("In-place mutation of non-snapshot-aware collection")
            .expectContains("'items'")
            .expectContains("mutableStateListOf()")
    }

    @Test
    fun `delegated state with mutableListOf and in-place add is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                var items by remember { mutableStateOf(mutableListOf(1, 2)) }
                items.add(3)
            }
            """
        ).indented()

        lintCheck(code)
            .expectWarningCount(1)
            .expectContains("In-place mutation of non-snapshot-aware collection")
            .expectContains("'items'")
    }

    @Test
    fun `mutableStateListOf used with add is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                val items = remember { mutableStateListOf(1, 2) }
                items.add(3)
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `reassignment instead of in-place mutation is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                val items = remember { mutableStateOf(listOf(1, 2)) }
                items.value = items.value + 3
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `mutableMapOf in state with put is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                val mapState = remember { mutableStateOf(mutableMapOf<String, Int>()) }
                mapState.value.put("a", 1)
            }
            """
        ).indented()

        lintCheck(code)
            .expectWarningCount(1)
            .expectContains("non-snapshot-aware collection")
            .expectContains("'mapState'")
    }

    @Test
    fun `mutableSetOf in state with add is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                val setState = remember { mutableStateOf(mutableSetOf<Int>()) }
                setState.value.add(1)
            }
            """
        ).indented()

        lintCheck(code)
            .expectWarningCount(1)
            .expectContains("'setState'")
    }

    @Test
    fun `arrayListOf in state with removeAt is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                val list = remember { mutableStateOf(arrayListOf(1, 2, 3)) }
                list.value.removeAt(0)
            }
            """
        ).indented()

        lintCheck(code)
            .expectWarningCount(1)
            .expectContains("'list'")
    }

    @Test
    fun `clear on non-snapshot list in state is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                val items = remember { mutableStateOf(mutableListOf<String>()) }
                items.value.clear()
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `delegated state remove is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                var items by remember { mutableStateOf(mutableListOf("a", "b")) }
                items.remove("a")
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `non-composable with mutableStateOf and add is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            fun notComposable() {
                val items = mutableStateOf(mutableListOf<Int>())
                items.value.add(1)
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `multiple mutations on same candidate report each call site`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                val items = remember { mutableStateOf(mutableListOf<Int>()) }
                items.value.add(1)
                items.value.add(2)
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(2)
    }

    @Test
    fun `mutableStateMapOf with put is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun Screen() {
                val mapState = remember { mutableStateMapOf<String, Int>() }
                mapState["a"] = 1
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }
}
