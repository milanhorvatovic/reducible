package io.github.milanhorvatovic.reducible.cookbook.session

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.test.given
import kotlin.test.Test

private val account = Account("ada@example.com", "Ada")
private val token = SessionToken("token-1", ttlMillis = 60_000)

class ShellReducerTest {
    @Test
    fun the_shell_follows_the_session_and_stays_starting_until_it_speaks() {
        shellReducer
            .given(ShellState.Starting)
            .on(ShellAction.Started)
            .expect(ShellState.Starting)
            .expectEnvelopes(EffectEnvelope(ShellEffect.ObserveSession, EffectScope.Free, ShellObserveKey))
            .andOn(ShellAction.SessionChanged(SessionState.SignedOut()))
            .expect(ShellState.SignedOut)
            .andOn(ShellAction.SessionChanged(SessionState.SigningIn("ada@example.com")))
            .expect(ShellState.SignedOut)
            .andOn(ShellAction.SessionChanged(SessionState.SignedIn(account, token)))
            .expect(ShellState.SignedIn(account))
            .expectNoEffects()
    }

    @Test
    fun sign_out_is_forwarded_to_the_session_not_applied_locally() {
        shellReducer
            .given(ShellState.SignedIn(account))
            .on(ShellAction.SignOut)
            .expect(ShellState.SignedIn(account))
            .expectEffects(ShellEffect.SignOut)
    }
}
