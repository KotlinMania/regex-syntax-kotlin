// port-lint: source ast/print.rs
package io.github.kotlinmania.regexsyntax.ast.print

import io.github.kotlinmania.regexsyntax.FmtError
import io.github.kotlinmania.regexsyntax.appendScalar
import io.github.kotlinmania.regexsyntax.ast.Assertion
import io.github.kotlinmania.regexsyntax.ast.AssertionKind
import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.ClassAscii
import io.github.kotlinmania.regexsyntax.ast.ClassAsciiKind
import io.github.kotlinmania.regexsyntax.ast.ClassBracketed
import io.github.kotlinmania.regexsyntax.ast.ClassPerl
import io.github.kotlinmania.regexsyntax.ast.ClassPerlKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOp
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOpKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetItem
import io.github.kotlinmania.regexsyntax.ast.ClassUnicode
import io.github.kotlinmania.regexsyntax.ast.ClassUnicodeKind
import io.github.kotlinmania.regexsyntax.ast.ClassUnicodeOpKind
import io.github.kotlinmania.regexsyntax.ast.Flag
import io.github.kotlinmania.regexsyntax.ast.Flags
import io.github.kotlinmania.regexsyntax.ast.FlagsItemKind
import io.github.kotlinmania.regexsyntax.ast.Group
import io.github.kotlinmania.regexsyntax.ast.GroupKind
import io.github.kotlinmania.regexsyntax.ast.HexLiteralKind
import io.github.kotlinmania.regexsyntax.ast.Literal
import io.github.kotlinmania.regexsyntax.ast.LiteralKind
import io.github.kotlinmania.regexsyntax.ast.Repetition
import io.github.kotlinmania.regexsyntax.ast.RepetitionKind
import io.github.kotlinmania.regexsyntax.ast.RepetitionRange
import io.github.kotlinmania.regexsyntax.ast.SetFlags
import io.github.kotlinmania.regexsyntax.ast.SpecialLiteralKind
import io.github.kotlinmania.regexsyntax.ast.visitor.Visitor
import io.github.kotlinmania.regexsyntax.ast.visitor.visit
import io.github.kotlinmania.regexsyntax.formatHexUpper
import io.github.kotlinmania.regexsyntax.formatHexUpperPadded
import io.github.kotlinmania.regexsyntax.formatOctal
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout
import io.github.kotlinmania.rustlang.core.result.Result

// This module provides a regular expression printer for `Ast`.

/**
 * A builder for constructing a printer.
 *
 * Note that since a printer doesn't have any configuration knobs, this type
 * remains unexported.
 */
internal class PrinterBuilder(
    internal var priv: Unit,
) {
    internal fun build(): Printer = Printer(priv = Unit)

    internal companion object {
        internal fun typeLayout(): TypeLayout<PrinterBuilder> =
            object : TypeLayout<PrinterBuilder> {
                override val alignBytes: Long = 1L
                override val sizeBytes: Long = 0L
                override fun readNative(addr: Long): PrinterBuilder =
                    PrinterBuilder(
                        PrimitiveLayouts.UNIT.readNative(addr),
                    )
                override fun writeNative(addr: Long, value: PrinterBuilder) {
                    PrimitiveLayouts.UNIT.writeNative(addr, value.priv)
                }
            }

        internal fun default(): PrinterBuilder = PrinterBuilder.new()

        internal fun new(): PrinterBuilder = PrinterBuilder(priv = Unit)
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
public class Printer(
    internal var priv: Unit,
) {
    /**
     * Print the given `Ast` to the given writer.
     */
    public fun print(ast: Ast, wtr: Appendable): Result<Unit, FmtError> =
        visit(ast, Writer(wtr = wtr))

    public companion object {
        public fun typeLayout(): TypeLayout<Printer> =
            object : TypeLayout<Printer> {
                override val alignBytes: Long = 1L
                override val sizeBytes: Long = 0L
                override fun readNative(addr: Long): Printer =
                    Printer(
                        PrimitiveLayouts.UNIT.readNative(addr),
                    )
                override fun writeNative(addr: Long, value: Printer) {
                    PrimitiveLayouts.UNIT.writeNative(addr, value.priv)
                }
            }

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

    override fun visitPre(ast: Ast): Result<Unit, FmtError> =
        when (ast) {
            is Ast.Group -> this.fmtGroupPre(ast.value)
            is Ast.ClassBracketed -> this.fmtClassBracketedPre(ast.value)
            else -> Result.Ok(Unit)
        }

    override fun visitPost(ast: Ast): Result<Unit, FmtError> =
        when (ast) {
            is Ast.Empty -> Result.Ok(Unit)
            is Ast.Flags -> this.fmtSetFlags(ast.value)
            is Ast.Literal -> this.fmtLiteral(ast.value)
            is Ast.Dot -> {
                this.wtr.append(".")
                Result.Ok(Unit)
            }
            is Ast.Assertion -> this.fmtAssertion(ast.value)
            is Ast.ClassPerl -> this.fmtClassPerl(ast.value)
            is Ast.ClassUnicode -> this.fmtClassUnicode(ast.value)
            is Ast.ClassBracketed -> this.fmtClassBracketedPost(ast.value)
            is Ast.Repetition -> this.fmtRepetition(ast.value)
            is Ast.Group -> this.fmtGroupPost(ast.value)
            is Ast.Alternation -> Result.Ok(Unit)
            is Ast.Concat -> Result.Ok(Unit)
        }

    override fun visitAlternationIn(): Result<Unit, FmtError> {
        this.wtr.append("|")
        return Result.Ok(Unit)
    }

    override fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit, FmtError> =
        when (ast) {
            is ClassSetItem.Bracketed -> this.fmtClassBracketedPre(ast.value)
            else -> Result.Ok(Unit)
        }

    override fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit, FmtError> =
        when (ast) {
            is ClassSetItem.Empty -> Result.Ok(Unit)
            is ClassSetItem.Literal -> this.fmtLiteral(ast.value)
            is ClassSetItem.Range -> {
                val range = ast.value
                this.fmtLiteral(range.start)
                this.wtr.append("-")
                this.fmtLiteral(range.end)
            }
            is ClassSetItem.Ascii -> this.fmtClassAscii(ast.value)
            is ClassSetItem.Unicode -> this.fmtClassUnicode(ast.value)
            is ClassSetItem.Perl -> this.fmtClassPerl(ast.value)
            is ClassSetItem.Bracketed -> this.fmtClassBracketedPost(ast.value)
            is ClassSetItem.Union -> Result.Ok(Unit)
        }

    override fun visitClassSetBinaryOpIn(ast: ClassSetBinaryOp): Result<Unit, FmtError> =
        this.fmtClassSetBinaryOpKind(ast.kind)

    internal fun fmtGroupPre(ast: Group): Result<Unit, FmtError> =
        when (val matchVal = ast.kind) {
            is GroupKind.CaptureIndex -> {
                this.wtr.append("(")
                Result.Ok(Unit)
            }
            is GroupKind.CaptureName -> {
                val start = if (matchVal.startsWithP) "(?P<" else "(?<"
                this.wtr.append(start)
                this.wtr.append(matchVal.name.name)
                this.wtr.append(">")
                Result.Ok(Unit)
            }
            is GroupKind.NonCapturing -> {
                this.wtr.append("(?")
                this.fmtFlags(matchVal.value)
                this.wtr.append(":")
                Result.Ok(Unit)
            }
        }

    internal fun fmtGroupPost(ast: Group): Result<Unit, FmtError> {
        this.wtr.append(")")
        return Result.Ok(Unit)
    }

    internal fun fmtRepetition(ast: Repetition): Result<Unit, FmtError> {
        val kind = ast.op.kind
        when {
            kind == RepetitionKind.ZeroOrOne && ast.greedy -> this.wtr.append("?")
            kind == RepetitionKind.ZeroOrOne -> this.wtr.append("??")
            kind == RepetitionKind.ZeroOrMore && ast.greedy -> this.wtr.append("*")
            kind == RepetitionKind.ZeroOrMore -> this.wtr.append("*?")
            kind == RepetitionKind.OneOrMore && ast.greedy -> this.wtr.append("+")
            kind == RepetitionKind.OneOrMore -> this.wtr.append("+?")
            else -> {
                val range = (kind as RepetitionKind.Range).value
                this.fmtRepetitionRange(range)
                if (!ast.greedy) {
                    this.wtr.append("?")
                }
            }
        }
        return Result.Ok(Unit)
    }

    internal fun fmtRepetitionRange(ast: RepetitionRange): Result<Unit, FmtError> {
        when (ast) {
            is RepetitionRange.Exactly -> this.wtr.append("{${ast.value}}")
            is RepetitionRange.AtLeast -> this.wtr.append("{${ast.value},}")
            is RepetitionRange.Bounded -> this.wtr.append("{${ast.field0},${ast.field1}}")
        }
        return Result.Ok(Unit)
    }

    internal fun fmtLiteral(ast: Literal): Result<Unit, FmtError> {
        when (val matchVal = ast.kind) {
            LiteralKind.Verbatim -> this.wtr.appendScalar(ast.c)
            LiteralKind.Meta, LiteralKind.Superfluous -> {
                this.wtr.append("\\")
                this.wtr.appendScalar(ast.c)
            }
            LiteralKind.Octal -> this.wtr.append("\\${formatOctal(ast.c)}")
            is LiteralKind.HexFixed -> when (matchVal.value) {
                HexLiteralKind.X -> this.wtr.append("\\x${formatHexUpperPadded(ast.c, 2)}")
                HexLiteralKind.UnicodeShort -> this.wtr.append("\\u${formatHexUpperPadded(ast.c, 4)}")
                HexLiteralKind.UnicodeLong -> this.wtr.append("\\U${formatHexUpperPadded(ast.c, 8)}")
            }
            is LiteralKind.HexBrace -> when (matchVal.value) {
                HexLiteralKind.X -> this.wtr.append("\\x{${formatHexUpper(ast.c)}}")
                HexLiteralKind.UnicodeShort -> this.wtr.append("\\u{${formatHexUpper(ast.c)}}")
                HexLiteralKind.UnicodeLong -> this.wtr.append("\\U{${formatHexUpper(ast.c)}}")
            }
            is LiteralKind.Special -> when (matchVal.value) {
                SpecialLiteralKind.Bell -> this.wtr.append("\\a")
                SpecialLiteralKind.FormFeed -> this.wtr.append("\\f")
                SpecialLiteralKind.Tab -> this.wtr.append("\\t")
                SpecialLiteralKind.LineFeed -> this.wtr.append("\\n")
                SpecialLiteralKind.CarriageReturn -> this.wtr.append("\\r")
                SpecialLiteralKind.VerticalTab -> this.wtr.append("\\v")
                SpecialLiteralKind.Space -> this.wtr.append("\\ ")
            }
        }
        return Result.Ok(Unit)
    }

    internal fun fmtAssertion(ast: Assertion): Result<Unit, FmtError> {
        when (ast.kind) {
            AssertionKind.StartLine -> this.wtr.append("^")
            AssertionKind.EndLine -> this.wtr.append("\$")
            AssertionKind.StartText -> this.wtr.append("\\A")
            AssertionKind.EndText -> this.wtr.append("\\z")
            AssertionKind.WordBoundary -> this.wtr.append("\\b")
            AssertionKind.NotWordBoundary -> this.wtr.append("\\B")
            AssertionKind.WordBoundaryStart -> this.wtr.append("\\b{start}")
            AssertionKind.WordBoundaryEnd -> this.wtr.append("\\b{end}")
            AssertionKind.WordBoundaryStartAngle -> this.wtr.append("\\<")
            AssertionKind.WordBoundaryEndAngle -> this.wtr.append("\\>")
            AssertionKind.WordBoundaryStartHalf -> this.wtr.append("\\b{start-half}")
            AssertionKind.WordBoundaryEndHalf -> this.wtr.append("\\b{end-half}")
        }
        return Result.Ok(Unit)
    }

    internal fun fmtSetFlags(ast: SetFlags): Result<Unit, FmtError> {
        this.wtr.append("(?")
        this.fmtFlags(ast.flags)
        this.wtr.append(")")
        return Result.Ok(Unit)
    }

    internal fun fmtFlags(ast: Flags): Result<Unit, FmtError> {
        for (item in ast.items) {
            when (val matchVal = item.kind) {
                FlagsItemKind.Negation -> this.wtr.append("-")
                is FlagsItemKind.Flag -> when (matchVal.value) {
                    Flag.CaseInsensitive -> this.wtr.append("i")
                    Flag.MultiLine -> this.wtr.append("m")
                    Flag.DotMatchesNewLine -> this.wtr.append("s")
                    Flag.SwapGreed -> this.wtr.append("U")
                    Flag.Unicode -> this.wtr.append("u")
                    Flag.Crlf -> this.wtr.append("R")
                    Flag.IgnoreWhitespace -> this.wtr.append("x")
                }
            }
        }
        return Result.Ok(Unit)
    }

    internal fun fmtClassBracketedPre(ast: ClassBracketed): Result<Unit, FmtError> {
        if (ast.negated) {
            this.wtr.append("[^")
        } else {
            this.wtr.append("[")
        }
        return Result.Ok(Unit)
    }

    internal fun fmtClassBracketedPost(ast: ClassBracketed): Result<Unit, FmtError> {
        this.wtr.append("]")
        return Result.Ok(Unit)
    }

    internal fun fmtClassSetBinaryOpKind(ast: ClassSetBinaryOpKind): Result<Unit, FmtError> {
        when (ast) {
            ClassSetBinaryOpKind.Intersection -> this.wtr.append("&&")
            ClassSetBinaryOpKind.Difference -> this.wtr.append("--")
            ClassSetBinaryOpKind.SymmetricDifference -> this.wtr.append("~~")
        }
        return Result.Ok(Unit)
    }

    internal fun fmtClassPerl(ast: ClassPerl): Result<Unit, FmtError> {
        val s = when {
            ast.kind == ClassPerlKind.Digit && ast.negated -> "\\D"
            ast.kind == ClassPerlKind.Digit -> "\\d"
            ast.kind == ClassPerlKind.Space && ast.negated -> "\\S"
            ast.kind == ClassPerlKind.Space -> "\\s"
            ast.kind == ClassPerlKind.Word && ast.negated -> "\\W"
            else -> "\\w"
        }
        this.wtr.append(s)
        return Result.Ok(Unit)
    }

    internal fun fmtClassAscii(ast: ClassAscii): Result<Unit, FmtError> {
        val s = when {
            ast.kind == ClassAsciiKind.Alnum && ast.negated -> "[:^alnum:]"
            ast.kind == ClassAsciiKind.Alnum -> "[:alnum:]"
            ast.kind == ClassAsciiKind.Alpha && ast.negated -> "[:^alpha:]"
            ast.kind == ClassAsciiKind.Alpha -> "[:alpha:]"
            ast.kind == ClassAsciiKind.Ascii && ast.negated -> "[:^ascii:]"
            ast.kind == ClassAsciiKind.Ascii -> "[:ascii:]"
            ast.kind == ClassAsciiKind.Blank && ast.negated -> "[:^blank:]"
            ast.kind == ClassAsciiKind.Blank -> "[:blank:]"
            ast.kind == ClassAsciiKind.Cntrl && ast.negated -> "[:^cntrl:]"
            ast.kind == ClassAsciiKind.Cntrl -> "[:cntrl:]"
            ast.kind == ClassAsciiKind.Digit && ast.negated -> "[:^digit:]"
            ast.kind == ClassAsciiKind.Digit -> "[:digit:]"
            ast.kind == ClassAsciiKind.Graph && ast.negated -> "[:^graph:]"
            ast.kind == ClassAsciiKind.Graph -> "[:graph:]"
            ast.kind == ClassAsciiKind.Lower && ast.negated -> "[:^lower:]"
            ast.kind == ClassAsciiKind.Lower -> "[:lower:]"
            ast.kind == ClassAsciiKind.Print && ast.negated -> "[:^print:]"
            ast.kind == ClassAsciiKind.Print -> "[:print:]"
            ast.kind == ClassAsciiKind.Punct && ast.negated -> "[:^punct:]"
            ast.kind == ClassAsciiKind.Punct -> "[:punct:]"
            ast.kind == ClassAsciiKind.Space && ast.negated -> "[:^space:]"
            ast.kind == ClassAsciiKind.Space -> "[:space:]"
            ast.kind == ClassAsciiKind.Upper && ast.negated -> "[:^upper:]"
            ast.kind == ClassAsciiKind.Upper -> "[:upper:]"
            ast.kind == ClassAsciiKind.Word && ast.negated -> "[:^word:]"
            ast.kind == ClassAsciiKind.Word -> "[:word:]"
            ast.kind == ClassAsciiKind.Xdigit && ast.negated -> "[:^xdigit:]"
            else -> "[:xdigit:]"
        }
        this.wtr.append(s)
        return Result.Ok(Unit)
    }

    internal fun fmtClassUnicode(ast: ClassUnicode): Result<Unit, FmtError> {
        if (ast.negated) {
            this.wtr.append("\\P")
        } else {
            this.wtr.append("\\p")
        }
        when (val matchVal = ast.kind) {
            is ClassUnicodeKind.OneLetter -> this.wtr.appendScalar(matchVal.value)
            is ClassUnicodeKind.Named -> this.wtr.append("{${matchVal.value}}")
            is ClassUnicodeKind.NamedValue -> {
                val sep = when (matchVal.op) {
                    ClassUnicodeOpKind.Equal -> "="
                    ClassUnicodeOpKind.Colon -> ":"
                    ClassUnicodeOpKind.NotEqual -> "!="
                }
                this.wtr.append("{${matchVal.name}$sep${matchVal.value}}")
            }
        }
        return Result.Ok(Unit)
    }
}
