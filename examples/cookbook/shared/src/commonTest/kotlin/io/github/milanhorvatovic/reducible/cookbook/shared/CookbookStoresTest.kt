package io.github.milanhorvatovic.reducible.cookbook.shared

import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugEntry
import io.github.milanhorvatovic.reducible.runtime.StoreScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class CookbookStoresTest {
    // The session store's observer is the recorder, the log, and the user wipe joined on one
    // hook, through a nullable-receiver `plus` that once recursed into itself: constructing the
    // root with diagnostics on overflowed the stack before the first screen existed.
    @Test
    fun the_composition_root_builds_with_diagnostics_on_and_logs_the_settings_start() =
        runTest {
            val stores =
                CookbookStores(
                    settingsScope = StoreScope.Testing(StandardTestDispatcher(testScheduler)),
                    diagnostics = true,
                )
            try {
                advanceUntilIdle()

                val settingsEntries =
                    stores.debugLog.entries.value
                        .filterIsInstance<DebugEntry.Reduced>()
                        .filter { entry -> entry.store == "settings" }
                assertTrue(
                    settingsEntries.any { entry -> "Started" in entry.action },
                    "the log observes the settings store: ${stores.debugLog.entries.value}",
                )
                assertTrue(
                    settingsEntries.any { entry ->
                        "Loaded" in entry.action
                    },
                    "the start action loaded the settings: $settingsEntries",
                )
            } finally {
                stores.close()
            }
        }
}
