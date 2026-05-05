// port-lint: source src/ast/parse.rs
package io.github.kotlinmania.regexsyntax.ast.parse

import io.github.kotlinmania.regexsyntax.ast.ErrorKind
import io.github.kotlinmania.regexsyntax.ast.Position
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.Alternation
import io.github.kotlinmania.regexsyntax.ast.ClassPerl
import io.github.kotlinmania.regexsyntax.ast.ClassPerlKind
import io.github.kotlinmania.regexsyntax.ast.Concat
import io.github.kotlinmania.regexsyntax.ast.Literal
import io.github.kotlinmania.regexsyntax.ast.LiteralKind
import io.github.kotlinmania.regexsyntax.ast.Repetition
import io.github.kotlinmania.regexsyntax.ast.RepetitionKind
import io.github.kotlinmania.regexsyntax.ast.RepetitionOp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParseTest {
    private fun parser(pattern: String): ParserI = ParserI(Parser(), pattern)

    private fun parserNestLimit(pattern: String, nestLimit: UInt): ParserI {
        val p = ParserBuilder.new().nestLimit(nestLimit).build()
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

    private data class TestError(
        val span: Span,
        val kind: ErrorKind,
    )

    private fun parseErr(pattern: String): TestError {
        val result = parser(pattern).parse()
        val ex = result.exceptionOrNull() as AstException
        return TestError(ex.err.span(), ex.err.kind())
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
        val ex = result.exceptionOrNull() as AstException
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
    fun parsePerlClass() {
        assertEquals(
            Ast.ClassPerl(
                ClassPerl(
                    span = spanEx(0, 2),
                    kind = ClassPerlKind.Digit,
                    negated = false,
                ),
            ),
            Parser().parse("\\d").getOrThrow(),
        )

        assertEquals(
            Ast.ClassPerl(
                ClassPerl(
                    span = spanEx(0, 2),
                    kind = ClassPerlKind.Digit,
                    negated = true,
                ),
            ),
            Parser().parse("\\D").getOrThrow(),
        )

        assertEquals(
            Ast.ClassPerl(
                ClassPerl(
                    span = spanEx(0, 2),
                    kind = ClassPerlKind.Space,
                    negated = false,
                ),
            ),
            Parser().parse("\\s").getOrThrow(),
        )

        assertEquals(
            Ast.ClassPerl(
                ClassPerl(
                    span = spanEx(0, 2),
                    kind = ClassPerlKind.Word,
                    negated = true,
                ),
            ),
            Parser().parse("\\W").getOrThrow(),
        )

        assertEquals(
            Ast.Concat(
                Concat(
                    span = spanEx(0, 3),
                    asts = mutableListOf(
                        Ast.ClassPerl(
                            ClassPerl(
                                span = spanEx(0, 2),
                                kind = ClassPerlKind.Digit,
                                negated = false,
                            ),
                        ),
                        Ast.Literal(
                            Literal(
                                span = spanEx(2, 3),
                                kind = LiteralKind.Verbatim,
                                c = 'z'.code,
                            ),
                        ),
                    ),
                ),
            ),
            Parser().parse("\\dz").getOrThrow(),
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
