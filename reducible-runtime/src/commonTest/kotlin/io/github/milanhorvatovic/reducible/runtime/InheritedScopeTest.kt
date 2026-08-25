@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private sealed interface Phase {
    data object Idle : Phase

    data object Working : Phase
}

private sealed interface Command {
    data object Start : Command

    data object Poke : Command
}

private val reducer =
    Reducer<Phase, Command, Unit> { state, command ->
        when (command) {
            Command.Start -> Phase.Working.withEffect(Unit)
            Command.Poke -> state.only()
        }
    }

private class RecordingWarnings : StoreObserver<Phase, Command> {
    val warnings = mutableListOf<StoreWarning<Command>>()

    override fun onReduced(
        previous: Phase,
        action: Command,
        next: Phase,
    ) {}

    override fun onWarning(warning: StoreWarning<Command>) {
        warnings += warning
    }
}

class InheritedScopeTest {
    private fun TestScope.owner(extra: CoroutineContext = SupervisorJob()): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler) + extra)

    @Test
    fun reduction_runs_on_the_inherited_scopes_dispatcher_and_ends_with_its_job() =
        runTest {
            val inherited = owner()
            val effectStarted = CompletableDeferred<Unit>()
            val effectCancelled = CompletableDeferred<Unit>()
            val warnings = RecordingWarnings()
            val store =
                Store(
                    initialState = Phase.Idle,
                    reducer = reducer,
                    handler =
                        EffectHandler<Unit, Command> { _, _ ->
                            effectStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                effectCancelled.complete(Unit)
                            }
                        },
                    scope = StoreScope.Inherited(inherited),
                    observer = warnings,
                )

            store.send(Command.Start)
            assertEquals(Phase.Idle, store.state, "a test dispatcher is not the main thread: the send is queued")
            advanceUntilIdle()
            assertEquals(Phase.Working, store.state)

            // A coroutine cancelled before its first resumption never runs its body, finally
            // included; the cancel must land on a handler that is already suspended.
            effectStarted.await()
            inherited.cancel()
            effectCancelled.await()
            store.send(Command.Poke)

            assertIs<StoreWarning.SentAfterClose<Command>>(warnings.warnings.single())
        }

    @Test
    fun closing_the_store_leaves_the_inherited_scope_alive() =
        runTest {
            val inherited = owner()
            val store = Store(Phase.Idle, reducer, EffectHandler<Unit, Command> { _, _ -> }, scope = StoreScope.Inherited(inherited))

            store.close()

            assertTrue(inherited.isActive)
            var ran = false
            inherited.launch { ran = true }
            advanceUntilIdle()
            assertTrue(ran, "the scope still runs work of its own")
        }

    @Test
    fun the_inherited_scopes_exception_handler_receives_an_escaped_defect() =
        runTest {
            val escaped = CompletableDeferred<Throwable>()
            val inherited = owner(SupervisorJob() + CoroutineExceptionHandler { _, error -> escaped.complete(error) })
            val store =
                Store(
                    initialState = Phase.Idle,
                    reducer = reducer,
                    handler = EffectHandler<Unit, Command> { _, _ -> throw IllegalStateException("handler let it escape") },
                    defects = rethrowingDefectHandler(),
                    scope = StoreScope.Inherited(inherited),
                )
            try {
                store.send(Command.Start)
                advanceUntilIdle()

                assertIs<DefectException>(escaped.await())
            } finally {
                inherited.cancel()
            }
        }

    @Test
    fun resolution_keeps_a_main_dispatcher_serializes_any_other_and_parents_the_store_to_the_scopes_job() {
        val main = StoreScope.Inherited(CoroutineScope(Dispatchers.Main + CoroutineName("screen"))).resolve()
        assertSame(Dispatchers.Main, main.reduction[ContinuationInterceptor])
        assertEquals("screen", main.reduction[CoroutineName]?.name, "the rest of the scope's context is carried along")

        val worker = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val serialized = StoreScope.Inherited(worker).resolve()
        assertNotSame(Dispatchers.Default, serialized.reduction[ContinuationInterceptor])
        assertSame(worker.coroutineContext[Job], serialized.parent)
        assertNull(serialized.reduction[Job], "the store adds its own child job; the scope's must not end up in the context twice")

        val undispatched = StoreScope.Inherited(CoroutineScope(Job())).resolve()
        assertNotNull(undispatched.reduction[ContinuationInterceptor], "a scope without a dispatcher still reduces somewhere serial")
    }
}
