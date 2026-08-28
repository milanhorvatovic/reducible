package io.github.milanhorvatovic.reducible.cookbook.shared

import io.github.milanhorvatovic.reducible.cookbook.notes.Note
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesError
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesRepository
import io.github.milanhorvatovic.reducible.cookbook.notes.NotesRepositoryException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Demo-only cooking notes: in-memory with simulated latency, one instance per recipe.
 * [failureInjected] is read on every call so the error and retry paths show from the UI.
 * Safe under the store's sequential effect usage only.
 */
public class InMemoryNotesRepository(
    seed: List<Note> = DEFAULT_SEED,
    private val failureInjected: () -> Boolean = { false },
    private val simulatedLatency: Duration = 600.milliseconds,
) : NotesRepository {
    private val notes = seed.toMutableList()
    private var nextId = seed.size + 1

    override suspend fun loadNotes(): List<Note> {
        delay(simulatedLatency)
        if (failureInjected()) {
            throw NotesRepositoryException(NotesError.Offline)
        }
        return notes.toList()
    }

    override suspend fun saveNote(text: String): Note {
        delay(simulatedLatency)
        if (failureInjected()) {
            throw NotesRepositoryException(NotesError.Offline)
        }
        val note = Note(id = nextId++.toString(), text = text)
        notes += note
        return note
    }

    private companion object {
        val DEFAULT_SEED =
            listOf(
                Note("1", "Use less salt next time"),
                Note("2", "Doubles well for guests"),
            )
    }
}
