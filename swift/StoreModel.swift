import Foundation
import Observation
import SwiftUI

/// Owns a store's lifetime for a SwiftUI screen: create it with view identity (`@State`) and
/// it closes the store in `deinit` — mandatory, because a store with running effects is
/// retained by its own scope and ARC alone never frees it. The model takes ownership of the
/// store passed to `init`. Screens that render a projection rather than the raw state hold a
/// `ViewStoreModel` from `view(_:)` instead; it retains this owner for them.
///
/// `state` stores nothing: it is a tracked pass-through of the store's own snapshot, and the
/// hop-free callback bridge (`observe`) serves purely as the invalidation signal. Values
/// arrive synchronously on the store's confined thread during the reduction, so SwiftUI
/// invalidates in the same runloop turn a tap reduced, and every read sees the store's
/// current truth — no copy, no staleness window. That also makes this a `StoreScope.Main`
/// consumer by construction: UI must not observe Background or Dedicated stores directly.
///
/// The subscription captures `self` weakly so it never keeps the model (and therefore the
/// store) alive, and `deinit` cancels it before closing. Call `activate()` from `.task` —
/// store lifetime (view identity) and observation lifetime stay separate. Hold it in
/// `@StateObject`, never in a `@State` assigned from `init`: the `ObservableObject` conformance
/// exists so the autoclosure creates the model once per view identity, and `objectWillChange`
/// never fires.
@available(iOS 17.0, macOS 14.0, *)
@Observable
@MainActor
public final class StoreModel<S: AnyObject, A: AnyObject>: ObservableObject {
    /// Always the store's current value. A computed property is not auto-instrumented by
    /// `@Observable`, so reads and invalidations register with the registrar manually.
    public var state: S {
        access(keyPath: \.state)
        return self.store.state
    }

    /// Exposed so the typed `ViewStore` factories exported from Kotlin can be applied to it.
    @ObservationIgnored
    public let store: Store<S, A>

    @ObservationIgnored
    private var subscription: Subscription?

    public init(store: Store<S, A>) {
        self.store = store
    }

    deinit {
        subscription?.cancel()
        store.close()
    }

    /// Wraps a view over this model's store; the result keeps this owner alive.
    public func view<VS: AnyObject, VA: AnyObject>(_ view: ViewStore<VS, VA>) -> ViewStoreModel<VS, VA> {
        ViewStoreModel(view: view, owner: self)
    }

    /// Wraps a view over this model's store, converting each projection once into a Swift
    /// value the screen renders from; the result keeps this owner alive.
    public func project<VS: AnyObject, VA: AnyObject, UI>(
        _ view: ViewStore<VS, VA>,
        _ convert: @escaping (VS) -> UI
    ) -> ProjectedModel<VS, VA, UI> {
        ProjectedModel(view: view, owner: self, convert: convert)
    }

    public func activate() {
        guard self.subscription == nil else { return }
        self.subscription = self.store.observe(onEach: { [weak self] state in
            self?.withMutation(keyPath: \.state) {}
        })
    }

    public func send(_ action: A) {
        self.store.send(action: action)
    }

    /// A SwiftUI binding that reads from the state and writes by sending an action, so text
    /// fields drive the reducer instead of mutating view-local copies.
    public func binding<V>(get: @escaping (S) -> V, send embed: @escaping (V) -> A) -> Binding<V> {
        Binding(
            get: { get(self.state) },
            set: { value in self.send(embed(value)) }
        )
    }
}
