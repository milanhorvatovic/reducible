package io.github.milanhorvatovic.reducible.test

import io.github.milanhorvatovic.reducible.Lens
import io.github.milanhorvatovic.reducible.Optional
import io.github.milanhorvatovic.reducible.Prism

/**
 * Asserts the affine-traversal laws every hand-written [Optional] must satisfy, in one line
 * per optic. Sources and foci must have structural equality (data classes).
 *
 * Laws: writing back the focus you read is an identity (get-set); reading after a write
 * returns the written value (set-get); writing twice is idempotent (set-set); and writing
 * into an absent focus is a no-op — inserting the child is the reducer's explicit decision,
 * never a side effect of a set.
 */
public fun <S, T> Optional<S, T>.assertOptionalLaws(
    present: S,
    replacement: T,
    vararg absent: S,
) {
    val focus =
        getOrNull(present)
            ?: throw AssertionError("Expected the focus to resolve on the present source:\n  $present")
    checkLaw(set(present, focus) == present) {
        "get-set law failed: writing back the focus read from the source must be an identity.\n  source: $present"
    }
    val written = set(present, replacement)
    checkLaw(getOrNull(written) == replacement) {
        "set-get law failed: reading after writing must return the written value.\n  wrote: $replacement\n  read:  ${getOrNull(written)}"
    }
    checkLaw(set(written, replacement) == written) {
        "set-set law failed: writing the same value twice must be idempotent.\n  source: $present"
    }
    for (source in absent) {
        checkLaw(getOrNull(source) == null) {
            "Expected the focus to be absent on:\n  $source"
        }
        checkLaw(set(source, replacement) == source) {
            "absence law failed: writing into an absent focus must be a no-op.\n  source: $source\n  became: ${set(source, replacement)}"
        }
    }
}

/** Asserts the lens laws (get-set, set-get, set-set) — a lens focus is always present. */
public fun <S, T> Lens<S, T>.assertLensLaws(
    source: S,
    replacement: T,
) {
    checkLaw(set(source, get(source)) == source) {
        "get-set law failed: writing back the focus read from the source must be an identity.\n  source: $source"
    }
    val written = set(source, replacement)
    checkLaw(get(written) == replacement) {
        "set-get law failed: reading after writing must return the written value.\n  wrote: $replacement\n  read:  ${get(written)}"
    }
    checkLaw(set(written, replacement) == written) {
        "set-set law failed: writing the same value twice must be idempotent.\n  source: $source"
    }
}

/**
 * Asserts the prism laws: embedding the matched focus rebuilds the source (get-embed), a
 * freshly embedded value is matched back (embed-get), and non-matching sources neither
 * resolve nor change on a write.
 */
public fun <S, T> Prism<S, T>.assertPrismLaws(
    matching: S,
    value: T,
    vararg nonMatching: S,
) {
    val focus =
        getOrNull(matching)
            ?: throw AssertionError("Expected the prism to match:\n  $matching")
    checkLaw(embed(focus) == matching) {
        "get-embed law failed: embedding the matched focus must rebuild the source.\n  source: $matching\n  rebuilt: ${embed(focus)}"
    }
    checkLaw(getOrNull(embed(value)) == value) {
        "embed-get law failed: a freshly embedded value must be matched back.\n  embedded: $value\n  matched:  ${getOrNull(embed(value))}"
    }
    for (source in nonMatching) {
        checkLaw(getOrNull(source) == null) {
            "Expected the prism not to match:\n  $source"
        }
        checkLaw(set(source, value) == source) {
            "absence law failed: set on a non-matching source must be a no-op.\n  source: $source\n  became: ${set(source, value)}"
        }
    }
}

private inline fun checkLaw(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) {
        throw AssertionError(message())
    }
}
