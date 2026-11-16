package io.github.kotlinmania.regexsyntax

import io.github.kotlinmania.rustlang.core.result.Result as RustResult

internal fun <T, E> RustResult<T, E>.unwrap(): T =
    when (this) {
        is RustResult.Ok -> this.value
        is RustResult.Err -> error("Called unwrap() on Err(${this.error})")
    }

internal fun <T, E> RustResult<T, E>.unwrapErr(): E =
    when (this) {
        is RustResult.Ok -> error("Called unwrapErr() on Ok(${this.value})")
        is RustResult.Err -> this.error
    }

internal fun <T> kotlin.Result<T>.unwrap(): T = this.getOrThrow()

internal fun <T> kotlin.Result<T>.unwrapErr(): Throwable =
    this.exceptionOrNull() ?: error("Called unwrapErr() on Result.success(${this.getOrNull()})")
