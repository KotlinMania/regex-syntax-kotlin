// port-lint: source ast/parse.rs
package io.github.kotlinmania.regexsyntax.ast.parse

import io.github.kotlinmania.regexsyntax.ast.ErrorKind
import io.github.kotlinmania.regexsyntax.ast.Assertion
import io.github.kotlinmania.regexsyntax.ast.AssertionKind
import io.github.kotlinmania.regexsyntax.ast.Position
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.Alternation
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
import io.github.kotlinmania.regexsyntax.ast.Flag
import io.github.kotlinmania.regexsyntax.ast.Flags
import io.github.kotlinmania.regexsyntax.ast.FlagsItem
import io.github.kotlinmania.regexsyntax.ast.FlagsItemKind
import io.github.kotlinmania.regexsyntax.ast.Group
import io.github.kotlinmania.regexsyntax.ast.GroupKind
import io.github.kotlinmania.regexsyntax.ast.HexLiteralKind
import io.github.kotlinmania.regexsyntax.ast.Literal
import io.github.kotlinmania.regexsyntax.ast.LiteralKind
import io.github.kotlinmania.regexsyntax.ast.Repetition
import io.github.kotlinmania.regexsyntax.ast.RepetitionKind
import io.github.kotlinmania.regexsyntax.ast.RepetitionOp
import io.github.kotlinmania.regexsyntax.ast.RepetitionRange
import io.github.kotlinmania.regexsyntax.ast.SetFlags
import io.github.kotlinmania.regexsyntax.ast.SpecialLiteralKind
import io.github.kotlinmania.regexsyntax.ast.parse.WithComments
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ParseTest {
    private fun parser(pattern: String): ParserI = ParserI(Parser(), pattern)

    private fun parserOctal(pattern: String): ParserI {
        val p = ParserBuilder.new().octal(true).build()
        return ParserI(p, pattern)
    }

    private fun parserEmptyMinRange(pattern: String): ParserI {
        val p = ParserBuilder.new().emptyMinRange(true).build()
        return ParserI(p, pattern)
    }

    private fun parserNestLimit(pattern: String, nestLimit: UInt): ParserI {
        val p = ParserBuilder.new().nestLimit(nestLimit).build()
        return ParserI(p, pattern)
    }

    private fun parserIgnoreWhitespace(pattern: String): ParserI {
        val p = ParserBuilder.new().ignoreWhitespace(true).build()
        return ParserI(p, pattern)
    }

    private fun npos(offset: Int, line: Int, column: Int): Position = Position(offset, line, column)

    private fun nspan(start: Position, end: Position): Span = Span(start, end)

    private fun spanEx(start: Int, endExclusive: Int): Span {
        val startPos = Position(start, 1, start + 1)
        val endPos = Position(endExclusive, 1, endExclusive + 1)
        return Span(startPos, endPos)
    }

    private fun spanRange(subject: String, range: IntRange): Span {
        val startOffset = range.first
        val endOffset = range.last + 1

        val startLine = 1 + subject.substring(0, startOffset).count { it == '\n' }
        val endLine = 1 + subject.substring(0, endOffset).count { it == '\n' }

        fun columnAt(offset: Int): Int {
            val prefix = subject.substring(0, offset)
            val lastNewline = prefix.lastIndexOf('\n')
            return if (lastNewline < 0) {
                prefix.length + 1
            } else {
                prefix.length - lastNewline
            }
        }

        val start = Position(startOffset, startLine, columnAt(startOffset))
        val end = Position(endOffset, endLine, columnAt(endOffset))
        return Span(start, end)
    }

    private fun spanRangeEx(subject: String, start: Int, endExclusive: Int): Span =
        spanRange(subject, start until endExclusive)

    private data class TestError(
        val span: Span,
        val kind: ErrorKind,
    )

    private fun expectAstException(t: Throwable): AstException = when (t) {
        is AstException -> t
        else -> (t.cause as? AstException) ?: throw t
    }

    private fun parseErr(pattern: String): TestError {
        val result = parser(pattern).parse()
        val ex = result.fold(
            onSuccess = { ast -> fail("expected parse failure for pattern=$pattern, got ast=$ast") },
            onFailure = { t -> expectAstException(t) }
        )
        return TestError(ex.err.span(), ex.err.kind())
    }

    private fun group(start: Int, endExclusive: Int, index: UInt, ast: Ast): Ast = Ast.group(
        Group(
            span = spanEx(start, endExclusive),
            kind = GroupKind.CaptureIndex(index),
            ast = ast,
        ),
    )

    private fun flagSet(pat: String, start: Int, endExclusive: Int, flag: Flag, negated: Boolean): Ast {
        val items = mutableListOf(
            FlagsItem(
                span = spanRangeEx(pat, endExclusive - 2, endExclusive - 1),
                kind = FlagsItemKind.Flag(flag),
            ),
        )
        if (negated) {
            items.add(
                0,
                FlagsItem(
                    span = spanRangeEx(pat, start + 2, endExclusive - 2),
                    kind = FlagsItemKind.Negation,
                ),
            )
        }
        return Ast.flags(
            SetFlags(
                span = spanRangeEx(pat, start, endExclusive),
                flags = Flags(
                    span = spanRangeEx(pat, start + 2, endExclusive - 1),
                    items = items,
                ),
            ),
        )
    }

    @Test
    fun parseNestLimit() {
        // A nest limit of 0 still allows some types of regexes.
        assertEquals(
            Ast.empty(spanEx(0, 0)),
            parserNestLimit("", 0u).parse().getOrThrow(),
        )
        assertEquals(
            lit('a'.code, 0),
            parserNestLimit("a", 0u).parse().getOrThrow(),
        )

        // Test repetition operations, which require one level of nesting.
        assertEquals(
            TestError(
                span = spanEx(0, 2),
                kind = ErrorKind.NestLimitExceeded(0u),
            ),
            parseErrNestLimit("a+", 0u),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 2),
                    op = RepetitionOp(
                        span = spanEx(1, 2),
                        kind = RepetitionKind.OneOrMore,
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parserNestLimit("a+", 1u).parse().getOrThrow(),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 3),
                kind = ErrorKind.NestLimitExceeded(1u),
            ),
            parseErrNestLimit("(a)+", 1u),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 2),
                kind = ErrorKind.NestLimitExceeded(1u),
            ),
            parseErrNestLimit("a+*", 1u),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 3),
                    op = RepetitionOp(
                        span = spanEx(2, 3),
                        kind = RepetitionKind.ZeroOrMore,
                    ),
                    greedy = true,
                    ast = Ast.repetition(
                        Repetition(
                            span = spanEx(0, 2),
                            op = RepetitionOp(
                                span = spanEx(1, 2),
                                kind = RepetitionKind.OneOrMore,
                            ),
                            greedy = true,
                            ast = lit('a'.code, 0),
                        ),
                    ),
                ),
            ),
            parserNestLimit("a+*", 2u).parse().getOrThrow(),
        )

        // Test concatenations. A concatenation requires one level of nesting.
        assertEquals(
            TestError(
                span = spanEx(0, 2),
                kind = ErrorKind.NestLimitExceeded(0u),
            ),
            parseErrNestLimit("ab", 0u),
        )
        assertEquals(
            concat(0, 2, listOf(lit('a'.code, 0), lit('b'.code, 1))),
            parserNestLimit("ab", 1u).parse().getOrThrow(),
        )
        assertEquals(
            concat(0, 3, listOf(lit('a'.code, 0), lit('b'.code, 1), lit('c'.code, 2))),
            parserNestLimit("abc", 1u).parse().getOrThrow(),
        )

        // Test alternations. An alternation requires one level of nesting.
        assertEquals(
            TestError(
                span = spanEx(0, 3),
                kind = ErrorKind.NestLimitExceeded(0u),
            ),
            parseErrNestLimit("a|b", 0u),
        )
        assertEquals(
            alt(0, 3, listOf(lit('a'.code, 0), lit('b'.code, 2))),
            parserNestLimit("a|b", 1u).parse().getOrThrow(),
        )
        assertEquals(
            alt(0, 5, listOf(lit('a'.code, 0), lit('b'.code, 2), lit('c'.code, 4))),
            parserNestLimit("a|b|c", 1u).parse().getOrThrow(),
        )
    }

    private fun parseErrNestLimit(pattern: String, nestLimit: UInt): TestError {
        val result = parserNestLimit(pattern, nestLimit).parse()
        val ex = result.fold(
            onSuccess = { ast -> fail("expected parse failure for nestLimit=$nestLimit pattern=$pattern, got ast=$ast") },
            onFailure = { t -> expectAstException(t) }
        )
        return TestError(ex.err.span(), ex.err.kind())
    }

    private fun lit(c: Int, start: Int): Ast = litWith(c, spanEx(start, start + utf8Len(c)))

    private fun litWith(c: Int, span: Span): Ast = Ast.literal(
        Literal(
            span = span,
            kind = LiteralKind.Verbatim,
            c = c,
        ),
    )

    private fun metaLit(c: Int, span: Span): Ast = Ast.literal(
        Literal(
            span = span,
            kind = LiteralKind.Meta,
            c = c,
        ),
    )

    private fun concat(start: Int, endExclusive: Int, asts: List<Ast>): Ast = Ast.concat(
        Concat(
            span = spanEx(start, endExclusive),
            asts = asts.toMutableList(),
        ),
    )

    private fun concatWith(span: Span, asts: List<Ast>): Ast = Ast.concat(
        Concat(
            span = span,
            asts = asts.toMutableList(),
        ),
    )

    private fun alt(start: Int, endExclusive: Int, asts: List<Ast>): Ast = Ast.alternation(
        Alternation(
            span = spanEx(start, endExclusive),
            asts = asts.toMutableList(),
        ),
    )

    private fun utf8Len(c: Int): Int = when {
        c <= 0x7F -> 1
        c <= 0x7FF -> 2
        c <= 0xFFFF -> 3
        else -> 4
    }

    @Test
    fun parseComments() {
        val pat = """
        (?x)
        # This is comment 1.
        foo # This is comment 2.
          # This is comment 3.
        bar
        # This is comment 4.
        """.trimIndent()

        val astc: WithComments = parser(pat).parseWithComments().getOrThrow()
        assertEquals(
            concatWith(
                spanRangeEx(pat, 0, pat.length),
                listOf(
                    flagSet(pat, 0, 4, Flag.IgnoreWhitespace, false),
                    litWith('f'.code, spanRangeEx(pat, 26, 27)),
                    litWith('o'.code, spanRangeEx(pat, 27, 28)),
                    litWith('o'.code, spanRangeEx(pat, 28, 29)),
                    litWith('b'.code, spanRangeEx(pat, 74, 75)),
                    litWith('a'.code, spanRangeEx(pat, 75, 76)),
                    litWith('r'.code, spanRangeEx(pat, 76, 77)),
                ),
            ),
            astc.ast,
        )
        assertEquals(
            listOf(
                Comment(
                    span = spanRangeEx(pat, 5, 26),
                    comment = " This is comment 1.",
                ),
                Comment(
                    span = spanRangeEx(pat, 30, 51),
                    comment = " This is comment 2.",
                ),
                Comment(
                    span = spanRangeEx(pat, 53, 74),
                    comment = " This is comment 3.",
                ),
                Comment(
                    span = spanRangeEx(pat, 78, 98),
                    comment = " This is comment 4.",
                ),
            ),
            astc.comments,
        )
    }

    @Test
    fun parseHolistic() {
        assertEquals(lit(']'.code, 0), Parser().parse("]").getOrThrow())
        assertEquals(
            concat(
                0,
                36,
                listOf(
                    metaLit('\\'.code, spanEx(0, 2)),
                    metaLit('.'.code, spanEx(2, 4)),
                    metaLit('+'.code, spanEx(4, 6)),
                    metaLit('*'.code, spanEx(6, 8)),
                    metaLit('?'.code, spanEx(8, 10)),
                    metaLit('('.code, spanEx(10, 12)),
                    metaLit(')'.code, spanEx(12, 14)),
                    metaLit('|'.code, spanEx(14, 16)),
                    metaLit('['.code, spanEx(16, 18)),
                    metaLit(']'.code, spanEx(18, 20)),
                    metaLit('{'.code, spanEx(20, 22)),
                    metaLit('}'.code, spanEx(22, 24)),
                    metaLit('^'.code, spanEx(24, 26)),
                    metaLit('$'.code, spanEx(26, 28)),
                    metaLit('#'.code, spanEx(28, 30)),
                    metaLit('&'.code, spanEx(30, 32)),
                    metaLit('-'.code, spanEx(32, 34)),
                    metaLit('~'.code, spanEx(34, 36)),
                ),
            ),
            Parser().parse("\\\\\\.\\+\\*\\?\\(\\)\\|\\[\\]\\{\\}\\^\\$\\#\\&\\-\\~").getOrThrow(),
        )
    }

    @Test
    fun parseIgnoreWhitespace() {
        // Test that basic whitespace insensitivity works.
        run {
            val pat = "(?x)a b"
            assertEquals(
                concatWith(
                    nspan(npos(0, 1, 1), npos(7, 1, 8)),
                    listOf(
                        flagSet(pat, 0, 4, Flag.IgnoreWhitespace, false),
                        litWith('a'.code, nspan(npos(4, 1, 5), npos(5, 1, 6))),
                        litWith('b'.code, nspan(npos(6, 1, 7), npos(7, 1, 8))),
                    ),
                ),
                parser(pat).parse().getOrThrow(),
            )
        }

        // Test that we can toggle whitespace insensitivity.
        run {
            val pat = "(?x)a b(?-x)a b"
            assertEquals(
                concatWith(
                    nspan(npos(0, 1, 1), npos(15, 1, 16)),
                    listOf(
                        flagSet(pat, 0, 4, Flag.IgnoreWhitespace, false),
                        litWith('a'.code, nspan(npos(4, 1, 5), npos(5, 1, 6))),
                        litWith('b'.code, nspan(npos(6, 1, 7), npos(7, 1, 8))),
                        flagSet(pat, 7, 12, Flag.IgnoreWhitespace, true),
                        litWith('a'.code, nspan(npos(12, 1, 13), npos(13, 1, 14))),
                        litWith(' '.code, nspan(npos(13, 1, 14), npos(14, 1, 15))),
                        litWith('b'.code, nspan(npos(14, 1, 15), npos(15, 1, 16))),
                    ),
                ),
                parser(pat).parse().getOrThrow(),
            )
        }

        // Test that nesting whitespace insensitive flags works.
        run {
            val pat = "a (?x:a )a "
            assertEquals(
                concatWith(
                    spanRangeEx(pat, 0, 11),
                    listOf(
                        litWith('a'.code, spanRangeEx(pat, 0, 1)),
                        litWith(' '.code, spanRangeEx(pat, 1, 2)),
                        Ast.group(
                            Group(
                                span = spanRangeEx(pat, 2, 9),
                                kind = GroupKind.NonCapturing(
                                    Flags(
                                        span = spanRangeEx(pat, 4, 5),
                                        items = mutableListOf(
                                            FlagsItem(
                                                span = spanRangeEx(pat, 4, 5),
                                                kind = FlagsItemKind.Flag(Flag.IgnoreWhitespace),
                                            ),
                                        ),
                                    ),
                                ),
                                ast = litWith('a'.code, spanRangeEx(pat, 6, 7)),
                            ),
                        ),
                        litWith('a'.code, spanRangeEx(pat, 9, 10)),
                        litWith(' '.code, spanRangeEx(pat, 10, 11)),
                    ),
                ),
                parser(pat).parse().getOrThrow(),
            )
        }

        // Test that whitespace after an opening paren is insignificant.
        run {
            val pat = "(?x)( ?P<foo> a )"
            assertEquals(
                concatWith(
                    spanRangeEx(pat, 0, pat.length),
                    listOf(
                        flagSet(pat, 0, 4, Flag.IgnoreWhitespace, false),
                        Ast.group(
                            Group(
                                span = spanRangeEx(pat, 4, pat.length),
                                kind = GroupKind.CaptureName(
                                    startsWithP = true,
                                    name = CaptureName(
                                        span = spanRangeEx(pat, 9, 12),
                                        name = "foo",
                                        index = 1u,
                                    ),
                                ),
                                ast = litWith('a'.code, spanRangeEx(pat, 14, 15)),
                            ),
                        ),
                    ),
                ),
                parser(pat).parse().getOrThrow(),
            )
        }

        run {
            val pat = "(?x)(  a )"
            assertEquals(
                concatWith(
                    spanRangeEx(pat, 0, pat.length),
                    listOf(
                        flagSet(pat, 0, 4, Flag.IgnoreWhitespace, false),
                        Ast.group(
                            Group(
                                span = spanRangeEx(pat, 4, pat.length),
                                kind = GroupKind.CaptureIndex(1u),
                                ast = litWith('a'.code, spanRangeEx(pat, 7, 8)),
                            ),
                        ),
                    ),
                ),
                parser(pat).parse().getOrThrow(),
            )
        }

        run {
            val pat = "(?x)(  ?:  a )"
            assertEquals(
                concatWith(
                    spanRangeEx(pat, 0, pat.length),
                    listOf(
                        flagSet(pat, 0, 4, Flag.IgnoreWhitespace, false),
                        Ast.group(
                            Group(
                                span = spanRangeEx(pat, 4, pat.length),
                                kind = GroupKind.NonCapturing(
                                    Flags(
                                        span = spanRangeEx(pat, 8, 8),
                                        items = mutableListOf(),
                                    ),
                                ),
                                ast = litWith('a'.code, spanRangeEx(pat, 11, 12)),
                            ),
                        ),
                    ),
                ),
                parser(pat).parse().getOrThrow(),
            )
        }

        run {
            val pat = "(?x)\\x { 53 }"
            assertEquals(
                concatWith(
                    spanRangeEx(pat, 0, pat.length),
                    listOf(
                        flagSet(pat, 0, 4, Flag.IgnoreWhitespace, false),
                        Ast.literal(
                            Literal(
                                span = spanEx(4, 13),
                                kind = LiteralKind.HexBrace(HexLiteralKind.X),
                                c = 'S'.code,
                            ),
                        ),
                    ),
                ),
                parser(pat).parse().getOrThrow(),
            )
        }

        // Test that whitespace after an escape is OK.
        run {
            val pat = "(?x)\\ "
            assertEquals(
                concatWith(
                    spanRangeEx(pat, 0, pat.length),
                    listOf(
                        flagSet(pat, 0, 4, Flag.IgnoreWhitespace, false),
                        Ast.literal(
                            Literal(
                                span = spanRangeEx(pat, 4, 6),
                                kind = LiteralKind.Superfluous,
                                c = ' '.code,
                            ),
                        ),
                    ),
                ),
                parser(pat).parse().getOrThrow(),
            )
        }
    }

    @Test
    fun parseNewlines() {
        run {
            val pat = ".\n."
            assertEquals(
                concatWith(
                    spanRange(pat, 0..2),
                    listOf(
                        Ast.dot(spanRange(pat, 0..0)),
                        litWith('\n'.code, spanRange(pat, 1..1)),
                        Ast.dot(spanRange(pat, 2..2)),
                    ),
                ),
                Parser().parse(pat).getOrThrow(),
            )
        }

        run {
            val pat = "foobar\nbaz\nquux\n"
            assertEquals(
                concatWith(
                    spanRange(pat, 0 until pat.length),
                    listOf(
                        litWith('f'.code, nspan(npos(0, 1, 1), npos(1, 1, 2))),
                        litWith('o'.code, nspan(npos(1, 1, 2), npos(2, 1, 3))),
                        litWith('o'.code, nspan(npos(2, 1, 3), npos(3, 1, 4))),
                        litWith('b'.code, nspan(npos(3, 1, 4), npos(4, 1, 5))),
                        litWith('a'.code, nspan(npos(4, 1, 5), npos(5, 1, 6))),
                        litWith('r'.code, nspan(npos(5, 1, 6), npos(6, 1, 7))),
                        litWith('\n'.code, nspan(npos(6, 1, 7), npos(7, 2, 1))),
                        litWith('b'.code, nspan(npos(7, 2, 1), npos(8, 2, 2))),
                        litWith('a'.code, nspan(npos(8, 2, 2), npos(9, 2, 3))),
                        litWith('z'.code, nspan(npos(9, 2, 3), npos(10, 2, 4))),
                        litWith('\n'.code, nspan(npos(10, 2, 4), npos(11, 3, 1))),
                        litWith('q'.code, nspan(npos(11, 3, 1), npos(12, 3, 2))),
                        litWith('u'.code, nspan(npos(12, 3, 2), npos(13, 3, 3))),
                        litWith('u'.code, nspan(npos(13, 3, 3), npos(14, 3, 4))),
                        litWith('x'.code, nspan(npos(14, 3, 4), npos(15, 3, 5))),
                        litWith('\n'.code, nspan(npos(15, 3, 5), npos(16, 4, 1))),
                    ),
                ),
                Parser().parse(pat).getOrThrow(),
            )
        }
    }

    @Test
    fun parseUncountedRepetition() {
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 2),
                    op = RepetitionOp(
                        span = spanEx(1, 2),
                        kind = RepetitionKind.ZeroOrMore,
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a*").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 2),
                    op = RepetitionOp(
                        span = spanEx(1, 2),
                        kind = RepetitionKind.OneOrMore,
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a+").parse().getOrThrow(),
        )

        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 2),
                    op = RepetitionOp(
                        span = spanEx(1, 2),
                        kind = RepetitionKind.ZeroOrOne,
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a?").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 3),
                    op = RepetitionOp(
                        span = spanEx(1, 3),
                        kind = RepetitionKind.ZeroOrOne,
                    ),
                    greedy = false,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a??").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 2),
                    op = RepetitionOp(
                        span = spanEx(1, 2),
                        kind = RepetitionKind.ZeroOrOne,
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a?").parse().getOrThrow(),
        )
        assertEquals(
            concat(
                0,
                3,
                listOf(
                    Ast.repetition(
                        Repetition(
                            span = spanEx(0, 2),
                            op = RepetitionOp(
                                span = spanEx(1, 2),
                                kind = RepetitionKind.ZeroOrOne,
                            ),
                            greedy = true,
                            ast = lit('a'.code, 0),
                        ),
                    ),
                    lit('b'.code, 2),
                ),
            ),
            parser("a?b").parse().getOrThrow(),
        )
        assertEquals(
            concat(
                0,
                4,
                listOf(
                    Ast.repetition(
                        Repetition(
                            span = spanEx(0, 3),
                            op = RepetitionOp(
                                span = spanEx(1, 3),
                                kind = RepetitionKind.ZeroOrOne,
                            ),
                            greedy = false,
                            ast = lit('a'.code, 0),
                        ),
                    ),
                    lit('b'.code, 3),
                ),
            ),
            parser("a??b").parse().getOrThrow(),
        )
        assertEquals(
            concat(
                0,
                3,
                listOf(
                    lit('a'.code, 0),
                    Ast.repetition(
                        Repetition(
                            span = spanEx(1, 3),
                            op = RepetitionOp(
                                span = spanEx(2, 3),
                                kind = RepetitionKind.ZeroOrOne,
                            ),
                            greedy = true,
                            ast = lit('b'.code, 1),
                        ),
                    ),
                ),
            ),
            parser("ab?").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 5),
                    op = RepetitionOp(
                        span = spanEx(4, 5),
                        kind = RepetitionKind.ZeroOrOne,
                    ),
                    greedy = true,
                    ast = group(
                        0,
                        4,
                        1u,
                        concat(1, 3, listOf(lit('a'.code, 1), lit('b'.code, 2))),
                    ),
                ),
            ),
            parser("(ab)?").parse().getOrThrow(),
        )
        assertEquals(
            alt(
                0,
                3,
                listOf(
                    Ast.empty(spanEx(0, 0)),
                    Ast.repetition(
                        Repetition(
                            span = spanEx(1, 3),
                            op = RepetitionOp(
                                span = spanEx(2, 3),
                                kind = RepetitionKind.ZeroOrOne,
                            ),
                            greedy = true,
                            ast = lit('a'.code, 1),
                        ),
                    ),
                ),
            ),
            parser("|a?").parse().getOrThrow(),
        )

        assertEquals(
            TestError(
                span = spanEx(0, 0),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("*"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 4),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("(?i)*"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 1),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("(*)"),
        )
        assertEquals(
            TestError(
                span = spanEx(3, 3),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("(?:?)"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 0),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("+"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 0),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("?"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 1),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("(?)"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 1),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("|*"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 1),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("|+"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 1),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("|?"),
        )
    }

    @Test
    fun parseCountedRepetition() {
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 4),
                    op = RepetitionOp(
                        span = spanEx(1, 4),
                        kind = RepetitionKind.Range(RepetitionRange.Exactly(5u)),
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a{5}").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 5),
                    op = RepetitionOp(
                        span = spanEx(1, 5),
                        kind = RepetitionKind.Range(RepetitionRange.AtLeast(5u)),
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a{5,}").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 6),
                    op = RepetitionOp(
                        span = spanEx(1, 6),
                        kind = RepetitionKind.Range(RepetitionRange.Bounded(5u, 9u)),
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a{5,9}").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 5),
                    op = RepetitionOp(
                        span = spanEx(1, 5),
                        kind = RepetitionKind.Range(RepetitionRange.Exactly(5u)),
                    ),
                    greedy = false,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a{5}?").parse().getOrThrow(),
        )
        assertEquals(
            concat(
                0,
                5,
                listOf(
                    lit('a'.code, 0),
                    Ast.repetition(
                        Repetition(
                            span = spanEx(1, 5),
                            op = RepetitionOp(
                                span = spanEx(2, 5),
                                kind = RepetitionKind.Range(RepetitionRange.Exactly(5u)),
                            ),
                            greedy = true,
                            ast = lit('b'.code, 1),
                        ),
                    ),
                ),
            ),
            parser("ab{5}").parse().getOrThrow(),
        )
        assertEquals(
            concat(
                0,
                6,
                listOf(
                    lit('a'.code, 0),
                    Ast.repetition(
                        Repetition(
                            span = spanEx(1, 5),
                            op = RepetitionOp(
                                span = spanEx(2, 5),
                                kind = RepetitionKind.Range(RepetitionRange.Exactly(5u)),
                            ),
                            greedy = true,
                            ast = lit('b'.code, 1),
                        ),
                    ),
                    lit('c'.code, 5),
                ),
            ),
            parser("ab{5}c").parse().getOrThrow(),
        )

        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 6),
                    op = RepetitionOp(
                        span = spanEx(1, 6),
                        kind = RepetitionKind.Range(RepetitionRange.Exactly(5u)),
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a{ 5 }").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 10),
                    op = RepetitionOp(
                        span = spanEx(1, 10),
                        kind = RepetitionKind.Range(RepetitionRange.Bounded(5u, 9u)),
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parser("a{ 5 , 9 }").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 5),
                    op = RepetitionOp(
                        span = spanEx(1, 5),
                        kind = RepetitionKind.Range(RepetitionRange.Bounded(0u, 9u)),
                    ),
                    greedy = true,
                    ast = lit('a'.code, 0),
                ),
            ),
            parserEmptyMinRange("a{,9}").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 8),
                    op = RepetitionOp(
                        span = spanEx(1, 8),
                        kind = RepetitionKind.Range(RepetitionRange.Bounded(5u, 9u)),
                    ),
                    greedy = false,
                    ast = lit('a'.code, 0),
                ),
            ),
            parserIgnoreWhitespace("a{5,9} ?").parse().getOrThrow(),
        )
        assertEquals(
            Ast.repetition(
                Repetition(
                    span = spanEx(0, 7),
                    op = RepetitionOp(
                        span = spanEx(2, 7),
                        kind = RepetitionKind.Range(RepetitionRange.Bounded(5u, 9u)),
                    ),
                    greedy = true,
                    ast = Ast.assertion(
                        Assertion(
                            span = spanEx(0, 2),
                            kind = AssertionKind.WordBoundary,
                        ),
                    ),
                ),
            ),
            parser("\\b{5,9}").parse().getOrThrow(),
        )

        assertEquals(
            TestError(
                span = spanEx(4, 4),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("(?i){0}"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 4),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("(?m){1,1}"),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 2),
                kind = ErrorKind.RepetitionCountDecimalEmpty,
            ),
            parseErr("a{]}"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 4),
                kind = ErrorKind.RepetitionCountDecimalEmpty,
            ),
            parseErr("a{1,]}"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 2),
                kind = ErrorKind.RepetitionCountUnclosed,
            ),
            parseErr("a{"),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 2),
                kind = ErrorKind.RepetitionCountDecimalEmpty,
            ),
            parseErr("a{}"),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 2),
                kind = ErrorKind.RepetitionCountDecimalEmpty,
            ),
            parseErr("a{a"),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 12),
                kind = ErrorKind.DecimalInvalid,
            ),
            parseErr("a{9999999999}"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 3),
                kind = ErrorKind.RepetitionCountUnclosed,
            ),
            parseErr("a{9"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 4),
                kind = ErrorKind.RepetitionCountDecimalEmpty,
            ),
            parseErr("a{9,a"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 14),
                kind = ErrorKind.DecimalInvalid,
            ),
            parseErr("a{9,9999999999}"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 4),
                kind = ErrorKind.RepetitionCountUnclosed,
            ),
            parseErr("a{9,"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 6),
                kind = ErrorKind.RepetitionCountUnclosed,
            ),
            parseErr("a{9,11"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 6),
                kind = ErrorKind.RepetitionCountInvalid,
            ),
            parseErr("a{2,1}"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 0),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("{5}"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 1),
                kind = ErrorKind.RepetitionMissing,
            ),
            parseErr("|{5}"),
        )
    }

    @Test
    fun parseAlternate() {
        assertEquals(
            Ast.alternation(
                Alternation(
                    span = spanEx(0, 3),
                    asts = mutableListOf(lit('a'.code, 0), lit('b'.code, 2)),
                ),
            ),
            parser("a|b").parse().getOrThrow(),
        )
        assertEquals(
            group(
                0,
                5,
                1u,
                Ast.alternation(
                    Alternation(
                        span = spanEx(1, 4),
                        asts = mutableListOf(lit('a'.code, 1), lit('b'.code, 3)),
                    ),
                ),
            ),
            parser("(a|b)").parse().getOrThrow(),
        )

        assertEquals(
            Ast.alternation(
                Alternation(
                    span = spanEx(0, 5),
                    asts = mutableListOf(lit('a'.code, 0), lit('b'.code, 2), lit('c'.code, 4)),
                ),
            ),
            parser("a|b|c").parse().getOrThrow(),
        )
        assertEquals(
            Ast.alternation(
                Alternation(
                    span = spanEx(0, 8),
                    asts = mutableListOf(
                        concat(0, 2, listOf(lit('a'.code, 0), lit('x'.code, 1))),
                        concat(3, 5, listOf(lit('b'.code, 3), lit('y'.code, 4))),
                        concat(6, 8, listOf(lit('c'.code, 6), lit('z'.code, 7))),
                    ),
                ),
            ),
            parser("ax|by|cz").parse().getOrThrow(),
        )
        assertEquals(
            group(
                0,
                10,
                1u,
                Ast.alternation(
                    Alternation(
                        span = spanEx(1, 9),
                        asts = mutableListOf(
                            concat(1, 3, listOf(lit('a'.code, 1), lit('x'.code, 2))),
                            concat(4, 6, listOf(lit('b'.code, 4), lit('y'.code, 5))),
                            concat(7, 9, listOf(lit('c'.code, 7), lit('z'.code, 8))),
                        ),
                    ),
                ),
            ),
            parser("(ax|by|cz)").parse().getOrThrow(),
        )
        assertEquals(
            group(
                0,
                14,
                1u,
                alt(
                    1,
                    13,
                    listOf(
                        concat(1, 3, listOf(lit('a'.code, 1), lit('x'.code, 2))),
                        group(
                            4,
                            13,
                            2u,
                            alt(
                                5,
                                12,
                                listOf(
                                    concat(5, 7, listOf(lit('b'.code, 5), lit('y'.code, 6))),
                                    group(
                                        8,
                                        12,
                                        3u,
                                        concat(9, 11, listOf(lit('c'.code, 9), lit('z'.code, 10))),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            parser("(ax|(by|(cz)))").parse().getOrThrow(),
        )

        assertEquals(
            alt(0, 1, listOf(Ast.empty(spanEx(0, 0)), Ast.empty(spanEx(1, 1)))),
            parser("|").parse().getOrThrow(),
        )
        assertEquals(
            alt(
                0,
                2,
                listOf(Ast.empty(spanEx(0, 0)), Ast.empty(spanEx(1, 1)), Ast.empty(spanEx(2, 2))),
            ),
            parser("||").parse().getOrThrow(),
        )
        assertEquals(
            alt(0, 2, listOf(lit('a'.code, 0), Ast.empty(spanEx(2, 2)))),
            parser("a|").parse().getOrThrow(),
        )
        assertEquals(
            alt(0, 2, listOf(Ast.empty(spanEx(0, 0)), lit('a'.code, 1))),
            parser("|a").parse().getOrThrow(),
        )

        assertEquals(
            group(
                0,
                3,
                1u,
                alt(1, 2, listOf(Ast.empty(spanEx(1, 1)), Ast.empty(spanEx(2, 2)))),
            ),
            parser("(|)").parse().getOrThrow(),
        )
        assertEquals(
            group(
                0,
                4,
                1u,
                alt(1, 3, listOf(lit('a'.code, 1), Ast.empty(spanEx(3, 3)))),
            ),
            parser("(a|)").parse().getOrThrow(),
        )
        assertEquals(
            group(
                0,
                4,
                1u,
                alt(1, 3, listOf(Ast.empty(spanEx(1, 1)), lit('a'.code, 2))),
            ),
            parser("(|a)").parse().getOrThrow(),
        )

        assertEquals(
            TestError(
                span = spanEx(3, 4),
                kind = ErrorKind.GroupUnopened,
            ),
            parseErr("a|b)"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 1),
                kind = ErrorKind.GroupUnclosed,
            ),
            parseErr("(a|b"),
        )
    }

    @Test
    fun parseUnsupportedLookaround() {
        assertEquals(
            TestError(
                span = spanEx(0, 3),
                kind = ErrorKind.UnsupportedLookAround,
            ),
            parseErr("(?=a)"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 3),
                kind = ErrorKind.UnsupportedLookAround,
            ),
            parseErr("(?!a)"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 4),
                kind = ErrorKind.UnsupportedLookAround,
            ),
            parseErr("(?<=a)"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 4),
                kind = ErrorKind.UnsupportedLookAround,
            ),
            parseErr("(?<!a)"),
        )
    }

    @Test
    fun parseGroup() {
        assertEquals(
            Ast.flags(
                SetFlags(
                    span = spanEx(0, 4),
                    flags = Flags(
                        span = spanEx(2, 3),
                        items = mutableListOf(
                            FlagsItem(
                                span = spanEx(2, 3),
                                kind = FlagsItemKind.Flag(Flag.CaseInsensitive),
                            ),
                        ),
                    ),
                ),
            ),
            parser("(?i)").parse().getOrThrow(),
        )
        assertEquals(
            Ast.flags(
                SetFlags(
                    span = spanEx(0, 5),
                    flags = Flags(
                        span = spanEx(2, 4),
                        items = mutableListOf(
                            FlagsItem(
                                span = spanEx(2, 3),
                                kind = FlagsItemKind.Flag(Flag.CaseInsensitive),
                            ),
                            FlagsItem(
                                span = spanEx(3, 4),
                                kind = FlagsItemKind.Flag(Flag.SwapGreed),
                            ),
                        ),
                    ),
                ),
            ),
            parser("(?iU)").parse().getOrThrow(),
        )
        assertEquals(
            Ast.flags(
                SetFlags(
                    span = spanEx(0, 6),
                    flags = Flags(
                        span = spanEx(2, 5),
                        items = mutableListOf(
                            FlagsItem(
                                span = spanEx(2, 3),
                                kind = FlagsItemKind.Flag(Flag.CaseInsensitive),
                            ),
                            FlagsItem(
                                span = spanEx(3, 4),
                                kind = FlagsItemKind.Negation,
                            ),
                            FlagsItem(
                                span = spanEx(4, 5),
                                kind = FlagsItemKind.Flag(Flag.SwapGreed),
                            ),
                        ),
                    ),
                ),
            ),
            parser("(?i-U)").parse().getOrThrow(),
        )

        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 2),
                    kind = GroupKind.CaptureIndex(1u),
                    ast = Ast.empty(spanEx(1, 1)),
                ),
            ),
            parser("()").parse().getOrThrow(),
        )
        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 3),
                    kind = GroupKind.CaptureIndex(1u),
                    ast = lit('a'.code, 1),
                ),
            ),
            parser("(a)").parse().getOrThrow(),
        )
        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 4),
                    kind = GroupKind.CaptureIndex(1u),
                    ast = Ast.group(
                        Group(
                            span = spanEx(1, 3),
                            kind = GroupKind.CaptureIndex(2u),
                            ast = Ast.empty(spanEx(2, 2)),
                        ),
                    ),
                ),
            ),
            parser("(())").parse().getOrThrow(),
        )

        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 5),
                    kind = GroupKind.NonCapturing(
                        Flags(
                            span = spanEx(2, 2),
                            items = mutableListOf(),
                        ),
                    ),
                    ast = lit('a'.code, 3),
                ),
            ),
            parser("(?:a)").parse().getOrThrow(),
        )

        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 6),
                    kind = GroupKind.NonCapturing(
                        Flags(
                            span = spanEx(2, 3),
                            items = mutableListOf(
                                FlagsItem(
                                    span = spanEx(2, 3),
                                    kind = FlagsItemKind.Flag(Flag.CaseInsensitive),
                                ),
                            ),
                        ),
                    ),
                    ast = lit('a'.code, 4),
                ),
            ),
            parser("(?i:a)").parse().getOrThrow(),
        )
        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 8),
                    kind = GroupKind.NonCapturing(
                        Flags(
                            span = spanEx(2, 5),
                            items = mutableListOf(
                                FlagsItem(
                                    span = spanEx(2, 3),
                                    kind = FlagsItemKind.Flag(Flag.CaseInsensitive),
                                ),
                                FlagsItem(
                                    span = spanEx(3, 4),
                                    kind = FlagsItemKind.Negation,
                                ),
                                FlagsItem(
                                    span = spanEx(4, 5),
                                    kind = FlagsItemKind.Flag(Flag.SwapGreed),
                                ),
                            ),
                        ),
                    ),
                    ast = lit('a'.code, 6),
                ),
            ),
            parser("(?i-U:a)").parse().getOrThrow(),
        )

        assertEquals(
            TestError(
                span = spanEx(0, 1),
                kind = ErrorKind.GroupUnclosed,
            ),
            parseErr("("),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 1),
                kind = ErrorKind.GroupUnclosed,
            ),
            parseErr("(?"),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 3),
                kind = ErrorKind.FlagUnrecognized,
            ),
            parseErr("(?P"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 4),
                kind = ErrorKind.GroupNameUnexpectedEof,
            ),
            parseErr("(?P<"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 1),
                kind = ErrorKind.GroupUnclosed,
            ),
            parseErr("(a"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 1),
                kind = ErrorKind.GroupUnclosed,
            ),
            parseErr("(()"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 1),
                kind = ErrorKind.GroupUnopened,
            ),
            parseErr(")"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 2),
                kind = ErrorKind.GroupUnopened,
            ),
            parseErr("a)"),
        )
    }

    @Test
    fun parseCaptureName() {
        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 7),
                    kind = GroupKind.CaptureName(
                        startsWithP = false,
                        name = CaptureName(
                            span = spanEx(3, 4),
                            name = "a",
                            index = 1u,
                        ),
                    ),
                    ast = lit('z'.code, 5),
                ),
            ),
            parser("(?<a>z)").parse().getOrThrow(),
        )
        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 8),
                    kind = GroupKind.CaptureName(
                        startsWithP = true,
                        name = CaptureName(
                            span = spanEx(4, 5),
                            name = "a",
                            index = 1u,
                        ),
                    ),
                    ast = lit('z'.code, 6),
                ),
            ),
            parser("(?P<a>z)").parse().getOrThrow(),
        )
        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 10),
                    kind = GroupKind.CaptureName(
                        startsWithP = true,
                        name = CaptureName(
                            span = spanEx(4, 7),
                            name = "abc",
                            index = 1u,
                        ),
                    ),
                    ast = lit('z'.code, 8),
                ),
            ),
            parser("(?P<abc>z)").parse().getOrThrow(),
        )

        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 10),
                    kind = GroupKind.CaptureName(
                        startsWithP = true,
                        name = CaptureName(
                            span = spanEx(4, 7),
                            name = "a_1",
                            index = 1u,
                        ),
                    ),
                    ast = lit('z'.code, 8),
                ),
            ),
            parser("(?P<a_1>z)").parse().getOrThrow(),
        )

        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 10),
                    kind = GroupKind.CaptureName(
                        startsWithP = true,
                        name = CaptureName(
                            span = spanEx(4, 7),
                            name = "a.1",
                            index = 1u,
                        ),
                    ),
                    ast = lit('z'.code, 8),
                ),
            ),
            parser("(?P<a.1>z)").parse().getOrThrow(),
        )

        assertEquals(
            Ast.group(
                Group(
                    span = spanEx(0, 11),
                    kind = GroupKind.CaptureName(
                        startsWithP = true,
                        name = CaptureName(
                            span = spanEx(4, 8),
                            name = "a[1]",
                            index = 1u,
                        ),
                    ),
                    ast = lit('z'.code, 9),
                ),
            ),
            parser("(?P<a[1]>z)").parse().getOrThrow(),
        )

        run {
            val pat = "(?P<a¾>)"
            assertEquals(
                Ast.group(
                    Group(
                        span = nspan(npos(0, 1, 1), npos(pat.length, 1, pat.length + 1)),
                        kind = GroupKind.CaptureName(
                            startsWithP = true,
                            name = CaptureName(
                                span = nspan(npos(4, 1, 5), npos(6, 1, 7)),
                                name = "a¾",
                                index = 1u,
                            ),
                        ),
                        ast = Ast.empty(nspan(npos(7, 1, 8), npos(7, 1, 8))),
                    ),
                ),
                parser(pat).parse().getOrThrow(),
            )
        }
        run {
            val pat = "(?P<名字>)"
            assertEquals(
                Ast.group(
                    Group(
                        span = nspan(npos(0, 1, 1), npos(pat.length, 1, pat.length + 1)),
                        kind = GroupKind.CaptureName(
                            startsWithP = true,
                            name = CaptureName(
                                span = nspan(npos(4, 1, 5), npos(6, 1, 7)),
                                name = "名字",
                                index = 1u,
                            ),
                        ),
                        ast = Ast.empty(nspan(npos(7, 1, 8), npos(7, 1, 8))),
                    ),
                ),
                parser(pat).parse().getOrThrow(),
            )
        }

        assertEquals(
            TestError(
                span = spanEx(4, 4),
                kind = ErrorKind.GroupNameUnexpectedEof,
            ),
            parseErr("(?P<"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 4),
                kind = ErrorKind.GroupNameEmpty,
            ),
            parseErr("(?P<>z)"),
        )
        assertEquals(
            TestError(
                span = spanEx(5, 5),
                kind = ErrorKind.GroupNameUnexpectedEof,
            ),
            parseErr("(?P<a"),
        )
        assertEquals(
            TestError(
                span = spanEx(6, 6),
                kind = ErrorKind.GroupNameUnexpectedEof,
            ),
            parseErr("(?P<ab"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 5),
                kind = ErrorKind.GroupNameInvalid,
            ),
            parseErr("(?P<0a"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 5),
                kind = ErrorKind.GroupNameInvalid,
            ),
            parseErr("(?P<~"),
        )
        assertEquals(
            TestError(
                span = spanEx(7, 8),
                kind = ErrorKind.GroupNameInvalid,
            ),
            parseErr("(?P<abc~"),
        )
        assertEquals(
            TestError(
                span = spanEx(12, 13),
                kind = ErrorKind.GroupNameDuplicate(spanEx(4, 5)),
            ),
            parseErr("(?P<a>y)(?P<a>z)"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 5),
                kind = ErrorKind.GroupNameInvalid,
            ),
            parseErr("(?P<5>)"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 5),
                kind = ErrorKind.GroupNameInvalid,
            ),
            parseErr("(?P<5a>)"),
        )
        assertEquals(
            TestError(
                span = nspan(npos(4, 1, 5), npos(5, 1, 6)),
                kind = ErrorKind.GroupNameInvalid,
            ),
            parseErr("(?P<¾>)"),
        )
        assertEquals(
            TestError(
                span = nspan(npos(4, 1, 5), npos(5, 1, 6)),
                kind = ErrorKind.GroupNameInvalid,
            ),
            parseErr("(?P<¾a>)"),
        )
        assertEquals(
            TestError(
                span = nspan(npos(4, 1, 5), npos(5, 1, 6)),
                kind = ErrorKind.GroupNameInvalid,
            ),
            parseErr("(?P<☃>)"),
        )
        assertEquals(
            TestError(
                span = nspan(npos(5, 1, 6), npos(6, 1, 7)),
                kind = ErrorKind.GroupNameInvalid,
            ),
            parseErr("(?P<a☃>)"),
        )
    }

    @Test
    fun parseFlags() {
        fun parseFlagsOk(pat: String): Flags = parser(pat).parseFlags().getOrThrow()
        fun parseFlagsErr(pat: String): TestError {
            val ex = parser(pat).parseFlags().fold(
                onSuccess = { flags -> fail("expected parseFlags failure for pattern=$pat, got flags=$flags") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        assertEquals(
            Flags(
                span = spanEx(0, 1),
                items = mutableListOf(
                    FlagsItem(
                        span = spanEx(0, 1),
                        kind = FlagsItemKind.Flag(Flag.CaseInsensitive),
                    ),
                ),
            ),
            parseFlagsOk("i:"),
        )
        assertEquals(
            Flags(
                span = spanEx(0, 1),
                items = mutableListOf(
                    FlagsItem(
                        span = spanEx(0, 1),
                        kind = FlagsItemKind.Flag(Flag.CaseInsensitive),
                    ),
                ),
            ),
            parseFlagsOk("i)"),
        )

        assertEquals(
            Flags(
                span = spanEx(0, 3),
                items = mutableListOf(
                    FlagsItem(spanEx(0, 1), FlagsItemKind.Flag(Flag.CaseInsensitive)),
                    FlagsItem(spanEx(1, 2), FlagsItemKind.Flag(Flag.DotMatchesNewLine)),
                    FlagsItem(spanEx(2, 3), FlagsItemKind.Flag(Flag.SwapGreed)),
                ),
            ),
            parseFlagsOk("isU:"),
        )

        assertEquals(
            Flags(
                span = spanEx(0, 4),
                items = mutableListOf(
                    FlagsItem(spanEx(0, 1), FlagsItemKind.Negation),
                    FlagsItem(spanEx(1, 2), FlagsItemKind.Flag(Flag.CaseInsensitive)),
                    FlagsItem(spanEx(2, 3), FlagsItemKind.Flag(Flag.DotMatchesNewLine)),
                    FlagsItem(spanEx(3, 4), FlagsItemKind.Flag(Flag.SwapGreed)),
                ),
            ),
            parseFlagsOk("-isU:"),
        )
        assertEquals(
            Flags(
                span = spanEx(0, 4),
                items = mutableListOf(
                    FlagsItem(spanEx(0, 1), FlagsItemKind.Flag(Flag.CaseInsensitive)),
                    FlagsItem(spanEx(1, 2), FlagsItemKind.Negation),
                    FlagsItem(spanEx(2, 3), FlagsItemKind.Flag(Flag.DotMatchesNewLine)),
                    FlagsItem(spanEx(3, 4), FlagsItemKind.Flag(Flag.SwapGreed)),
                ),
            ),
            parseFlagsOk("i-sU:"),
        )
        assertEquals(
            Flags(
                span = spanEx(0, 4),
                items = mutableListOf(
                    FlagsItem(spanEx(0, 1), FlagsItemKind.Flag(Flag.CaseInsensitive)),
                    FlagsItem(spanEx(1, 2), FlagsItemKind.Negation),
                    FlagsItem(spanEx(2, 3), FlagsItemKind.Flag(Flag.DotMatchesNewLine)),
                    FlagsItem(spanEx(3, 4), FlagsItemKind.Flag(Flag.CRLF)),
                ),
            ),
            parseFlagsOk("i-sR:"),
        )

        assertEquals(
            TestError(
                span = spanEx(3, 3),
                kind = ErrorKind.FlagUnexpectedEof,
            ),
            parseFlagsErr("isU"),
        )
        assertEquals(
            TestError(
                span = spanEx(3, 4),
                kind = ErrorKind.FlagUnrecognized,
            ),
            parseFlagsErr("isUa:"),
        )
        assertEquals(
            TestError(
                span = spanEx(3, 4),
                kind = ErrorKind.FlagDuplicate(spanEx(0, 1)),
            ),
            parseFlagsErr("isUi:"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 5),
                kind = ErrorKind.FlagRepeatedNegation(spanEx(1, 2)),
            ),
            parseFlagsErr("i-sU-i:"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 1),
                kind = ErrorKind.FlagDanglingNegation,
            ),
            parseFlagsErr("-)"),
        )
        assertEquals(
            TestError(
                span = spanEx(1, 2),
                kind = ErrorKind.FlagDanglingNegation,
            ),
            parseFlagsErr("i-)"),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 3),
                kind = ErrorKind.FlagDanglingNegation,
            ),
            parseFlagsErr("iU-)"),
        )
    }

    @Test
    fun parseFlag() {
        fun parseFlagOk(pat: String): Flag = parser(pat).parseFlag().getOrThrow()
        fun parseFlagErr(pat: String): TestError {
            val ex = parser(pat).parseFlag().fold(
                onSuccess = { flag -> fail("expected parseFlag failure for pattern=$pat, got flag=$flag") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        assertEquals(Flag.CaseInsensitive, parseFlagOk("i"))
        assertEquals(Flag.MultiLine, parseFlagOk("m"))
        assertEquals(Flag.DotMatchesNewLine, parseFlagOk("s"))
        assertEquals(Flag.SwapGreed, parseFlagOk("U"))
        assertEquals(Flag.Unicode, parseFlagOk("u"))
        assertEquals(Flag.CRLF, parseFlagOk("R"))
        assertEquals(Flag.IgnoreWhitespace, parseFlagOk("x"))

        assertEquals(
            TestError(
                span = spanEx(0, 1),
                kind = ErrorKind.FlagUnrecognized,
            ),
            parseFlagErr("a"),
        )
        assertEquals(
            TestError(
                span = spanRangeEx("☃", 0, 1),
                kind = ErrorKind.FlagUnrecognized,
            ),
            parseFlagErr("☃"),
        )
    }

    @Test
    fun parsePrimitiveNonEscape() {
        fun parsePrimOk(pat: String): Primitive = parser(pat).parsePrimitive().getOrThrow()

        assertEquals(
            Primitive.Dot(spanEx(0, 1)),
            parsePrimOk("."),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 1), AssertionKind.StartLine)),
            parsePrimOk("^"),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 1), AssertionKind.EndLine)),
            parsePrimOk("$"),
        )

        assertEquals(
            Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(spanEx(0, 1), LiteralKind.Verbatim, 'a'.code)),
            parsePrimOk("a"),
        )
        assertEquals(
            Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(spanEx(0, 1), LiteralKind.Verbatim, '|'.code)),
            parsePrimOk("|"),
        )
        assertEquals(
            Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(spanRangeEx("☃", 0, 1), LiteralKind.Verbatim, '☃'.code)),
            parsePrimOk("☃"),
        )
    }

    @Test
    fun parseEscape() {
        fun parsePrimOk(pat: String): Primitive = parser(pat).parsePrimitive().getOrThrow()
        fun parseEscapeErr(pat: String): TestError {
            val ex = parser(pat).parseEscape().fold(
                onSuccess = { prim -> fail("expected parseEscape failure for pattern=$pat, got prim=$prim") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        assertEquals(
            Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(spanEx(0, 2), LiteralKind.Meta, '|'.code)),
            parsePrimOk("\\|"),
        )

        val specials = listOf(
            Triple("\\a", 0x07, SpecialLiteralKind.Bell),
            Triple("\\f", 0x0C, SpecialLiteralKind.FormFeed),
            Triple("\\t", '\t'.code, SpecialLiteralKind.Tab),
            Triple("\\n", '\n'.code, SpecialLiteralKind.LineFeed),
            Triple("\\r", '\r'.code, SpecialLiteralKind.CarriageReturn),
            Triple("\\v", 0x0B, SpecialLiteralKind.VerticalTab),
        )
        for ((pat, c, kind) in specials) {
            assertEquals(
                Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(spanEx(0, 2), LiteralKind.Special(kind), c)),
                parsePrimOk(pat),
            )
        }

        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 2), AssertionKind.StartText)),
            parsePrimOk("\\A"),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 2), AssertionKind.EndText)),
            parsePrimOk("\\z"),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 2), AssertionKind.WordBoundary)),
            parsePrimOk("\\b"),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 9), AssertionKind.WordBoundaryStart)),
            parsePrimOk("\\b{start}"),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 7), AssertionKind.WordBoundaryEnd)),
            parsePrimOk("\\b{end}"),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 14), AssertionKind.WordBoundaryStartHalf)),
            parsePrimOk("\\b{start-half}"),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 12), AssertionKind.WordBoundaryEndHalf)),
            parsePrimOk("\\b{end-half}"),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 2), AssertionKind.WordBoundaryStartAngle)),
            parsePrimOk("\\<"),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 2), AssertionKind.WordBoundaryEndAngle)),
            parsePrimOk("\\>"),
        )
        assertEquals(
            Primitive.Assertion(Assertion(spanEx(0, 2), AssertionKind.NotWordBoundary)),
            parsePrimOk("\\B"),
        )

        // We also support superfluous escapes in most cases now too.
        for (c in listOf('!', '@', '%', '"', '\'', '/', ' ')) {
            val pat = "\\$c"
            assertEquals(
                Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(spanEx(0, 2), LiteralKind.Superfluous, c.code)),
                parsePrimOk(pat),
            )
        }

        // Some superfluous escapes, namely [0-9A-Za-z], are still banned.
        assertEquals(
            TestError(
                span = spanEx(0, 2),
                kind = ErrorKind.EscapeUnrecognized,
            ),
            parseEscapeErr("\\e"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 2),
                kind = ErrorKind.EscapeUnrecognized,
            ),
            parseEscapeErr("\\y"),
        )

        assertEquals(
            TestError(
                span = spanEx(0, 3),
                kind = ErrorKind.SpecialWordOrRepetitionUnexpectedEof,
            ),
            parseEscapeErr("\\b{"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 4),
                kind = ErrorKind.SpecialWordOrRepetitionUnexpectedEof,
            ),
            run {
                val ex = parserIgnoreWhitespace("\\b{ ").parseEscape().fold(
                    onSuccess = { prim -> fail("expected parseEscape failure for pattern=\\\\b{  (ignore whitespace), got prim=$prim") },
                    onFailure = { t -> expectAstException(t) }
                )
                TestError(ex.err.span(), ex.err.kind())
            },
        )
        assertEquals(
            TestError(
                span = spanEx(2, 4),
                kind = ErrorKind.RepetitionCountUnclosed,
            ),
            parseErr("\\b{ "),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 6),
                kind = ErrorKind.SpecialWordBoundaryUnclosed,
            ),
            parseEscapeErr("\\b{foo"),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 6),
                kind = ErrorKind.SpecialWordBoundaryUnclosed,
            ),
            parseEscapeErr("\\b{foo!}"),
        )
        assertEquals(
            TestError(
                span = spanEx(3, 6),
                kind = ErrorKind.SpecialWordBoundaryUnrecognized,
            ),
            parseEscapeErr("\\b{foo}"),
        )

        assertEquals(
            TestError(
                span = spanEx(0, 1),
                kind = ErrorKind.EscapeUnexpectedEof,
            ),
            parseEscapeErr("\\"),
        )
    }

    @Test
    fun parseUnsupportedBackreference() {
        fun parseEscapeErr(pat: String): TestError {
            val ex = parser(pat).parseEscape().fold(
                onSuccess = { prim -> fail("expected parseEscape failure for pattern=$pat, got prim=$prim") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        assertEquals(
            TestError(
                span = spanEx(0, 2),
                kind = ErrorKind.UnsupportedBackreference,
            ),
            parseEscapeErr("\\0"),
        )
        assertEquals(
            TestError(
                span = spanEx(0, 2),
                kind = ErrorKind.UnsupportedBackreference,
            ),
            parseEscapeErr("\\9"),
        )
    }

    @Test
    fun parseOctal() {
        fun parseEscapeOk(p: ParserI): Primitive = p.parseEscape().getOrThrow()
        fun parseEscapeErr(p: ParserI): TestError {
            val ex = p.parseEscape().fold(
                onSuccess = { prim -> fail("expected parseEscape failure, got prim=$prim") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        for (i in 0..511) {
            val pat = "\\${i.toString(8)}"
            assertEquals(
                Primitive.Literal(
                    io.github.kotlinmania.regexsyntax.ast.Literal(
                        span = spanEx(0, pat.length),
                        kind = LiteralKind.Octal,
                        c = i,
                    ),
                ),
                parseEscapeOk(parserOctal(pat)),
            )
        }
        assertEquals(
            Primitive.Literal(
                io.github.kotlinmania.regexsyntax.ast.Literal(
                    span = spanEx(0, 3),
                    kind = LiteralKind.Octal,
                    c = '?'.code,
                ),
            ),
            parseEscapeOk(parserOctal("\\778")),
        )
        assertEquals(
            Primitive.Literal(
                io.github.kotlinmania.regexsyntax.ast.Literal(
                    span = spanEx(0, 4),
                    kind = LiteralKind.Octal,
                    c = 0x01FF,
                ),
            ),
            parseEscapeOk(parserOctal("\\7777")),
        )
        assertEquals(
            concat(
                0,
                4,
                listOf(
                    Ast.literal(
                        io.github.kotlinmania.regexsyntax.ast.Literal(
                            span = spanEx(0, 3),
                            kind = LiteralKind.Octal,
                            c = '?'.code,
                        ),
                    ),
                    Ast.literal(
                        io.github.kotlinmania.regexsyntax.ast.Literal(
                            span = spanEx(3, 4),
                            kind = LiteralKind.Verbatim,
                            c = '8'.code,
                        ),
                    ),
                ),
            ),
            parserOctal("\\778").parse().getOrThrow(),
        )
        assertEquals(
            concat(
                0,
                5,
                listOf(
                    Ast.literal(
                        io.github.kotlinmania.regexsyntax.ast.Literal(
                            span = spanEx(0, 4),
                            kind = LiteralKind.Octal,
                            c = 0x01FF,
                        ),
                    ),
                    Ast.literal(
                        io.github.kotlinmania.regexsyntax.ast.Literal(
                            span = spanEx(4, 5),
                            kind = LiteralKind.Verbatim,
                            c = '7'.code,
                        ),
                    ),
                ),
            ),
            parserOctal("\\7777").parse().getOrThrow(),
        )

        assertEquals(
            TestError(
                span = spanEx(0, 2),
                kind = ErrorKind.EscapeUnrecognized,
            ),
            parseEscapeErr(parserOctal("\\8")),
        )
    }

    @Test
    fun parseHexTwo() {
        fun parseEscapeOk(pat: String): Primitive = parser(pat).parseEscape().getOrThrow()
        fun parseEscapeErr(pat: String): TestError {
            val ex = parser(pat).parseEscape().fold(
                onSuccess = { prim -> fail("expected parseEscape failure for pattern=$pat, got prim=$prim") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        for (i in 0..255) {
            val pat = "\\x${i.toString(16).padStart(2, '0')}"
            assertEquals(
                Primitive.Literal(
                    io.github.kotlinmania.regexsyntax.ast.Literal(
                        span = spanEx(0, pat.length),
                        kind = LiteralKind.HexFixed(HexLiteralKind.X),
                        c = i,
                    ),
                ),
                parseEscapeOk(pat),
            )
        }

        assertEquals(
            TestError(
                span = spanEx(3, 3),
                kind = ErrorKind.EscapeUnexpectedEof,
            ),
            parseEscapeErr("\\xF"),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 3),
                kind = ErrorKind.EscapeHexInvalidDigit,
            ),
            parseEscapeErr("\\xG"),
        )
        assertEquals(
            TestError(
                span = spanEx(3, 4),
                kind = ErrorKind.EscapeHexInvalidDigit,
            ),
            parseEscapeErr("\\xFG"),
        )
    }

    @Test
    fun parseHexFour() {
        fun parseEscapeOk(pat: String): Primitive = parser(pat).parseEscape().getOrThrow()
        fun parseEscapeErr(pat: String): TestError {
            val ex = parser(pat).parseEscape().fold(
                onSuccess = { prim -> fail("expected parseEscape failure for pattern=$pat, got prim=$prim") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        for (i in 0..65535) {
            if (i in 0xD800..0xDFFF) continue
            val pat = "\\u${i.toString(16).padStart(4, '0')}"
            assertEquals(
                Primitive.Literal(
                    io.github.kotlinmania.regexsyntax.ast.Literal(
                        span = spanEx(0, pat.length),
                        kind = LiteralKind.HexFixed(HexLiteralKind.UnicodeShort),
                        c = i,
                    ),
                ),
                parseEscapeOk(pat),
            )
        }

        assertEquals(
            TestError(
                span = spanEx(3, 3),
                kind = ErrorKind.EscapeUnexpectedEof,
            ),
            parseEscapeErr("\\uF"),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 3),
                kind = ErrorKind.EscapeHexInvalidDigit,
            ),
            parseEscapeErr("\\uG"),
        )
        assertEquals(
            TestError(
                span = spanEx(3, 4),
                kind = ErrorKind.EscapeHexInvalidDigit,
            ),
            parseEscapeErr("\\uFG"),
        )
        assertEquals(
            TestError(
                span = spanEx(4, 5),
                kind = ErrorKind.EscapeHexInvalidDigit,
            ),
            parseEscapeErr("\\uFFG"),
        )
        assertEquals(
            TestError(
                span = spanEx(5, 6),
                kind = ErrorKind.EscapeHexInvalidDigit,
            ),
            parseEscapeErr("\\uFFFG"),
        )
        assertEquals(
            TestError(
                span = spanEx(2, 6),
                kind = ErrorKind.EscapeHexInvalid,
            ),
            parseEscapeErr("\\uD800"),
        )
    }

    @Test
    fun parseHexEight() {
        fun parseEscapeOk(pat: String): Primitive = parser(pat).parseEscape().getOrThrow()
        fun parseEscapeErr(pat: String): TestError {
            val ex = parser(pat).parseEscape().fold(
                onSuccess = { prim -> fail("expected parseEscape failure for pattern=$pat, got prim=$prim") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        for (i in 0..65535) {
            if (i in 0xD800..0xDFFF) continue
            val pat = "\\U${i.toString(16).padStart(8, '0')}"
            assertEquals(
                Primitive.Literal(
                    io.github.kotlinmania.regexsyntax.ast.Literal(
                        span = spanEx(0, pat.length),
                        kind = LiteralKind.HexFixed(HexLiteralKind.UnicodeLong),
                        c = i,
                    ),
                ),
                parseEscapeOk(pat),
            )
        }

        assertEquals(TestError(spanEx(3, 3), ErrorKind.EscapeUnexpectedEof), parseEscapeErr("\\UF"))
        assertEquals(TestError(spanEx(2, 3), ErrorKind.EscapeHexInvalidDigit), parseEscapeErr("\\UG"))
        assertEquals(TestError(spanEx(3, 4), ErrorKind.EscapeHexInvalidDigit), parseEscapeErr("\\UFG"))
        assertEquals(TestError(spanEx(4, 5), ErrorKind.EscapeHexInvalidDigit), parseEscapeErr("\\UFFG"))
        assertEquals(TestError(spanEx(5, 6), ErrorKind.EscapeHexInvalidDigit), parseEscapeErr("\\UFFFG"))
        assertEquals(TestError(spanEx(6, 7), ErrorKind.EscapeHexInvalidDigit), parseEscapeErr("\\UFFFFG"))
        assertEquals(TestError(spanEx(7, 8), ErrorKind.EscapeHexInvalidDigit), parseEscapeErr("\\UFFFFFG"))
        assertEquals(TestError(spanEx(8, 9), ErrorKind.EscapeHexInvalidDigit), parseEscapeErr("\\UFFFFFFG"))
        assertEquals(TestError(spanEx(9, 10), ErrorKind.EscapeHexInvalidDigit), parseEscapeErr("\\UFFFFFFFG"))
    }

    @Test
    fun parseHexBrace() {
        fun parseEscapeOk(pat: String): Primitive = parser(pat).parseEscape().getOrThrow()
        fun parseEscapeErr(pat: String): TestError {
            val ex = parser(pat).parseEscape().fold(
                onSuccess = { prim -> fail("expected parseEscape failure for pattern=$pat, got prim=$prim") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        assertEquals(
            Primitive.Literal(
                io.github.kotlinmania.regexsyntax.ast.Literal(
                    span = spanEx(0, 8),
                    kind = LiteralKind.HexBrace(HexLiteralKind.UnicodeShort),
                    c = '⛄'.code,
                ),
            ),
            parseEscapeOk("\\u{26c4}"),
        )
        assertEquals(
            Primitive.Literal(
                io.github.kotlinmania.regexsyntax.ast.Literal(
                    span = spanEx(0, 8),
                    kind = LiteralKind.HexBrace(HexLiteralKind.UnicodeLong),
                    c = '⛄'.code,
                ),
            ),
            parseEscapeOk("\\U{26c4}"),
        )
        assertEquals(
            Primitive.Literal(
                io.github.kotlinmania.regexsyntax.ast.Literal(
                    span = spanEx(0, 8),
                    kind = LiteralKind.HexBrace(HexLiteralKind.X),
                    c = '⛄'.code,
                ),
            ),
            parseEscapeOk("\\x{26c4}"),
        )
        assertEquals(
            Primitive.Literal(
                io.github.kotlinmania.regexsyntax.ast.Literal(
                    span = spanEx(0, 8),
                    kind = LiteralKind.HexBrace(HexLiteralKind.X),
                    c = '⛄'.code,
                ),
            ),
            parseEscapeOk("\\x{26C4}"),
        )
        assertEquals(
            Primitive.Literal(
                io.github.kotlinmania.regexsyntax.ast.Literal(
                    span = spanEx(0, 10),
                    kind = LiteralKind.HexBrace(HexLiteralKind.X),
                    c = 0x10FFFF,
                ),
            ),
            parseEscapeOk("\\x{10fFfF}"),
        )

        assertEquals(TestError(spanEx(2, 2), ErrorKind.EscapeUnexpectedEof), parseEscapeErr("\\x"))
        assertEquals(TestError(spanEx(2, 3), ErrorKind.EscapeUnexpectedEof), parseEscapeErr("\\x{"))
        assertEquals(TestError(spanEx(2, 5), ErrorKind.EscapeUnexpectedEof), parseEscapeErr("\\x{FF"))
        assertEquals(TestError(spanEx(2, 4), ErrorKind.EscapeHexEmpty), parseEscapeErr("\\x{}"))
        assertEquals(TestError(spanEx(4, 5), ErrorKind.EscapeHexInvalidDigit), parseEscapeErr("\\x{FGF}"))
        assertEquals(TestError(spanEx(3, 9), ErrorKind.EscapeHexInvalid), parseEscapeErr("\\x{FFFFFF}"))
        assertEquals(TestError(spanEx(3, 7), ErrorKind.EscapeHexInvalid), parseEscapeErr("\\x{D800}"))
        assertEquals(TestError(spanEx(3, 12), ErrorKind.EscapeHexInvalid), parseEscapeErr("\\x{FFFFFFFFF}"))
    }

    @Test
    fun parseDecimal() {
        fun parseDecimalOk(pat: String): UInt = parser(pat).parseDecimal().getOrThrow()
        fun parseDecimalErr(pat: String): TestError {
            val ex = parser(pat).parseDecimal().fold(
                onSuccess = { n -> fail("expected parseDecimal failure for pattern=$pat, got n=$n") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        assertEquals(123u, parseDecimalOk("123"))
        assertEquals(0u, parseDecimalOk("0"))
        assertEquals(1u, parseDecimalOk("01"))

        assertEquals(TestError(spanEx(0, 0), ErrorKind.DecimalEmpty), parseDecimalErr("-1"))
        assertEquals(TestError(spanEx(0, 0), ErrorKind.DecimalEmpty), parseDecimalErr(""))
        assertEquals(TestError(spanEx(0, 10), ErrorKind.DecimalInvalid), parseDecimalErr("9999999999"))
    }

    @Test
    fun parseSetClass() {
        fun union(span: Span, items: List<ClassSetItem>): ClassSet =
            ClassSet.union(ClassSetUnion(span, items.toMutableList()))

        fun intersection(span: Span, lhs: ClassSet, rhs: ClassSet): ClassSet =
            ClassSet.BinaryOp(
                ClassSetBinaryOp(
                    span = span,
                    kind = ClassSetBinaryOpKind.Intersection,
                    lhs = lhs,
                    rhs = rhs,
                ),
            )

        fun difference(span: Span, lhs: ClassSet, rhs: ClassSet): ClassSet =
            ClassSet.BinaryOp(
                ClassSetBinaryOp(
                    span = span,
                    kind = ClassSetBinaryOpKind.Difference,
                    lhs = lhs,
                    rhs = rhs,
                ),
            )

        fun symdifference(span: Span, lhs: ClassSet, rhs: ClassSet): ClassSet =
            ClassSet.BinaryOp(
                ClassSetBinaryOp(
                    span = span,
                    kind = ClassSetBinaryOpKind.SymmetricDifference,
                    lhs = lhs,
                    rhs = rhs,
                ),
            )

        fun itemset(item: ClassSetItem): ClassSet = ClassSet.Item(item)
        fun itemAscii(cls: ClassAscii): ClassSetItem = ClassSetItem.Ascii(cls)
        fun itemUnicode(cls: ClassUnicode): ClassSetItem = ClassSetItem.Unicode(cls)
        fun itemPerl(cls: ClassPerl): ClassSetItem = ClassSetItem.Perl(cls)
        fun itemBracket(cls: ClassBracketed): ClassSetItem = ClassSetItem.Bracketed(cls)

        fun lit(span: Span, c: Char): ClassSetItem = ClassSetItem.Literal(
            io.github.kotlinmania.regexsyntax.ast.Literal(
                span = span,
                kind = LiteralKind.Verbatim,
                c = c.code,
            ),
        )

        fun empty(span: Span): ClassSetItem = ClassSetItem.Empty(span)

        fun range(span: Span, start: Char, end: Char): ClassSetItem {
            val pos1 = span.start.copy(
                offset = span.start.offset + utf8Len(start.code),
                column = span.start.column + 1,
            )
            val pos2 = span.end.copy(
                offset = span.end.offset - utf8Len(end.code),
                column = span.end.column - 1,
            )
            return ClassSetItem.Range(
                ClassSetRange(
                    span = span,
                    start = io.github.kotlinmania.regexsyntax.ast.Literal(
                        span = Span(span.start, pos1),
                        kind = LiteralKind.Verbatim,
                        c = start.code,
                    ),
                    end = io.github.kotlinmania.regexsyntax.ast.Literal(
                        span = Span(pos2, span.end),
                        kind = LiteralKind.Verbatim,
                        c = end.code,
                    ),
                ),
            )
        }

        fun alnum(span: Span, negated: Boolean): ClassAscii = ClassAscii(span, ClassAsciiKind.Alnum, negated)
        fun lower(span: Span, negated: Boolean): ClassAscii = ClassAscii(span, ClassAsciiKind.Lower, negated)

        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 11),
                    negated = false,
                    kind = itemset(itemAscii(alnum(spanEx(1, 10), false))),
                ),
            ),
            parser("[[:alnum:]]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 13),
                    negated = false,
                    kind = itemset(
                        itemBracket(
                            ClassBracketed(
                                span = spanEx(1, 12),
                                negated = false,
                                kind = itemset(itemAscii(alnum(spanEx(2, 11), false))),
                            ),
                        ),
                    ),
                ),
            ),
            parser("[[[:alnum:]]]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 22),
                    negated = false,
                    kind = intersection(
                        spanEx(1, 21),
                        itemset(itemAscii(alnum(spanEx(1, 10), false))),
                        itemset(itemAscii(lower(spanEx(12, 21), false))),
                    ),
                ),
            ),
            parser("[[:alnum:]&&[:lower:]]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 22),
                    negated = false,
                    kind = difference(
                        spanEx(1, 21),
                        itemset(itemAscii(alnum(spanEx(1, 10), false))),
                        itemset(itemAscii(lower(spanEx(12, 21), false))),
                    ),
                ),
            ),
            parser("[[:alnum:]--[:lower:]]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 22),
                    negated = false,
                    kind = symdifference(
                        spanEx(1, 21),
                        itemset(itemAscii(alnum(spanEx(1, 10), false))),
                        itemset(itemAscii(lower(spanEx(12, 21), false))),
                    ),
                ),
            ),
            parser("[[:alnum:]~~[:lower:]]").parse().getOrThrow(),
        )

        assertEquals(
            Ast.ClassBracketed(ClassBracketed(spanEx(0, 3), false, itemset(lit(spanEx(1, 2), 'a')))),
            parser("[a]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 5),
                    negated = false,
                    kind = union(
                        spanEx(1, 4),
                        listOf(
                            lit(spanEx(1, 2), 'a'),
                            ClassSetItem.Literal(
                                io.github.kotlinmania.regexsyntax.ast.Literal(
                                    span = spanEx(2, 4),
                                    kind = LiteralKind.Meta,
                                    c = ']'.code,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            parser("[a\\]]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 6),
                    negated = false,
                    kind = union(
                        spanEx(1, 5),
                        listOf(
                            lit(spanEx(1, 2), 'a'),
                            ClassSetItem.Literal(
                                io.github.kotlinmania.regexsyntax.ast.Literal(
                                    span = spanEx(2, 4),
                                    kind = LiteralKind.Meta,
                                    c = '-'.code,
                                ),
                            ),
                            lit(spanEx(4, 5), 'z'),
                        ),
                    ),
                ),
            ),
            parser("[a\\-z]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 4),
                    negated = false,
                    kind = union(spanEx(1, 3), listOf(lit(spanEx(1, 2), 'a'), lit(spanEx(2, 3), 'b'))),
                ),
            ),
            parser("[ab]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 4),
                    negated = false,
                    kind = union(spanEx(1, 3), listOf(lit(spanEx(1, 2), 'a'), lit(spanEx(2, 3), '-'))),
                ),
            ),
            parser("[a-]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 4),
                    negated = false,
                    kind = union(spanEx(1, 3), listOf(lit(spanEx(1, 2), '-'), lit(spanEx(2, 3), 'a'))),
                ),
            ),
            parser("[-a]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 5),
                    negated = false,
                    kind = itemset(
                        itemUnicode(
                            ClassUnicode(
                                span = spanEx(1, 4),
                                negated = false,
                                kind = ClassUnicodeKind.OneLetter('L'.code),
                            ),
                        ),
                    ),
                ),
            ),
            parser("[\\pL]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 4),
                    negated = false,
                    kind = itemset(
                        itemPerl(
                            ClassPerl(
                                span = spanEx(1, 3),
                                kind = ClassPerlKind.Word,
                                negated = false,
                            ),
                        ),
                    ),
                ),
            ),
            parser("[\\w]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 6),
                    negated = false,
                    kind = union(
                        spanEx(1, 5),
                        listOf(
                            lit(spanEx(1, 2), 'a'),
                            itemPerl(ClassPerl(spanEx(2, 4), ClassPerlKind.Word, false)),
                            lit(spanEx(4, 5), 'z'),
                        ),
                    ),
                ),
            ),
            parser("[a\\wz]").parse().getOrThrow(),
        )

        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 5),
                    negated = false,
                    kind = itemset(range(spanEx(1, 4), 'a', 'z')),
                ),
            ),
            parser("[a-z]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 8),
                    negated = false,
                    kind = union(
                        spanEx(1, 7),
                        listOf(
                            range(spanEx(1, 4), 'a', 'c'),
                            range(spanEx(4, 7), 'x', 'z'),
                        ),
                    ),
                ),
            ),
            parser("[a-cx-z]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 12),
                    negated = false,
                    kind = intersection(
                        spanEx(1, 11),
                        itemset(itemPerl(ClassPerl(spanEx(1, 3), ClassPerlKind.Word, false))),
                        union(
                            spanEx(5, 11),
                            listOf(
                                range(spanEx(5, 8), 'a', 'c'),
                                range(spanEx(8, 11), 'x', 'z'),
                            ),
                        ),
                    ),
                ),
            ),
            parser("[\\w&&a-cx-z]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 12),
                    negated = false,
                    kind = intersection(
                        spanEx(1, 11),
                        union(
                            spanEx(1, 7),
                            listOf(
                                range(spanEx(1, 4), 'a', 'c'),
                                range(spanEx(4, 7), 'x', 'z'),
                            ),
                        ),
                        itemset(itemPerl(ClassPerl(spanEx(9, 11), ClassPerlKind.Word, false))),
                    ),
                ),
            ),
            parser("[a-cx-z&&\\w]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 9),
                    negated = false,
                    kind = difference(
                        spanEx(1, 8),
                        difference(
                            spanEx(1, 5),
                            itemset(lit(spanEx(1, 2), 'a')),
                            itemset(lit(spanEx(4, 5), 'b')),
                        ),
                        itemset(lit(spanEx(7, 8), 'c')),
                    ),
                ),
            ),
            parser("[a--b--c]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 9),
                    negated = false,
                    kind = symdifference(
                        spanEx(1, 8),
                        symdifference(
                            spanEx(1, 5),
                            itemset(lit(spanEx(1, 2), 'a')),
                            itemset(lit(spanEx(4, 5), 'b')),
                        ),
                        itemset(lit(spanEx(7, 8), 'c')),
                    ),
                ),
            ),
            parser("[a~~b~~c]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 7),
                    negated = false,
                    kind = intersection(
                        spanEx(1, 6),
                        itemset(
                            ClassSetItem.Literal(
                                io.github.kotlinmania.regexsyntax.ast.Literal(
                                    span = spanEx(1, 3),
                                    kind = LiteralKind.Meta,
                                    c = '^'.code,
                                ),
                            ),
                        ),
                        itemset(lit(spanEx(5, 6), '^')),
                    ),
                ),
            ),
            parser("[\\^&&^]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 7),
                    negated = false,
                    kind = intersection(
                        spanEx(1, 6),
                        itemset(
                            ClassSetItem.Literal(
                                io.github.kotlinmania.regexsyntax.ast.Literal(
                                    span = spanEx(1, 3),
                                    kind = LiteralKind.Meta,
                                    c = '&'.code,
                                ),
                            ),
                        ),
                        itemset(lit(spanEx(5, 6), '&')),
                    ),
                ),
            ),
            parser("[\\&&&&]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 6),
                    negated = false,
                    kind = intersection(
                        spanEx(1, 5),
                        intersection(
                            spanEx(1, 3),
                            itemset(empty(spanEx(1, 1))),
                            itemset(empty(spanEx(3, 3))),
                        ),
                        itemset(empty(spanEx(5, 5))),
                    ),
                ),
            ),
            parser("[&&&&]").parse().getOrThrow(),
        )

        val pat = "[☃-⛄]"
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 5),
                    negated = false,
                    kind = itemset(
                        ClassSetItem.Range(
                            ClassSetRange(
                                span = spanEx(1, 4),
                                start = io.github.kotlinmania.regexsyntax.ast.Literal(
                                    span = spanEx(1, 2),
                                    kind = LiteralKind.Verbatim,
                                    c = '☃'.code,
                                ),
                                end = io.github.kotlinmania.regexsyntax.ast.Literal(
                                    span = spanEx(3, 4),
                                    kind = LiteralKind.Verbatim,
                                    c = '⛄'.code,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            parser(pat).parse().getOrThrow(),
        )

        assertEquals(
            Ast.ClassBracketed(ClassBracketed(spanEx(0, 3), false, itemset(lit(spanEx(1, 2), ']')))),
            parser("[]]").parse().getOrThrow(),
        )
        assertEquals(
            Ast.ClassBracketed(
                ClassBracketed(
                    span = spanEx(0, 5),
                    negated = false,
                    kind = union(
                        spanEx(1, 4),
                        listOf(
                            lit(spanEx(1, 2), ']'),
                            ClassSetItem.Literal(
                                io.github.kotlinmania.regexsyntax.ast.Literal(
                                    span = spanEx(2, 4),
                                    kind = LiteralKind.Meta,
                                    c = '['.code,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            parser("[]\\[]").parse().getOrThrow(),
        )
        assertEquals(
            concat(
                0,
                5,
                listOf(
                    Ast.ClassBracketed(
                        ClassBracketed(
                            span = spanEx(0, 4),
                            negated = false,
                            kind = itemset(
                                ClassSetItem.Literal(
                                    io.github.kotlinmania.regexsyntax.ast.Literal(
                                        span = spanEx(1, 3),
                                        kind = LiteralKind.Meta,
                                        c = '['.code,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    Ast.Literal(
                        Literal(
                            span = spanEx(4, 5),
                            kind = LiteralKind.Verbatim,
                            c = ']'.code,
                        ),
                    ),
                ),
            ),
            parser("[\\[]]").parse().getOrThrow(),
        )

        assertEquals(TestError(spanEx(0, 1), ErrorKind.ClassUnclosed), parseErr("["))
        assertEquals(TestError(spanEx(1, 2), ErrorKind.ClassUnclosed), parseErr("[["))
        assertEquals(TestError(spanEx(0, 1), ErrorKind.ClassUnclosed), parseErr("[[-]"))
        assertEquals(TestError(spanEx(1, 2), ErrorKind.ClassUnclosed), parseErr("[[[:alnum:]"))
        assertEquals(TestError(spanEx(1, 3), ErrorKind.ClassEscapeInvalid), parseErr("[\\b]"))
        assertEquals(TestError(spanEx(1, 3), ErrorKind.ClassRangeLiteral), parseErr("[\\w-a]"))
        assertEquals(TestError(spanEx(3, 5), ErrorKind.ClassRangeLiteral), parseErr("[a-\\w]"))
        assertEquals(TestError(spanEx(1, 4), ErrorKind.ClassRangeInvalid), parseErr("[z-a]"))

        fun parseErrIgnoreWhitespace(pat: String): TestError {
            val res = parserIgnoreWhitespace(pat).parse()
            val ex = res.fold(
                onSuccess = { ast -> fail("expected parse failure for ignoreWhitespace pattern=$pat, got ast=$ast") },
                onFailure = { t -> expectAstException(t) },
            )
            return TestError(ex.err.span(), ex.err.kind())
        }
        assertEquals(TestError(spanEx(0, 1), ErrorKind.ClassUnclosed), parseErrIgnoreWhitespace("[a "))
        assertEquals(TestError(spanEx(0, 1), ErrorKind.ClassUnclosed), parseErrIgnoreWhitespace("[a- "))
    }

    @Test
    fun parseSetClassOpen() {
        fun parseSetClassOpenOk(pat: String): Pair<ClassBracketed, ClassSetUnion> =
            parser(pat).parseSetClassOpen().getOrThrow()

        fun parseSetClassOpenOkIgnoreWhitespace(pat: String): Pair<ClassBracketed, ClassSetUnion> =
            parserIgnoreWhitespace(pat).parseSetClassOpen().getOrThrow()

        fun parseSetClassOpenErr(pat: String): TestError {
            val ex = parser(pat).parseSetClassOpen().fold(
                onSuccess = { pair -> fail("expected parseSetClassOpen failure for pattern=$pat, got pair=$pair") },
                onFailure = { t -> expectAstException(t) },
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        fun parseSetClassOpenErrIgnoreWhitespace(pat: String): TestError {
            val ex = parserIgnoreWhitespace(pat).parseSetClassOpen().fold(
                onSuccess = { pair -> fail("expected parseSetClassOpen failure for ignoreWhitespace pattern=$pat, got pair=$pair") },
                onFailure = { t -> expectAstException(t) },
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 1),
                    negated = false,
                    kind = ClassSet.union(ClassSetUnion(spanEx(1, 1), mutableListOf())),
                ),
                ClassSetUnion(spanEx(1, 1), mutableListOf()),
            ),
            parseSetClassOpenOk("[a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 4),
                    negated = false,
                    kind = ClassSet.union(ClassSetUnion(spanEx(4, 4), mutableListOf())),
                ),
                ClassSetUnion(spanEx(4, 4), mutableListOf()),
            ),
            parseSetClassOpenOkIgnoreWhitespace("[   a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 2),
                    negated = true,
                    kind = ClassSet.union(ClassSetUnion(spanEx(2, 2), mutableListOf())),
                ),
                ClassSetUnion(spanEx(2, 2), mutableListOf()),
            ),
            parseSetClassOpenOk("[^a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 4),
                    negated = true,
                    kind = ClassSet.union(ClassSetUnion(spanEx(4, 4), mutableListOf())),
                ),
                ClassSetUnion(spanEx(4, 4), mutableListOf()),
            ),
            parseSetClassOpenOkIgnoreWhitespace("[ ^ a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 2),
                    negated = false,
                    kind = ClassSet.union(ClassSetUnion(spanEx(1, 1), mutableListOf())),
                ),
                ClassSetUnion(
                    span = spanEx(1, 2),
                    items = mutableListOf(
                        ClassSetItem.Literal(
                            io.github.kotlinmania.regexsyntax.ast.Literal(
                                span = spanEx(1, 2),
                                kind = LiteralKind.Verbatim,
                                c = '-'.code,
                            ),
                        ),
                    ),
                ),
            ),
            parseSetClassOpenOk("[-a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 4),
                    negated = false,
                    kind = ClassSet.union(ClassSetUnion(spanEx(2, 2), mutableListOf())),
                ),
                ClassSetUnion(
                    span = spanEx(2, 3),
                    items = mutableListOf(
                        ClassSetItem.Literal(
                            io.github.kotlinmania.regexsyntax.ast.Literal(
                                span = spanEx(2, 3),
                                kind = LiteralKind.Verbatim,
                                c = '-'.code,
                            ),
                        ),
                    ),
                ),
            ),
            parseSetClassOpenOkIgnoreWhitespace("[ - a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 3),
                    negated = true,
                    kind = ClassSet.union(ClassSetUnion(spanEx(2, 2), mutableListOf())),
                ),
                ClassSetUnion(
                    span = spanEx(2, 3),
                    items = mutableListOf(
                        ClassSetItem.Literal(
                            io.github.kotlinmania.regexsyntax.ast.Literal(
                                span = spanEx(2, 3),
                                kind = LiteralKind.Verbatim,
                                c = '-'.code,
                            ),
                        ),
                    ),
                ),
            ),
            parseSetClassOpenOk("[^-a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 3),
                    negated = false,
                    kind = ClassSet.union(ClassSetUnion(spanEx(1, 1), mutableListOf())),
                ),
                ClassSetUnion(
                    span = spanEx(1, 3),
                    items = mutableListOf(
                        ClassSetItem.Literal(
                            io.github.kotlinmania.regexsyntax.ast.Literal(
                                span = spanEx(1, 2),
                                kind = LiteralKind.Verbatim,
                                c = '-'.code,
                            ),
                        ),
                        ClassSetItem.Literal(
                            io.github.kotlinmania.regexsyntax.ast.Literal(
                                span = spanEx(2, 3),
                                kind = LiteralKind.Verbatim,
                                c = '-'.code,
                            ),
                        ),
                    ),
                ),
            ),
            parseSetClassOpenOk("[--a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 2),
                    negated = false,
                    kind = ClassSet.union(ClassSetUnion(spanEx(1, 1), mutableListOf())),
                ),
                ClassSetUnion(
                    span = spanEx(1, 2),
                    items = mutableListOf(
                        ClassSetItem.Literal(
                            io.github.kotlinmania.regexsyntax.ast.Literal(
                                span = spanEx(1, 2),
                                kind = LiteralKind.Verbatim,
                                c = ']'.code,
                            ),
                        ),
                    ),
                ),
            ),
            parseSetClassOpenOk("[]a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 4),
                    negated = false,
                    kind = ClassSet.union(ClassSetUnion(spanEx(2, 2), mutableListOf())),
                ),
                ClassSetUnion(
                    span = spanEx(2, 3),
                    items = mutableListOf(
                        ClassSetItem.Literal(
                            io.github.kotlinmania.regexsyntax.ast.Literal(
                                span = spanEx(2, 3),
                                kind = LiteralKind.Verbatim,
                                c = ']'.code,
                            ),
                        ),
                    ),
                ),
            ),
            parseSetClassOpenOkIgnoreWhitespace("[ ] a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 3),
                    negated = true,
                    kind = ClassSet.union(ClassSetUnion(spanEx(2, 2), mutableListOf())),
                ),
                ClassSetUnion(
                    span = spanEx(2, 3),
                    items = mutableListOf(
                        ClassSetItem.Literal(
                            io.github.kotlinmania.regexsyntax.ast.Literal(
                                span = spanEx(2, 3),
                                kind = LiteralKind.Verbatim,
                                c = ']'.code,
                            ),
                        ),
                    ),
                ),
            ),
            parseSetClassOpenOk("[^]a]"),
        )
        assertEquals(
            Pair(
                ClassBracketed(
                    span = spanEx(0, 2),
                    negated = false,
                    kind = ClassSet.union(ClassSetUnion(spanEx(1, 1), mutableListOf())),
                ),
                ClassSetUnion(
                    span = spanEx(1, 2),
                    items = mutableListOf(
                        ClassSetItem.Literal(
                            io.github.kotlinmania.regexsyntax.ast.Literal(
                                span = spanEx(1, 2),
                                kind = LiteralKind.Verbatim,
                                c = '-'.code,
                            ),
                        ),
                    ),
                ),
            ),
            parseSetClassOpenOk("[-]a]"),
        )

        assertEquals(TestError(spanEx(0, 1), ErrorKind.ClassUnclosed), parseSetClassOpenErr("["))
        assertEquals(TestError(spanEx(0, 5), ErrorKind.ClassUnclosed), parseSetClassOpenErrIgnoreWhitespace("[    "))
        assertEquals(TestError(spanEx(0, 2), ErrorKind.ClassUnclosed), parseSetClassOpenErr("[^"))
        assertEquals(TestError(spanEx(0, 2), ErrorKind.ClassUnclosed), parseSetClassOpenErr("[]"))
        assertEquals(TestError(spanEx(0, 0), ErrorKind.ClassUnclosed), parseSetClassOpenErr("[-"))
        assertEquals(TestError(spanEx(0, 0), ErrorKind.ClassUnclosed), parseSetClassOpenErr("[--"))

        assertEquals(
            TestError(spanEx(4, 4), ErrorKind.ClassUnclosed),
            run {
                val ex = parser("(?x)[-#]").parseWithComments().fold(
                    onSuccess = { wc -> fail("expected parseWithComments failure, got $wc") },
                    onFailure = { t -> expectAstException(t) },
                )
                TestError(ex.err.span(), ex.err.kind())
            },
        )
    }

    @Test
    fun maybeParseAsciiClass() {
        assertEquals(
            ClassAscii(span = spanEx(0, 9), kind = ClassAsciiKind.Alnum, negated = false),
            parser("[:alnum:]").maybeParseAsciiClass(),
        )
        assertEquals(
            ClassAscii(span = spanEx(0, 9), kind = ClassAsciiKind.Alnum, negated = false),
            parser("[:alnum:]A").maybeParseAsciiClass(),
        )
        assertEquals(
            ClassAscii(span = spanEx(0, 10), kind = ClassAsciiKind.Alnum, negated = true),
            parser("[:^alnum:]").maybeParseAsciiClass(),
        )

        run {
            val p = parser("[:")
            assertEquals(null, p.maybeParseAsciiClass())
            assertEquals(0, p.offset())
        }
        run {
            val p = parser("[:^")
            assertEquals(null, p.maybeParseAsciiClass())
            assertEquals(0, p.offset())
        }
        run {
            val p = parser("[^:alnum:]")
            assertEquals(null, p.maybeParseAsciiClass())
            assertEquals(0, p.offset())
        }
        run {
            val p = parser("[:alnnum:]")
            assertEquals(null, p.maybeParseAsciiClass())
            assertEquals(0, p.offset())
        }
        run {
            val p = parser("[:alnum]")
            assertEquals(null, p.maybeParseAsciiClass())
            assertEquals(0, p.offset())
        }
        run {
            val p = parser("[:alnum:")
            assertEquals(null, p.maybeParseAsciiClass())
            assertEquals(0, p.offset())
        }
    }

    @Test
    fun parseUnicodeClass() {
        fun parseEscapeOk(pat: String): Primitive = parser(pat).parseEscape().getOrThrow()
        fun parseEscapeErr(pat: String): TestError {
            val ex = parser(pat).parseEscape().fold(
                onSuccess = { prim -> fail("expected parseEscape failure for pattern=$pat, got prim=$prim") },
                onFailure = { t -> expectAstException(t) }
            )
            return TestError(ex.err.span(), ex.err.kind())
        }

        assertEquals(
            Primitive.Unicode(ClassUnicode(spanEx(0, 3), false, ClassUnicodeKind.OneLetter('N'.code))),
            parseEscapeOk("\\pN"),
        )
        assertEquals(
            Primitive.Unicode(ClassUnicode(spanEx(0, 3), true, ClassUnicodeKind.OneLetter('N'.code))),
            parseEscapeOk("\\PN"),
        )
        assertEquals(
            Primitive.Unicode(ClassUnicode(spanEx(0, 5), false, ClassUnicodeKind.Named("N"))),
            parseEscapeOk("\\p{N}"),
        )
        assertEquals(
            Primitive.Unicode(ClassUnicode(spanEx(0, 5), true, ClassUnicodeKind.Named("N"))),
            parseEscapeOk("\\P{N}"),
        )
        assertEquals(
            Primitive.Unicode(ClassUnicode(spanEx(0, 9), false, ClassUnicodeKind.Named("Greek"))),
            parseEscapeOk("\\p{Greek}"),
        )

        assertEquals(
            Primitive.Unicode(
                ClassUnicode(
                    span = spanEx(0, 16),
                    negated = false,
                    kind = ClassUnicodeKind.NamedValue(ClassUnicodeOpKind.Colon, "scx", "Katakana"),
                ),
            ),
            parseEscapeOk("\\p{scx:Katakana}"),
        )
        assertEquals(
            Primitive.Unicode(
                ClassUnicode(
                    span = spanEx(0, 16),
                    negated = false,
                    kind = ClassUnicodeKind.NamedValue(ClassUnicodeOpKind.Equal, "scx", "Katakana"),
                ),
            ),
            parseEscapeOk("\\p{scx=Katakana}"),
        )
        assertEquals(
            Primitive.Unicode(
                ClassUnicode(
                    span = spanEx(0, 17),
                    negated = false,
                    kind = ClassUnicodeKind.NamedValue(ClassUnicodeOpKind.NotEqual, "scx", "Katakana"),
                ),
            ),
            parseEscapeOk("\\p{scx!=Katakana}"),
        )

        assertEquals(
            Primitive.Unicode(
                ClassUnicode(
                    span = spanEx(0, 5),
                    negated = false,
                    kind = ClassUnicodeKind.NamedValue(ClassUnicodeOpKind.Colon, "", ""),
                ),
            ),
            parseEscapeOk("\\p{:}"),
        )
        assertEquals(
            Primitive.Unicode(
                ClassUnicode(
                    span = spanEx(0, 5),
                    negated = false,
                    kind = ClassUnicodeKind.NamedValue(ClassUnicodeOpKind.Equal, "", ""),
                ),
            ),
            parseEscapeOk("\\p{=}"),
        )
        assertEquals(
            Primitive.Unicode(
                ClassUnicode(
                    span = spanEx(0, 6),
                    negated = false,
                    kind = ClassUnicodeKind.NamedValue(ClassUnicodeOpKind.NotEqual, "", ""),
                ),
            ),
            parseEscapeOk("\\p{!=}"),
        )

        assertEquals(TestError(spanEx(2, 2), ErrorKind.EscapeUnexpectedEof), parseEscapeErr("\\p"))
        assertEquals(TestError(spanEx(3, 3), ErrorKind.EscapeUnexpectedEof), parseEscapeErr("\\p{"))
        assertEquals(TestError(spanEx(4, 4), ErrorKind.EscapeUnexpectedEof), parseEscapeErr("\\p{N"))
        assertEquals(TestError(spanEx(8, 8), ErrorKind.EscapeUnexpectedEof), parseEscapeErr("\\p{Greek"))

        assertEquals(
            concat(
                0,
                4,
                listOf(
                    Ast.ClassUnicode(ClassUnicode(spanEx(0, 3), false, ClassUnicodeKind.OneLetter('N'.code))),
                    Ast.Literal(Literal(spanEx(3, 4), LiteralKind.Verbatim, 'z'.code)),
                ),
            ),
            parser("\\pNz").parse().getOrThrow(),
        )
        assertEquals(
            concat(
                0,
                10,
                listOf(
                    Ast.ClassUnicode(ClassUnicode(spanEx(0, 9), false, ClassUnicodeKind.Named("Greek"))),
                    Ast.Literal(Literal(spanEx(9, 10), LiteralKind.Verbatim, 'z'.code)),
                ),
            ),
            parser("\\p{Greek}z").parse().getOrThrow(),
        )
        assertEquals(TestError(spanEx(2, 3), ErrorKind.UnicodeClassInvalid), parseErr("\\p\\{"))
        assertEquals(TestError(spanEx(2, 3), ErrorKind.UnicodeClassInvalid), parseErr("\\P\\{"))
    }

    @Test
    fun parsePerlClass() {
        fun parseEscapeOk(pat: String): Primitive = parser(pat).parseEscape().getOrThrow()

        assertEquals(
            Primitive.Perl(ClassPerl(spanEx(0, 2), ClassPerlKind.Digit, false)),
            parseEscapeOk("\\d"),
        )
        assertEquals(
            Primitive.Perl(ClassPerl(spanEx(0, 2), ClassPerlKind.Digit, true)),
            parseEscapeOk("\\D"),
        )
        assertEquals(
            Primitive.Perl(ClassPerl(spanEx(0, 2), ClassPerlKind.Space, false)),
            parseEscapeOk("\\s"),
        )
        assertEquals(
            Primitive.Perl(ClassPerl(spanEx(0, 2), ClassPerlKind.Space, true)),
            parseEscapeOk("\\S"),
        )
        assertEquals(
            Primitive.Perl(ClassPerl(spanEx(0, 2), ClassPerlKind.Word, false)),
            parseEscapeOk("\\w"),
        )
        assertEquals(
            Primitive.Perl(ClassPerl(spanEx(0, 2), ClassPerlKind.Word, true)),
            parseEscapeOk("\\W"),
        )

        assertEquals(
            Ast.ClassPerl(ClassPerl(spanEx(0, 2), ClassPerlKind.Digit, false)),
            parser("\\d").parse().getOrThrow(),
        )
        assertEquals(
            concat(
                0,
                3,
                listOf(
                    Ast.ClassPerl(ClassPerl(spanEx(0, 2), ClassPerlKind.Digit, false)),
                    Ast.Literal(Literal(spanEx(2, 3), LiteralKind.Verbatim, 'z'.code)),
                ),
            ),
            parser("\\dz").parse().getOrThrow(),
        )
    }

    // This tests a bug fix where the nest limit checker wasn't decrementing
    // its depth during post-traversal, which causes long regexes to trip
    // the default limit too aggressively.
    @Test
    fun regression454NestTooBig() {
        val pattern = """
        2(?:
          [45]\\d{3}|
          7(?:
            1[0-267]|
            2[0-289]|
            3[0-29]|
            4[01]|
            5[1-3]|
            6[013]|
            7[0178]|
            91
          )|
          8(?:
            0[125]|
            [139][1-6]|
            2[0157-9]|
            41|
            6[1-35]|
            7[1-5]|
            8[1-8]|
            90
          )|
          9(?:
            0[0-2]|
            1[0-4]|
            2[568]|
            3[3-6]|
            5[5-7]|
            6[0167]|
            7[15]|
            8[0146-9]
          )
        )\\d{4}
        """.trimIndent()
        assertTrue(parserNestLimit(pattern, 50u).parse().isSuccess)
    }

    // This tests that we treat a trailing `-` in a character class as a
    // literal `-` even when whitespace mode is enabled and there is whitespace
    // after the trailing `-`.
    @Test
    fun regression455TrailingDashIgnoreWhitespace() {
        assertTrue(parser("(?x)[ / - ]").parse().isSuccess)
        assertTrue(parser("(?x)[ a - ]").parse().isSuccess)
        assertTrue(
            parser(
                """
                (?x)[
                a
                - ]
                """.trimIndent(),
            ).parse().isSuccess,
        )
        assertTrue(
            parser(
                """
                (?x)[
                a # wat
                - ]
                """.trimIndent(),
            ).parse().isSuccess,
        )

        assertTrue(parser("(?x)[ / -").parse().isFailure)
        assertTrue(parser("(?x)[ / - ").parse().isFailure)
        assertTrue(
            parser(
                """
                (?x)[
                / -
                """.trimIndent(),
            ).parse().isFailure,
        )
        assertTrue(
            parser(
                """
                (?x)[
                / - # wat
                """.trimIndent(),
            ).parse().isFailure,
        )
    }
}
