// port-lint: tests hir/translate.rs
package io.github.kotlinmania.regexsyntax.hir.translate

import io.github.kotlinmania.regexsyntax.ast.Alternation
import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.ClassAsciiKind
import io.github.kotlinmania.regexsyntax.ast.Concat
import io.github.kotlinmania.regexsyntax.ast.Position
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.parse.ParserBuilder
import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Class
import io.github.kotlinmania.regexsyntax.hir.Class.Bytes
import io.github.kotlinmania.regexsyntax.hir.Class.Unicode
import io.github.kotlinmania.regexsyntax.hir.ClassBytes
import io.github.kotlinmania.regexsyntax.hir.ClassBytesRange
import io.github.kotlinmania.regexsyntax.hir.ClassUnicode
import io.github.kotlinmania.regexsyntax.hir.ClassUnicodeRange
import io.github.kotlinmania.regexsyntax.hir.Dot
import io.github.kotlinmania.regexsyntax.hir.Error
import io.github.kotlinmania.regexsyntax.hir.ErrorKind
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.Properties
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.regexsyntax.unicode.ClassQuery
import io.github.kotlinmania.regexsyntax.unicode.cls
import io.github.kotlinmania.regexsyntax.unicode.perlWord
import io.github.kotlinmania.rustlang.core.result.Result

// Defines a translator that converts an `Ast` to an `Hir`.

// We create these errors to compare with real hir.Errors in the tests.
// We define equality between TestError and hir.Error to disregard the
// pattern string in hir.Error, which is annoying to provide in tests.
public open class TestError(
    public var span: Span,
    public var kind: ErrorKind,
) {
    public open fun eq(other: Error): Boolean = this.span == other.span && this.kind == other.kind
}

public fun Error.eq(other: TestError): Boolean = this.span == other.span && this.kind == other.kind

public fun parse(pattern: String): Ast = ParserBuilder.new().octal(true).build().parse(pattern).unwrap()

public fun t(pattern: String): Hir = TranslatorBuilder.new().utf8(true).build().translate(pattern, parse(pattern)).unwrap()

public fun tErr(pattern: String): Error = TranslatorBuilder.new().utf8(true).build().translate(pattern, parse(pattern)).unwrapErr()

public fun tBytes(pattern: String): Hir = TranslatorBuilder.new().utf8(false).build().translate(pattern, parse(pattern)).unwrap()

public fun props(pattern: String): Properties = t(pattern).properties().clone()

public fun propsBytes(pattern: String): Properties = tBytes(pattern).properties().clone()

public fun hirLit(s: String): Hir = hirBlit(s.encodeToByteArray().map { it.toUByte() })

public fun hirBlit(s: List<UByte>): Hir = Hir.literal(s)

public fun up(start: Char, end: Char): Pair<UInt, UInt> = Pair(start.code.toUInt(), end.code.toUInt())

public fun up(start: Char, end: UInt): Pair<UInt, UInt> = Pair(start.code.toUInt(), end)

public fun up(start: UInt, end: UInt): Pair<UInt, UInt> = Pair(start, end)

public fun hirCapture(index: UInt, expr: Hir): Hir = Hir.capture(Capture(index = index, name = null, sub = expr))

public fun hirCaptureName(index: UInt, name: String, expr: Hir): Hir = Hir.capture(Capture(index = index, name = name, sub = expr))

public fun hirQuest(greedy: Boolean, expr: Hir): Hir = Hir.repetition(Repetition(min = 0u, max = 1u, greedy = greedy, sub = expr))

public fun hirStar(greedy: Boolean, expr: Hir): Hir = Hir.repetition(Repetition(min = 0u, max = null, greedy = greedy, sub = expr))

public fun hirPlus(greedy: Boolean, expr: Hir): Hir = Hir.repetition(Repetition(min = 1u, max = null, greedy = greedy, sub = expr))

public fun hirRange(greedy: Boolean, min: UInt, max: UInt?, expr: Hir): Hir = Hir.repetition(Repetition(min = min, max = max, greedy = greedy, sub = expr))

public fun hirAlt(alts: List<Hir>): Hir = Hir.alternation(alts)

public fun hirCat(exprs: List<Hir>): Hir = Hir.concat(exprs)

internal fun hirUclassQuery(query: ClassQuery): Hir = Hir.cls(Class.Unicode(cls(query).unwrap()))

public fun hirUclassPerlWord(): Hir = Hir.cls(Class.Unicode(perlWord().unwrap()))

public fun hirAsciiUclass(kind: ClassAsciiKind): Hir = Hir.cls(Class.Unicode(ClassUnicode.new(asciiClassAsChars(kind).map { (s, e) -> ClassUnicodeRange.new(s, e) })))

public fun hirAsciiBclass(kind: ClassAsciiKind): Hir = Hir.cls(Class.Bytes(ClassBytes.new(asciiClass(kind).map { (s, e) -> ClassBytesRange.new(s, e) })))

public fun hirUclass(ranges: List<Pair<UInt, UInt>>): Hir = Hir.cls(uclass(ranges))

public fun hirBclass(ranges: List<Pair<UInt, UInt>>): Hir = Hir.cls(bclass(ranges))

public fun hirCaseFold(expr: Hir): Hir =
    when (val matchVal = expr.intoKind()) {
        is HirKind.Class -> {
            val (cls) = matchVal
            cls.caseFoldSimple()
            Hir.cls(cls)
        }
        else -> error("cannot case fold non-class Hir expr")
    }

public fun hirNegate(expr: Hir): Hir =
    when (val matchVal = expr.intoKind()) {
        is HirKind.Class -> {
            val (cls) = matchVal
            cls.negate()
            Hir.cls(cls)
        }
        else -> error("cannot negate non-class Hir expr")
    }

public fun uclass(ranges: List<Pair<UInt, UInt>>): Class {
    val ranges1: List<ClassUnicodeRange> = ranges.map { (s, e) -> ClassUnicodeRange.new(s, e) }
    return Class.Unicode(ClassUnicode.new(ranges1))
}

public fun bclass(ranges: List<Pair<UInt, UInt>>): Class {
    val ranges1: List<ClassBytesRange> = ranges.map { (s, e) -> ClassBytesRange.new(s.toUByte(), e.toUByte()) }
    return Class.Bytes(ClassBytes.new(ranges1))
}

public fun classCaseFold(cls: Class): Hir {
    cls.caseFoldSimple()
    return Hir.cls(cls)
}

public fun classNegate(cls: Class): Hir {
    cls.negate()
    return Hir.cls(cls)
}

public fun hirUnion(expr1: Hir, expr2: Hir): Hir {
    val k1 = expr1.intoKind()
    val k2 = expr2.intoKind()
    if (k1 is HirKind.Class && k2 is HirKind.Class) {
        val v1 = k1.value
        val v2 = k2.value
        if (v1 is Unicode && v2 is Unicode) {
            v1.value.union(v2.value)
            return Hir.cls(Class.Unicode(v1.value))
        }
        if (v1 is Bytes && v2 is Bytes) {
            v1.value.union(v2.value)
            return Hir.cls(Class.Bytes(v1.value))
        }
    }
    error("cannot union non-class Hir exprs")
}

public fun hirDifference(expr1: Hir, expr2: Hir): Hir {
    val k1 = expr1.intoKind()
    val k2 = expr2.intoKind()
    if (k1 is HirKind.Class && k2 is HirKind.Class) {
        val v1 = k1.value
        val v2 = k2.value
        if (v1 is Unicode && v2 is Unicode) {
            v1.value.difference(v2.value)
            return Hir.cls(Class.Unicode(v1.value))
        }
        if (v1 is Bytes && v2 is Bytes) {
            v1.value.difference(v2.value)
            return Hir.cls(Class.Bytes(v1.value))
        }
    }
    error("cannot difference non-class Hir exprs")
}

public fun hirLook(look: Look): Hir = Hir.look(look)

public class HirTranslateInlineTests {
    @kotlin.test.Test
    public fun empty() {
        check(t("") == Hir.empty())
        check(t("(?i)") == Hir.empty())
        check(t("()") == hirCapture(1u, Hir.empty()))
        check(t("(?:)") == Hir.empty())
        check(t("(?P<wat>)") == hirCaptureName(1u, "wat", Hir.empty()))
        check(t("|") == hirAlt(mutableListOf(Hir.empty(), Hir.empty())))
        check(t("()|()") == hirAlt(mutableListOf(hirCapture(1u, Hir.empty()), hirCapture(2u, Hir.empty()))))
        check(t("(|b)") == hirCapture(1u, hirAlt(mutableListOf(Hir.empty(), hirLit("b")))))
        check(t("(a|)") == hirCapture(1u, hirAlt(mutableListOf(hirLit("a"), Hir.empty()))))
        check(t("(a||c)") == hirCapture(1u, hirAlt(mutableListOf(hirLit("a"), Hir.empty(), hirLit("c")))))
        check(t("(||)") == hirCapture(1u, hirAlt(mutableListOf(Hir.empty(), Hir.empty(), Hir.empty()))))
    }

    @kotlin.test.Test
    public fun literal() {
        check(t("a") == hirLit("a"))
        check(t("(?-u)a") == hirLit("a"))
        check(t("☃") == hirLit("☃"))
        check(t("abcd") == hirLit("abcd"))
        check(tBytes("(?-u)a") == hirLit("a"))
        check(tBytes("(?-u)a") == hirLit("a"))
        check(tBytes("(?-u)\\x61") == hirLit("a"))
        check(tBytes("(?-u)\\xFF") == hirBlit(listOf(0xFFu.toUByte())))
        check(t("(?-u)☃") == hirLit("☃"))
        check(tErr("(?-u)\\xFF").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(5, 1, 6), Position.new(9, 1, 10)))))
    }

    @kotlin.test.Test
    public fun literalCaseInsensitive() {
        check(t("(?i)a") == hirUclass(listOf(up('A', 'A'), up('a', 'a'))))
        check(t("(?i:a)") == hirUclass(listOf(up('A', 'A'), up('a', 'a'))))
        check(t("a(?i)a(?-i)a") == hirCat(mutableListOf(hirLit("a"), hirUclass(listOf(up('A', 'A'), up('a', 'a'))), hirLit("a"))))
        check(t("(?i)ab@c") == hirCat(mutableListOf(hirUclass(listOf(up('A', 'A'), up('a', 'a'))), hirUclass(listOf(up('B', 'B'), up('b', 'b'))), hirLit("@"), hirUclass(listOf(up('C', 'C'), up('c', 'c'))))))
        check(t("(?i)β") == hirUclass(listOf(up('\u0392', '\u0392'), up('\u03b2', '\u03b2'), up('\u03d0', '\u03d0'))))
        check(t("(?i-u)a") == hirBclass(listOf(Pair(65u, 65u), Pair(97u, 97u))))
        check(t("(?-u)a(?i)a(?-i)a") == hirCat(mutableListOf(hirLit("a"), hirBclass(listOf(Pair(65u, 65u), Pair(97u, 97u))), hirLit("a"))))
        check(t("(?i-u)ab@c") == hirCat(mutableListOf(hirBclass(listOf(Pair(65u, 65u), Pair(97u, 97u))), hirBclass(listOf(Pair(66u, 66u), Pair(98u, 98u))), hirLit("@"), hirBclass(listOf(Pair(67u, 67u), Pair(99u, 99u))))))
        check(tBytes("(?i-u)a") == hirBclass(listOf(Pair(65u, 65u), Pair(97u, 97u))))
        check(tBytes("(?i-u)a") == hirBclass(listOf(Pair(65u, 65u), Pair(97u, 97u))))
        check(tBytes("(?i-u)\\x61") == hirBclass(listOf(Pair(65u, 65u), Pair(97u, 97u))))
        check(tBytes("(?i-u)\\xFF") == hirBlit(listOf(0xFFu.toUByte())))
        check(t("(?i-u)β") == hirLit("β"))
    }

    @kotlin.test.Test
    public fun dot() {
        check(t(".") == hirUclass(listOf(up('\u0000', '\t'), up('\u000b', 0x10ffffu))))
        check(t("(?R).") == hirUclass(listOf(up('\u0000', '\t'), up('\u000b', '\u000c'), up('\u000e', 0x10ffffu))))
        check(t("(?s).") == hirUclass(listOf(up('\u0000', 0x10ffffu))))
        check(t("(?Rs).") == hirUclass(listOf(up('\u0000', 0x10ffffu))))
        check(tBytes("(?-u).") == hirBclass(listOf(Pair(0u, 9u), Pair(11u, 255u))))
        check(tBytes("(?R-u).") == hirBclass(listOf(Pair(0u, 9u), Pair(11u, 12u), Pair(14u, 255u))))
        check(tBytes("(?s-u).") == hirBclass(listOf(Pair(0u, 255u))))
        check(tBytes("(?Rs-u).") == hirBclass(listOf(Pair(0u, 255u))))
        // If invalid UTF-8 isn't allowed, then non-Unicode `.` isn't allowed.
        check(tErr("(?-u).").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(5, 1, 6), Position.new(6, 1, 7)))))
        check(tErr("(?R-u).").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(6, 1, 7), Position.new(7, 1, 8)))))
        check(tErr("(?s-u).").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(6, 1, 7), Position.new(7, 1, 8)))))
        check(tErr("(?Rs-u).").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(7, 1, 8), Position.new(8, 1, 9)))))
    }

    @kotlin.test.Test
    public fun assertions() {
        check(t("^") == hirLook(Look.Start))
        check(t("\$") == hirLook(Look.End))
        check(t("\\A") == hirLook(Look.Start))
        check(t("\\z") == hirLook(Look.End))
        check(t("(?m)^") == hirLook(Look.StartLF))
        check(t("(?m)\$") == hirLook(Look.EndLF))
        check(t("(?m)\\A") == hirLook(Look.Start))
        check(t("(?m)\\z") == hirLook(Look.End))
        check(t("\\b") == hirLook(Look.WordUnicode))
        check(t("\\B") == hirLook(Look.WordUnicodeNegate))
        check(t("(?-u)\\b") == hirLook(Look.WordAscii))
        check(t("(?-u)\\B") == hirLook(Look.WordAsciiNegate))
    }

    @kotlin.test.Test
    public fun group() {
        check(t("(a)") == hirCapture(1u, hirLit("a")))
        check(t("(a)(b)") == hirCat(mutableListOf(hirCapture(1u, hirLit("a")), hirCapture(2u, hirLit("b")))))
        check(t("(a)|(b)") == hirAlt(mutableListOf(hirCapture(1u, hirLit("a")), hirCapture(2u, hirLit("b")))))
        check(t("(?P<foo>)") == hirCaptureName(1u, "foo", Hir.empty()))
        check(t("(?P<foo>a)") == hirCaptureName(1u, "foo", hirLit("a")))
        check(t("(?P<foo>a)(?P<bar>b)") == hirCat(mutableListOf(hirCaptureName(1u, "foo", hirLit("a")), hirCaptureName(2u, "bar", hirLit("b")))))
        check(t("(?:)") == Hir.empty())
        check(t("(?:a)") == hirLit("a"))
        check(t("(?:a)(b)") == hirCat(mutableListOf(hirLit("a"), hirCapture(1u, hirLit("b")))))
        check(t("(a)(?:b)(c)") == hirCat(mutableListOf(hirCapture(1u, hirLit("a")), hirLit("b"), hirCapture(2u, hirLit("c")))))
        check(t("(a)(?P<foo>b)(c)") == hirCat(mutableListOf(hirCapture(1u, hirLit("a")), hirCaptureName(2u, "foo", hirLit("b")), hirCapture(3u, hirLit("c")))))
        check(t("()") == hirCapture(1u, Hir.empty()))
        check(t("((?i))") == hirCapture(1u, Hir.empty()))
        check(t("((?x))") == hirCapture(1u, Hir.empty()))
        check(t("(((?x)))") == hirCapture(1u, hirCapture(2u, Hir.empty())))
    }

    @kotlin.test.Test
    public fun lineAnchors() {
        check(t("^") == hirLook(Look.Start))
        check(t("\$") == hirLook(Look.End))
        check(t("\\A") == hirLook(Look.Start))
        check(t("\\z") == hirLook(Look.End))
        check(t("(?m)\\A") == hirLook(Look.Start))
        check(t("(?m)\\z") == hirLook(Look.End))
        check(t("(?m)^") == hirLook(Look.StartLF))
        check(t("(?m)\$") == hirLook(Look.EndLF))
        check(t("(?R)\\A") == hirLook(Look.Start))
        check(t("(?R)\\z") == hirLook(Look.End))
        check(t("(?R)^") == hirLook(Look.Start))
        check(t("(?R)\$") == hirLook(Look.End))
        check(t("(?Rm)\\A") == hirLook(Look.Start))
        check(t("(?Rm)\\z") == hirLook(Look.End))
        check(t("(?Rm)^") == hirLook(Look.StartCRLF))
        check(t("(?Rm)\$") == hirLook(Look.EndCRLF))
    }

    @kotlin.test.Test
    public fun flags() {
        check(t("(?i:a)a") == hirCat(mutableListOf(hirUclass(listOf(up('A', 'A'), up('a', 'a'))), hirLit("a"))))
        check(t("(?i-u:a)β") == hirCat(mutableListOf(hirBclass(listOf(Pair(65u, 65u), Pair(97u, 97u))), hirLit("β"))))
        check(t("(?:(?i-u)a)b") == hirCat(mutableListOf(hirBclass(listOf(Pair(65u, 65u), Pair(97u, 97u))), hirLit("b"))))
        check(t("((?i-u)a)b") == hirCat(mutableListOf(hirCapture(1u, hirBclass(listOf(Pair(65u, 65u), Pair(97u, 97u)))), hirLit("b"))))
        check(t("(?i)(?-i:a)a") == hirCat(mutableListOf(hirLit("a"), hirUclass(listOf(up('A', 'A'), up('a', 'a'))))))
        check(t("(?im)a^") == hirCat(mutableListOf(hirUclass(listOf(up('A', 'A'), up('a', 'a'))), hirLook(Look.StartLF))))
        check(t("(?im)a^(?i-m)a^") == hirCat(mutableListOf(hirUclass(listOf(up('A', 'A'), up('a', 'a'))), hirLook(Look.StartLF), hirUclass(listOf(up('A', 'A'), up('a', 'a'))), hirLook(Look.Start))))
        check(t("(?U)a*a*?(?-U)a*a*?") == hirCat(mutableListOf(hirStar(false, hirLit("a")), hirStar(true, hirLit("a")), hirStar(true, hirLit("a")), hirStar(false, hirLit("a")))))
        check(t("(?:a(?i)a)a") == hirCat(mutableListOf(hirCat(mutableListOf(hirLit("a"), hirUclass(listOf(up('A', 'A'), up('a', 'a'))))), hirLit("a"))))
        check(t("(?i)(?:a(?-i)a)a") == hirCat(mutableListOf(hirCat(mutableListOf(hirUclass(listOf(up('A', 'A'), up('a', 'a'))), hirLit("a"))), hirUclass(listOf(up('A', 'A'), up('a', 'a'))))))
    }

    @kotlin.test.Test
    public fun escape() {
        check(t("\\\\\\.\\+\\*\\?\\(\\)\\|\\[\\]\\{\\}\\^\\\$\\#") == hirLit("\\.+*?()|[]{}^\$#"))
    }

    @kotlin.test.Test
    public fun repetition() {
        check(t("a?") == hirQuest(true, hirLit("a")))
        check(t("a*") == hirStar(true, hirLit("a")))
        check(t("a+") == hirPlus(true, hirLit("a")))
        check(t("a??") == hirQuest(false, hirLit("a")))
        check(t("a*?") == hirStar(false, hirLit("a")))
        check(t("a+?") == hirPlus(false, hirLit("a")))
        check(t("a{1}") == hirRange(true, 1u, 1u, hirLit("a")))
        check(t("a{1,}") == hirRange(true, 1u, null, hirLit("a")))
        check(t("a{1,2}") == hirRange(true, 1u, 2u, hirLit("a")))
        check(t("a{1}?") == hirRange(false, 1u, 1u, hirLit("a")))
        check(t("a{1,}?") == hirRange(false, 1u, null, hirLit("a")))
        check(t("a{1,2}?") == hirRange(false, 1u, 2u, hirLit("a")))
        check(t("ab?") == hirCat(mutableListOf(hirLit("a"), hirQuest(true, hirLit("b")))))
        check(t("(ab)?") == hirQuest(true, hirCapture(1u, hirLit("ab"))))
        check(t("a|b?") == hirAlt(mutableListOf(hirLit("a"), hirQuest(true, hirLit("b")))))
    }

    @kotlin.test.Test
    public fun catAlt() {
        val a = { hirLook(Look.Start) }
        val b = { hirLook(Look.End) }
        val c = { hirLook(Look.WordUnicode) }
        val d = { hirLook(Look.WordUnicodeNegate) }
        check(t("(^\$)") == hirCapture(1u, hirCat(mutableListOf(a(), b()))))
        check(t("^|\$") == hirAlt(mutableListOf(a(), b())))
        check(t("^|\$|\\b") == hirAlt(mutableListOf(a(), b(), c())))
        check(t("^\$|\$\\b|\\b\\B") == hirAlt(mutableListOf(hirCat(mutableListOf(a(), b())), hirCat(mutableListOf(b(), c())), hirCat(mutableListOf(c(), d())))))
        check(t("(^|\$)") == hirCapture(1u, hirAlt(mutableListOf(a(), b()))))
        check(t("(^|\$|\\b)") == hirCapture(1u, hirAlt(mutableListOf(a(), b(), c()))))
        check(t("(^\$|\$\\b|\\b\\B)") == hirCapture(1u, hirAlt(mutableListOf(hirCat(mutableListOf(a(), b())), hirCat(mutableListOf(b(), c())), hirCat(mutableListOf(c(), d()))))))
        check(t("(^\$|(\$\\b|(\\b\\B)))") == hirCapture(1u, hirAlt(mutableListOf(hirCat(mutableListOf(a(), b())), hirCapture(2u, hirAlt(mutableListOf(hirCat(mutableListOf(b(), c())), hirCapture(3u, hirCat(mutableListOf(c(), d()))))))))))
    }

    // Tests the HIR transformation of things like '[a-z]|[A-Z]' into
    // '[A-Za-z]'. In other words, an alternation of just classes is always
    // equivalent to a single class corresponding to the union of the branches
    // in that class. (Unless some branches match invalid UTF-8 and others
    // match non-ASCII Unicode.)
    @kotlin.test.Test
    public fun catClassFlattened() {
        check(t("[a-z]|[A-Z]") == hirUclass(listOf(up('A', 'Z'), up('a', 'z'))))
        // Combining all of the letter properties should give us the one giant
        // letter property.
        check(t("(?x)\n                \\p{Lowercase_Letter}\n                |\\p{Uppercase_Letter}\n                |\\p{Titlecase_Letter}\n                |\\p{Modifier_Letter}\n                |\\p{Other_Letter}\n            ") == hirUclassQuery(ClassQuery.Binary("letter")))
        // Byte classes that can truly match invalid UTF-8 cannot be combined
        // with Unicode classes.
        check(tBytes("[Δδ]|(?-u:[\\x90-\\xFF])|[Λλ]") == hirAlt(mutableListOf(hirUclass(listOf(up('\u0394', '\u0394'), up('\u03b4', '\u03b4'))), hirBclass(listOf(up(144u, 255u))), hirUclass(listOf(up('\u039b', '\u039b'), up('\u03bb', '\u03bb'))))))
        // Byte classes on their own can be combined, even if some are ASCII
        // and others are invalid UTF-8.
        check(tBytes("[a-z]|(?-u:[\\x90-\\xFF])|[A-Z]") == hirBclass(listOf(Pair(65u, 90u), Pair(97u, 122u), Pair(144u, 255u))))
    }

    @kotlin.test.Test
    public fun classAscii() {
        check(t("[[:alnum:]]") == hirAsciiUclass(ClassAsciiKind.Alnum))
        check(t("[[:alpha:]]") == hirAsciiUclass(ClassAsciiKind.Alpha))
        check(t("[[:ascii:]]") == hirAsciiUclass(ClassAsciiKind.Ascii))
        check(t("[[:blank:]]") == hirAsciiUclass(ClassAsciiKind.Blank))
        check(t("[[:cntrl:]]") == hirAsciiUclass(ClassAsciiKind.Cntrl))
        check(t("[[:digit:]]") == hirAsciiUclass(ClassAsciiKind.Digit))
        check(t("[[:graph:]]") == hirAsciiUclass(ClassAsciiKind.Graph))
        check(t("[[:lower:]]") == hirAsciiUclass(ClassAsciiKind.Lower))
        check(t("[[:print:]]") == hirAsciiUclass(ClassAsciiKind.Print))
        check(t("[[:punct:]]") == hirAsciiUclass(ClassAsciiKind.Punct))
        check(t("[[:space:]]") == hirAsciiUclass(ClassAsciiKind.Space))
        check(t("[[:upper:]]") == hirAsciiUclass(ClassAsciiKind.Upper))
        check(t("[[:word:]]") == hirAsciiUclass(ClassAsciiKind.Word))
        check(t("[[:xdigit:]]") == hirAsciiUclass(ClassAsciiKind.Xdigit))
        check(t("[[:^lower:]]") == hirNegate(hirAsciiUclass(ClassAsciiKind.Lower)))
        check(t("(?i)[[:lower:]]") == hirUclass(listOf(up('A', 'Z'), up('a', 'z'), up('\u017f', '\u017f'), up('\u212a', '\u212a'))))
        check(t("(?-u)[[:lower:]]") == hirAsciiBclass(ClassAsciiKind.Lower))
        check(t("(?i-u)[[:lower:]]") == hirCaseFold(hirAsciiBclass(ClassAsciiKind.Lower)))
        check(tErr("(?-u)[[:^lower:]]").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(6, 1, 7), Position.new(16, 1, 17)))))
        check(tErr("(?i-u)[[:^lower:]]").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(7, 1, 8), Position.new(17, 1, 18)))))
    }

    @kotlin.test.Test
    public fun classAsciiMultiple() {
        // See: https://github.com/rust-lang/regex/issues/680
        check(t("[[:alnum:][:^ascii:]]") == hirUnion(hirAsciiUclass(ClassAsciiKind.Alnum), hirUclass(listOf(up('\u0080', 0x10ffffu)))))
        check(tBytes("(?-u)[[:alnum:][:^ascii:]]") == hirUnion(hirAsciiBclass(ClassAsciiKind.Alnum), hirBclass(listOf(Pair(0x80u, 0xFFu)))))
    }

    @kotlin.test.Test
    public fun classPerlUnicode() {
        // Unicode
        check(t("\\d") == hirUclassQuery(ClassQuery.Binary("digit")))
        check(t("\\s") == hirUclassQuery(ClassQuery.Binary("space")))
        check(t("\\w") == hirUclassPerlWord())
        check(t("(?i)\\d") == hirUclassQuery(ClassQuery.Binary("digit")))
        check(t("(?i)\\s") == hirUclassQuery(ClassQuery.Binary("space")))
        check(t("(?i)\\w") == hirUclassPerlWord())
        // Unicode, negated
        check(t("\\D") == hirNegate(hirUclassQuery(ClassQuery.Binary("digit"))))
        check(t("\\S") == hirNegate(hirUclassQuery(ClassQuery.Binary("space"))))
        check(t("\\W") == hirNegate(hirUclassPerlWord()))
        check(t("(?i)\\D") == hirNegate(hirUclassQuery(ClassQuery.Binary("digit"))))
        check(t("(?i)\\S") == hirNegate(hirUclassQuery(ClassQuery.Binary("space"))))
        check(t("(?i)\\W") == hirNegate(hirUclassPerlWord()))
    }

    @kotlin.test.Test
    public fun classPerlAscii() {
        // ASCII only
        check(t("(?-u)\\d") == hirAsciiBclass(ClassAsciiKind.Digit))
        check(t("(?-u)\\s") == hirAsciiBclass(ClassAsciiKind.Space))
        check(t("(?-u)\\w") == hirAsciiBclass(ClassAsciiKind.Word))
        check(t("(?i-u)\\d") == hirAsciiBclass(ClassAsciiKind.Digit))
        check(t("(?i-u)\\s") == hirAsciiBclass(ClassAsciiKind.Space))
        check(t("(?i-u)\\w") == hirAsciiBclass(ClassAsciiKind.Word))
        // ASCII only, negated
        check(tBytes("(?-u)\\D") == hirNegate(hirAsciiBclass(ClassAsciiKind.Digit)))
        check(tBytes("(?-u)\\S") == hirNegate(hirAsciiBclass(ClassAsciiKind.Space)))
        check(tBytes("(?-u)\\W") == hirNegate(hirAsciiBclass(ClassAsciiKind.Word)))
        check(tBytes("(?i-u)\\D") == hirNegate(hirAsciiBclass(ClassAsciiKind.Digit)))
        check(tBytes("(?i-u)\\S") == hirNegate(hirAsciiBclass(ClassAsciiKind.Space)))
        check(tBytes("(?i-u)\\W") == hirNegate(hirAsciiBclass(ClassAsciiKind.Word)))
        // ASCII only, negated, with UTF-8 mode enabled.
        // In this case, negating any Perl class results in an error because
        // all such classes can match invalid UTF-8.
        check(tErr("(?-u)\\D").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(5, 1, 6), Position.new(7, 1, 8)))))
        check(tErr("(?-u)\\S").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(5, 1, 6), Position.new(7, 1, 8)))))
        check(tErr("(?-u)\\W").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(5, 1, 6), Position.new(7, 1, 8)))))
        check(tErr("(?i-u)\\D").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(6, 1, 7), Position.new(8, 1, 9)))))
        check(tErr("(?i-u)\\S").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(6, 1, 7), Position.new(8, 1, 9)))))
        check(tErr("(?i-u)\\W").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(6, 1, 7), Position.new(8, 1, 9)))))
    }

    @kotlin.test.Test
    public fun classUnicodeGencat() {
        check(t("\\pZ") == hirUclassQuery(ClassQuery.Binary("Z")))
        check(t("\\pz") == hirUclassQuery(ClassQuery.Binary("Z")))
        check(t("\\p{Separator}") == hirUclassQuery(ClassQuery.Binary("Z")))
        check(t("\\p{se      PaRa ToR}") == hirUclassQuery(ClassQuery.Binary("Z")))
        check(t("\\p{gc:Separator}") == hirUclassQuery(ClassQuery.Binary("Z")))
        check(t("\\p{gc=Separator}") == hirUclassQuery(ClassQuery.Binary("Z")))
        check(t("\\p{gc!=Separator}") == hirNegate(hirUclassQuery(ClassQuery.Binary("Z"))))
        check(t("\\p{Other}") == hirUclassQuery(ClassQuery.Binary("Other")))
        check(t("\\pC") == hirUclassQuery(ClassQuery.Binary("Other")))
        check(t("\\PZ") == hirNegate(hirUclassQuery(ClassQuery.Binary("Z"))))
        check(t("\\P{separator}") == hirNegate(hirUclassQuery(ClassQuery.Binary("Z"))))
        check(t("\\P{gc!=separator}") == hirUclassQuery(ClassQuery.Binary("Z")))
        check(t("\\p{any}") == hirUclassQuery(ClassQuery.Binary("Any")))
        check(t("\\p{assigned}") == hirUclassQuery(ClassQuery.Binary("Assigned")))
        check(t("\\p{ascii}") == hirUclassQuery(ClassQuery.Binary("ASCII")))
        check(t("\\p{gc:any}") == hirUclassQuery(ClassQuery.Binary("Any")))
        check(t("\\p{gc:assigned}") == hirUclassQuery(ClassQuery.Binary("Assigned")))
        check(t("\\p{gc:ascii}") == hirUclassQuery(ClassQuery.Binary("ASCII")))
        check(tErr("(?-u)\\pZ").eq(TestError(kind = ErrorKind.UnicodeNotAllowed, span = Span.new(Position.new(5, 1, 6), Position.new(8, 1, 9)))))
        check(tErr("(?-u)\\p{Separator}").eq(TestError(kind = ErrorKind.UnicodeNotAllowed, span = Span.new(Position.new(5, 1, 6), Position.new(18, 1, 19)))))
        check(tErr("\\pE").eq(TestError(kind = ErrorKind.UnicodePropertyNotFound, span = Span.new(Position.new(0, 1, 1), Position.new(3, 1, 4)))))
        check(tErr("\\p{Foo}").eq(TestError(kind = ErrorKind.UnicodePropertyNotFound, span = Span.new(Position.new(0, 1, 1), Position.new(7, 1, 8)))))
        check(tErr("\\p{gc:Foo}").eq(TestError(kind = ErrorKind.UnicodePropertyValueNotFound, span = Span.new(Position.new(0, 1, 1), Position.new(10, 1, 11)))))
    }

    @kotlin.test.Test
    public fun classUnicodeScript() {
        check(t("\\p{Greek}") == hirUclassQuery(ClassQuery.Binary("Greek")))
        check(t("(?i)\\p{Greek}") == hirCaseFold(hirUclassQuery(ClassQuery.Binary("Greek"))))
        check(t("(?i)\\P{Greek}") == hirNegate(hirCaseFold(hirUclassQuery(ClassQuery.Binary("Greek")))))
        check(tErr("\\p{sc:Foo}").eq(TestError(kind = ErrorKind.UnicodePropertyValueNotFound, span = Span.new(Position.new(0, 1, 1), Position.new(10, 1, 11)))))
        check(tErr("\\p{scx:Foo}").eq(TestError(kind = ErrorKind.UnicodePropertyValueNotFound, span = Span.new(Position.new(0, 1, 1), Position.new(11, 1, 12)))))
    }

    @kotlin.test.Test
    public fun classUnicodeAge() {
        check(tErr("\\p{age:Foo}").eq(TestError(kind = ErrorKind.UnicodePropertyValueNotFound, span = Span.new(Position.new(0, 1, 1), Position.new(11, 1, 12)))))
    }

    @kotlin.test.Test
    public fun classUnicodeAnyEmpty() {
        check(t("\\P{any}") == hirUclass(listOf()))
    }

    @kotlin.test.Test
    public fun classBracketed() {
        check(t("[a]") == hirLit("a"))
        check(t("[ab]") == hirUclass(listOf(up('a', 'b'))))
        check(t("[^[a]]") == classNegate(uclass(listOf(up('a', 'a')))))
        check(t("[a-z]") == hirUclass(listOf(up('a', 'z'))))
        check(t("[a-fd-h]") == hirUclass(listOf(up('a', 'h'))))
        check(t("[a-fg-m]") == hirUclass(listOf(up('a', 'm'))))
        check(t("[\\x00]") == hirUclass(listOf(up('\u0000', '\u0000'))))
        check(t("[\\n]") == hirUclass(listOf(up('\n', '\n'))))
        check(t("[\n]") == hirUclass(listOf(up('\n', '\n'))))
        check(t("[\\d]") == hirUclassQuery(ClassQuery.Binary("digit")))
        check(t("[\\pZ]") == hirUclassQuery(ClassQuery.Binary("separator")))
        check(t("[\\p{separator}]") == hirUclassQuery(ClassQuery.Binary("separator")))
        check(t("[^\\D]") == hirUclassQuery(ClassQuery.Binary("digit")))
        check(t("[^\\PZ]") == hirUclassQuery(ClassQuery.Binary("separator")))
        check(t("[^\\P{separator}]") == hirUclassQuery(ClassQuery.Binary("separator")))
        check(t("(?i)[^\\D]") == hirUclassQuery(ClassQuery.Binary("digit")))
        check(t("(?i)[^\\P{greek}]") == hirCaseFold(hirUclassQuery(ClassQuery.Binary("greek"))))
        check(t("(?-u)[a]") == hirBclass(listOf(Pair(97u, 97u))))
        check(t("(?-u)[\\x00]") == hirBclass(listOf(Pair(0u, 0u))))
        check(tBytes("(?-u)[\\xFF]") == hirBclass(listOf(Pair(255u, 255u))))
        check(t("(?i)[a]") == hirUclass(listOf(up('A', 'A'), up('a', 'a'))))
        check(t("(?i)[k]") == hirUclass(listOf(up('K', 'K'), up('k', 'k'), up('\u212a', '\u212a'))))
        check(t("(?i)[β]") == hirUclass(listOf(up('\u0392', '\u0392'), up('\u03b2', '\u03b2'), up('\u03d0', '\u03d0'))))
        check(t("(?i-u)[k]") == hirBclass(listOf(Pair(75u, 75u), Pair(107u, 107u))))
        check(t("[^a]") == classNegate(uclass(listOf(up('a', 'a')))))
        check(t("[^\\x00]") == classNegate(uclass(listOf(up('\u0000', '\u0000')))))
        check(tBytes("(?-u)[^a]") == classNegate(bclass(listOf(Pair(97u, 97u)))))
        check(t("[^\\d]") == hirNegate(hirUclassQuery(ClassQuery.Binary("digit"))))
        check(t("[^\\pZ]") == hirNegate(hirUclassQuery(ClassQuery.Binary("separator"))))
        check(t("[^\\p{separator}]") == hirNegate(hirUclassQuery(ClassQuery.Binary("separator"))))
        check(t("(?i)[^\\p{greek}]") == hirNegate(hirCaseFold(hirUclassQuery(ClassQuery.Binary("greek")))))
        check(t("(?i)[\\P{greek}]") == hirNegate(hirCaseFold(hirUclassQuery(ClassQuery.Binary("greek")))))
        // Test some weird cases.
        check(t("[\\[]") == hirUclass(listOf(up('[', '['))))
        check(t("[&]") == hirUclass(listOf(up('&', '&'))))
        check(t("[\\&]") == hirUclass(listOf(up('&', '&'))))
        check(t("[\\&\\&]") == hirUclass(listOf(up('&', '&'))))
        check(t("[\\x00-&]") == hirUclass(listOf(up('\u0000', '&'))))
        check(t("[&-\\xFF]") == hirUclass(listOf(up('&', '\u00ff'))))
        check(t("[~]") == hirUclass(listOf(up('~', '~'))))
        check(t("[\\~]") == hirUclass(listOf(up('~', '~'))))
        check(t("[\\~\\~]") == hirUclass(listOf(up('~', '~'))))
        check(t("[\\x00-~]") == hirUclass(listOf(up('\u0000', '~'))))
        check(t("[~-\\xFF]") == hirUclass(listOf(up('~', '\u00ff'))))
        check(t("[-]") == hirUclass(listOf(up('-', '-'))))
        check(t("[\\-]") == hirUclass(listOf(up('-', '-'))))
        check(t("[\\-\\-]") == hirUclass(listOf(up('-', '-'))))
        check(t("[\\x00-\\-]") == hirUclass(listOf(up('\u0000', '-'))))
        check(t("[\\--\\xFF]") == hirUclass(listOf(up('-', '\u00ff'))))
        check(tErr("(?-u)[^a]").eq(TestError(kind = ErrorKind.InvalidUtf8, span = Span.new(Position.new(5, 1, 6), Position.new(9, 1, 10)))))
        check(t("[^\\s\\S]") == hirUclass(listOf()))
        check(tBytes("(?-u)[^\\s\\S]") == hirBclass(listOf()))
    }

    @kotlin.test.Test
    public fun classBracketedUnion() {
        check(t("[a-zA-Z]") == hirUclass(listOf(up('A', 'Z'), up('a', 'z'))))
        check(t("[a\\pZb]") == hirUnion(hirUclass(listOf(up('a', 'b'))), hirUclassQuery(ClassQuery.Binary("separator"))))
        check(t("[\\pZ\\p{Greek}]") == hirUnion(hirUclassQuery(ClassQuery.Binary("greek")), hirUclassQuery(ClassQuery.Binary("separator"))))
        check(t("[\\p{age:3.0}\\pZ\\p{Greek}]") == hirUnion(hirUclassQuery(ClassQuery.ByValue(propertyName = "age", propertyValue = "3.0")), hirUnion(hirUclassQuery(ClassQuery.Binary("greek")), hirUclassQuery(ClassQuery.Binary("separator")))))
        check(t("[[[\\p{age:3.0}\\pZ]\\p{Greek}][\\p{Cyrillic}]]") == hirUnion(hirUclassQuery(ClassQuery.ByValue(propertyName = "age", propertyValue = "3.0")), hirUnion(hirUclassQuery(ClassQuery.Binary("cyrillic")), hirUnion(hirUclassQuery(ClassQuery.Binary("greek")), hirUclassQuery(ClassQuery.Binary("separator"))))))
        check(t("(?i)[\\p{age:3.0}\\pZ\\p{Greek}]") == hirCaseFold(hirUnion(hirUclassQuery(ClassQuery.ByValue(propertyName = "age", propertyValue = "3.0")), hirUnion(hirUclassQuery(ClassQuery.Binary("greek")), hirUclassQuery(ClassQuery.Binary("separator"))))))
        check(t("[^\\p{age:3.0}\\pZ\\p{Greek}]") == hirNegate(hirUnion(hirUclassQuery(ClassQuery.ByValue(propertyName = "age", propertyValue = "3.0")), hirUnion(hirUclassQuery(ClassQuery.Binary("greek")), hirUclassQuery(ClassQuery.Binary("separator"))))))
        check(t("(?i)[^\\p{age:3.0}\\pZ\\p{Greek}]") == hirNegate(hirCaseFold(hirUnion(hirUclassQuery(ClassQuery.ByValue(propertyName = "age", propertyValue = "3.0")), hirUnion(hirUclassQuery(ClassQuery.Binary("greek")), hirUclassQuery(ClassQuery.Binary("separator")))))))
    }

    @kotlin.test.Test
    public fun classBracketedNested() {
        check(t("[a[^c]]") == classNegate(uclass(listOf(up('c', 'c')))))
        check(t("[a-b[^c]]") == classNegate(uclass(listOf(up('c', 'c')))))
        check(t("[a-c[^c]]") == classNegate(uclass(listOf())))
        check(t("[^a[^c]]") == hirUclass(listOf(up('c', 'c'))))
        check(t("[^a-b[^c]]") == hirUclass(listOf(up('c', 'c'))))
        check(t("(?i)[a[^c]]") == hirNegate(classCaseFold(uclass(listOf(up('c', 'c'))))))
        check(t("(?i)[a-b[^c]]") == hirNegate(classCaseFold(uclass(listOf(up('c', 'c'))))))
        check(t("(?i)[^a[^c]]") == hirUclass(listOf(up('C', 'C'), up('c', 'c'))))
        check(t("(?i)[^a-b[^c]]") == hirUclass(listOf(up('C', 'C'), up('c', 'c'))))
        check(t("[^a-c[^c]]") == hirUclass(listOf()))
        check(t("(?i)[^a-c[^c]]") == hirUclass(listOf()))
    }

    @kotlin.test.Test
    public fun classBracketedIntersect() {
        check(t("[abc&&b-c]") == hirUclass(listOf(up('b', 'c'))))
        check(t("[abc&&[b-c]]") == hirUclass(listOf(up('b', 'c'))))
        check(t("[[abc]&&[b-c]]") == hirUclass(listOf(up('b', 'c'))))
        check(t("[a-z&&b-y&&c-x]") == hirUclass(listOf(up('c', 'x'))))
        check(t("[c-da-b&&a-d]") == hirUclass(listOf(up('a', 'd'))))
        check(t("[a-d&&c-da-b]") == hirUclass(listOf(up('a', 'd'))))
        check(t("[a-z&&a-c]") == hirUclass(listOf(up('a', 'c'))))
        check(t("[[a-z&&a-c]]") == hirUclass(listOf(up('a', 'c'))))
        check(t("[^[a-z&&a-c]]") == hirNegate(hirUclass(listOf(up('a', 'c')))))
        check(t("(?-u)[abc&&b-c]") == hirBclass(listOf(Pair(98u, 99u))))
        check(t("(?-u)[abc&&[b-c]]") == hirBclass(listOf(Pair(98u, 99u))))
        check(t("(?-u)[[abc]&&[b-c]]") == hirBclass(listOf(Pair(98u, 99u))))
        check(t("(?-u)[a-z&&b-y&&c-x]") == hirBclass(listOf(Pair(99u, 120u))))
        check(t("(?-u)[c-da-b&&a-d]") == hirBclass(listOf(Pair(97u, 100u))))
        check(t("(?-u)[a-d&&c-da-b]") == hirBclass(listOf(Pair(97u, 100u))))
        check(t("(?i)[abc&&b-c]") == hirCaseFold(hirUclass(listOf(up('b', 'c')))))
        check(t("(?i)[abc&&[b-c]]") == hirCaseFold(hirUclass(listOf(up('b', 'c')))))
        check(t("(?i)[[abc]&&[b-c]]") == hirCaseFold(hirUclass(listOf(up('b', 'c')))))
        check(t("(?i)[a-z&&b-y&&c-x]") == hirCaseFold(hirUclass(listOf(up('c', 'x')))))
        check(t("(?i)[c-da-b&&a-d]") == hirCaseFold(hirUclass(listOf(up('a', 'd')))))
        check(t("(?i)[a-d&&c-da-b]") == hirCaseFold(hirUclass(listOf(up('a', 'd')))))
        check(t("(?i-u)[abc&&b-c]") == hirCaseFold(hirBclass(listOf(Pair(98u, 99u)))))
        check(t("(?i-u)[abc&&[b-c]]") == hirCaseFold(hirBclass(listOf(Pair(98u, 99u)))))
        check(t("(?i-u)[[abc]&&[b-c]]") == hirCaseFold(hirBclass(listOf(Pair(98u, 99u)))))
        check(t("(?i-u)[a-z&&b-y&&c-x]") == hirCaseFold(hirBclass(listOf(Pair(99u, 120u)))))
        check(t("(?i-u)[c-da-b&&a-d]") == hirCaseFold(hirBclass(listOf(Pair(97u, 100u)))))
        check(t("(?i-u)[a-d&&c-da-b]") == hirCaseFold(hirBclass(listOf(Pair(97u, 100u)))))
        // In `[a ^]`, `^` does not need to be escaped, so it makes sense that
        // `^` is also allowed to be unescaped after `&&`.
        check(t("[\\^&&^]") == hirUclass(listOf(up('^', '^'))))
        // `]` needs to be escaped after `&&` since it's not at start of class.
        check(t("[]&&\\]]") == hirUclass(listOf(up(']', ']'))))
        check(t("[-&&-]") == hirUclass(listOf(up('-', '-'))))
        check(t("[\\&&&&]") == hirUclass(listOf(up('&', '&'))))
        check(t("[\\&&&\\&]") == hirUclass(listOf(up('&', '&'))))
        // Test precedence.
        check(t("[a-w&&[^c-g]z]") == hirUclass(listOf(up('a', 'b'), up('h', 'w'))))
    }

    @kotlin.test.Test
    public fun classBracketedIntersectNegate() {
        check(t("[^\\w&&\\d]") == hirNegate(hirUclassQuery(ClassQuery.Binary("digit"))))
        check(t("[^[a-z&&a-c]]") == hirNegate(hirUclass(listOf(up('a', 'c')))))
        check(t("[^[\\w&&\\d]]") == hirNegate(hirUclassQuery(ClassQuery.Binary("digit"))))
        check(t("[^[^\\w&&\\d]]") == hirUclassQuery(ClassQuery.Binary("digit")))
        check(t("[[[^\\w]&&[^\\d]]]") == hirNegate(hirUclassPerlWord()))
        check(tBytes("(?-u)[^\\w&&\\d]") == hirNegate(hirAsciiBclass(ClassAsciiKind.Digit)))
        check(tBytes("(?-u)[^[a-z&&a-c]]") == hirNegate(hirBclass(listOf(Pair(97u, 99u)))))
        check(tBytes("(?-u)[^[\\w&&\\d]]") == hirNegate(hirAsciiBclass(ClassAsciiKind.Digit)))
        check(tBytes("(?-u)[^[^\\w&&\\d]]") == hirAsciiBclass(ClassAsciiKind.Digit))
        check(tBytes("(?-u)[[[^\\w]&&[^\\d]]]") == hirNegate(hirAsciiBclass(ClassAsciiKind.Word)))
    }

    @kotlin.test.Test
    public fun classBracketedDifference() {
        check(t("[\\pL--[:ascii:]]") == hirDifference(hirUclassQuery(ClassQuery.Binary("letter")), hirUclass(listOf(up('\u0000', '\u007f')))))
        check(t("(?-u)[[:alpha:]--[:lower:]]") == hirBclass(listOf(Pair(65u, 90u))))
    }

    @kotlin.test.Test
    public fun classBracketedSymmetricDifference() {
        check(t("[\\p{sc:Greek}~~\\p{scx:Greek}]") == hirUclass(listOf(up('\u00b7', '\u00b7'), up('\u0300', '\u0301'), up('\u0304', '\u0304'), up('\u0306', '\u0306'), up('\u0308', '\u0308'), up('\u0313', '\u0313'), up('\u0342', '\u0342'), up('\u0345', '\u0345'), up('\u0374', '\u0374'), up('\u1dc0', '\u1dc1'), up('\u205d', '\u205d'))))
        check(t("[a-g~~c-j]") == hirUclass(listOf(up('a', 'b'), up('h', 'j'))))
        check(t("(?-u)[a-g~~c-j]") == hirBclass(listOf(Pair(97u, 98u), Pair(104u, 106u))))
    }

    @kotlin.test.Test
    public fun ignoreWhitespace() {
        check(t("(?x)\\12 3") == hirLit("\n3"))
        check(t("(?x)\\x { 53 }") == hirLit("S"))
        check(t("(?x)\\x # comment\n{ # comment\n    53 # comment\n} #comment") == hirLit("S"))
        check(t("(?x)\\x 53") == hirLit("S"))
        check(t("(?x)\\x # comment\n        53 # comment") == hirLit("S"))
        check(t("(?x)\\x5 3") == hirLit("S"))
        check(t("(?x)\\p # comment\n{ # comment\n    Separator # comment\n} # comment") == hirUclassQuery(ClassQuery.Binary("separator")))
        check(t("(?x)a # comment\n{ # comment\n    5 # comment\n    , # comment\n    10 # comment\n} # comment") == hirRange(true, 5u, 10u, hirLit("a")))
        check(t("(?x)a\\  # hi there") == hirLit("a "))
    }

    @kotlin.test.Test
    public fun analysisIsUtf8() {
        // Positive examples.
        check(propsBytes("a").isUtf8())
        check(propsBytes("ab").isUtf8())
        check(propsBytes("(?-u)a").isUtf8())
        check(propsBytes("(?-u)ab").isUtf8())
        check(propsBytes("\\xFF").isUtf8())
        check(propsBytes("\\xFF\\xFF").isUtf8())
        check(propsBytes("[^a]").isUtf8())
        check(propsBytes("[^a][^a]").isUtf8())
        check(propsBytes("\\b").isUtf8())
        check(propsBytes("\\B").isUtf8())
        check(propsBytes("(?-u)\\b").isUtf8())
        check(propsBytes("(?-u)\\B").isUtf8())
        // Negative examples.
        check(!propsBytes("(?-u)\\xFF").isUtf8())
        check(!propsBytes("(?-u)\\xFF\\xFF").isUtf8())
        check(!propsBytes("(?-u)[^a]").isUtf8())
        check(!propsBytes("(?-u)[^a][^a]").isUtf8())
    }

    @kotlin.test.Test
    public fun analysisCapturesLen() {
        check(0 == props("a").explicitCapturesLen())
        check(0 == props("(?:a)").explicitCapturesLen())
        check(0 == props("(?i-u:a)").explicitCapturesLen())
        check(0 == props("(?i-u)a").explicitCapturesLen())
        check(1 == props("(a)").explicitCapturesLen())
        check(1 == props("(?P<foo>a)").explicitCapturesLen())
        check(1 == props("()").explicitCapturesLen())
        check(1 == props("()a").explicitCapturesLen())
        check(1 == props("(a)+").explicitCapturesLen())
        check(2 == props("(a)(b)").explicitCapturesLen())
        check(2 == props("(a)|(b)").explicitCapturesLen())
        check(2 == props("((a))").explicitCapturesLen())
        check(1 == props("([a&&b])").explicitCapturesLen())
    }

    @kotlin.test.Test
    public fun analysisStaticCapturesLen() {
        val len = { pattern: String -> props(pattern).staticExplicitCapturesLen() }
        check(0 == len(""))
        check(0 == len("foo|bar"))
        check(null == len("(foo)|bar"))
        check(null == len("foo|(bar)"))
        check(1 == len("(foo|bar)"))
        check(1 == len("(a|b|c|d|e|f)"))
        check(1 == len("(a)|(b)|(c)|(d)|(e)|(f)"))
        check(2 == len("(a)(b)|(c)(d)|(e)(f)"))
        check(6 == len("(a)(b)(c)(d)(e)(f)"))
        check(3 == len("(a)(b)(extra)|(a)(b)()"))
        check(3 == len("(a)(b)((?:extra)?)"))
        check(null == len("(a)(b)(extra)?"))
        check(1 == len("(foo)|(bar)"))
        check(2 == len("(foo)(bar)"))
        check(2 == len("(foo)+(bar)"))
        check(null == len("(foo)*(bar)"))
        check(0 == len("(foo)?{0}"))
        check(null == len("(foo)?{1}"))
        check(1 == len("(foo){1}"))
        check(1 == len("(foo){1,}"))
        check(1 == len("(foo){1,}?"))
        check(null == len("(foo){1,}??"))
        check(null == len("(foo){0,}"))
        check(1 == len("(foo)(?:bar)"))
        check(2 == len("(foo(?:bar)+)(?:baz(boo))"))
        check(2 == len("(?P<bar>foo)(?:bar)(bal|loon)"))
        check(2 == len("<(a)[^>]+href=\"([^\"]+)\"|<(img)[^>]+src=\"([^\"]+)\""))
    }

    @kotlin.test.Test
    public fun analysisIsAllAssertions() {
        // Positive examples.
        val p = props("\\b")
        check(!p.lookSet().isEmpty())
        check(p.minimumLen() == 0)
        val p1 = props("\\B")
        check(!p1.lookSet().isEmpty())
        check(p1.minimumLen() == 0)
        val p2 = props("^")
        check(!p2.lookSet().isEmpty())
        check(p2.minimumLen() == 0)
        val p3 = props("\$")
        check(!p3.lookSet().isEmpty())
        check(p3.minimumLen() == 0)
        val p4 = props("\\A")
        check(!p4.lookSet().isEmpty())
        check(p4.minimumLen() == 0)
        val p5 = props("\\z")
        check(!p5.lookSet().isEmpty())
        check(p5.minimumLen() == 0)
        val p6 = props("\$^\\z\\A\\b\\B")
        check(!p6.lookSet().isEmpty())
        check(p6.minimumLen() == 0)
        val p7 = props("\$|^|\\z|\\A|\\b|\\B")
        check(!p7.lookSet().isEmpty())
        check(p7.minimumLen() == 0)
        val p8 = props("^\$|\$^")
        check(!p8.lookSet().isEmpty())
        check(p8.minimumLen() == 0)
        val p9 = props("((\\b)+())*^")
        check(!p9.lookSet().isEmpty())
        check(p9.minimumLen() == 0)
        // Negative examples.
        val p10 = props("^a")
        check(!p10.lookSet().isEmpty())
        check(p10.minimumLen() == 1)
    }

    @kotlin.test.Test
    public fun analysisLookSetPrefixAny() {
        val p = props("(?-u)(?i:(?:\\b|_)win(?:32|64|dows)?(?:\\b|_))")
        check(p.lookSetPrefixAny().contains(Look.WordAscii))
    }

    @kotlin.test.Test
    public fun analysisIsAnchored() {
        val isStart = { p: String -> props(p).lookSetPrefix().contains(Look.Start) }
        val isEnd = { p: String -> props(p).lookSetSuffix().contains(Look.End) }
        // Positive examples.
        check(isStart("^"))
        check(isEnd("\$"))
        check(isStart("^^"))
        check(props("\$\$").lookSetSuffix().contains(Look.End))
        check(isStart("^\$"))
        check(isEnd("^\$"))
        check(isStart("^foo"))
        check(isEnd("foo\$"))
        check(isStart("^foo|^bar"))
        check(isEnd("foo\$|bar\$"))
        check(isStart("^(foo|bar)"))
        check(isEnd("(foo|bar)\$"))
        check(isStart("^+"))
        check(isEnd("\$+"))
        check(isStart("^++"))
        check(isEnd("\$++"))
        check(isStart("(^)+"))
        check(isEnd("(\$)+"))
        check(isStart("\$^"))
        check(isStart("\$^"))
        check(isStart("\$^|^\$"))
        check(isEnd("\$^|^\$"))
        check(isStart("\\b^"))
        check(isEnd("\$\\b"))
        check(isStart("^(?m:^)"))
        check(isEnd("(?m:\$)\$"))
        check(isStart("(?m:^)^"))
        check(isEnd("\$(?m:\$)"))
        // Negative examples.
        check(!isStart("(?m)^"))
        check(!isEnd("(?m)\$"))
        check(!isStart("(?m:^\$)|\$^"))
        check(!isEnd("(?m:^\$)|\$^"))
        check(!isStart("\$^|(?m:^\$)"))
        check(!isEnd("\$^|(?m:^\$)"))
        check(!isStart("a^"))
        check(!isStart("\$a"))
        check(!isEnd("a^"))
        check(!isEnd("\$a"))
        check(!isStart("^foo|bar"))
        check(!isEnd("foo|bar\$"))
        check(!isStart("^*"))
        check(!isEnd("\$*"))
        check(!isStart("^*+"))
        check(!isEnd("\$*+"))
        check(!isStart("^+*"))
        check(!isEnd("\$+*"))
        check(!isStart("(^)*"))
        check(!isEnd("(\$)*"))
    }

    @kotlin.test.Test
    public fun analysisIsAnyAnchored() {
        val isStart = { p: String -> props(p).lookSet().contains(Look.Start) }
        val isEnd = { p: String -> props(p).lookSet().contains(Look.End) }
        // Positive examples.
        check(isStart("^"))
        check(isEnd("\$"))
        check(isStart("\\A"))
        check(isEnd("\\z"))
        // Negative examples.
        check(!isStart("(?m)^"))
        check(!isEnd("(?m)\$"))
        check(!isStart("\$"))
        check(!isEnd("^"))
    }

    @kotlin.test.Test
    public fun analysisCanEmpty() {
        // Positive examples.
        val assertEmpty = { p: String -> check(0 == propsBytes(p).minimumLen()) }
        assertEmpty("")
        assertEmpty("()")
        assertEmpty("()*")
        assertEmpty("()+")
        assertEmpty("()?")
        assertEmpty("a*")
        assertEmpty("a?")
        assertEmpty("a{0}")
        assertEmpty("a{0,}")
        assertEmpty("a{0,1}")
        assertEmpty("a{0,10}")
        assertEmpty("\\pL*")
        assertEmpty("a*|b")
        assertEmpty("b|a*")
        assertEmpty("a|")
        assertEmpty("|a")
        assertEmpty("a||b")
        assertEmpty("a*a?(abcd)*")
        assertEmpty("^")
        assertEmpty("\$")
        assertEmpty("(?m)^")
        assertEmpty("(?m)\$")
        assertEmpty("\\A")
        assertEmpty("\\z")
        assertEmpty("\\B")
        assertEmpty("(?-u)\\B")
        assertEmpty("\\b")
        assertEmpty("(?-u)\\b")
        // Negative examples.
        val assertNonEmpty = { p: String -> check(0 != propsBytes(p).minimumLen()) }
        assertNonEmpty("a+")
        assertNonEmpty("a{1}")
        assertNonEmpty("a{1,}")
        assertNonEmpty("a{1,2}")
        assertNonEmpty("a{1,10}")
        assertNonEmpty("b|a")
        assertNonEmpty("a*a+(abcd)*")
        assertNonEmpty("\\P{any}")
        assertNonEmpty("[a--a]")
        assertNonEmpty("[a&&b]")
    }

    @kotlin.test.Test
    public fun analysisIsLiteral() {
        // Positive examples.
        check(props("a").isLiteral())
        check(props("ab").isLiteral())
        check(props("abc").isLiteral())
        check(props("(?m)abc").isLiteral())
        check(props("(?:a)").isLiteral())
        check(props("foo(?:a)").isLiteral())
        check(props("(?:a)foo").isLiteral())
        check(props("[a]").isLiteral())
        // Negative examples.
        check(!props("").isLiteral())
        check(!props("^").isLiteral())
        check(!props("a|b").isLiteral())
        check(!props("(a)").isLiteral())
        check(!props("a+").isLiteral())
        check(!props("foo(a)").isLiteral())
        check(!props("(a)foo").isLiteral())
        check(!props("[ab]").isLiteral())
    }

    @kotlin.test.Test
    public fun analysisIsAlternationLiteral() {
        // Positive examples.
        check(props("a").isAlternationLiteral())
        check(props("ab").isAlternationLiteral())
        check(props("abc").isAlternationLiteral())
        check(props("(?m)abc").isAlternationLiteral())
        check(props("foo|bar").isAlternationLiteral())
        check(props("foo|bar|baz").isAlternationLiteral())
        check(props("[a]").isAlternationLiteral())
        check(props("(?:ab)|cd").isAlternationLiteral())
        check(props("ab|(?:cd)").isAlternationLiteral())
        // Negative examples.
        check(!props("").isAlternationLiteral())
        check(!props("^").isAlternationLiteral())
        check(!props("(a)").isAlternationLiteral())
        check(!props("a+").isAlternationLiteral())
        check(!props("foo(a)").isAlternationLiteral())
        check(!props("(a)foo").isAlternationLiteral())
        check(!props("[ab]").isAlternationLiteral())
        check(!props("[ab]|b").isAlternationLiteral())
        check(!props("a|[ab]").isAlternationLiteral())
        check(!props("(a)|b").isAlternationLiteral())
        check(!props("a|(b)").isAlternationLiteral())
        check(!props("a|b").isAlternationLiteral())
        check(!props("a|b|c").isAlternationLiteral())
        check(!props("[a]|b").isAlternationLiteral())
        check(!props("a|[b]").isAlternationLiteral())
        check(!props("(?:a)|b").isAlternationLiteral())
        check(!props("a|(?:b)").isAlternationLiteral())
        check(!props("(?:z|xx)@|xx").isAlternationLiteral())
    }

    // This tests that the smart Hir.repetition constructors does some basic
    // simplifications.
    @kotlin.test.Test
    public fun smartRepetition() {
        check(t("a{0}") == Hir.empty())
        check(t("a{1}") == hirLit("a"))
        check(t("\\B{32111}") == hirLook(Look.WordUnicodeNegate))
    }

    // This tests that the smart Hir.concat constructor simplifies the given
    // exprs in a way we expect.
    @kotlin.test.Test
    public fun smartConcat() {
        check(t("") == Hir.empty())
        check(t("(?:)") == Hir.empty())
        check(t("abc") == hirLit("abc"))
        check(t("(?:foo)(?:bar)") == hirLit("foobar"))
        check(t("quux(?:foo)(?:bar)baz") == hirLit("quuxfoobarbaz"))
        check(t("foo(?:bar^baz)quux") == hirCat(mutableListOf(hirLit("foobar"), hirLook(Look.Start), hirLit("bazquux"))))
        check(t("foo(?:ba(?:r^b)az)quux") == hirCat(mutableListOf(hirLit("foobar"), hirLook(Look.Start), hirLit("bazquux"))))
    }

    // This tests that the smart Hir.alternation constructor simplifies the
    // given exprs in a way we expect.
    @kotlin.test.Test
    public fun smartAlternation() {
        check(t("(?:foo)|(?:bar)") == hirAlt(mutableListOf(hirLit("foo"), hirLit("bar"))))
        check(t("quux|(?:abc|def|xyz)|baz") == hirAlt(mutableListOf(hirLit("quux"), hirLit("abc"), hirLit("def"), hirLit("xyz"), hirLit("baz"))))
        check(t("quux|(?:abc|(?:def|mno)|xyz)|baz") == hirAlt(mutableListOf(hirLit("quux"), hirLit("abc"), hirLit("def"), hirLit("mno"), hirLit("xyz"), hirLit("baz"))))
        check(t("a|b|c|d|e|f|x|y|z") == hirUclass(listOf(up('a', 'f'), up('x', 'z'))))
        // Tests that we lift common prefixes out of an alternation.
        check(t("[A-Z]foo|[A-Z]quux") == hirCat(mutableListOf(hirUclass(listOf(up('A', 'Z'))), hirAlt(mutableListOf(hirLit("foo"), hirLit("quux"))))))
        check(t("[A-Z][A-Z]|[A-Z]quux") == hirCat(mutableListOf(hirUclass(listOf(up('A', 'Z'))), hirAlt(mutableListOf(hirUclass(listOf(up('A', 'Z'))), hirLit("quux"))))))
        check(t("[A-Z][A-Z]|[A-Z][A-Z]quux") == hirCat(mutableListOf(hirUclass(listOf(up('A', 'Z'))), hirUclass(listOf(up('A', 'Z'))), hirAlt(mutableListOf(Hir.empty(), hirLit("quux"))))))
        check(t("[A-Z]foo|[A-Z]foobar") == hirCat(mutableListOf(hirUclass(listOf(up('A', 'Z'))), hirAlt(mutableListOf(hirLit("foo"), hirLit("foobar"))))))
    }

    @kotlin.test.Test
    public fun regressionAltEmptyConcat() {
        val span = Span.splat(Position.new(0, 0, 0))
        val ast = Ast.alternation(Alternation(span = span, asts = mutableListOf(Ast.concat(Concat(span = span, asts = mutableListOf())))))
        val t = Translator.new()
        check(Result.Ok(Hir.empty()) == t.translate("", ast))
    }

    @kotlin.test.Test
    public fun regressionEmptyAlt() {
        val span = Span.splat(Position.new(0, 0, 0))
        val ast = Ast.concat(Concat(span = span, asts = mutableListOf(Ast.alternation(Alternation(span = span, asts = mutableListOf())))))
        val t = Translator.new()
        check(Result.Ok(Hir.fail()) == t.translate("", ast))
    }

    @kotlin.test.Test
    public fun regressionSingletonAlt() {
        val span = Span.splat(Position.new(0, 0, 0))
        val ast = Ast.concat(Concat(span = span, asts = mutableListOf(Ast.alternation(Alternation(span = span, asts = mutableListOf(Ast.dot(span)))))))
        val t = Translator.new()
        check(Result.Ok(Hir.dot(Dot.AnyCharExceptLF)) == t.translate("", ast))
    }

    // See: https://bugs.chromium.org/p/oss-fuzz/issues/detail?id=63168
    @kotlin.test.Test
    public fun regressionFuzzMatch() {
        val pat = "[(\u0006 \u0000-򯷵]  \u0000 "
        val ast = ParserBuilder.new().octal(false).ignoreWhitespace(true).build().parse(pat).unwrap()
        val hir = TranslatorBuilder.new().utf8(true).caseInsensitive(false).multiLine(false).dotMatchesNewLine(false).swapGreed(true).unicode(true).build().translate(pat, ast).unwrap()
        check(hir == Hir.concat(mutableListOf(hirUclass(listOf(up('\u0000', 0xafdf5u))), hirLit("\u0000"))))
    }

    // See: https://bugs.chromium.org/p/oss-fuzz/issues/detail?id=63155
    @kotlin.test.Test
    public fun regressionFuzzDifference1() {
        val pat = "\\W\\W|\\W[^\\v--\\W\\W\\P{Script_Extensions:Pau_Cin_Hau}\\u10A1A1-\\U{3E3E3}--~~~~--~~~~~~~~------~~~~~~--~~~~~~]*"
        t(pat)
        // shouldn't panic
    }

    // See: https://bugs.chromium.org/p/oss-fuzz/issues/detail?id=63153
    @kotlin.test.Test
    public fun regressionFuzzCharDecrement1() {
        val pat = "w[w[^w?\rw\rw[^w?\rw[^w?\rw[^w?\rw[^w?\rw[^w?\rw[^w?\r\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000w?\rw[^w?\rw[^w?\rw[^w\u0000\u0000\u0001\u0000]\u0000\u0000-*\u0000]\u0000\u0000\u0000\u0000\u0000\u0000\u0001\u0000]\u0000\u0000-*\u0000]\u0000\u0000\u0000\u0000\u0000\u0001\u0000]\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000*\u0000\u0000\u0001\u0000]\u0000\u0000-*\u0000][^w?\rw[^w?\rw[^w?\rw[^w?\rw[^w?\rw[^w?\rw[^w\u0000\u0000\u0001\u0000]\u0000\u0000-*\u0000]\u0000\u0000\u0000\u0000\u0000\u0000\u0001\u0000]\u0000\u0000-*\u0000]\u0000\u0000\u0000\u0000\u0000\u0001\u0000]\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000x\u0000\u0000\u0001\u0000]\u0000\u0000-*\u0000]\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000*??\u0000\u007f{2}\u0010??\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0003\u0000\u0000\u0000}\u0000-*\u0000]\u0000\u0000\u0000\u0000\u0000\u0000\u0001\u0000]\u0000\u0000-*\u0000]\u0000\u0000\u0000\u0000\u0000\u0000\u0001\u0000]\u0000\u0000-*\u0000]\u0000\u0000\u0000\u0000\u0000\u0001\u0000]\u0000\u0000-*\u0000]\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0001\u0000]\u0000\u0001\u0001H-i]-]\u0000\u0000\u0000\u0000\u0001\u0000]\u0000\u0000\u0000\u0001\u0000]\u0000\u0000-*\u0000\u0000\u0000\u0000\u00019-\u007f]\u0000'|-\u007f]\u0000'|(?i-ux)[-\u007f]\u0000'\u0003\u0000\u0000\u0000}\u0000-*\u0000]<D\u0000\u0000\u0000\u0000\u0000\u0000\u0001]\u0000\u0000\u0000\u0000]\u0000\u0000-*\u0000]\u0000\u0000 "
        t(pat)
        // shouldn't panic
    }
}
