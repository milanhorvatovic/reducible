package io.github.milanhorvatovic.reducible

import kotlin.test.Test
import kotlin.test.assertEquals

private data class Tally(
    val total: Int,
    val entries: Int,
)

private sealed interface TallyAction {
    data class Add(
        val amount: Int,
    ) : TallyAction

    data object Reset : TallyAction
}

private val tallyReducer =
    Reducer<Tally, TallyAction, String> { state, action ->
        when (action) {
            is TallyAction.Add -> {
                Tally(state.total + action.amount, state.entries + 1).withEffect("persist")
            }

            TallyAction.Reset -> {
                Tally(0, 0).only()
            }
        }
    }

class ReplayTest {
    @Test
    fun replay_folds_the_action_log_to_the_final_state() {
        val actions =
            listOf(
                TallyAction.Add(3),
                TallyAction.Add(4),
                TallyAction.Reset,
                TallyAction.Add(5),
            )

        assertEquals(Tally(5, 1), tallyReducer.replay(Tally(0, 0), actions))
    }

    @Test
    fun replay_of_an_empty_log_is_the_initial_state() {
        assertEquals(Tally(7, 2), tallyReducer.replay(Tally(7, 2), emptyList()))
    }
}
