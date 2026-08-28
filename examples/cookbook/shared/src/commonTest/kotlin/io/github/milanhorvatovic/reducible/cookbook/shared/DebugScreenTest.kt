package io.github.milanhorvatovic.reducible.cookbook.shared

import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.IdentifiedEffectKey
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.cookbook.session.Account
import io.github.milanhorvatovic.reducible.cookbook.session.SessionAction
import io.github.milanhorvatovic.reducible.cookbook.session.SessionState
import io.github.milanhorvatovic.reducible.cookbook.session.SessionToken
import io.github.milanhorvatovic.reducible.cookbook.session.SignInForm
import io.github.milanhorvatovic.reducible.cookbook.session.SignInState
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugAction
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugEntry
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugEvent
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugLog
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.DebugState
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.ReplayVerdict
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.debugEffectHandler
import io.github.milanhorvatovic.reducible.cookbook.shared.debug.debugReducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.runtime.ActionRecorder
import io.github.milanhorvatovic.reducible.runtime.Defect
import io.github.milanhorvatovic.reducible.runtime.EffectEnd
import io.github.milanhorvatovic.reducible.runtime.EffectEvent
import io.github.milanhorvatovic.reducible.runtime.StoreWarning
import io.github.milanhorvatovic.reducible.runtime.plus
import io.github.milanhorvatovic.reducible.test.given
import io.github.milanhorvatovic.reducible.test.testStore
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private data object HintKey : EffectKey

/** A state whose serializer forgets a field — what a round trip must catch. */
private data class Lossy(
    val kept: String,
    val dropped: Int,
)

private object LossySerializer : KSerializer<Lossy> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Lossy", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Lossy,
    ) = encoder.encodeString(value.kept)

    override fun deserialize(decoder: Decoder): Lossy = Lossy(decoder.decodeString(), dropped = 0)
}

class DebugScreenTest {
    @Test
    fun back_asks_the_holder_to_close_and_changes_nothing() {
        debugReducer
            .given(DebugState())
            .on(DebugAction.BackClicked)
            .expect(DebugState())
            .expectNoEffects()
            .expectFollowUps(DebugEvent.Close)
            .andOn(DebugEvent.Close)
            .expect(DebugState())
            .expectNoEffects()
            .expectFollowUps()
    }

    @Test
    fun the_log_observer_redacts_what_the_types_redact() {
        val log = DebugLog()
        val observer = log.observer<SessionState, SessionAction>("session")

        observer.onReduced(
            SessionState.SignedOut(),
            SessionAction.SignIn("ada@example.com", "secret"),
            SessionState.SigningIn("ada@example.com"),
        )

        val entry = assertIs<DebugEntry.Reduced>(log.entries.value.single())
        assertEquals("session", entry.store)
        assertTrue("ada@example.com" in entry.action)
        assertFalse("secret" in entry.action, "password leaked into the debug log: ${entry.action}")
    }

    @Test
    fun effect_events_and_warnings_land_as_their_own_entries() {
        val log = DebugLog()
        val observer = log.observer<String, String>("recipes")

        observer.onEffect(EffectEvent.Launched("Load", EffectScope.StateScoped, key = null, queued = false))
        observer.onEffect(EffectEvent.Ended("Load", key = null, end = EffectEnd.CancelledByOwner))
        observer.onEffect(EffectEvent.Ended("Hint", key = IdentifiedEffectKey("notes", HintKey), end = EffectEnd.CancelledByKey))
        observer.onWarning(StoreWarning.SentAfterClose("Retry"))

        val (launched, ended, hint, warned) = log.entries.value
        assertEquals(DebugEntry.Effect(1, "recipes", "Load", "launched", key = null), launched)
        assertEquals(DebugEntry.Effect(2, "recipes", "Load", "cancelled, owner left", key = null), ended)
        // The key names which debounce was replaced, as the path composition built.
        assertEquals(DebugEntry.Effect(3, "recipes", "Hint", "cancelled, key relaunched", key = "notes/HintKey"), hint)
        assertEquals(DebugEntry.Warning(4, "recipes", "sent after close, dropped: Retry"), warned)
    }

    @Test
    fun the_round_trip_check_stays_silent_for_a_state_its_serializer_preserves() {
        val log = DebugLog()
        val check = log.roundTrip<SignInState, String>("signIn", SignInState.serializer())

        check.onReduced(SignInState.Editing(), "typed", SignInState.Editing(SignInForm(email = "ada@example.com")))

        assertTrue(log.entries.value.isEmpty(), "a faithful serializer logs nothing: ${log.entries.value}")
    }

    @Test
    fun the_round_trip_check_logs_a_defect_for_a_state_its_serializer_loses() {
        val log = DebugLog()
        val check = log.roundTrip<Lossy, String>("lossy", LossySerializer)

        check.onReduced(Lossy("a", 0), "bump", Lossy("a", dropped = 7))

        val defect = assertIs<DebugEntry.Defect>(log.entries.value.single())
        assertEquals("lossy", defect.store)
        assertTrue("round trip" in defect.message && "bump" in defect.message, defect.message)
    }

    @Test
    fun the_determinism_check_returns_the_first_result_and_stays_silent_for_a_pure_reducer() {
        val log = DebugLog()
        val reducer = log.deterministic("counter", Reducer<Int, Int, Nothing> { state, action -> (state + action).only() })

        assertEquals(3, reducer.reduce(1, 2).state)
        assertTrue(log.entries.value.isEmpty())
    }

    @Test
    fun the_determinism_check_logs_a_defect_when_two_reductions_disagree() {
        val log = DebugLog()
        var calls = 0
        val impure = Reducer<Int, Int, Nothing> { state, action -> (state + action + calls++).only() }
        val reducer = log.deterministic("counter", impure)

        reducer.reduce(0, 1)

        val defect = assertIs<DebugEntry.Defect>(log.entries.value.single())
        assertTrue("not deterministic" in defect.message && "1" in defect.message, defect.message)
    }

    @Test
    fun defects_name_where_they_happened() {
        val log = DebugLog()
        val defects = log.defects("debug")

        defects.onDefect(Defect.InEffect("Explode", IllegalStateException("effect bug")))
        defects.onDefect(Defect.InReducer(DebugState(), DebugAction.ThrowInReducer, IllegalStateException("reducer bug")))

        assertEquals(
            listOf(
                "effect Explode: IllegalStateException: effect bug",
                "reducer on ThrowInReducer: IllegalStateException: reducer bug",
            ),
            log.entries.value.map { entry -> (entry as DebugEntry.Defect).message },
        )
    }

    @Test
    fun long_states_are_previewed_not_stored_whole() {
        val log = DebugLog(previewLength = 20)
        val observer = log.observer<String, String>("wide")

        observer.onReduced("before", "tick", "x".repeat(500))

        val entry = assertIs<DebugEntry.Reduced>(log.entries.value.single())
        assertEquals("x".repeat(20) + "…", entry.state)
        assertEquals("tick", entry.action)
    }

    @Test
    fun the_session_recording_is_dropped_when_the_session_ends() {
        val recorder = ActionRecorder<SessionState, SessionAction>()
        val observer = recorder + atSessionEnd(recorder::clear)
        val account = Account("ada@example.com", "Ada")
        val token = SessionToken("token-1", ttlMillis = 1_000)

        observer.onReduced(
            SessionState.SignedOut(),
            SessionAction.SignIn("ada@example.com", "secret"),
            SessionState.SigningIn("ada@example.com"),
        )
        observer.onReduced(
            SessionState.SigningIn("ada@example.com"),
            SessionAction.Authenticated(account, token),
            SessionState.SignedIn(account, token),
        )
        assertEquals(2, recorder.recording?.actions?.size)

        observer.onReduced(SessionState.SignedIn(account, token), SessionAction.SignOut, SessionState.SignedOut())

        assertNull(recorder.recording, "nothing of a signed-out session may stay recorded")
    }

    @Test
    fun the_log_is_bounded_and_keeps_the_newest_entries() {
        val log = DebugLog(capacity = 3)

        repeat(5) { index -> log.audit("event $index") }

        assertEquals(listOf("event 2", "event 3", "event 4"), log.entries.value.map { entry -> (entry as DebugEntry.Audit).event })
    }

    @Test
    fun the_screen_follows_the_log_logs_its_own_defect_and_reports_the_replay_verdict() =
        runTest {
            val log = DebugLog()
            val store =
                testStore(
                    initialState = DebugState(),
                    reducer = debugReducer,
                    handler = debugEffectHandler(log, replayCheck = { ReplayVerdict.Reproduced(actions = 3) }),
                    defects = log.defects("debug"),
                )

            store.send(DebugAction.Started)
            runCurrent()
            log.audit("SignedIn(email=ada@example.com)")
            runCurrent()
            store.send(DebugAction.ThrowInEffect)
            advanceUntilIdle()
            store.send(DebugAction.VerifyReplay)
            advanceUntilIdle()

            store
                .expectAction(DebugAction.Started)
                .expectAction(
                    DebugAction.EntriesChanged(
                        log.entries.value
                            .take(0)
                            .toPersistent(),
                    ),
                    resulting = DebugState(),
                ).expectAction(
                    DebugAction.EntriesChanged(
                        log.entries.value
                            .take(1)
                            .toPersistent(),
                    ),
                ).expectAction(DebugAction.ThrowInEffect)
                // The deliberate throw reached the defect handler, which appended to the log, which fed the screen.
                .expectAction(
                    DebugAction.EntriesChanged(
                        log.entries.value
                            .take(2)
                            .toPersistent(),
                    ),
                ).expectAction(DebugAction.VerifyReplay)
                .expectAction(DebugAction.Replayed(ReplayVerdict.Reproduced(3)))
            val defect = assertIs<DebugEntry.Defect>(log.entries.value[1])
            assertEquals("debug", defect.store)
            assertTrue("Deliberate defect" in defect.message, defect.message)
            assertEquals(ReplayVerdict.Reproduced(3), store.state.replay)
            // The log observation never completes on its own, so close instead of finish.
            store.expectNoMoreActions()
            store.close()
        }
}

private fun List<DebugEntry>.toPersistent() = toPersistentList()
