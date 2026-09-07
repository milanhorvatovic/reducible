---
name: feature-authoring
description: >
  Writes or reviews one reducible feature: state modelled as sealed phases,
  a sealed action hierarchy with a Ui subset and effect-fed actions, effects
  as values with scope and key, a pure reducer, a total effect handler with
  injected suspensions, the store factory that owns the start action, and
  the ViewStore projection a screen renders. Use when adding a feature,
  screen, or component, or when a reducer or handler looks wrong.
---

# Feature authoring

## Purpose

A feature is one Kotlin file (or a small module) built from six parts in a fixed order: state, actions, effects, reducer, handler, factories. Getting the shapes right up front is what keeps the reducer pure, the handler total, and the screen cheap to render.

## Instructions

Work through the parts in order. Each step names the decision it settles.

### 1. State as sealed phases

Model the phases the screen can be in as a `sealed interface` with a `data class` (or `data object`) per phase, not one class with nullable fields. The phase is what a state-scoped effect is tied to: an effect requested when entering `Ticking` is cancelled by the runtime the moment the state becomes `Idle`, with no bookkeeping.

- Fields shared by every phase go on the interface (`val count: Int`).
- Mark the hierarchy `@Serializable` only when a platform restores it after process death; then every nested type is serializable too, and a `PersistentList` field uses `@Serializable(with = PersistentListSerializer::class)`.
- Keep secrets out of `toString()`: override it on the state and action classes that carry credentials, because observers and recordings stringify what they see.
- Do not store derived data (filtered rows, formatted amounts). Derive it in the view projection.

### 2. Actions as a sealed hierarchy

```kotlin
public sealed interface NotesAction {
    /** What a screen may send; the view narrows to this subset. */
    public sealed interface Ui : NotesAction
    public data object AddClicked : Ui
    public data class DraftChanged(val text: String) : Ui

    /** The unconditional start, sent by the store factory. */
    public data object Started : NotesAction

    /** Fed back by effects, never sent by a screen. */
    public data class Loaded(val notes: List<Note>) : NotesAction
    public data class LoadFailed(val reason: String) : NotesAction
}

/** What the screen asks its holder to do; an Event, so the store publishes it. */
public sealed interface NotesEvent : NotesAction, Event {
    public data object Close : NotesEvent
}
```

- The `Ui` sub-interface is the whole vocabulary a screen gets; `store.view(...)` embeds it with the identity.
- `Started` is unconditional and state-aware: in a fresh state it loads, in a restored `Content` it refreshes or does nothing. This is what makes a restored state converge.
- An `Event` is emitted as a follow-up (`state.andSend(NotesEvent.Close)`) and reduced as a no-op; it is still a reduced action, so it lands in logs and recordings.

### 3. Effects as values, with scope and key

```kotlin
public sealed interface NotesEffect {
    public data object Load : NotesEffect
    public data class Save(val text: String) : NotesEffect
    public data class Hint(val draft: String) : NotesEffect
}

internal data object HintDebounce : EffectKey
internal data object SaveKey : EffectKey {
    override val policy: KeyPolicy get() = KeyPolicy.Ordered
}
```

Pick scope and key from the table; the default (`StateScoped`, no key) is right for most requests.

| Need | Request it as |
| --- | --- |
| Request tied to the phase that asked (a load while `Loading`) | `state.withEffect(Load)` |
| Must outlive phase transitions until the store closes (an audit record, an observation every state handles) | `state.withEffect(effect, scope = EffectScope.Free)` |
| Latest request wins: debounce, "retry replaces the request in flight" | `withEffect(effect, key = SomeKey)` with the default `KeyPolicy.CancelPrevious`; a leading `delay` in the handler makes it a debounce |
| Writes that must land completely and in order | key with `KeyPolicy.Ordered` |
| Several effects with different scopes in one reduction | `Reduced(state, listOf(EffectEnvelope(a), EffectEnvelope(b, EffectScope.Free, ObserveKey)))` |
| A long-running observation of an app-scoped store | `Free` plus a stable key, so a re-request replaces the running subscription instead of stacking one |

Keys are compared by equality per store; a `data object` is the usual key. Keys of a child feature are namespaced by the composition operator, so a child may reuse a key name another child uses.

### 4. The reducer

```kotlin
public val notesReducer: Reducer<NotesState, NotesAction, NotesEffect> =
    Reducer { state, action ->
        when (state) {
            is NotesState.Loading -> when (action) {
                NotesAction.Started -> state.withEffect(NotesEffect.Load)
                is NotesAction.Loaded -> NotesState.Content(action.notes).only()
                is NotesAction.LoadFailed -> NotesState.Failed(action.reason).only()
                else -> state.only()
            }
            is NotesState.Content -> when (action) {
                NotesAction.Started -> state.only()                       // restored: nothing to fetch again
                NotesAction.AddClicked -> state.copy(editor = EditorState()).only()
                is NotesAction.DraftChanged ->
                    state.copy(editor = EditorState(action.text)).withEffect(NotesEffect.Hint(action.text), key = HintDebounce)
                NotesAction.BackClicked -> state.andSend(NotesEvent.Close)
                NotesEvent.Close -> state.only()
                is NotesAction.Loaded -> state.only()                    // a late answer after a phase change
                else -> state.only()
            }
            is NotesState.Failed -> when (action) {
                NotesAction.Retry -> NotesState.Loading.withEffect(NotesEffect.Load)
                else -> state.only()
            }
        }
    }
```

- Switch on state first, then action, and prefer exhaustive `when` without `else` once the hierarchy is stable: a new action then fails to compile in every phase that ignores it.
- Helpers: `state.only()` requests nothing; `withEffect(effect, scope, key)` one effect; `withEffects(a, b)` several state-scoped ones; `andSend(actions)` asks the store to reduce follow-ups right after this reduction, ahead of anything sent from elsewhere; `Reduced(...)` when scopes differ.
- Ignore late actions explicitly: an effect that was cancelled may already have sent its action, and the reducer is where it is dropped (`Ticked` in `Idle`).
- A follow-up cycle (an action whose reduction keeps following up) is cut by the runtime after 1000 follow-ups in one drain and reported as `Defect.FollowUpCycle`. Follow-ups start a child or emit an event; they do not loop.

### 5. The handler

```kotlin
public fun notesEffectHandler(
    repository: NotesRepository,
    hintDebounceWindow: suspend () -> Unit,
): EffectHandler<NotesEffect, NotesAction> =
    EffectHandler { effect, send ->
        when (effect) {
            NotesEffect.Load -> {
                try {
                    send(NotesAction.Loaded(repository.loadNotes()))
                } catch (failure: NotesUnavailable) {
                    send(NotesAction.LoadFailed(failure.reason))
                }
            }
            is NotesEffect.Hint -> {
                hintDebounceWindow()
                send(NotesAction.DraftHint(effect.draft))
            }
            is NotesEffect.Save -> send(NotesAction.Saved(repository.saveNote(effect.text)))
        }
    }
```

- Catch the expected failure types the boundary declares and turn each into an action; let everything else escape as a defect.
- Every suspension is a parameter (`tick`, `hintDebounceWindow`, `expiry`), so a test passes `{ delay(400) }` under virtual time and production passes the same. The feature module never imports `kotlinx.coroutines.delay`.
- `send` is thread-safe; the store re-dispatches to its confined thread. A `send` after the handler returned is delivered but reported as `StoreWarning.SentFromFinishedEffect`; do not launch coroutines inside a handler.
- A long-running observation (`gateway.observe { value -> send(Action.Changed(value)) }`) simply never returns; the store cancels it by scope or key.

### 6. Factories: store, view, events

```kotlin
public fun notesStore(
    repository: NotesRepository,
    restored: NotesState? = null,
    scope: StoreScope = StoreScope.Main,
): Store<NotesState, NotesAction> =
    Store(
        initialState = restored ?: NotesState.Loading,
        reducer = notesReducer,
        handler = notesEffectHandler(repository, hintDebounceWindow = { delay(400) }),
        scope = scope,
        start = NotesAction.Started,
    )

public fun notesView(store: Store<NotesState, NotesAction>): ViewStore<NotesViewState, NotesAction.Ui> =
    store.view(state = ::notesViewState, action = { action -> action })

public fun notesEvents(store: Store<NotesState, NotesAction>): Flow<NotesEvent> =
    store.events { action -> action as? NotesEvent }
```

- The factory is the only place the effect type, the handler, and the real suspensions meet; a platform holder passes a scope and, on Android, the restored state. It lives in the composition root (`AppStores`) when it needs app-owned dependencies, in the feature module otherwise.
- `NotesViewState` is a plain data class of exactly what the screen renders; the projection runs at most once per reduction and equal projections never invalidate the screen, so filtering and formatting belong here, not in state.
- A screen that holds nested views (tabs, rows) narrows further with `view.view(state, action)`.

### 7. Verify

Reducer tests with `given / on / expect`, a `testStore` lifecycle test with `finish()` passing, and, when the state is serializable, a round-trip test. Then the build's own gates (`./gradlew build -PskipIosTests` in the reducible repository, the project's equivalent elsewhere).

## Pitfalls

- A `delay`, `Clock.System.now()`, or `Random` inside a reducer or a feature module: inject it.
- Throwing for an expected failure inside a handler: the store treats it as a defect and, under the debug policy, crashes.
- Modelling a cancellation as an action (`StopLoading`) instead of a phase transition the runtime already cancels on.
- A `Started` that unconditionally resets state: a restored screen flashes back to `Loading`.
- Putting navigation into state (`navigateTo: Route?`) instead of emitting an `Event`; state survives rotation, events do not, which is the point.
- Nullable-field state (`loading: Boolean, error: String?, data: List<T>?`) instead of phases: illegal combinations become representable and effect scoping loses its anchor.
- A public store type that leaks the effect type (`Store<S, A, E>`): the effect type is internal machinery and the factory is where it stops.
