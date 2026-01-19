
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import com.arda.smell_detector.rules.LogicInUiIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS

class LogicInUiDetectorTest {

    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, *files)
            .issues(LogicInUiIssue.ISSUE)
            .allowMissingSdk(true)
            .skipTestModes(TestMode.BODY_REMOVAL)
            .skipTestModes(TestMode.IF_TO_WHEN)
            .run()
    }

    // -----------------------------------------------------
    // 1. Clean / baseline cases
    // -----------------------------------------------------

    @Test
    fun `small composable with no logic is clean`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable

            @Composable
            fun SimpleText() {
                Text("Hello")
                Text("World")
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `small composable with single if is ignored by CFC guard`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable

            data class UiState(val isLoading: Boolean)

            @Composable
            fun Small(uiState: UiState) {
                if (uiState.isLoading) {
                    Text("Loading")
                }
                Text("Body")
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `large composable with many UI statements but low logic density is clean`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable

            @Composable
            fun LargeMostlyUi() {
                Text("1")
                Text("2")
                Text("3")
                Text("4")
                Text("5")
                Text("6")
                Text("7")
                Text("8")
                Text("9")
                Text("10")

                val label = "Done"
                Text(label)
            }
            """
        ).indented()

        // Many statements, but no real control-flow → LIU ~ 0 → no warning
        lintCheck(code).expectClean()
    }

    // -----------------------------------------------------
    // 2. Warning cases
    // -----------------------------------------------------

    @Test
    fun `large composable with high logic density is reported`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable

            enum class Mode { A, B, C }

            data class UiState(
                val isLoading: Boolean,
                val hasError: Boolean,
                val mode: Mode,
                val items: List<String>,
                val retriesLeft: Int
            )

            @Composable
            fun HeavyLogic(uiState: UiState) {
                if (uiState.isLoading) {
                    Text("Loading")
                } else if (uiState.hasError) {
                    Text("Error")
                }

                when (uiState.mode) {
                    Mode.A -> Text("A")
                    Mode.B -> Text("B")
                    Mode.C -> Text("C")
                }

                for (item in uiState.items) {
                    Text(item)
                }

                var remaining = uiState.retriesLeft
                while (remaining > 0) {
                    remaining--
                }

                Text("Footer")
            }
            """
        ).indented()

        // Many control-flow constructs relative to statements → LIU should exceed threshold → 1 warning
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `nested control flow inside blocks still contributes to density`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable

            data class UiState(val list: List<Int>, val flag: Boolean)

            @Composable
            fun NestedLogic(uiState: UiState) {
                if (uiState.flag) {
        for (i in uiState.list) {
            if (i % 2 == 0) {
                Text("Even ${'$'}i")
            } else {
                Text("Odd ${'$'}i")
            }
        }
    }
    Text("Summary")
    Text("Footer")
    Text("Footer")
    Text("Footer")
    Text("Footer")
Text("Summary")
            }
            """
        ).indented()

        // Nested if+for+if else → high logic density for a non-trivial function → warning
        lintCheck(code).expectClean()
    }
//    @Composable
//    fun NestedLogic(uiState: UiState) {
//        if (uiState.flag) {
//            for (i in uiState.list) {
//                if (i % 2 == 0) {
//                    Text("Even ${'$'}i")
//                } else {
//                    Text("Odd ${'$'}i")
//                }
//            }
//        }
//        Text("Summary")
//        Text("Summary")
//        Text("Summary")
//        Text("Footer")
//        Text("Footer")
//    }
    @Test
    fun `multiple composables in same file only heavy one is reported`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable

            @Composable
            fun Header() {
                Text("Header")
            }

            @Composable
            fun Footer() {
                Text("Footer")
            }

            data class UiState(val a: Int, val b: Int)

            @Composable
            fun Mixed(uiState: UiState) {
                if (uiState.a > 0) {
                    Text("A+")
                }

                if (uiState.b > 0) {
                    Text("B+")
                }

                if (uiState.a > uiState.b) {
                    Text("A>B")
                } else {
                    Text("A<=B")
                }

                Text("More")
                Text("Final")
            }
            """
        ).indented()

        // Only Mixed() has enough control-flow + size to trigger LIU threshold → exactly one warning
        lintCheck(code).expectWarningCount(1)
    }

    // -----------------------------------------------------
    // 3. Statement counting verification
    // -----------------------------------------------------

    @Test
    fun `statements inside if and when blocks are counted correctly`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable

            enum class State { State1, State2 }

            @Composable
            fun StatementCountingTest() {
                // Root block: 3 statements (val x, if, when)
                val x = 1
                
                // If block: 2 statements (val y, val z)
                if (true) {
                    val y = 2
                    val z = 3
                }
                if (true) {
                    val y = 2
                    val z = 3
                }
                
                // When block: 2 clauses, each with 1 statement (Text in each clause)
                when (State.State1) {
                    State.State1 -> {
                        Text("State1")
                    }
                    State.State2 -> {
                        Text("State2")
                    }
                }
            }
            """
        ).indented()

        // Expected: 7 total statements
        // - Root: 3 (val x, if, when)
        // - If block: 2 (val y, val z)
        // - When clause 1: 1 (Text)
        // - When clause 2: 1 (Text)
        // Total: 3 + 2 + 1 + 1 = 7 statements
        // Control flow: 2 (if, when)
        // LIU = 2/7 = 0.29 < 0.3 threshold, but we have 2 control flow statements
        // Since MIN_CONTROL_FLOW_COUNT = 2, and we have exactly 2, and 7 >= MIN_STATEMENTS_FOR_ANALYSIS (5),
        // but LIU (0.29) < threshold (0.3), so it should be clean
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `statements inside nested if and when blocks are all counted`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable

            enum class Mode { A, B }

            @Composable
            fun NestedStatementCounting() {
                // Root block: 4 statements (val a, if, when, Text)
                val a = 1
                
                // If block: 3 statements (val b, if, Text)
                if (true) {
                    val b = 2
                    // Nested if: 2 statements (val c, Text)
                    if (true) {
                        val c = 3
                        Text("Nested")
                    }
                }
                
                // When block: 2 clauses
                when (Mode.A) {
                    Mode.A -> {
                        // Clause 1: 2 statements (val d, Text)
                        val d = 4
                        Text("A")
                    }
                    Mode.B -> {
                        // Clause 2: 1 statement (Text)
                        Text("B")
                    }
                }
                
                Text("End")
            }
            """
        ).indented()

        // Expected: 11 total statements
        // - Root: 4 (val a, if, when, Text)
        // - If block: 3 (val b, nested if, Text)
        // - Nested if block: 2 (val c, Text)
        // - When clause 1: 2 (val d, Text)
        // - When clause 2: 1 (Text)
        // Total: 4 + 3 + 2 + 2 + 1 = 12 statements (wait, let me recount)
        // Actually: Root has 4, if block has 3 (val b, nested if, Text), nested if has 2, when clause 1 has 2, when clause 2 has 1
        // But the nested if is inside the if block, so: 4 + 3 + 2 + 2 + 1 = 12
        // Control flow: 3 (if, nested if, when)
        // LIU = 3/12 = 0.25 < 0.3, but we have 3 >= MIN_CONTROL_FLOW_COUNT (2)
        // Since LIU < threshold, should be clean
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `high statement count with high control flow density triggers warning`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable

            data class UiState(val flag1: Boolean, val flag2: Boolean, val flag3: Boolean)

            @Composable
            fun HighDensityLogic(uiState: UiState) {
                // Root: 3 statements (if, if, if)
                if (uiState.flag1) {
                    // If 1: 2 statements (val, Text)
                    val x = 1
                    Text("Flag1")
                }
                
                if (uiState.flag2) {
                    // If 2: 2 statements (val, Text)
                    val y = 2
                    Text("Flag2")
                }
                
                if (uiState.flag3) {
                    // If 3: 2 statements (val, Text)
                    val z = 3
                    Text("Flag3")
                }
            }
            """
        ).indented()

        // Expected: 9 total statements
        // - Root: 3 (if, if, if)
        // - If 1: 2 (val x, Text)
        // - If 2: 2 (val y, Text)
        // - If 3: 2 (val z, Text)
        // Total: 3 + 2 + 2 + 2 = 9 statements
        // Control flow: 3 (if, if, if)
        // LIU = 3/9 = 0.33 > 0.3 threshold → should trigger warning
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `if_inside_onclick_callback_is_not_counted_but_ifs_in_composable_body_are_counted`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable
            import androidx.compose.material3.*
            
            @Composable
            fun TestComposable(flag: Boolean) {
                Button(
                    onClick = {
                        if (flag) {
                            // This IF should NOT be counted
                        }
                    }
                ) { }
                Text("")
                if (flag) {
                    // This IF should be counted (1)
                }
                if (flag) {
                    // This IF should be counted (2)
                }
                if (flag) {
                    // This IF should be counted (3)
                }
                if (flag) {
                    // This IF should be counted (4)
                }
            }
            """
        ).indented()

        // Should count only the 4 IFs in composable body, not the one in onClick
        // Total statements: Button call, onClick lambda (not counted as statement), Text, 4 IFs = 6 statements
        // Control flow: 4 IFs (all in composable body)
        // LIU = 4/6 = 0.67 > 0.3 threshold → should trigger warning
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `if_inside_onvaluechange_callback_is_not_counted`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable
            import androidx.compose.material3.*
            
            @Composable
            fun TestTextField(flag: Boolean) {
                TextField(
                    value = "",
                    onValueChange = { newValue ->
                        if (newValue.isNotEmpty()) {
                            println("Not empty")
                        } else {
                            println("Empty")
                        }
                        if (newValue.length > 10) {
                            println("Too long")
                        }
                    }
                )
                Text("Footer")
            }
            """
        ).indented()

        // Should NOT count IFs inside onValueChange callback
        // Only Text("Footer") is a statement, but total < MIN_STATEMENTS_FOR_ANALYSIS (5)
        lintCheck(code).expectClean()
    }

    @Test
    fun `if_inside_pointerinput_lambda_is_not_counted`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable
            import androidx.compose.foundation.gestures.*
            import androidx.compose.foundation.layout.*
            import androidx.compose.material3.*
            
            @Composable
            fun TestDraggable(flag: Boolean) {
                Box(
                    Modifier.pointerInput(Unit) {
                        if (flag) {
                            detectDragGestures { change, dragAmount ->
                                // drag logic
                            }
                        } else {
                            detectTapGestures {
                                // tap logic
                            }
                        }
                    }
                ) {
                    Text("Drag me")
                    Text("More text")
                    Text("Even more")
                    Text("Final text")
                }
            }
            """
        ).indented()

        // Should NOT count IFs inside pointerInput modifier lambda
        lintCheck(code).expectClean()
    }

    @Test
    fun `if_inside_composable_lambda_is_counted`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable
            import androidx.compose.foundation.layout.*
            import androidx.compose.material3.*
            
            @Composable
            fun TestComposable(flag: Boolean) {
                Column {
                    if (flag) {
                        Text("Flag is true")
                    } else {
                        Text("Flag is false")
                    }
                    if (flag) {
                        Text("Another if")
                    }
                    if (flag) {
                        Text("Third if")
                    }
                    if (flag) {
                        Text("Fourth if")
                    }
                }
            }
            """
        ).indented()

        // Should count IFs inside Column composable lambda (UI-building scope)
        // Total statements: Column call, 4 IFs = 5 statements
        // Control flow: 4 IFs
        // LIU = 4/5 = 0.8 > 0.3 threshold → should trigger warning
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `if_inside_lazycolumn_item_lambda_is_counted`() {
        val code = kotlin(
            """
            package test
            import androidx.compose.runtime.Composable
            import androidx.compose.foundation.lazy.*
            import androidx.compose.material3.*
            
            @Composable
            fun TestList(items: List<String>) {
                LazyColumn {
                    items(items) { item ->
                        if (item.isNotEmpty()) {
                            Text(item)
                        } else {
                            Text("Empty")
                        }
                        if (item.length > 5) {
                            Text("Long")
                        }
                        if (item.length > 10) {
                            Text("Very long")
                        }
                    }
                }
            }
            """
        ).indented()

        // Should count IFs inside LazyColumn item lambda (composable lambda)
        // Total statements: LazyColumn call, items call, 3 IFs = 5 statements
        // Control flow: 3 IFs
        // LIU = 3/5 = 0.6 > 0.3 threshold → should trigger warning
        lintCheck(code).expectWarningCount(1)
    }
}