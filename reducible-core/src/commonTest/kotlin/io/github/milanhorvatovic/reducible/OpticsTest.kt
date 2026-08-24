package io.github.milanhorvatovic.reducible

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

private data class Box(
    val label: String,
    val weight: Int,
)

private sealed interface Shipment {
    data object Pending : Shipment

    data class Packed(
        val box: Box,
    ) : Shipment
}

private val boxLens =
    lens<Shipment.Packed, Box>(
        get = { source -> source.box },
        set = { packed, box -> packed.copy(box = box) },
    )

private val packedPrism = casePrism<Shipment, Shipment.Packed>()

class OpticsTest {
    @Test
    fun lens_gets_and_sets() {
        val packed = Shipment.Packed(Box("a", 1))
        assertEquals(Box("a", 1), boxLens.get(packed))
        assertEquals(Shipment.Packed(Box("a", 5)), boxLens.set(packed, Box("a", 5)))
    }

    @Test
    fun case_prism_matches_only_its_case() {
        val packed = Shipment.Packed(Box("a", 1))
        assertEquals(packed, packedPrism.getOrNull(packed))
        assertNull(packedPrism.getOrNull(Shipment.Pending))
    }

    @Test
    fun prism_set_is_noop_when_case_absent() {
        val other: Shipment = Shipment.Pending
        assertSame(other, packedPrism.set(other, Shipment.Packed(Box("a", 1))))
    }

    @Test
    fun composed_optional_reaches_through_prism_and_lens() {
        val optional = packedPrism andThen boxLens
        val packed: Shipment = Shipment.Packed(Box("a", 1))

        assertEquals(Box("a", 1), optional.getOrNull(packed))
        assertEquals(Shipment.Packed(Box("b", 1)), optional.set(packed, Box("b", 1)))
        assertNull(optional.getOrNull(Shipment.Pending))
        assertSame(Shipment.Pending, optional.set(Shipment.Pending, Box("b", 1)))
    }
}
