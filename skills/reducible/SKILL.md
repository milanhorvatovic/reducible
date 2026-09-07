---
name: reducible
description: >
  Guides code built on the reducible Kotlin Multiplatform library
  (io.github.milanhorvatovic.reducible): pure reducers, runtime-owned
  effects, optics composition, Store, ViewStore, StoreViewModel, the SwiftUI
  bridge, and TestStore. Use when writing, reviewing, or wiring a feature,
  screen, or test in a project that depends on a reducible-* artifact or
  the swift/ bridge. Triggers when the user mentions reducers, actions,
  effects, effect keys, stores, store scopes, view stores, events,
  ifPresent, forEachIdentified, optics, StoreModel, or testStore in KMP
  code, or asks to add a feature, compose a child feature, hold a store on
  Android or iOS, inject dependencies into a store, or test a reducer or
  store. Do not use for other Redux-like or MVI libraries, or for UI work
  that never touches a store.
license: MIT
metadata:
  author: Milan Horvatovič
  version: "0.1.0"
---

# Reducible

## Purpose

Reducible is unidirectional state for Kotlin Multiplatform: a `Reducer` is a pure function `(state, action) -> Reduced(state, effects, followUps)`, an `EffectHandler` runs effect values, and a `Store` owns the coroutine scope, the effect lifetimes, and the thread reduction happens on. This skill holds the rules and shapes that make code on top of it correct: what belongs in a reducer, how effects are scoped and keyed, how features compose through optics, how each platform holds a store, and how everything is tested under virtual time. Coordinates are `io.github.milanhorvatovic:reducible-<module>`; until the artifacts reach Maven Central the library is consumed from source with `includeBuild`.

## Invariants

Every capability assumes these. Breaking one is a defect, not a style choice.

- **Reducers are pure.** No clocks, randomness, I/O, or coroutines; the same state and action always produce the same `Reduced`. A reducer that throws is reported as a `Defect.InReducer` and its action is skipped.
- **Handlers are total.** Expected failures are caught inside the handler and sent back as typed actions; an exception escaping a handler is `Defect.InEffect`. Cancellation is never a failure.
- **Suspensions are injected.** `delay`, clocks, and repositories reach a feature as constructor or factory parameters (`tick: suspend () -> Unit`, `repository: NotesRepository`), never as direct calls inside the feature module.
- **Feature modules depend on `reducible-core` only.** `reducible-runtime` is wired in the composition root; the effect type exists only at the `Store(...)` factory and never appears in a store's public type.
- **The store owns its scope.** UI never builds a `CoroutineScope` for a store. `StoreScope.Main` for screen stores; `Background` or `Dedicated` for app-scoped stores, which UI never observes directly; `Inherited` over an Android `viewModelScope`; `Testing` only in tests.
- **State-scoped effects die with their state class.** `withEffect(effect)` is cancelled the moment the store leaves the state class the requesting reduction entered; a child's effect also dies when its focus stops resolving. Leaving a phase is how work is stopped; there is no cancel action.
- **The start action belongs to the factory.** `Store(..., start = FooAction.Started)` sends it before the factory returns; the reducer handles it state-aware so a restored state converges instead of resetting.
- **Events are one-shot and for the holder.** An action marked `Event` is published on `Store.events` right after the state it followed and is reduced as a no-op. Navigation intents go there; anything a screen must not miss belongs in state.
- **Closing is the owner's job.** A screen store under `StoreScope.Main` is closed by whatever owns the screen (a `StoreModel` on iOS, a ViewModel on Android); `Inherited` closes with its scope. On iOS a store with running effects is retained by its own scope, so `close()` is mandatory.

## Capabilities

Route by what the task produces. Read only the matching file.

| Capability | Trigger | Path |
| --- | --- | --- |
| feature-authoring | Write or review one feature: its state phases, actions, effects and keys, reducer, handler, store factory, and view projection | capabilities/feature-authoring/capability.md |
| composition | Nest a child feature or a list of rows into a parent with `ifPresent` or `forEachIdentified`, write or adapt the optics, wire the parent handler | capabilities/composition/capability.md |

A task that spans two capabilities (a new feature plus its Android screen) loads both, feature first.

## References

- `references/api-reference.md` — read when a signature, parameter default, or module boundary is in question; it lists the public surface of every module and of the Swift bridge.
- The repository's [`examples/cookbook/README.md`](https://github.com/milanhorvatovic/reducible/blob/main/examples/cookbook/README.md) maps every library capability to the demo file that uses it; the [`examples/counter`](https://github.com/milanhorvatovic/reducible/tree/main/examples/counter) module is the smallest complete feature and the [`examples/koin`](https://github.com/milanhorvatovic/reducible/tree/main/examples/koin) module shows dependency injection.

## Contributing to the library itself

Changes inside the reducible repository follow its `AGENTS.md` (explicit code style, API dumps, commit conventions), which this skill does not repeat.
