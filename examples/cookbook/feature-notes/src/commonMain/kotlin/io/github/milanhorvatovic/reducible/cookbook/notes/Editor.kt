package io.github.milanhorvatovic.reducible.cookbook.notes

import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.only
import kotlinx.serialization.Serializable

/** Child feature: the note editor shown inside [NotesState.Content]. */
@Serializable
public data class EditorState(
    public val draft: String = "",
    public val error: NotesError? = null,
    public val hint: String? = null,
    public val hintCount: Int = 0,
)

public sealed interface EditorAction {
    public data class DraftChanged(
        public val text: String,
    ) : EditorAction

    /** Handled by the parent, which owns submission; the editor itself only clears errors. */
    public data object Save : EditorAction
}

public val editorReducer: Reducer<EditorState, EditorAction, Nothing> =
    Reducer { state, action ->
        when (action) {
            is EditorAction.DraftChanged -> {
                state.copy(draft = action.text, error = null, hint = null).only()
            }

            EditorAction.Save -> {
                state.only()
            }
        }
    }
