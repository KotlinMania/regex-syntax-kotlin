// port-lint: tests ast/parse.rs
package io.github.kotlinmania.regexsyntax.ast.parse

import io.github.kotlinmania.regexsyntax.ast.Alternation
import io.github.kotlinmania.regexsyntax.ast.Assertion
import io.github.kotlinmania.regexsyntax.ast.AssertionKind
import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.CaptureName
import io.github.kotlinmania.regexsyntax.ast.ClassAscii
import io.github.kotlinmania.regexsyntax.ast.ClassAsciiKind
import io.github.kotlinmania.regexsyntax.ast.ClassBracketed
import io.github.kotlinmania.regexsyntax.ast.ClassPerl
import io.github.kotlinmania.regexsyntax.ast.ClassPerlKind
import io.github.kotlinmania.regexsyntax.ast.ClassSet
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOp
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOpKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetItem
import io.github.kotlinmania.regexsyntax.ast.ClassSetRange
import io.github.kotlinmania.regexsyntax.ast.ClassSetUnion
import io.github.kotlinmania.regexsyntax.ast.ClassUnicode
import io.github.kotlinmania.regexsyntax.ast.ClassUnicodeKind
import io.github.kotlinmania.regexsyntax.ast.ClassUnicodeOpKind
import io.github.kotlinmania.regexsyntax.ast.Comment
import io.github.kotlinmania.regexsyntax.ast.Concat
import io.github.kotlinmania.regexsyntax.ast.Error
import io.github.kotlinmania.regexsyntax.ast.ErrorKind
import io.github.kotlinmania.regexsyntax.ast.Flag
import io.github.kotlinmania.regexsyntax.ast.Flags
import io.github.kotlinmania.regexsyntax.ast.FlagsItem
import io.github.kotlinmania.regexsyntax.ast.FlagsItemKind
import io.github.kotlinmania.regexsyntax.ast.Group
import io.github.kotlinmania.regexsyntax.ast.GroupKind
import io.github.kotlinmania.regexsyntax.ast.HexLiteralKind
import io.github.kotlinmania.regexsyntax.ast.Literal
import io.github.kotlinmania.regexsyntax.ast.LiteralKind
import io.github.kotlinmania.regexsyntax.ast.Position
import io.github.kotlinmania.regexsyntax.ast.Repetition
import io.github.kotlinmania.regexsyntax.ast.RepetitionKind
import io.github.kotlinmania.regexsyntax.ast.RepetitionOp
import io.github.kotlinmania.regexsyntax.ast.RepetitionRange
import io.github.kotlinmania.regexsyntax.ast.SetFlags
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.SpecialLiteralKind
import io.github.kotlinmania.regexsyntax.scalarFromU32
import io.github.kotlinmania.regexsyntax.scalarLenUtf8
import io.github.kotlinmania.regexsyntax.unwrap
import io.github.kotlinmania.regexsyntax.unwrapErr
import io.github.kotlinmania.rustlang.core.result.Result

// This module provides a regular expression parser.

// We create these errors to compare with real ast.Errors in the tests.
// We define equality between TestError and ast.Error to disregard the
// pattern string in ast.Error, which is annoying to provide in tests.
public open class TestError(
    public var span: Span,
    public var kind: ErrorKind,
) {
    public open fun eq(other: Error): Boolean = this.span == other.span && this.kind == other.kind
}

public fun Error.eq(other: TestError): Boolean = this.span == other.span && this.kind == other.kind

public fun s(str: String): String = str

internal fun parser(pattern: String): ParserI = ParserI.new(Parser.new(), pattern)

internal fun parserOctal(pattern: String): ParserI {
    val parser = ParserBuilder.new().octal(true).build()
    return ParserI.new(parser, pattern)
}

internal fun parserEmptyMinRange(pattern: String): ParserI {
    val parser = ParserBuilder.new().emptyMinRange(true).build()
    return ParserI.new(parser, pattern)
}

internal fun parserNestLimit(pattern: String, nestLimit: UInt): ParserI {
    val p = ParserBuilder.new().nestLimit(nestLimit).build()
    return ParserI.new(p, pattern)
}

internal fun parserIgnoreWhitespace(pattern: String): ParserI {
    val p = ParserBuilder.new().ignoreWhitespace(true).build()
    return ParserI.new(p, pattern)
}

/**
 * Short alias for creating a new span.
 */
public fun nspan(start: Position, end: Position): Span = Span.new(start, end)

/**
 * Short alias for creating a new position.
 */
public fun npos(offset: Int, line: Int, column: Int): Position = Position.new(offset, line, column)

/**
 * Create a new span from the given offset range. This assumes a single
 * line and sets the columns based on the offsets. i.e., This only works
 * out of the box for ASCII, which is fine for most tests.
 */
public fun span(range: IntRange): Span {
    val rStart = range.first
    val rEnd = range.last + 1
    val start = Position.new(rStart, 1, rStart + 1)
    val end = Position.new(rEnd, 1, rEnd + 1)
    return Span.new(start, end)
}

/**
 * Create a new span for the corresponding byte range in the given string.
 */
public fun spanRange(subject: String, range: IntRange): Span {
    val bytes = subject.encodeToByteArray()
    fun posAt(byteOffset: Int): Position {
        val prefix = bytes.copyOfRange(0, byteOffset).decodeToString()
        val line = 1 + prefix.count { it == '\n' }
        val lastNl = prefix.lastIndexOf('\n')
        val tail = if (lastNl >= 0) prefix.substring(lastNl + 1) else prefix
        var col = 1
        var idx = 0
        while (idx < tail.length) {
            val step = if (tail[idx].isHighSurrogate() && idx + 1 < tail.length && tail[idx + 1].isLowSurrogate()) 2 else 1
            col += 1
            idx += step
        }
        return Position(offset = byteOffset, line = line, column = col)
    }
    return Span.new(posAt(range.first), posAt(range.last + 1))
}

/**
 * Create a verbatim literal starting at the given position.
 */
public fun lit(c: Char, start: Int): Ast = litWith(c, span(start until start + scalarLenUtf8(c.code.toUInt())))

/**
 * Create a meta literal starting at the given position.
 */
public fun metaLit(c: Char, span: Span): Ast = Ast.literal(Literal(span = span, kind = LiteralKind.Meta, c = c.code.toUInt()))

/**
 * Create a verbatim literal with the given span.
 */
public fun litWith(c: Char, span: Span): Ast = Ast.literal(Literal(span = span, kind = LiteralKind.Verbatim, c = c.code.toUInt()))

/**
 * Create a concatenation with the given range.
 */
public fun concat(range: IntRange, asts: List<Ast>): Ast = concatWith(span(range), asts)

/**
 * Create a concatenation with the given span.
 */
public fun concatWith(span: Span, asts: List<Ast>): Ast = Ast.concat(Concat(span = span, asts = asts))

/**
 * Create an alternation with the given span.
 */
public fun alt(range: IntRange, asts: List<Ast>): Ast = Ast.alternation(Alternation(span = span(range), asts = asts))

/**
 * Create a capturing group with the given span.
 */
public fun group(range: IntRange, index: UInt, ast: Ast): Ast = Ast.group(Group(span = span(range), kind = GroupKind.CaptureIndex(index), ast = ast))

/**
 * Create an ast.SetFlags.
 *
 * The given pattern should be the full pattern string. The range given
 * should correspond to the byte offsets where the flag set occurs.
 *
 * If negated is true, then the set is interpreted as beginning with a
 * negation.
 */
public fun flagSet(pat: String, range: IntRange, flag: Flag, negated: Boolean): Ast {
    val rStart = range.first
    val rEnd = range.last + 1
    val items = mutableListOf(FlagsItem(span = spanRange(pat, (rEnd - 2) until (rEnd - 1)), kind = FlagsItemKind.Flag(flag)))
    if (negated) {
        items.add(0, FlagsItem(span = spanRange(pat, (rStart + 2) until (rEnd - 2)), kind = FlagsItemKind.Negation))
    }
    return Ast.flags(SetFlags(span = spanRange(pat, range), flags = Flags(span = spanRange(pat, (rStart + 2) until (rEnd - 1)), items = items)))
}

@kotlin.test.Test
public fun parseNestLimit() {
    when (val matchVal = Pair(parserNestLimit("", 0u).parse(), Result.Ok(Ast.empty(span(0 until 0))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parserNestLimit("a", 0u).parse(), Result.Ok(lit('a', 0)))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parserNestLimit("a+", 0u).parse().unwrapErr(), TestError(span = span(0 until 2), kind = ErrorKind.NestLimitExceeded(0u)))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!leftVal2.eq(rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parserNestLimit("a+", 1u).parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 2), op = RepetitionOp(span = span(1 until 2), kind = RepetitionKind.OneOrMore), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parserNestLimit("(a)+", 1u).parse().unwrapErr(), TestError(span = span(0 until 3), kind = ErrorKind.NestLimitExceeded(1u)))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!leftVal4.eq(rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parserNestLimit("a+*", 1u).parse().unwrapErr(), TestError(span = span(0 until 2), kind = ErrorKind.NestLimitExceeded(1u)))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!leftVal5.eq(rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parserNestLimit("a+*", 2u).parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 3), op = RepetitionOp(span = span(2 until 3), kind = RepetitionKind.ZeroOrMore), greedy = true, ast = Ast.repetition(Repetition(span = span(0 until 2), op = RepetitionOp(span = span(1 until 2), kind = RepetitionKind.OneOrMore), greedy = true, ast = lit('a', 0)))))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parserNestLimit("ab", 0u).parse().unwrapErr(), TestError(span = span(0 until 2), kind = ErrorKind.NestLimitExceeded(0u)))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!leftVal7.eq(rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parserNestLimit("ab", 1u).parse(), Result.Ok(concat(0 until 2, mutableListOf(lit('a', 0), lit('b', 1)))))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!(leftVal8 == rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parserNestLimit("abc", 1u).parse(), Result.Ok(concat(0 until 3, mutableListOf(lit('a', 0), lit('b', 1), lit('c', 2)))))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!(leftVal9 == rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parserNestLimit("a|b", 0u).parse().unwrapErr(), TestError(span = span(0 until 3), kind = ErrorKind.NestLimitExceeded(0u)))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!leftVal10.eq(rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parserNestLimit("a|b", 1u).parse(), Result.Ok(alt(0 until 3, mutableListOf(lit('a', 0), lit('b', 2)))))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!(leftVal11 == rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parserNestLimit("a|b|c", 1u).parse(), Result.Ok(alt(0 until 5, mutableListOf(lit('a', 0), lit('b', 2), lit('c', 4)))))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!(leftVal12 == rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
    when (val matchVal13 = Pair(parserNestLimit("[a]", 0u).parse().unwrapErr(), TestError(span = span(0 until 3), kind = ErrorKind.NestLimitExceeded(0u)))) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!leftVal13.eq(rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(parserNestLimit("[a]", 1u).parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 3), negated = false, kind = ClassSet.Item(ClassSetItem.Literal(Literal(span = span(1 until 2), kind = LiteralKind.Verbatim, c = 'a'.code.toUInt())))))))) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!(leftVal14 == rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
    when (val matchVal15 = Pair(parserNestLimit("[ab]", 1u).parse().unwrapErr(), TestError(span = span(1 until 3), kind = ErrorKind.NestLimitExceeded(1u)))) {
        else -> {
            val leftVal15 = matchVal15.first
            val rightVal15 = matchVal15.second
            if (!leftVal15.eq(rightVal15)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal15`\nright: `$rightVal15`\n\n")
            }
        }
    }
    when (val matchVal16 = Pair(parserNestLimit("[ab[cd]]", 2u).parse().unwrapErr(), TestError(span = span(3 until 7), kind = ErrorKind.NestLimitExceeded(2u)))) {
        else -> {
            val leftVal16 = matchVal16.first
            val rightVal16 = matchVal16.second
            if (!leftVal16.eq(rightVal16)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal16`\nright: `$rightVal16`\n\n")
            }
        }
    }
    when (val matchVal17 = Pair(parserNestLimit("[ab[cd]]", 3u).parse().unwrapErr(), TestError(span = span(4 until 6), kind = ErrorKind.NestLimitExceeded(3u)))) {
        else -> {
            val leftVal17 = matchVal17.first
            val rightVal17 = matchVal17.second
            if (!leftVal17.eq(rightVal17)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal17`\nright: `$rightVal17`\n\n")
            }
        }
    }
    when (val matchVal18 = Pair(parserNestLimit("[a--b]", 1u).parse().unwrapErr(), TestError(span = span(1 until 5), kind = ErrorKind.NestLimitExceeded(1u)))) {
        else -> {
            val leftVal18 = matchVal18.first
            val rightVal18 = matchVal18.second
            if (!leftVal18.eq(rightVal18)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal18`\nright: `$rightVal18`\n\n")
            }
        }
    }
    when (val matchVal19 = Pair(parserNestLimit("[a--bc]", 2u).parse().unwrapErr(), TestError(span = span(4 until 6), kind = ErrorKind.NestLimitExceeded(2u)))) {
        else -> {
            val leftVal19 = matchVal19.first
            val rightVal19 = matchVal19.second
            if (!leftVal19.eq(rightVal19)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal19`\nright: `$rightVal19`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseComments() {
    val pat = "(?x)\n# This is comment 1.\nfoo # This is comment 2.\n  # This is comment 3.\nbar\n# This is comment 4."
    val astc = parser(pat).parseWithComments().unwrap()
    when (val matchVal = Pair(astc.ast, concatWith(spanRange(pat, 0 until pat.encodeToByteArray().size), mutableListOf(flagSet(pat, 0 until 4, Flag.IgnoreWhitespace, false), litWith('f', spanRange(pat, 26 until 27)), litWith('o', spanRange(pat, 27 until 28)), litWith('o', spanRange(pat, 28 until 29)), litWith('b', spanRange(pat, 74 until 75)), litWith('a', spanRange(pat, 75 until 76)), litWith('r', spanRange(pat, 76 until 77)))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(astc.comments, mutableListOf(Comment(span = spanRange(pat, 5 until 26), comment = s(" This is comment 1.")), Comment(span = spanRange(pat, 30 until 51), comment = s(" This is comment 2.")), Comment(span = spanRange(pat, 53 until 74), comment = s(" This is comment 3.")), Comment(span = spanRange(pat, 78 until 98), comment = s(" This is comment 4."))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseHolistic() {
    when (val matchVal = Pair(parser("]").parse(), Result.Ok(lit(']', 0)))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("\\\\\\.\\+\\*\\?\\(\\)\\|\\[\\]\\{\\}\\^\\\$\\#\\&\\-\\~").parse(), Result.Ok(concat(0 until 36, mutableListOf(metaLit('\\', span(0 until 2)), metaLit('.', span(2 until 4)), metaLit('+', span(4 until 6)), metaLit('*', span(6 until 8)), metaLit('?', span(8 until 10)), metaLit('(', span(10 until 12)), metaLit(')', span(12 until 14)), metaLit('|', span(14 until 16)), metaLit('[', span(16 until 18)), metaLit(']', span(18 until 20)), metaLit('{', span(20 until 22)), metaLit('}', span(22 until 24)), metaLit('^', span(24 until 26)), metaLit('$', span(26 until 28)), metaLit('#', span(28 until 30)), metaLit('&', span(30 until 32)), metaLit('-', span(32 until 34)), metaLit('~', span(34 until 36))))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseIgnoreWhitespace() {
    // Test that basic whitespace insensitivity works.
    val pat = "(?x)a b"
    when (val matchVal = Pair(parser(pat).parse(), Result.Ok(concatWith(nspan(npos(0, 1, 1), npos(7, 1, 8)), mutableListOf(flagSet(pat, 0 until 4, Flag.IgnoreWhitespace, false), litWith('a', nspan(npos(4, 1, 5), npos(5, 1, 6))), litWith('b', nspan(npos(6, 1, 7), npos(7, 1, 8)))))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    // Test that we can toggle whitespace insensitivity.
    val pat1 = "(?x)a b(?-x)a b"
    when (val matchVal1 = Pair(parser(pat1).parse(), Result.Ok(concatWith(nspan(npos(0, 1, 1), npos(15, 1, 16)), mutableListOf(flagSet(pat1, 0 until 4, Flag.IgnoreWhitespace, false), litWith('a', nspan(npos(4, 1, 5), npos(5, 1, 6))), litWith('b', nspan(npos(6, 1, 7), npos(7, 1, 8))), flagSet(pat1, 7 until 12, Flag.IgnoreWhitespace, true), litWith('a', nspan(npos(12, 1, 13), npos(13, 1, 14))), litWith(' ', nspan(npos(13, 1, 14), npos(14, 1, 15))), litWith('b', nspan(npos(14, 1, 15), npos(15, 1, 16)))))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    // Test that nesting whitespace insensitive flags works.
    val pat2 = "a (?x:a )a "
    when (val matchVal2 = Pair(parser(pat2).parse(), Result.Ok(concatWith(spanRange(pat2, 0 until 11), mutableListOf(litWith('a', spanRange(pat2, 0 until 1)), litWith(' ', spanRange(pat2, 1 until 2)), Ast.group(Group(span = spanRange(pat2, 2 until 9), kind = GroupKind.NonCapturing(Flags(span = spanRange(pat2, 4 until 5), items = mutableListOf(FlagsItem(span = spanRange(pat2, 4 until 5), kind = FlagsItemKind.Flag(Flag.IgnoreWhitespace))))), ast = litWith('a', spanRange(pat2, 6 until 7)))), litWith('a', spanRange(pat2, 9 until 10)), litWith(' ', spanRange(pat2, 10 until 11))))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    // Test that whitespace after an opening paren is insignificant.
    val pat3 = "(?x)( ?P<foo> a )"
    when (val matchVal3 = Pair(parser(pat3).parse(), Result.Ok(concatWith(spanRange(pat3, 0 until pat3.encodeToByteArray().size), mutableListOf(flagSet(pat3, 0 until 4, Flag.IgnoreWhitespace, false), Ast.group(Group(span = spanRange(pat3, 4 until pat3.encodeToByteArray().size), kind = GroupKind.CaptureName(startsWithP = true, name = CaptureName(span = spanRange(pat3, 9 until 12), name = s("foo"), index = 1u)), ast = litWith('a', spanRange(pat3, 14 until 15))))))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    val pat4 = "(?x)(  a )"
    when (val matchVal4 = Pair(parser(pat4).parse(), Result.Ok(concatWith(spanRange(pat4, 0 until pat4.encodeToByteArray().size), mutableListOf(flagSet(pat4, 0 until 4, Flag.IgnoreWhitespace, false), Ast.group(Group(span = spanRange(pat4, 4 until pat4.encodeToByteArray().size), kind = GroupKind.CaptureIndex(1u), ast = litWith('a', spanRange(pat4, 7 until 8))))))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    val pat5 = "(?x)(  ?:  a )"
    when (val matchVal5 = Pair(parser(pat5).parse(), Result.Ok(concatWith(spanRange(pat5, 0 until pat5.encodeToByteArray().size), mutableListOf(flagSet(pat5, 0 until 4, Flag.IgnoreWhitespace, false), Ast.group(Group(span = spanRange(pat5, 4 until pat5.encodeToByteArray().size), kind = GroupKind.NonCapturing(Flags(span = spanRange(pat5, 8 until 8), items = mutableListOf())), ast = litWith('a', spanRange(pat5, 11 until 12))))))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    val pat6 = "(?x)\\x { 53 }"
    when (val matchVal6 = Pair(parser(pat6).parse(), Result.Ok(concatWith(spanRange(pat6, 0 until pat6.encodeToByteArray().size), mutableListOf(flagSet(pat6, 0 until 4, Flag.IgnoreWhitespace, false), Ast.literal(Literal(span = span(4 until 13), kind = LiteralKind.HexBrace(HexLiteralKind.X), c = 'S'.code.toUInt()))))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    // Test that whitespace after an escape is OK.
    val pat7 = "(?x)\\ "
    when (val matchVal7 = Pair(parser(pat7).parse(), Result.Ok(concatWith(spanRange(pat7, 0 until pat7.encodeToByteArray().size), mutableListOf(flagSet(pat7, 0 until 4, Flag.IgnoreWhitespace, false), Ast.literal(Literal(span = spanRange(pat7, 4 until 6), kind = LiteralKind.Superfluous, c = ' '.code.toUInt()))))))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseNewlines() {
    val pat = ".\n."
    when (val matchVal = Pair(parser(pat).parse(), Result.Ok(concatWith(spanRange(pat, 0 until 3), mutableListOf(Ast.dot(spanRange(pat, 0 until 1)), litWith('\n', spanRange(pat, 1 until 2)), Ast.dot(spanRange(pat, 2 until 3))))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    val pat1 = "foobar\nbaz\nquux\n"
    when (val matchVal1 = Pair(parser(pat1).parse(), Result.Ok(concatWith(spanRange(pat1, 0 until pat1.encodeToByteArray().size), mutableListOf(litWith('f', nspan(npos(0, 1, 1), npos(1, 1, 2))), litWith('o', nspan(npos(1, 1, 2), npos(2, 1, 3))), litWith('o', nspan(npos(2, 1, 3), npos(3, 1, 4))), litWith('b', nspan(npos(3, 1, 4), npos(4, 1, 5))), litWith('a', nspan(npos(4, 1, 5), npos(5, 1, 6))), litWith('r', nspan(npos(5, 1, 6), npos(6, 1, 7))), litWith('\n', nspan(npos(6, 1, 7), npos(7, 2, 1))), litWith('b', nspan(npos(7, 2, 1), npos(8, 2, 2))), litWith('a', nspan(npos(8, 2, 2), npos(9, 2, 3))), litWith('z', nspan(npos(9, 2, 3), npos(10, 2, 4))), litWith('\n', nspan(npos(10, 2, 4), npos(11, 3, 1))), litWith('q', nspan(npos(11, 3, 1), npos(12, 3, 2))), litWith('u', nspan(npos(12, 3, 2), npos(13, 3, 3))), litWith('u', nspan(npos(13, 3, 3), npos(14, 3, 4))), litWith('x', nspan(npos(14, 3, 4), npos(15, 3, 5))), litWith('\n', nspan(npos(15, 3, 5), npos(16, 4, 1)))))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseUncountedRepetition() {
    when (val matchVal = Pair(parser("a*").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 2), op = RepetitionOp(span = span(1 until 2), kind = RepetitionKind.ZeroOrMore), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("a+").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 2), op = RepetitionOp(span = span(1 until 2), kind = RepetitionKind.OneOrMore), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("a?").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 2), op = RepetitionOp(span = span(1 until 2), kind = RepetitionKind.ZeroOrOne), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("a??").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 3), op = RepetitionOp(span = span(1 until 3), kind = RepetitionKind.ZeroOrOne), greedy = false, ast = lit('a', 0)))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("a?").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 2), op = RepetitionOp(span = span(1 until 2), kind = RepetitionKind.ZeroOrOne), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("a?b").parse(), Result.Ok(concat(0 until 3, mutableListOf(Ast.repetition(Repetition(span = span(0 until 2), op = RepetitionOp(span = span(1 until 2), kind = RepetitionKind.ZeroOrOne), greedy = true, ast = lit('a', 0))), lit('b', 2)))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("a??b").parse(), Result.Ok(concat(0 until 4, mutableListOf(Ast.repetition(Repetition(span = span(0 until 3), op = RepetitionOp(span = span(1 until 3), kind = RepetitionKind.ZeroOrOne), greedy = false, ast = lit('a', 0))), lit('b', 3)))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("ab?").parse(), Result.Ok(concat(0 until 3, mutableListOf(lit('a', 0), Ast.repetition(Repetition(span = span(1 until 3), op = RepetitionOp(span = span(2 until 3), kind = RepetitionKind.ZeroOrOne), greedy = true, ast = lit('b', 1)))))))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("(ab)?").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 5), op = RepetitionOp(span = span(4 until 5), kind = RepetitionKind.ZeroOrOne), greedy = true, ast = group(0 until 4, 1u, concat(1 until 3, mutableListOf(lit('a', 1), lit('b', 2))))))))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!(leftVal8 == rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parser("|a?").parse(), Result.Ok(alt(0 until 3, mutableListOf(Ast.empty(span(0 until 0)), Ast.repetition(Repetition(span = span(1 until 3), op = RepetitionOp(span = span(2 until 3), kind = RepetitionKind.ZeroOrOne), greedy = true, ast = lit('a', 1)))))))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!(leftVal9 == rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("*").parse().unwrapErr(), TestError(span = span(0 until 0), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!leftVal10.eq(rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("(?i)*").parse().unwrapErr(), TestError(span = span(4 until 4), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!leftVal11.eq(rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parser("(*)").parse().unwrapErr(), TestError(span = span(1 until 1), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!leftVal12.eq(rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
    when (val matchVal13 = Pair(parser("(?:?)").parse().unwrapErr(), TestError(span = span(3 until 3), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!leftVal13.eq(rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(parser("+").parse().unwrapErr(), TestError(span = span(0 until 0), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!leftVal14.eq(rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
    when (val matchVal15 = Pair(parser("?").parse().unwrapErr(), TestError(span = span(0 until 0), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal15 = matchVal15.first
            val rightVal15 = matchVal15.second
            if (!leftVal15.eq(rightVal15)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal15`\nright: `$rightVal15`\n\n")
            }
        }
    }
    when (val matchVal16 = Pair(parser("(?)").parse().unwrapErr(), TestError(span = span(1 until 1), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal16 = matchVal16.first
            val rightVal16 = matchVal16.second
            if (!leftVal16.eq(rightVal16)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal16`\nright: `$rightVal16`\n\n")
            }
        }
    }
    when (val matchVal17 = Pair(parser("|*").parse().unwrapErr(), TestError(span = span(1 until 1), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal17 = matchVal17.first
            val rightVal17 = matchVal17.second
            if (!leftVal17.eq(rightVal17)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal17`\nright: `$rightVal17`\n\n")
            }
        }
    }
    when (val matchVal18 = Pair(parser("|+").parse().unwrapErr(), TestError(span = span(1 until 1), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal18 = matchVal18.first
            val rightVal18 = matchVal18.second
            if (!leftVal18.eq(rightVal18)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal18`\nright: `$rightVal18`\n\n")
            }
        }
    }
    when (val matchVal19 = Pair(parser("|?").parse().unwrapErr(), TestError(span = span(1 until 1), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal19 = matchVal19.first
            val rightVal19 = matchVal19.second
            if (!leftVal19.eq(rightVal19)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal19`\nright: `$rightVal19`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseCountedRepetition() {
    when (val matchVal = Pair(parser("a{5}").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 4), op = RepetitionOp(span = span(1 until 4), kind = RepetitionKind.Range(RepetitionRange.Exactly(5u))), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("a{5,}").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 5), op = RepetitionOp(span = span(1 until 5), kind = RepetitionKind.Range(RepetitionRange.AtLeast(5u))), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("a{5,9}").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 6), op = RepetitionOp(span = span(1 until 6), kind = RepetitionKind.Range(RepetitionRange.Bounded(5u, 9u))), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("a{5}?").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 5), op = RepetitionOp(span = span(1 until 5), kind = RepetitionKind.Range(RepetitionRange.Exactly(5u))), greedy = false, ast = lit('a', 0)))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("ab{5}").parse(), Result.Ok(concat(0 until 5, mutableListOf(lit('a', 0), Ast.repetition(Repetition(span = span(1 until 5), op = RepetitionOp(span = span(2 until 5), kind = RepetitionKind.Range(RepetitionRange.Exactly(5u))), greedy = true, ast = lit('b', 1)))))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("ab{5}c").parse(), Result.Ok(concat(0 until 6, mutableListOf(lit('a', 0), Ast.repetition(Repetition(span = span(1 until 5), op = RepetitionOp(span = span(2 until 5), kind = RepetitionKind.Range(RepetitionRange.Exactly(5u))), greedy = true, ast = lit('b', 1))), lit('c', 5)))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("a{ 5 }").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 6), op = RepetitionOp(span = span(1 until 6), kind = RepetitionKind.Range(RepetitionRange.Exactly(5u))), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("a{ 5 , 9 }").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 10), op = RepetitionOp(span = span(1 until 10), kind = RepetitionKind.Range(RepetitionRange.Bounded(5u, 9u))), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parserEmptyMinRange("a{,9}").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 5), op = RepetitionOp(span = span(1 until 5), kind = RepetitionKind.Range(RepetitionRange.Bounded(0u, 9u))), greedy = true, ast = lit('a', 0)))))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!(leftVal8 == rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parserIgnoreWhitespace("a{5,9} ?").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 8), op = RepetitionOp(span = span(1 until 8), kind = RepetitionKind.Range(RepetitionRange.Bounded(5u, 9u))), greedy = false, ast = lit('a', 0)))))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!(leftVal9 == rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("\\b{5,9}").parse(), Result.Ok(Ast.repetition(Repetition(span = span(0 until 7), op = RepetitionOp(span = span(2 until 7), kind = RepetitionKind.Range(RepetitionRange.Bounded(5u, 9u))), greedy = true, ast = Ast.assertion(Assertion(span = span(0 until 2), kind = AssertionKind.WordBoundary))))))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!(leftVal10 == rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("(?i){0}").parse().unwrapErr(), TestError(span = span(4 until 4), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!leftVal11.eq(rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parser("(?m){1,1}").parse().unwrapErr(), TestError(span = span(4 until 4), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!leftVal12.eq(rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
    when (val matchVal13 = Pair(parser("a{]}").parse().unwrapErr(), TestError(span = span(2 until 2), kind = ErrorKind.RepetitionCountDecimalEmpty))) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!leftVal13.eq(rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(parser("a{1,]}").parse().unwrapErr(), TestError(span = span(4 until 4), kind = ErrorKind.RepetitionCountDecimalEmpty))) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!leftVal14.eq(rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
    when (val matchVal15 = Pair(parser("a{").parse().unwrapErr(), TestError(span = span(1 until 2), kind = ErrorKind.RepetitionCountUnclosed))) {
        else -> {
            val leftVal15 = matchVal15.first
            val rightVal15 = matchVal15.second
            if (!leftVal15.eq(rightVal15)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal15`\nright: `$rightVal15`\n\n")
            }
        }
    }
    when (val matchVal16 = Pair(parser("a{}").parse().unwrapErr(), TestError(span = span(2 until 2), kind = ErrorKind.RepetitionCountDecimalEmpty))) {
        else -> {
            val leftVal16 = matchVal16.first
            val rightVal16 = matchVal16.second
            if (!leftVal16.eq(rightVal16)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal16`\nright: `$rightVal16`\n\n")
            }
        }
    }
    when (val matchVal17 = Pair(parser("a{a").parse().unwrapErr(), TestError(span = span(2 until 2), kind = ErrorKind.RepetitionCountDecimalEmpty))) {
        else -> {
            val leftVal17 = matchVal17.first
            val rightVal17 = matchVal17.second
            if (!leftVal17.eq(rightVal17)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal17`\nright: `$rightVal17`\n\n")
            }
        }
    }
    when (val matchVal18 = Pair(parser("a{9999999999}").parse().unwrapErr(), TestError(span = span(2 until 12), kind = ErrorKind.DecimalInvalid))) {
        else -> {
            val leftVal18 = matchVal18.first
            val rightVal18 = matchVal18.second
            if (!leftVal18.eq(rightVal18)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal18`\nright: `$rightVal18`\n\n")
            }
        }
    }
    when (val matchVal19 = Pair(parser("a{9").parse().unwrapErr(), TestError(span = span(1 until 3), kind = ErrorKind.RepetitionCountUnclosed))) {
        else -> {
            val leftVal19 = matchVal19.first
            val rightVal19 = matchVal19.second
            if (!leftVal19.eq(rightVal19)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal19`\nright: `$rightVal19`\n\n")
            }
        }
    }
    when (val matchVal20 = Pair(parser("a{9,a").parse().unwrapErr(), TestError(span = span(4 until 4), kind = ErrorKind.RepetitionCountDecimalEmpty))) {
        else -> {
            val leftVal20 = matchVal20.first
            val rightVal20 = matchVal20.second
            if (!leftVal20.eq(rightVal20)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal20`\nright: `$rightVal20`\n\n")
            }
        }
    }
    when (val matchVal21 = Pair(parser("a{9,9999999999}").parse().unwrapErr(), TestError(span = span(4 until 14), kind = ErrorKind.DecimalInvalid))) {
        else -> {
            val leftVal21 = matchVal21.first
            val rightVal21 = matchVal21.second
            if (!leftVal21.eq(rightVal21)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal21`\nright: `$rightVal21`\n\n")
            }
        }
    }
    when (val matchVal22 = Pair(parser("a{9,").parse().unwrapErr(), TestError(span = span(1 until 4), kind = ErrorKind.RepetitionCountUnclosed))) {
        else -> {
            val leftVal22 = matchVal22.first
            val rightVal22 = matchVal22.second
            if (!leftVal22.eq(rightVal22)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal22`\nright: `$rightVal22`\n\n")
            }
        }
    }
    when (val matchVal23 = Pair(parser("a{9,11").parse().unwrapErr(), TestError(span = span(1 until 6), kind = ErrorKind.RepetitionCountUnclosed))) {
        else -> {
            val leftVal23 = matchVal23.first
            val rightVal23 = matchVal23.second
            if (!leftVal23.eq(rightVal23)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal23`\nright: `$rightVal23`\n\n")
            }
        }
    }
    when (val matchVal24 = Pair(parser("a{2,1}").parse().unwrapErr(), TestError(span = span(1 until 6), kind = ErrorKind.RepetitionCountInvalid))) {
        else -> {
            val leftVal24 = matchVal24.first
            val rightVal24 = matchVal24.second
            if (!leftVal24.eq(rightVal24)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal24`\nright: `$rightVal24`\n\n")
            }
        }
    }
    when (val matchVal25 = Pair(parser("{5}").parse().unwrapErr(), TestError(span = span(0 until 0), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal25 = matchVal25.first
            val rightVal25 = matchVal25.second
            if (!leftVal25.eq(rightVal25)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal25`\nright: `$rightVal25`\n\n")
            }
        }
    }
    when (val matchVal26 = Pair(parser("|{5}").parse().unwrapErr(), TestError(span = span(1 until 1), kind = ErrorKind.RepetitionMissing))) {
        else -> {
            val leftVal26 = matchVal26.first
            val rightVal26 = matchVal26.second
            if (!leftVal26.eq(rightVal26)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal26`\nright: `$rightVal26`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseAlternate() {
    when (val matchVal = Pair(parser("a|b").parse(), Result.Ok(Ast.alternation(Alternation(span = span(0 until 3), asts = mutableListOf(lit('a', 0), lit('b', 2))))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("(a|b)").parse(), Result.Ok(group(0 until 5, 1u, Ast.alternation(Alternation(span = span(1 until 4), asts = mutableListOf(lit('a', 1), lit('b', 3)))))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("a|b|c").parse(), Result.Ok(Ast.alternation(Alternation(span = span(0 until 5), asts = mutableListOf(lit('a', 0), lit('b', 2), lit('c', 4))))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("ax|by|cz").parse(), Result.Ok(Ast.alternation(Alternation(span = span(0 until 8), asts = mutableListOf(concat(0 until 2, mutableListOf(lit('a', 0), lit('x', 1))), concat(3 until 5, mutableListOf(lit('b', 3), lit('y', 4))), concat(6 until 8, mutableListOf(lit('c', 6), lit('z', 7))))))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("(ax|by|cz)").parse(), Result.Ok(group(0 until 10, 1u, Ast.alternation(Alternation(span = span(1 until 9), asts = mutableListOf(concat(1 until 3, mutableListOf(lit('a', 1), lit('x', 2))), concat(4 until 6, mutableListOf(lit('b', 4), lit('y', 5))), concat(7 until 9, mutableListOf(lit('c', 7), lit('z', 8)))))))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("(ax|(by|(cz)))").parse(), Result.Ok(group(0 until 14, 1u, alt(1 until 13, mutableListOf(concat(1 until 3, mutableListOf(lit('a', 1), lit('x', 2))), group(4 until 13, 2u, alt(5 until 12, mutableListOf(concat(5 until 7, mutableListOf(lit('b', 5), lit('y', 6))), group(8 until 12, 3u, concat(9 until 11, mutableListOf(lit('c', 9), lit('z', 10))))))))))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("|").parse(), Result.Ok(alt(0 until 1, mutableListOf(Ast.empty(span(0 until 0)), Ast.empty(span(1 until 1))))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("||").parse(), Result.Ok(alt(0 until 2, mutableListOf(Ast.empty(span(0 until 0)), Ast.empty(span(1 until 1)), Ast.empty(span(2 until 2))))))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("a|").parse(), Result.Ok(alt(0 until 2, mutableListOf(lit('a', 0), Ast.empty(span(2 until 2))))))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!(leftVal8 == rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parser("|a").parse(), Result.Ok(alt(0 until 2, mutableListOf(Ast.empty(span(0 until 0)), lit('a', 1)))))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!(leftVal9 == rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("(|)").parse(), Result.Ok(group(0 until 3, 1u, alt(1 until 2, mutableListOf(Ast.empty(span(1 until 1)), Ast.empty(span(2 until 2)))))))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!(leftVal10 == rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("(a|)").parse(), Result.Ok(group(0 until 4, 1u, alt(1 until 3, mutableListOf(lit('a', 1), Ast.empty(span(3 until 3)))))))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!(leftVal11 == rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parser("(|a)").parse(), Result.Ok(group(0 until 4, 1u, alt(1 until 3, mutableListOf(Ast.empty(span(1 until 1)), lit('a', 2))))))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!(leftVal12 == rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
    when (val matchVal13 = Pair(parser("a|b)").parse().unwrapErr(), TestError(span = span(3 until 4), kind = ErrorKind.GroupUnopened))) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!leftVal13.eq(rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(parser("(a|b").parse().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.GroupUnclosed))) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!leftVal14.eq(rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseUnsupportedLookaround() {
    when (val matchVal = Pair(parser("(?=a)").parse().unwrapErr(), TestError(span = span(0 until 3), kind = ErrorKind.UnsupportedLookAround))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!leftVal.eq(rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("(?!a)").parse().unwrapErr(), TestError(span = span(0 until 3), kind = ErrorKind.UnsupportedLookAround))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!leftVal1.eq(rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("(?<=a)").parse().unwrapErr(), TestError(span = span(0 until 4), kind = ErrorKind.UnsupportedLookAround))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!leftVal2.eq(rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("(?<!a)").parse().unwrapErr(), TestError(span = span(0 until 4), kind = ErrorKind.UnsupportedLookAround))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!leftVal3.eq(rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseGroup() {
    when (val matchVal = Pair(parser("(?i)").parse(), Result.Ok(Ast.flags(SetFlags(span = span(0 until 4), flags = Flags(span = span(2 until 3), items = mutableListOf(FlagsItem(span = span(2 until 3), kind = FlagsItemKind.Flag(Flag.CaseInsensitive))))))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("(?iU)").parse(), Result.Ok(Ast.flags(SetFlags(span = span(0 until 5), flags = Flags(span = span(2 until 4), items = mutableListOf(FlagsItem(span = span(2 until 3), kind = FlagsItemKind.Flag(Flag.CaseInsensitive)), FlagsItem(span = span(3 until 4), kind = FlagsItemKind.Flag(Flag.SwapGreed))))))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("(?i-U)").parse(), Result.Ok(Ast.flags(SetFlags(span = span(0 until 6), flags = Flags(span = span(2 until 5), items = mutableListOf(FlagsItem(span = span(2 until 3), kind = FlagsItemKind.Flag(Flag.CaseInsensitive)), FlagsItem(span = span(3 until 4), kind = FlagsItemKind.Negation), FlagsItem(span = span(4 until 5), kind = FlagsItemKind.Flag(Flag.SwapGreed))))))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("()").parse(), Result.Ok(Ast.group(Group(span = span(0 until 2), kind = GroupKind.CaptureIndex(1u), ast = Ast.empty(span(1 until 1))))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("(a)").parse(), Result.Ok(Ast.group(Group(span = span(0 until 3), kind = GroupKind.CaptureIndex(1u), ast = lit('a', 1)))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("(())").parse(), Result.Ok(Ast.group(Group(span = span(0 until 4), kind = GroupKind.CaptureIndex(1u), ast = Ast.group(Group(span = span(1 until 3), kind = GroupKind.CaptureIndex(2u), ast = Ast.empty(span(2 until 2))))))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("(?:a)").parse(), Result.Ok(Ast.group(Group(span = span(0 until 5), kind = GroupKind.NonCapturing(Flags(span = span(2 until 2), items = mutableListOf())), ast = lit('a', 3)))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("(?i:a)").parse(), Result.Ok(Ast.group(Group(span = span(0 until 6), kind = GroupKind.NonCapturing(Flags(span = span(2 until 3), items = mutableListOf(FlagsItem(span = span(2 until 3), kind = FlagsItemKind.Flag(Flag.CaseInsensitive))))), ast = lit('a', 4)))))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("(?i-U:a)").parse(), Result.Ok(Ast.group(Group(span = span(0 until 8), kind = GroupKind.NonCapturing(Flags(span = span(2 until 5), items = mutableListOf(FlagsItem(span = span(2 until 3), kind = FlagsItemKind.Flag(Flag.CaseInsensitive)), FlagsItem(span = span(3 until 4), kind = FlagsItemKind.Negation), FlagsItem(span = span(4 until 5), kind = FlagsItemKind.Flag(Flag.SwapGreed))))), ast = lit('a', 6)))))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!(leftVal8 == rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parser("(").parse().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.GroupUnclosed))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!leftVal9.eq(rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("(?").parse().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.GroupUnclosed))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!leftVal10.eq(rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("(?P").parse().unwrapErr(), TestError(span = span(2 until 3), kind = ErrorKind.FlagUnrecognized))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!leftVal11.eq(rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parser("(?P<").parse().unwrapErr(), TestError(span = span(4 until 4), kind = ErrorKind.GroupNameUnexpectedEof))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!leftVal12.eq(rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
    when (val matchVal13 = Pair(parser("(a").parse().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.GroupUnclosed))) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!leftVal13.eq(rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(parser("(()").parse().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.GroupUnclosed))) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!leftVal14.eq(rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
    when (val matchVal15 = Pair(parser(")").parse().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.GroupUnopened))) {
        else -> {
            val leftVal15 = matchVal15.first
            val rightVal15 = matchVal15.second
            if (!leftVal15.eq(rightVal15)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal15`\nright: `$rightVal15`\n\n")
            }
        }
    }
    when (val matchVal16 = Pair(parser("a)").parse().unwrapErr(), TestError(span = span(1 until 2), kind = ErrorKind.GroupUnopened))) {
        else -> {
            val leftVal16 = matchVal16.first
            val rightVal16 = matchVal16.second
            if (!leftVal16.eq(rightVal16)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal16`\nright: `$rightVal16`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseCaptureName() {
    when (val matchVal = Pair(parser("(?<a>z)").parse(), Result.Ok(Ast.group(Group(span = span(0 until 7), kind = GroupKind.CaptureName(startsWithP = false, name = CaptureName(span = span(3 until 4), name = s("a"), index = 1u)), ast = lit('z', 5)))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("(?P<a>z)").parse(), Result.Ok(Ast.group(Group(span = span(0 until 8), kind = GroupKind.CaptureName(startsWithP = true, name = CaptureName(span = span(4 until 5), name = s("a"), index = 1u)), ast = lit('z', 6)))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("(?P<abc>z)").parse(), Result.Ok(Ast.group(Group(span = span(0 until 10), kind = GroupKind.CaptureName(startsWithP = true, name = CaptureName(span = span(4 until 7), name = s("abc"), index = 1u)), ast = lit('z', 8)))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("(?P<a_1>z)").parse(), Result.Ok(Ast.group(Group(span = span(0 until 10), kind = GroupKind.CaptureName(startsWithP = true, name = CaptureName(span = span(4 until 7), name = s("a_1"), index = 1u)), ast = lit('z', 8)))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("(?P<a.1>z)").parse(), Result.Ok(Ast.group(Group(span = span(0 until 10), kind = GroupKind.CaptureName(startsWithP = true, name = CaptureName(span = span(4 until 7), name = s("a.1"), index = 1u)), ast = lit('z', 8)))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("(?P<a[1]>z)").parse(), Result.Ok(Ast.group(Group(span = span(0 until 11), kind = GroupKind.CaptureName(startsWithP = true, name = CaptureName(span = span(4 until 8), name = s("a[1]"), index = 1u)), ast = lit('z', 9)))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("(?P<a¾>)").parse(), Result.Ok(Ast.group(Group(span = Span.new(Position.new(0, 1, 1), Position.new(9, 1, 9)), kind = GroupKind.CaptureName(startsWithP = true, name = CaptureName(span = Span.new(Position.new(4, 1, 5), Position.new(7, 1, 7)), name = s("a¾"), index = 1u)), ast = Ast.empty(Span.new(Position.new(8, 1, 8), Position.new(8, 1, 8)))))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("(?P<名字>)").parse(), Result.Ok(Ast.group(Group(span = Span.new(Position.new(0, 1, 1), Position.new(12, 1, 9)), kind = GroupKind.CaptureName(startsWithP = true, name = CaptureName(span = Span.new(Position.new(4, 1, 5), Position.new(10, 1, 7)), name = s("名字"), index = 1u)), ast = Ast.empty(Span.new(Position.new(11, 1, 8), Position.new(11, 1, 8)))))))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("(?P<").parse().unwrapErr(), TestError(span = span(4 until 4), kind = ErrorKind.GroupNameUnexpectedEof))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!leftVal8.eq(rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parser("(?P<>z)").parse().unwrapErr(), TestError(span = span(4 until 4), kind = ErrorKind.GroupNameEmpty))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!leftVal9.eq(rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("(?P<a").parse().unwrapErr(), TestError(span = span(5 until 5), kind = ErrorKind.GroupNameUnexpectedEof))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!leftVal10.eq(rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("(?P<ab").parse().unwrapErr(), TestError(span = span(6 until 6), kind = ErrorKind.GroupNameUnexpectedEof))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!leftVal11.eq(rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parser("(?P<0a").parse().unwrapErr(), TestError(span = span(4 until 5), kind = ErrorKind.GroupNameInvalid))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!leftVal12.eq(rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
    when (val matchVal13 = Pair(parser("(?P<~").parse().unwrapErr(), TestError(span = span(4 until 5), kind = ErrorKind.GroupNameInvalid))) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!leftVal13.eq(rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(parser("(?P<abc~").parse().unwrapErr(), TestError(span = span(7 until 8), kind = ErrorKind.GroupNameInvalid))) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!leftVal14.eq(rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
    when (val matchVal15 = Pair(parser("(?P<a>y)(?P<a>z)").parse().unwrapErr(), TestError(span = span(12 until 13), kind = ErrorKind.GroupNameDuplicate(original = span(4 until 5))))) {
        else -> {
            val leftVal15 = matchVal15.first
            val rightVal15 = matchVal15.second
            if (!leftVal15.eq(rightVal15)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal15`\nright: `$rightVal15`\n\n")
            }
        }
    }
    when (val matchVal16 = Pair(parser("(?P<5>)").parse().unwrapErr(), TestError(span = span(4 until 5), kind = ErrorKind.GroupNameInvalid))) {
        else -> {
            val leftVal16 = matchVal16.first
            val rightVal16 = matchVal16.second
            if (!leftVal16.eq(rightVal16)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal16`\nright: `$rightVal16`\n\n")
            }
        }
    }
    when (val matchVal17 = Pair(parser("(?P<5a>)").parse().unwrapErr(), TestError(span = span(4 until 5), kind = ErrorKind.GroupNameInvalid))) {
        else -> {
            val leftVal17 = matchVal17.first
            val rightVal17 = matchVal17.second
            if (!leftVal17.eq(rightVal17)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal17`\nright: `$rightVal17`\n\n")
            }
        }
    }
    when (val matchVal18 = Pair(parser("(?P<¾>)").parse().unwrapErr(), TestError(span = Span.new(Position.new(4, 1, 5), Position.new(6, 1, 6)), kind = ErrorKind.GroupNameInvalid))) {
        else -> {
            val leftVal18 = matchVal18.first
            val rightVal18 = matchVal18.second
            if (!leftVal18.eq(rightVal18)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal18`\nright: `$rightVal18`\n\n")
            }
        }
    }
    when (val matchVal19 = Pair(parser("(?P<¾a>)").parse().unwrapErr(), TestError(span = Span.new(Position.new(4, 1, 5), Position.new(6, 1, 6)), kind = ErrorKind.GroupNameInvalid))) {
        else -> {
            val leftVal19 = matchVal19.first
            val rightVal19 = matchVal19.second
            if (!leftVal19.eq(rightVal19)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal19`\nright: `$rightVal19`\n\n")
            }
        }
    }
    when (val matchVal20 = Pair(parser("(?P<☃>)").parse().unwrapErr(), TestError(span = Span.new(Position.new(4, 1, 5), Position.new(7, 1, 6)), kind = ErrorKind.GroupNameInvalid))) {
        else -> {
            val leftVal20 = matchVal20.first
            val rightVal20 = matchVal20.second
            if (!leftVal20.eq(rightVal20)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal20`\nright: `$rightVal20`\n\n")
            }
        }
    }
    when (val matchVal21 = Pair(parser("(?P<a☃>)").parse().unwrapErr(), TestError(span = Span.new(Position.new(5, 1, 6), Position.new(8, 1, 7)), kind = ErrorKind.GroupNameInvalid))) {
        else -> {
            val leftVal21 = matchVal21.first
            val rightVal21 = matchVal21.second
            if (!leftVal21.eq(rightVal21)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal21`\nright: `$rightVal21`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseFlags() {
    when (val matchVal = Pair(parser("i:").parseFlags(), Result.Ok(Flags(span = span(0 until 1), items = mutableListOf(FlagsItem(span = span(0 until 1), kind = FlagsItemKind.Flag(Flag.CaseInsensitive))))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("i)").parseFlags(), Result.Ok(Flags(span = span(0 until 1), items = mutableListOf(FlagsItem(span = span(0 until 1), kind = FlagsItemKind.Flag(Flag.CaseInsensitive))))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("isU:").parseFlags(), Result.Ok(Flags(span = span(0 until 3), items = mutableListOf(FlagsItem(span = span(0 until 1), kind = FlagsItemKind.Flag(Flag.CaseInsensitive)), FlagsItem(span = span(1 until 2), kind = FlagsItemKind.Flag(Flag.DotMatchesNewLine)), FlagsItem(span = span(2 until 3), kind = FlagsItemKind.Flag(Flag.SwapGreed))))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("-isU:").parseFlags(), Result.Ok(Flags(span = span(0 until 4), items = mutableListOf(FlagsItem(span = span(0 until 1), kind = FlagsItemKind.Negation), FlagsItem(span = span(1 until 2), kind = FlagsItemKind.Flag(Flag.CaseInsensitive)), FlagsItem(span = span(2 until 3), kind = FlagsItemKind.Flag(Flag.DotMatchesNewLine)), FlagsItem(span = span(3 until 4), kind = FlagsItemKind.Flag(Flag.SwapGreed))))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("i-sU:").parseFlags(), Result.Ok(Flags(span = span(0 until 4), items = mutableListOf(FlagsItem(span = span(0 until 1), kind = FlagsItemKind.Flag(Flag.CaseInsensitive)), FlagsItem(span = span(1 until 2), kind = FlagsItemKind.Negation), FlagsItem(span = span(2 until 3), kind = FlagsItemKind.Flag(Flag.DotMatchesNewLine)), FlagsItem(span = span(3 until 4), kind = FlagsItemKind.Flag(Flag.SwapGreed))))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("i-sR:").parseFlags(), Result.Ok(Flags(span = span(0 until 4), items = mutableListOf(FlagsItem(span = span(0 until 1), kind = FlagsItemKind.Flag(Flag.CaseInsensitive)), FlagsItem(span = span(1 until 2), kind = FlagsItemKind.Negation), FlagsItem(span = span(2 until 3), kind = FlagsItemKind.Flag(Flag.DotMatchesNewLine)), FlagsItem(span = span(3 until 4), kind = FlagsItemKind.Flag(Flag.CRLF))))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("isU").parseFlags().unwrapErr(), TestError(span = span(3 until 3), kind = ErrorKind.FlagUnexpectedEof))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!leftVal6.eq(rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("isUa:").parseFlags().unwrapErr(), TestError(span = span(3 until 4), kind = ErrorKind.FlagUnrecognized))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!leftVal7.eq(rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("isUi:").parseFlags().unwrapErr(), TestError(span = span(3 until 4), kind = ErrorKind.FlagDuplicate(original = span(0 until 1))))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!leftVal8.eq(rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parser("i-sU-i:").parseFlags().unwrapErr(), TestError(span = span(4 until 5), kind = ErrorKind.FlagRepeatedNegation(original = span(1 until 2))))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!leftVal9.eq(rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("-)").parseFlags().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.FlagDanglingNegation))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!leftVal10.eq(rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("i-)").parseFlags().unwrapErr(), TestError(span = span(1 until 2), kind = ErrorKind.FlagDanglingNegation))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!leftVal11.eq(rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parser("iU-)").parseFlags().unwrapErr(), TestError(span = span(2 until 3), kind = ErrorKind.FlagDanglingNegation))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!leftVal12.eq(rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseFlag() {
    when (val matchVal = Pair(parser("i").parseFlag(), Result.Ok(Flag.CaseInsensitive))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("m").parseFlag(), Result.Ok(Flag.MultiLine))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("s").parseFlag(), Result.Ok(Flag.DotMatchesNewLine))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("U").parseFlag(), Result.Ok(Flag.SwapGreed))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("u").parseFlag(), Result.Ok(Flag.Unicode))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("R").parseFlag(), Result.Ok(Flag.CRLF))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("x").parseFlag(), Result.Ok(Flag.IgnoreWhitespace))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("a").parseFlag().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.FlagUnrecognized))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!leftVal7.eq(rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("☃").parseFlag().unwrapErr(), TestError(span = spanRange("☃", 0 until 3), kind = ErrorKind.FlagUnrecognized))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!leftVal8.eq(rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parsePrimitiveNonEscape() {
    when (val matchVal = Pair(parser(".").parsePrimitive(), Result.Ok(Primitive.Dot(span(0 until 1))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("^").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 1), kind = AssertionKind.StartLine))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("\$").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 1), kind = AssertionKind.EndLine))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("a").parsePrimitive(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 1), kind = LiteralKind.Verbatim, c = 'a'.code.toUInt()))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("|").parsePrimitive(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 1), kind = LiteralKind.Verbatim, c = '|'.code.toUInt()))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("☃").parsePrimitive(), Result.Ok(Primitive.Literal(Literal(span = spanRange("☃", 0 until 3), kind = LiteralKind.Verbatim, c = '\u2603'.code.toUInt()))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseEscape() {
    when (val matchVal = Pair(parser("\\|").parsePrimitive(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 2), kind = LiteralKind.Meta, c = '|'.code.toUInt()))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    val specials = listOf(Triple("\\a", '\u0007', SpecialLiteralKind.Bell), Triple("\\f", '\u000c', SpecialLiteralKind.FormFeed), Triple("\\t", '\t', SpecialLiteralKind.Tab), Triple("\\n", '\n', SpecialLiteralKind.LineFeed), Triple("\\r", '\r', SpecialLiteralKind.CarriageReturn), Triple("\\v", '\u000b', SpecialLiteralKind.VerticalTab))
    for ((pat, c, kind) in specials) {
        when (val matchVal1 = Pair(parser(pat).parsePrimitive(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 2), kind = LiteralKind.Special(kind), c = c.code.toUInt()))))) {
            else -> {
                val leftVal1 = matchVal1.first
                val rightVal1 = matchVal1.second
                if (!(leftVal1 == rightVal1)) {
                    error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
                }
            }
        }
    }
    when (val matchVal2 = Pair(parser("\\A").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 2), kind = AssertionKind.StartText))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("\\z").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 2), kind = AssertionKind.EndText))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("\\b").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 2), kind = AssertionKind.WordBoundary))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("\\b{start}").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 9), kind = AssertionKind.WordBoundaryStart))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("\\b{end}").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 7), kind = AssertionKind.WordBoundaryEnd))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("\\b{start-half}").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 14), kind = AssertionKind.WordBoundaryStartHalf))))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("\\b{end-half}").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 12), kind = AssertionKind.WordBoundaryEndHalf))))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!(leftVal8 == rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parser("\\<").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 2), kind = AssertionKind.WordBoundaryStartAngle))))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!(leftVal9 == rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("\\>").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 2), kind = AssertionKind.WordBoundaryEndAngle))))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!(leftVal10 == rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("\\B").parsePrimitive(), Result.Ok(Primitive.Assertion(Assertion(span = span(0 until 2), kind = AssertionKind.NotWordBoundary))))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!(leftVal11 == rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    // We also support superfluous escapes in most cases now too.
    for (c1 in listOf('!', '@', '%', '"', '\'', '/', ' ')) {
        val pat1 = "\\$c1"
        when (val matchVal12 = Pair(parser(pat1).parsePrimitive(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 2), kind = LiteralKind.Superfluous, c = c1.code.toUInt()))))) {
            else -> {
                val leftVal12 = matchVal12.first
                val rightVal12 = matchVal12.second
                if (!(leftVal12 == rightVal12)) {
                    error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
                }
            }
        }
    }
    when (val matchVal13 = Pair(parser("\\e").parseEscape().unwrapErr(), TestError(span = span(0 until 2), kind = ErrorKind.EscapeUnrecognized))) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!leftVal13.eq(rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(parser("\\y").parseEscape().unwrapErr(), TestError(span = span(0 until 2), kind = ErrorKind.EscapeUnrecognized))) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!leftVal14.eq(rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
    when (val matchVal15 = Pair(parser("\\b{").parseEscape().unwrapErr(), TestError(span = span(0 until 3), kind = ErrorKind.SpecialWordOrRepetitionUnexpectedEof))) {
        else -> {
            val leftVal15 = matchVal15.first
            val rightVal15 = matchVal15.second
            if (!leftVal15.eq(rightVal15)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal15`\nright: `$rightVal15`\n\n")
            }
        }
    }
    when (val matchVal16 = Pair(parserIgnoreWhitespace("\\b{ ").parseEscape().unwrapErr(), TestError(span = span(0 until 4), kind = ErrorKind.SpecialWordOrRepetitionUnexpectedEof))) {
        else -> {
            val leftVal16 = matchVal16.first
            val rightVal16 = matchVal16.second
            if (!leftVal16.eq(rightVal16)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal16`\nright: `$rightVal16`\n\n")
            }
        }
    }
    when (val matchVal17 = Pair(parser("\\b{ ").parse().unwrapErr(), TestError(span = span(2 until 4), kind = ErrorKind.RepetitionCountUnclosed))) {
        else -> {
            val leftVal17 = matchVal17.first
            val rightVal17 = matchVal17.second
            if (!leftVal17.eq(rightVal17)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal17`\nright: `$rightVal17`\n\n")
            }
        }
    }
    when (val matchVal18 = Pair(parser("\\b{foo").parseEscape().unwrapErr(), TestError(span = span(2 until 6), kind = ErrorKind.SpecialWordBoundaryUnclosed))) {
        else -> {
            val leftVal18 = matchVal18.first
            val rightVal18 = matchVal18.second
            if (!leftVal18.eq(rightVal18)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal18`\nright: `$rightVal18`\n\n")
            }
        }
    }
    when (val matchVal19 = Pair(parser("\\b{foo!}").parseEscape().unwrapErr(), TestError(span = span(2 until 6), kind = ErrorKind.SpecialWordBoundaryUnclosed))) {
        else -> {
            val leftVal19 = matchVal19.first
            val rightVal19 = matchVal19.second
            if (!leftVal19.eq(rightVal19)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal19`\nright: `$rightVal19`\n\n")
            }
        }
    }
    when (val matchVal20 = Pair(parser("\\b{foo}").parseEscape().unwrapErr(), TestError(span = span(3 until 6), kind = ErrorKind.SpecialWordBoundaryUnrecognized))) {
        else -> {
            val leftVal20 = matchVal20.first
            val rightVal20 = matchVal20.second
            if (!leftVal20.eq(rightVal20)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal20`\nright: `$rightVal20`\n\n")
            }
        }
    }
    when (val matchVal21 = Pair(parser("\\").parseEscape().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal21 = matchVal21.first
            val rightVal21 = matchVal21.second
            if (!leftVal21.eq(rightVal21)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal21`\nright: `$rightVal21`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseUnsupportedBackreference() {
    when (val matchVal = Pair(parser("\\0").parseEscape().unwrapErr(), TestError(span = span(0 until 2), kind = ErrorKind.UnsupportedBackreference))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!leftVal.eq(rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("\\9").parseEscape().unwrapErr(), TestError(span = span(0 until 2), kind = ErrorKind.UnsupportedBackreference))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!leftVal1.eq(rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseOctal() {
    for (i in 0 until 511) {
        val pat = "\\" + i.toString(8)
        when (val matchVal = Pair(parserOctal(pat).parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until pat.encodeToByteArray().size), kind = LiteralKind.Octal, c = scalarFromU32(i.toUInt())!!))))) {
            else -> {
                val leftVal = matchVal.first
                val rightVal = matchVal.second
                if (!(leftVal == rightVal)) {
                    error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
                }
            }
        }
    }
    when (val matchVal1 = Pair(parserOctal("\\778").parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 3), kind = LiteralKind.Octal, c = '?'.code.toUInt()))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parserOctal("\\7777").parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 4), kind = LiteralKind.Octal, c = '\u01ff'.code.toUInt()))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parserOctal("\\778").parse(), Result.Ok(Ast.concat(Concat(span = span(0 until 4), asts = mutableListOf(Ast.literal(Literal(span = span(0 until 3), kind = LiteralKind.Octal, c = '?'.code.toUInt())), Ast.literal(Literal(span = span(3 until 4), kind = LiteralKind.Verbatim, c = '8'.code.toUInt())))))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parserOctal("\\7777").parse(), Result.Ok(Ast.concat(Concat(span = span(0 until 5), asts = mutableListOf(Ast.literal(Literal(span = span(0 until 4), kind = LiteralKind.Octal, c = '\u01ff'.code.toUInt())), Ast.literal(Literal(span = span(4 until 5), kind = LiteralKind.Verbatim, c = '7'.code.toUInt())))))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parserOctal("\\8").parseEscape().unwrapErr(), TestError(span = span(0 until 2), kind = ErrorKind.EscapeUnrecognized))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!leftVal5.eq(rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseHexTwo() {
    for (i in 0 until 256) {
        val pat = "\\x" + i.toString(16).padStart(2, '0')
        when (val matchVal = Pair(parser(pat).parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until pat.encodeToByteArray().size), kind = LiteralKind.HexFixed(HexLiteralKind.X), c = scalarFromU32(i.toUInt())!!))))) {
            else -> {
                val leftVal = matchVal.first
                val rightVal = matchVal.second
                if (!(leftVal == rightVal)) {
                    error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
                }
            }
        }
    }
    when (val matchVal1 = Pair(parser("\\xF").parseEscape().unwrapErr(), TestError(span = span(3 until 3), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!leftVal1.eq(rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("\\xG").parseEscape().unwrapErr(), TestError(span = span(2 until 3), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!leftVal2.eq(rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("\\xFG").parseEscape().unwrapErr(), TestError(span = span(3 until 4), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!leftVal3.eq(rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseHexFour() {
    for (i in 0 until 65536) {
        val c1 = scalarFromU32(i.toUInt()) ?: continue
        val pat = "\\u" + i.toString(16).padStart(4, '0')
        when (val matchVal1 = Pair(parser(pat).parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until pat.encodeToByteArray().size), kind = LiteralKind.HexFixed(HexLiteralKind.UnicodeShort), c = c1))))) {
            else -> {
                val leftVal = matchVal1.first
                val rightVal = matchVal1.second
                if (!(leftVal == rightVal)) {
                    error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
                }
            }
        }
    }
    when (val matchVal2 = Pair(parser("\\uF").parseEscape().unwrapErr(), TestError(span = span(3 until 3), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal1 = matchVal2.first
            val rightVal1 = matchVal2.second
            if (!leftVal1.eq(rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("\\uG").parseEscape().unwrapErr(), TestError(span = span(2 until 3), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal2 = matchVal3.first
            val rightVal2 = matchVal3.second
            if (!leftVal2.eq(rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("\\uFG").parseEscape().unwrapErr(), TestError(span = span(3 until 4), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal3 = matchVal4.first
            val rightVal3 = matchVal4.second
            if (!leftVal3.eq(rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("\\uFFG").parseEscape().unwrapErr(), TestError(span = span(4 until 5), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal4 = matchVal5.first
            val rightVal4 = matchVal5.second
            if (!leftVal4.eq(rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("\\uFFFG").parseEscape().unwrapErr(), TestError(span = span(5 until 6), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal5 = matchVal6.first
            val rightVal5 = matchVal6.second
            if (!leftVal5.eq(rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("\\uD800").parseEscape().unwrapErr(), TestError(span = span(2 until 6), kind = ErrorKind.EscapeHexInvalid))) {
        else -> {
            val leftVal6 = matchVal7.first
            val rightVal6 = matchVal7.second
            if (!leftVal6.eq(rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseHexEight() {
    for (i in 0 until 65536) {
        val c1 = scalarFromU32(i.toUInt()) ?: continue
        val pat = "\\U" + i.toString(16).padStart(8, '0')
        when (val matchVal1 = Pair(parser(pat).parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until pat.encodeToByteArray().size), kind = LiteralKind.HexFixed(HexLiteralKind.UnicodeLong), c = c1))))) {
            else -> {
                val leftVal = matchVal1.first
                val rightVal = matchVal1.second
                if (!(leftVal == rightVal)) {
                    error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
                }
            }
        }
    }
    when (val matchVal2 = Pair(parser("\\UF").parseEscape().unwrapErr(), TestError(span = span(3 until 3), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal1 = matchVal2.first
            val rightVal1 = matchVal2.second
            if (!leftVal1.eq(rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("\\UG").parseEscape().unwrapErr(), TestError(span = span(2 until 3), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal2 = matchVal3.first
            val rightVal2 = matchVal3.second
            if (!leftVal2.eq(rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("\\UFG").parseEscape().unwrapErr(), TestError(span = span(3 until 4), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal3 = matchVal4.first
            val rightVal3 = matchVal4.second
            if (!leftVal3.eq(rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("\\UFFG").parseEscape().unwrapErr(), TestError(span = span(4 until 5), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal4 = matchVal5.first
            val rightVal4 = matchVal5.second
            if (!leftVal4.eq(rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("\\UFFFG").parseEscape().unwrapErr(), TestError(span = span(5 until 6), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal5 = matchVal6.first
            val rightVal5 = matchVal6.second
            if (!leftVal5.eq(rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("\\UFFFFG").parseEscape().unwrapErr(), TestError(span = span(6 until 7), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal6 = matchVal7.first
            val rightVal6 = matchVal7.second
            if (!leftVal6.eq(rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("\\UFFFFFG").parseEscape().unwrapErr(), TestError(span = span(7 until 8), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal7 = matchVal8.first
            val rightVal7 = matchVal8.second
            if (!leftVal7.eq(rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parser("\\UFFFFFFG").parseEscape().unwrapErr(), TestError(span = span(8 until 9), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal8 = matchVal9.first
            val rightVal8 = matchVal9.second
            if (!leftVal8.eq(rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("\\UFFFFFFFG").parseEscape().unwrapErr(), TestError(span = span(9 until 10), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal9 = matchVal10.first
            val rightVal9 = matchVal10.second
            if (!leftVal9.eq(rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseHexBrace() {
    when (val matchVal = Pair(parser("\\u{26c4}").parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 8), kind = LiteralKind.HexBrace(HexLiteralKind.UnicodeShort), c = '\u26c4'.code.toUInt()))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("\\U{26c4}").parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 8), kind = LiteralKind.HexBrace(HexLiteralKind.UnicodeLong), c = '\u26c4'.code.toUInt()))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("\\x{26c4}").parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 8), kind = LiteralKind.HexBrace(HexLiteralKind.X), c = '\u26c4'.code.toUInt()))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("\\x{26C4}").parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 8), kind = LiteralKind.HexBrace(HexLiteralKind.X), c = '\u26c4'.code.toUInt()))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("\\x{10fFfF}").parseEscape(), Result.Ok(Primitive.Literal(Literal(span = span(0 until 10), kind = LiteralKind.HexBrace(HexLiteralKind.X), c = 0x10ffffu))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("\\x").parseEscape().unwrapErr(), TestError(span = span(2 until 2), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!leftVal5.eq(rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("\\x{").parseEscape().unwrapErr(), TestError(span = span(2 until 3), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!leftVal6.eq(rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("\\x{FF").parseEscape().unwrapErr(), TestError(span = span(2 until 5), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!leftVal7.eq(rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("\\x{}").parseEscape().unwrapErr(), TestError(span = span(2 until 4), kind = ErrorKind.EscapeHexEmpty))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!leftVal8.eq(rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parser("\\x{FGF}").parseEscape().unwrapErr(), TestError(span = span(4 until 5), kind = ErrorKind.EscapeHexInvalidDigit))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!leftVal9.eq(rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("\\x{FFFFFF}").parseEscape().unwrapErr(), TestError(span = span(3 until 9), kind = ErrorKind.EscapeHexInvalid))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!leftVal10.eq(rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("\\x{D800}").parseEscape().unwrapErr(), TestError(span = span(3 until 7), kind = ErrorKind.EscapeHexInvalid))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!leftVal11.eq(rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parser("\\x{FFFFFFFFF}").parseEscape().unwrapErr(), TestError(span = span(3 until 12), kind = ErrorKind.EscapeHexInvalid))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!leftVal12.eq(rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseDecimal() {
    when (val matchVal = Pair(parser("123").parseDecimal(), Result.Ok(123u))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("0").parseDecimal(), Result.Ok(0u))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("01").parseDecimal(), Result.Ok(1u))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("-1").parseDecimal().unwrapErr(), TestError(span = span(0 until 0), kind = ErrorKind.DecimalEmpty))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!leftVal3.eq(rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("").parseDecimal().unwrapErr(), TestError(span = span(0 until 0), kind = ErrorKind.DecimalEmpty))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!leftVal4.eq(rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("9999999999").parseDecimal().unwrapErr(), TestError(span = span(0 until 10), kind = ErrorKind.DecimalInvalid))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!leftVal5.eq(rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseSetClass() {
    fun union(span: Span, items: List<ClassSetItem>): ClassSet = ClassSet.union(ClassSetUnion(span = span, items = items))
    fun intersection(span: Span, lhs: ClassSet, rhs: ClassSet): ClassSet = ClassSet.BinaryOp(ClassSetBinaryOp(span = span, kind = ClassSetBinaryOpKind.Intersection, lhs = lhs, rhs = rhs))
    fun difference(span: Span, lhs: ClassSet, rhs: ClassSet): ClassSet = ClassSet.BinaryOp(ClassSetBinaryOp(span = span, kind = ClassSetBinaryOpKind.Difference, lhs = lhs, rhs = rhs))
    fun symdifference(span: Span, lhs: ClassSet, rhs: ClassSet): ClassSet = ClassSet.BinaryOp(ClassSetBinaryOp(span = span, kind = ClassSetBinaryOpKind.SymmetricDifference, lhs = lhs, rhs = rhs))
    fun itemset(item: ClassSetItem): ClassSet = ClassSet.Item(item)
    fun itemAscii(cls: ClassAscii): ClassSetItem = ClassSetItem.Ascii(cls)
    fun itemUnicode(cls: ClassUnicode): ClassSetItem = ClassSetItem.Unicode(cls)
    fun itemPerl(cls: ClassPerl): ClassSetItem = ClassSetItem.Perl(cls)
    fun itemBracket(cls: ClassBracketed): ClassSetItem = ClassSetItem.Bracketed(cls)
    fun lit(span: Span, c: Char): ClassSetItem = ClassSetItem.Literal(Literal(span = span, kind = LiteralKind.Verbatim, c = c.code.toUInt()))
    fun empty(span: Span): ClassSetItem = ClassSetItem.Empty(span)
    fun range(span: Span, start: Char, end: Char): ClassSetItem {
        val pos1 = span.start.copy(offset = span.start.offset + scalarLenUtf8(start.code.toUInt()), column = span.start.column + 1)
        val pos2 = span.end.copy(offset = span.end.offset - scalarLenUtf8(end.code.toUInt()), column = span.end.column - 1)
        return ClassSetItem.Range(ClassSetRange(span = span, start = Literal(span = span.copy(end = pos1), kind = LiteralKind.Verbatim, c = start.code.toUInt()), end = Literal(span = span.copy(start = pos2), kind = LiteralKind.Verbatim, c = end.code.toUInt())))
    }
    fun alnum(span: Span, negated: Boolean): ClassAscii = ClassAscii(span = span, kind = ClassAsciiKind.Alnum, negated = negated)
    fun lower(span: Span, negated: Boolean): ClassAscii = ClassAscii(span = span, kind = ClassAsciiKind.Lower, negated = negated)
    when (val matchVal = Pair(parser("[[:alnum:]]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 11), negated = false, kind = itemset(itemAscii(alnum(span(1 until 10), false)))))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("[[[:alnum:]]]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 13), negated = false, kind = itemset(itemBracket(ClassBracketed(span = span(1 until 12), negated = false, kind = itemset(itemAscii(alnum(span(2 until 11), false))))))))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("[[:alnum:]&&[:lower:]]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 22), negated = false, kind = intersection(span(1 until 21), itemset(itemAscii(alnum(span(1 until 10), false))), itemset(itemAscii(lower(span(12 until 21), false))))))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("[[:alnum:]--[:lower:]]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 22), negated = false, kind = difference(span(1 until 21), itemset(itemAscii(alnum(span(1 until 10), false))), itemset(itemAscii(lower(span(12 until 21), false))))))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("[[:alnum:]~~[:lower:]]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 22), negated = false, kind = symdifference(span(1 until 21), itemset(itemAscii(alnum(span(1 until 10), false))), itemset(itemAscii(lower(span(12 until 21), false))))))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("[a]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 3), negated = false, kind = itemset(lit(span(1 until 2), 'a'))))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("[a\\]]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 5), negated = false, kind = union(span(1 until 4), mutableListOf(lit(span(1 until 2), 'a'), ClassSetItem.Literal(Literal(span = span(2 until 4), kind = LiteralKind.Meta, c = ']'.code.toUInt()))))))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("[a\\-z]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 6), negated = false, kind = union(span(1 until 5), mutableListOf(lit(span(1 until 2), 'a'), ClassSetItem.Literal(Literal(span = span(2 until 4), kind = LiteralKind.Meta, c = '-'.code.toUInt())), lit(span(4 until 5), 'z')))))))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("[ab]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 4), negated = false, kind = union(span(1 until 3), mutableListOf(lit(span(1 until 2), 'a'), lit(span(2 until 3), 'b')))))))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!(leftVal8 == rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parser("[a-]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 4), negated = false, kind = union(span(1 until 3), mutableListOf(lit(span(1 until 2), 'a'), lit(span(2 until 3), '-')))))))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!(leftVal9 == rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("[-a]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 4), negated = false, kind = union(span(1 until 3), mutableListOf(lit(span(1 until 2), '-'), lit(span(2 until 3), 'a')))))))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!(leftVal10 == rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("[\\pL]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 5), negated = false, kind = itemset(itemUnicode(ClassUnicode(span = span(1 until 4), negated = false, kind = ClassUnicodeKind.OneLetter('L'.code.toUInt()))))))))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!(leftVal11 == rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parser("[\\w]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 4), negated = false, kind = itemset(itemPerl(ClassPerl(span = span(1 until 3), kind = ClassPerlKind.Word, negated = false)))))))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!(leftVal12 == rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
    when (val matchVal13 = Pair(parser("[a\\wz]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 6), negated = false, kind = union(span(1 until 5), mutableListOf(lit(span(1 until 2), 'a'), itemPerl(ClassPerl(span = span(2 until 4), kind = ClassPerlKind.Word, negated = false)), lit(span(4 until 5), 'z')))))))) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!(leftVal13 == rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(parser("[a-z]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 5), negated = false, kind = itemset(range(span(1 until 4), 'a', 'z'))))))) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!(leftVal14 == rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
    when (val matchVal15 = Pair(parser("[a-cx-z]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 8), negated = false, kind = union(span(1 until 7), mutableListOf(range(span(1 until 4), 'a', 'c'), range(span(4 until 7), 'x', 'z')))))))) {
        else -> {
            val leftVal15 = matchVal15.first
            val rightVal15 = matchVal15.second
            if (!(leftVal15 == rightVal15)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal15`\nright: `$rightVal15`\n\n")
            }
        }
    }
    when (val matchVal16 = Pair(parser("[\\w&&a-cx-z]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 12), negated = false, kind = intersection(span(1 until 11), itemset(itemPerl(ClassPerl(span = span(1 until 3), kind = ClassPerlKind.Word, negated = false))), union(span(5 until 11), mutableListOf(range(span(5 until 8), 'a', 'c'), range(span(8 until 11), 'x', 'z'))))))))) {
        else -> {
            val leftVal16 = matchVal16.first
            val rightVal16 = matchVal16.second
            if (!(leftVal16 == rightVal16)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal16`\nright: `$rightVal16`\n\n")
            }
        }
    }
    when (val matchVal17 = Pair(parser("[a-cx-z&&\\w]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 12), negated = false, kind = intersection(span(1 until 11), union(span(1 until 7), mutableListOf(range(span(1 until 4), 'a', 'c'), range(span(4 until 7), 'x', 'z'))), itemset(itemPerl(ClassPerl(span = span(9 until 11), kind = ClassPerlKind.Word, negated = false))))))))) {
        else -> {
            val leftVal17 = matchVal17.first
            val rightVal17 = matchVal17.second
            if (!(leftVal17 == rightVal17)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal17`\nright: `$rightVal17`\n\n")
            }
        }
    }
    when (val matchVal18 = Pair(parser("[a--b--c]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 9), negated = false, kind = difference(span(1 until 8), difference(span(1 until 5), itemset(lit(span(1 until 2), 'a')), itemset(lit(span(4 until 5), 'b'))), itemset(lit(span(7 until 8), 'c')))))))) {
        else -> {
            val leftVal18 = matchVal18.first
            val rightVal18 = matchVal18.second
            if (!(leftVal18 == rightVal18)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal18`\nright: `$rightVal18`\n\n")
            }
        }
    }
    when (val matchVal19 = Pair(parser("[a~~b~~c]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 9), negated = false, kind = symdifference(span(1 until 8), symdifference(span(1 until 5), itemset(lit(span(1 until 2), 'a')), itemset(lit(span(4 until 5), 'b'))), itemset(lit(span(7 until 8), 'c')))))))) {
        else -> {
            val leftVal19 = matchVal19.first
            val rightVal19 = matchVal19.second
            if (!(leftVal19 == rightVal19)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal19`\nright: `$rightVal19`\n\n")
            }
        }
    }
    when (val matchVal20 = Pair(parser("[\\^&&^]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 7), negated = false, kind = intersection(span(1 until 6), itemset(ClassSetItem.Literal(Literal(span = span(1 until 3), kind = LiteralKind.Meta, c = '^'.code.toUInt()))), itemset(lit(span(5 until 6), '^')))))))) {
        else -> {
            val leftVal20 = matchVal20.first
            val rightVal20 = matchVal20.second
            if (!(leftVal20 == rightVal20)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal20`\nright: `$rightVal20`\n\n")
            }
        }
    }
    when (val matchVal21 = Pair(parser("[\\&&&&]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 7), negated = false, kind = intersection(span(1 until 6), itemset(ClassSetItem.Literal(Literal(span = span(1 until 3), kind = LiteralKind.Meta, c = '&'.code.toUInt()))), itemset(lit(span(5 until 6), '&')))))))) {
        else -> {
            val leftVal21 = matchVal21.first
            val rightVal21 = matchVal21.second
            if (!(leftVal21 == rightVal21)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal21`\nright: `$rightVal21`\n\n")
            }
        }
    }
    when (val matchVal22 = Pair(parser("[&&&&]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 6), negated = false, kind = intersection(span(1 until 5), intersection(span(1 until 3), itemset(empty(span(1 until 1))), itemset(empty(span(3 until 3)))), itemset(empty(span(5 until 5))))))))) {
        else -> {
            val leftVal22 = matchVal22.first
            val rightVal22 = matchVal22.second
            if (!(leftVal22 == rightVal22)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal22`\nright: `$rightVal22`\n\n")
            }
        }
    }
    val pat = "[☃-⛄]"
    when (val matchVal23 = Pair(parser(pat).parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = spanRange(pat, 0 until 9), negated = false, kind = itemset(ClassSetItem.Range(ClassSetRange(span = spanRange(pat, 1 until 8), start = Literal(span = spanRange(pat, 1 until 4), kind = LiteralKind.Verbatim, c = '\u2603'.code.toUInt()), end = Literal(span = spanRange(pat, 5 until 8), kind = LiteralKind.Verbatim, c = '\u26c4'.code.toUInt()))))))))) {
        else -> {
            val leftVal23 = matchVal23.first
            val rightVal23 = matchVal23.second
            if (!(leftVal23 == rightVal23)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal23`\nright: `$rightVal23`\n\n")
            }
        }
    }
    when (val matchVal24 = Pair(parser("[]]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 3), negated = false, kind = itemset(lit(span(1 until 2), ']'))))))) {
        else -> {
            val leftVal24 = matchVal24.first
            val rightVal24 = matchVal24.second
            if (!(leftVal24 == rightVal24)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal24`\nright: `$rightVal24`\n\n")
            }
        }
    }
    when (val matchVal25 = Pair(parser("[]\\[]").parse(), Result.Ok(Ast.classBracketed(ClassBracketed(span = span(0 until 5), negated = false, kind = union(span(1 until 4), mutableListOf(lit(span(1 until 2), ']'), ClassSetItem.Literal(Literal(span = span(2 until 4), kind = LiteralKind.Meta, c = '['.code.toUInt()))))))))) {
        else -> {
            val leftVal25 = matchVal25.first
            val rightVal25 = matchVal25.second
            if (!(leftVal25 == rightVal25)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal25`\nright: `$rightVal25`\n\n")
            }
        }
    }
    when (val matchVal26 = Pair(parser("[\\[]]").parse(), Result.Ok(concat(0 until 5, mutableListOf(Ast.classBracketed(ClassBracketed(span = span(0 until 4), negated = false, kind = itemset(ClassSetItem.Literal(Literal(span = span(1 until 3), kind = LiteralKind.Meta, c = '['.code.toUInt()))))), Ast.literal(Literal(span = span(4 until 5), kind = LiteralKind.Verbatim, c = ']'.code.toUInt()))))))) {
        else -> {
            val leftVal26 = matchVal26.first
            val rightVal26 = matchVal26.second
            if (!(leftVal26 == rightVal26)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal26`\nright: `$rightVal26`\n\n")
            }
        }
    }
    when (val matchVal27 = Pair(parser("[").parse().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal27 = matchVal27.first
            val rightVal27 = matchVal27.second
            if (!leftVal27.eq(rightVal27)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal27`\nright: `$rightVal27`\n\n")
            }
        }
    }
    when (val matchVal28 = Pair(parser("[[").parse().unwrapErr(), TestError(span = span(1 until 2), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal28 = matchVal28.first
            val rightVal28 = matchVal28.second
            if (!leftVal28.eq(rightVal28)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal28`\nright: `$rightVal28`\n\n")
            }
        }
    }
    when (val matchVal29 = Pair(parser("[[-]").parse().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal29 = matchVal29.first
            val rightVal29 = matchVal29.second
            if (!leftVal29.eq(rightVal29)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal29`\nright: `$rightVal29`\n\n")
            }
        }
    }
    when (val matchVal30 = Pair(parser("[[[:alnum:]").parse().unwrapErr(), TestError(span = span(1 until 2), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal30 = matchVal30.first
            val rightVal30 = matchVal30.second
            if (!leftVal30.eq(rightVal30)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal30`\nright: `$rightVal30`\n\n")
            }
        }
    }
    when (val matchVal31 = Pair(parser("[\\b]").parse().unwrapErr(), TestError(span = span(1 until 3), kind = ErrorKind.ClassEscapeInvalid))) {
        else -> {
            val leftVal31 = matchVal31.first
            val rightVal31 = matchVal31.second
            if (!leftVal31.eq(rightVal31)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal31`\nright: `$rightVal31`\n\n")
            }
        }
    }
    when (val matchVal32 = Pair(parser("[\\w-a]").parse().unwrapErr(), TestError(span = span(1 until 3), kind = ErrorKind.ClassRangeLiteral))) {
        else -> {
            val leftVal32 = matchVal32.first
            val rightVal32 = matchVal32.second
            if (!leftVal32.eq(rightVal32)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal32`\nright: `$rightVal32`\n\n")
            }
        }
    }
    when (val matchVal33 = Pair(parser("[a-\\w]").parse().unwrapErr(), TestError(span = span(3 until 5), kind = ErrorKind.ClassRangeLiteral))) {
        else -> {
            val leftVal33 = matchVal33.first
            val rightVal33 = matchVal33.second
            if (!leftVal33.eq(rightVal33)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal33`\nright: `$rightVal33`\n\n")
            }
        }
    }
    when (val matchVal34 = Pair(parser("[z-a]").parse().unwrapErr(), TestError(span = span(1 until 4), kind = ErrorKind.ClassRangeInvalid))) {
        else -> {
            val leftVal34 = matchVal34.first
            val rightVal34 = matchVal34.second
            if (!leftVal34.eq(rightVal34)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal34`\nright: `$rightVal34`\n\n")
            }
        }
    }
    when (val matchVal35 = Pair(parserIgnoreWhitespace("[a ").parse().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal35 = matchVal35.first
            val rightVal35 = matchVal35.second
            if (!leftVal35.eq(rightVal35)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal35`\nright: `$rightVal35`\n\n")
            }
        }
    }
    when (val matchVal36 = Pair(parserIgnoreWhitespace("[a- ").parse().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal36 = matchVal36.first
            val rightVal36 = matchVal36.second
            if (!leftVal36.eq(rightVal36)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal36`\nright: `$rightVal36`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseSetClassOpen() {
    when (val matchVal = Pair(parser("[a]").parseSetClassOpen(), run {
        val set = ClassBracketed(span = span(0 until 1), negated = false, kind = ClassSet.union(ClassSetUnion(span = span(1 until 1), items = mutableListOf())))
        val union = ClassSetUnion(span = span(1 until 1), items = mutableListOf())
        Result.Ok(Pair(set, union))
    })) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parserIgnoreWhitespace("[   a]").parseSetClassOpen(), run {
        val set1 = ClassBracketed(span = span(0 until 4), negated = false, kind = ClassSet.union(ClassSetUnion(span = span(4 until 4), items = mutableListOf())))
        val union1 = ClassSetUnion(span = span(4 until 4), items = mutableListOf())
        Result.Ok(Pair(set1, union1))
    })) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("[^a]").parseSetClassOpen(), run {
        val set2 = ClassBracketed(span = span(0 until 2), negated = true, kind = ClassSet.union(ClassSetUnion(span = span(2 until 2), items = mutableListOf())))
        val union2 = ClassSetUnion(span = span(2 until 2), items = mutableListOf())
        Result.Ok(Pair(set2, union2))
    })) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parserIgnoreWhitespace("[ ^ a]").parseSetClassOpen(), run {
        val set3 = ClassBracketed(span = span(0 until 4), negated = true, kind = ClassSet.union(ClassSetUnion(span = span(4 until 4), items = mutableListOf())))
        val union3 = ClassSetUnion(span = span(4 until 4), items = mutableListOf())
        Result.Ok(Pair(set3, union3))
    })) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("[-a]").parseSetClassOpen(), run {
        val set4 = ClassBracketed(span = span(0 until 2), negated = false, kind = ClassSet.union(ClassSetUnion(span = span(1 until 1), items = mutableListOf())))
        val union4 = ClassSetUnion(span = span(1 until 2), items = mutableListOf(ClassSetItem.Literal(Literal(span = span(1 until 2), kind = LiteralKind.Verbatim, c = '-'.code.toUInt()))))
        Result.Ok(Pair(set4, union4))
    })) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parserIgnoreWhitespace("[ - a]").parseSetClassOpen(), run {
        val set5 = ClassBracketed(span = span(0 until 4), negated = false, kind = ClassSet.union(ClassSetUnion(span = span(2 until 2), items = mutableListOf())))
        val union5 = ClassSetUnion(span = span(2 until 3), items = mutableListOf(ClassSetItem.Literal(Literal(span = span(2 until 3), kind = LiteralKind.Verbatim, c = '-'.code.toUInt()))))
        Result.Ok(Pair(set5, union5))
    })) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("[^-a]").parseSetClassOpen(), run {
        val set6 = ClassBracketed(span = span(0 until 3), negated = true, kind = ClassSet.union(ClassSetUnion(span = span(2 until 2), items = mutableListOf())))
        val union6 = ClassSetUnion(span = span(2 until 3), items = mutableListOf(ClassSetItem.Literal(Literal(span = span(2 until 3), kind = LiteralKind.Verbatim, c = '-'.code.toUInt()))))
        Result.Ok(Pair(set6, union6))
    })) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("[--a]").parseSetClassOpen(), run {
        val set7 = ClassBracketed(span = span(0 until 3), negated = false, kind = ClassSet.union(ClassSetUnion(span = span(1 until 1), items = mutableListOf())))
        val union7 = ClassSetUnion(span = span(1 until 3), items = mutableListOf(ClassSetItem.Literal(Literal(span = span(1 until 2), kind = LiteralKind.Verbatim, c = '-'.code.toUInt())), ClassSetItem.Literal(Literal(span = span(2 until 3), kind = LiteralKind.Verbatim, c = '-'.code.toUInt()))))
        Result.Ok(Pair(set7, union7))
    })) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("[]a]").parseSetClassOpen(), run {
        val set8 = ClassBracketed(span = span(0 until 2), negated = false, kind = ClassSet.union(ClassSetUnion(span = span(1 until 1), items = mutableListOf())))
        val union8 = ClassSetUnion(span = span(1 until 2), items = mutableListOf(ClassSetItem.Literal(Literal(span = span(1 until 2), kind = LiteralKind.Verbatim, c = ']'.code.toUInt()))))
        Result.Ok(Pair(set8, union8))
    })) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!(leftVal8 == rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parserIgnoreWhitespace("[ ] a]").parseSetClassOpen(), run {
        val set9 = ClassBracketed(span = span(0 until 4), negated = false, kind = ClassSet.union(ClassSetUnion(span = span(2 until 2), items = mutableListOf())))
        val union9 = ClassSetUnion(span = span(2 until 3), items = mutableListOf(ClassSetItem.Literal(Literal(span = span(2 until 3), kind = LiteralKind.Verbatim, c = ']'.code.toUInt()))))
        Result.Ok(Pair(set9, union9))
    })) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!(leftVal9 == rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("[^]a]").parseSetClassOpen(), run {
        val set10 = ClassBracketed(span = span(0 until 3), negated = true, kind = ClassSet.union(ClassSetUnion(span = span(2 until 2), items = mutableListOf())))
        val union10 = ClassSetUnion(span = span(2 until 3), items = mutableListOf(ClassSetItem.Literal(Literal(span = span(2 until 3), kind = LiteralKind.Verbatim, c = ']'.code.toUInt()))))
        Result.Ok(Pair(set10, union10))
    })) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!(leftVal10 == rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("[-]a]").parseSetClassOpen(), run {
        val set11 = ClassBracketed(span = span(0 until 2), negated = false, kind = ClassSet.union(ClassSetUnion(span = span(1 until 1), items = mutableListOf())))
        val union11 = ClassSetUnion(span = span(1 until 2), items = mutableListOf(ClassSetItem.Literal(Literal(span = span(1 until 2), kind = LiteralKind.Verbatim, c = '-'.code.toUInt()))))
        Result.Ok(Pair(set11, union11))
    })) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!(leftVal11 == rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parser("[").parseSetClassOpen().unwrapErr(), TestError(span = span(0 until 1), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!leftVal12.eq(rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
    when (val matchVal13 = Pair(parserIgnoreWhitespace("[    ").parseSetClassOpen().unwrapErr(), TestError(span = span(0 until 5), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!leftVal13.eq(rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(parser("[^").parseSetClassOpen().unwrapErr(), TestError(span = span(0 until 2), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!leftVal14.eq(rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
    when (val matchVal15 = Pair(parser("[]").parseSetClassOpen().unwrapErr(), TestError(span = span(0 until 2), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal15 = matchVal15.first
            val rightVal15 = matchVal15.second
            if (!leftVal15.eq(rightVal15)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal15`\nright: `$rightVal15`\n\n")
            }
        }
    }
    when (val matchVal16 = Pair(parser("[-").parseSetClassOpen().unwrapErr(), TestError(span = span(0 until 0), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal16 = matchVal16.first
            val rightVal16 = matchVal16.second
            if (!leftVal16.eq(rightVal16)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal16`\nright: `$rightVal16`\n\n")
            }
        }
    }
    when (val matchVal17 = Pair(parser("[--").parseSetClassOpen().unwrapErr(), TestError(span = span(0 until 0), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal17 = matchVal17.first
            val rightVal17 = matchVal17.second
            if (!leftVal17.eq(rightVal17)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal17`\nright: `$rightVal17`\n\n")
            }
        }
    }
    when (val matchVal18 = Pair(parser("(?x)[-#]").parseWithComments().unwrapErr(), TestError(span = span(4 until 4), kind = ErrorKind.ClassUnclosed))) {
        else -> {
            val leftVal18 = matchVal18.first
            val rightVal18 = matchVal18.second
            if (!leftVal18.eq(rightVal18)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal18`\nright: `$rightVal18`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun maybeParseAsciiClass() {
    when (val matchVal = Pair(parser("[:alnum:]").maybeParseAsciiClass(), ClassAscii(span = span(0 until 9), kind = ClassAsciiKind.Alnum, negated = false))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("[:alnum:]A").maybeParseAsciiClass(), ClassAscii(span = span(0 until 9), kind = ClassAsciiKind.Alnum, negated = false))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("[:^alnum:]").maybeParseAsciiClass(), ClassAscii(span = span(0 until 10), kind = ClassAsciiKind.Alnum, negated = true))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    val p = parser("[:")
    when (val matchVal3 = Pair(p.maybeParseAsciiClass(), null)) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(p.offset(), 0)) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    val p1 = parser("[:^")
    when (val matchVal5 = Pair(p1.maybeParseAsciiClass(), null)) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(p1.offset(), 0)) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    val p2 = parser("[^:alnum:]")
    when (val matchVal7 = Pair(p2.maybeParseAsciiClass(), null)) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(p2.offset(), 0)) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!(leftVal8 == rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    val p3 = parser("[:alnnum:]")
    when (val matchVal9 = Pair(p3.maybeParseAsciiClass(), null)) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!(leftVal9 == rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(p3.offset(), 0)) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!(leftVal10 == rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    val p4 = parser("[:alnum]")
    when (val matchVal11 = Pair(p4.maybeParseAsciiClass(), null)) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!(leftVal11 == rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(p4.offset(), 0)) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!(leftVal12 == rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
    val p5 = parser("[:alnum:")
    when (val matchVal13 = Pair(p5.maybeParseAsciiClass(), null)) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!(leftVal13 == rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(p5.offset(), 0)) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!(leftVal14 == rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parseUnicodeClass() {
    when (val matchVal = Pair(parser("\\pN").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 3), negated = false, kind = ClassUnicodeKind.OneLetter('N'.code.toUInt())))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("\\PN").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 3), negated = true, kind = ClassUnicodeKind.OneLetter('N'.code.toUInt())))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("\\p{N}").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 5), negated = false, kind = ClassUnicodeKind.Named(s("N"))))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("\\P{N}").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 5), negated = true, kind = ClassUnicodeKind.Named(s("N"))))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("\\p{Greek}").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 9), negated = false, kind = ClassUnicodeKind.Named(s("Greek"))))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("\\p{scx:Katakana}").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 16), negated = false, kind = ClassUnicodeKind.NamedValue(op = ClassUnicodeOpKind.Colon, name = s("scx"), value = s("Katakana"))))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("\\p{scx=Katakana}").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 16), negated = false, kind = ClassUnicodeKind.NamedValue(op = ClassUnicodeOpKind.Equal, name = s("scx"), value = s("Katakana"))))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("\\p{scx!=Katakana}").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 17), negated = false, kind = ClassUnicodeKind.NamedValue(op = ClassUnicodeOpKind.NotEqual, name = s("scx"), value = s("Katakana"))))))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
    when (val matchVal8 = Pair(parser("\\p{:}").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 5), negated = false, kind = ClassUnicodeKind.NamedValue(op = ClassUnicodeOpKind.Colon, name = s(""), value = s(""))))))) {
        else -> {
            val leftVal8 = matchVal8.first
            val rightVal8 = matchVal8.second
            if (!(leftVal8 == rightVal8)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal8`\nright: `$rightVal8`\n\n")
            }
        }
    }
    when (val matchVal9 = Pair(parser("\\p{=}").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 5), negated = false, kind = ClassUnicodeKind.NamedValue(op = ClassUnicodeOpKind.Equal, name = s(""), value = s(""))))))) {
        else -> {
            val leftVal9 = matchVal9.first
            val rightVal9 = matchVal9.second
            if (!(leftVal9 == rightVal9)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal9`\nright: `$rightVal9`\n\n")
            }
        }
    }
    when (val matchVal10 = Pair(parser("\\p{!=}").parseEscape(), Result.Ok(Primitive.Unicode(ClassUnicode(span = span(0 until 6), negated = false, kind = ClassUnicodeKind.NamedValue(op = ClassUnicodeOpKind.NotEqual, name = s(""), value = s(""))))))) {
        else -> {
            val leftVal10 = matchVal10.first
            val rightVal10 = matchVal10.second
            if (!(leftVal10 == rightVal10)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal10`\nright: `$rightVal10`\n\n")
            }
        }
    }
    when (val matchVal11 = Pair(parser("\\p").parseEscape().unwrapErr(), TestError(span = span(2 until 2), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal11 = matchVal11.first
            val rightVal11 = matchVal11.second
            if (!leftVal11.eq(rightVal11)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal11`\nright: `$rightVal11`\n\n")
            }
        }
    }
    when (val matchVal12 = Pair(parser("\\p{").parseEscape().unwrapErr(), TestError(span = span(3 until 3), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal12 = matchVal12.first
            val rightVal12 = matchVal12.second
            if (!leftVal12.eq(rightVal12)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal12`\nright: `$rightVal12`\n\n")
            }
        }
    }
    when (val matchVal13 = Pair(parser("\\p{N").parseEscape().unwrapErr(), TestError(span = span(4 until 4), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal13 = matchVal13.first
            val rightVal13 = matchVal13.second
            if (!leftVal13.eq(rightVal13)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal13`\nright: `$rightVal13`\n\n")
            }
        }
    }
    when (val matchVal14 = Pair(parser("\\p{Greek").parseEscape().unwrapErr(), TestError(span = span(8 until 8), kind = ErrorKind.EscapeUnexpectedEof))) {
        else -> {
            val leftVal14 = matchVal14.first
            val rightVal14 = matchVal14.second
            if (!leftVal14.eq(rightVal14)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal14`\nright: `$rightVal14`\n\n")
            }
        }
    }
    when (val matchVal15 = Pair(parser("\\pNz").parse(), Result.Ok(Ast.concat(Concat(span = span(0 until 4), asts = mutableListOf(Ast.classUnicode(ClassUnicode(span = span(0 until 3), negated = false, kind = ClassUnicodeKind.OneLetter('N'.code.toUInt()))), Ast.literal(Literal(span = span(3 until 4), kind = LiteralKind.Verbatim, c = 'z'.code.toUInt())))))))) {
        else -> {
            val leftVal15 = matchVal15.first
            val rightVal15 = matchVal15.second
            if (!(leftVal15 == rightVal15)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal15`\nright: `$rightVal15`\n\n")
            }
        }
    }
    when (val matchVal16 = Pair(parser("\\p{Greek}z").parse(), Result.Ok(Ast.concat(Concat(span = span(0 until 10), asts = mutableListOf(Ast.classUnicode(ClassUnicode(span = span(0 until 9), negated = false, kind = ClassUnicodeKind.Named(s("Greek")))), Ast.literal(Literal(span = span(9 until 10), kind = LiteralKind.Verbatim, c = 'z'.code.toUInt())))))))) {
        else -> {
            val leftVal16 = matchVal16.first
            val rightVal16 = matchVal16.second
            if (!(leftVal16 == rightVal16)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal16`\nright: `$rightVal16`\n\n")
            }
        }
    }
    when (val matchVal17 = Pair(parser("\\p\\{").parse().unwrapErr(), TestError(span = span(2 until 3), kind = ErrorKind.UnicodeClassInvalid))) {
        else -> {
            val leftVal17 = matchVal17.first
            val rightVal17 = matchVal17.second
            if (!leftVal17.eq(rightVal17)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal17`\nright: `$rightVal17`\n\n")
            }
        }
    }
    when (val matchVal18 = Pair(parser("\\P\\{").parse().unwrapErr(), TestError(span = span(2 until 3), kind = ErrorKind.UnicodeClassInvalid))) {
        else -> {
            val leftVal18 = matchVal18.first
            val rightVal18 = matchVal18.second
            if (!leftVal18.eq(rightVal18)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal18`\nright: `$rightVal18`\n\n")
            }
        }
    }
}

@kotlin.test.Test
public fun parsePerlClass() {
    when (val matchVal = Pair(parser("\\d").parseEscape(), Result.Ok(Primitive.Perl(ClassPerl(span = span(0 until 2), kind = ClassPerlKind.Digit, negated = false))))) {
        else -> {
            val leftVal = matchVal.first
            val rightVal = matchVal.second
            if (!(leftVal == rightVal)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal`\nright: `$rightVal`\n\n")
            }
        }
    }
    when (val matchVal1 = Pair(parser("\\D").parseEscape(), Result.Ok(Primitive.Perl(ClassPerl(span = span(0 until 2), kind = ClassPerlKind.Digit, negated = true))))) {
        else -> {
            val leftVal1 = matchVal1.first
            val rightVal1 = matchVal1.second
            if (!(leftVal1 == rightVal1)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal1`\nright: `$rightVal1`\n\n")
            }
        }
    }
    when (val matchVal2 = Pair(parser("\\s").parseEscape(), Result.Ok(Primitive.Perl(ClassPerl(span = span(0 until 2), kind = ClassPerlKind.Space, negated = false))))) {
        else -> {
            val leftVal2 = matchVal2.first
            val rightVal2 = matchVal2.second
            if (!(leftVal2 == rightVal2)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal2`\nright: `$rightVal2`\n\n")
            }
        }
    }
    when (val matchVal3 = Pair(parser("\\S").parseEscape(), Result.Ok(Primitive.Perl(ClassPerl(span = span(0 until 2), kind = ClassPerlKind.Space, negated = true))))) {
        else -> {
            val leftVal3 = matchVal3.first
            val rightVal3 = matchVal3.second
            if (!(leftVal3 == rightVal3)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal3`\nright: `$rightVal3`\n\n")
            }
        }
    }
    when (val matchVal4 = Pair(parser("\\w").parseEscape(), Result.Ok(Primitive.Perl(ClassPerl(span = span(0 until 2), kind = ClassPerlKind.Word, negated = false))))) {
        else -> {
            val leftVal4 = matchVal4.first
            val rightVal4 = matchVal4.second
            if (!(leftVal4 == rightVal4)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal4`\nright: `$rightVal4`\n\n")
            }
        }
    }
    when (val matchVal5 = Pair(parser("\\W").parseEscape(), Result.Ok(Primitive.Perl(ClassPerl(span = span(0 until 2), kind = ClassPerlKind.Word, negated = true))))) {
        else -> {
            val leftVal5 = matchVal5.first
            val rightVal5 = matchVal5.second
            if (!(leftVal5 == rightVal5)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal5`\nright: `$rightVal5`\n\n")
            }
        }
    }
    when (val matchVal6 = Pair(parser("\\d").parse(), Result.Ok(Ast.classPerl(ClassPerl(span = span(0 until 2), kind = ClassPerlKind.Digit, negated = false))))) {
        else -> {
            val leftVal6 = matchVal6.first
            val rightVal6 = matchVal6.second
            if (!(leftVal6 == rightVal6)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal6`\nright: `$rightVal6`\n\n")
            }
        }
    }
    when (val matchVal7 = Pair(parser("\\dz").parse(), Result.Ok(Ast.concat(Concat(span = span(0 until 3), asts = mutableListOf(Ast.classPerl(ClassPerl(span = span(0 until 2), kind = ClassPerlKind.Digit, negated = false)), Ast.literal(Literal(span = span(2 until 3), kind = LiteralKind.Verbatim, c = 'z'.code.toUInt())))))))) {
        else -> {
            val leftVal7 = matchVal7.first
            val rightVal7 = matchVal7.second
            if (!(leftVal7 == rightVal7)) {
                error("assertion failed: `(left == right)`\n\nleft:  `$leftVal7`\nright: `$rightVal7`\n\n")
            }
        }
    }
}

// This tests a bug fix where the nest limit checker wasn't decrementing
// its depth during post-traversal, which causes long regexes to trip
// the default limit too aggressively.
@kotlin.test.Test
public fun regression454NestTooBig() {
    val pattern = "\n        2(?:\n          [45]\\d{3}|\n          7(?:\n            1[0-267]|\n            2[0-289]|\n            3[0-29]|\n            4[01]|\n            5[1-3]|\n            6[013]|\n            7[0178]|\n            91\n          )|\n          8(?:\n            0[125]|\n            [139][1-6]|\n            2[0157-9]|\n            41|\n            6[1-35]|\n            7[1-5]|\n            8[1-8]|\n            90\n          )|\n          9(?:\n            0[0-2]|\n            1[0-4]|\n            2[568]|\n            3[3-6]|\n            5[5-7]|\n            6[0167]|\n            7[15]|\n            8[0146-9]\n          )\n        )\\d{4}\n        "
    check(parserNestLimit(pattern, 50u).parse().isOk())
}

// This tests that we treat a trailing `-` in a character class as a
// literal `-` even when whitespace mode is enabled and there is whitespace
// after the trailing `-`.
@kotlin.test.Test
public fun regression455TrailingDashIgnoreWhitespace() {
    check(parser("(?x)[ / - ]").parse().isOk())
    check(parser("(?x)[ a - ]").parse().isOk())
    check(parser("(?x)[\n            a\n            - ]\n        ").parse().isOk())
    check(parser("(?x)[\n            a # wat\n            - ]\n        ").parse().isOk())
    check(parser("(?x)[ / -").parse().isErr())
    check(parser("(?x)[ / - ").parse().isErr())
    check(parser("(?x)[\n            / -\n        ").parse().isErr())
    check(parser("(?x)[\n            / - # wat\n        ").parse().isErr())
}
