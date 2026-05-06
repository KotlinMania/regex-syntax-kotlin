// port-lint: source hir/print.rs
package io.github.kotlinmania.regexsyntax.hir.print

import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.regexsyntax.parser.ParserBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class PrintTest {
    private fun roundtrip(given: String, expected: String) {
        roundtrip_with({ it }, given, expected)
    }

    private fun roundtrip_bytes(given: String, expected: String) {
        roundtrip_with({ it.utf8(false) }, given, expected)
    }

    private fun roundtrip_with(f: (ParserBuilder) -> ParserBuilder, given: String, expected: String) {
        val builder = ParserBuilder.new()
        val hir = f(builder).build().parse(given).getOrThrow()

        val printer = Printer.new()
        val dst = StringBuilder()
        printer.print(hir, dst).getOrThrow()

        builder.build().parse(dst.toString()).getOrThrow()

        assertEquals(expected, dst.toString())
    }

    @Test
    fun print_literal() {
        roundtrip("a", "a")
        roundtrip("\\xff", "\u00FF")
        roundtrip_bytes("\\xff", "\u00FF")
        roundtrip_bytes("(?-u)\\xff", "(?-u:\\xFF)")
        roundtrip("☃", "☃")
    }

    @Test
    fun print_class() {
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
        roundtrip_bytes("(?-u)[a-\\xFF]", "(?-u:[a-\\xFF])")

        // The following test that the printer escapes meta characters
        // in character classes.
        roundtrip("[\\[]", "\\[")
        roundtrip("[Z-_]", "[Z-_]")
        roundtrip("[Z-_--Z]", "[\\[-_]")

        // The following test that the printer escapes meta characters
        // in byte oriented character classes.
        roundtrip_bytes("(?-u)[\\[]", "\\[")
        roundtrip_bytes("(?-u)[Z-_]", "(?-u:[Z-_])")
        roundtrip_bytes("(?-u)[Z-_--Z]", "(?-u:[\\[-_])")

        // This tests that an empty character class is correctly roundtripped.
        roundtrip("\\P{any}", "[a&&b]")
        roundtrip_bytes("(?-u)[^\\x00-\\xFF]", "[a&&b]")
    }

    @Test
    fun print_anchor() {
        roundtrip("^", "\\A")
        roundtrip("$", "\\z")
        roundtrip("(?m)^", "(?m:^)")
        roundtrip("(?m)$", "(?m:$)")
    }

    @Test
    fun print_word_boundary() {
        roundtrip("\\b", "\\b")
        roundtrip("\\B", "\\B")
        roundtrip("(?-u)\\b", "(?-u:\\b)")
        roundtrip_bytes("(?-u)\\B", "(?-u:\\B)")
    }

    @Test
    fun print_repetition() {
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
    fun print_group() {
        roundtrip("()", "((?:))")
        roundtrip("(?P<foo>)", "(?P<foo>(?:))")
        roundtrip("(?:)", "(?:)")

        roundtrip("(a)", "(a)")
        roundtrip("(?P<foo>a)", "(?P<foo>a)")
        roundtrip("(?:a)", "a")

        roundtrip("((((a))))", "((((a))))")
    }

    @Test
    fun print_alternation() {
        roundtrip("|", "(?:(?:)|(?:))")
        roundtrip("||", "(?:(?:)|(?:)|(?:))")

        roundtrip("a|b", "[ab]")
        roundtrip("ab|cd", "(?:(?:ab)|(?:cd))")
        roundtrip("a|b|c", "[a-c]")
        roundtrip("ab|cd|ef", "(?:(?:ab)|(?:cd)|(?:ef))")
        roundtrip("foo|bar|quux", "(?:(?:foo)|(?:bar)|(?:quux))")
    }

    // This is a regression test that stresses a peculiarity of how the HIR
    // is both constructed and printed. Namely, it is legal for a repetition
    // to directly contain a concatenation. This particular construct isn't
    // really possible to build from the concrete syntax directly, since you'd
    // be forced to put the concatenation into (at least) a non-capturing
    // group. Concurrently, the printer doesn't consider this case and just
    // kind of naively prints the child expression and tacks on the repetition
    // operator.
    //
    // As a result, if you attached '+' to a 'concat(a, b)', the printer gives
    // you 'ab+', but clearly it really should be '(?:ab)+'.
    //
    // This bug isn't easy to surface because most ways of building an HIR
    // come directly from the concrete syntax, and as mentioned above, it just
    // isn't possible to build this kind of HIR from the concrete syntax.
    // Nevertheless, this is definitely a bug.
    //
    // See: https://github.com/rust-lang/regex/issues/731
    @Test
    fun regression_repetition_concat() {
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

    // Just like regression_repetition_concat, but with the repetition using
    // an alternation as a child expression instead.
    //
    // See: https://github.com/rust-lang/regex/issues/731
    @Test
    fun regression_repetition_alternation() {
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

    // This regression test is very similar in flavor to
    // regression_repetition_concat in that the root of the issue lies in a
    // peculiarity of how the HIR is represented and how the printer writes it
    // out. Like the other regression, this one is also rooted in the fact that
    // you can't produce the peculiar HIR from the concrete syntax. Namely, you
    // just can't have a 'concat(a, alt(b, c))' because the 'alt' will normally
    // be in (at least) a non-capturing group. Why? Because the '|' has very
    // low precedence (lower that concatenation), and so something like 'ab|c'
    // is actually 'alt(ab, c)'.
    //
    // See: https://github.com/rust-lang/regex/issues/516
    @Test
    fun regression_alternation_concat() {
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
