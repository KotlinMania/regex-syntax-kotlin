// port-lint: source src/ast/mod.rs
package io.github.kotlinmania.regexsyntax.ast

/*
 * Copyright (c) The rust-lang regex contributors.
 * Licensed under either of Apache-2.0 OR MIT.
 */

/*!
Defines an abstract syntax for regular expressions.
*/

import io.github.kotlinmania.regexsyntax.error.Formatter as ErrorFormatter

/**
 * An error that occurred while parsing a regular expression into an abstract
 * syntax tree.
 *
 * Note that not all ASTs represents a valid regular expression. For example,
 * an AST is constructed without error for `\p{Quux}`, but `Quux` is not a
 * valid Unicode property name. That particular error is reported when
 * translating an AST to the high-level intermediate representation
 * ([io.github.kotlinmania.regexsyntax.hir.Hir]).
 */
data class Error(
    /** The kind of error. */
    private val kindValue: ErrorKind,
    /**
     * The original pattern that the parser generated the error from. Every
     * span in an error is a valid range into this string.
     */
    private val patternValue: String,
    /** The span of this error. */
    private val spanValue: Span,
) {
    /** Return the type of this error. */
    fun kind(): ErrorKind = kindValue

    /**
     * The original pattern string in which this error occurred.
     *
     * Every span reported by this error is reported in terms of this string.
     */
    fun pattern(): String = patternValue

    /** Return the span at which this error occurred. */
    fun span(): Span = spanValue

    /**
     * Return an auxiliary span. This span exists only for some errors that
     * benefit from being able to point to two locations in the original
     * regular expression. For example, "duplicate" errors will have the
     * main error position set to the duplicate occurrence while its
     * auxiliary span will be set to the initial occurrence.
     */
    fun auxiliarySpan(): Span? = when (val k = kindValue) {
        is ErrorKind.FlagDuplicate -> k.original
        is ErrorKind.FlagRepeatedNegation -> k.original
        is ErrorKind.GroupNameDuplicate -> k.original
        else -> null
    }

    override fun toString(): String = ErrorFormatter.fromAstError(this).toString()
}

/**
 * The type of an error that occurred while building an AST.
 *
 * This error type is "non-exhaustive" upstream. Adding a new variant is not
 * considered a breaking change.
 */
sealed class ErrorKind {
    /**
     * The capturing group limit was exceeded.
     *
     * Note that this represents a limit on the total number of capturing
     * groups in a regex and not necessarily the number of nested capturing
     * groups. That is, the nest limit can be low and it is still possible for
     * this error to occur.
     */
    object CaptureLimitExceeded : ErrorKind()

    /** An invalid escape sequence was found in a character class set. */
    object ClassEscapeInvalid : ErrorKind()

    /**
     * An invalid character class range was found. An invalid range is any
     * range where the start is greater than the end.
     */
    object ClassRangeInvalid : ErrorKind()

    /**
     * An invalid range boundary was found in a character class. Range
     * boundaries must be a single literal codepoint, but this error indicates
     * that something else was found, such as a nested class.
     */
    object ClassRangeLiteral : ErrorKind()

    /** An opening `[` was found with no corresponding closing `]`. */
    object ClassUnclosed : ErrorKind()

    /**
     * Note that this error variant is no longer used. Namely, a decimal
     * number can only appear as a repetition quantifier. When the number
     * in a repetition quantifier is empty, then it gets its own specialized
     * error, [RepetitionCountDecimalEmpty].
     */
    object DecimalEmpty : ErrorKind()

    /** An invalid decimal number was given where one was expected. */
    object DecimalInvalid : ErrorKind()

    /** A bracketed hex literal was empty. */
    object EscapeHexEmpty : ErrorKind()

    /** A bracketed hex literal did not correspond to a Unicode scalar value. */
    object EscapeHexInvalid : ErrorKind()

    /** An invalid hexadecimal digit was found. */
    object EscapeHexInvalidDigit : ErrorKind()

    /** EOF was found before an escape sequence was completed. */
    object EscapeUnexpectedEof : ErrorKind()

    /** An unrecognized escape sequence. */
    object EscapeUnrecognized : ErrorKind()

    /** A dangling negation was used when setting flags, e.g., `i-`. */
    object FlagDanglingNegation : ErrorKind()

    /** A flag was used twice, e.g., `i-i`. */
    data class FlagDuplicate(
        /**
         * The position of the original flag. The error position
         * points to the duplicate flag.
         */
        val original: Span,
    ) : ErrorKind()

    /** The negation operator was used twice, e.g., `-i-s`. */
    data class FlagRepeatedNegation(
        /**
         * The position of the original negation operator. The error position
         * points to the duplicate negation operator.
         */
        val original: Span,
    ) : ErrorKind()

    /** Expected a flag but got EOF, e.g., `(?`. */
    object FlagUnexpectedEof : ErrorKind()

    /** Unrecognized flag, e.g., `a`. */
    object FlagUnrecognized : ErrorKind()

    /** A duplicate capture name was found. */
    data class GroupNameDuplicate(
        /**
         * The position of the initial occurrence of the capture name. The
         * error position itself points to the duplicate occurrence.
         */
        val original: Span,
    ) : ErrorKind()

    /** A capture group name is empty, e.g., `(?P<>abc)`. */
    object GroupNameEmpty : ErrorKind()

    /**
     * An invalid character was seen for a capture group name. This includes
     * errors where the first character is a digit (even though subsequent
     * characters are allowed to be digits).
     */
    object GroupNameInvalid : ErrorKind()

    /** A closing `>` could not be found for a capture group name. */
    object GroupNameUnexpectedEof : ErrorKind()

    /**
     * An unclosed group, e.g., `(ab`.
     *
     * The span of this error corresponds to the unclosed parenthesis.
     */
    object GroupUnclosed : ErrorKind()

    /** An unopened group, e.g., `ab)`. */
    object GroupUnopened : ErrorKind()

    /**
     * The nest limit was exceeded. The limit stored here is the limit
     * configured in the parser.
     */
    data class NestLimitExceeded(val limit: Int) : ErrorKind()

    /**
     * The range provided in a counted repetition operator is invalid. The
     * range is invalid if the start is greater than the end.
     */
    object RepetitionCountInvalid : ErrorKind()

    /**
     * An opening `{` was not followed by a valid decimal value.
     * For example, `x{}` or `x{]}` would fail.
     */
    object RepetitionCountDecimalEmpty : ErrorKind()

    /** An opening `{` was found with no corresponding closing `}`. */
    object RepetitionCountUnclosed : ErrorKind()

    /**
     * A repetition operator was applied to a missing sub-expression. This
     * occurs, for example, in the regex consisting of just a `*` or even
     * `(?i)*`. It is, however, possible to create a repetition operating on
     * an empty sub-expression. For example, `()*` is still considered valid.
     */
    object RepetitionMissing : ErrorKind()

    /**
     * The special word boundary syntax, `\b{something}`, was used, but
     * either EOF without `}` was seen, or an invalid character in the
     * braces was seen.
     */
    object SpecialWordBoundaryUnclosed : ErrorKind()

    /**
     * The special word boundary syntax, `\b{something}`, was used, but
     * `something` was not recognized as a valid word boundary kind.
     */
    object SpecialWordBoundaryUnrecognized : ErrorKind()

    /**
     * The syntax `\b{` was observed, but afterwards the end of the pattern
     * was observed without being able to tell whether it was meant to be a
     * bounded repetition on the `\b` or the beginning of a special word
     * boundary assertion.
     */
    object SpecialWordOrRepetitionUnexpectedEof : ErrorKind()

    /**
     * The Unicode class is not valid. This typically occurs when a `\p` is
     * followed by something other than a `{`.
     */
    object UnicodeClassInvalid : ErrorKind()

    /**
     * When octal support is disabled, this error is produced when an octal
     * escape is used. The octal escape is assumed to be an invocation of
     * a backreference, which is the common case.
     */
    object UnsupportedBackreference : ErrorKind()

    /**
     * When syntax similar to PCRE's look-around is used, this error is
     * returned. Some example syntaxes that are rejected include, but are
     * not necessarily limited to, `(?=re)`, `(?!re)`, `(?<=re)` and
     * `(?<!re)`. Note that all of these syntaxes are otherwise invalid; this
     * error is used to improve the user experience.
     */
    object UnsupportedLookAround : ErrorKind()

    override fun toString(): String = when (this) {
        CaptureLimitExceeded -> "exceeded the maximum number of " +
            "capturing groups (${UInt.MAX_VALUE})"
        ClassEscapeInvalid -> "invalid escape sequence found in character class"
        ClassRangeInvalid -> "invalid character class range, " +
            "the start must be <= the end"
        ClassRangeLiteral -> "invalid range boundary, must be a literal"
        ClassUnclosed -> "unclosed character class"
        DecimalEmpty -> "decimal literal empty"
        DecimalInvalid -> "decimal literal invalid"
        EscapeHexEmpty -> "hexadecimal literal empty"
        EscapeHexInvalid -> "hexadecimal literal is not a Unicode scalar value"
        EscapeHexInvalidDigit -> "invalid hexadecimal digit"
        EscapeUnexpectedEof -> "incomplete escape sequence, " +
            "reached end of pattern prematurely"
        EscapeUnrecognized -> "unrecognized escape sequence"
        FlagDanglingNegation -> "dangling flag negation operator"
        is FlagDuplicate -> "duplicate flag"
        is FlagRepeatedNegation -> "flag negation operator repeated"
        FlagUnexpectedEof -> "expected flag but got end of regex"
        FlagUnrecognized -> "unrecognized flag"
        is GroupNameDuplicate -> "duplicate capture group name"
        GroupNameEmpty -> "empty capture group name"
        GroupNameInvalid -> "invalid capture group character"
        GroupNameUnexpectedEof -> "unclosed capture group name"
        GroupUnclosed -> "unclosed group"
        GroupUnopened -> "unopened group"
        is NestLimitExceeded -> "exceed the maximum number of " +
            "nested parentheses/brackets ($limit)"
        RepetitionCountInvalid -> "invalid repetition count range, " +
            "the start must be <= the end"
        RepetitionCountDecimalEmpty -> "repetition quantifier expects a valid decimal"
        RepetitionCountUnclosed -> "unclosed counted repetition"
        RepetitionMissing -> "repetition operator missing expression"
        SpecialWordBoundaryUnclosed -> "special word boundary assertion is either " +
            "unclosed or contains an invalid character"
        SpecialWordBoundaryUnrecognized -> "unrecognized special word boundary assertion, " +
            "valid choices are: start, end, start-half " +
            "or end-half"
        SpecialWordOrRepetitionUnexpectedEof -> "found either the beginning of a special word " +
            "boundary or a bounded repetition on a \\b with " +
            "an opening brace, but no closing brace"
        UnicodeClassInvalid -> "invalid Unicode character class"
        UnsupportedBackreference -> "backreferences are not supported"
        UnsupportedLookAround -> "look-around, including look-ahead and look-behind, " +
            "is not supported"
    }
}

/**
 * Span represents the position information of a single AST item.
 *
 * All span positions are absolute byte offsets that can be used on the
 * original regular expression that was parsed.
 */
data class Span(
    /** The start byte offset. */
    val start: Position,
    /** The end byte offset. */
    val end: Position,
) : Comparable<Span> {
    override fun toString(): String = "Span($start, $end)"

    override fun compareTo(other: Span): Int {
        val c = start.compareTo(other.start)
        return if (c != 0) c else end.compareTo(other.end)
    }

    /** Create a new span by replacing the starting the position with the one given. */
    fun withStart(pos: Position): Span = Span(pos, end)

    /** Create a new span by replacing the ending the position with the one given. */
    fun withEnd(pos: Position): Span = Span(start, pos)

    /** Returns true if and only if this span occurs on a single line. */
    fun isOneLine(): Boolean = start.line == end.line

    /**
     * Returns true if and only if this span is empty. That is, it points to
     * a single position in the concrete syntax of a regular expression.
     */
    fun isEmpty(): Boolean = start.offset == end.offset

    companion object {
        /** Create a new span with the given positions. */
        fun new(start: Position, end: Position): Span = Span(start, end)

        /** Create a new span using the given position as the start and end. */
        fun splat(pos: Position): Span = Span(pos, pos)
    }
}

/**
 * A single position in a regular expression.
 *
 * A position encodes one half of a span, and include the byte offset, line
 * number and column number.
 */
data class Position(
    /**
     * The absolute offset of this position, starting at `0` from the
     * beginning of the regular expression pattern string.
     */
    val offset: Int,
    /** The line number, starting at `1`. */
    val line: Int,
    /** The approximate column number, starting at `1`. */
    val column: Int,
) : Comparable<Position> {
    override fun toString(): String = "Position(o: $offset, l: $line, c: $column)"

    override fun compareTo(other: Position): Int = offset.compareTo(other.offset)

    companion object {
        /**
         * Create a new position with the given information.
         *
         * `offset` is the absolute offset of the position, starting at `0` from
         * the beginning of the regular expression pattern string.
         *
         * `line` is the line number, starting at `1`.
         *
         * `column` is the approximate column number, starting at `1`.
         */
        fun new(offset: Int, line: Int, column: Int): Position = Position(offset, line, column)
    }
}

/**
 * An abstract syntax tree for a singular expression along with comments
 * found.
 *
 * Comments are not stored in the tree itself to avoid complexity. Each
 * comment contains a span of precisely where it occurred in the original
 * regular expression.
 */
data class WithComments(
    /** The actual ast. */
    val ast: Ast,
    /** All comments found in the original regular expression. */
    val comments: MutableList<Comment>,
)

/**
 * A comment from a regular expression with an associated span.
 *
 * A regular expression can only contain comments when the `x` flag is
 * enabled.
 */
data class Comment(
    /** The span of this comment, including the beginning `#` and ending `\n`. */
    val span: Span,
    /**
     * The comment text, starting with the first character following the `#`
     * and ending with the last character preceding the `\n`.
     */
    val comment: String,
)

/**
 * An abstract syntax tree for a single regular expression.
 *
 * An [Ast]'s [toString] implementation uses constant stack space and heap
 * space proportional to the size of the [Ast].
 */
sealed class Ast {
    /** An empty regex that matches everything. */
    data class Empty(val span: Span) : Ast()

    /** A set of flags, e.g., `(?is)`. */
    data class Flags(val value: SetFlags) : Ast()

    /** A single character literal, which includes escape sequences. */
    data class Literal(val value: io.github.kotlinmania.regexsyntax.ast.Literal) : Ast()

    /** The "any character" class. */
    data class Dot(val span: Span) : Ast()

    /** A single zero-width assertion. */
    data class Assertion(val value: io.github.kotlinmania.regexsyntax.ast.Assertion) : Ast()

    /** A single Unicode character class, e.g., `\pL` or `\p{Greek}`. */
    data class ClassUnicode(val value: io.github.kotlinmania.regexsyntax.ast.ClassUnicode) : Ast()

    /** A single perl character class, e.g., `\d` or `\W`. */
    data class ClassPerl(val value: io.github.kotlinmania.regexsyntax.ast.ClassPerl) : Ast()

    /**
     * A single bracketed character class set, which may contain zero or more
     * character ranges and/or zero or more nested classes. e.g.,
     * `[a-zA-Z\pL]`.
     */
    data class ClassBracketed(val value: io.github.kotlinmania.regexsyntax.ast.ClassBracketed) : Ast()

    /** A repetition operator applied to an arbitrary regular expression. */
    data class Repetition(val value: io.github.kotlinmania.regexsyntax.ast.Repetition) : Ast()

    /** A grouped regular expression. */
    data class Group(val value: io.github.kotlinmania.regexsyntax.ast.Group) : Ast()

    /** An alternation of regular expressions. */
    data class Alternation(val value: io.github.kotlinmania.regexsyntax.ast.Alternation) : Ast()

    /** A concatenation of regular expressions. */
    data class Concat(val value: io.github.kotlinmania.regexsyntax.ast.Concat) : Ast()

    /** Return the span of this abstract syntax tree. */
    fun span(): Span = when (this) {
        is Empty -> span
        is Flags -> value.span
        is Literal -> value.span
        is Dot -> span
        is Assertion -> value.span
        is ClassUnicode -> value.span
        is ClassPerl -> value.span
        is ClassBracketed -> value.span
        is Repetition -> value.span
        is Group -> value.span
        is Alternation -> value.span
        is Concat -> value.span
    }

    /** Return true if and only if this Ast is empty. */
    fun isEmpty(): Boolean = this is Empty

    /**
     * Returns true if and only if this AST has any (including possibly empty)
     * subexpressions.
     */
    internal fun hasSubexprs(): Boolean = when (this) {
        is Empty,
        is Flags,
        is Literal,
        is Dot,
        is Assertion,
        is ClassUnicode,
        is ClassPerl -> false
        is ClassBracketed,
        is Repetition,
        is Group,
        is Alternation,
        is Concat -> true
    }

    companion object {
        /** Create an "empty" AST item. */
        fun empty(span: Span): Ast = Empty(span)

        /** Create a "flags" AST item. */
        fun flags(e: SetFlags): Ast = Flags(e)

        /** Create a "literal" AST item. */
        fun literal(e: io.github.kotlinmania.regexsyntax.ast.Literal): Ast = Literal(e)

        /** Create a "dot" AST item. */
        fun dot(span: Span): Ast = Dot(span)

        /** Create a "assertion" AST item. */
        fun assertion(e: io.github.kotlinmania.regexsyntax.ast.Assertion): Ast = Assertion(e)

        /** Create a "Unicode class" AST item. */
        fun classUnicode(e: io.github.kotlinmania.regexsyntax.ast.ClassUnicode): Ast = ClassUnicode(e)

        /** Create a "Perl class" AST item. */
        fun classPerl(e: io.github.kotlinmania.regexsyntax.ast.ClassPerl): Ast = ClassPerl(e)

        /** Create a "bracketed class" AST item. */
        fun classBracketed(e: io.github.kotlinmania.regexsyntax.ast.ClassBracketed): Ast = ClassBracketed(e)

        /** Create a "repetition" AST item. */
        fun repetition(e: io.github.kotlinmania.regexsyntax.ast.Repetition): Ast = Repetition(e)

        /** Create a "group" AST item. */
        fun group(e: io.github.kotlinmania.regexsyntax.ast.Group): Ast = Group(e)

        /** Create a "alternation" AST item. */
        fun alternation(e: io.github.kotlinmania.regexsyntax.ast.Alternation): Ast = Alternation(e)

        /** Create a "concat" AST item. */
        fun concat(e: io.github.kotlinmania.regexsyntax.ast.Concat): Ast = Concat(e)
    }
}

/** An alternation of regular expressions. */
data class Alternation(
    /** The span of this alternation. */
    val span: Span,
    /** The alternate regular expressions. */
    val asts: MutableList<Ast>,
) {
    /**
     * Return this alternation as an AST.
     *
     * If this alternation contains zero ASTs, then [Ast.empty] is returned.
     * If this alternation contains exactly 1 AST, then the corresponding AST
     * is returned. Otherwise, [Ast.alternation] is returned.
     */
    fun intoAst(): Ast = when (asts.size) {
        0 -> Ast.empty(span)
        1 -> asts.removeAt(asts.size - 1)
        else -> Ast.alternation(this)
    }
}

/** A concatenation of regular expressions. */
data class Concat(
    /** The span of this concatenation. */
    val span: Span,
    /** The concatenation regular expressions. */
    val asts: MutableList<Ast>,
) {
    /**
     * Return this concatenation as an AST.
     *
     * If this alternation contains zero ASTs, then [Ast.empty] is returned.
     * If this alternation contains exactly 1 AST, then the corresponding AST
     * is returned. Otherwise, [Ast.concat] is returned.
     */
    fun intoAst(): Ast = when (asts.size) {
        0 -> Ast.empty(span)
        1 -> asts.removeAt(asts.size - 1)
        else -> Ast.concat(this)
    }
}

/**
 * A single literal expression.
 *
 * A literal corresponds to a single Unicode scalar value. Literals may be
 * represented in their literal form, e.g., `a` or in their escaped form,
 * e.g., `\x61`.
 */
data class Literal(
    /** The span of this literal. */
    val span: Span,
    /** The kind of this literal. */
    val kind: LiteralKind,
    /** The Unicode scalar value corresponding to this literal. */
    val c: Char,
) {
    /**
     * If this literal was written as a `\x` hex escape, then this returns
     * the corresponding byte value. Otherwise, this returns `null`.
     */
    fun byte(): UByte? = when (kind) {
        is LiteralKind.HexFixed -> if (kind.value == HexLiteralKind.X) {
            val code = c.code
            if (code in 0..0xFF) code.toUByte() else null
        } else null
        else -> null
    }
}

/** The kind of a single literal expression. */
sealed class LiteralKind {
    /** The literal is written verbatim, e.g., `a` or `☃`. */
    object Verbatim : LiteralKind()

    /**
     * The literal is written as an escape because it is otherwise a special
     * regex meta character, e.g., `\*` or `\[`.
     */
    object Meta : LiteralKind()

    /**
     * The literal is written as an escape despite the fact that the escape is
     * unnecessary, e.g., `\%` or `\/`.
     */
    object Superfluous : LiteralKind()

    /** The literal is written as an octal escape, e.g., `\141`. */
    object Octal : LiteralKind()

    /**
     * The literal is written as a hex code with a fixed number of digits
     * depending on the type of the escape, e.g., `\x61` or `a` or
     * `\U00000061`.
     */
    data class HexFixed(val value: HexLiteralKind) : LiteralKind()

    /**
     * The literal is written as a hex code with a bracketed number of
     * digits. The only restriction is that the bracketed hex code must refer
     * to a valid Unicode scalar value.
     */
    data class HexBrace(val value: HexLiteralKind) : LiteralKind()

    /**
     * The literal is written as a specially recognized escape, e.g., `\f`
     * or `\n`.
     */
    data class Special(val value: SpecialLiteralKind) : LiteralKind()
}

/**
 * The type of a special literal.
 *
 * A special literal is a special escape sequence recognized by the regex
 * parser, e.g., `\f` or `\n`.
 */
enum class SpecialLiteralKind {
    /** Bell, spelled `\a` (`\x07`). */
    Bell,

    /** Form feed, spelled `\f` (`\x0C`). */
    FormFeed,

    /** Tab, spelled `\t` (`\x09`). */
    Tab,

    /** Line feed, spelled `\n` (`\x0A`). */
    LineFeed,

    /** Carriage return, spelled `\r` (`\x0D`). */
    CarriageReturn,

    /** Vertical tab, spelled `\v` (`\x0B`). */
    VerticalTab,

    /**
     * Space, spelled `\ ` (`\x20`). Note that this can only appear when
     * parsing in verbose mode.
     */
    Space,
}

/**
 * The type of a Unicode hex literal.
 *
 * Note that all variants behave the same when used with brackets. They only
 * differ when used without brackets in the number of hex digits that must
 * follow.
 */
enum class HexLiteralKind {
    /**
     * A `\x` prefix. When used without brackets, this form is limited to
     * two digits.
     */
    X,

    /**
     * A `\u` prefix. When used without brackets, this form is limited to
     * four digits.
     */
    UnicodeShort,

    /**
     * A `\U` prefix. When used without brackets, this form is limited to
     * eight digits.
     */
    UnicodeLong;

    /**
     * The number of digits that must be used with this literal form when
     * used without brackets. When used with brackets, there is no
     * restriction on the number of digits.
     */
    fun digits(): Int = when (this) {
        X -> 2
        UnicodeShort -> 4
        UnicodeLong -> 8
    }
}

/** A Perl character class. */
data class ClassPerl(
    /** The span of this class. */
    val span: Span,
    /** The kind of Perl class. */
    val kind: ClassPerlKind,
    /**
     * Whether the class is negated or not. e.g., `\d` is not negated but
     * `\D` is.
     */
    val negated: Boolean,
)

/** The available Perl character classes. */
enum class ClassPerlKind {
    /** Decimal numbers. */
    Digit,

    /** Whitespace. */
    Space,

    /** Word characters. */
    Word,
}

/** An ASCII character class. */
data class ClassAscii(
    /** The span of this class. */
    val span: Span,
    /** The kind of ASCII class. */
    val kind: ClassAsciiKind,
    /**
     * Whether the class is negated or not. e.g., `[[:alpha:]]` is not negated
     * but `[[:^alpha:]]` is.
     */
    val negated: Boolean,
)

/** The available ASCII character classes. */
enum class ClassAsciiKind {
    /** `[0-9A-Za-z]` */
    Alnum,

    /** `[A-Za-z]` */
    Alpha,

    /** `[\x00-\x7F]` */
    Ascii,

    /** `[ \t]` */
    Blank,

    /** `[\x00-\x1F\x7F]` */
    Cntrl,

    /** `[0-9]` */
    Digit,

    /** `[!-~]` */
    Graph,

    /** `[a-z]` */
    Lower,

    /** `[ -~]` */
    Print,

    /** ``[!-/:-@\[-`{-~]`` */
    Punct,

    /** `[\t\n\v\f\r ]` */
    Space,

    /** `[A-Z]` */
    Upper,

    /** `[0-9A-Za-z_]` */
    Word,

    /** `[0-9A-Fa-f]` */
    Xdigit;

    companion object {
        /**
         * Return the corresponding ClassAsciiKind variant for the given name.
         *
         * The name given should correspond to the lowercase version of the
         * variant name. e.g., `cntrl` is the name for [Cntrl].
         *
         * If no variant with the corresponding name exists, then `null` is
         * returned.
         */
        fun fromName(name: String): ClassAsciiKind? = when (name) {
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

/** A Unicode character class. */
data class ClassUnicode(
    /** The span of this class. */
    val span: Span,
    /**
     * Whether this class is negated or not.
     *
     * Note: be careful when using this attribute. This specifically refers
     * to whether the class is written as `\p` or `\P`, where the latter
     * is `negated = true`. However, it also possible to write something like
     * `\P{scx!=Katakana}` which is actually equivalent to
     * `\p{scx=Katakana}` and is therefore not actually negated even though
     * `negated = true` here. To test whether this class is truly negated
     * or not, use the [isNegated] method.
     */
    val negated: Boolean,
    /** The kind of Unicode class. */
    val kind: ClassUnicodeKind,
) {
    /**
     * Returns true if this class has been negated.
     *
     * Note that this takes the Unicode op into account, if it's present.
     * e.g., [isNegated] for `\P{scx!=Katakana}` will return `false`.
     */
    fun isNegated(): Boolean = when (val k = kind) {
        is ClassUnicodeKind.NamedValue -> if (k.op == ClassUnicodeOpKind.NotEqual) !negated else negated
        else -> negated
    }
}

/** The available forms of Unicode character classes. */
sealed class ClassUnicodeKind {
    /** A one letter abbreviated class, e.g., `\pN`. */
    data class OneLetter(val value: Char) : ClassUnicodeKind()

    /**
     * A binary property, general category or script. The string may be
     * empty.
     */
    data class Named(val value: String) : ClassUnicodeKind()

    /** A property name and an associated value. */
    data class NamedValue(
        /** The type of Unicode op used to associate `name` with `value`. */
        val op: ClassUnicodeOpKind,
        /** The property name (which may be empty). */
        val name: String,
        /** The property value (which may be empty). */
        val value: String,
    ) : ClassUnicodeKind()
}

/** The type of op used in a Unicode character class. */
enum class ClassUnicodeOpKind {
    /** A property set to a specific value, e.g., `\p{scx=Katakana}`. */
    Equal,

    /**
     * A property set to a specific value using a colon, e.g.,
     * `\p{scx:Katakana}`.
     */
    Colon,

    /** A property that isn't a particular value, e.g., `\p{scx!=Katakana}`. */
    NotEqual;

    /** Whether the op is an equality op or not. */
    fun isEqual(): Boolean = when (this) {
        Equal, Colon -> true
        else -> false
    }
}

/** A bracketed character class, e.g., `[a-z0-9]`. */
data class ClassBracketed(
    /** The span of this class. */
    val span: Span,
    /**
     * Whether this class is negated or not. e.g., `[a]` is not negated but
     * `[^a]` is.
     */
    val negated: Boolean,
    /**
     * The type of this set. A set is either a normal union of things, e.g.,
     * `[abc]` or a result of applying set operations, e.g., `[\pL--c]`.
     */
    val kind: ClassSet,
)

/**
 * A character class set.
 *
 * This type corresponds to the internal structure of a bracketed character
 * class. That is, every bracketed character is one of two types: a union of
 * items (literals, ranges, other bracketed classes) or a tree of binary set
 * operations.
 */
sealed class ClassSet {
    /**
     * An item, which can be a single literal, range, nested character class
     * or a union of items.
     */
    data class Item(val value: ClassSetItem) : ClassSet()

    /** A single binary operation (i.e., &&, -- or ~~). */
    data class BinaryOp(val value: ClassSetBinaryOp) : ClassSet()

    /** Return the span of this character class set. */
    fun span(): Span = when (this) {
        is Item -> value.span()
        is BinaryOp -> value.span
    }

    /** Return true if and only if this class set is empty. */
    internal fun isEmpty(): Boolean = when (this) {
        is Item -> value is ClassSetItem.Empty
        else -> false
    }

    companion object {
        /** Build a set from a union. */
        fun union(ast: ClassSetUnion): ClassSet = Item(ClassSetItem.Union(ast))
    }
}

/** A single component of a character class set. */
sealed class ClassSetItem {
    /**
     * An empty item.
     *
     * Note that a bracketed character class cannot contain a single empty
     * item. Empty items can appear when using one of the binary operators.
     * For example, `[&&]` is the intersection of two empty classes.
     */
    data class Empty(val span: Span) : ClassSetItem()

    /** A single literal. */
    data class Literal(val value: io.github.kotlinmania.regexsyntax.ast.Literal) : ClassSetItem()

    /** A range between two literals. */
    data class Range(val value: ClassSetRange) : ClassSetItem()

    /** An ASCII character class, e.g., `[:alnum:]` or `[:punct:]`. */
    data class Ascii(val value: ClassAscii) : ClassSetItem()

    /** A Unicode character class, e.g., `\pL` or `\p{Greek}`. */
    data class Unicode(val value: ClassUnicode) : ClassSetItem()

    /** A perl character class, e.g., `\d` or `\W`. */
    data class Perl(val value: ClassPerl) : ClassSetItem()

    /**
     * A bracketed character class set, which may contain zero or more
     * character ranges and/or zero or more nested classes. e.g.,
     * `[a-zA-Z\pL]`.
     */
    data class Bracketed(val value: ClassBracketed) : ClassSetItem()

    /** A union of items. */
    data class Union(val value: ClassSetUnion) : ClassSetItem()

    /** Return the span of this character class set item. */
    fun span(): Span = when (this) {
        is Empty -> span
        is Literal -> value.span
        is Range -> value.span
        is Ascii -> value.span
        is Perl -> value.span
        is Unicode -> value.span
        is Bracketed -> value.span
        is Union -> value.span
    }
}

/** A single character class range in a set. */
data class ClassSetRange(
    /** The span of this range. */
    val span: Span,
    /** The start of this range. */
    val start: Literal,
    /** The end of this range. */
    val end: Literal,
) {
    /**
     * Returns true if and only if this character class range is valid.
     *
     * The only case where a range is invalid is if its start is greater than
     * its end.
     */
    fun isValid(): Boolean = start.c <= end.c
}

/** A union of items inside a character class set. */
data class ClassSetUnion(
    /**
     * The span of the items in this operation. e.g., the `a-z0-9` in
     * `[^a-z0-9]`
     */
    var span: Span,
    /** The sequence of items that make up this union. */
    val items: MutableList<ClassSetItem>,
) {
    /**
     * Push a new item in this union.
     *
     * The ending position of this union's span is updated to the ending
     * position of the span of the item given. If the union is empty, then
     * the starting position of this union is set to the starting position
     * of this item.
     *
     * In other words, if you only use this method to add items to a union
     * and you set the spans on each item correctly, then you should never
     * need to adjust the span of the union directly.
     */
    fun push(item: ClassSetItem) {
        if (items.isEmpty()) {
            span = span.copy(start = item.span().start)
        }
        span = span.copy(end = item.span().end)
        items.add(item)
    }

    /**
     * Return this union as a character class set item.
     *
     * If this union contains zero items, then an empty union is
     * returned. If this concatenation contains exactly 1 item, then the
     * corresponding item is returned. Otherwise, [ClassSetItem.Union] is
     * returned.
     */
    fun intoItem(): ClassSetItem = when (items.size) {
        0 -> ClassSetItem.Empty(span)
        1 -> items.removeAt(items.size - 1)
        else -> ClassSetItem.Union(this)
    }
}

/** A Unicode character class set operation. */
data class ClassSetBinaryOp(
    /** The span of this operation. e.g., the `a-z--[h-p]` in `[a-z--h-p]`. */
    val span: Span,
    /** The type of this set operation. */
    val kind: ClassSetBinaryOpKind,
    /** The left hand side of the operation. */
    val lhs: ClassSet,
    /** The right hand side of the operation. */
    val rhs: ClassSet,
)

/**
 * The type of a Unicode character class set operation.
 *
 * Note that this doesn't explicitly represent union since there is no
 * explicit union operator. Concatenation inside a character class corresponds
 * to the union operation.
 */
enum class ClassSetBinaryOpKind {
    /** The intersection of two sets, e.g., `\pN&&[a-z]`. */
    Intersection,

    /** The difference of two sets, e.g., `\pN--[0-9]`. */
    Difference,

    /**
     * The symmetric difference of two sets. The symmetric difference is the
     * set of elements belonging to one but not both sets.
     * e.g., `[\pL~~[:ascii:]]`.
     */
    SymmetricDifference,
}

/** A single zero-width assertion. */
data class Assertion(
    /** The span of this assertion. */
    val span: Span,
    /** The assertion kind, e.g., `\b` or `^`. */
    val kind: AssertionKind,
)

/** An assertion kind. */
enum class AssertionKind {
    /** `^` */
    StartLine,

    /** `$` */
    EndLine,

    /** `\A` */
    StartText,

    /** `\z` */
    EndText,

    /** `\b` */
    WordBoundary,

    /** `\B` */
    NotWordBoundary,

    /** `\b{start}` */
    WordBoundaryStart,

    /** `\b{end}` */
    WordBoundaryEnd,

    /** `\<` (alias for `\b{start}`) */
    WordBoundaryStartAngle,

    /** `\>` (alias for `\b{end}`) */
    WordBoundaryEndAngle,

    /** `\b{start-half}` */
    WordBoundaryStartHalf,

    /** `\b{end-half}` */
    WordBoundaryEndHalf,
}

/** A repetition operation applied to a regular expression. */
data class Repetition(
    /** The span of this operation. */
    val span: Span,
    /** The actual operation. */
    val op: RepetitionOp,
    /** Whether this operation was applied greedily or not. */
    val greedy: Boolean,
    /** The regular expression under repetition. */
    val ast: Ast,
)

/** The repetition operator itself. */
data class RepetitionOp(
    /**
     * The span of this operator. This includes things like `+`, `*?` and
     * `{m,n}`.
     */
    val span: Span,
    /** The type of operation. */
    val kind: RepetitionKind,
)

/** The kind of a repetition operator. */
sealed class RepetitionKind {
    /** `?` */
    object ZeroOrOne : RepetitionKind()

    /** `*` */
    object ZeroOrMore : RepetitionKind()

    /** `+` */
    object OneOrMore : RepetitionKind()

    /** `{m,n}` */
    data class Range(val value: RepetitionRange) : RepetitionKind()
}

/** A range repetition operator. */
sealed class RepetitionRange {
    /** `{m}` */
    data class Exactly(val value: Int) : RepetitionRange()

    /** `{m,}` */
    data class AtLeast(val value: Int) : RepetitionRange()

    /** `{m,n}` */
    data class Bounded(val start: Int, val end: Int) : RepetitionRange()

    /**
     * Returns true if and only if this repetition range is valid.
     *
     * The only case where a repetition range is invalid is if it is bounded
     * and its start is greater than its end.
     */
    fun isValid(): Boolean = when (this) {
        is Bounded -> start <= end
        else -> true
    }
}

/**
 * A grouped regular expression.
 *
 * This includes both capturing and non-capturing groups. This does **not**
 * include flag-only groups like `(?is)`, but does contain any group that
 * contains a sub-expression, e.g., `(a)`, `(?P<name>a)`, `(?:a)` and
 * `(?is:a)`.
 */
data class Group(
    /** The span of this group. */
    val span: Span,
    /** The kind of this group. */
    val kind: GroupKind,
    /** The regular expression in this group. */
    val ast: Ast,
) {
    /**
     * If this group is non-capturing, then this returns the (possibly empty)
     * set of flags. Otherwise, `null` is returned.
     */
    fun flags(): Flags? = when (kind) {
        is GroupKind.NonCapturing -> kind.value
        else -> null
    }

    /** Returns true if and only if this group is capturing. */
    fun isCapturing(): Boolean = when (kind) {
        is GroupKind.CaptureIndex, is GroupKind.CaptureName -> true
        is GroupKind.NonCapturing -> false
    }

    /**
     * Returns the capture index of this group, if this is a capturing group.
     *
     * This returns a capture index precisely when [isCapturing] is `true`.
     */
    fun captureIndex(): Int? = when (kind) {
        is GroupKind.CaptureIndex -> kind.value
        is GroupKind.CaptureName -> kind.name.index
        is GroupKind.NonCapturing -> null
    }
}

/** The kind of a group. */
sealed class GroupKind {
    /** `(a)` */
    data class CaptureIndex(val value: Int) : GroupKind()

    /** `(?<name>a)` or `(?P<name>a)` */
    data class CaptureName(
        /** True if the `?P<` syntax is used and false if the `?<` syntax is used. */
        val startsWithP: Boolean,
        /** The capture name. */
        val name: io.github.kotlinmania.regexsyntax.ast.CaptureName,
    ) : GroupKind()

    /** `(?:a)` and `(?i:a)` */
    data class NonCapturing(val value: Flags) : GroupKind()
}

/**
 * A capture name.
 *
 * This corresponds to the name itself between the angle brackets in, e.g.,
 * `(?P<foo>expr)`.
 */
data class CaptureName(
    /** The span of this capture name. */
    val span: Span,
    /** The capture name. */
    val name: String,
    /** The capture index. */
    val index: Int,
)

/** A group of flags that is not applied to a particular regular expression. */
data class SetFlags(
    /** The span of these flags, including the grouping parentheses. */
    val span: Span,
    /** The actual sequence of flags. */
    val flags: Flags,
)

/**
 * A group of flags.
 *
 * This corresponds only to the sequence of flags themselves, e.g., `is-u`.
 */
data class Flags(
    /** The span of this group of flags. */
    val span: Span,
    /**
     * A sequence of flag items. Each item is either a flag or a negation
     * operator.
     */
    val items: MutableList<FlagsItem>,
) {
    /**
     * Add the given item to this sequence of flags.
     *
     * If the item was added successfully, then `null` is returned. If the
     * given item is a duplicate, then `i` is returned, where
     * `items[i].kind == item.kind`.
     */
    fun addItem(item: FlagsItem): Int? {
        for ((i, x) in items.withIndex()) {
            if (x.kind == item.kind) {
                return i
            }
        }
        items.add(item)
        return null
    }

    /**
     * Returns the state of the given flag in this set.
     *
     * If the given flag is in the set but is negated, then `false` is
     * returned.
     *
     * If the given flag is in the set and is not negated, then `true`
     * is returned.
     *
     * Otherwise, `null` is returned.
     */
    fun flagState(flag: Flag): Boolean? {
        var negated = false
        for (x in items) {
            when (val kind = x.kind) {
                is FlagsItemKind.Negation -> {
                    negated = true
                }
                is FlagsItemKind.Flag -> if (kind.value == flag) {
                    return !negated
                }
            }
        }
        return null
    }
}

/** A single item in a group of flags. */
data class FlagsItem(
    /** The span of this item. */
    val span: Span,
    /** The kind of this item. */
    val kind: FlagsItemKind,
)

/** The kind of an item in a group of flags. */
sealed class FlagsItemKind {
    /**
     * A negation operator applied to all subsequent flags in the enclosing
     * group.
     */
    object Negation : FlagsItemKind()

    /** A single flag in a group. */
    data class Flag(val value: io.github.kotlinmania.regexsyntax.ast.Flag) : FlagsItemKind()

    /** Returns true if and only if this item is a negation operator. */
    fun isNegation(): Boolean = this is Negation
}

/** A single flag. */
enum class Flag {
    /** `i` */
    CaseInsensitive,

    /** `m` */
    MultiLine,

    /** `s` */
    DotMatchesNewLine,

    /** `U` */
    SwapGreed,

    /** `u` */
    Unicode,

    /** `R` */
    CRLF,

    /** `x` */
    IgnoreWhitespace,
}
