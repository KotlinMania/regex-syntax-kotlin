package io.github.kotlinmania.regexsyntax.hir

import io.github.kotlinmania.regexsyntax.debug.Byte
import io.github.kotlinmania.regexsyntax.scalarEscapeDebug
import io.github.kotlinmania.regexsyntax.unicode.CaseFoldError
import io.github.kotlinmania.rustlang.core.result.Result

/**
 * The type of an error that occurred while building an `Hir`.
 *
 * This error type is marked as `nonExhaustive`. This means that adding a
 * new variant is not considered a breaking change.
 */
public sealed class ErrorKind {
    /**
     * This error occurs when a Unicode feature is used when Unicode
     * support is disabled. For example `(?-u:\pL)` would trigger this error.
     */
    public data object UnicodeNotAllowed : ErrorKind()
    /**
     * This error occurs when translating a pattern that could match a byte
     * sequence that isn't UTF-8 and `utf8` was enabled.
     */
    public data object InvalidUtf8 : ErrorKind()
    /**
     * This error occurs when one uses a non-ASCII byte for a line terminator,
     * but where Unicode mode is enabled and UTF-8 mode is disabled.
     */
    public data object InvalidLineTerminator : ErrorKind()
    /**
     * This occurs when an unrecognized Unicode property name could not
     * be found.
     */
    public data object UnicodePropertyNotFound : ErrorKind()
    /**
     * This occurs when an unrecognized Unicode property value could not
     * be found.
     */
    public data object UnicodePropertyValueNotFound : ErrorKind()
    /**
     * This occurs when a Unicode-aware Perl character class (`\w`, `\s` or
     * `\d`) could not be found. This can occur when the `unicode - perl`
     * crate feature is not enabled.
     */
    public data object UnicodePerlClassNotFound : ErrorKind()
    /**
     * This occurs when the Unicode simple case mapping tables are not
     * available, and the regular expression required Unicode aware case
     * insensitivity.
     */
    public data object UnicodeCaseUnavailable : ErrorKind()

    public fun rustEquals(other: ErrorKind): Boolean = this == other

    public open fun fmt(f: StringBuilder) {
        val msg =
            when (this) {
                UnicodeNotAllowed -> "Unicode not allowed here"
                InvalidUtf8 -> "pattern can match invalid UTF-8"
                InvalidLineTerminator -> "invalid line terminator, must be ASCII"
                UnicodePropertyNotFound -> "Unicode property not found"
                UnicodePropertyValueNotFound -> "Unicode property value not found"
                UnicodePerlClassNotFound -> {
                    "Unicode-aware Perl class not found (make sure the unicode-perl feature is enabled)"
                }
                UnicodeCaseUnavailable -> {
                    "Unicode-aware case insensitivity matching is not available (make sure the unicode-case feature is enabled)"
                }
            }
        f.append(msg)
    }

    override fun toString(): String = buildString { fmt(this) }
}

/**
 * The underlying kind of an arbitrary [Hir] expression.
 *
 * An `HirKind` is principally useful for doing case analysis on the type
 * of a regular expression. If you're looking to build new `Hir` values,
 * then you _must_ use the smart constructors defined on `Hir`, like
 * [Hir.repetition], to build new `Hir` values. The API intentionally does
 * not expose any way of building an `Hir` directly from an `HirKind`.
 */
public sealed class HirKind {
    /**
     * The empty regular expression, which matches everything, including the
     * empty string.
     */
    public data object Empty : HirKind()
    /**
     * A literal string that matches exactly these bytes.
     */
    public data class Literal(public var value: io.github.kotlinmania.regexsyntax.hir.Literal) : HirKind()
    /**
     * A single character class that matches any of the characters in the
     * class. A class can either consist of Unicode scalar values as
     * characters, or it can use bytes.
     *
     * A class may be empty. In which case, it matches nothing.
     */
    public data class Class(public var value: io.github.kotlinmania.regexsyntax.hir.Class) : HirKind()
    /**
     * A look-around assertion. A look-around match always has zero length.
     */
    public data class Look(public var value: io.github.kotlinmania.regexsyntax.hir.Look) : HirKind()
    /**
     * A repetition operation applied to a sub-expression.
     */
    public data class Repetition(public var value: io.github.kotlinmania.regexsyntax.hir.Repetition) : HirKind()
    /**
     * A capturing group, which contains a sub-expression.
     */
    public data class Capture(public var value: io.github.kotlinmania.regexsyntax.hir.Capture) : HirKind()
    /**
     * A concatenation of expressions.
     *
     * A concatenation matches only if each of its sub-expressions match one
     * after the other.
     *
     * Concatenations are guaranteed by `Hir`s smart constructors to always
     * have at least two sub-expressions.
     */
    public data class Concat(public var value: List<Hir>) : HirKind()
    /**
     * An alternation of expressions.
     *
     * An alternation matches only if at least one of its sub-expressions
     * match. If multiple sub-expressions match, then the leftmost is
     * preferred.
     *
     * Alternations are guaranteed by `Hir`s smart constructors to always
     * have at least two sub-expressions.
     */
    public data class Alternation(public var value: List<Hir>) : HirKind()

    public fun rustEquals(other: HirKind): Boolean = this == other

    /**
     * Returns a slice of this kind's sub-expressions, if any.
     */
    public open fun subs(): List<Hir> =
        when (this) {
            is HirKind.Empty -> listOf()
            is HirKind.Literal -> listOf()
            is HirKind.Class -> listOf()
            is HirKind.Look -> listOf()
            is HirKind.Repetition -> listOf(this.value.sub)
            is HirKind.Capture -> listOf(this.value.sub)
            is HirKind.Concat -> this.value
            is HirKind.Alternation -> this.value
        }

    public open fun fmt(f: StringBuilder) {
        when (this) {
            is HirKind.Empty -> f.append("Empty")
            is HirKind.Literal -> f.append("Literal(${this.value})")
            is HirKind.Class -> f.append("Class(${this.value})")
            is HirKind.Look -> f.append("Look(${this.value})")
            is HirKind.Repetition -> f.append("Repetition(${this.value})")
            is HirKind.Capture -> f.append("Capture(${this.value})")
            is HirKind.Concat -> f.append("Concat(${this.value})")
            is HirKind.Alternation -> f.append("Alternation(${this.value})")
        }
    }

    override fun toString(): String = buildString { fmt(this) }
}

/**
 * The high-level intermediate representation of a character class.
 *
 * A character class corresponds to a set of characters. A character is either
 * defined by a Unicode scalar value or a byte.
 *
 * A character class, regardless of its character type, is represented by a
 * sequence of non-overlapping non-adjacent ranges of characters.
 *
 * There are no guarantees about which class variant is used. Generally
 * speaking, the Unicode variant is used whenever a class needs to contain
 * non-ASCII Unicode scalar values. But the Unicode variant can be used even
 * when Unicode mode is disabled. For example, at the time of writing, the
 * regex `(?-u:a|\xc2\xa0)` will compile down to HIR for the Unicode class
 * `[a\u00A0]` due to optimizations.
 *
 * Note that `Bytes` variant may be produced even when it exclusively matches
 * valid UTF-8. This is because a `Bytes` variant represents an intention by
 * the author of the regular expression to disable Unicode mode, which in turn
 * impacts the semantics of case insensitive matching. For example, `(? i) k`
 * and `(? i - u) k` will not match the same set of strings.
 */
public sealed class Class {
    /**
     * A set of characters represented by Unicode scalar values.
     */
    public data class Unicode(public var value: ClassUnicode) : Class()
    /**
     * A set of characters represented by arbitrary bytes (one byte per
     * character).
     */
    public data class Bytes(public var value: ClassBytes) : Class()

    public fun rustEquals(other: Class): Boolean = this == other

    /**
     * Apply Unicode simple case folding to this character class, in place.
     * The character class will be expanded to include all simple case folded
     * character variants.
     *
     * If this is a byte oriented character class, then this will be limited
     * to the ASCII ranges `A - Z` and `a - z`.
     *
     * # Panics
     *
     * This routine panics when the case mapping data necessary for this
     * routine to complete is unavailable. This occurs when the `unicode - case`
     * feature is not enabled and the underlying class is Unicode oriented.
     *
     * Callers should prefer using `tryCaseFoldSimple` instead, which will
     * return an error instead of panicking.
     */
    public open fun caseFoldSimple() {
        when (this) {
            is Class.Unicode -> {
                val (x) = this
                x.caseFoldSimple()
            }
            is Class.Bytes -> {
                val (x1) = this
                x1.caseFoldSimple()
            }
        }
    }

    /**
     * Apply Unicode simple case folding to this character class, in place.
     * The character class will be expanded to include all simple case folded
     * character variants.
     *
     * If this is a byte oriented character class, then this will be limited
     * to the ASCII ranges `A - Z` and `a - z`.
     *
     * # Error
     *
     * This routine returns an error when the case mapping data necessary
     * for this routine to complete is unavailable. This occurs when the
     * `unicode - case` feature is not enabled and the underlying class is
     * Unicode oriented.
     */
    public open fun tryCaseFoldSimple(): Result<Unit, CaseFoldError> {
        when (this) {
            is Class.Unicode -> {
                val (x) = this
                return x.tryCaseFoldSimple()
            }
            is Class.Bytes -> {
                val (x1) = this
                x1.caseFoldSimple()
            }
        }
        return Result.Ok(Unit)
    }

    /**
     * Negate this character class in place.
     *
     * After completion, this character class will contain precisely the
     * characters that weren't previously in the class.
     */
    public open fun negate() {
        when (this) {
            is Class.Unicode -> {
                val (x) = this
                x.negate()
            }
            is Class.Bytes -> {
                val (x1) = this
                x1.negate()
            }
        }
    }

    /**
     * Returns true if and only if this character class will only ever match
     * valid UTF-8.
     *
     * A character class can match invalid UTF-8 only when the following
     * conditions are met:
     *
     * 1. The translator was configured to permit generating an expression
     *    that can match invalid UTF-8. (By default, this is disabled.)
     * 2. Unicode mode (via the `u` flag) was disabled either in the concrete
     *    syntax or in the parser builder. By default, Unicode mode is
     *    enabled.
     */
    public open fun isUtf8(): Boolean =
        when (this) {
            is Class.Unicode -> true
            is Class.Bytes -> {
                val (x) = this
                x.isAscii()
            }
        }

    /**
     * Returns the length, in bytes, of the smallest string matched by this
     * character class.
     *
     * For non-empty byte oriented classes, this always returns `1`. For
     * non-empty Unicode oriented classes, this can return `1`, `2`, `3` or
     * `4`. For empty classes, `None` is returned. It is impossible for `0` to
     * be returned.
     *
     * # Example
     *
     * This example shows some examples of regexes and their corresponding
     * minimum length, if any.
     *
     * ```kotlin
     * import regexsyntax.parse
     *
     * // The empty string has a min length of 0.
     * val hir = parse("").getOrElse { return Result.failure(it) }
     * check(0 == hir.properties().minimumLen())
     * // As do other types of regexes that only match the empty string.
     * val hir1 = parse("^\$\\b\\B").getOrElse { return Result.failure(it) }
     * check(0 == hir1.properties().minimumLen())
     * // A regex that can match the empty string but match more is still 0.
     * val hir2 = parse("a*").getOrElse { return Result.failure(it) }
     * check(0 == hir2.properties().minimumLen())
     * // A regex that matches nothing has no minimum defined.
     * val hir3 = parse("[a&&b]").getOrElse { return Result.failure(it) }
     * check(null == hir3.properties().minimumLen())
     * // Character classes usually have a minimum length of 1.
     * val hir4 = parse("\\w").getOrElse { return Result.failure(it) }
     * check(1 == hir4.properties().minimumLen())
     * // But sometimes Unicode classes might be bigger!
     * val hir5 = parse("\\p{Cyrillic}").getOrElse { return Result.failure(it) }
     * check(2 == hir5.properties().minimumLen())
     * ```
     */
    public open fun minimumLen(): Int? =
        when (this) {
            is Class.Unicode -> {
                val (x) = this
                x.minimumLen()
            }
            is Class.Bytes -> {
                val (x1) = this
                x1.minimumLen()
            }
        }

    /**
     * Returns the length, in bytes, of the longest string matched by this
     * character class.
     *
     * For non-empty byte oriented classes, this always returns `1`. For
     * non-empty Unicode oriented classes, this can return `1`, `2`, `3` or
     * `4`. For empty classes, `None` is returned. It is impossible for `0` to
     * be returned.
     *
     * # Example
     *
     * This example shows some examples of regexes and their corresponding
     * maximum length, if any.
     *
     * ```kotlin
     * import regexsyntax.parse
     *
     * // The empty string has a max length of 0.
     * val hir = parse("").getOrElse { return Result.failure(it) }
     * check(0 == hir.properties().maximumLen())
     * // As do other types of regexes that only match the empty string.
     * val hir1 = parse("^\$\\b\\B").getOrElse { return Result.failure(it) }
     * check(0 == hir1.properties().maximumLen())
     * // A regex that matches nothing has no maximum defined.
     * val hir2 = parse("[a&&b]").getOrElse { return Result.failure(it) }
     * check(null == hir2.properties().maximumLen())
     * // Bounded repeats work as you expect.
     * val hir3 = parse("x{2,10}").getOrElse { return Result.failure(it) }
     * check(10 == hir3.properties().maximumLen())
     * // An unbounded repeat means there is no maximum.
     * val hir4 = parse("x{2,}").getOrElse { return Result.failure(it) }
     * check(null == hir4.properties().maximumLen())
     * // With Unicode enabled, \w can match up to 4 bytes!
     * val hir5 = parse("\\w").getOrElse { return Result.failure(it) }
     * check(4 == hir5.properties().maximumLen())
     * // Without Unicode enabled, \w matches at most 1 byte.
     * val hir6 = parse("(?-u)\\w").getOrElse { return Result.failure(it) }
     * check(1 == hir6.properties().maximumLen())
     * ```
     */
    public open fun maximumLen(): Int? =
        when (this) {
            is Class.Unicode -> {
                val (x) = this
                x.maximumLen()
            }
            is Class.Bytes -> {
                val (x1) = this
                x1.maximumLen()
            }
        }

    /**
     * Returns true if and only if this character class is empty. That is,
     * it has no elements.
     *
     * An empty character can never match anything, including an empty string.
     */
    public open fun isEmpty(): Boolean =
        when (this) {
            is Class.Unicode -> {
                val (x) = this
                x.ranges().isEmpty()
            }
            is Class.Bytes -> {
                val (x1) = this
                x1.ranges().isEmpty()
            }
        }

    /**
     * If this class consists of exactly one element (whether a codepoint or a
     * byte), then return it as a literal byte string.
     *
     * If this class is empty or contains more than one element, then `None`
     * is returned.
     */
    public open fun literal(): List<UByte>? =
        when (this) {
            is Class.Unicode -> {
                val (x) = this
                x.literal()
            }
            is Class.Bytes -> {
                val (x1) = this
                x1.literal()
            }
        }

    public open fun fmt(f: StringBuilder) {
        val entries = mutableListOf<String>()
        when (this) {
            is Class.Unicode -> {
                val (cls) = this
                for (r in cls.ranges()) {
                    entries.add("'${scalarEscapeDebug(r.start)}'..='${scalarEscapeDebug(r.end)}'")
                }
            }
            is Class.Bytes -> {
                val (cls1) = this
                for (r1 in cls1.ranges()) {
                    entries.add("${Byte(r1.start)}..=${Byte(r1.end)}")
                }
            }
        }
        f.append(entries.joinToString(", ", "{", "}"))
    }

    override fun toString(): String = buildString { fmt(this) }
}

/**
 * The high-level intermediate representation for a look-around assertion.
 *
 * An assertion match is always zero-length. Also called an "empty match."
 */
public sealed class Look(
    public val value: Int,
) {
    /**
     * Match the beginning of text. Specifically, this matches at the starting
     * position of the input.
     */
    public data object Start : Look(1 shl 0)
    /**
     * Match the end of text. Specifically, this matches at the ending
     * position of the input.
     */
    public data object End : Look(1 shl 1)
    /**
     * Match the beginning of a line or the beginning of text. Specifically,
     * this matches at the starting position of the input, or at the position
     * immediately following a `\n` character.
     */
    public data object StartLF : Look(1 shl 2)
    /**
     * Match the end of a line or the end of text. Specifically, this matches
     * at the end position of the input, or at the position immediately
     * preceding a `\n` character.
     */
    public data object EndLF : Look(1 shl 3)
    /**
     * Match the beginning of a line or the beginning of text. Specifically,
     * this matches at the starting position of the input, or at the position
     * immediately following either a `\r` or `\n` character, but never after
     * a `\r` when a `\n` follows.
     */
    public data object StartCRLF : Look(1 shl 4)
    /**
     * Match the end of a line or the end of text. Specifically, this matches
     * at the end position of the input, or at the position immediately
     * preceding a `\r` or `\n` character, but never before a `\n` when a `\r`
     * precedes it.
     */
    public data object EndCRLF : Look(1 shl 5)
    /**
     * Match an ASCII-only word boundary. That is, this matches a position
     * where the left adjacent character and right adjacent character
     * correspond to a word and non-word or a non-word and word character.
     */
    public data object WordAscii : Look(1 shl 6)
    /**
     * Match an ASCII-only negation of a word boundary.
     */
    public data object WordAsciiNegate : Look(1 shl 7)
    /**
     * Match a Unicode-aware word boundary. That is, this matches a position
     * where the left adjacent character and right adjacent character
     * correspond to a word and non-word or a non-word and word character.
     */
    public data object WordUnicode : Look(1 shl 8)
    /**
     * Match a Unicode-aware negation of a word boundary.
     */
    public data object WordUnicodeNegate : Look(1 shl 9)
    /**
     * Match the start of an ASCII-only word boundary. That is, this matches a
     * position at either the beginning of the haystack or where the previous
     * character is not a word character and the following character is a word
     * character.
     */
    public data object WordStartAscii : Look(1 shl 10)
    /**
     * Match the end of an ASCII-only word boundary. That is, this matches
     * a position at either the end of the haystack or where the previous
     * character is a word character and the following character is not a word
     * character.
     */
    public data object WordEndAscii : Look(1 shl 11)
    /**
     * Match the start of a Unicode word boundary. That is, this matches a
     * position at either the beginning of the haystack or where the previous
     * character is not a word character and the following character is a word
     * character.
     */
    public data object WordStartUnicode : Look(1 shl 12)
    /**
     * Match the end of a Unicode word boundary. That is, this matches a
     * position at either the end of the haystack or where the previous
     * character is a word character and the following character is not a word
     * character.
     */
    public data object WordEndUnicode : Look(1 shl 13)
    /**
     * Match the start half of an ASCII-only word boundary. That is, this
     * matches a position at either the beginning of the haystack or where the
     * previous character is not a word character.
     */
    public data object WordStartHalfAscii : Look(1 shl 14)
    /**
     * Match the end half of an ASCII-only word boundary. That is, this
     * matches a position at either the end of the haystack or where the
     * following character is not a word character.
     */
    public data object WordEndHalfAscii : Look(1 shl 15)
    /**
     * Match the start half of a Unicode word boundary. That is, this matches
     * a position at either the beginning of the haystack or where the
     * previous character is not a word character.
     */
    public data object WordStartHalfUnicode : Look(1 shl 16)
    /**
     * Match the end half of a Unicode word boundary. That is, this matches
     * a position at either the end of the haystack or where the following
     * character is not a word character.
     */
    public data object WordEndHalfUnicode : Look(1 shl 17)

    public fun rustEquals(other: Look): Boolean = this == other

    /**
     * Flip the look-around assertion to its equivalent for reverse searches.
     * For example, `StartLF` gets translated to `EndLF`.
     *
     * Some assertions, such as `WordUnicode`, remain the same since they
     * match the same positions regardless of the direction of the search.
     */
    public open fun reversed(): Look =
        when (this) {
            Look.Start -> Look.End
            Look.End -> Look.Start
            Look.StartLF -> Look.EndLF
            Look.EndLF -> Look.StartLF
            Look.StartCRLF -> Look.EndCRLF
            Look.EndCRLF -> Look.StartCRLF
            Look.WordAscii -> Look.WordAscii
            Look.WordAsciiNegate -> Look.WordAsciiNegate
            Look.WordUnicode -> Look.WordUnicode
            Look.WordUnicodeNegate -> Look.WordUnicodeNegate
            Look.WordStartAscii -> Look.WordEndAscii
            Look.WordEndAscii -> Look.WordStartAscii
            Look.WordStartUnicode -> Look.WordEndUnicode
            Look.WordEndUnicode -> Look.WordStartUnicode
            Look.WordStartHalfAscii -> Look.WordEndHalfAscii
            Look.WordEndHalfAscii -> Look.WordStartHalfAscii
            Look.WordStartHalfUnicode -> Look.WordEndHalfUnicode
            Look.WordEndHalfUnicode -> Look.WordStartHalfUnicode
        }

    /**
     * Return the underlying representation of this look-around enumeration
     * as an integer. Giving the return value to the [Look.fromRepr]
     * constructor is guaranteed to return the same look-around variant that
     * one started with within a semver compatible release of this crate.
     */
    public open fun asRepr(): UInt {
        // Direct conversion of the integer enum representation to UInt.
        return this.value.toUInt()
    }

    /**
     * Returns a convenient single codepoint representation of this
     * look-around assertion. Each assertion is guaranteed to be represented
     * by a distinct character.
     *
     * This is useful for succinctly representing a look-around assertion in
     * human friendly but succinct output intended for a programmer working on
     * regex internals.
     */
    public open fun asChar(): UInt =
        when (this) {
            Look.Start -> 'A'.code.toUInt()
            Look.End -> 'z'.code.toUInt()
            Look.StartLF -> '^'.code.toUInt()
            Look.EndLF -> '$'.code.toUInt()
            Look.StartCRLF -> 'r'.code.toUInt()
            Look.EndCRLF -> 'R'.code.toUInt()
            Look.WordAscii -> 'b'.code.toUInt()
            Look.WordAsciiNegate -> 'B'.code.toUInt()
            Look.WordUnicode -> 0x1D6C3u
            Look.WordUnicodeNegate -> 0x1D6A9u
            Look.WordStartAscii -> '<'.code.toUInt()
            Look.WordEndAscii -> '>'.code.toUInt()
            Look.WordStartUnicode -> '\u3008'.code.toUInt()
            Look.WordEndUnicode -> '\u3009'.code.toUInt()
            Look.WordStartHalfAscii -> '\u25c1'.code.toUInt()
            Look.WordEndHalfAscii -> '\u25b7'.code.toUInt()
            Look.WordStartHalfUnicode -> '\u25c0'.code.toUInt()
            Look.WordEndHalfUnicode -> '\u25b6'.code.toUInt()
        }

    public companion object {
        /**
         * Given the underlying representation of a `Look` value, return the
         * corresponding `Look` value if the representation is valid. Otherwise
         * `None` is returned.
         */
        public fun fromRepr(repr: UInt): Look? =
            when (repr) {
                0x1u -> Look.Start
                0x2u -> Look.End
                0x4u -> Look.StartLF
                0x8u -> Look.EndLF
                0x10u -> Look.StartCRLF
                0x20u -> Look.EndCRLF
                0x40u -> Look.WordAscii
                0x80u -> Look.WordAsciiNegate
                0x100u -> Look.WordUnicode
                0x200u -> Look.WordUnicodeNegate
                0x400u -> Look.WordStartAscii
                0x800u -> Look.WordEndAscii
                0x1000u -> Look.WordStartUnicode
                0x2000u -> Look.WordEndUnicode
                0x4000u -> Look.WordStartHalfAscii
                0x8000u -> Look.WordEndHalfAscii
                0x10000u -> Look.WordStartHalfUnicode
                0x20000u -> Look.WordEndHalfUnicode
                else -> null
            }
    }
}

/**
 * A type describing the different flavors of `.`.
 *
 * This type is meant to be used with [Hir.dot], which is a convenience
 * routine for building HIR values derived from the `.` regex.
 */
public sealed class Dot {
    /**
     * Matches the UTF-8 encoding of any Unicode scalar value.
     *
     * This is equivalent to `(? su:.)` and also `\p{any}`.
     */
    public data object AnyChar : Dot()
    /**
     * Matches any byte value.
     *
     * This is equivalent to `(? s - u:.)` and also `(?-u:[\x00-\xFF])`.
     */
    public data object AnyByte : Dot()
    /**
     * Matches the UTF-8 encoding of any Unicode scalar value except for the
     * `char` given.
     *
     * This is equivalent to using `(? u - s:.)` with the line terminator set
     * to a particular ASCII byte. (Because of peculiarities in the regex
     * engines, a line terminator must be a single byte. It follows that when
     * UTF-8 mode is enabled, this single byte must also be a Unicode scalar
     * value. That is, ti must be ASCII.)
     *
     * (This and `AnyCharExceptLF` both exist because of legacy reasons.
     * `AnyCharExceptLF` will be dropped in the next breaking change release.)
     */
    public data class AnyCharExcept(public var value: UInt) : Dot()
    /**
     * Matches the UTF-8 encoding of any Unicode scalar value except for `\n`.
     *
     * This is equivalent to `(? u - s:.)` and also `[\p{any}--\n]`.
     */
    public data object AnyCharExceptLF : Dot()
    /**
     * Matches the UTF-8 encoding of any Unicode scalar value except for `\r`
     * and `\n`.
     *
     * This is equivalent to `(? uR - s:.)` and also `[\p{any}--\r\n]`.
     */
    public data object AnyCharExceptCRLF : Dot()
    /**
     * Matches any byte value except for the `UByte` given.
     *
     * This is equivalent to using `(? - us:.)` with the line terminator set
     * to a particular ASCII byte. (Because of peculiarities in the regex
     * engines, a line terminator must be a single byte. It follows that when
     * UTF-8 mode is enabled, this single byte must also be a Unicode scalar
     * value. That is, ti must be ASCII.)
     *
     * (This and `AnyByteExceptLF` both exist because of legacy reasons.
     * `AnyByteExceptLF` will be dropped in the next breaking change release.)
     */
    public data class AnyByteExcept(public var value: UByte) : Dot()
    /**
     * Matches any byte value except for `\n`.
     *
     * This is equivalent to `(? - su:.)` and also `(?-u:[[\x00-\xFF]--\n])`.
     */
    public data object AnyByteExceptLF : Dot()
    /**
     * Matches any byte value except for `\r` and `\n`.
     *
     * This is equivalent to `(? R - su:.)` and also `(?-u:[[\x00-\xFF]--\r\n])`.
     */
    public data object AnyByteExceptCRLF : Dot()

    public fun rustEquals(other: Dot): Boolean = this == other
}
