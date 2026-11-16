// port-lint: tests ast/print.rs
package io.github.kotlinmania.regexsyntax.ast.print

import io.github.kotlinmania.regexsyntax.ast.parse.ParserBuilder

// This module provides a regular expression printer for `Ast`.

public fun roundtrip(given: String) {
    roundtripWith({ b -> b }, given)
}

public fun roundtripWith(f: (ParserBuilder) -> ParserBuilder, given: String) {
    val builder = ParserBuilder.new()
    f(builder)
    val ast = builder.build().parse(given).unwrap()
    val printer = Printer.new()
    val dst = StringBuilder()
    printer.print(ast, dst).unwrap()
    check(given == dst.toString())
}

public class AstPrintInlineTests {
    @kotlin.test.Test
    public fun printLiteral() {
        roundtrip("a")
        roundtrip("\\[")
        roundtripWith({ b -> b.octal(true) }, "\\141")
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

    @kotlin.test.Test
    public fun printDot() {
        roundtrip(".")
    }

    @kotlin.test.Test
    public fun printConcat() {
        roundtrip("ab")
        roundtrip("abcde")
        roundtrip("a(bcd)ef")
    }

    @kotlin.test.Test
    public fun printAlternation() {
        roundtrip("a|b")
        roundtrip("a|b|c|d|e")
        roundtrip("|a|b|c|d|e")
        roundtrip("|a|b|c|d|e|")
        roundtrip("a(b|c|d)|e|f")
    }

    @kotlin.test.Test
    public fun printAssertion() {
        roundtrip("^")
        roundtrip("\$")
        roundtrip("\\A")
        roundtrip("\\z")
        roundtrip("\\b")
        roundtrip("\\B")
    }

    @kotlin.test.Test
    public fun printRepetition() {
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

    @kotlin.test.Test
    public fun printFlags() {
        roundtrip("(?i)")
        roundtrip("(?-i)")
        roundtrip("(?s-i)")
        roundtrip("(?-si)")
        roundtrip("(?siUmux)")
    }

    @kotlin.test.Test
    public fun printGroup() {
        roundtrip("(?i:a)")
        roundtrip("(?P<foo>a)")
        roundtrip("(?<foo>a)")
        roundtrip("(a)")
    }

    @kotlin.test.Test
    public fun printClass() {
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
