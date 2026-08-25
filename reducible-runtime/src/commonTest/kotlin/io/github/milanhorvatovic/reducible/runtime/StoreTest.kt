@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private sealed interface TestState {
    data object Idle : TestState

    data object Loading : TestState

    data class Content(
        val value: String,
    ) : TestState
}

private sealed interface TestAction {
    data object Start : TestAction

    data object Cancel : TestAction

    data class Loaded(
        val value: String,
    ) : TestAction
}

private sealed interface TestEffect {
    data object Load : TestEffect
}

private fun reducer(loadScope: EffectScope): Reducer<TestState, TestAction, TestEffect> =
    Reducer { state, action ->
        when (state) {
            TestState.Idle -> {
                when (action) {
                    TestAction.Start -> TestState.Loading.withEffect(TestEffect.Load, loadScope)
                    TestAction.Cancel -> state.only()
                    is TestAction.Loaded -> TestState.Content(action.value).only()
                }
            }

            TestState.Loading -> {
                when (action) {
                    TestAction.Start -> state.only()
                    TestAction.Cancel -> TestState.Idle.only()
                    is TestAction.Loaded -> TestState.Content(action.value).only()
                }
            }

            is TestState.Content -> {
                state.only()
            }
        }
    }

class StoreTest {
    private class RecordingDefects : DefectHandler<TestState, TestAction, TestEffect> {
        val defects = mutableListOf<Pair<TestEffect, Throwable>>()

        override fun onDefect(defect: Defect<TestState, TestAction, TestEffect>) {
            when (defect) {
                is Defect.InEffect -> defects += defect.effect to defect.error
                is Defect.InReducer -> throw AssertionError("unexpected reducer defect: ${defect.error}")
                is Defect.FollowUpCycle -> throw AssertionError("unexpected follow-up cycle after ${defect.action}")
            }
        }
    }

    private val recordingDefects = RecordingDefects()

    private fun TestScope.store(
        reducer: Reducer<TestState, TestAction, TestEffect> = reducer(EffectScope.StateScoped),
        handler: EffectHandler<TestEffect, TestAction> = EffectHandler { _, _ -> },
        effectDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        isOnConfinedThread: () -> Boolean = { true },
    ): Store<TestState, TestAction> =
        Store(
            initialState = TestState.Idle,
            reducer = reducer,
            handler = handler,
            defects = recordingDefects,
            confinedDispatcher = StandardTestDispatcher(testScheduler),
            effectDispatcher = effectDispatcher,
            isOnConfinedThread = isOnConfinedThread,
        )

    @Test
    fun reduction_is_synchronous_on_the_calling_thread() =
        runTest {
            val store = store()

            store.send(TestAction.Start)

            assertEquals(TestState.Loading, store.state)
        }

    @Test
    fun reentrant_send_is_queued_not_recursed() =
        runTest {
            // Unconfined effect dispatcher makes the handler send synchronously inside the drain
            // loop — the classic dispatch-during-dispatch case.
            val store =
                store(
                    handler = EffectHandler { _, send -> send(TestAction.Loaded("inline")) },
                    effectDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

            store.send(TestAction.Start)

            assertEquals(TestState.Content("inline"), store.state)
        }

    @Test
    fun state_scoped_effect_is_cancelled_when_state_class_is_left() =
        runTest {
            val store =
                store(
                    handler =
                        EffectHandler { _, send ->
                            delay(1_000)
                            send(TestAction.Loaded("late"))
                        },
                )

            store.send(TestAction.Start)
            store.send(TestAction.Cancel)
            advanceUntilIdle()

            assertEquals(TestState.Idle, store.state)
        }

    @Test
    fun free_effect_survives_the_state_transition() =
        runTest {
            val store =
                store(
                    reducer = reducer(EffectScope.Free),
                    handler =
                        EffectHandler { _, send ->
                            delay(1_000)
                            send(TestAction.Loaded("late"))
                        },
                )

            store.send(TestAction.Start)
            store.send(TestAction.Cancel)
            advanceUntilIdle()

            assertEquals(TestState.Content("late"), store.state)
        }

    @Test
    fun escaped_exception_reaches_the_defect_handler_with_its_effect() =
        runTest {
            val store =
                store(
                    handler = EffectHandler { _, _ -> error("handler bug") },
                )

            store.send(TestAction.Start)
            advanceUntilIdle()

            assertEquals(1, recordingDefects.defects.size)
            val (effect, error) = recordingDefects.defects.single()
            assertEquals(TestEffect.Load, effect)
            assertEquals("handler bug", error.message)
            assertEquals(TestState.Loading, store.state)
        }

    @Test
    fun cancellation_is_never_a_defect() =
        runTest {
            val store =
                store(
                    handler =
                        EffectHandler { _, send ->
                            delay(1_000)
                            send(TestAction.Loaded("late"))
                        },
                )

            store.send(TestAction.Start)
            store.send(TestAction.Cancel)
            advanceUntilIdle()

            assertTrue(recordingDefects.defects.isEmpty())
        }

    @Test
    fun send_from_background_thread_hops_to_main() =
        runTest {
            var callCount = 0
            val store = store(isOnConfinedThread = { callCount++ > 0 })

            store.send(TestAction.Start)
            assertEquals(TestState.Idle, store.state)

            runCurrent()
            assertEquals(TestState.Loading, store.state)
        }

    @Test
    fun background_scope_reduces_without_a_main_dispatcher() =
        runTest {
            // Public constructor, real dispatchers: host tests have no main looper, so passing
            // proves Background never touches Dispatchers.Main.
            val store =
                Store(
                    initialState = TestState.Idle,
                    reducer = reducer(EffectScope.StateScoped),
                    handler = EffectHandler { _, send -> send(TestAction.Loaded("background")) },
                    scope = StoreScope.Background,
                )
            try {
                store.send(TestAction.Start)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        store.stateFlow.first { state -> state == TestState.Content("background") }
                    }
                }
            } finally {
                store.close()
            }
        }

    @Test
    fun send_after_close_is_ignored() =
        runTest {
            val store = store()

            store.send(TestAction.Start)
            store.close()
            store.send(TestAction.Cancel)
            advanceUntilIdle()

            assertEquals(TestState.Loading, store.state)
        }

    @Test
    fun close_cancels_running_effects() =
        runTest {
            val store =
                store(
                    handler =
                        EffectHandler { _, send ->
                            delay(1_000)
                            send(TestAction.Loaded("late"))
                        },
                )

            store.send(TestAction.Start)
            store.close()
            advanceUntilIdle()

            assertEquals(TestState.Loading, store.state)
            assertTrue(recordingDefects.defects.isEmpty())
        }
}
