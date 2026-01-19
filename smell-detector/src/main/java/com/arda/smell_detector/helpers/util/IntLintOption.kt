package com.arda.smell_detector.helpers.util

import com.android.tools.lint.client.api.Configuration
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.StringOption
import slack.lint.compose.util.LintOption

/**
 * A lint option that loads an integer value from configuration.
 */
class IntLintOption(private val option: StringOption) : LintOption {
    var value: Int = 0
        private set

    override fun load(configuration: Configuration, issue: Issue) {
        val stringValue = configuration.getOption(issue, option.name)
        value = stringValue?.toIntOrNull() ?: 0
    }
}

