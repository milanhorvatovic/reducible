package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.cookbook.settings.Units
import kotlin.test.Test
import kotlin.test.assertEquals

class QuantityFormattingTest {
    @Test
    fun amounts_scale_with_servings() {
        val tomatoes = Ingredient("Tomatoes", 800.0, Measure.Gram)

        assertEquals("800 g", formatAmount(tomatoes, servings = 4, baseServings = 4, Units.Metric))
        assertEquals("1.2 kg", formatAmount(tomatoes, servings = 6, baseServings = 4, Units.Metric))
        assertEquals("200 g", formatAmount(tomatoes, servings = 1, baseServings = 4, Units.Metric))
    }

    @Test
    fun imperial_converts_weight_and_volume_and_leaves_spoons_and_pieces_alone() {
        assertEquals("7.1 oz", formatAmount(Ingredient("Spinach", 200.0, Measure.Gram), 4, 4, Units.Imperial))
        assertEquals("1.8 lb", formatAmount(Ingredient("Tomatoes", 800.0, Measure.Gram), 4, 4, Units.Imperial))
        assertEquals("6.8 fl oz", formatAmount(Ingredient("Milk", 200.0, Measure.Milliliter), 4, 4, Units.Imperial))
        assertEquals("2.1 cups", formatAmount(Ingredient("Stock", 500.0, Measure.Milliliter), 4, 4, Units.Imperial))
        assertEquals("2 tbsp", formatAmount(Ingredient("Oil", 2.0, Measure.Tablespoon), 4, 4, Units.Imperial))
        assertEquals("1.5", formatAmount(Ingredient("Egg", 1.0, Measure.Piece), 6, 4, Units.Imperial))
    }

    @Test
    fun metric_volume_rolls_over_to_litres() {
        assertEquals("500 ml", formatAmount(Ingredient("Stock", 500.0, Measure.Milliliter), 4, 4, Units.Metric))
        assertEquals("1 l", formatAmount(Ingredient("Stock", 500.0, Measure.Milliliter), 8, 4, Units.Metric))
    }

    @Test
    fun numbers_print_one_decimal_at_most() {
        assertEquals("800", formatNumber(800.0))
        assertEquals("1.5", formatNumber(1.5))
        assertEquals("0.3", formatNumber(0.333))
        assertEquals("2", formatNumber(1.96))
    }
}
