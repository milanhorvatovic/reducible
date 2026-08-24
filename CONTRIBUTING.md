# Contributing

Thanks for taking the time. Issues and pull requests are welcome; for anything larger than a fix, open an issue first so the design is agreed before the code is written.

## Setup

- JDK 17 or newer on `PATH` to launch Gradle. The daemon itself runs on JDK 21 (`gradle/gradle-daemon-jvm.properties`; detekt's embedded compiler does not run on newer JDKs) and the compile toolchain is 17; both are provisioned automatically through the Foojay resolver on first use.
- Android SDK, with `ANDROID_HOME` set or a `local.properties` at the repository root containing `sdk.dir=…`.
- Xcode with the iOS SDK, for the iOS targets, the simulator tests, and the Cookbook framework. The JVM host tests and lint run without it.
- Node (version in `.nvmrc`) and `npm ci`, which installs the pinned prettier and markdownlint.
- [pre-commit](https://pre-commit.com) (`brew install pre-commit`, `pipx install pre-commit`, or `mise use pre-commit`), then `pre-commit install` once in the clone. It runs the formatters and linters below on the files a commit touches.
- On macOS, `brew install swiftformat swiftlint` for the Swift hooks; the Linux CI job skips them and the macOS job runs them.

```sh
./gradlew build -PskipIosTests   # JVM host tests, lint, API check — the inner loop
./gradlew build                  # everything, including the iOS simulator tests
```

## Making a change

1. Branch from `main`.
2. Keep reducers pure and handlers total: expected failures become typed actions; an exception escaping a handler is a defect. Suspensions (delays, clocks) are injected by the wiring, never called from a feature module directly.
3. Add or update tests next to the code. Pure reducers use the `given / on / expect` DSL; anything that touches the runtime uses `testStore` under virtual time, and `finish()` must pass.
4. Public API changes: every module declares `explicitApi()`, and the public surface is pinned by the dumps under each module's `api/`. After a deliberate change run `./gradlew apiDump` and commit the dumps with the change. `./gradlew apiCheck` (part of `build`) fails on an unrecorded change.
5. Formatters and linters, one of each per language: Kotlin sources — ktlint through kotlinter (`./gradlew lintKotlin`, `./gradlew formatKotlin` to fix; `./gradlew installKotlinterPrePushHook` runs it before every push) and detekt (`./gradlew detekt`, part of `check`); Gradle scripts — the same ktlint through the pre-commit hook; Swift — SwiftFormat and SwiftLint; Markdown — prettier and markdownlint; YAML — yamllint and actionlint; TOML — taplo. `pre-commit run --all-files` runs everything but the Gradle-side Kotlin checks over the whole tree, which is what CI does.
6. The code is explicit rather than terse, and the tools enforce it. Swift: `self.` on every member access (SwiftFormat inserts it), `return` written out, closure parameters named rather than `$0` or `_`, `if let x = x` rather than the shorthand, and types, `-> Void`, `get {}`, and `= nil` kept where written. A closure is passed with its argument label whenever the API has one — `Button("Retry", action: { … })`, `VStack(content: { … })`, `ForEach(rows, content: { row in … })`, `.toolbar(content: { … })` — and only unlabeled parameters such as `map(_:)` or `.task(_:)` keep the trailing form; SwiftLint's custom rules `trailing_closure_argument` and `unnamed_closure_parameter` enforce both, by regex, so an unlabeled API the pattern does not know gets added to its allow-list rather than a disable comment. Kotlin: braces on every branch of an `if` and on every multi-line branch of a `when` (detekt), `explicitApi()` in every module, formatting by `ktlint_official`. Lambda parameters are named rather than `it`, and in Compose code every lambda passed to a composable is passed by parameter name — `Button(onClick = { … }, content = { Text("Retry") })`, `items(items = rows, key = { row -> row.id }, itemContent = { row -> … })`, `NavHost(…, builder = { … })` — never as a trailing lambda. Both are detekt rules of this repository (`detekt-rules/`: `NoImplicitIt`, `NamedComposableLambdas`), loaded into every module's `detekt` task; the Compose rule is switched on per module in `config/detekt/compose.yml`, where the lowercase builders it cannot recognise by shape are listed.
7. Commit in [conventional commits](https://www.conventionalcommits.org/) form — `feat(runtime): …`, `fix(core): …`, `docs: …` — with a subject in the imperative mood and a body that explains why when the diff does not, written as flowing paragraphs (one source line per paragraph; no hard wrap). Each commit should build and pass on its own.
8. Open the pull request against `main`. CI runs the JVM job on Linux and the iOS job on macOS; both must pass.

## Scopes

The commit scope is the module without its prefix: `core`, `runtime`, `test`, `immutable`, `optics-arrow`, `android`, `swift`, `examples`. Cross-cutting changes carry no scope.

## Releasing

Releases are cut from `main` by the maintainer: bump `version` in `gradle.properties`, move the `Unreleased` section of `CHANGELOG.md` under the new version, tag `v<version>`, and publish with `./gradlew publishToMavenCentral` in an environment that supplies the Maven Central credentials and the signing key (`ORG_GRADLE_PROJECT_mavenCentralUsername`, `ORG_GRADLE_PROJECT_mavenCentralPassword`, `ORG_GRADLE_PROJECT_signingInMemoryKey`, `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword`).
