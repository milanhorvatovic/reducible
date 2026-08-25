package io.github.milanhorvatovic.reducible

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals

private sealed interface ChildAction {
    data class Computed(
        val value: Int,
    ) : ChildAction
}

private sealed interface HostAction {
    data class Child(
        val action: ChildAction,
    ) : HostAction

    data class Row(
        val action: IdentifiedAction<String, ChildAction>,
    ) : HostAction
}

private sealed interface ChildEffect {
    data class Compute(
        val input: Int,
    ) : ChildEffect
}

private val childHandler =
    EffectHandler<ChildEffect, ChildAction> { effect, send ->
        when (effect) {
            is ChildEffect.Compute -> send(ChildAction.Computed(effect.input * 2))
        }
    }

class HandlerCompositionTest {
    @Test
    fun delegated_branch_embeds_child_actions_into_the_parent_type() {
        val received = mutableListOf<HostAction>()

        runHandler {
            childHandler.handle(ChildEffect.Compute(21), received::add, HostAction::Child)
        }

        assertEquals(listOf<HostAction>(HostAction.Child(ChildAction.Computed(42))), received)
    }

    @Test
    fun identified_branch_readdresses_child_actions_to_their_element() {
        val received = mutableListOf<HostAction>()

        runHandler {
            childHandler.handle(ChildEffect.Compute(3), received::add) { rowAction ->
                HostAction.Row(IdentifiedAction("row-1", rowAction))
            }
        }

        assertEquals(
            listOf<HostAction>(HostAction.Row(IdentifiedAction("row-1", ChildAction.Computed(6)))),
            received,
        )
    }
}

/** Runs a non-suspending handler body; core-api tests carry no coroutines dependency. */
private fun runHandler(block: suspend () -> Unit) {
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result -> result.getOrThrow() })
}
