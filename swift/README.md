# SwiftUI and Combine bridge

Four Swift files that turn a `Store` or `ViewStore` exported from Kotlin into something a SwiftUI screen holds. They are not a Kotlin module — Swift cannot ship in a Maven artifact — so they are bundled into the app's own umbrella framework, which is where the Kotlin classes they reference (`Store`, `ViewStore`, `Subscription`) come from.

| File | Role |
| --- | --- |
| `StoreModel.swift` | Owns a store's lifetime: create it with view identity, it closes the store in `deinit`. `state` is a tracked pass-through of the store's snapshot, invalidated synchronously on the main thread as the store reduces. |
| `ViewStoreModel.swift` | The same over a `ViewStore`: projected state, `send` narrowed to the screen's actions, retains its `StoreModel` owner. Obtained from `StoreModel.view(_:)`. |
| `ProjectedModel.swift` | Converts each projection once into a Swift value the body renders from, so list diffing and scrolling never cross the Kotlin boundary. Obtained from `StoreModel.project(_:_:)`. |
| `StorePublisher.swift` | `statePublisher(of:)`: a Combine publisher over a store or view, for `ObservableObject` screens not on iOS 17 observation. |

All three models conform to `ObservableObject` for one reason: `@StateObject`'s autoclosure creates them once per view identity. Invalidation comes from `@Observable`; `objectWillChange` never fires. Call `activate()` from `.task`, so store lifetime (view identity) and observation lifetime stay separate.

## Using it

With [SKIE](https://skie.touchlab.co) applied to the umbrella module, Swift files under `src/iosMain/swift` are compiled into the framework. Copy these four files there (or symlink the directory, as [`examples/cookbook/shared`](../examples/cookbook/shared/src/iosMain) does), export `reducible-runtime` from the framework, and the models are available to the app under the framework's module name:

```kotlin
// umbrella module's build.gradle.kts
kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            export(libs.reducible.core)
            export(libs.reducible.runtime)
        }
    }
}
```

Without SKIE, add the files to the app target instead and prefix each with `import <YourFramework>`.

Requires iOS 17 / macOS 14 for `@Observable`; `statePublisher(of:)` alone works on earlier versions.
