package io.github.milanhorvatovic.reducible.cookbook.notes

import io.github.milanhorvatovic.reducible.test.assertOptionalLaws
import io.github.milanhorvatovic.reducible.test.assertPrismLaws
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

private val note = Note("1", "Use less salt")
private val editing = NotesState.Content(persistentListOf(note), EditorState("draft"))

class NotesOpticsLawsTest {
    @Test
    fun hand_written_editor_optional_is_lawful() {
        editorOptional.assertOptionalLaws(
            present = editing,
            replacement = EditorState("replaced"),
            NotesState.Content(persistentListOf(note), editor = null),
            NotesState.Loading,
            NotesState.Submitting(persistentListOf(note), "draft"),
            NotesState.LoadFailed(NotesError.Offline),
        )
    }

    @Test
    fun content_prism_is_lawful() {
        contentPrism.assertPrismLaws(
            matching = editing,
            value = NotesState.Content(persistentListOf(), editor = null),
            NotesState.Loading,
            NotesState.LoadFailed(NotesError.Offline),
        )
    }

    @Test
    fun editor_action_prism_is_lawful() {
        editorActionPrism.assertPrismLaws(
            matching = NotesAction.Editor(EditorAction.Save),
            value = EditorAction.DraftChanged("draft"),
            NotesAction.Started,
            NotesAction.Retry,
            NotesAction.AddClicked,
        )
    }
}
