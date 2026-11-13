package io.github.kotlinmania.regexsyntax.hir

import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.debug.Byte
import io.github.kotlinmania.regexsyntax.debug.Bytes
import io.github.kotlinmania.regexsyntax.from
import io.github.kotlinmania.regexsyntax.hir.interval.Interval
import io.github.kotlinmania.regexsyntax.hir.interval.IntervalSet
import io.github.kotlinmania.regexsyntax.hir.interval.IntervalSetIter
import io.github.kotlinmania.regexsyntax.hir.print.Printer
import io.github.kotlinmania.regexsyntax.hir.visitor.visit
import io.github.kotlinmania.rustlang.core.mem.ObjectSlotLayout
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout

public class DebugStruct(
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
    public var kind: ErrorKind,
    /**
     * The original pattern that the translator's Ast was parsed from. Every
     * span in an error is a valid range into this string.
     */
    public var pattern: String,
    /**
     * The span of this error, derived from the Ast given to the translator.
     */
    public var span: Span,
) : Error {
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
        from(this).fmt(f)
    }
}

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
public open class Hir(
    /**
     * The underlying HIR kind.
     */
    public var kind: HirKind,
    /**
     * Analysis info about this HIR, computed during construction.
     */
    public var props: Properties,
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
    public open fun intoKind(): HirKind = replace(this.kind, HirKind.Empty)

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
    public open fun intoParts(): Pair<HirKind, Properties> = Pair(replace(this.kind, HirKind.Empty), replace(this.props, Properties.empty()))

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

    /**
     * A custom `Drop` impl is used for `HirKind` such that it uses constant stack
     * space but heap space proportional to the depth of the total `Hir`.
     */
    public open fun drop() {
        run {
            val matchVal = this.kind()
            when {
                matchVal == HirKind.Empty -> { return }
                matchVal is HirKind.Literal -> { return }
                matchVal is HirKind.Class -> { return }
                matchVal is HirKind.Look -> { return }
                matchVal is HirKind.Capture && (matchVal as HirKind.Capture).value.sub.kind.subs().isEmpty() -> {
                    val (x) = matchVal
                    return
                }
                matchVal is HirKind.Repetition && (matchVal as HirKind.Repetition).value.sub.kind.subs().isEmpty() -> {
                    val (x1) = matchVal
                    return
                }
                matchVal is HirKind.Concat && (matchVal as HirKind.Concat).value.isEmpty() -> {
                    val (x2) = matchVal
                    return
                }
                matchVal is HirKind.Alternation && (matchVal as HirKind.Alternation).value.isEmpty() -> {
                    val (x3) = matchVal
                    return
                }
                else -> {}
            }
        }
        val stack = mutableListOf(mem.replace(this, Hir.empty()))
        while (true) {
            val matchVal1 = stack.removeLastOrNull()
            if (matchVal1 == null) break
            val expr = matchVal1
            when (val matchVal2 = expr.kind) {
                HirKind.Empty -> {}
                is HirKind.Literal -> {}
                is HirKind.Class -> {}
                is HirKind.Look -> {}
                is HirKind.Capture -> {
                    val (x4) = matchVal2
                    stack.add(mem.replace(x4.sub, Hir.empty()))
                }
                is HirKind.Repetition -> {
                    val (x5) = matchVal2
                    stack.add(mem.replace(x5.sub, Hir.empty()))
                }
                is HirKind.Concat -> {
                    val (x6) = matchVal2
                    stack.extend(x6.drain(0 until 0))
                }
                is HirKind.Alternation -> {
                    val (x7) = matchVal2
                    stack.extend(x7.drain(0 until 0))
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
            val bytes = lit
            if (bytes.isEmpty()) {
                return Hir.empty()
            }
            val lit1 = Literal(bytes)
            val props = Properties.Literal(lit1)
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
            } else when (val matchVal = cls.literal()) {
                null -> {}
                else -> {
                    val bytes = matchVal
                    return Hir.Literal(bytes)
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
                rep.max = (rep.max?.let({ n -> minOf(n, 1) }) ?: 1)
            }
            // The regex a{0}' is always equivalent to the empty regex. This is
            // true even when 'a' is an expression that never matches anything
            // (like '\P{any}').
            //
            // Additionally, the regex a{1}' is always equivalent to 'a'.
            if (rep.min == 0u && rep.max == 0) {
                return Hir.empty()
            } else if (rep.min == 1u && rep.max == 1) {
                return rep.sub
            }
            val props = Properties.Repetition(rep)
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
         * val hir = Hir.concat(mutableListOf(Hir.concat(mutableListOf(Hir.Literal(listOf(97u)), Hir.Literal(listOf(98u)), Hir.Literal(listOf(99u)))), Hir.concat(mutableListOf(Hir.Literal(listOf(120u)), Hir.Literal(listOf(121u)), Hir.Literal(listOf(122u))))))
         * val expected = Hir.Literal("abcxyz".encodeToByteArray().map { it.toUByte() })
         * check(expected == hir)
         * ```
         */
        public fun concat(subs: List<Hir>): Hir {
            // We rebuild the concatenation by simplifying it. Would be nice to do
            // it in place, but that seems a little tricky?
            val new = mutableListOf()
            // This gobbles up any adjacent literals in a concatenation and smushes
            // them together. Basically, when we see a literal, we add its bytes
            // to 'priorLit', and whenever we see anything else, we first take
            // any bytes in 'priorLit' and add it to the 'new' concatenation.
            var priorLit: List<UByte>? = null
            for (sub in subs) {
                val (kind, props) = sub.intoParts()
                when {
                    kind is HirKind.Literal && kind.value is Literal -> {
                        val (bytes) = kind.value as Literal
                        if (priorLit != null) {
                            val priorBytes = priorLit
                            priorBytes.extendFromSlice(bytes)
                        } else {
                            priorLit = bytes.toVec()
                        }
                    }
                    // We also flatten concats that are direct children of another
                    // concat. We only need to do this one level deep since
                    // Hir.concat is the only way to build concatenations, and so
                    // flattening happens inductively.
                    kind is HirKind.Concat -> {
                        val (subs2) = kind
                        for (sub2 in subs2) {
                            val (kind2, props2) = sub2.intoParts()
                            when {
                                kind2 is HirKind.Literal && kind2.value is Literal -> {
                                    val (bytes1) = kind2.value as Literal
                                    if (priorLit != null) {
                                        val priorBytes1 = priorLit
                                        priorBytes1.extendFromSlice(bytes1)
                                    } else {
                                        priorLit = bytes1.toVec()
                                    }
                                }
                                else -> {
                                    val kind21 = kind2
                                    when (val matchVal = priorLit.also { priorLit = null }) {
                                        null -> {}
                                        else -> {
                                            val priorBytes2 = matchVal
                                            new.add(Hir.Literal(priorBytes2))
                                        }
                                    }
                                    new.add(Hir(kind = kind21, props = props2))
                                }
                            }
                        }
                    }
                    // We can just skip empty HIRs.
                    kind == HirKind.Empty -> {}
                    else -> {
                        val kind1 = kind
                        when (val matchVal1 = priorLit.also { priorLit = null }) {
                            null -> {}
                            else -> {
                                val priorBytes3 = matchVal1
                                new.add(Hir.Literal(priorBytes3))
                            }
                        }
                        new.add(Hir(kind = kind1, props = props))
                    }
                }
            }
            when (val matchVal2 = priorLit.also { priorLit = null }) {
                null -> {}
                else -> {
                    val priorBytes4 = matchVal2
                    new.add(Hir.Literal(priorBytes4))
                }
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
         * # Example
         *
         * This is a simple example showing how an alternation might get
         * simplified.
         *
         * ```kotlin
         * val hir = Hir.alternation(mutableListOf(Hir.Literal(listOf(97u)), Hir.Literal(listOf(98u)), Hir.Literal(listOf(99u)), Hir.Literal(listOf(100u)), Hir.Literal(listOf(101u)), Hir.Literal(listOf(102u))))
         * val expected = Hir.Class(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new('a', 'f')))))
         * check(expected == hir)
         * ```
         *
         * And another example showing how common prefixes might get factored
         * out.
         *
         * ```kotlin
         * val hir = Hir.alternation(mutableListOf(Hir.concat(mutableListOf(Hir.Literal("abc".encodeToByteArray().map { it.toUByte() }), Hir.Class(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new('A', 'Z'))))))), Hir.concat(mutableListOf(Hir.Literal("abc".encodeToByteArray().map { it.toUByte() }), Hir.Class(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new('a', 'z')))))))))
         * val expected = Hir.concat(mutableListOf(Hir.Literal("abc".encodeToByteArray().map { it.toUByte() }), Hir.alternation(mutableListOf(Hir.Class(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new('A', 'Z'))))), Hir.Class(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new('a', 'z')))))))))
         * check(expected == hir)
         * ```
         *
         * Note that these sorts of simplifications are not guaranteed.
         */
        public fun alternation(subs: List<Hir>): Hir {
            // We rebuild the alternation by simplifying it. We proceed similarly
            // as the concatenation case. But in this case, there's no literal
            // simplification happening. We're just flattening alternations.
            var new = ArrayList(subs.size)
            for (sub in subs) {
                val (kind, props) = sub.intoParts()
                when (kind) {
                    is HirKind.Alternation -> {
                        val (subs2) = kind
                        new.extend(subs2)
                    }
                    else -> {
                        val kind1 = kind
                        new.add(Hir(kind = kind1, props = props))
                    }
                }
            }
            if (new.isEmpty()) {
                return Hir.fail()
            } else if (new.size == 1) {
                return new.removeLastOrNull()!!
            }
            // Now that it's completely flattened, look for the special case of
            // char1|char2|...|charN' and collapse that into a class. Note that
            // we look for 'char' first and then bytes. The issue here is that if
            // we find both non-ASCII codepoints and non-ASCII singleton bytes,
            // then it isn't actually possible to smush them into a single class.
            // (Because classes are either "all codepoints" or "all bytes." You
            // can have a class that both matches non-ASCII but valid UTF-8 and
            // invalid UTF-8.) So we look for all chars and then all bytes, and
            // don't handle anything else.
            when (val matchVal = singletonChars(new)) {
                null -> {}
                else -> {
                    val singletons = matchVal
                    val it = singletons.map({ ch -> ClassUnicodeRange(start = ch, end = ch) })
                    return Hir.Class(Class.Unicode(ClassUnicode.new(it)))
                }
            }
            when (val matchVal1 = singletonBytes(new)) {
                null -> {}
                else -> {
                    val singletons1 = matchVal1
                    val it1 = singletons1.map({ b -> ClassBytesRange(start = b, end = b) })
                    return Hir.Class(Class.Bytes(ClassBytes.new(it1)))
                }
            }
            // Similar to singleton chars, we can also look for alternations of
            // classes. Those can be smushed into a single class.
            when (val matchVal2 = classChars(new)) {
                null -> {}
                else -> {
                    val cls = matchVal2
                    return Hir.Class(cls)
                }
            }
            when (val matchVal3 = classBytes(new)) {
                null -> {}
                else -> {
                    val cls1 = matchVal3
                    return Hir.Class(cls1)
                }
            }
            // Factor out a common prefix if we can, which might potentially
            // simplify the expression and unlock other optimizations downstream.
            // It also might generally make NFA matching and DFA construction
            // faster by reducing the scope of branching in the regex.
            new = run {
                val matchVal4 = liftCommonPrefix(new)
                if (matchVal4.isSuccess) {
                    val hir = matchVal4.getOrThrow()
                    return hir
                } else {
                    val unchanged = matchVal4.exceptionOrNull()!!
                    unchanged
                }
            }
            val props1 = Properties.alternation(new)
            return Hir(kind = HirKind.Alternation(new), props = props1)
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
         * val hir = Hir.Dot(Dot.AnyByte)
         * val expected = Hir.Class(Class.Bytes(ClassBytes.new(listOf(ClassBytesRange.new(0x00u, 0xFFu)))))
         * check(expected == hir)
         * ```
         */
        public fun dot(dot: Dot): Hir =
            when (dot) {
                Dot.AnyChar -> Hir.Class(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new('\u0000', 0x10ffff)))))
                Dot.AnyByte -> Hir.Class(Class.Bytes(ClassBytes.new(listOf(ClassBytesRange.new(0u, 255u)))))
                is Dot.AnyCharExcept -> {
                    val (ch) = dot
                    var cls = ClassUnicode.new(listOf(ClassUnicodeRange.new(ch, ch)))
                    cls.negate()
                    Hir.Class(Class.Unicode(cls))
                }
                Dot.AnyCharExceptLF -> {
                    Hir.Class(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new('\u0000', '\t'), ClassUnicodeRange.new('\u000b', 0x10ffff)))))
                }
                Dot.AnyCharExceptCRLF -> {
                    Hir.Class(Class.Unicode(ClassUnicode.new(listOf(ClassUnicodeRange.new('\u0000', '\t'), ClassUnicodeRange.new('\u000b', '\u000c'), ClassUnicodeRange.new('\u000e', 0x10ffff)))))
                }
                is Dot.AnyByteExcept -> {
                    val (byte) = dot
                    var cls1 = ClassBytes.new(listOf(ClassBytesRange.new(byte, byte)))
                    cls1.negate()
                    Hir.Class(Class.Bytes(cls1))
                }
                Dot.AnyByteExceptLF -> {
                    Hir.Class(Class.Bytes(ClassBytes.new(listOf(ClassBytesRange.new(0u, 9u), ClassBytesRange.new(11u, 255u)))))
                }
                Dot.AnyByteExceptCRLF -> {
                    Hir.Class(Class.Bytes(ClassBytes.new(listOf(ClassBytesRange.new(0u, 9u), ClassBytesRange.new(11u, 12u), ClassBytesRange.new(14u, 255u)))))
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
    public var field0: List<UByte>
        get() = value
        set(v) {
            value = v
        }

    public open fun fmt(f: StringBuilder) {
        Bytes(this.value).fmt(f)
    }
}

/**
 * A set of characters represented by Unicode scalar values.
 */
public open class ClassUnicode internal constructor(
    setCtorArg: IntervalSet<ClassUnicodeRange>,
) {
    public var set: IntervalSet<ClassUnicodeRange> = setCtorArg
        internal set

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
     * routine to complete is unavailable. This occurs when the `unicode - case`
     * feature is not enabled.
     *
     * Callers should prefer using `tryCaseFoldSimple` instead, which will
     * return an error instead of panicking.
     */
    public open fun caseFoldSimple() {
        this.set.caseFoldSimple().expect("unicode-case feature must be enabled")
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
     * `unicode - case` feature is not enabled.
     */
    public open fun tryCaseFoldSimple(): Result<Unit> = this.set.caseFoldSimple()

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
     * but all adds all elements from the given class that aren't in this
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
    public open fun isAscii(): Boolean = (this.set.intervals().last()?.let({ r -> r.end <= '\u007f' }) ?: true)

    /**
     * Returns the length, in bytes, of the smallest string matched by this
     * character class.
     *
     * Returns `None` when the class is empty.
     */
    public open fun minimumLen(): Int? {
        val first = (this.ranges().get(0) ?: return null)
        // Correct because c1 < c2 implies c1.lenUtf8() < c2.lenUtf8().
        return first.start.lenUtf8()
    }

    /**
     * Returns the length, in bytes, of the longest string matched by this
     * character class.
     *
     * Returns `None` when the class is empty.
     */
    public open fun maximumLen(): Int? {
        val last = (this.ranges().last() ?: return null)
        // Correct because c1 < c2 implies c1.lenUtf8() < c2.lenUtf8().
        return last.end.lenUtf8()
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
            rs[0].start.encodeUtf8(List(4) { 0 }).toString().intoBytes()
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
        return ClassBytes.new(this.ranges().map({ r ->
            // Since we are guaranteed that our codepoint range is ASCII, the
            // u8::tryFrom' calls below are guaranteed to be correct.
            ClassBytesRange(start = UByte.tryFrom(r.start).unwrap(), end = UByte.tryFrom(r.end).unwrap())
        }))
    }

    public companion object {
        public operator fun invoke(
            set: IntervalSet<ClassUnicodeRange>,
        ): ClassUnicode = ClassUnicode(
            setCtorArg = set,
        )

        /**
         * Create a new class from a sequence of ranges.
         *
         * The given ranges do not need to be in any specific order, and ranges
         * may overlap. Ranges will automatically be sorted into a canonical
         * non-overlapping order.
         */
        public fun <I> new(ranges: I): ClassUnicode where I : Iterable<ClassUnicodeRange> = ClassUnicode(set = IntervalSet.new(ranges))

        /**
         * Create a new class with no ranges.
         *
         * An empty class matches nothing. That is, it is equivalent to
         * [Hir.fail].
         */
        public fun empty(): ClassUnicode = ClassUnicode.new(mutableListOf())
    }
}

/**
 * An iterator over all ranges in a Unicode character class.
 *
 * The scope `a` refers to the scope of the underlying class.
 */
public open class ClassUnicodeIter internal constructor(
    valueCtorArg: IntervalSetIter<ClassUnicodeRange>,
) : Iterable<ClassUnicodeRange> {
    public var value: IntervalSetIter<ClassUnicodeRange> = valueCtorArg
        internal set

    public var field0: IntervalSetIter<ClassUnicodeRange>
        get() = value
        set(v) {
            value = v
        }

    override fun iterator(): kotlin.collections.Iterator<ClassUnicodeRange> = object : AbstractIterator<ClassUnicodeRange>() {
        override fun computeNext() {
            when (val v = this@ClassUnicodeIter.next()) {
                null -> done()
                else -> setNext(v)
            }
        }
    }

    public open fun next(): ClassUnicodeRange? = this.value.next()

    public companion object {
        public operator fun invoke(
            value: IntervalSetIter<ClassUnicodeRange>,
        ): ClassUnicodeIter = ClassUnicodeIter(
            valueCtorArg = value,
        )
    }
}

/**
 * A single range of characters represented by Unicode scalar values.
 *
 * The range is closed. That is, the start and end of the range are included
 * in the range.
 */
public open class ClassUnicodeRange(
    public var start: Char,
    public var end: Char,
) : Interval {
    public open fun fmt(f: StringBuilder) {
        val start =
            if (!this.start.isWhitespace() && !this.start.isControl()) {
                this.start.toString()
            } else {
                "0x${UInt.from(this.start)}"
            }
        val end =
            if (!this.end.isWhitespace() && !this.end.isControl()) {
                this.end.toString()
            } else {
                "0x${UInt.from(this.end)}"
            }
        DebugStruct(f, "ClassUnicodeRange").field("start", start).field("end", end).finish()
    }

    public open fun lower(): Char = this.start

    public open fun upper(): Char = this.end

    public open fun setLower(bound: Char) {
        this.start = bound
    }

    public open fun setUpper(bound: Char) {
        this.end = bound
    }

    /**
     * Apply simple case folding to this Unicode scalar value range.
     *
     * Additional ranges are appended to the given vector. Canonical ordering
     * is *not* maintained in the given vector.
     */
    public open fun caseFoldSimple(ranges: List<ClassUnicodeRange>): Result<Unit> {
        val folder = SimpleCaseFolder.new().getOrElse { return Result.failure(it) }
        if (!folder.overlaps(this.start, this.end)) {
            return Result.success(Unit)
        }
        val (start, end) = Pair(UInt.from(this.start), UInt.from(this.end))
        for (cp in (start..end).filterMap(Char::fromU32)) {
            for (cpFolded in folder.mapping(cp)) {
                ranges.push(ClassUnicodeRange.new(cpFolded, cpFolded))
            }
        }
        return Result.success(Unit)
    }

    /**
     * Return the start of this range.
     *
     * The start of a range is always less than or equal to the end of the
     * range.
     */
    public open fun start(): Char = this.start

    /**
     * Return the end of this range.
     *
     * The end of a range is always greater than or equal to the start of the
     * range.
     */
    public open fun end(): Char = this.end

    /**
     * Returns the number of codepoints in this range.
     */
    public open fun len(): Int {
        val diff = 1 + UInt.from(this.end) - UInt.from(this.start)
        // This is likely to panic in 16-bit targets since a Int can only fit
        // 2^16. It's not clear what to do here, other than to return an error
        // when building a Unicode class that contains a range whose length
        // overflows Int. (Which, to be honest, is probably quite common on
        // 16-bit targets. For example, this would imply that '.' and '\p{any}'
        // would be impossible to build.)
        return Int.tryFrom(diff).expect("char class len fits in usize")
    }

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<ClassUnicodeRange> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<ClassUnicodeRange> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 8L
                override fun readNative(addr: Long): ClassUnicodeRange =
                    ClassUnicodeRange(
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.CHAR).readNative(addr),
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.CHAR).readNative(addr + 4L),
                    )
                override fun writeNative(addr: Long, value: ClassUnicodeRange) {
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.CHAR).writeNative(addr, value.start)
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.CHAR).writeNative(addr + 4L, value.end)
                }
            }

        /**
         * Create a new Unicode scalar value range for a character class.
         *
         * The returned range is always in a canonical form. That is, the range
         * returned always satisfies the invariant that `start <= end`.
         */
        public fun new(start: Char, end: Char): ClassUnicodeRange = ClassUnicodeRange.create(start, end)
    }
}

/**
 * A set of characters represented by arbitrary bytes.
 *
 * Each byte corresponds to one character.
 */
public open class ClassBytes internal constructor(
    setCtorArg: IntervalSet<ClassBytesRange>,
) {
    public var set: IntervalSet<ClassBytesRange> = setCtorArg
        internal set

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
        this.set.caseFoldSimple().expect("ASCII case folding never fails")
    }

    /**
     * Negate this byte class.
     *
     * For all `b` where `b` is a any byte, if `b` was in this set, then it
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
     * but all adds all elements from the given class that aren't in this
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
    public open fun isAscii(): Boolean = (this.set.intervals().last()?.let({ r -> r.end <= 0x7F }) ?: true)

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
            mutableListOf(rs[0].start)
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
        return ClassUnicode.new(this.ranges().map({ r ->
            // Since we are guaranteed that our byte range is ASCII, the
            // char::from' calls below are correct and will not erroneously
            // convert a raw byte value into its corresponding codepoint.
            ClassUnicodeRange(start = Char.from(r.start), end = Char.from(r.end))
        }))
    }

    public companion object {
        public operator fun invoke(
            set: IntervalSet<ClassBytesRange>,
        ): ClassBytes = ClassBytes(
            setCtorArg = set,
        )

        /**
         * Create a new class from a sequence of ranges.
         *
         * The given ranges do not need to be in any specific order, and ranges
         * may overlap. Ranges will automatically be sorted into a canonical
         * non-overlapping order.
         */
        public fun <I> new(ranges: I): ClassBytes where I : Iterable<ClassBytesRange> = ClassBytes(set = IntervalSet.new(ranges))

        /**
         * Create a new class with no ranges.
         *
         * An empty class matches nothing. That is, it is equivalent to
         * [Hir.fail].
         */
        public fun empty(): ClassBytes = ClassBytes.new(mutableListOf())
    }
}

/**
 * An iterator over all ranges in a byte character class.
 *
 * The scope `a` refers to the scope of the underlying class.
 */
public open class ClassBytesIter internal constructor(
    valueCtorArg: IntervalSetIter<ClassBytesRange>,
) : Iterable<ClassBytesRange> {
    public var value: IntervalSetIter<ClassBytesRange> = valueCtorArg
        internal set

    public var field0: IntervalSetIter<ClassBytesRange>
        get() = value
        set(v) {
            value = v
        }

    override fun iterator(): kotlin.collections.Iterator<ClassBytesRange> = object : AbstractIterator<ClassBytesRange>() {
        override fun computeNext() {
            when (val v = this@ClassBytesIter.next()) {
                null -> done()
                else -> setNext(v)
            }
        }
    }

    public open fun next(): ClassBytesRange? = this.value.next()

    public companion object {
        public operator fun invoke(
            value: IntervalSetIter<ClassBytesRange>,
        ): ClassBytesIter = ClassBytesIter(
            valueCtorArg = value,
        )
    }
}

/**
 * A single range of characters represented by arbitrary bytes.
 *
 * The range is closed. That is, the start and end of the range are included
 * in the range.
 */
public open class ClassBytesRange(
    public var start: UByte,
    public var end: UByte,
) : Interval {
    public open fun lower(): UByte = this.start

    public open fun upper(): UByte = this.end

    public open fun setLower(bound: UByte) {
        this.start = bound
    }

    public open fun setUpper(bound: UByte) {
        this.end = bound
    }

    /**
     * Apply simple case folding to this byte range. Only ASCII case mappings
     * (for a-z) are applied.
     *
     * Additional ranges are appended to the given vector. Canonical ordering
     * is *not* maintained in the given vector.
     */
    public open fun caseFoldSimple(ranges: List<ClassBytesRange>): Result<Unit> {
        if (!ClassBytesRange.new(97u, 122u).isIntersectionEmpty(this)) {
            val lower = max(this.start, 97u)
            val upper = minOf(this.end, 122u)
            ranges.push(ClassBytesRange.new((lower - 32u).toUByte(), (upper - 32u).toUByte()))
        }
        if (!ClassBytesRange.new(65u, 90u).isIntersectionEmpty(this)) {
            val lower1 = max(this.start, 65u)
            val upper1 = minOf(this.end, 90u)
            ranges.push(ClassBytesRange.new((lower1 + 32u).toUByte(), (upper1 + 32u).toUByte()))
        }
        return Result.success(Unit)
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
    public open fun len(): Int = (((Int.from((((this.end).toLong() - (this.start).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!)).toLong() + (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!

    public open fun fmt(f: StringBuilder) {
        DebugStruct(f, "ClassBytesRange").field("start", io.github.kotlinmania.regexsyntax.debug.Byte(this.start)).field("end", io.github.kotlinmania.regexsyntax.debug.Byte(this.end)).finish()
    }

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<ClassBytesRange> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<ClassBytesRange> {
                override val alignBytes: Long = 1L
                override val sizeBytes: Long = 2L
                override fun readNative(addr: Long): ClassBytesRange =
                    ClassBytesRange(
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.U8).readNative(addr),
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.U8).readNative(addr + 1L),
                    )
                override fun writeNative(addr: Long, value: ClassBytesRange) {
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.U8).writeNative(addr, value.start)
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.U8).writeNative(addr + 1L, value.end)
                }
            }

        /**
         * Create a new byte range for a character class.
         *
         * The returned range is always in a canonical form. That is, the range
         * returned always satisfies the invariant that `start <= end`.
         */
        public fun new(start: UByte, end: UByte): ClassBytesRange = ClassBytesRange.create(start, end)
    }
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
)

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
public open class Properties(
    public var value: PropertiesI,
) {
    public var field0: PropertiesI
        get() = value
        set(v) {
            value = v
        }

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
    public open fun lookSet(): LookSet = this.value.lookSet

    /**
     * Returns a set of all look-around assertions that appear as a prefix for
     * this HIR value. That is, the set returned corresponds to the set of
     * assertions that must be passed before matching any bytes in a haystack.
     *
     * For example, `hir.lookSetPrefix().contains(Look.Start)` returns true
     * if and only if the HIR is fully anchored at the start.
     */
    public open fun lookSetPrefix(): LookSet = this.value.lookSetPrefix

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
    public open fun lookSetPrefixAny(): LookSet = this.value.lookSetPrefixAny

    /**
     * Returns a set of all look-around assertions that appear as a suffix for
     * this HIR value. That is, the set returned corresponds to the set of
     * assertions that must be passed in order to be considered a match after
     * all other consuming HIR expressions.
     *
     * For example, `hir.lookSetSuffix().contains(Look.End)` returns true
     * if and only if the HIR is fully anchored at the end.
     */
    public open fun lookSetSuffix(): LookSet = this.value.lookSetSuffix

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
    public open fun lookSetSuffixAny(): LookSet = this.value.lookSetSuffixAny

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
    public open fun memoryUsage(): ULong = mem.sizeOf<PropertiesI>(typeLayoutT = ObjectSlotLayout<PropertiesI>()).toULong()

    public companion object {
        /**
         * Returns a new set of properties that corresponds to the union of the
         * iterator of properties given.
         *
         * This is useful when one has multiple `Hir` expressions and wants
         * to combine them into a single alternation without constructing the
         * corresponding `Hir`. This routine provides a way of combining the
         * properties of each `Hir` expression into one set of properties
         * representing the union of those expressions.
         *
         * # Example: union with HIRs that never match
         *
         * This example shows that unioning properties together with one that
         * represents a regex that never matches will "poison" certain attributes,
         * like the minimum and maximum lengths.
         *
         * ```kotlin
         * import regexsyntax.parse
         *
         * val hir1 = parse("ab?c?").getOrElse { return Result.failure(it) }
         * check(1 == hir1.properties().minimumLen())
         * check(3 == hir1.properties().maximumLen())
         * val hir2 = parse("[a&&b]").getOrElse { return Result.failure(it) }
         * check(null == hir2.properties().minimumLen())
         * check(null == hir2.properties().maximumLen())
         * val hir3 = parse("wxy?z?").getOrElse { return Result.failure(it) }
         * check(2 == hir3.properties().minimumLen())
         * check(4 == hir3.properties().maximumLen())
         * val unioned = Properties.union(listOf(hir1.properties(), hir2.properties(), hir3.properties()))
         * check(null == unioned.minimumLen())
         * check(null == unioned.maximumLen())
         * ```
         *
         * The maximum length can also be "poisoned" by a pattern that has no
         * upper bound on the length of a match. The minimum length remains
         * unaffected:
         *
         * ```kotlin
         * import regexsyntax.parse
         *
         * val hir1 = parse("ab?c?").getOrElse { return Result.failure(it) }
         * check(1 == hir1.properties().minimumLen())
         * check(3 == hir1.properties().maximumLen())
         * val hir2 = parse("a+").getOrElse { return Result.failure(it) }
         * check(1 == hir2.properties().minimumLen())
         * check(null == hir2.properties().maximumLen())
         * val hir3 = parse("wxy?z?").getOrElse { return Result.failure(it) }
         * check(2 == hir3.properties().minimumLen())
         * check(4 == hir3.properties().maximumLen())
         * val unioned = Properties.union(listOf(hir1.properties(), hir2.properties(), hir3.properties()))
         * check(1 == unioned.minimumLen())
         * check(null == unioned.maximumLen())
         * ```
         */
        public fun <I, P> union(props: I): Properties where I : Iterable<P>, P : Borrow<Properties> {
            val it = props.iterator().peekable()
            // While empty alternations aren't possible, we still behave as if they
            // are. When we have an empty alternate, then clearly the look-around
            // prefix and suffix is empty. Otherwise, it is the intersection of all
            // prefixes and suffixes (respectively) of the branches.
            val fix =
                if (it.peek() == null) {
                    LookSet.empty()
                } else {
                    LookSet.full()
                }
            // And also, an empty alternate means we have 0 static capture groups,
            // but we otherwise start with the number corresponding to the first
            // alternate. If any subsequent alternate has a different number of
            // static capture groups, then we overall have a variation and not a
            // static number of groups.
            val staticExplicitCapturesLen = it.peek().andThen({ p -> p.borrow().staticExplicitCapturesLen() })
            // The base case is an empty alternation, which matches nothing.
            // Note though that empty alternations aren't possible, because the
            // Hir.alternation smart constructor rewrites those as empty character
            // classes.
            val props1 = PropertiesI(minimumLen = null, maximumLen = null, lookSet = LookSet.empty(), lookSetPrefix = fix, lookSetSuffix = fix, lookSetPrefixAny = LookSet.empty(), lookSetSuffixAny = LookSet.empty(), utf8 = true, explicitCapturesLen = 0, staticExplicitCapturesLen = staticExplicitCapturesLen, literal = false, alternationLiteral = true)
            var (minPoisoned, maxPoisoned) = Pair(false, false)
            // Handle properties that need to visit every child hir.
            for (prop in it) {
                val p = prop.borrow()
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
                    when (val matchVal = p.minimumLen()) {
                        null -> {
                            props1.minimumLen = null
                            minPoisoned = true
                        }
                        else -> {
                            val xmin = matchVal
                            if ((props1.minimumLen?.let({ pmin -> xmin < pmin }) ?: true)) {
                                props1.minimumLen = xmin
                            }
                        }
                    }
                }
                if (!maxPoisoned) {
                    when (val matchVal1 = p.maximumLen()) {
                        null -> {
                            props1.maximumLen = null
                            maxPoisoned = true
                        }
                        else -> {
                            val xmax = matchVal1
                            if ((props1.maximumLen?.let({ pmax -> xmax > pmax }) ?: true)) {
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
            val inner = PropertiesI(minimumLen = lit.value.size, maximumLen = lit.value.size, lookSet = LookSet.empty(), lookSetPrefix = LookSet.empty(), lookSetSuffix = LookSet.empty(), lookSetPrefixAny = LookSet.empty(), lookSetSuffixAny = LookSet.empty(), utf8 = String.fromUtf8(lit.value).isOk(), explicitCapturesLen = 0, staticExplicitCapturesLen = 0, literal = true, alternationLiteral = true)
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
                p.minimumLen()?.let({ childMin ->
                    val repMin = Int.tryFrom(rep.min).unwrapOr(Int.MAX_VALUE)
                    childMin.saturatingMul(repMin)
                })
            val maximumLen =
                rep.max?.let(closure@{ repMax ->
                    val repMax1 = Int.tryFrom(repMax).ok().getOrElse { return@closure Result.failure(it) }
                    val childMax = p.maximumLen().getOrElse { return@closure Result.failure(it) }
                    ((childMax).toLong() * (repMax1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                })
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
            if (rep.min == 0u && (inner.staticExplicitCapturesLen?.let({ len -> len > 0 }) ?: false)) {
                // If we require a match 0 times, then our captures len is
                // guaranteed to be zero. Otherwise, if we *can* match the empty
                // string, then it's impossible to know how many captures will be
                // in the resulting match.
                if (rep.max == 0) {
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
            return Properties(p.value.clone().copy(explicitCapturesLen = p.explicitCapturesLen().saturatingAdd(1), staticExplicitCapturesLen = p.staticExplicitCapturesLen()?.let({ len -> len.saturatingAdd(1) }), literal = false, alternationLiteral = false))
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
            for (x in concat.iter()) {
                val p = x.properties()
                props.lookSet.setUnion(p.lookSet())
                props.utf8 = props.utf8 && p.isUtf8()
                props.explicitCapturesLen = props.explicitCapturesLen.saturatingAdd(p.explicitCapturesLen())
                props.staticExplicitCapturesLen = p.staticExplicitCapturesLen().andThen(closure1@{ len1 ->
                    Pair(len1, props.staticExplicitCapturesLen.getOrElse { return@closure1 Result.failure(it) })
                }).andThen({ (len1, len2) -> len1.saturatingAdd(len2) })
                props.literal = props.literal && p.isLiteral()
                props.alternationLiteral = props.alternationLiteral && p.isAlternationLiteral()
                when (val matchVal = props.minimumLen) {
                    null -> {}
                    else -> {
                        val minimumLen = matchVal
                        when (val matchVal1 = p.minimumLen()) {
                            null -> props.minimumLen = null
                            else -> {
                                val len = matchVal1
                                // We use saturating arithmetic here because the
                                // minimum is just a lower bound. We can't go any
                                // higher than what our number types permit.
                                props.minimumLen = minimumLen.saturatingAdd(len)
                            }
                        }
                    }
                }
                when (val matchVal2 = props.maximumLen) {
                    null -> {}
                    else -> {
                        val maximumLen = matchVal2
                        when (val matchVal3 = p.maximumLen()) {
                            null -> props.maximumLen = null
                            else -> {
                                val len1 = matchVal3
                                props.maximumLen = ((maximumLen).toLong() + (len1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
                            }
                        }
                    }
                }
            }
            // Handle the prefix properties, which only requires visiting
            // child exprs until one matches more than the empty string.
            val it = concat.iter()
            while (true) {
                val matchVal4 = it.next()
                if (matchVal4 == null) break
                val x1 = matchVal4
                props.lookSetPrefix.setUnion(x1.properties().lookSetPrefix())
                props.lookSetPrefixAny.setUnion(x1.properties().lookSetPrefixAny())
                if (x1.properties().maximumLen().mapOr(true, { x2 -> x2 > 0 })) {
                    break
                }
            }
            // Same thing for the suffix properties, but in reverse.
            val it1 = concat.iter().rev()
            while (true) {
                val matchVal5 = it1.next()
                if (matchVal5 == null) break
                val x2 = matchVal5
                props.lookSetSuffix.setUnion(x2.properties().lookSetSuffix())
                props.lookSetSuffixAny.setUnion(x2.properties().lookSetSuffixAny())
                if (x2.properties().maximumLen().mapOr(true, { x3 -> x3 > 0 })) {
                    break
                }
            }
            return Properties(props)
        }

        /**
         * Create a new set of HIR properties for a concatenation.
         */
        public fun alternation(alts: List<Hir>): Properties = Properties.union(alts.map({ hir -> hir.properties() }))
    }
}

/**
 * The property definition. It is split out so that we can box it, and
 * there by make `Properties` use less stack size. This is kind-of important
 * because every HIR value has a `Properties` attached to it.
 *
 * This does have the unfortunate consequence that creating any HIR value
 * always leads to at least one alloc for properties, but this is generally
 * true anyway (for pretty much all HirKinds except for look-arounds).
 */
public open class PropertiesI(
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
)

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
        // OK because max value always fits in a u8, which in turn always
        // fits in a Int, regardless of target.
        return Int.tryFrom(this.bits.countOneBits().toUInt()).unwrap()
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
    public open fun iter(): LookSetIter = LookSetIter(set = this)

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
        this = this.insert(look)
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
        this = this.remove(look)
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
        this = this.subtract(other)
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
        this = this.union(other)
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
        this = this.intersect(other)
    }

    /**
     * Write a `LookSet` as a native endian 32-bit integer to the beginning
     * of the slice given.
     *
     * # Panics
     *
     * This panics if `slice.len()<4`.
     */
    public open fun writeRepr(slice: List<UByte>) {
        val raw = run { val rawBits = this.bits; listOf<UByte>(rawBits.toUByte(), (rawBits shr 8).toUByte(), (rawBits shr 16).toUByte(), (rawBits shr 24).toUByte()) }
        slice[0] = raw[0]
        slice[1] = raw[1]
        slice[2] = raw[2]
        slice[3] = raw[3]
    }

    public open fun fmt(f: StringBuilder) {
        if (this.isEmpty()) {
            return f.append("∅")
        }
        for (look in this.iter()) {
            f.append("${look.asChar()}").getOrElse { return Result.failure(it) }
        }
        Result.success(Unit)
    }

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<LookSet> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<LookSet> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 4L
                override fun readNative(addr: Long): LookSet =
                    LookSet(
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.U32).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: LookSet) {
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.U32).writeNative(addr, value.bits)
                }
            }

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
         * This panics if `slice.len()<4`.
         */
        public fun readRepr(slice: List<UByte>): LookSet {
            val bits = UInt.fromNeBytes(slice[0 until 4].tryInto().unwrap())
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
    override fun iterator(): kotlin.collections.Iterator<Look> = object : AbstractIterator<Look>() {
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
        // We'll never have more than u8.Max distinct look-around assertions,
        // so 'bit' will always fit into a u16.
        val bit = UShort.tryFrom(this.set.bits.trailingZeros()).unwrap()
        val look = (Look.fromRepr(1u shl bit.toInt()) ?: return null)
        this.set = this.set.remove(look)
        return look
    }

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<LookSetIter> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<LookSetIter> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 4L
                override fun readNative(addr: Long): LookSetIter =
                    LookSetIter(
                        (LookSet.typeLayout()).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: LookSetIter) {
                    (LookSet.typeLayout()).writeNative(addr, value.set)
                }
            }
    }
}
