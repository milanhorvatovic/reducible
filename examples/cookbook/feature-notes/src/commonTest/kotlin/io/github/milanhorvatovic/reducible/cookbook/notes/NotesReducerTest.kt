package io.github.milanhorvatovic.reducible.cookbook.notes

import io.github.milanhorvatovic.reducible.EffectEnvelope
import io.github.milanhorvatovic.reducible.EffectScope
import io.github.milanhorvatovic.reducible.test.given
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

private val note = Note("1", "Use less salt")
private val newNote = Note("2", "Try with basil")

class NotesReducerTest {
    @Test
    fun loading_flow_reaches_content() {
        notesReducer
            .given(NotesState.Loading)
            .on(NotesAction.Started)
            .expect(NotesState.Loading)
            .expectEffects(NotesEffect.LoadNotes)
            .andOn(NotesAction.Loaded(listOf(note)))
            .expect(NotesState.Content(persistentListOf(note), editor = null))
            .expectNoEffects()
    }

    @Test
    fun load_failure_reaches_error_and_retry_reloads() {
        notesReducer
            .given(NotesState.Loading)
            .on(NotesAction.LoadFailed(NotesError.Offline))
            .expect(NotesState.LoadFailed(NotesError.Offline))
            .expectNoEffects()
            .andOn(NotesAction.Retry)
            .expect(NotesState.Loading)
            .expectEffects(NotesEffect.LoadNotes)
    }

    @Test
    fun editor_flow_submits_and_appends() {
        notesReducer
            .given(NotesState.Content(persistentListOf(note), editor = null))
            .on(NotesAction.AddClicked)
            .expect(NotesState.Content(persistentListOf(note), EditorState()))
            .andOn(NotesAction.Editor(EditorAction.DraftChanged("Try with basil")))
            .expect(NotesState.Content(persistentListOf(note), EditorState("Try with basil")))
            .expectEnvelopes(
                EffectEnvelope(
                    NotesEffect.ComputeHint("Try with basil"),
                    EffectScope.StateScoped,
                    HintDebounce,
                ),
            ).andOn(NotesAction.Editor(EditorAction.Save))
            .expect(NotesState.Submitting(persistentListOf(note), "Try with basil"))
            .expectEffects(NotesEffect.SaveNote("Try with basil"))
            .andOn(NotesAction.Saved(newNote))
            .expect(NotesState.Content(persistentListOf(note, newNote), editor = null))
            .expectNoEffects()
    }

    @Test
    fun save_with_blank_draft_is_ignored() {
        val editing = NotesState.Content(persistentListOf(note), EditorState("   "))

        notesReducer
            .given(editing)
            .on(NotesAction.Editor(EditorAction.Save))
            .expect(editing)
            .expectNoEffects()
    }

    @Test
    fun save_failure_reopens_editor_with_draft_and_error() {
        notesReducer
            .given(NotesState.Submitting(persistentListOf(note), "draft"))
            .on(NotesAction.SaveFailed(NotesError.Offline))
            .expect(
                NotesState.Content(
                    persistentListOf(note),
                    EditorState("draft", NotesError.Offline),
                ),
            ).expectNoEffects()
    }

    @Test
    fun draft_change_clears_previous_error_and_requests_a_debounced_hint() {
        notesReducer
            .given(
                NotesState.Content(persistentListOf(note), EditorState("draft", NotesError.Offline)),
            ).on(NotesAction.Editor(EditorAction.DraftChanged("draft!")))
            .expect(NotesState.Content(persistentListOf(note), EditorState("draft!")))
            .expectEffects(NotesEffect.ComputeHint("draft!"))
    }

    @Test
    fun hint_applies_only_while_the_draft_still_matches() {
        val editing = NotesState.Content(persistentListOf(note), EditorState("current"))

        notesReducer
            .given(editing)
            .on(NotesAction.DraftHint("current"))
            .expect(
                NotesState.Content(
                    persistentListOf(note),
                    EditorState("current", hint = "Hint 1: 7 characters", hintCount = 1),
                ),
            ).expectNoEffects()
            .andOn(NotesAction.DraftHint("stale"))
            .expect(
                NotesState.Content(
                    persistentListOf(note),
                    EditorState("current", hint = "Hint 1: 7 characters", hintCount = 1),
                ),
            ).expectNoEffects()
    }

    @Test
    fun hint_counter_increments_per_applied_hint() {
        notesReducer
            .given(
                NotesState.Content(
                    persistentListOf(note),
                    EditorState("abc", hint = "Hint 3: 3 characters", hintCount = 3),
                ),
            ).on(NotesAction.DraftHint("abc"))
            .expectState { state ->
                state is NotesState.Content && state.editor ==
                    EditorState(
                        "abc",
                        hint = "Hint 4: 3 characters",
                        hintCount = 4,
                    )
            }
    }

    @Test
    fun editor_actions_are_dropped_while_editor_absent() {
        val content = NotesState.Content(persistentListOf(note), editor = null)

        notesReducer
            .given(content)
            .on(NotesAction.Editor(EditorAction.DraftChanged("late")))
            .expect(content)
            .expectNoEffects()
    }

    // Process death: Started is sent unconditionally on creation, the reducer is state-aware.
    @Test
    fun started_is_state_aware_after_restoration() {
        notesReducer
            .given(NotesState.Content(persistentListOf(note), editor = null))
            .on(NotesAction.Started)
            .expect(NotesState.Content(persistentListOf(note), editor = null))
            .expectNoEffects()

        notesReducer
            .given(NotesState.Submitting(persistentListOf(note), "draft"))
            .on(NotesAction.Started)
            .expect(NotesState.Content(persistentListOf(note), editor = null))
            .expectNoEffects()

        notesReducer
            .given(NotesState.LoadFailed(NotesError.Offline))
            .on(NotesAction.Started)
            .expect(NotesState.Loading)
            .expectEffects(NotesEffect.LoadNotes)
    }
}
