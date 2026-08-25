package io.github.milanhorvatovic.reducible

import kotlin.test.Test
import kotlin.test.assertEquals

// A child that, once opened, asks to be started: the follow-up must come back addressed to it.
private sealed interface FollowChildState {
    data object Closed : FollowChildState

    data class Open(
        val id: String,
        val started: Boolean = false,
    ) : FollowChildState
}

private sealed interface FollowChildAction {
    data object Open : FollowChildAction

    data object Start : FollowChildAction
}

private val childReducer =
    Reducer<FollowChildState, FollowChildAction, Nothing> { state, action ->
        when (action) {
            FollowChildAction.Open -> (state as? FollowChildState.Open ?: FollowChildState.Open("solo")).andSend(FollowChildAction.Start)
            FollowChildAction.Start -> (state as? FollowChildState.Open)?.copy(started = true)?.only() ?: state.only()
        }
    }

private data class FollowHost(
    val child: FollowChildState = FollowChildState.Closed,
    val rows: List<FollowChildState.Open> = emptyList(),
    val log: List<String> = emptyList(),
)

private sealed interface FollowHostAction {
    data class Child(
        val action: FollowChildAction,
    ) : FollowHostAction

    data class Row(
        val identified: IdentifiedAction<String, FollowChildAction>,
    ) : FollowHostAction

    data object Note : FollowHostAction
}

private val childOptional =
    lens<FollowHost, FollowChildState>(get = { host -> host.child }, set = { host, child -> host.copy(child = child) })

private val rowsLens =
    lens<FollowHost, List<FollowChildState.Open>>(get = { host -> host.rows }, set = { host, rows -> host.copy(rows = rows) })

private val rowReducer =
    Reducer<FollowChildState.Open, FollowChildAction, Nothing> { state, action ->
        when (action) {
            FollowChildAction.Open -> state.andSend(FollowChildAction.Start)
            FollowChildAction.Start -> state.copy(started = true).only()
        }
    }

class FollowUpCompositionTest {
    @Test
    fun ifPresent_embeds_the_child_follow_up_into_the_parent_action() {
        val host =
            childReducer.ifPresent(
                state = childOptional,
                action =
                    prism(getOrNull = { action ->
                        (action as? FollowHostAction.Child)?.action
                    }, embed = { childAction ->
                        FollowHostAction.Child(childAction)
                    }),
                effect = { effect -> effect },
            )

        val reduced = host.reduce(FollowHost(), FollowHostAction.Child(FollowChildAction.Open))

        assertEquals(FollowHost(child = FollowChildState.Open("solo")), reduced.state)
        assertEquals(listOf(FollowHostAction.Child(FollowChildAction.Start)), reduced.followUps)
    }

    @Test
    fun forEachIdentified_addresses_the_follow_up_to_the_row_it_came_from() {
        val host =
            rowReducer.forEachIdentified(
                list = rowsLens,
                identity = { row -> row.id },
                action =
                    prism(getOrNull = { action ->
                        (action as? FollowHostAction.Row)?.identified
                    }, embed = { identified -> FollowHostAction.Row(identified) }),
                effect = { _, effect -> effect },
            )
        val rows = listOf(FollowChildState.Open("a"), FollowChildState.Open("b"))

        val reduced = host.reduce(FollowHost(rows = rows), FollowHostAction.Row(IdentifiedAction("b", FollowChildAction.Open)))

        assertEquals(listOf(FollowHostAction.Row(IdentifiedAction("b", FollowChildAction.Start))), reduced.followUps)
    }

    @Test
    fun combine_concatenates_follow_ups_in_reducer_order() {
        val first = Reducer<FollowHost, FollowHostAction, Nothing> { state, _ -> state.andSend(FollowHostAction.Note) }
        val second =
            Reducer<FollowHost, FollowHostAction, Nothing> {
                state,
                _,
                ->
                state.copy(log = state.log + "second").andSend(FollowHostAction.Child(FollowChildAction.Start))
            }

        val reduced = combine(first, second).reduce(FollowHost(), FollowHostAction.Note)

        assertEquals(listOf("second"), reduced.state.log)
        assertEquals(listOf(FollowHostAction.Note, FollowHostAction.Child(FollowChildAction.Start)), reduced.followUps)
    }
}
