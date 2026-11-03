// port-lint: source ast/print.rs
package io.github.kotlinmania.regexsyntax.ast.print

import io.github.kotlinmania.regexsyntax.ast.parse.ParserBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class PrintTest {
    private fun roundtrip(given: String) {
        roundtripWith({ it }, given)
    }

    private fun roundtripWith(f: (ParserBuilder) -> ParserBuilder, given: String) {
        val builder = ParserBuilder.new()
        val ast = f(builder).build().parse(given).getOrThrow()

        val printer = Printer.new()
        val dst = StringBuilder()
        printer.print(ast, dst).getOrThrow()
        assertEquals(given, dst.toString())
    }

    @Test
    fun printLiteral() {
        roundtrip("a")
        roundtrip("\\[")
        roundtripWith({ it.octal(true) }, "\\141")
        roundtrip("\\x61")
        roundtrip("\\x7F")
        roundtrip("\\u0061")
        roundtrip("\\U00000061")
        roundtrip("\\x{61}")
        roundtrip("\\x{7F}")
        roundtrip("\\u{61}")
        roundtrip("\\U{61}")

        roundtrip("\\a")
        roundtrip("\\f")
        roundtrip("\\t")
        roundtrip("\\n")
        roundtrip("\\r")
        roundtrip("\\v")
        roundtrip("(?x)\\ ")
    }

    @Test
    fun printDot() {
        roundtrip(".")
    }

    @Test
    fun printConcat() {
        roundtrip("ab")
        roundtrip("abcde")
        roundtrip("a(bcd)ef")
    }

    @Test
    fun printAlternation() {
        roundtrip("a|b")
        roundtrip("a|b|c|d|e")
        roundtrip("|a|b|c|d|e")
        roundtrip("|a|b|c|d|e|")
        roundtrip("a(b|c|d)|e|f")
    }

    @Test
    fun printAssertion() {
        roundtrip("^")
        roundtrip("$")
        roundtrip("\\A")
        roundtrip("\\z")
        roundtrip("\\b")
        roundtrip("\\B")
    }

    @Test
    fun printRepetition() {
        roundtrip("a?")
        roundtrip("a??")
        roundtrip("a*")
        roundtrip("a*?")
        roundtrip("a+")
        roundtrip("a+?")
        roundtrip("a{5}")
        roundtrip("a{5}?")
        roundtrip("a{5,}")
        roundtrip("a{5,}?")
        roundtrip("a{5,10}")
        roundtrip("a{5,10}?")
    }

    @Test
    fun printFlags() {
        roundtrip("(?i)")
        roundtrip("(?-i)")
        roundtrip("(?s-i)")
        roundtrip("(?-si)")
        roundtrip("(?siUmux)")
    }

    @Test
    fun printGroup() {
        roundtrip("(?i:a)")
        roundtrip("(?P<foo>a)")
        roundtrip("(?<foo>a)")
        roundtrip("(a)")
    }

    @Test
    fun printClass() {
        roundtrip("[abc]")
        roundtrip("[a-z]")
        roundtrip("[^a-z]")
        roundtrip("[a-z0-9]")
        roundtrip("[-a-z0-9]")
        roundtrip("[-a-z0-9]")
        roundtrip("[a-z0-9---]")
        roundtrip("[a-z&&m-n]")
        roundtrip("[[a-z&&m-n]]")
        roundtrip("[a-z--m-n]")
        roundtrip("[a-z~~m-n]")
        roundtrip("[a-z[0-9]]")
        roundtrip("[a-z[^0-9]]")

        roundtrip("\\d")
        roundtrip("\\D")
        roundtrip("\\s")
        roundtrip("\\S")
        roundtrip("\\w")
        roundtrip("\\W")

        roundtrip("[[:alnum:]]")
        roundtrip("[[:^alnum:]]")
        roundtrip("[[:alpha:]]")
        roundtrip("[[:^alpha:]]")
        roundtrip("[[:ascii:]]")
        roundtrip("[[:^ascii:]]")
        roundtrip("[[:blank:]]")
        roundtrip("[[:^blank:]]")
        roundtrip("[[:cntrl:]]")
        roundtrip("[[:^cntrl:]]")
        roundtrip("[[:digit:]]")
        roundtrip("[[:^digit:]]")
        roundtrip("[[:graph:]]")
        roundtrip("[[:^graph:]]")
        roundtrip("[[:lower:]]")
        roundtrip("[[:^lower:]]")
        roundtrip("[[:print:]]")
        roundtrip("[[:^print:]]")
        roundtrip("[[:punct:]]")
        roundtrip("[[:^punct:]]")
        roundtrip("[[:space:]]")
        roundtrip("[[:^space:]]")
        roundtrip("[[:upper:]]")
        roundtrip("[[:^upper:]]")
        roundtrip("[[:word:]]")
        roundtrip("[[:^word:]]")
        roundtrip("[[:xdigit:]]")
        roundtrip("[[:^xdigit:]]")

        roundtrip("\\pL")
        roundtrip("\\PL")
        roundtrip("\\p{L}")
        roundtrip("\\P{L}")
        roundtrip("\\p{X=Y}")
        roundtrip("\\P{X=Y}")
        roundtrip("\\p{X:Y}")
        roundtrip("\\P{X:Y}")
        roundtrip("\\p{X!=Y}")
        roundtrip("\\P{X!=Y}")
    }
}
