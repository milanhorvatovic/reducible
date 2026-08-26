package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val appending = Reducer<List<String>, String, Nothing> { state, action -> (state + action).only() }

class StartActionTest {
    @Test
    fun the_start_action_is_reduced_before_the_factory_returns_on_the_confined_thread() =
        runTest {
            val seen = mutableListOf<String>()
            val store =
                Store(
                    initialState = emptyList(),
                    reducer = appending,
                    handler = EffectHandler<Nothing, String> { _, _ -> },
                    defects = rethrowingDefectHandler(),
                    confinedDispatcher = StandardTestDispatcher(testScheduler),
                    effectDispatcher = StandardTestDispatcher(testScheduler),
                    isOnConfinedThread = { true },
                    observer = StoreObserver { _, action, _ -> seen += action },
                    start = "started",
                )

            assertEquals(listOf("started"), store.state)
            assertEquals(listOf("started"), seen, "the observer attached at construction saw it")
            store.close()
        }

    @Test
    fun off_the_confined_thread_the_start_action_is_first_in_the_queue() =
        runTest {
            val store =
                Store(
                    initialState = emptyList(),
                    reducer = appending,
                    handler = EffectHandler<Nothing, String> { _, _ -> },
                    scope = StoreScope.Testing(StandardTestDispatcher(testScheduler)),
                    start = "started",
                )

            store.send("later")
            advanceUntilIdle()

            assertEquals(listOf("started", "later"), store.state)
            store.close()
        }

    @Test
    fun a_recording_store_records_its_start_action() =
        runTest {
            val store =
                RecordingStore(
                    initialState = emptyList(),
                    reducer = appending,
                    handler = EffectHandler<Nothing, String> { _, _ -> },
                    scope = StoreScope.Testing(StandardTestDispatcher(testScheduler)),
                    start = "started",
                )
            advanceUntilIdle()

            assertEquals(listOf("started"), store.recording.actions)
            store.close()
        }
}
