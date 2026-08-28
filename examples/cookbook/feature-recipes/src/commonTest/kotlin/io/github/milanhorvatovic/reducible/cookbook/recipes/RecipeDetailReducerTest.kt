package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectOwner
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.IdentifiedEffectKey
import io.github.milanhorvatovic.reducible.cookbook.notes.EditorAction
import io.github.milanhorvatovic.reducible.cookbook.notes.EditorState
import io.github.milanhorvatovic.reducible.cookbook.notes.Note
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesAction
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesEffect
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesState
import io.github.milanhorvatovic.reducible.cookbook.settings.Settings
import io.github.milanhorvatovic.reducible.cookbook.settings.Units
import io.github.milanhorvatovic.reducible.test.given
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ready =
    RecipeDetailState.Ready(
        recipe = soup,
        servings = 4,
        units = Units.Metric,
        steps = persistentListOf(StepState.Idle(0, soup.steps[0])),
        notes = NotesState.Loading,
    )

private val startEnvelopes =
    arrayOf(
        EffectEnvelope<RecipeDetailEffect>(RecipeDetailEffect.Load("soup"), EffectScope.StateScoped, DetailLoadKey),
        EffectEnvelope(RecipeDetailEffect.ObserveSettings, EffectScope.Free, SettingsObserveKey),
    )

class RecipeDetailReducerTest {
    @Test
    fun back_asks_the_holder_to_close_and_changes_nothing() {
        val loading = RecipeDetailState.Loading("soup")
        recipeDetailReducer
            .given(loading)
            .on(RecipeDetailAction.BackClicked)
            .expect(loading)
            .expectNoEffects()
            .expectFollowUps(RecipeDetailEvent.Close)
            .andOn(RecipeDetailEvent.Close)
            .expect(loading)
            .expectNoEffects()
            .expectFollowUps()
    }

    @Test
    fun start_loads_keyed_and_observes_settings_free_then_loaded_builds_steps_and_a_loading_notes_child() {
        recipeDetailReducer
            .given(RecipeDetailState.Loading("soup"))
            .on(RecipeDetailAction.Started)
            .expect(RecipeDetailState.Loading("soup"))
            .expectEnvelopes(*startEnvelopes)
            .andOn(RecipeDetailAction.SettingsChanged(Settings(units = Units.Imperial)))
            .expect(RecipeDetailState.Loading("soup", Units.Imperial))
            .expectNoEffects()
            .andOn(RecipeDetailAction.Loaded(soup))
            .expect(ready.copy(units = Units.Imperial))
            .expectNoEffects()
            .expectFollowUps(RecipeDetailAction.Notes(NotesAction.Started))
    }

    @Test
    fun servings_scale_and_never_drop_below_one() {
        recipeDetailReducer
            .given(ready.copy(servings = 2))
            .on(RecipeDetailAction.Decreased)
            .expect(ready.copy(servings = 1))
            .expectNoEffects()
            .andOn(RecipeDetailAction.Decreased)
            .expect(ready.copy(servings = 1))
            .expectNoEffects()
            .andOn(RecipeDetailAction.Increased)
            .expect(ready.copy(servings = 2))
            .expectNoEffects()
    }

    @Test
    fun step_actions_are_routed_by_index_and_the_countdown_is_owned_by_its_step() {
        val reduced = recipeDetailReducer.reduce(ready, stepAction(0, StepAction.Start))

        assertEquals(ready.copy(steps = persistentListOf(StepState.Running(0, soup.steps[0], remainingSeconds = 900))), reduced.state)
        val envelope = reduced.effects.single()
        assertEquals(RecipeDetailEffect.Step(0, StepEffect.Countdown), envelope.effect)
        assertEquals(EffectScope.StateScoped, envelope.scope)

        @Suppress("UNCHECKED_CAST")
        val owner = envelope.owner as EffectOwner<RecipeDetailState>
        assertTrue(owner.resolvesIn(reduced.state))
        // Pausing leaves the Running class inside the row: the countdown's implicit owner stops resolving.
        assertFalse(owner.resolvesIn(recipeDetailReducer.reduce(reduced.state, stepAction(0, StepAction.Pause)).state))
        assertFalse(owner.resolvesIn(RecipeDetailState.Loading("soup")))
    }

    @Test
    fun notes_actions_reach_the_child_through_its_optic_and_its_effects_come_back_embedded() {
        recipeDetailReducer
            .given(ready)
            .on(RecipeDetailAction.Notes(NotesAction.Started))
            .expect(ready)
            .expectEffects(RecipeDetailEffect.Notes(NotesEffect.LoadNotes))
            .andOn(RecipeDetailAction.Notes(NotesAction.Loaded(listOf(Note("1", "Less salt")))))
            .expect(ready.copy(notes = NotesState.Content(persistentListOf(Note("1", "Less salt")), editor = null)))
            .expectNoEffects()
            .andOn(notesAction(NotesAction.AddClicked))
            .expect(ready.copy(notes = NotesState.Content(persistentListOf(Note("1", "Less salt")), EditorState())))
            .expectNoEffects()
            .andOn(notesAction(NotesAction.Editor(EditorAction.DraftChanged("Add basil"))))
            .expect(ready.copy(notes = NotesState.Content(persistentListOf(Note("1", "Less salt")), EditorState("Add basil"))))
            .expectEffects(RecipeDetailEffect.Notes(NotesEffect.ComputeHint("Add basil")))
    }

    @Test
    fun a_notes_effect_keeps_the_child_key_and_is_owned_by_the_notes_focus() {
        val editing = ready.copy(notes = NotesState.Content(persistentListOf(), EditorState()))
        val reduced = recipeDetailReducer.reduce(editing, notesAction(NotesAction.Editor(EditorAction.DraftChanged("x"))))

        val envelope = reduced.effects.single()
        assertEquals(RecipeDetailEffect.Notes(NotesEffect.ComputeHint("x")), envelope.effect)
        assertTrue(envelope.key != null, "the child's debounce key must survive embedding")

        @Suppress("UNCHECKED_CAST")
        val owner = envelope.owner as EffectOwner<RecipeDetailState>
        assertTrue(owner.resolvesIn(reduced.state))
        assertFalse(owner.resolvesIn(RecipeDetailState.LoadFailed("soup", RecipesError.Offline)))
    }

    @Test
    fun restart_after_process_death_pauses_running_timers_and_reloads() {
        val restored = ready.copy(steps = persistentListOf(StepState.Running(0, soup.steps[0], remainingSeconds = 42)))

        recipeDetailReducer
            .given(restored)
            .on(RecipeDetailAction.Started)
            .expect(ready.copy(steps = persistentListOf(StepState.Paused(0, soup.steps[0], remainingSeconds = 42))))
            .expectEnvelopes(*startEnvelopes)
            // The notes child restored alongside needs its own start to converge.
            .expectFollowUps(RecipeDetailAction.Notes(NotesAction.Started))
    }

    @Test
    fun a_refresh_keeps_the_phase_of_unchanged_steps_and_resets_changed_ones() {
        val running = ready.copy(steps = persistentListOf(StepState.Running(0, soup.steps[0], remainingSeconds = 42)))
        val rewritten = soup.copy(steps = listOf(Step("Simmer longer", timerSeconds = 1200), Step("Blend")))

        recipeDetailReducer
            .given(running)
            .on(RecipeDetailAction.Loaded(soup))
            .expect(running)
            .expectNoEffects()
            // A refresh keeps the notes child as it is; only the first arrival starts it.
            .expectFollowUps()
            .andOn(RecipeDetailAction.Loaded(rewritten))
            .expect(
                running.copy(
                    recipe = rewritten,
                    steps = persistentListOf(StepState.Idle(0, rewritten.steps[0]), StepState.Idle(1, rewritten.steps[1])),
                ),
            ).expectNoEffects()
    }

    @Test
    fun a_failed_first_load_is_retryable_and_a_failed_refresh_keeps_the_recipe() {
        recipeDetailReducer
            .given(RecipeDetailState.Loading("soup", Units.Imperial))
            .on(RecipeDetailAction.LoadFailed(RecipesError.Offline))
            .expect(RecipeDetailState.LoadFailed("soup", RecipesError.Offline, Units.Imperial))
            .expectNoEffects()
            .andOn(RecipeDetailAction.Retry)
            .expect(RecipeDetailState.Loading("soup", Units.Imperial))
            .expectEnvelopes(*startEnvelopes)

        recipeDetailReducer
            .given(ready)
            .on(RecipeDetailAction.LoadFailed(RecipesError.Offline))
            .expect(ready.copy(failure = RecipesError.Offline))
            .expectNoEffects()
    }

    @Test
    fun state_round_trips_through_json_for_process_death() {
        val state: RecipeDetailState =
            ready.copy(
                servings = 6,
                steps = persistentListOf(StepState.Paused(0, soup.steps[0], remainingSeconds = 42)),
                notes =
                    NotesState.Content(
                        persistentListOf(Note("1", "Less salt")),
                        EditorState("Add basil", hint = "Hint 1", hintCount = 1),
                    ),
            )

        val json = Json.encodeToString(RecipeDetailState.serializer(), state)

        assertEquals(state, Json.decodeFromString(RecipeDetailState.serializer(), json))
    }
}
