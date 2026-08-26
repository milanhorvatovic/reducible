package io.github.milanhorvatovic.reducible.runtime

import kotlinx.coroutines.flow.Flow

/**
 * Feeds every value this flow emits into a store as an action — the building block for the
 * app-scoped-store rule: a screen store observes shared state through a long-running effect,
 * never by holding the other store directly. Call it from the effect-handler branch that owns
 * the observation, passing the handler's own `send`:
 *
 * ```
 * is ScreenEffect.ObserveSession ->
 *     sessionStore.stateFlow.feedInto(send) { session -> ScreenAction.SessionChanged(session) }
 * ```
 *
 * A [kotlinx.coroutines.flow.StateFlow] source delivers the current value immediately and
 * never completes, so the effect runs until cancelled. Scope it by who handles what it feeds:
 * [io.github.milanhorvatovic.reducible.EffectScope.Free] when every state handles the
 * action, state-scoped when leaving the requesting state class should end the observation —
 * and give it a stable [io.github.milanhorvatovic.reducible.EffectKey] so re-requesting
 * it replaces the running subscription instead of stacking a second one.
 */
public suspend fun <T, A> Flow<T>.feedInto(
    send: (A) -> Unit,
    action: (T) -> A,
) {
    collect { value -> send(action(value)) }
}
