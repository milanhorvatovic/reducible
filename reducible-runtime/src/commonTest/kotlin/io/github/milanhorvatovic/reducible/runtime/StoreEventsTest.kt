@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Event
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.andSend
import io.github.milanhorvatovic.reducible.only
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private sealed interface Counting {
    data object Tick : Counting

    /** What the counter tells its holder: it went past the limit. */
    sealed interface Told :
        Counting,
        Event {
        data class Overflowed(
            val count: Int,
        ) : Told
    }
}

private const val LIMIT = 2

private val counterReducer =
    Reducer<Int, Counting, Nothing> { count, action ->
        when (action) {
            Counting.Tick -> {
                val next = count + 1
                if (next > LIMIT) {
                    next.andSend(Counting.Told.Overflowed(next))
                } else {
                    next.only()
                }
            }

            is Counting.Told -> {
                count.only()
            }
        }
    }

private class EventWarnings : StoreObserver<Int, Counting> {
    val warnings = mutableListOf<StoreWarning<Counting>>()

    override fun onReduced(
        previous: Int,
        action: Counting,
        next: Int,
    ) {}

    override fun onWarning(warning: StoreWarning<Counting>) {
        warnings += warning
    }
}

class StoreEventsTest {
    private val warnings = EventWarnings()

    private fun TestScope.store(): Store<Int, Counting> =
        Store(
            initialState = 0,
            reducer = counterReducer,
            handler = EffectHandler { _, _ -> },
            defects = rethrowingDefectHandler(),
            confinedDispatcher = StandardTestDispatcher(testScheduler),
            effectDispatcher = StandardTestDispatcher(testScheduler),
            isOnConfinedThread = { true },
            observer = warnings,
        )

    @Test
    fun an_event_reaches_the_collector_after_the_state_it_followed_and_nothing_else_does() =
        runTest {
            val store = store()
            val seen = mutableListOf<Pair<Counting.Told, Int>>()
            backgroundScope.launch {
                store.events { action -> action as? Counting.Told }.collect { told -> seen += told to store.state }
            }
            runCurrent()

            repeat(LIMIT + 1) { store.send(Counting.Tick) }
            runCurrent()

            assertEquals<List<Pair<Counting.Told, Int>>>(listOf(Counting.Told.Overflowed(3) to 3), seen)
            assertTrue(warnings.warnings.isEmpty(), "nothing dropped: ${warnings.warnings}")
        }

    @Test
    fun an_event_nobody_collects_is_dropped_and_reported() =
        runTest {
            val store = store()

            repeat(LIMIT + 1) { store.send(Counting.Tick) }

            assertEquals<List<StoreWarning<Counting>>>(listOf(StoreWarning.EventDropped(Counting.Told.Overflowed(3))), warnings.warnings)
            assertEquals(3, store.state, "the event was still reduced, as a no-op, in order")
        }
}
