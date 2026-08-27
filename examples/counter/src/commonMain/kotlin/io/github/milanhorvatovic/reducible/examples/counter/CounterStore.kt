package io.github.milanhorvatovic.reducible.examples.counter

import io.github.milanhorvatovic.reducible.runtime.Store
import io.github.milanhorvatovic.reducible.runtime.StoreScope
import io.github.milanhorvatovic.reducible.runtime.ViewStore
import io.github.milanhorvatovic.reducible.runtime.view
import kotlinx.coroutines.delay

/** What the screen renders: the phase is a boolean to it, and the state class stays inside the feature. */
public data class CounterViewState(
    public val count: Int,
    public val ticking: Boolean,
)

public fun counterViewState(state: CounterState): CounterViewState = CounterViewState(state.count, state is CounterState.Ticking)

/**
 * The factory a platform holder calls. It owns the wiring — the reducer, the handler with its
 * real clock, the effect type that never leaves this function — and takes only what the holder
 * decides: the [scope] the store reduces in and lives as long as.
 */
public fun counterStore(
    scope: StoreScope = StoreScope.Main,
    tick: suspend () -> Unit = { delay(1_000) },
): Store<CounterState, CounterAction> =
    Store(
        initialState = CounterState.Idle(),
        reducer = counterReducer,
        handler = counterEffectHandler(tick),
        scope = scope,
    )

/** The screen's projection of the store: rendered state in, [CounterAction.Ui] out. */
public fun counterView(store: Store<CounterState, CounterAction>): ViewStore<CounterViewState, CounterAction.Ui> =
    store.view(state = ::counterViewState, action = { action -> action })
