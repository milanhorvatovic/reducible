import Shared
import SwiftUI

@main
struct CookbookApp: App {
    /// One composition root for the process. Settings reduce on a named serial queue, which
    /// `dedicated(queue:)` turns into the store's `Dedicated` scope.
    @State private var stores = CookbookStores(
        settingsScope: StoreScopeDarwinKt.dedicated(queue: DispatchQueue(label: "cookbook.stores")),
        diagnostics: isDebugBuild
    )

    var body: some Scene {
        WindowGroup(content: {
            RootView(stores: self.stores)
        })
    }
}

#if DEBUG
    private let isDebugBuild = true
#else
    private let isDebugBuild = false
#endif
