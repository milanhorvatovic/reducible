package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.IdentifiedAction
import io.github.milanhorvatovic.reducible.cookbook.notes.Note
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesAction
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesState
import io.github.milanhorvatovic.reducible.cookbook.settings.Units
import io.github.milanhorvatovic.reducible.test.assertOptionalLaws
import io.github.milanhorvatovic.reducible.test.assertPrismLaws
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

private val ready =
    RecipeDetailState.Ready(
        recipe = soup,
        servings = 4,
        units = Units.Metric,
        steps = persistentListOf(StepState.Idle(0, soup.steps[0])),
        notes = NotesState.Content(persistentListOf(Note("1", "Less salt")), editor = null),
    )

private val absent = arrayOf(RecipeDetailState.Loading("soup"), RecipeDetailState.LoadFailed("soup", RecipesError.Offline))

class RecipeDetailOpticsTest {
    @Test
    fun the_notes_path_is_lawful() {
        notesOptional.assertOptionalLaws(present = ready, replacement = NotesState.Loading, *absent)
    }

    @Test
    fun the_steps_path_is_lawful() {
        stepsOptional.assertOptionalLaws(present = ready, replacement = persistentListOf(StepState.Done(0, soup.steps[0])), *absent)
    }

    @Test
    fun the_action_prisms_match_their_own_case_only() {
        stepActionPrism.assertPrismLaws(
            matching = RecipeDetailAction.Step(IdentifiedAction(0, StepAction.Start)),
            value = IdentifiedAction(1, StepAction.Ticked),
            RecipeDetailAction.Started,
            RecipeDetailAction.Notes(NotesAction.Retry),
        )
        notesActionPrism.assertPrismLaws(
            matching = RecipeDetailAction.Notes(NotesAction.AddClicked),
            value = NotesAction.Retry,
            RecipeDetailAction.Started,
            RecipeDetailAction.Step(IdentifiedAction(0, StepAction.Start)),
        )
    }
}
