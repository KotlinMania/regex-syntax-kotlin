package io.github.kotlinmania.regexsyntax

/**
 * Escapes all regular expression meta characters in [text].
 *
 * The string returned may be safely used as a literal in a regular expression.
 */
public fun escape(text: String): String =
    buildString(text.length) {
        escapeInto(text, this)
    }

/**
 * Escapes all meta characters in [text] and writes the result into [buf].
 *
 * This will append escape characters into the given buffer. The characters
 * that are appended are safe to use as a literal in a regular expression.
 */
public fun escapeInto(text: String, buf: StringBuilder) {
    buf.ensureCapacity(buf.length + text.length)
    for (c in text.unicodeScalars()) {
        if (isMetaCharacter(c)) {
            buf.append('\\')
        }
        buf.appendScalar(c)
    }
}

/**
 * Returns `true` if the given Unicode scalar value has significance in a regex.
 *
 * Generally speaking, these are the only characters which *must* be escaped
 * in order to match their literal meaning. For example, to match a literal
 * `|`, one could write `\|`. Sometimes escaping isn't always necessary. For
 * example, `-` is treated as a meta character because of its significance
 * for writing ranges inside of character classes, but the regex `-` will
 * match a literal `-` because `-` has no special meaning outside of character
 * classes.
 *
 * In order to determine whether a character may be escaped at all, the
 * [isEscapeableCharacter] routine should be used. The difference between
 * `isMetaCharacter` and `isEscapeableCharacter` is that the latter will
 * return `true` for some characters that are *not* meta characters. For
 * example, `%` and `\%` both match a literal `%` in all contexts. In other
 * words, `isEscapeableCharacter` includes "superfluous" escapes.
 *
 * Note that the set of characters for which this function returns `true` or
 * `false` is fixed and won't change in a semver compatible release.
 */
public fun isMetaCharacter(c: UInt): Boolean =
    when (c.toInt()) {
        '\\'.code, '.'.code, '+'.code, '*'.code, '?'.code,
        '('.code, ')'.code, '|'.code, '['.code, ']'.code,
        '{'.code, '}'.code, '^'.code, '$'.code, '#'.code,
        '&'.code, '-'.code, '~'.code,
        -> true
        else -> false
    }

/**
 * Returns `true` if the given Unicode scalar value can be escaped in a regex.
 *
 * This returns `true` in all cases that [isMetaCharacter] returns `true`, but
 * also returns `true` in some cases where [isMetaCharacter] returns `false`.
 * For example, `%` is not a meta character, but it is escapable. That is,
 * `%` and `\%` both match a literal `%` in all contexts.
 *
 * The purpose of this routine is to provide knowledge about what characters
 * may be escaped. Namely, most regex engines permit "superfluous" escapes
 * where characters without any special significance may be escaped even
 * though there is no actual *need* to do so.
 *
 * This will return `false` for some characters. For example, `e` is not
 * escapable. Therefore, `\e` will either result in a parse error, or it could
 * backwards compatibly evolve into a new construct with its own meaning.
 */
public fun isEscapeableCharacter(c: UInt): Boolean {
    // Certainly escapable if it's a meta character.
    if (isMetaCharacter(c)) {
        return true
    }
    // Any character that isn't ASCII is definitely not escapable.
    if (!scalarIsAscii(c)) {
        return false
    }
    // Otherwise, we basically say that everything is escapable unless it's a
    // letter or digit. Letters and digits are reserved for adding new syntax
    // in a backwards compatible way.
    val code = c.toInt()
    return when {
        code in '0'.code..'9'.code -> false
        code in 'A'.code..'Z'.code -> false
        code in 'a'.code..'z'.code -> false
        // Support for \< and \> word boundary assertions retains '<' and '>'
        // as non-escapable since those escape sequences are significant.
        code == '<'.code || code == '>'.code -> false
        else -> true
    }
}
