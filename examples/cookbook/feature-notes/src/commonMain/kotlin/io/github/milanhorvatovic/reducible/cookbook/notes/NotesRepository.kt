package io.github.milanhorvatovic.reducible.cookbook.notes

import io.github.milanhorvatovic.reducible.EffectHandler

/** The feature's data boundary; implementations are wired by the consuming app. */
public interface NotesRepository {
    /** @throws NotesRepositoryException for expected failures. */
    public suspend fun loadNotes(): List<Note>

    /** @throws NotesRepositoryException for expected failures. */
    public suspend fun saveNote(text: String): Note
}

public class NotesRepositoryException(
    public val error: NotesError,
) : Exception("Notes repository failed: $error")

/**
 * Handlers-total: expected repository failures become typed failure actions; anything else
 * escaping here is a defect and reaches the store's defect handler.
 *
 * [hintDebounceWindow] is the suspension that makes [NotesEffect.ComputeHint] debounced —
 * supplied by the wiring (features carry no coroutines, so the delay cannot live here).
 */
public fun notesEffectHandler(
    repository: NotesRepository,
    hintDebounceWindow: suspend () -> Unit,
): EffectHandler<NotesEffect, NotesAction> =
    EffectHandler { effect, send ->
        when (effect) {
            NotesEffect.LoadNotes -> {
                try {
                    send(NotesAction.Loaded(repository.loadNotes()))
                } catch (failure: NotesRepositoryException) {
                    send(NotesAction.LoadFailed(failure.error))
                }
            }

            is NotesEffect.SaveNote -> {
                try {
                    send(NotesAction.Saved(repository.saveNote(effect.text)))
                } catch (failure: NotesRepositoryException) {
                    send(NotesAction.SaveFailed(failure.error))
                }
            }

            // The leading suspension is the debounce: a newer ComputeHint cancels this one
            // while it waits inside the window.
            is NotesEffect.ComputeHint -> {
                hintDebounceWindow()
                send(NotesAction.DraftHint(effect.text))
            }
        }
    }
