package io.github.milanhorvatovic.reducible.cookbook.session

import io.github.milanhorvatovic.reducible.test.testStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private val account = Account("ada@example.com", "Ada")
private val token = SessionToken("token-1", ttlMillis = 5_000)

private object SlowAuth : AuthGateway {
    override suspend fun authenticate(
        email: String,
        password: String,
    ): Authentication {
        delay(1_000)
        return Authentication(account, token)
    }
}

private class RecordingAudit : AuditSink {
    val events = mutableListOf<AuditEvent>()

    override suspend fun record(event: AuditEvent) {
        delay(300)
        events += event
    }
}

// Free versus state-scoped, under virtual time against the real reducer and handler: a
// sign-out during authentication cancels the request, while the audit record for the attempt
// still lands after its own latency.
class SessionEffectScopeIntegrationTest {
    @Test
    fun sign_out_during_authentication_cancels_the_request_but_not_the_audit() =
        runTest {
            val audit = RecordingAudit()
            val store =
                testStore(
                    SessionState.SignedOut(),
                    sessionReducer,
                    sessionEffectHandler(SlowAuth, audit, expiry = { ttlMillis ->
                        delay(ttlMillis)
                    }),
                )

            store.send(SessionAction.SignIn("ada@example.com", "secret"))
            advanceTimeBy(100)
            store.send(SessionAction.SignOut)
            advanceUntilIdle()

            store
                .expectAction(SessionAction.SignIn("ada@example.com", "secret"), resulting = SessionState.SigningIn("ada@example.com"))
                .expectAction(SessionAction.SignOut, resulting = SessionState.SignedOut())
            // No Authenticated action: the state-scoped request died with SigningIn.
            store.finish()
            assertEquals(listOf<AuditEvent>(AuditEvent.SignInAttempted("ada@example.com")), audit.events)
        }

    @Test
    fun a_completed_sign_in_expires_on_its_own_timer() =
        runTest {
            val audit = RecordingAudit()
            val store =
                testStore(
                    SessionState.SignedOut(),
                    sessionReducer,
                    sessionEffectHandler(SlowAuth, audit, expiry = { ttlMillis ->
                        delay(ttlMillis)
                    }),
                )

            store.send(SessionAction.SignIn("ada@example.com", "secret"))
            advanceUntilIdle()

            store
                .expectAction(SessionAction.SignIn("ada@example.com", "secret"))
                .expectAction(SessionAction.Authenticated(account, token), resulting = SessionState.SignedIn(account, token))
                .expectAction(SessionAction.TokenExpired, resulting = SessionState.SignedOut(SessionError.Expired))
            store.finish()
            assertEquals(
                listOf<AuditEvent>(
                    AuditEvent.SignInAttempted("ada@example.com"),
                    AuditEvent.SignedIn("ada@example.com"),
                    AuditEvent.SessionExpired,
                ),
                audit.events,
            )
        }

    @Test
    fun signing_out_before_expiry_cancels_the_timer() =
        runTest {
            val audit = RecordingAudit()
            val store =
                testStore(
                    SessionState.SignedOut(),
                    sessionReducer,
                    sessionEffectHandler(SlowAuth, audit, expiry = { ttlMillis ->
                        delay(ttlMillis)
                    }),
                )

            store.send(SessionAction.SignIn("ada@example.com", "secret"))
            advanceTimeBy(2_000)
            store.send(SessionAction.SignOut)
            advanceUntilIdle()

            store
                .expectAction(SessionAction.SignIn("ada@example.com", "secret"))
                .expectAction(SessionAction.Authenticated(account, token))
                .expectAction(SessionAction.SignOut, resulting = SessionState.SignedOut())
            // No TokenExpired: the keyed, state-scoped timer left with SignedIn.
            store.finish()
        }
}
