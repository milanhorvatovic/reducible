package io.github.milanhorvatovic.reducible.immutable

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.EffectOwner
import io.github.milanhorvatovic.reducible.IdentifiedAction
import io.github.milanhorvatovic.reducible.IdentifiedEffectKey
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.lens
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.prism
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data class Board(
    val rows: PersistentList<RowState>,
)

private sealed interface RowState {
    val id: String

    data class Idle(
        override val id: String,
    ) : RowState

    data class Working(
        override val id: String,
    ) : RowState
}

private sealed interface BoardAction {
    data class Row(
        val action: IdentifiedAction<String, RowAction>,
    ) : BoardAction
}

private sealed interface RowAction {
    data object Begin : RowAction

    data object Stop : RowAction
}

private data object Work

private data object WorkKey : EffectKey

private val rowReducer =
    Reducer<RowState, RowAction, Work> { state, action ->
        when (action) {
            RowAction.Begin -> RowState.Working(state.id).withEffect(Work, key = WorkKey)
            RowAction.Stop -> RowState.Idle(state.id).only()
        }
    }

private val boardReducer =
    rowReducer.forEachIdentified(
        list = lens<Board, PersistentList<RowState>>(get = { board -> board.rows }, set = { board, rows -> board.copy(rows = rows) }),
        identity = { row -> row.id },
        action = prism(getOrNull = { action -> (action as? BoardAction.Row)?.action }, embed = { rowAction -> BoardAction.Row(rowAction) }),
        effect = { _, effect -> effect },
    )

private fun begin(id: String) = BoardAction.Row(IdentifiedAction(id, RowAction.Begin))

@Suppress("UNCHECKED_CAST")
private fun EffectEnvelope<*>.resolvesIn(board: Board): Boolean = (owner as EffectOwner<Board>).resolvesIn(board)

class PersistentForEachIdentifiedTest {
    private val board = Board(persistentListOf(RowState.Idle("a"), RowState.Idle("b"), RowState.Idle("c")))

    @Test
    fun a_row_action_replaces_one_element_and_shares_the_rest() {
        val reduced = boardReducer.reduce(board, begin("b"))

        assertEquals(Board(persistentListOf(RowState.Idle("a"), RowState.Working("b"), RowState.Idle("c"))), reduced.state)
        assertSame(board.rows[0], reduced.state.rows[0], "untouched rows must be the same instances")
        assertSame(board.rows[2], reduced.state.rows[2], "untouched rows must be the same instances")
    }

    @Test
    fun effects_are_keyed_by_identity_and_owned_by_the_row_and_its_phase() {
        val reduced = boardReducer.reduce(board, begin("b"))
        val envelope = reduced.effects.single()

        assertEquals(Work, envelope.effect)
        assertEquals(IdentifiedEffectKey("b", WorkKey), envelope.key)
        assertTrue(envelope.resolvesIn(reduced.state))
        assertFalse(
            envelope.resolvesIn(boardReducer.reduce(reduced.state, BoardAction.Row(IdentifiedAction("b", RowAction.Stop))).state),
            "leaving the phase must stop resolution",
        )
        assertFalse(
            envelope.resolvesIn(Board(persistentListOf(RowState.Idle("a"), RowState.Idle("c")))),
            "removing the row must stop resolution",
        )
        assertTrue(envelope.resolvesIn(Board(persistentListOf(RowState.Working("b")))), "a sibling's removal is not this row's business")
    }

    @Test
    fun an_action_for_an_unknown_row_is_dropped() {
        val reduced = boardReducer.reduce(board, begin("zz"))

        assertSame(board, reduced.state)
        assertTrue(reduced.effects.isEmpty())
    }
}
