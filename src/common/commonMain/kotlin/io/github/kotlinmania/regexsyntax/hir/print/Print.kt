// port-lint: source hir/print.rs
package io.github.kotlinmania.regexsyntax.hir.print

import io.github.kotlinmania.regexsyntax.FmtError
import io.github.kotlinmania.regexsyntax.formatHexUpperPadded
import io.github.kotlinmania.regexsyntax.hir.Class
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.visitor.Visitor
import io.github.kotlinmania.regexsyntax.hir.visitor.visit
import io.github.kotlinmania.regexsyntax.isMetaCharacter
import io.github.kotlinmania.regexsyntax.scalarToString
import io.github.kotlinmania.regexsyntax.unicodeScalars
import io.github.kotlinmania.rustlang.core.result.Result
import io.github.kotlinmania.rustlang.core.str.fromUtf8

// This module provides a regular expression printer for `Hir`.

/**
 * A builder for constructing a printer.
 *
 * Note that since a printer doesn't have any configuration knobs, this type
 * remains unexported.
 */
internal class PrinterBuilder(
    internal var priv: Unit = Unit,
) {
    internal fun build(): Printer = Printer(priv = Unit)

    internal companion object {
        internal fun default(): PrinterBuilder = new()

        internal fun new(): PrinterBuilder = PrinterBuilder(priv = Unit)
    }
}

/**
 * A printer for a regular expression's high-level intermediate
 * representation.
 *
 * A printer converts a high-level intermediate representation (HIR) to a
 * regular expression pattern string. This particular printer uses constant
 * stack space and heap space proportional to the size of the HIR.
 *
 * Since this printer is only using the HIR, the pattern it prints will likely
 * not resemble the original pattern at all. For example, a pattern like
 * `\pL` will have its entire class written out.
 *
 * The purpose of this printer is to provide a means to mutate an HIR and then
 * build a regular expression from the result of that mutation. (A regex
 * library could provide a constructor from this HIR explicitly, but that
 * creates an unnecessary public coupling between the regex library and this
 * specific HIR representation.)
 */
public open class Printer(
    internal var priv: Unit = Unit,
) {
    /**
     * Print the given `Hir` to the given writer.
     */
    public open fun print(hir: Hir, wtr: Appendable): Result<Unit, FmtError> =
        visit(hir, Writer(wtr = wtr))

    public companion object {
        /**
         * Create a new printer.
         */
        public fun new(): Printer = PrinterBuilder.new().build()
    }
}

internal class Writer(
    internal var wtr: Appendable,
) : Visitor<Unit, FmtError> {
    override fun finish(): Result<Unit, FmtError> = Result.Ok(Unit)

    override fun visitPre(hir: Hir): Result<Unit, FmtError> {
        when (val kind = hir.kind()) {
            HirKind.Empty -> {
                // Technically an empty sub-expression could be "printed" by
                // just ignoring it, but in practice, you could have a
                // repetition operator attached to an empty expression, and you
                // really need something in the concrete syntax to make that
                // work as you expect.
                this.wtr.append("(?:)")
            }
            // Repetition operators are strictly suffix oriented.
            is HirKind.Repetition -> {}
            is HirKind.Literal -> {
                val bytes = kind.value.value
                // See the comment on the `Concat` and `Alternation` case below
                // for why we put parens here. Literals are, conceptually,
                // a special case of concatenation where each element is a
                // character. The HIR flattens this into a byte sequence, but we
                // still need to treat it like a concatenation for correct
                // printing. As a special case, we don't write parens if there
                // is only one character. One character means there is no
                // concat so we don't need parens. Adding parens would still be
                // correct, but we drop them here because it tends to create
                // rather noisy regexes even in simple cases.
                val result = fromUtf8(bytes)
                val scalars =
                    when (result) {
                        is Result.Ok -> result.value.unicodeScalars()
                        is Result.Err -> null
                    }
                val len = scalars?.size ?: bytes.size
                if (len > 1) {
                    this.wtr.append("(?:")
                }
                if (scalars != null) {
                    for (c in scalars) {
                        this.writeLiteralChar(c)
                    }
                } else {
                    for (b in bytes) {
                        this.writeLiteralByte(b)
                    }
                }
                if (len > 1) {
                    this.wtr.append(")")
                }
            }
            is HirKind.Class -> {
                when (val clsVal = kind.value) {
                    is Class.Unicode -> {
                        val cls = clsVal.value
                        if (cls.ranges().isEmpty()) {
                            this.wtr.append("[a&&b]")
                            return Result.Ok(Unit)
                        }
                        this.wtr.append("[")
                        for (range in cls.iter()) {
                            if (range.start() == range.end()) {
                                this.writeLiteralChar(range.start())
                            } else if (range.start() + 1u == range.end()) {
                                this.writeLiteralChar(range.start())
                                this.writeLiteralChar(range.end())
                            } else {
                                this.writeLiteralChar(range.start())
                                this.wtr.append("-")
                                this.writeLiteralChar(range.end())
                            }
                        }
                        this.wtr.append("]")
                    }
                    is Class.Bytes -> {
                        val cls = clsVal.value
                        if (cls.ranges().isEmpty()) {
                            this.wtr.append("[a&&b]")
                            return Result.Ok(Unit)
                        }
                        this.wtr.append("(?-u:[")
                        for (range in cls.iter()) {
                            if (range.start() == range.end()) {
                                this.writeLiteralClassByte(range.start())
                            } else if (range.start().toUInt() + 1u == range.end().toUInt()) {
                                this.writeLiteralClassByte(range.start())
                                this.writeLiteralClassByte(range.end())
                            } else {
                                this.writeLiteralClassByte(range.start())
                                this.wtr.append("-")
                                this.writeLiteralClassByte(range.end())
                            }
                        }
                        this.wtr.append("])")
                    }
                }
            }
            is HirKind.Look -> {
                when (kind.value) {
                    Look.Start -> this.wtr.append("\\A")
                    Look.End -> this.wtr.append("\\z")
                    Look.StartLF -> this.wtr.append("(?m:^)")
                    Look.EndLF -> this.wtr.append("(?m:$)")
                    Look.StartCRLF -> this.wtr.append("(?mR:^)")
                    Look.EndCRLF -> this.wtr.append("(?mR:$)")
                    Look.WordAscii -> this.wtr.append("(?-u:\\b)")
                    Look.WordAsciiNegate -> this.wtr.append("(?-u:\\B)")
                    Look.WordUnicode -> this.wtr.append("\\b")
                    Look.WordUnicodeNegate -> this.wtr.append("\\B")
                    Look.WordStartAscii -> this.wtr.append("(?-u:\\b{start})")
                    Look.WordEndAscii -> this.wtr.append("(?-u:\\b{end})")
                    Look.WordStartUnicode -> this.wtr.append("\\b{start}")
                    Look.WordEndUnicode -> this.wtr.append("\\b{end}")
                    Look.WordStartHalfAscii -> this.wtr.append("(?-u:\\b{start-half})")
                    Look.WordEndHalfAscii -> this.wtr.append("(?-u:\\b{end-half})")
                    Look.WordStartHalfUnicode -> this.wtr.append("\\b{start-half}")
                    Look.WordEndHalfUnicode -> this.wtr.append("\\b{end-half}")
                }
            }
            is HirKind.Capture -> {
                val name = kind.value.name
                this.wtr.append("(")
                if (name != null) {
                    this.wtr.append("?P<$name>")
                }
            }
            // Why do this? Wrapping concats and alts in non-capturing groups
            // is not *always* necessary, but is sometimes necessary. For
            // example, `concat(a, alt(b, c))` should be written as `a(?:b|c)`
            // and not `ab|c`. The former is clearly the intended meaning, but
            // the latter is actually `alt(concat(a, b), c)`.
            //
            // It would be possible to only group these things in cases where
            // it is strictly necessary, but it requires knowing the parent
            // expression. And since this technique is simpler and always
            // correct, we take this route. More to the point, it is a non-goal
            // of an HIR printer to show a nice easy-to-read regex. Indeed,
            // its construction forbids it from doing so. Therefore, inserting
            // extra groups where they aren't necessary is perfectly okay.
            is HirKind.Concat -> this.wtr.append("(?:")
            is HirKind.Alternation -> this.wtr.append("(?:")
        }
        return Result.Ok(Unit)
    }

    override fun visitPost(hir: Hir): Result<Unit, FmtError> {
        when (val kind = hir.kind()) {
            // Handled during visitPre
            HirKind.Empty -> {}
            is HirKind.Literal -> {}
            is HirKind.Class -> {}
            is HirKind.Look -> {}
            is HirKind.Repetition -> {
                val x = kind.value
                val min = x.min
                val max = x.max
                when {
                    min == 0u && max == 1u -> this.wtr.append("?")
                    min == 0u && max == null -> this.wtr.append("*")
                    min == 1u && max == null -> this.wtr.append("+")
                    min == 1u && max == 1u -> {
                        // `a{1}` and `a{1}?` are exactly equivalent to `a`.
                        return Result.Ok(Unit)
                    }
                    max == null -> this.wtr.append("{$min,}")
                    min == max -> {
                        this.wtr.append("{$min}")
                        // a{m} and a{m}? are always exactly equivalent.
                        return Result.Ok(Unit)
                    }
                    else -> this.wtr.append("{$min,$max}")
                }
                if (!x.greedy) {
                    this.wtr.append("?")
                }
            }
            is HirKind.Capture -> this.wtr.append(")")
            is HirKind.Concat -> this.wtr.append(")")
            is HirKind.Alternation -> this.wtr.append(")")
        }
        return Result.Ok(Unit)
    }

    override fun visitAlternationIn(): Result<Unit, FmtError> {
        this.wtr.append("|")
        return Result.Ok(Unit)
    }

    internal fun writeLiteralChar(c: UInt): Result<Unit, FmtError> {
        if (isMetaCharacter(c)) {
            this.wtr.append("\\")
        }
        this.wtr.append(scalarToString(c))
        return Result.Ok(Unit)
    }

    internal fun writeLiteralByte(b: UByte): Result<Unit, FmtError> {
        val u = b.toUInt()
        if (u <= 0x7Fu && !isAsciiControlOrWhitespace(u)) {
            return this.writeLiteralChar(u)
        }
        this.wtr.append("(?-u:\\x${formatHexUpperPadded(u, 2)})")
        return Result.Ok(Unit)
    }

    internal fun writeLiteralClassByte(b: UByte): Result<Unit, FmtError> {
        val u = b.toUInt()
        if (u <= 0x7Fu && !isAsciiControlOrWhitespace(u)) {
            return this.writeLiteralChar(u)
        }
        this.wtr.append("\\x${formatHexUpperPadded(u, 2)}")
        return Result.Ok(Unit)
    }
}

private fun isAsciiControlOrWhitespace(b: UInt): Boolean =
    b <= 0x1Fu || b == 0x7Fu || b == 0x20u
