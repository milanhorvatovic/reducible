package io.github.milanhorvatovic.reducible.runtime

import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalObjCName
import kotlin.native.ObjCName

/**
 * A store as one screen sees it: state projected to what the screen renders, actions
 * narrowed to what the screen may send. Built with [Store.view], nested with
 * [ViewStore.view]. Owns nothing — the root store's owner still closes the store — so any
 * number of views may sit over one store, a screen plus its sheets and rows.
 *
 * The projection is pure and applied at most once per upstream state: a [state] read returns
 * the same projected object until the store reduces again, so a screen reading it several
 * times per render allocates nothing. Equal consecutive projections are dropped before they
 * reach [stateFlow] collectors and [observe] callbacks: a reduction that leaves the rendered
 * data unchanged never invalidates the screen.
 */
public class ViewStore<VS : Any, VA> internal constructor(
    projected: StateFlow<VS>,
    private val observeSource: ((VS) -> Unit) -> Subscription,
    private val sendAction: (VA) -> Unit,
) {
    /** The reactive surface — `stateFlow` for Kotlin consumers, `stateSequence` in Swift. */
    @OptIn(ExperimentalObjCName::class)
    @ObjCName(swiftName = "stateSequence")
    public val stateFlow: StateFlow<VS> = projected

    /** The synchronous snapshot: the projection of the store's current state. */
    public val state: VS
        get() = stateFlow.value

    public fun send(action: VA) {
        sendAction(action)
    }

    /**
     * [Store.observe] through the projection: synchronous on the store's confined thread,
     * current value first, equal consecutive values dropped.
     */
    public fun observe(onEach: (VS) -> Unit): Subscription = observeSource(onEach)
}

/**
 * Projects this store for one screen. [state] maps feature state to what the screen renders;
 * [action] embeds what the screen sends into the feature's action type — the identity when
 * the screen's actions are a sealed sub-interface of the feature's, or a `Prism.embed` when
 * they are a child's, mirroring the extraction `ifPresent` performs on the reducer side.
 */
public fun <S : Any, A, VS : Any, VA> Store<S, A>.view(
    state: (S) -> VS,
    action: (VA) -> A,
): ViewStore<VS, VA> {
    val projected = ProjectedStateFlow(stateFlow, state)
    return ViewStore(
        projected = projected,
        observeSource = { onEach -> observe(distinctProjection(projected::projectionOf, onEach)) },
        sendAction = { viewAction -> send(action(viewAction)) },
    )
}

/** Narrows a view further, for a child component rendered inside the screen. */
public fun <VS : Any, VA, CS : Any, CA> ViewStore<VS, VA>.view(
    state: (VS) -> CS,
    action: (CA) -> VA,
): ViewStore<CS, CA> {
    val projected = ProjectedStateFlow(stateFlow, state)
    return ViewStore(
        projected = projected,
        observeSource = { onEach -> observe(distinctProjection(projected::projectionOf, onEach)) },
        sendAction = { viewAction -> send(action(viewAction)) },
    )
}

private fun <S, VS : Any> distinctProjection(
    project: (S) -> VS,
    onEach: (VS) -> Unit,
): (S) -> Unit {
    var last: VS? = null
    return { source ->
        val projected = project(source)
        if (projected != last) {
            last = projected
            onEach(projected)
        }
    }
}

/**
 * The upstream flow seen through a projection. Runs no coroutine of its own: [value] is
 * derived from the upstream's, and each collector filters its own duplicates, so an
 * uncollected view costs nothing — unlike `stateIn`, which would need a scope and keep a
 * collector alive for the store's whole lifetime. The one piece of state is a cache of the
 * last projection keyed by upstream identity, so repeated reads between reductions (a SwiftUI
 * body reads `state` several times) return one object instead of allocating one per read.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class, ExperimentalAtomicApi::class)
private class ProjectedStateFlow<S, VS : Any>(
    private val upstream: StateFlow<S>,
    private val project: (S) -> VS,
) : StateFlow<VS> {
    private class Cached<S, VS>(
        val source: S,
        val projected: VS,
    )

    private val cache = AtomicReference<Cached<S, VS>?>(null)

    override val value: VS
        get() = projectionOf(upstream.value)

    override val replayCache: List<VS>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<VS>): Nothing {
        var last: VS? = null
        upstream.collect { source ->
            val projected = projectionOf(source)
            if (projected != last) {
                last = projected
                collector.emit(projected)
            }
        }
    }

    // Identity, not equality: the store publishes a new state object per reduction, and two
    // threads racing here at worst compute the same projection twice. Shared with the observe
    // path, so a reduction yields one projected object however many ways it is read.
    fun projectionOf(source: S): VS {
        cache.load()?.takeIf { cached -> cached.source === source }?.let { cached -> return cached.projected }
        return project(source).also { projected -> cache.store(Cached(source, projected)) }
    }
}
