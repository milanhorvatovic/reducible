package io.github.milanhorvatovic.reducible.cookbook.session

/**
 * How screen features reach the app-scoped session store without depending on the runtime:
 * the wiring implements it over the store, features see suspending calls only. [observe]
 * never returns — it delivers the current session, then every change, until cancelled.
 */
public interface SessionGateway {
    public suspend fun signIn(
        email: String,
        password: String,
    )

    public suspend fun signOut()

    public suspend fun observe(onEach: (SessionState) -> Unit)
}
