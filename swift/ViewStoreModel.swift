import Foundation
import Observation
import SwiftUI

/// The SwiftUI face of a `ViewStore`: the projected state as a tracked pass-through, and
/// `send` narrowed to what the screen may send. Obtained from `StoreModel.view(_:)`, which
/// is also what keeps the store alive: the model retains its owner, so a screen holds only
/// this object in `@State` and the store closes when the last view model over it is gone.
///
/// Invalidation rides the same hop-free callback bridge as `StoreModel`, with one
/// difference that is the point of projecting at all: the bridge already drops reductions
/// whose projection is unchanged, so a state change the screen does not render never
/// invalidates it. Call `activate()` from `.task`.
///
/// `ObservableObject` is conformed to for one reason: `@StateObject`, whose autoclosure creates
/// the model once per view identity even from a view's `init`. A plain `@State` set in `init`
/// would build — and start — a fresh store on every re-initialisation of the view struct.
/// `objectWillChange` never fires; invalidation comes from `@Observable` alone.
@available(iOS 17.0, macOS 14.0, *)
@Observable
@MainActor
public final class ViewStoreModel<VS: AnyObject, VA: AnyObject>: ObservableObject {
    /// Always the current projection. A computed property is not auto-instrumented by
    /// `@Observable`, so reads and invalidations register with the registrar manually.
    public var state: VS {
        access(keyPath: \.state)
        return self.view.state
    }

    @ObservationIgnored
    private let view: ViewStore<VS, VA>

    @ObservationIgnored
    private let owner: AnyObject

    @ObservationIgnored
    private var subscription: Subscription?

    init(view: ViewStore<VS, VA>, owner: AnyObject) {
        self.view = view
        self.owner = owner
    }

    deinit {
        subscription?.cancel()
    }

    public func activate() {
        guard self.subscription == nil else { return }
        self.subscription = self.view.observe(onEach: { [weak self] projection in
            self?.withMutation(keyPath: \.state) {}
        })
    }

    public func send(_ action: VA) {
        self.view.send(action: action)
    }

    /// A SwiftUI binding that reads from the projection and writes by sending an action, so
    /// text fields drive the reducer instead of mutating view-local copies.
    public func binding<V>(get: @escaping (VS) -> V, send embed: @escaping (V) -> VA) -> Binding<V> {
        Binding(
            get: { get(self.state) },
            set: { value in self.send(embed(value)) }
        )
    }
}
