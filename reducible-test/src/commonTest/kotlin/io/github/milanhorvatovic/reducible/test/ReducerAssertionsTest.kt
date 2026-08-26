package io.github.milanhorvatovic.reducible.test

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private sealed interface State {
    data object Off : State

    data class On(
        val level: Int,
    ) : State
}

private sealed interface Action {
    data object Toggle : Action

    data object Brighten : Action
}

private sealed interface Effect {
    data object Hum : Effect
}

private val reducer =
    Reducer<State, Action, Effect> { state, action ->
        when (state) {
            State.Off -> {
                when (action) {
                    Action.Toggle -> State.On(1).withEffect(Effect.Hum, EffectScope.Free)
                    Action.Brighten -> state.only()
                }
            }

            is State.On -> {
                when (action) {
                    Action.Toggle -> State.Off.only()
                    Action.Brighten -> state.copy(level = state.level + 1).only()
                }
            }
        }
    }

class ReducerAssertionsTest {
    @Test
    fun passing_scenario_chains_through_transitions() {
        reducer
            .given(State.Off)
            .on(Action.Toggle)
            .expect(State.On(1))
            .expectEffects(Effect.Hum)
            .andOn(Action.Brighten)
            .expect(State.On(2))
            .expectNoEffects()
            .andOn(Action.Toggle)
            .expect(State.Off)
    }

    @Test
    fun expectEnvelopes_checks_scope() {
        reducer
            .given(State.Off)
            .on(Action.Toggle)
            .expectEnvelopes(EffectEnvelope(Effect.Hum, EffectScope.Free))

        val failure =
            assertFailsWith<AssertionError> {
                reducer
                    .given(State.Off)
                    .on(Action.Toggle)
                    .expectEnvelopes(EffectEnvelope(Effect.Hum, EffectScope.StateScoped))
            }
        assertTrue("StateScoped" in failure.message.orEmpty())
    }

    @Test
    fun state_mismatch_names_both_states() {
        val failure =
            assertFailsWith<AssertionError> {
                reducer.given(State.Off).on(Action.Toggle).expect(State.Off)
            }
        assertTrue("Off" in failure.message.orEmpty())
        assertTrue("On" in failure.message.orEmpty())
    }

    @Test
    fun unexpected_effects_fail_expectNoEffects() {
        assertFailsWith<AssertionError> {
            reducer.given(State.Off).on(Action.Toggle).expectNoEffects()
        }
    }

    @Test
    fun expectState_supports_predicate_assertions() {
        reducer
            .given(State.On(3))
            .on(Action.Brighten)
            .expectState { state -> state is State.On && state.level == 4 }
    }
}
