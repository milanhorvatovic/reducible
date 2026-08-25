package io.github.milanhorvatovic.reducible

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private sealed interface OwnerParentState {
    data class Open(
        val panel: OwnerPanelState?,
    ) : OwnerParentState

    data object Closed : OwnerParentState
}

private data class OwnerPanelState(
    val rows: List<OwnerRowState> = emptyList(),
)

private data class OwnerRowState(
    val id: String,
)

private sealed interface OwnerParentAction {
    data class Panel(
        val action: OwnerPanelAction,
    ) : OwnerParentAction
}

private sealed interface OwnerPanelAction {
    data object Begin : OwnerPanelAction

    data class Row(
        val action: IdentifiedAction<String, OwnerRowAction>,
    ) : OwnerPanelAction
}

private sealed interface OwnerRowAction {
    data object Begin : OwnerRowAction
}

private sealed interface OwnerEffect {
    data object PanelWork : OwnerEffect

    data object RowWork : OwnerEffect
}

private val panelOptional: Optional<OwnerParentState, OwnerPanelState> =
    casePrism<OwnerParentState, OwnerParentState.Open>() andThen
        optional(
            getOrNull = { parent -> parent.panel },
            set = { open, panel ->
                if (open.panel == null) {
                    open
                } else {
                    open.copy(panel = panel)
                }
            },
        )

private val panelActionPrism: Prism<OwnerParentAction, OwnerPanelAction> =
    prism(
        getOrNull = { action -> (action as? OwnerParentAction.Panel)?.action },
        embed = { panelAction -> OwnerParentAction.Panel(panelAction) },
    )

private val rowActionPrism: Prism<OwnerPanelAction, IdentifiedAction<String, OwnerRowAction>> =
    prism(
        getOrNull = { action -> (action as? OwnerPanelAction.Row)?.action },
        embed = { rowAction -> OwnerPanelAction.Row(rowAction) },
    )

private val rowReducer =
    Reducer<OwnerRowState, OwnerRowAction, OwnerEffect> { state, action ->
        when (action) {
            OwnerRowAction.Begin -> state.withEffect(OwnerEffect.RowWork)
        }
    }

private val panelReducer =
    combine(
        rowReducer.forEachIdentified(
            list = lens<OwnerPanelState, List<OwnerRowState>>(get = { panel -> panel.rows }, set = { s, rows -> s.copy(rows = rows) }),
            identity = { row -> row.id },
            action = rowActionPrism,
            effect = { _, effect -> effect },
        ),
        Reducer<OwnerPanelState, OwnerPanelAction, OwnerEffect> { state, action ->
            when (action) {
                OwnerPanelAction.Begin -> state.withEffect(OwnerEffect.PanelWork)
                is OwnerPanelAction.Row -> state.only()
            }
        },
    )

private val parentReducer: Reducer<OwnerParentState, OwnerParentAction, OwnerEffect> =
    panelReducer.ifPresent(state = panelOptional, action = panelActionPrism, effect = { effect -> effect })

@Suppress("UNCHECKED_CAST")
private fun EffectEnvelope<*>.resolvesIn(state: OwnerParentState): Boolean = (owner as EffectOwner<OwnerParentState>).resolvesIn(state)

class OwnerCompositionTest {
    private val withPanel = OwnerParentState.Open(OwnerPanelState())
    private val withoutPanel = OwnerParentState.Open(panel = null)

    @Test
    fun scoped_envelope_owner_resolves_exactly_while_the_optic_does() {
        val envelope =
            parentReducer
                .reduce(withPanel, OwnerParentAction.Panel(OwnerPanelAction.Begin))
                .effects
                .single()

        assertTrue(envelope.resolvesIn(withPanel))
        assertFalse(envelope.resolvesIn(withoutPanel))
        assertFalse(envelope.resolvesIn(OwnerParentState.Closed))
    }

    @Test
    fun nested_row_owner_requires_the_optic_chain_and_the_row_identity() {
        val twoRows = OwnerParentState.Open(OwnerPanelState(listOf(OwnerRowState("a"), OwnerRowState("b"))))
        val envelope =
            parentReducer
                .reduce(
                    twoRows,
                    OwnerParentAction.Panel(OwnerPanelAction.Row(IdentifiedAction("a", OwnerRowAction.Begin))),
                ).effects
                .single()

        assertTrue(envelope.resolvesIn(twoRows))
        // Removing the requesting row stops resolution; an unrelated sibling removal does not.
        assertFalse(envelope.resolvesIn(OwnerParentState.Open(OwnerPanelState(listOf(OwnerRowState("b"))))))
        assertTrue(envelope.resolvesIn(OwnerParentState.Open(OwnerPanelState(listOf(OwnerRowState("a"))))))
        assertFalse(envelope.resolvesIn(withoutPanel))
        assertFalse(envelope.resolvesIn(OwnerParentState.Closed))
    }
}
