package io.github.milanhorvatovic.reducible.cookbook.notes

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

private val note = Note("1", "Use less salt")

class NotesViewStateTest {
    @Test
    fun loading_and_submitting_both_render_as_busy() {
        assertEquals(NotesViewState.Busy(saving = false), notesViewState(NotesState.Loading))
        assertEquals(
            NotesViewState.Busy(saving = true),
            notesViewState(NotesState.Submitting(persistentListOf(note), draft = "d")),
        )
    }

    @Test
    fun content_without_editor_can_add() {
        assertEquals(
            NotesViewState.Notes(persistentListOf(note), editor = null, canAdd = true),
            notesViewState(NotesState.Content(persistentListOf(note), editor = null)),
        )
    }

    @Test
    fun open_editor_is_projected_without_its_hint_counter() {
        val editor = EditorState("draft", NotesError.Offline, hint = "Hint 2: 5 characters", hintCount = 2)

        assertEquals(
            NotesViewState.Notes(
                persistentListOf(note),
                editor = EditorViewState("draft", NotesError.Offline, hint = "Hint 2: 5 characters"),
                canAdd = false,
            ),
            notesViewState(NotesState.Content(persistentListOf(note), editor)),
        )
    }

    @Test
    fun hint_counter_alone_does_not_change_the_projection() {
        val once = EditorState("draft", hint = "Hint 1: 5 characters", hintCount = 1)
        val twice = once.copy(hintCount = 2)

        assertEquals(
            notesViewState(NotesState.Content(persistentListOf(note), once)),
            notesViewState(NotesState.Content(persistentListOf(note), twice)),
        )
    }

    @Test
    fun load_failure_renders_as_failed() {
        assertEquals(
            NotesViewState.Failed(NotesError.Offline),
            notesViewState(NotesState.LoadFailed(NotesError.Offline)),
        )
    }
}
