package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// A long-running observation: the docs' ObserveSession pattern — a handler collecting an
// infinite flow and sending one action per emission.
private sealed interface WatchState {
    data object Idle : WatchState

    data class Watching(
        val ticks: Int,
        val last: Int,
    ) : WatchState
}

private sealed interface WatchAction {
    data object Start : WatchAction

    data object Stop : WatchAction

    data class Tick(
        val value: Int,
    ) : WatchAction
}

private sealed interface WatchEffect {
    data object Observe : WatchEffect
}

private val watchReducer =
    Reducer<WatchState, WatchAction, WatchEffect> { state, action ->
        when (state) {
            WatchState.Idle -> {
                when (action) {
                    WatchAction.Start -> WatchState.Watching(0, 0).withEffect(WatchEffect.Observe)
                    WatchAction.Stop -> state.only()
                    is WatchAction.Tick -> state.only()
                }
            }

            is WatchState.Watching -> {
                when (action) {
                    WatchAction.Start -> state.only()
                    WatchAction.Stop -> WatchState.Idle.only()
                    is WatchAction.Tick -> state.copy(ticks = state.ticks + 1, last = action.value).only()
                }
            }
        }
    }

private val tickerHandler =
    EffectHandler<WatchEffect, WatchAction> { _, send ->
        flow {
            var value = 0
            while (true) {
                delay(100)
                emit(++value)
            }
        }.collect { tick -> send(WatchAction.Tick(tick)) }
    }

class ObservationEffectTest {
    @Test
    fun observation_emits_across_same_class_transitions_and_dies_on_state_exit() =
        runTest {
            val store =
                Store(
                    initialState = WatchState.Idle,
                    reducer = watchReducer,
                    handler = tickerHandler,
                    defects = DefectHandler { defect -> throw AssertionError("unexpected defect: $defect") },
                    confinedDispatcher = StandardTestDispatcher(testScheduler),
                    effectDispatcher = StandardTestDispatcher(testScheduler),
                    isOnConfinedThread = { true },
                )

            store.send(WatchAction.Start)
            advanceTimeBy(350)

            // Three emissions from one still-running observation: Watching -> Watching keeps the
            // same state class, so the state-scoped effect is NOT cancelled by new data arriving.
            assertEquals(WatchState.Watching(ticks = 3, last = 3), store.state)

            store.send(WatchAction.Stop)
            advanceUntilIdle()

            // Leaving Watching cancelled the collection: no further ticks, and cancellation was
            // not treated as a defect.
            assertEquals(WatchState.Idle, store.state)
        }
}
