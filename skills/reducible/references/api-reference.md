# API reference

The public surface per module, condensed to what a call site needs. Signatures omit `public` and Kotlin's `out`/`in` variance. Read a module's KDoc for contracts beyond a line.

## Contents

- [reducible-core](#reducible-core)
- [reducible-runtime](#reducible-runtime)
- [reducible-test](#reducible-test)
- [reducible-immutable](#reducible-immutable)
- [reducible-optics-arrow](#reducible-optics-arrow)
- [reducible-android](#reducible-android)
- [Swift bridge](#swift-bridge)

## reducible-core

Package `io.github.milanhorvatovic.reducible`. Depends on the Kotlin standard library only.

```kotlin
fun interface Reducer<S, A, E> { fun reduce(state: S, action: A): Reduced<S, A, E> }
data class Reduced<S, A, E>(val state: S, val effects: List<EffectEnvelope<E>> = emptyList(), val followUps: List<A> = emptyList())

fun <S, E> S.only(): Reduced<S, Nothing, E>
fun <S, E> S.withEffect(effect: E, scope: EffectScope = EffectScope.StateScoped, key: EffectKey? = null): Reduced<S, Nothing, E>
fun <S, E> S.withEffects(vararg effects: E): Reduced<S, Nothing, E>          // all state-scoped
fun <S, A> S.andSend(vararg actions: A): Reduced<S, A, Nothing>
fun <S, A, E> Reduced<S, A, E>.andSend(vararg actions: A): Reduced<S, A, E>

enum class EffectScope { StateScoped, Free }
enum class KeyPolicy { CancelPrevious, Ordered }
interface EffectKey { val policy: KeyPolicy get() = KeyPolicy.CancelPrevious }
data class EffectEnvelope<E>(val effect: E, val scope: EffectScope = EffectScope.StateScoped, val key: EffectKey? = null, val owner: EffectOwner<*>? = null)
fun interface EffectOwner<S> { fun resolvesIn(state: S): Boolean }          // created by the operators, never by features
fun interface EffectHandler<E, A> { suspend fun handle(effect: E, send: (A) -> Unit) }
interface Event                                                              // marker: published on Store.events, reduced as a no-op

interface Optional<S, T> { fun getOrNull(source: S): T?; fun set(source: S, value: T): S }
interface Lens<S, T> : Optional<S, T> { fun get(source: S): T }
interface Prism<S, T> : Optional<S, T> { fun embed(value: T): S }
fun <S, T> lens(get: (S) -> T, set: (S, T) -> S): Lens<S, T>
fun <S, T> optional(getOrNull: (S) -> T?, set: (S, T) -> S): Optional<S, T>
fun <S, T> prism(getOrNull: (S) -> T?, embed: (T) -> S): Prism<S, T>
inline fun <S, reified T : S> casePrism(): Prism<S, T>
infix fun <S, M, T> Optional<S, M>.andThen(next: Optional<M, T>): Optional<S, T>

fun <S, A, E> combine(vararg reducers: Reducer<S, A, E>): Reducer<S, A, E>
fun <PS, PA, PE, CS, CA, CE> Reducer<CS, CA, CE>.ifPresent(state: Optional<PS, CS>, action: Prism<PA, CA>, effect: (CE) -> PE, slot: Any = state): Reducer<PS, PA, PE>
fun <PS, PA, PE, CS, CA, CE, I> Reducer<CS, CA, CE>.forEachIdentified(list: Optional<PS, List<CS>>, identity: (CS) -> I, action: Prism<PA, IdentifiedAction<I, CA>>, effect: (I, CE) -> PE): Reducer<PS, PA, PE>
fun <PS, PA, PE, CS, CA, CE, I> rowReduced(state: PS, reduced: Reduced<CS, CA, CE>, id: I, rows: (PS) -> List<CS>?, identity: (CS) -> I, action: Prism<PA, IdentifiedAction<I, CA>>, effect: (I, CE) -> PE): Reduced<PS, PA, PE>
suspend fun <E, CA, PA> EffectHandler<E, CA>.handle(effect: E, send: (PA) -> Unit, action: (CA) -> PA)
data class IdentifiedAction<I, A>(val id: I, val action: A)
data class IdentifiedEffectKey(val id: Any?, val key: EffectKey) : EffectKey    // prints as a path: notes/editor/HintDebounce

fun <S, A, E> Reducer<S, A, E>.replay(initialState: S, actions: Iterable<A>): S
```

## reducible-runtime

Package `io.github.milanhorvatovic.reducible.runtime`. Depends on core and kotlinx-coroutines.

```kotlin
fun <S : Any, A, E> Store(
    initialState: S,
    reducer: Reducer<S, A, E>,
    handler: EffectHandler<E, A>,
    defects: DefectHandler<S, A, E> = rethrowingDefectHandler(),
    scope: StoreScope = StoreScope.Main,
    observer: StoreObserver<S, A>? = null,
    start: A? = null,
): Store<S, A>

class Store<S : Any, A> {
    val stateFlow: StateFlow<S>            // `stateSequence` in Swift
    val state: S
    val events: Flow<A>                    // one-shot; only actions marked Event
    val activeEffects: Int
    fun send(action: A)                    // safe from any thread; closed store ignores it
    fun observe(onEach: (S) -> Unit): Subscription   // synchronous on the confined thread
    fun close()
}
fun <S : Any, A, VE : Any> Store<S, A>.events(select: (A) -> VE?): Flow<VE>
fun interface Subscription { fun cancel() }

sealed interface StoreScope {
    data object Main; data object Background
    data class Dedicated(val dispatcher: CoroutineDispatcher)
    data class Inherited(val scope: CoroutineScope)
    data class Testing(val dispatcher: CoroutineDispatcher)
}

class ViewStore<VS : Any, VA> { val stateFlow: StateFlow<VS>; val state: VS; fun send(action: VA); fun observe(onEach: (VS) -> Unit): Subscription }
fun <S : Any, A, VS : Any, VA> Store<S, A>.view(state: (S) -> VS, action: (VA) -> A): ViewStore<VS, VA>
fun <VS : Any, VA, CS : Any, CA> ViewStore<VS, VA>.view(state: (VS) -> CS, action: (CA) -> VA): ViewStore<CS, CA>

fun interface StoreObserver<S, A> {
    fun onReduced(previous: S, action: A, next: S)
    fun onEffect(event: EffectEvent) {}
    fun onWarning(warning: StoreWarning<A>) {}
}
operator fun <S, A> StoreObserver<S, A>.plus(other: StoreObserver<S, A>): StoreObserver<S, A>
sealed interface EffectEvent { Launched(effect, scope, key, queued); Ended(effect, key, end: EffectEnd); Skipped(effect, key) }
enum class EffectEnd { Completed, Defect, CancelledByOwner, CancelledByKey }
sealed interface StoreWarning<A> { SentAfterClose(action); SentFromFinishedEffect(action, effect); EventDropped(event) }

sealed interface Defect<S, A, E> { InEffect(effect, error); InReducer(state, action, error); FollowUpCycle(state, action) }
fun interface DefectHandler<S, A, E> { fun onDefect(defect: Defect<S, A, E>) }
fun rethrowingDefectHandler(): DefectHandler<Any?, Any?, Any?>            // debug: crash at the defect
fun loggingDefectHandler(log: (DefectException) -> Unit): DefectHandler<Any?, Any?, Any?>   // release: keep running
class DefectException(val defect: Defect<*, *, *>) : RuntimeException

class ActionRecorder<S, A>(capacity: Int = Int.MAX_VALUE) : StoreObserver<S, A> { val recording: Recording<S, A>?; fun clear() }
data class Recording<S, A>(val initialState: S, val actions: List<A>)
fun <S : Any, A, E> RecordingStore(initialState, reducer, handler, defects = rethrowingDefectHandler(), scope = StoreScope.Main, start: A? = null): RecordingStore<S, A>
class RecordingStore<S : Any, A> { val initialState: S; val recording: Recording<S, A>; fun clearRecording(); /* plus the Store surface */ }

suspend fun <T, A> Flow<T>.feedInto(send: (A) -> Unit, action: (T) -> A)   // collect into a store; a StateFlow source never completes

// iosMain
fun dedicated(queue: dispatch_queue_t): StoreScope.Dedicated                 // Swift: StoreScopeDarwinKt.dedicated(queue:)
fun dispatchQueueDispatcher(queue: dispatch_queue_t): CoroutineDispatcher
```

## reducible-test

Package `io.github.milanhorvatovic.reducible.test`. Depends on core, runtime, kotlinx-coroutines-test.

```kotlin
fun <S, A, E> Reducer<S, A, E>.given(state: S): ReducerScenario<S, A, E>
class ReducerScenario<S, A, E> { fun on(action: A): ReducerVerdict<S, A, E> }
class ReducerVerdict<S, A, E> {
    fun expect(state: S); fun expectState(assertion: (S) -> Boolean)
    fun expectEffects(vararg effects: E)                 // values in order, scopes ignored
    fun expectEnvelopes(vararg envelopes: EffectEnvelope<E>)
    fun expectNoEffects(); fun expectFollowUps(vararg actions: A)
    fun andOn(action: A): ReducerVerdict<S, A, E>        // continue from the produced state
}

fun <S, T> Optional<S, T>.assertOptionalLaws(present: S, replacement: T, vararg absent: S)
fun <S, T> Lens<S, T>.assertLensLaws(source: S, replacement: T)
fun <S, T> Prism<S, T>.assertPrismLaws(matching: S, value: T, vararg nonMatching: S)

fun <S : Any, A, E> TestScope.testStore(initialState: S, reducer: Reducer<S, A, E>, handler: EffectHandler<E, A>, defects: DefectHandler<S, A, E> = rethrowingDefectHandler()): TestStore<S, A>
class TestStore<S : Any, A> {
    val state: S; val stateFlow: StateFlow<S>
    fun send(action: A); fun observe(onEach: (S) -> Unit): Subscription; fun close()
    fun expectAction(expected: A): TestStore<S, A>
    fun expectAction(expected: A, resulting: S): TestStore<S, A>
    fun expectState(expected: S): TestStore<S, A>
    fun expectNoMoreActions(): TestStore<S, A>
    fun finish(cancelInFlightEffects: Boolean = false)  // exhaustive: unasserted actions or in-flight effects fail
}
```

## reducible-immutable

Package `io.github.milanhorvatovic.reducible.immutable`. Depends on core, kotlinx-collections-immutable, kotlinx-serialization.

```kotlin
fun <PS, PA, PE, CS, CA, CE, I> Reducer<CS, CA, CE>.forEachIdentified(list: Optional<PS, PersistentList<CS>>, identity: (CS) -> I, action: Prism<PA, IdentifiedAction<I, CA>>, effect: (I, CE) -> PE): Reducer<PS, PA, PE>
class PersistentListSerializer<T>(elementSerializer: KSerializer<T>) : KSerializer<PersistentList<T>>   // @Serializable(with = PersistentListSerializer::class)
```

## reducible-optics-arrow

Package `io.github.milanhorvatovic.reducible.optics.arrow`. Depends on core and arrow-optics.

```kotlin
fun <S, T> arrow.optics.Lens<S, T>.asCoreLens(): Lens<S, T>
fun <S, T> arrow.optics.Prism<S, T>.asCorePrism(): Prism<S, T>
fun <S, T> arrow.optics.Optional<S, T>.asCoreOptional(): Optional<S, T>
```

## reducible-android

Package `io.github.milanhorvatovic.reducible.android`. Depends on runtime, lifecycle-viewmodel-savedstate, kotlinx-serialization-json.

```kotlin
abstract class StoreViewModel<S : Any, A>(
    savedStateHandle: SavedStateHandle,
    serializer: KSerializer<S>,
    createStore: (restored: S?, scope: StoreScope) -> Store<S, A>,   // scope is StoreScope.Inherited(viewModelScope)
    persisted: (S) -> S = { state -> state },                        // what is saved; drop what Started refetches
    restoreFailed: (Throwable) -> Unit = {},                          // undecodable saved state starts fresh
) : ViewModel() {
    val store: Store<S, A>
    val state: StateFlow<S>
    fun send(action: A)
}
```

## Swift bridge

Sources under `swift/`, bundled into the app's umbrella framework through SKIE. All types are `@MainActor`, `@Observable`, iOS 17 and macOS 14 or newer.

```swift
final class StoreModel<S: AnyObject, A: AnyObject>: ObservableObject {
    init(store: Store<S, A>)                        // takes ownership; closes the store in deinit
    var state: S { get }                             // tracked pass-through of the store's snapshot
    let store: Store<S, A>
    func view<VS, VA>(_ view: ViewStore<VS, VA>) -> ViewStoreModel<VS, VA>
    func project<VS, VA, UI>(_ view: ViewStore<VS, VA>, _ convert: @escaping (VS) -> UI) -> ProjectedModel<VS, VA, UI>
    func activate()                                  // call from .task
    func send(_ action: A)
    func binding<V>(get: @escaping (S) -> V, send embed: @escaping (V) -> A) -> Binding<V>
}

final class ViewStoreModel<VS: AnyObject, VA: AnyObject>: ObservableObject {   // from StoreModel.view(_:); retains its owner
    var state: VS { get }
    func activate(); func send(_ action: VA)
    func binding<V>(get: @escaping (VS) -> V, send embed: @escaping (V) -> VA) -> Binding<V>
}

final class ProjectedModel<VS: AnyObject, VA: AnyObject, UI>: ObservableObject {   // from StoreModel.project(_:_:); retains its owner
    private(set) var ui: UI                          // the Swift value converted once per reduction
    func activate(); func send(_ action: VA)
    func binding<V>(get: @escaping (UI) -> V, send embed: @escaping (V) -> VA) -> Binding<V>
}

func statePublisher<S, A>(of store: Store<S, A>) -> AnyPublisher<S, Never>        // Combine; synchronous on the confined thread
func statePublisher<VS, VA>(of view: ViewStore<VS, VA>) -> AnyPublisher<VS, Never>
```

SKIE additions seen from Swift: `store.stateSequence` (an `AsyncSequence` over `stateFlow`), `SkieSwiftFlow<E>` for `Flow<E>` (iterate with `for await`), `onEnum(of:)` for sealed hierarchies, and top-level Kotlin functions under `<FileName>Kt`.
