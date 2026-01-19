import com.android.tools.lint.checks.infrastructure.LintDetectorTest.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import com.arda.smell_detector.rules.ComposableFunctionComplexityIssue
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS

class ComposableFunctionComplexityDetectorTest {

    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, *files)
            .issues(ComposableFunctionComplexityIssue.ISSUE)
            .allowMissingSdk(true)
            .skipTestModes(TestMode.IF_TO_WHEN)
            .skipTestModes(TestMode.BODY_REMOVAL)
            .run()
    }

    @Test
    fun `does_not_flag_simple_composable`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            
            @Composable
            fun SimpleComposable(text: String) {
                Text(text)
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `does_not_flag_moderately_complex_composable`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            
            @Composable
            fun ModeratelyComplex(flag: Boolean) {
                if (flag) {
                    Text("On")
                } else {
                    Text("Off")
                }
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `flags_heavy_composable_with_branches_loops_and_sideEffects`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            
            @Composable
            fun HeavyComposable(flag: Boolean, items: List<Int>) {
                var sum = 0
                for (i in items) {
                    if (i % 2 == 0) {
                        sum += i
                    } else {
                        sum -= i
                    }
                }
                
                if (flag) {
                    LaunchedEffect(items.size) {
                        var acc = 0
                        for (i in 0..10) {
                            if (i % 2 == 0) {
                                acc += i
                            } else {
                                acc -= i
                            }
                        }
                        println(acc)
                    }
                } else {
                    SideEffect {
                        println("flag is false")
                    }
                }
                
                when {
                    sum > 0 -> Text("Positive: ${'$'}sum")
                    sum < 0 -> Text("Negative: ${'$'}sum")
                    else    -> Text("Zero")
                }
            }
            """
        ).indented()

        // Many branches, loops, nested side-effects → CFC should exceed threshold
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `flags_composable_with_very_complex_sideEffect_only`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            
            @Composable
            fun ComplexSideEffectOnly(trigger: Int) {
                Text("Header")
                
                LaunchedEffect(trigger) {
                    var a = 0
                    var b = 1
                    for (i in 0 until 20) {
                        val tmp = a + b
                        a = b
                        b = tmp
                        if (tmp % 3 == 0) {
                            println("div3: ${'$'}tmp")
                        } else if (tmp % 5 == 0) {
                            println("div5: ${'$'}tmp")
                        } else {
                            println(tmp)
                        }
                    }
                    when {
                        a > b -> println("a>b")
                        a < b -> println("a<b")
                        else  -> println("a==b")
                    }
                }
            }
            """
        ).indented()

        // Heavy logic concentrated inside LaunchedEffect → CFC should exceed threshold
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `non_composable_function_is_ignored`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            
            fun NotComposable(flag: Boolean, items: List<Int>) {
                var sum = 0
                for (i in items) {
                    if (i % 2 == 0) {
                        sum += i
                    } else {
                        sum -= i
                    }
                }
                println(sum)
            }
            """
        ).indented()

        // No @Composable, so rule should not apply
        lintCheck(code).expectClean()
    }

    @Test
    fun `multiple_composables_only_complex_one_flagged`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            
            @Composable
            fun LightComposable(value: Int) {
                if (value > 0) {
                    Text("Positive")
                } else {
                    Text("Non-positive")
                }
            }
            
            @Composable
            fun HeavyComposable2(items: List<Int>) {
                var sum = 0
                for (i in items) {
                    if (i % 2 == 0) sum += i else sum -= i
                }
                
                LaunchedEffect(sum) {
                    for (j in 0..10) {
                        if (j % 2 == 0) {
                            println("even ${'$'}j")
                        } else {
                            println("odd ${'$'}j")
                        }
                    }
                }
                
                when {
                    sum > 100 -> Text("Big")
                    sum > 0 -> Text("Small")
                    sum == 0 -> Text("Zero")
                    else -> Text("Negative")
                }
            }
            """
        ).indented()

        // Only HeavyComposable2 should cross the CFC threshold
        lintCheck(code).expectWarningCount(1)
    }

    // -----------------------------------------------------
    // Expression-bodied composable tests
    // -----------------------------------------------------

    @Test
    fun `does_not_flag_simple_expression_body_composable`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            import androidx.compose.foundation.layout.*
            import androidx.compose.material3.*
            
            @Composable
            fun SimpleExpressionBody(text: String) = Column {
                Text(text)
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `flags_expression_body_composable_with_high_complexity`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            import androidx.compose.foundation.layout.*
            import androidx.compose.material3.*
            
            @Composable
            fun ComplexExpressionBody(items: List<Int>) = Column {
                items.forEach { item ->
                    if (item % 2 == 0) {
                        Text("Even: ${'$'}item")
                    } else {
                        Text("Odd: ${'$'}item")
                    }
                }
                
                items.forEach { item ->
                    if (item > 10) {
                        Text("Large: ${'$'}item")
                    }
                }
                
                items.forEach { item ->
                    if (item < 0) {
                        Text("Negative: ${'$'}item")
                    }
                }
                
                items.forEach { item ->
                    Text("Item: ${'$'}item")
                }
                
                when {
                    items.isEmpty() -> Text("Empty")
                    items.size > 10 -> Text("Large list")
                    else -> Text("Small list")
                }
            }
            """
        ).indented()

        // Multiple forEach loops (> 4) should trigger the loop limit check
        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `flags_expression_body_composable_with_nested_composables`() {
        val code = kotlin(
            """
            import androidx.compose.runtime.*
            import androidx.compose.foundation.layout.*
            import androidx.compose.material3.*
            
            @Composable
            fun NestedExpressionBody(data: List<String>) = Column {
                Row {
                    data.forEach { item ->
                        if (item.isNotEmpty()) {
                            Text(item)
                        }
                    }
                }
                
                Box {
                    data.forEach { item ->
                        Text(item.uppercase())
                    }
                }
                
                LaunchedEffect(data.size) {
                    var count = 0
                    for (i in 0..10) {
                        if (i % 2 == 0) {
                            count++
                        }
                    }
                    println(count)
                }
            }
            """
        ).indented()

        // Should detect complexity from nested forEach, loops, and side effects
        lintCheck(code).expectWarningCount(1)
    }
}
