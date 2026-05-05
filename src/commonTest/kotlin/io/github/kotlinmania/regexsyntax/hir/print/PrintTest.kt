// port-lint: source src/hir/print.rs
package io.github.kotlinmania.regexsyntax.hir.print

import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.regexsyntax.parser.ParserBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class PrintTest {
    private fun roundtrip(given: String, expected: String) {
        roundtripWith({ it }, given, expected)
    }

    private fun roundtripBytes(given: String, expected: String) {
        roundtripWith({ it.utf8(false) }, given, expected)
    }

    private fun roundtripWith(f: (ParserBuilder) -> ParserBuilder, given: String, expected: String) {
        val builder = ParserBuilder.new()
        val hir = f(builder).build().parse(given).getOrThrow()

        val printer = Printer.new()
        val dst = StringBuilder()
        printer.print(hir, dst).getOrThrow()

        builder.build().parse(dst.toString()).getOrThrow()

        assertEquals(expected, dst.toString())
    }

    @Test
    fun printLiteral() {
        roundtrip("a", "a")
        roundtrip("\\xff", "\u00FF")
        roundtripBytes("\\xff", "\u00FF")
        roundtripBytes("(?-u)\\xff", "(?-u:\\xFF)")
        roundtrip("☃", "☃")
    }

    @Test
    fun printClass() {
        roundtrip("[a]", "a")
        roundtrip("[ab]", "[ab]")
        roundtrip("[a-z]", "[a-z]")
        roundtrip("[a-z--b-c--x-y]", "[ad-wz]")
        roundtrip("[^\\x01-\\u{10FFFF}]", "\u0000")
        roundtrip("[-]", "\\-")
        roundtrip("[☃-⛄]", "[☃-⛄]")

        roundtrip("(?-u)[a]", "a")
        roundtrip("(?-u)[ab]", "(?-u:[ab])")
        roundtrip("(?-u)[a-z]", "(?-u:[a-z])")
        roundtripBytes("(?-u)[a-\\xFF]", "(?-u:[a-\\xFF])")

        // The following test that the printer escapes meta characters
        // in character classes.
        roundtrip("[\\[]", "\\[")
        roundtrip("[Z-_]", "[Z-_]")
        roundtrip("[Z-_--Z]", "[\\[-_]")

        // The following test that the printer escapes meta characters
        // in byte oriented character classes.
        roundtripBytes("(?-u)[\\[]", "\\[")
        roundtripBytes("(?-u)[Z-_]", "(?-u:[Z-_])")
        roundtripBytes("(?-u)[Z-_--Z]", "(?-u:[\\[-_])")

        // This tests that an empty character class is correctly roundtripped.
        roundtrip("\\P{any}", "[a&&b]")
        roundtripBytes("(?-u)[^\\x00-\\xFF]", "[a&&b]")
    }

    @Test
    fun printAnchor() {
        roundtrip("^", "\\A")
        roundtrip("$", "\\z")
        roundtrip("(?m)^", "(?m:^)")
        roundtrip("(?m)$", "(?m:$)")
    }

    @Test
    fun printWordBoundary() {
        roundtrip("\\b", "\\b")
        roundtrip("\\B", "\\B")
        roundtrip("(?-u)\\b", "(?-u:\\b)")
        roundtripBytes("(?-u)\\B", "(?-u:\\B)")
    }

    @Test
    fun printRepetition() {
        roundtrip("a?", "a?")
        roundtrip("a??", "a??")
        roundtrip("(?U)a?", "a??")

        roundtrip("a*", "a*")
        roundtrip("a*?", "a*?")
        roundtrip("(?U)a*", "a*?")

        roundtrip("a+", "a+")
        roundtrip("a+?", "a+?")
        roundtrip("(?U)a+", "a+?")

        roundtrip("a{1}", "a")
        roundtrip("a{2}", "a{2}")
        roundtrip("a{1,}", "a+")
        roundtrip("a{1,5}", "a{1,5}")
        roundtrip("a{1}?", "a")
        roundtrip("a{2}?", "a{2}")
        roundtrip("a{1,}?", "a+?")
        roundtrip("a{1,5}?", "a{1,5}?")
        roundtrip("(?U)a{1}", "a")
        roundtrip("(?U)a{2}", "a{2}")
        roundtrip("(?U)a{1,}", "a+?")
        roundtrip("(?U)a{1,5}", "a{1,5}?")

        // Test that various zero-length repetitions always translate to an
        // empty regex. This is more a property of HIR's smart constructors
        // than the printer though.
        roundtrip("a{0}", "(?:)")
        roundtrip("(?:ab){0}", "(?:)")
        roundtrip("\\p{any}{0}", "(?:)")
        roundtrip("\\P{any}{0}", "(?:)")
    }

    @Test
    fun printGroup() {
        roundtrip("()", "((?:))")
        roundtrip("(?P<foo>)", "(?P<foo>(?:))")
        roundtrip("(?:)", "(?:)")

        roundtrip("(a)", "(a)")
        roundtrip("(?P<foo>a)", "(?P<foo>a)")
        roundtrip("(?:a)", "a")

        roundtrip("((((a))))", "((((a))))")
    }

    @Test
    fun printAlternation() {
        roundtrip("|", "(?:(?:)|(?:))")
        roundtrip("||", "(?:(?:)|(?:)|(?:))")

        roundtrip("a|b", "[ab]")
        roundtrip("ab|cd", "(?:(?:ab)|(?:cd))")
        roundtrip("a|b|c", "[a-c]")
        roundtrip("ab|cd|ef", "(?:(?:ab)|(?:cd)|(?:ef))")
        roundtrip("foo|bar|quux", "(?:(?:foo)|(?:bar)|(?:quux))")
    }

    // See: https://github.com/rust-lang/regex/issues/731
    @Test
    fun regressionRepetitionConcat() {
        var expr = Hir.concat(listOf(
            Hir.literal("x".encodeToByteArray()),
            Hir.repetition(Repetition(
                min = 1u,
                max = null,
                greedy = true,
                sub = Hir.literal("ab".encodeToByteArray()),
            )),
            Hir.literal("y".encodeToByteArray()),
        ))
        assertEquals("(?:x(?:ab)+y)", expr.toString())

        expr = Hir.concat(listOf(
            Hir.look(Look.Start),
            Hir.repetition(Repetition(
                min = 1u,
                max = null,
                greedy = true,
                sub = Hir.concat(listOf(
                    Hir.look(Look.Start),
                    Hir.look(Look.End),
                )),
            )),
            Hir.look(Look.End),
        ))
        assertEquals("(?:\\A\\A\\z\\z)", expr.toString())
    }

    // See: https://github.com/rust-lang/regex/issues/731
    @Test
    fun regressionRepetitionAlternation() {
        var expr = Hir.concat(listOf(
            Hir.literal("ab".encodeToByteArray()),
            Hir.repetition(Repetition(
                min = 1u,
                max = null,
                greedy = true,
                sub = Hir.alternation(listOf(
                    Hir.literal("cd".encodeToByteArray()),
                    Hir.literal("ef".encodeToByteArray()),
                )),
            )),
            Hir.literal("gh".encodeToByteArray()),
        ))
        assertEquals("(?:(?:ab)(?:(?:cd)|(?:ef))+(?:gh))", expr.toString())

        expr = Hir.concat(listOf(
            Hir.look(Look.Start),
            Hir.repetition(Repetition(
                min = 1u,
                max = null,
                greedy = true,
                sub = Hir.alternation(listOf(
                    Hir.look(Look.Start),
                    Hir.look(Look.End),
                )),
            )),
            Hir.look(Look.End),
        ))
        assertEquals("(?:\\A(?:\\A|\\z)\\z)", expr.toString())
    }

    // See: https://github.com/rust-lang/regex/issues/516
    @Test
    fun regressionAlternationConcat() {
        var expr = Hir.concat(listOf(
            Hir.literal("ab".encodeToByteArray()),
            Hir.alternation(listOf(
                Hir.literal("mn".encodeToByteArray()),
                Hir.literal("xy".encodeToByteArray()),
            )),
        ))
        assertEquals("(?:(?:ab)(?:(?:mn)|(?:xy)))", expr.toString())

        expr = Hir.concat(listOf(
            Hir.look(Look.Start),
            Hir.alternation(listOf(
                Hir.look(Look.Start),
                Hir.look(Look.End),
            )),
        ))
        assertEquals("(?:\\A(?:\\A|\\z))", expr.toString())
    }
}
