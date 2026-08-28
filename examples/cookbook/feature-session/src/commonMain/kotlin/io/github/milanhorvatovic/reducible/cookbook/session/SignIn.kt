package io.github.milanhorvatovic.reducible.cookbook.session

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.Event
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.andSend
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.serialization.Serializable

@Serializable
public data class SignInForm(
    public val email: String = "",
    public val password: String = "",
) {
    // Observers stringify states into debug logs; the password must never reach one.
    override fun toString(): String = "SignInForm(email=$email, password=***)"
}

@Serializable
public sealed interface SignInProblem {
    @Serializable
    public data object EmailInvalid : SignInProblem

    @Serializable
    public data object PasswordMissing : SignInProblem

    @Serializable
    public data class Rejected(
        public val error: SessionError,
    ) : SignInProblem
}

/** The sign-in screen. It never becomes "done": the shell replaces it once the session is signed in. */
@Serializable
public sealed interface SignInState {
    @Serializable
    public data class Editing(
        public val form: SignInForm = SignInForm(),
        public val problem: SignInProblem? = null,
    ) : SignInState

    @Serializable
    public data class Submitting(
        public val form: SignInForm,
    ) : SignInState
}

public sealed interface SignInAction {
    /** What the screen may send; the rest is fed by the session observation or the scope holder. */
    public sealed interface Ui : SignInAction

    public data class EmailChanged(
        public val text: String,
    ) : Ui

    public data class PasswordChanged(
        public val text: String,
    ) : Ui {
        override fun toString(): String = "PasswordChanged(text=***)"
    }

    public data object Submit : Ui

    public data object DebugClicked : Ui

    public data object Started : SignInAction

    public data class SessionChanged(
        public val session: SessionState,
    ) : SignInAction
}

/** What the sign-in screen asks its holder to do; an [Event], so the store publishes it. */
public sealed interface SignInEvent :
    SignInAction,
    Event {
    public data object OpenDebug : SignInEvent
}

public sealed interface SignInEffect {
    public data object ObserveSession : SignInEffect

    public data class Submit(
        public val email: String,
        public val password: String,
    ) : SignInEffect {
        override fun toString(): String = "Submit(email=$email, password=***)"
    }
}

internal data object SignInObserveKey : EffectKey

public val signInReducer: Reducer<SignInState, SignInAction, SignInEffect> =
    Reducer { state, action ->
        when (state) {
            is SignInState.Editing -> {
                when (action) {
                    is SignInAction.EmailChanged -> {
                        SignInState.Editing(state.form.copy(email = action.text)).only()
                    }

                    is SignInAction.PasswordChanged -> {
                        SignInState.Editing(state.form.copy(password = action.text)).only()
                    }

                    SignInAction.Submit -> {
                        val problem = state.form.problem()
                        if (problem != null) {
                            SignInState.Editing(state.form, problem).only()
                        } else {
                            SignInState
                                .Submitting(state.form)
                                .withEffect(SignInEffect.Submit(state.form.email, state.form.password))
                        }
                    }

                    SignInAction.Started -> {
                        state.observingSession()
                    }

                    is SignInAction.SessionChanged -> {
                        when (action.session) {
                            is SessionState.SigningIn -> SignInState.Submitting(state.form).only()
                            is SessionState.SignedOut -> state.only()
                            is SessionState.SignedIn -> state.only()
                        }
                    }

                    SignInAction.DebugClicked -> {
                        state.andSend(SignInEvent.OpenDebug)
                    }

                    SignInEvent.OpenDebug -> {
                        state.only()
                    }
                }
            }

            is SignInState.Submitting -> {
                when (action) {
                    is SignInAction.SessionChanged -> {
                        when (val session = action.session) {
                            is SessionState.SignedOut -> {
                                SignInState.Editing(state.form, session.failure?.let(SignInProblem::Rejected)).only()
                            }

                            is SessionState.SigningIn -> {
                                state.only()
                            }

                            is SessionState.SignedIn -> {
                                state.only()
                            }
                        }
                    }

                    SignInAction.Started -> {
                        state.observingSession()
                    }

                    is SignInAction.EmailChanged -> {
                        state.only()
                    }

                    is SignInAction.PasswordChanged -> {
                        state.only()
                    }

                    SignInAction.Submit -> {
                        state.only()
                    }

                    SignInAction.DebugClicked -> {
                        state.andSend(SignInEvent.OpenDebug)
                    }

                    SignInEvent.OpenDebug -> {
                        state.only()
                    }
                }
            }
        }
    }

// Free, not state-scoped: the observation must survive the Editing/Submitting transitions,
// which a state-scoped effect would read as leaving its owner. The key makes a restart after
// process death replace the running observation instead of stacking a second one.
private fun SignInState.observingSession() = withEffect(SignInEffect.ObserveSession, EffectScope.Free, SignInObserveKey)

private fun SignInForm.problem(): SignInProblem? =
    when {
        '@' !in email -> SignInProblem.EmailInvalid
        password.isEmpty() -> SignInProblem.PasswordMissing
        else -> null
    }

public fun signInEffectHandler(session: SessionGateway): EffectHandler<SignInEffect, SignInAction> =
    EffectHandler { effect, send ->
        when (effect) {
            SignInEffect.ObserveSession -> session.observe { state -> send(SignInAction.SessionChanged(state)) }
            is SignInEffect.Submit -> session.signIn(effect.email, effect.password)
        }
    }

public data class SignInViewState(
    public val email: String,
    public val password: String,
    public val submitting: Boolean,
    public val canSubmit: Boolean,
    public val problem: SignInProblem?,
) {
    override fun toString(): String =
        "SignInViewState(email=$email, password=***, submitting=$submitting, canSubmit=$canSubmit, problem=$problem)"
}

public fun signInViewState(state: SignInState): SignInViewState =
    when (state) {
        is SignInState.Editing -> {
            SignInViewState(
                email = state.form.email,
                password = state.form.password,
                submitting = false,
                canSubmit = state.form.email.isNotBlank() && state.form.password.isNotEmpty(),
                problem = state.problem,
            )
        }

        is SignInState.Submitting -> {
            SignInViewState(
                email = state.form.email,
                password = state.form.password,
                submitting = true,
                canSubmit = false,
                problem = null,
            )
        }
    }
