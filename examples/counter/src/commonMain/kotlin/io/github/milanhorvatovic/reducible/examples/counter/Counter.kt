package io.github.milanhorvatovic.reducible.examples.counter

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect

/**
 * The counter's state as two phases. The phase is what a state-scoped effect is tied to: the
 * tick requested on entering [Ticking] is cancelled by the runtime the moment the state
 * becomes [Idle], so stopping needs no bookkeeping in the reducer or the handler.
 */
public sealed interface CounterState {
    public val count: Int

    public data class Idle(
        override val count: Int = 0,
    ) : CounterState

    public data class Ticking(
        override val count: Int,
    ) : CounterState
}

public sealed interface CounterAction {
    /** What a screen may send; the store's view narrows to this subset. */
    public sealed interface Ui : CounterAction

    public data object Increment : Ui

    public data object Decrement : Ui

    public data object StartTicking : Ui

    public data object StopTicking : Ui

    /** Fed back by the tick effect, never sent by a screen. */
    public data object Ticked : CounterAction
}

public sealed interface CounterEffect {
    /** Waits one tick, then sends [CounterAction.Ticked]. */
    public data object Tick : CounterEffect
}

public val counterReducer: Reducer<CounterState, CounterAction, CounterEffect> =
    Reducer { state, action ->
        when (action) {
            CounterAction.Increment -> {
                state.withCount(state.count + 1).only()
            }

            CounterAction.Decrement -> {
                state.withCount(state.count - 1).only()
            }

            CounterAction.StartTicking -> {
                when (state) {
                    is CounterState.Idle -> CounterState.Ticking(state.count).withEffect(CounterEffect.Tick)
                    is CounterState.Ticking -> state.only()
                }
            }

            CounterAction.StopTicking -> {
                // Leaving Ticking is what cancels the tick in flight.
                when (state) {
                    is CounterState.Ticking -> CounterState.Idle(state.count).only()
                    is CounterState.Idle -> state.only()
                }
            }

            CounterAction.Ticked -> {
                when (state) {
                    is CounterState.Ticking -> state.copy(count = state.count + 1).withEffect(CounterEffect.Tick)

                    // A tick that raced the stop: the effect was cancelled, but the action it had
                    // already sent still arrives, and the reducer is where it is ignored.
                    is CounterState.Idle -> state.only()
                }
            }
        }
    }

private fun CounterState.withCount(count: Int): CounterState =
    when (this) {
        is CounterState.Idle -> copy(count = count)
        is CounterState.Ticking -> copy(count = count)
    }

/** [tick] is the one suspension the feature needs, injected so tests own time. */
public fun counterEffectHandler(tick: suspend () -> Unit): EffectHandler<CounterEffect, CounterAction> =
    EffectHandler { effect, send ->
        when (effect) {
            CounterEffect.Tick -> {
                tick()
                send(CounterAction.Ticked)
            }
        }
    }
