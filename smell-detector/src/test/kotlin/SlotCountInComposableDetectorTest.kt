package com.arda.smell_detector

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import com.arda.smell_detector.rules.SlotCountInComposableDetector
import com.arda.smell_detector.rules.SlotCountInComposableIssue
import stubs.ANDROIDX_STUBS
import stubs.COLOR_STUBS
import stubs.COMPOSITION_LOCAL_STUBS
import stubs.ROUNDED_CORNER_SHAPE_STUBS

class SlotCountInComposableDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = SlotCountInComposableDetector()

    override fun getIssues(): List<Issue> = listOf(SlotCountInComposableIssue.ISSUE)

    /**
     * Helper to run lint tests with the necessary Compose stubs
     */
    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(ANDROIDX_STUBS, COLOR_STUBS, ROUNDED_CORNER_SHAPE_STUBS, COMPOSITION_LOCAL_STUBS, *files)
            .allowMissingSdk(true)
            .run()
    }

    // -----------------------------------------------------
    // 1. Baseline cases (no warning)
    // -----------------------------------------------------

    fun `test_no_slots_is_clean`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.Composable

            @Composable
            fun MyComposable() {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    fun `test_two_slots_is_clean`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.Composable

            @Composable
            fun Screen(
                header: @Composable () -> Unit,
                content: @Composable () -> Unit
            ) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    // -----------------------------------------------------
    // 2. Warning cases
    // -----------------------------------------------------

    fun `test_three_slots_is_flagged`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.Composable
            @Composable
            fun Screen(
                header: @Composable () -> Unit,
                body: @Composable () -> Unit,
                footer: @Composable () -> Unit,
                footer2: @Composable () -> Unit
            ) {}
            """
        ).indented()

        lintCheck(code)
            .expect(
                """
                src/test.kt:3: Warning: Composable exposes 4 slots (max allowed is 3) [SlotCountInComposable]
                fun Screen(
                    ~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    fun `test_scope_receiver_slots_are_flagged`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.Composable

            interface MyScope

            @Composable
            fun Screen(
                slot1: @Composable MyScope.() -> Unit,
                slot2: @Composable MyScope.() -> Unit,
                slot3: @Composable MyScope.() -> Unit,
                slot4: @Composable MyScope.() -> Unit
            ) {}
            """
        ).indented()

        lintCheck(code)
            .expect(
                """
                src/MyScope.kt:6: Warning: Composable exposes 4 slots (max allowed is 3) [SlotCountInComposable]
                fun Screen(
                    ~~~~~~
                0 errors, 1 warnings
                """
            )
    }

    // -----------------------------------------------------
    // 3. Non-composable cases should not be flagged
    // -----------------------------------------------------

    fun `test_not_composable_function_is_ignored`() {
        val code = kotlin(
            """
            fun RegularFunction(
                x: @androidx.compose.runtime.Composable () -> Unit,
                y: @androidx.compose.runtime.Composable () -> Unit,
                z: @androidx.compose.runtime.Composable () -> Unit
            ) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    fun `test_non_composable_lambdas_are_ignored`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.Composable

            @Composable
            fun MyComposable(
                processor: (Int) -> Unit,
                onClick: (String) -> Unit,
                builder: (Int, String) -> Boolean
            ) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    fun `test_mixed_slots_and_non_slots`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.Composable

            @Composable
            fun Screen(
                a: @Composable () -> Unit,
                b: () -> Unit,
                c: Int,
                d: String
            ) {}
            """
        ).indented()

        lintCheck(code).expectClean()
    }
}
