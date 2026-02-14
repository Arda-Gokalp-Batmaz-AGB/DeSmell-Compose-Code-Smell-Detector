import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.arda.smell_detector.rules.ReactiveStatePassThroughIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS
import stubs.FLOW_AND_COLLECT_STUBS
import stubs.COLLECT_AS_STATE_STUBS

class ReactiveStatePassThroughDetectorTest {

    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, FLOW_AND_COLLECT_STUBS, COLLECT_AS_STATE_STUBS, *files)
            .issues(ReactiveStatePassThroughIssue.ISSUE)
            .allowMissingSdk(true)
            .run()
    }

    @Test
    fun `single pass-through State parameter is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun Container(state: State<UiState>) {
                Child(state)  // Single pass-through — chain of 1, not flagged
            }

            @Composable
            fun Child(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `single pass-through MutableState parameter is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun Container(state: MutableState<UiState>) {
                Child(state)  // Single pass-through — chain of 1, not flagged
            }

            @Composable
            fun Child(state: MutableState<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `single pass-through StateFlow parameter is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            @Composable
            fun Container(stateFlow: StateFlow<UiState>) {
                Child(stateFlow)  // Single pass-through — chain of 1, not flagged
            }

            @Composable
            fun Child(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `single pass-through Flow parameter is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.Flow

            data class UiState(val title: String)

            @Composable
            fun Container(flow: Flow<UiState>) {
                Child(flow)  // Single pass-through — chain of 1, not flagged
            }

            @Composable
            fun Child(flow: Flow<UiState>) {
                val state = flow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `state consumed locally with value access is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String, val subtitle: String)

            @Composable
            fun Container(state: State<UiState>) {
                val title = state.value.title  // Consumes state locally
                Child(state)
            }

            @Composable
            fun Child(state: State<UiState>) {
                Text(state.value.subtitle)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `state passed to multiple children is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun Container(state: State<UiState>) {
                ChildA(state)  // Shared state is valid
                ChildB(state)
            }

            @Composable
            fun ChildA(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun ChildB(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `state transformed with derivedStateOf is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun Container(state: State<UiState>) {
                val titleState = derivedStateOf { state.value.title }
                Child(titleState)
            }

            @Composable
            fun Child(state: State<String>) {
                Text(state.value)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `immutable value passed to child is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun Container(state: State<UiState>) {
                Child(state.value)  // Passing snapshot, not reactive object
            }

            @Composable
            fun Child(uiState: UiState) {
                Text(uiState.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `state used in delegated property is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun Container(state: State<UiState>) {
                val value by state  // Delegation is consumption
                Child(state)
            }

            @Composable
            fun Child(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `non-reactive parameter is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun Container(uiState: UiState) {
                Child(uiState)  // Not reactive, immutable data
            }

            @Composable
            fun Child(uiState: UiState) {
                Text(uiState.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `non-composable function is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            fun helper(state: State<UiState>): String {
                return state.value.title
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `state not passed to any child is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun Container(state: State<UiState>) {
                // State received but never used (different issue)
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `state collected and passed to child is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            @Composable
            fun Container(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsState(UiState(""))  // Collection is consumption
                Child(state)
            }

            @Composable
            fun Child(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `multiple pass-through layers each flagged independently`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun LayerA(state: State<UiState>) {
                LayerB(state)  // Pass-through
            }

            @Composable
            fun LayerB(state: State<UiState>) {
                LayerC(state)  // Pass-through
            }

            @Composable
            fun LayerC(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // Both LayerA and LayerB are pass-through layers
        lintCheck(code)
            .expectWarningCount(2)
            .expectContains("parameter 'state'")
            .expectContains("in function 'LayerA'")
            .expectContains("in function 'LayerB'")
    }

    @Test
    fun `state value from mutableState passed as Int through layers is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun T1() {
                var den by remember { mutableStateOf(0) }
                T2(den)
            }

            @Composable
            fun T2(test: Int) {
                T3(test)
            }

            @Composable
            fun T3(test: Int) {
                T4(test)
            }

            @Composable
            fun T4(test: Int) {
            }
            """
        ).indented()

        // t2 and t3 are pass-through layers for the state-derived value
        // reports should include the creation-point variable 'den' in function 'T1'
        lintCheck(code)
            .expectWarningCount(2)
            .expectContains("parameter 'test'")
            .expectContains("in function 'T2'")
            .expectContains("in function 'T3'")
            .expectContains("originated from variable 'den' in function 'T1'")
    }

    @Test
    fun `state value from mutableState consumed in middle layer is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            @Composable
            fun t1() {
                var den by remember { mutableStateOf(0) }
                t2(den)
            }

            @Composable
            fun t2(test: Int) {
                val x = test + 1
                t3(x)
            }

            @Composable
            fun t3(test: Int) {
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `single pass-through state parameter not consumed is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun Container(state: State<UiState>) {
                // state is in scope but not actually read — single pass-through, chain of 1
                Child(state)
            }

            @Composable
            fun Child(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }
}
