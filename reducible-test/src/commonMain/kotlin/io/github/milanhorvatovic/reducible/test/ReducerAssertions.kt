package io.github.milanhorvatovic.reducible.test

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.Reduced
import io.github.milanhorvatovic.reducible.Reducer

/**
 * Entry point of the reducer assertion DSL — pure function assertions, no coroutine machinery:
 *
 * ```
 * reducer.given(Loading)
 *     .on(Loaded(cart)).expect(Content(cart)).expectNoEffects()
 *     .andOn(Submit).expect(Submitting(cart)).expectEffects(SaveCart)
 * ```
 */
public fun <S, A, E> Reducer<S, A, E>.given(state: S): ReducerScenario<S, A, E> = ReducerScenario(this, state)

public class ReducerScenario<S, A, E> internal constructor(
    private val reducer: Reducer<S, A, E>,
    private val state: S,
) {
    public fun on(action: A): ReducerVerdict<S, A, E> = ReducerVerdict(reducer, reducer.reduce(state, action))
}

public class ReducerVerdict<S, A, E> internal constructor(
    private val reducer: Reducer<S, A, E>,
    private val reduced: Reduced<S, A, E>,
) {
    public fun expect(state: S): ReducerVerdict<S, A, E> =
        apply {
            if (reduced.state != state) {
                throw AssertionError("Expected state:\n  $state\nbut reduction produced:\n  ${reduced.state}")
            }
        }

    public fun expectState(assertion: (S) -> Boolean): ReducerVerdict<S, A, E> =
        apply {
            if (!assertion(reduced.state)) {
                throw AssertionError("State did not satisfy the assertion:\n  ${reduced.state}")
            }
        }

    /** Asserts the requested effects in order, ignoring their scopes. */
    public fun expectEffects(vararg effects: E): ReducerVerdict<S, A, E> =
        apply {
            val actual = reduced.effects.map { envelope -> envelope.effect }
            if (actual != effects.toList()) {
                throw AssertionError("Expected effects:\n  ${effects.toList()}\nbut reduction requested:\n  $actual")
            }
        }

    /** Asserts the requested effects in order, including their scopes. */
    public fun expectEnvelopes(vararg envelopes: EffectEnvelope<E>): ReducerVerdict<S, A, E> =
        apply {
            if (reduced.effects != envelopes.toList()) {
                throw AssertionError(
                    "Expected envelopes:\n  ${envelopes.toList()}\nbut reduction requested:\n  ${reduced.effects}",
                )
            }
        }

    public fun expectNoEffects(): ReducerVerdict<S, A, E> =
        apply {
            if (reduced.effects.isNotEmpty()) {
                throw AssertionError("Expected no effects but reduction requested:\n  ${reduced.effects}")
            }
        }

    /** Asserts the follow-up actions the reduction asks the store to reduce next, in order — none when called bare. */
    public fun expectFollowUps(vararg actions: A): ReducerVerdict<S, A, E> =
        apply {
            if (reduced.followUps != actions.toList()) {
                throw AssertionError("Expected follow-ups:\n  ${actions.toList()}\nbut reduction asked for:\n  ${reduced.followUps}")
            }
        }

    /** Continues the scenario from the produced state — a multi-step transition assertion. */
    public fun andOn(action: A): ReducerVerdict<S, A, E> = ReducerVerdict(reducer, reducer.reduce(reduced.state, action))
}
