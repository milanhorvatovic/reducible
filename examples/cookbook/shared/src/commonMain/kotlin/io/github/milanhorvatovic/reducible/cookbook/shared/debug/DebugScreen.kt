package io.github.milanhorvatovic.reducible.cookbook.shared.debug

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.Event
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.andSend
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.runtime.feedInto
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

/** The debug screen: the live action log plus three probes into the runtime. */
public data class DebugState(
    public val entries: PersistentList<DebugEntry> = persistentListOf(),
    public val replay: ReplayVerdict? = null,
)

/** Whether replaying the recorded session actions over the pure reducer reproduces the live session state. */
public sealed interface ReplayVerdict {
    public data object NothingRecorded : ReplayVerdict

    public data class Reproduced(
        public val actions: Int,
    ) : ReplayVerdict

    public data class Diverged(
        public val actions: Int,
        public val replayed: String,
        public val live: String,
    ) : ReplayVerdict
}

public sealed interface DebugAction {
    public sealed interface Ui : DebugAction

    public data object Clear : Ui

    /** Makes an effect handler throw, to show a defect being logged instead of crashing. */
    public data object ThrowInEffect : Ui

    /** Makes this reducer throw, to show the action skipped, the state kept, and the defect logged. */
    public data object ThrowInReducer : Ui

    public data object VerifyReplay : Ui

    public data object BackClicked : Ui

    public data object Started : DebugAction

    public data class EntriesChanged(
        public val entries: PersistentList<DebugEntry>,
    ) : DebugAction

    public data class Replayed(
        public val verdict: ReplayVerdict,
    ) : DebugAction
}

/** What the debug screen asks its holder to do; an [Event], so the store publishes it. */
public sealed interface DebugEvent :
    DebugAction,
    Event {
    public data object Close : DebugEvent
}

public sealed interface DebugEffect {
    public data object ObserveLog : DebugEffect

    public data object ClearLog : DebugEffect

    public data object Explode : DebugEffect

    public data object Replay : DebugEffect
}

internal data object ObserveLogKey : EffectKey

public val debugReducer: Reducer<DebugState, DebugAction, DebugEffect> =
    Reducer { state, action ->
        when (action) {
            DebugAction.Started -> state.withEffect(DebugEffect.ObserveLog, key = ObserveLogKey)
            is DebugAction.EntriesChanged -> state.copy(entries = action.entries).only()
            DebugAction.Clear -> state.copy(replay = null).withEffect(DebugEffect.ClearLog)
            DebugAction.ThrowInEffect -> state.withEffect(DebugEffect.Explode)
            DebugAction.ThrowInReducer -> throw IllegalStateException("Deliberate defect requested from the debug screen reducer")
            DebugAction.VerifyReplay -> state.withEffect(DebugEffect.Replay)
            is DebugAction.Replayed -> state.copy(replay = action.verdict).only()
            DebugAction.BackClicked -> state.andSend(DebugEvent.Close)
            DebugEvent.Close -> state.only()
        }
    }

public fun debugEffectHandler(
    log: DebugLog,
    replayCheck: () -> ReplayVerdict,
): EffectHandler<DebugEffect, DebugAction> =
    EffectHandler { effect, send ->
        when (effect) {
            DebugEffect.ObserveLog -> log.entries.feedInto(send, DebugAction::EntriesChanged)
            DebugEffect.ClearLog -> log.clear()
            DebugEffect.Explode -> throw IllegalStateException("Deliberate defect requested from the debug screen")
            DebugEffect.Replay -> send(DebugAction.Replayed(replayCheck()))
        }
    }
