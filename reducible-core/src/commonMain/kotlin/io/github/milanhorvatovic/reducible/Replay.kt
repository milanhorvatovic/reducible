package io.github.milanhorvatovic.reducible

/**
 * Replays a recorded action log as a fold over the pure reducer, returning the final state.
 * Effects are discarded — they already ran when the log was recorded — and so are follow-up
 * actions, which the log holds as reduced actions in their place; replay reproduces the state
 * trajectory only, which is what makes a reducer log debuggable after the fact.
 */
public fun <S, A, E> Reducer<S, A, E>.replay(
    initialState: S,
    actions: Iterable<A>,
): S {
    var state = initialState
    for (action in actions) {
        state = reduce(state, action).state
    }
    return state
}
