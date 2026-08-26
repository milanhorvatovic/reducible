package io.github.milanhorvatovic.reducible.optics.arrow

import arrow.core.Option
import io.github.milanhorvatovic.reducible.andThen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import arrow.optics.Lens as ArrowLens
import arrow.optics.Optional as ArrowOptional
import arrow.optics.Prism as ArrowPrism

private data class Box(
    val label: String,
)

private sealed interface Shipment {
    data object Pending : Shipment

    data class Packed(
        val box: Box,
    ) : Shipment
}

private val arrowPacked: ArrowPrism<Shipment, Shipment.Packed> =
    ArrowPrism(
        getOption = { shipment -> Option.fromNullable(shipment as? Shipment.Packed) },
        reverseGet = { packed -> packed },
    )

private val arrowBox: ArrowLens<Shipment.Packed, Box> =
    ArrowLens(
        get = { packed -> packed.box },
        set = { packed, box -> packed.copy(box = box) },
    )

private val arrowBoxInShipment: ArrowOptional<Shipment, Box> =
    ArrowOptional(
        getOption = { shipment -> Option.fromNullable((shipment as? Shipment.Packed)?.box) },
        set = { shipment, box ->
            if (shipment is Shipment.Packed) {
                shipment.copy(box = box)
            } else {
                shipment
            }
        },
    )

class ArrowAdaptersTest {
    @Test
    fun adapted_lens_and_prism_compose_with_core_optics() {
        val optional = arrowPacked.asCorePrism() andThen arrowBox.asCoreLens()
        val packed: Shipment = Shipment.Packed(Box("a"))

        assertEquals(Box("a"), optional.getOrNull(packed))
        assertEquals(Shipment.Packed(Box("b")), optional.set(packed, Box("b")))
        assertNull(optional.getOrNull(Shipment.Pending))
        assertSame(Shipment.Pending, optional.set(Shipment.Pending, Box("b")))
    }

    @Test
    fun adapted_arrow_optional_matches_core_semantics() {
        val viaArrow = arrowBoxInShipment.asCoreOptional()
        val packed: Shipment = Shipment.Packed(Box("a"))

        assertEquals(Box("a"), viaArrow.getOrNull(packed))
        assertEquals(Shipment.Packed(Box("b")), viaArrow.set(packed, Box("b")))
        assertSame(Shipment.Pending, viaArrow.set(Shipment.Pending, Box("b")))
    }
}
