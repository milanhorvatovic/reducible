package io.github.milanhorvatovic.reducible.test

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.runtime.DefectHandler
import io.github.milanhorvatovic.reducible.runtime.Store
import io.github.milanhorvatovic.reducible.runtime.StoreObserver
import io.github.milanhorvatovic.reducible.runtime.StoreScope
import io.github.milanhorvatovic.reducible.runtime.Subscription
import io.github.milanhorvatovic.reducible.runtime.rethrowingDefectHandler
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * A deterministic store for feature-level integration tests — reducer, handler, and store
 * semantics under the test scheduler's virtual time. Create one inside `runTest` via
 * [testStore], drive it with [send] plus the scope's `advanceTimeBy` / `advanceUntilIdle`,
 * then assert exhaustively:
 *
 * ```
 * val store = testStore(initialState, reducer, handler)
 * store.send(Action.Started)
 * advanceUntilIdle()
 * store.expectAction(Action.Started)
 *     .expectAction(Action.Loaded(notes))
 *     .expectState(State.Content(notes))
 * store.finish()
 * ```
 *
 * Every reduced action — effect-fed actions included — arrives through the store's observer
 * hook in reduction order and must be consumed by [expectAction]; [finish] fails on anything
 * left unasserted, so a test cannot silently ignore what its effects fed back. Everything
 * runs on the single test-scheduler thread, so assertions are safe after any advance. The
 * store surface ([state], [stateFlow], [send], [observe], [close]) passes straight through
 * to the wrapped [Store].
 *
 * A store with an effect that never completes on its own — an observation over a
 * `StateFlow`, a periodic tick — is driven with `advanceTimeBy` and ended with
 * `finish(cancelInFlightEffects = true)`. Never `advanceUntilIdle` such a store: the effect
 * keeps scheduling, the drain never returns, and neither `runTest`'s timeout nor a
 * Kotlin/Native test binary can pre-empt a loop that does not suspend.
 */
public class TestStore<S : Any, A> internal constructor(
    private val delegate: Store<S, A>,
    private val received: ArrayDeque<Reduction<S, A>>,
    private val drain: () -> Unit,
) {
    public val state: S
        get() = delegate.state

    public val stateFlow: StateFlow<S>
        get() = delegate.stateFlow

    public fun send(action: A) {
        delegate.send(action)
    }

    public fun observe(onEach: (S) -> Unit): Subscription = delegate.observe(onEach)

    public fun close() {
        delegate.close()
    }

    /** Consumes the next reduced action and asserts it equals [expected]. */
    public fun expectAction(expected: A): TestStore<S, A> =
        apply {
            nextReduction(expected)
        }

    /** Consumes the next reduced action and asserts it AND the state it produced. */
    public fun expectAction(
        expected: A,
        resulting: S,
    ): TestStore<S, A> =
        apply {
            val reduction = nextReduction(expected)
            if (reduction.next != resulting) {
                throw AssertionError(
                    "Action matched but its resulting state did not.\nExpected state:\n  $resulting\nbut reducing $expected produced:\n  ${reduction.next}",
                )
            }
        }

    public fun expectState(expected: S): TestStore<S, A> =
        apply {
            if (state != expected) {
                throw AssertionError("Expected state:\n  $expected\nbut the store holds:\n  $state")
            }
        }

    public fun expectNoMoreActions(): TestStore<S, A> =
        apply {
            if (received.isNotEmpty()) {
                throw AssertionError(
                    "Every reduced action must be asserted; still unasserted:\n  ${received.map { reduction -> reduction.action }}",
                )
            }
        }

    /**
     * The exhaustiveness gate: asserts every reduced action was expected AND no effect is
     * still in flight — a pending effect means either the scheduler was not advanced far
     * enough or the actions it would send were never going to be asserted — then closes.
     *
     * [cancelInFlightEffects] is the deliberate exception for an effect that never completes
     * on its own. The store closes first, which cancels every effect, the scheduler drains
     * what the cancellation left, and only the actions reduced before the close must have
     * been asserted.
     */
    public fun finish(cancelInFlightEffects: Boolean = false) {
        if (cancelInFlightEffects) {
            close()
            drain()
            expectNoMoreActions()
            return
        }
        try {
            expectNoMoreActions()
            val inFlight = delegate.activeEffects
            if (inFlight > 0) {
                throw AssertionError(
                    "$inFlight effect(s) still in flight at finish(); advance the test scheduler, assert the actions they " +
                        "will send, or finish(cancelInFlightEffects = true) for an effect that never completes on its own.",
                )
            }
        } finally {
            close()
        }
    }

    private fun nextReduction(expected: A): Reduction<S, A> {
        if (received.isEmpty()) {
            throw AssertionError("Expected action:\n  $expected\nbut no further actions were reduced.")
        }
        val reduction = received.removeFirst()
        if (reduction.action != expected) {
            throw AssertionError(
                "Expected action:\n  $expected\nbut the next reduced action was:\n  ${reduction.action}",
            )
        }
        return reduction
    }
}

internal class Reduction<S, A>(
    val action: A,
    val next: S,
)

/** Creates a [TestStore] driven by this scope's scheduler. */
public fun <S : Any, A, E> TestScope.testStore(
    initialState: S,
    reducer: Reducer<S, A, E>,
    handler: EffectHandler<E, A>,
    defects: DefectHandler<S, A, E> = rethrowingDefectHandler(),
): TestStore<S, A> {
    val received = ArrayDeque<Reduction<S, A>>()
    val delegate =
        Store(
            initialState = initialState,
            reducer = reducer,
            handler = handler,
            defects = defects,
            scope = StoreScope.Testing(StandardTestDispatcher(testScheduler)),
            observer = StoreObserver { _, action, next -> received.addLast(Reduction(action, next)) },
        )
    return TestStore(delegate, received, drain = { testScheduler.advanceUntilIdle() })
}
