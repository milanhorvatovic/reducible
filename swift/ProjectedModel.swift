import Foundation
import Observation
import SwiftUI

/// The model for screens that render from Swift values. Each new projection is converted once,
/// inside the observe callback, into a Swift value type the body reads from `ui`. Rendering,
/// list diffing, and scrolling then never cross the Kotlin boundary — no wrapper per row, no
/// `NSString` per read — which is what makes this the tier for list screens and anything that
/// ticks. `ViewStoreModel` stays the lighter choice for screens that read a few scalars.
///
/// `convert` runs on the main thread, synchronously during the reduction, once per change;
/// the store's equality filter has already dropped reductions whose projection is unchanged.
/// Obtained from `StoreModel.project(_:_:)`, which is also what keeps the store alive: the
/// model retains its owner. `ObservableObject` is conformed to for `@StateObject`'s
/// once-per-identity creation only; `objectWillChange` never fires.
@available(iOS 17.0, macOS 14.0, *)
@Observable
@MainActor
public final class ProjectedModel<VS: AnyObject, VA: AnyObject, UI>: ObservableObject {
    /// The Swift copy of the current projection — stored, unlike `ViewStoreModel.state`,
    /// because producing it once per change is the whole point.
    public private(set) var ui: UI

    @ObservationIgnored
    private let view: ViewStore<VS, VA>

    @ObservationIgnored
    private let owner: AnyObject

    @ObservationIgnored
    private let convert: (VS) -> UI

    @ObservationIgnored
    private var subscription: Subscription?

    init(view: ViewStore<VS, VA>, owner: AnyObject, convert: @escaping (VS) -> UI) {
        self.view = view
        self.owner = owner
        self.convert = convert
        self.ui = convert(view.state)
    }

    deinit {
        subscription?.cancel()
    }

    public func activate() {
        guard self.subscription == nil else { return }
        self.subscription = self.view.observe(onEach: { [weak self] state in
            guard let self = self else { return }
            self.ui = self.convert(state)
        })
    }

    public func send(_ action: VA) {
        self.view.send(action: action)
    }

    /// A SwiftUI binding that reads from the Swift copy and writes by sending an action.
    public func binding<V>(get: @escaping (UI) -> V, send embed: @escaping (V) -> VA) -> Binding<V> {
        Binding(
            get: { get(self.ui) },
            set: { value in self.send(embed(value)) }
        )
    }
}
