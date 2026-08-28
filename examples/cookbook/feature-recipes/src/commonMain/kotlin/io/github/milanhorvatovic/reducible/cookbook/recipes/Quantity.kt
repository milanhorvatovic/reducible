package io.github.milanhorvatovic.reducible.cookbook.recipes

import io.github.milanhorvatovic.reducible.cookbook.settings.Units
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The amount of [ingredient] for [servings] portions of a recipe written for [baseServings],
 * in the [units] the reader cooks in. Pure, so the view projection derives it on every read
 * and the state stores neither scaled nor converted quantities.
 */
public fun formatAmount(
    ingredient: Ingredient,
    servings: Int,
    baseServings: Int,
    units: Units,
): String {
    val quantity = ingredient.quantity * servings / baseServings
    return when (ingredient.measure) {
        Measure.Piece -> {
            formatNumber(quantity)
        }

        Measure.Teaspoon -> {
            "${formatNumber(quantity)} tsp"
        }

        Measure.Tablespoon -> {
            "${formatNumber(quantity)} tbsp"
        }

        Measure.Gram -> {
            when (units) {
                Units.Metric -> {
                    if (quantity >= 1000) {
                        "${formatNumber(quantity / 1000)} kg"
                    } else {
                        "${formatNumber(quantity)} g"
                    }
                }

                Units.Imperial -> {
                    val ounces = quantity / GRAMS_PER_OUNCE
                    if (ounces >= 16) {
                        "${formatNumber(ounces / 16)} lb"
                    } else {
                        "${formatNumber(ounces)} oz"
                    }
                }
            }
        }

        Measure.Milliliter -> {
            when (units) {
                Units.Metric -> {
                    if (quantity >= 1000) {
                        "${formatNumber(quantity / 1000)} l"
                    } else {
                        "${formatNumber(quantity)} ml"
                    }
                }

                Units.Imperial -> {
                    val fluidOunces = quantity / MILLILITERS_PER_FLUID_OUNCE
                    if (fluidOunces >= 8) {
                        "${formatNumber(fluidOunces / 8)} cups"
                    } else {
                        "${formatNumber(fluidOunces)} fl oz"
                    }
                }
            }
        }
    }
}

/** One decimal at most, none when whole: 800, 1.5, 0.3. */
internal fun formatNumber(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == floor(rounded)) {
        rounded.toInt().toString()
    } else {
        rounded.toString()
    }
}

private const val GRAMS_PER_OUNCE = 28.3495
private const val MILLILITERS_PER_FLUID_OUNCE = 29.5735
