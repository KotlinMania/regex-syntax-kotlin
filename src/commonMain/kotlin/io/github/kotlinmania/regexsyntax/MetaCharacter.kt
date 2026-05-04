// port-lint: source src/lib.rs
package io.github.kotlinmania.regexsyntax

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
