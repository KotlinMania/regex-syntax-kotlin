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

import io.github.kotlinmania.regexsyntax.unicode.UnicodeWordError
import io.github.kotlinmania.regexsyntax.unicode.isWordCharacter as unicodeIsWordCharacter
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.parser.parse as parserParse

/**
 * Escapes all regular expression meta characters in [text].
 *
 * The string returned may be safely used as a literal in a regular
 * expression.
 */
fun escape(text: String): String {
    val quoted = StringBuilder()
    escapeInto(text, quoted)
    return quoted.toString()
}

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

/**
 * Escapes all meta characters in [text] and writes the result into [buf].
 *
 * This will append escape characters into the given buffer. The characters
 * that are appended are safe to use as a literal in a regular expression.
 */
fun escapeInto(text: String, buf: StringBuilder) {
    for (c in text) {
        if (isMetaCharacter(c)) {
            buf.append('\\')
        }
        buf.append(c)
    }
}

/**
 * Returns true if the given character has significance in a regex.
 *
 * Generally speaking, these are the only characters which _must_ be
 * escaped in order to match their literal meaning. For example, to match a
 * literal `|`, one could write `\|`. Sometimes escaping isn't always
 * necessary. For example, `-` is treated as a meta character because of
 * its significance for writing ranges inside of character classes, but
 * the regex `-` will match a literal `-` because `-` has no special
 * meaning outside of character classes.
 *
 * In order to determine whether a character may be escaped at all, the
 * [isEscapeableCharacter] routine should be used. The difference between
 * [isMetaCharacter] and [isEscapeableCharacter] is that the latter will
 * return true for some characters that are _not_ meta characters. For
 * example, `%` and `\%` both match a literal `%` in all contexts. In
 * other words, [isEscapeableCharacter] includes "superfluous" escapes.
 *
 * Note that the set of characters for which this function returns `true`
 * or `false` is fixed and won't change in a semver compatible release.
 */
fun isMetaCharacter(c: Char): Boolean = when (c) {
    '\\', '.', '+', '*', '?', '(', ')', '|', '[', ']', '{',
    '}', '^', '$', '#', '&', '-', '~' -> true
    else -> false
}

/**
 * Returns true if the given character can be escaped in a regex.
 *
 * This returns true in all cases that [isMetaCharacter] returns true,
 * but also returns true in some cases where [isMetaCharacter] returns
 * false. For example, `%` is not a meta character, but it is escapable.
 * That is, `%` and `\%` both match a literal `%` in all contexts.
 *
 * The purpose of this routine is to provide knowledge about what
 * characters may be escaped. Namely, most regex engines permit
 * "superfluous" escapes where characters without any special significance
 * may be escaped even though there is no actual _need_ to do so.
 *
 * This will return false for some characters. For example, `e` is not
 * escapable. Therefore, `\e` will either result in a parse error (which
 * is true today), or it could backwards compatibly evolve into a new
 * construct with its own meaning. Indeed, that is the purpose of banning
 * _some_ superfluous escapes: it provides a way to evolve the syntax in
 * a compatible manner.
 */
fun isEscapeableCharacter(c: Char): Boolean {
    // Certainly escapable if it's a meta character.
    if (isMetaCharacter(c)) {
        return true
    }
    // Any character that isn't ASCII is definitely not escapable. There's
    // no real need to allow things like \☃ right?
    if (c.code > 0x7F) {
        return false
    }
    // Otherwise, we basically say that everything is escapable unless it's
    // a letter or digit. Things like \3 are either octal (when enabled) or
    // an error, and we should keep it that way. Otherwise, letters are
    // reserved for adding new syntax in a backwards compatible way.
    return when (c) {
        in '0'..'9', in 'A'..'Z', in 'a'..'z' -> false
        // While not currently supported, we keep these as not escapable to
        // give us some flexibility with respect to supporting the \< and
        // \> word boundary assertions in the future. By rejecting them as
        // escapable, \< and \> will result in a parse error. Thus, we can
        // turn them into something else in the future without it being a
        // backwards incompatible change.
        //
        // Now we support \< and \>, and we need to retain them as *not*
        // escapable here since the escape sequence is significant.
        '<', '>' -> false
        else -> true
    }
}

/**
 * Returns true if and only if the given character is a Unicode word
 * character.
 *
 * A Unicode word character is defined by
 * [UTS#18 Annex C](https://unicode.org/reports/tr18/) (Compatibility Properties).
 * In particular, a character is considered a word character if it is in
 * either of the Alphabetic or Join Control properties, or is in one of the
 * Decimal Number, Mark or Connector Punctuation general categories.
 *
 * # Throws
 *
 * Throws [UnicodeWordError] if the Unicode word character data is not
 * available. Callers that prefer a [Result]-typed signature should use
 * [tryIsWordCharacter] instead.
 */
fun isWordCharacter(c: Int): Boolean = tryIsWordCharacter(c).getOrElse {
    throw it
}

/**
 * Returns true if and only if the given character is a Unicode word
 * character.
 *
 * A Unicode word character is defined by
 * [UTS#18 Annex C](https://unicode.org/reports/tr18/) (Compatibility Properties).
 * In particular, a character is considered a word character if it is in
 * either of the Alphabetic or Join Control properties, or is in one of the
 * Decimal Number, Mark or Connector Punctuation general categories.
 *
 * # Errors
 *
 * Returns a [Result] wrapping a [UnicodeWordError] if the Unicode word
 * character data is not available.
 */
fun tryIsWordCharacter(c: Int): Result<Boolean> = unicodeIsWordCharacter(c)

/**
 * Returns true if and only if the given byte is an ASCII word character.
 *
 * An ASCII word character is defined by the following character class:
 * `[_0-9a-zA-Z]`.
 */
fun isWordByte(c: Byte): Boolean {
    val v = c.toInt() and 0xFF
    return when (v) {
        '_'.code -> true
        in '0'.code..'9'.code -> true
        in 'a'.code..'z'.code -> true
        in 'A'.code..'Z'.code -> true
        else -> false
    }
}
