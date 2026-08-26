package io.github.milanhorvatovic.reducible.optics.arrow

import io.github.milanhorvatovic.reducible.Lens
import io.github.milanhorvatovic.reducible.Optional
import io.github.milanhorvatovic.reducible.Prism
import arrow.optics.Lens as ArrowLens
import arrow.optics.Optional as ArrowOptional
import arrow.optics.Prism as ArrowPrism

/**
 * Adapters from Arrow Optics (typically KSP-generated) to the core optics interfaces, so a
 * feature can choose Arrow codegen without the core depending on Arrow.
 */
public fun <S, T> ArrowLens<S, T>.asCoreLens(): Lens<S, T> {
    val arrow = this
    return object : Lens<S, T> {
        override fun get(source: S): T = arrow.get(source)

        override fun set(
            source: S,
            value: T,
        ): S = arrow.set(source, value)
    }
}

public fun <S, T> ArrowPrism<S, T>.asCorePrism(): Prism<S, T> {
    val arrow = this
    return object : Prism<S, T> {
        override fun getOrNull(source: S): T? = arrow.getOrNull(source)

        override fun embed(value: T): S = arrow.reverseGet(value)
    }
}

public fun <S, T> ArrowOptional<S, T>.asCoreOptional(): Optional<S, T> {
    val arrow = this
    return object : Optional<S, T> {
        override fun getOrNull(source: S): T? = arrow.getOrNull(source)

        override fun set(
            source: S,
            value: T,
        ): S = arrow.set(source, value)
    }
}
