// port-lint: tests hir/print.rs
package io.github.kotlinmania.regexsyntax.hir.print

import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.regexsyntax.parser.ParserBuilder
import io.github.kotlinmania.regexsyntax.unwrap

// This module provides a regular expression printer for `Hir`.

public fun roundtrip(given: String, expected: String) {
    roundtripWith({ b -> b }, given, expected)
}

public fun roundtripBytes(given: String, expected: String) {
    roundtripWith({ b -> b.utf8(false) }, given, expected)
}

public fun roundtripWith(f: (ParserBuilder) -> ParserBuilder, given: String, expected: String) {
    val builder = ParserBuilder.new()
    f(builder)
    val hir = builder.build().parse(given).unwrap()
    val printer = Printer.new()
    val dst = StringBuilder()
    printer.print(hir, dst).unwrap()
    // Check that the result is actually valid.
    builder.build().parse(dst.toString()).unwrap()
    check(expected == dst.toString())
}

@kotlin.test.Test
public fun printLiteral() {
    roundtrip("a", "a")
    roundtrip("\\xff", "ÿ")
    roundtripBytes("\\xff", "ÿ")
    roundtripBytes("(?-u)\\xff", "(?-u:\\xFF)")
    roundtrip("☃", "☃")
}

@kotlin.test.Test
public fun printClass() {
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

@kotlin.test.Test
public fun printAnchor() {
    roundtrip("^", "\\A")
    roundtrip("\$", "\\z")
    roundtrip("(?m)^", "(?m:^)")
    roundtrip("(?m)\$", "(?m:\$)")
}

@kotlin.test.Test
public fun printWordBoundary() {
    roundtrip("\\b", "\\b")
    roundtrip("\\B", "\\B")
    roundtrip("(?-u)\\b", "(?-u:\\b)")
    roundtripBytes("(?-u)\\B", "(?-u:\\B)")
}

@kotlin.test.Test
public fun printRepetition() {
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
    run {
        roundtrip("\\p{any}{0}", "(?:)")
        roundtrip("\\P{any}{0}", "(?:)")
    }
}

@kotlin.test.Test
public fun printGroup() {
    roundtrip("()", "((?:))")
    roundtrip("(?P<foo>)", "(?P<foo>(?:))")
    roundtrip("(?:)", "(?:)")
    roundtrip("(a)", "(a)")
    roundtrip("(?P<foo>a)", "(?P<foo>a)")
    roundtrip("(?:a)", "a")
    roundtrip("((((a))))", "((((a))))")
}

@kotlin.test.Test
public fun printAlternation() {
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
// As a result, if you attached '+' to a concat(a, b)', the printer gives
// you ab+', but clearly it really should be '(?:ab)+'.
//
// This bug isn't easy to surface because most ways of building an HIR
// come directly from the concrete syntax, and as mentioned above, it just
// isn't possible to build this kind of HIR from the concrete syntax.
// Nevertheless, this is definitely a bug.
//
// See: https://github.com/rust-lang/regex/issues/731
@kotlin.test.Test
public fun regressionRepetitionConcat() {
    val expr = Hir.concat(mutableListOf(Hir.literal("x".encodeToByteArray().map { it.toUByte() }), Hir.repetition(Repetition(min = 1u, max = null, greedy = true, sub = Hir.literal("ab".encodeToByteArray().map { it.toUByte() }))), Hir.literal("y".encodeToByteArray().map { it.toUByte() })))
    check("(?:x(?:ab)+y)" == expr.toString())
    val expr1 = Hir.concat(mutableListOf(Hir.look(Look.Start), Hir.repetition(Repetition(min = 1u, max = null, greedy = true, sub = Hir.concat(mutableListOf(Hir.look(Look.Start), Hir.look(Look.End))))), Hir.look(Look.End)))
    check("(?:\\A\\A\\z\\z)" == expr1.toString())
}

// Just like regressionRepetitionConcat, but with the repetition using
// an alternation as a child expression instead.
//
// See: https://github.com/rust-lang/regex/issues/731
@kotlin.test.Test
public fun regressionRepetitionAlternation() {
    val expr = Hir.concat(mutableListOf(Hir.literal("ab".encodeToByteArray().map { it.toUByte() }), Hir.repetition(Repetition(min = 1u, max = null, greedy = true, sub = Hir.alternation(mutableListOf(Hir.literal("cd".encodeToByteArray().map { it.toUByte() }), Hir.literal("ef".encodeToByteArray().map { it.toUByte() }))))), Hir.literal("gh".encodeToByteArray().map { it.toUByte() })))
    check("(?:(?:ab)(?:(?:cd)|(?:ef))+(?:gh))" == expr.toString())
    val expr1 = Hir.concat(mutableListOf(Hir.look(Look.Start), Hir.repetition(Repetition(min = 1u, max = null, greedy = true, sub = Hir.alternation(mutableListOf(Hir.look(Look.Start), Hir.look(Look.End))))), Hir.look(Look.End)))
    check("(?:\\A(?:\\A|\\z)\\z)" == expr1.toString())
}

// This regression test is very similar in flavor to
// regressionRepetitionConcat in that the root of the issue lies in a
// peculiarity of how the HIR is represented and how the printer writes it
// out. Like the other regression, this one is also rooted in the fact that
// you can't produce the peculiar HIR from the concrete syntax. Namely, you
// just can't have a `concat(a, alt(b, c))` because the `alt` will normally
// be in (at least) a non-capturing group. Why? Because the '|' has very
// low precedence (lower that concatenation), and so something like `ab|c`
// is actually `alt(ab, c)`.
//
// See: https://github.com/rust-lang/regex/issues/516
@kotlin.test.Test
public fun regressionAlternationConcat() {
    val expr = Hir.concat(mutableListOf(Hir.literal("ab".encodeToByteArray().map { it.toUByte() }), Hir.alternation(mutableListOf(Hir.literal("mn".encodeToByteArray().map { it.toUByte() }), Hir.literal("xy".encodeToByteArray().map { it.toUByte() })))))
    check("(?:(?:ab)(?:(?:mn)|(?:xy)))" == expr.toString())
    val expr1 = Hir.concat(mutableListOf(Hir.look(Look.Start), Hir.alternation(mutableListOf(Hir.look(Look.Start), Hir.look(Look.End)))))
    check("(?:\\A(?:\\A|\\z))" == expr1.toString())
}
