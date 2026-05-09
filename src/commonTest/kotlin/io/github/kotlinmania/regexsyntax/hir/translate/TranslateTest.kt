// port-lint: source hir/translate.rs
package io.github.kotlinmania.regexsyntax.hir.translate

import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.ClassAsciiKind
import io.github.kotlinmania.regexsyntax.ast.parse.ParserBuilder
import io.github.kotlinmania.regexsyntax.ast.Position
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.Alternation as AstAlternation
import io.github.kotlinmania.regexsyntax.ast.Concat as AstConcat
import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Class
import io.github.kotlinmania.regexsyntax.hir.ClassBytes
import io.github.kotlinmania.regexsyntax.hir.ClassBytesRange
import io.github.kotlinmania.regexsyntax.hir.ClassUnicode
import io.github.kotlinmania.regexsyntax.hir.ClassUnicodeRange
import io.github.kotlinmania.regexsyntax.hir.Dot
import io.github.kotlinmania.regexsyntax.hir.Error as HirError
import io.github.kotlinmania.regexsyntax.hir.ErrorKind
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.Properties
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.regexsyntax.unicode.ClassQuery
import io.github.kotlinmania.regexsyntax.unicode.perlWord
import io.github.kotlinmania.regexsyntax.unicode.unicodeClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    private fun tErr(pattern: String): HirError {
        val ast = parse(pattern)
        val translator = TranslatorBuilder.new().utf8(true).build()
        val result = translator.translate(pattern, ast)
        val ex = result.exceptionOrNull() as HirException
        return ex.err
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

    private fun props(pattern: String): Properties = t(pattern).properties()

    private fun propsBytes(pattern: String): Properties = tBytes(pattern).properties()

    private fun hirUclassQuery(query: ClassQuery): Hir =
        Hir.`class`(Class.Unicode(unicodeClass(query).getOrThrow()))

    private fun hirUclassPerlWord(): Hir =
        Hir.`class`(Class.Unicode(perlWord().getOrThrow()))

    private fun hirAsciiUclass(kind: ClassAsciiKind): Hir =
        Hir.`class`(Class.Unicode(ClassUnicode.new(
            asciiClassAsChars(kind).map { (start, end) -> ClassUnicodeRange.new(start, end) },
        )))

    private fun hirAsciiBclass(kind: ClassAsciiKind): Hir =
        Hir.`class`(Class.Bytes(ClassBytes.new(
            asciiClass(kind).map { (start, end) -> ClassBytesRange.new(start.toByte(), end.toByte()) },
        )))

    private fun hirUclass(vararg ranges: Pair<Int, Int>): Hir = Hir.`class`(uclass(*ranges))

    private fun hirBclass(vararg ranges: Pair<Int, Int>): Hir = Hir.`class`(bclass(*ranges))

    private fun hirCaseFold(expr: Hir): Hir {
        return when (val kind = expr.intoKind()) {
            is HirKind.Class -> {
                kind.value.caseFoldSimple()
                Hir.`class`(kind.value)
            }
            else -> throw IllegalStateException("cannot case fold non-class Hir expr")
        }
    }

    private fun hirNegate(expr: Hir): Hir {
        return when (val kind = expr.intoKind()) {
            is HirKind.Class -> {
                kind.value.negate()
                Hir.`class`(kind.value)
            }
            else -> throw IllegalStateException("cannot negate non-class Hir expr")
        }
    }

    private fun uclass(vararg ranges: Pair<Int, Int>): Class =
        Class.Unicode(ClassUnicode.new(ranges.map { (start, end) -> ClassUnicodeRange.new(start, end) }))

    private fun bclass(vararg ranges: Pair<Int, Int>): Class =
        Class.Bytes(ClassBytes.new(ranges.map { (start, end) -> ClassBytesRange.new(start.toByte(), end.toByte()) }))

    private fun classCaseFold(cls: Class): Hir {
        cls.caseFoldSimple()
        return Hir.`class`(cls)
    }

    private fun classNegate(cls: Class): Hir {
        cls.negate()
        return Hir.`class`(cls)
    }

    private fun hirUnion(expr1: Hir, expr2: Hir): Hir {
        return when (val kind1 = expr1.intoKind()) {
            is HirKind.Class -> when (val kind2 = expr2.intoKind()) {
                is HirKind.Class -> when (val cls1 = kind1.value) {
                    is Class.Unicode -> {
                        cls1.value.union((kind2.value as Class.Unicode).value)
                        Hir.`class`(Class.Unicode(cls1.value))
                    }
                    is Class.Bytes -> {
                        cls1.value.union((kind2.value as Class.Bytes).value)
                        Hir.`class`(Class.Bytes(cls1.value))
                    }
                }
                else -> throw IllegalStateException("cannot union non-class Hir exprs")
            }
            else -> throw IllegalStateException("cannot union non-class Hir exprs")
        }
    }

    private fun hirDifference(expr1: Hir, expr2: Hir): Hir {
        return when (val kind1 = expr1.intoKind()) {
            is HirKind.Class -> when (val kind2 = expr2.intoKind()) {
                is HirKind.Class -> when (val cls1 = kind1.value) {
                    is Class.Unicode -> {
                        cls1.value.difference((kind2.value as Class.Unicode).value)
                        Hir.`class`(Class.Unicode(cls1.value))
                    }
                    is Class.Bytes -> {
                        cls1.value.difference((kind2.value as Class.Bytes).value)
                        Hir.`class`(Class.Bytes(cls1.value))
                    }
                }
                else -> throw IllegalStateException("cannot difference non-class Hir exprs")
            }
            else -> throw IllegalStateException("cannot difference non-class Hir exprs")
        }
    }

    private fun hirLook(look: Look): Hir = Hir.look(look)

    private fun c(ch: Char): Int = ch.code

    private fun asciiClass(kind: ClassAsciiKind): List<Pair<Int, Int>> = when (kind) {
        ClassAsciiKind.Alnum -> listOf(c('0') to c('9'), c('A') to c('Z'), c('a') to c('z'))
        ClassAsciiKind.Alpha -> listOf(c('A') to c('Z'), c('a') to c('z'))
        ClassAsciiKind.Ascii -> listOf(0x00 to 0x7F)
        ClassAsciiKind.Blank -> listOf(c('\t') to c('\t'), c(' ') to c(' '))
        ClassAsciiKind.Cntrl -> listOf(0x00 to 0x1F, 0x7F to 0x7F)
        ClassAsciiKind.Digit -> listOf(c('0') to c('9'))
        ClassAsciiKind.Graph -> listOf(c('!') to c('~'))
        ClassAsciiKind.Lower -> listOf(c('a') to c('z'))
        ClassAsciiKind.Print -> listOf(c(' ') to c('~'))
        ClassAsciiKind.Punct -> listOf(c('!') to c('/'), c(':') to c('@'), c('[') to c('`'), c('{') to c('~'))
        ClassAsciiKind.Space -> listOf(c('\t') to c('\t'), c('\n') to c('\n'), 0x0B to 0x0B, 0x0C to 0x0C, c('\r') to c('\r'), c(' ') to c(' '))
        ClassAsciiKind.Upper -> listOf(c('A') to c('Z'))
        ClassAsciiKind.Word -> listOf(c('0') to c('9'), c('A') to c('Z'), c('_') to c('_'), c('a') to c('z'))
        ClassAsciiKind.Xdigit -> listOf(c('0') to c('9'), c('A') to c('F'), c('a') to c('f'))
    }

    private fun asciiClassAsChars(kind: ClassAsciiKind): List<Pair<Int, Int>> = asciiClass(kind)

    private fun rustEscaped(s: String): String {
        val out = StringBuilder()
        var index = 0
        while (index < s.length) {
            val ch = s[index]
            if (ch != '\\' || index + 1 >= s.length) {
                out.append(ch)
                index++
                continue
            }
            when (val next = s[index + 1]) {
                '0' -> {
                    out.append('\u0000')
                    index += 2
                }
                'r' -> {
                    out.append('\r')
                    index += 2
                }
                'u' -> {
                    val open = index + 2
                    check(open < s.length && s[open] == '{')
                    val close = s.indexOf('}', open)
                    check(close >= 0)
                    val codepoint = s.substring(open + 1, close).toInt(16)
                    out.appendCodepoint(codepoint)
                    index = close + 1
                }
                else -> {
                    out.append('\\')
                    out.append(next)
                    index += 2
                }
            }
        }
        return out.toString()
    }

    private fun StringBuilder.appendCodepoint(codepoint: Int): StringBuilder {
        if (codepoint <= 0xFFFF) {
            append(codepoint.toChar())
        } else {
            val value = codepoint - 0x10000
            append((0xD800 or (value ushr 10)).toChar())
            append((0xDC00 or (value and 0x3FF)).toChar())
        }
        return this
    }

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
        assertEquals(tBytes("(?-u)a"), hirLit("a"))
        assertEquals(tBytes("""(?-u)\x61"""), hirLit("a"))
        assertEquals(tBytes("""(?-u)\xFF"""), hirBlit(byteArrayOf(0xFF.toByte())))

        assertEquals(t("(?-u)☃"), hirLit("☃"))
        assertTrue(
            TestError(
                span = Span.new(Position.new(5, 1, 6), Position.new(9, 1, 10)),
                kind = ErrorKind.InvalidUtf8,
            ).eq(tErr("""(?-u)\xFF""")),
        )
    }

    @Test
    fun literal_case_insensitive() {
        assertEquals(t("(?i)a"), hirUclass(c('A') to c('A'), c('a') to c('a')))
        assertEquals(t("(?i:a)"), hirUclass(c('A') to c('A'), c('a') to c('a')))
        assertEquals(
            t("a(?i)a(?-i)a"),
            hirCat(listOf(
                hirLit("a"),
                hirUclass(c('A') to c('A'), c('a') to c('a')),
                hirLit("a"),
            )),
        )
        assertEquals(
            t("(?i)ab@c"),
            hirCat(listOf(
                hirUclass(c('A') to c('A'), c('a') to c('a')),
                hirUclass(c('B') to c('B'), c('b') to c('b')),
                hirLit("@"),
                hirUclass(c('C') to c('C'), c('c') to c('c')),
            )),
        )
        assertEquals(
            t("(?i)β"),
            hirUclass(c('Β') to c('Β'), c('β') to c('β'), c('ϐ') to c('ϐ')),
        )

        assertEquals(t("(?i-u)a"), hirBclass(c('A') to c('A'), c('a') to c('a')))
        assertEquals(
            t("(?-u)a(?i)a(?-i)a"),
            hirCat(listOf(
                hirLit("a"),
                hirBclass(c('A') to c('A'), c('a') to c('a')),
                hirLit("a"),
            )),
        )
        assertEquals(
            t("(?i-u)ab@c"),
            hirCat(listOf(
                hirBclass(c('A') to c('A'), c('a') to c('a')),
                hirBclass(c('B') to c('B'), c('b') to c('b')),
                hirLit("@"),
                hirBclass(c('C') to c('C'), c('c') to c('c')),
            )),
        )

        assertEquals(
            tBytes("(?i-u)a"),
            hirBclass(c('A') to c('A'), c('a') to c('a')),
        )
        assertEquals(
            tBytes("(?i-u)a"),
            hirBclass(c('A') to c('A'), c('a') to c('a')),
        )
        assertEquals(
            tBytes("""(?i-u)\x61"""),
            hirBclass(c('A') to c('A'), c('a') to c('a')),
        )
        assertEquals(tBytes("""(?i-u)\xFF"""), hirBlit(byteArrayOf(0xFF.toByte())))

        assertEquals(t("(?i-u)β"), hirLit("β"))
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
    fun line_anchors() {
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
        assertEquals(
            t("(a)(?P<foo>b)(c)"),
            hirCat(listOf(
                hirCapture(1u, hirLit("a")),
                hirCaptureName(2u, "foo", hirLit("b")),
                hirCapture(3u, hirLit("c")),
            )),
        )
        assertEquals(t("()"), hirCapture(1u, Hir.empty()))
        assertEquals(t("((?i))"), hirCapture(1u, Hir.empty()))
        assertEquals(t("((?x))"), hirCapture(1u, Hir.empty()))
        assertEquals(
            t("(((?x)))"),
            hirCapture(1u, hirCapture(2u, Hir.empty())),
        )
    }

    @Test
    fun flags() {
        assertEquals(
            t("(?i:a)a"),
            hirCat(listOf(hirUclass(c('A') to c('A'), c('a') to c('a')), hirLit("a"))),
        )
        assertEquals(
            t("(?i-u:a)β"),
            hirCat(listOf(
                hirBclass(c('A') to c('A'), c('a') to c('a')),
                hirLit("β"),
            )),
        )
        assertEquals(
            t("(?:(?i-u)a)b"),
            hirCat(listOf(
                hirBclass(c('A') to c('A'), c('a') to c('a')),
                hirLit("b"),
            )),
        )
        assertEquals(
            t("((?i-u)a)b"),
            hirCat(listOf(
                hirCapture(1u, hirBclass(c('A') to c('A'), c('a') to c('a'))),
                hirLit("b"),
            )),
        )
        assertEquals(
            t("(?i)(?-i:a)a"),
            hirCat(listOf(hirLit("a"), hirUclass(c('A') to c('A'), c('a') to c('a')))),
        )
        assertEquals(
            t("(?im)a^"),
            hirCat(listOf(
                hirUclass(c('A') to c('A'), c('a') to c('a')),
                hirLook(Look.StartLF),
            )),
        )
        assertEquals(
            t("(?im)a^(?i-m)a^"),
            hirCat(listOf(
                hirUclass(c('A') to c('A'), c('a') to c('a')),
                hirLook(Look.StartLF),
                hirUclass(c('A') to c('A'), c('a') to c('a')),
                hirLook(Look.Start),
            )),
        )
        assertEquals(
            t("(?U)a*a*?(?-U)a*a*?"),
            hirCat(listOf(
                hirStar(false, hirLit("a")),
                hirStar(true, hirLit("a")),
                hirStar(true, hirLit("a")),
                hirStar(false, hirLit("a")),
            )),
        )
        assertEquals(
            t("(?:a(?i)a)a"),
            hirCat(listOf(
                hirCat(listOf(
                    hirLit("a"),
                    hirUclass(c('A') to c('A'), c('a') to c('a')),
                )),
                hirLit("a"),
            )),
        )
        assertEquals(
            t("(?i)(?:a(?-i)a)a"),
            hirCat(listOf(
                hirCat(listOf(
                    hirUclass(c('A') to c('A'), c('a') to c('a')),
                    hirLit("a"),
                )),
                hirUclass(c('A') to c('A'), c('a') to c('a')),
            )),
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

        assertTrue(
            TestError(
                span = Span.new(Position.new(5, 1, 6), Position.new(6, 1, 7)),
                kind = ErrorKind.InvalidUtf8,
            ).eq(tErr("(?-u).")),
        )
        assertTrue(
            TestError(
                span = Span.new(Position.new(6, 1, 7), Position.new(7, 1, 8)),
                kind = ErrorKind.InvalidUtf8,
            ).eq(tErr("(?R-u).")),
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
    fun cat_alt() {
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

    // Tests the HIR transformation of things like '[a-z]|[A-Z]' into
    // '[A-Za-z]'. In other words, an alternation of just classes is always
    // equivalent to a single class corresponding to the union of the branches
    // in that class. (Unless some branches match invalid UTF-8 and others
    // match non-ASCII Unicode.)
    @Test
    fun cat_class_flattened() {
        assertEquals(t("""[a-z]|[A-Z]"""), hirUclass(c('A') to c('Z'), c('a') to c('z')))
        // Combining all of the letter properties should give us the one giant
        // letter property.
        assertEquals(
            t("""(?x)
                \p{Lowercase_Letter}
                |\p{Uppercase_Letter}
                |\p{Titlecase_Letter}
                |\p{Modifier_Letter}
                |\p{Other_Letter}
            """),
            hirUclassQuery(ClassQuery.Binary("letter")),
        )
        // Byte classes that can truly match invalid UTF-8 cannot be combined
        // with Unicode classes.
        assertEquals(
            tBytes("""[Δδ]|(?-u:[\x90-\xFF])|[Λλ]"""),
            hirAlt(listOf(
                hirUclass(c('Δ') to c('Δ'), c('δ') to c('δ')),
                hirBclass(0x90 to 0xFF),
                hirUclass(c('Λ') to c('Λ'), c('λ') to c('λ')),
            )),
        )
        // Byte classes on their own can be combined, even if some are ASCII
        // and others are invalid UTF-8.
        assertEquals(
            tBytes("""[a-z]|(?-u:[\x90-\xFF])|[A-Z]"""),
            hirBclass(c('A') to c('Z'), c('a') to c('z'), 0x90 to 0xFF),
        )
    }

    @Test
    fun class_ascii() {
        assertEquals(t("[[:alnum:]]"), hirAsciiUclass(ClassAsciiKind.Alnum))
        assertEquals(t("[[:alpha:]]"), hirAsciiUclass(ClassAsciiKind.Alpha))
        assertEquals(t("[[:ascii:]]"), hirAsciiUclass(ClassAsciiKind.Ascii))
        assertEquals(t("[[:blank:]]"), hirAsciiUclass(ClassAsciiKind.Blank))
        assertEquals(t("[[:cntrl:]]"), hirAsciiUclass(ClassAsciiKind.Cntrl))
        assertEquals(t("[[:digit:]]"), hirAsciiUclass(ClassAsciiKind.Digit))
        assertEquals(t("[[:graph:]]"), hirAsciiUclass(ClassAsciiKind.Graph))
        assertEquals(t("[[:lower:]]"), hirAsciiUclass(ClassAsciiKind.Lower))
        assertEquals(t("[[:print:]]"), hirAsciiUclass(ClassAsciiKind.Print))
        assertEquals(t("[[:punct:]]"), hirAsciiUclass(ClassAsciiKind.Punct))
        assertEquals(t("[[:space:]]"), hirAsciiUclass(ClassAsciiKind.Space))
        assertEquals(t("[[:upper:]]"), hirAsciiUclass(ClassAsciiKind.Upper))
        assertEquals(t("[[:word:]]"), hirAsciiUclass(ClassAsciiKind.Word))
        assertEquals(t("[[:xdigit:]]"), hirAsciiUclass(ClassAsciiKind.Xdigit))

        assertEquals(t("[[:^lower:]]"), hirNegate(hirAsciiUclass(ClassAsciiKind.Lower)))
        assertEquals(
            t("(?i)[[:lower:]]"),
            hirUclass(c('A') to c('Z'), c('a') to c('z'), 0x017F to 0x017F, 0x212A to 0x212A),
        )

        assertEquals(t("(?-u)[[:lower:]]"), hirAsciiBclass(ClassAsciiKind.Lower))
        assertEquals(t("(?i-u)[[:lower:]]"), hirCaseFold(hirAsciiBclass(ClassAsciiKind.Lower)))

        assertTrue(
            TestError(
                span = Span.new(Position.new(6, 1, 7), Position.new(16, 1, 17)),
                kind = ErrorKind.InvalidUtf8,
            ).eq(tErr("(?-u)[[:^lower:]]")),
        )
        assertTrue(
            TestError(
                span = Span.new(Position.new(7, 1, 8), Position.new(17, 1, 18)),
                kind = ErrorKind.InvalidUtf8,
            ).eq(tErr("(?i-u)[[:^lower:]]")),
        )
    }

    @Test
    fun class_ascii_multiple() {
        // See: https://github.com/rust-lang/regex/issues/680
        assertEquals(
            t("[[:alnum:][:^ascii:]]"),
            hirUnion(
                hirAsciiUclass(ClassAsciiKind.Alnum),
                hirUclass(0x80 to 0x10FFFF),
            ),
        )
        assertEquals(
            tBytes("(?-u)[[:alnum:][:^ascii:]]"),
            hirUnion(
                hirAsciiBclass(ClassAsciiKind.Alnum),
                hirBclass(0x80 to 0xFF),
            ),
        )
    }

    @Test
    fun class_perl_unicode() {
        // Unicode
        assertEquals(t("""\d"""), hirUclassQuery(ClassQuery.Binary("digit")))
        assertEquals(t("""\s"""), hirUclassQuery(ClassQuery.Binary("space")))
        assertEquals(t("""\w"""), hirUclassPerlWord())
        assertEquals(t("""(?i)\d"""), hirUclassQuery(ClassQuery.Binary("digit")))
        assertEquals(t("""(?i)\s"""), hirUclassQuery(ClassQuery.Binary("space")))
        assertEquals(t("""(?i)\w"""), hirUclassPerlWord())

        // Unicode, negated
        assertEquals(t("""\D"""), hirNegate(hirUclassQuery(ClassQuery.Binary("digit"))))
        assertEquals(t("""\S"""), hirNegate(hirUclassQuery(ClassQuery.Binary("space"))))
        assertEquals(t("""\W"""), hirNegate(hirUclassPerlWord()))
        assertEquals(t("""(?i)\D"""), hirNegate(hirUclassQuery(ClassQuery.Binary("digit"))))
        assertEquals(t("""(?i)\S"""), hirNegate(hirUclassQuery(ClassQuery.Binary("space"))))
        assertEquals(t("""(?i)\W"""), hirNegate(hirUclassPerlWord()))
    }

    @Test
    fun class_perl_ascii() {
        // ASCII only
        assertEquals(t("""(?-u)\d"""), hirAsciiBclass(ClassAsciiKind.Digit))
        assertEquals(t("""(?-u)\s"""), hirAsciiBclass(ClassAsciiKind.Space))
        assertEquals(t("""(?-u)\w"""), hirAsciiBclass(ClassAsciiKind.Word))
        assertEquals(t("""(?i-u)\d"""), hirAsciiBclass(ClassAsciiKind.Digit))
        assertEquals(t("""(?i-u)\s"""), hirAsciiBclass(ClassAsciiKind.Space))
        assertEquals(t("""(?i-u)\w"""), hirAsciiBclass(ClassAsciiKind.Word))

        // ASCII only, negated
        assertEquals(tBytes("""(?-u)\D"""), hirNegate(hirAsciiBclass(ClassAsciiKind.Digit)))
        assertEquals(tBytes("""(?-u)\S"""), hirNegate(hirAsciiBclass(ClassAsciiKind.Space)))
        assertEquals(tBytes("""(?-u)\W"""), hirNegate(hirAsciiBclass(ClassAsciiKind.Word)))
        assertEquals(tBytes("""(?i-u)\D"""), hirNegate(hirAsciiBclass(ClassAsciiKind.Digit)))
        assertEquals(tBytes("""(?i-u)\S"""), hirNegate(hirAsciiBclass(ClassAsciiKind.Space)))
        assertEquals(tBytes("""(?i-u)\W"""), hirNegate(hirAsciiBclass(ClassAsciiKind.Word)))

        // ASCII only, negated, with UTF-8 mode enabled.
        // In this case, negating any Perl class results in an error because
        // all such classes can match invalid UTF-8.
        for (pattern in listOf("""(?-u)\D""", """(?-u)\S""", """(?-u)\W""")) {
            assertTrue(
                TestError(
                    span = Span.new(Position.new(5, 1, 6), Position.new(7, 1, 8)),
                    kind = ErrorKind.InvalidUtf8,
                ).eq(tErr(pattern)),
            )
        }
        for (pattern in listOf("""(?i-u)\D""", """(?i-u)\S""", """(?i-u)\W""")) {
            assertTrue(
                TestError(
                    span = Span.new(Position.new(6, 1, 7), Position.new(8, 1, 9)),
                    kind = ErrorKind.InvalidUtf8,
                ).eq(tErr(pattern)),
            )
        }
    }

    @Test
    fun class_unicode_gencat() {
        assertEquals(t("""\pZ"""), hirUclassQuery(ClassQuery.Binary("Z")))
        assertEquals(t("""\pz"""), hirUclassQuery(ClassQuery.Binary("Z")))
        assertEquals(t("""\p{Separator}"""), hirUclassQuery(ClassQuery.Binary("Z")))
        assertEquals(t("""\p{se      PaRa ToR}"""), hirUclassQuery(ClassQuery.Binary("Z")))
        assertEquals(t("""\p{gc:Separator}"""), hirUclassQuery(ClassQuery.Binary("Z")))
        assertEquals(t("""\p{gc=Separator}"""), hirUclassQuery(ClassQuery.Binary("Z")))
        assertEquals(t("""\p{gc!=Separator}"""), hirNegate(hirUclassQuery(ClassQuery.Binary("Z"))))
        assertEquals(t("""\p{Other}"""), hirUclassQuery(ClassQuery.Binary("Other")))
        assertEquals(t("""\pC"""), hirUclassQuery(ClassQuery.Binary("Other")))

        assertEquals(t("""\PZ"""), hirNegate(hirUclassQuery(ClassQuery.Binary("Z"))))
        assertEquals(t("""\P{separator}"""), hirNegate(hirUclassQuery(ClassQuery.Binary("Z"))))
        assertEquals(t("""\P{gc!=separator}"""), hirUclassQuery(ClassQuery.Binary("Z")))

        assertEquals(t("""\p{any}"""), hirUclassQuery(ClassQuery.Binary("Any")))
        assertEquals(t("""\p{assigned}"""), hirUclassQuery(ClassQuery.Binary("Assigned")))
        assertEquals(t("""\p{ascii}"""), hirUclassQuery(ClassQuery.Binary("ASCII")))
        assertEquals(t("""\p{gc:any}"""), hirUclassQuery(ClassQuery.Binary("Any")))
        assertEquals(t("""\p{gc:assigned}"""), hirUclassQuery(ClassQuery.Binary("Assigned")))
        assertEquals(t("""\p{gc:ascii}"""), hirUclassQuery(ClassQuery.Binary("ASCII")))

        assertTrue(
            TestError(
                span = Span.new(Position.new(5, 1, 6), Position.new(8, 1, 9)),
                kind = ErrorKind.UnicodeNotAllowed,
            ).eq(tErr("""(?-u)\pZ""")),
        )
        assertTrue(
            TestError(
                span = Span.new(Position.new(5, 1, 6), Position.new(18, 1, 19)),
                kind = ErrorKind.UnicodeNotAllowed,
            ).eq(tErr("""(?-u)\p{Separator}""")),
        )
        assertTrue(
            TestError(
                span = Span.new(Position.new(0, 1, 1), Position.new(3, 1, 4)),
                kind = ErrorKind.UnicodePropertyNotFound,
            ).eq(tErr("""\pE""")),
        )
        assertTrue(
            TestError(
                span = Span.new(Position.new(0, 1, 1), Position.new(7, 1, 8)),
                kind = ErrorKind.UnicodePropertyNotFound,
            ).eq(tErr("""\p{Foo}""")),
        )
        assertTrue(
            TestError(
                span = Span.new(Position.new(0, 1, 1), Position.new(10, 1, 11)),
                kind = ErrorKind.UnicodePropertyValueNotFound,
            ).eq(tErr("""\p{gc:Foo}""")),
        )
    }

    @Test
    fun class_unicode_script() {
        assertEquals(t("""\p{Greek}"""), hirUclassQuery(ClassQuery.Binary("Greek")))
        assertEquals(
            t("""(?i)\p{Greek}"""),
            hirCaseFold(hirUclassQuery(ClassQuery.Binary("Greek"))),
        )
        assertEquals(
            t("""(?i)\P{Greek}"""),
            hirNegate(hirCaseFold(hirUclassQuery(ClassQuery.Binary("Greek")))),
        )

        assertTrue(
            TestError(
                span = Span.new(Position.new(0, 1, 1), Position.new(10, 1, 11)),
                kind = ErrorKind.UnicodePropertyValueNotFound,
            ).eq(tErr("""\p{sc:Foo}""")),
        )
        assertTrue(
            TestError(
                span = Span.new(Position.new(0, 1, 1), Position.new(11, 1, 12)),
                kind = ErrorKind.UnicodePropertyValueNotFound,
            ).eq(tErr("""\p{scx:Foo}""")),
        )
    }

    @Test
    fun class_unicode_age() {
        assertTrue(
            TestError(
                span = Span.new(Position.new(0, 1, 1), Position.new(11, 1, 12)),
                kind = ErrorKind.UnicodePropertyValueNotFound,
            ).eq(tErr("""\p{age:Foo}""")),
        )
    }

    @Test
    fun class_unicode_any_empty() {
        assertEquals(t("""\P{any}"""), hirUclass())
    }

    @Test
    fun class_bracketed() {
        assertEquals(t("[a]"), hirLit("a"))
        assertEquals(t("[ab]"), hirUclass(c('a') to c('b')))
        assertEquals(t("[^[a]]"), classNegate(uclass(c('a') to c('a'))))
        assertEquals(t("[a-z]"), hirUclass(c('a') to c('z')))
        assertEquals(t("[a-fd-h]"), hirUclass(c('a') to c('h')))
        assertEquals(t("[a-fg-m]"), hirUclass(c('a') to c('m')))
        assertEquals(t("""[\x00]"""), hirUclass(0 to 0))
        assertEquals(t("""[\n]"""), hirUclass(c('\n') to c('\n')))
        assertEquals(t("[\n]"), hirUclass(c('\n') to c('\n')))
        assertEquals(t("""[\d]"""), hirUclassQuery(ClassQuery.Binary("digit")))
        assertEquals(t("""[\pZ]"""), hirUclassQuery(ClassQuery.Binary("separator")))
        assertEquals(t("""[\p{separator}]"""), hirUclassQuery(ClassQuery.Binary("separator")))
        assertEquals(t("""[^\D]"""), hirUclassQuery(ClassQuery.Binary("digit")))
        assertEquals(t("""[^\PZ]"""), hirUclassQuery(ClassQuery.Binary("separator")))
        assertEquals(t("""[^\P{separator}]"""), hirUclassQuery(ClassQuery.Binary("separator")))
        assertEquals(t("""(?i)[^\D]"""), hirUclassQuery(ClassQuery.Binary("digit")))
        assertEquals(
            t("""(?i)[^\P{greek}]"""),
            hirCaseFold(hirUclassQuery(ClassQuery.Binary("greek"))),
        )

        assertEquals(t("(?-u)[a]"), hirBclass(c('a') to c('a')))
        assertEquals(t("""(?-u)[\x00]"""), hirBclass(0 to 0))
        assertEquals(tBytes("""(?-u)[\xFF]"""), hirBclass(0xFF to 0xFF))

        assertEquals(t("(?i)[a]"), hirUclass(c('A') to c('A'), c('a') to c('a')))
        assertEquals(
            t("(?i)[k]"),
            hirUclass(c('K') to c('K'), c('k') to c('k'), 0x212A to 0x212A),
        )
        assertEquals(
            t("(?i)[β]"),
            hirUclass(c('Β') to c('Β'), c('β') to c('β'), c('ϐ') to c('ϐ')),
        )
        assertEquals(t("(?i-u)[k]"), hirBclass(c('K') to c('K'), c('k') to c('k')))

        assertEquals(t("[^a]"), classNegate(uclass(c('a') to c('a'))))
        assertEquals(t("""[^\x00]"""), classNegate(uclass(0 to 0)))
        assertEquals(tBytes("(?-u)[^a]"), classNegate(bclass(c('a') to c('a'))))
        assertEquals(t("""[^\d]"""), hirNegate(hirUclassQuery(ClassQuery.Binary("digit"))))
        assertEquals(t("""[^\pZ]"""), hirNegate(hirUclassQuery(ClassQuery.Binary("separator"))))
        assertEquals(t("""[^\p{separator}]"""), hirNegate(hirUclassQuery(ClassQuery.Binary("separator"))))
        assertEquals(
            t("""(?i)[^\p{greek}]"""),
            hirNegate(hirCaseFold(hirUclassQuery(ClassQuery.Binary("greek")))),
        )
        assertEquals(
            t("""(?i)[\P{greek}]"""),
            hirNegate(hirCaseFold(hirUclassQuery(ClassQuery.Binary("greek")))),
        )

        // Test some weird cases.
        assertEquals(t("""[\[]"""), hirUclass(c('[') to c('[')))

        assertEquals(t("""[&]"""), hirUclass(c('&') to c('&')))
        assertEquals(t("""[\&]"""), hirUclass(c('&') to c('&')))
        assertEquals(t("""[\&\&]"""), hirUclass(c('&') to c('&')))
        assertEquals(t("""[\x00-&]"""), hirUclass(0 to c('&')))
        assertEquals(t("""[&-\xFF]"""), hirUclass(c('&') to 0xFF))

        assertEquals(t("""[~]"""), hirUclass(c('~') to c('~')))
        assertEquals(t("""[\~]"""), hirUclass(c('~') to c('~')))
        assertEquals(t("""[\~\~]"""), hirUclass(c('~') to c('~')))
        assertEquals(t("""[\x00-~]"""), hirUclass(0 to c('~')))
        assertEquals(t("""[~-\xFF]"""), hirUclass(c('~') to 0xFF))

        assertEquals(t("""[-]"""), hirUclass(c('-') to c('-')))
        assertEquals(t("""[\-]"""), hirUclass(c('-') to c('-')))
        assertEquals(t("""[\-\-]"""), hirUclass(c('-') to c('-')))
        assertEquals(t("""[\x00-\-]"""), hirUclass(0 to c('-')))
        assertEquals(t("""[\--\xFF]"""), hirUclass(c('-') to 0xFF))

        assertTrue(
            TestError(
                span = Span.new(Position.new(5, 1, 6), Position.new(9, 1, 10)),
                kind = ErrorKind.InvalidUtf8,
            ).eq(tErr("(?-u)[^a]")),
        )
        assertEquals(t("""[^\s\S]"""), hirUclass())
        assertEquals(tBytes("""(?-u)[^\s\S]"""), hirBclass())
    }

    @Test
    fun class_bracketed_union() {
        assertEquals(t("[a-zA-Z]"), hirUclass(c('A') to c('Z'), c('a') to c('z')))
        assertEquals(
            t("""[a\pZb]"""),
            hirUnion(
                hirUclass(c('a') to c('b')),
                hirUclassQuery(ClassQuery.Binary("separator")),
            ),
        )
        assertEquals(
            t("""[\pZ\p{Greek}]"""),
            hirUnion(
                hirUclassQuery(ClassQuery.Binary("greek")),
                hirUclassQuery(ClassQuery.Binary("separator")),
            ),
        )
        assertEquals(
            t("""[\p{age:3.0}\pZ\p{Greek}]"""),
            hirUnion(
                hirUclassQuery(ClassQuery.ByValue("age", "3.0")),
                hirUnion(
                    hirUclassQuery(ClassQuery.Binary("greek")),
                    hirUclassQuery(ClassQuery.Binary("separator")),
                ),
            ),
        )
        assertEquals(
            t("""[[[\p{age:3.0}\pZ]\p{Greek}][\p{Cyrillic}]]"""),
            hirUnion(
                hirUclassQuery(ClassQuery.ByValue("age", "3.0")),
                hirUnion(
                    hirUclassQuery(ClassQuery.Binary("cyrillic")),
                    hirUnion(
                        hirUclassQuery(ClassQuery.Binary("greek")),
                        hirUclassQuery(ClassQuery.Binary("separator")),
                    ),
                ),
            ),
        )

        assertEquals(
            t("""(?i)[\p{age:3.0}\pZ\p{Greek}]"""),
            hirCaseFold(hirUnion(
                hirUclassQuery(ClassQuery.ByValue("age", "3.0")),
                hirUnion(
                    hirUclassQuery(ClassQuery.Binary("greek")),
                    hirUclassQuery(ClassQuery.Binary("separator")),
                ),
            )),
        )
        assertEquals(
            t("""[^\p{age:3.0}\pZ\p{Greek}]"""),
            hirNegate(hirUnion(
                hirUclassQuery(ClassQuery.ByValue("age", "3.0")),
                hirUnion(
                    hirUclassQuery(ClassQuery.Binary("greek")),
                    hirUclassQuery(ClassQuery.Binary("separator")),
                ),
            )),
        )
        assertEquals(
            t("""(?i)[^\p{age:3.0}\pZ\p{Greek}]"""),
            hirNegate(hirCaseFold(hirUnion(
                hirUclassQuery(ClassQuery.ByValue("age", "3.0")),
                hirUnion(
                    hirUclassQuery(ClassQuery.Binary("greek")),
                    hirUclassQuery(ClassQuery.Binary("separator")),
                ),
            ))),
        )
    }

    @Test
    fun class_bracketed_nested() {
        assertEquals(t("""[a[^c]]"""), classNegate(uclass(c('c') to c('c'))))
        assertEquals(t("""[a-b[^c]]"""), classNegate(uclass(c('c') to c('c'))))
        assertEquals(t("""[a-c[^c]]"""), classNegate(uclass()))

        assertEquals(t("""[^a[^c]]"""), hirUclass(c('c') to c('c')))
        assertEquals(t("""[^a-b[^c]]"""), hirUclass(c('c') to c('c')))

        assertEquals(
            t("""(?i)[a[^c]]"""),
            hirNegate(classCaseFold(uclass(c('c') to c('c')))),
        )
        assertEquals(
            t("""(?i)[a-b[^c]]"""),
            hirNegate(classCaseFold(uclass(c('c') to c('c')))),
        )

        assertEquals(t("""(?i)[^a[^c]]"""), hirUclass(c('C') to c('C'), c('c') to c('c')))
        assertEquals(t("""(?i)[^a-b[^c]]"""), hirUclass(c('C') to c('C'), c('c') to c('c')))

        assertEquals(t("""[^a-c[^c]]"""), hirUclass())
        assertEquals(t("""(?i)[^a-c[^c]]"""), hirUclass())
    }

    @Test
    fun class_bracketed_intersect() {
        assertEquals(t("[abc&&b-c]"), hirUclass(c('b') to c('c')))
        assertEquals(t("[abc&&[b-c]]"), hirUclass(c('b') to c('c')))
        assertEquals(t("[[abc]&&[b-c]]"), hirUclass(c('b') to c('c')))
        assertEquals(t("[a-z&&b-y&&c-x]"), hirUclass(c('c') to c('x')))
        assertEquals(t("[c-da-b&&a-d]"), hirUclass(c('a') to c('d')))
        assertEquals(t("[a-d&&c-da-b]"), hirUclass(c('a') to c('d')))
        assertEquals(t("""[a-z&&a-c]"""), hirUclass(c('a') to c('c')))
        assertEquals(t("""[[a-z&&a-c]]"""), hirUclass(c('a') to c('c')))
        assertEquals(t("""[^[a-z&&a-c]]"""), hirNegate(hirUclass(c('a') to c('c'))))

        assertEquals(t("(?-u)[abc&&b-c]"), hirBclass(c('b') to c('c')))
        assertEquals(t("(?-u)[abc&&[b-c]]"), hirBclass(c('b') to c('c')))
        assertEquals(t("(?-u)[[abc]&&[b-c]]"), hirBclass(c('b') to c('c')))
        assertEquals(t("(?-u)[a-z&&b-y&&c-x]"), hirBclass(c('c') to c('x')))
        assertEquals(t("(?-u)[c-da-b&&a-d]"), hirBclass(c('a') to c('d')))
        assertEquals(t("(?-u)[a-d&&c-da-b]"), hirBclass(c('a') to c('d')))

        assertEquals(t("(?i)[abc&&b-c]"), hirCaseFold(hirUclass(c('b') to c('c'))))
        assertEquals(t("(?i)[abc&&[b-c]]"), hirCaseFold(hirUclass(c('b') to c('c'))))
        assertEquals(t("(?i)[[abc]&&[b-c]]"), hirCaseFold(hirUclass(c('b') to c('c'))))
        assertEquals(t("(?i)[a-z&&b-y&&c-x]"), hirCaseFold(hirUclass(c('c') to c('x'))))
        assertEquals(t("(?i)[c-da-b&&a-d]"), hirCaseFold(hirUclass(c('a') to c('d'))))
        assertEquals(t("(?i)[a-d&&c-da-b]"), hirCaseFold(hirUclass(c('a') to c('d'))))

        assertEquals(t("(?i-u)[abc&&b-c]"), hirCaseFold(hirBclass(c('b') to c('c'))))
        assertEquals(t("(?i-u)[abc&&[b-c]]"), hirCaseFold(hirBclass(c('b') to c('c'))))
        assertEquals(t("(?i-u)[[abc]&&[b-c]]"), hirCaseFold(hirBclass(c('b') to c('c'))))
        assertEquals(t("(?i-u)[a-z&&b-y&&c-x]"), hirCaseFold(hirBclass(c('c') to c('x'))))
        assertEquals(t("(?i-u)[c-da-b&&a-d]"), hirCaseFold(hirBclass(c('a') to c('d'))))
        assertEquals(t("(?i-u)[a-d&&c-da-b]"), hirCaseFold(hirBclass(c('a') to c('d'))))

        // In `[a^]`, `^` does not need to be escaped, so it makes sense that
        // `^` is also allowed to be unescaped after `&&`.
        assertEquals(t("""[\^&&^]"""), hirUclass(c('^') to c('^')))
        // `]` needs to be escaped after `&&` since it's not at start of class.
        assertEquals(t("""[]&&\]]"""), hirUclass(c(']') to c(']')))
        assertEquals(t("""[-&&-]"""), hirUclass(c('-') to c('-')))
        assertEquals(t("""[\&&&&]"""), hirUclass(c('&') to c('&')))
        assertEquals(t("""[\&&&\&]"""), hirUclass(c('&') to c('&')))
        // Test precedence.
        assertEquals(
            t("""[a-w&&[^c-g]z]"""),
            hirUclass(c('a') to c('b'), c('h') to c('w')),
        )
    }

    @Test
    fun class_bracketed_intersect_negate() {
        assertEquals(t("""[^\w&&\d]"""), hirNegate(hirUclassQuery(ClassQuery.Binary("digit"))))
        assertEquals(t("""[^[a-z&&a-c]]"""), hirNegate(hirUclass(c('a') to c('c'))))
        assertEquals(t("""[^[\w&&\d]]"""), hirNegate(hirUclassQuery(ClassQuery.Binary("digit"))))
        assertEquals(t("""[^[^\w&&\d]]"""), hirUclassQuery(ClassQuery.Binary("digit")))
        assertEquals(t("""[[[^\w]&&[^\d]]]"""), hirNegate(hirUclassPerlWord()))

        assertEquals(tBytes("""(?-u)[^\w&&\d]"""), hirNegate(hirAsciiBclass(ClassAsciiKind.Digit)))
        assertEquals(tBytes("""(?-u)[^[a-z&&a-c]]"""), hirNegate(hirBclass(c('a') to c('c'))))
        assertEquals(tBytes("""(?-u)[^[\w&&\d]]"""), hirNegate(hirAsciiBclass(ClassAsciiKind.Digit)))
        assertEquals(tBytes("""(?-u)[^[^\w&&\d]]"""), hirAsciiBclass(ClassAsciiKind.Digit))
        assertEquals(tBytes("""(?-u)[[[^\w]&&[^\d]]]"""), hirNegate(hirAsciiBclass(ClassAsciiKind.Word)))
    }

    @Test
    fun class_bracketed_difference() {
        assertEquals(
            t("""[\pL--[:ascii:]]"""),
            hirDifference(
                hirUclassQuery(ClassQuery.Binary("letter")),
                hirUclass(0 to 0x7F),
            ),
        )

        assertEquals(
            t("""(?-u)[[:alpha:]--[:lower:]]"""),
            hirBclass(c('A') to c('Z')),
        )
    }

    @Test
    fun class_bracketed_symmetric_difference() {
        assertEquals(
            t("""[\p{sc:Greek}~~\p{scx:Greek}]"""),
            // Class({
            //     '·'..='·',
            //     '\u{300}'..='\u{301}',
            //     '\u{304}'..='\u{304}',
            //     '\u{306}'..='\u{306}',
            //     '\u{308}'..='\u{308}',
            //     '\u{313}'..='\u{313}',
            //     '\u{342}'..='\u{342}',
            //     '\u{345}'..='\u{345}',
            //     'ʹ'..='ʹ',
            //     '\u{1dc0}'..='\u{1dc1}',
            //     '⁝'..='⁝',
            // })
            hirUclass(
                c('·') to c('·'),
                0x0300 to 0x0301,
                0x0304 to 0x0304,
                0x0306 to 0x0306,
                0x0308 to 0x0308,
                0x0313 to 0x0313,
                0x0342 to 0x0342,
                0x0345 to 0x0345,
                c('ʹ') to c('ʹ'),
                0x1DC0 to 0x1DC1,
                c('⁝') to c('⁝'),
            ),
        )
        assertEquals(t("""[a-g~~c-j]"""), hirUclass(c('a') to c('b'), c('h') to c('j')))

        assertEquals(
            t("""(?-u)[a-g~~c-j]"""),
            hirBclass(c('a') to c('b'), c('h') to c('j')),
        )
    }

    @Test
    fun ignore_whitespace() {
        assertEquals(t("""(?x)\12 3"""), hirLit("\n3"))
        assertEquals(t("""(?x)\x { 53 }"""), hirLit("S"))
        assertEquals(
            t("""(?x)\x # comment
{ # comment
    53 # comment
} #comment"""),
            hirLit("S"),
        )

        assertEquals(t("""(?x)\x 53"""), hirLit("S"))
        assertEquals(
            t("""(?x)\x # comment
        53 # comment"""),
            hirLit("S"),
        )
        assertEquals(t("""(?x)\x5 3"""), hirLit("S"))

        assertEquals(
            t("""(?x)\p # comment
{ # comment
    Separator # comment
} # comment"""),
            hirUclassQuery(ClassQuery.Binary("separator")),
        )

        assertEquals(
            t("""(?x)a # comment
{ # comment
    5 # comment
    , # comment
    10 # comment
} # comment"""),
            hirRange(true, 5u, 10u, hirLit("a")),
        )

        assertEquals(t("""(?x)a\  # hi there"""), hirLit("a "))
    }

    @Test
    fun analysis_is_utf8() {
        // Positive examples.
        assertTrue(propsBytes("""a""").isUtf8())
        assertTrue(propsBytes("""ab""").isUtf8())
        assertTrue(propsBytes("""(?-u)a""").isUtf8())
        assertTrue(propsBytes("""(?-u)ab""").isUtf8())
        assertTrue(propsBytes("""\xFF""").isUtf8())
        assertTrue(propsBytes("""\xFF\xFF""").isUtf8())
        assertTrue(propsBytes("""[^a]""").isUtf8())
        assertTrue(propsBytes("""[^a][^a]""").isUtf8())
        assertTrue(propsBytes("""\b""").isUtf8())
        assertTrue(propsBytes("""\B""").isUtf8())
        assertTrue(propsBytes("""(?-u)\b""").isUtf8())
        assertTrue(propsBytes("""(?-u)\B""").isUtf8())

        // Negative examples.
        assertFalse(propsBytes("""(?-u)\xFF""").isUtf8())
        assertFalse(propsBytes("""(?-u)\xFF\xFF""").isUtf8())
        assertFalse(propsBytes("""(?-u)[^a]""").isUtf8())
        assertFalse(propsBytes("""(?-u)[^a][^a]""").isUtf8())
    }

    @Test
    fun analysis_captures_len() {
        assertEquals(0, props("""a""").explicitCapturesLen())
        assertEquals(0, props("""(?:a)""").explicitCapturesLen())
        assertEquals(0, props("""(?i-u:a)""").explicitCapturesLen())
        assertEquals(0, props("""(?i-u)a""").explicitCapturesLen())
        assertEquals(1, props("""(a)""").explicitCapturesLen())
        assertEquals(1, props("""(?P<foo>a)""").explicitCapturesLen())
        assertEquals(1, props("""()""").explicitCapturesLen())
        assertEquals(1, props("""()a""").explicitCapturesLen())
        assertEquals(1, props("""(a)+""").explicitCapturesLen())
        assertEquals(2, props("""(a)(b)""").explicitCapturesLen())
        assertEquals(2, props("""(a)|(b)""").explicitCapturesLen())
        assertEquals(2, props("""((a))""").explicitCapturesLen())
        assertEquals(1, props("""([a&&b])""").explicitCapturesLen())
    }

    @Test
    fun analysis_static_captures_len() {
        val len = { pattern: String -> props(pattern).staticExplicitCapturesLen() }
        assertEquals(0, len(""""""))
        assertEquals(0, len("""foo|bar"""))
        assertEquals(null, len("""(foo)|bar"""))
        assertEquals(null, len("""foo|(bar)"""))
        assertEquals(1, len("""(foo|bar)"""))
        assertEquals(1, len("""(a|b|c|d|e|f)"""))
        assertEquals(1, len("""(a)|(b)|(c)|(d)|(e)|(f)"""))
        assertEquals(2, len("""(a)(b)|(c)(d)|(e)(f)"""))
        assertEquals(6, len("""(a)(b)(c)(d)(e)(f)"""))
        assertEquals(3, len("""(a)(b)(extra)|(a)(b)()"""))
        assertEquals(3, len("""(a)(b)((?:extra)?)"""))
        assertEquals(null, len("""(a)(b)(extra)?"""))
        assertEquals(1, len("""(foo)|(bar)"""))
        assertEquals(2, len("""(foo)(bar)"""))
        assertEquals(2, len("""(foo)+(bar)"""))
        assertEquals(null, len("""(foo)*(bar)"""))
        assertEquals(0, len("""(foo)?{0}"""))
        assertEquals(null, len("""(foo)?{1}"""))
        assertEquals(1, len("""(foo){1}"""))
        assertEquals(1, len("""(foo){1,}"""))
        assertEquals(1, len("""(foo){1,}?"""))
        assertEquals(null, len("""(foo){1,}??"""))
        assertEquals(null, len("""(foo){0,}"""))
        assertEquals(1, len("""(foo)(?:bar)"""))
        assertEquals(2, len("""(foo(?:bar)+)(?:baz(boo))"""))
        assertEquals(2, len("""(?P<bar>foo)(?:bar)(bal|loon)"""))
        assertEquals(2, len("""<(a)[^>]+href="([^"]+)"|<(img)[^>]+src="([^"]+)""""))
    }

    @Test
    fun analysis_is_all_assertions() {
        // Positive examples.
        for (pattern in listOf("""\b""", """\B""", """^""", """$""", """\A""", """\z""", """$^\z\A\b\B""", """$|^|\z|\A|\b|\B""", """^$|$^""", """((\b)+())*^""")) {
            val p = props(pattern)
            assertFalse(p.lookSet().isEmpty())
            assertEquals(0, p.minimumLen())
        }

        // Negative examples.
        val p = props("""^a""")
        assertFalse(p.lookSet().isEmpty())
        assertEquals(1, p.minimumLen())
    }

    @Test
    fun analysis_look_set_prefix_any() {
        val p = props("""(?-u)(?i:(?:\b|_)win(?:32|64|dows)?(?:\b|_))""")
        assertTrue(p.lookSetPrefixAny().contains(Look.WordAscii))
    }

    @Test
    fun analysis_is_anchored() {
        val isStart = { pattern: String -> props(pattern).lookSetPrefix().contains(Look.Start) }
        val isEnd = { pattern: String -> props(pattern).lookSetSuffix().contains(Look.End) }

        // Positive examples.
        assertTrue(isStart("""^"""))
        assertTrue(isEnd("""$"""))

        assertTrue(isStart("""^^"""))
        assertTrue(props("""$$""").lookSetSuffix().contains(Look.End))

        assertTrue(isStart("""^$"""))
        assertTrue(isEnd("""^$"""))

        assertTrue(isStart("""^foo"""))
        assertTrue(isEnd("""foo$"""))

        assertTrue(isStart("""^foo|^bar"""))
        assertTrue(isEnd("""foo$|bar$"""))

        assertTrue(isStart("""^(foo|bar)"""))
        assertTrue(isEnd("""(foo|bar)$"""))

        assertTrue(isStart("""^+"""))
        assertTrue(isEnd("""$+"""))
        assertTrue(isStart("""^++"""))
        assertTrue(isEnd("""$++"""))
        assertTrue(isStart("""(^)+"""))
        assertTrue(isEnd("""($)+"""))

        assertTrue(isStart("""$^"""))
        assertTrue(isStart("""$^"""))
        assertTrue(isStart("""$^|^$"""))
        assertTrue(isEnd("""$^|^$"""))

        assertTrue(isStart("""\b^"""))
        assertTrue(isEnd("""$\b"""))
        assertTrue(isStart("""^(?m:^)"""))
        assertTrue(isEnd("""(?m:$)$"""))
        assertTrue(isStart("""(?m:^)^"""))
        assertTrue(isEnd("""$(?m:$)"""))

        // Negative examples.
        assertFalse(isStart("""(?m)^"""))
        assertFalse(isEnd("""(?m)$"""))
        assertFalse(isStart("""(?m:^$)|$^"""))
        assertFalse(isEnd("""(?m:^$)|$^"""))
        assertFalse(isStart("""$^|(?m:^$)"""))
        assertFalse(isEnd("""$^|(?m:^$)"""))

        assertFalse(isStart("""a^"""))
        assertFalse(isStart("${'$'}a"))

        assertFalse(isEnd("""a^"""))
        assertFalse(isEnd("${'$'}a"))

        assertFalse(isStart("""^foo|bar"""))
        assertFalse(isEnd("""foo|bar$"""))

        assertFalse(isStart("""^*"""))
        assertFalse(isEnd("""$*"""))
        assertFalse(isStart("""^*+"""))
        assertFalse(isEnd("""$*+"""))
        assertFalse(isStart("""^+*"""))
        assertFalse(isEnd("""$+*"""))
        assertFalse(isStart("""(^)*"""))
        assertFalse(isEnd("""($)*"""))
    }

    @Test
    fun analysis_is_any_anchored() {
        val isStart = { pattern: String -> props(pattern).lookSet().contains(Look.Start) }
        val isEnd = { pattern: String -> props(pattern).lookSet().contains(Look.End) }

        // Positive examples.
        assertTrue(isStart("""^"""))
        assertTrue(isEnd("""$"""))
        assertTrue(isStart("""\A"""))
        assertTrue(isEnd("""\z"""))

        // Negative examples.
        assertFalse(isStart("""(?m)^"""))
        assertFalse(isEnd("""(?m)$"""))
        assertFalse(isStart("""$"""))
        assertFalse(isEnd("""^"""))
    }

    @Test
    fun analysis_can_empty() {
        // Positive examples.
        val assertEmpty = { pattern: String -> assertEquals(0, propsBytes(pattern).minimumLen()) }
        assertEmpty("""""")
        assertEmpty("""()""")
        assertEmpty("""()*""")
        assertEmpty("""()+""")
        assertEmpty("""()?""")
        assertEmpty("""a*""")
        assertEmpty("""a?""")
        assertEmpty("""a{0}""")
        assertEmpty("""a{0,}""")
        assertEmpty("""a{0,1}""")
        assertEmpty("""a{0,10}""")
        assertEmpty("""\pL*""")
        assertEmpty("""a*|b""")
        assertEmpty("""b|a*""")
        assertEmpty("""a|""")
        assertEmpty("""|a""")
        assertEmpty("""a||b""")
        assertEmpty("""a*a?(abcd)*""")
        assertEmpty("""^""")
        assertEmpty("""$""")
        assertEmpty("""(?m)^""")
        assertEmpty("""(?m)$""")
        assertEmpty("""\A""")
        assertEmpty("""\z""")
        assertEmpty("""\B""")
        assertEmpty("""(?-u)\B""")
        assertEmpty("""\b""")
        assertEmpty("""(?-u)\b""")

        // Negative examples.
        val assertNonEmpty = { pattern: String -> assertNotEquals(0, propsBytes(pattern).minimumLen()) }
        assertNonEmpty("""a+""")
        assertNonEmpty("""a{1}""")
        assertNonEmpty("""a{1,}""")
        assertNonEmpty("""a{1,2}""")
        assertNonEmpty("""a{1,10}""")
        assertNonEmpty("""b|a""")
        assertNonEmpty("""a*a+(abcd)*""")
        assertNonEmpty("""\P{any}""")
        assertNonEmpty("""[a--a]""")
        assertNonEmpty("""[a&&b]""")
    }

    @Test
    fun analysis_is_literal() {
        // Positive examples.
        assertTrue(props("""a""").isLiteral())
        assertTrue(props("""ab""").isLiteral())
        assertTrue(props("""abc""").isLiteral())
        assertTrue(props("""(?m)abc""").isLiteral())
        assertTrue(props("""(?:a)""").isLiteral())
        assertTrue(props("""foo(?:a)""").isLiteral())
        assertTrue(props("""(?:a)foo""").isLiteral())
        assertTrue(props("""[a]""").isLiteral())

        // Negative examples.
        assertFalse(props("""""").isLiteral())
        assertFalse(props("""^""").isLiteral())
        assertFalse(props("""a|b""").isLiteral())
        assertFalse(props("""(a)""").isLiteral())
        assertFalse(props("""a+""").isLiteral())
        assertFalse(props("""foo(a)""").isLiteral())
        assertFalse(props("""(a)foo""").isLiteral())
        assertFalse(props("""[ab]""").isLiteral())
    }

    @Test
    fun analysis_is_alternation_literal() {
        // Positive examples.
        assertTrue(props("""a""").isAlternationLiteral())
        assertTrue(props("""ab""").isAlternationLiteral())
        assertTrue(props("""abc""").isAlternationLiteral())
        assertTrue(props("""(?m)abc""").isAlternationLiteral())
        assertTrue(props("""foo|bar""").isAlternationLiteral())
        assertTrue(props("""foo|bar|baz""").isAlternationLiteral())
        assertTrue(props("""[a]""").isAlternationLiteral())
        assertTrue(props("""(?:ab)|cd""").isAlternationLiteral())
        assertTrue(props("""ab|(?:cd)""").isAlternationLiteral())

        // Negative examples.
        assertFalse(props("""""").isAlternationLiteral())
        assertFalse(props("""^""").isAlternationLiteral())
        assertFalse(props("""(a)""").isAlternationLiteral())
        assertFalse(props("""a+""").isAlternationLiteral())
        assertFalse(props("""foo(a)""").isAlternationLiteral())
        assertFalse(props("""(a)foo""").isAlternationLiteral())
        assertFalse(props("""[ab]""").isAlternationLiteral())
        assertFalse(props("""[ab]|b""").isAlternationLiteral())
        assertFalse(props("""a|[ab]""").isAlternationLiteral())
        assertFalse(props("""(a)|b""").isAlternationLiteral())
        assertFalse(props("""a|(b)""").isAlternationLiteral())
        assertFalse(props("""a|b""").isAlternationLiteral())
        assertFalse(props("""a|b|c""").isAlternationLiteral())
        assertFalse(props("""[a]|b""").isAlternationLiteral())
        assertFalse(props("""a|[b]""").isAlternationLiteral())
        assertFalse(props("""(?:a)|b""").isAlternationLiteral())
        assertFalse(props("""a|(?:b)""").isAlternationLiteral())
        assertFalse(props("""(?:z|xx)@|xx""").isAlternationLiteral())
    }

    // This tests that the smart Hir.repetition constructors does some basic
    // simplifications.
    @Test
    fun smart_repetition() {
        assertEquals(t("""a{0}"""), Hir.empty())
        assertEquals(t("""a{1}"""), hirLit("a"))
        assertEquals(t("""\B{32111}"""), hirLook(Look.WordUnicodeNegate))
    }

    // This tests that the smart Hir.concat constructor simplifies the given
    // exprs in a way we expect.
    @Test
    fun smart_concat() {
        assertEquals(t(""), Hir.empty())
        assertEquals(t("(?:)"), Hir.empty())
        assertEquals(t("abc"), hirLit("abc"))
        assertEquals(t("(?:foo)(?:bar)"), hirLit("foobar"))
        assertEquals(t("quux(?:foo)(?:bar)baz"), hirLit("quuxfoobarbaz"))
        assertEquals(
            t("foo(?:bar^baz)quux"),
            hirCat(listOf(
                hirLit("foobar"),
                hirLook(Look.Start),
                hirLit("bazquux"),
            )),
        )
        assertEquals(
            t("foo(?:ba(?:r^b)az)quux"),
            hirCat(listOf(
                hirLit("foobar"),
                hirLook(Look.Start),
                hirLit("bazquux"),
            )),
        )
    }

    // This tests that the smart Hir.alternation constructor simplifies the
    // given exprs in a way we expect.
    @Test
    fun smart_alternation() {
        assertEquals(
            t("(?:foo)|(?:bar)"),
            hirAlt(listOf(hirLit("foo"), hirLit("bar"))),
        )
        assertEquals(
            t("quux|(?:abc|def|xyz)|baz"),
            hirAlt(listOf(
                hirLit("quux"),
                hirLit("abc"),
                hirLit("def"),
                hirLit("xyz"),
                hirLit("baz"),
            )),
        )
        assertEquals(
            t("quux|(?:abc|(?:def|mno)|xyz)|baz"),
            hirAlt(listOf(
                hirLit("quux"),
                hirLit("abc"),
                hirLit("def"),
                hirLit("mno"),
                hirLit("xyz"),
                hirLit("baz"),
            )),
        )
        assertEquals(
            t("a|b|c|d|e|f|x|y|z"),
            hirUclass(c('a') to c('f'), c('x') to c('z')),
        )
        // Tests that we lift common prefixes out of an alternation.
        assertEquals(
            t("[A-Z]foo|[A-Z]quux"),
            hirCat(listOf(
                hirUclass(c('A') to c('Z')),
                hirAlt(listOf(hirLit("foo"), hirLit("quux"))),
            )),
        )
        assertEquals(
            t("[A-Z][A-Z]|[A-Z]quux"),
            hirCat(listOf(
                hirUclass(c('A') to c('Z')),
                hirAlt(listOf(hirUclass(c('A') to c('Z')), hirLit("quux"))),
            )),
        )
        assertEquals(
            t("[A-Z][A-Z]|[A-Z][A-Z]quux"),
            hirCat(listOf(
                hirUclass(c('A') to c('Z')),
                hirUclass(c('A') to c('Z')),
                hirAlt(listOf(Hir.empty(), hirLit("quux"))),
            )),
        )
        assertEquals(
            t("[A-Z]foo|[A-Z]foobar"),
            hirCat(listOf(
                hirUclass(c('A') to c('Z')),
                hirAlt(listOf(hirLit("foo"), hirLit("foobar"))),
            )),
        )
    }

    @Test
    fun regression_alt_empty_concat() {
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
    fun regression_empty_alt() {
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
    fun regression_singleton_alt() {
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

    // See: https://bugs.chromium.org/p/oss-fuzz/issues/detail?id=63168
    @Test
    fun regression_fuzz_match() {
        val pat = rustEscaped("""[(\u{6} \0-\u{afdf5}]  \0 """)
        val ast = ParserBuilder.new()
            .octal(false)
            .ignoreWhitespace(true)
            .build()
            .parse(pat)
            .getOrThrow()
        val hir = TranslatorBuilder.new()
            .utf8(true)
            .caseInsensitive(false)
            .multiLine(false)
            .dotMatchesNewLine(false)
            .swapGreed(true)
            .unicode(true)
            .build()
            .translate(pat, ast)
            .getOrThrow()
        assertEquals(
            hir,
            Hir.concat(listOf(
                hirUclass(0 to 0xAFDF5),
                hirLit("\u0000"),
            )),
        )
    }

    // See: https://bugs.chromium.org/p/oss-fuzz/issues/detail?id=63155
    @Test
    fun regression_fuzz_difference1() {
        val pat = """\W\W|\W[^\v--\W\W\P{Script_Extensions:Pau_Cin_Hau}\u10A1A1-\U{3E3E3}--~~~~--~~~~~~~~------~~~~~~--~~~~~~]*"""
        t(pat) // shouldn't panic
    }

    // See: https://bugs.chromium.org/p/oss-fuzz/issues/detail?id=63153
    @Test
    fun regression_fuzz_char_decrement1() {
        val pat = rustEscaped("""w[w[^w?\rw\rw[^w?\rw[^w?\rw[^w?\rw[^w?\rw[^w?\rw[^w?\r\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0\0w?\rw[^w?\rw[^w?\rw[^w\0\0\u{1}\0]\0\0-*\0]\0\0\0\0\0\0\u{1}\0]\0\0-*\0]\0\0\0\0\0\u{1}\0]\0\0\0\0\0\0\0\0\0*\0\0\u{1}\0]\0\0-*\0][^w?\rw[^w?\rw[^w?\rw[^w?\rw[^w?\rw[^w?\rw[^w\0\0\u{1}\0]\0\0-*\0]\0\0\0\0\0\0\u{1}\0]\0\0-*\0]\0\0\0\0\0\u{1}\0]\0\0\0\0\0\0\0\0\0x\0\0\u{1}\0]\0\0-*\0]\0\0\0\0\0\0\0\0\0*??\0\u{7f}{2}\u{10}??\0\0\0\0\0\0\0\0\0\u{3}\0\0\0}\0-*\0]\0\0\0\0\0\0\u{1}\0]\0\0-*\0]\0\0\0\0\0\0\u{1}\0]\0\0-*\0]\0\0\0\0\0\u{1}\0]\0\0-*\0]\0\0\0\0\0\0\0\u{1}\0]\0\u{1}\u{1}H-i]-]\0\0\0\0\u{1}\0]\0\0\0\u{1}\0]\0\0-*\0\0\0\0\u{1}9-\u{7f}]\0'|-\u{7f}]\0'|(?i-ux)[-\u{7f}]\0'\u{3}\0\0\0}\0-*\0]<D\0\0\0\0\0\0\u{1}]\0\0\0\0]\0\0-*\0]\0\0 """)
        t(pat) // shouldn't panic
    }
}
