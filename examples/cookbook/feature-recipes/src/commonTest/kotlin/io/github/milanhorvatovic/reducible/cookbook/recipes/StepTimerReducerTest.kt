package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.test.given
import kotlin.test.Test
import kotlin.test.assertEquals

private val simmer = Step("Simmer", timerSeconds = 3)
private val stir = Step("Stir")

class StepTimerReducerTest {
    @Test
    fun the_countdown_is_a_phase_that_ends_itself_at_zero() {
        stepReducer
            .given(StepState.Idle(1, simmer))
            .on(StepAction.Start)
            .expect(StepState.Running(1, simmer, remainingSeconds = 3))
            .expectEffects(StepEffect.Countdown)
            .andOn(StepAction.Ticked)
            .expect(StepState.Running(1, simmer, remainingSeconds = 2))
            .expectNoEffects()
            .andOn(StepAction.Ticked)
            .expect(StepState.Running(1, simmer, remainingSeconds = 1))
            .expectNoEffects()
            .andOn(StepAction.Ticked)
            .expect(StepState.Done(1, simmer))
            .expectNoEffects()
    }

    @Test
    fun pause_and_resume_move_between_phases_and_only_running_requests_the_countdown() {
        stepReducer
            .given(StepState.Running(1, simmer, remainingSeconds = 2))
            .on(StepAction.Pause)
            .expect(StepState.Paused(1, simmer, remainingSeconds = 2))
            .expectNoEffects()
            .andOn(StepAction.Ticked)
            .expect(StepState.Paused(1, simmer, remainingSeconds = 2))
            .expectNoEffects()
            .andOn(StepAction.Start)
            .expect(StepState.Running(1, simmer, remainingSeconds = 2))
            .expectEffects(StepEffect.Countdown)
    }

    @Test
    fun reset_returns_to_idle_from_any_phase_and_done_can_start_over() {
        stepReducer
            .given(StepState.Paused(1, simmer, remainingSeconds = 2))
            .on(StepAction.Reset)
            .expect(StepState.Idle(1, simmer))
            .expectNoEffects()

        stepReducer
            .given(StepState.Done(1, simmer))
            .on(StepAction.Start)
            .expect(StepState.Running(1, simmer, remainingSeconds = 3))
            .expectEffects(StepEffect.Countdown)
    }

    @Test
    fun a_step_without_a_timer_ignores_start() {
        stepReducer
            .given(StepState.Idle(0, stir))
            .on(StepAction.Start)
            .expect(StepState.Idle(0, stir))
            .expectNoEffects()
    }

    @Test
    fun a_step_restored_mid_run_comes_back_paused() {
        assertEquals(StepState.Paused(1, simmer, remainingSeconds = 2), StepState.Running(1, simmer, remainingSeconds = 2).interrupted())
        assertEquals(StepState.Done(1, simmer), StepState.Done(1, simmer).interrupted())
    }
}
