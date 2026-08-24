package io.github.milanhorvatovic.reducible

/**
 * How long an effect may run relative to the state that requested it.
 *
 * [StateScoped] is the default: the runtime cancels the effect when the store leaves the state
 * class that was entered by the reduction which requested it — and an effect that came through
 * a composition operator is scoped tighter, to the child focus that requested it (see
 * [EffectOwner]). [Free] effects run to completion regardless of state transitions (until the
 * store closes).
 */
public enum class EffectScope { StateScoped, Free }

/**
 * Identity for keyed effects. Two effects share a key when they must not run independently;
 * the key's [policy] says how the runtime arbitrates between a new request and the running
 * holder of the key. Features define keys as objects or data classes
 * (`data object SearchDebounce : EffectKey`); keys are compared per store, by equality.
 */
public interface EffectKey {
    /** [KeyPolicy.CancelPrevious] unless the key overrides it. */
    public val policy: KeyPolicy
        get() = KeyPolicy.CancelPrevious
}

/** What launching a keyed effect does to the effect currently holding the same key. */
public enum class KeyPolicy {
    /**
     * Cancels the running holder first, so only the latest request completes — which, with a
     * leading suspension in the handler, is debounce.
     */
    CancelPrevious,

    /**
     * Queues behind the running holder: effects under the key run one at a time in request
     * order, and a later request never cancels one already in flight — for writes that must
     * land completely and in order.
     */
    Ordered,
}

/**
 * Ownership of a state-scoped effect: after every reduction the runtime cancels the effect
 * once [resolvesIn] returns false for the store's new state (and never launches one whose
 * owner already fails). Composition operators create and wrap owners — each ranges over the
 * state type of the level that created it, and every nesting level re-scopes the inner owner
 * rather than passing it through, which is what makes the star-projected storage in
 * [EffectEnvelope] sound. Feature code never constructs one; an envelope without an owner
 * falls back to the runtime's class-granular scoping.
 */
public fun interface EffectOwner<in S> {
    public fun resolvesIn(state: S): Boolean
}

/**
 * An effect value paired with its cancellation scope, optional [EffectKey], and — when it
 * came through a composition operator — the [EffectOwner] tying its lifetime to the child
 * focus that requested it and to the state class that child entered.
 */
public data class EffectEnvelope<out E>(
    public val effect: E,
    public val scope: EffectScope = EffectScope.StateScoped,
    public val key: EffectKey? = null,
    public val owner: EffectOwner<*>? = null,
)

/**
 * Executes effect values. Handlers are total: expected failures are caught here and translated
 * into typed failure actions; an exception escaping a handler is a defect, not a domain event.
 * The runtime treats [kotlin.coroutines.cancellation.CancellationException] as cancellation,
 * never failure.
 *
 * [send] is safe to call from any thread; the store re-dispatches to the main thread.
 */
public fun interface EffectHandler<in E, A> {
    public suspend fun handle(
        effect: E,
        send: (A) -> Unit,
    )
}
