package io.github.milanhorvatovic.reducible.cookbook.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.milanhorvatovic.reducible.android.StoreViewModel
import io.github.milanhorvatovic.reducible.cookbook.session.SessionError
import io.github.milanhorvatovic.reducible.cookbook.session.SignInAction
import io.github.milanhorvatovic.reducible.cookbook.session.SignInProblem
import io.github.milanhorvatovic.reducible.cookbook.session.SignInState
import io.github.milanhorvatovic.reducible.cookbook.session.SignInViewState
import io.github.milanhorvatovic.reducible.cookbook.shared.CookbookStores
import io.github.milanhorvatovic.reducible.runtime.ViewStore

/** The scope holder with process-death restore: a half-typed form survives the app being killed. */
class SignInViewModel(
    savedStateHandle: SavedStateHandle,
    stores: CookbookStores,
) : StoreViewModel<SignInState, SignInAction>(
        savedStateHandle = savedStateHandle,
        serializer = SignInState.serializer(),
        createStore = { restored, scope -> stores.signInStore(restored, scope) },
    ) {
    val view: ViewStore<SignInViewState, SignInAction.Ui> = stores.signInView(store)
}

/** [debugAvailable] hides the entry with diagnostics off, when the debug screen would be empty. */
@Composable
fun SignInRoute(
    viewModel: SignInViewModel,
    debugAvailable: Boolean,
) {
    val state by viewModel.view.stateFlow.collectAsStateWithLifecycle()
    SignInScreen(state = state, send = viewModel.view::send, debugAvailable = debugAvailable)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInScreen(
    state: SignInViewState,
    send: (SignInAction.Ui) -> Unit,
    debugAvailable: Boolean,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cookbook") },
                actions = {
                    if (debugAvailable) {
                        TextButton(onClick = { send(SignInAction.DebugClicked) }, content = { Text("Debug") })
                    }
                },
            )
        },
        content = { padding ->
            Column(
                modifier = Modifier.padding(padding).padding(horizontal = 24.dp, vertical = 16.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = {
                    Text("Sign in to sync your recipes", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.email,
                        onValueChange = { text -> send(SignInAction.EmailChanged(text)) },
                        label = { Text("Email") },
                        singleLine = true,
                        enabled = !state.submitting,
                        isError = state.problem is SignInProblem.EmailInvalid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.password,
                        onValueChange = { text -> send(SignInAction.PasswordChanged(text)) },
                        label = { Text("Password") },
                        singleLine = true,
                        enabled = !state.submitting,
                        isError = state.problem is SignInProblem.PasswordMissing,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    state.problem?.let { problem ->
                        Text(problem.label(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = { send(SignInAction.Submit) },
                        enabled = state.canSubmit,
                        modifier = Modifier.fillMaxWidth(),
                        content = {
                            if (state.submitting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Sign in")
                            }
                        },
                    )
                    Text(
                        "Any email signs in with the password \"secret\"; \"offline\" simulates no network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        },
    )
}

private fun SignInProblem.label(): String =
    when (this) {
        SignInProblem.EmailInvalid -> "Enter a valid email address."
        SignInProblem.PasswordMissing -> "Enter your password."
        is SignInProblem.Rejected -> error.label()
    }

fun SessionError.label(): String =
    when (this) {
        SessionError.InvalidCredentials -> "Email or password is wrong."
        SessionError.Offline -> "You appear to be offline."
        SessionError.Expired -> "Your session expired; sign in again."
        is SessionError.Unexpected -> "Something went wrong: $message"
    }
