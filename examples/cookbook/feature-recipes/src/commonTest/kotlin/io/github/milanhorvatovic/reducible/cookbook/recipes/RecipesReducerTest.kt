package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectOwner
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.IdentifiedAction
import io.github.milanhorvatovic.reducible.IdentifiedEffectKey
import io.github.milanhorvatovic.reducible.test.given
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val listed = RecipesState.Loaded(persistentListOf(RecipeRowState.Remote(soup), RecipeRowState.Remote(pancakes)))

class RecipesReducerTest {
    @Test
    fun navigation_intents_become_events_for_the_holder_and_change_nothing() {
        recipesReducer
            .given(listed)
            .on(RecipesAction.RecipeClicked("soup"))
            .expect(listed)
            .expectNoEffects()
            .expectFollowUps(RecipesEvent.OpenRecipe("soup"))
            .andOn(RecipesEvent.OpenRecipe("soup"))
            .expect(listed)
            .expectNoEffects()
            .expectFollowUps()
        recipesReducer.given(listed).on(RecipesAction.SettingsClicked).expectFollowUps(RecipesEvent.OpenSettings)
        recipesReducer.given(listed).on(RecipesAction.DebugClicked).expectFollowUps(RecipesEvent.OpenDebug)
        recipesReducer.given(RecipesState.Loading).on(RecipesAction.SignOutClicked).expectFollowUps(RecipesEvent.SignOut)
    }

    @Test
    fun start_loads_and_lists_every_recipe_as_remote() {
        recipesReducer
            .given(RecipesState.Loading)
            .on(RecipesAction.Started)
            .expect(RecipesState.Loading)
            .expectEnvelopes(EffectEnvelope(RecipesEffect.Load, EffectScope.StateScoped, LoadKey))
            .andOn(RecipesAction.Loaded(listOf(soup, pancakes)))
            .expect(listed)
            .expectNoEffects()
    }

    @Test
    fun row_actions_are_routed_by_identity_and_their_effects_re_keyed_per_row() {
        val reduced = recipesReducer.reduce(listed, recipeRowAction("pancakes", RecipeRowAction.DownloadClicked))

        assertEquals(
            listed.copy(
                rows = persistentListOf(RecipeRowState.Remote(soup), RecipeRowState.Downloading(pancakes, favorite = false, percent = 0)),
            ),
            reduced.state,
        )
        val envelope = reduced.effects.single()
        assertEquals(RecipesEffect.Row("pancakes", RecipeRowEffect.Download("pancakes")), envelope.effect)
        assertEquals(IdentifiedEffectKey("pancakes", DownloadKey), envelope.key)

        // The owner is the row's identity: dismissing pancakes cancels the download, dismissing soup does not.
        @Suppress("UNCHECKED_CAST")
        val owner = envelope.owner as EffectOwner<RecipesState>
        assertTrue(owner.resolvesIn(reduced.state))
        assertTrue(owner.resolvesIn(recipesReducer.reduce(reduced.state, RecipesAction.Dismissed("soup")).state))
        assertFalse(owner.resolvesIn(recipesReducer.reduce(reduced.state, RecipesAction.Dismissed("pancakes")).state))
        // Cancelling leaves the Downloading phase inside the row: the download is cancelled too.
        assertFalse(
            owner.resolvesIn(recipesReducer.reduce(reduced.state, recipeRowAction("pancakes", RecipeRowAction.CancelClicked)).state),
        )
    }

    @Test
    fun a_row_action_for_an_unknown_row_is_dropped() {
        recipesReducer
            .given(listed)
            .on(recipeRowAction("focaccia", RecipeRowAction.DownloadClicked))
            .expect(listed)
            .expectNoEffects()
    }

    @Test
    fun refresh_merges_fresh_data_by_identity_and_keeps_each_row_phase() {
        val downloading =
            listed.copy(
                rows = persistentListOf(RecipeRowState.Downloading(soup, favorite = true, percent = 40), RecipeRowState.Remote(pancakes)),
            )
        val renamed = soup.copy(title = "Roasted tomato soup")

        recipesReducer
            .given(downloading)
            .on(RecipesAction.Refresh)
            .expect(downloading.copy(refreshing = true))
            .expectEnvelopes(EffectEnvelope(RecipesEffect.Load, EffectScope.StateScoped, LoadKey))
            .andOn(RecipesAction.Loaded(listOf(renamed, focaccia)))
            .expect(
                downloading.copy(
                    rows =
                        persistentListOf(
                            RecipeRowState.Downloading(renamed, favorite = true, percent = 40),
                            RecipeRowState.Remote(focaccia),
                        ),
                ),
            ).expectNoEffects()
    }

    @Test
    fun restart_after_process_death_resets_interrupted_downloads_and_refreshes() {
        val restored =
            listed.copy(
                rows =
                    persistentListOf(
                        RecipeRowState.Downloading(soup, favorite = false, percent = 40),
                        RecipeRowState.Offline(pancakes, favorite = true),
                    ),
            )

        recipesReducer
            .given(restored)
            .on(RecipesAction.Started)
            .expect(
                restored.copy(
                    rows = persistentListOf(RecipeRowState.Remote(soup), RecipeRowState.Offline(pancakes, favorite = true)),
                    refreshing = true,
                ),
            ).expectEnvelopes(EffectEnvelope(RecipesEffect.Load, EffectScope.StateScoped, LoadKey))
    }

    @Test
    fun search_is_keyed_and_stale_results_are_dropped() {
        recipesReducer
            .given(listed)
            .on(RecipesAction.QueryChanged("to"))
            .expect(listed.copy(query = "to"))
            .expectEnvelopes(EffectEnvelope(RecipesEffect.Search("to"), EffectScope.StateScoped, SearchKey))
            .andOn(RecipesAction.QueryChanged("tom"))
            .expect(listed.copy(query = "tom"))
            .expectEnvelopes(EffectEnvelope(RecipesEffect.Search("tom"), EffectScope.StateScoped, SearchKey))
            .andOn(RecipesAction.Searched("to", setOf("soup", "pancakes")))
            .expect(listed.copy(query = "tom"))
            .expectNoEffects()
            .andOn(RecipesAction.Searched("tom", setOf("soup")))
            .expect(listed.copy(query = "tom", matching = setOf("soup")))
            .expectNoEffects()
    }

    @Test
    fun a_blank_query_clears_the_search_without_asking_the_repository() {
        recipesReducer
            .given(listed.copy(query = "tom", matching = setOf("soup")))
            .on(RecipesAction.QueryChanged(" "))
            .expect(listed)
            .expectNoEffects()
    }

    @Test
    fun dismiss_removes_the_row_and_keeps_it_out_of_refreshes() {
        recipesReducer
            .given(listed)
            .on(RecipesAction.Dismissed("soup"))
            .expect(listed.copy(rows = persistentListOf(RecipeRowState.Remote(pancakes)), hidden = setOf("soup")))
            .expectNoEffects()
            .andOn(RecipesAction.Loaded(listOf(soup, pancakes, focaccia)))
            .expect(
                listed.copy(
                    rows = persistentListOf(RecipeRowState.Remote(pancakes), RecipeRowState.Remote(focaccia)),
                    hidden = setOf("soup"),
                ),
            ).expectNoEffects()
    }

    @Test
    fun a_failed_first_load_is_retryable_and_a_failed_refresh_keeps_the_rows() {
        recipesReducer
            .given(RecipesState.Loading)
            .on(RecipesAction.LoadFailed(RecipesError.Offline))
            .expect(RecipesState.LoadFailed(RecipesError.Offline))
            .expectNoEffects()
            .andOn(RecipesAction.Retry)
            .expect(RecipesState.Loading)
            .expectEnvelopes(EffectEnvelope(RecipesEffect.Load, EffectScope.StateScoped, LoadKey))

        recipesReducer
            .given(listed.copy(refreshing = true))
            .on(RecipesAction.LoadFailed(RecipesError.Offline))
            .expect(listed.copy(failure = RecipesError.Offline))
            .expectNoEffects()
    }

    @Test
    fun state_round_trips_through_json_for_process_death() {
        val state: RecipesState =
            listed.copy(
                rows =
                    persistentListOf(
                        RecipeRowState.Downloading(soup, favorite = true, percent = 40),
                        RecipeRowState.Failed(pancakes, favorite = false, RecipesError.Offline),
                    ),
                query = "tom",
                matching = setOf("soup"),
                hidden = setOf("focaccia"),
            )

        val json = Json.encodeToString(RecipesState.serializer(), state)

        assertEquals(state, Json.decodeFromString(RecipesState.serializer(), json))
    }

    @Test
    fun row_action_helper_embeds_by_identity() {
        assertEquals(
            RecipesAction.Row(IdentifiedAction("soup", RecipeRowAction.FavoriteToggled)),
            recipeRowAction("soup", RecipeRowAction.FavoriteToggled),
        )
    }
}
