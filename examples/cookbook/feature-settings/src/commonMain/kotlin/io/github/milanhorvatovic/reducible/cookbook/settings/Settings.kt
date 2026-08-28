package io.github.milanhorvatovic.reducible.cookbook.settings

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.KeyPolicy
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.serialization.Serializable

@Serializable
public enum class Units {
    Metric,
    Imperial,
}

@Serializable
public data class Settings(
    public val units: Units = Units.Metric,
    /** Makes the fake repositories fail on purpose, to walk the error paths from the UI. */
    public val failureInjection: Boolean = false,
)

/**
 * The app-scoped settings: one store per process in a dedicated scope, observed by
 * screens through effects. Edits apply to state at once and reach storage write-behind.
 */
public sealed interface SettingsState {
    public data object Loading : SettingsState

    public data class Ready(
        public val settings: Settings,
    ) : SettingsState
}

public sealed interface SettingsAction {
    public data object Started : SettingsAction

    public data class Loaded(
        public val settings: Settings,
    ) : SettingsAction

    public data class UnitsChanged(
        public val units: Units,
    ) : SettingsAction

    public data object FailureInjectionToggled : SettingsAction

    /** The persist window closed with no further edit: the settings as they stand are due to be written. */
    public data object PersistDue : SettingsAction
}

public sealed interface SettingsEffect {
    public data object Load : SettingsEffect

    /** Keyed cancel-previous by [PersistWindowKey]: a burst of edits schedules one write. */
    public data object SchedulePersist : SettingsEffect

    /** Keyed ordered by [PersistKey]: writes land whole and in order; an edit mid-write queues behind it. */
    public data class Persist(
        public val settings: Settings,
    ) : SettingsEffect
}

internal data object PersistWindowKey : EffectKey

internal data object PersistKey : EffectKey {
    override val policy: KeyPolicy
        get() = KeyPolicy.Ordered
}

public val settingsReducer: Reducer<SettingsState, SettingsAction, SettingsEffect> =
    Reducer { state, action ->
        when (state) {
            SettingsState.Loading -> {
                when (action) {
                    SettingsAction.Started -> state.withEffect(SettingsEffect.Load)
                    is SettingsAction.Loaded -> SettingsState.Ready(action.settings).only()
                    is SettingsAction.UnitsChanged -> state.only()
                    SettingsAction.FailureInjectionToggled -> state.only()
                    SettingsAction.PersistDue -> state.only()
                }
            }

            is SettingsState.Ready -> {
                when (action) {
                    is SettingsAction.UnitsChanged -> {
                        state.edited(state.settings.copy(units = action.units))
                    }

                    SettingsAction.FailureInjectionToggled -> {
                        state.edited(state.settings.copy(failureInjection = !state.settings.failureInjection))
                    }

                    SettingsAction.Started -> {
                        state.only()
                    }

                    is SettingsAction.Loaded -> {
                        state.only()
                    }

                    SettingsAction.PersistDue -> {
                        state.withEffect(SettingsEffect.Persist(state.settings), key = PersistKey)
                    }
                }
            }
        }
    }

private fun SettingsState.Ready.edited(settings: Settings) =
    SettingsState.Ready(settings).withEffect(SettingsEffect.SchedulePersist, key = PersistWindowKey)

/** The feature's storage boundary; implementations are wired by the consuming app. */
public interface SettingsRepository {
    public suspend fun load(): Settings

    public suspend fun save(settings: Settings)
}

/**
 * [persistWindow] is the suspension that makes persistence write-behind: a newer edit cancels
 * an older [SettingsEffect.SchedulePersist] still waiting inside the window, so a burst
 * schedules one write, while the write itself runs under an ordered key so an edit arriving
 * mid-write queues behind it instead of cancelling it. Supplied by the wiring, since features
 * carry no coroutines.
 */
public fun settingsEffectHandler(
    repository: SettingsRepository,
    persistWindow: suspend () -> Unit,
): EffectHandler<SettingsEffect, SettingsAction> =
    EffectHandler { effect, send ->
        when (effect) {
            SettingsEffect.Load -> {
                send(SettingsAction.Loaded(repository.load()))
            }

            SettingsEffect.SchedulePersist -> {
                persistWindow()
                send(SettingsAction.PersistDue)
            }

            is SettingsEffect.Persist -> {
                repository.save(effect.settings)
            }
        }
    }
