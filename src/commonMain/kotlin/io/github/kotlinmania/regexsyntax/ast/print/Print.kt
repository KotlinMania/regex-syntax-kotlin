// port-lint: source src/ast/print.rs
package io.github.kotlinmania.regexsyntax.ast.print

/*
 * Copyright (c) The rust-lang regex contributors.
 * Licensed under either of Apache-2.0 OR MIT.
 */

/*!
 * This module provides a regular expression printer for [Ast].
 */

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

/**
 * A builder for constructing a printer.
 *
 * Note that since a printer doesn't have any configuration knobs, this type
 * remains unexported.
 */
private class PrinterBuilder {
    fun build(): Printer = Printer()
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
class Printer {
    /**
     * Print the given [Ast] to the given writer. The writer must implement
     * [Appendable]. Typical implementations of [Appendable] that can be used
     * here are a [StringBuilder] or any other text sink.
     */
    fun print(ast: Ast, wtr: Appendable): Result<Unit> {
        return visit(ast, Writer(wtr))
    }

    companion object {
        /** Create a new printer. */
        fun new(): Printer = PrinterBuilder().build()
    }
}

private class Writer(val wtr: Appendable) : Visitor<Unit, Throwable> {
    override fun finish(): Result<Unit> = Result.success(Unit)

    override fun visitPre(ast: Ast): Result<Unit> {
        return when (ast) {
            is Ast.Group -> fmtGroupPre(ast.value)
            is Ast.ClassBracketed -> fmtClassBracketedPre(ast.value)
            else -> Result.success(Unit)
        }
    }

    override fun visitPost(ast: Ast): Result<Unit> {
        return when (ast) {
            is Ast.Empty -> Result.success(Unit)
            is Ast.Flags -> fmtSetFlags(ast.value)
            is Ast.Literal -> fmtLiteral(ast.value)
            is Ast.Dot -> writeStr(".")
            is Ast.Assertion -> fmtAssertion(ast.value)
            is Ast.ClassPerl -> fmtClassPerl(ast.value)
            is Ast.ClassUnicode -> fmtClassUnicode(ast.value)
            is Ast.ClassBracketed -> fmtClassBracketedPost(ast.value)
            is Ast.Repetition -> fmtRepetition(ast.value)
            is Ast.Group -> fmtGroupPost(ast.value)
            is Ast.Alternation -> Result.success(Unit)
            is Ast.Concat -> Result.success(Unit)
        }
    }

    override fun visitAlternationIn(): Result<Unit> = writeStr("|")

    override fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit> {
        return when (ast) {
            is ClassSetItem.Bracketed -> fmtClassBracketedPre(ast.value)
            else -> Result.success(Unit)
        }
    }

    override fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit> {
        return when (ast) {
            is ClassSetItem.Empty -> Result.success(Unit)
            is ClassSetItem.Literal -> fmtLiteral(ast.value)
            is ClassSetItem.Range -> {
                val r1 = fmtLiteral(ast.value.start)
                if (r1.isFailure) return r1
                val r2 = writeStr("-")
                if (r2.isFailure) return r2
                val r3 = fmtLiteral(ast.value.end)
                if (r3.isFailure) return r3
                Result.success(Unit)
            }
            is ClassSetItem.Ascii -> fmtClassAscii(ast.value)
            is ClassSetItem.Unicode -> fmtClassUnicode(ast.value)
            is ClassSetItem.Perl -> fmtClassPerl(ast.value)
            is ClassSetItem.Bracketed -> fmtClassBracketedPost(ast.value)
            is ClassSetItem.Union -> Result.success(Unit)
        }
    }

    override fun visitClassSetBinaryOpIn(ast: ClassSetBinaryOp): Result<Unit> {
        return fmtClassSetBinaryOpKind(ast.kind)
    }

    private fun writeStr(s: String): Result<Unit> = runCatching { wtr.append(s); Unit }

    private fun writeChar(c: Char): Result<Unit> = runCatching { wtr.append(c); Unit }

    private fun fmtGroupPre(ast: Group): Result<Unit> {
        return when (val k = ast.kind) {
            is GroupKind.CaptureIndex -> writeStr("(")
            is GroupKind.CaptureName -> {
                val start = if (k.startsWithP) "(?P<" else "(?<"
                val r1 = writeStr(start)
                if (r1.isFailure) return r1
                val r2 = writeStr(k.name.name)
                if (r2.isFailure) return r2
                val r3 = writeStr(">")
                if (r3.isFailure) return r3
                Result.success(Unit)
            }
            is GroupKind.NonCapturing -> {
                val r1 = writeStr("(?")
                if (r1.isFailure) return r1
                val r2 = fmtFlags(k.value)
                if (r2.isFailure) return r2
                val r3 = writeStr(":")
                if (r3.isFailure) return r3
                Result.success(Unit)
            }
        }
    }

    private fun fmtGroupPost(ast: Group): Result<Unit> = writeStr(")")

    private fun fmtRepetition(ast: Repetition): Result<Unit> {
        return when (val k = ast.op.kind) {
            is RepetitionKind.ZeroOrOne -> if (ast.greedy) writeStr("?") else writeStr("??")
            is RepetitionKind.ZeroOrMore -> if (ast.greedy) writeStr("*") else writeStr("*?")
            is RepetitionKind.OneOrMore -> if (ast.greedy) writeStr("+") else writeStr("+?")
            is RepetitionKind.Range -> {
                val r1 = fmtRepetitionRange(k.value)
                if (r1.isFailure) return r1
                if (!ast.greedy) {
                    val r2 = writeStr("?")
                    if (r2.isFailure) return r2
                }
                Result.success(Unit)
            }
        }
    }

    private fun fmtRepetitionRange(ast: RepetitionRange): Result<Unit> {
        return when (ast) {
            is RepetitionRange.Exactly -> writeStr("{${ast.value}}")
            is RepetitionRange.AtLeast -> writeStr("{${ast.value},}")
            is RepetitionRange.Bounded -> writeStr("{${ast.start},${ast.end}}")
        }
    }

    private fun fmtLiteral(ast: Literal): Result<Unit> {
        val cu = ast.c.code
        return when (val k = ast.kind) {
            is LiteralKind.Verbatim -> writeChar(ast.c)
            is LiteralKind.Meta, is LiteralKind.Superfluous -> writeStr("\\${ast.c}")
            is LiteralKind.Octal -> writeStr("\\${cu.toString(8)}")
            is LiteralKind.HexFixed -> when (k.value) {
                HexLiteralKind.X -> writeStr("\\x" + hexFixed(cu, 2))
                HexLiteralKind.UnicodeShort -> writeStr("\\u" + hexFixed(cu, 4))
                HexLiteralKind.UnicodeLong -> writeStr("\\U" + hexFixed(cu, 8))
            }
            is LiteralKind.HexBrace -> when (k.value) {
                HexLiteralKind.X -> writeStr("\\x{" + cu.toString(16).uppercase() + "}")
                HexLiteralKind.UnicodeShort -> writeStr("\\u{" + cu.toString(16).uppercase() + "}")
                HexLiteralKind.UnicodeLong -> writeStr("\\U{" + cu.toString(16).uppercase() + "}")
            }
            is LiteralKind.Special -> when (k.value) {
                SpecialLiteralKind.Bell -> writeStr("\\a")
                SpecialLiteralKind.FormFeed -> writeStr("\\f")
                SpecialLiteralKind.Tab -> writeStr("\\t")
                SpecialLiteralKind.LineFeed -> writeStr("\\n")
                SpecialLiteralKind.CarriageReturn -> writeStr("\\r")
                SpecialLiteralKind.VerticalTab -> writeStr("\\v")
                SpecialLiteralKind.Space -> writeStr("\\ ")
            }
        }
    }

    private fun fmtAssertion(ast: Assertion): Result<Unit> {
        return when (ast.kind) {
            AssertionKind.StartLine -> writeStr("^")
            AssertionKind.EndLine -> writeStr("$")
            AssertionKind.StartText -> writeStr("\\A")
            AssertionKind.EndText -> writeStr("\\z")
            AssertionKind.WordBoundary -> writeStr("\\b")
            AssertionKind.NotWordBoundary -> writeStr("\\B")
            AssertionKind.WordBoundaryStart -> writeStr("\\b{start}")
            AssertionKind.WordBoundaryEnd -> writeStr("\\b{end}")
            AssertionKind.WordBoundaryStartAngle -> writeStr("\\<")
            AssertionKind.WordBoundaryEndAngle -> writeStr("\\>")
            AssertionKind.WordBoundaryStartHalf -> writeStr("\\b{start-half}")
            AssertionKind.WordBoundaryEndHalf -> writeStr("\\b{end-half}")
        }
    }

    private fun fmtSetFlags(ast: SetFlags): Result<Unit> {
        val r1 = writeStr("(?")
        if (r1.isFailure) return r1
        val r2 = fmtFlags(ast.flags)
        if (r2.isFailure) return r2
        val r3 = writeStr(")")
        if (r3.isFailure) return r3
        return Result.success(Unit)
    }

    private fun fmtFlags(ast: Flags): Result<Unit> {
        for (item in ast.items) {
            val r = when (val kind = item.kind) {
                is FlagsItemKind.Negation -> writeStr("-")
                is FlagsItemKind.Flag -> when (kind.value) {
                    Flag.CaseInsensitive -> writeStr("i")
                    Flag.MultiLine -> writeStr("m")
                    Flag.DotMatchesNewLine -> writeStr("s")
                    Flag.SwapGreed -> writeStr("U")
                    Flag.Unicode -> writeStr("u")
                    Flag.CRLF -> writeStr("R")
                    Flag.IgnoreWhitespace -> writeStr("x")
                }
            }
            if (r.isFailure) return r
        }
        return Result.success(Unit)
    }

    private fun fmtClassBracketedPre(ast: ClassBracketed): Result<Unit> {
        return if (ast.negated) writeStr("[^") else writeStr("[")
    }

    private fun fmtClassBracketedPost(ast: ClassBracketed): Result<Unit> = writeStr("]")

    private fun fmtClassSetBinaryOpKind(ast: ClassSetBinaryOpKind): Result<Unit> {
        return when (ast) {
            ClassSetBinaryOpKind.Intersection -> writeStr("&&")
            ClassSetBinaryOpKind.Difference -> writeStr("--")
            ClassSetBinaryOpKind.SymmetricDifference -> writeStr("~~")
        }
    }

    private fun fmtClassPerl(ast: ClassPerl): Result<Unit> {
        return when (ast.kind) {
            ClassPerlKind.Digit -> if (ast.negated) writeStr("\\D") else writeStr("\\d")
            ClassPerlKind.Space -> if (ast.negated) writeStr("\\S") else writeStr("\\s")
            ClassPerlKind.Word -> if (ast.negated) writeStr("\\W") else writeStr("\\w")
        }
    }

    private fun fmtClassAscii(ast: ClassAscii): Result<Unit> {
        return when (ast.kind) {
            ClassAsciiKind.Alnum -> if (ast.negated) writeStr("[:^alnum:]") else writeStr("[:alnum:]")
            ClassAsciiKind.Alpha -> if (ast.negated) writeStr("[:^alpha:]") else writeStr("[:alpha:]")
            ClassAsciiKind.Ascii -> if (ast.negated) writeStr("[:^ascii:]") else writeStr("[:ascii:]")
            ClassAsciiKind.Blank -> if (ast.negated) writeStr("[:^blank:]") else writeStr("[:blank:]")
            ClassAsciiKind.Cntrl -> if (ast.negated) writeStr("[:^cntrl:]") else writeStr("[:cntrl:]")
            ClassAsciiKind.Digit -> if (ast.negated) writeStr("[:^digit:]") else writeStr("[:digit:]")
            ClassAsciiKind.Graph -> if (ast.negated) writeStr("[:^graph:]") else writeStr("[:graph:]")
            ClassAsciiKind.Lower -> if (ast.negated) writeStr("[:^lower:]") else writeStr("[:lower:]")
            ClassAsciiKind.Print -> if (ast.negated) writeStr("[:^print:]") else writeStr("[:print:]")
            ClassAsciiKind.Punct -> if (ast.negated) writeStr("[:^punct:]") else writeStr("[:punct:]")
            ClassAsciiKind.Space -> if (ast.negated) writeStr("[:^space:]") else writeStr("[:space:]")
            ClassAsciiKind.Upper -> if (ast.negated) writeStr("[:^upper:]") else writeStr("[:upper:]")
            ClassAsciiKind.Word -> if (ast.negated) writeStr("[:^word:]") else writeStr("[:word:]")
            ClassAsciiKind.Xdigit -> if (ast.negated) writeStr("[:^xdigit:]") else writeStr("[:xdigit:]")
        }
    }

    private fun fmtClassUnicode(ast: ClassUnicode): Result<Unit> {
        val r0 = if (ast.negated) writeStr("\\P") else writeStr("\\p")
        if (r0.isFailure) return r0
        return when (val k = ast.kind) {
            is ClassUnicodeKind.OneLetter -> writeChar(k.value)
            is ClassUnicodeKind.Named -> writeStr("{${k.value}}")
            is ClassUnicodeKind.NamedValue -> when (k.op) {
                ClassUnicodeOpKind.Equal -> writeStr("{${k.name}=${k.value}}")
                ClassUnicodeOpKind.Colon -> writeStr("{${k.name}:${k.value}}")
                ClassUnicodeOpKind.NotEqual -> writeStr("{${k.name}!=${k.value}}")
            }
        }
    }
}

private fun hexFixed(value: Int, width: Int): String {
    return value.toString(16).uppercase().padStart(width, '0')
}
