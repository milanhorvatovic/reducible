package io.github.milanhorvatovic.reducible.runtime

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A consistent snapshot of a store's history: [initialState] plus [actions] replayed over
 * the pure reducer (`reducer.replay(initialState, actions)`) reproduce the state trajectory;
 * replaying a prefix (`actions.take(n)`) walks it step by step.
 */
public data class Recording<S, A>(
    public val initialState: S,
    public val actions: List<A>,
)

/**
 * The recording observer: pass one to a store's `observer` parameter to capture every reduced
 * action in reduction order — effect-fed actions included. The caller owns the recorder, so a
 * staging build can hand it to any store factory and dump [recording] on demand.
 *
 * The baseline is the state before the first recorded action. [capacity] bounds the log: past
 * it the oldest action is dropped and the baseline moves to the state that action produced,
 * so replay over the retained tail stays exact. [clear] drops the log and re-baselines at the
 * next action. Actions are kept as the objects they are — a recorder on a store whose actions
 * carry secrets keeps those secrets alive for as long as the recording does; bound it, clear it
 * when the secret's lifetime ends, or do not record that store. A debugging tool, not a
 * persistence mechanism.
 */
@OptIn(ExperimentalAtomicApi::class)
public class ActionRecorder<S, A>(
    private val capacity: Int = Int.MAX_VALUE,
) : StoreObserver<S, A> {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    // The state each action produced is kept so a trimmed log can re-baseline exactly; states
    // are immutable and structurally shared, so this costs little beyond the reference.
    private class Entry<S, A>(
        val action: A,
        val next: S,
    )

    // PersistentList so an append shares structure instead of copying the whole log —
    // List.plus would make the nth append cost n and the session quadratic.
    private class Log<S, A>(
        val initialState: S,
        val entries: PersistentList<Entry<S, A>>,
    )

    private val current = AtomicReference<Log<S, A>?>(null)

    /** Null until the first action after creation or [clear]. */
    public val recording: Recording<S, A>?
        get() = current.load()?.let { log -> Recording(log.initialState, log.entries.map { entry -> entry.action }) }

    override fun onReduced(
        previous: S,
        action: A,
        next: S,
    ) {
        while (true) {
            val loaded = current.load()
            val updated =
                loaded?.let { log -> Log(log.initialState, log.entries.adding(Entry(action, next))).trimmed() }
                    ?: Log(previous, persistentListOf(Entry(action, next)))
            if (current.compareAndSet(loaded, updated)) {
                return
            }
        }
    }

    public fun clear() {
        current.store(null)
    }

    private fun Log<S, A>.trimmed(): Log<S, A> =
        if (entries.size <= capacity) {
            this
        } else {
            Log(entries[0].next, entries.removingAt(0))
        }
}
