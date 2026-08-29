import Shared
import SwiftUI

/// Which flow is on screen follows the shell's view of the session; nothing here reads the
/// background session store directly.
struct RootView: View {
    let stores: CookbookStores
    @StateObject private var shell: ViewStoreModel<ShellState, ShellActionUi>

    init(stores: CookbookStores) {
        self.stores = stores
        _shell = StateObject(wrappedValue: makeShellModel(stores))
    }

    var body: some View {
        Group(content: {
            switch onEnum(of: self.shell.state) {
            case .starting:
                ProgressView()

            case .signedOut:
                SignInFlow(stores: self.stores)

            case .signedIn(let signedIn):
                RecipesFlow(stores: self.stores, account: signedIn.account, shell: self.shell)
            }
        })
        .task {
            self.shell.activate()
        }
    }
}
