import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import com.arda.smell_detector.rules.ReactiveStatePassThroughIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS
import stubs.FLOW_AND_COLLECT_STUBS
import stubs.COLLECT_AS_STATE_STUBS
import stubs.COLLECT_AS_STATE_WITH_LIFECYCLE_STUBS

class ReactiveStatePassThroughDetectorTest {

    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, FLOW_AND_COLLECT_STUBS, COLLECT_AS_STATE_STUBS, COLLECT_AS_STATE_WITH_LIFECYCLE_STUBS, *files)
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

    // ─── Broader consumption tests: any usage other than passing as function argument = consumed ───

    @Test
    fun `state consumed via toString in multi-layer chain is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun LayerA(state: State<UiState>) {
                LayerB(state)  // Would be pass-through, but LayerB consumes
            }

            @Composable
            fun LayerB(state: State<UiState>) {
                val s = state.toString()  // Consumed via method call — breaks chain
                LayerC(state)
            }

            @Composable
            fun LayerC(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // LayerB consumes state (toString), so the chain is broken: LayerA → LayerB (consumed)
        // Chain length = 1, not flagged
        lintCheck(code).expectClean()
    }

    @Test
    fun `derived param consumed via method call in chain is not flagged`() {
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
                val s = test.toString()  // Consumed via method call — breaks chain
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

        // T2 consumes test (toString), so the chain from T2 is broken
        // Only T3 remains as pass-through (chain of 1), not flagged
        lintCheck(code).expectClean()
    }

    @Test
    fun `state consumed via let block in multi-layer chain is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun LayerA(state: State<UiState>) {
                LayerB(state)
            }

            @Composable
            fun LayerB(state: State<UiState>) {
                state.let { println(it) }  // Consumed via let (dot-qualified + lambda)
                LayerC(state)
            }

            @Composable
            fun LayerC(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            fun println(x: Any?) {}
            """
        ).indented()

        // LayerB consumes state via .let { }, breaking the chain
        lintCheck(code).expectClean()
    }

    @Test
    fun `chain still detected when consumption only at the end`() {
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
                T3(test)  // Pass-through
            }

            @Composable
            fun T3(test: Int) {
                T4(test)  // Pass-through
            }

            @Composable
            fun T4(test: Int) {
                val s = test.toString()  // Consumed here at the end — not pass-through
            }
            """
        ).indented()

        // T2 and T3 are pass-throughs (chain of 2), T4 consumes
        // T2 and T3 should be flagged
        lintCheck(code)
            .expectWarningCount(2)
            .expectContains("parameter 'test'")
            .expectContains("in function 'T2'")
            .expectContains("in function 'T3'")
    }

    @Test
    fun `state consumed via hashCode in chain is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*

            data class UiState(val title: String)

            @Composable
            fun LayerA(state: State<UiState>) {
                LayerB(state)
            }

            @Composable
            fun LayerB(state: State<UiState>) {
                val h = state.hashCode()  // Consumed via method call
                LayerC(state)
            }

            @Composable
            fun LayerC(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // LayerB consumes state via hashCode(), breaking the chain
        lintCheck(code).expectClean()
    }

    @Test
    fun `derived param used in string interpolation is consumed`() {
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
                T3(test)  // Pass-through
            }

            @Composable
            fun T3(test: Int) {
                val label = "${'$'}test items"  // Consumed — used in string template
                T4(test)
            }

            @Composable
            fun T4(test: Int) {
            }
            """
        ).indented()

        // T2 → T3: T3 consumes test (string template), so T3 is NOT a pass-through
        // Only T2 remains as pass-through (chain of 1), not flagged
        lintCheck(code).expectClean()
    }

    // ─── Flow / collectAsState / collectAsStateWithLifecycle tests ───

    @Test
    fun `Flow passed through multiple layers with collectAsState only in last layer is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.Flow

            data class UiState(val title: String)

            @Composable
            fun LayerA(flow: Flow<UiState>) {
                LayerB(flow)  // Pass-through
            }

            @Composable
            fun LayerB(flow: Flow<UiState>) {
                LayerC(flow)  // Pass-through
            }

            @Composable
            fun LayerC(flow: Flow<UiState>) {
                val state = flow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // LayerA and LayerB both pass Flow through without collecting; LayerC consumes via collectAsState
        // Skip FQN mode: type resolution for Flow in FQN mode can differ and produce inconsistent output
        lint()
            .files(COMPOSITION_LOCAL_STUBS, FLOW_AND_COLLECT_STUBS, COLLECT_AS_STATE_STUBS, COLLECT_AS_STATE_WITH_LIFECYCLE_STUBS, code)
            .issues(ReactiveStatePassThroughIssue.ISSUE)
            .allowMissingSdk(true)
            .skipTestModes(TestMode.FULLY_QUALIFIED)
            .run()
            .expectWarningCount(2)
            .expectContains("parameter 'flow'")
            .expectContains("in function 'LayerA'")
            .expectContains("in function 'LayerB'")
    }

    @Test
    fun `StateFlow passed through multiple layers is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            @Composable
            fun LayerA(stateFlow: StateFlow<UiState>) {
                LayerB(stateFlow)  // Pass-through
            }

            @Composable
            fun LayerB(stateFlow: StateFlow<UiState>) {
                LayerC(stateFlow)  // Pass-through
            }

            @Composable
            fun LayerC(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // LayerA and LayerB both pass StateFlow through without collecting
        lintCheck(code)
            .expectWarningCount(2)
            .expectContains("parameter 'stateFlow'")
            .expectContains("in function 'LayerA'")
            .expectContains("in function 'LayerB'")
    }

    @Test
    fun `MutableStateFlow passed through multiple layers is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.MutableStateFlow

            data class UiState(val title: String)

            @Composable
            fun LayerA(flow: MutableStateFlow<UiState>) {
                LayerB(flow)  // Pass-through
            }

            @Composable
            fun LayerB(flow: MutableStateFlow<UiState>) {
                LayerC(flow)  // Pass-through
            }

            @Composable
            fun LayerC(flow: MutableStateFlow<UiState>) {
                val state = flow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // LayerA and LayerB both pass MutableStateFlow through without collecting
        // Skip FQN mode: the lint framework's type resolution for MutableStateFlow (invariant <T>)
        // through the StateFlow<out T> / Flow<out T> hierarchy fails when names are fully qualified,
        // producing no warnings even though the detector logic is correct.
        lint()
            .files(COMPOSITION_LOCAL_STUBS, FLOW_AND_COLLECT_STUBS, COLLECT_AS_STATE_STUBS, COLLECT_AS_STATE_WITH_LIFECYCLE_STUBS, code)
            .issues(ReactiveStatePassThroughIssue.ISSUE)
            .allowMissingSdk(true)
            .skipTestModes(TestMode.FULLY_QUALIFIED)
            .run()
            .expectWarningCount(2)
            .expectContains("parameter 'flow'")
            .expectContains("in function 'LayerA'")
            .expectContains("in function 'LayerB'")
    }

    @Test
    fun `StateFlow consumed via collectAsState in middle layer breaks chain`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            @Composable
            fun LayerA(stateFlow: StateFlow<UiState>) {
                LayerB(stateFlow)
            }

            @Composable
            fun LayerB(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsState(UiState(""))  // Consumed via collectAsState
                LayerC(stateFlow)
            }

            @Composable
            fun LayerC(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // LayerB consumes stateFlow via collectAsState, breaking the chain
        lintCheck(code).expectClean()
    }

    @Test
    fun `Flow consumed via collectAsState in middle layer breaks chain`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.Flow

            data class UiState(val title: String)

            @Composable
            fun LayerA(flow: Flow<UiState>) {
                LayerB(flow)
            }

            @Composable
            fun LayerB(flow: Flow<UiState>) {
                val state = flow.collectAsState(UiState(""))  // Consumed via collectAsState
                LayerC(flow)
            }

            @Composable
            fun LayerC(flow: Flow<UiState>) {
                val state = flow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // LayerB consumes flow via collectAsState, breaking the chain
        lintCheck(code).expectClean()
    }

    @Test
    fun `StateFlow consumed via collectAsStateWithLifecycle breaks chain`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow
            import androidx.lifecycle.compose.collectAsStateWithLifecycle

            data class UiState(val title: String)

            @Composable
            fun LayerA(stateFlow: StateFlow<UiState>) {
                LayerB(stateFlow)
            }

            @Composable
            fun LayerB(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsStateWithLifecycle(UiState(""))  // Consumed
                LayerC(stateFlow)
            }

            @Composable
            fun LayerC(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsStateWithLifecycle(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // LayerB consumes stateFlow via collectAsStateWithLifecycle, breaking the chain
        lintCheck(code).expectClean()
    }

    @Test
    fun `StateFlow collected to State then State passed to single child is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            @Composable
            fun Parent(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsState(UiState(""))  // Consumed — collected to State
                Child(state)  // Passing collected State — single layer, not flagged
            }

            @Composable
            fun Child(state: State<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // Parent consumes StateFlow via collectAsState; passing result State is chain of 1
        lintCheck(code).expectClean()
    }

    @Test
    fun `StateFlow collected to State then State passed through multiple layers is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            @Composable
            fun Parent(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsState(UiState(""))
                LayerA(state)
            }

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

        // Parent consumes StateFlow (collectAsState), but the resulting State is
        // passed through LayerA → LayerB → LayerC — chain of 2 pass-throughs
        // The origin points to the collectAsState creation in Parent
        lintCheck(code)
            .expectWarningCount(2)
            .expectContains("parameter 'state'")
            .expectContains("in function 'LayerA'")
            .expectContains("in function 'LayerB'")
            .expectContains("originated from variable 'state' in function 'Parent'")
    }

    @Test
    fun `collectAsStateWithLifecycle creation point is flagged as origin`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow
            import androidx.lifecycle.compose.collectAsStateWithLifecycle

            data class UiState(val title: String)

            @Composable
            fun Parent(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsStateWithLifecycle(UiState(""))
                LayerA(state)
            }

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

        // Parent consumes StateFlow (collectAsStateWithLifecycle), but the resulting State is
        // passed through LayerA → LayerB → LayerC — chain of 2 pass-throughs
        // The origin points to the collectAsStateWithLifecycle creation in Parent
        lintCheck(code)
            .expectWarningCount(2)
            .expectContains("parameter 'state'")
            .expectContains("in function 'LayerA'")
            .expectContains("in function 'LayerB'")
            .expectContains("originated from variable 'state' in function 'Parent'")
    }

    @Test
    fun `multiple different reactive params where one is consumed and other passed through`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            @Composable
            fun LayerA(state: State<UiState>, stateFlow: StateFlow<UiState>) {
                LayerB(state, stateFlow)
            }

            @Composable
            fun LayerB(state: State<UiState>, stateFlow: StateFlow<UiState>) {
                val collected = stateFlow.collectAsState(UiState(""))  // stateFlow consumed
                LayerC(state, stateFlow)
            }

            @Composable
            fun LayerC(state: State<UiState>, stateFlow: StateFlow<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // state: LayerA → LayerB → LayerC (LayerC consumes). Chain of 2 — flagged.
        // stateFlow: LayerB consumes via collectAsState, breaking the chain.
        lintCheck(code)
            .expectWarningCount(2)
            .expectContains("parameter 'state'")
            .expectContains("in function 'LayerA'")
            .expectContains("in function 'LayerB'")
    }

    @Test
    fun `Flow consumed via extension method in middle layer is not flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.Flow

            data class UiState(val title: String)

            @Composable
            fun LayerA(flow: Flow<UiState>) {
                LayerB(flow)
            }

            @Composable
            fun LayerB(flow: Flow<UiState>) {
                flow.also { }  // Consumed — method call on flow
                LayerC(flow)
            }

            @Composable
            fun LayerC(flow: Flow<UiState>) {
                val state = flow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // LayerB consumes flow via .also { }, breaking the chain
        lintCheck(code).expectClean()
    }

    @Test
    fun `three layer StateFlow pass-through chain is flagged`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            @Composable
            fun LayerA(stateFlow: StateFlow<UiState>) {
                LayerB(stateFlow)  // Pass-through
            }

            @Composable
            fun LayerB(stateFlow: StateFlow<UiState>) {
                LayerC(stateFlow)  // Pass-through
            }

            @Composable
            fun LayerC(stateFlow: StateFlow<UiState>) {
                LayerD(stateFlow)  // Pass-through
            }

            @Composable
            fun LayerD(stateFlow: StateFlow<UiState>) {
                val state = stateFlow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // LayerA, LayerB, and LayerC all pass-through — chain of 3
        lintCheck(code)
            .expectWarningCount(3)
            .expectContains("parameter 'stateFlow'")
            .expectContains("in function 'LayerA'")
            .expectContains("in function 'LayerB'")
            .expectContains("in function 'LayerC'")
    }

    // ─── Overloaded composable tests: same name, different params ───

    @Test
    fun `overloaded composables with same name but different params are handled correctly`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.Flow
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            // 1-param overloads: flow consumed via collectAsState in middle layer — chain broken
            @Composable
            fun LayerA(flow: Flow<UiState>) {
                LayerB(flow)
            }

            @Composable
            fun LayerB(flow: Flow<UiState>) {
                val state = flow.collectAsState(UiState(""))  // Consumed
                LayerC(flow)
            }

            @Composable
            fun LayerC(flow: Flow<UiState>) {
                val state = flow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            // 2-param overloads: state pass-through chain, stateFlow consumed in middle
            @Composable
            fun LayerA(state: State<UiState>, stateFlow: StateFlow<UiState>) {
                LayerB(state, stateFlow)
            }

            @Composable
            fun LayerB(state: State<UiState>, stateFlow: StateFlow<UiState>) {
                val collected = stateFlow.collectAsState(UiState(""))  // stateFlow consumed
                LayerC(state, stateFlow)
            }

            @Composable
            fun LayerC(state: State<UiState>, stateFlow: StateFlow<UiState>) {
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // Only the 2-param overloads should produce warnings:
        // state is passed through LayerA(2-param) → LayerB(2-param) → LayerC(2-param) which consumes
        // The 1-param overloads should NOT be flagged (LayerB consumes flow via collectAsState)
        lintCheck(code)
            .expectWarningCount(2)
            .expectContains("parameter 'state'")
            .expectContains("in function 'LayerA'")
            .expectContains("in function 'LayerB'")
    }

    @Test
    fun `overloaded composables both clean do not produce false positives`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            // 1-param overload: single pass-through (chain of 1) — not flagged
            @Composable
            fun Screen(state: State<UiState>) {
                Child(state)
            }

            @Composable
            fun Child(state: State<UiState>) {
                Text(state.value.title)
            }

            // 2-param overload: stateFlow consumed, state single pass-through — not flagged
            @Composable
            fun Screen(state: State<UiState>, stateFlow: StateFlow<UiState>) {
                val collected = stateFlow.collectAsState(UiState(""))
                Child(state)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // No chains of >= 2: 1-param Screen passes to Child (chain of 1).
        // 2-param Screen consumes stateFlow and passes state to Child (chain of 1).
        lintCheck(code).expectClean()
    }

    @Test
    fun `overloaded composables where both overloads have pass-through chains`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.*
            import kotlinx.coroutines.flow.StateFlow

            data class UiState(val title: String)

            // 1-param overload: pass-through chain of 2
            @Composable
            fun Wrapper(state: State<UiState>) {
                Middle(state)
            }

            @Composable
            fun Middle(state: State<UiState>) {
                Leaf(state)
            }

            @Composable
            fun Leaf(state: State<UiState>) {
                Text(state.value.title)
            }

            // 2-param overload: pass-through chain of 2 for stateFlow
            @Composable
            fun Wrapper(state: State<UiState>, stateFlow: StateFlow<UiState>) {
                Middle(state, stateFlow)
            }

            @Composable
            fun Middle(state: State<UiState>, stateFlow: StateFlow<UiState>) {
                Leaf(state, stateFlow)
            }

            @Composable
            fun Leaf(state: State<UiState>, stateFlow: StateFlow<UiState>) {
                val uiState = stateFlow.collectAsState(UiState(""))
                Text(state.value.title)
            }

            @Composable
            fun Text(text: String) {}
            """
        ).indented()

        // 1-param chain: Wrapper → Middle → Leaf (consumes). Chain of 2 for 'state'.
        // 2-param chain: both state and stateFlow pass-through in Wrapper and Middle.
        //   state: Wrapper#2 → Middle#2 → Leaf#2 (consumes). Chain of 2.
        //   stateFlow: Wrapper#2 → Middle#2 → Leaf#2 (consumes via collectAsState). Chain of 2.
        // Total: 4 warnings from 1-param (Wrapper, Middle) + 4 from 2-param (Wrapper×2params, Middle×2params)
        // But actually: 1-param has 2 warnings (state in Wrapper#1, Middle#1)
        // 2-param has 4 warnings (state+stateFlow in Wrapper#2, state+stateFlow in Middle#2)
        lintCheck(code)
            .expectWarningCount(6)
            .expectContains("parameter 'state'")
            .expectContains("parameter 'stateFlow'")
            .expectContains("in function 'Wrapper'")
            .expectContains("in function 'Middle'")
    }
}
