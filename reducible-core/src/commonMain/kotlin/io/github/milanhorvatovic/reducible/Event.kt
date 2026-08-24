package io.github.milanhorvatovic.reducible

/**
 * Marks an action a reducer emits for whoever holds the store, never for the state: a
 * screen's navigation intent, a child's word to its parent. A reducer emits one as a
 * follow-up (`state.andSend(RecipesEvent.OpenRecipe(id))`) and reduces it as a no-op; the
 * store publishes it right after the state that produced it. An event is therefore also a
 * reduced action — logged, recorded, replayable — and a parent composing the child sees it
 * embedded like any other child action, free to translate or swallow it. Only an action the
 * store's own action type marks leaves the store; a child's event wrapped in a parent action
 * does not.
 */
public interface Event
