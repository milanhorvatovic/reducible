package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.replay
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private sealed interface FetchState {
    data object Idle : FetchState

    data object Fetching : FetchState

    data class Done(
        val value: String,
    ) : FetchState
}

private sealed interface FetchAction {
    data object Fetch : FetchAction

    data class Fetched(
        val value: String,
    ) : FetchAction
}

private sealed interface FetchEffect {
    data object Load : FetchEffect
}

private val fetchReducer =
    Reducer<FetchState, FetchAction, FetchEffect> { state, action ->
        when (state) {
            FetchState.Idle -> {
                when (action) {
                    FetchAction.Fetch -> FetchState.Fetching.withEffect(FetchEffect.Load)
                    is FetchAction.Fetched -> state.only()
                }
            }

            FetchState.Fetching -> {
                when (action) {
                    FetchAction.Fetch -> state.only()
                    is FetchAction.Fetched -> FetchState.Done(action.value).only()
                }
            }

            is FetchState.Done -> {
                state.only()
            }
        }
    }

class RecordingStoreTest {
    @Test
    fun log_contains_effect_fed_actions_and_replays_to_the_live_state() =
        runTest {
            val store =
                RecordingStore(
                    initialState = FetchState.Idle,
                    reducer = fetchReducer,
                    handler = EffectHandler { _, send -> send(FetchAction.Fetched("result")) },
                    scope = StoreScope.Background,
                )
            try {
                store.send(FetchAction.Fetch)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        store.stateFlow.first { state -> state == FetchState.Done("result") }
                    }
                }

                val recording = store.recording
                assertEquals(
                    listOf(FetchAction.Fetch, FetchAction.Fetched("result")),
                    recording.actions,
                )
                assertEquals(
                    store.state,
                    fetchReducer.replay(recording.initialState, recording.actions),
                )
            } finally {
                store.close()
            }
        }

    @Test
    fun clearRecording_empties_the_log_without_touching_state() =
        runTest {
            val store =
                RecordingStore(
                    initialState = FetchState.Idle,
                    reducer = fetchReducer,
                    handler = EffectHandler { _, _ -> },
                    scope = StoreScope.Background,
                )
            try {
                store.send(FetchAction.Fetch)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        store.stateFlow.first { state -> state == FetchState.Fetching }
                    }
                }

                store.clearRecording()

                assertTrue(store.recording.actions.isEmpty())
                assertEquals(FetchState.Fetching, store.state)
            } finally {
                store.close()
            }
        }
}
