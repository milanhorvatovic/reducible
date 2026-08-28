package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Event
import io.github.milanhorvatovic.reducible.IdentifiedAction
import io.github.milanhorvatovic.reducible.Lens
import io.github.milanhorvatovic.reducible.Optional
import io.github.milanhorvatovic.reducible.Prism
import io.github.milanhorvatovic.reducible.Reduced
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.andSend
import io.github.milanhorvatovic.reducible.andThen
import io.github.milanhorvatovic.reducible.casePrism
import io.github.milanhorvatovic.reducible.combine
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesAction
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesEffect
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesState
import io.github.milanhorvatovic.reducible.cookbook.notes.notesReducer
import io.github.milanhorvatovic.reducible.cookbook.settings.Settings
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsGateway
import io.github.milanhorvatovic.reducible.cookbook.settings.Units
import io.github.milanhorvatovic.reducible.handle
import io.github.milanhorvatovic.reducible.ifPresent
import io.github.milanhorvatovic.reducible.immutable.PersistentListSerializer
import io.github.milanhorvatovic.reducible.immutable.forEachIdentified
import io.github.milanhorvatovic.reducible.lens
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.prism
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable

/**
 * One recipe on screen: a parent over two kinds of children — the steps as identified rows
 * with their own timers, and the notes feature from its own module as an optional child —
 * plus servings the reader scales and the units it follows from the app-scoped settings.
 */
@Serializable
public sealed interface RecipeDetailState {
    /** [units] is remembered before the recipe arrives so the first render is already right. */
    @Serializable
    public data class Loading(
        public val recipeId: String,
        public val units: Units = Units.Metric,
    ) : RecipeDetailState

    @Serializable
    public data class Ready(
        public val recipe: Recipe,
        public val servings: Int,
        public val units: Units,
        @Serializable(with = PersistentListSerializer::class)
        public val steps: PersistentList<StepState>,
        public val notes: NotesState,
        public val failure: RecipesError? = null,
    ) : RecipeDetailState

    @Serializable
    public data class LoadFailed(
        public val recipeId: String,
        public val error: RecipesError,
        public val units: Units = Units.Metric,
    ) : RecipeDetailState
}

public sealed interface RecipeDetailAction {
    public sealed interface Ui : RecipeDetailAction

    /** The ingredients tab's whole vocabulary — its view is typed over this alone. */
    public sealed interface Servings : Ui

    public data object Increased : Servings

    public data object Decreased : Servings

    public data object Retry : Ui

    public data object BackClicked : Ui

    /** Addressed to one step by index; [stepAction] builds it from the step's Ui subset. */
    public data class Step(
        public val action: IdentifiedAction<Int, StepAction>,
    ) : Ui

    /** The notes child's action; [notesAction] builds it from the notes Ui subset. */
    public data class Notes(
        public val action: NotesAction,
    ) : Ui

    public data object Started : RecipeDetailAction

    public data class Loaded(
        public val recipe: Recipe,
    ) : RecipeDetailAction

    public data class LoadFailed(
        public val error: RecipesError,
    ) : RecipeDetailAction

    public data class SettingsChanged(
        public val settings: Settings,
    ) : RecipeDetailAction
}

public fun stepAction(
    index: Int,
    action: StepAction.Ui,
): RecipeDetailAction.Ui = RecipeDetailAction.Step(IdentifiedAction(index, action))

public fun notesAction(action: NotesAction.Ui): RecipeDetailAction.Ui = RecipeDetailAction.Notes(action)

/** What the detail screen asks its holder to do; an [Event], so the store publishes it. */
public sealed interface RecipeDetailEvent :
    RecipeDetailAction,
    Event {
    public data object Close : RecipeDetailEvent
}

public sealed interface RecipeDetailEffect {
    public data class Load(
        public val recipeId: String,
    ) : RecipeDetailEffect

    public data object ObserveSettings : RecipeDetailEffect

    public data class Step(
        public val index: Int,
        public val effect: StepEffect,
    ) : RecipeDetailEffect

    public data class Notes(
        public val effect: NotesEffect,
    ) : RecipeDetailEffect
}

internal data object DetailLoadKey : EffectKey

internal data object SettingsObserveKey : EffectKey

// Hand-written optics, law-tested: the two children sit behind the same case prism.
internal val readyPrism: Prism<RecipeDetailState, RecipeDetailState.Ready> = casePrism()

internal val notesOptional: Optional<RecipeDetailState, NotesState> =
    readyPrism andThen lens(get = { ready -> ready.notes }, set = { ready, notes -> ready.copy(notes = notes) })

internal val stepsLens: Lens<RecipeDetailState.Ready, PersistentList<StepState>> =
    lens(
        get = { ready -> ready.steps },
        set = { ready, steps -> ready.copy(steps = steps) },
    )

internal val stepsOptional: Optional<RecipeDetailState, PersistentList<StepState>> = readyPrism andThen stepsLens

internal val notesActionPrism: Prism<RecipeDetailAction, NotesAction> =
    prism(
        getOrNull = { action -> (action as? RecipeDetailAction.Notes)?.action },
        embed = { notesAction -> RecipeDetailAction.Notes(notesAction) },
    )

internal val stepActionPrism: Prism<RecipeDetailAction, IdentifiedAction<Int, StepAction>> =
    prism(
        getOrNull = { action -> (action as? RecipeDetailAction.Step)?.action },
        embed = { stepAction -> RecipeDetailAction.Step(stepAction) },
    )

public val recipeDetailReducer: Reducer<RecipeDetailState, RecipeDetailAction, RecipeDetailEffect> =
    combine(
        notesReducer.ifPresent(
            state = notesOptional,
            action = notesActionPrism,
            effect = RecipeDetailEffect::Notes,
            slot = "notes",
        ),
        stepReducer.forEachIdentified(
            list = stepsOptional,
            identity = { step -> step.index },
            action = stepActionPrism,
            effect = { index, effect -> RecipeDetailEffect.Step(index, effect) },
        ),
        Reducer { state, action ->
            when (state) {
                is RecipeDetailState.Loading -> {
                    when (action) {
                        RecipeDetailAction.Started -> state.starting(state.recipeId)
                        RecipeDetailAction.Retry -> state.starting(state.recipeId)
                        is RecipeDetailAction.Loaded -> state.ready(action.recipe).andSend(RecipeDetailAction.Notes(NotesAction.Started))
                        is RecipeDetailAction.LoadFailed -> RecipeDetailState.LoadFailed(state.recipeId, action.error, state.units).only()
                        is RecipeDetailAction.SettingsChanged -> state.copy(units = action.settings.units).only()
                        RecipeDetailAction.Increased -> state.only()
                        RecipeDetailAction.Decreased -> state.only()
                        is RecipeDetailAction.Step -> state.only()
                        is RecipeDetailAction.Notes -> state.only()
                        RecipeDetailAction.BackClicked -> state.andSend(RecipeDetailEvent.Close)
                        RecipeDetailEvent.Close -> state.only()
                    }
                }

                is RecipeDetailState.Ready -> {
                    when (action) {
                        // A restored process: no effect ticks a step saved running, so it comes
                        // back paused, the recipe reloads so the data converges, and the notes
                        // child gets its own start so it converges too.
                        RecipeDetailAction.Started -> {
                            state
                                .copy(steps = state.steps.map { step -> step.interrupted() }.toPersistentList())
                                .starting(state.recipe.id)
                                .andSend(RecipeDetailAction.Notes(NotesAction.Started))
                        }

                        RecipeDetailAction.Retry -> {
                            state.copy(failure = null).starting(state.recipe.id)
                        }

                        is RecipeDetailAction.Loaded -> {
                            state.refreshed(action.recipe).only()
                        }

                        is RecipeDetailAction.LoadFailed -> {
                            state.copy(failure = action.error).only()
                        }

                        is RecipeDetailAction.SettingsChanged -> {
                            state.copy(units = action.settings.units).only()
                        }

                        RecipeDetailAction.Increased -> {
                            state.copy(servings = state.servings + 1).only()
                        }

                        RecipeDetailAction.Decreased -> {
                            state.copy(servings = maxOf(1, state.servings - 1)).only()
                        }

                        is RecipeDetailAction.Step -> {
                            state.only()
                        }

                        is RecipeDetailAction.Notes -> {
                            state.only()
                        }

                        RecipeDetailAction.BackClicked -> {
                            state.andSend(RecipeDetailEvent.Close)
                        }

                        RecipeDetailEvent.Close -> {
                            state.only()
                        }
                    }
                }

                is RecipeDetailState.LoadFailed -> {
                    when (action) {
                        RecipeDetailAction.Started -> RecipeDetailState.Loading(state.recipeId, state.units).starting(state.recipeId)
                        RecipeDetailAction.Retry -> RecipeDetailState.Loading(state.recipeId, state.units).starting(state.recipeId)
                        is RecipeDetailAction.SettingsChanged -> state.copy(units = action.settings.units).only()
                        is RecipeDetailAction.Loaded -> state.only()
                        is RecipeDetailAction.LoadFailed -> state.only()
                        RecipeDetailAction.Increased -> state.only()
                        RecipeDetailAction.Decreased -> state.only()
                        is RecipeDetailAction.Step -> state.only()
                        is RecipeDetailAction.Notes -> state.only()
                        RecipeDetailAction.BackClicked -> state.andSend(RecipeDetailEvent.Close)
                        RecipeDetailEvent.Close -> state.only()
                    }
                }
            }
        },
    )

// Loading is keyed so a retry replaces a request in flight; the settings observation is free
// because it must outlive the Loading-to-Ready transition, and keyed so a restart replaces it.
private fun RecipeDetailState.starting(recipeId: String): Reduced<RecipeDetailState, RecipeDetailAction, RecipeDetailEffect> =
    Reduced(
        this,
        listOf(
            EffectEnvelope(RecipeDetailEffect.Load(recipeId), key = DetailLoadKey),
            EffectEnvelope(RecipeDetailEffect.ObserveSettings, EffectScope.Free, SettingsObserveKey),
        ),
    )

private fun RecipeDetailState.Loading.ready(recipe: Recipe): RecipeDetailState.Ready =
    RecipeDetailState.Ready(
        recipe = recipe,
        servings = recipe.servings,
        units = units,
        steps = recipe.steps.mapIndexed { index, step -> StepState.Idle(index, step) }.toPersistentList(),
        notes = NotesState.Loading,
    )

/** Fresh recipe data by step index: a step whose text and timer are unchanged keeps its phase. */
private fun RecipeDetailState.Ready.refreshed(recipe: Recipe): RecipeDetailState.Ready =
    copy(
        recipe = recipe,
        failure = null,
        steps =
            recipe.steps
                .mapIndexed { index, step ->
                    steps.getOrNull(index)?.takeIf { existing -> existing.step == step }
                        ?: StepState.Idle(index, step)
                }.toPersistentList(),
    )

/**
 * The parent handler keeps the exhaustive `when` over its own effects and delegates each
 * child's through `handle`. Starting the notes child is the reducer's decision, made as a
 * follow-up action when the recipe arrives, so this handler only translates.
 */
public fun recipeDetailEffectHandler(
    repository: RecipesRepository,
    notesHandler: EffectHandler<NotesEffect, NotesAction>,
    settings: SettingsGateway,
    tick: suspend () -> Unit,
): EffectHandler<RecipeDetailEffect, RecipeDetailAction> {
    val stepHandler = stepEffectHandler(tick)
    return EffectHandler { effect, send ->
        when (effect) {
            is RecipeDetailEffect.Load -> {
                try {
                    send(RecipeDetailAction.Loaded(repository.recipe(effect.recipeId)))
                } catch (failure: RecipesException) {
                    send(RecipeDetailAction.LoadFailed(failure.error))
                }
            }

            RecipeDetailEffect.ObserveSettings -> {
                settings.observe { current -> send(RecipeDetailAction.SettingsChanged(current)) }
            }

            is RecipeDetailEffect.Step -> {
                stepHandler.handle(
                    effect.effect,
                    send,
                ) { stepAction -> RecipeDetailAction.Step(IdentifiedAction(effect.index, stepAction)) }
            }

            is RecipeDetailEffect.Notes -> {
                notesHandler.handle(effect.effect, send, RecipeDetailAction::Notes)
            }
        }
    }
}
