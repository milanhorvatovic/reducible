---
name: android-holder
description: >
  Holds a reducible store on Android: a plain ViewModel lending
  StoreScope.Inherited(viewModelScope), StoreViewModel from
  reducible-android for process-death restore through SavedStateHandle and
  a KSerializer, Compose collection of a ViewStore with
  collectAsStateWithLifecycle, store events collected at the navigation
  host, and the Application-owned composition root with a Dedicated
  executor. Use when writing an Android screen, ViewModel, or NavHost
  over a store.
---

# Android holder

## Purpose

On Android the ViewModel is the scope holder and nothing more: it builds the store in `StoreScope.Inherited(viewModelScope)`, exposes the view, and, when the screen must survive process death, owns the saved-state round trip. Logic that grows beyond that belongs in the reducer.

## Instructions

### 1. The composition root lives in the Application

```kotlin
class CookbookApplication : Application() {
    private val storesExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "app-stores") }

    val stores: AppStores by lazy {
        AppStores(
            settingsScope = StoreScope.Dedicated(storesExecutor.asCoroutineDispatcher()),
            diagnostics = BuildConfig.DEBUG,
        )
    }
}

val Context.appStores: AppStores
    get() = (applicationContext as CookbookApplication).stores
```

One named executor for every `Dedicated` store gives them a total order and a recognizable thread in stack traces.

### 2. A plain ViewModel when nothing needs restoring

```kotlin
class ShellViewModel(stores: AppStores) : ViewModel() {
    private val store = stores.shellStore(StoreScope.Inherited(viewModelScope))
    val view: ViewStore<ShellState, ShellAction.Ui> = stores.shellView(store)
}
```

The store reduces on the ViewModel's dispatcher (a main dispatcher keeps same-turn reduction) and closes when the ViewModel is cleared; there is no `onCleared` to write. Use this for screens whose state derives entirely from app-scoped stores on start, where a restored snapshot would only be overwritten.

### 3. `StoreViewModel` when the screen must survive process death

```kotlin
class SignInViewModel(savedStateHandle: SavedStateHandle, stores: AppStores) :
    StoreViewModel<SignInState, SignInAction>(
        savedStateHandle = savedStateHandle,
        serializer = SignInState.serializer(),
        createStore = { restored, scope -> stores.signInStore(restored, scope) },
    ) {
    val view: ViewStore<SignInViewState, SignInAction.Ui> = stores.signInView(store)
}
```

- `createStore` receives the decoded previous state (or null) and the `Inherited` scope; the factory passes `start`, and the reducer's state-aware `Started` makes the restored state converge.
- Every reduction is captured back through a saved-state provider as JSON of `serializer`. Pass `persisted = { state -> state.withoutFetchedRows() }` to drop what the start action refetches anyway: every screen shares one saved-state transaction budget of about a megabyte.
- Saved state that no longer decodes (an older app version's shape) is a fresh start reported to `restoreFailed`, never a crash loop.
- Requires `reducible-android` and the state hierarchy marked `@Serializable`.

### 4. The Compose screen

```kotlin
@Composable
fun SignInRoute(viewModel: SignInViewModel) {
    val state by viewModel.view.stateFlow.collectAsStateWithLifecycle()
    SignInScreen(state = state, send = viewModel.view::send)
}
```

The screen composable takes `state: SignInViewState` and `send: (SignInAction.Ui) -> Unit` and nothing else, so previews and UI tests need no store. Text fields send `DraftChanged(text)` on change; there is no view-local copy of the draft.

### 5. Events at the navigation host

Screens never navigate. A screen sends an intent, the store publishes an `Event`, and the destination that owns the `NavController` collects it while composed:

```kotlin
composable(route = "recipes", content = { backStackEntry ->
    val viewModel: RecipesViewModel = viewModel(initializer = { RecipesViewModel(createSavedStateHandle(), stores) })
    OnEvents(events = stores.recipesEvents(viewModel.store), handle = { event ->
        when (event) {
            is RecipesEvent.OpenRecipe -> navController.navigate("recipe/${event.id}")
            RecipesEvent.SignOut -> shell.send(ShellAction.SignOut)
        }
    })
    RecipesRoute(viewModel)
})

@Composable
private fun <E : Any> OnEvents(events: Flow<E>, handle: (E) -> Unit) {
    val current by rememberUpdatedState(handle)
    LaunchedEffect(key1 = Unit, block = { events.collect { event -> current(event) } })
}
```

Keyed on nothing: the store outlives every recomposition, so the flow taken at the first one stays right, and `rememberUpdatedState` keeps the handler fresh. An event nobody collects is dropped and reported as `StoreWarning.EventDropped`; a destination collects exactly while its screen can be asking.

### 6. Verify

Reducer and `testStore` tests cover the feature; the holder is verified by running the app through rotation and, for `StoreViewModel`, through "Don't keep activities" or `adb shell am kill`, checking the screen converges rather than resets.

## Pitfalls

- Building a store inside a composable with `remember`: it is rebuilt on configuration change and never closed. The ViewModel is the holder.
- Collecting `store.events` with a replaying or buffering operator: a recreated screen would navigate again on an intent it already served.
- Calling `navController.navigate` from the screen composable: navigation is the host's reaction to an event.
- A `StoreViewModel` for a screen whose state is fully derived on start: the restore round trip buys nothing and costs the saved-state budget.
- Passing `viewModelScope` as a raw scope to a factory expecting `StoreScope`: wrap it, `StoreScope.Inherited(viewModelScope)`.
- Observing an app-scoped `Background` store from Compose directly instead of through the screen store's gateway effect.
