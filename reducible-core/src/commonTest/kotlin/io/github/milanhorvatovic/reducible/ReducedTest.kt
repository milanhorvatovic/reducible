package io.github.milanhorvatovic.reducible

import kotlin.test.Test
import kotlin.test.assertEquals

class ReducedTest {
    @Test
    fun only_requests_no_effects() {
        val reduced: Reduced<String, String, Int> = "state".only()
        assertEquals("state", reduced.state)
        assertEquals(emptyList(), reduced.effects)
        assertEquals(emptyList(), reduced.followUps)
    }

    @Test
    fun withEffect_defaults_to_state_scoped() {
        val reduced: Reduced<String, String, Int> = "state".withEffect(7)
        assertEquals(listOf(EffectEnvelope(7, EffectScope.StateScoped)), reduced.effects)
    }

    @Test
    fun withEffect_carries_explicit_scope() {
        val reduced: Reduced<String, String, Int> = "state".withEffect(7, EffectScope.Free)
        assertEquals(listOf(EffectEnvelope(7, EffectScope.Free)), reduced.effects)
    }

    @Test
    fun withEffects_preserves_order() {
        val reduced: Reduced<String, String, Int> = "state".withEffects(1, 2, 3)
        assertEquals(listOf(1, 2, 3), reduced.effects.map { envelope -> envelope.effect })
    }

    @Test
    fun andSend_records_follow_ups_in_order_on_a_state_and_on_a_reduction() {
        val bare: Reduced<String, String, Int> = "state".andSend("next")
        assertEquals(listOf("next"), bare.followUps)
        assertEquals(emptyList(), bare.effects)

        val withEffect: Reduced<String, String, Int> = "state".withEffect(7).andSend("a").andSend("b", "c")
        assertEquals(listOf(7), withEffect.effects.map { envelope -> envelope.effect })
        assertEquals(listOf("a", "b", "c"), withEffect.followUps)
    }
}
