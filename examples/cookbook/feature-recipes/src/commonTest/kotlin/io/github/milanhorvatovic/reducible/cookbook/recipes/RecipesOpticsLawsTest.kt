package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.IdentifiedAction
import io.github.milanhorvatovic.reducible.test.assertLensLaws
import io.github.milanhorvatovic.reducible.test.assertOptionalLaws
import io.github.milanhorvatovic.reducible.test.assertPrismLaws
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

private val listed = RecipesState.Loaded(persistentListOf(RecipeRowState.Remote(soup), RecipeRowState.Remote(pancakes)), query = "so")

class RecipesOpticsLawsTest {
    @Test
    fun loaded_prism_matches_the_loaded_case_only() {
        loadedPrism.assertPrismLaws(
            matching = listed,
            value = RecipesState.Loaded(persistentListOf(RecipeRowState.Offline(focaccia, favorite = true))),
            RecipesState.Loading,
            RecipesState.LoadFailed(RecipesError.Offline),
        )
    }

    @Test
    fun rows_lens_focuses_the_row_list() {
        rowsLens.assertLensLaws(listed, persistentListOf(RecipeRowState.Downloading(soup, favorite = false, percent = 10)))
    }

    @Test
    fun rows_optional_composes_prism_and_lens() {
        rowsOptional.assertOptionalLaws(
            present = listed,
            replacement = persistentListOf(RecipeRowState.Failed(pancakes, favorite = false, RecipesError.Offline)),
            RecipesState.Loading,
            RecipesState.LoadFailed(RecipesError.Offline),
        )
    }

    @Test
    fun row_action_prism_matches_row_actions_only() {
        rowActionPrism.assertPrismLaws(
            matching = RecipesAction.Row(IdentifiedAction("soup", RecipeRowAction.DownloadClicked)),
            value = IdentifiedAction("pancakes", RecipeRowAction.Completed),
            RecipesAction.Retry,
            RecipesAction.Started,
            RecipesAction.QueryChanged("x"),
        )
    }
}
