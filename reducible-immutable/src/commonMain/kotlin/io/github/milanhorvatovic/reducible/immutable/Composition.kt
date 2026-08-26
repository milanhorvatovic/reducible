package io.github.milanhorvatovic.reducible.immutable

import io.github.milanhorvatovic.reducible.IdentifiedAction
import io.github.milanhorvatovic.reducible.Optional
import io.github.milanhorvatovic.reducible.Prism
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.rowReduced
import kotlinx.collections.immutable.PersistentList

/**
 * `forEachIdentified` over a persistent list: the same routing by identity, key namespacing,
 * and row ownership as the core operator, but a row action replaces one element by structural
 * sharing instead of copying the list — O(log n) per action and no conversion in the optic's
 * setter. For features whose state already holds a [PersistentList], this is the one to use.
 */
public fun <PS, PA, PE, CS, CA, CE, I> Reducer<CS, CA, CE>.forEachIdentified(
    list: Optional<PS, PersistentList<CS>>,
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
        rowReduced(
            list.set(parentState, elements.replacingAt(index, reduced.state)),
            reduced,
            identified.id,
            list::getOrNull,
            identity,
            action,
            effect,
        )
    }
