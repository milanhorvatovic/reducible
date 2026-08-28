package io.github.milanhorvatovic.reducible.cookbook.shared

import io.github.milanhorvatovic.reducible.cookbook.notes.NotesAction
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesViewState
import io.github.milanhorvatovic.reducible.cookbook.recipes.IngredientsViewState
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDetailAction
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDetailState
import io.github.milanhorvatovic.reducible.cookbook.recipes.RecipeDetailViewState
import io.github.milanhorvatovic.reducible.cookbook.recipes.StepsViewState
import io.github.milanhorvatovic.reducible.cookbook.recipes.recipeDetailViewState
import io.github.milanhorvatovic.reducible.runtime.Store
import io.github.milanhorvatovic.reducible.runtime.ViewStore
import io.github.milanhorvatovic.reducible.runtime.view

/**
 * The detail screen's views over one store: the screen itself, then one nested view per tab,
 * each narrowed to the tab's section and, where the tab has a vocabulary of its own, to that
 * vocabulary — the ingredients tab can only change servings, the notes tab speaks the notes
 * feature's own Ui actions. All four share the store; its owner closes it once.
 */
public class RecipeDetailViews(
    store: Store<RecipeDetailState, RecipeDetailAction>,
) {
    public val detail: ViewStore<RecipeDetailViewState, RecipeDetailAction.Ui> =
        store.view(
            state = ::recipeDetailViewState,
            action = { action -> action },
        )

    public val ingredients: ViewStore<IngredientsViewState, RecipeDetailAction.Servings> =
        detail.view(state = { detail ->
            detail.ingredients
        }, action = { action -> action })

    public val steps: ViewStore<StepsViewState, RecipeDetailAction.Ui> =
        detail.view(
            state = { detail -> detail.steps },
            action = { action -> action },
        )

    public val notes: ViewStore<NotesViewState, NotesAction.Ui> =
        detail.view(
            state = { detail -> detail.notes },
            action = RecipeDetailAction::Notes,
        )
}
