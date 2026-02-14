package com.arda.smell_detector

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.arda.smell_detector.rules.ComposableFunctionComplexityIssue
import com.arda.smell_detector.rules.ConstantsInComposableIssue
import com.arda.smell_detector.rules.HighSideEffectDensityIssue
import com.arda.smell_detector.rules.LogicInUiIssue
import com.arda.smell_detector.rules.MultipleFlowCollectionsPerComposableIssue
import com.arda.smell_detector.rules.MutableStateInConditionIssue
import com.arda.smell_detector.rules.MutableStateMutationInComposableIssue
import com.arda.smell_detector.rules.NonSavableRememberSaveableIssue
import com.arda.smell_detector.rules.ReactiveStatePassThroughIssue
import com.arda.smell_detector.rules.RememberUpdatedStateWithConstantIssue
import com.arda.smell_detector.rules.ReusedKeyInNestedScopeIssue
import com.arda.smell_detector.rules.SideEffectComplexityIssue
import com.arda.smell_detector.rules.SlotCountInComposableIssue

class SmellIssueRegistry : IssueRegistry() {

    override val issues = listOf(
        ConstantsInComposableIssue.ISSUE,
        SlotCountInComposableIssue.ISSUE,
        ReusedKeyInNestedScopeIssue.ISSUE,
        MutableStateMutationInComposableIssue.ISSUE,
        LogicInUiIssue.ISSUE,
        MutableStateInConditionIssue.ISSUE,
        RememberUpdatedStateWithConstantIssue.ISSUE,
        NonSavableRememberSaveableIssue.ISSUE,
        MultipleFlowCollectionsPerComposableIssue.ISSUE,
        ReactiveStatePassThroughIssue.ISSUE,
        SideEffectComplexityIssue.ISSUE,
        ComposableFunctionComplexityIssue.ISSUE,
        HighSideEffectDensityIssue.ISSUE
    )

    override val api = CURRENT_API
    override val minApi = 8

    override val vendor = Vendor(
        vendorName = "Arda Gökalp Batmaz",
        identifier = "compose-code-smell-detector",
        feedbackUrl = "https://github.com/Arda-Gokalp-Batmaz-AGB",
        contact = "https://github.com/Arda-Gokalp-Batmaz-AGB"
    )
}