package io.github.milanhorvatovic.reducible

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

// Child feature: a counter with a typed effect.
private data class Counter(
    val id: String,
    val count: Int,
)

private sealed interface CounterAction {
    data object Increment : CounterAction
}

private sealed interface CounterEffect {
    data class Persist(
        val count: Int,
    ) : CounterEffect
}

private data object PersistKey : EffectKey

private val counterReducer =
    Reducer<Counter, CounterAction, CounterEffect> { state, action ->
        when (action) {
            CounterAction.Increment -> {
                state
                    .copy(count = state.count + 1)
                    .withEffect(CounterEffect.Persist(state.count + 1), key = PersistKey)
            }
        }
    }

// Parent feature embedding one optional counter and a list of counters.
private sealed interface ParentState {
    data object Empty : ParentState

    data class Active(
        val counter: Counter,
        val rows: List<Counter>,
    ) : ParentState
}

private sealed interface ParentAction {
    data class Single(
        val action: CounterAction,
    ) : ParentAction

    data class Row(
        val identified: IdentifiedAction<String, CounterAction>,
    ) : ParentAction
}

private sealed interface ParentEffect {
    data class FromSingle(
        val effect: CounterEffect,
    ) : ParentEffect

    data class FromRow(
        val id: String,
        val effect: CounterEffect,
    ) : ParentEffect
}

private val activePrism = casePrism<ParentState, ParentState.Active>()

private val counterOptional =
    activePrism andThen
        lens<ParentState.Active, Counter>(
            get = { parent -> parent.counter },
            set = { active, counter -> active.copy(counter = counter) },
        )

private val singleActionPrism =
    prism<ParentAction, CounterAction>(
        getOrNull = { action -> (action as? ParentAction.Single)?.action },
        embed = { counterAction -> ParentAction.Single(counterAction) },
    )

private val rowsOptional =
    activePrism andThen
        lens<ParentState.Active, List<Counter>>(
            get = { parent -> parent.rows },
            set = { active, rows -> active.copy(rows = rows) },
        )

private val rowActionPrism =
    prism<ParentAction, IdentifiedAction<String, CounterAction>>(
        getOrNull = { action -> (action as? ParentAction.Row)?.identified },
        embed = { identified -> ParentAction.Row(identified) },
    )

private val composed =
    combine(
        counterReducer.ifPresent(
            state = counterOptional,
            action = singleActionPrism,
            effect = ParentEffect::FromSingle,
        ),
        counterReducer.forEachIdentified(
            list = rowsOptional,
            identity = Counter::id,
            action = rowActionPrism,
            effect = ParentEffect::FromRow,
        ),
    )

class CompositionTest {
    private val active =
        ParentState.Active(
            counter = Counter("main", 0),
            rows = listOf(Counter("a", 10), Counter("b", 20)),
        )

    @Test
    fun ifPresent_routes_action_and_maps_effects() {
        val reduced = composed.reduce(active, ParentAction.Single(CounterAction.Increment))

        val state = reduced.state as ParentState.Active
        assertEquals(1, state.counter.count)
        assertEquals(
            listOf<ParentEffect>(ParentEffect.FromSingle(CounterEffect.Persist(1))),
            reduced.effects.map { envelope -> envelope.effect },
        )
        assertEquals(listOf(EffectScope.StateScoped), reduced.effects.map { envelope -> envelope.scope })
        // A single scoped child's key is namespaced by its slot, the optic by default.
        assertEquals(IdentifiedEffectKey(counterOptional, PersistKey), reduced.effects.single().key)
    }

    @Test
    fun ifPresent_drops_action_when_child_absent() {
        val reduced = composed.reduce(ParentState.Empty, ParentAction.Single(CounterAction.Increment))

        assertSame(ParentState.Empty, reduced.state)
        assertEquals(emptyList(), reduced.effects)
    }

    @Test
    fun forEachIdentified_updates_only_the_addressed_element() {
        val reduced =
            composed.reduce(
                active,
                ParentAction.Row(IdentifiedAction("b", CounterAction.Increment)),
            )

        val state = reduced.state as ParentState.Active
        assertEquals(listOf(Counter("a", 10), Counter("b", 21)), state.rows)
        assertEquals(
            listOf<ParentEffect>(ParentEffect.FromRow("b", CounterEffect.Persist(21))),
            reduced.effects.map { envelope -> envelope.effect },
        )
        // List children get their key namespaced by identity, so rows cannot cancel each other.
        assertEquals(IdentifiedEffectKey("b", PersistKey), reduced.effects.single().key)
    }

    @Test
    fun forEachIdentified_drops_action_when_list_container_absent() {
        val reduced =
            composed.reduce(
                ParentState.Empty,
                ParentAction.Row(IdentifiedAction("a", CounterAction.Increment)),
            )

        assertSame(ParentState.Empty, reduced.state)
        assertEquals(emptyList(), reduced.effects)
    }

    @Test
    fun forEachIdentified_drops_action_for_removed_identity() {
        val reduced =
            composed.reduce(
                active,
                ParentAction.Row(IdentifiedAction("gone", CounterAction.Increment)),
            )

        assertSame(active, reduced.state)
        assertEquals(emptyList(), reduced.effects)
    }

    @Test
    fun combine_threads_state_and_concatenates_effects() {
        val appendA = Reducer<String, Unit, String> { state, _ -> (state + "a").withEffect("ea") }
        val appendB = Reducer<String, Unit, String> { state, _ -> (state + "b").withEffect("eb") }

        val reduced = combine(appendA, appendB).reduce("", Unit)

        assertEquals("ab", reduced.state)
        assertEquals(listOf("ea", "eb"), reduced.effects.map { envelope -> envelope.effect })
    }
}

// Two children of one reducer type side by side, and the pair embedded once more: the
// namespacing that keeps their keys apart, and the path a named slot prints as.
private data class Twins(
    val left: Counter,
    val right: Counter,
)

private sealed interface TwinAction {
    data class Left(
        val action: CounterAction,
    ) : TwinAction

    data class Right(
        val action: CounterAction,
    ) : TwinAction
}

private sealed interface TwinEffect {
    data class Left(
        val effect: CounterEffect,
    ) : TwinEffect

    data class Right(
        val effect: CounterEffect,
    ) : TwinEffect
}

private val leftLens = lens<Twins, Counter>({ twins -> twins.left }, { twins, counter -> twins.copy(left = counter) })
private val rightLens = lens<Twins, Counter>({ twins -> twins.right }, { twins, counter -> twins.copy(right = counter) })
private val leftActionPrism =
    prism<TwinAction, CounterAction>(
        { action -> (action as? TwinAction.Left)?.action },
        { counterAction -> TwinAction.Left(counterAction) },
    )
private val rightActionPrism =
    prism<TwinAction, CounterAction>(
        { action -> (action as? TwinAction.Right)?.action },
        { counterAction -> TwinAction.Right(counterAction) },
    )

private data class Outer(
    val twins: Twins?,
)

private data class OuterAction(
    val action: TwinAction,
)

private data class OuterEffect(
    val effect: TwinEffect,
)

private val twinsOptional = optional<Outer, Twins>({ outer -> outer.twins }, { outer, twins -> outer.copy(twins = twins) })
private val outerActionPrism = prism<OuterAction, TwinAction>({ action -> action.action }, { twinAction -> OuterAction(twinAction) })

class SlotNamespacingTest {
    private val twins = Twins(Counter("l", 0), Counter("r", 0))

    @Test
    fun two_children_of_one_type_get_distinct_keys_without_naming_anything() {
        val reducer =
            combine(
                counterReducer.ifPresent(leftLens, leftActionPrism, TwinEffect::Left),
                counterReducer.ifPresent(rightLens, rightActionPrism, TwinEffect::Right),
            )

        val left =
            reducer
                .reduce(twins, TwinAction.Left(CounterAction.Increment))
                .effects
                .single()
                .key
        val right =
            reducer
                .reduce(twins, TwinAction.Right(CounterAction.Increment))
                .effects
                .single()
                .key
        val leftAgain =
            reducer
                .reduce(twins, TwinAction.Left(CounterAction.Increment))
                .effects
                .single()
                .key

        assertNotEquals(left, right, "siblings share PersistKey inside their reducer; the slot keeps them apart")
        assertEquals(left, leftAgain, "a child's own re-request still replaces its running holder")
        assertEquals(PersistKey.policy, left!!.policy)
    }

    @Test
    fun a_named_slot_reads_as_a_path_and_nests() {
        val reducer =
            combine(
                counterReducer.ifPresent(leftLens, leftActionPrism, TwinEffect::Left, slot = "left"),
                counterReducer.ifPresent(rightLens, rightActionPrism, TwinEffect::Right, slot = "right"),
            ).ifPresent(twinsOptional, outerActionPrism, ::OuterEffect, slot = "twins")

        val key =
            reducer
                .reduce(Outer(twins), OuterAction(TwinAction.Left(CounterAction.Increment)))
                .effects
                .single()
                .key

        assertEquals(IdentifiedEffectKey("twins", IdentifiedEffectKey("left", PersistKey)), key)
        assertEquals("twins/left/PersistKey", key.toString())
    }
}
