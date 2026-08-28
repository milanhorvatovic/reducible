package io.github.milanhorvatovic.reducible.cookbook.session

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.test.given
import kotlin.test.Test

private val account = Account("ada@example.com", "Ada")
private val token = SessionToken("token-1", ttlMillis = 60_000)

class SessionReducerTest {
    @Test
    fun sign_in_authenticates_state_scoped_and_audits_free() {
        sessionReducer
            .given(SessionState.SignedOut())
            .on(SessionAction.SignIn("ada@example.com", "secret"))
            .expect(SessionState.SigningIn("ada@example.com"))
            .expectEnvelopes(
                EffectEnvelope(SessionEffect.Authenticate("ada@example.com", "secret")),
                EffectEnvelope(SessionEffect.Audit(AuditEvent.SignInAttempted("ada@example.com")), EffectScope.Free),
            ).andOn(SessionAction.Authenticated(account, token))
            .expect(SessionState.SignedIn(account, token))
            .expectEnvelopes(
                EffectEnvelope(SessionEffect.ScheduleExpiry(60_000), key = ExpiryKey),
                EffectEnvelope(SessionEffect.Audit(AuditEvent.SignedIn("ada@example.com")), EffectScope.Free),
            )
    }

    @Test
    fun failed_authentication_returns_to_signed_out_with_the_reason() {
        sessionReducer
            .given(SessionState.SigningIn("ada@example.com"))
            .on(SessionAction.AuthenticationFailed(SessionError.InvalidCredentials))
            .expect(SessionState.SignedOut(SessionError.InvalidCredentials))
            .expectNoEffects()
    }

    @Test
    fun sign_out_while_signing_in_abandons_the_attempt_silently() {
        sessionReducer
            .given(SessionState.SigningIn("ada@example.com"))
            .on(SessionAction.SignOut)
            .expect(SessionState.SignedOut())
            .expectNoEffects()
    }

    @Test
    fun expiry_signs_out_with_the_expired_reason_and_audits() {
        sessionReducer
            .given(SessionState.SignedIn(account, token))
            .on(SessionAction.TokenExpired)
            .expect(SessionState.SignedOut(SessionError.Expired))
            .expectEnvelopes(EffectEnvelope(SessionEffect.Audit(AuditEvent.SessionExpired), EffectScope.Free))
    }

    @Test
    fun sign_out_clears_the_session_and_audits() {
        sessionReducer
            .given(SessionState.SignedIn(account, token))
            .on(SessionAction.SignOut)
            .expect(SessionState.SignedOut())
            .expectEnvelopes(EffectEnvelope(SessionEffect.Audit(AuditEvent.SignedOut), EffectScope.Free))
    }

    @Test
    fun stale_effect_fed_actions_are_ignored_outside_their_state() {
        sessionReducer
            .given(SessionState.SignedOut())
            .on(SessionAction.Authenticated(account, token))
            .expect(SessionState.SignedOut())
            .expectNoEffects()
            .andOn(SessionAction.TokenExpired)
            .expect(SessionState.SignedOut())
            .expectNoEffects()
    }
}
