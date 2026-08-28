package io.github.milanhorvatovic.reducible.cookbook.settings

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.test.given
import io.github.milanhorvatovic.reducible.test.testStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class RecordingRepository(
    private var stored: Settings = Settings(),
) : SettingsRepository {
    val saves = mutableListOf<Settings>()

    override suspend fun load(): Settings {
        delay(100)
        return stored
    }

    override suspend fun save(settings: Settings) {
        delay(100)
        stored = settings
        saves += settings
    }
}

class SettingsReducerTest {
    @Test
    fun start_loads_edits_schedule_a_write_and_the_due_write_is_keyed_for_order() {
        settingsReducer
            .given(SettingsState.Loading)
            .on(SettingsAction.Started)
            .expect(SettingsState.Loading)
            .expectEffects(SettingsEffect.Load)
            .andOn(SettingsAction.Loaded(Settings()))
            .expect(SettingsState.Ready(Settings()))
            .expectNoEffects()
            .andOn(SettingsAction.UnitsChanged(Units.Imperial))
            .expect(SettingsState.Ready(Settings(units = Units.Imperial)))
            .expectEnvelopes(EffectEnvelope(SettingsEffect.SchedulePersist, EffectScope.StateScoped, PersistWindowKey))
            .andOn(SettingsAction.FailureInjectionToggled)
            .expect(SettingsState.Ready(Settings(units = Units.Imperial, failureInjection = true)))
            .expectEnvelopes(EffectEnvelope(SettingsEffect.SchedulePersist, EffectScope.StateScoped, PersistWindowKey))
            .andOn(SettingsAction.PersistDue)
            .expect(SettingsState.Ready(Settings(units = Units.Imperial, failureInjection = true)))
            .expectEnvelopes(
                EffectEnvelope(
                    SettingsEffect.Persist(Settings(units = Units.Imperial, failureInjection = true)),
                    EffectScope.StateScoped,
                    PersistKey,
                ),
            )
    }

    @Test
    fun edits_and_due_writes_before_loading_are_dropped() {
        settingsReducer
            .given(SettingsState.Loading)
            .on(SettingsAction.UnitsChanged(Units.Imperial))
            .expect(SettingsState.Loading)
            .expectNoEffects()
            .andOn(SettingsAction.PersistDue)
            .expect(SettingsState.Loading)
            .expectNoEffects()
    }

    @Test
    fun a_burst_of_edits_writes_the_last_value_once() =
        runTest {
            val repository = RecordingRepository()
            val store = testStore(SettingsState.Loading, settingsReducer, settingsEffectHandler(repository, persistWindow = { delay(500) }))

            store.send(SettingsAction.Started)
            advanceUntilIdle()
            store.send(SettingsAction.UnitsChanged(Units.Imperial))
            advanceTimeBy(100)
            store.send(SettingsAction.FailureInjectionToggled)
            advanceTimeBy(100)
            store.send(SettingsAction.UnitsChanged(Units.Metric))
            advanceUntilIdle()

            val last = Settings(units = Units.Metric, failureInjection = true)
            store
                .expectAction(SettingsAction.Started)
                .expectAction(SettingsAction.Loaded(Settings()), resulting = SettingsState.Ready(Settings()))
                .expectAction(SettingsAction.UnitsChanged(Units.Imperial))
                .expectAction(SettingsAction.FailureInjectionToggled)
                .expectAction(SettingsAction.UnitsChanged(Units.Metric), resulting = SettingsState.Ready(last))
                .expectAction(SettingsAction.PersistDue, resulting = SettingsState.Ready(last))
            store.finish()
            assertEquals(listOf(last), repository.saves)
        }

    @Test
    fun an_edit_during_a_write_queues_behind_it_and_both_land_in_order() =
        runTest {
            val repository = RecordingRepository()
            // No window: every edit is due at once, so the second edit arrives while the first write runs.
            val store = testStore(SettingsState.Loading, settingsReducer, settingsEffectHandler(repository, persistWindow = {}))

            store.send(SettingsAction.Started)
            advanceUntilIdle()
            store.send(SettingsAction.UnitsChanged(Units.Imperial))
            advanceTimeBy(50)
            store.send(SettingsAction.FailureInjectionToggled)

            // The first write takes 100 and is not cancelled by the edit: it has landed while the
            // second, queued behind it, is still running.
            advanceTimeBy(110)
            assertEquals(listOf(Settings(units = Units.Imperial)), repository.saves)
            advanceUntilIdle()
            assertEquals(
                listOf(Settings(units = Units.Imperial), Settings(units = Units.Imperial, failureInjection = true)),
                repository.saves,
            )

            store
                .expectAction(SettingsAction.Started)
                .expectAction(SettingsAction.Loaded(Settings()))
                .expectAction(SettingsAction.UnitsChanged(Units.Imperial))
                .expectAction(SettingsAction.PersistDue)
                .expectAction(SettingsAction.FailureInjectionToggled)
                .expectAction(SettingsAction.PersistDue)
            store.finish()
        }
}
