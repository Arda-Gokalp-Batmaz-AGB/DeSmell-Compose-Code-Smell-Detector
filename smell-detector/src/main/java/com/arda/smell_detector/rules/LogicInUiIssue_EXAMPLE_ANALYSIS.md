# Statement Counting Analysis: PodcastImage Example

## Your Function

```kotlin
@Composable
fun PodcastImage(url: String, modifier: Modifier = Modifier, aspectRatio: Float = 1f) {
    val imagePainter = rememberCoilPainter(url)  // Statement 1
    if (true) { }                                // Statement 2
    if (true) { }                                // Statement 3
    if (true) { }                                // Statement 4
    Box(...) {                                   // Statement 5
        Image(...)                               // NOT counted (nested)
        val t = 5                                // NOT counted (nested)
        when(t) { }                              // NOT counted as statement, BUT counted as control flow
        when (imagePainter.loadState) { ... }    // NOT counted as statement, control flow EXCLUDED
    }
}
```

## How It Counts

### Total Statements (Top-Level Only)

The detector uses:
```kotlin
counters.totalStatements = body.expressions.size
```

This counts **only direct expressions** in the composable body block.

**Counted Statements:**
1. ✅ `val imagePainter = rememberCoilPainter(url)` - Top-level statement
2. ✅ `if (true) { }` - Top-level statement
3. ✅ `if (true) { }` - Top-level statement
4. ✅ `if (true) { }` - Top-level statement
5. ✅ `Box(...) { ... }` - Top-level statement

**NOT Counted (Nested Inside Box):**
- ❌ `Image(...)` - Nested inside Box block
- ❌ `val t = 5` - Nested inside Box block
- ❌ `when(t) { }` - Nested inside Box block (not counted as statement)
- ❌ `when (imagePainter.loadState) { ... }` - Nested inside Box block (not counted as statement)

**Total Statements: 5**

---

### Control Flow Count (Recursive)

The detector visits ALL control flow expressions recursively using `AbstractUastVisitor`:

**Counted Control Flow:**
1. ✅ `if (true) { }` - Line 2, top-level
2. ✅ `if (true) { }` - Line 3, top-level
3. ✅ `if (true) { }` - Line 4, top-level
4. ✅ `when(t) { }` - Nested inside Box, but still counted

**EXCLUDED Control Flow:**
- ❌ `when (imagePainter.loadState) { ... }` - **EXCLUDED** because it checks `ImageLoadState` (acceptable UI state)

**Total Control Flow: 4**

---

## LIU Calculation

```
LIU = controlFlowCount / totalStatements
LIU = 4 / 5 = 0.80 (80%)
```

## Threshold Checks

1. **MIN_STATEMENTS_FOR_ANALYSIS = 5**
   - Total = 5 ✅ **PASSES**

2. **MIN_CONTROL_FLOW_COUNT = 4**
   - Control Flow = 4 ✅ **PASSES**

3. **LIU_THRESHOLD = 0.30**
   - LIU = 0.80 > 0.30 ❌ **EXCEEDS THRESHOLD**

## Result

**This function WILL be flagged** because:
- LIU = 0.80 (80% of statements are control flow)
- Exceeds threshold of 0.30 (30%)
- Has 4 control flow statements (meets minimum)

---

## Why This Design?

### Only Top-Level Statements

**Rationale:**
- Nested statements are part of the structure, not separate logical units
- `Box { Image(...) }` is ONE statement (the Box), not two
- Prevents double-counting and inflation

**Example:**
```kotlin
Box {           // 1 statement
    Image(...)  // Part of Box structure
    Text(...)   // Part of Box structure
}
// Total: 1, not 3
```

### Control Flow Counted Recursively

**Rationale:**
- Control flow at any level adds complexity
- Nested `if/when` still represents logic in UI
- Need to catch logic even when nested

**Example:**
```kotlin
Box {
    if (condition) {  // This IS counted (adds complexity)
        Image(...)
    }
}
```

### UI State Checks Excluded

**Rationale:**
- `ImageLoadState`, `LoadingState` are legitimate UI concerns
- Not business logic that should be in ViewModel
- Part of UI rendering logic

---

## Summary for Your Example

| Metric | Value | Notes |
|--------|-------|-------|
| **Total Statements** | 5 | Only top-level (val, 3 if, Box) |
| **Control Flow** | 4 | 3 if + 1 when(t), excluding ImageLoadState when |
| **LIU** | 0.80 | 4/5 = 80% |
| **Threshold** | 0.30 | 30% |
| **Result** | ✅ **FLAGGED** | LIU 0.80 > 0.30 |

The function will trigger because 80% control flow density is very high and indicates logic-heavy UI.

