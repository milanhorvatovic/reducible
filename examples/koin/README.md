# Dependency injection with Koin

How a feature's dependencies reach its stores when the app wires with [Koin](https://insert-koin.io).

- `Profile.kt` — a feature that loads a profile through a `ProfileRepository`, a `fun interface` the feature declares and the app implements. The handler is total: the repository's `ProfileUnavailable` becomes `ProfileAction.Failed`.
- `ProfileModule.kt` — `ProfileStores`, the store factory Koin builds with the repository injected; `profileModule`, the feature's own definitions; `simulatedNetworkModule`, the app's binding for the repository.
- `ProfileModuleTest.kt` — a Koin graph with a test binding for the repository, and the store under `StoreScope.Testing`.

## The pattern

Define the **store factory**, not the store, in Koin:

```kotlin
class ProfileStores(private val repository: ProfileRepository) {
    fun profileStore(scope: StoreScope = StoreScope.Main): Store<ProfileState, ProfileAction> =
        Store(ProfileState.Loading, profileReducer, profileEffectHandler(repository), scope = scope, start = ProfileAction.Started)
}

val profileModule = module {
    singleOf(::ProfileStores)
}
```

Two reasons. Koin resolves by class, so `Store<ProfileState, ProfileAction>` and another feature's `Store<…>` would be the same definition in one graph. And a screen holder wants a fresh store per screen, built in its own `StoreScope` — a factory method taking the scope, not a singleton to look up. What Koin injects is what the feature cannot fake for itself: the repository.

Keep the app's bindings for the feature's boundaries in a separate module, so a test binds a fake without touching the feature's definitions:

```kotlin
koinApplication {
    modules(profileModule, module { single<ProfileRepository> { ProfileRepository { Profile("Ada", "ada@example.com") } } })
}
```

## On the platforms

Android, with `koin-androidx-compose`: the ViewModel takes the factory and passes its own scope.

```kotlin
class ProfileViewModel(stores: ProfileStores) : ViewModel() {
    val store = stores.profileStore(StoreScope.Inherited(viewModelScope))
}

val androidModule = module { viewModelOf(::ProfileViewModel) }

@Composable
fun ProfileRoute(viewModel: ProfileViewModel = koinViewModel()) { … }
```

iOS: start Koin once from Kotlin (`startKoin { modules(profileModule, simulatedNetworkModule) }`), expose a small accessor from the umbrella module (`fun profileStores(): ProfileStores = KoinPlatform.getKoin().get()`), and hand the store to a `StoreModel` from the `swift/` bridge, which closes it in `deinit`.

```sh
./gradlew :examples:koin:allTests -PskipIosTests
```
