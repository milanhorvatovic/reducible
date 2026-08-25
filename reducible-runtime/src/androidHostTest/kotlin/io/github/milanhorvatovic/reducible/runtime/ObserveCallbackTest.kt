package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The hop-free bridge: observe callbacks must fire synchronously on the store's confined
// thread, so a Combine subject fed from them adds zero hops on top of reduction.
class ObserveCallbackTest {
    @Test
    fun callbacks_arrive_on_the_confined_thread_and_stop_on_cancel() =
        runBlocking {
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "observe-store").apply { isDaemon = true }
                }
            val store =
                Store(
                    initialState = 0,
                    reducer = Reducer<Int, Int, Nothing> { state, amount -> (state + amount).only() },
                    handler = EffectHandler { _, _ -> },
                    scope = StoreScope.Dedicated(executor.asCoroutineDispatcher()),
                )
            val received = CopyOnWriteArrayList<Pair<Int, String>>()
            try {
                val subscription =
                    store.observe { value ->
                        // Coroutines debug mode appends " @coroutine#N" to thread names in tests.
                        received += value to Thread.currentThread().name.substringBefore(" @")
                    }

                store.send(1)
                store.send(2)
                withTimeout(5_000) { store.stateFlow.first { state -> state == 3 } }
                // Let the collector drain its conflated tail before asserting.
                withTimeout(5_000) {
                    while (received.lastOrNull()?.first != 3) {
                        kotlinx.coroutines.delay(10)
                    }
                }

                assertTrue(received.all { entry -> entry.second == "observe-store" }, "callbacks left the confined thread: $received")
                assertEquals(3, received.last().first)

                subscription.cancel()
                val seen = received.size
                store.send(4)
                withTimeout(5_000) { store.stateFlow.first { state -> state == 7 } }
                assertEquals(seen, received.size, "cancelled observation kept receiving")
            } finally {
                store.close()
                executor.shutdown()
            }
        }
}
