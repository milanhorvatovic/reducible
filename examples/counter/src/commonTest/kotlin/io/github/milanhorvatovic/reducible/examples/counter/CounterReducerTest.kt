package io.github.milanhorvatovic.reducible.examples.counter

import io.github.milanhorvatovic.reducible.test.given
import kotlin.test.Test

class CounterReducerTest {
    @Test
    fun increment_and_decrement_keep_the_phase_and_request_nothing() {
        counterReducer
            .given(CounterState.Idle(1))
            .on(CounterAction.Increment)
            .expect(CounterState.Idle(2))
            .expectNoEffects()
            .andOn(CounterAction.Decrement)
            .expect(CounterState.Idle(1))
            .expectNoEffects()

        counterReducer
            .given(CounterState.Ticking(5))
            .on(CounterAction.Increment)
            .expect(CounterState.Ticking(6))
            .expectNoEffects()
    }

    @Test
    fun starting_enters_ticking_and_requests_the_first_tick() {
        counterReducer
            .given(CounterState.Idle(3))
            .on(CounterAction.StartTicking)
            .expect(CounterState.Ticking(3))
            .expectEffects(CounterEffect.Tick)
    }

    @Test
    fun a_tick_while_ticking_counts_and_requests_the_next() {
        counterReducer
            .given(CounterState.Ticking(3))
            .on(CounterAction.Ticked)
            .expect(CounterState.Ticking(4))
            .expectEffects(CounterEffect.Tick)
    }

    @Test
    fun stopping_returns_to_idle_and_a_late_tick_is_ignored() {
        counterReducer
            .given(CounterState.Ticking(2))
            .on(CounterAction.StopTicking)
            .expect(CounterState.Idle(2))
            .expectNoEffects()
            .andOn(CounterAction.Ticked)
            .expect(CounterState.Idle(2))
            .expectNoEffects()
    }

    @Test
    fun the_view_hides_the_phase_behind_a_flag() {
        counterReducer
            .given(CounterState.Idle(7))
            .on(CounterAction.StartTicking)
            .expectState { state -> counterViewState(state) == CounterViewState(count = 7, ticking = true) }
    }
}
