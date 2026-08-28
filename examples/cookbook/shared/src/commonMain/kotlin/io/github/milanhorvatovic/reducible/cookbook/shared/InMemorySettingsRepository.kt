package io.github.milanhorvatovic.reducible.cookbook.shared

import io.github.milanhorvatovic.reducible.cookbook.settings.Settings
import io.github.milanhorvatovic.reducible.cookbook.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Demo-only storage: in-memory with simulated latency. Safe under the store's sequential effect usage only. */
public class InMemorySettingsRepository(
    initial: Settings = Settings(),
    private val simulatedLatency: Duration = 300.milliseconds,
) : SettingsRepository {
    private var stored = initial

    override suspend fun load(): Settings {
        delay(simulatedLatency)
        return stored
    }

    override suspend fun save(settings: Settings) {
        delay(simulatedLatency)
        stored = settings
    }
}
