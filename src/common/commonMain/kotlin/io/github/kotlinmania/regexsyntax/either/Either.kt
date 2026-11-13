// port-lint: source either.rs
package io.github.kotlinmania.regexsyntax.either

/**
 * A simple binary sum type.
 *
 * This is occasionally useful in an ad hoc fashion.
 */
public sealed class Either<Left, Right> {
    public data class Left<Left, Right>(public var value: Left) : Either<Left, Right>()
    public data class Right<Left, Right>(public var value: Right) : Either<Left, Right>()

    public fun rustEquals(other: Either<Left, Right>): Boolean = this == other
}
