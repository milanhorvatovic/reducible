package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoggingDefectHandlerTest {
    @Test
    fun logs_the_wrapped_effect_defect_and_keeps_the_store_alive() =
        runTest {
            val logged = mutableListOf<DefectException>()
            val store =
                Store(
                    initialState = 0,
                    reducer = Reducer<Int, Unit, Unit> { state, _ -> (state + 1).withEffect(Unit) },
                    handler = EffectHandler { _, _ -> error("boom") },
                    defects = loggingDefectHandler(logged::add),
                    scope = StoreScope.Testing(StandardTestDispatcher(testScheduler)),
                )
            try {
                store.send(Unit)
                advanceUntilIdle()

                assertEquals(1, logged.size)
                assertIs<Defect.InEffect<*>>(logged.single().defect)
                assertTrue("escaped its handler" in logged.single().message.orEmpty())
                assertEquals("boom", logged.single().cause?.message)

                // The defect was logged, not rethrown: the store keeps reducing.
                store.send(Unit)
                advanceUntilIdle()
                assertEquals(2, store.state)
                assertEquals(2, logged.size)
            } finally {
                store.close()
            }
        }

    @Test
    fun logs_a_reducer_defect_skips_the_action_and_keeps_reducing() =
        runTest {
            val logged = mutableListOf<DefectException>()
            val store =
                Store(
                    initialState = 0,
                    reducer =
                        Reducer<Int, Int, Nothing> { state, action ->
                            if (action <
                                0
                            ) {
                                error("negative")
                            } else {
                                (state + action).only()
                            }
                        },
                    handler = EffectHandler { _, _ -> },
                    defects = loggingDefectHandler(logged::add),
                    scope = StoreScope.Testing(StandardTestDispatcher(testScheduler)),
                )
            try {
                store.send(1)
                store.send(-1)
                store.send(2)
                advanceUntilIdle()

                assertEquals(3, store.state, "the throwing action is skipped, the others reduce")
                val defect = assertIs<Defect.InReducer<*, *>>(logged.single().defect)
                assertEquals(1, defect.state)
                assertEquals(-1, defect.action)
                assertEquals("negative", logged.single().cause?.message)
                assertTrue("Reducers are pure" in logged.single().message.orEmpty())
            } finally {
                store.close()
            }
        }
}
