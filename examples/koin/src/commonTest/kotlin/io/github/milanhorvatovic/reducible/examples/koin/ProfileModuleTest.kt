package io.github.milanhorvatovic.reducible.examples.koin

import io.github.milanhorvatovic.reducible.runtime.StoreScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.koin.core.Koin
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileModuleTest {
    private val ada = Profile(name = "Ada", email = "ada@example.com")

    @Test
    fun the_app_modules_resolve_the_store_factory() {
        val koin = koinApplication { modules(profileModule, simulatedNetworkModule) }.koin

        assertNotNull(koin.getOrNull<ProfileStores>())
        koin.close()
    }

    @Test
    fun a_store_from_the_factory_loads_through_the_bound_repository() =
        runTest {
            val koin = koinWith(ProfileRepository { ada })
            val store = koin.get<ProfileStores>().profileStore(testingScope())

            advanceUntilIdle()

            assertEquals(ProfileState.Loaded(ada), store.state)
            store.close()
            koin.close()
        }

    @Test
    fun a_failing_repository_lands_in_failed_and_retry_asks_again() =
        runTest {
            var attempts = 0
            val koin =
                koinWith(
                    ProfileRepository {
                        attempts++
                        if (attempts == 1) {
                            throw ProfileUnavailable("offline")
                        } else {
                            ada
                        }
                    },
                )
            val store = koin.get<ProfileStores>().profileStore(testingScope())

            advanceUntilIdle()
            assertEquals(ProfileState.Failed("offline"), store.state)

            store.send(ProfileAction.Retry)
            advanceUntilIdle()
            assertEquals(ProfileState.Loaded(ada), store.state)

            store.close()
            koin.close()
        }

    /** The feature module plus a test binding for the boundary the app would bind to a network. */
    private fun koinWith(repository: ProfileRepository): Koin =
        koinApplication {
            modules(profileModule, module { single<ProfileRepository> { repository } })
        }.koin

    private fun TestScope.testingScope(): StoreScope = StoreScope.Testing(StandardTestDispatcher(testScheduler))
}
