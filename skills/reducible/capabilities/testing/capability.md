---
name: testing
description: >
  Tests reducible code at three levels: the pure reducer with the
  given / on / expect DSL (states, effects, envelopes, follow-ups),
  hand-written optics with the lens, prism, and optional law assertions,
  and a feature end to end with testStore under the coroutine test
  scheduler's virtual time, asserting every reduced action and finishing
  exhaustively. Also covers projection tests, saved-state round trips, and
  Koin module tests. Use when writing or fixing any test that touches a
  reducer, optic, handler, or store.
---

# Testing

## Purpose

Each layer has a test shape that needs no mocking framework: reducers are pure functions, handlers take their suspensions as parameters, and `testStore` runs reducer, handler, and store semantics under virtual time. A test that passes here reproduces on both platforms, because the feature never touched a real dispatcher.

## Instructions

### 1. Reducer transitions with the DSL

```kotlin
counterReducer
    .given(CounterState.Idle(3))
    .on(CounterAction.StartTicking)
    .expect(CounterState.Ticking(3))
    .expectEffects(CounterEffect.Tick)
    .andOn(CounterAction.StopTicking)
    .expect(CounterState.Idle(3))
    .expectNoEffects()
```

- `expect(state)` compares structurally; `expectState { state -> ... }` for a predicate.
- `expectEffects(a, b)` asserts the effect values in order and ignores scopes; `expectEnvelopes(EffectEnvelope(a, EffectScope.Free, SomeKey))` asserts scope and key too. Use envelopes whenever the scope or key is the point of the transition.
- `expectFollowUps(ChildAction.Started)` asserts what the reduction asks the store to reduce next; called bare it asserts none.
- One test per behaviour, named for it: `stopping_returns_to_idle_and_a_late_tick_is_ignored`. Cover the late action (`Ticked` in `Idle`) and the restored start (`Started` in `Content`) explicitly.

### 2. Optic laws

```kotlin
readyPrism.assertPrismLaws(matching = ready, value = otherReady, RecipeDetailState.Loading("1"), RecipeDetailState.LoadFailed("1", error))
stepsLens.assertLensLaws(source = ready, replacement = persistentListOf(step))
notesOptional.assertOptionalLaws(present = ready, replacement = NotesState.Loading, RecipeDetailState.Loading("1"))
```

One test per hand-written optic. Sources and foci need structural equality, so data classes throughout. The absence law (`set` on an absent focus is a no-op) is what makes `ifPresent` safe; the assertion checks it for every absent source passed.

### 3. Feature lifecycle with `testStore`

```kotlin
@Test
fun full_lifecycle_from_started_to_saved() = runTest {
    val store = testStore(
        initialState = NotesState.Loading,
        reducer = notesReducer,
        handler = notesEffectHandler(FakeRepository, hintDebounceWindow = { delay(400) }),
    )

    store.send(NotesAction.Started)
    advanceUntilIdle()
    store
        .expectAction(NotesAction.Started, resulting = NotesState.Loading)
        .expectAction(NotesAction.Loaded(listOf(seed)), resulting = NotesState.Content(persistentListOf(seed)))

    store.send(NotesAction.Editor(EditorAction.DraftChanged("Doubles well")))
    advanceTimeBy(399)
    store.expectAction(NotesAction.Editor(EditorAction.DraftChanged("Doubles well")))
    store.expectNoMoreActions()          // the debounce window has not elapsed
    advanceUntilIdle()
    store.expectAction(NotesAction.DraftHint("Doubles well"))

    store.finish()                       // fails on any unasserted action or effect still in flight
}
```

- `testStore` is an extension on `TestScope`; it builds the store under `StoreScope.Testing(StandardTestDispatcher(testScheduler))`, so `advanceTimeBy`, `runCurrent`, and `advanceUntilIdle` own all time, including the injected `delay`s.
- Every reduced action, effect-fed ones included, must be consumed by `expectAction` in reduction order; `resulting =` pins the state that action produced. `expectState` checks the current state without consuming.
- `finish()` closes the store after asserting no unasserted actions and no effect in flight; a pending effect means the scheduler was not advanced far enough or its action was never going to be asserted.
- Fakes are plain objects implementing the feature's gateway or repository interface; a failing fake throws the boundary's typed exception so the handler's catch path is exercised.

### 4. Effects that never complete

An observation over a `StateFlow` or a periodic tick never ends on its own. Drive it with `advanceTimeBy`, never `advanceUntilIdle` (the drain never returns and nothing can pre-empt a loop that does not suspend), and end with either:

```kotlin
store.finish(cancelInFlightEffects = true)   // close first, drain what cancellation left, assert the rest
// or
store.expectNoMoreActions(); store.close()
```

The session example: sign in, `advanceTimeBy(100)`, sign out, `advanceUntilIdle()` is fine because the authentication effect is state-scoped and dies with `SigningIn`; the assertion that no `Authenticated` action follows is the point of the test.

### 5. Projections, serialization, modules

- A view projection is a pure function: `assertEquals(RecipesViewState.Content(rows = filtered), recipesViewState(state))`, one test per rule the projection applies (search narrows rows, amounts scale by servings).
- Restored state: encode and decode every phase with the state's serializer and assert equality, so a shape change that breaks restore fails here, not on a user's device.
- A Koin module: `koinApplication { modules(featureModule, module { single<Repository> { fake } }) }.koin`, resolve the factory class, build a store in `StoreScope.Testing(StandardTestDispatcher(testScheduler))`, `advanceUntilIdle()`, assert state, then `store.close()` and `koin.close()`.
- Runtime-level behaviour (scopes, keys, ownership) is the library's to test; a feature test asserts the feature's actions and states, not `EffectEvent`s.

### 6. Where tests live

Next to the code in `commonTest`, with `reducible-test`, `kotlin-test`, and `kotlinx-coroutines-test` as test dependencies. Reducer and optic tests run without a coroutine; lifecycle tests inside `runTest`.

## Pitfalls

- `advanceUntilIdle()` on a store with a never-completing effect: the test hangs until the task timeout.
- Asserting only the final state and skipping `expectAction`: `finish()` fails on the unasserted actions, and it should; the sequence is the behaviour.
- A real `delay` in the handler under test because the suspension was not injected: the test waits in wall-clock time or, under `runTest`, skips it entirely and hides the debounce.
- Testing a child through the parent's optics with hand-built parent actions when the child has its own reducer test; test the child directly, and the parent's routing once.
- Mocking `Store` or `EffectHandler`: neither needs it; a fake gateway and virtual time cover the boundary.
