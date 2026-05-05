// port-lint: source src/hir/translate.rs
package io.github.kotlinmania.regexsyntax.hir.translate

import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.parse.ParserBuilder
import io.github.kotlinmania.regexsyntax.ast.Position
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.Alternation as AstAlternation
import io.github.kotlinmania.regexsyntax.ast.Concat as AstConcat
import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Dot
import io.github.kotlinmania.regexsyntax.hir.Error as HirError
import io.github.kotlinmania.regexsyntax.hir.ErrorKind
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.Repetition
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslateTest {
    private fun spanSplat(pos: Position): Span = Span.splat(pos)

    private fun parse(pattern: String): Ast =
        ParserBuilder.new().octal(true).build().parse(pattern).getOrThrow()

    private fun t(pattern: String): Hir {
        val ast = parse(pattern)
        val translator = TranslatorBuilder.new().utf8(true).build()
        return translator.translate(pattern, ast).getOrThrow()
    }

    private fun tBytes(pattern: String): Hir {
        val ast = parse(pattern)
        val translator = TranslatorBuilder.new().utf8(false).build()
        return translator.translate(pattern, ast).getOrThrow()
    }

    private data class TestError(
        val span: Span,
        val kind: ErrorKind,
    ) {
        fun eq(other: HirError): Boolean = span == other.span() && kind == other.kind()
    }

    private fun tErr(pattern: String): TestError {
        val ast = parse(pattern)
        val translator = TranslatorBuilder.new().utf8(true).build()
        val result = translator.translate(pattern, ast)
        val ex = result.exceptionOrNull() as HirException
        return TestError(ex.err.span(), ex.err.kind())
    }

    private fun hirLit(s: String): Hir = Hir.literal(s.encodeToByteArray())

    private fun hirBlit(bytes: ByteArray): Hir = Hir.literal(bytes)

    private fun hirCapture(index: UInt, expr: Hir): Hir =
        Hir.capture(Capture(index = index, name = null, sub = expr))

    private fun hirCaptureName(index: UInt, name: String, expr: Hir): Hir =
        Hir.capture(Capture(index = index, name = name, sub = expr))

    private fun hirQuest(greedy: Boolean, expr: Hir): Hir =
        Hir.repetition(
            Repetition(min = 0u, max = 1u, greedy = greedy, sub = expr),
        )

    private fun hirStar(greedy: Boolean, expr: Hir): Hir =
        Hir.repetition(
            Repetition(min = 0u, max = null, greedy = greedy, sub = expr),
        )

    private fun hirPlus(greedy: Boolean, expr: Hir): Hir =
        Hir.repetition(
            Repetition(min = 1u, max = null, greedy = greedy, sub = expr),
        )

    private fun hirRange(greedy: Boolean, min: UInt, max: UInt?, expr: Hir): Hir =
        Hir.repetition(
            Repetition(min = min, max = max, greedy = greedy, sub = expr),
        )

    private fun hirAlt(alts: List<Hir>): Hir = Hir.alternation(alts)

    private fun hirCat(exprs: List<Hir>): Hir = Hir.concat(exprs)

    private fun hirLook(look: Look): Hir = Hir.look(look)

    @Test
    fun empty() {
        assertEquals(t(""), Hir.empty())
        assertEquals(t("(?i)"), Hir.empty())
        assertEquals(t("()"), hirCapture(1u, Hir.empty()))
        assertEquals(t("(?:)"), Hir.empty())
        assertEquals(t("(?P<wat>)"), hirCaptureName(1u, "wat", Hir.empty()))
        assertEquals(t("|"), hirAlt(listOf(Hir.empty(), Hir.empty())))
        assertEquals(
            t("()|()"),
            hirAlt(listOf(hirCapture(1u, Hir.empty()), hirCapture(2u, Hir.empty()))),
        )
        assertEquals(
            t("(|b)"),
            hirCapture(1u, hirAlt(listOf(Hir.empty(), hirLit("b")))),
        )
        assertEquals(
            t("(a|)"),
            hirCapture(1u, hirAlt(listOf(hirLit("a"), Hir.empty()))),
        )
        assertEquals(
            t("(a||c)"),
            hirCapture(1u, hirAlt(listOf(hirLit("a"), Hir.empty(), hirLit("c")))),
        )
        assertEquals(
            t("(||)"),
            hirCapture(1u, hirAlt(listOf(Hir.empty(), Hir.empty(), Hir.empty()))),
        )
    }

    @Test
    fun literal() {
        assertEquals(t("a"), hirLit("a"))
        assertEquals(t("(?-u)a"), hirLit("a"))
        assertEquals(t("☃"), hirLit("☃"))
        assertEquals(t("abcd"), hirLit("abcd"))

        assertEquals(tBytes("(?-u)a"), hirLit("a"))
        assertEquals(tBytes("(?-u)\\x61"), hirLit("a"))
        assertEquals(tBytes("(?-u)\\xFF"), hirBlit(byteArrayOf(0xFF.toByte())))

        assertEquals(t("(?-u)☃"), hirLit("☃"))
        assertEquals(
            true,
            tErr("(?-u)\\xFF").eq(
                HirError(
                    ErrorKind.InvalidUtf8,
                    "(?-u)\\xFF",
                    Span.new(Position.new(5, 1, 6), Position.new(9, 1, 10)),
                ),
            ),
        )
    }

    @Test
    fun assertions() {
        assertEquals(t("^"), hirLook(Look.Start))
        assertEquals(t("$"), hirLook(Look.End))
        assertEquals(t("\\A"), hirLook(Look.Start))
        assertEquals(t("\\z"), hirLook(Look.End))
        assertEquals(t("(?m)^"), hirLook(Look.StartLF))
        assertEquals(t("(?m)$"), hirLook(Look.EndLF))
        assertEquals(t("(?m)\\A"), hirLook(Look.Start))
        assertEquals(t("(?m)\\z"), hirLook(Look.End))

        assertEquals(t("\\b"), hirLook(Look.WordUnicode))
        assertEquals(t("\\B"), hirLook(Look.WordUnicodeNegate))
        assertEquals(t("(?-u)\\b"), hirLook(Look.WordAscii))
        assertEquals(t("(?-u)\\B"), hirLook(Look.WordAsciiNegate))
    }

    @Test
    fun lineAnchors() {
        assertEquals(t("^"), hirLook(Look.Start))
        assertEquals(t("$"), hirLook(Look.End))
        assertEquals(t("\\A"), hirLook(Look.Start))
        assertEquals(t("\\z"), hirLook(Look.End))

        assertEquals(t("(?m)\\A"), hirLook(Look.Start))
        assertEquals(t("(?m)\\z"), hirLook(Look.End))
        assertEquals(t("(?m)^"), hirLook(Look.StartLF))
        assertEquals(t("(?m)$"), hirLook(Look.EndLF))

        assertEquals(t("(?R)\\A"), hirLook(Look.Start))
        assertEquals(t("(?R)\\z"), hirLook(Look.End))
        assertEquals(t("(?R)^"), hirLook(Look.Start))
        assertEquals(t("(?R)$"), hirLook(Look.End))

        assertEquals(t("(?Rm)\\A"), hirLook(Look.Start))
        assertEquals(t("(?Rm)\\z"), hirLook(Look.End))
        assertEquals(t("(?Rm)^"), hirLook(Look.StartCRLF))
        assertEquals(t("(?Rm)$"), hirLook(Look.EndCRLF))
    }

    @Test
    fun group() {
        assertEquals(t("(a)"), hirCapture(1u, hirLit("a")))
        assertEquals(
            t("(a)(b)"),
            hirCat(listOf(hirCapture(1u, hirLit("a")), hirCapture(2u, hirLit("b")))),
        )
        assertEquals(
            t("(a)|(b)"),
            hirAlt(listOf(hirCapture(1u, hirLit("a")), hirCapture(2u, hirLit("b")))),
        )
        assertEquals(t("(?P<foo>)"), hirCaptureName(1u, "foo", Hir.empty()))
        assertEquals(t("(?P<foo>a)"), hirCaptureName(1u, "foo", hirLit("a")))
        assertEquals(
            t("(?P<foo>a)(?P<bar>b)"),
            hirCat(listOf(
                hirCaptureName(1u, "foo", hirLit("a")),
                hirCaptureName(2u, "bar", hirLit("b")),
            )),
        )
        assertEquals(t("(?:)"), Hir.empty())
        assertEquals(t("(?:a)"), hirLit("a"))
        assertEquals(
            t("(?:a)(b)"),
            hirCat(listOf(hirLit("a"), hirCapture(1u, hirLit("b")))),
        )
        assertEquals(
            t("(a)(?:b)(c)"),
            hirCat(listOf(hirCapture(1u, hirLit("a")), hirLit("b"), hirCapture(2u, hirLit("c")))),
        )
    }

    @Test
    fun dot() {
        assertEquals(t("."), Hir.dot(Dot.AnyCharExceptLF))
        assertEquals(t("(?R)."), Hir.dot(Dot.AnyCharExceptCRLF))
        assertEquals(t("(?s)."), Hir.dot(Dot.AnyChar))
        assertEquals(t("(?Rs)."), Hir.dot(Dot.AnyChar))

        assertEquals(tBytes("(?-u)."), Hir.dot(Dot.AnyByteExceptLF))
        assertEquals(tBytes("(?R-u)."), Hir.dot(Dot.AnyByteExceptCRLF))
        assertEquals(tBytes("(?s-u)."), Hir.dot(Dot.AnyByte))
        assertEquals(tBytes("(?Rs-u)."), Hir.dot(Dot.AnyByte))

        assertEquals(
            true,
            tErr("(?-u).").eq(
                HirError(
                    ErrorKind.InvalidUtf8,
                    "(?-u).",
                    Span.new(Position.new(5, 1, 6), Position.new(6, 1, 7)),
                ),
            ),
        )
        assertEquals(
            true,
            tErr("(?R-u).").eq(
                HirError(
                    ErrorKind.InvalidUtf8,
                    "(?R-u).",
                    Span.new(Position.new(6, 1, 7), Position.new(7, 1, 8)),
                ),
            ),
        )
    }

    @Test
    fun escape() {
        assertEquals(
            t("\\\\\\.\\+\\*\\?\\(\\)\\|\\[\\]\\{\\}\\^\\$\\#"),
            hirLit("\\.+*?()|[]{}^$#"),
        )
    }

    @Test
    fun repetition() {
        assertEquals(t("a?"), hirQuest(true, hirLit("a")))
        assertEquals(t("a*"), hirStar(true, hirLit("a")))
        assertEquals(t("a+"), hirPlus(true, hirLit("a")))
        assertEquals(t("a??"), hirQuest(false, hirLit("a")))
        assertEquals(t("a*?"), hirStar(false, hirLit("a")))
        assertEquals(t("a+?"), hirPlus(false, hirLit("a")))

        assertEquals(t("a{1}"), hirRange(true, 1u, 1u, hirLit("a")))
        assertEquals(t("a{1,}"), hirRange(true, 1u, null, hirLit("a")))
        assertEquals(t("a{1,2}"), hirRange(true, 1u, 2u, hirLit("a")))
        assertEquals(t("a{1}?"), hirRange(false, 1u, 1u, hirLit("a")))
        assertEquals(t("a{1,}?"), hirRange(false, 1u, null, hirLit("a")))
        assertEquals(t("a{1,2}?"), hirRange(false, 1u, 2u, hirLit("a")))

        assertEquals(
            t("ab?"),
            hirCat(listOf(hirLit("a"), hirQuest(true, hirLit("b")))),
        )
        assertEquals(
            t("(ab)?"),
            hirQuest(true, hirCapture(1u, hirLit("ab"))),
        )
        assertEquals(
            t("a|b?"),
            hirAlt(listOf(hirLit("a"), hirQuest(true, hirLit("b")))),
        )
    }

    @Test
    fun catAlt() {
        val a = { hirLook(Look.Start) }
        val b = { hirLook(Look.End) }
        val c = { hirLook(Look.WordUnicode) }
        val d = { hirLook(Look.WordUnicodeNegate) }

        assertEquals(t("(^$)"), hirCapture(1u, hirCat(listOf(a(), b()))))
        assertEquals(t("^|$"), hirAlt(listOf(a(), b())))
        assertEquals(t("^|$|\\b"), hirAlt(listOf(a(), b(), c())))
        assertEquals(
            t("^$|$\\b|\\b\\B"),
            hirAlt(listOf(
                hirCat(listOf(a(), b())),
                hirCat(listOf(b(), c())),
                hirCat(listOf(c(), d())),
            )),
        )
        assertEquals(t("(^|$)"), hirCapture(1u, hirAlt(listOf(a(), b()))))
        assertEquals(
            t("(^|$|\\b)"),
            hirCapture(1u, hirAlt(listOf(a(), b(), c()))),
        )
        assertEquals(
            t("(^$|$\\b|\\b\\B)"),
            hirCapture(
                1u,
                hirAlt(listOf(
                    hirCat(listOf(a(), b())),
                    hirCat(listOf(b(), c())),
                    hirCat(listOf(c(), d())),
                )),
            ),
        )
        assertEquals(
            t("(^$|($\\b|(\\b\\B)))"),
            hirCapture(
                1u,
                hirAlt(listOf(
                    hirCat(listOf(a(), b())),
                    hirCapture(
                        2u,
                        hirAlt(listOf(
                            hirCat(listOf(b(), c())),
                            hirCapture(3u, hirCat(listOf(c(), d()))),
                        )),
                    ),
                )),
            ),
        )
    }

    @Test
    fun regressionAltEmptyConcat() {
        val span = spanSplat(Position.new(0, 0, 0))
        val ast = Ast.Alternation(
            AstAlternation(
                span = span,
                asts = mutableListOf(
                    Ast.Concat(
                        AstConcat(
                            span = span,
                            asts = mutableListOf(),
                        ),
                    ),
                ),
            ),
        )

        val t = Translator()
        assertEquals(Result.success(Hir.empty()), t.translate("", ast))
    }

    @Test
    fun regressionEmptyAlt() {
        val span = spanSplat(Position.new(0, 0, 0))
        val ast = Ast.Concat(
            AstConcat(
                span = span,
                asts = mutableListOf(
                    Ast.Alternation(
                        AstAlternation(
                            span = span,
                            asts = mutableListOf(),
                        ),
                    ),
                ),
            ),
        )

        val t = Translator()
        assertEquals(Result.success(Hir.fail()), t.translate("", ast))
    }

    @Test
    fun regressionSingletonAlt() {
        val span = spanSplat(Position.new(0, 0, 0))
        val ast = Ast.Concat(
            AstConcat(
                span = span,
                asts = mutableListOf(
                    Ast.Alternation(
                        AstAlternation(
                            span = span,
                            asts = mutableListOf<Ast>(Ast.Dot(span)),
                        ),
                    ),
                ),
            ),
        )

        val t = Translator()
        assertEquals(Result.success(Hir.dot(Dot.AnyCharExceptLF)), t.translate("", ast))
    }
}
