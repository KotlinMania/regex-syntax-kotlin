// port-lint: source src/lib.rs
package io.github.kotlinmania.regexsyntax

/**
 * This crate provides a robust regular expression parser.
 *
 * This crate defines two primary types:
 *
 * - [io.github.kotlinmania.regexsyntax.ast.Ast] is the abstract syntax of a regular expression.
 *   An abstract syntax corresponds to a structured representation of the concrete syntax of a
 *   regular expression, where the concrete syntax is the pattern string itself (for example,
 *   `foo(bar)+`). Given some abstract syntax, it can be converted back to the original concrete
 *   syntax (modulo some details, like whitespace). To a first approximation, the abstract syntax
 *   is complex and difficult to analyze.
 * - [io.github.kotlinmania.regexsyntax.hir.Hir] is the high-level intermediate representation
 *   ("HIR" or "high-level IR" for short) of a regular expression. It corresponds to an
 *   intermediate state of a regular expression that sits between the abstract syntax and the low
 *   level compiled opcodes that are eventually responsible for executing a regular expression
 *   search. Given some high-level IR, it is not possible to produce the original concrete syntax
 *   (although it is possible to produce an equivalent concrete syntax, but it will likely scarcely
 *   resemble the original pattern). To a first approximation, the high-level IR is simple and easy
 *   to analyze.
 *
 * These two types come with conversion routines:
 *
 * - [io.github.kotlinmania.regexsyntax.ast.parse.Parser] converts concrete syntax (a [String]) to
 *   an [io.github.kotlinmania.regexsyntax.ast.Ast].
 * - [io.github.kotlinmania.regexsyntax.hir.translate.Translator] converts an
 *   [io.github.kotlinmania.regexsyntax.ast.Ast] to an [io.github.kotlinmania.regexsyntax.hir.Hir].
 *
 * As a convenience, the above two conversion routines are combined into one via
 * [io.github.kotlinmania.regexsyntax.parser.Parser]. It's also exposed as the top-level [parse]
 * free function.
 *
 * # Example
 *
 * This example shows how to parse a pattern string into its HIR:
 *
 * ```
 * import io.github.kotlinmania.regexsyntax.hir.Hir
 * import io.github.kotlinmania.regexsyntax.parse
 *
 * val hir = parse("a|b").getOrThrow()
 * check(hir == Hir.alternation(listOf(
 *     Hir.literal("a".encodeToByteArray()),
 *     Hir.literal("b".encodeToByteArray()),
 * )))
 * ```
 *
 * # Concrete syntax supported
 *
 * The concrete syntax is documented as part of the public API of the upstream Rust `regex` crate.
 *
 * # Input safety
 *
 * A key feature of this library is that it is safe to use with end user facing input. This plays
 * a significant role in the internal implementation. In particular:
 *
 * 1. Parsers provide a `nest_limit` option that permits callers to control how deeply nested a
 *    regular expression is allowed to be. This makes it possible to do case analysis over an `Ast`
 *    or an `Hir` using recursion without worrying about stack overflow.
 * 2. Since relying on a particular stack size is brittle, this crate goes to great lengths to
 *    ensure that all interactions with both the `Ast` and the `Hir` do not use recursion. Namely,
 *    they use constant stack space and heap space proportional to the size of the original pattern
 *    string (in bytes). This includes the type's corresponding destructors. (One exception to this
 *    is literal extraction, but this will eventually get fixed.)
 *
 * # Error reporting
 *
 * The [toString] implementations on all `Error` types exposed in this library provide nice human
 * readable errors that are suitable for showing to end users in a monospace font.
 *
 * # Literal extraction
 *
 * This crate provides limited support for literal extraction from `Hir` values (see
 * [io.github.kotlinmania.regexsyntax.hir.literal]). Be warned that literal extraction uses
 * recursion, and therefore, stack size proportional to the size of the `Hir`.
 *
 * The purpose of literal extraction is to speed up searches. That is, if you know a regular
 * expression must match a prefix or suffix literal, then it is often quicker to search for
 * instances of that literal, and then confirm or deny the match using the full regular expression
 * engine. These optimizations are done automatically in the upstream Rust `regex` crate.
 *
 * # Crate features
 *
 * Upstream Rust exposes Cargo features (for example, `unicode-*`) to control Unicode table
 * availability. This Kotlin port does not preserve feature gating; Unicode tables are shipped as
 * Kotlin source under [io.github.kotlinmania.regexsyntax.unicodetables].
 */

