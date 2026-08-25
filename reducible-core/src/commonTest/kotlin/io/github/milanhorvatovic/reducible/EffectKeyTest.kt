package io.github.milanhorvatovic.reducible

import kotlin.test.Test
import kotlin.test.assertEquals

private data object PlainKey : EffectKey

private data object WriteKey : EffectKey {
    override val policy: KeyPolicy
        get() = KeyPolicy.Ordered
}

class EffectKeyTest {
    @Test
    fun a_key_is_cancel_previous_unless_it_says_otherwise() {
        assertEquals(KeyPolicy.CancelPrevious, PlainKey.policy)
        assertEquals(KeyPolicy.Ordered, WriteKey.policy)
    }

    @Test
    fun an_identified_key_keeps_the_row_key_policy() {
        assertEquals(KeyPolicy.CancelPrevious, IdentifiedEffectKey("row", PlainKey).policy)
        assertEquals(KeyPolicy.Ordered, IdentifiedEffectKey("row", WriteKey).policy)
    }
}
