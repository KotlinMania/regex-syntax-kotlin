package io.github.kotlinmania.regexsyntax.ast

import io.github.kotlinmania.regexsyntax.ast.Alternation as AstAlternation
import io.github.kotlinmania.regexsyntax.ast.Assertion as AstAssertion
import io.github.kotlinmania.regexsyntax.ast.CaptureName as AstCaptureName
import io.github.kotlinmania.regexsyntax.ast.ClassBracketed as AstClassBracketed
import io.github.kotlinmania.regexsyntax.ast.ClassPerl as AstClassPerl
import io.github.kotlinmania.regexsyntax.ast.ClassUnicode as AstClassUnicode
import io.github.kotlinmania.regexsyntax.ast.Concat as AstConcat
import io.github.kotlinmania.regexsyntax.ast.Flag as AstFlag
import io.github.kotlinmania.regexsyntax.ast.Group as AstGroup
import io.github.kotlinmania.regexsyntax.ast.Literal as AstLiteral
import io.github.kotlinmania.regexsyntax.ast.Repetition as AstRepetition
import io.github.kotlinmania.regexsyntax.ast.print.Printer

/**
 * The type of an error that occurred while building an AST.
 *
 * This error type is marked as `nonExhaustive`. This means that adding a
 * new variant is not considered a breaking change.
 */
public sealed class ErrorKind {
    /**
     * The capturing group limit was exceeded.
     *
     * Note that this represents a limit on the total number of capturing
     * groups in a regex and not necessarily the number of nested capturing
     * groups. That is, the nest limit can be low and it is still possible for
     * this error to occur.
     */
    public data object CaptureLimitExceeded : ErrorKind()
    /**
     * An invalid escape sequence was found in a character class set.
     */
    public data object ClassEscapeInvalid : ErrorKind()
    /**
     * An invalid character class range was found. An invalid range is any
     * range where the start is greater than the end.
     */
    public data object ClassRangeInvalid : ErrorKind()
    /**
     * An invalid range boundary was found in a character class. Range
     * boundaries must be a single literal codepoint, but this error indicates
     * that something else was found, such as a nested class.
     */
    public data object ClassRangeLiteral : ErrorKind()
    /**
     * An opening `[` was found with no corresponding closing `]`.
     */
    public data object ClassUnclosed : ErrorKind()
    /**
     * Note that this error variant is no longer used. Namely, a decimal
     * number can only appear as a repetition quantifier. When the number
     * in a repetition quantifier is empty, then it gets its own specialized
     * error, `RepetitionCountDecimalEmpty`.
     */
    public data object DecimalEmpty : ErrorKind()
    /**
     * An invalid decimal number was given where one was expected.
     */
    public data object DecimalInvalid : ErrorKind()
    /**
     * A bracketed hex literal was empty.
     */
    public data object EscapeHexEmpty : ErrorKind()
    /**
     * A bracketed hex literal did not correspond to a Unicode scalar value.
     */
    public data object EscapeHexInvalid : ErrorKind()
    /**
     * An invalid hexadecimal digit was found.
     */
    public data object EscapeHexInvalidDigit : ErrorKind()
    /**
     * EOF was found before an escape sequence was completed.
     */
    public data object EscapeUnexpectedEof : ErrorKind()
    /**
     * An unrecognized escape sequence.
     */
    public data object EscapeUnrecognized : ErrorKind()
    /**
     * A dangling negation was used when setting flags, e.g., `i -`.
     */
    public data object FlagDanglingNegation : ErrorKind()
    /**
     * A flag was used twice, e.g., `i - i`.
     */
    public data class FlagDuplicate(public var original: Span) : ErrorKind()
    /**
     * The negation operator was used twice, e.g., `- i - s`.
     */
    public data class FlagRepeatedNegation(public var original: Span) : ErrorKind()
    /**
     * Expected a flag but got EOF, e.g., `(?`.
     */
    public data object FlagUnexpectedEof : ErrorKind()
    /**
     * Unrecognized flag, e.g., `a`.
     */
    public data object FlagUnrecognized : ErrorKind()
    /**
     * A duplicate capture name was found.
     */
    public data class GroupNameDuplicate(public var original: Span) : ErrorKind()
    /**
     * A capture group name is empty, e.g., `(? P<> abc)`.
     */
    public data object GroupNameEmpty : ErrorKind()
    /**
     * An invalid character was seen for a capture group name. This includes
     * errors where the first character is a digit (even though subsequent
     * characters are allowed to be digits).
     */
    public data object GroupNameInvalid : ErrorKind()
    /**
     * A closing `>` could not be found for a capture group name.
     */
    public data object GroupNameUnexpectedEof : ErrorKind()
    /**
     * An unclosed group, e.g., `(ab`.
     *
     * The span of this error corresponds to the unclosed parenthesis.
     */
    public data object GroupUnclosed : ErrorKind()
    /**
     * An unopened group, e.g., `ab)`.
     */
    public data object GroupUnopened : ErrorKind()
    /**
     * The nest limit was exceeded. The limit stored here is the limit
     * configured in the parser.
     */
    public data class NestLimitExceeded(public var value: UInt) : ErrorKind()
    /**
     * The range provided in a counted repetition operator is invalid. The
     * range is invalid if the start is greater than the end.
     */
    public data object RepetitionCountInvalid : ErrorKind()
    /**
     * An opening `{` was not followed by a valid decimal value.
     * For example, `x {}` or `x{]}` would fail.
     */
    public data object RepetitionCountDecimalEmpty : ErrorKind()
    /**
     * An opening `{` was found with no corresponding closing `}`.
     */
    public data object RepetitionCountUnclosed : ErrorKind()
    /**
     * A repetition operator was applied to a missing sub-expression. This
     * occurs, for example, in the regex consisting of just a `*` or even
     * `(? i) *`. It is, however, possible to create a repetition operating on
     * an empty sub-expression. For example, `() *` is still considered valid.
     */
    public data object RepetitionMissing : ErrorKind()
    /**
     * The special word boundary syntax, `\b{something}`, was used, but
     * either EOF without `}` was seen, or an invalid character in the
     * braces was seen.
     */
    public data object SpecialWordBoundaryUnclosed : ErrorKind()
    /**
     * The special word boundary syntax, `\b{something}`, was used, but
     * `something` was not recognized as a valid word boundary kind.
     */
    public data object SpecialWordBoundaryUnrecognized : ErrorKind()
    /**
     * The syntax `\b{` was observed, but afterwards the end of the pattern
     * was observed without being able to tell whether it was meant to be a
     * bounded repetition on the `\b` or the beginning of a special word
     * boundary assertion.
     */
    public data object SpecialWordOrRepetitionUnexpectedEof : ErrorKind()
    /**
     * The Unicode class is not valid. This typically occurs when a `\p` is
     * followed by something other than a `{`.
     */
    public data object UnicodeClassInvalid : ErrorKind()
    /**
     * When octal support is disabled, this error is produced when an octal
     * escape is used. The octal escape is assumed to be an invocation of
     * a backreference, which is the common case.
     */
    public data object UnsupportedBackreference : ErrorKind()
    /**
     * When syntax similar to PCRE's look-around is used, this error is
     * returned. Some example syntaxes that are rejected include, but are
     * not necessarily limited to, `(? = re)`, `(? !re)`, `(? <= re)` and
     * `(?<!re)`. Note that all of these syntaxes are otherwise invalid; this
     * error is used to improve the user experience.
     */
    public data object UnsupportedLookAround : ErrorKind()

    public fun rustEquals(other: ErrorKind): Boolean = this == other

    public open fun fmt(f: StringBuilder) {
        when (this) {
            CaptureLimitExceeded -> f.append("exceeded the maximum number of capturing groups (${UInt.MAX_VALUE})")
            ClassEscapeInvalid -> {
                f.append("invalid escape sequence found in character class")
            }
            ClassRangeInvalid -> f.append("invalid character class range, the start must be <= the end")
            ClassRangeLiteral -> {
                f.append("invalid range boundary, must be a literal")
            }
            ClassUnclosed -> f.append("unclosed character class")
            DecimalEmpty -> f.append("decimal literal empty")
            DecimalInvalid -> f.append("decimal literal invalid")
            EscapeHexEmpty -> f.append("hexadecimal literal empty")
            EscapeHexInvalid -> {
                f.append("hexadecimal literal is not a Unicode scalar value")
            }
            EscapeHexInvalidDigit -> f.append("invalid hexadecimal digit")
            EscapeUnexpectedEof -> f.append("incomplete escape sequence, reached end of pattern prematurely")
            EscapeUnrecognized -> f.append("unrecognized escape sequence")
            FlagDanglingNegation -> {
                f.append("dangling flag negation operator")
            }
            is FlagDuplicate -> f.append("duplicate flag")
            is FlagRepeatedNegation -> {
                f.append("flag negation operator repeated")
            }
            FlagUnexpectedEof -> {
                f.append("expected flag but got end of regex")
            }
            FlagUnrecognized -> f.append("unrecognized flag")
            is GroupNameDuplicate -> {
                f.append("duplicate capture group name")
            }
            GroupNameEmpty -> f.append("empty capture group name")
            GroupNameInvalid -> f.append("invalid capture group character")
            GroupNameUnexpectedEof -> f.append("unclosed capture group name")
            GroupUnclosed -> f.append("unclosed group")
            GroupUnopened -> f.append("unopened group")
            is NestLimitExceeded -> {
                val (limit) = this
                f.append("exceed the maximum number of nested parentheses/brackets ($limit)")
            }
            RepetitionCountInvalid -> f.append("invalid repetition count range, the start must be <= the end")
            RepetitionCountDecimalEmpty -> {
                f.append("repetition quantifier expects a valid decimal")
            }
            RepetitionCountUnclosed -> {
                f.append("unclosed counted repetition")
            }
            RepetitionMissing -> {
                f.append("repetition operator missing expression")
            }
            SpecialWordBoundaryUnclosed -> {
                f.append("special word boundary assertion is either unclosed or contains an invalid character")
            }
            SpecialWordBoundaryUnrecognized -> {
                f.append("unrecognized special word boundary assertion, valid choices are: start, end, start-half or end-half")
            }
            SpecialWordOrRepetitionUnexpectedEof -> {
                f.append("found either the beginning of a special word boundary or a bounded repetition on a \\b with an opening brace, but no closing brace")
            }
            UnicodeClassInvalid -> {
                f.append("invalid Unicode character class")
            }
            UnsupportedBackreference -> {
                f.append("backreferences are not supported")
            }
            UnsupportedLookAround -> f.append("look-around, including look-ahead and look-behind, is not supported")
        }
    }
}

/**
 * An abstract syntax tree for a single regular expression.
 *
 * An `Ast`s `fmt.Display` implementation uses constant stack space and heap
 * space proportional to the size of the `Ast`.
 *
 * This type defines its own destructor that uses constant stack space and
 * heap space proportional to the size of the `Ast`.
 */
public sealed class Ast {
    /**
     * An empty regex that matches everything.
     */
    public data class Empty(public var value: Span) : Ast()
    /**
     * A set of flags, e.g., `(? is)`.
     */
    public data class Flags(public var value: SetFlags) : Ast()
    /**
     * A single character literal, which includes escape sequences.
     */
    public data class Literal(public var value: AstLiteral) : Ast()
    /**
     * The "any character" class.
     */
    public data class Dot(public var value: Span) : Ast()
    /**
     * A single zero-width assertion.
     */
    public data class Assertion(public var value: AstAssertion) : Ast()
    /**
     * A single Unicode character class, e.g., `\pL` or `\p{Greek}`.
     */
    public data class ClassUnicode(public var value: AstClassUnicode) : Ast()
    /**
     * A single perl character class, e.g., `\d` or `\W`.
     */
    public data class ClassPerl(public var value: AstClassPerl) : Ast()
    /**
     * A single bracketed character class set, which may contain zero or more
     * character ranges and/or zero or more nested classes. e.g.,
     * `[a-zA-Z\pL]`.
     */
    public data class ClassBracketed(public var value: AstClassBracketed) : Ast()
    /**
     * A repetition operator applied to an arbitrary regular expression.
     */
    public data class Repetition(public var value: AstRepetition) : Ast()
    /**
     * A grouped regular expression.
     */
    public data class Group(public var value: AstGroup) : Ast()
    /**
     * An alternation of regular expressions.
     */
    public data class Alternation(public var value: AstAlternation) : Ast()
    /**
     * A concatenation of regular expressions.
     */
    public data class Concat(public var value: AstConcat) : Ast()

    public fun rustEquals(other: Ast): Boolean = this == other

    /**
     * Return the span of this abstract syntax tree.
     */
    public open fun span(): Span =
        when (this) {
            is Ast.Empty -> {
                val (span) = this
                span
            }
            is Ast.Flags -> {
                val (x) = this
                x.span
            }
            is Ast.Literal -> {
                val (x1) = this
                x1.span
            }
            is Ast.Dot -> {
                val (span1) = this
                span1
            }
            is Ast.Assertion -> {
                val (x2) = this
                x2.span
            }
            is Ast.ClassUnicode -> {
                val (x3) = this
                x3.span
            }
            is Ast.ClassPerl -> {
                val (x4) = this
                x4.span
            }
            is Ast.ClassBracketed -> {
                val (x5) = this
                x5.span
            }
            is Ast.Repetition -> {
                val (x6) = this
                x6.span
            }
            is Ast.Group -> {
                val (x7) = this
                x7.span
            }
            is Ast.Alternation -> {
                val (x8) = this
                x8.span
            }
            is Ast.Concat -> {
                val (x9) = this
                x9.span
            }
        }

    /**
     * Return true if and only if this Ast is empty.
     */
    public open fun isEmpty(): Boolean =
        when (this) {
            is Ast.Empty -> true
            else -> false
        }

    /**
     * Returns true if and only if this AST has any (including possibly empty)
     * subexpressions.
     */
    public open fun hasSubexprs(): Boolean =
        when (this) {
            is Ast.Empty -> false
            is Ast.Flags -> false
            is Ast.Literal -> false
            is Ast.Dot -> false
            is Ast.Assertion -> false
            is Ast.ClassUnicode -> false
            is Ast.ClassPerl -> false
            is Ast.ClassBracketed -> true
            is Ast.Repetition -> true
            is Ast.Group -> true
            is Ast.Alternation -> true
            is Ast.Concat -> true
        }

    /**
     * Print a display representation of this Ast.
     *
     * This does not preserve any of the original whitespace formatting that may
     * have originally been present in the concrete syntax from which this Ast
     * was generated.
     *
     * This implementation uses constant stack space and heap space proportional
     * to the size of the `Ast`.
     */
    public open fun fmt(f: StringBuilder) {
        Printer.new().print(this, f)
    }

    override fun toString(): String = buildString { fmt(this) }

    /**
     * A custom `Drop` impl is used for `Ast` such that it uses constant stack
     * space but heap space proportional to the depth of the `Ast`.
     */
    public open fun drop() {
        when (this) {
            is Ast.Empty,
            is Ast.Flags,
            is Ast.Literal,
            is Ast.Dot,
            is Ast.Assertion,
            is Ast.ClassUnicode,
            is Ast.ClassPerl,
            is Ast.ClassBracketed -> return
            is Ast.Repetition -> if (!this.value.ast.hasSubexprs()) return
            is Ast.Group -> if (!this.value.ast.hasSubexprs()) return
            is Ast.Alternation -> if (this.value.asts.isEmpty()) return
            is Ast.Concat -> if (this.value.asts.isEmpty()) return
        }
        val emptySpan = Span.splat(Position.new(0, 0, 0))
        val emptyAst: () -> Ast = { Ast.empty(emptySpan) }
        val stack = mutableListOf<Ast>(this)
        while (stack.isNotEmpty()) {
            when (val ast = stack.removeLast()) {
                is Ast.Repetition -> {
                    val child = ast.value.ast
                    ast.value.ast = emptyAst()
                    stack.add(child)
                }
                is Ast.Group -> {
                    val child = ast.value.ast
                    ast.value.ast = emptyAst()
                    stack.add(child)
                }
                is Ast.Alternation -> {
                    val children = ast.value.mutableAsts.toList()
                    ast.value.mutableAsts.clear()
                    stack.addAll(children)
                }
                is Ast.Concat -> {
                    val children = ast.value.mutableAsts.toList()
                    ast.value.mutableAsts.clear()
                    stack.addAll(children)
                }
                else -> {}
            }
        }
    }

    public companion object {
        /**
         * Create an "empty" AST item.
         */
        public fun empty(span: Span): Ast = Ast.Empty(span)

        /**
         * Create a "flags" AST item.
         */
        public fun flags(e: SetFlags): Ast = Ast.Flags(e)

        /**
         * Create a "literal" AST item.
         */
        public fun literal(e: Literal): Ast = Ast.Literal(e)

        /**
         * Create a "dot" AST item.
         */
        public fun dot(span: Span): Ast = Ast.Dot(span)

        /**
         * Create a "assertion" AST item.
         */
        public fun assertion(e: Assertion): Ast = Ast.Assertion(e)

        /**
         * Create a "Unicode class" AST item.
         */
        public fun classUnicode(e: ClassUnicode): Ast = Ast.ClassUnicode(e)

        /**
         * Create a "Perl class" AST item.
         */
        public fun classPerl(e: ClassPerl): Ast = Ast.ClassPerl(e)

        /**
         * Create a "bracketed class" AST item.
         */
        public fun classBracketed(e: ClassBracketed): Ast = Ast.ClassBracketed(e)

        /**
         * Create a "repetition" AST item.
         */
        public fun repetition(e: Repetition): Ast = Ast.Repetition(e)

        /**
         * Create a "group" AST item.
         */
        public fun group(e: Group): Ast = Ast.Group(e)

        /**
         * Create a "alternation" AST item.
         */
        public fun alternation(e: Alternation): Ast = Ast.Alternation(e)

        /**
         * Create a "concat" AST item.
         */
        public fun concat(e: Concat): Ast = Ast.Concat(e)
    }
}

/**
 * The kind of a single literal expression.
 */
public sealed class LiteralKind {
    /**
     * The literal is written verbatim, e.g., `a` or `☃`.
     */
    public data object Verbatim : LiteralKind()
    /**
     * The literal is written as an escape because it is otherwise a special
     * regex meta character, e.g., `\*` or `\[`.
     */
    public data object Meta : LiteralKind()
    /**
     * The literal is written as an escape despite the fact that the escape is
     * unnecessary, e.g., `\%` or `\/`.
     */
    public data object Superfluous : LiteralKind()
    /**
     * The literal is written as an octal escape, e.g., `\141`.
     */
    public data object Octal : LiteralKind()
    /**
     * The literal is written as a hex code with a fixed number of digits
     * depending on the type of the escape, e.g., `\x61` or `\u0061` or
     * `\U00000061`.
     */
    public data class HexFixed(public var value: HexLiteralKind) : LiteralKind()
    /**
     * The literal is written as a hex code with a bracketed number of
     * digits. The only restriction is that the bracketed hex code must refer
     * to a valid Unicode scalar value.
     */
    public data class HexBrace(public var value: HexLiteralKind) : LiteralKind()
    /**
     * The literal is written as a specially recognized escape, e.g., `\f`
     * or `\n`.
     */
    public data class Special(public var value: SpecialLiteralKind) : LiteralKind()

    public fun rustEquals(other: LiteralKind): Boolean = this == other
}

/**
 * The type of a special literal.
 *
 * A special literal is a special escape sequence recognized by the regex
 * parser, e.g., `\f` or `\n`.
 */
public sealed class SpecialLiteralKind {
    /**
     * Bell, spelled `\a` (`\x07`).
     */
    public data object Bell : SpecialLiteralKind()
    /**
     * Form feed, spelled `\f` (`\x0C`).
     */
    public data object FormFeed : SpecialLiteralKind()
    /**
     * Tab, spelled `\t` (`\x09`).
     */
    public data object Tab : SpecialLiteralKind()
    /**
     * Line feed, spelled `\n` (`\x0A`).
     */
    public data object LineFeed : SpecialLiteralKind()
    /**
     * Carriage return, spelled `\r` (`\x0D`).
     */
    public data object CarriageReturn : SpecialLiteralKind()
    /**
     * Vertical tab, spelled `\v` (`\x0B`).
     */
    public data object VerticalTab : SpecialLiteralKind()
    /**
     * Space, spelled `\` (`\x20`). Note that this can only appear when
     * parsing in verbose mode.
     */
    public data object Space : SpecialLiteralKind()

    public fun rustEquals(other: SpecialLiteralKind): Boolean = this == other
}

/**
 * The type of a Unicode hex literal.
 *
 * Note that all variants behave the same when used with brackets. They only
 * differ when used without brackets in the number of hex digits that must
 * follow.
 */
public sealed class HexLiteralKind {
    /**
     * A `\x` prefix. When used without brackets, this form is limited to
     * two digits.
     */
    public data object X : HexLiteralKind()
    /**
     * A `\u` prefix. When used without brackets, this form is limited to
     * four digits.
     */
    public data object UnicodeShort : HexLiteralKind()
    /**
     * A `\U` prefix. When used without brackets, this form is limited to
     * eight digits.
     */
    public data object UnicodeLong : HexLiteralKind()

    public fun rustEquals(other: HexLiteralKind): Boolean = this == other

    /**
     * The number of digits that must be used with this literal form when
     * used without brackets. When used with brackets, there is no
     * restriction on the number of digits.
     */
    public open fun digits(): UInt =
        when (this) {
            HexLiteralKind.X -> 2u
            HexLiteralKind.UnicodeShort -> 4u
            HexLiteralKind.UnicodeLong -> 8u
        }
}

/**
 * The available Perl character classes.
 */
public sealed class ClassPerlKind {
    /**
     * Decimal numbers.
     */
    public data object Digit : ClassPerlKind()
    /**
     * Whitespace.
     */
    public data object Space : ClassPerlKind()
    /**
     * Word characters.
     */
    public data object Word : ClassPerlKind()

    public fun rustEquals(other: ClassPerlKind): Boolean = this == other
}

/**
 * The available ASCII character classes.
 */
public sealed class ClassAsciiKind {
    /**
     * `[0 - 9A - Za - z]`
     */
    public data object Alnum : ClassAsciiKind()
    /**
     * `[A - Za - z]`
     */
    public data object Alpha : ClassAsciiKind()
    /**
     * `[\x00-\x7F]`
     */
    public data object Ascii : ClassAsciiKind()
    /**
     * `[ \t]`
     */
    public data object Blank : ClassAsciiKind()
    /**
     * `[\x00-\x1F\x7F]`
     */
    public data object Cntrl : ClassAsciiKind()
    /**
     * `[0 - 9]`
     */
    public data object Digit : ClassAsciiKind()
    /**
     * `[! - ~]`
     */
    public data object Graph : ClassAsciiKind()
    /**
     * `[a - z]`
     */
    public data object Lower : ClassAsciiKind()
    /**
     * `[- ~]`
     */
    public data object Print : ClassAsciiKind()
    /**
     * [!-/:-@\[-`{-~]``
     */
    public data object Punct : ClassAsciiKind()
    /**
     * `[\t\n\v\f\r ]`
     */
    public data object Space : ClassAsciiKind()
    /**
     * `[A - Z]`
     */
    public data object Upper : ClassAsciiKind()
    /**
     * `[0 - 9A - Za - z_]`
     */
    public data object Word : ClassAsciiKind()
    /**
     * `[0 - 9A - Fa - f]`
     */
    public data object Xdigit : ClassAsciiKind()

    public fun rustEquals(other: ClassAsciiKind): Boolean = this == other

    public companion object {
        /**
         * Return the corresponding ClassAsciiKind variant for the given name.
         *
         * The name given should correspond to the lowercase version of the
         * variant name. e.g., `cntrl` is the name for `ClassAsciiKind.Cntrl`.
         *
         * If no variant with the corresponding name exists, then `None` is
         * returned.
         */
        public fun fromName(name: String): ClassAsciiKind? =
            when (name) {
                "alnum" -> Alnum
                "alpha" -> Alpha
                "ascii" -> Ascii
                "blank" -> Blank
                "cntrl" -> Cntrl
                "digit" -> Digit
                "graph" -> Graph
                "lower" -> Lower
                "print" -> Print
                "punct" -> Punct
                "space" -> Space
                "upper" -> Upper
                "word" -> Word
                "xdigit" -> Xdigit
                else -> null
            }
    }
}

/**
 * The available forms of Unicode character classes.
 */
public sealed class ClassUnicodeKind {
    /**
     * A one letter abbreviated class, e.g., `\pN`.
     */
    public data class OneLetter(public var value: UInt) : ClassUnicodeKind()
    /**
     * A binary property, general category or script. The string may be
     * empty.
     */
    public data class Named(public var value: String) : ClassUnicodeKind()
    /**
     * A property name and an associated value.
     */
    public data class NamedValue(public var op: ClassUnicodeOpKind, public var name: String, public var value: String) : ClassUnicodeKind()

    public fun rustEquals(other: ClassUnicodeKind): Boolean = this == other
}

/**
 * The type of op used in a Unicode character class.
 */
public sealed class ClassUnicodeOpKind {
    /**
     * A property set to a specific value, e.g., `\p{scx=Katakana}`.
     */
    public data object Equal : ClassUnicodeOpKind()
    /**
     * A property set to a specific value using a colon, e.g.,
     * `\p{scx:Katakana}`.
     */
    public data object Colon : ClassUnicodeOpKind()
    /**
     * A property that isn't a particular value, e.g., `\p{scx!=Katakana}`.
     */
    public data object NotEqual : ClassUnicodeOpKind()

    public fun rustEquals(other: ClassUnicodeOpKind): Boolean = this == other

    /**
     * Whether the op is an equality op or not.
     */
    public open fun isEqual(): Boolean =
        when (this) {
            ClassUnicodeOpKind.Equal, ClassUnicodeOpKind.Colon -> true
            else -> false
        }
}

/**
 * A character class set.
 *
 * This type corresponds to the internal structure of a bracketed character
 * class. That is, every bracketed character is one of two types: a union of
 * items (literals, ranges, other bracketed classes) or a tree of binary set
 * operations.
 */
public sealed class ClassSet {
    /**
     * An item, which can be a single literal, range, nested character class
     * or a union of items.
     */
    public data class Item(public var value: ClassSetItem) : ClassSet()
    /**
     * A single binary operation (i.e., &&, -- or ~~).
     */
    public data class BinaryOp(public var value: ClassSetBinaryOp) : ClassSet()

    public fun rustEquals(other: ClassSet): Boolean = this == other

    /**
     * Return the span of this character class set.
     */
    public open fun span(): Span =
        when (this) {
            is ClassSet.Item -> {
                val (x) = this
                x.span()
            }
            is ClassSet.BinaryOp -> {
                val (x1) = this
                x1.span
            }
        }

    /**
     * Return true if and only if this class set is empty.
     */
    public open fun isEmpty(): Boolean =
        when {
            this is ClassSet.Item && (this as ClassSet.Item).value is ClassSetItem.Empty -> true
            else -> false
        }

    /**
     * A custom `Drop` impl is used for `ClassSet` such that it uses constant
     * stack space but heap space proportional to the depth of the `ClassSet`.
     */
    public open fun drop() {
        when (this) {
            is ClassSet.Item -> {
                when (val item = this.value) {
                    is ClassSetItem.Empty,
                    is ClassSetItem.Literal,
                    is ClassSetItem.Range,
                    is ClassSetItem.Ascii,
                    is ClassSetItem.Unicode,
                    is ClassSetItem.Perl -> return
                    is ClassSetItem.Bracketed -> if (item.value.kind.isEmpty()) return
                    is ClassSetItem.Union -> if (item.value.items.isEmpty()) return
                }
            }
            is ClassSet.BinaryOp -> {
                if (this.value.lhs.isEmpty() && this.value.rhs.isEmpty()) return
            }
        }
        val emptySpan = Span.splat(Position.new(0, 0, 0))
        val emptySet: () -> ClassSet = { ClassSet.Item(ClassSetItem.Empty(emptySpan)) }
        val stack = mutableListOf<ClassSet>(this)
        while (stack.isNotEmpty()) {
            when (val set = stack.removeLast()) {
                is ClassSet.Item -> {
                    when (val item = set.value) {
                        is ClassSetItem.Bracketed -> {
                            val child = item.value.kind
                            item.value.kind = emptySet()
                            stack.add(child)
                        }
                        is ClassSetItem.Union -> {
                            val children = item.value.mutableItems.map { ClassSet.Item(it) }
                            item.value.mutableItems.clear()
                            stack.addAll(children)
                        }
                        else -> {}
                    }
                }
                is ClassSet.BinaryOp -> {
                    val lhs = set.value.lhs
                    val rhs = set.value.rhs
                    set.value.lhs = emptySet()
                    set.value.rhs = emptySet()
                    stack.add(lhs)
                    stack.add(rhs)
                }
            }
        }
    }

    public companion object {
        /**
         * Build a set from a union.
         */
        public fun union(ast: ClassSetUnion): ClassSet = ClassSet.Item(ClassSetItem.Union(ast))
    }
}

/**
 * A single component of a character class set.
 */
public sealed class ClassSetItem {
    /**
     * An empty item.
     *
     * Note that a bracketed character class cannot contain a single empty
     * item. Empty items can appear when using one of the binary operators.
     * For example, `[&&]` is the intersection of two empty classes.
     */
    public data class Empty(public var value: Span) : ClassSetItem()
    /**
     * A single literal.
     */
    public data class Literal(public var value: AstLiteral) : ClassSetItem()
    /**
     * A range between two literals.
     */
    public data class Range(public var value: ClassSetRange) : ClassSetItem()
    /**
     * An ASCII character class, e.g., `[: alnum:]` or `[: punct:]`.
     */
    public data class Ascii(public var value: ClassAscii) : ClassSetItem()
    /**
     * A Unicode character class, e.g., `\pL` or `\p{Greek}`.
     */
    public data class Unicode(public var value: ClassUnicode) : ClassSetItem()
    /**
     * A perl character class, e.g., `\d` or `\W`.
     */
    public data class Perl(public var value: ClassPerl) : ClassSetItem()
    /**
     * A bracketed character class set, which may contain zero or more
     * character ranges and/or zero or more nested classes. e.g.,
     * `[a-zA-Z\pL]`.
     */
    public data class Bracketed(public var value: ClassBracketed) : ClassSetItem()
    /**
     * A union of items.
     */
    public data class Union(public var value: ClassSetUnion) : ClassSetItem()

    public fun rustEquals(other: ClassSetItem): Boolean = this == other

    /**
     * Return the span of this character class set item.
     */
    public open fun span(): Span =
        when (this) {
            is ClassSetItem.Empty -> {
                val (span) = this
                span
            }
            is ClassSetItem.Literal -> {
                val (x) = this
                x.span
            }
            is ClassSetItem.Range -> {
                val (x1) = this
                x1.span
            }
            is ClassSetItem.Ascii -> {
                val (x2) = this
                x2.span
            }
            is ClassSetItem.Perl -> {
                val (x3) = this
                x3.span
            }
            is ClassSetItem.Unicode -> {
                val (x4) = this
                x4.span
            }
            is ClassSetItem.Bracketed -> {
                val (x5) = this
                x5.span
            }
            is ClassSetItem.Union -> {
                val (x6) = this
                x6.span
            }
        }
}

/**
 * The type of a Unicode character class set operation.
 *
 * Note that this doesn't explicitly represent union since there is no
 * explicit union operator. Concatenation inside a character class corresponds
 * to the union operation.
 */
public sealed class ClassSetBinaryOpKind {
    /**
     * The intersection of two sets, e.g., `\pN&&[a-z]`.
     */
    public data object Intersection : ClassSetBinaryOpKind()
    /**
     * The difference of two sets, e.g., `\pN--[0-9]`.
     */
    public data object Difference : ClassSetBinaryOpKind()
    /**
     * The symmetric difference of two sets. The symmetric difference is the
     * set of elements belonging to one but not both sets.
     * e.g., `[\pL~~[:ascii:]]`.
     */
    public data object SymmetricDifference : ClassSetBinaryOpKind()

    public fun rustEquals(other: ClassSetBinaryOpKind): Boolean = this == other
}

/**
 * An assertion kind.
 */
public sealed class AssertionKind {
    /**
     * `^`
     */
    public data object StartLine : AssertionKind()
    /**
     * `$`
     */
    public data object EndLine : AssertionKind()
    /**
     * `\A`
     */
    public data object StartText : AssertionKind()
    /**
     * `\z`
     */
    public data object EndText : AssertionKind()
    /**
     * `\b`
     */
    public data object WordBoundary : AssertionKind()
    /**
     * `\B`
     */
    public data object NotWordBoundary : AssertionKind()
    /**
     * `\b{start}`
     */
    public data object WordBoundaryStart : AssertionKind()
    /**
     * `\b{end}`
     */
    public data object WordBoundaryEnd : AssertionKind()
    /**
     * `\<` (alias for `\b{start}`)
     */
    public data object WordBoundaryStartAngle : AssertionKind()
    /**
     * `\>` (alias for `\b{end}`)
     */
    public data object WordBoundaryEndAngle : AssertionKind()
    /**
     * `\b{start-half}`
     */
    public data object WordBoundaryStartHalf : AssertionKind()
    /**
     * `\b{end-half}`
     */
    public data object WordBoundaryEndHalf : AssertionKind()

    public fun rustEquals(other: AssertionKind): Boolean = this == other
}

/**
 * The kind of a repetition operator.
 */
public sealed class RepetitionKind {
    /**
     * `?`
     */
    public data object ZeroOrOne : RepetitionKind()
    /**
     * `*`
     */
    public data object ZeroOrMore : RepetitionKind()
    /**
     * `+`
     */
    public data object OneOrMore : RepetitionKind()
    /**
     * `{ m, n }`
     */
    public data class Range(public var value: RepetitionRange) : RepetitionKind()

    public fun rustEquals(other: RepetitionKind): Boolean = this == other
}

/**
 * A range repetition operator.
 */
public sealed class RepetitionRange {
    /**
     * `{ m }`
     */
    public data class Exactly(public var value: UInt) : RepetitionRange()
    /**
     * `{ m, }`
     */
    public data class AtLeast(public var value: UInt) : RepetitionRange()
    /**
     * `{ m, n }`
     */
    public data class Bounded(public var field0: UInt, public var field1: UInt) : RepetitionRange()

    public fun rustEquals(other: RepetitionRange): Boolean = this == other

    /**
     * Returns true if and only if this repetition range is valid.
     *
     * The only case where a repetition range is invalid is if it is bounded
     * and its start is greater than its end.
     */
    public open fun isValid(): Boolean =
        when {
            this is RepetitionRange.Bounded && (this as RepetitionRange.Bounded).field0 > (this as RepetitionRange.Bounded).field1 -> {
                val (s, e) = this as RepetitionRange.Bounded
                false
            }
            else -> true
        }
}

/**
 * The kind of a group.
 */
public sealed class GroupKind {
    /**
     * `(a)`
     */
    public data class CaptureIndex(public var value: UInt) : GroupKind()
    /**
     * `(?<name> a)` or `(? P<name> a)`
     */
    public data class CaptureName(public var startsWithP: Boolean, public var name: AstCaptureName) : GroupKind()
    /**
     * `(?: a)` and `(? i: a)`
     */
    public data class NonCapturing(public var value: Flags) : GroupKind()

    public fun rustEquals(other: GroupKind): Boolean = this == other
}

/**
 * The kind of an item in a group of flags.
 */
public sealed class FlagsItemKind {
    /**
     * A negation operator applied to all subsequent flags in the enclosing
     * group.
     */
    public data object Negation : FlagsItemKind()
    /**
     * A single flag in a group.
     */
    public data class Flag(public var value: AstFlag) : FlagsItemKind()

    public fun rustEquals(other: FlagsItemKind): Boolean = this == other

    /**
     * Returns true if and only if this item is a negation operator.
     */
    public open fun isNegation(): Boolean =
        when (this) {
            FlagsItemKind.Negation -> true
            else -> false
        }
}

/**
 * A single flag.
 */
public sealed class Flag {
    /**
     * `i`
     */
    public data object CaseInsensitive : Flag()
    /**
     * `m`
     */
    public data object MultiLine : Flag()
    /**
     * `s`
     */
    public data object DotMatchesNewLine : Flag()
    /**
     * `U`
     */
    public data object SwapGreed : Flag()
    /**
     * `u`
     */
    public data object Unicode : Flag()
    /**
     * `R`
     */
    public data object Crlf : Flag()
    /**
     * `x`
     */
    public data object IgnoreWhitespace : Flag()

    public fun rustEquals(other: Flag): Boolean = this == other
}
