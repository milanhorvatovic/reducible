package io.github.milanhorvatovic.reducible.cookbook.recipes

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

private val soupRow =
    RecipeRowViewState(
        "soup",
        "Tomato soup",
        "Quick weeknight soup",
        minutes = 30,
        servings = 4,
        favorite = false,
        download = DownloadStatus.Remote,
    )

class RecipesViewStateTest {
    @Test
    fun a_search_narrows_the_rendered_rows_but_never_the_feature_state() {
        val state =
            RecipesState.Loaded(
                persistentListOf(RecipeRowState.Remote(soup), RecipeRowState.Downloading(pancakes, favorite = true, percent = 50)),
                query = "tom",
                matching = setOf("soup"),
            )

        assertEquals(
            RecipesViewState.Content(persistentListOf(soupRow), query = "tom", refreshing = false, failure = null, narrowed = true),
            recipesViewState(state),
        )
    }

    @Test
    fun rows_carry_their_download_phase_without_the_recipe_body() {
        val state =
            RecipesState.Loaded(
                persistentListOf(
                    RecipeRowState.Downloading(soup, favorite = true, percent = 50),
                    RecipeRowState.Failed(pancakes, favorite = false, RecipesError.Offline),
                ),
                refreshing = true,
                failure = RecipesError.Offline,
            )

        assertEquals(
            RecipesViewState.Content(
                persistentListOf(
                    soupRow.copy(favorite = true, download = DownloadStatus.Downloading(50)),
                    RecipeRowViewState(
                        "pancakes",
                        "Pancakes",
                        "Sunday breakfast",
                        minutes = 20,
                        servings = 2,
                        favorite = false,
                        download = DownloadStatus.Failed(RecipesError.Offline),
                    ),
                ),
                query = "",
                refreshing = true,
                failure = RecipesError.Offline,
                narrowed = false,
            ),
            recipesViewState(state),
        )
    }

    @Test
    fun loading_and_failure_map_directly() {
        assertEquals(RecipesViewState.Loading, recipesViewState(RecipesState.Loading))
        assertEquals(RecipesViewState.Failed(RecipesError.Offline), recipesViewState(RecipesState.LoadFailed(RecipesError.Offline)))
    }
}
