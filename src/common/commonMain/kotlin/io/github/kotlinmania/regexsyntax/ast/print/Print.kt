// port-lint: source ast/print.rs
package io.github.kotlinmania.regexsyntax.ast.print

import io.github.kotlinmania.regexsyntax.ast.Assertion
import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.CaptureName
import io.github.kotlinmania.regexsyntax.ast.ClassAscii
import io.github.kotlinmania.regexsyntax.ast.ClassBracketed
import io.github.kotlinmania.regexsyntax.ast.ClassPerl
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOp
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOpKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetItem
import io.github.kotlinmania.regexsyntax.ast.ClassUnicode
import io.github.kotlinmania.regexsyntax.ast.Flag
import io.github.kotlinmania.regexsyntax.ast.Flags
import io.github.kotlinmania.regexsyntax.ast.FlagsItemKind
import io.github.kotlinmania.regexsyntax.ast.Group
import io.github.kotlinmania.regexsyntax.ast.HexLiteralKind
import io.github.kotlinmania.regexsyntax.ast.Literal
import io.github.kotlinmania.regexsyntax.ast.Repetition
import io.github.kotlinmania.regexsyntax.ast.RepetitionRange
import io.github.kotlinmania.regexsyntax.ast.SetFlags
import io.github.kotlinmania.regexsyntax.ast.SpecialLiteralKind
import io.github.kotlinmania.regexsyntax.ast.visitor.Visitor
import io.github.kotlinmania.regexsyntax.ast.visitor.visit
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout

// This module provides a regular expression printer for `Ast`.

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
 * A printer for a regular expression abstract syntax tree.
 *
 * A printer converts an abstract syntax tree (AST) to a regular expression
 * pattern string. This particular printer uses constant stack space and heap
 * space proportional to the size of the AST.
 *
 * This printer will not necessarily preserve the original formatting of the
 * regular expression pattern string. For example, all whitespace and comments
 * are ignored.
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
    public open fun <W : fmt.Write> print(ast: Ast, wtr: W) {
        visit(ast, Writer(wtr = wtr))
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

    public open fun visitPre(ast: Ast) {
        when (ast) {
            is Ast.Group -> {
                val (x) = ast
                this.fmtGroupPre(x)
            }
            is Ast.ClassBracketed -> {
                val (x1) = ast
                this.fmtClassBracketedPre(x1)
            }
            else -> Result.success(Unit)
        }
    }

    public open fun visitPost(ast: Ast) {
        when (ast) {
            is Ast.Empty -> Result.success(Unit)
            is Ast.Flags -> {
                val (x) = ast
                this.fmtSetFlags(x)
            }
            is Ast.Literal -> {
                val (x1) = ast
                this.fmtLiteral(x1)
            }
            is Ast.Dot -> this.wtr.append(".")
            is Ast.Assertion -> {
                val (x2) = ast
                this.fmtAssertion(x2)
            }
            is Ast.ClassPerl -> {
                val (x3) = ast
                this.fmtClassPerl(x3)
            }
            is Ast.ClassUnicode -> {
                val (x4) = ast
                this.fmtClassUnicode(x4)
            }
            is Ast.ClassBracketed -> {
                val (x5) = ast
                this.fmtClassBracketedPost(x5)
            }
            is Ast.Repetition -> {
                val (x6) = ast
                this.fmtRepetition(x6)
            }
            is Ast.Group -> {
                val (x7) = ast
                this.fmtGroupPost(x7)
            }
            is Ast.Alternation -> Result.success(Unit)
            is Ast.Concat -> Result.success(Unit)
        }
    }

    public open fun visitAlternationIn() {
        this.wtr.append("|")
    }

    public open fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit> =
        when (ast) {
            is ClassSetItem.Bracketed -> {
                val (x) = ast
                this.fmtClassBracketedPre(x)
            }
            else -> Result.success(Unit)
        }

    public open fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit> {
        return when (ast) {
            is Empty -> Result.success(Unit)
            is Literal -> {
                val (x) = ast
                this.fmtLiteral(x)
            }
            is Range -> {
                val (x1) = ast
                this.fmtLiteral(x1.start).getOrElse { return Result.failure(it) }
                this.wtr.append("-").getOrElse { return Result.failure(it) }
                this.fmtLiteral(x1.end).getOrElse { return Result.failure(it) }
                Result.success(Unit)
            }
            is Ascii -> {
                val (x2) = ast
                this.fmtClassAscii(x2)
            }
            is Unicode -> {
                val (x3) = ast
                this.fmtClassUnicode(x3)
            }
            is Perl -> {
                val (x4) = ast
                this.fmtClassPerl(x4)
            }
            is Bracketed -> {
                val (x5) = ast
                this.fmtClassBracketedPost(x5)
            }
            is Union -> Result.success(Unit)
        }
    }

    public open fun visitClassSetBinaryOpIn(ast: ClassSetBinaryOp): Result<Unit> = this.fmtClassSetBinaryOpKind(ast.kind)

    public open fun fmtGroupPre(ast: Group) {
        when (val matchVal = ast.kind) {
            is CaptureIndex -> this.wtr.append("(")
            is CaptureName -> {
                val name = matchVal.name
                val startsWithP = matchVal.startsWithP
                val start =
                    if (startsWithP) {
                        "(?P<"
                    } else {
                        "(?<"
                    }
                this.wtr.append(start).getOrElse { return Result.failure(it) }
                this.wtr.append(name.name).getOrElse { return Result.failure(it) }
                this.wtr.append(">").getOrElse { return Result.failure(it) }
                Result.success(Unit)
            }
            is NonCapturing -> {
                val (flags) = matchVal
                this.wtr.append("(?").getOrElse { return Result.failure(it) }
                this.fmtFlags(flags).getOrElse { return Result.failure(it) }
                this.wtr.append(":").getOrElse { return Result.failure(it) }
                Result.success(Unit)
            }
        }
    }

    public open fun fmtGroupPost(ast: Group) {
        this.wtr.append(")")
    }

    public open fun fmtRepetition(ast: Repetition) {
        run {
            val matchVal = ast.op.kind
            when {
                matchVal == ZeroOrOne && ast.greedy -> this.wtr.append("?")
                matchVal == ZeroOrOne -> this.wtr.append("??")
                matchVal == ZeroOrMore && ast.greedy -> this.wtr.append("*")
                matchVal == ZeroOrMore -> this.wtr.append("*?")
                matchVal == OneOrMore && ast.greedy -> this.wtr.append("+")
                matchVal == OneOrMore -> this.wtr.append("+?")
                else -> {
                    val (x) = matchVal
                    this.fmtRepetitionRange(x).getOrElse { return Result.failure(it) }
                    if (!ast.greedy) {
                        this.wtr.append("?").getOrElse { return Result.failure(it) }
                    }
                    Result.success(Unit)
                }
            }
        }
    }

    public open fun fmtRepetitionRange(ast: RepetitionRange) {
        when (ast) {
            is Exactly -> {
                val (x) = ast
                this.wtr.append("{$x}")
            }
            is AtLeast -> {
                val (x1) = ast
                this.wtr.append("{$x,}")
            }
            is Bounded -> {
                val (x2, y) = ast
                this.wtr.append("{$x,$y}")
            }
        }
    }

    public open fun fmtLiteral(ast: Literal) {
        run {
            val matchVal = ast.kind
            when {
                matchVal == Verbatim -> this.wtr.append(ast.c)
                matchVal == Meta || matchVal == Superfluous -> this.wtr.append("\\${ast.c}")
                matchVal == Octal -> this.wtr.append("\\${UInt.from(ast.c)}")
                matchVal is HexFixed && matchVal.value == HexLiteralKind.X -> {
                    this.wtr.append("\\x${UInt.from(ast.c)}")
                }
                matchVal is HexFixed && matchVal.value == HexLiteralKind.UnicodeShort -> {
                    this.wtr.append("\\u${UInt.from(ast.c)}")
                }
                matchVal is HexFixed && matchVal.value == HexLiteralKind.UnicodeLong -> {
                    this.wtr.append("\\U${UInt.from(ast.c)}")
                }
                matchVal is HexBrace && matchVal.value == HexLiteralKind.X -> {
                    this.wtr.append("\\x{${UInt.from(ast.c)}}")
                }
                matchVal is HexBrace && matchVal.value == HexLiteralKind.UnicodeShort -> {
                    this.wtr.append("\\u{${UInt.from(ast.c)}}")
                }
                matchVal is HexBrace && matchVal.value == HexLiteralKind.UnicodeLong -> {
                    this.wtr.append("\\U{${UInt.from(ast.c)}}")
                }
                matchVal is Special && matchVal.value == SpecialLiteralKind.Bell -> {
                    this.wtr.append("\\a")
                }
                matchVal is Special && matchVal.value == SpecialLiteralKind.FormFeed -> {
                    this.wtr.append("\\f")
                }
                matchVal is Special && matchVal.value == SpecialLiteralKind.Tab -> this.wtr.append("\\t")
                matchVal is Special && matchVal.value == SpecialLiteralKind.LineFeed -> {
                    this.wtr.append("\\n")
                }
                matchVal is Special && matchVal.value == SpecialLiteralKind.CarriageReturn -> {
                    this.wtr.append("\\r")
                }
                matchVal is Special && matchVal.value == SpecialLiteralKind.VerticalTab -> {
                    this.wtr.append("\\v")
                }
                else -> {
                    this.wtr.append("\\ ")
                }
            }
        }
    }

    public open fun fmtAssertion(ast: Assertion) {
        when (ast.kind) {
            StartLine -> this.wtr.append("^")
            EndLine -> this.wtr.append("\$")
            StartText -> this.wtr.append("\\A")
            EndText -> this.wtr.append("\\z")
            WordBoundary -> this.wtr.append("\\b")
            NotWordBoundary -> this.wtr.append("\\B")
            WordBoundaryStart -> this.wtr.append("\\b{start}")
            WordBoundaryEnd -> this.wtr.append("\\b{end}")
            WordBoundaryStartAngle -> this.wtr.append("\\<")
            WordBoundaryEndAngle -> this.wtr.append("\\>")
            WordBoundaryStartHalf -> this.wtr.append("\\b{start-half}")
            WordBoundaryEndHalf -> this.wtr.append("\\b{end-half}")
        }
    }

    public open fun fmtSetFlags(ast: SetFlags) {
        this.wtr.append("(?").getOrElse { return Result.failure(it) }
        this.fmtFlags(ast.flags).getOrElse { return Result.failure(it) }
        this.wtr.append(")").getOrElse { return Result.failure(it) }
        Result.success(Unit)
    }

    public open fun fmtFlags(ast: Flags) {
        for (item in ast.items) {
            when (val matchVal = item.kind) {
                FlagsItemKind.Negation -> this.wtr.append("-")
                is FlagsItemKind.Flag -> {
                    val (flag) = matchVal
                    when (flag) {
                        Flag.CaseInsensitive -> this.wtr.append("i")
                        Flag.MultiLine -> this.wtr.append("m")
                        Flag.DotMatchesNewLine -> this.wtr.append("s")
                        Flag.SwapGreed -> this.wtr.append("U")
                        Flag.Unicode -> this.wtr.append("u")
                        Flag.crlf -> this.wtr.append("R")
                        Flag.IgnoreWhitespace -> this.wtr.append("x")
                    }
                }
            }.getOrElse { return Result.failure(it) }
        }
        Result.success(Unit)
    }

    public open fun fmtClassBracketedPre(ast: ClassBracketed) {
        if (ast.negated) {
            this.wtr.append("[^")
        } else {
            this.wtr.append("[")
        }
    }

    public open fun fmtClassBracketedPost(ast: ClassBracketed) {
        this.wtr.append("]")
    }

    public open fun fmtClassSetBinaryOpKind(ast: ClassSetBinaryOpKind) {
        when (ast) {
            Intersection -> this.wtr.append("&&")
            Difference -> this.wtr.append("--")
            SymmetricDifference -> this.wtr.append("~~")
        }
    }

    public open fun fmtClassPerl(ast: ClassPerl) {
        run {
            val matchVal = ast.kind
            when {
                matchVal == Digit && ast.negated -> this.wtr.append("\\D")
                matchVal == Digit -> this.wtr.append("\\d")
                matchVal == Space && ast.negated -> this.wtr.append("\\S")
                matchVal == Space -> this.wtr.append("\\s")
                matchVal == Word && ast.negated -> this.wtr.append("\\W")
                else -> this.wtr.append("\\w")
            }
        }
    }

    public open fun fmtClassAscii(ast: ClassAscii) {
        run {
            val matchVal = ast.kind
            when {
                matchVal == Alnum && ast.negated -> this.wtr.append("[:^alnum:]")
                matchVal == Alnum -> this.wtr.append("[:alnum:]")
                matchVal == Alpha && ast.negated -> this.wtr.append("[:^alpha:]")
                matchVal == Alpha -> this.wtr.append("[:alpha:]")
                matchVal == Ascii && ast.negated -> this.wtr.append("[:^ascii:]")
                matchVal == Ascii -> this.wtr.append("[:ascii:]")
                matchVal == Blank && ast.negated -> this.wtr.append("[:^blank:]")
                matchVal == Blank -> this.wtr.append("[:blank:]")
                matchVal == Cntrl && ast.negated -> this.wtr.append("[:^cntrl:]")
                matchVal == Cntrl -> this.wtr.append("[:cntrl:]")
                matchVal == Digit && ast.negated -> this.wtr.append("[:^digit:]")
                matchVal == Digit -> this.wtr.append("[:digit:]")
                matchVal == Graph && ast.negated -> this.wtr.append("[:^graph:]")
                matchVal == Graph -> this.wtr.append("[:graph:]")
                matchVal == Lower && ast.negated -> this.wtr.append("[:^lower:]")
                matchVal == Lower -> this.wtr.append("[:lower:]")
                matchVal == Print && ast.negated -> this.wtr.append("[:^print:]")
                matchVal == Print -> this.wtr.append("[:print:]")
                matchVal == Punct && ast.negated -> this.wtr.append("[:^punct:]")
                matchVal == Punct -> this.wtr.append("[:punct:]")
                matchVal == Space && ast.negated -> this.wtr.append("[:^space:]")
                matchVal == Space -> this.wtr.append("[:space:]")
                matchVal == Upper && ast.negated -> this.wtr.append("[:^upper:]")
                matchVal == Upper -> this.wtr.append("[:upper:]")
                matchVal == Word && ast.negated -> this.wtr.append("[:^word:]")
                matchVal == Word -> this.wtr.append("[:word:]")
                matchVal == Xdigit && ast.negated -> this.wtr.append("[:^xdigit:]")
                else -> this.wtr.append("[:xdigit:]")
            }
        }
    }

    public open fun fmtClassUnicode(ast: ClassUnicode) {
        if (ast.negated) {
            this.wtr.append("\\P").getOrElse { return Result.failure(it) }
        } else {
            this.wtr.append("\\p").getOrElse { return Result.failure(it) }
        }
        run {
            val matchVal = ast.kind
            when {
                matchVal is OneLetter -> {
                    val (c) = matchVal
                    this.wtr.append(c)
                }
                matchVal is Named -> {
                    val (x) = matchVal
                    this.wtr.append("{$x}")
                }
                matchVal is NamedValue && matchVal.op == Equal -> {
                    val name = matchVal.name
                    val value = matchVal.value
                    this.wtr.append("{$name=$value}")
                }
                matchVal is NamedValue && matchVal.op == Colon -> {
                    val name1 = matchVal.name
                    val value1 = matchVal.value
                    this.wtr.append("{$name1:$value1}")
                }
                else -> {
                    val name2 = matchVal.name
                    val value2 = matchVal.value
                    this.wtr.append("{$name2!=$value2}")
                }
            }
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
