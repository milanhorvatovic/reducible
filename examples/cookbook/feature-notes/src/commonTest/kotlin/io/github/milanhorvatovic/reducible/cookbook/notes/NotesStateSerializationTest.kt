package io.github.milanhorvatovic.reducible.cookbook.notes

import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class NotesStateSerializationTest {
    private fun roundTrip(state: NotesState): NotesState =
        Json.decodeFromString(NotesState.serializer(), Json.encodeToString(NotesState.serializer(), state))

    @Test
    fun every_state_case_survives_a_round_trip() {
        val states =
            listOf(
                NotesState.Loading,
                NotesState.Content(persistentListOf(Note("1", "Use less salt")), EditorState("draft")),
                NotesState.Content(persistentListOf(), editor = null),
                NotesState.Submitting(persistentListOf(Note("1", "Use less salt")), "draft"),
                NotesState.LoadFailed(NotesError.Unexpected("boom")),
            )

        for (state in states) {
            assertEquals(state, roundTrip(state))
        }
    }
}
