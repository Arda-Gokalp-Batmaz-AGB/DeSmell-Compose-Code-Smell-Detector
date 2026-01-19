# HighSideEffectDensityIssue Detector - Improvement Ideas

## 1. **Improved Statement Counting** (High Priority)
**Current Issue**: Uses `bodyBlock?.statements?.size` which only counts top-level statements, similar to the issue we fixed in `LogicInUiIssue`.

**Improvement**: 
- Use recursive statement counting (like in `LogicInUiIssue`)
- Count all statements in blocks, including nested blocks inside `if`/`when`/loops
- This gives a more accurate SED calculation

**Implementation**:
```kotlin
private fun countAllStatements(function: KtFunction): Int {
    val bodyBlock = function.bodyBlockExpression ?: return 0
    var count = 0
    bodyBlock.accept(object : KtTreeVisitorVoid() {
        override fun visitBlockExpression(expression: KtBlockExpression) {
            count += expression.statements.size
            super.visitBlockExpression(expression)
        }
    })
    return count
}
```

---

## 2. **Weighted Side Effects** (Medium Priority)
**Current Issue**: All side effects are treated equally, but some are more complex than others.

**Improvement**:
- Assign weights to different effect types
- `DisposableEffect` with cleanup is more complex than `SideEffect`
- `produceState` is more complex than `LaunchedEffect`

**Weighted SED Formula**:
```kotlin
Weighted SED = Σ(effect_weight) / total_statements

Weights:
- SideEffect: 1.0
- LaunchedEffect: 1.5
- DisposableEffect: 2.0 (with cleanup: 2.5)
- produceState: 2.0
- produceRetainedState: 2.5
```

---

## 3. **Effect Complexity Analysis** (Medium Priority)
**Current Issue**: Doesn't consider the complexity of the effect body itself.

**Improvement**:
- Analyze the complexity of code inside each effect
- Count statements/lines inside effect bodies
- Effects with complex logic contribute more to SED

**Implementation**:
```kotlin
private fun calculateEffectComplexity(effect: KtCallExpression): Double {
    val lambda = effect.lambdaArguments.firstOrNull()?.getLambdaExpression()
    val body = lambda?.bodyExpression as? KtBlockExpression
    return when {
        body == null -> 1.0
        body.statements.size > 10 -> 2.0
        body.statements.size > 5 -> 1.5
        else -> 1.0
    }
}
```

---

## 4. **Nested Effects Detection** (Medium Priority)
**Current Issue**: Only counts top-level effects, but effects can be nested inside `if`/`when`/loops.

**Improvement**:
- Recursively find all effects, not just top-level ones
- Nested effects are still problematic and should be counted
- Consider depth penalty (effects nested deeper are worse)

**Implementation**:
```kotlin
private fun findAllEffects(function: KtFunction): List<EffectInfo> {
    val effects = mutableListOf<EffectInfo>()
    function.accept(object : KtTreeVisitorVoid() {
        var depth = 0
        override fun visitCallExpression(expression: KtCallExpression) {
            if (expression.isSideEffectCall()) {
                effects.add(EffectInfo(expression, depth))
            }
            depth++
            super.visitCallExpression(expression)
            depth--
        }
    })
    return effects
}
```

---

## 5. **Context-Aware Detection** (Low Priority)
**Current Issue**: Some effect patterns are acceptable and shouldn't be flagged.

**Improvement**:
- Exclude certain acceptable patterns:
  - Single `LaunchedEffect` for one-time initialization
  - `remember { }` + `LaunchedEffect` pattern (common and acceptable)
  - Effects inside `@Composable` helper functions (not the main composable)
- Consider composable size - small composables with 2 effects might be fine

**Implementation**:
```kotlin
private fun isAcceptableEffectPattern(effects: List<KtCallExpression>): Boolean {
    // Single LaunchedEffect for initialization is usually fine
    if (effects.size == 1 && effects[0].isLaunchedEffect()) {
        return true
    }
    // remember + LaunchedEffect pattern is common
    if (effects.size == 2 && hasRememberLaunchedEffectPattern(effects)) {
        return true
    }
    return false
}
```

---

## 6. **Effect Grouping Analysis** (Medium Priority)
**Current Issue**: Doesn't detect when multiple effects are related and could be consolidated.

**Improvement**:
- Group effects by their keys/dependencies
- Multiple `LaunchedEffect` with same keys = consolidation opportunity
- Effects with overlapping dependencies = potential consolidation

**Implementation**:
```kotlin
data class EffectGroup(
    val effects: List<KtCallExpression>,
    val commonKeys: Set<String>,
    val canBeConsolidated: Boolean
)

private fun groupRelatedEffects(effects: List<KtCallExpression>): List<EffectGroup> {
    // Group by similar keys or dependencies
    // Flag groups that could be consolidated
}
```

---

## 7. **Configuration Options** (Low Priority)
**Current Issue**: Thresholds are hardcoded.

**Improvement**:
- Make `SED_THRESHOLD` and `MIN_SIDE_EFFECTS` configurable via lint options
- Allow teams to adjust based on their codebase

**Implementation**:
```kotlin
internal const val SED_THRESHOLD_OPTION_NAME = "sedThreshold"
internal const val MIN_SIDE_EFFECTS_OPTION_NAME = "minSideEffects"

// In beforeCheckRootProject:
val sedThreshold = configuration.getOption(ISSUE, SED_THRESHOLD_OPTION_NAME)?.value?.toDouble() ?: 0.5
```

---

## 8. **Enhanced Reporting** (Low Priority)
**Current Issue**: Message could be more actionable.

**Improvement**:
- List which effects are present
- Suggest specific consolidation opportunities
- Show effect locations in the code

**Enhanced Message**:
```
Composable 'MyScreen' has high side-effect density (SED=0.67).
Found 4 side-effects: LaunchedEffect (line 15), DisposableEffect (line 22), 
LaunchedEffect (line 28), produceState (line 35).
Consider consolidating the two LaunchedEffect calls (lines 15, 28) which 
share similar dependencies.
```

---

## 9. **Integration with Other Metrics** (Medium Priority)
**Current Issue**: Doesn't consider other complexity metrics.

**Improvement**:
- Consider CFC (Composable Function Complexity) - high CFC + high SED is worse
- Consider LIU (Logic in UI) - high LIU + high SED indicates severe issues
- Combined metric: `(CFC * SED) / statements` for overall complexity

**Implementation**:
```kotlin
// If CFC is also high, lower the SED threshold
val adjustedThreshold = if (cfc > 30) {
    SED_THRESHOLD * 0.8  // More strict
} else {
    SED_THRESHOLD
}
```

---

## 10. **Effect Type-Specific Analysis** (Low Priority)
**Current Issue**: Doesn't analyze what each effect is doing.

**Improvement**:
- Detect common anti-patterns:
  - Multiple `LaunchedEffect` doing similar work
  - `DisposableEffect` without proper cleanup
  - `produceState` that could be `derivedStateOf`
  - Effects that should be in ViewModel

**Pattern Detection**:
```kotlin
private fun detectAntiPatterns(effects: List<KtCallExpression>): List<String> {
    val patterns = mutableListOf<String>()
    
    // Multiple LaunchedEffect with similar code
    if (hasDuplicateLaunchedEffects(effects)) {
        patterns.add("Multiple LaunchedEffect calls with similar logic")
    }
    
    // produceState that could be derivedStateOf
    if (hasProduceStateThatCouldBeDerived(effects)) {
        patterns.add("produceState could be replaced with derivedStateOf")
    }
    
    return patterns
}
```

---

## 11. **Effect Dependency Analysis** (Medium Priority)
**Current Issue**: Doesn't analyze effect dependencies/keys.

**Improvement**:
- Analyze effect keys/dependencies
- Flag effects with too many dependencies (complexity indicator)
- Detect circular dependencies between effects
- Suggest key consolidation

**Implementation**:
```kotlin
data class EffectDependencies(
    val keys: List<String>,
    val stateReads: List<String>,
    val complexity: Int
)

private fun analyzeEffectDependencies(effect: KtCallExpression): EffectDependencies {
    // Extract keys, state reads, calculate complexity
}
```

---

## 12. **Performance Impact Estimation** (Low Priority)
**Current Issue**: Doesn't estimate performance impact.

**Improvement**:
- Estimate recomposition frequency based on effect dependencies
- Flag effects that might cause excessive recompositions
- Consider effect restartability

**Implementation**:
```kotlin
private fun estimateRecompositionImpact(effects: List<KtCallExpression>): String {
    val restartableCount = effects.count { it.isRestartableEffect }
    val nonRestartableCount = effects.size - restartableCount
    
    return when {
        restartableCount > 3 -> "High risk: $restartableCount restartable effects may cause frequent recompositions"
        nonRestartableCount > 2 -> "Medium risk: $nonRestartableCount non-restartable effects"
        else -> "Low risk"
    }
}
```

---

## Priority Summary

### High Priority (Implement First)
1. ✅ Improved Statement Counting - Critical for accuracy

### Medium Priority (Significant Value)
2. ✅ Weighted Side Effects - Better reflects complexity
3. ✅ Effect Complexity Analysis - More nuanced detection
4. ✅ Nested Effects Detection - Catches more issues
5. ✅ Effect Grouping Analysis - Actionable suggestions
6. ✅ Integration with Other Metrics - Holistic view
7. ✅ Effect Dependency Analysis - Deeper insights

### Low Priority (Nice to Have)
8. ✅ Context-Aware Detection - Reduces false positives
9. ✅ Configuration Options - Flexibility
10. ✅ Enhanced Reporting - Better UX
11. ✅ Effect Type-Specific Analysis - Advanced patterns
12. ✅ Performance Impact Estimation - Additional value

---

## Recommended Implementation Order

1. **Phase 1**: Fix statement counting (High Priority #1)
2. **Phase 2**: Add nested effects detection (#4) and weighted effects (#2)
3. **Phase 3**: Add effect complexity (#3) and grouping analysis (#6)
4. **Phase 4**: Add dependency analysis (#11) and integration with other metrics (#9)
5. **Phase 5**: Polish with context-aware detection (#5), configuration (#7), and enhanced reporting (#8)

