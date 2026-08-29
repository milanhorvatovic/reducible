# Examples

Each example is a Gradle module in this build and depends on the library by project path. They are not published.

| Example | Shows | Start at |
| --- | --- | --- |
| [`counter`](counter) | The smallest complete feature: state as phases, a state-scoped effect with an injected clock, a store factory, a view projection, reducer tests and a `TestStore` test | `Counter.kt` |
| [`koin`](koin) | Dependency injection with Koin: a repository behind a `fun interface`, a store factory Koin builds, tests that bind a fake repository | `ProfileModule.kt` |
| [`cookbook`](cookbook) | The end-to-end showcase on both platforms: four feature modules, a composition root with app-scoped and screen stores, a Jetpack Compose app, a SwiftUI app, and a map from every library capability to the file that uses it | `cookbook/README.md` |

```sh
./gradlew :examples:counter:allTests -PskipIosTests
./gradlew :examples:koin:allTests -PskipIosTests
./gradlew :examples:cookbook:androidApp:assembleDebug
```
