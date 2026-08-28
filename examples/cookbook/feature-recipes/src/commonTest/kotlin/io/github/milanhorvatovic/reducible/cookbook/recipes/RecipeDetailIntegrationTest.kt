package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.cookbook.notes.Note
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesAction
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesRepository
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesState
import io.github.milanhorvatovic.reducible.cookbook.notes.notesEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.settings.Settings
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsGateway
import io.github.milanhorvatovic.reducible.cookbook.settings.Units
import io.github.milanhorvatovic.reducible.test.testStore
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private val quick = soup.copy(steps = listOf(Step("Simmer", timerSeconds = 3), Step("Blend", timerSeconds = 2)))
private val note = Note("1", "Less salt")

private object OneRecipe : RecipesRepository {
    override suspend fun loadRecipes(): List<Recipe> = listOf(quick)

    override suspend fun recipe(id: String): Recipe {
        delay(100)
        return quick
    }

    override suspend fun search(query: String): Set<String> = emptySet()
}

private object OneNote : NotesRepository {
    override suspend fun loadNotes(): List<Note> {
        delay(100)
        return listOf(note)
    }

    override suspend fun saveNote(text: String): Note = error("not exercised")
}

/** Imperial after a short delay, then silent: the detail must render metric first and follow. */
private object ImperialSettings : SettingsGateway {
    override suspend fun observe(onEach: (Settings) -> Unit) {
        delay(50)
        onEach(Settings(units = Units.Imperial))
        delay(Long.MAX_VALUE)
    }

    override suspend fun changeUnits(units: Units) = error("not exercised")

    override suspend fun toggleFailureInjection() = error("not exercised")
}

private fun step(
    index: Int,
    action: StepAction,
): RecipeDetailAction =
    RecipeDetailAction.Step(
        io.github.milanhorvatovic.reducible
            .IdentifiedAction(index, action),
    )

private fun TestScope.detailStore() =
    testStore(
        RecipeDetailState.Loading("soup"),
        recipeDetailReducer,
        recipeDetailEffectHandler(
            repository = OneRecipe,
            notesHandler = notesEffectHandler(OneNote, hintDebounceWindow = { delay(400) }),
            settings = ImperialSettings,
            tick = { delay(1_000) },
        ),
    )

// The whole screen against the real reducers and handlers under virtual time: settings arrive
// through the gateway, the notes child is started once its host is ready, and two timers tick
// independently — pausing one leaves the other running.
class RecipeDetailIntegrationTest {
    @Test
    fun start_follows_settings_loads_the_recipe_and_starts_the_notes_child() =
        runTest {
            val store = detailStore()

            store.send(RecipeDetailAction.Started)
            advanceUntilIdle()

            val loaded =
                RecipeDetailState.Ready(
                    recipe = quick,
                    servings = 4,
                    units = Units.Imperial,
                    steps = persistentListOf(StepState.Idle(0, quick.steps[0]), StepState.Idle(1, quick.steps[1])),
                    notes = NotesState.Loading,
                )
            store
                .expectAction(RecipeDetailAction.Started)
                .expectAction(
                    RecipeDetailAction.SettingsChanged(Settings(units = Units.Imperial)),
                    resulting = RecipeDetailState.Loading("soup", Units.Imperial),
                ).expectAction(RecipeDetailAction.Loaded(quick), resulting = loaded)
                .expectAction(RecipeDetailAction.Notes(NotesAction.Started))
                .expectAction(
                    RecipeDetailAction.Notes(NotesAction.Loaded(listOf(note))),
                    resulting = loaded.copy(notes = NotesState.Content(persistentListOf(note), editor = null)),
                )
            // The settings observation never completes on its own, so close instead of finish.
            store.expectNoMoreActions()
            store.close()
        }

    @Test
    fun timers_tick_independently_and_pausing_one_stops_only_its_countdown() =
        runTest {
            val store = detailStore()
            store.send(RecipeDetailAction.Started)
            advanceUntilIdle()
            store
                .expectAction(RecipeDetailAction.Started)
                .expectAction(RecipeDetailAction.SettingsChanged(Settings(units = Units.Imperial)))
                .expectAction(RecipeDetailAction.Loaded(quick))
                .expectAction(RecipeDetailAction.Notes(NotesAction.Started))
                .expectAction(RecipeDetailAction.Notes(NotesAction.Loaded(listOf(note))))

            store.send(stepAction(0, StepAction.Start))
            store.send(stepAction(1, StepAction.Start))
            advanceTimeBy(1_500)
            store.send(stepAction(0, StepAction.Pause))
            advanceTimeBy(2_000)
            runCurrent()

            store
                .expectAction(step(0, StepAction.Start))
                .expectAction(step(1, StepAction.Start))
                .expectAction(step(0, StepAction.Ticked))
                .expectAction(step(1, StepAction.Ticked))
                .expectAction(step(0, StepAction.Pause))
                // Only step 1 keeps ticking; it reaches Done and its countdown ends with the phase.
                .expectAction(step(1, StepAction.Ticked))
            store.expectNoMoreActions()
            val ready = store.state as RecipeDetailState.Ready
            kotlin.test.assertEquals(
                persistentListOf(StepState.Paused(0, quick.steps[0], remainingSeconds = 2), StepState.Done(1, quick.steps[1])),
                ready.steps,
            )
            store.close()
        }
}
