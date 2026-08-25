package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.KeyPolicy
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The debounced-input pattern: every keystroke requests a keyed search effect whose handler
// starts with a delay; the new key cancels the predecessor, so only the last query resolves.
private data class SearchState(
    val query: String,
    val results: List<String>,
)

private sealed interface SearchAction {
    data class Typed(
        val query: String,
    ) : SearchAction

    data class Results(
        val hit: String,
    ) : SearchAction
}

private sealed interface SearchEffect {
    data class Search(
        val query: String,
    ) : SearchEffect
}

private data object SearchDebounce : EffectKey

private data object OtherKey : EffectKey

private data object OrderedKey : EffectKey {
    override val policy: KeyPolicy
        get() = KeyPolicy.Ordered
}

// A gate that closes: work queued under an ordered key is owned by the Open phase, so closing
// must cancel the running item and the ones still waiting their turn.
private sealed interface GateState {
    data class Open(
        val done: List<String>,
    ) : GateState

    data object Closed : GateState
}

private sealed interface GateAction {
    data class Work(
        val name: String,
    ) : GateAction

    data class Done(
        val name: String,
    ) : GateAction

    data object Close : GateAction
}

private sealed interface GateEffect {
    data class Run(
        val name: String,
    ) : GateEffect
}

private val gateReducer =
    Reducer<GateState, GateAction, GateEffect> { state, action ->
        when (state) {
            is GateState.Open -> {
                when (action) {
                    is GateAction.Work -> state.withEffect(GateEffect.Run(action.name), key = OrderedKey)
                    is GateAction.Done -> state.copy(done = state.done + action.name).only()
                    GateAction.Close -> GateState.Closed.only()
                }
            }

            GateState.Closed -> {
                state.only()
            }
        }
    }

private val searchReducer =
    Reducer<SearchState, SearchAction, SearchEffect> { state, action ->
        when (action) {
            is SearchAction.Typed -> {
                state
                    .copy(query = action.query)
                    .withEffect(SearchEffect.Search(action.query), key = SearchDebounce)
            }

            is SearchAction.Results -> {
                state.copy(results = state.results + action.hit).only()
            }
        }
    }

private val searchHandler =
    EffectHandler<SearchEffect, SearchAction> { effect, send ->
        when (effect) {
            is SearchEffect.Search -> {
                delay(300)
                send(SearchAction.Results("hit:${effect.query}"))
            }
        }
    }

class KeyedEffectTest {
    private class RecordingDefects : DefectHandler<SearchState, SearchAction, SearchEffect> {
        val defects = mutableListOf<Throwable>()

        override fun onDefect(defect: Defect<SearchState, SearchAction, SearchEffect>) {
            defects += defect.error
        }
    }

    private val recordingDefects = RecordingDefects()

    @Test
    fun keyed_effect_debounces_rapid_inputs_to_the_last_one() =
        runTest {
            val store =
                Store(
                    initialState = SearchState("", emptyList()),
                    reducer = searchReducer,
                    handler = searchHandler,
                    defects = recordingDefects,
                    confinedDispatcher = StandardTestDispatcher(testScheduler),
                    effectDispatcher = StandardTestDispatcher(testScheduler),
                    isOnConfinedThread = { true },
                )

            store.send(SearchAction.Typed("a"))
            advanceTimeBy(100)
            store.send(SearchAction.Typed("ab"))
            advanceTimeBy(100)
            store.send(SearchAction.Typed("abc"))
            advanceUntilIdle()

            assertEquals(SearchState("abc", listOf("hit:abc")), store.state)
            assertTrue(recordingDefects.defects.isEmpty(), "cancel-previous must never be a defect")
        }

    @Test
    fun distinct_keys_do_not_cancel_each_other() =
        runTest {
            val reducer =
                Reducer<SearchState, SearchAction, SearchEffect> { state, action ->
                    when (action) {
                        is SearchAction.Typed -> {
                            state.copy(query = action.query).withEffect(
                                SearchEffect.Search(action.query),
                                key =
                                    if (action.query.length % 2 == 0) {
                                        SearchDebounce
                                    } else {
                                        OtherKey
                                    },
                            )
                        }

                        is SearchAction.Results -> {
                            state.copy(results = state.results + action.hit).only()
                        }
                    }
                }
            val store =
                Store(
                    initialState = SearchState("", emptyList()),
                    reducer = reducer,
                    handler = searchHandler,
                    defects = recordingDefects,
                    confinedDispatcher = StandardTestDispatcher(testScheduler),
                    effectDispatcher = StandardTestDispatcher(testScheduler),
                    isOnConfinedThread = { true },
                )

            store.send(SearchAction.Typed("a"))
            store.send(SearchAction.Typed("ab"))
            advanceUntilIdle()

            assertEquals(listOf("hit:a", "hit:ab"), store.state.results)
        }

    @Test
    fun ordered_key_runs_effects_one_at_a_time_in_request_order() =
        runTest {
            val reducer =
                Reducer<SearchState, SearchAction, SearchEffect> { state, action ->
                    when (action) {
                        is SearchAction.Typed -> {
                            state.copy(query = action.query).withEffect(SearchEffect.Search(action.query), key = OrderedKey)
                        }

                        is SearchAction.Results -> {
                            state.copy(results = state.results + action.hit).only()
                        }
                    }
                }
            val store =
                Store(
                    initialState = SearchState("", emptyList()),
                    reducer = reducer,
                    handler = searchHandler,
                    defects = recordingDefects,
                    confinedDispatcher = StandardTestDispatcher(testScheduler),
                    effectDispatcher = StandardTestDispatcher(testScheduler),
                    isOnConfinedThread = { true },
                )

            store.send(SearchAction.Typed("a"))
            advanceTimeBy(100)
            store.send(SearchAction.Typed("ab"))
            advanceTimeBy(100)
            store.send(SearchAction.Typed("abc"))

            // Each search takes 300: run in parallel they would finish at 300, 400, and 500;
            // queued they finish at 300, 600, and 900.
            advanceTimeBy(250)
            assertEquals(listOf("hit:a"), store.state.results)
            advanceTimeBy(200)
            assertEquals(listOf("hit:a", "hit:ab"), store.state.results)
            advanceUntilIdle()
            assertEquals(listOf("hit:a", "hit:ab", "hit:abc"), store.state.results)
            assertTrue(recordingDefects.defects.isEmpty(), "queuing must never be a defect")
        }

    @Test
    fun leaving_the_owning_state_cancels_the_running_and_the_queued_ordered_effects() =
        runTest {
            val started = mutableListOf<String>()
            val handler =
                EffectHandler<GateEffect, GateAction> { effect, send ->
                    when (effect) {
                        is GateEffect.Run -> {
                            started += effect.name
                            delay(300)
                            send(GateAction.Done(effect.name))
                        }
                    }
                }
            val store =
                Store(
                    initialState = GateState.Open(emptyList()),
                    reducer = gateReducer,
                    handler = handler,
                    defects = DefectHandler { defect -> throw defect.error },
                    confinedDispatcher = StandardTestDispatcher(testScheduler),
                    effectDispatcher = StandardTestDispatcher(testScheduler),
                    isOnConfinedThread = { true },
                )

            store.send(GateAction.Work("a"))
            store.send(GateAction.Work("b"))
            advanceTimeBy(100)
            store.send(GateAction.Close)
            advanceUntilIdle()

            assertEquals(GateState.Closed, store.state)
            assertEquals(listOf("a"), started, "the queued item must be cancelled before it ever starts")
            assertEquals(0, store.activeEffects)
        }
}
