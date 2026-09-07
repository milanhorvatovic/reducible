---
name: store-wiring
description: >
  Builds and reviews the composition root of a reducible app: app-scoped
  stores on Background or Dedicated scopes, screen store factories with
  restored state and scope parameters, gateways that let a screen observe
  an app-scoped store through feedInto, StoreScope selection, observers,
  ActionRecorder and replay, DefectHandler policy, and dependency injection
  with constructor parameters or Koin. Use when creating or changing
  AppStores, wiring a new store, or choosing where a store lives.
---

# Store wiring

## Purpose

The composition root is the one place where features meet the runtime: real repositories, real suspensions, the `Store(...)` factory, and the scope each store lives in. Everything a platform holder does is call a factory here, pass a scope, and close what it owns.

## Instructions

### 1. Shape the composition root

One class per app, owned by the platform's application object (`Application` on Android, the `App` struct on iOS) and alive for the process:

```kotlin
public class AppStores(
    settingsScope: StoreScope,                 // the platform's Dedicated scope
    public val diagnostics: Boolean,
    auth: AuthGateway,
    settingsRepository: SettingsRepository,
) {
    /** App-scoped, never observed by UI directly: screens follow it through SessionGateway. */
    public val session: Store<SessionState, SessionAction> =
        Store(
            initialState = SessionState.SignedOut(),
            reducer = sessionReducer,
            handler = sessionEffectHandler(auth, expiry = { ttlMillis -> delay(ttlMillis) }),
            defects = defectsOf("session"),
            scope = StoreScope.Background,
            observer = observerOf("session"),
        )

    public val settings: Store<SettingsState, SettingsAction> =
        Store(initialState = SettingsState.Loading, reducer = settingsReducer, handler = settingsEffectHandler(settingsRepository, persistWindow = { delay(500) }), scope = settingsScope, start = SettingsAction.Started)

    private val sessionGateway: SessionGateway = StoreSessionGateway(session)

    /** [restored] is the state a previous process saved, or null on a fresh start. */
    public fun signInStore(restored: SignInState? = null, scope: StoreScope = StoreScope.Main): Store<SignInState, SignInAction> =
        Store(
            initialState = restored ?: SignInState.Editing(),
            reducer = signInReducer,
            handler = signInEffectHandler(sessionGateway),
            defects = defectsOf("signIn"),
            scope = scope,
            observer = observerOf("signIn"),
            start = SignInAction.Started,
        )

    public fun signInView(store: Store<SignInState, SignInAction>): ViewStore<SignInViewState, SignInAction.Ui> =
        store.view(state = ::signInViewState, action = { action -> action })

    public fun signInEvents(store: Store<SignInState, SignInAction>): Flow<SignInEvent> =
        store.events { action -> action as? SignInEvent }

    public fun close() {
        session.close()
        settings.close()
    }
}
```

Every screen factory comes as a triple: `fooStore(restored, scope)`, `fooView(store)`, and, when the screen asks its holder for anything, `fooEvents(store)`. The factory passes the feature's `start` action, so holders only wire and close.

### 2. Choose the scope

| Store | Scope | Why |
| --- | --- | --- |
| A screen | `StoreScope.Main` (default) | Reduction is confined to the main thread and synchronous when sent from it: a tap produces new state in the same runloop turn |
| A screen on Android, held by a ViewModel | `StoreScope.Inherited(viewModelScope)` | Reduces on the ViewModel's dispatcher, closes when the ViewModel is cleared, nothing to close by hand |
| App-scoped, no UI observes it (session, feature flags) | `StoreScope.Background` | Serialized on a worker slice; state reaches screens through gateway effects |
| App-scoped, wanted on a recognizable thread | `StoreScope.Dedicated(dispatcher)` | A named single-thread executor on Android, `dedicated(queue:)` over a serial GCD queue on iOS; the runtime re-serializes any dispatcher |
| Any store in a test | `StoreScope.Testing(StandardTestDispatcher(testScheduler))` | Reduction and effects on one scheduler; production never shares a dispatcher between them |

Effects always run on `Dispatchers.Default`; the effect dispatcher is not pluggable. UI must never observe a `Background` or `Dedicated` store directly.

### 3. Cross-store observation through gateways

A screen store never holds another store. It observes shared state through a gateway interface the feature module declares and the root implements over the store, with `feedInto` inside the effect handler:

```kotlin
// feature module
public fun interface SettingsGateway {
    public suspend fun observe(onEach: (Settings) -> Unit)
}

// composition root
private class StoreSettingsGateway(private val store: Store<SettingsState, SettingsAction>) : SettingsGateway {
    override suspend fun observe(onEach: (Settings) -> Unit) {
        store.stateFlow.mapNotNull { state -> (state as? SettingsState.Ready)?.settings }.feedInto(onEach) { settings -> settings }
    }
}

// feature handler branch
RecipeDetailEffect.ObserveSettings -> settings.observe { current -> send(RecipeDetailAction.SettingsChanged(current)) }
```

The reducer requests the observation as `EffectEnvelope(ObserveSettings, EffectScope.Free, SettingsObserveKey)`: free because it must outlive the `Loading` to `Ready` transition, keyed so a restart replaces the running subscription. Writes go the same way (`gateway.changeUnits(units)` calls `store.send`).

### 4. Observers, recordings, defects

- `StoreObserver` is the single hook: `onReduced` per reduced action, `onEffect` for each launch, skip, and end, `onWarning` for what the runtime tolerates (`SentAfterClose`, `SentFromFinishedEffect`, `EventDropped`). Observers compose with `+` and must stay fast: they run inside the reduction loop.
- `ActionRecorder(capacity)` is an observer that keeps a bounded action log; `reducer.replay(recording.initialState, recording.actions)` reproduces the state. Bound it and `clear()` it when the recorded actions carry secrets; it is a debugging tool, not persistence. `RecordingStore(...)` is the one-liner when no other observer is wanted.
- `DefectHandler`: `rethrowingDefectHandler()` (the default) crashes at the defect, right for debug builds; `loggingDefectHandler { exception -> log(exception) }` keeps a release build alive and logs the broken contract with the original error as cause. Choose per build type in the root.
- With diagnostics off, pass no observer: nothing is stringified per reduction, which is the largest avoidable cost.

### 5. Dependency injection

Constructor injection into the root is the default. With Koin, register a factory class per feature rather than the store:

```kotlin
public class ProfileStores(private val repository: ProfileRepository) {
    public fun profileStore(scope: StoreScope = StoreScope.Main): Store<ProfileState, ProfileAction> =
        Store(ProfileState.Loading, profileReducer, profileEffectHandler(repository), scope = scope, start = ProfileAction.Started)
}

public val profileModule: Module = module { singleOf(::ProfileStores) }
public val networkModule: Module = module { single<ProfileRepository> { HttpProfileRepository() } }
```

Koin resolves by class, so `Store<ProfileState, ProfileAction>` and another feature's `Store<...>` would be one definition; and a holder wants a fresh store per screen built in its own scope, which is a method with a parameter, not a singleton. Bindings the feature cannot fake (repositories, gateways) are what the container injects; a test binds a fake in a module of its own.

### 6. Consume the library

Coordinates are `io.github.milanhorvatovic:reducible-core`, `reducible-runtime`, `reducible-test`, `reducible-immutable`, `reducible-optics-arrow`, `reducible-android`. Feature modules take `api(reducible-core)` (plus `reducible-immutable` for persistent lists); the root module takes `api(reducible-runtime)`; tests take `implementation(reducible-test)` with `kotlinx-coroutines-test`. Until the artifacts are on Maven Central, `includeBuild("path/to/reducible")` in `settings.gradle.kts` substitutes the coordinates with the source build.

## Pitfalls

- A Compose screen or SwiftUI view collecting `session.stateFlow` of a `Background` store: cross-thread UI observation. Go through a gateway into the screen store.
- A store factory that takes a `CoroutineScope` or a dispatcher instead of a `StoreScope`: the typed scope is what keeps reduction serialized and UI-safe.
- Registering `Store<S, A>` in a DI graph as a singleton: one screen's store outlives the screen and collides with every other store type under erasure.
- Forgetting `start =` in a factory and sending `Started` from the platform: one holder will forget, and a restored state will not converge.
- An unbounded `ActionRecorder` on a store whose actions carry credentials.
- Closing an app-scoped store from a screen: only the root owns it.
