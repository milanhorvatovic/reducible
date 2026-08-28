package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.cookbook.notes.Note
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesState
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesViewState
import io.github.milanhorvatovic.reducible.cookbook.settings.Units
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

class RecipeDetailViewStateTest {
    @Test
    fun ready_scales_and_converts_ingredients_and_projects_each_child() {
        val state =
            RecipeDetailState.Ready(
                recipe = soup,
                servings = 8,
                units = Units.Imperial,
                steps = persistentListOf(StepState.Paused(0, soup.steps[0], remainingSeconds = 42)),
                notes = NotesState.Content(persistentListOf(Note("1", "Less salt")), editor = null),
                failure = RecipesError.Offline,
            )

        assertEquals(
            RecipeDetailViewState(
                phase = DetailPhase.Ready("Tomato soup", "Quick weeknight soup", minutes = 30, failure = RecipesError.Offline),
                ingredients =
                    IngredientsViewState(
                        servings = 8,
                        canDecrease = true,
                        units = Units.Imperial,
                        lines = persistentListOf(IngredientLine("Tomatoes", "3.5 lb")),
                    ),
                steps = StepsViewState(persistentListOf(StepLine(0, "Simmer", TimerStatus.Paused(42)))),
                notes = NotesViewState.Notes(persistentListOf(Note("1", "Less salt")), editor = null, canAdd = true),
            ),
            recipeDetailViewState(state),
        )
    }

    @Test
    fun loading_and_failure_keep_every_section_present_but_empty() {
        val loading = recipeDetailViewState(RecipeDetailState.Loading("soup", Units.Imperial))

        assertEquals(DetailPhase.Loading, loading.phase)
        assertEquals(
            IngredientsViewState(servings = 0, canDecrease = false, units = Units.Imperial, lines = persistentListOf()),
            loading.ingredients,
        )
        assertEquals(StepsViewState(persistentListOf()), loading.steps)
        assertEquals(NotesViewState.Busy(saving = false), loading.notes)

        assertEquals(
            DetailPhase.Failed(RecipesError.Offline),
            recipeDetailViewState(RecipeDetailState.LoadFailed("soup", RecipesError.Offline)).phase,
        )
    }

    @Test
    fun a_step_without_a_timer_has_no_timer_status() {
        val state =
            RecipeDetailState.Ready(
                recipe = pancakes,
                servings = 2,
                units = Units.Metric,
                steps = persistentListOf(StepState.Idle(0, pancakes.steps[0])),
                notes = NotesState.Loading,
            )

        assertEquals(persistentListOf(StepLine(0, "Fry", timer = null)), recipeDetailViewState(state).steps.lines)
    }
}
