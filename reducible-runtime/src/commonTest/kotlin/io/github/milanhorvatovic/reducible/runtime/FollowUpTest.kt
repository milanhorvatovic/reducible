package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.andSend
import io.github.milanhorvatovic.reducible.only
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FollowUpTest {
    @Test
    fun follow_ups_are_reduced_right_after_their_reduction_and_before_later_sends() =
        runTest {
            val reducer =
                Reducer<List<String>, String, Nothing> { state, action ->
                    when (action) {
                        "start" -> (state + action).andSend("follow-1", "follow-2")
                        else -> (state + action).only()
                    }
                }
            val order = mutableListOf<String>()
            val store =
                Store(
                    initialState = emptyList(),
                    reducer = reducer,
                    handler = EffectHandler<Nothing, String> { _, _ -> },
                    defects = DefectHandler { defect -> throw defect.error },
                    confinedDispatcher = StandardTestDispatcher(testScheduler),
                    effectDispatcher = StandardTestDispatcher(testScheduler),
                    isOnConfinedThread = { true },
                    observer = StoreObserver { _, action, _ -> order += action },
                )

            store.send("start")
            store.send("later")
            advanceUntilIdle()

            assertEquals(listOf("start", "follow-1", "follow-2", "later"), order)
            assertEquals(order, store.state)
            store.close()
        }

    @Test
    fun a_follow_up_cycle_is_cut_reported_and_the_sends_queued_behind_it_still_reduce() =
        runTest {
            val logged = mutableListOf<DefectException>()
            lateinit var store: Store<Int, String>
            store =
                Store(
                    initialState = 0,
                    reducer = loopingReducer,
                    handler = EffectHandler<Nothing, String> { _, _ -> },
                    defects = loggingDefectHandler(logged::add),
                    confinedDispatcher = StandardTestDispatcher(testScheduler),
                    effectDispatcher = StandardTestDispatcher(testScheduler),
                    isOnConfinedThread = { true },
                    // A re-entrant send during the first reduction: queued behind the follow-ups,
                    // it must survive the cut.
                    observer =
                        StoreObserver { previous, _, _ ->
                            if (previous == 0) {
                                store.send("plain")
                            }
                        },
                )

            store.send("loop")

            val defect = assertIs<Defect.FollowUpCycle<*, *>>(logged.single().defect)
            assertEquals("loop", defect.action)
            assertEquals(MAX_FOLLOW_UPS_PER_DRAIN + 1, defect.state, "the bound, plus the reduction that asked past it")
            assertEquals(MAX_FOLLOW_UPS_PER_DRAIN + 1 + 1000, store.state, "the re-entrant send reduced after the cut")

            store.send("plain")
            assertEquals(MAX_FOLLOW_UPS_PER_DRAIN + 1 + 2000, store.state, "the store keeps reducing")
            store.close()
        }

    @Test
    fun under_the_rethrowing_policy_a_follow_up_cycle_crashes_naming_the_action() =
        runTest {
            val store =
                Store(
                    initialState = 0,
                    reducer = loopingReducer,
                    handler = EffectHandler<Nothing, String> { _, _ -> },
                    defects = rethrowingDefectHandler(),
                    confinedDispatcher = StandardTestDispatcher(testScheduler),
                    effectDispatcher = StandardTestDispatcher(testScheduler),
                    isOnConfinedThread = { true },
                )

            val failure = assertFailsWith<DefectException> { store.send("loop") }

            assertIs<Defect.FollowUpCycle<*, *>>(failure.defect)
            assertTrue("'loop'" in failure.message.orEmpty())
            assertTrue("follow-up cycle" in failure.message.orEmpty())
            store.close()
        }
}

/** `loop` follows up with itself forever; anything else adds a thousand and stops. */
private val loopingReducer =
    Reducer<Int, String, Nothing> { state, action ->
        when (action) {
            "loop" -> (state + 1).andSend("loop")
            else -> (state + 1000).only()
        }
    }
