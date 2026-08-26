package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.replay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val countingReducer =
    Reducer<Int, Int, Nothing> { state, action ->
        (state + action).only()
    }

class ActionRecorderTest {
    @Test
    fun recorder_passed_as_observer_captures_and_replays() =
        runTest {
            val recorder = ActionRecorder<Int, Int>()
            val store =
                Store(
                    initialState = 10,
                    reducer = countingReducer,
                    handler = EffectHandler { _, _ -> },
                    scope = StoreScope.Background,
                    observer = recorder,
                )
            try {
                store.send(5)
                store.send(7)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { store.stateFlow.first { state -> state == 22 } }
                }

                val recording = recorder.recording!!
                assertEquals(10, recording.initialState)
                assertEquals(listOf(5, 7), recording.actions)
                assertEquals(22, countingReducer.replay(recording.initialState, recording.actions))
            } finally {
                store.close()
            }
        }

    @Test
    fun a_bounded_recorder_drops_the_oldest_action_and_rebaselines_exactly() {
        val recorder = ActionRecorder<Int, Int>(capacity = 2)

        recorder.onReduced(previous = 10, action = 5, next = 15)
        recorder.onReduced(previous = 15, action = 7, next = 22)
        recorder.onReduced(previous = 22, action = 1, next = 23)

        val recording = recorder.recording!!
        // The baseline moved to the state the dropped action produced, so replay stays exact.
        assertEquals(15, recording.initialState)
        assertEquals(listOf(7, 1), recording.actions)
        assertEquals(23, countingReducer.replay(recording.initialState, recording.actions))
    }

    @Test
    fun clear_rebaselines_at_the_next_action() =
        runTest {
            val recorder = ActionRecorder<Int, Int>()
            val store =
                Store(
                    initialState = 0,
                    reducer = countingReducer,
                    handler = EffectHandler { _, _ -> },
                    scope = StoreScope.Background,
                    observer = recorder,
                )
            try {
                store.send(1)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { store.stateFlow.first { state -> state == 1 } }
                }

                recorder.clear()
                assertNull(recorder.recording)

                store.send(2)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { store.stateFlow.first { state -> state == 3 } }
                }

                val recording = recorder.recording!!
                assertEquals(1, recording.initialState)
                assertEquals(listOf(2), recording.actions)
                assertEquals(3, countingReducer.replay(recording.initialState, recording.actions))
            } finally {
                store.close()
            }
        }
}
