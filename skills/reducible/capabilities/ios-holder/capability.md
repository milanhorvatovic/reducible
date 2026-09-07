---
name: ios-holder
description: >
  Holds a reducible store on iOS: the umbrella Kotlin framework that
  exports the runtime and bundles the swift/ bridge through SKIE, the
  StoreModel that owns a store's lifetime, ViewStoreModel and
  ProjectedModel for SwiftUI screens, statePublisher for Combine and
  ObservableObject code, @StateObject creation once per view identity,
  activate() in .task, events consumed with for await, and dedicated(queue:)
  for Dedicated scopes. Use when writing a SwiftUI screen, model factory,
  or App struct over a store.
---

# iOS holder

## Purpose

On iOS nothing closes a store for free: a store with running effects is retained by its own coroutine scope, so ARC never collects it. The Swift bridge makes ownership explicit: a `StoreModel` closes the store in `deinit`, and every screen-facing model retains that owner. Invalidation rides a hop-free callback, so SwiftUI invalidates in the same runloop turn a tap reduced.

## Instructions

### 1. The umbrella framework

The shared Kotlin module that the app links exports `reducible-core`, `reducible-runtime`, and the feature modules, applies the SKIE plugin, and bundles the bridge sources by placing (or symlinking) the library's `swift/` directory at `src/iosMain/swift`:

```kotlin
plugins { alias(libs.plugins.kotlinMultiplatform); alias(libs.plugins.skie) }

skie {
    features {
        // Default arguments only for the wiring's factories; the whole library would generate overload sets Swift never calls.
        group("com.example.app.shared") { co.touchlab.skie.configuration.DefaultArgumentInterop.Enabled(true) }
    }
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
            export(projects.reducibleCore)
            export(projects.reducibleRuntime)
            export(projects.features.recipes)
        }
    }
}
```

SKIE turns `StateFlow` into a typed `AsyncSequence` (`store.stateSequence`), `Flow<Event>` into `SkieSwiftFlow<Event>`, and sealed classes into `onEnum(of:)` switches. Kotlin top-level functions surface under their file name (`CounterStoreKt.counterView(store:)`, `StoreScopeDarwinKt.dedicated(queue:)`).

### 2. The App owns the composition root

```swift
@main
struct CookbookApp: App {
    @State private var stores = AppStores(
        settingsScope: StoreScopeDarwinKt.dedicated(queue: DispatchQueue(label: "app.stores")),
        diagnostics: isDebugBuild
    )

    var body: some Scene {
        WindowGroup(content: { RootView(stores: self.stores) })
    }
}
```

### 3. Model factories: create, own, project

One factory per screen, called once per view identity. It creates the store (already started by the Kotlin factory), hands its lifetime to a `StoreModel`, and returns the screen's model over it:

```swift
@MainActor
func makeSignInModels(_ stores: AppStores) -> SignInModels {
    let store = stores.signInStore(restored: nil)
    let owner = StoreModel(store: store)
    return SignInModels(model: owner.view(stores.signInView(store: store)), events: stores.signInEvents(store: store))
}

/// A class only so @StateObject creates the pair once per view identity; it publishes nothing.
@MainActor
final class SignInModels: ObservableObject {
    let model: ViewStoreModel<SignInViewState, SignInActionUi>
    let events: SkieSwiftFlow<SignInEvent>
    init(model: ViewStoreModel<SignInViewState, SignInActionUi>, events: SkieSwiftFlow<SignInEvent>) {
        self.model = model
        self.events = events
    }
}
```

Pick the model tier per screen:

| Screen | Model | Why |
| --- | --- | --- |
| Reads a few scalars from the projection | `owner.view(viewStore)` → `ViewStoreModel` | Tracked pass-through of the Kotlin projection, no copy |
| Renders a list, or anything that ticks | `owner.project(viewStore, convert)` → `ProjectedModel<VS, VA, UI>` | `convert` runs once per reduction into a Swift value; rendering, diffing, and scrolling never cross the Kotlin boundary |
| Still on `ObservableObject` and Combine | `statePublisher(of: viewStore)` in a `@Published`-backed model that also holds the `StoreModel` | Same equality-filtered projection, delivered synchronously on the main thread |

Several models over one store retain the same owner; the store closes when the last model is gone.

### 4. The SwiftUI screen

```swift
struct SignInView: View {
    @StateObject private var models: SignInModels
    @State private var path: [Route] = []

    init(stores: AppStores) {
        _models = StateObject(wrappedValue: makeSignInModels(stores))
    }

    var body: some View {
        let state = self.models.model.state
        Form(content: {
            TextField("Email", text: self.models.model.binding(get: { state in state.email }, send: { text in SignInActionEmailChanged(text: text) }))
            Button("Sign in", action: { self.models.model.send(SignInActionSubmit()) })
                .disabled(!state.canSubmit)
        })
        .task { self.models.model.activate() }
        .task {
            for await event in self.models.events {
                switch onEnum(of: event) {
                case .openDebug: self.path.append(.debug)
                }
            }
        }
    }
}
```

- `@StateObject` with the factory in the `init` autoclosure runs once per view identity; a `@State` assigned in `init` would build and start a fresh store on every re-initialisation of the view struct.
- `activate()` from `.task` starts observation; store lifetime (view identity) and observation lifetime stay separate.
- Events are consumed with `for await` in their own `.task`; the host that owns the `NavigationStack` path acts on them, the screen never navigates.
- `binding(get:send:)` makes text fields and pickers drive the reducer instead of view-local copies.
- Sealed Kotlin types switch through `onEnum(of:)`; `Ui` sub-interfaces arrive as `FooActionUi`, cases as `FooActionSubmit()`.

### 5. Restore

iOS has no process-death round trip in the bridge; factories are called with `restored: nil`. State that must survive is persisted by a repository behind an effect, like any other data.

### 6. Verify

Build the framework and the app (`xcodebuild -project ... -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO build`), then check with the Xcode memory graph that leaving a screen frees its store: a retained `Store` after pop means an owner was never created or an observation captured the model strongly.

## Pitfalls

- Holding a `Store` without a `StoreModel`: it leaks with its effects until the process ends.
- `@State private var model = makeModel(stores)` or a `@State` set in `init`: a new store per re-initialisation.
- Forgetting `.task { model.activate() }`: the screen renders the initial state and never invalidates.
- Reading Kotlin objects inside `ForEach` rows of a long list: use `ProjectedModel` so one reduction is one conversion.
- Observing a `Background` or `Dedicated` store from a model: those never touch the main thread; go through the screen store's gateway effect.
- Consuming `store.events` with a subject that replays: a recreated screen navigates again.
- Strong `self` inside an `observe(onEach:)` callback of a hand-written model: the model, and the store, never deinit.
