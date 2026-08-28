package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.cookbook.notes.NotesState
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesViewState
import io.github.milanhorvatovic.reducible.cookbook.notes.notesViewState
import io.github.milanhorvatovic.reducible.cookbook.settings.Units
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * The detail screen as it renders: one section per tab, each always present so a tab's view
 * can be nested off this one with a plain field access, empty while the recipe loads.
 */
public data class RecipeDetailViewState(
    public val phase: DetailPhase,
    public val ingredients: IngredientsViewState,
    public val steps: StepsViewState,
    public val notes: NotesViewState,
)

public sealed interface DetailPhase {
    public data object Loading : DetailPhase

    public data class Ready(
        public val title: String,
        public val summary: String,
        public val minutes: Int,
        /** A refresh that failed; the recipe already on screen stays. */
        public val failure: RecipesError?,
    ) : DetailPhase

    public data class Failed(
        public val error: RecipesError,
    ) : DetailPhase
}

/** Amounts already scaled to [servings] and converted to [units]; the state holds neither. */
public data class IngredientsViewState(
    public val servings: Int,
    public val canDecrease: Boolean,
    public val units: Units,
    public val lines: PersistentList<IngredientLine>,
)

public data class IngredientLine(
    public val name: String,
    public val amount: String,
)

public data class StepsViewState(
    public val lines: PersistentList<StepLine>,
)

public data class StepLine(
    public val index: Int,
    public val text: String,
    /** Null for a step without a timer. */
    public val timer: TimerStatus?,
)

public sealed interface TimerStatus {
    public data class Idle(
        public val totalSeconds: Int,
    ) : TimerStatus

    public data class Running(
        public val remainingSeconds: Int,
    ) : TimerStatus

    public data class Paused(
        public val remainingSeconds: Int,
    ) : TimerStatus

    public data object Done : TimerStatus
}

public fun recipeDetailViewState(state: RecipeDetailState): RecipeDetailViewState =
    when (state) {
        is RecipeDetailState.Loading -> {
            RecipeDetailViewState(
                phase = DetailPhase.Loading,
                ingredients = IngredientsViewState(servings = 0, canDecrease = false, units = state.units, lines = persistentListOf()),
                steps = StepsViewState(persistentListOf()),
                notes = notesViewState(NotesState.Loading),
            )
        }

        is RecipeDetailState.Ready -> {
            RecipeDetailViewState(
                phase = DetailPhase.Ready(state.recipe.title, state.recipe.summary, state.recipe.minutes, state.failure),
                ingredients =
                    IngredientsViewState(
                        servings = state.servings,
                        canDecrease = state.servings > 1,
                        units = state.units,
                        lines =
                            state.recipe.ingredients
                                .map { ingredient ->
                                    IngredientLine(
                                        ingredient.name,
                                        formatAmount(ingredient, state.servings, state.recipe.servings, state.units),
                                    )
                                }.toPersistentList(),
                    ),
                steps = StepsViewState(state.steps.map { step -> step.toLine() }.toPersistentList()),
                notes = notesViewState(state.notes),
            )
        }

        is RecipeDetailState.LoadFailed -> {
            RecipeDetailViewState(
                phase = DetailPhase.Failed(state.error),
                ingredients = IngredientsViewState(servings = 0, canDecrease = false, units = state.units, lines = persistentListOf()),
                steps = StepsViewState(persistentListOf()),
                notes = notesViewState(NotesState.Loading),
            )
        }
    }

private fun StepState.toLine(): StepLine =
    StepLine(
        index = index,
        text = step.text,
        timer =
            when (this) {
                is StepState.Idle -> step.timerSeconds?.let(TimerStatus::Idle)
                is StepState.Running -> TimerStatus.Running(remainingSeconds)
                is StepState.Paused -> TimerStatus.Paused(remainingSeconds)
                is StepState.Done -> TimerStatus.Done
            },
    )
