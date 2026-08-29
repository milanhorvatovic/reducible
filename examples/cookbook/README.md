# Cookbook

The end-to-end example: a deliberately generic recipe app whose only purpose is to exercise every part of the library — four feature modules, a composition root that wires the stores, a Jetpack Compose app, and a SwiftUI app. Nothing in it is a product; everything in it is a pattern.

## Layout

| Module | Purpose |
| --- | --- |
| `feature-notes` | Notes with an embedded editor child: `ifPresent`, keyed debounce, process-death restore; embedded again as a child of the recipe detail |
| `feature-session` | App-scoped session on a background store, sign-in screen, app shell; free vs state-scoped effects |
| `feature-settings` | App-scoped settings in a dedicated scope with keyed write-behind persistence, and the settings screen over it |
| `feature-recipes` | Recipes list of identified rows with per-row download effects, keyed search; recipe detail embedding step timers and the notes feature |
| `shared` | The composition root (`CookbookStores`): app-scoped stores, screen-store factories, typed views, the debug log; builds the `Shared` framework for iOS with the SwiftUI bridge bundled |
| `androidApp` | Compose: navigation shell, sign-in, recipes and detail, settings, action log |
| `iosApp` | SwiftUI; consumes the `Shared` framework (see its [README](iosApp/README.md)) |

Feature modules depend on `reducible-core` (and each other) only, so reducers cannot reach coroutines at compile time; `reducible-runtime` is wired in through `shared`. The Android app depends on `shared` and `reducible-android`; the iOS app on the framework alone.

## Running the demo

```sh
./gradlew :examples:cookbook:androidApp:assembleDebug        # Android APK
./gradlew :examples:cookbook:shared:assembleSharedXCFramework  # iOS XCFramework
open examples/cookbook/iosApp/iosApp.xcodeproj                 # iOS app; Xcode builds the framework in a pre-build phase
```

- Sign in with any email and the password `secret`. The password `offline` simulates no network; anything else is rejected.
- Sessions expire on their own after 90 seconds; the sign-in screen then says why.
- The **Debug** screen shows every reduction of every store, each effect's launch and end, runtime warnings, audit events, and defects. **Verify replay** replays the recorded session actions over the pure reducer and compares with the live state. **Throw in effect** makes an effect handler throw, to show a defect being logged instead of crashing; **Throw in reducer** makes the debug reducer itself throw, to show the action skipped, the state kept, and the defect logged.
- **Settings › Failure injection** makes the fake repositories fail on their next call: recipe loads and searches, downloads, and notes. Every error and retry path in the app is reachable from it.

## Showcase map

The Cookbook demo exists to exercise every part of the library end to end. This page maps each library capability to the place in the demo that uses it, so a reader can go from "what does `forEachIdentified` look like in practice" to a file in one step. Paths into the demo are relative to this directory; paths into the library (`reducible-*`, `swift/`) are relative to the repository root. Symbols are named so `grep` finds them when lines move.

## Core: reducers, effects, composition

| Capability | Where |
| --- | --- |
| Sealed state as phases, `Reduced`, `only()`, `withEffect` | Every feature; the smallest is `feature-recipes/…/StepTimer.kt` (`stepReducer`) |
| Mixed effect scopes in one reduction (`Reduced` with envelopes) | `feature-session/…/Session.kt` (`sessionReducer`, sign-in requests `Authenticate` state-scoped and `Audit` free) |
| `EffectScope.Free` vs `StateScoped` | `Session.kt`: the audit record lands even when a sign-out cancels authentication. Test: `SessionEffectScopeIntegrationTest` |
| Keyed effects as debounce (cancel-previous plus a leading window) | `feature-recipes/…/Recipes.kt` (`SearchKey`, `RecipesEffect.Search`) and `feature-notes/…/NotesFeature.kt` (`HintDebounce`) |
| Keyed effect as write-behind: a debounce window that schedules the write | `feature-settings/…/Settings.kt` (`PersistWindowKey`, `SettingsEffect.SchedulePersist`) |
| `KeyPolicy.Ordered`: writes queue behind one in flight instead of cancelling it | `Settings.kt` (`PersistKey`); test `SettingsReducerTest.an_edit_during_a_write_queues_behind_it_and_both_land_in_order` |
| Keyed effect as "retry replaces the request in flight" | `Recipes.kt` (`LoadKey`), `RecipeDetail.kt` (`DetailLoadKey`) |
| Long-running observation effect, free and keyed | `SignIn.kt`, `Shell.kt`, `RecipeDetail.kt` (`ObserveSettings`), `SettingsScreen.kt` |
| `combine` | `Recipes.kt`, `RecipeDetail.kt`, `NotesFeature.kt` |
| `ifPresent` over an optional child | `RecipeDetail.kt` (notes child), `NotesFeature.kt` (editor child) |
| `forEachIdentified` over identified rows | `Recipes.kt` (recipe rows), `RecipeDetail.kt` (step timers) — both through the persistent-list variant in `reducible-immutable`, which replaces one row by structural sharing; the copying variant in `reducible-core` remains for plain lists, and `rowReduced` is the shared building block |
| `IdentifiedAction`, `IdentifiedEffectKey` | `Recipes.kt` (`recipeRowAction`), test `RecipesReducerTest.row_actions_are_routed_by_identity_and_their_effects_re_keyed_per_row`; child slots `RecipeDetail.kt` (`slot = "notes"`), `NotesFeature.kt` (`slot = "editor"`), keys shown as paths on the Debug screen |
| `EffectOwner`: focus plus phase ownership of child effects | `reducible-core/…/Composition.kt` (`orClassOf`, `through`, `throughElement`); tests `ChildPhaseOwnerTest`, `OwnedEffectTest`, `RecipeDetailIntegrationTest.timers_tick_independently_and_pausing_one_stops_only_its_countdown` |
| `andSend`: a reduction asks the store to reduce follow-up actions next, before anything sent later | `RecipeDetail.kt` (the first `Loaded` and a restored `Started` start the notes child); test `RecipeDetailReducerTest` |
| Handler-side composition with `handle` | `Recipes.kt` and `RecipeDetail.kt` (`recipesEffectHandler`, `recipeDetailEffectHandler`) |
| Handlers-total: expected failures become typed actions | `Session.kt` (`AuthException`), `RecipeRow.kt` (`RecipesException`), `NotesRepository.kt` |
| Features carry no coroutines: suspensions injected by the wiring | `sessionEffectHandler(expiry = …)`, `settingsEffectHandler(persistWindow = …)`, `recipesEffectHandler(searchWindow = …)`, `stepEffectHandler(tick = …)` |
| Gateways instead of store references across features | `SessionGateway.kt`, `SettingsGateway.kt`; implemented in `shared/…/CookbookStores.kt` (`StoreSessionGateway`, `StoreSettingsGateway`) |
| Restored state converges after process death | `Recipes.kt` (`Started` in `Loaded` resets interrupted downloads), `RecipeDetail.kt` (running timers come back paused), `SignIn.kt` |

## Optics

| Capability | Where |
| --- | --- |
| Hand-written `casePrism`, `lens`, `optional`, `prism`, `andThen` | `Recipes.kt` (`rowsOptional`, `rowActionPrism`), `RecipeDetail.kt` (`readyPrism`, `notesOptional`, `stepsOptional`), `NotesFeature.kt` (`editorOptional`) |
| Arrow Optics adapters, optional module | `reducible-optics-arrow` (`asCoreLens`, `asCoreOptional`, `asCorePrism`) with `ArrowAdaptersTest`; no demo module depends on it, so generated optics never reach the framework's API |
| Optic law assertions | `RecipesOpticsLawsTest`, `RecipeDetailOpticsTest`, `NotesOpticsLawsTest` (`assertOptionalLaws`, `assertPrismLaws`, `assertLensLaws`) |

## Runtime: stores and their scopes

| Capability | Where |
| --- | --- |
| `StoreScope.Main` screen stores | The default of every `*Store()` factory in `CookbookStores.kt` except the two app-scoped stores below; iOS builds them so |
| `StoreScope.Inherited` over `viewModelScope` | Android `StoreViewModel` (`reducible-android`) and the plain ViewModels in `AppRoot.kt`, `DebugScreen.kt`, `SettingsScreen.kt`: the store reduces on the ViewModel's dispatcher and closes with it, no `onCleared` |
| Start action owned by the store factory (`start = …Started`) | Every started store in `CookbookStores.kt`; the Android ViewModels only wire, the Swift model factories wire and close |
| `StoreScope.Background` app-scoped store | `CookbookStores.session` |
| `StoreScope.Dedicated` on a platform-supplied executor | `CookbookStores.settings`; Android `CookbookApplication.kt` (named single-thread executor), iOS `CookbookApp.swift` (`dedicated(queue:)` over a serial GCD queue) |
| `StoreScope.Testing` | Every `testStore(…)` and the direct `Store(…)` calls in `reducible-runtime` tests |
| Screen-to-host events: `Event` actions published on `Store.events` | `RecipesEvent`, `RecipeDetailEvent`, `SettingsScreenEvent`, `DebugEvent`, `SignInEvent`; the reducers answer `*Clicked` intents with `andSend(event)` and reduce the event as a no-op, so it also lands in the log and the recording. `CookbookStores.*Events` narrow the stream per screen; Android `AppRoot.kt` collects per destination, iOS `RecipesView.swift` and `SignInView.swift` in `.task`. Test: `StoreEventsTest` |
| Dropped-event warning (`StoreWarning.EventDropped`) | Emitted when nothing collects; the debug log shows it. Test: `StoreEventsTest.an_event_nobody_collects_is_dropped_and_reported` |
| `feedInto` bridge from an app-scoped store into a screen | `CookbookStores.kt` (`StoreSessionGateway.observe`, `StoreSettingsGateway.observe`), `shared/…/debug/DebugScreen.kt` (`ObserveLog`) |
| `StoreObserver` and fan-out with `plus` | `CookbookStores.session` (`sessionRecorder + debugLog.observer("session")`) |
| `ActionRecorder`, `Recording`, `replay` | `CookbookStores.verifySessionReplay`; surfaced by the Debug screen's **Verify replay** |
| Bounded recording, cleared when its secrets expire | `CookbookStores.sessionRecorder` (`capacity = 100`); test `ActionRecorderTest.a_bounded_recorder_drops_the_oldest_action_and_rebaselines_exactly` |
| User-scoped data wiped with the session | `CookbookStores.forgetUser` via `atSessionEnd`: the recording and the per-recipe notes repositories, cleared from the session's thread with a CAS-guarded map |
| `DefectHandler`, `loggingDefectHandler`: effect and reducer defects on one policy | `shared/…/debug/DebugLog.kt` (`defects`); triggered by **Throw in effect** and **Throw in reducer** |
| `StoreObserver.onEffect` and `onWarning`: effect timeline and runtime warnings | `DebugLog.kt` (`observer`): launched, queued, skipped, completed, cancelled by owner or key, defect; sends after close and from finished effects |
| A store deliberately left unobserved (feedback loop) | `CookbookStores.debugStore` |
| Debug invariants: every reduction round-trips the state through its serializer, every reducer runs twice | `DebugLog.kt` (`roundTrip`, `deterministic`), wired in `CookbookStores` (`roundTripOf`, `reducerOf`) while diagnostics are on; failures land as defects on the Debug screen |
| Secrets redacted before observers stringify state | `Session.kt` (`SignIn.toString`), `SignIn.kt` (`SignInForm`, `PasswordChanged`); test `SignInReducerTest.passwords_never_appear_in_stringified_states_actions_or_effects` |

## Views: what a screen sees

| Capability | Where |
| --- | --- |
| `ViewStore`, `Store.view` | `reducible-runtime/…/ViewStore.kt`; every `*View(store)` factory in `CookbookStores.kt` |
| One projection per reduction, cached by upstream identity | `ViewStore.kt` (`ProjectedStateFlow.projectionOf`); test `ViewStoreTest.repeated_state_reads_return_one_projection_until_the_store_reduces_again` |
| Nested `ViewStore.view` per tab, narrowed state and vocabulary | `shared/…/RecipeDetailViews.kt` |
| Action subset by sealed `Ui` sub-interface | `NotesAction.Ui`, `RecipesAction.Ui`, `RecipeRowAction.Ui`, `RecipeDetailAction.Ui` and `.Servings`, `SignInAction.Ui`, `ShellAction.Ui`, `DebugAction.Ui`, `SettingsScreenAction.Ui` |
| Projection that drops fields the screen never renders | `NotesViewState.kt`, `RecipesViewState.kt` (search narrows rows here, never in feature state), `RecipeDetailViewState.kt` (amounts scaled and converted on read) |
| Typed view factories for Swift | `CookbookStores.kt` (`*View`), `RecipeDetailViews.kt`; helper actions `recipeRowAction`, `stepAction`, `notesAction` |

## Platform holders

| Capability | Where |
| --- | --- |
| `StoreViewModel` with process-death restore | Android `SignInScreen.kt`, `RecipesScreen.kt`, `RecipeDetailScreen.kt` |
| Plain `ViewModel` for stores derived from app-scoped sources | Android `AppRoot.kt` (shell), `DebugScreen.kt`, `SettingsScreen.kt` |
| Application-owned composition root | Android `CookbookApplication.kt`; iOS `CookbookApp.swift` |
| `StoreModel` owner plus `ViewStoreModel` per view (`@Observable`) | `swift/StoreModel.swift`, `ViewStoreModel.swift`; iOS `Models.swift` |
| `ProjectedModel`: one Swift value per reduction, rendering never crosses the boundary | `swift/ProjectedModel.swift`; iOS `RecipesView.swift` (`recipesUI`), `RecipeDetailView.swift` (`stepsUI`). The other screens stay on `ViewStoreModel` to show both tiers |
| Models created once per view identity (`@StateObject` autoclosure) | Every iOS screen `init`; `ObservableObject` conformance on both models exists for this alone |
| Forcing a Kotlin/Native collection before reading a memory graph | `shared/src/iosMain/kotlin/…/Diagnostics.kt` (`collectGarbage`); Debug screen's **Collect garbage** on iOS |
| Four view models retaining one owner | iOS `Models.swift` (`makeRecipeDetailModels`) |
| Combine `statePublisher` for `ObservableObject` screens | `swift/StorePublisher.swift`; iOS `SettingsView.swift` (`SettingsModel`) |
| SwiftUI bindings that write by sending actions | `ViewStoreModel.binding(get:send:)`; iOS `SignInView.swift`, `RecipesView.swift` (`searchable`) |
| State-driven flow selection at the root | Android `AppRoot.kt`, iOS `RootView.swift` over `ShellState` |

## Testing

| Capability | Where |
| --- | --- |
| Pure reducer DSL `given / on / expect / expectEnvelopes` | Every `*ReducerTest` |
| `testStore` integration under virtual time, exhaustive `finish()` | `RecipesDownloadIntegrationTest`, `SessionEffectScopeIntegrationTest`, `SettingsReducerTest`, `NotesLifecycleIntegrationTest` |
| Ending a test whose observation never completes (`expectNoMoreActions` then `close`) | `RecipeDetailIntegrationTest`, `SettingsScreenTest`, `DebugScreenTest` |
| Pure projection tests | Every `*ViewStateTest`, `QuantityFormattingTest` |
| Serialization round trips for saved state | `RecipesReducerTest`, `RecipeDetailReducerTest`, `SignInReducerTest`, `NotesStateSerializationTest` |
