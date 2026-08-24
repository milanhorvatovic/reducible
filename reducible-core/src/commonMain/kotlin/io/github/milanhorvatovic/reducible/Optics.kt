package io.github.milanhorvatovic.reducible

/**
 * "There may or may not be a [T] inside [S]." The common denominator of [Lens] and [Prism],
 * and what child scoping composes through. [set] is a no-op when the focus is absent.
 */
public interface Optional<S, T> {
    public fun getOrNull(source: S): T?

    public fun set(
        source: S,
        value: T,
    ): S
}

/** Focus on a value that is always present — a field of a product type. */
public interface Lens<S, T> : Optional<S, T> {
    public fun get(source: S): T

    override fun getOrNull(source: S): T? = get(source)
}

/** Focus on one case of a sum type; [embed] rebuilds the sum from the case. */
public interface Prism<S, T> : Optional<S, T> {
    public fun embed(value: T): S

    override fun set(
        source: S,
        value: T,
    ): S =
        if (getOrNull(source) != null) {
            embed(value)
        } else {
            source
        }
}

public fun <S, T> lens(
    get: (S) -> T,
    set: (S, T) -> S,
): Lens<S, T> =
    object : Lens<S, T> {
        override fun get(source: S): T = get(source)

        override fun set(
            source: S,
            value: T,
        ): S = set(source, value)
    }

public fun <S, T> optional(
    getOrNull: (S) -> T?,
    set: (S, T) -> S,
): Optional<S, T> =
    object : Optional<S, T> {
        override fun getOrNull(source: S): T? = getOrNull(source)

        override fun set(
            source: S,
            value: T,
        ): S = set(source, value)
    }

public fun <S, T> prism(
    getOrNull: (S) -> T?,
    embed: (T) -> S,
): Prism<S, T> =
    object : Prism<S, T> {
        override fun getOrNull(source: S): T? = getOrNull(source)

        override fun embed(value: T): S = embed(value)
    }

/** Prism onto one case of a sealed hierarchy, where the case type is its own embedding. */
public inline fun <S, reified T : S> casePrism(): Prism<S, T> = prism(getOrNull = { source -> source as? T }, embed = { value -> value })

public infix fun <S, M, T> Optional<S, M>.andThen(next: Optional<M, T>): Optional<S, T> {
    val outer = this
    return object : Optional<S, T> {
        override fun getOrNull(source: S): T? = outer.getOrNull(source)?.let(next::getOrNull)

        override fun set(
            source: S,
            value: T,
        ): S {
            val middle = outer.getOrNull(source) ?: return source
            return outer.set(source, next.set(middle, value))
        }
    }
}
