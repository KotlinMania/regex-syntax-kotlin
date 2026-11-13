package io.github.kotlinmania.regexsyntax

// This crate provides a robust regular expression parser.
//
// This crate defines two primary types:
//
// * [`Ast`](ast.Ast) is the abstract syntax of a regular expression.
//  An abstract syntax corresponds to a *structured representation* of the
//  concrete syntax of a regular expression, where the concrete syntax is the
//  pattern string itself (e.g., `foo(bar) +`). Given some abstract syntax, it
//  can be converted back to the original concrete syntax (modulo some details,
//  like whitespace). To a first approximation, the abstract syntax is complex
//  and difficult to analyze.
// * [`Hir`](hir.Hir) is the high-level intermediate representation
//  ("HIR" or "high-level IR" for short) of regular expression. It corresponds to
//  an intermediate state of a regular expression that sits between the abstract
//  syntax and the low level compiled opcodes that are eventually responsible for
//  executing a regular expression search. Given some high-level IR, it is not
//  possible to produce the original concrete syntax (although it is possible to
//  produce an equivalent concrete syntax, but it will likely scarcely resemble
//  the original pattern). To a first approximation, the high-level IR is simple
//  and easy to analyze.
//
// These two types come with conversion routines:
//
// * An [ast.parse.Parser] converts concrete syntax (a `String`) to an
// [`Ast`](ast.Ast).
// * A [hir.translate.Translator] converts an [`Ast`](ast.Ast) to a
// [`Hir`](hir.Hir).
//
// As a convenience, the above two conversion routines are combined into one via
// the top-level [Parser] type. This `Parser` will first convert your pattern to
// an `Ast` and then convert the `Ast` to an `Hir`. It's also exposed as top-level
// [parse] free function.
//
// # Example
//
// This example shows how to parse a pattern string into its HIR:
//
// ```kotlin
// import parser.parse
// import regexsyntax.hir.Hir
//
// val hir = parse("a|b").getOrElse { return Result.failure(it) }
// check(hir == Hir.alternation(mutableListOf(Hir.literal("a".encodeToByteArray().map { it.toUByte() }), Hir.literal("b".encodeToByteArray().map { it.toUByte() }))))
// ```
//
// # Concrete syntax supported
//
// The concrete syntax is documented as part of the public API of the
// [`regex` crate](https://docs.rs/regex/%2A/regex/#syntax).
//
// # Input safety
//
// A key feature of this library is that it is safe to use with end user facing
// input. This plays a significant role in the internal implementation. In
// particular:
//
// 1. Parsers provide a `nestLimit` option that permits callers to control how
//   deeply nested a regular expression is allowed to be. This makes it possible
//   to do case analysis over an `Ast` or an `Hir` using recursion without
//   worrying about stack overflow.
// 2. Since relying on a particular stack size is brittle, this crate goes to
//   great lengths to ensure that all interactions with both the `Ast` and the
//   `Hir` do not use recursion. Namely, they use constant stack space and heap
//   space proportional to the size of the original pattern string (in bytes).
//   This includes the type's corresponding destructors. (One exception to this
//   is literal extraction, but this will eventually get fixed.)
//
// # Error reporting
//
// The `Display` implementations on all `Error` types exposed in this library
// provide nice human readable errors that are suitable for showing to end users
// in a monospace font.
//
// # Literal extraction
//
// This crate provides limited support for [literal extraction from `Hir`
// values](hir.literal). Be warned that literal extraction uses recursion, and
// therefore, stack size proportional to the size of the `Hir`.
//
// The purpose of literal extraction is to speed up searches. That is, if you
// know a regular expression must match a prefix or suffix literal, then it is
// often quicker to search for instances of that literal, and then confirm or deny
// the match using the full regular expression engine. These optimizations are
// done automatically in the `regex` crate.
//
// # Crate features
//
// An important feature provided by this crate is its Unicode support. This
// includes things like case folding, boolean properties, general categories,
// scripts and Unicode-aware support for the Perl classes `\w`, `\s` and `\d`.
// However, a downside of this support is that it requires bundling several
// Unicode data tables that are substantial in size.
//
// A fair number of use cases do not require full Unicode support. For this
// reason, this crate exposes a number of features to control which Unicode
// data is available.
//
// If a regular expression attempts to use a Unicode feature that is not available
// because the corresponding crate feature was disabled, then translating that
// regular expression to an `Hir` will return an error. (It is still possible
// construct an `Ast` for such a regular expression, since Unicode data is not
// used until translation to an `Hir`.) Stated differently, enabling or disabling
// any of the features below can only add or subtract from the total set of valid
// regular expressions. Enabling or disabling a feature will never modify the
// match semantics of a regular expression.
//
// The following features are available:
//
// * **std** -
//  Enables support for the standard library. This feature is enabled by default.
//  When disabled, only `core` and `alloc` are used. Otherwise, enabling `std`
//  generally just enables `std.error.Error` trait impls for the various error
//  types.
// * **unicode** -
//  Enables all Unicode features. This feature is enabled by default, and will
//  always cover all Unicode features, even if more are added in the future.
// * **unicode-age** -
//  Provide the data for the
//  [Unicode `Age` property](https://www.unicode.org/reports/tr44/tr44-24.html#CharacterAge).
//  This makes it possible to use classes like `\p{Age:6.0}` to refer to all
//  codepoints first introduced in Unicode 6.0
// * **unicode-bool** -
//  Provide the data for numerous Unicode boolean properties. The full list
//  is not included here, but contains properties like `Alphabetic`, `Emoji`,
//  `Lowercase`, `Math`, `Uppercase` and `WhiteSpace`.
// * **unicode-case** -
//  Provide the data for case insensitive matching using
//  [Unicode's "simple loose matches" specification](https://www.unicode.org/reports/tr18/#SimpleLooseMatches).
// * **unicode-gencat** -
//  Provide the data for
//  [Unicode general categories](https://www.unicode.org/reports/tr44/tr44-24.html#GeneralCategoryValues).
//  This includes, but is not limited to, `DecimalNumber`, `Letter`,
//  `MathSymbol`, `Number` and `Punctuation`.
// * **unicode-perl** -
//  Provide the data for supporting the Unicode-aware Perl character classes,
//  corresponding to `\w`, `\s` and `\d`. This is also necessary for using
//  Unicode-aware word boundary assertions. Note that if this feature is
//  disabled, the `\s` and `\d` character classes are still available if the
//  `unicode - Boolean` and `unicode - gencat` features are enabled, respectively.
// * **unicode-script** -
//  Provide the data for
//  [Unicode scripts and script extensions](https://www.unicode.org/reports/tr24/).
//  This includes, but is not limited to, `Arabic`, `Cyrillic`, `Hebrew`,
//  `Latin` and `Thai`.
// * **unicode-segment** -
//  Provide the data necessary to provide the properties used to implement the
//  [Unicode text segmentation algorithms](https://www.unicode.org/reports/tr29/).
//  This enables using classes like `\p{gcb=Extend}`, `\p{wb=Katakana}` and
//  `\p{sb=ATerm}`.
// * **arbitrary** -
//  Enabling this feature introduces a public dependency on the
//  [`arbitrary`](https://crates.io/crates/arbitrary)
//  crate. Namely, it implements the `Arbitrary` trait from that crate for the
//  [`Ast`](ast.Ast) type. This feature is disabled by default.

@kotlin.test.Test
public fun escapeMeta() {
    check(escape("\\.+*?()|[]{}^\$#&-~") == "\\\\\\.\\+\\*\\?\\(\\)\\|\\[\\]\\{\\}\\^\\\$\\#\\&\\-\\~".toString())
}

@kotlin.test.Test
public fun wordByte() {
    check(isWordByte(97u))
    check(!isWordByte(45u))
}

@kotlin.test.Test
public fun wordChar() {
    check(isWordCharacter('a')) { "ASCII" }
    check(isWordCharacter('\u00e0')) { "Latin-1" }
    check(isWordCharacter('\u03b2')) { "Greek" }
    check(isWordCharacter(0x11011)) { "Brahmi (Unicode 6.0)" }
    check(isWordCharacter(0x11611)) { "Modi (Unicode 7.0)" }
    check(isWordCharacter(0x11711)) { "Ahom (Unicode 8.0)" }
    check(isWordCharacter(0x17828)) { "Tangut (Unicode 9.0)" }
    check(isWordCharacter(0x1b1b1)) { "Nushu (Unicode 10.0)" }
    check(isWordCharacter(0x16e40)) { "Medefaidrin (Unicode 11.0)" }
    check(!isWordCharacter('-'))
    check(!isWordCharacter('\u2603'))
}
