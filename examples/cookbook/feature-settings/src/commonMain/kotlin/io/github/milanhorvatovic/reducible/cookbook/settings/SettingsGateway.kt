package io.github.milanhorvatovic.reducible.cookbook.settings

/**
 * How other features follow and change the app-scoped settings store without depending on the
 * runtime: the wiring implements it over the store. [observe] never returns — it delivers the
 * settings once loaded, then every change, until cancelled. The mutators fire and forget; the
 * outcome arrives through [observe], so a screen never holds an optimistic copy.
 */
public interface SettingsGateway {
    public suspend fun observe(onEach: (Settings) -> Unit)

    public suspend fun changeUnits(units: Units)

    public suspend fun toggleFailureInjection()
}
