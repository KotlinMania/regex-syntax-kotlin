// port-lint: source src/either.rs
package io.github.kotlinmania.regexsyntax.either

/*
 * Copyright (c) The rust-lang regex contributors.
 * Licensed under either of Apache-2.0 OR MIT.
 */

/**
 * A simple binary sum type.
 *
 * This is occasionally useful in an ad hoc fashion.
 */
sealed class Either<Left, Right> {
    data class Left<L, R>(val value: L) : Either<L, R>()
    data class Right<L, R>(val value: R) : Either<L, R>()
}
