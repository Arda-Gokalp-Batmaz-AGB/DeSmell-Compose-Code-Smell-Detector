// Copyright (C) 2022 Salesforce, Inc.
// Copyright 2022 Twitter, Inc.
// SPDX-License-Identifier: Apache-2.0
package slack.lint.compose.util

import org.jetbrains.kotlin.psi.KtFunction
import org.jetbrains.uast.UAnnotated
import org.jetbrains.uast.UMethod

val UAnnotated.isComposable: Boolean
  get() = findAnnotation("androidx.compose.runtime.Composable") != null

/**
 * Composable scope types - extension functions on these types are implicitly composable
 * even without an explicit @Composable annotation.
 */
private val COMPOSABLE_SCOPE_TYPES = setOf(
    "LazyListScope",
    "LazyColumnScope",
    "LazyRowScope",
    "LazyVerticalGridScope",
    "LazyHorizontalGridScope",
    "androidx.compose.foundation.lazy.LazyListScope",
    "androidx.compose.foundation.lazy.LazyColumnScope",
    "androidx.compose.foundation.lazy.LazyRowScope",
    "androidx.compose.foundation.lazy.grid.LazyVerticalGridScope",
    "androidx.compose.foundation.lazy.grid.LazyHorizontalGridScope"
)

/**
 * Enhanced composable detection that works for both normal composables and extension functions
 * (e.g., LazyListScope extensions). This checks both UAST and PSI representations to ensure
 * extension functions are properly detected.
 * 
 * Extension functions on composable scopes (like LazyListScope) are considered composable
 * even without an explicit @Composable annotation, as they can only be called within
 * composable contexts.
 */
fun UMethod.isComposableFunction(): Boolean {
    // First check UAST representation (works for normal composables)
    if (this.isComposable) {
        return true
    }

    // For extension functions, check PSI directly
    val sourcePsi = this.sourcePsi
    if (sourcePsi is KtFunction) {
        // Check if function has @Composable annotation in PSI
        val hasComposableAnnotation = sourcePsi.annotationEntries.any { annotationEntry ->
            val annotationText = annotationEntry.text
            annotationText.contains("@Composable") ||
            annotationText.contains("androidx.compose.runtime.Composable") ||
            annotationEntry.shortName?.asString() == "Composable"
        }
        
        if (hasComposableAnnotation) {
            return true
        }
        
        // Check if this is an extension function on a composable scope
        // Extension functions on LazyListScope, LazyColumnScope, etc. are implicitly composable
        val receiverTypeRef = sourcePsi.receiverTypeReference
        if (receiverTypeRef != null) {
            val receiverTypeText = receiverTypeRef.text.trim()
            // Check exact match first (e.g., "LazyListScope")
            if (receiverTypeText in COMPOSABLE_SCOPE_TYPES) {
                return true
            }
            // Check if it's a qualified name ending with the scope type
            // (e.g., "androidx.compose.foundation.lazy.LazyListScope")
            if (COMPOSABLE_SCOPE_TYPES.any { scopeType ->
                receiverTypeText == scopeType || 
                receiverTypeText.endsWith(".$scopeType") ||
                receiverTypeText.endsWith(".${scopeType.substringAfterLast('.')}")
            }) {
                return true
            }
        }
    }

    // Also check if the method has the annotation directly
    if (this.hasAnnotation("androidx.compose.runtime.Composable")) {
        return true
    }

    return false
}
