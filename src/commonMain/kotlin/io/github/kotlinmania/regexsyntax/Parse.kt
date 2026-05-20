// port-lint: source lib.rs
package io.github.kotlinmania.regexsyntax

import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.parser.parse as parserParse

/**
 * A convenience routine for parsing a regex using default options.
 *
 * This is equivalent to `io.github.kotlinmania.regexsyntax.parser.Parser.new().parse(pattern)`.
 *
 * If you need to set non-default options, then use
 * [io.github.kotlinmania.regexsyntax.parser.ParserBuilder].
 *
 * This routine returns an [Hir] value. Namely, it automatically parses the pattern as an `Ast`
 * and then invokes the translator to convert the `Ast` into an `Hir`. If you need access to the
 * `Ast`, then you should use [io.github.kotlinmania.regexsyntax.ast.parse.Parser].
 */
fun parse(pattern: String): Result<Hir> = parserParse(pattern)
