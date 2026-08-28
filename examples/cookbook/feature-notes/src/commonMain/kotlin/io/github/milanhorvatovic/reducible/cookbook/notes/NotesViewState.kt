package io.github.milanhorvatovic.reducible.cookbook.notes

import kotlinx.collections.immutable.PersistentList

/**
 * The notes screen as it renders: the feature's four states collapse to three, and fields the
 * screen never shows (the submitting draft, the hint counter) are gone, so a reduction that
 * touches only those never invalidates the UI. Errors stay typed — wording is the
 * platform's, localized there.
 */
public sealed interface NotesViewState {
    /** Loading or saving: progress only, no input accepted. */
    public data class Busy(
        public val saving: Boolean,
    ) : NotesViewState

    public data class Notes(
        public val notes: PersistentList<Note>,
        public val editor: EditorViewState?,
        public val canAdd: Boolean,
    ) : NotesViewState

    public data class Failed(
        public val error: NotesError,
    ) : NotesViewState
}

public data class EditorViewState(
    public val draft: String,
    public val error: NotesError?,
    public val hint: String?,
)

public fun notesViewState(state: NotesState): NotesViewState =
    when (state) {
        NotesState.Loading -> {
            NotesViewState.Busy(saving = false)
        }

        is NotesState.Submitting -> {
            NotesViewState.Busy(saving = true)
        }

        is NotesState.Content -> {
            NotesViewState.Notes(
                notes = state.notes,
                editor = state.editor?.let { editor -> EditorViewState(editor.draft, editor.error, editor.hint) },
                canAdd = state.editor == null,
            )
        }

        is NotesState.LoadFailed -> {
            NotesViewState.Failed(state.error)
        }
    }
