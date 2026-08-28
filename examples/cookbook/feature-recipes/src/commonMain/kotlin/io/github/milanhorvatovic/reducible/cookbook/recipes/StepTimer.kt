package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.serialization.Serializable

/**
 * One step of a recipe with its cooking timer, a child feature addressed by step index. The
 * timer is a phase of the step: the countdown effect belongs to [Running] and ends the moment
 * the step leaves it, so pause, reset, and completion cancel it without any timer handle.
 */
@Serializable
public sealed interface StepState {
    public val index: Int
    public val step: Step

    @Serializable
    public data class Idle(
        override val index: Int,
        override val step: Step,
    ) : StepState

    @Serializable
    public data class Running(
        override val index: Int,
        override val step: Step,
        public val remainingSeconds: Int,
    ) : StepState

    @Serializable
    public data class Paused(
        override val index: Int,
        override val step: Step,
        public val remainingSeconds: Int,
    ) : StepState

    @Serializable
    public data class Done(
        override val index: Int,
        override val step: Step,
    ) : StepState
}

public sealed interface StepAction {
    public sealed interface Ui : StepAction

    public data object Start : Ui

    public data object Pause : Ui

    public data object Reset : Ui

    public data object Ticked : StepAction
}

public sealed interface StepEffect {
    /** Ticks once a second for as long as the step stays [StepState.Running]. */
    public data object Countdown : StepEffect
}

public val stepReducer: Reducer<StepState, StepAction, StepEffect> =
    Reducer { state, action ->
        when (state) {
            is StepState.Idle -> {
                when (action) {
                    StepAction.Start -> state.started()
                    StepAction.Pause -> state.only()
                    StepAction.Reset -> state.only()
                    StepAction.Ticked -> state.only()
                }
            }

            is StepState.Running -> {
                when (action) {
                    StepAction.Ticked -> {
                        if (state.remainingSeconds <= 1) {
                            StepState.Done(state.index, state.step).only()
                        } else {
                            state.copy(remainingSeconds = state.remainingSeconds - 1).only()
                        }
                    }

                    StepAction.Pause -> {
                        StepState.Paused(state.index, state.step, state.remainingSeconds).only()
                    }

                    StepAction.Reset -> {
                        StepState.Idle(state.index, state.step).only()
                    }

                    StepAction.Start -> {
                        state.only()
                    }
                }
            }

            is StepState.Paused -> {
                when (action) {
                    StepAction.Start -> {
                        StepState.Running(state.index, state.step, state.remainingSeconds).withEffect(StepEffect.Countdown)
                    }

                    StepAction.Reset -> {
                        StepState.Idle(state.index, state.step).only()
                    }

                    StepAction.Pause -> {
                        state.only()
                    }

                    StepAction.Ticked -> {
                        state.only()
                    }
                }
            }

            is StepState.Done -> {
                when (action) {
                    StepAction.Start -> state.started()
                    StepAction.Reset -> StepState.Idle(state.index, state.step).only()
                    StepAction.Pause -> state.only()
                    StepAction.Ticked -> state.only()
                }
            }
        }
    }

private fun StepState.started() =
    step.timerSeconds
        ?.let { seconds -> StepState.Running(index, step, seconds).withEffect(StepEffect.Countdown) }
        ?: only()

/** A step restored mid-run has no effect ticking it any more, so it comes back paused where it stopped. */
internal fun StepState.interrupted(): StepState =
    when (this) {
        is StepState.Running -> StepState.Paused(index, step, remainingSeconds)
        is StepState.Idle -> this
        is StepState.Paused -> this
        is StepState.Done -> this
    }

/** [tick] is the one-second suspension, supplied by the wiring since features carry no coroutines. */
public fun stepEffectHandler(tick: suspend () -> Unit): EffectHandler<StepEffect, StepAction> =
    EffectHandler { effect, send ->
        when (effect) {
            StepEffect.Countdown -> {
                while (true) {
                    tick()
                    send(StepAction.Ticked)
                }
            }
        }
    }
