package io.github.kotlinmania.regexsyntax.hir

import io.github.kotlinmania.regexsyntax.appendScalar
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.debug.Bytes
import io.github.kotlinmania.regexsyntax.encodeScalarUtf8List
import io.github.kotlinmania.regexsyntax.error.fromFormatterErrorKind
import io.github.kotlinmania.regexsyntax.formatHexUpper
import io.github.kotlinmania.regexsyntax.hir.interval.Bound
import io.github.kotlinmania.regexsyntax.hir.interval.Interval
import io.github.kotlinmania.regexsyntax.hir.interval.IntervalSet
import io.github.kotlinmania.regexsyntax.hir.interval.IntervalSetIter
import io.github.kotlinmania.regexsyntax.hir.interval.ScalarBound
import io.github.kotlinmania.regexsyntax.hir.interval.UByteBound
import io.github.kotlinmania.regexsyntax.hir.print.Printer
import io.github.kotlinmania.regexsyntax.isValidUtf8
import io.github.kotlinmania.regexsyntax.normalizeUByteList
import io.github.kotlinmania.regexsyntax.scalarFromU32
import io.github.kotlinmania.regexsyntax.scalarIsControl
import io.github.kotlinmania.regexsyntax.scalarIsWhitespace
import io.github.kotlinmania.regexsyntax.scalarLenUtf8
import io.github.kotlinmania.regexsyntax.scalarToString
import io.github.kotlinmania.regexsyntax.unicode.CaseFoldError
import io.github.kotlinmania.regexsyntax.unicode.SimpleCaseFolder
import io.github.kotlinmania.rustlang.core.result.Result

internal class DebugStruct(
    private val out: StringBuilder,
    private val name: String,
) {
    private val fields: MutableList<String> = mutableListOf()

    public fun field(name: String, value: Any?): DebugStruct =
        apply {
            fields.add("$name: $value")
        }

    public fun finish() {
        if (fields.isEmpty()) {
            out.append(name)
        } else {
            out.append("$name { ${fields.joinToString(", ")} }")
        }
    }

    public fun finishNonExhaustive() {
        fields.add("..")
        finish()
    }
}

/**
 * An error that can occur while translating an `Ast` to a `Hir`.
 */
public open class Error(
    /**
     * The kind of error.
     */
    internal var kind: ErrorKind,
    /**
     * The original pattern that the translator's Ast was parsed from. Every
     * span in an error is a valid range into this string.
     */
    internal var pattern: String,
    /**
     * The span of this error, derived from the Ast given to the translator.
     */
    internal var span: Span,
) : Exception() {
    /**
     * Return the type of this error.
     */
    public open fun kind(): ErrorKind = this.kind

    /**
     * The original pattern string in which this error occurred.
     *
     * Every span reported by this error is reported in terms of this string.
     */
    public open fun pattern(): String = this.pattern

    /**
     * Return the span at which this error occurred.
     */
    public open fun span(): Span = this.span

    public open fun fmt(f: StringBuilder) {
        fromFormatterErrorKind(this).fmt(f)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Error && this.kind == other.kind && this.pattern == other.pattern && this.span == other.span)

    override fun hashCode(): Int {
        var result = this.kind.hashCode()
        result = 31 * result + this.pattern.hashCode()
        result = 31 * result + this.span.hashCode()
        return result
    }

    override fun toString(): String = buildString { fmt(this) }
}

/**
 * The constituent parts returned by [Hir.intoParts].
 */
public data class HirParts(
    public val kind: HirKind,
    public val properties: Properties,
)

/**
 * A high-level intermediate representation (HIR) for a regular expression.
 *
 * An HIR value is a combination of a [HirKind] and a set of [Properties].
 * An `HirKind` indicates what kind of regular expression it is (a literal,
 * a repetition, a look-around assertion, etc.), where as a `Properties`
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
 * `(? i: A)` to `[aA]`).
 *
 * The specific type of an HIR expression can be accessed via its `kind`
 * or `intoKind` methods. This extra level of indirection exists for two
 * reasons:
 *
 * 1. Construction of an HIR expression *must* use the constructor methods on
 * this `Hir` type instead of building the `HirKind` values directly. This
 * permits construction to enforce invariants like "concatenations always
 * consist of two or more sub-expressions."
 * 2. Every HIR expression contains attributes that are defined inductively,
 * and can be computed cheaply during the construction process. For example,
 * one such attribute is whether the expression must match at the beginning of
 * the haystack.
 *
 * In particular, if you have an `HirKind` value, then there is intentionally
 * no way to build an `Hir` value from it. You instead need to do case
 * analysis on the `HirKind` value and build the `Hir` value using its smart
 * constructors.
 *
 * # UTF-8
 *
 * If the HIR was produced by a translator with
 * [`TranslatorBuilder.utf8`](translate.TranslatorBuilder.utf8) enabled,
 * then the HIR is guaranteed to match UTF-8 exclusively for all non-empty
 * matches.
 *
 * For empty matches, those can occur at any position. It is the
 * responsibility of the regex engine to determine whether empty matches are
 * permitted between the code units of a single codepoint.
 *
 * # Stack space
 *
 * This type defines its own destructor that uses constant stack space and
 * heap space proportional to the size of the HIR.
 *
 * Also, an `Hir`s `fmt.Display` implementation prints an HIR as a regular
 * expression pattern string, and uses constant stack space and heap space
 * proportional to the size of the `Hir`. The regex it prints is guaranteed to
 * be _semantically_ equivalent to the original concrete syntax, but it may
 * look very different. (And potentially not practically readable by a human.)
 *
 * An `Hir`s `fmt.Debug` implementation currently does not use constant
 * stack space. The implementation will also suppress some details (such as
 * the `Properties` inlined into every `Hir` value to make it less noisy).
 */
public open class Hir internal constructor(
    /**
     * The underlying HIR kind.
     */
    internal var kind: HirKind,
    /**
     * Analysis info about this HIR, computed during construction.
     */
    internal var props: Properties,
) {
    /**
     * Methods for accessing the underlying `HirKind` and `Properties`.
     */
    /**
     * Returns a reference to the underlying HIR kind.
     */
    public open fun kind(): HirKind = this.kind

    /**
     * Consumes ownership of this HIR expression and returns its underlying
     * `HirKind`.
     */
    public open fun intoKind(): HirKind {
        val k = this.kind
        this.kind = HirKind.Empty
        return k
    }

    /**
     * Returns the properties computed for this `Hir`.
     */
    public open fun properties(): Properties = this.props

    /**
     * Splits this HIR into its constituent parts.
     *
     * This is useful because `val Hir { kind, props } = hir;` does not work
     * because of `Hir`s custom `Drop` implementation.
     */
    public open fun intoParts(): HirParts {
        val k = this.kind
        val p = this.props
        this.kind = HirKind.Empty
        this.props = Properties.empty()
        return HirParts(k, p)
    }

    public open fun fmt(f: StringBuilder) {
        this.kind.fmt(f)
    }

    /**
     * Print a display representation of this Hir.
     *
     * The result of this is a valid regular expression pattern string.
     *
     * This implementation uses constant stack space and heap space proportional
     * to the size of the `Hir`.
     */
    public open fun fmtDisplay(f: StringBuilder) {
        Printer.new().print(this, f)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Hir && this.kind == other.kind && this.props == other.props)

    override fun hashCode(): Int {
        var result = this.kind.hashCode()
        result = 31 * result + this.props.hashCode()
        return result
    }

    override fun toString(): String = buildString { fmtDisplay(this) }

    /**
     * A custom `Drop` impl is used for `HirKind` such that it uses constant stack
     * space but heap space proportional to the depth of the total `Hir`.
     */
    public open fun drop() {
        when (val matchVal = this.kind()) {
            is HirKind.Empty, is HirKind.Literal, is HirKind.Class, is HirKind.Look -> return
            is HirKind.Capture ->
                if (matchVal.value.sub.kind
                        .subs()
                        .isEmpty()
                ) {
                    return
                }
            is HirKind.Repetition ->
                if (matchVal.value.sub.kind
                        .subs()
                        .isEmpty()
                ) {
                    return
                }
            is HirKind.Concat -> if (matchVal.value.isEmpty()) return
            is HirKind.Alternation -> if (matchVal.value.isEmpty()) return
        }
        val (rootKind, rootProps) = this.intoParts()
        val stack = mutableListOf(Hir(kind = rootKind, props = rootProps))
        while (stack.isNotEmpty()) {
            val expr = stack.removeLast()
            when (val matchVal2 = expr.kind) {
                HirKind.Empty, is HirKind.Literal, is HirKind.Class, is HirKind.Look -> {}
                is HirKind.Capture -> {
                    val (ck, cp) = matchVal2.value.sub.intoParts()
                    stack.add(Hir(kind = ck, props = cp))
                }
                is HirKind.Repetition -> {
                    val (rk, rp) = matchVal2.value.sub.intoParts()
                    stack.add(Hir(kind = rk, props = rp))
                }
                is HirKind.Concat -> {
                    stack.addAll(matchVal2.value)
                    matchVal2.value = emptyList()
                }
                is HirKind.Alternation -> {
                    stack.addAll(matchVal2.value)
                    matchVal2.value = emptyList()
                }
            }
        }
    }

    public companion object {
        /**
         * Smart constructors for HIR values.
         *
         * These constructors are called "smart" because they do inductive work or
         * simplifications. For example, calling `Hir.repetition` with a repetition
         * like `a { 0 }` will actually return a `Hir` with a `HirKind.Empty` kind
         * since it is equivalent to an empty regex. Another example is calling
         * `Hir.concat(listOf(expr))`. Instead of getting a `HirKind.Concat`, you'll
         * just get back the original `expr` since it's precisely equivalent.
         *
         * Smart constructors enable maintaining invariants about the HIR data type
         * while also simultaneously keeping the representation as simple as possible.
         */
        /**
         * Returns an empty HIR expression.
         *
         * An empty HIR expression always matches, including the empty string.
         */
        public fun empty(): Hir {
            val props = Properties.empty()
            return Hir(kind = HirKind.Empty, props = props)
        }

        /**
         * Returns an HIR expression that can never match anything. That is,
         * the size of the set of strings in the language described by the HIR
         * returned is `0`.
         *
         * This is distinct from [Hir.empty] in that the empty string matches
         * the HIR returned by `Hir.empty`. That is, the set of strings in the
         * language describe described by `Hir.empty` is non-empty.
         *
         * Note that currently, the HIR returned uses an empty character class to
         * indicate that nothing can match. An equivalent expression that cannot
         * match is an empty alternation, but all such "fail" expressions are
         * normalized (via smart constructors) to empty character classes. This is
         * because empty character classes can be spelled in the concrete syntax
         * of a regex (e.g., `\P{any}` or `(?-u:[^\x00-\xFF])` or `[a && b]`), but
         * empty alternations cannot.
         */
        public fun fail(): Hir {
            val cls = Class.Bytes(ClassBytes.empty())
            val props = Properties.cls(cls)
            // We can't just call Hir.cls here because it defers to Hir.fail
            // in order to canonicalize the Hir value used to represent "cannot
            // match."
            return Hir(kind = HirKind.Class(cls), props = props)
        }

        /**
         * Creates a literal HIR expression.
         *
         * This accepts anything that can be converted into a `Holder<[UByte]>`.
         *
         * Note that there is no mechanism for storing a `char` or a `Holder<String>`
         * in an HIR. Everything is "just bytes." Whether a `Literal` (or
         * any HIR node) matches valid UTF-8 exclusively can be queried via
         * [Properties.isUtf8].
         *
         * # Example
         *
         * This example shows that concatenations of `Literal` HIR values will
         * automatically get flattened and combined together. So for example, even
         * if you concat multiple `Literal` values that are themselves not valid
         * UTF-8, they might add up to valid UTF-8. This also demonstrates just
         * how "smart" Hir's smart constructors are.
         *
         * ```kotlin
         * val literals = mutableListOf(Hir.Literal(listOf(0xE2)), Hir.Literal(listOf(0x98)), Hir.Literal(listOf(0x83)))
         * // Each literal, on its own, is invalid UTF-8.
         * check(ArrayDeque(literals).all({ hir -> !hir.properties().isUtf8() }))
         * val concat = Hir.concat(literals)
         * // But the concatenation is valid UTF-8!
         * check(concat.properties().isUtf8())
         * // And also notice that the literals have been concatenated into a
         * // single `Literal`, to the point where there is no explicit `Concat`!
         * val expected = HirKind.Literal(Literal(Holder.from(("☃").encodeToByteArray().map { it.toUByte() })))
         * check(expected == concat.kind())
         * ```
         *
         * # Example: building a literal from a `char`
         *
         * This example shows how to build a single `Hir` literal from a `char`
         * value. Since a [Literal] is just bytes, we just need to UTF-8
         * encode a `char` value:
         *
         * ```kotlin
         * val ch = '\u2603'
         * val got = Hir.Literal(ch.encodeUtf8(List(4) { 0 }).encodeToByteArray().map { it.toUByte() })
         * val expected = HirKind.Literal(Literal(Holder.from(("☃").encodeToByteArray().map { it.toUByte() })))
         * check(expected == got.kind())
         * ```
         */
        public fun literal(lit: List<UByte>): Hir {
            val bytes = lit.normalizeUByteList()
            if (bytes.isEmpty()) {
                return Hir.empty()
            }
            val lit1 = Literal(bytes)
            val props = Properties.literal(lit1)
            return Hir(kind = HirKind.Literal(lit1), props = props)
        }

        /**
         * Creates a class HIR expression. The class may either be defined over
         * ranges of Unicode codepoints or ranges of raw byte values.
         *
         * Note that an empty class is permitted. An empty class is equivalent to
         * `Hir.fail()`.
         */
        public fun cls(cls: Class): Hir {
            if (cls.isEmpty()) {
                return Hir.fail()
            } else {
                when (val matchVal = cls.literal()) {
                    null -> {}
                    else -> {
                        val bytes = matchVal
                        return Hir.literal(bytes)
                    }
                }
            }
            val props = Properties.cls(cls)
            return Hir(kind = HirKind.Class(cls), props = props)
        }

        /**
         * Creates a look-around assertion HIR expression.
         */
        public fun look(look: Look): Hir {
            val props = Properties.look(look)
            return Hir(kind = HirKind.Look(look), props = props)
        }

        /**
         * Creates a repetition HIR expression.
         */
        public fun repetition(rep: Repetition): Hir {
            // If the sub-expression of a repetition can only match the empty
            // string, then we force its maximum to be at most 1.
            if (rep.sub.properties().maximumLen() == 0) {
                rep.min = minOf(rep.min, 1u)
                rep.max = rep.max?.let { n -> minOf(n, 1u) } ?: 1u
            }
            // The regex `a{0}` is always equivalent to the empty regex. This is
            // true even when `a` is an expression that never matches anything
            // (like `\P{any}`).
            //
            // Additionally, the regex `a{1}` is always equivalent to `a`.
            if (rep.min == 0u && rep.max == 0u) {
                return Hir.empty()
            } else if (rep.min == 1u && rep.max == 1u) {
                return rep.sub
            }
            val props = Properties.repetition(rep)
            return Hir(kind = HirKind.Repetition(rep), props = props)
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
        public fun capture(capture: Capture): Hir {
            val props = Properties.capture(capture)
            return Hir(kind = HirKind.Capture(capture), props = props)
        }

        /**
         * Returns the concatenation of the given expressions.
         *
         * This attempts to flatten and simplify the concatenation as appropriate.
         *
         * # Example
         *
         * This shows a simple example of basic flattening of both concatenations
         * and literals.
         *
         * ```kotlin
         * val hir = Hir.concat(listOf(Hir.concat(listOf(Hir.literal(listOf(97u)), Hir.literal(listOf(98u)), Hir.literal(listOf(99u)))), Hir.concat(listOf(Hir.literal(listOf(120u)), Hir.literal(listOf(121u)), Hir.literal(listOf(122u))))))
         * val expected = Hir.literal("abcxyz".encodeToByteArray().map { it.toUByte() })
         * check(expected == hir)
         * ```
         */
        public fun concat(subs: List<Hir>): Hir {
            // We rebuild the concatenation by simplifying it.
            val new = mutableListOf<Hir>()
            // This gobbles up any adjacent literals in a concatenation and smushes
            // them together. Basically, when we see a literal, we add its bytes
            // to `priorLit`, and whenever we see anything else, we first take
            // any bytes in `priorLit` and add it to the `new` concatenation.
            val priorLit = mutableListOf<UByte>()
            for (sub in subs) {
                val (kind, props) = sub.intoParts()
                when {
                    kind is HirKind.Literal -> {
                        priorLit.addAll(kind.value.value)
                    }
                    // We also flatten concats that are direct children of another
                    // concat. We only need to do this one level deep since
                    // Hir.concat is the only way to build concatenations, and so
                    // flattening happens inductively.
                    kind is HirKind.Concat -> {
                        val subs2 = kind.value
                        for (sub2 in subs2) {
                            val (kind2, props2) = sub2.intoParts()
                            when {
                                kind2 is HirKind.Literal -> {
                                    priorLit.addAll(kind2.value.value)
                                }
                                else -> {
                                    if (priorLit.isNotEmpty()) {
                                        new.add(Hir.literal(priorLit.toList()))
                                        priorLit.clear()
                                    }
                                    new.add(Hir(kind = kind2, props = props2))
                                }
                            }
                        }
                    }
                    // We can just skip empty HIRs.
                    kind == HirKind.Empty -> {}
                    else -> {
                        if (priorLit.isNotEmpty()) {
                            new.add(Hir.literal(priorLit.toList()))
                            priorLit.clear()
                        }
                        new.add(Hir(kind = kind, props = props))
                    }
                }
            }
            if (priorLit.isNotEmpty()) {
                new.add(Hir.literal(priorLit.toList()))
                priorLit.clear()
            }
            if (new.isEmpty()) {
                return Hir.empty()
            } else if (new.size == 1) {
                return new.removeLastOrNull()!!
            }
            val props1 = Properties.concat(new)
            return Hir(kind = HirKind.Concat(new), props = props1)
        }

        /**
         * Returns the alternation of the given expressions.
         *
         * This flattens and simplifies the alternation as appropriate. This may
         * include factoring out common prefixes or even rewriting the alternation
         * as a character class.
         *
         * Note that an empty alternation is equivalent to `Hir.fail()`. (It
         * is not possible for one to write an empty alternation, or even an
         * alternation with a single sub-expression, in the concrete syntax of a
         * regex.)
         *
         * Note that these sorts of simplifications are not guaranteed.
         */
        public fun alternation(subs: List<Hir>): Hir {
            // We rebuild the alternation by simplifying it. We proceed similarly
            // as the concatenation case. But in this case, there's no literal
            // simplification happening. We're just flattening alternations.
            val new = ArrayList<Hir>(subs.size)
            for (sub in subs) {
                val (kind, props) = sub.intoParts()
                when (kind) {
                    is HirKind.Alternation -> {
                        new.addAll(kind.value)
                    }
                    else -> {
                        new.add(Hir(kind = kind, props = props))
                    }
                }
            }
            if (new.isEmpty()) {
                return Hir.fail()
            } else if (new.size == 1) {
                return new.removeLastOrNull()!!
            }
            // Now that it's completely flattened, look for the special case of
            // `char1|char2|...|charN` and collapse that into a class. Note that
            // we look for scalar codepoints first and then bytes. The issue here is that if
            // we find both non-ASCII codepoints and non-ASCII singleton bytes,
            // then it isn't actually possible to smush them into a single class.
            // (Because classes are either "all codepoints" or "all bytes." You
            // can have a class that both matches non-ASCII but valid UTF-8 and
            // invalid UTF-8.) So we look for all scalars and then all bytes, and
            // don't handle anything else.
            when (val singletons = singletonChars(new)) {
                null -> {}
                else -> {
                    val it = singletons.map { ch -> ClassUnicodeRange(start = ch, end = ch) }
                    return Hir.cls(Class.Unicode(ClassUnicode.new(it)))
                }
            }
            when (val singletons1 = singletonBytes(new)) {
                null -> {}
                else -> {
                    val it1 = singletons1.map { b -> ClassBytesRange(start = b, end = b) }
                    return Hir.cls(Class.Bytes(ClassBytes.new(it1)))
                }
            }
            // Similar to singleton scalars, we can also look for alternations of
            // classes. Those can be smushed into a single class.
            when (val cls = classChars(new)) {
                null -> {}
                else -> return Hir.cls(cls)
            }
            when (val cls1 = classBytes(new)) {
                null -> {}
                else -> return Hir.cls(cls1)
            }
            // Factor out a common prefix if we can, which might potentially
            // simplify the expression and unlock other optimizations downstream.
            // It also might generally make NFA matching and DFA construction
            // faster by reducing the scope of branching in the regex.
            val alternations =
                when (val lifted = liftCommonPrefix(new)) {
                    is Result.Ok -> return lifted.value
                    is Result.Err -> lifted.error
                }
            val props1 = Properties.alternation(alternations)
            return Hir(kind = HirKind.Alternation(alternations), props = props1)
        }

        /**
         * Returns an HIR expression for `.`.
         *
         * * [Dot.AnyChar] maps to `(? su - R:.)`.
         * * [Dot.AnyByte] maps to `(? s - Ru:.)`.
         * * [Dot.AnyCharExceptLF] maps to `(? u - Rs:.)`.
         * * [Dot.AnyCharExceptCRLF] maps to `(? Ru - s:.)`.
         * * [Dot.AnyByteExceptLF] maps to `(? - Rsu:.)`.
         * * [Dot.AnyByteExceptCRLF] maps to `(? R - su:.)`.
         *
         * # Example
         *
         * Note that this is a convenience routine for constructing the correct
         * character class based on the value of `Dot`. There is no explicit "dot"
         * HIR value. It is just an abbreviation for a common character class.
         *
         * ```kotlin
         * val hir = Hir.dot(Dot.AnyByte)
         * val expected = Hir.cls(Class.Bytes(ClassBytes.new(listOf(ClassBytesRange.new(0x00u, 0xFFu)))))
         * check(expected == hir)
         * ```
         */
        public fun dot(dot: Dot): Hir =
            when (dot) {
                Dot.AnyChar -> Hir.cls(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new(0x00u, 0x10FFFFu)))))
                Dot.AnyByte -> Hir.cls(Class.Bytes(ClassBytes.new(listOf(ClassBytesRange.new(0u, 255u)))))
                is Dot.AnyCharExcept -> {
                    val (ch) = dot
                    val cls = ClassUnicode.new(listOf(ClassUnicodeRange.new(ch, ch)))
                    cls.negate()
                    Hir.cls(Class.Unicode(cls))
                }
                Dot.AnyCharExceptLF -> {
                    Hir.cls(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new(0x00u, 0x09u), ClassUnicodeRange.new(0x0Bu, 0x10FFFFu)))))
                }
                Dot.AnyCharExceptCRLF -> {
                    Hir.cls(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new(0x00u, 0x09u), ClassUnicodeRange.new(0x0Bu, 0x0Cu), ClassUnicodeRange.new(0x0Eu, 0x10FFFFu)))))
                }
                is Dot.AnyByteExcept -> {
                    val (byte) = dot
                    val cls1 = ClassBytes.new(listOf(ClassBytesRange.new(byte, byte)))
                    cls1.negate()
                    Hir.cls(Class.Bytes(cls1))
                }
                Dot.AnyByteExceptLF -> {
                    Hir.cls(Class.Bytes(ClassBytes.new(listOf(ClassBytesRange.new(0u, 9u), ClassBytesRange.new(11u, 255u)))))
                }
                Dot.AnyByteExceptCRLF -> {
                    Hir.cls(Class.Bytes(ClassBytes.new(listOf(ClassBytesRange.new(0u, 9u), ClassBytesRange.new(11u, 12u), ClassBytesRange.new(14u, 255u)))))
                }
            }
    }
}

/**
 * The high-level intermediate representation of a literal.
 *
 * A literal corresponds to `0` or more bytes that should be matched
 * literally. The smart constructors defined on `Hir` will automatically
 * concatenate adjacent literals into one literal, and will even automatically
 * replace empty literals with `Hir.empty()`.
 *
 * Note that despite a literal being represented by a sequence of bytes, its
 * `Debug` implementation will attempt to print it as a normal string. (That
 * is, not a sequence of decimal numbers.)
 */
public open class Literal(
    public var value: List<UByte>,
) {
    init {
        this.value = this.value.normalizeUByteList()
    }
    internal var field0: List<UByte>
        get() = value
        set(v) {
            value = v
        }

    public open fun fmt(f: StringBuilder) {
        Bytes(this.value).fmt(f)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Literal && this.value == other.value)

    override fun hashCode(): Int = this.value.hashCode()

    override fun toString(): String = buildString { fmt(this) }
}

/**
 * A set of characters represented by Unicode scalar values.
 */
public open class ClassUnicode internal constructor(
    setCtorArg: IntervalSet<ClassUnicodeRange, UInt>,
) {
    internal var set: IntervalSet<ClassUnicodeRange, UInt> = setCtorArg

    /**
     * Add a new range to this set.
     */
    public open fun push(range: ClassUnicodeRange) {
        this.set.push(range)
    }

    /**
     * Return an iterator over all ranges in this class.
     *
     * The iterator yields ranges in ascending order.
     */
    public open fun iter(): ClassUnicodeIter = ClassUnicodeIter(this.set.iter())

    /**
     * Return the underlying ranges as a slice.
     */
    public open fun ranges(): List<ClassUnicodeRange> = this.set.intervals()

    /**
     * Expand this character class such that it contains all case folded
     * characters, according to Unicode's "simple" mapping. For example, if
     * this class consists of the range `a - z`, then applying case folding will
     * result in the class containing both the ranges `a - z` and `A - Z`.
     *
     * # Panics
     *
     * This routine panics when the case mapping data necessary for this
     * routine to complete is unavailable. This occurs when the `unicode-case`
     * feature is not enabled.
     *
     * Callers should prefer using `tryCaseFoldSimple` instead, which will
     * return an error instead of panicking.
     */
    public open fun caseFoldSimple() {
        when (this.set.caseFoldSimple()) {
            is Result.Ok -> {}
            is Result.Err -> error("unicode-case feature must be enabled")
        }
    }

    /**
     * Expand this character class such that it contains all case folded
     * characters, according to Unicode's "simple" mapping. For example, if
     * this class consists of the range `a - z`, then applying case folding will
     * result in the class containing both the ranges `a - z` and `A - Z`.
     *
     * # Error
     *
     * This routine returns an error when the case mapping data necessary
     * for this routine to complete is unavailable. This occurs when the
     * `unicode-case` feature is not enabled.
     */
    public open fun tryCaseFoldSimple(): Result<Unit, CaseFoldError> = this.set.caseFoldSimple()

    /**
     * Negate this character class.
     *
     * For all `c` where `c` is a Unicode scalar value, if `c` was in this
     * set, then it will not be in this set after negation.
     */
    public open fun negate() {
        this.set.negate()
    }

    /**
     * Union this character class with the given character class, in place.
     */
    public open fun union(other: ClassUnicode) {
        this.set.union(other.set)
    }

    /**
     * Intersect this character class with the given character class, in
     * place.
     */
    public open fun intersect(other: ClassUnicode) {
        this.set.intersect(other.set)
    }

    /**
     * Subtract the given character class from this character class, in place.
     */
    public open fun difference(other: ClassUnicode) {
        this.set.difference(other.set)
    }

    /**
     * Compute the symmetric difference of the given character classes, in
     * place.
     *
     * This computes the symmetric difference of two character classes. This
     * removes all elements in this class that are also in the given class,
     * but adds all elements from the given class that aren't in this
     * class. That is, the class will contain all elements in either class,
     * but will not contain any elements that are in both classes.
     */
    public open fun symmetricDifference(other: ClassUnicode) {
        this.set.symmetricDifference(other.set)
    }

    /**
     * Returns true if and only if this character class will either match
     * nothing or only ASCII bytes. Stated differently, this returns false
     * if and only if this class contains a non-ASCII codepoint.
     */
    public open fun isAscii(): Boolean =
        this.set
            .intervals()
            .lastOrNull()
            ?.let { r -> r.end <= 0x7Fu } ?: true

    /**
     * Returns the length, in bytes, of the smallest string matched by this
     * character class.
     *
     * Returns `None` when the class is empty.
     */
    public open fun minimumLen(): Int? {
        val first = this.ranges().firstOrNull() ?: return null
        // Correct because c1 < c2 implies scalarLenUtf8(c1) <= scalarLenUtf8(c2).
        return scalarLenUtf8(first.start)
    }

    /**
     * Returns the length, in bytes, of the longest string matched by this
     * character class.
     *
     * Returns `None` when the class is empty.
     */
    public open fun maximumLen(): Int? {
        val last = this.ranges().lastOrNull() ?: return null
        // Correct because c1 < c2 implies scalarLenUtf8(c1) <= scalarLenUtf8(c2).
        return scalarLenUtf8(last.end)
    }

    /**
     * If this class consists of exactly one codepoint, then return it as
     * a literal byte string.
     *
     * If this class is empty or contains more than one codepoint, then `None`
     * is returned.
     */
    public open fun literal(): List<UByte>? {
        val rs = this.ranges()
        return if (rs.size == 1 && rs[0].start == rs[0].end) {
            encodeScalarUtf8List(rs[0].start)
        } else {
            null
        }
    }

    /**
     * If this class consists of only ASCII ranges, then return its
     * corresponding and equivalent byte class.
     */
    public open fun toByteClass(): ClassBytes? {
        if (!this.isAscii()) {
            return null
        }
        return ClassBytes.new(
            this.ranges().map { r ->
                // Since we are guaranteed that our codepoint range is ASCII, the
                // UByte conversions below are guaranteed to be correct.
                ClassBytesRange(start = r.start.toUByte(), end = r.end.toUByte())
            },
        )
    }

    internal fun clone(): ClassUnicode = ClassUnicode(setCtorArg = this.set.copySet())

    override fun equals(other: Any?): Boolean =
        this === other || (other is ClassUnicode && this.set == other.set)

    override fun hashCode(): Int = this.set.hashCode()

    override fun toString(): String =
        buildString {
            val entries = mutableListOf<String>()
            for (r in ranges()) {
                entries.add(r.toString())
            }
            append("ClassUnicode(${entries.joinToString(", ", "{", "}")})")
        }

    public companion object {
        internal operator fun invoke(
            set: IntervalSet<ClassUnicodeRange, UInt>,
        ): ClassUnicode = ClassUnicode(setCtorArg = set)

        /**
         * Create a new class from a sequence of ranges.
         *
         * The given ranges do not need to be in any specific order, and ranges
         * may overlap. Ranges will automatically be sorted into a canonical
         * non-overlapping order.
         */
        public fun new(ranges: List<ClassUnicodeRange>): ClassUnicode =
            ClassUnicode(set = IntervalSet.new(ranges, ClassUnicodeRangeInterval))

        /**
         * Create a new class with no ranges.
         *
         * An empty class matches nothing. That is, it is equivalent to
         * [Hir.fail].
         */
        public fun empty(): ClassUnicode = ClassUnicode.new(emptyList())
    }
}

/**
 * An iterator over all ranges in a Unicode character class.
 */
public open class ClassUnicodeIter internal constructor(
    valueCtorArg: IntervalSetIter<ClassUnicodeRange>,
) : Iterable<ClassUnicodeRange> {
    internal var value: IntervalSetIter<ClassUnicodeRange> = valueCtorArg

    override fun iterator(): kotlin.collections.Iterator<ClassUnicodeRange> =
        object : AbstractIterator<ClassUnicodeRange>() {
            override fun computeNext() {
                when (val v = this@ClassUnicodeIter.next()) {
                    null -> done()
                    else -> setNext(v)
                }
            }
        }

    public open fun next(): ClassUnicodeRange? = this.value.next()

    internal companion object {
        internal operator fun invoke(
            value: IntervalSetIter<ClassUnicodeRange>,
        ): ClassUnicodeIter = ClassUnicodeIter(valueCtorArg = value)
    }
}

/**
 * A single range of characters represented by Unicode scalar values.
 *
 * The range is closed. That is, the start and end of the range are included
 * in the range.
 */
public open class ClassUnicodeRange internal constructor(
    internal var start: UInt,
    internal var end: UInt,
) : Comparable<ClassUnicodeRange> {
    override fun compareTo(other: ClassUnicodeRange): Int {
        val startCmp = this.start.compareTo(other.start)
        return if (startCmp != 0) startCmp else this.end.compareTo(other.end)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ClassUnicodeRange && this.start == other.start && this.end == other.end)

    override fun hashCode(): Int {
        var result = this.start.hashCode()
        result = 31 * result + this.end.hashCode()
        return result
    }

    public open fun fmt(f: StringBuilder) {
        val startStr =
            if (!scalarIsWhitespace(this.start) && !scalarIsControl(this.start)) {
                scalarToString(this.start)
            } else {
                "0x${formatHexUpper(this.start)}"
            }
        val endStr =
            if (!scalarIsWhitespace(this.end) && !scalarIsControl(this.end)) {
                scalarToString(this.end)
            } else {
                "0x${formatHexUpper(this.end)}"
            }
        DebugStruct(f, "ClassUnicodeRange").field("start", startStr).field("end", endStr).finish()
    }

    override fun toString(): String = buildString { fmt(this) }

    internal fun lower(): UInt = this.start

    internal fun upper(): UInt = this.end

    internal fun setLower(bound: UInt) {
        this.start = bound
    }

    internal fun setUpper(bound: UInt) {
        this.end = bound
    }

    /**
     * Apply simple case folding to this Unicode scalar value range.
     *
     * Additional ranges are appended to the given vector. Canonical ordering
     * is *not* maintained in the given vector.
     */
    internal fun caseFoldSimple(ranges: MutableList<ClassUnicodeRange>): Result<Unit, CaseFoldError> {
        val folder =
            when (val res = SimpleCaseFolder.new()) {
                is Result.Ok -> res.value
                is Result.Err -> return Result.Err(res.error)
            }
        if (!folder.overlaps(this.start, this.end)) {
            return Result.Ok(Unit)
        }
        val start = this.start
        val end = this.end
        for (raw in start..end) {
            val cp = scalarFromU32(raw) ?: continue
            for (cpFolded in folder.mapping(cp)) {
                ranges.add(ClassUnicodeRange.new(cpFolded, cpFolded))
            }
        }
        return Result.Ok(Unit)
    }

    /**
     * Return the start of this range.
     *
     * The start of a range is always less than or equal to the end of the
     * range.
     */
    public open fun start(): UInt = this.start

    /**
     * Return the end of this range.
     *
     * The end of a range is always greater than or equal to the start of the
     * range.
     */
    public open fun end(): UInt = this.end

    /**
     * Returns the number of codepoints in this range.
     */
    public open fun len(): Int {
        val diff = 1u + this.end - this.start
        return diff.toInt()
    }

    public companion object {
        /**
         * Create a new Unicode scalar value range for a character class.
         *
         * The returned range is always in a canonical form. That is, the range
         * returned always satisfies the invariant that `start <= end`.
         */
        public fun new(start: UInt, end: UInt): ClassUnicodeRange = ClassUnicodeRangeInterval.create(start, end)

        internal fun default(): ClassUnicodeRange = ClassUnicodeRange(start = 0x00u, end = 0x00u)
    }
}

internal object ClassUnicodeRangeInterval : Interval<ClassUnicodeRange, UInt> {
    override fun boundOps(): Bound<UInt> = ScalarBound

    override fun lower(range: ClassUnicodeRange): UInt = range.start

    override fun upper(range: ClassUnicodeRange): UInt = range.end

    override fun setLower(range: ClassUnicodeRange, bound: UInt) {
        range.start = bound
    }

    override fun setUpper(range: ClassUnicodeRange, bound: UInt) {
        range.end = bound
    }

    override fun copyInterval(range: ClassUnicodeRange): ClassUnicodeRange =
        ClassUnicodeRange(start = range.start, end = range.end)

    override fun defaultInterval(): ClassUnicodeRange = ClassUnicodeRange.default()

    override fun caseFoldSimple(
        range: ClassUnicodeRange,
        intervals: MutableList<ClassUnicodeRange>,
    ): Result<Unit, CaseFoldError> = range.caseFoldSimple(intervals)
}

/**
 * A set of characters represented by arbitrary bytes.
 *
 * Each byte corresponds to one character.
 */
public open class ClassBytes internal constructor(
    setCtorArg: IntervalSet<ClassBytesRange, UByte>,
) {
    internal var set: IntervalSet<ClassBytesRange, UByte> = setCtorArg

    /**
     * Add a new range to this set.
     */
    public open fun push(range: ClassBytesRange) {
        this.set.push(range)
    }

    /**
     * Return an iterator over all ranges in this class.
     *
     * The iterator yields ranges in ascending order.
     */
    public open fun iter(): ClassBytesIter = ClassBytesIter(this.set.iter())

    /**
     * Return the underlying ranges as a slice.
     */
    public open fun ranges(): List<ClassBytesRange> = this.set.intervals()

    /**
     * Expand this character class such that it contains all case folded
     * characters. For example, if this class consists of the range `a - z`,
     * then applying case folding will result in the class containing both the
     * ranges `a - z` and `A - Z`.
     *
     * Note that this only applies ASCII case folding, which is limited to the
     * characters `a - z` and `A - Z`.
     */
    public open fun caseFoldSimple() {
        when (this.set.caseFoldSimple()) {
            is Result.Ok -> {}
            is Result.Err -> error("ASCII case folding never fails")
        }
    }

    /**
     * Negate this byte class.
     *
     * For all `b` where `b` is any byte, if `b` was in this set, then it
     * will not be in this set after negation.
     */
    public open fun negate() {
        this.set.negate()
    }

    /**
     * Union this byte class with the given byte class, in place.
     */
    public open fun union(other: ClassBytes) {
        this.set.union(other.set)
    }

    /**
     * Intersect this byte class with the given byte class, in place.
     */
    public open fun intersect(other: ClassBytes) {
        this.set.intersect(other.set)
    }

    /**
     * Subtract the given byte class from this byte class, in place.
     */
    public open fun difference(other: ClassBytes) {
        this.set.difference(other.set)
    }

    /**
     * Compute the symmetric difference of the given byte classes, in place.
     *
     * This computes the symmetric difference of two byte classes. This
     * removes all elements in this class that are also in the given class,
     * but adds all elements from the given class that aren't in this
     * class. That is, the class will contain all elements in either class,
     * but will not contain any elements that are in both classes.
     */
    public open fun symmetricDifference(other: ClassBytes) {
        this.set.symmetricDifference(other.set)
    }

    /**
     * Returns true if and only if this character class will either match
     * nothing or only ASCII bytes. Stated differently, this returns false
     * if and only if this class contains a non-ASCII byte.
     */
    public open fun isAscii(): Boolean =
        this.set
            .intervals()
            .lastOrNull()
            ?.let { r -> r.end <= 0x7Fu.toUByte() } ?: true

    /**
     * Returns the length, in bytes, of the smallest string matched by this
     * character class.
     *
     * Returns `None` when the class is empty.
     */
    public open fun minimumLen(): Int? =
        if (this.ranges().isEmpty()) {
            null
        } else {
            1
        }

    /**
     * Returns the length, in bytes, of the longest string matched by this
     * character class.
     *
     * Returns `None` when the class is empty.
     */
    public open fun maximumLen(): Int? =
        if (this.ranges().isEmpty()) {
            null
        } else {
            1
        }

    /**
     * If this class consists of exactly one byte, then return it as
     * a literal byte string.
     *
     * If this class is empty or contains more than one byte, then `None`
     * is returned.
     */
    public open fun literal(): List<UByte>? {
        val rs = this.ranges()
        return if (rs.size == 1 && rs[0].start == rs[0].end) {
            listOf(rs[0].start)
        } else {
            null
        }
    }

    /**
     * If this class consists of only ASCII ranges, then return its
     * corresponding and equivalent Unicode class.
     */
    public open fun toUnicodeClass(): ClassUnicode? {
        if (!this.isAscii()) {
            return null
        }
        return ClassUnicode.new(
            this.ranges().map { r ->
                // Since we are guaranteed that our byte range is ASCII, the
                // UInt conversions below are correct and will not erroneously
                // convert a raw byte value into its corresponding codepoint.
                ClassUnicodeRange(start = r.start.toUInt(), end = r.end.toUInt())
            },
        )
    }

    internal fun clone(): ClassBytes = ClassBytes(setCtorArg = this.set.copySet())

    override fun equals(other: Any?): Boolean =
        this === other || (other is ClassBytes && this.set == other.set)

    override fun hashCode(): Int = this.set.hashCode()

    override fun toString(): String =
        buildString {
            val entries = mutableListOf<String>()
            for (r in ranges()) {
                entries.add(r.toString())
            }
            append("ClassBytes(${entries.joinToString(", ", "{", "}")})")
        }

    public companion object {
        internal operator fun invoke(
            set: IntervalSet<ClassBytesRange, UByte>,
        ): ClassBytes = ClassBytes(setCtorArg = set)

        /**
         * Create a new class from a sequence of ranges.
         *
         * The given ranges do not need to be in any specific order, and ranges
         * may overlap. Ranges will automatically be sorted into a canonical
         * non-overlapping order.
         */
        public fun new(ranges: List<ClassBytesRange>): ClassBytes =
            ClassBytes(set = IntervalSet.new(ranges, ClassBytesRangeInterval))

        /**
         * Create a new class with no ranges.
         *
         * An empty class matches nothing. That is, it is equivalent to
         * [Hir.fail].
         */
        public fun empty(): ClassBytes = ClassBytes.new(emptyList())
    }
}

/**
 * An iterator over all ranges in a byte character class.
 */
public open class ClassBytesIter internal constructor(
    valueCtorArg: IntervalSetIter<ClassBytesRange>,
) : Iterable<ClassBytesRange> {
    internal var value: IntervalSetIter<ClassBytesRange> = valueCtorArg

    override fun iterator(): kotlin.collections.Iterator<ClassBytesRange> =
        object : AbstractIterator<ClassBytesRange>() {
            override fun computeNext() {
                when (val v = this@ClassBytesIter.next()) {
                    null -> done()
                    else -> setNext(v)
                }
            }
        }

    public open fun next(): ClassBytesRange? = this.value.next()

    internal companion object {
        internal operator fun invoke(
            value: IntervalSetIter<ClassBytesRange>,
        ): ClassBytesIter = ClassBytesIter(valueCtorArg = value)
    }
}

/**
 * A single range of characters represented by arbitrary bytes.
 *
 * The range is closed. That is, the start and end of the range are included
 * in the range.
 */
public open class ClassBytesRange internal constructor(
    internal var start: UByte,
    internal var end: UByte,
) : Comparable<ClassBytesRange> {
    override fun compareTo(other: ClassBytesRange): Int {
        val startCmp = this.start.compareTo(other.start)
        return if (startCmp != 0) startCmp else this.end.compareTo(other.end)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ClassBytesRange && this.start == other.start && this.end == other.end)

    override fun hashCode(): Int {
        var result = this.start.hashCode()
        result = 31 * result + this.end.hashCode()
        return result
    }

    internal fun lower(): UByte = this.start

    internal fun upper(): UByte = this.end

    internal fun setLower(bound: UByte) {
        this.start = bound
    }

    internal fun setUpper(bound: UByte) {
        this.end = bound
    }

    /**
     * Apply simple case folding to this byte range. Only ASCII case mappings
     * (for a-z) are applied.
     *
     * Additional ranges are appended to the given vector. Canonical ordering
     * is *not* maintained in the given vector.
     */
    internal fun caseFoldSimple(ranges: MutableList<ClassBytesRange>): Result<Unit, CaseFoldError> {
        if (!ClassBytesRangeInterval.isIntersectionEmpty(ClassBytesRange.new(97u, 122u), this)) {
            val lower = maxOf(this.start, 97u.toUByte())
            val upper = minOf(this.end, 122u.toUByte())
            ranges.add(ClassBytesRange.new((lower.toUInt() - 32u).toUByte(), (upper.toUInt() - 32u).toUByte()))
        }
        if (!ClassBytesRangeInterval.isIntersectionEmpty(ClassBytesRange.new(65u, 90u), this)) {
            val lower1 = maxOf(this.start, 65u.toUByte())
            val upper1 = minOf(this.end, 90u.toUByte())
            ranges.add(ClassBytesRange.new((lower1.toUInt() + 32u).toUByte(), (upper1.toUInt() + 32u).toUByte()))
        }
        return Result.Ok(Unit)
    }

    /**
     * Return the start of this range.
     *
     * The start of a range is always less than or equal to the end of the
     * range.
     */
    public open fun start(): UByte = this.start

    /**
     * Return the end of this range.
     *
     * The end of a range is always greater than or equal to the start of the
     * range.
     */
    public open fun end(): UByte = this.end

    /**
     * Returns the number of bytes in this range.
     */
    public open fun len(): Int = (this.end.toInt() - this.start.toInt()) + 1

    public open fun fmt(f: StringBuilder) {
        DebugStruct(f, "ClassBytesRange")
            .field(
                "start",
                io.github.kotlinmania.regexsyntax.debug
                    .Byte(this.start),
            ).field(
                "end",
                io.github.kotlinmania.regexsyntax.debug
                    .Byte(this.end),
            ).finish()
    }

    override fun toString(): String = buildString { fmt(this) }

    public companion object {
        /**
         * Create a new byte range for a character class.
         *
         * The returned range is always in a canonical form. That is, the range
         * returned always satisfies the invariant that `start <= end`.
         */
        public fun new(start: UByte, end: UByte): ClassBytesRange = ClassBytesRangeInterval.create(start, end)

        internal fun default(): ClassBytesRange = ClassBytesRange(start = 0u, end = 0u)
    }
}

internal object ClassBytesRangeInterval : Interval<ClassBytesRange, UByte> {
    override fun boundOps(): Bound<UByte> = UByteBound

    override fun lower(range: ClassBytesRange): UByte = range.start

    override fun upper(range: ClassBytesRange): UByte = range.end

    override fun setLower(range: ClassBytesRange, bound: UByte) {
        range.start = bound
    }

    override fun setUpper(range: ClassBytesRange, bound: UByte) {
        range.end = bound
    }

    override fun copyInterval(range: ClassBytesRange): ClassBytesRange =
        ClassBytesRange(start = range.start, end = range.end)

    override fun defaultInterval(): ClassBytesRange = ClassBytesRange.default()

    override fun caseFoldSimple(
        range: ClassBytesRange,
        intervals: MutableList<ClassBytesRange>,
    ): Result<Unit, CaseFoldError> = range.caseFoldSimple(intervals)
}

/**
 * The high-level intermediate representation for a capturing group.
 *
 * A capturing group always has an index and a child expression. It may
 * also have a name associated with it (e.g., `(?P<foo>\w)`), but it's not
 * necessary.
 *
 * Note that there is no explicit representation of a non-capturing group
 * in a `Hir`. Instead, non-capturing grouping is handled automatically by
 * the recursive structure of the `Hir` itself.
 */
public open class Capture(
    /**
     * The capture index of the capture.
     */
    public var index: UInt,
    /**
     * The name of the capture, if it exists.
     */
    public var name: String?,
    /**
     * The expression inside the capturing group, which may be empty.
     */
    public var sub: Hir,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Capture && this.index == other.index && this.name == other.name && this.sub == other.sub)

    override fun hashCode(): Int {
        var result = this.index.hashCode()
        result = 31 * result + (this.name?.hashCode() ?: 0)
        result = 31 * result + this.sub.hashCode()
        return result
    }

    override fun toString(): String = "Capture(index=$index, name=$name, sub=$sub)"
}

/**
 * The high-level intermediate representation of a repetition operator.
 *
 * A repetition operator permits the repetition of an arbitrary
 * sub-expression.
 */
public open class Repetition(
    /**
     * The minimum range of the repetition.
     *
     * Note that special cases like `?`, `+` and `*` all get translated into
     * the ranges `{ 0, 1 }`, `{ 1, }` and `{ 0, }`, respectively.
     *
     * When `min` is zero, this expression can match the empty string
     * regardless of what its sub-expression is.
     */
    public var min: UInt,
    /**
     * The maximum range of the repetition.
     *
     * Note that when `max` is `None`, `min` acts as a lower bound but where
     * there is no upper bound. For something like `x { 5 }` where the min and
     * max are equivalent, `min` will be set to `5` and `max` will be set to
     * `Some(5)`.
     */
    public var max: UInt?,
    /**
     * Whether this repetition operator is greedy or not. A greedy operator
     * will match as much as it can. A non-greedy operator will match as
     * little as it can.
     *
     * Typically, operators are greedy by default and are only non-greedy when
     * a `?` suffix is used, e.g., `(expr) *` is greedy while `(expr) * ?` is
     * not. However, this can be inverted via the `U` "ungreedy" flag.
     */
    public var greedy: Boolean,
    /**
     * The expression being repeated.
     */
    public var sub: Hir,
) {
    /**
     * Returns a new repetition with the same `min`, `max` and `greedy`
     * values, but with its sub-expression replaced with the one given.
     */
    public open fun with(sub: Hir): Repetition = Repetition(min = this.min, max = this.max, greedy = this.greedy, sub = sub)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Repetition && this.min == other.min && this.max == other.max && this.greedy == other.greedy && this.sub == other.sub)

    override fun hashCode(): Int {
        var result = this.min.hashCode()
        result = 31 * result + (this.max?.hashCode() ?: 0)
        result = 31 * result + this.greedy.hashCode()
        result = 31 * result + this.sub.hashCode()
        return result
    }

    override fun toString(): String = "Repetition(min=$min, max=$max, greedy=$greedy, sub=$sub)"
}

/**
 * A type that collects various properties of an HIR value.
 *
 * Properties are always scalar values and represent meta data that is
 * computed inductively on an HIR value. Properties are defined for all
 * HIR values.
 *
 * All methods on a `Properties` value take constant time and are meant to
 * be cheap to call.
 */
public open class Properties internal constructor(
    internal var value: PropertiesI,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Properties && this.value == other.value)

    override fun hashCode(): Int = this.value.hashCode()

    override fun toString(): String = "Properties(${this.value})"

    /**
     * Returns the length (in bytes) of the smallest string matched by this
     * HIR.
     *
     * A return value of `0` is possible and occurs when the HIR can match an
     * empty string.
     *
     * `None` is returned when there is no minimum length. This occurs in
     * precisely the cases where the HIR matches nothing. i.e., The language
     * the regex matches is empty. An example of such a regex is `\P{any}`.
     */
    public open fun minimumLen(): Int? = this.value.minimumLen

    /**
     * Returns the length (in bytes) of the longest string matched by this
     * HIR.
     *
     * A return value of `0` is possible and occurs when nothing longer than
     * the empty string is in the language described by this HIR.
     *
     * `None` is returned when there is no longest matching string. This
     * occurs when the HIR matches nothing or when there is no upper bound on
     * the length of matching strings. Example of such regexes are `\P{any}`
     * (matches nothing) and `a +` (has no upper bound).
     */
    public open fun maximumLen(): Int? = this.value.maximumLen

    /**
     * Returns a set of all look-around assertions that appear at least once
     * in this HIR value.
     */
    public open fun lookSet(): LookSet = LookSet(bits = this.value.lookSet.bits)

    /**
     * Returns a set of all look-around assertions that appear as a prefix for
     * this HIR value. That is, the set returned corresponds to the set of
     * assertions that must be passed before matching any bytes in a haystack.
     *
     * For example, `hir.lookSetPrefix().contains(Look.Start)` returns true
     * if and only if the HIR is fully anchored at the start.
     */
    public open fun lookSetPrefix(): LookSet = LookSet(bits = this.value.lookSetPrefix.bits)

    /**
     * Returns a set of all look-around assertions that appear as a _possible_
     * prefix for this HIR value. That is, the set returned corresponds to the
     * set of assertions that _may_ be passed before matching any bytes in a
     * haystack.
     *
     * For example, `hir.lookSetPrefixAny().contains(Look.Start)` returns
     * true if and only if it's possible for the regex to match through a
     * anchored assertion before consuming any input.
     */
    public open fun lookSetPrefixAny(): LookSet = LookSet(bits = this.value.lookSetPrefixAny.bits)

    /**
     * Returns a set of all look-around assertions that appear as a suffix for
     * this HIR value. That is, the set returned corresponds to the set of
     * assertions that must be passed in order to be considered a match after
     * all other consuming HIR expressions.
     *
     * For example, `hir.lookSetSuffix().contains(Look.End)` returns true
     * if and only if the HIR is fully anchored at the end.
     */
    public open fun lookSetSuffix(): LookSet = LookSet(bits = this.value.lookSetSuffix.bits)

    /**
     * Returns a set of all look-around assertions that appear as a _possible_
     * suffix for this HIR value. That is, the set returned corresponds to the
     * set of assertions that _may_ be passed before matching any bytes in a
     * haystack.
     *
     * For example, `hir.lookSetSuffixAny().contains(Look.End)` returns
     * true if and only if it's possible for the regex to match through a
     * anchored assertion at the end of a match without consuming any input.
     */
    public open fun lookSetSuffixAny(): LookSet = LookSet(bits = this.value.lookSetSuffixAny.bits)

    /**
     * Return true if and only if the corresponding HIR will always match
     * valid UTF-8.
     *
     * When this returns false, then it is possible for this HIR expression to
     * match invalid UTF-8, including by matching between the code units of
     * a single UTF-8 encoded codepoint.
     *
     * Note that this returns true even when the corresponding HIR can match
     * the empty string. Since an empty string can technically appear between
     * UTF-8 code units, it is possible for a match to be reported that splits
     * a codepoint which could in turn be considered matching invalid UTF-8.
     * However, it is generally assumed that such empty matches are handled
     * specially by the search routine if it is absolutely required that
     * matches not split a codepoint.
     *
     * # Example
     *
     * This code example shows the UTF-8 property of a variety of patterns.
     *
     * ```kotlin
     * import regexsyntax.ParserBuilder
     * import regexsyntax.parse
     *
     * // Examples of isUtf8() == true'.
     * check(parse("a").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * check(parse("[^a]").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * check(parse(".").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * check(parse("\\W").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * check(parse("\\b").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * check(parse("\\B").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * check(parse("(?-u)\\b").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * check(parse("(?-u)\\B").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * // Unicode mode is enabled by default, and in
     * // that mode, all \x hex escapes are treated as
     * // codepoints. So this actually matches the UTF-8
     * // encoding of U+00FF.
     * check(parse("\\xFF").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * // Now we show examples of isUtf8() == false'.
     * // The only way to do this is to force the parser
     * // to permit invalid UTF-8, otherwise all of these
     * // would fail to parse!
     * val parse = { pattern ->
     *     ParserBuilder.new().utf8(false).build().parse(pattern)
     * }
     * check(!parse("(?-u)[^a]").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * check(!parse("(?-u).").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * check(!parse("(?-u)\\W").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * // Conversely to the equivalent example above,
     * // when Unicode mode is disabled, \x hex escapes
     * // are treated as their raw byte values.
     * check(!parse("(?-u)\\xFF").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * // Note that just because we disabled UTF-8 in the
     * // parser doesn't mean we still can't use Unicode.
     * // It is enabled by default, so \xFF is still
     * // equivalent to matching the UTF-8 encoding of
     * // U+00FF by default.
     * check(parse("\\xFF").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * // Even though we use raw bytes that individually
     * // are not valid UTF-8, when combined together, the
     * // overall expression *does* match valid UTF-8!
     * check(parse("(?-u)\\xE2\\x98\\x83").getOrElse { return Result.failure(it) }.properties().isUtf8())
     * ```
     */
    public open fun isUtf8(): Boolean = this.value.utf8

    /**
     * Returns the total number of explicit capturing groups in the
     * corresponding HIR.
     *
     * Note that this does not include the implicit capturing group
     * corresponding to the entire match that is typically included by regex
     * engines.
     *
     * # Example
     *
     * This method will return `0` for `a` and `1` for `(a)`:
     *
     * ```kotlin
     * import regexsyntax.parse
     *
     * check(0 == parse("a").getOrElse { return Result.failure(it) }.properties().explicitCapturesLen())
     * check(1 == parse("(a)").getOrElse { return Result.failure(it) }.properties().explicitCapturesLen())
     * ```
     */
    public open fun explicitCapturesLen(): Int = this.value.explicitCapturesLen

    /**
     * Returns the total number of explicit capturing groups that appear in
     * every possible match.
     *
     * If the number of capture groups can vary depending on the match, then
     * this returns `None`. That is, a value is only returned when the number
     * of matching groups is invariant or "static."
     *
     * Note that this does not include the implicit capturing group
     * corresponding to the entire match.
     *
     * # Example
     *
     * This shows a few cases where a static number of capture groups is
     * available and a few cases where it is not.
     *
     * ```kotlin
     * import regexsyntax.parse
     *
     * val len = { pattern ->
     *     parse(pattern).map({ h ->
     *         h.properties().staticExplicitCapturesLen()
     *     })
     * }
     * check(0 == len("a").getOrElse { return Result.failure(it) })
     * check(1 == len("(a)").getOrElse { return Result.failure(it) })
     * check(1 == len("(a)|(b)").getOrElse { return Result.failure(it) })
     * check(2 == len("(a)(b)|(c)(d)").getOrElse { return Result.failure(it) })
     * check(null == len("(a)|b").getOrElse { return Result.failure(it) })
     * check(null == len("a|(b)").getOrElse { return Result.failure(it) })
     * check(null == len("(b)*").getOrElse { return Result.failure(it) })
     * check(1 == len("(b)+").getOrElse { return Result.failure(it) })
     * ```
     */
    public open fun staticExplicitCapturesLen(): Int? = this.value.staticExplicitCapturesLen

    /**
     * Return true if and only if this HIR is a simple literal. This is
     * only true when this HIR expression is either itself a `Literal` or a
     * concatenation of only `Literal`s.
     *
     * For example, `f` and `foo` are literals, but `f +`, `(foo)`, `foo()` and
     * the empty string are not (even though they contain sub-expressions that
     * are literals).
     */
    public open fun isLiteral(): Boolean = this.value.literal

    /**
     * Return true if and only if this HIR is either a simple literal or an
     * alternation of simple literals. This is only
     * true when this HIR expression is either itself a `Literal` or a
     * concatenation of only `Literal`s or an alternation of only `Literal`s.
     *
     * For example, `f`, `foo`, `a | b | c`, and `foo | bar | baz` are alternation
     * literals, but `f +`, `(foo)`, `foo()`, and the empty pattern are not
     * (even though that contain sub-expressions that are literals).
     */
    public open fun isAlternationLiteral(): Boolean = this.value.alternationLiteral

    /**
     * Returns the total amount of heap memory usage, in bytes, used by this
     * `Properties` value.
     */
    public open fun memoryUsage(): ULong = 48uL

    internal fun clone(): Properties = Properties(this.value.copyProps())

    public companion object {
        /**
         * Returns a new set of properties that corresponds to the union of the
         * list of properties given.
         */
        public fun union(props: List<Properties>): Properties {
            val first = props.firstOrNull()
            // While empty alternations aren't possible, we still behave as if they
            // are. When we have an empty alternate, then clearly the look-around
            // prefix and suffix is empty. Otherwise, it is the intersection of all
            // prefixes and suffixes (respectively) of the branches.
            val fixPrefix = if (first == null) LookSet.empty() else LookSet.full()
            val fixSuffix = if (first == null) LookSet.empty() else LookSet.full()
            // And also, an empty alternate means we have 0 static capture groups,
            // but we otherwise start with the number corresponding to the first
            // alternate. If any subsequent alternate has a different number of
            // static capture groups, then we overall have a variation and not a
            // static number of groups.
            val staticExplicitCapturesLen = first?.staticExplicitCapturesLen()
            // The base case is an empty alternation, which matches nothing.
            // Note though that empty alternations aren't possible, because the
            // Hir.alternation smart constructor rewrites those as empty character
            // classes.
            val props1 =
                PropertiesI(
                    minimumLen = null,
                    maximumLen = null,
                    lookSet = LookSet.empty(),
                    lookSetPrefix = fixPrefix,
                    lookSetSuffix = fixSuffix,
                    lookSetPrefixAny = LookSet.empty(),
                    lookSetSuffixAny = LookSet.empty(),
                    utf8 = true,
                    explicitCapturesLen = 0,
                    staticExplicitCapturesLen = staticExplicitCapturesLen,
                    literal = false,
                    alternationLiteral = true,
                )
            var minPoisoned = false
            var maxPoisoned = false
            // Handle properties that need to visit every child hir.
            for (p in props) {
                props1.lookSet.setUnion(p.lookSet())
                props1.lookSetPrefix.setIntersect(p.lookSetPrefix())
                props1.lookSetSuffix.setIntersect(p.lookSetSuffix())
                props1.lookSetPrefixAny.setUnion(p.lookSetPrefixAny())
                props1.lookSetSuffixAny.setUnion(p.lookSetSuffixAny())
                props1.utf8 = props1.utf8 && p.isUtf8()
                props1.explicitCapturesLen = props1.explicitCapturesLen.saturatingAdd(p.explicitCapturesLen())
                if (props1.staticExplicitCapturesLen != p.staticExplicitCapturesLen()) {
                    props1.staticExplicitCapturesLen = null
                }
                props1.alternationLiteral = props1.alternationLiteral && p.isLiteral()
                if (!minPoisoned) {
                    when (val xmin = p.minimumLen()) {
                        null -> {
                            props1.minimumLen = null
                            minPoisoned = true
                        }
                        else -> {
                            if (props1.minimumLen?.let { pmin -> xmin < pmin } ?: true) {
                                props1.minimumLen = xmin
                            }
                        }
                    }
                }
                if (!maxPoisoned) {
                    when (val xmax = p.maximumLen()) {
                        null -> {
                            props1.maximumLen = null
                            maxPoisoned = true
                        }
                        else -> {
                            if (props1.maximumLen?.let { pmax -> xmax > pmax } ?: true) {
                                props1.maximumLen = xmax
                            }
                        }
                    }
                }
            }
            return Properties(props1)
        }

        /**
         * Create a new set of HIR properties for an empty regex.
         */
        public fun empty(): Properties {
            val inner = PropertiesI(minimumLen = 0, maximumLen = 0, lookSet = LookSet.empty(), lookSetPrefix = LookSet.empty(), lookSetSuffix = LookSet.empty(), lookSetPrefixAny = LookSet.empty(), lookSetSuffixAny = LookSet.empty(), utf8 = true, explicitCapturesLen = 0, staticExplicitCapturesLen = 0, literal = false, alternationLiteral = false)
            return Properties(inner)
        }

        /**
         * Create a new set of HIR properties for a literal regex.
         */
        public fun literal(lit: Literal): Properties {
            val inner = PropertiesI(minimumLen = lit.value.size, maximumLen = lit.value.size, lookSet = LookSet.empty(), lookSetPrefix = LookSet.empty(), lookSetSuffix = LookSet.empty(), lookSetPrefixAny = LookSet.empty(), lookSetSuffixAny = LookSet.empty(), utf8 = isValidUtf8(lit.value), explicitCapturesLen = 0, staticExplicitCapturesLen = 0, literal = true, alternationLiteral = true)
            return Properties(inner)
        }

        /**
         * Create a new set of HIR properties for a character class.
         */
        public fun cls(cls: Class): Properties {
            val inner = PropertiesI(minimumLen = cls.minimumLen(), maximumLen = cls.maximumLen(), lookSet = LookSet.empty(), lookSetPrefix = LookSet.empty(), lookSetSuffix = LookSet.empty(), lookSetPrefixAny = LookSet.empty(), lookSetSuffixAny = LookSet.empty(), utf8 = cls.isUtf8(), explicitCapturesLen = 0, staticExplicitCapturesLen = 0, literal = false, alternationLiteral = false)
            return Properties(inner)
        }

        /**
         * Create a new set of HIR properties for a look-around assertion.
         */
        public fun look(look: Look): Properties {
            val inner = PropertiesI(minimumLen = 0, maximumLen = 0, lookSet = LookSet.singleton(look), lookSetPrefix = LookSet.singleton(look), lookSetSuffix = LookSet.singleton(look), lookSetPrefixAny = LookSet.singleton(look), lookSetSuffixAny = LookSet.singleton(look), utf8 = true, explicitCapturesLen = 0, staticExplicitCapturesLen = 0, literal = false, alternationLiteral = false)
            return Properties(inner)
        }

        /**
         * Create a new set of HIR properties for a repetition.
         */
        public fun repetition(rep: Repetition): Properties {
            val p = rep.sub.properties()
            val minimumLen =
                p.minimumLen()?.let { childMin ->
                    val repMin = if (rep.min > Int.MAX_VALUE.toUInt()) Int.MAX_VALUE else rep.min.toInt()
                    childMin.saturatingMul(repMin)
                }
            val maximumLen =
                rep.max?.let { repMax ->
                    if (repMax > Int.MAX_VALUE.toUInt()) {
                        null
                    } else {
                        val repMax1 = repMax.toInt()
                        p.maximumLen()?.let { childMax ->
                            val prod = childMax.toLong() * repMax1.toLong()
                            if (prod in Int.MIN_VALUE..Int.MAX_VALUE) prod.toInt() else null
                        }
                    }
                }
            val inner = PropertiesI(minimumLen = minimumLen, maximumLen = maximumLen, lookSet = p.lookSet(), lookSetPrefix = LookSet.empty(), lookSetSuffix = LookSet.empty(), lookSetPrefixAny = p.lookSetPrefixAny(), lookSetSuffixAny = p.lookSetSuffixAny(), utf8 = p.isUtf8(), explicitCapturesLen = p.explicitCapturesLen(), staticExplicitCapturesLen = p.staticExplicitCapturesLen(), literal = false, alternationLiteral = false)
            // If the repetition operator can match the empty string, then its
            // lookset prefix and suffixes themselves remain empty since they are
            // no longer required to match.
            if (rep.min > 0u) {
                inner.lookSetPrefix = p.lookSetPrefix()
                inner.lookSetSuffix = p.lookSetSuffix()
            }
            // If the static captures len of the sub-expression is not known or
            // is greater than zero, then it automatically propagates to the
            // repetition, regardless of the repetition. Otherwise, it might
            // change, but only when the repetition can match 0 times.
            if (rep.min == 0u && (inner.staticExplicitCapturesLen?.let { len -> len > 0 } ?: false)) {
                // If we require a match 0 times, then our captures len is
                // guaranteed to be zero. Otherwise, if we *can* match the empty
                // string, then it's impossible to know how many captures will be
                // in the resulting match.
                if (rep.max == 0u) {
                    inner.staticExplicitCapturesLen = 0
                } else {
                    inner.staticExplicitCapturesLen = null
                }
            }
            return Properties(inner)
        }

        /**
         * Create a new set of HIR properties for a capture.
         */
        public fun capture(capture: Capture): Properties {
            val p = capture.sub.properties()
            return Properties(
                p.value.copyProps(
                    explicitCapturesLen = p.explicitCapturesLen().saturatingAdd(1),
                    staticExplicitCapturesLen = p.staticExplicitCapturesLen()?.let { len -> len.saturatingAdd(1) },
                    literal = false,
                    alternationLiteral = false,
                ),
            )
        }

        /**
         * Create a new set of HIR properties for a concatenation.
         */
        public fun concat(concat: List<Hir>): Properties {
            // The base case is an empty concatenation, which matches the empty
            // string. Note though that empty concatenations aren't possible,
            // because the Hir.concat smart constructor rewrites those as
            // Hir.empty.
            val props = PropertiesI(minimumLen = 0, maximumLen = 0, lookSet = LookSet.empty(), lookSetPrefix = LookSet.empty(), lookSetSuffix = LookSet.empty(), lookSetPrefixAny = LookSet.empty(), lookSetSuffixAny = LookSet.empty(), utf8 = true, explicitCapturesLen = 0, staticExplicitCapturesLen = 0, literal = true, alternationLiteral = true)
            // Handle properties that need to visit every child hir.
            for (x in concat) {
                val p = x.properties()
                props.lookSet.setUnion(p.lookSet())
                props.utf8 = props.utf8 && p.isUtf8()
                props.explicitCapturesLen = props.explicitCapturesLen.saturatingAdd(p.explicitCapturesLen())
                props.staticExplicitCapturesLen =
                    p.staticExplicitCapturesLen()?.let { len1 ->
                        props.staticExplicitCapturesLen?.let { len2 -> len1.saturatingAdd(len2) }
                    }
                props.literal = props.literal && p.isLiteral()
                props.alternationLiteral = props.alternationLiteral && p.isAlternationLiteral()
                when (val minimumLen = props.minimumLen) {
                    null -> {}
                    else -> {
                        when (val len = p.minimumLen()) {
                            null -> props.minimumLen = null
                            else -> {
                                // We use saturating arithmetic here because the
                                // minimum is just a lower bound. We can't go any
                                // higher than what our number types permit.
                                props.minimumLen = minimumLen.saturatingAdd(len)
                            }
                        }
                    }
                }
                when (val maximumLen = props.maximumLen) {
                    null -> {}
                    else -> {
                        when (val len1 = p.maximumLen()) {
                            null -> props.maximumLen = null
                            else -> {
                                val sum = maximumLen.toLong() + len1.toLong()
                                props.maximumLen = if (sum in Int.MIN_VALUE..Int.MAX_VALUE) sum.toInt() else null
                            }
                        }
                    }
                }
            }
            // Handle the prefix properties, which only requires visiting
            // child exprs until one matches more than the empty string.
            for (x1 in concat) {
                props.lookSetPrefix.setUnion(x1.properties().lookSetPrefix())
                props.lookSetPrefixAny.setUnion(x1.properties().lookSetPrefixAny())
                if (x1.properties().maximumLen()?.let { x2 -> x2 > 0 } ?: true) {
                    break
                }
            }
            // Same thing for the suffix properties, but in reverse.
            for (i in concat.size - 1 downTo 0) {
                val x2 = concat[i]
                props.lookSetSuffix.setUnion(x2.properties().lookSetSuffix())
                props.lookSetSuffixAny.setUnion(x2.properties().lookSetSuffixAny())
                if (x2.properties().maximumLen()?.let { x3 -> x3 > 0 } ?: true) {
                    break
                }
            }
            return Properties(props)
        }

        /**
         * Create a new set of HIR properties for an alternation.
         */
        public fun alternation(alts: List<Hir>): Properties = Properties.union(alts.map { hir -> hir.properties() })
    }
}

internal data class PropertiesI(
    public var minimumLen: Int?,
    public var maximumLen: Int?,
    public var lookSet: LookSet,
    public var lookSetPrefix: LookSet,
    public var lookSetSuffix: LookSet,
    public var lookSetPrefixAny: LookSet,
    public var lookSetSuffixAny: LookSet,
    public var utf8: Boolean,
    public var explicitCapturesLen: Int,
    public var staticExplicitCapturesLen: Int?,
    public var literal: Boolean,
    public var alternationLiteral: Boolean,
) {
    internal fun copyProps(
        explicitCapturesLen: Int = this.explicitCapturesLen,
        staticExplicitCapturesLen: Int? = this.staticExplicitCapturesLen,
        literal: Boolean = this.literal,
        alternationLiteral: Boolean = this.alternationLiteral,
    ): PropertiesI =
        PropertiesI(
            minimumLen = this.minimumLen,
            maximumLen = this.maximumLen,
            lookSet = LookSet(bits = this.lookSet.bits),
            lookSetPrefix = LookSet(bits = this.lookSetPrefix.bits),
            lookSetSuffix = LookSet(bits = this.lookSetSuffix.bits),
            lookSetPrefixAny = LookSet(bits = this.lookSetPrefixAny.bits),
            lookSetSuffixAny = LookSet(bits = this.lookSetSuffixAny.bits),
            utf8 = this.utf8,
            explicitCapturesLen = explicitCapturesLen,
            staticExplicitCapturesLen = staticExplicitCapturesLen,
            literal = literal,
            alternationLiteral = alternationLiteral,
        )
}

/**
 * A set of look-around assertions.
 *
 * This is useful for efficiently tracking look-around assertions. For
 * example, an [Hir] provides properties that return `LookSet`s.
 */
public open class LookSet(
    /**
     * The underlying representation this set is exposed to make it possible
     * to store it somewhere efficiently. The representation is that
     * of a bitset, where each assertion occupies bit `i` where `i =
     * Look.asRepr()`.
     *
     * Note that users of this internal representation must permit the full
     * range of `UShort` values to be represented. For example, even if the
     * current implementation only makes use of the 10 least significant bits,
     * it may use more bits in a future semver compatible release.
     */
    public var bits: UInt,
) {
    /**
     * Returns the total number of look-around assertions in this set.
     */
    public open fun len(): Int {
        // OK because max value fits in an Int on every target.
        return this.bits.countOneBits()
    }

    /**
     * Returns true if and only if this set is empty.
     */
    public open fun isEmpty(): Boolean = this.len() == 0

    /**
     * Returns true if and only if the given look-around assertion is in this
     * set.
     */
    public open fun contains(look: Look): Boolean = this.bits and look.asRepr() != 0u

    /**
     * Returns true if and only if this set contains any anchor assertions.
     * This includes both "start/end of haystack" and "start/end of line."
     */
    public open fun containsAnchor(): Boolean = this.containsAnchorHaystack() || this.containsAnchorLine()

    /**
     * Returns true if and only if this set contains any "start/end of
     * haystack" anchors. This doesn't include "start/end of line" anchors.
     */
    public open fun containsAnchorHaystack(): Boolean = this.contains(Look.Start) || this.contains(Look.End)

    /**
     * Returns true if and only if this set contains any "start/end of line"
     * anchors. This doesn't include "start/end of haystack" anchors. This
     * includes both `\n` line anchors and CRLF (`\r\n`) aware line anchors.
     */
    public open fun containsAnchorLine(): Boolean = this.contains(Look.StartLF) || this.contains(Look.EndLF) || this.contains(Look.StartCRLF) || this.contains(Look.EndCRLF)

    /**
     * Returns true if and only if this set contains any "start/end of line"
     * anchors that only treat `\n` as line terminators. This does not include
     * haystack anchors or CRLF aware line anchors.
     */
    public open fun containsAnchorLf(): Boolean = this.contains(Look.StartLF) || this.contains(Look.EndLF)

    /**
     * Returns true if and only if this set contains any "start/end of line"
     * anchors that are CRLF-aware. This doesn't include "start/end of
     * haystack" or "start/end of line-feed" anchors.
     */
    public open fun containsAnchorCrlf(): Boolean = this.contains(Look.StartCRLF) || this.contains(Look.EndCRLF)

    /**
     * Returns true if and only if this set contains any word boundary or
     * negated word boundary assertions. This include both Unicode and ASCII
     * word boundaries.
     */
    public open fun containsWord(): Boolean = this.containsWordUnicode() || this.containsWordAscii()

    /**
     * Returns true if and only if this set contains any Unicode word boundary
     * or negated Unicode word boundary assertions.
     */
    public open fun containsWordUnicode(): Boolean = this.contains(Look.WordUnicode) || this.contains(Look.WordUnicodeNegate) || this.contains(Look.WordStartUnicode) || this.contains(Look.WordEndUnicode) || this.contains(Look.WordStartHalfUnicode) || this.contains(Look.WordEndHalfUnicode)

    /**
     * Returns true if and only if this set contains any ASCII word boundary
     * or negated ASCII word boundary assertions.
     */
    public open fun containsWordAscii(): Boolean = this.contains(Look.WordAscii) || this.contains(Look.WordAsciiNegate) || this.contains(Look.WordStartAscii) || this.contains(Look.WordEndAscii) || this.contains(Look.WordStartHalfAscii) || this.contains(Look.WordEndHalfAscii)

    /**
     * Returns an iterator over all of the look-around assertions in this set.
     */
    public open fun iter(): LookSetIter = LookSetIter(set = LookSet(bits = this.bits))

    /**
     * Return a new set that is equivalent to the original, but with the given
     * assertion added to it. If the assertion is already in the set, then the
     * returned set is equivalent to the original.
     */
    public open fun insert(look: Look): LookSet = LookSet(bits = this.bits or look.asRepr())

    /**
     * Updates this set in place with the result of inserting the given
     * assertion into this set.
     */
    public open fun setInsert(look: Look) {
        this.bits = this.bits or look.asRepr()
    }

    /**
     * Return a new set that is equivalent to the original, but with the given
     * assertion removed from it. If the assertion is not in the set, then the
     * returned set is equivalent to the original.
     */
    public open fun remove(look: Look): LookSet = LookSet(bits = this.bits and look.asRepr().inv())

    /**
     * Updates this set in place with the result of removing the given
     * assertion from this set.
     */
    public open fun setRemove(look: Look) {
        this.bits = this.bits and look.asRepr().inv()
    }

    /**
     * Returns a new set that is the result of subtracting the given set from
     * this set.
     */
    public open fun subtract(other: LookSet): LookSet = LookSet(bits = this.bits and other.bits.inv())

    /**
     * Updates this set in place with the result of subtracting the given set
     * from this set.
     */
    public open fun setSubtract(other: LookSet) {
        this.bits = this.bits and other.bits.inv()
    }

    /**
     * Returns a new set that is the union of this and the one given.
     */
    public open fun union(other: LookSet): LookSet = LookSet(bits = this.bits or other.bits)

    /**
     * Updates this set in place with the result of unioning it with the one
     * given.
     */
    public open fun setUnion(other: LookSet) {
        this.bits = this.bits or other.bits
    }

    /**
     * Returns a new set that is the intersection of this and the one given.
     */
    public open fun intersect(other: LookSet): LookSet = LookSet(bits = this.bits and other.bits)

    /**
     * Updates this set in place with the result of intersecting it with the
     * one given.
     */
    public open fun setIntersect(other: LookSet) {
        this.bits = this.bits and other.bits
    }

    /**
     * Write a `LookSet` as a native endian 32-bit integer to the beginning
     * of the slice given.
     *
     * # Panics
     *
     * This panics if `slice.size < 4`.
     */
    public open fun writeRepr(slice: MutableList<UByte>) {
        require(slice.size >= 4) { "slice too short for LookSet.writeRepr" }
        val rawBits = this.bits
        slice[0] = rawBits.toUByte()
        slice[1] = (rawBits shr 8).toUByte()
        slice[2] = (rawBits shr 16).toUByte()
        slice[3] = (rawBits shr 24).toUByte()
    }

    public open fun fmt(f: StringBuilder) {
        if (this.isEmpty()) {
            f.append("∅")
            return
        }
        for (look in this.iter()) {
            f.appendScalar(look.asChar())
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is LookSet && this.bits == other.bits)

    override fun hashCode(): Int = this.bits.hashCode()

    override fun toString(): String = buildString { fmt(this) }

    public companion object {
        /**
         * Create an empty set of look-around assertions.
         */
        public fun empty(): LookSet = LookSet(bits = 0u)

        /**
         * Create a full set of look-around assertions.
         *
         * This set contains all possible look-around assertions.
         */
        public fun full(): LookSet = LookSet(bits = 0u.inv())

        /**
         * Create a look-around set containing the look-around assertion given.
         *
         * This is a convenience routine for creating an empty set and inserting
         * one look-around assertions.
         */
        public fun singleton(look: Look): LookSet = LookSet.empty().insert(look)

        /**
         * Return a `LookSet` from the slice given as a native endian 32-bit
         * integer.
         *
         * # Panics
         *
         * This panics if `slice.size < 4`.
         */
        public fun readRepr(slice: List<UByte>): LookSet {
            val normalized = slice.normalizeUByteList()
            require(normalized.size >= 4) { "slice too short for LookSet.readRepr" }
            val bits =
                normalized[0].toUInt() or
                    (normalized[1].toUInt() shl 8) or
                    (normalized[2].toUInt() shl 16) or
                    (normalized[3].toUInt() shl 24)
            return LookSet(bits = bits)
        }
    }
}

/**
 * An iterator over all look-around assertions in a [LookSet].
 *
 * This iterator is created by [LookSet.iter].
 */
public open class LookSetIter(
    public var set: LookSet,
) : Iterable<Look> {
    override fun iterator(): kotlin.collections.Iterator<Look> =
        object : AbstractIterator<Look>() {
            override fun computeNext() {
                when (val v = this@LookSetIter.next()) {
                    null -> done()
                    else -> setNext(v)
                }
            }
        }

    public open fun next(): Look? {
        if (this.set.isEmpty()) {
            return null
        }
        // We will never have more than 255 distinct look-around
        // assertions, so `bit` will always fit cleanly.
        val bit = this.set.bits.countTrailingZeroBits()
        val look = Look.fromRepr(1u shl bit) ?: return null
        this.set = this.set.remove(look)
        return look
    }
}

private fun Int.saturatingAdd(other: Int): Int {
    val sum = this.toLong() + other.toLong()
    return when {
        sum > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
        sum < Int.MIN_VALUE.toLong() -> Int.MIN_VALUE
        else -> sum.toInt()
    }
}

private fun Int.saturatingMul(other: Int): Int {
    val prod = this.toLong() * other.toLong()
    return when {
        prod > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
        prod < Int.MIN_VALUE.toLong() -> Int.MIN_VALUE
        else -> prod.toInt()
    }
}
