package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * Creates a [RecordingStore] — a [Store] assembled with an [ActionRecorder] on the observer
 * hook, for the one-liner case; factories that take an `observer` parameter don't need it —
 * pass an [ActionRecorder] there instead. The effect type exists only at this construction
 * gate, like [Store]'s.
 */
public fun <S : Any, A, E> RecordingStore(
    initialState: S,
    reducer: Reducer<S, A, E>,
    handler: EffectHandler<E, A>,
    defects: DefectHandler<S, A, E> = rethrowingDefectHandler(),
    scope: StoreScope = StoreScope.Main,
    start: A? = null,
): RecordingStore<S, A> {
    val recorder = ActionRecorder<S, A>()
    return RecordingStore(
        initialState = initialState,
        recorder = recorder,
        delegate =
            Store(
                initialState = initialState,
                reducer = reducer,
                handler = handler,
                defects = defects,
                scope = scope,
                observer = recorder,
                start = start,
            ),
    )
}

/**
 * A store that keeps its action log for after-the-fact debugging: [initialState] plus
 * [recording]'s actions replayed over the pure reducer reproduce the state trajectory.
 * Recording rides the store's observer hook, so it captures every action in reduction order —
 * effect-fed actions included — and can only observe, never intercept. The store surface
 * ([state], [stateFlow], [events], [send], [observe], [close]) passes straight through to the
 * wrapped [Store].
 */
public class RecordingStore<S : Any, A> internal constructor(
    public val initialState: S,
    private val recorder: ActionRecorder<S, A>,
    private val delegate: Store<S, A>,
) {
    /** The synchronous snapshot of the current state. */
    public val state: S
        get() = delegate.state

    /** The reactive surface — `stateFlow` for Kotlin consumers, `stateSequence` in Swift. */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName(swiftName = "stateSequence")
    public val stateFlow: StateFlow<S>
        get() = delegate.stateFlow

    /** The store's one-shot events for the holder; see [Store.events]. */
    public val events: Flow<A>
        get() = delegate.events

    /** Never null: before the first action it is the empty recording at [initialState]. */
    public val recording: Recording<S, A>
        get() = recorder.recording ?: Recording(initialState, emptyList())

    public fun send(action: A) {
        delegate.send(action)
    }

    public fun observe(onEach: (S) -> Unit): Subscription = delegate.observe(onEach)

    public fun close() {
        delegate.close()
    }

    public fun clearRecording() {
        recorder.clear()
    }
}
