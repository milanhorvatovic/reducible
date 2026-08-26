package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class ObserverFanOutTest {
    @Test
    fun fans_one_reduction_out_to_both_observers_in_order() {
        val calls = mutableListOf<String>()
        val combined =
            StoreObserver<Int, String> { previous, action, next -> calls += "first:$previous$action$next" } +
                StoreObserver { previous, action, next -> calls += "second:$previous$action$next" }

        combined.onReduced(0, "x", 1)

        assertEquals(listOf("first:0x1", "second:0x1"), calls)
    }

    @Test
    fun fans_effect_events_and_warnings_out_to_both_observers_in_order() {
        val calls = mutableListOf<String>()

        fun named(name: String) =
            object : StoreObserver<Int, String> {
                override fun onReduced(
                    previous: Int,
                    action: String,
                    next: Int,
                ) {
                    calls += "$name:reduced"
                }

                override fun onEffect(event: EffectEvent) {
                    calls += "$name:${event::class.simpleName}"
                }

                override fun onWarning(warning: StoreWarning<String>) {
                    calls += "$name:${warning::class.simpleName}"
                }
            }
        val combined = named("first") + named("second")

        combined.onEffect(EffectEvent.Launched("load", EffectScope.StateScoped, key = null, queued = false))
        combined.onWarning(StoreWarning.SentAfterClose("late"))

        assertEquals(listOf("first:Launched", "second:Launched", "first:SentAfterClose", "second:SentAfterClose"), calls)
    }

    @Test
    fun recording_and_a_second_observer_coexist_on_one_store() =
        runTest {
            val recorder = ActionRecorder<Int, Int>()
            var reductions = 0
            val store =
                Store(
                    initialState = 0,
                    reducer = Reducer<Int, Int, Nothing> { state, action -> (state + action).only() },
                    handler = EffectHandler { _, _ -> },
                    scope = StoreScope.Background,
                    observer = recorder + StoreObserver { _, _, _ -> reductions++ },
                )
            try {
                store.send(2)
                store.send(3)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { store.stateFlow.first { state -> state == 5 } }
                }

                assertEquals(listOf(2, 3), recorder.recording!!.actions)
                assertEquals(2, reductions)
            } finally {
                store.close()
            }
        }
}
