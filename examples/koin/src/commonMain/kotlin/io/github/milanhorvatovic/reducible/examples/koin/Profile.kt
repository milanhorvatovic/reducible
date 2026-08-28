package io.github.milanhorvatovic.reducible.examples.koin

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect

public data class Profile(
    public val name: String,
    public val email: String,
)

/** The boundary the effect handler calls. What stands behind it is the composition root's decision. */
public fun interface ProfileRepository {
    public suspend fun load(): Profile
}

/** The repository's expected failure, typed so the handler can turn it into an action. */
public class ProfileUnavailable(
    public val reason: String,
) : Exception(reason)

public sealed interface ProfileState {
    public data object Loading : ProfileState

    public data class Loaded(
        public val profile: Profile,
    ) : ProfileState

    public data class Failed(
        public val reason: String,
    ) : ProfileState
}

public sealed interface ProfileAction {
    public sealed interface Ui : ProfileAction

    public data object Retry : Ui

    public data object Started : ProfileAction

    public data class Loaded(
        public val profile: Profile,
    ) : ProfileAction

    public data class Failed(
        public val reason: String,
    ) : ProfileAction
}

public sealed interface ProfileEffect {
    public data object Load : ProfileEffect
}

public val profileReducer: Reducer<ProfileState, ProfileAction, ProfileEffect> =
    Reducer { state, action ->
        when (action) {
            ProfileAction.Started -> {
                // State-aware: a store that starts in Loaded does not fetch again.
                if (state is ProfileState.Loading) {
                    state.withEffect(ProfileEffect.Load)
                } else {
                    state.only()
                }
            }

            ProfileAction.Retry -> {
                if (state is ProfileState.Failed) {
                    ProfileState.Loading.withEffect(ProfileEffect.Load)
                } else {
                    state.only()
                }
            }

            is ProfileAction.Loaded -> {
                ProfileState.Loaded(action.profile).only()
            }

            is ProfileAction.Failed -> {
                ProfileState.Failed(action.reason).only()
            }
        }
    }

/** Total: the repository's expected failure becomes [ProfileAction.Failed]; anything else escaping is a defect. */
public fun profileEffectHandler(repository: ProfileRepository): EffectHandler<ProfileEffect, ProfileAction> =
    EffectHandler { effect, send ->
        when (effect) {
            ProfileEffect.Load -> {
                val outcome =
                    try {
                        ProfileAction.Loaded(repository.load())
                    } catch (unavailable: ProfileUnavailable) {
                        ProfileAction.Failed(unavailable.reason)
                    }
                send(outcome)
            }
        }
    }
