package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Event
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/** A cancellable handle to a callback observation started with [Store.observe]. */
public fun interface Subscription {
    public fun cancel()
}

/**
 * The scope a store runs in: which thread reduces, and whose lifetime the store has. A typed
 * choice instead of a raw dispatcher or coroutine scope, so a feature cannot accidentally
 * observe UI state off the main thread, and so reduction is serialized whatever is supplied. Effects run
 * on [Dispatchers.Default] under every case but [Testing]. The first four cases pick the
 * thread and leave the lifetime to the store, which owns its scope until [Store.close];
 * [Inherited] takes both from a scope the caller already owns.
 *
 * [Main] is the default and the right choice for every screen store: reduction is confined to
 * the main thread and synchronous when sent from it, so a UI event produces new state in the
 * same runloop turn.
 *
 * [Background] serializes reduction on its own slice of the default worker pool and never
 * touches the main dispatcher — for app-scoped stores (session, feature flags) that no UI
 * observes directly; their state reaches screens through effects.
 *
 * [Dedicated] serializes reduction on a caller-supplied dispatcher defined outside this
 * library — for example one named single-thread executor maintained just for reducer stores,
 * which gives every store sharing it a total order and a recognizable name in stack traces.
 * The runtime serializes the supplied dispatcher itself (`limitedParallelism(1)`), so any
 * dispatcher is safe to pass; sharing one instance across stores is what creates the shared
 * thread. UI must not observe a [Dedicated] store directly.
 *
 * [Inherited] reduces on a caller-supplied scope's dispatcher and lives as long as the scope.
 * An Android screen store built in `Inherited(viewModelScope)` follows whatever scope the
 * ViewModel was defined with — its dispatcher, its exception handler, its name — and closes
 * when the ViewModel is cleared, with nothing left to close by hand. A main dispatcher keeps
 * [Main]'s same-turn reduction; any other is serialized as under [Dedicated]; a scope without
 * one reduces as under [Background]. The store still runs in a scope of its own, a child of
 * the inherited one, so [Store.close] ends the store alone, never the caller's scope, and
 * one store's escaped defect cannot cancel a sibling built in the same scope.
 *
 * [Testing] routes reduction AND effects onto one supplied dispatcher — typically a
 * coroutines-test dispatcher, so the test scheduler owns all time and a store becomes fully
 * deterministic. For tests only; production stores never share a dispatcher with their
 * effects.
 */
public sealed interface StoreScope {
    public data object Main : StoreScope

    public data object Background : StoreScope

    public data class Dedicated(
        public val dispatcher: CoroutineDispatcher,
    ) : StoreScope

    public data class Inherited(
        public val scope: CoroutineScope,
    ) : StoreScope

    public data class Testing(
        public val dispatcher: CoroutineDispatcher,
    ) : StoreScope
}

/**
 * A [StoreScope] resolved to what the runtime builds a store from. [reduction] is the
 * context the store's scope is made of: a serial dispatcher, plus whatever else an inherited
 * scope carried, minus its job. [parent] is that job, or null when the store's lifetime is
 * its own. [isOnConfinedThread] is the same-turn test behind [Store.send].
 */
internal class ResolvedScope(
    val reduction: CoroutineContext,
    val effectDispatcher: CoroutineDispatcher = Dispatchers.Default,
    val parent: Job? = null,
    val isOnConfinedThread: () -> Boolean = membershipTest(reduction),
)

internal fun StoreScope.resolve(): ResolvedScope =
    when (this) {
        StoreScope.Main -> {
            ResolvedScope(reduction = Dispatchers.Main)
        }

        StoreScope.Background -> {
            ResolvedScope(reduction = Dispatchers.Default.limitedParallelism(1))
        }

        is StoreScope.Dedicated -> {
            // Serialized here so any caller-supplied dispatcher is safe, parallel or not.
            ResolvedScope(reduction = dispatcher.limitedParallelism(1))
        }

        is StoreScope.Inherited -> {
            val inherited = scope.coroutineContext
            ResolvedScope(
                reduction = inherited.minusKey(Job) + serialized(inherited[ContinuationInterceptor]),
                parent = inherited[Job],
            )
        }

        is StoreScope.Testing -> {
            ResolvedScope(reduction = dispatcher, effectDispatcher = dispatcher)
        }
    }

// A main dispatcher is serial already and is kept as it is, so an inherited `immediate` stays
// immediate; a non-dispatcher interceptor is replaced, since nothing serial can be made of it.
private fun serialized(interceptor: ContinuationInterceptor?): CoroutineDispatcher =
    when (interceptor) {
        is MainCoroutineDispatcher -> interceptor
        is CoroutineDispatcher -> interceptor.limitedParallelism(1)
        else -> Dispatchers.Default.limitedParallelism(1)
    }

// Only a main dispatcher has a cheap membership test; anywhere else every send enqueues onto
// the serial dispatcher instead, giving up same-turn reduction, which only UI stores need.
private fun membershipTest(reduction: CoroutineContext): () -> Boolean =
    if (reduction[ContinuationInterceptor] is MainCoroutineDispatcher) {
        ::isMainThread
    } else {
        neverOnConfinedThread
    }

private val neverOnConfinedThread: () -> Boolean = { false }

/**
 * The store's single observation hook — observers observe, they never mutate. [onReduced] is
 * called on the store's confined thread in reduction order, once per reduced action,
 * effect-fed actions included. Keep it fast; it runs inside the reduction loop.
 *
 * The two other methods default to nothing, so a plain lambda observer keeps working.
 * [onEffect] follows each effect's life, also on the confined thread: a launch (or the skip
 * that took its place) lands during the reduction that requested it, an end after the actions
 * the effect fed — except that a store's close ends every effect at once and reports none of
 * them; the store is gone.
 * [onWarning] reports what the runtime accepts but a feature should look at, and may arrive
 * from any thread, since a send racing a close comes from the sender's.
 */
public fun interface StoreObserver<S, A> {
    public fun onReduced(
        previous: S,
        action: A,
        next: S,
    )

    public fun onEffect(event: EffectEvent) {}

    public fun onWarning(warning: StoreWarning<A>) {}
}

/**
 * One step in an effect's life. Effects are opaque here: the store's public type is
 * effect-free by design, and observers stringify what they log.
 */
public sealed interface EffectEvent {
    public val effect: Any?
    public val key: EffectKey?

    /** [queued] when an ordered key made the effect wait for the running holder first. */
    public data class Launched(
        override val effect: Any?,
        public val scope: EffectScope,
        override val key: EffectKey?,
        public val queued: Boolean,
    ) : EffectEvent

    public data class Ended(
        override val effect: Any?,
        override val key: EffectKey?,
        public val end: EffectEnd,
    ) : EffectEvent

    /**
     * Never launched: state-scoped, and its owner already failed in the state the requesting
     * reduction produced — a later reducer in the same `combine` removed the child that asked.
     * Reported during that reduction, in the launch's place.
     */
    public data class Skipped(
        override val effect: Any?,
        override val key: EffectKey?,
    ) : EffectEvent
}

/** How an effect ended. Cancellation by the store's close is never reported (see [StoreObserver]). */
public enum class EffectEnd {
    Completed,

    /** The handler let an exception escape; the store's [DefectHandler] saw it first. */
    Defect,

    /** State-scoped, and its owner stopped resolving in the new state. */
    CancelledByOwner,

    /** A cancel-previous key was launched again. */
    CancelledByKey,
}

/** Behaviour the runtime tolerates but a feature author should know about. */
public sealed interface StoreWarning<out A> {
    /** A send after [Store.close]: dropped, since state whose effects could never run must not change. */
    public data class SentAfterClose<out A>(
        public val action: A,
    ) : StoreWarning<A>

    /**
     * A handler's `send` was called after the handler returned or was cancelled — its work
     * escaped into a coroutine the store neither owns nor cancels. The action is still
     * reduced; the effect it came from is reported with it.
     */
    public data class SentFromFinishedEffect<out A>(
        public val action: A,
        public val effect: Any?,
    ) : StoreWarning<A>

    /**
     * An [Event] the reducer emitted found no collector, or one too far behind: dropped. The
     * store keeps no event for a screen that is not listening; state is what survives.
     */
    public data class EventDropped<out A>(
        public val event: A,
    ) : StoreWarning<A>
}

/**
 * Fans the store's single observation hook out to both observers — recording plus analytics
 * on one store, say. Both run in the order given, this observer first, under the single-hook
 * contract: observers observe, they never mutate, and both stay fast because reductions and
 * effect events reach them inside the reduction loop.
 */
public operator fun <S, A> StoreObserver<S, A>.plus(other: StoreObserver<S, A>): StoreObserver<S, A> {
    val first = this
    return object : StoreObserver<S, A> {
        override fun onReduced(
            previous: S,
            action: A,
            next: S,
        ) {
            first.onReduced(previous, action, next)
            other.onReduced(previous, action, next)
        }

        override fun onEffect(event: EffectEvent) {
            first.onEffect(event)
            other.onEffect(event)
        }

        override fun onWarning(warning: StoreWarning<A>) {
            first.onWarning(warning)
            other.onWarning(warning)
        }
    }
}

/**
 * What went wrong outside the domain. Expected failures are actions; a defect is an exception
 * the architecture says cannot happen: a handler that let one escape (handlers are total) or
 * a reducer that threw (reducers are pure).
 */
public sealed interface Defect<out S, out A, out E> {
    public val error: Throwable

    public data class InEffect<out E>(
        public val effect: E,
        override val error: Throwable,
    ) : Defect<Nothing, Nothing, E>

    /** The store skipped [action] and kept [state]; reduction continues with the next action. */
    public data class InReducer<out S, out A>(
        public val state: S,
        public val action: A,
        override val error: Throwable,
    ) : Defect<S, A, Nothing>

    /**
     * Reducing [action] asked for follow-ups when one drain of the queue had already reduced a
     * thousand of them — a follow-up cycle, which on the synchronous queue would never return.
     * The store kept [state], dropped those follow-ups and every one still queued, and went on
     * with the sends queued behind them. [error] is synthesized: no code threw.
     */
    public data class FollowUpCycle<out S, out A>(
        public val state: S,
        public val action: A,
    ) : Defect<S, A, Nothing> {
        override val error: Throwable = IllegalStateException("$MAX_FOLLOW_UPS_PER_DRAIN follow-up actions reduced in one drain")
    }
}

/**
 * Receives every [Defect] of a store. The default, [rethrowingDefectHandler], crashes at the
 * defect; a release build installs [loggingDefectHandler] instead. The runtime never reports
 * cancellation as a defect.
 */
public fun interface DefectHandler<in S, in A, in E> {
    public fun onDefect(defect: Defect<S, A, E>)
}

/** The debug-build policy: crash at the defect, wrapped so the message names the broken contract. */
public fun rethrowingDefectHandler(): DefectHandler<Any?, Any?, Any?> = DefectHandler { defect -> throw DefectException(defect) }

/**
 * The release policy: keep the app alive and log loudly. Each defect reaches [log] wrapped in
 * a [DefectException], so the line carries the broken contract with the original error as its
 * cause; the sink is injected because the logging framework belongs to the app.
 */
public fun loggingDefectHandler(log: (DefectException) -> Unit): DefectHandler<Any?, Any?, Any?> =
    DefectHandler { defect -> log(DefectException(defect)) }

public class DefectException(
    public val defect: Defect<*, *, *>,
) : RuntimeException(message(defect), defect.error)

private fun message(defect: Defect<*, *, *>): String =
    when (defect) {
        is Defect.InEffect -> {
            "Effect '${defect.effect}' escaped its handler. Handlers are total: catch expected failures and " +
                "send a typed failure action."
        }

        is Defect.InReducer -> {
            "Reducer threw on '${defect.action}' in state '${defect.state}'. Reducers are pure: the action " +
                "was skipped and the state kept."
        }

        is Defect.FollowUpCycle -> {
            "Reducing '${defect.action}' in state '${defect.state}' asked for follow-ups after " +
                "$MAX_FOLLOW_UPS_PER_DRAIN had already been reduced in one drain: a follow-up cycle. " +
                "They were dropped and the state kept."
        }
    }
