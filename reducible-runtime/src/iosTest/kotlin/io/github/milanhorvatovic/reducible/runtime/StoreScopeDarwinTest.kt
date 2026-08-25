package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSThread
import platform.darwin.dispatch_queue_create
import kotlin.test.Test
import kotlin.test.assertEquals

class StoreScopeDarwinTest {
    @Test
    fun store_dedicated_to_a_gcd_queue_reduces_off_the_main_thread() =
        runBlocking {
            // Null attributes mean a serial queue — DISPATCH_QUEUE_SERIAL is the NULL macro.
            val queue = dispatch_queue_create("app-store-queue", attr = null)

            val mainThreadDuringReduce = mutableListOf<Boolean>()
            val recordingReducer =
                Reducer<Int, Unit, Nothing> { state, _ ->
                    mainThreadDuringReduce += NSThread.isMainThread()
                    (state + 1).only()
                }

            val store = Store(0, recordingReducer, EffectHandler { _, _ -> }, scope = dedicated(queue))
            try {
                store.send(Unit)
                withTimeout(5_000) {
                    store.stateFlow.first { state -> state == 1 }
                }
            } finally {
                store.close()
            }

            assertEquals(listOf(false), mainThreadDuringReduce)
        }
}
