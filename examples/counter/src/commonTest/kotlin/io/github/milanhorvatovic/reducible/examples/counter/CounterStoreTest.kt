package io.github.milanhorvatovic.reducible.examples.counter

import io.github.milanhorvatovic.reducible.test.testStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CounterStoreTest {
    @Test
    fun ticking_counts_once_per_tick_until_stopped() =
        runTest {
            val store = testStore(CounterState.Idle(), counterReducer, counterEffectHandler(tick = { delay(1_000) }))

            store.send(CounterAction.StartTicking)
            advanceTimeBy(2_500)
            store.send(CounterAction.StopTicking)
            advanceUntilIdle()

            store
                .expectAction(CounterAction.StartTicking, CounterState.Ticking(0))
                .expectAction(CounterAction.Ticked, CounterState.Ticking(1))
                .expectAction(CounterAction.Ticked, CounterState.Ticking(2))
                .expectAction(CounterAction.StopTicking, CounterState.Idle(2))
            // Leaving Ticking cancelled the third tick: nothing is in flight, nothing is unasserted.
            store.finish()
        }

    @Test
    fun restarting_after_a_stop_ticks_again_from_the_kept_count() =
        runTest {
            val store = testStore(CounterState.Idle(), counterReducer, counterEffectHandler(tick = { delay(1_000) }))

            store.send(CounterAction.StartTicking)
            advanceTimeBy(1_500)
            store.send(CounterAction.StopTicking)
            store.send(CounterAction.StartTicking)
            advanceTimeBy(1_500)
            store.send(CounterAction.StopTicking)
            advanceUntilIdle()

            store
                .expectAction(CounterAction.StartTicking, CounterState.Ticking(0))
                .expectAction(CounterAction.Ticked, CounterState.Ticking(1))
                .expectAction(CounterAction.StopTicking, CounterState.Idle(1))
                .expectAction(CounterAction.StartTicking, CounterState.Ticking(1))
                .expectAction(CounterAction.Ticked, CounterState.Ticking(2))
                .expectAction(CounterAction.StopTicking, CounterState.Idle(2))
            store.finish()
        }
}
