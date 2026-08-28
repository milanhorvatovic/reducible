package io.github.milanhorvatovic.reducible.cookbook.settings

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Event
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.andSend
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect

/**
 * The settings screen over the app-scoped settings store. It holds no copy of its own: an
 * edit is forwarded through the gateway and the new value comes back through the observation,
 * the same path every other screen follows the settings on.
 */
public sealed interface SettingsScreenState {
    public data object Loading : SettingsScreenState

    public data class Ready(
        public val settings: Settings,
    ) : SettingsScreenState
}

public sealed interface SettingsScreenAction {
    public sealed interface Ui : SettingsScreenAction

    public data class UnitsSelected(
        public val units: Units,
    ) : Ui

    public data object FailureInjectionToggled : Ui

    public data object BackClicked : Ui

    public data object Started : SettingsScreenAction

    public data class SettingsChanged(
        public val settings: Settings,
    ) : SettingsScreenAction
}

/** What the settings screen asks its holder to do; an [Event], so the store publishes it. */
public sealed interface SettingsScreenEvent :
    SettingsScreenAction,
    Event {
    public data object Close : SettingsScreenEvent
}

public sealed interface SettingsScreenEffect {
    public data object Observe : SettingsScreenEffect

    public data class ChangeUnits(
        public val units: Units,
    ) : SettingsScreenEffect

    public data object ToggleFailureInjection : SettingsScreenEffect
}

internal data object SettingsScreenObserveKey : EffectKey

public val settingsScreenReducer: Reducer<SettingsScreenState, SettingsScreenAction, SettingsScreenEffect> =
    Reducer { state, action ->
        when (action) {
            // Free and keyed: the observation outlives Loading-to-Ready and a restart replaces it.
            SettingsScreenAction.Started -> {
                state.withEffect(SettingsScreenEffect.Observe, EffectScope.Free, SettingsScreenObserveKey)
            }

            is SettingsScreenAction.SettingsChanged -> {
                SettingsScreenState.Ready(action.settings).only()
            }

            is SettingsScreenAction.UnitsSelected -> {
                state.withEffect(SettingsScreenEffect.ChangeUnits(action.units))
            }

            SettingsScreenAction.FailureInjectionToggled -> {
                state.withEffect(SettingsScreenEffect.ToggleFailureInjection)
            }

            SettingsScreenAction.BackClicked -> {
                state.andSend(SettingsScreenEvent.Close)
            }

            SettingsScreenEvent.Close -> {
                state.only()
            }
        }
    }

public fun settingsScreenEffectHandler(settings: SettingsGateway): EffectHandler<SettingsScreenEffect, SettingsScreenAction> =
    EffectHandler { effect, send ->
        when (effect) {
            SettingsScreenEffect.Observe -> settings.observe { current -> send(SettingsScreenAction.SettingsChanged(current)) }
            is SettingsScreenEffect.ChangeUnits -> settings.changeUnits(effect.units)
            SettingsScreenEffect.ToggleFailureInjection -> settings.toggleFailureInjection()
        }
    }

/** Controls are disabled until the first value arrives; until then they show the defaults. */
public data class SettingsViewState(
    public val ready: Boolean,
    public val units: Units,
    public val failureInjection: Boolean,
)

public fun settingsViewState(state: SettingsScreenState): SettingsViewState =
    when (state) {
        SettingsScreenState.Loading -> {
            SettingsViewState(ready = false, units = Units.Metric, failureInjection = false)
        }

        is SettingsScreenState.Ready -> {
            SettingsViewState(ready = true, units = state.settings.units, failureInjection = state.settings.failureInjection)
        }
    }
