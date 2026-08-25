@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

private data class Counter(
    val count: Int,
    val label: String,
)

private sealed interface CounterAction {
    /** The subset a screen may send; [Relabel] is reserved for the feature itself. */
    sealed interface Ui : CounterAction

    data object Increment : Ui

    data class Relabel(
        val label: String,
    ) : CounterAction
}

private data class Summary(
    val count: Int,
)

private val counterReducer =
    Reducer<Counter, CounterAction, Nothing> { state, action ->
        when (action) {
            CounterAction.Increment -> state.copy(count = state.count + 1).only()
            is CounterAction.Relabel -> state.copy(label = action.label).only()
        }
    }

class ViewStoreTest {
    private fun TestScope.store(): Store<Counter, CounterAction> =
        Store(
            initialState = Counter(count = 0, label = "a"),
            reducer = counterReducer,
            handler = EffectHandler { _, _ -> },
            defects = DefectHandler { defect -> throw defect.error },
            confinedDispatcher = StandardTestDispatcher(testScheduler),
            effectDispatcher = StandardTestDispatcher(testScheduler),
            isOnConfinedThread = { true },
        )

    private fun Store<Counter, CounterAction>.countView(): ViewStore<Int, CounterAction.Ui> =
        view(state = { counter -> counter.count }, action = { action -> action })

    @Test
    fun state_is_projected_and_actions_are_embedded_synchronously() =
        runTest {
            val store = store()
            val view = store.countView()

            assertEquals(0, view.state)

            view.send(CounterAction.Increment)

            assertEquals(1, view.state)
            assertEquals(1, view.stateFlow.value)
            assertEquals(listOf(1), view.stateFlow.replayCache)
            assertEquals(Counter(count = 1, label = "a"), store.state)
        }

    @Test
    fun repeated_state_reads_return_one_projection_until_the_store_reduces_again() =
        runTest {
            val store = store()
            val view: ViewStore<Summary, CounterAction.Ui> =
                store.view(state = { counter ->
                    Summary(counter.count)
                }, action = { action -> action })

            val first = view.state
            assertSame(first, view.state, "a read between reductions must not allocate a new projection")
            assertSame(first, view.stateFlow.value)

            view.send(CounterAction.Increment)

            val second = view.state
            assertNotSame(first, second)
            assertEquals(Summary(1), second)
            assertSame(second, view.state)
        }

    @Test
    fun observers_receive_the_same_projection_object_that_state_reads_return() =
        runTest {
            val store = store()
            val view: ViewStore<Summary, CounterAction.Ui> =
                store.view(state = { counter ->
                    Summary(counter.count)
                }, action = { action -> action })
            val received = mutableListOf<Summary>()
            view.observe { summary -> received += summary }
            runCurrent()

            view.send(CounterAction.Increment)
            runCurrent()

            assertSame(view.state, received.last(), "observe and state must not project the same reduction twice")
            store.close()
        }

    @Test
    fun collectors_see_only_projections_that_changed() =
        runTest {
            val store = store()
            val view = store.countView()
            val received = mutableListOf<Int>()
            backgroundScope.launch { view.stateFlow.collect { summary -> received += summary } }
            runCurrent()

            store.send(CounterAction.Relabel("b"))
            runCurrent()
            assertEquals(listOf(0), received, "a reduction that leaves the projection equal must not emit")

            store.send(CounterAction.Increment)
            runCurrent()
            assertEquals(listOf(0, 1), received)

            store.close()
        }

    @Test
    fun observe_delivers_the_current_projection_then_only_changes_until_cancelled() =
        runTest {
            val store = store()
            val view = store.countView()
            val received = mutableListOf<Int>()

            val subscription = view.observe { summary -> received += summary }
            runCurrent()
            assertEquals(listOf(0), received)

            store.send(CounterAction.Relabel("b"))
            runCurrent()
            assertEquals(listOf(0), received)

            view.send(CounterAction.Increment)
            runCurrent()
            assertEquals(listOf(0, 1), received)

            subscription.cancel()
            view.send(CounterAction.Increment)
            runCurrent()
            assertEquals(listOf(0, 1), received, "cancelled observation kept receiving")

            store.close()
        }

    @Test
    fun nested_view_composes_projection_and_embedding() =
        runTest {
            val store = store()
            val screen: ViewStore<Counter, CounterAction> = store.view(state = { counter -> counter }, action = { action -> action })
            val label: ViewStore<String, String> =
                screen.view(state = { counter ->
                    counter.label
                }, action = { label -> CounterAction.Relabel(label) })
            val received = mutableListOf<String>()
            backgroundScope.launch { label.stateFlow.collect { label -> received += label } }
            runCurrent()

            label.send("renamed")
            runCurrent()

            assertEquals("renamed", label.state)
            assertEquals(Counter(count = 0, label = "renamed"), store.state)

            store.send(CounterAction.Increment)
            runCurrent()
            assertEquals(listOf("a", "renamed"), received, "a sibling field change must not reach the child view")

            store.close()
        }
}
