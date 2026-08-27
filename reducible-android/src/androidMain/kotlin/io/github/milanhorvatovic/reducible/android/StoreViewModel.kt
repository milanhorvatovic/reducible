package io.github.milanhorvatovic.reducible.android

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.milanhorvatovic.reducible.runtime.Store
import io.github.milanhorvatovic.reducible.runtime.StoreScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * The scope holder for a screen store: it lends the store its lifetime and owns the
 * process-death round trip, nothing else — logic that grows beyond that belongs in the
 * reducer.
 *
 * [createStore] receives the state a previous process saved (or null on a fresh start) and
 * the scope to build the store in: [StoreScope.Inherited] over `viewModelScope`, so the
 * store reduces on the ViewModel's dispatcher and closes when the ViewModel is cleared, with
 * no `onCleared` to write. A factory that builds the store in some other scope owns its
 * close. Every reduction's state is then captured back through a saved-state provider,
 * serialized as JSON of [serializer]. A feature ViewModel is its store wiring and nothing
 * else: the store factory sends the feature's unconditional start action
 * (`Store(..., start = )`), and the reducer recovers state-aware, which is what makes the
 * restored-state path converge after process death.
 *
 * Saved state that cannot be decoded — a Bundle written by an app version whose state shape
 * differed — is a fresh start, reported to [restoreFailed], never a crash that repeats until
 * the task is cleared. [persisted] chooses what is saved: the identity by default, or a
 * projection that drops what the start action refetches anyway (loaded rows, say), because
 * every screen's saved state shares one transaction budget of about a megabyte and a
 * fetched list survives it only until it does not.
 */
public abstract class StoreViewModel<S : Any, A>(
    savedStateHandle: SavedStateHandle,
    private val serializer: KSerializer<S>,
    createStore: (restored: S?, scope: StoreScope) -> Store<S, A>,
    private val persisted: (S) -> S = { state -> state },
    restoreFailed: (Throwable) -> Unit = {},
) : ViewModel() {
    public val store: Store<S, A> =
        createStore(
            restoredState(savedStateHandle.get<Bundle>(KEY)?.getString(KEY), serializer, restoreFailed),
            StoreScope.Inherited(viewModelScope),
        )

    /** The reactive surface for Compose collection. */
    public val state: StateFlow<S>
        get() = store.stateFlow

    init {
        savedStateHandle.setSavedStateProvider(KEY) {
            Bundle().apply { putString(KEY, Json.encodeToString(serializer, persisted(store.state))) }
        }
    }

    public fun send(action: A) {
        store.send(action)
    }

    private companion object {
        const val KEY = "store-state"
    }
}

/**
 * Decodes what a previous process saved, or nothing when [json] is absent or unreadable —
 * the latter reported to [restoreFailed]. `Json` signals an unreadable input with
 * `SerializationException` and a decoded value that is no valid `S` with its supertype
 * `IllegalArgumentException`; both mean the same here: start fresh.
 */
internal fun <S> restoredState(
    json: String?,
    serializer: KSerializer<S>,
    restoreFailed: (Throwable) -> Unit,
): S? {
    if (json == null) {
        return null
    }
    return try {
        Json.decodeFromString(serializer, json)
    } catch (failure: IllegalArgumentException) {
        restoreFailed(failure)
        null
    }
}
