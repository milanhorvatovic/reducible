package io.github.milanhorvatovic.reducible.test

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private sealed interface PingState {
    data object Idle : PingState

    data class Ponged(
        val count: Int,
    ) : PingState
}

private sealed interface PingAction {
    data object Ping : PingAction

    data object Pong : PingAction
}

private sealed interface PingEffect {
    data object Bounce : PingEffect
}

private val pingReducer =
    Reducer<PingState, PingAction, PingEffect> { state, action ->
        when (action) {
            PingAction.Ping -> {
                state.withEffect(PingEffect.Bounce)
            }

            PingAction.Pong -> {
                when (state) {
                    PingState.Idle -> PingState.Ponged(1).only()
                    is PingState.Ponged -> state.copy(count = state.count + 1).only()
                }
            }
        }
    }

/** Like [pingReducer], but the bounce is free: it outlives the state change, as an observation does. */
private val observingReducer =
    Reducer<PingState, PingAction, PingEffect> { state, action ->
        when (action) {
            PingAction.Ping -> state.withEffect(PingEffect.Bounce, scope = EffectScope.Free)
            PingAction.Pong -> pingReducer.reduce(state, action)
        }
    }

private val bouncingHandler =
    EffectHandler<PingEffect, PingAction> { _, send ->
        delay(50)
        send(PingAction.Pong)
    }

/** Pongs every 50 ms until cancelled — the shape of an observation or a ticking timer. */
private val tickingHandler =
    EffectHandler<PingEffect, PingAction> { _, send ->
        while (true) {
            delay(50)
            send(PingAction.Pong)
        }
    }

class TestStoreTest {
    @Test
    fun asserts_sent_and_effect_fed_actions_in_reduction_order() =
        runTest {
            val store = testStore(PingState.Idle, pingReducer, bouncingHandler)

            store.send(PingAction.Ping)
            advanceUntilIdle()

            store
                .expectAction(PingAction.Ping)
                .expectAction(PingAction.Pong)
                .expectState(PingState.Ponged(1))
            store.finish()
        }

    @Test
    fun expectAction_with_resulting_state_asserts_both() =
        runTest {
            val store = testStore(PingState.Idle, pingReducer, bouncingHandler)

            store.send(PingAction.Ping)
            advanceUntilIdle()

            store
                .expectAction(PingAction.Ping, resulting = PingState.Idle)
                .expectAction(PingAction.Pong, resulting = PingState.Ponged(1))
            store.finish()
        }

    @Test
    fun mismatched_resulting_state_names_the_state_not_the_action() =
        runTest {
            val store = testStore(PingState.Idle, pingReducer, bouncingHandler)

            store.send(PingAction.Ping)
            advanceUntilIdle()

            val failure =
                assertFailsWith<AssertionError> {
                    store.expectAction(PingAction.Ping, resulting = PingState.Ponged(9))
                }
            assertTrue("resulting state" in failure.message.orEmpty())
            store.close()
        }

    @Test
    fun finish_fails_while_an_effect_is_still_in_flight() =
        runTest {
            val store = testStore(PingState.Idle, pingReducer, bouncingHandler)

            store.send(PingAction.Ping)
            runCurrent() // reduce Ping and launch Bounce, whose delay is still pending

            store.expectAction(PingAction.Ping)
            val failure = assertFailsWith<AssertionError> { store.finish() }
            assertTrue("in flight" in failure.message.orEmpty())
        }

    @Test
    fun finish_can_cancel_an_effect_that_never_completes_on_its_own() =
        runTest {
            val store = testStore(PingState.Idle, observingReducer, tickingHandler)

            store.send(PingAction.Ping)
            advanceTimeBy(120)

            store
                .expectAction(PingAction.Ping)
                .expectAction(PingAction.Pong)
                .expectAction(PingAction.Pong)
                .expectState(PingState.Ponged(2))
            store.finish(cancelInFlightEffects = true)
        }

    @Test
    fun cancelling_in_flight_effects_still_fails_on_unasserted_actions() =
        runTest {
            val store = testStore(PingState.Idle, observingReducer, tickingHandler)

            store.send(PingAction.Ping)
            advanceTimeBy(60)

            val failure = assertFailsWith<AssertionError> { store.finish(cancelInFlightEffects = true) }
            assertTrue("unasserted" in failure.message.orEmpty())
        }

    @Test
    fun wrong_expectation_names_both_actions() =
        runTest {
            val store = testStore(PingState.Idle, pingReducer, bouncingHandler)

            store.send(PingAction.Ping)
            advanceUntilIdle()

            val failure =
                assertFailsWith<AssertionError> {
                    store.expectAction(PingAction.Pong)
                }
            assertTrue("Ping" in failure.message.orEmpty())
            store.close()
        }

    @Test
    fun finish_fails_on_unasserted_actions() =
        runTest {
            val store = testStore(PingState.Idle, pingReducer, bouncingHandler)

            store.send(PingAction.Ping)
            advanceUntilIdle()

            val failure = assertFailsWith<AssertionError> { store.finish() }
            assertTrue("unasserted" in failure.message.orEmpty())
        }

    @Test
    fun expecting_beyond_the_log_fails() =
        runTest {
            val store = testStore(PingState.Idle, pingReducer, bouncingHandler)

            assertFailsWith<AssertionError> { store.expectAction(PingAction.Ping) }
            store.close()
        }
}
