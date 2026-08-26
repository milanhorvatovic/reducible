package io.github.milanhorvatovic.reducible.test

import io.github.milanhorvatovic.reducible.andThen
import io.github.milanhorvatovic.reducible.casePrism
import io.github.milanhorvatovic.reducible.lens
import io.github.milanhorvatovic.reducible.optional
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private data class Editor(
    val draft: String,
)

private sealed interface Screen {
    data object Empty : Screen

    data class Open(
        val title: String,
        val editor: Editor?,
    ) : Screen
}

private val openPrism = casePrism<Screen, Screen.Open>()

private val titleLens =
    lens<Screen.Open, String>(
        get = { source -> source.title },
        set = { open, title -> open.copy(title = title) },
    )

private val lawfulEditor =
    openPrism andThen
        optional<Screen.Open, Editor>(
            getOrNull = { source -> source.editor },
            set = { open, editor ->
                if (open.editor == null) {
                    open
                } else {
                    open.copy(editor = editor)
                }
            },
        )

// The exact defect the C1 comparison caught: set inserts into an absent focus.
private val insertingEditor =
    openPrism andThen
        optional<Screen.Open, Editor>(
            getOrNull = { source -> source.editor },
            set = { open, editor -> open.copy(editor = editor) },
        )

class OpticAssertionsTest {
    private val editing: Screen = Screen.Open("t", Editor("draft"))

    @Test
    fun lawful_optics_pass() {
        titleLens.assertLensLaws(Screen.Open("t", null), replacement = "new")
        openPrism.assertPrismLaws(
            matching = editing,
            value = Screen.Open("other", null),
            Screen.Empty,
        )
        lawfulEditor.assertOptionalLaws(
            present = editing,
            replacement = Editor("replaced"),
            Screen.Open("t", editor = null),
            Screen.Empty,
        )
    }

    @Test
    fun set_into_absent_focus_fails_the_absence_law() {
        val failure =
            assertFailsWith<AssertionError> {
                insertingEditor.assertOptionalLaws(
                    present = editing,
                    replacement = Editor("replaced"),
                    Screen.Open("t", editor = null),
                )
            }
        assertTrue("no-op" in failure.message.orEmpty())
    }

    @Test
    fun lens_ignoring_the_written_value_fails_set_get() {
        val stubborn =
            lens<Screen.Open, String>(
                get = { source -> source.title },
                set = { open, _ -> open },
            )

        val failure =
            assertFailsWith<AssertionError> {
                stubborn.assertLensLaws(Screen.Open("t", null), replacement = "new")
            }
        assertTrue("set-get" in failure.message.orEmpty())
    }

    @Test
    fun prism_that_never_matches_its_own_embedding_fails_embed_get() {
        val broken =
            io.github.milanhorvatovic.reducible.prism<Screen, Screen.Open>(
                getOrNull = { null },
                embed = { value -> value },
            )

        assertFailsWith<AssertionError> {
            broken.assertPrismLaws(matching = editing, value = Screen.Open("x", null))
        }
    }
}
