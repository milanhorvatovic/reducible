package io.github.milanhorvatovic.reducible

/**
 * The unit of architecture: a pure function from the current state and an action to the next
 * state plus the effects to run. No dependencies, no coroutines, no lifecycle.
 */
public fun interface Reducer<S, A, out E> {
    public fun reduce(
        state: S,
        action: A,
    ): Reduced<S, A, E>
}

/**
 * The result of one reduction: the next state, the effects it requests, and the [followUps]
 * it asks the store to reduce next — immediately after this reduction, in order, before
 * anything sent from elsewhere — the way a parent starts a child it has just created.
 */
public data class Reduced<out S, out A, out E>(
    public val state: S,
    public val effects: List<EffectEnvelope<E>> = emptyList(),
    public val followUps: List<A> = emptyList(),
)

// The helpers leave the action type as Nothing — covariance widens it to whatever the reducer
// returns — so a reduction without follow-ups never has to name it, and andSend introduces it.

/** The state transition requests no effects. */
public fun <S, E> S.only(): Reduced<S, Nothing, E> = Reduced(this)

public fun <S, E> S.withEffect(
    effect: E,
    scope: EffectScope = EffectScope.StateScoped,
    key: EffectKey? = null,
): Reduced<S, Nothing, E> = Reduced(this, listOf(EffectEnvelope(effect, scope, key)))

/** All effects are [EffectScope.StateScoped]; use [withEffect] per effect for mixed scopes. */
public fun <S, E> S.withEffects(vararg effects: E): Reduced<S, Nothing, E> =
    Reduced(this, effects.map { effect -> EffectEnvelope(effect, EffectScope.StateScoped) })

/** The store reduces [actions] right after this reduction, in order. */
public fun <S, A> S.andSend(vararg actions: A): Reduced<S, A, Nothing> = Reduced(this, followUps = actions.toList())

/** Adds [actions] for the store to reduce right after this reduction, in order. */
public fun <S, A, E> Reduced<S, A, E>.andSend(vararg actions: A): Reduced<S, A, E> = copy(followUps = followUps + actions)
