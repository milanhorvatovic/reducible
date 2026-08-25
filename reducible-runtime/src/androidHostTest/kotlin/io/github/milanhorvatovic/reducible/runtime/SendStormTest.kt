package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

// The send path under real contention: the injected-dispatcher suites prove the design is
// serial; this proves the implementation is, with actual parallel senders.
class SendStormTest {
    private val threads = 8
    private val sendsPerThread = 500
    private val expected = threads * sendsPerThread

    private fun storm(scope: StoreScope) =
        runBlocking {
            val reductions = AtomicInteger(0)
            val store =
                Store(
                    initialState = 0,
                    reducer = Reducer<Int, Int, Nothing> { state, amount -> (state + amount).only() },
                    handler = EffectHandler { _, _ -> },
                    scope = scope,
                    observer = { _, _, _ -> reductions.incrementAndGet() },
                )
            try {
                val pool = Executors.newFixedThreadPool(threads)
                val start = CountDownLatch(1)
                repeat(threads) {
                    pool.execute {
                        start.await()
                        repeat(sendsPerThread) { store.send(1) }
                    }
                }
                start.countDown()
                pool.shutdown()
                check(pool.awaitTermination(30, TimeUnit.SECONDS)) { "senders did not finish" }

                withTimeout(30_000) { store.stateFlow.first { state -> state == expected } }
                assertEquals(expected, store.state)
                assertEquals(expected, reductions.get())
            } finally {
                store.close()
            }
        }

    @Test
    fun background_store_loses_and_duplicates_nothing_under_parallel_sends() = storm(StoreScope.Background)

    @Test
    fun dedicated_store_loses_and_duplicates_nothing_under_parallel_sends() {
        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "storm-store").apply { isDaemon = true }
            }
        try {
            storm(StoreScope.Dedicated(executor.asCoroutineDispatcher()))
        } finally {
            executor.shutdown()
        }
    }
}
