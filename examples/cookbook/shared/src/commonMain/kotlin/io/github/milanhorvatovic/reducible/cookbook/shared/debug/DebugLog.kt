package io.github.milanhorvatovic.reducible.cookbook.shared.debug

import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.runtime.Defect
import io.github.milanhorvatovic.reducible.runtime.DefectHandler
import io.github.milanhorvatovic.reducible.runtime.EffectEnd
import io.github.milanhorvatovic.reducible.runtime.EffectEvent
import io.github.milanhorvatovic.reducible.runtime.StoreObserver
import io.github.milanhorvatovic.reducible.runtime.StoreWarning
import io.github.milanhorvatovic.reducible.runtime.loggingDefectHandler
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

public sealed interface DebugEntry {
    public val sequence: Int

    public data class Reduced(
        override val sequence: Int,
        public val store: String,
        public val action: String,
        public val state: String,
    ) : DebugEntry

    public data class Audit(
        override val sequence: Int,
        public val event: String,
    ) : DebugEntry

    public data class Defect(
        override val sequence: Int,
        public val store: String,
        public val message: String,
    ) : DebugEntry

    /**
     * One step of an effect's life: launched, queued, skipped, completed, cancelled, or ended in
     * a defect. [key] is the effect's key as a path (`notes/editor/HintDebounce`), null when
     * unkeyed — it is what explains a cancellation by key.
     */
    public data class Effect(
        override val sequence: Int,
        public val store: String,
        public val effect: String,
        public val phase: String,
        public val key: String?,
    ) : DebugEntry

    /** Something the runtime tolerated but a feature author should look at. */
    public data class Warning(
        override val sequence: Int,
        public val store: String,
        public val message: String,
    ) : DebugEntry
}

/**
 * One log for every store in the app, fed from each store's observer hook and defect handler:
 * reductions, each effect's launch and end, runtime warnings, and defects. Appends arrive from
 * every store's confined thread at once — the main thread, a background worker, the dedicated
 * executor, a sender racing a close — so the log is a CAS-updated flow rather than a list.
 * Stringified actions, states, and effects are what gets logged: types carrying secrets redact
 * them in their `toString`.
 */
@OptIn(ExperimentalAtomicApi::class)
public class DebugLog(
    private val capacity: Int = 200,
    private val previewLength: Int = 400,
) {
    private val sequence = AtomicInt(0)
    private val log = MutableStateFlow<PersistentList<DebugEntry>>(persistentListOf())

    public val entries: StateFlow<PersistentList<DebugEntry>> = log.asStateFlow()

    /**
     * Logs every reduction, effect event, and warning of the store named [store], with actions,
     * states, and effects previewed to [previewLength].
     */
    public fun <S, A> observer(store: String): StoreObserver<S, A> =
        object : StoreObserver<S, A> {
            override fun onReduced(
                previous: S,
                action: A,
                next: S,
            ) {
                append { sequence -> DebugEntry.Reduced(sequence, store, preview(action), preview(next)) }
            }

            override fun onEffect(event: EffectEvent) {
                append { sequence -> DebugEntry.Effect(sequence, store, preview(event.effect), event.phase(), event.key?.let(::preview)) }
            }

            override fun onWarning(warning: StoreWarning<A>) {
                append { sequence -> DebugEntry.Warning(sequence, store, warning.message()) }
            }
        }

    /** Logs the defects of the store named [store] — an escaped handler exception or a throwing reducer — keeping the app alive. */
    public fun defects(store: String): DefectHandler<Any?, Any?, Any?> =
        loggingDefectHandler { exception ->
            val error = exception.defect.error
            val where =
                when (val defect = exception.defect) {
                    is Defect.InEffect -> "effect ${preview(defect.effect)}"
                    is Defect.InReducer -> "reducer on ${preview(defect.action)}"
                    is Defect.FollowUpCycle -> "follow-up cycle after ${preview(defect.action)}"
                }
            append { sequence -> DebugEntry.Defect(sequence, store, "$where: ${error::class.simpleName}: ${error.message}") }
        }

    /**
     * Checks on every reduction the contract process-death restore stands on: the new state
     * survives a JSON round trip through [serializer] unchanged. Android tests that contract
     * only at the moment it restores; this tests it on every reduction, on both platforms, so a
     * field a serializer drops or an `equals` that disagrees with the decoded value shows up
     * as a defect naming the action. One encode and one decode per reduction on the store's
     * thread — a diagnostics build pays it, release never installs it.
     */
    internal fun <S, A> roundTrip(
        store: String,
        serializer: KSerializer<S>,
    ): StoreObserver<S, A> =
        StoreObserver { _, action, next ->
            val restored =
                try {
                    Json.decodeFromString(serializer, Json.encodeToString(serializer, next))
                } catch (failure: IllegalArgumentException) {
                    append { sequence ->
                        DebugEntry.Defect(
                            sequence,
                            store,
                            "state after ${preview(action)} does not survive serialization: ${failure.message}",
                        )
                    }
                    return@StoreObserver
                }
            if (restored != next) {
                append { sequence ->
                    DebugEntry.Defect(
                        sequence,
                        store,
                        "state after ${preview(
                            action,
                        )} changed across a serialization round trip: ${preview(next)} came back as ${preview(restored)}",
                    )
                }
            }
        }

    /**
     * Reduces every action twice and logs a defect when the results differ — a reducer that read
     * a clock, a random source, or mutable state it captured. Effects compare by value, since
     * their envelopes carry owner functions that are never equal. Doubles the reducer's cost;
     * a diagnostics build pays it.
     */
    internal fun <S, A, E> deterministic(
        store: String,
        reducer: Reducer<S, A, E>,
    ): Reducer<S, A, E> =
        Reducer { state, action ->
            val first = reducer.reduce(state, action)
            val second = reducer.reduce(state, action)
            val same =
                first.state == second.state &&
                    first.effects.map { envelope -> envelope.effect } == second.effects.map { envelope -> envelope.effect } &&
                    first.followUps == second.followUps
            if (!same) {
                append { sequence ->
                    DebugEntry.Defect(sequence, store, "reducer is not deterministic on ${preview(action)} in ${preview(state)}")
                }
            }
            first
        }

    private fun EffectEvent.phase(): String =
        when (this) {
            is EffectEvent.Launched -> {
                if (queued) {
                    "queued"
                } else {
                    "launched"
                }
            }

            is EffectEvent.Ended -> {
                when (end) {
                    EffectEnd.Completed -> "completed"
                    EffectEnd.Defect -> "defect"
                    EffectEnd.CancelledByOwner -> "cancelled, owner left"
                    EffectEnd.CancelledByKey -> "cancelled, key relaunched"
                }
            }

            is EffectEvent.Skipped -> {
                "skipped, owner already gone"
            }
        }

    private fun StoreWarning<*>.message(): String =
        when (this) {
            is StoreWarning.SentAfterClose -> "sent after close, dropped: ${preview(action)}"
            is StoreWarning.SentFromFinishedEffect -> "sent from a finished effect ${preview(effect)}: ${preview(action)}"
            is StoreWarning.EventDropped -> "event dropped, nothing collecting: ${preview(event)}"
        }

    public fun audit(event: String) {
        append { sequence -> DebugEntry.Audit(sequence, event) }
    }

    public fun clear() {
        log.value = persistentListOf()
    }

    // A list state renders to kilobytes; two hundred of those is memory the log has no use for.
    private fun preview(value: Any?): String {
        val text = value.toString()
        return if (text.length <= previewLength) {
            text
        } else {
            text.take(previewLength) + "…"
        }
    }

    private fun append(entry: (sequence: Int) -> DebugEntry) {
        val next = entry(sequence.addAndFetch(1))
        log.update { current ->
            (
                if (current.size < capacity) {
                    current
                } else {
                    current.removingAt(0)
                }
            ).adding(next)
        }
    }
}
