package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.Reduced
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.serialization.Serializable

/**
 * One row of the recipes list, a child feature in its own right. The offline download is a
 * phase of the row rather than a flag, so the state-scoped download effect leaves with the
 * phase: cancel, and the download stops with no bookkeeping.
 */
@Serializable
public sealed interface RecipeRowState {
    public val recipe: Recipe
    public val favorite: Boolean

    @Serializable
    public data class Remote(
        override val recipe: Recipe,
        override val favorite: Boolean = false,
    ) : RecipeRowState

    @Serializable
    public data class Downloading(
        override val recipe: Recipe,
        override val favorite: Boolean,
        public val percent: Int,
    ) : RecipeRowState

    @Serializable
    public data class Offline(
        override val recipe: Recipe,
        override val favorite: Boolean,
    ) : RecipeRowState

    @Serializable
    public data class Failed(
        override val recipe: Recipe,
        override val favorite: Boolean,
        public val error: RecipesError,
    ) : RecipeRowState
}

public sealed interface RecipeRowAction {
    public sealed interface Ui : RecipeRowAction

    public data object DownloadClicked : Ui

    public data object CancelClicked : Ui

    public data object RemoveOfflineClicked : Ui

    public data object FavoriteToggled : Ui

    public data class Progress(
        public val percent: Int,
    ) : RecipeRowAction

    public data object Completed : RecipeRowAction

    public data class DownloadFailed(
        public val error: RecipesError,
    ) : RecipeRowAction
}

public sealed interface RecipeRowEffect {
    /** Keyed per row once embedded by `forEachIdentified`, so rows downloading at once never cancel each other. */
    public data class Download(
        public val recipeId: String,
    ) : RecipeRowEffect
}

internal data object DownloadKey : EffectKey

public val recipeRowReducer: Reducer<RecipeRowState, RecipeRowAction, RecipeRowEffect> =
    Reducer { state, action ->
        when (state) {
            is RecipeRowState.Remote -> {
                when (action) {
                    RecipeRowAction.DownloadClicked -> state.downloading()
                    RecipeRowAction.FavoriteToggled -> state.copy(favorite = !state.favorite).only()
                    RecipeRowAction.CancelClicked -> state.only()
                    RecipeRowAction.RemoveOfflineClicked -> state.only()
                    is RecipeRowAction.Progress -> state.only()
                    RecipeRowAction.Completed -> state.only()
                    is RecipeRowAction.DownloadFailed -> state.only()
                }
            }

            is RecipeRowState.Downloading -> {
                when (action) {
                    RecipeRowAction.CancelClicked -> RecipeRowState.Remote(state.recipe, state.favorite).only()
                    is RecipeRowAction.Progress -> state.copy(percent = action.percent).only()
                    RecipeRowAction.Completed -> RecipeRowState.Offline(state.recipe, state.favorite).only()
                    is RecipeRowAction.DownloadFailed -> RecipeRowState.Failed(state.recipe, state.favorite, action.error).only()
                    RecipeRowAction.FavoriteToggled -> state.copy(favorite = !state.favorite).only()
                    RecipeRowAction.DownloadClicked -> state.only()
                    RecipeRowAction.RemoveOfflineClicked -> state.only()
                }
            }

            is RecipeRowState.Offline -> {
                when (action) {
                    RecipeRowAction.RemoveOfflineClicked -> RecipeRowState.Remote(state.recipe, state.favorite).only()
                    RecipeRowAction.FavoriteToggled -> state.copy(favorite = !state.favorite).only()
                    RecipeRowAction.DownloadClicked -> state.only()
                    RecipeRowAction.CancelClicked -> state.only()
                    is RecipeRowAction.Progress -> state.only()
                    RecipeRowAction.Completed -> state.only()
                    is RecipeRowAction.DownloadFailed -> state.only()
                }
            }

            is RecipeRowState.Failed -> {
                when (action) {
                    RecipeRowAction.DownloadClicked -> state.downloading()
                    RecipeRowAction.FavoriteToggled -> state.copy(favorite = !state.favorite).only()
                    RecipeRowAction.CancelClicked -> state.only()
                    RecipeRowAction.RemoveOfflineClicked -> state.only()
                    is RecipeRowAction.Progress -> state.only()
                    RecipeRowAction.Completed -> state.only()
                    is RecipeRowAction.DownloadFailed -> state.only()
                }
            }
        }
    }

private fun RecipeRowState.downloading(): Reduced<RecipeRowState, RecipeRowAction, RecipeRowEffect> =
    RecipeRowState
        .Downloading(recipe, favorite, percent = 0)
        .withEffect(RecipeRowEffect.Download(recipe.id), key = DownloadKey)

/** Replaces the recipe data while keeping the row's phase and favorite — what a refresh does to a row. */
internal fun RecipeRowState.withRecipe(recipe: Recipe): RecipeRowState =
    when (this) {
        is RecipeRowState.Remote -> copy(recipe = recipe)
        is RecipeRowState.Downloading -> copy(recipe = recipe)
        is RecipeRowState.Offline -> copy(recipe = recipe)
        is RecipeRowState.Failed -> copy(recipe = recipe)
    }

/** Handlers-total: an expected download failure becomes the row's typed failure action. */
public fun recipeRowEffectHandler(downloads: RecipeDownloads): EffectHandler<RecipeRowEffect, RecipeRowAction> =
    EffectHandler { effect, send ->
        when (effect) {
            is RecipeRowEffect.Download -> {
                try {
                    downloads.download(effect.recipeId) { percent -> send(RecipeRowAction.Progress(percent)) }
                    send(RecipeRowAction.Completed)
                } catch (failure: RecipesException) {
                    send(RecipeRowAction.DownloadFailed(failure.error))
                }
            }
        }
    }
