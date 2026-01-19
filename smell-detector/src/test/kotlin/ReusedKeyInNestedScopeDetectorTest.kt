package com.arda.smell_detector.rules

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestLintResult
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Test
import stubs.COMPOSITION_LOCAL_STUBS

class ReusedKeyInNestedScopeDetectorTest {

    // ----------------------
    // Unified lintCheck helper
    // ----------------------
    private fun lintCheck(vararg files: TestFile): TestLintResult {
        return lint()
            .files(COMPOSITION_LOCAL_STUBS, *files)
            .issues(ReusedKeyInNestedScopeIssue.ISSUE)
            .allowMissingSdk(true)
            .run()
    }

    // ----------------------
    // TEST CASES
    // ----------------------

    @Test
    fun `key reused across multi-level composable chain is reported`() {
        val code = kotlin(
            """
        package test
        import androidx.compose.runtime.Composable
        import androidx.compose.runtime.key
        import androidx.compose.runtime.LaunchedEffect

        @Composable
        fun Test(mey: String) {
            key(mey) {
                Dene(meyt = mey, t = "")
            }
        }

        @Composable
        fun Dene(meyt: String, t: String) {
            DeneAlt(meyt)
        }

        @Composable
        fun DeneAlt(mey: String) {
            LaunchedEffect(mey) { }
        }
        """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `same key reused in nested key and LaunchedEffect is reported`() {
        val code = kotlin(
            """
            package test

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.key
            import androidx.compose.runtime.LaunchedEffect

            @Composable
            fun Sample(userId: String) {
                key(userId) {
                    LaunchedEffect(userId) {
                    }
                }
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `same key reused in nested key with a non-nested key is reported`() {
        val code = kotlin(
            """
            package test

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.key
            import androidx.compose.runtime.LaunchedEffect

            @Composable
            fun Sample(userId: String) {
            val t = 5
                key(userId) {
                    key(t,userId) {
                        
                    }
                }
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `same key reused in nested key scopes is reported`() {
        val code = kotlin(
            """
            package test

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.key

            @Composable
            fun Sample(userId: String) {
                key(userId) {
                    key(userId) {
                    }
                }
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `same key reused in LaunchedEffect and DisposableEffect is reported`() {
        val code = kotlin(
            """
            package test

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.LaunchedEffect
            import androidx.compose.runtime.DisposableEffect

            @Composable
            fun Sample(userId: String) {
                LaunchedEffect(userId) {
                    DisposableEffect(userId) {
                    }
                }
            }
            """
        ).indented()

        lintCheck(code).expectWarningCount(1)
    }

    @Test
    fun `different keys in parent and child are not reported`() {
        val code = kotlin(
            """
            package test

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.key
            import androidx.compose.runtime.LaunchedEffect

            @Composable
            fun Sample(userId: String, sessionId: String) {
                key(userId) {
                    LaunchedEffect(sessionId) {
                    }
                }
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }

    @Test
    fun `same key in sibling scopes but not nested is not reported`() {
        val code = kotlin(
            """
            package test

            import androidx.compose.runtime.Composable
            import androidx.compose.runtime.key
            import androidx.compose.runtime.LaunchedEffect

            @Composable
            fun Sample(userId: String) {
                key(userId) {
                }
                LaunchedEffect(userId) {
                }
            }
            """
        ).indented()

        lintCheck(code).expectClean()
    }
}
