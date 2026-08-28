package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.Event
import io.github.milanhorvatovic.reducible.IdentifiedAction
import io.github.milanhorvatovic.reducible.Lens
import io.github.milanhorvatovic.reducible.Optional
import io.github.milanhorvatovic.reducible.Prism
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.andSend
import io.github.milanhorvatovic.reducible.andThen
import io.github.milanhorvatovic.reducible.casePrism
import io.github.milanhorvatovic.reducible.combine
import io.github.milanhorvatovic.reducible.handle
import io.github.milanhorvatovic.reducible.immutable.PersistentListSerializer
import io.github.milanhorvatovic.reducible.immutable.forEachIdentified
import io.github.milanhorvatovic.reducible.lens
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.prism
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable

/** The recipes list: a parent over identified rows, plus search, refresh, and hiding. */
@Serializable
public sealed interface RecipesState {
    @Serializable
    public data object Loading : RecipesState

    @Serializable
    public data class Loaded(
        @Serializable(with = PersistentListSerializer::class)
        public val rows: PersistentList<RecipeRowState>,
        public val query: String = "",
        /** Ids the last search matched; null while no search narrows the list. */
        public val matching: Set<String>? = null,
        /** Hidden by the user; a refresh does not bring them back. */
        public val hidden: Set<String> = emptySet(),
        public val refreshing: Boolean = false,
        public val failure: RecipesError? = null,
    ) : RecipesState

    @Serializable
    public data class LoadFailed(
        public val error: RecipesError,
    ) : RecipesState
}

public sealed interface RecipesAction {
    public sealed interface Ui : RecipesAction

    public data object Retry : Ui

    public data object Refresh : Ui

    public data class QueryChanged(
        public val text: String,
    ) : Ui

    /** Hides one recipe; a download in progress for it is cancelled with the row. */
    public data class Dismissed(
        public val id: String,
    ) : Ui

    /**
     * Addressed to one row by identity, handled by the row reducer through
     * `forEachIdentified`. Build it with [recipeRowAction], which admits the row's Ui subset only.
     */
    public data class Row(
        public val action: IdentifiedAction<String, RecipeRowAction>,
    ) : Ui

    /** Where the user wants to go; the reducer answers each with the [RecipesEvent] the holder acts on. */
    public sealed interface Navigation : Ui

    public data class RecipeClicked(
        public val id: String,
    ) : Navigation

    public data object SettingsClicked : Navigation

    public data object DebugClicked : Navigation

    public data object SignOutClicked : Navigation

    public data object Started : RecipesAction

    public data class Loaded(
        public val recipes: List<Recipe>,
    ) : RecipesAction

    public data class LoadFailed(
        public val error: RecipesError,
    ) : RecipesAction

    public data class Searched(
        public val query: String,
        public val matching: Set<String>,
    ) : RecipesAction

    public data class SearchFailed(
        public val error: RecipesError,
    ) : RecipesAction
}

public fun recipeRowAction(
    id: String,
    action: RecipeRowAction.Ui,
): RecipesAction.Ui = RecipesAction.Row(IdentifiedAction(id, action))

/** What the recipes screen asks its holder to do; an [Event], so the store publishes it. */
public sealed interface RecipesEvent :
    RecipesAction,
    Event {
    public data class OpenRecipe(
        public val id: String,
    ) : RecipesEvent

    public data object OpenSettings : RecipesEvent

    public data object OpenDebug : RecipesEvent

    public data object SignOut : RecipesEvent
}

public sealed interface RecipesEffect {
    public data object Load : RecipesEffect

    /** Keyed: the handler's leading window turns a burst of keystrokes into one search. */
    public data class Search(
        public val query: String,
    ) : RecipesEffect

    public data class Row(
        public val id: String,
        public val effect: RecipeRowEffect,
    ) : RecipesEffect
}

internal data object LoadKey : EffectKey

internal data object SearchKey : EffectKey

// Hand-written optics, law-tested: the row list as an Optional over the whole state, and the
// row action as a Prism over the whole action type. forEachIdentified composes both.
internal val loadedPrism: Prism<RecipesState, RecipesState.Loaded> = casePrism()

// Focused as a PersistentList so the row operator replaces one element by structural sharing.
internal val rowsLens: Lens<RecipesState.Loaded, PersistentList<RecipeRowState>> =
    lens(
        get = { state -> state.rows },
        set = { loaded, rows -> loaded.copy(rows = rows) },
    )

internal val rowsOptional: Optional<RecipesState, PersistentList<RecipeRowState>> = loadedPrism andThen rowsLens

internal val rowActionPrism: Prism<RecipesAction, IdentifiedAction<String, RecipeRowAction>> =
    prism(
        getOrNull = { action -> (action as? RecipesAction.Row)?.action },
        embed = { rowAction -> RecipesAction.Row(rowAction) },
    )

public val recipesReducer: Reducer<RecipesState, RecipesAction, RecipesEffect> =
    combine(
        recipeRowReducer.forEachIdentified(
            list = rowsOptional,
            identity = { row -> row.recipe.id },
            action = rowActionPrism,
            effect = { id, effect -> RecipesEffect.Row(id, effect) },
        ),
        // Navigation passes through the store so it is logged and replayed like any intent; the
        // holder acts on the event, this reducer only names it.
        Reducer { state, action ->
            if (action !is RecipesAction.Navigation) {
                return@Reducer state.only()
            }
            when (action) {
                is RecipesAction.RecipeClicked -> state.andSend(RecipesEvent.OpenRecipe(action.id))
                RecipesAction.SettingsClicked -> state.andSend(RecipesEvent.OpenSettings)
                RecipesAction.DebugClicked -> state.andSend(RecipesEvent.OpenDebug)
                RecipesAction.SignOutClicked -> state.andSend(RecipesEvent.SignOut)
            }
        },
        Reducer { state, action ->
            when (state) {
                RecipesState.Loading -> {
                    when (action) {
                        RecipesAction.Started -> state.withEffect(RecipesEffect.Load, key = LoadKey)
                        is RecipesAction.Loaded -> RecipesState.Loaded(action.recipes.map(RecipeRowState::Remote).toPersistentList()).only()
                        is RecipesAction.LoadFailed -> RecipesState.LoadFailed(action.error).only()
                        RecipesAction.Retry -> state.only()
                        RecipesAction.Refresh -> state.only()
                        is RecipesAction.QueryChanged -> state.only()
                        is RecipesAction.Dismissed -> state.only()
                        is RecipesAction.Row -> state.only()
                        is RecipesAction.Searched -> state.only()
                        is RecipesAction.SearchFailed -> state.only()
                        is RecipesAction.Navigation -> state.only()
                        is RecipesEvent -> state.only()
                    }
                }

                is RecipesState.Loaded -> {
                    when (action) {
                        // A restored process: no effect runs for a row saved mid-download, so the
                        // row returns to Remote, and the list refreshes so the data converges.
                        RecipesAction.Started -> {
                            state
                                .copy(rows = state.rows.map { row -> row.interrupted() }.toPersistentList(), refreshing = true)
                                .withEffect(RecipesEffect.Load, key = LoadKey)
                        }

                        RecipesAction.Refresh -> {
                            state.copy(refreshing = true, failure = null).withEffect(RecipesEffect.Load, key = LoadKey)
                        }

                        is RecipesAction.Loaded -> {
                            state.copy(rows = state.merged(action.recipes), refreshing = false, failure = null).only()
                        }

                        is RecipesAction.LoadFailed -> {
                            state.copy(refreshing = false, failure = action.error).only()
                        }

                        is RecipesAction.QueryChanged -> {
                            if (action.text.isBlank()) {
                                state.copy(query = "", matching = null).only()
                            } else {
                                state.copy(query = action.text).withEffect(RecipesEffect.Search(action.text), key = SearchKey)
                            }
                        }

                        // A result for a query the user has already changed is stale — drop it.
                        is RecipesAction.Searched -> {
                            if (action.query == state.query) {
                                state.copy(matching = action.matching, failure = null).only()
                            } else {
                                state.only()
                            }
                        }

                        is RecipesAction.SearchFailed -> {
                            state.copy(failure = action.error).only()
                        }

                        is RecipesAction.Dismissed -> {
                            state
                                .copy(
                                    rows = state.rows.removingAll { row -> row.recipe.id == action.id },
                                    hidden = state.hidden + action.id,
                                ).only()
                        }

                        RecipesAction.Retry -> {
                            state.only()
                        }

                        is RecipesAction.Row -> {
                            state.only()
                        }

                        is RecipesAction.Navigation -> {
                            state.only()
                        }

                        is RecipesEvent -> {
                            state.only()
                        }
                    }
                }

                is RecipesState.LoadFailed -> {
                    when (action) {
                        RecipesAction.Started -> RecipesState.Loading.withEffect(RecipesEffect.Load, key = LoadKey)
                        RecipesAction.Retry -> RecipesState.Loading.withEffect(RecipesEffect.Load, key = LoadKey)
                        RecipesAction.Refresh -> state.only()
                        is RecipesAction.Loaded -> state.only()
                        is RecipesAction.LoadFailed -> state.only()
                        is RecipesAction.QueryChanged -> state.only()
                        is RecipesAction.Dismissed -> state.only()
                        is RecipesAction.Row -> state.only()
                        is RecipesAction.Searched -> state.only()
                        is RecipesAction.SearchFailed -> state.only()
                        is RecipesAction.Navigation -> state.only()
                        is RecipesEvent -> state.only()
                    }
                }
            }
        },
    )

private fun RecipeRowState.interrupted(): RecipeRowState =
    when (this) {
        is RecipeRowState.Downloading -> RecipeRowState.Remote(recipe, favorite)
        is RecipeRowState.Remote -> this
        is RecipeRowState.Offline -> this
        is RecipeRowState.Failed -> this
    }

/** Fresh data by identity: rows keep their phase and favorite, new recipes arrive Remote, hidden ones stay out. */
private fun RecipesState.Loaded.merged(recipes: List<Recipe>): PersistentList<RecipeRowState> {
    val existing = rows.associateBy { row -> row.recipe.id }
    return recipes
        .filterNot { recipe -> recipe.id in hidden }
        .map { recipe -> existing[recipe.id]?.withRecipe(recipe) ?: RecipeRowState.Remote(recipe) }
        .toPersistentList()
}

/**
 * The parent handler owns the exhaustive `when` over its effects and delegates row effects to
 * the row handler, re-embedding what the row sends back by identity — `handle` is that
 * re-embedding as a one-liner. [searchWindow] is the debounce suspension, supplied by the wiring.
 */
public fun recipesEffectHandler(
    repository: RecipesRepository,
    downloads: RecipeDownloads,
    searchWindow: suspend () -> Unit,
): EffectHandler<RecipesEffect, RecipesAction> {
    val rowHandler = recipeRowEffectHandler(downloads)
    return EffectHandler { effect, send ->
        when (effect) {
            RecipesEffect.Load -> {
                try {
                    send(RecipesAction.Loaded(repository.loadRecipes()))
                } catch (failure: RecipesException) {
                    send(RecipesAction.LoadFailed(failure.error))
                }
            }

            is RecipesEffect.Search -> {
                searchWindow()
                try {
                    send(RecipesAction.Searched(effect.query, repository.search(effect.query)))
                } catch (failure: RecipesException) {
                    send(RecipesAction.SearchFailed(failure.error))
                }
            }

            is RecipesEffect.Row -> {
                rowHandler.handle(effect.effect, send) { rowAction -> RecipesAction.Row(IdentifiedAction(effect.id, rowAction)) }
            }
        }
    }
}
