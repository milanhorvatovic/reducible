---
name: composition
description: >
  Composes reducible features: a child reducer nested into a parent with
  ifPresent, a list of identified rows with forEachIdentified (core or
  persistent-list variant), the hand-written or Arrow optics that focus
  them, the parent handler that delegates child effects with handle, and
  the follow-ups that start a child. Use when a screen embeds another
  feature or renders rows with their own state and effects.
---

# Composition

## Purpose

A parent runs a child reducer inside a focus described by optics. The operators do three things the parent must not redo by hand: they drop child actions that arrive when the focus is absent, they re-scope child effects so they live exactly as long as the focus and the child's phase, and they namespace child effect keys so siblings never cancel each other.

## Instructions

### 1. Decide the shape

| The child is | Operator | Focus type |
| --- | --- | --- |
| One optional component (an editor sheet, a notes panel) | `child.ifPresent(state, action, effect, slot)` | `Optional<ParentState, ChildState>` |
| Every element of a list, each with its own phase and effects | `child.forEachIdentified(list, identity, action, effect)` | `Optional<ParentState, List<ChildState>>` |
| The same, over a `PersistentList` already held in state | `forEachIdentified` from `reducible-immutable` | `Optional<ParentState, PersistentList<ChildState>>` |
| Several reducers over one state type (children plus the parent's own logic) | `combine(a, b, parent)` | none |

Rows are addressed by identity, never by position: an action arriving after a removal or a reorder finds its row or is dropped.

### 2. Write the optics

Hand-written optics are enough; the interfaces are `Optional<S, T>` (`getOrNull`, `set`), `Lens<S, T>` (adds `get`), `Prism<S, T>` (adds `embed`).

```kotlin
internal val readyPrism: Prism<RecipeDetailState, RecipeDetailState.Ready> = casePrism()

internal val notesOptional: Optional<RecipeDetailState, NotesState> =
    readyPrism andThen lens(get = { ready -> ready.notes }, set = { ready, notes -> ready.copy(notes = notes) })

internal val stepsOptional: Optional<RecipeDetailState, PersistentList<StepState>> =
    readyPrism andThen lens(get = { ready -> ready.steps }, set = { ready, steps -> ready.copy(steps = steps) })

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
```

- `casePrism<S, T>()` focuses one case of a sealed hierarchy; `lens` a field; `optional` a nullable field; `andThen` composes any two.
- `set` on an absent focus is a no-op by law: inserting the child is the reducer's explicit decision, never a side effect of a set.
- Law-test every hand-written optic with `assertOptionalLaws`, `assertLensLaws`, `assertPrismLaws` from `reducible-test`.
- With Arrow Optics codegen, adapt the generated optics through `reducible-optics-arrow` (`asCoreLens()`, `asCorePrism()`, `asCoreOptional()`) so the core never depends on Arrow.

### 3. Embed the child in the parent's types

The parent's action and effect hierarchies carry the child's as cases:

```kotlin
public sealed interface RecipeDetailAction {
    public sealed interface Ui : RecipeDetailAction
    public data class Step(val action: IdentifiedAction<Int, StepAction>) : Ui
    public data class Notes(val action: NotesAction) : Ui
    // ...
}

public sealed interface RecipeDetailEffect {
    public data class Step(val index: Int, val effect: StepEffect) : RecipeDetailEffect
    public data class Notes(val effect: NotesEffect) : RecipeDetailEffect
    // ...
}

/** Helpers so a screen builds a child action without knowing the wrapping. */
public fun stepAction(index: Int, action: StepAction.Ui): RecipeDetailAction.Ui = RecipeDetailAction.Step(IdentifiedAction(index, action))
public fun notesAction(action: NotesAction.Ui): RecipeDetailAction.Ui = RecipeDetailAction.Notes(action)
```

A child's `Event` arrives at the parent embedded like any other child action; the parent translates it into its own event or swallows it. Only an action the parent's own action type marks as `Event` leaves the store.

### 4. Compose the reducer

```kotlin
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
        Reducer { state, action -> /* the parent's own transitions */ },
    )
```

- `combine` threads the state through in order and concatenates effects and follow-ups. Children first, parent last, so the parent's arm sees the child's updated state; a parent that removes a child in the same reduction makes that child's just-requested effects `Skipped`.
- `slot` names the child in effect keys (`notes/HintDebounce` in logs). Two children of the same reducer type in one store need distinct slots; the default slot is the optic object itself.
- Child follow-ups come back embedded through the action prism, addressed to the same child; child effects are wrapped by `effect` and owned by the focus plus the child's phase.

### 5. Start the child from the parent

Creating a child state does not start it. The parent asks for the child's start action as a follow-up in the reduction that inserts it, and again in its own `Started` when restored:

```kotlin
is RecipeDetailAction.Loaded ->
    state.ready(action.recipe).andSend(RecipeDetailAction.Notes(NotesAction.Started))

RecipeDetailAction.Started ->   // restored Ready: converge, and re-start the child
    state.copy(steps = state.steps.map { step -> step.interrupted() }.toPersistentList())
        .starting(state.recipe.id)
        .andSend(RecipeDetailAction.Notes(NotesAction.Started))
```

Follow-ups run right after this reduction, before anything sent from elsewhere, so the child's first effect is launched inside a state where the child exists.

### 6. Compose the handler

The parent handler keeps an exhaustive `when` over its own effect type and delegates each child branch with `handle`, which embeds what the child sends back:

```kotlin
public fun recipeDetailEffectHandler(
    repository: RecipesRepository,
    notesHandler: EffectHandler<NotesEffect, NotesAction>,
    tick: suspend () -> Unit,
): EffectHandler<RecipeDetailEffect, RecipeDetailAction> {
    val stepHandler = stepEffectHandler(tick)
    return EffectHandler { effect, send ->
        when (effect) {
            is RecipeDetailEffect.Load -> { /* the parent's own effect */ }
            is RecipeDetailEffect.Notes -> notesHandler.handle(effect.effect, send, RecipeDetailAction::Notes)
            is RecipeDetailEffect.Step ->
                stepHandler.handle(effect.effect, send) { stepAction -> RecipeDetailAction.Step(IdentifiedAction(effect.index, stepAction)) }
        }
    }
}
```

Effect extraction deliberately stays in the parent's `when`: a new child effect case is a compile error in the parent handler, never a runtime routing miss. The child handler is a parameter, so the composition root builds it with the child's own dependencies.

### 7. Narrow the view

A screen region that renders one child gets its own `ViewStore` by narrowing the parent's: `parentView.view(state = { view -> view.notes }, action = ::notesAction)`. Nested views cost nothing until collected.

## Pitfalls

- Indexing rows by position (`identity = { _ -> index }` recomputed after removal): the operator then routes a late action to the wrong row. Identity must be stable across reorders.
- A child module that imports `reducible-runtime`: children depend on `reducible-core` only; the runtime enters at the root.
- Two children of one reducer type sharing the default slot: their keyed effects cancel each other. Name the slots.
- Handling a child's effect by re-dispatching on a string or class name: use the sealed wrapper case and `handle`.
- Expecting a child action to reach a child that was just removed: it is dropped, by design; a test asserting on it is asserting the wrong thing.
- Inserting a child through an optic `set` on an absent focus: it stays absent. Insert in the reducer explicitly.
