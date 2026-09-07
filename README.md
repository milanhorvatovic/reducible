# Reducible

[![CI](https://github.com/milanhorvatovic/reducible/actions/workflows/ci.yml/badge.svg)](https://github.com/milanhorvatovic/reducible/actions/workflows/ci.yml) [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Unidirectional state for Kotlin Multiplatform: pure reducers, effects the runtime owns, features composed through optics, and one store that native UI drives on Android and iOS.

> Pre-release. The API is settling and may change before 1.0. Artifacts are not on Maven Central yet; see [Building](#building) to use the library from source.

## What it gives you

- **Reducers are pure functions** — `(state, action) -> Reduced(state, effects, followUps)`. No dependencies, no coroutines, no lifecycle: `reducible-core` depends on nothing but the Kotlin standard library.
- **Effects are values; the runtime runs them.** A state-scoped effect is cancelled the moment the store leaves the state that asked for it. A keyed effect either cancels the previous holder of its key (debounce, "retry replaces the request in flight") or queues behind it (writes that must land completely and in order).
- **Features compose through optics.** A child reducer runs inside a parent's focus (`ifPresent`, `forEachIdentified`); its effects live exactly as long as that focus, and its keys are namespaced by it, so two children never cancel each other's work.
- **One store runtime, typed scopes.** `StoreScope.Main` for screens, `Background` and `Dedicated` for app-scoped stores, `Inherited` over an Android `viewModelScope`, `Testing` under a test scheduler.
- **Screens see projections.** A `ViewStore` narrows state to what a screen renders and actions to what it may send; a reduction that leaves the projection unchanged never invalidates the screen.
- **Observable by design.** Every reduction, effect launch and end, runtime warning, and defect reaches one observer hook. An action recording replays over the pure reducer to reproduce any state after the fact.
- **Testable in isolation.** A reducer assertion DSL, optic law assertions, and a `TestStore` that runs reducer, handler, and store under virtual time and fails on anything left unasserted.

## Modules

| Artifact | Purpose | Depends on |
| --- | --- | --- |
| `reducible-core` | `Reducer`, `Reduced`, effects and keys, optics (`Lens`, `Prism`, `Optional`), composition (`combine`, `ifPresent`, `forEachIdentified`), `Event`, `replay` | nothing |
| `reducible-runtime` | `Store`, `StoreScope`, `ViewStore`, `StoreObserver`, `DefectHandler`, `ActionRecorder`, `feedInto` | core, kotlinx-coroutines |
| `reducible-immutable` | `forEachIdentified` over a `PersistentList` with structural sharing; `PersistentListSerializer` | core, kotlinx-collections-immutable, kotlinx-serialization |
| `reducible-optics-arrow` | Adapters from Arrow Optics (hand-written or KSP-generated) to the core optics | core, arrow-optics |
| `reducible-test` | The `given / on / expect` reducer DSL, optic law assertions, `TestStore` | core, runtime, kotlinx-coroutines-test |
| `reducible-android` | `StoreViewModel`: a store held by a `ViewModel`, with process-death restore through `SavedStateHandle` | runtime, lifecycle-viewmodel-savedstate, kotlinx-serialization |
| [`swift/`](swift) | The SwiftUI and Combine bridge (`StoreModel`, `ViewStoreModel`, `ProjectedModel`, `statePublisher`), bundled into the app's own framework | the runtime, through your umbrella framework |

Targets: Android (`minSdk 26`), `iosArm64`, `iosSimulatorArm64`. All modules declare `explicitApi()`, and the public surface is pinned by API dumps under each module's `api/` directory.

Once published, the coordinates are `io.github.milanhorvatovic:<artifact>:<version>`:

```kotlin
// build.gradle.kts of a shared Kotlin Multiplatform module
kotlin {
    sourceSets {
        commonMain.dependencies {
            api("io.github.milanhorvatovic:reducible-core:<version>")
            api("io.github.milanhorvatovic:reducible-runtime:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.milanhorvatovic:reducible-test:<version>")
        }
        androidMain.dependencies {
            implementation("io.github.milanhorvatovic:reducible-android:<version>")
        }
    }
}
```

## A feature in one file

State as phases, actions as a sealed hierarchy, effects as values, and a reducer that is a plain function. This is [`examples/counter`](examples/counter), trimmed:

```kotlin
sealed interface CounterState {
    val count: Int
    data class Idle(override val count: Int = 0) : CounterState
    data class Ticking(override val count: Int) : CounterState
}

sealed interface CounterAction {
    sealed interface Ui : CounterAction
    data object Increment : Ui
    data object StartTicking : Ui
    data object StopTicking : Ui
    data object Ticked : CounterAction   // fed back by the effect, never sent by a screen
}

sealed interface CounterEffect {
    data object Tick : CounterEffect
}

val counterReducer = Reducer<CounterState, CounterAction, CounterEffect> { state, action ->
    when (action) {
        CounterAction.Increment -> state.withCount(state.count + 1).only()
        CounterAction.StartTicking -> when (state) {
            is CounterState.Idle -> CounterState.Ticking(state.count).withEffect(CounterEffect.Tick)
            is CounterState.Ticking -> state.only()
        }
        // Leaving Ticking is what cancels the tick in flight: the effect is state-scoped.
        CounterAction.StopTicking -> CounterState.Idle(state.count).only()
        CounterAction.Ticked -> when (state) {
            is CounterState.Ticking -> state.copy(count = state.count + 1).withEffect(CounterEffect.Tick)
            is CounterState.Idle -> state.only()   // a tick that raced the stop
        }
    }
}

// The one suspension the feature needs is injected, so tests own time.
fun counterEffectHandler(tick: suspend () -> Unit) = EffectHandler<CounterEffect, CounterAction> { effect, send ->
    when (effect) {
        CounterEffect.Tick -> { tick(); send(CounterAction.Ticked) }
    }
}

// The factory a platform holder calls; the effect type never leaves it.
fun counterStore(scope: StoreScope = StoreScope.Main): Store<CounterState, CounterAction> =
    Store(CounterState.Idle(), counterReducer, counterEffectHandler(tick = { delay(1_000) }), scope = scope)

// What a screen sees: rendered state in, the Ui subset of actions out.
fun counterView(store: Store<CounterState, CounterAction>): ViewStore<CounterViewState, CounterAction.Ui> =
    store.view(state = { state -> CounterViewState(state.count, ticking = state is CounterState.Ticking) }, action = { action -> action })
```

And the tests, without a coroutine in sight for the reducer and under virtual time for the store:

```kotlin
counterReducer
    .given(CounterState.Idle(3))
    .on(CounterAction.StartTicking)
    .expect(CounterState.Ticking(3))
    .expectEffects(CounterEffect.Tick)

runTest {
    val store = testStore(CounterState.Idle(), counterReducer, counterEffectHandler(tick = { delay(1_000) }))
    store.send(CounterAction.StartTicking)
    advanceTimeBy(2_500)
    store.send(CounterAction.StopTicking)
    advanceUntilIdle()
    store
        .expectAction(CounterAction.StartTicking, CounterState.Ticking(0))
        .expectAction(CounterAction.Ticked, CounterState.Ticking(1))
        .expectAction(CounterAction.Ticked, CounterState.Ticking(2))
        .expectAction(CounterAction.StopTicking, CounterState.Idle(2))
    store.finish()   // fails on any unasserted action or effect still in flight
}
```

## Holding a store

A store is owned by whatever owns the screen's lifetime; the feature never knows which platform it runs on.

### Android

A plain `ViewModel` lends the store its scope. `StoreScope.Inherited(viewModelScope)` makes the store reduce on the ViewModel's dispatcher and close when the ViewModel is cleared — no `onCleared` to write:

```kotlin
class CounterViewModel : ViewModel() {
    private val store = counterStore(StoreScope.Inherited(viewModelScope))
    val view: ViewStore<CounterViewState, CounterAction.Ui> = counterView(store)
}

@Composable
fun CounterRoute(viewModel: CounterViewModel = viewModel()) {
    val state by viewModel.view.stateFlow.collectAsStateWithLifecycle()
    CounterScreen(state, send = viewModel.view::send)
}
```

For a screen whose state must survive process death, `reducible-android`'s `StoreViewModel` adds the saved-state round trip: it hands the factory the restored state (or null) and the scope, and captures every reduction back through a saved-state provider, serialized with the state's `KSerializer`:

```kotlin
class SignInViewModel(savedStateHandle: SavedStateHandle, stores: AppStores) :
    StoreViewModel<SignInState, SignInAction>(
        savedStateHandle = savedStateHandle,
        serializer = SignInState.serializer(),
        createStore = { restored, scope -> stores.signInStore(restored, scope) },
    ) {
    val view = stores.signInView(store)
}
```

### iOS

The umbrella framework exports the runtime and bundles the SwiftUI bridge from [`swift/`](swift). A `StoreModel` owns the store's lifetime and closes it in `deinit`; a `ViewStoreModel` over it is what a screen holds, invalidated synchronously on the main thread as the store reduces:

```swift
struct CounterView: View {
    @StateObject private var model: ViewStoreModel<CounterViewState, CounterActionUi>

    init(store: Store<CounterState, CounterAction>) {
        let owner = StoreModel(store: store)
        _model = StateObject(wrappedValue: owner.view(CounterStoreKt.counterView(store: store)))
    }

    var body: some View {
        let state = model.state
        VStack {
            Text("\(state.count)")
            Button(state.ticking ? "Stop" : "Start") {
                model.send(state.ticking ? CounterActionStopTicking() : CounterActionStartTicking())
            }
        }
        .task { model.activate() }
    }
}
```

`ProjectedModel` converts each projection once into a Swift value for list screens and anything that ticks, and `statePublisher(of:)` serves the same state to Combine-based screens. The Cookbook example shows all three tiers side by side.

## Concepts in more depth

**Effect scope and ownership.** `withEffect(effect)` requests a state-scoped effect: the runtime cancels it when the store leaves the state class the requesting reduction entered. `withEffect(effect, scope = EffectScope.Free)` runs to completion regardless of state, until the store closes. An effect that came through a composition operator is owned tighter — by the child focus that requested it, and by the state class the child entered — which is what lets a parent remove a child and know its work stopped.

**Keys.** `withEffect(effect, key = SearchKey)` puts the effect under a key. `KeyPolicy.CancelPrevious` (the default) cancels the running holder first, so a handler that starts with a delay is a debounce and a request keyed like the one in flight replaces it. `KeyPolicy.Ordered` queues behind the running holder, for writes that must not be cut short. Keys are namespaced by composition, so a child's `HintDebounce` becomes `notes/editor/HintDebounce` in logs and never collides with a sibling's.

**Composition.** `combine(a, b)` threads the state through several reducers of the same type. `child.ifPresent(state = optional, action = prism, effect = wrap)` runs a child reducer only while the parent's focus resolves, drops child actions that arrive after the child is gone, and re-scopes child effects to the focus. `child.forEachIdentified(list, identity, action, effect)` does the same for every element of a list, addressed by identity, never by position. Hand-written optics (`lens`, `prism`, `optional`, `casePrism`, `andThen`) are enough; `reducible-optics-arrow` adapts Arrow's generated ones.

**Follow-ups and events.** `state.andSend(action)` asks the store to reduce more actions right after this reduction, before anything sent from elsewhere — the way a parent starts a child it has just created. An action marked `Event` is published on `Store.events` right after the state that produced it, for whoever holds the store to act on: a navigation intent, a child's word to its parent. Events are one-shot; anything a screen must not miss belongs in state.

**Store scopes.** The store owns its coroutine scope; UI never constructs one. `Main` reduces on the main thread, synchronously when sent from it. `Background` serializes reduction on a worker; `Dedicated` on a dispatcher the app supplies (a named thread on Android, a GCD queue on iOS through `dedicated(queue:)`). `Inherited` reduces on a caller's scope and lives as long as it. `Testing` puts reduction and effects on one test dispatcher so the test scheduler owns all time.

**Observing a store.** `StoreObserver` sees every reduction (`onReduced`), each effect's launch, skip, and end (`onEffect`), and the runtime's warnings (`onWarning`): a send after close, a send from a finished effect, an event nobody collected. Observers compose with `+`. `ActionRecorder` is an observer that keeps a bounded action log; `reducer.replay(initialState, actions)` folds it back into a state. A `DefectHandler` receives what the architecture says cannot happen — a handler that let an exception escape, a reducer that threw — and either rethrows (debug) or logs and keeps the app alive (release).

**Testing.** `reducer.given(state).on(action).expect(next).expectEffects(…)` asserts a reduction with no coroutine machinery. `assertLensLaws`, `assertPrismLaws`, and `assertOptionalLaws` check hand-written optics. `testStore(initialState, reducer, handler)` inside `runTest` runs everything under virtual time; every reduced action — effect-fed ones included — must be consumed by `expectAction`, and `finish()` fails on anything left over or still in flight.

## Examples

| Example | Shows |
| --- | --- |
| [`examples/counter`](examples/counter) | The smallest complete feature: phases, a state-scoped effect, an injected clock, a store factory, a view, and both kinds of test |
| [`examples/koin`](examples/koin) | Dependency injection with Koin: a repository behind an interface, a store factory Koin builds, and tests that bind a fake |
| [`examples/cookbook`](examples/cookbook) | The end-to-end showcase: four feature modules, a composition root with app-scoped and screen stores, a Compose app, a SwiftUI app, and a map from every library capability to the file that uses it |

## Using the library with a coding agent

The repository ships an [Agent Skill](https://agentskills.io) at [`skills/reducible`](skills/reducible): the invariants, feature and composition shapes, platform holders, and test patterns an agent needs to build on the library correctly. Install it into a project with `npx skills add milanhorvatovic/reducible`, or copy the directory into the project's `.agents/skills/` (or the tool's own skills directory) and it loads whenever the work touches a reducer, store, or view. Inside this repository `.agents/skills/reducible` and `.claude/skills/reducible` are symlinks to it, so agents working here pick it up too.

## Building

Requirements:

- JDK 17 or newer on `PATH` to launch Gradle (the daemon's JDK 21 and the compile toolchain 17 auto-provision through the Foojay resolver)
- Android SDK (`ANDROID_HOME`, or `local.properties` with `sdk.dir`)
- Xcode with the iOS SDK for the iOS targets and the framework

```sh
./gradlew build -PskipIosTests                 # inner loop: JVM host tests, lint, API check; no simulator binaries
./gradlew build                                # everything, including the iOS simulator tests
./gradlew apiDump                              # refresh the API dumps after a deliberate public-API change
./gradlew publishToMavenLocal                  # try the artifacts from another build via mavenLocal()
```

Until the artifacts are on Maven Central, consume the library from source: clone it and `includeBuild("path/to/reducible")` in your `settings.gradle.kts`, then depend on the coordinates above — Gradle substitutes the included build.

## Conventions

- Conventional commits; each commit builds and passes on its own.
- `explicitApi()` everywhere; a public-API change updates the dumps (`./gradlew apiDump`) in the same commit.
- One formatter and one linter per language, run by pre-commit and CI: ktlint and detekt for Kotlin (`./gradlew lintKotlin detekt`, fix with `formatKotlin`), SwiftFormat and SwiftLint for Swift, prettier and markdownlint for Markdown, yamllint and actionlint for YAML, taplo for TOML. The code is explicit by rule — `self.`, `return`, named closure parameters, and closures passed by argument label in Swift; braces on every `if` and multi-line `when` branch, named lambda parameters, and Compose slots passed by name (`content = { … }`) in Kotlin — and the rules are enforced: SwiftLint custom rules for Swift, the repository's own detekt rules (`detekt-rules/`) for Kotlin. See [CONTRIBUTING.md](CONTRIBUTING.md).
- Features carry no coroutines: suspensions are injected by the wiring, so reducers stay pure and handlers stay testable under virtual time.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Security reports go through [SECURITY.md](SECURITY.md); community expectations are in the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

[MIT](LICENSE) © 2026 Milan Horvatovič
