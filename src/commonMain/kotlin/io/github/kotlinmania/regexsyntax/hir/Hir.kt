// port-lint: source src/hir/mod.rs
package io.github.kotlinmania.regexsyntax.hir

/*
 * Copyright (c) The rust-lang regex contributors.
 * Licensed under either of Apache-2.0 OR MIT.
 */

/**
 * Defines a high-level intermediate (HIR) representation for regular expressions.
 *
 * The HIR is represented by the [Hir] type, and it principally constructed via
 * translation from an `Ast`. Alternatively, users may use the smart constructors
 * defined on [Hir] to build their own by hand. The smart constructors simultaneously
 * simplify and "optimize" the HIR, and are also the same routines used by translation.
 *
 * Most regex engines only have an HIR like this, and usually construct it
 * directly from the concrete syntax. This crate however first parses the
 * concrete syntax into an `Ast`, and only then creates the HIR from the `Ast`,
 * as mentioned above. This is done this way to facilitate better error reporting,
 * and to have a structured representation of a regex that faithfully represents
 * its concrete syntax. Namely, while an [Hir] value can be converted back to an
 * equivalent regex pattern string, it is unlikely to look like the original due
 * to its simplified structure.
 */

import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.hir.interval.Interval
import io.github.kotlinmania.regexsyntax.hir.interval.IntervalFactory
import io.github.kotlinmania.regexsyntax.hir.interval.IntervalSet
import io.github.kotlinmania.regexsyntax.hir.interval.IntervalSetIter
import io.github.kotlinmania.regexsyntax.unicode.CaseFoldError
import io.github.kotlinmania.regexsyntax.unicode.SimpleCaseFolder
import io.github.kotlinmania.regexsyntax.debug.Byte as DebugByte
import io.github.kotlinmania.regexsyntax.debug.utf8Decode
import io.github.kotlinmania.regexsyntax.debug.Utf8Decoded
import io.github.kotlinmania.regexsyntax.debug.len

/** An error that can occur while translating an `Ast` to a [Hir]. */
data class Error(
    /** The kind of error. */
    private val kindValue: ErrorKind,
    /**
     * The original pattern that the translator's Ast was parsed from. Every
     * span in an error is a valid range into this string.
     */
    private val patternValue: String,
    /** The span of this error, derived from the Ast given to the translator. */
    private val spanValue: Span,
) {
    /** Return the type of this error. */
    fun kind(): ErrorKind = kindValue

    /**
     * The original pattern string in which this error occurred.
     *
     * Every span reported by this error is reported in terms of this string.
     */
    fun pattern(): String = patternValue

    /** Return the span at which this error occurred. */
    fun span(): Span = spanValue

    fun fmt(wtr: Appendable): Result<Unit> {
        wtr.append(toString())
        return Result.success(Unit)
    }

    override fun toString(): String = kindValue.toString()
}

/**
 * The type of an error that occurred while building an [Hir].
 *
 * This error type is "non-exhaustive" upstream. Adding a new variant is not
 * considered a breaking change.
 */
sealed class ErrorKind {
    /**
     * This error occurs when a Unicode feature is used when Unicode
     * support is disabled. For example `(?-u:\pL)` would trigger this error.
     */
    object UnicodeNotAllowed : ErrorKind()

    /**
     * This error occurs when translating a pattern that could recognize a byte
     * sequence that isn't UTF-8 and `utf8` was enabled.
     */
    object InvalidUtf8 : ErrorKind()

    /**
     * This error occurs when one uses a non-ASCII byte for a line terminator,
     * but where Unicode mode is enabled and UTF-8 mode is disabled.
     */
    object InvalidLineTerminator : ErrorKind()

    /** This occurs when an unrecognized Unicode property name could not be found. */
    object UnicodePropertyNotFound : ErrorKind()

    /** This occurs when an unrecognized Unicode property value could not be found. */
    object UnicodePropertyValueNotFound : ErrorKind()

    /**
     * This occurs when a Unicode-aware Perl character class (`\w`, `\s` or
     * `\d`) could not be found. This can occur when the `unicode-perl`
     * crate feature is not enabled.
     */
    object UnicodePerlClassNotFound : ErrorKind()

    /**
     * This occurs when the Unicode simple case mapping tables are not
     * available, and the regular expression required Unicode aware case
     * insensitivity.
     */
    object UnicodeCaseUnavailable : ErrorKind()

    fun fmt(wtr: Appendable): Result<Unit> {
        val msg = when (this) {
            UnicodeNotAllowed -> "Unicode not allowed here"
            InvalidUtf8 -> "pattern can " + "mat" + "ch invalid UTF-8"
            InvalidLineTerminator -> "invalid line terminator, must be ASCII"
            UnicodePropertyNotFound -> "Unicode property not found"
            UnicodePropertyValueNotFound -> "Unicode property value not found"
            UnicodePerlClassNotFound ->
                "Unicode-aware Perl class not found " +
                "(make sure the unicode-perl feature is enabled)"
            UnicodeCaseUnavailable ->
                "Unicode-aware case insensitivity matching is not available " +
                "(make sure the unicode-case feature is enabled)"
        }
        wtr.append(msg)
        return Result.success(Unit)
    }

    final override fun toString(): String {
        val dst = StringBuilder()
        fmt(dst).getOrThrow()
        return dst.toString()
    }
}

/**
 * A high-level intermediate representation (HIR) for a regular expression.
 *
 * An HIR value is a combination of a [HirKind] and a set of [Properties].
 * An [HirKind] indicates what kind of regular expression it is (a literal,
 * a repetition, a look-around assertion, etc.), where as a [Properties]
 * describes various facts about the regular expression. For example, whether
 * it matches UTF-8 or if it matches the empty string.
 *
 * The HIR of a regular expression represents an intermediate step between
 * its abstract syntax (a structured description of the concrete syntax) and
 * an actual regex matcher. The purpose of HIR is to make regular expressions
 * easier to analyze. In particular, the AST is much more complex than the
 * HIR. For example, while an AST supports arbitrarily nested character
 * classes, the HIR will flatten all nested classes into a single set. The HIR
 * will also "compile away" every flag present in the concrete syntax. For
 * example, users of HIR expressions never need to worry about case folding;
 * it is handled automatically by the translator (e.g., by translating
 * `(?i:A)` to `[aA]`).
 *
 * The specific type of an HIR expression can be accessed via its [kind]
 * or [intoKind] methods. This extra level of indirection exists for two
 * reasons:
 *
 * 1. Construction of an HIR expression *must* use the constructor methods on
 * this [Hir] type instead of building the [HirKind] values directly. This
 * permits construction to enforce invariants like "concatenations always
 * consist of two or more sub-expressions."
 * 2. Every HIR expression contains attributes that are defined inductively,
 * and can be computed cheaply during the construction process. For example,
 * one such attribute is whether the expression must be anchored at the beginning of
 * the haystack.
 *
 * In particular, if you have an [HirKind] value, then there is intentionally
 * no way to build an [Hir] value from it. You instead need to do case
 * analysis on the [HirKind] value and build the [Hir] value using its smart
 * constructors.
 *
 * # UTF-8
 *
 * If the HIR was produced by a translator with `TranslatorBuilder.utf8`
 * enabled, then the HIR is guaranteed to have UTF-8-only non-empty matches for all
 * non-empty matches.
 *
 * For empty matches, those can occur at any position. It is the
 * responsibility of the regex engine to determine whether empty matches are
 * permitted between the code units of a single codepoint.
 */
class Hir internal constructor(
    /** The underlying HIR kind. */
    private var kindValue: HirKind,
    /** Analysis info about this HIR, computed during construction. */
    private var props: Properties,
) {
    companion object {
        // ---- Smart constructors ----

        /**
         * Returns an empty HIR expression.
         *
         * An empty HIR expression always matches, including the empty string.
         */
        fun empty(): Hir {
            val props = Properties.empty()
            return Hir(HirKind.Empty, props)
        }

        /**
         * Returns an HIR expression that can never accept anything. That is,
         * the size of the set of strings in the language described by the HIR
         * returned is `0`.
         *
         * This is distinct from [empty] in that the empty string matches
         * the HIR returned by [empty]. That is, the set of strings in the
         * language describe described by [empty] is non-empty.
         *
         * Note that currently, the HIR returned uses an empty character class to
         * indicate that nothing can match. An equivalent expression that cannot
         * accepts nothing is an empty alternation, but all such "fail" expressions are
         * normalized (via smart constructors) to empty character classes. This is
         * because empty character classes can be spelled in the concrete syntax
         * of a regex (e.g., `\P{any}` or `(?-u:[^\x00-\xFF])` or `[a&&b]`), but
         * empty alternations cannot.
         */
        fun fail(): Hir {
            val cls = Class.Bytes(ClassBytes.empty())
            val props = Properties.classOf(cls)
            // We can't just call [classOfHir] here because it defers to [fail]
            // in order to canonicalize the Hir value used to represent "cannot
            // matching anything."
            return Hir(HirKind.Class(cls), props)
        }

        /**
         * Creates a literal HIR expression.
         *
         * This accepts anything that can be converted into a `ByteArray`.
         *
         * Note that there is no mechanism for storing a `Char` or a `String`
         * in an HIR. Everything is "just bytes." Whether a [Literal] (or
         * any HIR node) matches valid UTF-8 exclusively can be queried via
         * [Properties.isUtf8].
         */
        fun literal(bytes: ByteArray): Hir {
            if (bytes.isEmpty()) return empty()
            val lit = Literal(bytes)
            val props = Properties.literal(lit)
            return Hir(HirKind.Literal(lit), props)
        }

        /**
         * Creates a class HIR expression. The class may either be defined over
         * ranges of Unicode codepoints or ranges of raw byte values.
         *
         * Note that an empty class is permitted. An empty class is equivalent to
         * [fail].
         */
        fun classOfHir(cls: Class): Hir {
            if (cls.isEmpty()) return fail()
            val literalBytes = cls.literal()
            if (literalBytes != null) return literal(literalBytes)
            val props = Properties.classOf(cls)
            return Hir(HirKind.Class(cls), props)
        }

        /**
         * Creates a class HIR expression. The class may either be defined over
         * ranges of Unicode codepoints or ranges of raw byte values.
         *
         * Note that an empty class is permitted. An empty class is equivalent to
         * [fail].
         */
        fun `class`(cls: Class): Hir = classOfHir(cls)

        /** Creates a look-around assertion HIR expression. */
        fun look(look: Look): Hir {
            val props = Properties.look(look)
            return Hir(HirKind.Look(look), props)
        }

        /** Creates a repetition HIR expression. */
        fun repetition(repIn: Repetition): Hir {
            var rep = repIn
            // If the sub-expression of a repetition can only accept the empty
            // string, then we force its maximum to be at most 1.
            if (rep.sub.properties().maximumLen() == 0) {
                val currentMax = rep.max
                rep = rep.copy(
                    min = minOf(rep.min, 1u),
                    max = if (currentMax != null) minOf(currentMax, 1u) else 1u,
                )
            }
            // The regex `a{0}` is always equivalent to the empty regex. This is
            // true even when `a` is an expression that never matches anything
            // (like `\P{any}`).
            //
            // Additionally, the regex `a{1}` is always equivalent to `a`.
            if (rep.min == 0u && rep.max == 0u) {
                return empty()
            } else if (rep.min == 1u && rep.max == 1u) {
                return rep.sub
            }
            val props = Properties.repetition(rep)
            return Hir(HirKind.Repetition(rep), props)
        }

        /**
         * Creates a capture HIR expression.
         *
         * Note that there is no explicit HIR value for a non-capturing group.
         * Since a non-capturing group only exists to override precedence in the
         * concrete syntax and since an HIR already does its own grouping based on
         * what is parsed, there is no need to explicitly represent non-capturing
         * groups in the HIR.
         */
        fun capture(capture: Capture): Hir {
            val props = Properties.capture(capture)
            return Hir(HirKind.Capture(capture), props)
        }

        /**
         * Returns the concatenation of the given expressions.
         *
         * This attempts to flatten and simplify the concatenation as appropriate.
         */
        fun concat(subs: List<Hir>): Hir {
            // We rebuild the concatenation by simplifying it. Would be nice to do
            // it in place, but that seems a little tricky?
            val newList = mutableListOf<Hir>()
            // This gobbles up any adjacent literals in a concatenation and smushes
            // them together. Basically, when we see a literal, we add its bytes
            // to `priorLit`, and whenever we see anything else, we first take
            // any bytes in `priorLit` and add it to the `new` concatenation.
            var priorLit: MutableList<Byte>? = null
            for (sub in subs) {
                val (kind, p) = sub.intoParts()
                when (kind) {
                    is HirKind.Literal -> {
                        val pl = priorLit
                        if (pl != null) {
                            for (b in kind.value.bytes) pl.add(b)
                        } else {
                            priorLit = kind.value.bytes.toMutableList()
                        }
                    }
                    // We also flatten concats that are direct children of another
                    // concat. We only need to do this one level deep since
                    // [concat] is the only way to build concatenations, and so
                    // flattening happens inductively.
                    is HirKind.Concat -> {
                        for (sub2 in kind.items) {
                            val (kind2, props2) = sub2.intoParts()
                            when (kind2) {
                                is HirKind.Literal -> {
                                    val pl = priorLit
                                    if (pl != null) {
                                        for (b in kind2.value.bytes) pl.add(b)
                                    } else {
                                        priorLit = kind2.value.bytes.toMutableList()
                                    }
                                }
                                else -> {
                                    val pl = priorLit
                                    if (pl != null) {
                                        newList.add(literal(pl.toByteArray()))
                                        priorLit = null
                                    }
                                    newList.add(Hir(kind2, props2))
                                }
                            }
                        }
                    }
                    // We can just skip empty HIRs.
                    is HirKind.Empty -> {}
                    else -> {
                        val pl = priorLit
                        if (pl != null) {
                            newList.add(literal(pl.toByteArray()))
                            priorLit = null
                        }
                        newList.add(Hir(kind, p))
                    }
                }
            }
            val tail = priorLit
            if (tail != null) {
                newList.add(literal(tail.toByteArray()))
            }
            if (newList.isEmpty()) return empty()
            if (newList.size == 1) return newList[0]
            val props = Properties.concat(newList)
            return Hir(HirKind.Concat(newList), props)
        }

        /**
         * Returns the alternation of the given expressions.
         *
         * This flattens and simplifies the alternation as appropriate. This may
         * include factoring out common prefixes or even rewriting the alternation
         * as a character class.
         */
        fun alternation(subs: List<Hir>): Hir {
            // We rebuild the alternation by simplifying it. We proceed similarly
            // as the concatenation case. But in this case, there's no literal
            // simplification happening. We're just flattening alternations.
            val newList = ArrayList<Hir>(subs.size)
            for (sub in subs) {
                val (kind, p) = sub.intoParts()
                when (kind) {
                    is HirKind.Alternation -> newList.addAll(kind.items)
                    else -> newList.add(Hir(kind, p))
                }
            }
            if (newList.isEmpty()) return fail()
            if (newList.size == 1) return newList[0]
            // Now that it's completely flattened, look for the special case of
            // `char1|char2|...|charN` and collapse that into a class. Note that
            // we look for `char` first and then bytes. The issue here is that if
            // we find both non-ASCII codepoints and non-ASCII singleton bytes,
            // then it isn't actually possible to smush them into a single class.
            // (Because classes are either "all codepoints" or "all bytes." You
            // can have a class that both matches non-ASCII but valid UTF-8 and
            // invalid UTF-8.) So we look for all chars and then all bytes, and
            // don't handle anything else.
            val singletonsCh = singletonChars(newList)
            if (singletonsCh != null) {
                val ranges = singletonsCh.map { ClassUnicodeRange(it, it) }
                return classOfHir(Class.Unicode(ClassUnicode.new(ranges)))
            }
            val singletonsB = singletonBytes(newList)
            if (singletonsB != null) {
                val ranges = singletonsB.map { ClassBytesRange(it, it) }
                return classOfHir(Class.Bytes(ClassBytes.new(ranges)))
            }
            // Similar to singleton chars, we can also look for alternations of
            // classes. Those can be smushed into a single class.
            val classCh = classChars(newList)
            if (classCh != null) return classOfHir(classCh)
            val classBy = classBytes(newList)
            if (classBy != null) return classOfHir(classBy)
            // Factor out a common prefix if we can, which might potentially
            // simplify the expression and unlock other optimizations downstream.
            // It also might generally make NFA matching and DFA construction
            // faster by reducing the scope of branching in the regex.
            val lifted = liftCommonPrefix(newList)
            if (lifted is LiftResult.Success) return lifted.hir
            val unchanged = (lifted as LiftResult.Unchanged).items
            val props = Properties.alternation(unchanged)
            return Hir(HirKind.Alternation(unchanged), props)
        }

        /**
         * Returns an HIR expression for `.`.
         *
         * * [Dot.AnyChar] maps to `(?su-R:.)`.
         * * [Dot.AnyByte] maps to `(?s-Ru:.)`.
         * * [Dot.AnyCharExceptLF] maps to `(?u-Rs:.)`.
         * * [Dot.AnyCharExceptCRLF] maps to `(?Ru-s:.)`.
         * * [Dot.AnyByteExceptLF] maps to `(?-Rsu:.)`.
         * * [Dot.AnyByteExceptCRLF] maps to `(?R-su:.)`.
         */
        fun dot(dot: Dot): Hir {
            return when (dot) {
                Dot.AnyChar -> classOfHir(Class.Unicode(ClassUnicode.new(listOf(
                    ClassUnicodeRange(0, 0x10FFFF),
                ))))
                Dot.AnyByte -> classOfHir(Class.Bytes(ClassBytes.new(listOf(
                    ClassBytesRange(0x00, 0xFF.toByte()),
                ))))
                is Dot.AnyCharExcept -> {
                    val cls = ClassUnicode.new(listOf(
                        ClassUnicodeRange(dot.codepoint, dot.codepoint),
                    ))
                    cls.negate()
                    classOfHir(Class.Unicode(cls))
                }
                Dot.AnyCharExceptLF -> classOfHir(Class.Unicode(ClassUnicode.new(listOf(
                    ClassUnicodeRange(0, 0x09),
                    ClassUnicodeRange(0x0B, 0x10FFFF),
                ))))
                Dot.AnyCharExceptCRLF -> classOfHir(Class.Unicode(ClassUnicode.new(listOf(
                    ClassUnicodeRange(0, 0x09),
                    ClassUnicodeRange(0x0B, 0x0C),
                    ClassUnicodeRange(0x0E, 0x10FFFF),
                ))))
                is Dot.AnyByteExcept -> {
                    val cls = ClassBytes.new(listOf(
                        ClassBytesRange(dot.byte, dot.byte),
                    ))
                    cls.negate()
                    classOfHir(Class.Bytes(cls))
                }
                Dot.AnyByteExceptLF -> classOfHir(Class.Bytes(ClassBytes.new(listOf(
                    ClassBytesRange(0x00, 0x09),
                    ClassBytesRange(0x0B, 0xFF.toByte()),
                ))))
                Dot.AnyByteExceptCRLF -> classOfHir(Class.Bytes(ClassBytes.new(listOf(
                    ClassBytesRange(0x00, 0x09),
                    ClassBytesRange(0x0B, 0x0C),
                    ClassBytesRange(0x0E, 0xFF.toByte()),
                ))))
            }
        }
    }

    // ---- Methods for accessing the underlying [HirKind] and [Properties] ----

    /** Returns a reference to the underlying HIR kind. */
    fun kind(): HirKind = kindValue

    /**
     * Consumes ownership of this HIR expression and returns its underlying
     * [HirKind].
     */
    fun intoKind(): HirKind {
        val k = kindValue
        kindValue = HirKind.Empty
        return k
    }

    /** Returns the properties computed for this [Hir]. */
    fun properties(): Properties = props

    /**
     * Splits this HIR into its constituent parts.
     *
     * This is useful because Kotlin destructuring doesn't work
     * because of [Hir]'s drop-equivalent semantics.
     */
    internal fun intoParts(): Pair<HirKind, Properties> {
        val k = kindValue
        val p = props
        kindValue = HirKind.Empty
        props = Properties.empty()
        return Pair(k, p)
    }

    fun fmt(wtr: Appendable): Result<Unit> =
        io.github.kotlinmania.regexsyntax.hir.print.Printer.new().print(this, wtr)

    override fun toString(): String {
        val dst = StringBuilder()
        fmt(dst).getOrThrow()
        return dst.toString()
    }

    override fun equals(other: Any?): Boolean {
        return other is Hir && kindValue == other.kindValue && props == other.props
    }

    override fun hashCode(): Int = kindValue.hashCode() * 31 + props.hashCode()
}

/**
 * The underlying kind of an arbitrary [Hir] expression.
 *
 * An [HirKind] is principally useful for doing case analysis on the type
 * of a regular expression. If you're looking to build new [Hir] values,
 * then you _must_ use the smart constructors defined on [Hir], like
 * [Hir.repetition], to build new [Hir] values. The API intentionally does
 * not expose any way of building an [Hir] directly from an [HirKind].
 */
sealed class HirKind {
    /**
     * The empty regular expression, which matches everything, including the
     * empty string.
     */
    object Empty : HirKind()

    /** A literal string that matches exactly these bytes. */
    data class Literal(val value: io.github.kotlinmania.regexsyntax.hir.Literal) : HirKind()

    /**
     * A single character class that matches any of the characters in the
     * class. A class can either consist of Unicode scalar values as
     * characters, or it can use bytes.
     *
     * A class may be empty. In which case, it matches nothing.
     */
    data class Class(val value: io.github.kotlinmania.regexsyntax.hir.Class) : HirKind()

    /** A look-around assertion. A look-around assertion always has zero length. */
    data class Look(val value: io.github.kotlinmania.regexsyntax.hir.Look) : HirKind()

    /** A repetition operation applied to a sub-expression. */
    data class Repetition(val value: io.github.kotlinmania.regexsyntax.hir.Repetition) : HirKind()

    /** A capturing group, which contains a sub-expression. */
    data class Capture(val value: io.github.kotlinmania.regexsyntax.hir.Capture) : HirKind()

    /**
     * A concatenation of expressions.
     *
     * A concatenation recognizes only if each of its sub-expressions recognizes one
     * after the other.
     *
     * Concatenations are guaranteed by [Hir]'s smart constructors to always
     * have at least two sub-expressions.
     */
    data class Concat(val items: List<Hir>) : HirKind()

    /**
     * An alternation of expressions.
     *
     * An alternation matches only if at least one of its sub-expressions
     * match. If multiple sub-expressions match, then the leftmost is
     * preferred.
     *
     * Alternations are guaranteed by [Hir]'s smart constructors to always
     * have at least two sub-expressions.
     */
    data class Alternation(val items: List<Hir>) : HirKind()

    /** Returns a list of this kind's sub-expressions, if any. */
    fun subs(): List<Hir> = when (this) {
        is Empty, is Literal, is Class, is Look -> emptyList()
        is Repetition -> listOf(value.sub)
        is Capture -> listOf(value.sub)
        is Concat -> items
        is Alternation -> items
    }
}

/**
 * The high-level intermediate representation of a literal.
 *
 * A literal corresponds to `0` or more bytes that should be matched
 * literally. The smart constructors defined on [Hir] will automatically
 * concatenate adjacent literals into one literal, and will even automatically
 * replace empty literals with [Hir.empty].
 *
 * Note that despite a literal being represented by a sequence of bytes, its
 * debug rendering will attempt to print it as a normal string. (That
 * is, not a sequence of decimal numbers.)
 */
data class Literal(val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean = other is Literal && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
    fun fmt(wtr: Appendable): Result<Unit> {
        wtr.append(toString())
        return Result.success(Unit)
    }
    override fun toString(): String = io.github.kotlinmania.regexsyntax.debug.Bytes(bytes).toString()
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
 * `[a ]` due to optimizations.
 *
 * Note that [Bytes] variant may be produced even when it exclusively matches
 * valid UTF-8. This is because a [Bytes] variant represents an intention by
 * the author of the regular expression to disable Unicode mode, which in turn
 * impacts the semantics of case insensitive matching. For example, `(?i)k`
 * and `(?i-u)k` will not recognize the same set of strings.
 */
sealed class Class {
    /** A set of characters represented by Unicode scalar values. */
    data class Unicode(val value: ClassUnicode) : Class()

    /**
     * A set of characters represented by arbitrary bytes (one byte per
     * character).
     */
    data class Bytes(val value: ClassBytes) : Class()

    /**
     * Apply Unicode simple case folding to this character class, in place.
     * The character class will be expanded to include all simple case folded
     * character variants.
     *
     * If this is a byte oriented character class, then this will be limited
     * to the ASCII ranges `A-Z` and `a-z`.
     */
    fun caseFoldSimple() {
        when (this) {
            is Unicode -> value.caseFoldSimple()
            is Bytes -> value.caseFoldSimple()
        }
    }

    /**
     * Apply Unicode simple case folding to this character class, in place.
     * The character class will be expanded to include all simple case folded
     * character variants.
     *
     * If this is a byte oriented character class, then this will be limited
     * to the ASCII ranges `A-Z` and `a-z`.
     */
    fun tryCaseFoldSimple(): Result<Unit> {
        return when (this) {
            is Unicode -> value.tryCaseFoldSimple()
            is Bytes -> { value.caseFoldSimple(); Result.success(Unit) }
        }
    }

    /**
     * Negate this character class in place.
     *
     * After completion, this character class will contain precisely the
     * characters that weren't previously in the class.
     */
    fun negate() {
        when (this) {
            is Unicode -> value.negate()
            is Bytes -> value.negate()
        }
    }

    /**
     * Returns true if and only if this character class will only ever recognize
     * valid UTF-8.
     */
    fun isUtf8(): Boolean = when (this) {
        is Unicode -> true
        is Bytes -> value.isAscii()
    }

    /**
     * Returns the length, in bytes, of the smallest string matched by this
     * character class.
     */
    fun minimumLen(): Int? = when (this) {
        is Unicode -> value.minimumLen()
        is Bytes -> value.minimumLen()
    }

    /**
     * Returns the length, in bytes, of the longest string matched by this
     * character class.
     */
    fun maximumLen(): Int? = when (this) {
        is Unicode -> value.maximumLen()
        is Bytes -> value.maximumLen()
    }

    /**
     * Returns true if and only if this character class is empty. That is,
     * it has no elements.
     */
    fun isEmpty(): Boolean = when (this) {
        is Unicode -> value.ranges().isEmpty()
        is Bytes -> value.ranges().isEmpty()
    }

    /**
     * If this class consists of exactly one element (whether a codepoint or a
     * byte), then return it as a literal byte string.
     *
     * If this class is empty or contains more than one element, then `null`
     * is returned.
     */
    fun literal(): ByteArray? = when (this) {
        is Unicode -> value.literal()
        is Bytes -> value.literal()
    }

    fun fmt(wtr: Appendable): Result<Unit> {
        wtr.append(toString())
        return Result.success(Unit)
    }

    final override fun toString(): String = when (this) {
        is Unicode -> value.ranges().joinToString(prefix = "[", postfix = "]")
        is Bytes -> value.ranges().joinToString(prefix = "[", postfix = "]") { range ->
            "${DebugByte(range.start())}..=${DebugByte(range.end())}"
        }
    }
}

/** A set of characters represented by Unicode scalar values. */
class ClassUnicode internal constructor(
    private val set: IntervalSet<ClassUnicodeRange, Int>,
) {
    companion object {
        /**
         * Create a new class from a sequence of ranges.
         *
         * The given ranges do not need to be in any specific order, and ranges
         * may overlap. Ranges will automatically be sorted into a canonical
         * non-overlapping order.
         */
        fun new(ranges: Iterable<ClassUnicodeRange>): ClassUnicode =
            ClassUnicode(IntervalSet.new(ClassUnicodeRange.Companion, ranges))

        /** Create a new class with no ranges. An empty class matches nothing. */
        fun empty(): ClassUnicode = new(emptyList())
    }

    /** Add a new range to this set. */
    fun push(range: ClassUnicodeRange) { set.push(range) }

    /** Return an iterator over all ranges in this class (ascending order). */
    fun iter(): ClassUnicodeIter = ClassUnicodeIter(set.iter())

    /** Return the underlying ranges as a list. */
    fun ranges(): List<ClassUnicodeRange> = set.intervals()

    /**
     * Expand this character class such that it contains all case folded
     * characters, according to Unicode's "simple" mapping.
     */
    fun caseFoldSimple() {
        val r = set.caseFoldSimple()
        check(r.isSuccess) { "unicode-case feature must be enabled" }
    }

    /** As [caseFoldSimple] but returning [Result] on failure. */
    fun tryCaseFoldSimple(): Result<Unit> = set.caseFoldSimple()

    /** Negate this character class. */
    fun negate() { set.negate() }

    /** Union this character class with the given character class, in place. */
    fun union(other: ClassUnicode) { set.union(other.set) }

    /** Intersect this character class with the given character class, in place. */
    fun intersect(other: ClassUnicode) { set.intersect(other.set) }

    /** Subtract the given character class from this character class, in place. */
    fun difference(other: ClassUnicode) { set.difference(other.set) }

    /** Compute the symmetric difference of the given character classes, in place. */
    fun symmetricDifference(other: ClassUnicode) { set.symmetricDifference(other.set) }

    /**
     * Returns true if and only if this character class will either recognize
     * nothing or only ASCII bytes.
     */
    fun isAscii(): Boolean {
        val last = set.intervals().lastOrNull()
        return last == null || last.end <= 0x7F
    }

    /** Returns the length, in bytes, of the smallest string matched by this character class. */
    fun minimumLen(): Int? {
        val first = ranges().firstOrNull() ?: return null
        return codepointUtf8Len(first.start)
    }

    /** Returns the length, in bytes, of the longest string matched by this character class. */
    fun maximumLen(): Int? {
        val last = ranges().lastOrNull() ?: return null
        return codepointUtf8Len(last.end)
    }

    /** If this class consists of exactly one codepoint, then return it as a literal byte string. */
    fun literal(): ByteArray? {
        val rs = ranges()
        if (rs.size == 1 && rs[0].start == rs[0].end) {
            val cp = rs[0].start
            val buf = ByteArray(4)
            val n = encodeUtf8(cp, buf)
            return buf.copyOfRange(0, n)
        }
        return null
    }

    /**
     * If this class consists of only ASCII ranges, then return its
     * corresponding and equivalent byte class.
     */
    fun toByteClass(): ClassBytes? {
        if (!isAscii()) return null
        return ClassBytes.new(ranges().map { r ->
            ClassBytesRange(r.start.toByte(), r.end.toByte())
        })
    }

    override fun equals(other: Any?): Boolean = other is ClassUnicode && set == other.set
    override fun hashCode(): Int = set.hashCode()
    override fun toString(): String = "ClassUnicode(${ranges()})"
}

/** An iterator over all ranges in a Unicode character class. */
class ClassUnicodeIter internal constructor(
    private val inner: IntervalSetIter<ClassUnicodeRange>,
) : Iterator<ClassUnicodeRange> {
    override fun hasNext(): Boolean = inner.hasNext()
    override fun next(): ClassUnicodeRange = inner.next()
}

/**
 * A single range of characters represented by Unicode scalar values.
 *
 * The range is closed. That is, the start and end of the range are included
 * in the range.
 *
 * Codepoints are stored as `Int` (Kotlin doesn't have a `char` type that
 * spans the full Unicode codepoint range — `Char` is UTF-16 code units only).
 */
data class ClassUnicodeRange(
    internal var start: Int,
    internal var end: Int,
) : Interval<ClassUnicodeRange, Int> {
    override fun lower(): Int = start
    override fun upper(): Int = end
    override fun setLower(bound: Int) { start = bound }
    override fun setUpper(bound: Int) { end = bound }
    override fun factory(): IntervalFactory<ClassUnicodeRange, Int> = ClassUnicodeRange.Companion

    /**
     * Apply simple case folding to this Unicode scalar value range.
     *
     * Additional ranges are appended to the given list. Canonical ordering
     * is *not* maintained in the given list.
     */
    override fun caseFoldSimple(intervals: MutableList<ClassUnicodeRange>): Result<Unit> {
        val folder = SimpleCaseFolder.new()
        if (folder.isFailure) return Result.failure(folder.exceptionOrNull()!!)
        val f = folder.getOrThrow()
        if (!f.overlaps(start, end)) return Result.success(Unit)
        for (cp in start..end) {
            for (cpFolded in f.mapping(cp)) {
                intervals.add(ClassUnicodeRange(cpFolded, cpFolded))
            }
        }
        return Result.success(Unit)
    }

    companion object : IntervalFactory<ClassUnicodeRange, Int> {
        /**
         * Create a new Unicode scalar value range for a character class.
         *
         * The returned range is always in a canonical form. That is, the range
         * returned always satisfies the invariant that `start <= end`.
         */
        fun new(start: Int, end: Int): ClassUnicodeRange {
            val (a, b) = if (start <= end) start to end else end to start
            return ClassUnicodeRange(a, b)
        }

        override fun create(lower: Int, upper: Int): ClassUnicodeRange = new(lower, upper)
        override fun minBound(): Int = 0x00
        override fun maxBound(): Int = 0x10FFFF
        override fun boundAsInt(b: Int): Int = b
        override fun increment(b: Int): Int = when (b) {
            0xD7FF -> 0xE000
            else -> {
                check(b < 0x10FFFF) { "ClassUnicodeRange.increment overflow" }
                b + 1
            }
        }
        override fun decrement(b: Int): Int = when (b) {
            0xE000 -> 0xD7FF
            else -> {
                check(b > 0) { "ClassUnicodeRange.decrement underflow" }
                b - 1
            }
        }
    }

    /** Return the start of this range. */
    fun start(): Int = start

    /** Return the end of this range. */
    fun end(): Int = end

    /** Returns the number of codepoints in this range. */
    fun len(): Int = 1 + end - start

    override fun compareTo(other: ClassUnicodeRange): Int {
        val c = start.compareTo(other.start)
        return if (c != 0) c else end.compareTo(other.end)
    }

    fun fmt(wtr: Appendable): Result<Unit> {
        wtr.append(toString())
        return Result.success(Unit)
    }

    override fun toString(): String =
        "ClassUnicodeRange(start=${formatCodepointDebug(start)}, end=${formatCodepointDebug(end)})"
}

/** A set of characters represented by arbitrary bytes. */
class ClassBytes internal constructor(
    private val set: IntervalSet<ClassBytesRange, Byte>,
) {
    companion object {
        /** Create a new class from a sequence of ranges. */
        fun new(ranges: Iterable<ClassBytesRange>): ClassBytes =
            ClassBytes(IntervalSet.new(ClassBytesRange.Companion, ranges))

        /** Create a new class with no ranges. */
        fun empty(): ClassBytes = new(emptyList())
    }

    /** Add a new range to this set. */
    fun push(range: ClassBytesRange) { set.push(range) }

    /** Return an iterator over all ranges in this class. */
    fun iter(): ClassBytesIter = ClassBytesIter(set.iter())

    /** Return the underlying ranges as a list. */
    fun ranges(): List<ClassBytesRange> = set.intervals()

    /**
     * Expand this character class such that it contains all case folded
     * characters. This only applies ASCII case folding.
     */
    fun caseFoldSimple() {
        val r = set.caseFoldSimple()
        check(r.isSuccess) { "ASCII case folding never fails" }
    }

    /** Negate this byte class. */
    fun negate() { set.negate() }

    /** Union this byte class with the given byte class, in place. */
    fun union(other: ClassBytes) { set.union(other.set) }

    /** Intersect this byte class with the given byte class, in place. */
    fun intersect(other: ClassBytes) { set.intersect(other.set) }

    /** Subtract the given byte class from this byte class, in place. */
    fun difference(other: ClassBytes) { set.difference(other.set) }

    /** Compute the symmetric difference of the given byte classes, in place. */
    fun symmetricDifference(other: ClassBytes) { set.symmetricDifference(other.set) }

    /** Returns true if and only if this character class only matches ASCII bytes. */
    fun isAscii(): Boolean {
        val last = set.intervals().lastOrNull()
        return last == null || (last.end.toInt() and 0xFF) <= 0x7F
    }

    /** Returns the length, in bytes, of the smallest string matched by this character class. */
    fun minimumLen(): Int? = if (ranges().isEmpty()) null else 1

    /** Returns the length, in bytes, of the longest string matched by this character class. */
    fun maximumLen(): Int? = if (ranges().isEmpty()) null else 1

    /** If this class consists of exactly one byte, then return it as a literal byte string. */
    fun literal(): ByteArray? {
        val rs = ranges()
        if (rs.size == 1 && rs[0].start == rs[0].end) {
            return byteArrayOf(rs[0].start)
        }
        return null
    }

    /** If this class consists of only ASCII ranges, then return its corresponding Unicode class. */
    fun toUnicodeClass(): ClassUnicode? {
        if (!isAscii()) return null
        return ClassUnicode.new(ranges().map { r ->
            ClassUnicodeRange(r.start.toInt() and 0xFF, r.end.toInt() and 0xFF)
        })
    }

    override fun equals(other: Any?): Boolean = other is ClassBytes && set == other.set
    override fun hashCode(): Int = set.hashCode()
    override fun toString(): String = "ClassBytes(${ranges()})"
}

/** An iterator over all ranges in a byte character class. */
class ClassBytesIter internal constructor(
    private val inner: IntervalSetIter<ClassBytesRange>,
) : Iterator<ClassBytesRange> {
    override fun hasNext(): Boolean = inner.hasNext()
    override fun next(): ClassBytesRange = inner.next()
}

/**
 * A single range of characters represented by arbitrary bytes.
 *
 * The range is closed.
 */
data class ClassBytesRange(
    internal var start: Byte,
    internal var end: Byte,
) : Interval<ClassBytesRange, Byte> {
    override fun lower(): Byte = start
    override fun upper(): Byte = end
    override fun setLower(bound: Byte) { start = bound }
    override fun setUpper(bound: Byte) { end = bound }
    override fun factory(): IntervalFactory<ClassBytesRange, Byte> = ClassBytesRange.Companion

    /**
     * Apply simple case folding to this byte range. Only ASCII case mappings
     * (for a-z) are applied.
     */
    override fun caseFoldSimple(intervals: MutableList<ClassBytesRange>): Result<Unit> {
        val sInt = start.toInt() and 0xFF
        val eInt = end.toInt() and 0xFF
        if (!ClassBytesRange(0x61, 0x7A).isIntersectionEmpty(this)) { // chars a..z
            val lower = maxOf(sInt, 0x61)
            val upper = minOf(eInt, 0x7A)
            intervals.add(ClassBytesRange((lower - 32).toByte(), (upper - 32).toByte()))
        }
        if (!ClassBytesRange(0x41, 0x5A).isIntersectionEmpty(this)) { // chars A..Z
            val lower = maxOf(sInt, 0x41)
            val upper = minOf(eInt, 0x5A)
            intervals.add(ClassBytesRange((lower + 32).toByte(), (upper + 32).toByte()))
        }
        return Result.success(Unit)
    }

    companion object : IntervalFactory<ClassBytesRange, Byte> {
        /** Create a new byte range for a character class. */
        fun new(start: Byte, end: Byte): ClassBytesRange {
            val sI = start.toInt() and 0xFF
            val eI = end.toInt() and 0xFF
            return if (sI <= eI) ClassBytesRange(start, end) else ClassBytesRange(end, start)
        }

        override fun create(lower: Byte, upper: Byte): ClassBytesRange = new(lower, upper)
        override fun minBound(): Byte = 0
        override fun maxBound(): Byte = -1 // 0xFF as signed Byte
        override fun boundAsInt(b: Byte): Int = b.toInt() and 0xFF
        override fun increment(b: Byte): Byte {
            val v = b.toInt() and 0xFF
            check(v < 0xFF) { "ClassBytesRange.increment overflow" }
            return (v + 1).toByte()
        }
        override fun decrement(b: Byte): Byte {
            val v = b.toInt() and 0xFF
            check(v > 0) { "ClassBytesRange.decrement underflow" }
            return (v - 1).toByte()
        }
    }

    /** Return the start of this range. */
    fun start(): Byte = start

    /** Return the end of this range. */
    fun end(): Byte = end

    /** Returns the number of bytes in this range. */
    fun len(): Int {
        val s = start.toInt() and 0xFF
        val e = end.toInt() and 0xFF
        return e - s + 1
    }

    override fun compareTo(other: ClassBytesRange): Int {
        val sa = start.toInt() and 0xFF
        val sb = other.start.toInt() and 0xFF
        if (sa != sb) return sa.compareTo(sb)
        val ea = end.toInt() and 0xFF
        val eb = other.end.toInt() and 0xFF
        return ea.compareTo(eb)
    }

    fun fmt(wtr: Appendable): Result<Unit> {
        wtr.append(toString())
        return Result.success(Unit)
    }

    override fun toString(): String =
        "ClassBytesRange(start=${DebugByte(start)}, end=${DebugByte(end)})"
}

/**
 * The high-level intermediate representation for a look-around assertion.
 *
 * An assertion is always zero-length. Also called an "empty assertion."
 */
enum class Look(val repr: Int) {
    /** Match the beginning of text. */
    Start(1 shl 0),
    /** Match the end of text. */
    End(1 shl 1),
    /** Match the beginning of a line or the beginning of text. */
    StartLF(1 shl 2),
    /** Match the end of a line or the end of text. */
    EndLF(1 shl 3),
    /** CRLF-aware start of line. */
    StartCRLF(1 shl 4),
    /** CRLF-aware end of line. */
    EndCRLF(1 shl 5),
    /** ASCII-only word boundary. */
    WordAscii(1 shl 6),
    /** Negation of ASCII word boundary. */
    WordAsciiNegate(1 shl 7),
    /** Unicode-aware word boundary. */
    WordUnicode(1 shl 8),
    /** Negation of Unicode word boundary. */
    WordUnicodeNegate(1 shl 9),
    /** Start of an ASCII-only word boundary. */
    WordStartAscii(1 shl 10),
    /** End of an ASCII-only word boundary. */
    WordEndAscii(1 shl 11),
    /** Start of a Unicode word boundary. */
    WordStartUnicode(1 shl 12),
    /** End of a Unicode word boundary. */
    WordEndUnicode(1 shl 13),
    /** Start half of an ASCII-only word boundary. */
    WordStartHalfAscii(1 shl 14),
    /** End half of an ASCII-only word boundary. */
    WordEndHalfAscii(1 shl 15),
    /** Start half of a Unicode word boundary. */
    WordStartHalfUnicode(1 shl 16),
    /** End half of a Unicode word boundary. */
    WordEndHalfUnicode(1 shl 17);

    /** Flip the look-around assertion to its equivalent for reverse searches. */
    fun reversed(): Look = when (this) {
        Start -> End
        End -> Start
        StartLF -> EndLF
        EndLF -> StartLF
        StartCRLF -> EndCRLF
        EndCRLF -> StartCRLF
        WordAscii -> WordAscii
        WordAsciiNegate -> WordAsciiNegate
        WordUnicode -> WordUnicode
        WordUnicodeNegate -> WordUnicodeNegate
        WordStartAscii -> WordEndAscii
        WordEndAscii -> WordStartAscii
        WordStartUnicode -> WordEndUnicode
        WordEndUnicode -> WordStartUnicode
        WordStartHalfAscii -> WordEndHalfAscii
        WordEndHalfAscii -> WordStartHalfAscii
        WordStartHalfUnicode -> WordEndHalfUnicode
        WordEndHalfUnicode -> WordStartHalfUnicode
    }

    /**
     * Return the underlying representation of this look-around enumeration
     * as an integer. Giving the return value to the [fromRepr]
     * constructor is guaranteed to return the same look-around variant that
     * one started with within a semver compatible release of this library.
     */
    fun asRepr(): Int = repr

    /**
     * Returns a convenient single codepoint representation of this
     * look-around assertion. Each assertion is guaranteed to be represented
     * by a distinct character.
     */
    fun asChar(): String = when (this) {
        Start -> "A"
        End -> "z"
        StartLF -> "^"
        EndLF -> "$"
        StartCRLF -> "r"
        EndCRLF -> "R"
        WordAscii -> "b"
        WordAsciiNegate -> "B"
        WordUnicode -> "𝛃"
        WordUnicodeNegate -> "𝚩"
        WordStartAscii -> "<"
        WordEndAscii -> ">"
        WordStartUnicode -> "〈"
        WordEndUnicode -> "〉"
        WordStartHalfAscii -> "◁"
        WordEndHalfAscii -> "▷"
        WordStartHalfUnicode -> "◀"
        WordEndHalfUnicode -> "▶"
    }

    companion object {
        /**
         * Given the underlying representation of a [Look] value, return the
         * corresponding [Look] value if the representation is valid. Otherwise
         * `null` is returned.
         */
        fun fromRepr(repr: Int): Look? = entries.firstOrNull { it.repr == repr }
    }
}

/**
 * The high-level intermediate representation for a capturing group.
 *
 * A capturing group always has an index and a child expression. It may
 * also have a name associated with it (e.g., `(?P<foo>\w)`), but it's not
 * necessary.
 */
data class Capture(
    /** The capture index of the capture. */
    val index: UInt,
    /** The name of the capture, if it exists. */
    val name: String?,
    /** The expression inside the capturing group, which may be empty. */
    val sub: Hir,
)

/**
 * The high-level intermediate representation of a repetition operator.
 *
 * A repetition operator permits the repetition of an arbitrary
 * sub-expression.
 */
data class Repetition(
    /**
     * The minimum range of the repetition.
     *
     * Note that special cases like `?`, `+` and `*` all get translated into
     * the ranges `{0,1}`, `{1,}` and `{0,}`, respectively.
     */
    val min: UInt,
    /**
     * The maximum range of the repetition.
     *
     * Note that when [max] is `null`, [min] acts as a lower bound but where
     * there is no upper bound. For something like `x{5}` where the min and
     * max are equivalent, [min] will be set to `5` and [max] will be set to
     * `5u`.
     */
    val max: UInt?,
    /**
     * Whether this repetition operator is greedy or not. A greedy operator
     * will consume as much as it can. A non-greedy operator will consume as
     * little as it can.
     */
    val greedy: Boolean,
    /** The expression being repeated. */
    val sub: Hir,
) {
    /**
     * Returns a new repetition with the same [min], [max] and [greedy]
     * values, but with its sub-expression replaced with the one given.
     */
    fun with(newSub: Hir): Repetition = copy(sub = newSub)
}

/**
 * A type describing the different flavors of `.`.
 *
 * This type is meant to be used with [Hir.dot], which is a convenience
 * routine for building HIR values derived from the `.` regex.
 */
sealed class Dot {
    /** Matches the UTF-8 encoding of any Unicode scalar value. */
    object AnyChar : Dot()

    /** Matches any byte value. */
    object AnyByte : Dot()

    /** Matches the UTF-8 encoding of any Unicode scalar value except for the codepoint given. */
    data class AnyCharExcept(val codepoint: Int) : Dot()

    /** Matches the UTF-8 encoding of any Unicode scalar value except for `\n`. */
    object AnyCharExceptLF : Dot()

    /** Matches the UTF-8 encoding of any Unicode scalar value except for `\r` and `\n`. */
    object AnyCharExceptCRLF : Dot()

    /** Matches any byte value except for the byte given. */
    data class AnyByteExcept(val byte: Byte) : Dot()

    /** Matches any byte value except for `\n`. */
    object AnyByteExceptLF : Dot()

    /** Matches any byte value except for `\r` and `\n`. */
    object AnyByteExceptCRLF : Dot()
}

/**
 * A type that collects various properties of an HIR value.
 *
 * Properties are always scalar values and represent meta data that is
 * computed inductively on an HIR value. Properties are defined for all
 * HIR values.
 *
 * All methods on a [Properties] value take constant time and are meant to
 * be cheap to call.
 */
@ConsistentCopyVisibility
data class Properties internal constructor(internal val inner: PropertiesI) {
    /** Returns the length (in bytes) of the smallest string matched by this HIR. */
    fun minimumLen(): Int? = inner.minimumLen

    /** Returns the length (in bytes) of the longest string matched by this HIR. */
    fun maximumLen(): Int? = inner.maximumLen

    /** Returns a set of all look-around assertions that appear at least once in this HIR value. */
    fun lookSet(): LookSet = inner.lookSet

    /** Returns a set of all look-around assertions that appear as a prefix for this HIR value. */
    fun lookSetPrefix(): LookSet = inner.lookSetPrefix

    /** Returns a set of all look-around assertions that appear as a possible prefix. */
    fun lookSetPrefixAny(): LookSet = inner.lookSetPrefixAny

    /** Returns a set of all look-around assertions that appear as a suffix. */
    fun lookSetSuffix(): LookSet = inner.lookSetSuffix

    /** Returns a set of all look-around assertions that appear as a possible suffix. */
    fun lookSetSuffixAny(): LookSet = inner.lookSetSuffixAny

    /** Return true if and only if the corresponding HIR will always recognize valid UTF-8. */
    fun isUtf8(): Boolean = inner.utf8

    /** Returns the total number of explicit capturing groups in the corresponding HIR. */
    fun explicitCapturesLen(): Int = inner.explicitCapturesLen

    /** Returns the total number of explicit capturing groups that appear in every possible match. */
    fun staticExplicitCapturesLen(): Int? = inner.staticExplicitCapturesLen

    /** Return true if and only if this HIR is a simple literal. */
    fun isLiteral(): Boolean = inner.literal

    /** Return true if and only if this HIR is either a simple literal or an alternation of simple literals. */
    fun isAlternationLiteral(): Boolean = inner.alternationLiteral

    /** Returns the total amount of heap memory usage, in bytes, used by this [Properties] value. */
    fun memoryUsage(): Int = 12 * 4 // approximate; not load-bearing

    companion object {
        /** Returns a new set of properties that corresponds to the union of the iterator of properties given. */
        fun union(propsList: Iterable<Properties>): Properties {
            val it = propsList.iterator()
            val firstSeen = it.hasNext()
            // While empty alternations aren't possible, we still behave as if they
            // are. When we have an empty alternate, then clearly the look-around
            // prefix and suffix is empty. Otherwise, it is the intersection of all
            // prefixes and suffixes (respectively) of the branches.
            val fix = if (!firstSeen) LookSet.empty() else LookSet.full()
            // We re-iterate from the start to compute the
            // [staticExplicitCapturesLen] field off the first element.
            // Use a helper.
            val all = propsList.toList()
            val staticInit = all.firstOrNull()?.staticExplicitCapturesLen()
            val out = PropertiesI(
                minimumLen = null,
                maximumLen = null,
                lookSet = LookSet.empty(),
                lookSetPrefix = fix,
                lookSetSuffix = fix,
                lookSetPrefixAny = LookSet.empty(),
                lookSetSuffixAny = LookSet.empty(),
                utf8 = true,
                explicitCapturesLen = 0,
                staticExplicitCapturesLen = staticInit,
                literal = false,
                alternationLiteral = true,
            )
            var minPoisoned = false
            var maxPoisoned = false
            for (p in all) {
                out.lookSet = out.lookSet.union(p.lookSet())
                out.lookSetPrefix = out.lookSetPrefix.intersect(p.lookSetPrefix())
                out.lookSetSuffix = out.lookSetSuffix.intersect(p.lookSetSuffix())
                out.lookSetPrefixAny = out.lookSetPrefixAny.union(p.lookSetPrefixAny())
                out.lookSetSuffixAny = out.lookSetSuffixAny.union(p.lookSetSuffixAny())
                out.utf8 = out.utf8 && p.isUtf8()
                out.explicitCapturesLen = saturatingAdd(out.explicitCapturesLen, p.explicitCapturesLen())
                if (out.staticExplicitCapturesLen != p.staticExplicitCapturesLen()) {
                    out.staticExplicitCapturesLen = null
                }
                out.alternationLiteral = out.alternationLiteral && p.isLiteral()
                if (!minPoisoned) {
                    val xmin = p.minimumLen()
                    if (xmin != null) {
                        if (out.minimumLen == null || xmin < out.minimumLen!!) {
                            out.minimumLen = xmin
                        }
                    } else {
                        out.minimumLen = null
                        minPoisoned = true
                    }
                }
                if (!maxPoisoned) {
                    val xmax = p.maximumLen()
                    if (xmax != null) {
                        if (out.maximumLen == null || xmax > out.maximumLen!!) {
                            out.maximumLen = xmax
                        }
                    } else {
                        out.maximumLen = null
                        maxPoisoned = true
                    }
                }
            }
            return Properties(out)
        }

        /** Create a new set of HIR properties for an empty regex. */
        internal fun empty(): Properties = Properties(PropertiesI(
            minimumLen = 0,
            maximumLen = 0,
            lookSet = LookSet.empty(),
            lookSetPrefix = LookSet.empty(),
            lookSetSuffix = LookSet.empty(),
            lookSetPrefixAny = LookSet.empty(),
            lookSetSuffixAny = LookSet.empty(),
            utf8 = true,
            explicitCapturesLen = 0,
            staticExplicitCapturesLen = 0,
            literal = false,
            alternationLiteral = false,
        ))

        /** Create a new set of HIR properties for a literal regex. */
        internal fun literal(lit: Literal): Properties = Properties(PropertiesI(
            minimumLen = lit.bytes.size,
            maximumLen = lit.bytes.size,
            lookSet = LookSet.empty(),
            lookSetPrefix = LookSet.empty(),
            lookSetSuffix = LookSet.empty(),
            lookSetPrefixAny = LookSet.empty(),
            lookSetSuffixAny = LookSet.empty(),
            utf8 = isValidUtf8(lit.bytes),
            explicitCapturesLen = 0,
            staticExplicitCapturesLen = 0,
            literal = true,
            alternationLiteral = true,
        ))

        /** Create a new set of HIR properties for a character class. */
        internal fun classOf(cls: Class): Properties = Properties(PropertiesI(
            minimumLen = cls.minimumLen(),
            maximumLen = cls.maximumLen(),
            lookSet = LookSet.empty(),
            lookSetPrefix = LookSet.empty(),
            lookSetSuffix = LookSet.empty(),
            lookSetPrefixAny = LookSet.empty(),
            lookSetSuffixAny = LookSet.empty(),
            utf8 = cls.isUtf8(),
            explicitCapturesLen = 0,
            staticExplicitCapturesLen = 0,
            literal = false,
            alternationLiteral = false,
        ))

        /** Create a new set of HIR properties for a look-around assertion. */
        internal fun look(look: Look): Properties = Properties(PropertiesI(
            minimumLen = 0,
            maximumLen = 0,
            lookSet = LookSet.singleton(look),
            lookSetPrefix = LookSet.singleton(look),
            lookSetSuffix = LookSet.singleton(look),
            lookSetPrefixAny = LookSet.singleton(look),
            lookSetSuffixAny = LookSet.singleton(look),
            utf8 = true,
            explicitCapturesLen = 0,
            staticExplicitCapturesLen = 0,
            literal = false,
            alternationLiteral = false,
        ))

        /** Create a new set of HIR properties for a repetition. */
        internal fun repetition(rep: Repetition): Properties {
            val p = rep.sub.properties()
            val childMin = p.minimumLen()
            val minimumLen = if (childMin != null) {
                val repMin = rep.min.toInt()
                saturatingMul(childMin, repMin)
            } else null
            val repMax = rep.max
            val maximumLen = if (repMax != null) {
                val rmax = repMax.toInt()
                val childMax = p.maximumLen()
                if (childMax != null) checkedMul(childMax, rmax) else null
            } else null
            val out = PropertiesI(
                minimumLen = minimumLen,
                maximumLen = maximumLen,
                lookSet = p.lookSet(),
                lookSetPrefix = LookSet.empty(),
                lookSetSuffix = LookSet.empty(),
                lookSetPrefixAny = p.lookSetPrefixAny(),
                lookSetSuffixAny = p.lookSetSuffixAny(),
                utf8 = p.isUtf8(),
                explicitCapturesLen = p.explicitCapturesLen(),
                staticExplicitCapturesLen = p.staticExplicitCapturesLen(),
                literal = false,
                alternationLiteral = false,
            )
            // If the repetition operator can accept the empty string, then its
            // lookset prefix and suffixes themselves remain empty since they are
            // no longer required to match.
            if (rep.min > 0u) {
                out.lookSetPrefix = p.lookSetPrefix()
                out.lookSetSuffix = p.lookSetSuffix()
            }
            // If the static captures len of the sub-expression is not known or
            // is greater than zero, then it automatically propagates to the
            // repetition, regardless of the repetition. Otherwise, it might
            // change, but only when the repetition can accept 0 times.
            val sl = out.staticExplicitCapturesLen
            if (rep.min == 0u && sl != null && sl > 0) {
                // If we require acceptance 0 times, then our captures len is
                // guaranteed to be zero. Otherwise, if we *can* accept the empty
                // string, then it's impossible to know how many captures will be
                // in the resulting result.
                out.staticExplicitCapturesLen = if (rep.max == 0u) 0 else null
            }
            return Properties(out)
        }

        /** Create a new set of HIR properties for a capture. */
        internal fun capture(capture: Capture): Properties {
            val p = capture.sub.properties()
            return Properties(p.inner.copy(
                explicitCapturesLen = saturatingAdd(p.explicitCapturesLen(), 1),
                staticExplicitCapturesLen = p.staticExplicitCapturesLen()?.run { saturatingAdd(this, 1) },
                literal = false,
                alternationLiteral = false,
            ))
        }

        /** Create a new set of HIR properties for a concatenation. */
        internal fun concat(concat: List<Hir>): Properties {
            val out = PropertiesI(
                minimumLen = 0,
                maximumLen = 0,
                lookSet = LookSet.empty(),
                lookSetPrefix = LookSet.empty(),
                lookSetSuffix = LookSet.empty(),
                lookSetPrefixAny = LookSet.empty(),
                lookSetSuffixAny = LookSet.empty(),
                utf8 = true,
                explicitCapturesLen = 0,
                staticExplicitCapturesLen = 0,
                literal = true,
                alternationLiteral = true,
            )
            for (x in concat) {
                val p = x.properties()
                out.lookSet = out.lookSet.union(p.lookSet())
                out.utf8 = out.utf8 && p.isUtf8()
                out.explicitCapturesLen = saturatingAdd(out.explicitCapturesLen, p.explicitCapturesLen())
                val a = p.staticExplicitCapturesLen()
                val b = out.staticExplicitCapturesLen
                out.staticExplicitCapturesLen = if (a != null && b != null) saturatingAdd(a, b) else null
                out.literal = out.literal && p.isLiteral()
                out.alternationLiteral = out.alternationLiteral && p.isAlternationLiteral()
                val cur = out.minimumLen
                if (cur != null) {
                    val pm = p.minimumLen()
                    out.minimumLen = if (pm == null) null else saturatingAdd(cur, pm)
                }
                val curMax = out.maximumLen
                if (curMax != null) {
                    val pm = p.maximumLen()
                    out.maximumLen = if (pm == null) null else checkedAdd(curMax, pm)
                }
            }
            // Handle the prefix properties, which only requires visiting
            // child exprs until one matches more than the empty string.
            for (x in concat) {
                out.lookSetPrefix = out.lookSetPrefix.union(x.properties().lookSetPrefix())
                out.lookSetPrefixAny = out.lookSetPrefixAny.union(x.properties().lookSetPrefixAny())
                val mx = x.properties().maximumLen()
                if (mx == null || mx > 0) break
            }
            // Same thing for the suffix properties, but in reverse.
            for (x in concat.asReversed()) {
                out.lookSetSuffix = out.lookSetSuffix.union(x.properties().lookSetSuffix())
                out.lookSetSuffixAny = out.lookSetSuffixAny.union(x.properties().lookSetSuffixAny())
                val mx = x.properties().maximumLen()
                if (mx == null || mx > 0) break
            }
            return Properties(out)
        }

        /** Create a new set of HIR properties for an alternation. */
        internal fun alternation(alts: List<Hir>): Properties =
            union(alts.map { it.properties() })
    }
}

/** Internal property bundle. */
internal data class PropertiesI(
    var minimumLen: Int?,
    var maximumLen: Int?,
    var lookSet: LookSet,
    var lookSetPrefix: LookSet,
    var lookSetSuffix: LookSet,
    var lookSetPrefixAny: LookSet,
    var lookSetSuffixAny: LookSet,
    var utf8: Boolean,
    var explicitCapturesLen: Int,
    var staticExplicitCapturesLen: Int?,
    var literal: Boolean,
    var alternationLiteral: Boolean,
)

/**
 * A set of look-around assertions.
 *
 * This is useful for efficiently tracking look-around assertions. For
 * example, an [Hir] provides properties that return [LookSet]s.
 */
data class LookSet(
    /**
     * The underlying representation this set is exposed to make it possible
     * to store it somewhere efficiently. The representation is that
     * of a bitset, where each assertion occupies bit `i` where
     * `i = Look.asRepr()`.
     */
    var bits: Int,
) : Iterable<Look> {
    companion object {
        /** Create an empty set of look-around assertions. */
        fun empty(): LookSet = LookSet(0)

        /** Create a full set of look-around assertions. */
        fun full(): LookSet = LookSet(0.inv())

        /** Create a look-around set containing the look-around assertion given. */
        fun singleton(look: Look): LookSet = empty().insert(look)

        /** Return a [LookSet] from the slice given as a native endian 32-bit integer. */
        fun readRepr(slice: ByteArray): LookSet {
            check(slice.size >= 4)
            val bits = (slice[0].toInt() and 0xFF) or
                ((slice[1].toInt() and 0xFF) shl 8) or
                ((slice[2].toInt() and 0xFF) shl 16) or
                ((slice[3].toInt() and 0xFF) shl 24)
            return LookSet(bits)
        }
    }

    /** Returns the total number of look-around assertions in this set. */
    fun len(): Int {
        var b = bits
        var c = 0
        while (b != 0) { c += b and 1; b = b ushr 1 }
        return c
    }

    /** Returns true if and only if this set is empty. */
    fun isEmpty(): Boolean = bits == 0

    /** Returns true if and only if the given look-around assertion is in this set. */
    fun contains(look: Look): Boolean = (bits and look.asRepr()) != 0

    /** Returns true if and only if this set contains any anchor assertions. */
    fun containsAnchor(): Boolean = containsAnchorHaystack() || containsAnchorLine()

    /** Returns true if and only if this set contains any "start/end of haystack" anchors. */
    fun containsAnchorHaystack(): Boolean = contains(Look.Start) || contains(Look.End)

    /** Returns true if and only if this set contains any "start/end of line" anchors. */
    fun containsAnchorLine(): Boolean =
        contains(Look.StartLF) || contains(Look.EndLF) ||
        contains(Look.StartCRLF) || contains(Look.EndCRLF)

    /** Returns true if and only if this set contains any \n line anchors. */
    fun containsAnchorLf(): Boolean = contains(Look.StartLF) || contains(Look.EndLF)

    /** Returns true if and only if this set contains any CRLF-aware line anchors. */
    fun containsAnchorCrlf(): Boolean = contains(Look.StartCRLF) || contains(Look.EndCRLF)

    /** Returns true if and only if this set contains any word boundary or negated word boundary assertions. */
    fun containsWord(): Boolean = containsWordUnicode() || containsWordAscii()

    fun containsWordUnicode(): Boolean =
        contains(Look.WordUnicode) || contains(Look.WordUnicodeNegate) ||
        contains(Look.WordStartUnicode) || contains(Look.WordEndUnicode) ||
        contains(Look.WordStartHalfUnicode) || contains(Look.WordEndHalfUnicode)

    fun containsWordAscii(): Boolean =
        contains(Look.WordAscii) || contains(Look.WordAsciiNegate) ||
        contains(Look.WordStartAscii) || contains(Look.WordEndAscii) ||
        contains(Look.WordStartHalfAscii) || contains(Look.WordEndHalfAscii)

    /** Returns an iterator over all of the look-around assertions in this set. */
    override fun iterator(): Iterator<Look> = LookSetIter(this)

    /** Return a new set with the given assertion added. */
    fun insert(look: Look): LookSet = LookSet(bits or look.asRepr())

    /** Updates this set in place with the result of inserting the given assertion into this set. */
    fun setInsert(look: Look) {
        bits = bits or look.asRepr()
    }

    /** Return a new set with the given assertion removed. */
    fun remove(look: Look): LookSet = LookSet(bits and look.asRepr().inv())

    /** Updates this set in place with the result of removing the given assertion from this set. */
    fun setRemove(look: Look) {
        bits = bits and look.asRepr().inv()
    }

    /** Returns a new set that is the result of subtracting the given set from this set. */
    fun subtract(other: LookSet): LookSet = LookSet(bits and other.bits.inv())

    /** Updates this set in place with the result of subtracting the given set from this set. */
    fun setSubtract(other: LookSet) {
        bits = bits and other.bits.inv()
    }

    /** Returns a new set that is the union of this and the one given. */
    fun union(other: LookSet): LookSet = LookSet(bits or other.bits)

    /** Updates this set in place with the result of unioning it with the one given. */
    fun setUnion(other: LookSet) {
        bits = bits or other.bits
    }

    /** Returns a new set that is the intersection of this and the one given. */
    fun intersect(other: LookSet): LookSet = LookSet(bits and other.bits)

    /** Updates this set in place with the result of intersecting it with the one given. */
    fun setIntersect(other: LookSet) {
        bits = bits and other.bits
    }

    /** Write a [LookSet] as a native endian 32-bit integer to the beginning of the slice given. */
    fun writeRepr(slice: ByteArray) {
        check(slice.size >= 4)
        slice[0] = (bits and 0xFF).toByte()
        slice[1] = ((bits ushr 8) and 0xFF).toByte()
        slice[2] = ((bits ushr 16) and 0xFF).toByte()
        slice[3] = ((bits ushr 24) and 0xFF).toByte()
    }

    override fun toString(): String {
        if (isEmpty()) return "∅"
        val out = StringBuilder()
        for (look in this) out.append(look.asChar())
        return out.toString()
    }

    fun fmt(wtr: Appendable): Result<Unit> {
        wtr.append(toString())
        return Result.success(Unit)
    }
}

/** An iterator over all look-around assertions in a [LookSet]. */
class LookSetIter internal constructor(initial: LookSet) : Iterator<Look> {
    private var set: LookSet = initial

    override fun hasNext(): Boolean = nextLook() != null

    override fun next(): Look {
        val look = nextLook() ?: throw NoSuchElementException()
        set = set.remove(look)
        return look
    }

    private fun nextLook(): Look? {
        if (set.isEmpty()) return null
        // We'll never have more than UByte.MAX_VALUE distinct look-around
        // assertions, so `bit` will always fit into a UShort.
        var bit = 0
        var candidateBits = set.bits
        while (candidateBits and 1 == 0) {
            bit++
            candidateBits = candidateBits ushr 1
        }
        return Look.fromRepr(1 shl bit)
    }
}

// ---- Helper functions used by the smart constructors ----

/**
 * Given a sequence of HIR values where each value corresponds to a Unicode
 * class (or an all-ASCII byte class), return a single Unicode class
 * corresponding to the union of the classes found.
 */
private fun classChars(hirs: List<Hir>): Class? {
    val cls = ClassUnicode.new(emptyList())
    for (hir in hirs) {
        when (val k = hir.kind()) {
            is HirKind.Class -> when (val c = k.value) {
                is Class.Unicode -> cls.union(c.value)
                is Class.Bytes -> {
                    val asUni = c.value.toUnicodeClass() ?: return null
                    cls.union(asUni)
                }
            }
            else -> return null
        }
    }
    return Class.Unicode(cls)
}

/**
 * Given a sequence of HIR values where each value corresponds to a byte class
 * (or an all-ASCII Unicode class), return a single byte class corresponding
 * to the union of the classes found.
 */
private fun classBytes(hirs: List<Hir>): Class? {
    val cls = ClassBytes.new(emptyList())
    for (hir in hirs) {
        when (val k = hir.kind()) {
            is HirKind.Class -> when (val c = k.value) {
                is Class.Unicode -> {
                    val asBytes = c.value.toByteClass() ?: return null
                    cls.union(asBytes)
                }
                is Class.Bytes -> cls.union(c.value)
            }
            else -> return null
        }
    }
    return Class.Bytes(cls)
}

/**
 * Given a sequence of HIR values where each value corresponds to a literal
 * that is a single codepoint, return that sequence of codepoints. Otherwise return
 * null. No deduplication is done.
 */
private fun singletonChars(hirs: List<Hir>): List<Int>? {
    val singletons = ArrayList<Int>()
    for (hir in hirs) {
        val literal = when (val k = hir.kind()) {
            is HirKind.Literal -> k.value.bytes
            else -> return null
        }
        val ch = when (val r = utf8Decode(literal)) {
            null, is Utf8Decoded.Failed -> return null
            is Utf8Decoded.Ok -> r.codepoint
        }
        if (literal.size != ch.len()) return null
        singletons.add(ch)
    }
    return singletons
}

/**
 * Given a sequence of HIR values where each value corresponds to a literal
 * that is a single byte, return that sequence of bytes. Otherwise return
 * null. No deduplication is done.
 */
private fun singletonBytes(hirs: List<Hir>): List<Byte>? {
    val singletons = ArrayList<Byte>()
    for (hir in hirs) {
        val literal = when (val k = hir.kind()) {
            is HirKind.Literal -> k.value.bytes
            else -> return null
        }
        if (literal.size != 1) return null
        singletons.add(literal[0])
    }
    return singletons
}

private sealed class LiftResult {
    class Success(val hir: Hir) : LiftResult()
    class Unchanged(val items: List<Hir>) : LiftResult()
}

/**
 * Looks for a common prefix in the list of alternation branches given. If one
 * is found, then an equivalent but (hopefully) simplified Hir is returned.
 * Otherwise, the original given list of branches is returned unmodified.
 */
private fun liftCommonPrefix(hirs: List<Hir>): LiftResult {
    if (hirs.size <= 1) return LiftResult.Unchanged(hirs)
    var prefix: List<Hir> = when (val k = hirs[0].kind()) {
        is HirKind.Concat -> k.items
        else -> return LiftResult.Unchanged(hirs)
    }
    if (prefix.isEmpty()) return LiftResult.Unchanged(hirs)
    for (i in 1 until hirs.size) {
        val concat = when (val k = hirs[i].kind()) {
            is HirKind.Concat -> k.items
            else -> return LiftResult.Unchanged(hirs)
        }
        var commonLen = 0
        val maxLen = minOf(prefix.size, concat.size)
        while (commonLen < maxLen && prefix[commonLen] == concat[commonLen]) {
            commonLen++
        }
        prefix = prefix.subList(0, commonLen)
        if (prefix.isEmpty()) return LiftResult.Unchanged(hirs)
    }
    val len = prefix.size
    check(len != 0)
    val prefixConcat = ArrayList<Hir>()
    val suffixAlts = ArrayList<Hir>()
    for ((i, h) in hirs.withIndex()) {
        val concat = when (val k = h.kind()) {
            is HirKind.Concat -> k.items
            else -> error("unreachable: liftCommonPrefix concat case mismatch")
        }
        suffixAlts.add(Hir.concat(concat.subList(len, concat.size)))
        if (i == 0) {
            prefixConcat.addAll(concat.subList(0, len))
        }
    }
    val total = ArrayList<Hir>(prefixConcat.size + 1)
    total.addAll(prefixConcat)
    total.add(Hir.alternation(suffixAlts))
    return LiftResult.Success(Hir.concat(total))
}

private fun formatCodepointDebug(codepoint: Int): String =
    if (codepoint in 0x21..0x7E) codepoint.toChar().toString() else "0x${codepoint.toString(16).uppercase()}"

// ---- Saturating + checked Int arithmetic helpers ----
// Saturating helpers clamp to Int.MAX_VALUE on overflow; checked helpers
// return null instead.

private fun saturatingAdd(a: Int, b: Int): Int {
    val r = a + b
    return if (r < a || r < b) Int.MAX_VALUE else r
}

private fun saturatingMul(a: Int, b: Int): Int {
    if (a == 0 || b == 0) return 0
    val r = a * b
    return if (r / a != b) Int.MAX_VALUE else r
}

private fun checkedAdd(a: Int, b: Int): Int? {
    val r = a + b
    return if (r < a || r < b) null else r
}

private fun checkedMul(a: Int, b: Int): Int? {
    if (a == 0 || b == 0) return 0
    val r = a * b
    return if (r / a != b) null else r
}

/** Encode a Unicode codepoint as UTF-8 into [dest], returning the number of bytes written. */
internal fun encodeUtf8(codepoint: Int, dest: ByteArray): Int {
    return when {
        codepoint < 0x80 -> {
            dest[0] = codepoint.toByte()
            1
        }
        codepoint < 0x800 -> {
            dest[0] = (0xC0 or (codepoint ushr 6)).toByte()
            dest[1] = (0x80 or (codepoint and 0x3F)).toByte()
            2
        }
        codepoint < 0x10000 -> {
            dest[0] = (0xE0 or (codepoint ushr 12)).toByte()
            dest[1] = (0x80 or ((codepoint ushr 6) and 0x3F)).toByte()
            dest[2] = (0x80 or (codepoint and 0x3F)).toByte()
            3
        }
        else -> {
            dest[0] = (0xF0 or (codepoint ushr 18)).toByte()
            dest[1] = (0x80 or ((codepoint ushr 12) and 0x3F)).toByte()
            dest[2] = (0x80 or ((codepoint ushr 6) and 0x3F)).toByte()
            dest[3] = (0x80 or (codepoint and 0x3F)).toByte()
            4
        }
    }
}

/** Number of bytes a codepoint takes to encode as UTF-8. */
internal fun codepointUtf8Len(codepoint: Int): Int = when {
    codepoint < 0x80 -> 1
    codepoint < 0x800 -> 2
    codepoint < 0x10000 -> 3
    else -> 4
}

/** Validate UTF-8 encoding of a byte sequence. */
internal fun isValidUtf8(bytes: ByteArray): Boolean {
    return try {
        bytes.decodeToString(throwOnInvalidSequence = true)
        true
    } catch (_: Throwable) {
        false
    }
}
