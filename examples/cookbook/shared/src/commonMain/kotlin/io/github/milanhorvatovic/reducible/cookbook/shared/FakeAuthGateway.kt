package io.github.milanhorvatovic.reducible.cookbook.shared

import io.github.milanhorvatovic.reducible.cookbook.session.Account
import io.github.milanhorvatovic.reducible.cookbook.session.AuthException
import io.github.milanhorvatovic.reducible.cookbook.session.AuthGateway
import io.github.milanhorvatovic.reducible.cookbook.session.Authentication
import io.github.milanhorvatovic.reducible.cookbook.session.SessionError
import io.github.milanhorvatovic.reducible.cookbook.session.SessionToken
import kotlinx.coroutines.delay

/**
 * Demo-only authentication: any email signs in with the password `secret`; `offline`
 * simulates no network; anything else is rejected. Tokens expire after [tokenTtlMillis] so
 * the session's own expiry timer is visible within a demo session.
 */
public class FakeAuthGateway(
    private val latency: suspend () -> Unit = { delay(700) },
    private val tokenTtlMillis: Long = 90_000,
) : AuthGateway {
    private var issued = 0

    override suspend fun authenticate(
        email: String,
        password: String,
    ): Authentication {
        latency()
        return when (password) {
            "secret" -> {
                val displayName = email.substringBefore('@').replaceFirstChar { letter -> letter.uppercase() }
                Authentication(Account(email, displayName), SessionToken("token-${++issued}", tokenTtlMillis))
            }

            "offline" -> {
                throw AuthException(SessionError.Offline)
            }

            else -> {
                throw AuthException(SessionError.InvalidCredentials)
            }
        }
    }
}
