package io.github.milanhorvatovic.reducible.cookbook.session

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Reduced
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.serialization.Serializable

public data class Account(
    public val email: String,
    public val displayName: String,
)

/** [ttlMillis] is how long the token stays valid; the session schedules its own expiry from it. */
public data class SessionToken(
    public val value: String,
    public val ttlMillis: Long,
)

@Serializable
public sealed interface SessionError {
    @Serializable
    public data object InvalidCredentials : SessionError

    @Serializable
    public data object Offline : SessionError

    @Serializable
    public data object Expired : SessionError

    @Serializable
    public data class Unexpected(
        public val message: String,
    ) : SessionError
}

/**
 * The app-scoped session: one store per process, reducing in a background scope. No
 * screen observes it directly — screen stores follow it through a [SessionGateway]
 * observation effect and act on it through the same gateway.
 */
public sealed interface SessionState {
    /** [failure] says why the previous session ended; null on a clean start or sign-out. */
    public data class SignedOut(
        public val failure: SessionError? = null,
    ) : SessionState

    public data class SigningIn(
        public val email: String,
    ) : SessionState

    public data class SignedIn(
        public val account: Account,
        public val token: SessionToken,
    ) : SessionState
}

public sealed interface SessionAction {
    public data class SignIn(
        public val email: String,
        public val password: String,
    ) : SessionAction {
        // Observers stringify actions into debug logs; the password must never reach one.
        override fun toString(): String = "SignIn(email=$email, password=***)"
    }

    public data class Authenticated(
        public val account: Account,
        public val token: SessionToken,
    ) : SessionAction

    public data class AuthenticationFailed(
        public val error: SessionError,
    ) : SessionAction

    public data object TokenExpired : SessionAction

    public data object SignOut : SessionAction
}

public sealed interface SessionEffect {
    /** State-scoped: a sign-out while authenticating cancels the request. */
    public data class Authenticate(
        public val email: String,
        public val password: String,
    ) : SessionEffect {
        override fun toString(): String = "Authenticate(email=$email, password=***)"
    }

    /** State-scoped and keyed: ends with the session, and a fresh sign-in replaces the timer. */
    public data class ScheduleExpiry(
        public val ttlMillis: Long,
    ) : SessionEffect

    /** Free: an audit record is about what happened, so it completes whatever the session does next. */
    public data class Audit(
        public val event: AuditEvent,
    ) : SessionEffect
}

public sealed interface AuditEvent {
    public data class SignInAttempted(
        public val email: String,
    ) : AuditEvent

    public data class SignedIn(
        public val email: String,
    ) : AuditEvent

    public data object SignedOut : AuditEvent

    public data object SessionExpired : AuditEvent
}

internal data object ExpiryKey : EffectKey

public val sessionReducer: Reducer<SessionState, SessionAction, SessionEffect> =
    Reducer { state, action ->
        when (state) {
            is SessionState.SignedOut -> {
                when (action) {
                    is SessionAction.SignIn -> {
                        Reduced(
                            SessionState.SigningIn(action.email),
                            listOf(
                                EffectEnvelope(SessionEffect.Authenticate(action.email, action.password)),
                                EffectEnvelope(
                                    SessionEffect.Audit(AuditEvent.SignInAttempted(action.email)),
                                    EffectScope.Free,
                                ),
                            ),
                        )
                    }

                    is SessionAction.Authenticated -> {
                        state.only()
                    }

                    is SessionAction.AuthenticationFailed -> {
                        state.only()
                    }

                    SessionAction.TokenExpired -> {
                        state.only()
                    }

                    SessionAction.SignOut -> {
                        state.only()
                    }
                }
            }

            is SessionState.SigningIn -> {
                when (action) {
                    is SessionAction.Authenticated -> {
                        Reduced(
                            SessionState.SignedIn(action.account, action.token),
                            listOf(
                                EffectEnvelope(SessionEffect.ScheduleExpiry(action.token.ttlMillis), key = ExpiryKey),
                                EffectEnvelope(
                                    SessionEffect.Audit(AuditEvent.SignedIn(action.account.email)),
                                    EffectScope.Free,
                                ),
                            ),
                        )
                    }

                    is SessionAction.AuthenticationFailed -> {
                        SessionState.SignedOut(action.error).only()
                    }

                    SessionAction.SignOut -> {
                        SessionState.SignedOut().only()
                    }

                    is SessionAction.SignIn -> {
                        state.only()
                    }

                    SessionAction.TokenExpired -> {
                        state.only()
                    }
                }
            }

            is SessionState.SignedIn -> {
                when (action) {
                    SessionAction.SignOut -> {
                        SessionState.SignedOut().withEffect(SessionEffect.Audit(AuditEvent.SignedOut), EffectScope.Free)
                    }

                    SessionAction.TokenExpired -> {
                        SessionState
                            .SignedOut(SessionError.Expired)
                            .withEffect(SessionEffect.Audit(AuditEvent.SessionExpired), EffectScope.Free)
                    }

                    is SessionAction.SignIn -> {
                        state.only()
                    }

                    is SessionAction.Authenticated -> {
                        state.only()
                    }

                    is SessionAction.AuthenticationFailed -> {
                        state.only()
                    }
                }
            }
        }
    }

public data class Authentication(
    public val account: Account,
    public val token: SessionToken,
)

/** The feature's authentication boundary; implementations are wired by the consuming app. */
public interface AuthGateway {
    /** @throws AuthException for expected failures. */
    public suspend fun authenticate(
        email: String,
        password: String,
    ): Authentication
}

public class AuthException(
    public val error: SessionError,
) : Exception("Authentication failed: $error")

public fun interface AuditSink {
    public suspend fun record(event: AuditEvent)
}

/**
 * Handlers-total: expected authentication failures become typed failure actions. [expiry] is
 * the suspension that makes [SessionEffect.ScheduleExpiry] a timer — supplied by the wiring,
 * since features carry no coroutines and cannot delay themselves.
 */
public fun sessionEffectHandler(
    auth: AuthGateway,
    audit: AuditSink,
    expiry: suspend (ttlMillis: Long) -> Unit,
): EffectHandler<SessionEffect, SessionAction> =
    EffectHandler { effect, send ->
        when (effect) {
            is SessionEffect.Authenticate -> {
                try {
                    val authentication = auth.authenticate(effect.email, effect.password)
                    send(SessionAction.Authenticated(authentication.account, authentication.token))
                } catch (failure: AuthException) {
                    send(SessionAction.AuthenticationFailed(failure.error))
                }
            }

            is SessionEffect.ScheduleExpiry -> {
                expiry(effect.ttlMillis)
                send(SessionAction.TokenExpired)
            }

            is SessionEffect.Audit -> {
                audit.record(effect.event)
            }
        }
    }
