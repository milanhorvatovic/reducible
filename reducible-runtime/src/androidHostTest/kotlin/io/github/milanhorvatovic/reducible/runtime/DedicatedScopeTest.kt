package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class DedicatedScopeTest {
    @Test
    fun stores_sharing_a_dedicated_dispatcher_reduce_on_its_thread() =
        runBlocking {
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "app-store-thread").apply { isDaemon = true }
                }
            val dedicated = StoreScope.Dedicated(executor.asCoroutineDispatcher())

            val threads = mutableListOf<String>()
            val recordingReducer =
                Reducer<Int, Unit, Nothing> { state, _ ->
                    // Coroutines debug mode appends " @coroutine#N" to thread names in tests.
                    threads += Thread.currentThread().name.substringBefore(" @")
                    (state + 1).only()
                }

            val first = Store(0, recordingReducer, EffectHandler { _, _ -> }, scope = dedicated)
            val second = Store(0, recordingReducer, EffectHandler { _, _ -> }, scope = dedicated)
            try {
                first.send(Unit)
                second.send(Unit)
                withTimeout(5_000) {
                    first.stateFlow.first { state -> state == 1 }
                    second.stateFlow.first { state -> state == 1 }
                }
            } finally {
                first.close()
                second.close()
                executor.shutdown()
            }

            assertEquals(listOf("app-store-thread", "app-store-thread"), threads)
        }
}
