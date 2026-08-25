package io.github.milanhorvatovic.reducible.runtime

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t
import kotlin.coroutines.CoroutineContext

/**
 * [StoreScope.Dedicated] on a GCD queue the iOS app already owns, so the reduction
 * thread can be chosen from Swift: `StoreScopeDarwinKt.dedicated(queue:)`. A serial
 * queue is the natural choice; the runtime re-serializes regardless.
 */
public fun dedicated(queue: dispatch_queue_t): StoreScope.Dedicated = StoreScope.Dedicated(DispatchQueueDispatcher(queue))

/** Wraps a GCD queue as a coroutine dispatcher. */
public fun dispatchQueueDispatcher(queue: dispatch_queue_t): CoroutineDispatcher = DispatchQueueDispatcher(queue)

private class DispatchQueueDispatcher(
    private val queue: dispatch_queue_t,
) : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        dispatch_async(queue) { block.run() }
    }
}
