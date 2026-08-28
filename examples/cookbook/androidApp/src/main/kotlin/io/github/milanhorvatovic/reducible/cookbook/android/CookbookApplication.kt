package io.github.milanhorvatovic.reducible.cookbook.android

import android.app.Application
import android.content.Context
import io.github.milanhorvatovic.reducible.cookbook.shared.CookbookStores
import io.github.milanhorvatovic.reducible.runtime.StoreScope
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

class CookbookApplication : Application() {
    /** Every Dedicated store in the app shares this one thread, named so it is recognizable in stack traces. */
    private val storesExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "cookbook-stores") }

    val stores: CookbookStores by lazy {
        CookbookStores(
            settingsScope = StoreScope.Dedicated(storesExecutor.asCoroutineDispatcher()),
            diagnostics = BuildConfig.DEBUG,
        )
    }
}

val Context.cookbookStores: CookbookStores
    get() = (applicationContext as CookbookApplication).stores
