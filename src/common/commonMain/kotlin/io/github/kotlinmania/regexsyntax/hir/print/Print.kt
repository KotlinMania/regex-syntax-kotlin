// port-lint: source hir/print.rs
package io.github.kotlinmania.regexsyntax.hir.print

import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Class
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Literal
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.visitor.Visitor
import io.github.kotlinmania.regexsyntax.hir.visitor.visit
import io.github.kotlinmania.regexsyntax.isMetaCharacter
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout

// This module provides a regular expression printer for `Hir`.

public typealias Output = Unit

public typealias Err = fmt.Error

/**
 * A builder for constructing a printer.
 *
 * Note that since a printer doesn't have any configuration knobs, this type
 * remains unexported.
 */
public open class PrinterBuilder(
    public var priv: Unit,
) {
    public open fun build(): Printer = Printer(priv = Unit)

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<PrinterBuilder> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<PrinterBuilder> {
                override val alignBytes: Long = 1L
                override val sizeBytes: Long = 0L
                override fun readNative(addr: Long): PrinterBuilder =
                    PrinterBuilder(
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.UNIT).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: PrinterBuilder) {
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.UNIT).writeNative(addr, value.priv)
                }
            }

        public fun default(): PrinterBuilder = PrinterBuilder.new()

        public fun new(): PrinterBuilder = PrinterBuilder(priv = Unit)
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
    public var priv: Unit,
) {
    /**
     * Print the given `Ast` to the given writer. The writer must implement
     * `fmt.Write`. Typical implementations of `fmt.Write` that can be used
     * here are a `fmt.Formatter` (which is available in `fmt.Display`
     * implementations) or a `String`.
     */
    public open fun <W : fmt.Write> print(hir: Hir, wtr: W) {
        visit(hir, Writer(wtr = wtr))
    }

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<Printer> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<Printer> {
                override val alignBytes: Long = 1L
                override val sizeBytes: Long = 0L
                override fun readNative(addr: Long): Printer =
                    Printer(
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.UNIT).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: Printer) {
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.UNIT).writeNative(addr, value.priv)
                }
            }

        /**
         * Create a new printer.
         */
        public fun new(): Printer = PrinterBuilder.new().build()
    }
}

public open class Writer<W>(
    public var wtr: W,
) : Visitor {
    public open fun finish() {
        Result.success(Unit)
    }

    public open fun visitPre(hir: Hir) {
        run {
            val matchVal = hir.kind()
            when {
                matchVal == HirKind.Empty -> {
                    // Technically an empty sub-expression could be "printed" by
                    // just ignoring it, but in practice, you could have a
                    // repetition operator attached to an empty expression, and you
                    // really need something in the concrete syntax to make that
                    // work as you'd expect.
                    this.wtr.append("(?:)").getOrElse { return Result.failure(it) }
                }
                // Repetition operators are strictly suffix oriented.
                matchVal is HirKind.Repetition -> {}
                matchVal is HirKind.Literal && matchVal.value is Literal -> {
                    val (bytes) = matchVal.value as Literal
                    // See the comment on the 'Concat' and 'Alternation' case below
                    // for why we put parens here. Literals are, conceptually,
                    // a special case of concatenation where each element is a
                    // character. The HIR flattens this into a Holder<[u8]>, but we
                    // still need to treat it like a concatenation for correct
                    // printing. As a special case, we don't write parens if there
                    // is only one character. One character means there is no
                    // concat so we don't need parens. Adding parens would still be
                    // correct, but we drop them here because it tends to create
                    // rather noisy regexes even in simple cases.
                    val result = String.fromUtf8(bytes)
                    val len = result.mapOr(bytes.len(), { s -> s.chars().count() })
                    if (len > 1) {
                        this.wtr.append("(?:").getOrElse { return Result.failure(it) }
                    }
                    when (result) {
                        is Ok -> {
                            val (string) = result
                            for (c in string.chars()) {
                                this.writeLiteralChar(c).getOrElse { return Result.failure(it) }
                            }
                        }
                        is Err -> {
                            for (b in bytes.iter()) {
                                this.writeLiteralByte(b).getOrElse { return Result.failure(it) }
                            }
                        }
                    }
                    if (len > 1) {
                        this.wtr.append(")").getOrElse { return Result.failure(it) }
                    }
                }
                matchVal is HirKind.Class && matchVal.value is Class.Unicode -> {
                    val (cls) = matchVal.value as Class.Unicode
                    if (cls.ranges().isEmpty()) {
                        return this.wtr.append("[a&&b]")
                    }
                    this.wtr.append("[").getOrElse { return Result.failure(it) }
                    for (range in cls.iter()) {
                        if (range.start() == range.end()) {
                            this.writeLiteralChar(range.start()).getOrElse { return Result.failure(it) }
                        } else if (UInt.from(range.start()) + 1 == UInt.from(range.end())) {
                            this.writeLiteralChar(range.start()).getOrElse { return Result.failure(it) }
                            this.writeLiteralChar(range.end()).getOrElse { return Result.failure(it) }
                        } else {
                            this.writeLiteralChar(range.start()).getOrElse { return Result.failure(it) }
                            this.wtr.append("-").getOrElse { return Result.failure(it) }
                            this.writeLiteralChar(range.end()).getOrElse { return Result.failure(it) }
                        }
                    }
                    this.wtr.append("]").getOrElse { return Result.failure(it) }
                }
                matchVal is HirKind.Class && matchVal.value is Class.Bytes -> {
                    val (cls1) = matchVal.value as Class.Bytes
                    if (cls1.ranges().isEmpty()) {
                        return this.wtr.append("[a&&b]")
                    }
                    this.wtr.append("(?-u:[").getOrElse { return Result.failure(it) }
                    for (range1 in cls1.iter()) {
                        if (range1.start() == range1.end()) {
                            this.writeLiteralClassByte(range1.start()).getOrElse { return Result.failure(it) }
                        } else if (range1.start() + 1 == range1.end()) {
                            this.writeLiteralClassByte(range1.start()).getOrElse { return Result.failure(it) }
                            this.writeLiteralClassByte(range1.end()).getOrElse { return Result.failure(it) }
                        } else {
                            this.writeLiteralClassByte(range1.start()).getOrElse { return Result.failure(it) }
                            this.wtr.append("-").getOrElse { return Result.failure(it) }
                            this.writeLiteralClassByte(range1.end()).getOrElse { return Result.failure(it) }
                        }
                    }
                    this.wtr.append("])").getOrElse { return Result.failure(it) }
                }
                matchVal is HirKind.Look -> {
                    val (look) = matchVal
                    when (look) {
                        Look.Start -> {
                            this.wtr.append("\\A").getOrElse { return Result.failure(it) }
                        }
                        Look.End -> {
                            this.wtr.append("\\z").getOrElse { return Result.failure(it) }
                        }
                        Look.StartLF -> {
                            this.wtr.append("(?m:^)").getOrElse { return Result.failure(it) }
                        }
                        Look.EndLF -> {
                            this.wtr.append("(?m:\$)").getOrElse { return Result.failure(it) }
                        }
                        Look.StartCRLF -> {
                            this.wtr.append("(?mR:^)").getOrElse { return Result.failure(it) }
                        }
                        Look.EndCRLF -> {
                            this.wtr.append("(?mR:\$)").getOrElse { return Result.failure(it) }
                        }
                        Look.WordAscii -> {
                            this.wtr.append("(?-u:\\b)").getOrElse { return Result.failure(it) }
                        }
                        Look.WordAsciiNegate -> {
                            this.wtr.append("(?-u:\\B)").getOrElse { return Result.failure(it) }
                        }
                        Look.WordUnicode -> {
                            this.wtr.append("\\b").getOrElse { return Result.failure(it) }
                        }
                        Look.WordUnicodeNegate -> {
                            this.wtr.append("\\B").getOrElse { return Result.failure(it) }
                        }
                        Look.WordStartAscii -> {
                            this.wtr.append("(?-u:\\b{start})").getOrElse { return Result.failure(it) }
                        }
                        Look.WordEndAscii -> {
                            this.wtr.append("(?-u:\\b{end})").getOrElse { return Result.failure(it) }
                        }
                        Look.WordStartUnicode -> {
                            this.wtr.append("\\b{start}").getOrElse { return Result.failure(it) }
                        }
                        Look.WordEndUnicode -> {
                            this.wtr.append("\\b{end}").getOrElse { return Result.failure(it) }
                        }
                        Look.WordStartHalfAscii -> {
                            this.wtr.append("(?-u:\\b{start-half})").getOrElse { return Result.failure(it) }
                        }
                        Look.WordEndHalfAscii -> {
                            this.wtr.append("(?-u:\\b{end-half})").getOrElse { return Result.failure(it) }
                        }
                        Look.WordStartHalfUnicode -> {
                            this.wtr.append("\\b{start-half}").getOrElse { return Result.failure(it) }
                        }
                        Look.WordEndHalfUnicode -> {
                            this.wtr.append("\\b{end-half}").getOrElse { return Result.failure(it) }
                        }
                    }
                }
                matchVal is HirKind.Capture && matchVal.value is Capture -> {
                    val name = (matchVal.value as Capture).name
                    this.wtr.append("(").getOrElse { return Result.failure(it) }
                    when (val matchVal1 = name) {
                        null -> {}
                        else -> {
                            this.wtr.append("?P<$name>").getOrElse { return Result.failure(it) }
                        }
                    }
                }
                // Why do this? Wrapping concats and alts in non-capturing groups
                // is not *always* necessary, but is sometimes necessary. For
                // example, concat(a, alt(b, c))' should be written as a(?:b|c)'
                // and not ab|c'. The former is clearly the intended meaning, but
                // the latter is actually alt(concat(a, b), c)'.
                //
                // It would be possible to only group these things in cases where
                // it's strictly necessary, but it requires knowing the parent
                // expression. And since this technique is simpler and always
                // correct, we take this route. More to the point, it is a non-goal
                // of an HIR printer to show a nice easy-to-read regex. Indeed,
                // its construction forbids it from doing so. Therefore, inserting
                // extra groups where they aren't necessary is perfectly okay.
                matchVal is HirKind.Concat -> {
                    this.wtr.append("(?:").getOrElse { return Result.failure(it) }
                }
                else -> {
                    this.wtr.append("(?:").getOrElse { return Result.failure(it) }
                }
            }
        }
        Result.success(Unit)
    }

    public open fun visitPost(hir: Hir) {
        when (val matchVal = hir.kind()) {
            // Handled during visitPre
            HirKind.Empty -> {}
            is HirKind.Literal -> {}
            is HirKind.Class -> {}
            is HirKind.Look -> {}
            is HirKind.Repetition -> {
                val (x) = matchVal
                run {
                    val matchVal1 = Pair(x.min, x.max)
                    when {
                        matchVal1.first == 0 && matchVal1.second != null && matchVal1.second == 1 -> {
                            this.wtr.append("?").getOrElse { return Result.failure(it) }
                        }
                        matchVal1.first == 0 && matchVal1.second == null -> {
                            this.wtr.append("*").getOrElse { return Result.failure(it) }
                        }
                        matchVal1.first == 1 && matchVal1.second == null -> {
                            this.wtr.append("+").getOrElse { return Result.failure(it) }
                        }
                        matchVal1.first == 1 && matchVal1.second != null && matchVal1.second == 1 -> {
                            // a{1}' and a{1}?' are exactly equivalent to 'a'.
                            return Result.success(Unit)
                        }
                        matchVal1.second == null -> {
                            val m = matchVal1.first
                            this.wtr.append("{$m,}").getOrElse { return Result.failure(it) }
                        }
                        matchVal1.second != null && matchVal1.first == matchVal1.second -> {
                            val m1 = matchVal1.first
                            val n = matchVal1.second
                            this.wtr.append("{$m}").getOrElse { return Result.failure(it) }
                            // a{m} and a{m}? are always exactly equivalent.
                            return Result.success(Unit)
                        }
                        else -> {
                            val m2 = matchVal1.first
                            val n1 = matchVal1.second
                            this.wtr.append("{$m,$n}").getOrElse { return Result.failure(it) }
                        }
                    }
                }
                if (!x.greedy) {
                    this.wtr.append("?").getOrElse { return Result.failure(it) }
                }
            }
            is HirKind.Capture -> {
                this.wtr.append(")").getOrElse { return Result.failure(it) }
            }
            is HirKind.Concat -> {
                this.wtr.append(")").getOrElse { return Result.failure(it) }
            }
            is HirKind.Alternation -> {
                this.wtr.append(")").getOrElse { return Result.failure(it) }
            }
        }
        Result.success(Unit)
    }

    public open fun visitAlternationIn() {
        this.wtr.append("|")
    }

    public open fun writeLiteralChar(c: Char) {
        if (isMetaCharacter(c)) {
            this.wtr.append("\\").getOrElse { return Result.failure(it) }
        }
        this.wtr.append(c)
    }

    public open fun writeLiteralByte(b: UByte) {
        if (b <= 0x7Fu.toUByte() && !b.isAsciiControl() && !b.isAsciiWhitespace()) {
            this.writeLiteralChar(Char.tryFrom(b).unwrap())
        } else {
            this.wtr.append("(?-u:\\x$b)")
        }
    }

    public open fun writeLiteralClassByte(b: UByte) {
        if (b <= 0x7Fu.toUByte() && !b.isAsciiControl() && !b.isAsciiWhitespace()) {
            this.writeLiteralChar(Char.tryFrom(b).unwrap())
        } else {
            this.wtr.append("\\x$b")
        }
    }

    public companion object {
        public fun <W> typeLayout(typeLayoutW: io.github.kotlinmania.rustlang.core.mem.TypeLayout<W>): io.github.kotlinmania.rustlang.core.mem.TypeLayout<Writer<W>> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<Writer<W>> {
                override val alignBytes: Long = listOf(1L, typeLayoutW.alignBytes).max()
                override val sizeBytes: Long = io.github.kotlinmania.rustlang.core.mem.alignUpBytes(typeLayoutW.sizeBytes, alignBytes)
                override fun readNative(addr: Long): Writer<W> =
                    Writer(
                        (typeLayoutW).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: Writer<W>) {
                    (typeLayoutW).writeNative(addr, value.wtr)
                }
            }
    }
}
