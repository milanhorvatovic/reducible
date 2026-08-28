package io.github.milanhorvatovic.reducible.cookbook.session

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.test.given
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private val account = Account("ada@example.com", "Ada")
private val token = SessionToken("token-1", ttlMillis = 60_000)
private val filled = SignInForm("ada@example.com", "secret")

class SignInReducerTest {
    @Test
    fun the_debug_intent_becomes_an_event_for_the_holder_in_either_phase() {
        signInReducer
            .given(SignInState.Editing())
            .on(SignInAction.DebugClicked)
            .expect(SignInState.Editing())
            .expectNoEffects()
            .expectFollowUps(SignInEvent.OpenDebug)
            .andOn(SignInEvent.OpenDebug)
            .expect(SignInState.Editing())
            .expectFollowUps()
        signInReducer
            .given(SignInState.Submitting(filled))
            .on(SignInAction.DebugClicked)
            .expect(SignInState.Submitting(filled))
            .expectFollowUps(SignInEvent.OpenDebug)
    }

    @Test
    fun start_observes_the_session_free_and_keyed() {
        signInReducer
            .given(SignInState.Editing())
            .on(SignInAction.Started)
            .expect(SignInState.Editing())
            .expectEnvelopes(EffectEnvelope(SignInEffect.ObserveSession, EffectScope.Free, SignInObserveKey))
    }

    @Test
    fun typing_clears_the_previous_problem() {
        signInReducer
            .given(SignInState.Editing(SignInForm(), SignInProblem.EmailInvalid))
            .on(SignInAction.EmailChanged("ada@example.com"))
            .expect(SignInState.Editing(SignInForm(email = "ada@example.com")))
            .expectNoEffects()
    }

    @Test
    fun submit_validates_before_reaching_the_session() {
        signInReducer
            .given(SignInState.Editing(SignInForm("ada", "secret")))
            .on(SignInAction.Submit)
            .expect(SignInState.Editing(SignInForm("ada", "secret"), SignInProblem.EmailInvalid))
            .expectNoEffects()

        signInReducer
            .given(SignInState.Editing(SignInForm("ada@example.com", "")))
            .on(SignInAction.Submit)
            .expect(SignInState.Editing(SignInForm("ada@example.com", ""), SignInProblem.PasswordMissing))
            .expectNoEffects()
    }

    @Test
    fun valid_submit_hands_the_form_to_the_session_and_follows_its_verdict() {
        signInReducer
            .given(SignInState.Editing(filled))
            .on(SignInAction.Submit)
            .expect(SignInState.Submitting(filled))
            .expectEffects(SignInEffect.Submit("ada@example.com", "secret"))
            .andOn(SignInAction.SessionChanged(SessionState.SigningIn("ada@example.com")))
            .expect(SignInState.Submitting(filled))
            .expectNoEffects()
            .andOn(SignInAction.SessionChanged(SessionState.SignedOut(SessionError.InvalidCredentials)))
            .expect(SignInState.Editing(filled, SignInProblem.Rejected(SessionError.InvalidCredentials)))
            .expectNoEffects()
    }

    @Test
    fun a_signed_in_session_leaves_the_screen_untouched_for_the_shell_to_replace() {
        signInReducer
            .given(SignInState.Submitting(filled))
            .on(SignInAction.SessionChanged(SessionState.SignedIn(account, token)))
            .expect(SignInState.Submitting(filled))
            .expectNoEffects()
    }

    @Test
    fun inputs_are_ignored_while_submitting() {
        signInReducer
            .given(SignInState.Submitting(filled))
            .on(SignInAction.EmailChanged("x"))
            .expect(SignInState.Submitting(filled))
            .expectNoEffects()
            .andOn(SignInAction.Submit)
            .expect(SignInState.Submitting(filled))
            .expectNoEffects()
    }

    @Test
    fun view_state_enables_submit_only_for_a_filled_idle_form() {
        assertEquals(
            SignInViewState("ada@example.com", "secret", submitting = false, canSubmit = true, problem = null),
            signInViewState(SignInState.Editing(filled)),
        )
        assertEquals(
            SignInViewState("", "", submitting = false, canSubmit = false, problem = null),
            signInViewState(SignInState.Editing()),
        )
        assertEquals(
            SignInViewState("ada@example.com", "secret", submitting = true, canSubmit = false, problem = null),
            signInViewState(SignInState.Submitting(filled)),
        )
    }

    @Test
    fun state_round_trips_through_json_for_process_death() {
        val state: SignInState = SignInState.Editing(filled, SignInProblem.Rejected(SessionError.Offline))

        val json = Json.encodeToString(SignInState.serializer(), state)

        assertEquals(state, Json.decodeFromString(SignInState.serializer(), json))
    }

    @Test
    fun passwords_never_appear_in_stringified_states_actions_or_effects() {
        val rendered =
            listOf(
                SignInState.Submitting(filled).toString(),
                SignInAction.PasswordChanged("secret").toString(),
                SignInEffect.Submit("ada@example.com", "secret").toString(),
                SessionAction.SignIn("ada@example.com", "secret").toString(),
                SessionEffect.Authenticate("ada@example.com", "secret").toString(),
                signInViewState(SignInState.Editing(filled)).toString(),
            )

        rendered.forEach { text -> assertFalse("secret" in text, "password leaked into: $text") }
    }
}
