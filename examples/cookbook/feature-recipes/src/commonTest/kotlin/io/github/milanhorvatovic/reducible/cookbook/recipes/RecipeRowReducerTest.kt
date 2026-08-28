package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.test.given
import kotlin.test.Test

class RecipeRowReducerTest {
    @Test
    fun download_is_a_keyed_phase_of_the_row() {
        recipeRowReducer
            .given(RecipeRowState.Remote(soup))
            .on(RecipeRowAction.DownloadClicked)
            .expect(RecipeRowState.Downloading(soup, favorite = false, percent = 0))
            .expectEnvelopes(EffectEnvelope(RecipeRowEffect.Download("soup"), EffectScope.StateScoped, DownloadKey))
            .andOn(RecipeRowAction.Progress(40))
            .expect(RecipeRowState.Downloading(soup, favorite = false, percent = 40))
            .expectNoEffects()
            .andOn(RecipeRowAction.Completed)
            .expect(RecipeRowState.Offline(soup, favorite = false))
            .expectNoEffects()
            .andOn(RecipeRowAction.RemoveOfflineClicked)
            .expect(RecipeRowState.Remote(soup))
            .expectNoEffects()
    }

    @Test
    fun cancel_leaves_the_downloading_phase_which_is_what_ends_the_effect() {
        recipeRowReducer
            .given(RecipeRowState.Downloading(soup, favorite = true, percent = 60))
            .on(RecipeRowAction.CancelClicked)
            .expect(RecipeRowState.Remote(soup, favorite = true))
            .expectNoEffects()
    }

    @Test
    fun a_failed_download_is_retryable() {
        recipeRowReducer
            .given(RecipeRowState.Downloading(soup, favorite = false, percent = 40))
            .on(RecipeRowAction.DownloadFailed(RecipesError.Offline))
            .expect(RecipeRowState.Failed(soup, favorite = false, RecipesError.Offline))
            .expectNoEffects()
            .andOn(RecipeRowAction.DownloadClicked)
            .expect(RecipeRowState.Downloading(soup, favorite = false, percent = 0))
            .expectEffects(RecipeRowEffect.Download("soup"))
    }

    @Test
    fun favorite_toggles_in_every_phase_without_disturbing_it() {
        recipeRowReducer
            .given(RecipeRowState.Downloading(soup, favorite = false, percent = 80))
            .on(RecipeRowAction.FavoriteToggled)
            .expect(RecipeRowState.Downloading(soup, favorite = true, percent = 80))
            .expectNoEffects()

        recipeRowReducer
            .given(RecipeRowState.Offline(soup, favorite = true))
            .on(RecipeRowAction.FavoriteToggled)
            .expect(RecipeRowState.Offline(soup, favorite = false))
            .expectNoEffects()
    }

    @Test
    fun effect_fed_actions_outside_the_downloading_phase_are_ignored() {
        recipeRowReducer
            .given(RecipeRowState.Offline(soup, favorite = false))
            .on(RecipeRowAction.Progress(10))
            .expect(RecipeRowState.Offline(soup, favorite = false))
            .expectNoEffects()
            .andOn(RecipeRowAction.Completed)
            .expect(RecipeRowState.Offline(soup, favorite = false))
            .expectNoEffects()
    }
}
