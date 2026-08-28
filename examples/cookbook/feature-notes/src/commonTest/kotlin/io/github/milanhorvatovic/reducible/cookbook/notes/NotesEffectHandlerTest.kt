package io.github.milanhorvatovic.reducible.cookbook.notes

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeRepository(
    private val loadResult: Result<List<Note>>,
    private val saveResult: Result<Note> = Result.failure(IllegalStateException("unused")),
) : NotesRepository {
    override suspend fun loadNotes(): List<Note> = loadResult.getOrThrow()

    override suspend fun saveNote(text: String): Note = saveResult.getOrThrow()
}

class NotesEffectHandlerTest {
    private var debounceWindows = 0

    private suspend fun collect(
        repository: NotesRepository,
        effect: NotesEffect,
    ): List<NotesAction> {
        val actions = mutableListOf<NotesAction>()
        val handler = notesEffectHandler(repository, hintDebounceWindow = { debounceWindows++ })
        handler.handle(effect) { action -> actions += action }
        return actions
    }

    @Test
    fun successful_load_sends_loaded() =
        runTest {
            val notes = listOf(Note("1", "Use less salt"))

            val actions = collect(FakeRepository(Result.success(notes)), NotesEffect.LoadNotes)

            assertEquals(listOf<NotesAction>(NotesAction.Loaded(notes)), actions)
        }

    @Test
    fun expected_load_failure_becomes_typed_action() =
        runTest {
            val repository =
                FakeRepository(
                    Result.failure(NotesRepositoryException(NotesError.Offline)),
                )

            val actions = collect(repository, NotesEffect.LoadNotes)

            assertEquals(listOf<NotesAction>(NotesAction.LoadFailed(NotesError.Offline)), actions)
        }

    @Test
    fun expected_save_failure_becomes_typed_action() =
        runTest {
            val repository =
                FakeRepository(
                    loadResult = Result.success(emptyList()),
                    saveResult = Result.failure(NotesRepositoryException(NotesError.Unexpected("disk"))),
                )

            val actions = collect(repository, NotesEffect.SaveNote("text"))

            assertEquals(
                listOf<NotesAction>(NotesAction.SaveFailed(NotesError.Unexpected("disk"))),
                actions,
            )
        }

    @Test
    fun hint_computation_suspends_in_the_debounce_window_first() =
        runTest {
            val repository = FakeRepository(Result.success(emptyList()))

            val actions = collect(repository, NotesEffect.ComputeHint("draft"))

            assertEquals(1, debounceWindows)
            assertEquals(listOf<NotesAction>(NotesAction.DraftHint("draft")), actions)
        }
}
