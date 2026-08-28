package io.github.milanhorvatovic.reducible.cookbook.notes

import io.github.milanhorvatovic.reducible.test.testStore
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private val note = Note("1", "Use less salt")

private object UnusedRepository : NotesRepository {
    override suspend fun loadNotes(): List<Note> = error("not exercised")

    override suspend fun saveNote(text: String): Note = error("not exercised")
}

// The debounced hint verified against the REAL reducer and handler under virtual time —
// the integration test the live app runs demonstrated by screenshot before TestStore existed.
class NotesDebounceIntegrationTest {
    @Test
    fun rapid_draft_changes_compute_exactly_one_hint_for_the_final_text() =
        runTest {
            val store =
                testStore(
                    initialState = NotesState.Content(persistentListOf(note), EditorState()),
                    reducer = notesReducer,
                    handler = notesEffectHandler(UnusedRepository, hintDebounceWindow = { delay(400) }),
                )

            store.send(NotesAction.Editor(EditorAction.DraftChanged("C")))
            advanceTimeBy(100)
            store.send(NotesAction.Editor(EditorAction.DraftChanged("Ch")))
            advanceTimeBy(100)
            store.send(NotesAction.Editor(EditorAction.DraftChanged("Che")))
            advanceUntilIdle()

            store
                .expectAction(NotesAction.Editor(EditorAction.DraftChanged("C")))
                .expectAction(NotesAction.Editor(EditorAction.DraftChanged("Ch")))
                .expectAction(NotesAction.Editor(EditorAction.DraftChanged("Che")))
                // Exactly one hint: the two earlier keyed effects were cancelled in their window.
                .expectAction(
                    NotesAction.DraftHint("Che"),
                    resulting =
                        NotesState.Content(
                            persistentListOf(note),
                            EditorState("Che", hint = "Hint 1: 3 characters", hintCount = 1),
                        ),
                )
            store.finish()
        }
}
