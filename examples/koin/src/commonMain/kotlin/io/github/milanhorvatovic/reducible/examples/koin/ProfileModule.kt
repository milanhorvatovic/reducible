package io.github.milanhorvatovic.reducible.examples.koin

import io.github.milanhorvatovic.reducible.runtime.Store
import io.github.milanhorvatovic.reducible.runtime.StoreScope
import kotlinx.coroutines.delay
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * The store factory Koin hands out. A class rather than a Koin definition of the store itself,
 * for two reasons: Koin resolves by class, so `Store<ProfileState, ProfileAction>` and another
 * feature's `Store<…>` would be the same definition in one graph; and a screen holder wants a
 * fresh store per screen, built in the holder's own [StoreScope] — a factory method with a
 * parameter, not a singleton to look up. The dependencies the feature cannot fake — here the
 * repository — are what Koin injects into the factory.
 */
public class ProfileStores(
    private val repository: ProfileRepository,
) {
    public fun profileStore(scope: StoreScope = StoreScope.Main): Store<ProfileState, ProfileAction> =
        Store(
            initialState = ProfileState.Loading,
            reducer = profileReducer,
            handler = profileEffectHandler(repository),
            scope = scope,
            start = ProfileAction.Started,
        )
}

/** The feature's definitions: everything it needs except the repository, which the app binds. */
public val profileModule: Module =
    module {
        singleOf(::ProfileStores)
    }

/** The app's binding for the repository, separate so a test or a preview binds a fake instead. */
public val simulatedNetworkModule: Module =
    module {
        single<ProfileRepository> { SimulatedProfileRepository() }
    }

/** Demo-only: answers after a delay, like a network would. */
public class SimulatedProfileRepository(
    private val latency: suspend () -> Unit = { delay(600) },
) : ProfileRepository {
    override suspend fun load(): Profile {
        latency()
        return Profile(name = "Ada Lovelace", email = "ada@example.com")
    }
}
