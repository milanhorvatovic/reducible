package io.github.milanhorvatovic.reducible.cookbook.recipes

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList

/**
 * The list as it renders: the search narrows the rows here, not in the feature state, so a
 * download keeps running for a row the current query hides. Rows carry the fields the list
 * shows and the download phase without the recipe body.
 */
public sealed interface RecipesViewState {
    public data object Loading : RecipesViewState

    public data class Content(
        public val rows: PersistentList<RecipeRowViewState>,
        public val query: String,
        public val refreshing: Boolean,
        public val failure: RecipesError?,
        /** True while a search narrows the list, so an empty list can say why. */
        public val narrowed: Boolean,
    ) : RecipesViewState

    public data class Failed(
        public val error: RecipesError,
    ) : RecipesViewState
}

public data class RecipeRowViewState(
    public val id: String,
    public val title: String,
    public val summary: String,
    public val minutes: Int,
    public val servings: Int,
    public val favorite: Boolean,
    public val download: DownloadStatus,
)

public sealed interface DownloadStatus {
    public data object Remote : DownloadStatus

    public data class Downloading(
        public val percent: Int,
    ) : DownloadStatus

    public data object Offline : DownloadStatus

    public data class Failed(
        public val error: RecipesError,
    ) : DownloadStatus
}

public fun recipesViewState(state: RecipesState): RecipesViewState =
    when (state) {
        RecipesState.Loading -> {
            RecipesViewState.Loading
        }

        is RecipesState.Loaded -> {
            val matching = state.matching
            RecipesViewState.Content(
                rows =
                    state.rows
                        .filter { row -> matching == null || row.recipe.id in matching }
                        .map { row -> row.toViewState() }
                        .toPersistentList(),
                query = state.query,
                refreshing = state.refreshing,
                failure = state.failure,
                narrowed = matching != null,
            )
        }

        is RecipesState.LoadFailed -> {
            RecipesViewState.Failed(state.error)
        }
    }

private fun RecipeRowState.toViewState(): RecipeRowViewState =
    RecipeRowViewState(
        id = recipe.id,
        title = recipe.title,
        summary = recipe.summary,
        minutes = recipe.minutes,
        servings = recipe.servings,
        favorite = favorite,
        download =
            when (this) {
                is RecipeRowState.Remote -> DownloadStatus.Remote
                is RecipeRowState.Downloading -> DownloadStatus.Downloading(percent)
                is RecipeRowState.Offline -> DownloadStatus.Offline
                is RecipeRowState.Failed -> DownloadStatus.Failed(error)
            },
    )
