import Combine
import Foundation

/// Combine surface over the same StateFlow the AsyncSequence serves, built on the hop-free
/// callback bridge: values are delivered synchronously on the store's confined thread (the
/// main thread for `StoreScope.Main` stores), so no `.receive(on:)` is needed there. A free
/// generic function rather than an extension property, because Swift extensions of a generic
/// Objective-C class cannot reference its type parameters. Each call creates its own upstream
/// subscription, cancelled when the downstream cancels — intended for one long-lived consumer.
public func statePublisher<S: AnyObject, A: AnyObject>(of store: Store<S, A>) -> AnyPublisher<S, Never> {
    let subject = CurrentValueSubject<S, Never>(store.state)
    let subscription = store.observe(onEach: { state in subject.send(state) })
    return subject
        .handleEvents(receiveCancel: { subscription.cancel() })
        .removeDuplicates(by: { lhs, rhs in (lhs as? NSObject)?.isEqual(rhs) ?? false })
        .eraseToAnyPublisher()
}

/// The same Combine surface over a `ViewStore`: the projection, deduplicated by the store's own
/// equality filter before it reaches the subject, for screens still built on `ObservableObject`.
public func statePublisher<VS: AnyObject, VA: AnyObject>(of view: ViewStore<VS, VA>) -> AnyPublisher<VS, Never> {
    let subject = CurrentValueSubject<VS, Never>(view.state)
    let subscription = view.observe(onEach: { projection in subject.send(projection) })
    return subject
        .handleEvents(receiveCancel: { subscription.cancel() })
        .eraseToAnyPublisher()
}
