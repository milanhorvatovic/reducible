package io.github.milanhorvatovic.reducible.cookbook.notes

import io.github.milanhorvatovic.reducible.EffectKey
import io.github.milanhorvatovic.reducible.Optional
import io.github.milanhorvatovic.reducible.Prism
import io.github.milanhorvatovic.reducible.Reducer
import io.github.milanhorvatovic.reducible.andThen
import io.github.milanhorvatovic.reducible.casePrism
import io.github.milanhorvatovic.reducible.combine
import io.github.milanhorvatovic.reducible.ifPresent
import io.github.milanhorvatovic.reducible.immutable.PersistentListSerializer
import io.github.milanhorvatovic.reducible.only
import io.github.milanhorvatovic.reducible.optional
import io.github.milanhorvatovic.reducible.prism
import io.github.milanhorvatovic.reducible.withEffect
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.Serializable

@Serializable
public sealed interface NotesState {
    @Serializable
    public data object Loading : NotesState

    @Serializable
    public data class Content(
        @Serializable(with = PersistentListSerializer::class)
        public val notes: PersistentList<Note>,
        public val editor: EditorState?,
    ) : NotesState

    @Serializable
    public data class Submitting(
        @Serializable(with = PersistentListSerializer::class)
        public val notes: PersistentList<Note>,
        public val draft: String,
    ) : NotesState

    @Serializable
    public data class LoadFailed(
        public val error: NotesError,
    ) : NotesState
}

public sealed interface NotesAction {
    /**
     * What the screen may send. Everything else is fed by effects or the scope holder, and
     * a view built over [Ui] cannot reach it.
     */
    public sealed interface Ui : NotesAction

    /** Sent unconditionally on store creation; the reducer is state-aware (process death). */
    public data object Started : NotesAction

    public data class Loaded(
        public val notes: List<Note>,
    ) : NotesAction

    public data class LoadFailed(
        public val error: NotesError,
    ) : NotesAction

    public data object Retry : Ui

    public data object AddClicked : Ui

    public data object EditorDismissed : Ui

    public data class Editor(
        public val action: EditorAction,
    ) : Ui

    public data class Saved(
        public val note: Note,
    ) : NotesAction

    public data class SaveFailed(
        public val error: NotesError,
    ) : NotesAction

    public data class DraftHint(
        public val text: String,
    ) : NotesAction
}

public sealed interface NotesEffect {
    public data object LoadNotes : NotesEffect

    public data class SaveNote(
        public val text: String,
    ) : NotesEffect

    /** Debounced by [HintDebounce]: a newer draft cancels the in-flight computation. */
    public data class ComputeHint(
        public val text: String,
    ) : NotesEffect
}

internal data object HintDebounce : EffectKey

// Hand-written optics: a case prism into Content, then an optional over the nullable editor.
internal val contentPrism = casePrism<NotesState, NotesState.Content>()

internal val editorOptional: Optional<NotesState, EditorState> =
    contentPrism andThen
        optional(
            getOrNull = { state -> state.editor },
            // Lawful Optional: writing into an absent focus is a no-op. Opening the editor is
            // the reducer's explicit decision (AddClicked), never a side effect of a set.
            set = { content, editor ->
                if (content.editor == null) {
                    content
                } else {
                    content.copy(editor = editor)
                }
            },
        )

internal val editorActionPrism: Prism<NotesAction, EditorAction> =
    prism(
        getOrNull = { action -> (action as? NotesAction.Editor)?.action },
        embed = { editorAction -> NotesAction.Editor(editorAction) },
    )

public val notesReducer: Reducer<NotesState, NotesAction, NotesEffect> =
    combine(
        editorReducer.ifPresent(
            state = editorOptional,
            action = editorActionPrism,
            effect = { effect -> effect },
            slot = "editor",
        ),
        Reducer { state, action ->
            when (state) {
                NotesState.Loading -> {
                    when (action) {
                        NotesAction.Started -> {
                            state.withEffect(NotesEffect.LoadNotes)
                        }

                        is NotesAction.Loaded -> {
                            NotesState.Content(action.notes.toPersistentList(), editor = null).only()
                        }

                        is NotesAction.LoadFailed -> {
                            NotesState.LoadFailed(action.error).only()
                        }

                        NotesAction.Retry -> {
                            state.only()
                        }

                        NotesAction.AddClicked -> {
                            state.only()
                        }

                        NotesAction.EditorDismissed -> {
                            state.only()
                        }

                        is NotesAction.Editor -> {
                            state.only()
                        }

                        is NotesAction.Saved -> {
                            state.only()
                        }

                        is NotesAction.SaveFailed -> {
                            state.only()
                        }

                        is NotesAction.DraftHint -> {
                            state.only()
                        }
                    }
                }

                is NotesState.Content -> {
                    when (action) {
                        NotesAction.Started -> {
                            state.only()
                        }

                        NotesAction.AddClicked -> {
                            state.copy(editor = EditorState()).only()
                        }

                        NotesAction.EditorDismissed -> {
                            state.copy(editor = null).only()
                        }

                        is NotesAction.Editor -> {
                            when (action.action) {
                                EditorAction.Save -> {
                                    val draft = state.editor?.draft
                                    if (draft.isNullOrBlank()) {
                                        state.only()
                                    } else {
                                        NotesState
                                            .Submitting(state.notes, draft)
                                            .withEffect(NotesEffect.SaveNote(draft))
                                    }
                                }

                                // The scoped child reducer applied the draft; the parent owns the
                                // debounced hint computation. No editor means the child dropped the
                                // action, so no hint is requested either.
                                is EditorAction.DraftChanged -> {
                                    if (state.editor == null) {
                                        state.only()
                                    } else {
                                        state.withEffect(
                                            NotesEffect.ComputeHint(action.action.text),
                                            key = HintDebounce,
                                        )
                                    }
                                }
                            }
                        }

                        is NotesAction.Loaded -> {
                            state.only()
                        }

                        is NotesAction.LoadFailed -> {
                            state.only()
                        }

                        NotesAction.Retry -> {
                            state.only()
                        }

                        is NotesAction.Saved -> {
                            state.only()
                        }

                        is NotesAction.SaveFailed -> {
                            state.only()
                        }

                        is NotesAction.DraftHint -> {
                            val editor = state.editor
                            if (editor == null || editor.draft != action.text) {
                                // The hint is for a draft the user has already changed — drop it.
                                state.only()
                            } else {
                                val count = editor.hintCount + 1
                                state
                                    .copy(
                                        editor =
                                            editor.copy(
                                                hint = "Hint $count: ${action.text.trim().length} characters",
                                                hintCount = count,
                                            ),
                                    ).only()
                            }
                        }
                    }
                }

                is NotesState.Submitting -> {
                    when (action) {
                        NotesAction.Started -> {
                            NotesState.Content(state.notes, editor = null).only()
                        }

                        is NotesAction.Saved -> {
                            NotesState.Content(state.notes.adding(action.note), editor = null).only()
                        }

                        is NotesAction.SaveFailed -> {
                            NotesState.Content(state.notes, EditorState(state.draft, action.error)).only()
                        }

                        is NotesAction.Loaded -> {
                            state.only()
                        }

                        is NotesAction.LoadFailed -> {
                            state.only()
                        }

                        NotesAction.Retry -> {
                            state.only()
                        }

                        NotesAction.AddClicked -> {
                            state.only()
                        }

                        NotesAction.EditorDismissed -> {
                            state.only()
                        }

                        is NotesAction.Editor -> {
                            state.only()
                        }

                        is NotesAction.DraftHint -> {
                            state.only()
                        }
                    }
                }

                is NotesState.LoadFailed -> {
                    when (action) {
                        NotesAction.Started -> NotesState.Loading.withEffect(NotesEffect.LoadNotes)
                        NotesAction.Retry -> NotesState.Loading.withEffect(NotesEffect.LoadNotes)
                        is NotesAction.Loaded -> state.only()
                        is NotesAction.LoadFailed -> state.only()
                        NotesAction.AddClicked -> state.only()
                        NotesAction.EditorDismissed -> state.only()
                        is NotesAction.Editor -> state.only()
                        is NotesAction.Saved -> state.only()
                        is NotesAction.SaveFailed -> state.only()
                        is NotesAction.DraftHint -> state.only()
                    }
                }
            }
        },
    )
