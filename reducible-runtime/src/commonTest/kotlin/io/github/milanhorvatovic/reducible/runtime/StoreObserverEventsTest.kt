package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.KeyPolicy
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// A job that loads while Working, persists under a cancel-previous key, and archives under an
// ordered one — enough to make every effect event and warning the store reports observable.
private sealed interface JobState {
    data object Idle : JobState

    data class Working(
        val results: List<String>,
    ) : JobState
}

private sealed interface JobAction {
    data object Start : JobAction

    data class Finished(
        val name: String,
    ) : JobAction

    data object Stop : JobAction

    data object Save : JobAction

    data object Archive : JobAction
}

private sealed interface JobEffect {
    data object Load : JobEffect

    data object Persist : JobEffect

    data object Archive : JobEffect
}

private data object PersistKey : EffectKey

private data object ArchiveKey : EffectKey {
    override val policy: KeyPolicy
        get() = KeyPolicy.Ordered
}

private val jobReducer =
    Reducer<JobState, JobAction, JobEffect> { state, action ->
        when (state) {
            JobState.Idle -> {
                when (action) {
                    JobAction.Start -> JobState.Working(emptyList()).withEffect(JobEffect.Load)
                    is JobAction.Finished -> state.only()
                    JobAction.Stop -> state.only()
                    JobAction.Save -> state.only()
                    JobAction.Archive -> state.only()
                }
            }

            is JobState.Working -> {
                when (action) {
                    JobAction.Start -> state.only()
                    is JobAction.Finished -> state.copy(results = state.results + action.name).only()
                    JobAction.Stop -> JobState.Idle.only()
                    JobAction.Save -> state.withEffect(JobEffect.Persist, key = PersistKey)
                    JobAction.Archive -> state.withEffect(JobEffect.Archive, key = ArchiveKey)
                }
            }
        }
    }

private val jobHandler =
    EffectHandler<JobEffect, JobAction> { effect, send ->
        when (effect) {
            JobEffect.Load -> {
                delay(100)
                send(JobAction.Finished("load"))
            }

            JobEffect.Persist -> {
                delay(100)
            }

            JobEffect.Archive -> {
                delay(100)
            }
        }
    }

private class RecordingObserver : StoreObserver<JobState, JobAction> {
    val events = mutableListOf<String>()

    override fun onReduced(
        previous: JobState,
        action: JobAction,
        next: JobState,
    ) {
        events += "reduced:$action"
    }

    override fun onEffect(event: EffectEvent) {
        events +=
            when (event) {
                is EffectEvent.Launched -> {
                    "launched:${event.effect}" +
                        if (event.queued) {
                            ":queued"
                        } else {
                            ""
                        }
                }

                is EffectEvent.Ended -> {
                    "ended:${event.effect}:${event.end}"
                }

                is EffectEvent.Skipped -> {
                    "skipped:${event.effect}"
                }
            }
    }

    override fun onWarning(warning: StoreWarning<JobAction>) {
        events += "warning:${warning::class.simpleName}"
    }
}

class StoreObserverEventsTest {
    private val observer = RecordingObserver()

    private fun List<String>.effectsOf(effect: String) =
        filter { line ->
            line.startsWith("launched:$effect") ||
                line.startsWith("ended:$effect")
        }

    private fun TestScope.store(
        handler: EffectHandler<JobEffect, JobAction> = jobHandler,
        defects: DefectHandler<JobState, JobAction, JobEffect> = DefectHandler { defect -> throw defect.error },
    ): Store<JobState, JobAction> =
        Store(
            initialState = JobState.Idle,
            reducer = jobReducer,
            handler = handler,
            defects = defects,
            confinedDispatcher = StandardTestDispatcher(testScheduler),
            effectDispatcher = StandardTestDispatcher(testScheduler),
            isOnConfinedThread = { true },
            observer = observer,
        )

    @Test
    fun an_effect_is_reported_launched_in_its_reduction_and_ended_after_what_it_fed() =
        runTest {
            val store = store()

            store.send(JobAction.Start)
            advanceUntilIdle()

            assertEquals(
                listOf("reduced:Start", "launched:Load", "reduced:Finished(name=load)", "ended:Load:Completed"),
                observer.events,
            )
            store.close()
        }

    @Test
    fun leaving_the_owning_state_ends_the_effect_by_owner() =
        runTest {
            val store = store()

            store.send(JobAction.Start)
            advanceTimeBy(50)
            store.send(JobAction.Stop)
            advanceUntilIdle()

            assertTrue("ended:Load:CancelledByOwner" in observer.events, observer.events.toString())
            assertTrue(observer.events.none { line -> line.startsWith("reduced:Finished") }, observer.events.toString())
            store.close()
        }

    @Test
    fun relaunching_a_cancel_previous_key_ends_the_holder_by_key() =
        runTest {
            val store = store()

            store.send(JobAction.Start)
            advanceUntilIdle()
            store.send(JobAction.Save)
            advanceTimeBy(50)
            store.send(JobAction.Save)
            advanceUntilIdle()

            // The relaunch is reported during its reduction; the end it caused arrives a hop later.
            assertEquals(
                listOf("launched:Persist", "launched:Persist", "ended:Persist:CancelledByKey", "ended:Persist:Completed"),
                observer.events.effectsOf("Persist"),
            )
            store.close()
        }

    @Test
    fun a_launch_queued_behind_an_ordered_holder_says_so() =
        runTest {
            val store = store()

            store.send(JobAction.Start)
            advanceUntilIdle()
            store.send(JobAction.Archive)
            store.send(JobAction.Archive)
            advanceUntilIdle()

            assertEquals(
                listOf("launched:Archive", "launched:Archive:queued", "ended:Archive:Completed", "ended:Archive:Completed"),
                observer.events.effectsOf("Archive"),
            )
            store.close()
        }

    @Test
    fun a_defect_is_reported_as_the_effects_end() =
        runTest {
            val store =
                store(
                    handler = EffectHandler { _, _ -> error("handler bug") },
                    defects = DefectHandler { },
                )

            store.send(JobAction.Start)
            advanceUntilIdle()

            assertTrue("ended:Load:Defect" in observer.events, observer.events.toString())
            store.close()
        }

    @Test
    fun a_send_after_close_is_dropped_and_warned() =
        runTest {
            val store = store()
            store.close()

            store.send(JobAction.Start)
            advanceUntilIdle()

            assertEquals(JobState.Idle, store.state)
            assertEquals(listOf("warning:SentAfterClose"), observer.events)
        }

    @Test
    fun a_send_from_a_finished_effect_is_delivered_and_warned() =
        runTest {
            var leaked: ((JobAction) -> Unit)? = null
            val store =
                store(
                    handler =
                        EffectHandler { effect, send ->
                            when (effect) {
                                JobEffect.Load -> leaked = send
                                JobEffect.Persist -> Unit
                                JobEffect.Archive -> Unit
                            }
                        },
                )

            store.send(JobAction.Start)
            advanceUntilIdle()
            leaked!!(JobAction.Finished("late"))
            advanceUntilIdle()

            assertEquals(JobState.Working(listOf("late")), store.state)
            assertEquals(
                listOf(
                    "reduced:Start",
                    "launched:Load",
                    "ended:Load:Completed",
                    "warning:SentFromFinishedEffect",
                    "reduced:Finished(name=late)",
                ),
                observer.events,
            )
            store.close()
        }
}
