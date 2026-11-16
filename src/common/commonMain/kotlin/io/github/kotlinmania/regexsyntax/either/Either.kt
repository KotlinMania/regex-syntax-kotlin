// port-lint: source either.rs
package io.github.kotlinmania.regexsyntax.either

/**
 * A simple binary sum type.
 *
 * This is occasionally useful in an ad hoc fashion.
 */
internal sealed class Either<Left, Right> {
    internal data class Left<Left, Right>(internal var value: Left) : Either<Left, Right>()

    internal data class Right<Left, Right>(internal var value: Right) : Either<Left, Right>()
}
