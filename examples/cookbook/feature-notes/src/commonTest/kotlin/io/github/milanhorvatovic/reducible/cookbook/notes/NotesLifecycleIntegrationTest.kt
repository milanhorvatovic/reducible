package io.github.milanhorvatovic.reducible.cookbook.notes

import io.github.milanhorvatovic.reducible.test.testStore
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

private val seed = Note("1", "Use less salt")
private val saved = Note("2", "Doubles well for guests")

private object LifecycleRepository : NotesRepository {
    override suspend fun loadNotes(): List<Note> = listOf(seed)

    override suspend fun saveNote(text: String): Note = Note("2", text)
}

// The whole screen lifecycle — load, edit with a debounced hint, submit — against the real
// reducer, real handler, and a fake repository, deterministic under virtual time. The
// standing template for feature integration tests.
class NotesLifecycleIntegrationTest {
    @Test
    fun full_lifecycle_from_started_to_saved() =
        runTest {
            val store =
                testStore(
                    initialState = NotesState.Loading,
                    reducer = notesReducer,
                    handler = notesEffectHandler(LifecycleRepository, hintDebounceWindow = { delay(400) }),
                )

            store.send(NotesAction.Started)
            advanceUntilIdle()
            store
                .expectAction(NotesAction.Started, resulting = NotesState.Loading)
                .expectAction(
                    NotesAction.Loaded(listOf(seed)),
                    resulting = NotesState.Content(persistentListOf(seed), editor = null),
                )

            store.send(NotesAction.AddClicked)
            runCurrent()
            store.expectAction(
                NotesAction.AddClicked,
                resulting = NotesState.Content(persistentListOf(seed), EditorState()),
            )

            store.send(NotesAction.Editor(EditorAction.DraftChanged("Doubles well for guests")))
            advanceUntilIdle()
            store
                .expectAction(
                    NotesAction.Editor(EditorAction.DraftChanged("Doubles well for guests")),
                    resulting = NotesState.Content(persistentListOf(seed), EditorState("Doubles well for guests")),
                ).expectAction(
                    NotesAction.DraftHint("Doubles well for guests"),
                    resulting =
                        NotesState.Content(
                            persistentListOf(seed),
                            EditorState("Doubles well for guests", hint = "Hint 1: 23 characters", hintCount = 1),
                        ),
                )

            store.send(NotesAction.Editor(EditorAction.Save))
            advanceUntilIdle()
            store
                .expectAction(
                    NotesAction.Editor(EditorAction.Save),
                    resulting = NotesState.Submitting(persistentListOf(seed), "Doubles well for guests"),
                ).expectAction(
                    NotesAction.Saved(saved),
                    resulting = NotesState.Content(persistentListOf(seed, saved), editor = null),
                )

            store.finish()
        }
}
