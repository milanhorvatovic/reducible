import Shared
import SwiftUI

enum SignedOutRoute: Hashable {
    case debug
}

/// The screen never navigates: it sends the intent to its store, and this host acts on the event.
struct SignInFlow: View {
    let stores: CookbookStores
    @StateObject private var models: SignInModels
    @State private var path: [SignedOutRoute] = []

    init(stores: CookbookStores) {
        self.stores = stores
        _models = StateObject(wrappedValue: makeSignInModels(stores))
    }

    var body: some View {
        NavigationStack(path: self.$path, root: {
            SignInForm(model: self.models.model)
                .navigationTitle("Cookbook")
                .toolbar(content: {
                    if self.stores.diagnostics {
                        Button("Debug", action: {
                            self.models.model.send(SignInActionDebugClicked())
                        })
                    }
                })
                .navigationDestination(for: SignedOutRoute.self, destination: { route in
                    switch route {
                    case .debug:
                        DebugView(stores: self.stores)
                    }
                })
        })
        .task {
            self.models.model.activate()
        }
        .task {
            for await event in self.models.events {
                switch onEnum(of: event) {
                case .openDebug:
                    self.path.append(.debug)
                }
            }
        }
    }
}

private struct SignInForm: View {
    let model: ViewStoreModel<SignInViewState, SignInActionUi>

    var body: some View {
        let state = self.model.state
        Form(content: {
            Section("Sign in to sync your recipes", content: {
                TextField(
                    "Email",
                    text: self.model.binding(
                        get: { state in state.email },
                        send: { text in SignInActionEmailChanged(text: text) }
                    )
                )
                .textInputAutocapitalization(.never)
                .keyboardType(.emailAddress)
                .disabled(state.submitting)
                SecureField(
                    "Password",
                    text: self.model.binding(
                        get: { state in state.password },
                        send: { text in SignInActionPasswordChanged(text: text) }
                    )
                )
                .disabled(state.submitting)
                if let problem = state.problem {
                    Text(label(for: problem)).font(.footnote).foregroundStyle(.red)
                }
                Button(
                    action: {
                        self.model.send(SignInActionSubmit())
                    },
                    label: {
                        if state.submitting {
                            ProgressView()
                        } else {
                            Text("Sign in")
                        }
                    }
                )
                .disabled(!state.canSubmit)
            })
            Section(content: {
                Text("Any email signs in with the password “secret”; “offline” simulates no network.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            })
        })
    }
}

func label(for problem: SignInProblem) -> String {
    switch onEnum(of: problem) {
    case .emailInvalid:
        return "Enter a valid email address."
    case .passwordMissing:
        return "Enter your password."
    case .rejected(let rejected):
        return label(for: rejected.error)
    }
}

func label(for error: SessionError) -> String {
    switch onEnum(of: error) {
    case .invalidCredentials:
        return "Email or password is wrong."
    case .offline:
        return "You appear to be offline."
    case .expired:
        return "Your session expired; sign in again."
    case .unexpected(let unexpected):
        return "Something went wrong: \(unexpected.message)"
    }
}
