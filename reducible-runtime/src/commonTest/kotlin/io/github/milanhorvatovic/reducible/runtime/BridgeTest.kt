package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private data class ScreenState(
    val latest: Int,
)

private sealed interface ScreenAction {
    data object Start : ScreenAction

    data class AppChanged(
        val value: Int,
    ) : ScreenAction
}

private sealed interface ScreenEffect {
    data object ObserveApp : ScreenEffect
}

private val screenReducer =
    Reducer<ScreenState, ScreenAction, ScreenEffect> { state, action ->
        when (action) {
            ScreenAction.Start -> state.withEffect(ScreenEffect.ObserveApp)
            is ScreenAction.AppChanged -> state.copy(latest = action.value).only()
        }
    }

class BridgeTest {
    @Test
    fun screen_store_follows_the_app_store_through_a_bridging_effect() =
        runTest {
            val testing = StoreScope.Testing(StandardTestDispatcher(testScheduler))
            val appStore =
                Store(
                    initialState = 0,
                    reducer = Reducer<Int, Int, Nothing> { _, action -> action.only() },
                    handler = EffectHandler { _, _ -> },
                    scope = testing,
                )
            val screenStore =
                Store(
                    initialState = ScreenState(latest = -1),
                    reducer = screenReducer,
                    handler =
                        EffectHandler<ScreenEffect, ScreenAction> { effect, send ->
                            when (effect) {
                                ScreenEffect.ObserveApp -> {
                                    appStore.stateFlow.feedInto(send) { appState -> ScreenAction.AppChanged(appState) }
                                }
                            }
                        },
                    scope = testing,
                )

            screenStore.send(ScreenAction.Start)
            runCurrent()
            // The StateFlow source delivers its current value on subscription.
            assertEquals(0, screenStore.state.latest)

            appStore.send(5)
            runCurrent()
            assertEquals(5, screenStore.state.latest)

            // Closing the observer store ends the bridge; the app store keeps moving alone.
            screenStore.close()
            appStore.send(7)
            runCurrent()
            assertEquals(7, appStore.state)
            assertEquals(5, screenStore.state.latest)

            appStore.close()
        }
}
