package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.EffectOwner
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Event
import io.github.milanhorvatovic.reducible.KeyPolicy
import io.github.milanhorvatovic.reducible.Reducer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.cancellation.CancellationException
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName
import kotlin.reflect.KClass

/**
 * Creates a [Store]. The effect type exists only here, at the construction gate, proving the
 * reducer, handler, and defect handler agree on it; the store's public type is effect-free —
 * effects are internal machinery, not consumer surface. (A factory rather than a constructor
 * because Kotlin constructors cannot declare their own type parameters.)
 *
 * [start] is the feature's unconditional start action, sent before the factory returns — so
 * the wiring that builds the store owns it and no platform holder can forget it. On a store
 * reducing on the main thread ([StoreScope.Main], or [StoreScope.Inherited] over a
 * main dispatcher) built there, it is reduced by the time the caller holds the store;
 * elsewhere it is the first action in the queue. The reducer handles it state-aware, which is
 * what makes a restored state converge.
 */
public fun <S : Any, A, E> Store(
    initialState: S,
    reducer: Reducer<S, A, E>,
    handler: EffectHandler<E, A>,
    defects: DefectHandler<S, A, E> = rethrowingDefectHandler(),
    scope: StoreScope = StoreScope.Main,
    observer: StoreObserver<S, A>? = null,
    start: A? = null,
): Store<S, A> =
    Store(
        initialState = initialState,
        reducer = reducer,
        handler = handler,
        defects = defects,
        resolved = scope.resolve(),
        observer = observer,
        start = start,
    )

/** The full-injection variant behind the public factory, for deterministic runtime tests. */
internal fun <S : Any, A, E> Store(
    initialState: S,
    reducer: Reducer<S, A, E>,
    handler: EffectHandler<E, A>,
    defects: DefectHandler<S, A, E>,
    confinedDispatcher: CoroutineDispatcher,
    effectDispatcher: CoroutineDispatcher,
    isOnConfinedThread: () -> Boolean,
    observer: StoreObserver<S, A>? = null,
    start: A? = null,
): Store<S, A> =
    Store(
        initialState = initialState,
        reducer = reducer,
        handler = handler,
        defects = defects,
        resolved =
            ResolvedScope(
                reduction = confinedDispatcher,
                effectDispatcher = effectDispatcher,
                isOnConfinedThread = isOnConfinedThread,
            ),
        observer = observer,
        start = start,
    )

@Suppress("UNCHECKED_CAST")
private fun <S : Any, A, E> Store(
    initialState: S,
    reducer: Reducer<S, A, E>,
    handler: EffectHandler<E, A>,
    defects: DefectHandler<S, A, E>,
    resolved: ResolvedScope,
    observer: StoreObserver<S, A>?,
    start: A?,
): Store<S, A> =
    Store(
        initialState = initialState,
        // Existential capture: this signature is where the three E parties are proven to
        // match; past the gate the effect values only flow between them, so erasure is sound.
        reducer = reducer,
        handler = handler as EffectHandler<Any?, A>,
        defects = defects as DefectHandler<S, A, Any?>,
        resolved = resolved,
        observer = observer,
        marker = Unit,
    ).also { store ->
        if (start != null) {
            store.send(start)
        }
    }

/**
 * A running feature — the full surface exported to UI on both platforms: observe [state],
 * call [send], react to [events], and [close] when the owner's lifetime ends. Construction
 * goes through the [Store] factory function, the gate where the effect type lives. Screens
 * usually observe a [ViewStore] built with [view] instead: rendered state only, sendable
 * actions only; [events] stay with the holder, which is what acts on them.
 *
 * Reduction is confined to the thread [StoreScope] selects — on [StoreScope.Main] a
 * UI event produces new state in the same runloop turn; only effects launch. A queue handles
 * re-entrant [send] instead of recursing into half-applied state. State-scoped effects are
 * cancelled when their owner stops resolving in the new state — the state class entered by
 * the requesting reduction for plain effects; for effects that came through a composition
 * operator, the child focus itself plus the child state class the child's reduction entered.
 *
 * The store owns its scope; UI never constructs one. Under [StoreScope.Inherited] that
 * scope is a child of the caller's, so the caller's cancellation closes the store as [close]
 * would. Effects always run on [Dispatchers.Default] — the dispatcher itself is deliberately
 * not pluggable. Closing is mandatory on iOS (`deinit`): a store with running effects is
 * referenced by its own scope, so ARC never collects it on its own. A closed store ignores
 * further sends — a late send racing the close (an in-flight tap after `deinit`) is benign,
 * never a crash and never a state change whose effects could not run; the observer hears of
 * it as a warning.
 *
 * A reducer that throws is a defect like a handler that lets an exception escape: both reach
 * the [DefectHandler], and under the logging policy the store skips that action, keeps its
 * state, and goes on reducing.
 */
@OptIn(ExperimentalAtomicApi::class)
public class Store<S : Any, A> internal constructor(
    initialState: S,
    private val reducer: Reducer<S, A, Any?>,
    private val handler: EffectHandler<Any?, A>,
    private val defects: DefectHandler<S, A, Any?>,
    resolved: ResolvedScope,
    private val observer: StoreObserver<S, A>?,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) {
    private val scope = CoroutineScope(resolved.reduction + SupervisorJob(resolved.parent))
    private val effectDispatcher = resolved.effectDispatcher
    private val isOnConfinedThread = resolved.isOnConfinedThread
    private val _stateFlow = MutableStateFlow(initialState)
    private val _events = MutableSharedFlow<A>(extraBufferCapacity = EVENT_BUFFER)

    /**
     * The reactive surface — `stateFlow` for Kotlin consumers, `stateSequence` in Swift,
     * where SKIE bridges it as a typed `AsyncSequence`.
     */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName(swiftName = "stateSequence")
    public val stateFlow: StateFlow<S> = _stateFlow.asStateFlow()

    /** The synchronous snapshot of the current state. */
    public val state: S
        get() = _stateFlow.value

    /**
     * The [Event]s the reducer emits, each published right after the state it followed, for
     * the holder to act on — navigate, hand to a parent store. One-shot: an event reaches
     * whoever collects at that moment, and one nobody collects is dropped and reported as
     * [StoreWarning.EventDropped]. Anything a screen must not miss belongs in [state].
     * Narrow to a screen's own event type with [events].
     */
    public val events: Flow<A> = _events

    private val queue = ArrayDeque<A>()
    private var reducing = false

    // Follow-ups are prepended and re-entrant sends appended, so the queue's head holds exactly
    // this many follow-ups — what a cut cycle drops without touching the sends behind them.
    private var queuedFollowUps = 0
    private val ownedJobs = mutableListOf<Running>()
    private val keyedJobs = mutableMapOf<EffectKey, Running>()
    private val runningEffects = AtomicInt(0)

    /**
     * A launched effect. [end] is written by whoever ends it — the sweep, a key claim, the
     * handler's catch — before the job completes, so the completion callback can report why.
     */
    private class Running(
        val owner: EffectOwner<Any>,
        val job: Job,
        val end: AtomicReference<EffectEnd?>,
    )

    /**
     * Effects launched and not yet complete: running handlers, ordered ones waiting their
     * turn, and cancelled ones until their job finishes. A diagnostic — primarily the
     * in-flight gate behind a test store's exhaustive finish.
     */
    public val activeEffects: Int
        get() = runningEffects.load()

    public fun send(action: A) {
        if (!scope.isActive) {
            observer?.onWarning(StoreWarning.SentAfterClose(action))
            return
        }
        if (isOnConfinedThread()) {
            dispatch(action)
        } else {
            scope.launch { dispatch(action) }
        }
    }

    /**
     * Callback observation of [stateFlow], invoked synchronously on the store's confined
     * thread (the main thread for [StoreScope.Main] stores) — the hop-free bridge for
     * callback-shaped consumers such as Combine publishers. The current value is delivered
     * first, though asynchronously (collection starts on the confined dispatcher) — except
     * under an inherited `Main.immediate`, where a call from the main thread has it delivered
     * before returning; read [state] for a snapshot that is synchronous either way. Rapid
     * successive reductions conflate, matching StateFlow. The observation ends at
     * [Subscription.cancel] or when the store closes.
     */
    public fun observe(onEach: (S) -> Unit): Subscription {
        val job =
            scope.launch {
                stateFlow.collect { state -> onEach(state) }
            }
        return Subscription { job.cancel() }
    }

    public fun close() {
        scope.cancel()
    }

    private fun dispatch(action: A) {
        // A closed store ignores sends instead of reducing into state whose effects can never
        // run — a late send (an in-flight tap racing a deinit close) is benign, not a crash.
        if (!scope.isActive) {
            return
        }
        queue.addLast(action)
        if (reducing) {
            return
        }
        reducing = true
        var followUpsThisDrain = 0
        try {
            while (queue.isNotEmpty()) {
                val next = queue.removeFirst()
                if (queuedFollowUps > 0) {
                    queuedFollowUps--
                }
                val previous = _stateFlow.value
                val reduced =
                    try {
                        reducer.reduce(previous, next)
                    } catch (error: Throwable) {
                        defects.onDefect(Defect.InReducer(previous, next, error))
                        continue
                    }
                // Observe before publishing: anyone reacting to the new state can already
                // rely on the observation (e.g. the recording log) being complete.
                observer?.onReduced(previous, next, reduced.state)
                _stateFlow.value = reduced.state
                if (next is Event) {
                    publish(next)
                }
                sweepOwnedJobs(reduced.state)
                reduced.effects.forEach { envelope -> launchEffect(reduced.state, envelope) }
                if (reduced.followUps.isEmpty()) {
                    continue
                }
                followUpsThisDrain += reduced.followUps.size
                if (followUpsThisDrain > MAX_FOLLOW_UPS_PER_DRAIN) {
                    // A follow-up that reaches an arm following up again never drains, and this
                    // loop is synchronous: the confined thread would freeze with nothing to see.
                    // The queued follow-ups go with the new ones; the sends behind them stay.
                    repeat(queuedFollowUps) { queue.removeFirst() }
                    queuedFollowUps = 0
                    followUpsThisDrain = 0
                    defects.onDefect(Defect.FollowUpCycle(reduced.state, next))
                    continue
                }
                // Follow-ups go to the front: they belong to this reduction, ahead of anything
                // a re-entrant send queued behind it.
                queue.addAll(0, reduced.followUps)
                queuedFollowUps += reduced.followUps.size
            }
        } finally {
            reducing = false
        }
    }

    // Delivery is to whoever collects right now: no collector, or one too far behind for the
    // buffer, and the event is gone with the warning as its only trace. Replay would be worse —
    // a screen recreated after a rotation would navigate again on an intent it already served.
    private fun publish(event: A) {
        val delivered = _events.subscriptionCount.value > 0 && _events.tryEmit(event)
        if (!delivered) {
            observer?.onWarning(StoreWarning.EventDropped(event))
        }
    }

    /**
     * Cancels every state-scoped effect whose owner no longer resolves in [state] — the
     * optic-granular half of scoped cancellation; the class-granular half rides the implicit
     * owner attached at launch. Runs on the confined thread only, once per reduced action;
     * completed jobs, keyed ones included, are pruned here rather than from completion
     * callbacks, which would race these collections.
     */
    private fun sweepOwnedJobs(state: S) {
        keyedJobs.values.removeAll { running -> running.job.isCompleted }
        ownedJobs.removeAll { owned ->
            when {
                owned.job.isCompleted -> {
                    true
                }

                owned.owner.resolvesIn(state) -> {
                    false
                }

                else -> {
                    owned.end.store(EffectEnd.CancelledByOwner)
                    owned.job.cancel()
                    true
                }
            }
        }
    }

    private fun launchEffect(
        current: S,
        envelope: EffectEnvelope<Any?>,
    ) {
        val owner = envelope.resolvedOwner(current::class)
        // An owner already failing in the state its own reduction produced (the child was
        // gone by the end of the combine chain) would be cancelled before running — skip the
        // launch entirely.
        if (envelope.scope == EffectScope.StateScoped && !owner.resolvesIn(current)) {
            observer?.onEffect(EffectEvent.Skipped(envelope.effect, envelope.key))
            return
        }
        val predecessor = envelope.key?.let(::claimKey)
        observer?.onEffect(EffectEvent.Launched(envelope.effect, envelope.scope, envelope.key, queued = predecessor != null))
        val end = AtomicReference<EffectEnd?>(null)
        runningEffects.addAndFetch(1)
        val job =
            scope.launch(effectDispatcher) {
                predecessor?.join()
                // A send after the handler returned or was cancelled has escaped structured
                // concurrency: it is delivered, and reported, because dropping it would hide the
                // leak behind a silently missing action.
                val finished = AtomicBoolean(false)
                val guardedSend: (A) -> Unit = { action ->
                    if (finished.load()) {
                        observer?.onWarning(StoreWarning.SentFromFinishedEffect(action, envelope.effect))
                    }
                    send(action)
                }
                try {
                    handler.handle(envelope.effect, guardedSend)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    end.store(EffectEnd.Defect)
                    defects.onDefect(Defect.InEffect(envelope.effect, error))
                } finally {
                    finished.store(true)
                }
            }
        val running = Running(owner, job, end)
        job.invokeOnCompletion { cause ->
            runningEffects.addAndFetch(-1)
            val recorded = end.load()
            // An unrecorded cancellation is the store closing, which reports nothing; every
            // other end was recorded by whoever caused it before the job completed.
            if (recorded == null && cause is CancellationException) {
                return@invokeOnCompletion
            }
            val reported = recorded ?: EffectEnd.Completed
            if (observer != null && scope.isActive) {
                scope.launch { observer.onEffect(EffectEvent.Ended(envelope.effect, envelope.key, reported)) }
            }
        }
        if (envelope.scope == EffectScope.StateScoped) {
            ownedJobs += running
        }
        envelope.key?.let { key -> keyedJobs[key] = running }
    }

    /**
     * Applies the key's policy to the effect holding it and returns what the new holder must
     * wait for: nothing under cancel-previous, where the running holder is cancelled; the
     * running holder under ordered, which is awaited, never cancelled — `join` returns on any
     * completion, so a predecessor that was cancelled or failed still lets the queue move on.
     */
    private fun claimKey(key: EffectKey): Job? {
        val running = keyedJobs.remove(key) ?: return null
        return when (key.policy) {
            KeyPolicy.CancelPrevious -> {
                running.end.store(EffectEnd.CancelledByKey)
                running.job.cancel()
                null
            }

            KeyPolicy.Ordered -> {
                running.job
            }
        }
    }

    // The owner ranges over the state of the composition level that created it; the operator
    // gate proved the type, so the runtime evaluates it erased. An envelope without an owner
    // gets the implicit class-granular one: cancel on leaving the state class entered by the
    // reduction that requested the effect.
    @Suppress("UNCHECKED_CAST")
    private fun EffectEnvelope<Any?>.resolvedOwner(entered: KClass<out S>): EffectOwner<Any> =
        (owner as EffectOwner<Any>?)
            ?: EffectOwner { state -> state::class == entered }
}

/** The events this store emits that [select] recognizes — a screen's own event type, typically. */
public fun <S : Any, A, VE : Any> Store<S, A>.events(select: (A) -> VE?): Flow<VE> = events.mapNotNull(select)

internal expect fun isMainThread(): Boolean

/**
 * Events buffered between a reduction and the collector's turn. A main-thread collector drains
 * between reductions; the buffer only has to absorb a burst of follow-ups from one drain.
 */
internal const val EVENT_BUFFER: Int = 64

/**
 * Follow-ups one drain of the queue may reduce before the store calls it a cycle. No
 * legitimate chain comes near it: follow-ups start a child or two, they do not fan out.
 */
internal const val MAX_FOLLOW_UPS_PER_DRAIN: Int = 1000
