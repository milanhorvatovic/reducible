package io.github.milanhorvatovic.reducible.cookbook.session

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect

/**
 * The app root's view of the session, which decides which flow is on screen. [Starting]
 * lasts until the first session value arrives from the background store, so the sign-in
 * screen never flashes for a user who is already signed in.
 */
public sealed interface ShellState {
    public data object Starting : ShellState

    public data object SignedOut : ShellState

    public data class SignedIn(
        public val account: Account,
    ) : ShellState
}

public sealed interface ShellAction {
    public sealed interface Ui : ShellAction

    public data object SignOut : Ui

    public data object Started : ShellAction

    public data class SessionChanged(
        public val session: SessionState,
    ) : ShellAction
}

public sealed interface ShellEffect {
    public data object ObserveSession : ShellEffect

    public data object SignOut : ShellEffect
}

internal data object ShellObserveKey : EffectKey

public val shellReducer: Reducer<ShellState, ShellAction, ShellEffect> =
    Reducer { state, action ->
        when (action) {
            ShellAction.Started -> {
                state.withEffect(ShellEffect.ObserveSession, EffectScope.Free, ShellObserveKey)
            }

            is ShellAction.SessionChanged -> {
                when (val session = action.session) {
                    is SessionState.SignedOut -> ShellState.SignedOut.only()
                    is SessionState.SigningIn -> ShellState.SignedOut.only()
                    is SessionState.SignedIn -> ShellState.SignedIn(session.account).only()
                }
            }

            ShellAction.SignOut -> {
                state.withEffect(ShellEffect.SignOut)
            }
        }
    }

public fun shellEffectHandler(session: SessionGateway): EffectHandler<ShellEffect, ShellAction> =
    EffectHandler { effect, send ->
        when (effect) {
            ShellEffect.ObserveSession -> session.observe { state -> send(ShellAction.SessionChanged(state)) }
            ShellEffect.SignOut -> session.signOut()
        }
    }
