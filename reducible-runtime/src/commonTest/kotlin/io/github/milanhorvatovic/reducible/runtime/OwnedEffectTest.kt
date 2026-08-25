package io.github.milanhorvatovic.reducible.runtime

import io.github.milanhorvatovic.reducible.EffectHandler
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.combine
import io.github.milanhorvatovic.reducible.ifPresent
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.optional
import io.github.milanhorvatovic.reducible.prism
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private data class DrawerState(
    val editor: DrawerEditorState? = null,
    val ticks: Int = 0,
)

private sealed interface DrawerEditorState {
    data object Idle : DrawerEditorState

    data object Working : DrawerEditorState
}

private sealed interface DrawerAction {
    data object Open : DrawerAction

    data object Close : DrawerAction

    data class Editor(
        val action: DrawerEditorAction,
    ) : DrawerAction

    data object Ticked : DrawerAction
}

private sealed interface DrawerEditorAction {
    data object Begin : DrawerEditorAction

    data object Stop : DrawerEditorAction
}

private sealed interface DrawerEffect {
    data object Tick : DrawerEffect
}

private val editorOptic =
    optional<DrawerState, DrawerEditorState>(
        getOrNull = { drawer -> drawer.editor },
        set = { state, editor ->
            if (state.editor == null) {
                state
            } else {
                state.copy(editor = editor)
            }
        },
    )

private val editorActionPrism =
    prism<DrawerAction, DrawerEditorAction>(
        getOrNull = { action -> (action as? DrawerAction.Editor)?.action },
        embed = { editorAction -> DrawerAction.Editor(editorAction) },
    )

private val editorReducer =
    Reducer<DrawerEditorState, DrawerEditorAction, DrawerEffect> { _, action ->
        when (action) {
            DrawerEditorAction.Begin -> DrawerEditorState.Working.withEffect(DrawerEffect.Tick)
            DrawerEditorAction.Stop -> DrawerEditorState.Idle.only()
        }
    }

// The parent state is one class, so nothing here cancels through the root's class owner —
// whatever cancels in these tests cancels through envelope owners alone.
private val drawerReducer =
    combine(
        editorReducer.ifPresent(state = editorOptic, action = editorActionPrism, effect = { effect -> effect }),
        Reducer { state, action ->
            when (action) {
                DrawerAction.Open -> state.copy(editor = DrawerEditorState.Idle).only()
                DrawerAction.Close -> state.copy(editor = null).only()
                is DrawerAction.Editor -> state.only()
                DrawerAction.Ticked -> state.copy(ticks = state.ticks + 1).only()
            }
        },
    )

private val tickHandler =
    EffectHandler<DrawerEffect, DrawerAction> { effect, send ->
        when (effect) {
            DrawerEffect.Tick -> {
                delay(60_000)
                send(DrawerAction.Ticked)
            }
        }
    }

class OwnedEffectTest {
    @Test
    fun dismissing_the_child_within_the_same_state_class_cancels_its_effect() =
        runTest {
            val store = drawerStore()
            try {
                store.send(DrawerAction.Open)
                store.send(DrawerAction.Editor(DrawerEditorAction.Begin))
                runCurrent()
                assertEquals(1, store.activeEffects)

                store.send(DrawerAction.Close)
                runCurrent()
                assertEquals(0, store.activeEffects)

                advanceUntilIdle()
                assertEquals(0, store.state.ticks)
            } finally {
                store.close()
            }
        }

    @Test
    fun the_child_leaving_its_own_phase_cancels_its_effect_while_it_stays_present() =
        runTest {
            val store = drawerStore()
            try {
                store.send(DrawerAction.Open)
                store.send(DrawerAction.Editor(DrawerEditorAction.Begin))
                runCurrent()
                assertEquals(1, store.activeEffects)

                // Same parent class, same focus present — only the child's phase changes.
                store.send(DrawerAction.Editor(DrawerEditorAction.Stop))
                runCurrent()
                assertEquals(0, store.activeEffects)

                advanceUntilIdle()
                assertEquals(0, store.state.ticks)
            } finally {
                store.close()
            }
        }

    @Test
    fun the_effect_completes_while_the_child_stays_present() =
        runTest {
            val store = drawerStore()
            try {
                store.send(DrawerAction.Open)
                store.send(DrawerAction.Editor(DrawerEditorAction.Begin))
                advanceUntilIdle()
                assertEquals(1, store.state.ticks)
            } finally {
                store.close()
            }
        }

    @Test
    fun an_effect_whose_child_is_gone_by_the_end_of_the_reduction_never_launches() =
        runTest {
            // A later reducer in the same combine dismisses the editor on the very action the
            // child used to request its effect — the envelope's owner already fails in the
            // reduction's final state, so the launch is skipped outright.
            val dismissingReducer =
                combine(
                    editorReducer.ifPresent(state = editorOptic, action = editorActionPrism, effect = { effect -> effect }),
                    Reducer { state: DrawerState, action: DrawerAction ->
                        when (action) {
                            is DrawerAction.Editor -> state.copy(editor = null).only()
                            DrawerAction.Open -> state.copy(editor = DrawerEditorState.Idle).only()
                            DrawerAction.Close -> state.copy(editor = null).only()
                            DrawerAction.Ticked -> state.copy(ticks = state.ticks + 1).only()
                        }
                    },
                )
            val events = mutableListOf<EffectEvent>()
            val store =
                Store(
                    initialState = DrawerState(),
                    reducer = dismissingReducer,
                    handler = tickHandler,
                    scope = StoreScope.Testing(StandardTestDispatcher(testScheduler)),
                    observer =
                        object : StoreObserver<DrawerState, DrawerAction> {
                            override fun onReduced(
                                previous: DrawerState,
                                action: DrawerAction,
                                next: DrawerState,
                            ) {
                            }

                            override fun onEffect(event: EffectEvent) {
                                events += event
                            }
                        },
                )
            try {
                store.send(DrawerAction.Open)
                store.send(DrawerAction.Editor(DrawerEditorAction.Begin))
                runCurrent()
                assertEquals(0, store.activeEffects)
                // The skip stands in for the launch the observer would otherwise have seen.
                assertEquals(listOf<EffectEvent>(EffectEvent.Skipped(DrawerEffect.Tick, key = null)), events)

                advanceUntilIdle()
                assertEquals(0, store.state.ticks)
            } finally {
                store.close()
            }
        }

    private fun kotlinx.coroutines.test.TestScope.drawerStore(): Store<DrawerState, DrawerAction> =
        Store(
            initialState = DrawerState(),
            reducer = drawerReducer,
            handler = tickHandler,
            scope = StoreScope.Testing(StandardTestDispatcher(testScheduler)),
        )
}
