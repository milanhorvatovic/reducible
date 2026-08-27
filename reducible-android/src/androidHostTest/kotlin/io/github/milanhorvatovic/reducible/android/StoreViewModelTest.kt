package io.github.milanhorvatovic.reducible.android

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.runtime.Store
import io.github.milanhorvatovic.reducible.runtime.StoreObserver
import io.github.milanhorvatovic.reducible.runtime.StoreWarning
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class CountingViewModel(
    savedStateHandle: SavedStateHandle,
    observer: StoreObserver<Int, Unit>,
) : StoreViewModel<Int, Unit>(
        savedStateHandle = savedStateHandle,
        serializer = Int.serializer(),
        createStore = { restored, scope ->
            Store(
                initialState = restored ?: 0,
                reducer = Reducer<Int, Unit, Nothing> { state, _ -> (state + 1).only() },
                handler = EffectHandler { _, _ -> },
                scope = scope,
                observer = observer,
            )
        },
    )

private class RecordingWarnings : StoreObserver<Int, Unit> {
    val warnings = mutableListOf<StoreWarning<Unit>>()

    override fun onReduced(
        previous: Int,
        action: Unit,
        next: Int,
    ) {}

    override fun onWarning(warning: StoreWarning<Unit>) {
        warnings += warning
    }
}

class StoreViewModelTest {
    // No send before the clear: on a host JVM the main-thread check would reach Looper, which
    // the stub android.jar does not implement. The closed flag is checked ahead of it.
    @Test
    fun clearing_the_view_model_closes_the_store_without_an_onCleared_override() {
        val warnings = RecordingWarnings()
        val viewModels = ViewModelStore()
        val viewModel = CountingViewModel(SavedStateHandle(), warnings)
        viewModels.put("counting", viewModel)
        assertTrue(warnings.warnings.isEmpty())

        viewModels.clear()
        viewModel.send(Unit)

        assertIs<StoreWarning.SentAfterClose<Unit>>(warnings.warnings.single())
    }
}
