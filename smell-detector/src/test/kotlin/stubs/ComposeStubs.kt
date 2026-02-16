package stubs

import com.android.tools.lint.checks.infrastructure.LintDetectorTest

val ANDROIDX_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.compose.ui.unit
    class Dp
    fun Dp(value: Int): Dp = Dp()
    """
).indented()

val ROUNDED_CORNER_SHAPE_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.compose.foundation.shape

    import androidx.compose.ui.unit.Dp

    class RoundedCornerShape(radius: Dp)
    """
).indented()

val RememberSaveable_LOCAL_STUBS = LintDetectorTest.kotlin(
    """
   package androidx.compose.runtime.saveable

    import androidx.compose.runtime.Composable
    @Composable
    fun <T> rememberSaveable(vararg inputs: Any?, saver: Any? = null, init: () -> T): T = init()
    """
).indented()

val COMPOSITION_LOCAL_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.compose.runtime
    import androidx.compose.runtime.Composable

    annotation class Composable
    annotation class Stable
    abstract class CompositionLocal<T>(val default: T)

    class MutableState<T>(var value: T)
    fun <T> mutableStateOf(value: T): MutableState<T> = MutableState(value)
    fun <T> mutableStateListOf(vararg elements: T): MutableList<T> = elements.toMutableList()
    fun <K, V> mutableStateMapOf(): MutableMap<K, V> = mutableMapOf()
    fun <T> remember(calculation: @Composable () -> T): T = calculation()
    operator fun <T> MutableState<T>.getValue(thisObj: Any?, property: kotlin.reflect.KProperty<*>): T = value
    operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: kotlin.reflect.KProperty<*>, newValue: T) {
        value = newValue
    }
    @Composable
    fun <T> rememberUpdatedState(newValue: T): State<T> = object : State<T> {
        override val value: T = newValue
    }

public interface State<T> {
    val value: T
}

// DerivedState is a separate type from State to avoid being flagged by MutableStateInConditionDetector
public interface DerivedState<T> {
    val value: T
}

fun <T> derivedStateOf(calculation: () -> T): DerivedState<T> = TODO()

private class StateImpl<T>(override val value: T) : State<T>
    object LocalContext : CompositionLocal<Any>(Any())
    object LocalDensity : CompositionLocal<Any>(Any())
    fun RoundedCornerShape(size: Dp) = Unit
    fun key(key: Any?, block: () -> Unit) { }
    fun LaunchedEffect(vararg keys: Any?, block: suspend () -> Unit) { }

    interface DisposableEffectResult
    class DisposableEffectScope {
        fun onDispose(onDisposeEffect: () -> Unit): DisposableEffectResult = object : DisposableEffectResult {}
    }
    @Composable
    fun DisposableEffect(vararg keys: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult): DisposableEffectResult {
        return DisposableEffectScope().effect()
    }
    fun <T> remember(vararg keys: Any?, calculation: () -> T): T = calculation()
    val <T> CompositionLocal<T>.current: T
        @Composable get() = this.default
    """
).indented()

val FLOW_AND_COLLECT_STUBS = LintDetectorTest.kotlin(
    """
    package kotlinx.coroutines.flow
    
    interface Flow<out T>
    interface StateFlow<out T> : Flow<T>
    interface MutableStateFlow<T> : StateFlow<T>
    """
).indented()

val COLLECT_AS_STATE_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.compose.runtime
    
    import androidx.compose.runtime.Composable
    import kotlinx.coroutines.flow.Flow
    
    @Composable
    fun <T> Flow<T>.collectAsState(initial: T): State<T> = TODO()
    """
).indented()

val COLLECT_AS_STATE_WITH_LIFECYCLE_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.lifecycle.compose
    
    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.State
    import kotlinx.coroutines.flow.Flow
    
    @Composable
    fun <T> Flow<T>.collectAsStateWithLifecycle(initialValue: T): State<T> = TODO()
    """
).indented()

val TEXT_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.compose.ui.text

    import androidx.compose.runtime.Composable

    @Composable
    fun Text(text: String) {}
    """
).indented()

val LAYOUT_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.compose.foundation.layout

    import androidx.compose.runtime.Composable

    @Composable
    fun Column(content: @Composable () -> Unit) { content() }
    """
).indented()

val COLOR_STUBS = LintDetectorTest.kotlin(
    """
    package androidx.compose.ui.graphics
    object Color {
        fun Red(): Color = Color()
        class Color
    }
    """
).indented()

val PARCELABLE_STUBS = LintDetectorTest.kotlin(
    """
    package android.os

    interface Parcelable {
        companion object {
            fun <T : Parcelable> describeContents(obj: T): Int = 0
        }
        fun describeContents(): Int = 0
        fun writeToParcel(dest: android.os.Parcel, flags: Int)
    }

    class Parcel
    """
).indented()

val PARCELIZE_STUBS = LintDetectorTest.kotlin(
    """
    package kotlinx.parcelize

    annotation class Parcelize
    """
).indented()