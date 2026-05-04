// port-lint: source src/lib.rs
package io.github.kotlinmania.regexsyntax

import io.github.kotlinmania.regexsyntax.unicode.UnicodeWordError
import io.github.kotlinmania.regexsyntax.unicode.isWordCharacter as unicodeIsWordCharacter

/**
 * Returns true if and only if the given character is a Unicode word
 * character.
 *
 * A Unicode word character is defined by
 * [UTS#18 Annex C](https://unicode.org/reports/tr18/#Compatibility_Properties).
 * In particular, a character is considered a word character if it is in
 * either of the Alphabetic or Join_Control properties, or is in one of the
 * Decimal_Number, Mark or Connector_Punctuation general categories.
 *
 * Throws [UnicodeWordError] if the unicode-perl data is not available.
 * Callers that prefer a [Result]-typed signature should use
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
 * [UTS#18 Annex C](https://unicode.org/reports/tr18/#Compatibility_Properties).
 * In particular, a character is considered a word character if it is in
 * either of the Alphabetic or Join_Control properties, or is in one of the
 * Decimal_Number, Mark or Connector_Punctuation general categories.
 *
 * Returns a [Result] wrapping a [UnicodeWordError] if the unicode-perl
 * data is not available.
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
