package io.github.milanhorvatovic.reducible.android

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.fail

class RestoredStateTest {
    private val serializer = ListSerializer(String.serializer())

    @Test
    fun nothing_saved_is_a_fresh_start_without_a_failure() {
        assertNull(restoredState(null, serializer) { fail("nothing to decode, nothing to report") })
    }

    @Test
    fun saved_state_is_decoded() {
        assertEquals(listOf("a", "b"), restoredState("""["a","b"]""", serializer) { failure -> fail("readable input: $failure") })
    }

    @Test
    fun saved_state_of_another_shape_is_a_fresh_start_that_reports_why() {
        val failures = mutableListOf<Throwable>()

        val restored = restoredState("[1, 2]", serializer, failures::add)

        assertNull(restored)
        assertIs<SerializationException>(failures.single())
    }

    @Test
    fun unreadable_saved_state_is_a_fresh_start_that_reports_why() {
        val failures = mutableListOf<Throwable>()

        val restored = restoredState("not json", serializer, failures::add)

        assertNull(restored)
        assertIs<SerializationException>(failures.single())
    }
}
