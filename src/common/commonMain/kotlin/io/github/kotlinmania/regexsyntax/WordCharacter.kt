package io.github.kotlinmania.regexsyntax

import io.github.kotlinmania.regexsyntax.unicode.UnicodeWordError
import io.github.kotlinmania.rustlang.core.result.Result

/**
 * Returns `true` if and only if the given Unicode scalar value is a Unicode
 * word character.
 *
 * A Unicode word character is defined by
 * [UTS#18 Annex C](https://unicode.org/reports/tr18/#Compatibility_Properties).
 * In particular, a character is considered a word character if it is in either
 * of the `Alphabetic` or `Join_Control` properties, or is in one of the
 * `Decimal_Number`, `Mark` or `Connector_Punctuation` general categories.
 *
 * Throws an [IllegalStateException] if the `unicode-perl` feature is not
 * enabled. Callers may use [tryIsWordCharacter] instead.
 */
public fun isWordCharacter(c: UInt): Boolean =
    tryIsWordCharacter(c).expect("unicode-perl feature must be enabled")

/**
 * Returns `true` if and only if the given Unicode scalar value is a Unicode
 * word character.
 *
 * A Unicode word character is defined by
 * [UTS#18 Annex C](https://unicode.org/reports/tr18/#Compatibility_Properties).
 * In particular, a character is considered a word character if it is in either
 * of the `Alphabetic` or `Join_Control` properties, or is in one of the
 * `Decimal_Number`, `Mark` or `Connector_Punctuation` general categories.
 */
public fun tryIsWordCharacter(c: UInt): Result<Boolean, UnicodeWordError> =
    io.github.kotlinmania.regexsyntax.unicode.isWordCharacter(c)

/**
 * Returns `true` if and only if the given byte is an ASCII word character.
 *
 * An ASCII word character is defined by the character class `[_0-9a-zA-Z]`.
 */
public fun isWordByte(c: UByte): Boolean =
    when (c.toUInt()) {
        95u, in 48u..57u, in 97u..122u, in 65u..90u -> true
        else -> false
    }
