package io.github.milanhorvatovic.reducible

/**
 * Runs [reducers] in order, threading the state through and concatenating effects and
 * follow-ups. The lists are allocated only once a reducer requests something: most actions
 * request nothing, and this runs once per action per composition level.
 */
public fun <S, A, E> combine(vararg reducers: Reducer<S, A, E>): Reducer<S, A, E> =
    Reducer { state, action ->
        var current = state
        var effects: MutableList<EffectEnvelope<E>>? = null
        var followUps: MutableList<A>? = null
        for (reducer in reducers) {
            val reduced = reducer.reduce(current, action)
            current = reduced.state
            if (reduced.effects.isNotEmpty()) {
                (effects ?: mutableListOf<EffectEnvelope<E>>().also { list -> effects = list }) += reduced.effects
            }
            if (reduced.followUps.isNotEmpty()) {
                (followUps ?: mutableListOf<A>().also { list -> followUps = list }) += reduced.followUps
            }
        }
        Reduced(current, effects ?: emptyList(), followUps ?: emptyList())
    }

/**
 * Scopes a child reducer into a parent. The child runs only when [action] matches a child
 * action AND [state] currently resolves; a child action arriving while the child is absent is
 * dropped — the effect completing after removal must not write into a state that no longer
 * holds the child.
 *
 * A child effect is owned exactly as a root effect would be, inside the focus: while [state]
 * resolves AND the child stays in the state class its reduction entered (or, for an envelope
 * carrying its own owner, while that owner resolves in the child). A child that leaves its
 * phase cancels its state-scoped effects the same way a root state does. Child follow-ups come
 * back embedded through [action], addressed to the same child.
 *
 * Child keys are namespaced by [slot], so two children of one reducer type in one store cannot
 * cancel each other's keyed effects. The default slot is the optic itself — a stable object, so
 * a child's re-request still replaces its own running holder — and a name (`slot = "notes"`)
 * makes the key read as a path in logs; nested children nest their slots.
 */
public fun <PS, PA, PE, CS, CA, CE> Reducer<CS, CA, CE>.ifPresent(
    state: Optional<PS, CS>,
    action: Prism<PA, CA>,
    effect: (CE) -> PE,
    slot: Any = state,
): Reducer<PS, PA, PE> =
    Reducer { parentState, parentAction ->
        val childAction = action.getOrNull(parentAction) ?: return@Reducer parentState.only()
        val childState = state.getOrNull(parentState) ?: return@Reducer parentState.only()
        val reduced = reduce(childState, childAction)
        Reduced(
            state.set(parentState, reduced.state),
            reduced.effects.map { envelope ->
                EffectEnvelope(
                    effect(envelope.effect),
                    envelope.scope,
                    envelope.key?.let { key -> IdentifiedEffectKey(slot, key) },
                    envelope.owner.orClassOf(reduced.state).through(state),
                )
            },
            reduced.followUps.map(action::embed),
        )
    }

/**
 * The owner a child envelope carries, or — when the child reducer set none — the implicit one
 * every root effect gets from the store: the state class the requesting reduction [entered].
 * Made explicit here because the operator wraps the owner for the parent level, and the store
 * only synthesizes the class owner for envelopes that reach it with none.
 */
@Suppress("UNCHECKED_CAST") // The operator that created the inner owner proved it ranges over CS.
private fun <CS> EffectOwner<*>?.orClassOf(entered: CS): EffectOwner<CS> {
    if (this != null) {
        return this as EffectOwner<CS>
    }
    val enteredClass = (entered as Any?)?.let { value -> value::class } ?: return EffectOwner { true }
    return EffectOwner { child -> (child as Any?)?.let { value -> value::class } == enteredClass }
}

/**
 * Re-scopes a child envelope's owner to the parent level: the effect stays owned exactly
 * while [optic] resolves AND the child owner still resolves in the focused child.
 */
private fun <PS, CS> EffectOwner<CS>.through(optic: Optional<PS, CS>): EffectOwner<PS> =
    EffectOwner { parentState ->
        val child = optic.getOrNull(parentState) ?: return@EffectOwner false
        resolvesIn(child)
    }

/**
 * Runs this child handler for one branch of a parent handler's exhaustive `when`, embedding
 * every action the child sends back into the parent action type — the handler-side mirror of
 * [ifPresent] and [forEachIdentified], which embed child effects on the way out. Effect
 * extraction deliberately stays in the parent's `when` over its sealed effect type, so a new
 * effect case is a compile error in the parent handler, never a runtime routing miss:
 *
 * ```
 * EffectHandler<ParentEffect, ParentAction> { effect, send ->
 *     when (effect) {
 *         is ParentEffect.Editor ->
 *             editorHandler.handle(effect.effect, send, ParentAction::Editor)
 *
 *         is ParentEffect.Row ->
 *             rowHandler.handle(effect.effect, send) {
 *                 ParentAction.Row(IdentifiedAction(effect.id, it))
 *             }
 *     }
 * }
 * ```
 */
public suspend fun <E, CA, PA> EffectHandler<E, CA>.handle(
    effect: E,
    send: (PA) -> Unit,
    action: (CA) -> PA,
) {
    handle(effect) { childAction -> send(action(childAction)) }
}

/** A child action addressed to one element of an identified list. */
public data class IdentifiedAction<out I, out A>(
    public val id: I,
    public val action: A,
)

/**
 * A child's [EffectKey] namespaced by where it came from — the element identity in a list, the
 * slot in [ifPresent] — so two children using the same key inside their own reducer cannot
 * cancel each other's effects. The child key's [policy] carries through unchanged. Prints as a
 * path, `notes/editor/HintDebounce`, since nested composition nests the wrapping.
 */
public data class IdentifiedEffectKey(
    public val id: Any?,
    public val key: EffectKey,
) : EffectKey {
    override val policy: KeyPolicy
        get() = key.policy

    override fun toString(): String = "$id/$key"
}

/**
 * Scopes a child reducer over every element of a list, addressed by identity key — never by
 * position, so an action arriving after a removal or reorder finds its element or is dropped.
 * Row effects are owned like [ifPresent]'s: by the row's identity in the list and the state
 * class the row entered, with row-level keys namespaced by identity.
 *
 * Each row action copies the list once to replace the row, O(n) — fine for the tens to
 * hundreds of rows a screen shows. A list-heavy feature can use the structural-sharing variant
 * over a persistent list from the `core-immutable` module, built on [rowEffects].
 */
public fun <PS, PA, PE, CS, CA, CE, I> Reducer<CS, CA, CE>.forEachIdentified(
    list: Optional<PS, List<CS>>,
    identity: (CS) -> I,
    action: Prism<PA, IdentifiedAction<I, CA>>,
    effect: (I, CE) -> PE,
): Reducer<PS, PA, PE> =
    Reducer { parentState, parentAction ->
        val identified = action.getOrNull(parentAction) ?: return@Reducer parentState.only()
        val elements = list.getOrNull(parentState) ?: return@Reducer parentState.only()
        val index = elements.indexOfFirst { element -> identity(element) == identified.id }
        if (index < 0) {
            return@Reducer parentState.only()
        }
        val reduced = reduce(elements[index], identified.action)
        val updated = elements.toMutableList().apply { this[index] = reduced.state }
        rowReduced(list.set(parentState, updated), reduced, identified.id, list::getOrNull, identity, action, effect)
    }

/**
 * Embeds one row's reduction into the parent level, [state] being the parent state with the
 * row already replaced — the building block behind [forEachIdentified] and any list operator
 * over another list type. Every effect is wrapped by [effect] with the row's [id], its key
 * namespaced by that identity, and its owner set so the effect lives exactly while the row
 * with this identity is still among [rows] AND the row stays in the state class its reduction
 * entered (or its own owner, if it set one, still resolves). Follow-ups come back addressed
 * to the row through [action].
 */
public fun <PS, PA, PE, CS, CA, CE, I> rowReduced(
    state: PS,
    reduced: Reduced<CS, CA, CE>,
    id: I,
    rows: (PS) -> List<CS>?,
    identity: (CS) -> I,
    action: Prism<PA, IdentifiedAction<I, CA>>,
    effect: (I, CE) -> PE,
): Reduced<PS, PA, PE> =
    Reduced(
        state = state,
        effects =
            reduced.effects.map { envelope ->
                EffectEnvelope(
                    effect = effect(id, envelope.effect),
                    scope = envelope.scope,
                    key = envelope.key?.let { key -> IdentifiedEffectKey(id, key) },
                    owner = envelope.owner.orClassOf(reduced.state).throughElement(rows, identity, id),
                )
            },
        followUps = reduced.followUps.map { followUp -> action.embed(IdentifiedAction(id, followUp)) },
    )

/**
 * Re-scopes a row envelope's owner to the parent level: the effect stays owned exactly while
 * the element with this row's identity is still in the list AND the row owner still resolves
 * in it — so removing one row cancels that row's effects and no sibling's, and a row leaving
 * its phase cancels its own.
 */
private fun <PS, CS, I> EffectOwner<CS>.throughElement(
    rows: (PS) -> List<CS>?,
    identity: (CS) -> I,
    id: I,
): EffectOwner<PS> =
    EffectOwner { parentState ->
        val element =
            rows(parentState)?.firstOrNull { element -> identity(element) == id }
                ?: return@EffectOwner false
        resolvesIn(element)
    }
