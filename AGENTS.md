# Agent instructions

Guidance for coding agents working in this repository. Humans: see [CONTRIBUTING.md](CONTRIBUTING.md).

## What this is

A Kotlin Multiplatform library (`reducible-*` modules) for unidirectional state — pure reducers, runtime-owned effects, optics-based composition — plus examples under `examples/`. Only the `reducible-*` modules are published; the examples consume them by project path.

## Build and verify

- Inner loop: `./gradlew build -PskipIosTests` (JVM host tests, kotlinter lint, API check). Run it before reporting a change as done.
- Full: `./gradlew build` also runs the iOS simulator tests (needs Xcode).
- A public-API change must be accompanied by `./gradlew apiDump`; `apiCheck` fails otherwise.
- `./gradlew formatKotlin` fixes Kotlin lint findings; the style is `ktlint_official`. `./gradlew detekt` (in `check`) enforces braces on every `if` branch and every multi-line `when` branch.
- `pre-commit run --all-files` runs the other formatters and linters: ktlint on Gradle scripts, SwiftFormat and SwiftLint (macOS), prettier and markdownlint on Markdown, yamllint and actionlint on YAML, taplo on TOML. Needs `npm ci` first. Run it before reporting a change that touches those files as done.
- No JDK on `PATH`? Point `JAVA_HOME` at a JDK 17+, for example Android Studio's bundled runtime. The daemon then provisions its own JDK 21 (`gradle/gradle-daemon-jvm.properties`).

## Rules of the code

- Every module declares `explicitApi()`. New public symbols are a deliberate choice; prefer `internal`.
- Reducers are pure: no clocks, no randomness, no I/O. Handlers are total: expected failures become typed actions. Suspensions (`delay`, clocks) are injected through the wiring, never called in a feature module directly.
- Feature modules depend on `reducible-core` only; `reducible-runtime` reaches them through the composition root.
- Tests live next to the code: the `given / on / expect` DSL for reducers, `testStore` under virtual time for anything touching the runtime, with `finish()` passing.
- Comments explain why, not what; KDoc on public symbols states the contract.
- Write explicit code, never the abbreviated form. Swift: `self.` on every member access, `return` written out, named closure parameters (no `$0`, no `_ in` for a value the closure receives), `if let x = x`, explicit types, `-> Void`, `get {}`, `= nil`; a closure is passed with its argument label whenever the API has one (`Button("Retry", action: { … })`, `VStack(content: { … })`, `ForEach(rows, content: { row in … })`, `.toolbar(content: { … })`, `store.observe(onEach: { state in … })`), and only an unlabeled parameter (`map(_:)`, `.task(_:)`, `withMutation(keyPath:_:)`) keeps the trailing form — SwiftLint's custom rules `trailing_closure_argument` and `unnamed_closure_parameter` fail the build otherwise; add an API to the pattern's allow-list only when it truly has no label. Kotlin: braces on every `if` branch and every multi-line `when` branch; lambda parameters named, never `it`; in Compose code every lambda passed to a composable goes by parameter name (`content =`, `itemContent =`, `builder =`, `initializer =`), never as a trailing lambda. All of it is enforced: ktlint and detekt's shipped rules for layout and braces, the repository's own detekt rules in `detekt-rules/` (`NoImplicitIt` everywhere, `NamedComposableLambdas` in Compose modules via `config/detekt/compose.yml`), SwiftLint's custom rules for Swift. Do not loosen the tools to make a shortcut pass; extend `detekt-rules/` when a new rule is needed.

## Commits

Conventional commits, imperative subject, ≤72 characters, body in flowing paragraphs — one source line per paragraph, no hard wrap; readers' tools wrap — explaining why when the diff does not. Scopes: `core`, `runtime`, `test`, `immutable`, `optics-arrow`, `android`, `swift`, `examples`, `skill`; none for cross-cutting changes. Do not add attribution trailers unless asked.
