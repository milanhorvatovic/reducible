package io.github.milanhorvatovic.reducible.cookbook.settings

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.test.given
import io.github.milanhorvatovic.reducible.test.testStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A gateway that answers the observation from its own memory, like the store-backed one does. */
private class MemoryGateway(
    private var settings: Settings = Settings(),
) : SettingsGateway {
    val changes = mutableListOf<Settings>()
    private var listener: ((Settings) -> Unit)? = null

    override suspend fun observe(onEach: (Settings) -> Unit) {
        delay(50)
        listener = onEach
        onEach(settings)
        CompletableDeferred<Unit>().await()
    }

    override suspend fun changeUnits(units: Units) {
        apply(settings.copy(units = units))
    }

    override suspend fun toggleFailureInjection() {
        apply(settings.copy(failureInjection = !settings.failureInjection))
    }

    private fun apply(next: Settings) {
        settings = next
        changes += next
        listener?.invoke(next)
    }
}

class SettingsScreenTest {
    @Test
    fun back_asks_the_holder_to_close_and_changes_nothing() {
        settingsScreenReducer
            .given(SettingsScreenState.Loading)
            .on(SettingsScreenAction.BackClicked)
            .expect(SettingsScreenState.Loading)
            .expectNoEffects()
            .expectFollowUps(SettingsScreenEvent.Close)
            .andOn(SettingsScreenEvent.Close)
            .expect(SettingsScreenState.Loading)
            .expectNoEffects()
            .expectFollowUps()
    }

    @Test
    fun the_screen_observes_free_and_keyed_and_forwards_edits_without_a_local_copy() {
        settingsScreenReducer
            .given(SettingsScreenState.Loading)
            .on(SettingsScreenAction.Started)
            .expect(SettingsScreenState.Loading)
            .expectEnvelopes(EffectEnvelope(SettingsScreenEffect.Observe, EffectScope.Free, SettingsScreenObserveKey))
            .andOn(SettingsScreenAction.SettingsChanged(Settings()))
            .expect(SettingsScreenState.Ready(Settings()))
            .expectNoEffects()
            .andOn(SettingsScreenAction.UnitsSelected(Units.Imperial))
            .expect(SettingsScreenState.Ready(Settings()))
            .expectEffects(SettingsScreenEffect.ChangeUnits(Units.Imperial))
            .andOn(SettingsScreenAction.FailureInjectionToggled)
            .expect(SettingsScreenState.Ready(Settings()))
            .expectEffects(SettingsScreenEffect.ToggleFailureInjection)
    }

    @Test
    fun view_state_disables_controls_until_the_first_value_arrives() {
        assertEquals(
            SettingsViewState(ready = false, units = Units.Metric, failureInjection = false),
            settingsViewState(SettingsScreenState.Loading),
        )
        assertEquals(
            SettingsViewState(ready = true, units = Units.Imperial, failureInjection = true),
            settingsViewState(SettingsScreenState.Ready(Settings(Units.Imperial, failureInjection = true))),
        )
    }

    @Test
    fun edits_round_trip_through_the_gateway_and_come_back_as_changes() =
        runTest {
            val gateway = MemoryGateway()
            val store = testStore(SettingsScreenState.Loading, settingsScreenReducer, settingsScreenEffectHandler(gateway))

            store.send(SettingsScreenAction.Started)
            advanceUntilIdle()
            store.send(SettingsScreenAction.UnitsSelected(Units.Imperial))
            advanceUntilIdle()
            store.send(SettingsScreenAction.FailureInjectionToggled)
            advanceUntilIdle()

            store
                .expectAction(SettingsScreenAction.Started)
                .expectAction(SettingsScreenAction.SettingsChanged(Settings()), resulting = SettingsScreenState.Ready(Settings()))
                .expectAction(SettingsScreenAction.UnitsSelected(Units.Imperial))
                .expectAction(SettingsScreenAction.SettingsChanged(Settings(Units.Imperial)))
                .expectAction(SettingsScreenAction.FailureInjectionToggled)
                .expectAction(
                    SettingsScreenAction.SettingsChanged(Settings(Units.Imperial, failureInjection = true)),
                    resulting = SettingsScreenState.Ready(Settings(Units.Imperial, failureInjection = true)),
                )
            assertEquals(listOf(Settings(Units.Imperial), Settings(Units.Imperial, failureInjection = true)), gateway.changes)
            // The observation never completes on its own, so close instead of finish.
            store.expectNoMoreActions()
            store.close()
        }
}
