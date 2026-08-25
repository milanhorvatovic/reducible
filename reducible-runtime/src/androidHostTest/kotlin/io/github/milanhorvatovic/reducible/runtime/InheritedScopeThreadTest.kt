package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class InheritedScopeThreadTest {
    @Test
    fun a_store_reduces_on_the_thread_of_the_scope_it_inherits() =
        runBlocking {
            val executor =
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "screen-scope-thread").apply { isDaemon = true }
                }
            val inherited = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())

            val threads = mutableListOf<String>()
            val recordingReducer =
                Reducer<Int, Unit, Nothing> { state, _ ->
                    // Coroutines debug mode appends " @coroutine#N" to thread names in tests.
                    threads += Thread.currentThread().name.substringBefore(" @")
                    (state + 1).only()
                }

            val store = Store(0, recordingReducer, EffectHandler { _, _ -> }, scope = StoreScope.Inherited(inherited))
            try {
                store.send(Unit)
                withTimeout(5_000) { store.stateFlow.first { state -> state == 1 } }
            } finally {
                inherited.cancel()
                executor.shutdown()
            }

            assertEquals(listOf("screen-scope-thread"), threads)
        }
}
