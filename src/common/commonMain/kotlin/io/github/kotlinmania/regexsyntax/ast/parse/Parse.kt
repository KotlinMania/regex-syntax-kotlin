// port-lint: source ast/parse.rs
package io.github.kotlinmania.regexsyntax.ast.parse

import io.github.kotlinmania.regexsyntax.ast.Alternation as AstAlternation
import io.github.kotlinmania.regexsyntax.ast.Assertion as AstAssertion
import io.github.kotlinmania.regexsyntax.ast.AssertionKind
import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.CaptureName
import io.github.kotlinmania.regexsyntax.ast.ClassAscii
import io.github.kotlinmania.regexsyntax.ast.ClassAsciiKind
import io.github.kotlinmania.regexsyntax.ast.ClassBracketed
import io.github.kotlinmania.regexsyntax.ast.ClassPerl
import io.github.kotlinmania.regexsyntax.ast.ClassPerlKind
import io.github.kotlinmania.regexsyntax.ast.ClassSet
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOp
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOpKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetItem
import io.github.kotlinmania.regexsyntax.ast.ClassSetRange
import io.github.kotlinmania.regexsyntax.ast.ClassSetUnion
import io.github.kotlinmania.regexsyntax.ast.ClassUnicode
import io.github.kotlinmania.regexsyntax.ast.ClassUnicodeKind
import io.github.kotlinmania.regexsyntax.ast.ClassUnicodeOpKind
import io.github.kotlinmania.regexsyntax.ast.Comment
import io.github.kotlinmania.regexsyntax.ast.Concat
import io.github.kotlinmania.regexsyntax.ast.Error
import io.github.kotlinmania.regexsyntax.ast.ErrorKind
import io.github.kotlinmania.regexsyntax.ast.Flag
import io.github.kotlinmania.regexsyntax.ast.Flags
import io.github.kotlinmania.regexsyntax.ast.FlagsItem
import io.github.kotlinmania.regexsyntax.ast.FlagsItemKind
import io.github.kotlinmania.regexsyntax.ast.Group as AstGroup
import io.github.kotlinmania.regexsyntax.ast.GroupKind
import io.github.kotlinmania.regexsyntax.ast.HexLiteralKind
import io.github.kotlinmania.regexsyntax.ast.Literal as AstLiteral
import io.github.kotlinmania.regexsyntax.ast.LiteralKind
import io.github.kotlinmania.regexsyntax.ast.Position
import io.github.kotlinmania.regexsyntax.ast.Repetition
import io.github.kotlinmania.regexsyntax.ast.RepetitionKind
import io.github.kotlinmania.regexsyntax.ast.RepetitionOp
import io.github.kotlinmania.regexsyntax.ast.RepetitionRange
import io.github.kotlinmania.regexsyntax.ast.SetFlags
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.SpecialLiteralKind
import io.github.kotlinmania.regexsyntax.ast.WithComments
import io.github.kotlinmania.regexsyntax.ast.visitor.Visitor
import io.github.kotlinmania.regexsyntax.ast.visitor.visit
import io.github.kotlinmania.regexsyntax.appendScalar
import io.github.kotlinmania.regexsyntax.decodeUtf8ScalarAt
import io.github.kotlinmania.regexsyntax.either.Either
import io.github.kotlinmania.regexsyntax.isEscapeableCharacter
import io.github.kotlinmania.regexsyntax.isMetaCharacter
import io.github.kotlinmania.regexsyntax.parseUIntRadix
import io.github.kotlinmania.regexsyntax.scalarFromU32
import io.github.kotlinmania.regexsyntax.scalarIsAlphabetic
import io.github.kotlinmania.regexsyntax.scalarIsAlphanumeric
import io.github.kotlinmania.regexsyntax.scalarIsAsciiDigit
import io.github.kotlinmania.regexsyntax.scalarIsAsciiHexDigit
import io.github.kotlinmania.regexsyntax.scalarIsWhitespace
import io.github.kotlinmania.regexsyntax.scalarLenUtf8
import io.github.kotlinmania.regexsyntax.unicodeScalars
import io.github.kotlinmania.regexsyntax.utf8ByteStartsWith
import io.github.kotlinmania.regexsyntax.withBorrow
import io.github.kotlinmania.regexsyntax.withBorrowMut
import io.github.kotlinmania.rustlang.core.cell.Cell
import io.github.kotlinmania.rustlang.core.cell.RefCell
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout
import io.github.kotlinmania.rustlang.core.result.Result

// This module provides a regular expression parser.

internal typealias ParseResult<T> = Result<T, Error>

private inline fun ch(c: Char): UInt = c.code.toUInt()

/**
 * A primitive is an expression with no sub-expressions. This includes
 * literals, assertions and non-set character classes. This representation
 * is used as intermediate state in the parser.
 *
 * This does not include ASCII character classes, since they can only appear
 * within a set character class.
 */
internal sealed class Primitive {
    internal data class Literal(internal var value: AstLiteral) : Primitive()
    internal data class Assertion(internal var value: AstAssertion) : Primitive()
    internal data class Dot(internal var value: Span) : Primitive()
    internal data class Perl(internal var value: ClassPerl) : Primitive()
    internal data class Unicode(internal var value: ClassUnicode) : Primitive()

    internal fun rustEquals(other: Primitive): Boolean = this == other

    /**
     * Return the span of this primitive.
     */
    internal fun span(): Span =
        when (this) {
            is Primitive.Literal -> this.value.span
            is Primitive.Assertion -> this.value.span
            is Primitive.Dot -> this.value
            is Primitive.Perl -> this.value.span
            is Primitive.Unicode -> this.value.span
        }

    /**
     * Convert this primitive into a proper AST.
     */
    internal fun intoAst(): Ast =
        when (this) {
            is Primitive.Literal -> Ast.literal(this.value)
            is Primitive.Assertion -> Ast.assertion(this.value)
            is Primitive.Dot -> Ast.dot(this.value)
            is Primitive.Perl -> Ast.classPerl(this.value)
            is Primitive.Unicode -> Ast.classUnicode(this.value)
        }

    /**
     * Convert this primitive into an item in a character class.
     *
     * If this primitive is not a legal item (i.e., an assertion or a dot),
     * then return an error.
     */
    internal fun intoClassSetItem(p: ParserI): ParseResult<ClassSetItem> =
        when (this) {
            is Primitive.Literal -> Result.Ok(ClassSetItem.Literal(this.value))
            is Primitive.Perl -> Result.Ok(ClassSetItem.Perl(this.value))
            is Primitive.Unicode -> Result.Ok(ClassSetItem.Unicode(this.value))
            else -> Result.Err(p.error(this.span(), ErrorKind.ClassEscapeInvalid))
        }

    /**
     * Convert this primitive into a literal in a character class. In
     * particular, literals are the only valid items that can appear in
     * ranges.
     *
     * If this primitive is not a legal item (i.e., a class, assertion or a
     * dot), then return an error.
     */
    internal fun intoClassLiteral(p: ParserI): ParseResult<AstLiteral> =
        when (this) {
            is Primitive.Literal -> Result.Ok(this.value)
            else -> Result.Err(p.error(this.span(), ErrorKind.ClassRangeLiteral))
        }
}

/**
 * Returns true if the given character is a hexadecimal digit.
 */
internal fun isHex(c: UInt): Boolean = scalarIsAsciiHexDigit(c)

/**
 * Returns true if the given character is a valid in a capture group name.
 *
 * If `first` is true, then `c` is treated as the first character in the
 * group name (which must be alphabetic or underscore).
 */
internal fun isCaptureChar(c: UInt, first: Boolean): Boolean =
    if (first) {
        c == ch('_') || scalarIsAlphabetic(c)
    } else {
        c == ch('_') || c == ch('.') || c == ch('[') || c == ch(']') || scalarIsAlphanumeric(c)
    }

/**
 * A builder for a regular expression parser.
 *
 * This builder permits modifying configuration options for the parser.
 */
public class ParserBuilder internal constructor(
    internal var ignoreWhitespace: Boolean,
    internal var nestLimit: UInt,
    internal var octal: Boolean,
    internal var emptyMinRange: Boolean,
) {
    /**
     * Build a parser from this configuration with the given pattern.
     */
    public fun build(): Parser = Parser(
        posCtorArg = Cell.new(Position(offset = 0, line = 1, column = 1)),
        captureIndexCtorArg = Cell.new(0u),
        nestLimit = this.nestLimit,
        octal = this.octal,
        emptyMinRange = this.emptyMinRange,
        initialIgnoreWhitespace = this.ignoreWhitespace,
        ignoreWhitespaceCtorArg = Cell.new(this.ignoreWhitespace),
        commentsCtorArg = RefCell.new(mutableListOf()),
        stackGroupCtorArg = RefCell.new(mutableListOf()),
        stackClassCtorArg = RefCell.new(mutableListOf()),
        captureNamesCtorArg = RefCell.new(mutableListOf()),
        scratchCtorArg = RefCell.new(StringBuilder()),
    )

    /**
     * Set the nesting limit for this parser.
     *
     * The nesting limit controls how deep the abstract syntax tree is allowed
     * to be. If the AST exceeds the given limit (e.g., with too many nested
     * groups), then an error is returned by the parser.
     *
     * The purpose of this limit is to act as a heuristic to prevent stack
     * overflow for consumers that do structural induction on an `Ast` using
     * explicit recursion. While this crate never does this (instead using
     * constant stack space and moving the call stack to the heap), other
     * crates may.
     *
     * This limit is not checked until the entire AST is parsed. Therefore,
     * if callers want to put a limit on the amount of heap space used, then
     * they should impose a limit on the length, in bytes, of the concrete
     * pattern string. In particular, this is viable since this parser
     * implementation will limit itself to heap space proportional to the
     * length of the pattern string.
     *
     * Note that a nest limit of `0` will return a nest limit error for most
     * patterns but not all. For example, a nest limit of `0` permits `a` but
     * not `ab`, since `ab` requires a concatenation, which results in a nest
     * depth of `1`. In general, a nest limit is not something that manifests
     * in an obvious way in the concrete syntax, therefore, it should not be
     * used in a granular way.
     */
    public fun nestLimit(limit: UInt): ParserBuilder {
        this.nestLimit = limit
        return this
    }

    /**
     * Whether to support octal syntax or not.
     *
     * Octal syntax is a little-known way of uttering Unicode codepoints in
     * a regular expression. For example, `a`, `\x61`, `\u0061` and
     * `\141` are all equivalent regular expressions, where the last example
     * shows octal syntax.
     *
     * While supporting octal syntax isn't in and of itself a problem, it does
     * make good error messages harder. That is, in PCRE based regex engines,
     * syntax like `\0` invokes a backreference, which is explicitly
     * unsupported in Rust's regex engine. However, many users expect it to
     * be supported. Therefore, when octal support is disabled, the error
     * message will explicitly mention that backreferences aren't supported.
     *
     * Octal syntax is disabled by default.
     */
    public fun octal(yes: Boolean): ParserBuilder {
        this.octal = yes
        return this
    }

    /**
     * Enable verbose mode in the regular expression.
     *
     * When enabled, verbose mode permits insignificant whitespace in many
     * places in the regular expression, as well as comments. Comments are
     * started using `#` and continue until the end of the line.
     *
     * By default, this is disabled. It may be selectively enabled in the
     * regular expression by using the `x` flag regardless of this setting.
     */
    public fun ignoreWhitespace(yes: Boolean): ParserBuilder {
        this.ignoreWhitespace = yes
        return this
    }

    /**
     * Allow using `{ , n }` as an equivalent to `{ 0, n }`.
     *
     * When enabled, the parser accepts `{ , n }` as valid syntax for `{ 0, n }`.
     * Most regular expression engines don't support the `{ , n }` syntax, but
     * some others do it, namely Python's `re` library.
     *
     * This is disabled by default.
     */
    public fun emptyMinRange(yes: Boolean): ParserBuilder {
        this.emptyMinRange = yes
        return this
    }

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<ParserBuilder> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<ParserBuilder> {
                override val alignBytes: Long = 4L
                override val sizeBytes: Long = 12L
                override fun readNative(addr: Long): ParserBuilder =
                    ParserBuilder(
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.BOOL).readNative(addr),
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.U32).readNative(addr + 4L),
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.BOOL).readNative(addr + 8L),
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.BOOL).readNative(addr + 9L),
                    )
                override fun writeNative(addr: Long, value: ParserBuilder) {
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.BOOL).writeNative(addr, value.ignoreWhitespace)
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.U32).writeNative(addr + 4L, value.nestLimit)
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.BOOL).writeNative(addr + 8L, value.octal)
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.BOOL).writeNative(addr + 9L, value.emptyMinRange)
                }
            }

        public fun default(): ParserBuilder = ParserBuilder.new()

        /**
         * Create a new parser builder with a default configuration.
         */
        public fun new(): ParserBuilder = ParserBuilder(ignoreWhitespace = false, nestLimit = 250u, octal = false, emptyMinRange = false)
    }
}

/**
 * A regular expression parser.
 *
 * This parses a string representation of a regular expression into an
 * abstract syntax tree. The size of the tree is proportional to the length
 * of the regular expression pattern.
 *
 * A `Parser` can be configured in more detail via a [ParserBuilder].
 */
public class Parser internal constructor(
    posCtorArg: Cell<Position>,
    captureIndexCtorArg: Cell<UInt>,
    /**
     * The maximum number of open parens/brackets allowed. If the parser
     * exceeds this number, then an error is returned.
     */
    internal var nestLimit: UInt,
    /**
     * Whether to support octal syntax or not. When `false`, the parser will
     * return an error helpfully pointing out that backreferences are not
     * supported.
     */
    internal var octal: Boolean,
    /**
     * The initial setting for `ignoreWhitespace` as provided by
     * `ParserBuilder`. It is used when resetting the parser's state.
     */
    internal var initialIgnoreWhitespace: Boolean,
    /**
     * Whether the parser supports `{ , n }` repetitions as an equivalent to
     * `{ 0, n }.`
     */
    internal var emptyMinRange: Boolean,
    ignoreWhitespaceCtorArg: Cell<Boolean>,
    commentsCtorArg: RefCell<MutableList<Comment>>,
    stackGroupCtorArg: RefCell<MutableList<GroupState>>,
    stackClassCtorArg: RefCell<MutableList<ClassState>>,
    captureNamesCtorArg: RefCell<MutableList<CaptureName>>,
    scratchCtorArg: RefCell<StringBuilder>,
) {
    /**
     * The current position of the parser.
     */
    internal var pos: Cell<Position> = posCtorArg

    /**
     * The current capture index.
     */
    internal var captureIndex: Cell<UInt> = captureIndexCtorArg

    /**
     * Whether whitespace should be ignored. When enabled, comments are
     * also permitted.
     */
    internal var ignoreWhitespace: Cell<Boolean> = ignoreWhitespaceCtorArg

    /**
     * A list of comments, in order of appearance.
     */
    internal var comments: RefCell<MutableList<Comment>> = commentsCtorArg

    /**
     * A stack of grouped sub-expressions, including alternations.
     */
    internal var stackGroup: RefCell<MutableList<GroupState>> = stackGroupCtorArg

    /**
     * A stack of nested character classes. This is only non-empty when
     * parsing a class.
     */
    internal var stackClass: RefCell<MutableList<ClassState>> = stackClassCtorArg

    /**
     * A sorted sequence of capture names. This is used to detect duplicate
     * capture names and report an error if one is detected.
     */
    internal var captureNames: RefCell<MutableList<CaptureName>> = captureNamesCtorArg

    /**
     * A scratch buffer used in various places. Mostly this is used to
     * accumulate relevant characters from parts of a pattern.
     */
    internal var scratch: RefCell<StringBuilder> = scratchCtorArg

    /**
     * Parse the regular expression into an abstract syntax tree.
     */
    public fun parse(pattern: String): Result<Ast, Error> = ParserI.new(this, pattern).parse()

    /**
     * Parse the regular expression and return an abstract syntax tree with
     * all of the comments found in the pattern.
     */
    public fun parseWithComments(pattern: String): Result<WithComments, Error> =
        ParserI.new(this, pattern).parseWithComments()

    /**
     * Reset the internal state of a parser.
     *
     * This is called at the beginning of every parse. This prevents the
     * parser from running with inconsistent state (say, if a previous
     * invocation returned an error and the parser is reused).
     */
    internal fun reset() {
        // These settings should be in line with the construction
        // in `ParserBuilder.build`.
        this.pos.set(Position(offset = 0, line = 1, column = 1))
        this.ignoreWhitespace.set(this.initialIgnoreWhitespace)
        this.comments.withBorrowMut { it.value.clear() }
        this.stackGroup.withBorrowMut { it.value.clear() }
        this.stackClass.withBorrowMut { it.value.clear() }
    }

    public companion object {
        /**
         * Create a new parser with a default configuration.
         *
         * The parser can be run with either the `parse` or `parseWithComments`
         * methods. The parse methods return an abstract syntax tree.
         *
         * To set configuration options on the parser, use [ParserBuilder].
         */
        public fun new(): Parser = ParserBuilder.new().build()
    }
}

/**
 * ParserI is the internal parser implementation.
 *
 * We use this separate type so that we can carry the provided pattern string
 * along with us. In particular, a `Parser` internal state is not tied to any
 * one pattern, but `ParserI` is.
 */
internal class ParserI(
    /**
     * The parser state/configuration.
     */
    internal var parserRef: Parser,
    /**
     * The full regular expression provided by the user.
     */
    internal var patternStr: String,
) {
    internal val utf8Bytes: ByteArray = this.patternStr.encodeToByteArray()

    /**
     * Return a reference to the parser state.
     */
    internal fun parser(): Parser = this.parserRef

    /**
     * Return a reference to the pattern being parsed.
     */
    internal fun pattern(): String = this.patternStr

    /**
     * Create a new error with the given span and error type.
     */
    internal fun error(span: Span, kind: ErrorKind): Error =
        Error(kind = kind, pattern = this.pattern(), span = span)

    /**
     * Return the current offset of the parser.
     *
     * The offset starts at `0` from the beginning of the regular expression
     * pattern string.
     */
    internal fun offset(): Int = this.parser().pos.get().offset

    /**
     * Return the current line number of the parser.
     *
     * The line number starts at `1`.
     */
    internal fun line(): Int = this.parser().pos.get().line

    /**
     * Return the current column of the parser.
     *
     * The column number starts at `1` and is reset whenever a `\n` is seen.
     */
    internal fun column(): Int = this.parser().pos.get().column

    /**
     * Return the next capturing index. Each subsequent call increments the
     * internal index.
     *
     * The span given should correspond to the location of the opening
     * parenthesis.
     *
     * If the capture limit is exceeded, then an error is returned.
     */
    internal fun nextCaptureIndex(span: Span): ParseResult<UInt> {
        val current = this.parser().captureIndex.get()
        if (current == UInt.MAX_VALUE) {
            return Result.Err(this.error(span, ErrorKind.CaptureLimitExceeded))
        }
        val i = current + 1u
        this.parser().captureIndex.set(i)
        return Result.Ok(i)
    }

    /**
     * Adds the given capture name to this parser. If this capture name has
     * already been used, then an error is returned.
     */
    internal fun addCaptureName(cap: CaptureName): ParseResult<Unit> =
        this.parser().captureNames.withBorrowMut { guard ->
            val names = guard.value
            val idx = names.binarySearch { it.name.compareTo(cap.name) }
            if (idx < 0) {
                val ins = -idx - 1
                names.add(ins, cap.copy())
                Result.Ok(Unit)
            } else {
                Result.Err(this.error(cap.span, ErrorKind.GroupNameDuplicate(original = names[idx].span)))
            }
        }

    /**
     * Return whether the parser should ignore whitespace or not.
     */
    internal fun ignoreWhitespace(): Boolean = this.parser().ignoreWhitespace.get()

    /**
     * Return the character at the current position of the parser.
     *
     * This panics if the current position does not point to a valid char.
     */
    internal fun char(): UInt = this.charAt(this.offset())

    /**
     * Return the character at the given position.
     *
     * This panics if the given position does not point to a valid char.
     */
    internal fun charAt(i: Int): UInt {
        if (i < 0 || i >= this.utf8Bytes.size) {
            error("expected char at offset $i")
        }
        return decodeUtf8ScalarAt(this.utf8Bytes, i)
    }

    /**
     * Bump the parser to the next Unicode scalar value.
     *
     * If the end of the input has been reached, then `false` is returned.
     */
    internal fun bump(): Boolean {
        if (this.isEof()) {
            return false
        }
        val structVal = this.pos()
        var line = structVal.line
        var column = structVal.column
        val current = this.char()
        if (current == ch('\n')) {
            line += 1
            column = 1
        } else {
            column += 1
        }
        val offset = structVal.offset + scalarLenUtf8(current)
        this.parser().pos.set(Position(offset = offset, line = line, column = column))
        return offset < this.utf8Bytes.size
    }

    /**
     * If the substring starting at the current position of the parser has
     * the given prefix, then bump the parser to the character immediately
     * following the prefix and return true. Otherwise, don't bump the parser
     * and return false.
     */
    internal fun bumpIf(prefix: String): Boolean =
        if (utf8ByteStartsWith(this.utf8Bytes, this.offset(), prefix)) {
            for (unused in prefix.unicodeScalars()) {
                this.bump()
            }
            true
        } else {
            false
        }

    /**
     * Returns true if and only if the parser is positioned at a look-around
     * prefix. The conditions under which this returns true must always
     * correspond to a regular expression that would otherwise be consider
     * invalid.
     *
     * This should only be called immediately after parsing the opening of
     * a group or a set of flags.
     */
    internal fun isLookaroundPrefix(): Boolean =
        this.bumpIf("?=") || this.bumpIf("?!") || this.bumpIf("?<=") || this.bumpIf("?<!")

    /**
     * Bump the parser, and if the `x` flag is enabled, bump through any
     * subsequent spaces. Return true if and only if the parser is not at
     * EOF.
     */
    internal fun bumpAndBumpSpace(): Boolean {
        if (!this.bump()) {
            return false
        }
        this.bumpSpace()
        return !this.isEof()
    }

    /**
     * If the `x` flag is enabled (i.e., whitespace insensitivity with
     * comments), then this will advance the parser through all whitespace
     * and comments to the next non-whitespace non-comment byte.
     *
     * If the `x` flag is disabled, then this is a no-op.
     *
     * This should be used selectively throughout the parser where
     * arbitrary whitespace is permitted when the `x` flag is enabled. For
     * example, `{ 5, 6 }` is equivalent to `{ 5, 6 }`.
     */
    internal fun bumpSpace() {
        if (!this.ignoreWhitespace()) {
            return
        }
        while (!this.isEof()) {
            val cur = this.char()
            if (scalarIsWhitespace(cur)) {
                this.bump()
            } else if (cur == ch('#')) {
                val start = this.pos()
                val commentText = StringBuilder()
                this.bump()
                while (!this.isEof()) {
                    val c = this.char()
                    this.bump()
                    if (c == ch('\n')) {
                        break
                    }
                    commentText.appendScalar(c)
                }
                val comment = Comment(span = Span.new(start, this.pos()), comment = commentText.toString())
                this.parser().comments.withBorrowMut { it.value.add(comment) }
            } else {
                break
            }
        }
    }

    /**
     * Peek at the next character in the input without advancing the parser.
     *
     * If the input has been exhausted, then this returns `None`.
     */
    internal fun peek(): UInt? {
        if (this.isEof()) {
            return null
        }
        val nextOffset = this.offset() + scalarLenUtf8(this.char())
        return if (nextOffset >= this.utf8Bytes.size) null else decodeUtf8ScalarAt(this.utf8Bytes, nextOffset)
    }

    /**
     * Like peek, but will ignore spaces when the parser is in whitespace
     * insensitive mode.
     */
    internal fun peekSpace(): UInt? {
        if (!this.ignoreWhitespace()) {
            return this.peek()
        }
        if (this.isEof()) {
            return null
        }
        val startOffset = this.offset() + scalarLenUtf8(this.char())
        var inComment = false
        var idx = startOffset
        while (idx < this.utf8Bytes.size) {
            val c = decodeUtf8ScalarAt(this.utf8Bytes, idx)
            val step = scalarLenUtf8(c)
            when {
                inComment -> {
                    if (c == ch('\n')) {
                        inComment = false
                    }
                    idx += step
                }
                scalarIsWhitespace(c) -> idx += step
                c == ch('#') -> {
                    inComment = true
                    idx += step
                }
                else -> return c
            }
        }
        return null
    }

    /**
     * Returns true if the next call to `bump` would return false.
     */
    internal fun isEof(): Boolean = this.offset() == this.utf8Bytes.size

    /**
     * Return the current position of the parser, which includes the offset,
     * line and column.
     */
    internal fun pos(): Position = this.parser().pos.get()

    /**
     * Create a span at the current position of the parser. Both the start
     * and end of the span are set.
     */
    internal fun span(): Span = Span.splat(this.pos())

    /**
     * Create a span that covers the current character.
     */
    internal fun spanChar(): Span {
        val cur = this.pos()
        val c = this.char()
        val nextOffset = cur.offset + scalarLenUtf8(c)
        val next = if (c == ch('\n')) {
            Position(offset = nextOffset, line = cur.line + 1, column = 1)
        } else {
            Position(offset = nextOffset, line = cur.line, column = cur.column + 1)
        }
        return Span.new(cur, next)
    }

    /**
     * Parse and push a single alternation on to the parser's internal stack.
     */
    internal fun pushAlternate(concat: Concat): ParseResult<Concat> {
        check(this.char() == ch('|'))
        concat.span.end = this.pos()
        this.pushOrAddAlternation(concat)
        this.bump()
        return Result.Ok(Concat(span = this.span(), asts = emptyList()))
    }

    /**
     * Pushes or adds the given branch of an alternation to the parser's
     * internal stack of state.
     */
    internal fun pushOrAddAlternation(concat: Concat) {
        this.parser().stackGroup.withBorrowMut { guard ->
            val stack = guard.value
            val top = stack.lastOrNull()
            if (top is GroupState.Alternation) {
                top.value.mutableAsts.add(concat.intoAst())
                return
            }
            stack.add(
                GroupState.Alternation(
                    AstAlternation(
                        span = Span.new(concat.span.start, this.pos()),
                        asts = listOf(concat.intoAst()),
                    )
                )
            )
        }
    }

    /**
     * Parse and push a group AST (and its parent concatenation) on to the
     * parser's internal stack.
     */
    internal fun pushGroup(concat: Concat): ParseResult<Concat> {
        check(this.char() == ch('('))
        return when (val parsed = this.parseGroup()) {
            is Result.Err -> parsed
            is Result.Ok -> when (val matchVal = parsed.value) {
                is Either.Left -> {
                    val set = matchVal.value
                    val ignore = set.flags.flagState(Flag.IgnoreWhitespace)
                    if (ignore != null) {
                        this.parser().ignoreWhitespace.set(ignore)
                    }
                    concat.mutableAsts.add(Ast.flags(set))
                    Result.Ok(concat)
                }
                is Either.Right -> {
                    val group = matchVal.value
                    val oldIgnoreWhitespace = this.ignoreWhitespace()
                    val newIgnoreWhitespace = group.flags()?.flagState(Flag.IgnoreWhitespace) ?: oldIgnoreWhitespace
                    this.parser().stackGroup.withBorrowMut { guard ->
                        guard.value.add(
                            GroupState.Group(
                                concat = concat,
                                group = group,
                                ignoreWhitespace = oldIgnoreWhitespace,
                            )
                        )
                    }
                    this.parser().ignoreWhitespace.set(newIgnoreWhitespace)
                    Result.Ok(Concat(span = this.span(), asts = emptyList()))
                }
            }
        }
    }

    /**
     * Pop a group AST from the parser's internal stack and set the group's
     * AST to the given concatenation.
     */
    internal fun popGroup(groupConcat: Concat): ParseResult<Concat> {
        check(this.char() == ch(')'))
        var priorConcat: Concat? = null
        var group2: AstGroup? = null
        var ignoreWhitespace2 = false
        var alt1: AstAlternation? = null
        var errorResult: ParseResult<Concat>? = null
        this.parser().stackGroup.withBorrowMut { guard ->
            val stack = guard.value
            when (val top = stack.removeLastOrNull()) {
                is GroupState.Group -> {
                    priorConcat = top.concat
                    group2 = top.group
                    ignoreWhitespace2 = top.ignoreWhitespace
                }
                is GroupState.Alternation -> {
                    alt1 = top.value
                    when (val prev = stack.removeLastOrNull()) {
                        is GroupState.Group -> {
                            priorConcat = prev.concat
                            group2 = prev.group
                            ignoreWhitespace2 = prev.ignoreWhitespace
                        }
                        null, is GroupState.Alternation -> {
                            errorResult = Result.Err(this.error(this.spanChar(), ErrorKind.GroupUnopened))
                        }
                    }
                }
                null -> {
                    errorResult = Result.Err(this.error(this.spanChar(), ErrorKind.GroupUnopened))
                }
            }
        }
        errorResult?.let { return it }
        val prior = priorConcat!!
        val group = group2!!
        this.parser().ignoreWhitespace.set(ignoreWhitespace2)
        groupConcat.span.end = this.pos()
        this.bump()
        group.span.end = this.pos()
        val alt = alt1
        if (alt == null) {
            group.ast = groupConcat.intoAst()
        } else {
            alt.span.end = groupConcat.span.end
            alt.mutableAsts.add(groupConcat.intoAst())
            group.ast = alt.intoAst()
        }
        prior.mutableAsts.add(Ast.group(group))
        return Result.Ok(prior)
    }

    /**
     * Pop the last state from the parser's internal stack, if it exists, and
     * add the given concatenation to it.
     */
    internal fun popGroupEnd(concat: Concat): ParseResult<Ast> {
        concat.span.end = this.pos()
        var firstResult: ParseResult<Ast>? = null
        var secondResult: ParseResult<Ast>? = null
        this.parser().stackGroup.withBorrowMut { guard ->
            val stack = guard.value
            firstResult = when (val top = stack.removeLastOrNull()) {
                null -> Result.Ok(concat.intoAst())
                is GroupState.Alternation -> {
                    val alt = top.value
                    alt.span.end = this.pos()
                    alt.mutableAsts.add(concat.intoAst())
                    Result.Ok(Ast.alternation(alt))
                }
                is GroupState.Group -> {
                    Result.Err(this.error(top.group.span, ErrorKind.GroupUnclosed))
                }
            }
            if (firstResult is Result.Ok) {
                secondResult = when (val next = stack.removeLastOrNull()) {
                    null -> firstResult
                    is GroupState.Alternation -> error("unreachable")
                    is GroupState.Group -> Result.Err(this.error(next.group.span, ErrorKind.GroupUnclosed))
                }
            }
        }
        val first = firstResult!!
        if (first is Result.Err) return first
        return secondResult!!
    }

    /**
     * Parse the opening of a character class and push the current class
     * parsing context onto the parser's stack.
     */
    internal fun pushClassOpen(parentUnion: ClassSetUnion): ParseResult<ClassSetUnion> {
        check(this.char() == ch('['))
        val (nestedSet, nestedUnion) = when (val r = this.parseSetClassOpen()) {
            is Result.Err -> return r
            is Result.Ok -> r.value
        }
        this.parser().stackClass.withBorrowMut { guard ->
            guard.value.add(ClassState.Open(union = parentUnion, set = nestedSet))
        }
        return Result.Ok(nestedUnion)
    }

    /**
     * Parse the end of a character class set and pop the character class
     * parser stack.
     */
    internal fun popClass(nestedUnion: ClassSetUnion): ParseResult<Either<ClassSetUnion, ClassBracketed>> {
        check(this.char() == ch(']'))
        val item = ClassSet.Item(nestedUnion.intoItem())
        val prevset = this.popClassOp(item)
        var popped: ClassState? = null
        var isEmptyAfterPop = false
        this.parser().stackClass.withBorrowMut { guard ->
            popped = guard.value.removeLastOrNull()
            isEmptyAfterPop = guard.value.isEmpty()
        }
        return when (val top = popped) {
            null -> error("unexpected empty character class stack")
            is ClassState.Op -> error("unexpected ClassState::Op")
            is ClassState.Open -> {
                val union = top.union
                val set = top.set
                this.bump()
                set.span.end = this.pos()
                set.kind = prevset
                if (isEmptyAfterPop) {
                    Result.Ok(Either.Right(set))
                } else {
                    union.push(ClassSetItem.Bracketed(set))
                    Result.Ok(Either.Left(union))
                }
            }
        }
    }

    /**
     * Return an "unclosed class" error whose span points to the most
     * recently opened class.
     */
    internal fun unclosedClassError(): Error {
        val span = this.parser().stackClass.withBorrow { stack ->
            var found: Span? = null
            for (i in stack.indices.reversed()) {
                val state = stack[i]
                if (state is ClassState.Open) {
                    found = state.set.span
                    break
                }
            }
            found
        }
        if (span != null) {
            return this.error(span, ErrorKind.ClassUnclosed)
        }
        error("no open character class found")
    }

    /**
     * Push the current set of class items on to the class parser's stack as
     * the left hand side of the given operator.
     */
    internal fun pushClassOp(nextKind: ClassSetBinaryOpKind, nextUnion: ClassSetUnion): ClassSetUnion {
        val item = ClassSet.Item(nextUnion.intoItem())
        val newLhs = this.popClassOp(item)
        this.parser().stackClass.withBorrowMut { guard ->
            guard.value.add(ClassState.Op(kind = nextKind, lhs = newLhs))
        }
        return ClassSetUnion(span = this.span(), items = emptyList())
    }

    /**
     * Pop a character class set from the character class parser stack.
     */
    internal fun popClassOp(rhs: ClassSet): ClassSet {
        var opState: ClassState.Op? = null
        this.parser().stackClass.withBorrowMut { guard ->
            val stack = guard.value
            when (val top = stack.removeLastOrNull()) {
                is ClassState.Op -> opState = top
                is ClassState.Open -> stack.add(top)
                null -> error("unreachable")
            }
        }
        val op = opState ?: return rhs
        val span = Span.new(op.lhs.span().start, rhs.span().end)
        return ClassSet.BinaryOp(ClassSetBinaryOp(span = span, kind = op.kind, lhs = op.lhs, rhs = rhs))
    }

    /**
     * Parse the regular expression into an abstract syntax tree.
     */
    internal fun parse(): ParseResult<Ast> =
        when (val r = this.parseWithComments()) {
            is Result.Ok -> Result.Ok(r.value.ast)
            is Result.Err -> r
        }

    /**
     * Parse the regular expression and return an abstract syntax tree with
     * all of the comments found in the pattern.
     */
    internal fun parseWithComments(): ParseResult<WithComments> {
        check(this.offset() == 0)
        this.parser().reset()
        var concat = Concat(span = this.span(), asts = emptyList())
        while (true) {
            this.bumpSpace()
            if (this.isEof()) {
                break
            }
            when (this.char()) {
                ch('(') -> concat = when (val r = this.pushGroup(concat)) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }
                ch(')') -> concat = when (val r = this.popGroup(concat)) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }
                ch('|') -> concat = when (val r = this.pushAlternate(concat)) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }
                ch('[') -> {
                    val cls = when (val r = this.parseSetClass()) {
                        is Result.Ok -> r.value
                        is Result.Err -> return r
                    }
                    concat.mutableAsts.add(Ast.classBracketed(cls))
                }
                ch('?') -> concat = when (val r = this.parseUncountedRepetition(concat, RepetitionKind.ZeroOrOne)) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }
                ch('*') -> concat = when (val r = this.parseUncountedRepetition(concat, RepetitionKind.ZeroOrMore)) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }
                ch('+') -> concat = when (val r = this.parseUncountedRepetition(concat, RepetitionKind.OneOrMore)) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }
                ch('{') -> concat = when (val r = this.parseCountedRepetition(concat)) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }
                else -> {
                    val prim = when (val r = this.parsePrimitive()) {
                        is Result.Ok -> r.value
                        is Result.Err -> return r
                    }
                    concat.mutableAsts.add(prim.intoAst())
                }
            }
        }
        val ast = when (val r = this.popGroupEnd(concat)) {
            is Result.Ok -> r.value
            is Result.Err -> return r
        }
        when (val r = NestLimiter.new(this).check(ast)) {
            is Result.Err -> return r
            is Result.Ok -> {}
        }
        val takenComments = this.parser().comments.withBorrowMut { guard ->
            val list = guard.value.toList()
            guard.value.clear()
            list
        }
        return Result.Ok(WithComments(ast = ast, comments = takenComments))
    }

    /**
     * Parses an uncounted repetition operation. An uncounted repetition
     * operator includes ?, * and +, but does not include the {m,n} syntax.
     * The given `kind` should correspond to the operator observed by the
     * caller.
     *
     * This assumes that the parser is currently positioned at the repetition
     * operator and advances the parser to the first character after the
     * operator. (Note that the operator may include a single additional `?`,
     * which makes the operator ungreedy.)
     *
     * The caller should include the concatenation that is being built. The
     * concatenation returned includes the repetition operator applied to the
     * last expression in the given concatenation.
     */
    internal fun parseUncountedRepetition(concat: Concat, kind: RepetitionKind): ParseResult<Concat> {
        check(this.char() == ch('?') || this.char() == ch('*') || this.char() == ch('+'))
        val opStart = this.pos()
        val ast1 = concat.mutableAsts.removeLastOrNull()
            ?: return Result.Err(this.error(this.span(), ErrorKind.RepetitionMissing))
        when (ast1) {
            is Ast.Empty, is Ast.Flags -> {
                return Result.Err(this.error(this.span(), ErrorKind.RepetitionMissing))
            }
            else -> {}
        }
        var greedy = true
        if (this.bump() && this.char() == ch('?')) {
            greedy = false
            this.bump()
        }
        concat.mutableAsts.add(
            Ast.repetition(
                Repetition(
                    span = ast1.span().withEnd(this.pos()),
                    op = RepetitionOp(span = Span.new(opStart, this.pos()), kind = kind),
                    greedy = greedy,
                    ast = ast1,
                )
            )
        )
        return Result.Ok(concat)
    }

    /**
     * Parses a counted repetition operation. A counted repetition operator
     * corresponds to the {m,n} syntax, and does not include the ?, * or +
     * operators.
     *
     * This assumes that the parser is currently positioned at the opening `{`
     * and advances the parser to the first character after the operator.
     * (Note that the operator may include a single additional `?`, which
     * makes the operator ungreedy.)
     *
     * The caller should include the concatenation that is being built. The
     * concatenation returned includes the repetition operator applied to the
     * last expression in the given concatenation.
     */
    internal fun parseCountedRepetition(concat: Concat): ParseResult<Concat> {
        check(this.char() == ch('{'))
        val start = this.pos()
        val ast1 = concat.mutableAsts.removeLastOrNull()
            ?: return Result.Err(this.error(this.span(), ErrorKind.RepetitionMissing))
        when (ast1) {
            is Ast.Empty, is Ast.Flags -> {
                return Result.Err(this.error(this.span(), ErrorKind.RepetitionMissing))
            }
            else -> {}
        }
        if (!this.bumpAndBumpSpace()) {
            return Result.Err(this.error(Span.new(start, this.pos()), ErrorKind.RepetitionCountUnclosed))
        }
        val countStart = specializeErr(this.parseDecimal(), ErrorKind.DecimalEmpty, ErrorKind.RepetitionCountDecimalEmpty)
        if (this.isEof()) {
            return Result.Err(this.error(Span.new(start, this.pos()), ErrorKind.RepetitionCountUnclosed))
        }
        val range =
            if (this.char() == ch(',')) {
                if (!this.bumpAndBumpSpace()) {
                    return Result.Err(this.error(Span.new(start, this.pos()), ErrorKind.RepetitionCountUnclosed))
                }
                if (this.char() != ch('}')) {
                    val countStart1: UInt =
                        when (countStart) {
                            is Result.Ok -> countStart.value
                            is Result.Err -> {
                                val err = countStart.error
                                if (err.kind == ErrorKind.RepetitionCountDecimalEmpty) {
                                    if (this.parser().emptyMinRange) {
                                        0u
                                    } else {
                                        return countStart
                                    }
                                } else {
                                    return countStart
                                }
                            }
                        }
                    val countEnd = when (val r = specializeErr(this.parseDecimal(), ErrorKind.DecimalEmpty, ErrorKind.RepetitionCountDecimalEmpty)) {
                        is Result.Ok -> r.value
                        is Result.Err -> return r
                    }
                    RepetitionRange.Bounded(countStart1, countEnd)
                } else {
                    val c = when (countStart) {
                        is Result.Ok -> countStart.value
                        is Result.Err -> return countStart
                    }
                    RepetitionRange.AtLeast(c)
                }
            } else {
                val c = when (countStart) {
                    is Result.Ok -> countStart.value
                    is Result.Err -> return countStart
                }
                RepetitionRange.Exactly(c)
            }
        if (this.isEof() || this.char() != ch('}')) {
            return Result.Err(this.error(Span.new(start, this.pos()), ErrorKind.RepetitionCountUnclosed))
        }
        var greedy = true
        if (this.bumpAndBumpSpace() && this.char() == ch('?')) {
            greedy = false
            this.bump()
        }
        val opSpan = Span.new(start, this.pos())
        if (!range.isValid()) {
            return Result.Err(this.error(opSpan, ErrorKind.RepetitionCountInvalid))
        }
        concat.mutableAsts.add(
            Ast.repetition(
                Repetition(
                    span = ast1.span().withEnd(this.pos()),
                    op = RepetitionOp(span = opSpan, kind = RepetitionKind.Range(range)),
                    greedy = greedy,
                    ast = ast1,
                )
            )
        )
        return Result.Ok(concat)
    }

    /**
     * Parse a group (which contains a sub-expression) or a set of flags.
     *
     * If a group was found, then it is returned with an empty AST. If a set
     * of flags is found, then that set is returned.
     *
     * The parser should be positioned at the opening parenthesis.
     *
     * This advances the parser to the character before the start of the
     * sub-expression (in the case of a group) or to the closing parenthesis
     * immediately following the set of flags.
     *
     * # Errors
     *
     * If flags are given and incorrectly specified, then a corresponding
     * error is returned.
     *
     * If a capture name is given and it is incorrectly specified, then a
     * corresponding error is returned.
     */
    internal fun parseGroup(): ParseResult<Either<SetFlags, AstGroup>> {
        check(this.char() == ch('('))
        val openSpan = this.spanChar()
        this.bump()
        this.bumpSpace()
        if (this.isLookaroundPrefix()) {
            return Result.Err(this.error(Span.new(openSpan.start, this.span().end), ErrorKind.UnsupportedLookAround))
        }
        val innerSpan = this.span()
        var startsWithP = true
        return if (this.bumpIf("?P<") ||
            run {
                startsWithP = false
                this.bumpIf("?<")
            }) {
            val captureIndex = when (val r = this.nextCaptureIndex(openSpan)) {
                is Result.Ok -> r.value
                is Result.Err -> return r
            }
            val name = when (val r = this.parseCaptureName(captureIndex)) {
                is Result.Ok -> r.value
                is Result.Err -> return r
            }
            Result.Ok(
                Either.Right(
                    AstGroup(
                        span = openSpan,
                        kind = GroupKind.CaptureName(startsWithP = startsWithP, name = name),
                        ast = Ast.empty(this.span()),
                    )
                )
            )
        } else if (this.bumpIf("?")) {
            if (this.isEof()) {
                return Result.Err(this.error(openSpan, ErrorKind.GroupUnclosed))
            }
            val flags = when (val r = this.parseFlags()) {
                is Result.Ok -> r.value
                is Result.Err -> return r
            }
            val charEnd = this.char()
            this.bump()
            if (charEnd == ch(')')) {
                // We don't allow empty flags, e.g., `(?)`. We instead
                // interpret it as a repetition operator missing its argument.
                if (flags.items.isEmpty()) {
                    return Result.Err(this.error(innerSpan, ErrorKind.RepetitionMissing))
                }
                Result.Ok(Either.Left(SetFlags(span = openSpan.copy(end = this.pos()), flags = flags)))
            } else {
                check(charEnd == ch(':'))
                Result.Ok(Either.Right(AstGroup(span = openSpan, kind = GroupKind.NonCapturing(flags), ast = Ast.empty(this.span()))))
            }
        } else {
            val captureIndex1 = when (val r = this.nextCaptureIndex(openSpan)) {
                is Result.Ok -> r.value
                is Result.Err -> return r
            }
            Result.Ok(Either.Right(AstGroup(span = openSpan, kind = GroupKind.CaptureIndex(captureIndex1), ast = Ast.empty(this.span()))))
        }
    }

    /**
     * Parses a capture group name. Assumes that the parser is positioned at
     * the first character in the name following the opening `<` (and may
     * possibly be EOF). This advances the parser to the first character
     * following the closing `>`.
     *
     * The caller must provide the capture index of the group for this name.
     */
    internal fun parseCaptureName(captureIndex: UInt): ParseResult<CaptureName> {
        if (this.isEof()) {
            return Result.Err(this.error(this.span(), ErrorKind.GroupNameUnexpectedEof))
        }
        val start = this.pos()
        while (true) {
            if (this.char() == ch('>')) {
                break
            }
            if (!isCaptureChar(this.char(), this.pos() == start)) {
                return Result.Err(this.error(this.spanChar(), ErrorKind.GroupNameInvalid))
            }
            if (!this.bump()) {
                break
            }
        }
        val end = this.pos()
        if (this.isEof()) {
            return Result.Err(this.error(this.span(), ErrorKind.GroupNameUnexpectedEof))
        }
        check(this.char() == ch('>'))
        this.bump()
        val name = this.utf8Bytes.decodeToString(start.offset, end.offset)
        if (name.isEmpty()) {
            return Result.Err(this.error(Span.new(start, start), ErrorKind.GroupNameEmpty))
        }
        val capname = CaptureName(span = Span.new(start, end), name = name, index = captureIndex)
        when (val r = this.addCaptureName(capname)) {
            is Result.Ok -> {}
            is Result.Err -> return r
        }
        return Result.Ok(capname)
    }

    /**
     * Parse a sequence of flags starting at the current character.
     *
     * This advances the parser to the character immediately following the
     * flags, which is guaranteed to be either `:` or `)`.
     *
     * # Errors
     *
     * If any flags are duplicated, then an error is returned.
     *
     * If the negation operator is used more than once, then an error is
     * returned.
     *
     * If no flags could be found or if the negation operation is not followed
     * by any flags, then an error is returned.
     */
    internal fun parseFlags(): ParseResult<Flags> {
        val flags = Flags(span = this.span(), items = emptyList())
        var lastWasNegation: Span? = null
        while (this.char() != ch(':') && this.char() != ch(')')) {
            if (this.char() == ch('-')) {
                lastWasNegation = this.spanChar()
                val item = FlagsItem(span = this.spanChar(), kind = FlagsItemKind.Negation)
                val duplicateIdx = flags.addItem(item)
                if (duplicateIdx != null) {
                    return Result.Err(this.error(this.spanChar(), ErrorKind.FlagRepeatedNegation(original = flags.items[duplicateIdx].span)))
                }
            } else {
                lastWasNegation = null
                val parsedFlag = when (val r = this.parseFlag()) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }
                val item1 = FlagsItem(span = this.spanChar(), kind = FlagsItemKind.Flag(parsedFlag))
                val duplicateIdx1 = flags.addItem(item1)
                if (duplicateIdx1 != null) {
                    return Result.Err(this.error(this.spanChar(), ErrorKind.FlagDuplicate(original = flags.items[duplicateIdx1].span)))
                }
            }
            if (!this.bump()) {
                return Result.Err(this.error(this.span(), ErrorKind.FlagUnexpectedEof))
            }
        }
        val negSpan = lastWasNegation
        if (negSpan != null) {
            return Result.Err(this.error(negSpan, ErrorKind.FlagDanglingNegation))
        }
        flags.span.end = this.pos()
        return Result.Ok(flags)
    }

    /**
     * Parse the current character as a flag. Do not advance the parser.
     *
     * # Errors
     *
     * If the flag is not recognized, then an error is returned.
     */
    internal fun parseFlag(): ParseResult<Flag> =
        when (this.char()) {
            ch('i') -> Result.Ok(Flag.CaseInsensitive)
            ch('m') -> Result.Ok(Flag.MultiLine)
            ch('s') -> Result.Ok(Flag.DotMatchesNewLine)
            ch('U') -> Result.Ok(Flag.SwapGreed)
            ch('u') -> Result.Ok(Flag.Unicode)
            ch('R') -> Result.Ok(Flag.CRLF)
            ch('x') -> Result.Ok(Flag.IgnoreWhitespace)
            else -> Result.Err(this.error(this.spanChar(), ErrorKind.FlagUnrecognized))
        }

    /**
     * Parse a primitive AST. e.g., A literal, non-set character class or
     * assertion.
     *
     * This assumes that the parser expects a primitive at the current
     * location. i.e., All other non-primitive cases have been handled.
     * For example, if the parser's position is at `|`, then `|` will be
     * treated as a literal (e.g., inside a character class).
     *
     * This advances the parser to the first character immediately following
     * the primitive.
     */
    internal fun parsePrimitive(): ParseResult<Primitive> =
        when (val c = this.char()) {
            ch('\\') -> this.parseEscape()
            ch('.') -> {
                val ast = Primitive.Dot(this.spanChar())
                this.bump()
                Result.Ok(ast)
            }
            ch('^') -> {
                val ast1 = Primitive.Assertion(AstAssertion(span = this.spanChar(), kind = AssertionKind.StartLine))
                this.bump()
                Result.Ok(ast1)
            }
            ch('$') -> {
                val ast2 = Primitive.Assertion(AstAssertion(span = this.spanChar(), kind = AssertionKind.EndLine))
                this.bump()
                Result.Ok(ast2)
            }
            else -> {
                val ast3 = Primitive.Literal(AstLiteral(span = this.spanChar(), kind = LiteralKind.Verbatim, c = c))
                this.bump()
                Result.Ok(ast3)
            }
        }

    /**
     * Parse an escape sequence as a primitive AST.
     *
     * This assumes the parser is positioned at the start of the escape
     * sequence, i.e., `\`. It advances the parser to the first position
     * immediately following the escape sequence.
     */
    internal fun parseEscape(): ParseResult<Primitive> {
        check(this.char() == ch('\\'))
        val start = this.pos()
        if (!this.bump()) {
            return Result.Err(this.error(Span.new(start, this.pos()), ErrorKind.EscapeUnexpectedEof))
        }
        val c = this.char()
        // Put some of the more complicated routines into helpers.
        when {
            c in ch('0')..ch('7') -> {
                if (!this.parser().octal) {
                    return Result.Err(this.error(Span.new(start, this.spanChar().end), ErrorKind.UnsupportedBackreference))
                }
                val lit = this.parseOctal()
                lit.span.start = start
                return Result.Ok(Primitive.Literal(lit))
            }
            c in ch('8')..ch('9') && !this.parser().octal -> {
                return Result.Err(this.error(Span.new(start, this.spanChar().end), ErrorKind.UnsupportedBackreference))
            }
            c == ch('x') || c == ch('u') || c == ch('U') -> {
                val lit1 = when (val r = this.parseHex()) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }
                lit1.span.start = start
                return Result.Ok(Primitive.Literal(lit1))
            }
            c == ch('p') || c == ch('P') -> {
                val cls = when (val r = this.parseUnicodeClass()) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }
                cls.span.start = start
                return Result.Ok(Primitive.Unicode(cls))
            }
            c == ch('d') || c == ch('s') || c == ch('w') || c == ch('D') || c == ch('S') || c == ch('W') -> {
                val cls1 = this.parsePerlClass()
                cls1.span.start = start
                return Result.Ok(Primitive.Perl(cls1))
            }
            else -> {}
        }
        // Handle all of the one letter sequences inline.
        this.bump()
        val span = Span.new(start, this.pos())
        if (isMetaCharacter(c)) {
            return Result.Ok(Primitive.Literal(AstLiteral(span = span, kind = LiteralKind.Meta, c = c)))
        }
        if (isEscapeableCharacter(c)) {
            return Result.Ok(Primitive.Literal(AstLiteral(span = span, kind = LiteralKind.Superfluous, c = c)))
        }
        fun special(kind: SpecialLiteralKind, c1: UInt): ParseResult<Primitive> =
            Result.Ok(Primitive.Literal(AstLiteral(span = span, kind = LiteralKind.Special(kind), c = c1)))
        return when (c) {
            ch('a') -> special(SpecialLiteralKind.Bell, 0x07u)
            ch('f') -> special(SpecialLiteralKind.FormFeed, 0x0Cu)
            ch('t') -> special(SpecialLiteralKind.Tab, ch('\t'))
            ch('n') -> special(SpecialLiteralKind.LineFeed, ch('\n'))
            ch('r') -> special(SpecialLiteralKind.CarriageReturn, ch('\r'))
            ch('v') -> special(SpecialLiteralKind.VerticalTab, 0x0Bu)
            ch('A') -> Result.Ok(Primitive.Assertion(AstAssertion(span = span, kind = AssertionKind.StartText)))
            ch('z') -> Result.Ok(Primitive.Assertion(AstAssertion(span = span, kind = AssertionKind.EndText)))
            ch('b') -> {
                val wb = AstAssertion(span = span, kind = AssertionKind.WordBoundary)
                // After a \b, we "try" to parse things like \b{start} for
                // special word boundary assertions.
                if (!this.isEof() && this.char() == ch('{')) {
                    when (val r = this.maybeParseSpecialWordBoundary(start)) {
                        is Result.Err -> return r
                        is Result.Ok -> {
                            val kind = r.value
                            if (kind != null) {
                                wb.kind = kind
                                wb.span.end = this.pos()
                            }
                        }
                    }
                }
                Result.Ok(Primitive.Assertion(wb))
            }
            ch('B') -> Result.Ok(Primitive.Assertion(AstAssertion(span = span, kind = AssertionKind.NotWordBoundary)))
            ch('<') -> Result.Ok(Primitive.Assertion(AstAssertion(span = span, kind = AssertionKind.WordBoundaryStartAngle)))
            ch('>') -> Result.Ok(Primitive.Assertion(AstAssertion(span = span, kind = AssertionKind.WordBoundaryEndAngle)))
            else -> Result.Err(this.error(span, ErrorKind.EscapeUnrecognized))
        }
    }

    /**
     * Attempt to parse a specialty word boundary. That is, `\b{start}`,
     * `\b{end}`, `\b{start-half}` or `\b{end-half}`.
     *
     * This is similar to `maybeParseAsciiClass` in that, in most cases,
     * if it fails it will just return `None` with no error. This is done
     * because `\b{5}` is a valid expression and we want to allow that to be parsed
     * by the existing counted repetition parsing code. (I thought about just
     * invoking the counted repetition code from here, but it seemed a little
     * ham-fisted.)
     *
     * Unlike `maybeParseAsciiClass` though, this can return an error.
     * Namely, if we definitely know it isn't a counted repetition, then we
     * return an error specific to the specialty word boundaries.
     *
     * This assumes the parser is positioned at a `{` immediately following
     * a `\b`. When `None` is returned, the parser is returned to the position
     * at which it started: pointing at a `{`.
     *
     * The position given should correspond to the start of the `\b`.
     */
    internal fun maybeParseSpecialWordBoundary(wbStart: Position): ParseResult<AssertionKind?> {
        check(this.char() == ch('{'))
        val isValidChar: (UInt) -> Boolean = { c ->
            c in ch('A')..ch('Z') || c in ch('a')..ch('z') || c == ch('-')
        }
        val start = this.pos()
        if (!this.bumpAndBumpSpace()) {
            return Result.Err(this.error(Span.new(wbStart, this.pos()), ErrorKind.SpecialWordOrRepetitionUnexpectedEof))
        }
        val startContents = this.pos()
        // This is one of the critical bits: if the first non-whitespace
        // character isn't in [-A-Za-z] (i.e., this can't be a special word
        // boundary), then we bail and allow the counted repetition parser to deal
        // with this.
        if (!isValidChar(this.char())) {
            this.parser().pos.set(start)
            return Result.Ok(null)
        }
        // Now collect up our chars until we see a '}'.
        return this.parser().scratch.withBorrowMut { guard ->
            val scratch = guard.value
            scratch.clear()
            while (!this.isEof() && isValidChar(this.char())) {
                scratch.appendScalar(this.char())
                this.bumpAndBumpSpace()
            }
            if (this.isEof() || this.char() != ch('}')) {
                return Result.Err(this.error(Span.new(start, this.pos()), ErrorKind.SpecialWordBoundaryUnclosed))
            }
            val end = this.pos()
            this.bump()
            val kind =
                when (scratch.toString()) {
                    "start" -> AssertionKind.WordBoundaryStart
                    "end" -> AssertionKind.WordBoundaryEnd
                    "start-half" -> AssertionKind.WordBoundaryStartHalf
                    "end-half" -> AssertionKind.WordBoundaryEndHalf
                    else -> {
                        return Result.Err(this.error(Span.new(startContents, end), ErrorKind.SpecialWordBoundaryUnrecognized))
                    }
                }
            Result.Ok(kind)
        }
    }

    /**
     * Parse an octal representation of a Unicode codepoint up to 3 digits
     * long. This expects the parser to be positioned at the first octal
     * digit and advances the parser to the first character immediately
     * following the octal number. This also assumes that parsing octal
     * escapes is enabled.
     *
     * Assuming the preconditions are met, this routine can never fail.
     */
    internal fun parseOctal(): AstLiteral {
        check(this.parser().octal)
        check(this.char() in ch('0')..ch('7'))
        val start = this.pos()
        // Parse up to two more digits.
        while (this.bump() && this.char() in ch('0')..ch('7') && this.pos().offset - start.offset <= 2) {}
        val end = this.pos()
        val octal = this.utf8Bytes.decodeToString(start.offset, end.offset)
        // Parsing the octal should never fail since the above guarantees a
        // valid number.
        val codepoint = parseUIntRadix(octal, 8) ?: error("valid octal number")
        // The max value for 3 digit octal is 0777 = 511 and [0, 511] has no
        // invalid Unicode scalar values.
        val c = scalarFromU32(codepoint) ?: error("Unicode scalar value")
        return AstLiteral(span = Span.new(start, end), kind = LiteralKind.Octal, c = c)
    }

    /**
     * Parse a hex representation of a Unicode codepoint. This handles both
     * hex notations, i.e., `\xFF` and `\x{FFFF}`. This expects the parser to
     * be positioned at the `x`, `u` or `U` prefix. The parser is advanced to
     * the first character immediately following the hexadecimal literal.
     */
    internal fun parseHex(): ParseResult<AstLiteral> {
        check(this.char() == ch('x') || this.char() == ch('u') || this.char() == ch('U'))
        val hexKind =
            when (this.char()) {
                ch('x') -> HexLiteralKind.X
                ch('u') -> HexLiteralKind.UnicodeShort
                else -> HexLiteralKind.UnicodeLong
            }
        if (!this.bumpAndBumpSpace()) {
            return Result.Err(this.error(this.span(), ErrorKind.EscapeUnexpectedEof))
        }
        return if (this.char() == ch('{')) {
            this.parseHexBrace(hexKind)
        } else {
            this.parseHexDigits(hexKind)
        }
    }

    /**
     * Parse an N-digit hex representation of a Unicode codepoint. This
     * expects the parser to be positioned at the first digit and will advance
     * the parser to the first character immediately following the escape
     * sequence.
     *
     * The number of digits given must be 2 (for `\xNN`), 4 (for `\uNNNN`)
     * or 8 (for `\UNNNNNNNN`).
     */
    internal fun parseHexDigits(kind: HexLiteralKind): ParseResult<AstLiteral> {
        return this.parser().scratch.withBorrowMut { guard ->
            val scratch = guard.value
            scratch.clear()
            val start = this.pos()
            for (i in 0 until kind.digits().toInt()) {
                if (i > 0 && !this.bumpAndBumpSpace()) {
                    return Result.Err(this.error(this.span(), ErrorKind.EscapeUnexpectedEof))
                }
                if (!isHex(this.char())) {
                    return Result.Err(this.error(this.spanChar(), ErrorKind.EscapeHexInvalidDigit))
                }
                scratch.appendScalar(this.char())
            }
            // The final bump just moves the parser past the literal, which may
            // be EOF.
            this.bumpAndBumpSpace()
            val end = this.pos()
            val hex = scratch.toString()
            when (val c = parseUIntRadix(hex, 16)?.let(::scalarFromU32)) {
                null -> Result.Err(this.error(Span.new(start, end), ErrorKind.EscapeHexInvalid))
                else -> Result.Ok(AstLiteral(span = Span.new(start, end), kind = LiteralKind.HexFixed(kind), c = c))
            }
        }
    }

    /**
     * Parse a hex representation of any Unicode scalar value. This expects
     * the parser to be positioned at the opening brace `{` and will advance
     * the parser to the first character following the closing brace `}`.
     */
    internal fun parseHexBrace(kind: HexLiteralKind): ParseResult<AstLiteral> {
        return this.parser().scratch.withBorrowMut { guard ->
            val scratch = guard.value
            scratch.clear()
            val bracePos = this.pos()
            val start = this.spanChar().end
            while (this.bumpAndBumpSpace() && this.char() != ch('}')) {
                if (!isHex(this.char())) {
                    return Result.Err(this.error(this.spanChar(), ErrorKind.EscapeHexInvalidDigit))
                }
                scratch.appendScalar(this.char())
            }
            if (this.isEof()) {
                return Result.Err(this.error(Span.new(bracePos, this.pos()), ErrorKind.EscapeUnexpectedEof))
            }
            val end = this.pos()
            val hex = scratch.toString()
            check(this.char() == ch('}'))
            this.bumpAndBumpSpace()
            if (hex.isEmpty()) {
                return Result.Err(this.error(Span.new(bracePos, this.pos()), ErrorKind.EscapeHexEmpty))
            }
            when (val c = parseUIntRadix(hex, 16)?.let(::scalarFromU32)) {
                null -> Result.Err(this.error(Span.new(start, end), ErrorKind.EscapeHexInvalid))
                else -> Result.Ok(AstLiteral(span = Span.new(start, this.pos()), kind = LiteralKind.HexBrace(kind), c = c))
            }
        }
    }

    /**
     * Parse a decimal number into a u32 while trimming leading and trailing
     * whitespace.
     *
     * This expects the parser to be positioned at the first position where
     * a decimal digit could occur. This will advance the parser to the byte
     * immediately following the last contiguous decimal digit.
     *
     * If no decimal digit could be found or if there was a problem parsing
     * the complete set of digits into a u32, then an error is returned.
     */
    internal fun parseDecimal(): ParseResult<UInt> {
        return this.parser().scratch.withBorrowMut { guard ->
            val scratch = guard.value
            scratch.clear()
            while (!this.isEof() && scalarIsWhitespace(this.char())) {
                this.bump()
            }
            val start = this.pos()
            while (!this.isEof() && this.char() in ch('0')..ch('9')) {
                scratch.appendScalar(this.char())
                this.bumpAndBumpSpace()
            }
            val span = Span.new(start, this.pos())
            while (!this.isEof() && scalarIsWhitespace(this.char())) {
                this.bumpAndBumpSpace()
            }
            val digits = scratch.toString()
            if (digits.isEmpty()) {
                return Result.Err(this.error(span, ErrorKind.DecimalEmpty))
            }
            when (val n = parseUIntRadix(digits, 10)) {
                null -> Result.Err(this.error(span, ErrorKind.DecimalInvalid))
                else -> Result.Ok(n)
            }
        }
    }

    /**
     * Parse a standard character class consisting primarily of characters or
     * character ranges, but can also contain nested character classes of
     * any type (sans `.`).
     *
     * This assumes the parser is positioned at the opening `[`. If parsing
     * is successful, then the parser is advanced to the position immediately
     * following the closing `]`.
     */
    internal fun parseSetClass(): ParseResult<ClassBracketed> {
        check(this.char() == ch('['))
        var union = ClassSetUnion(span = this.span(), items = emptyList())
        while (true) {
            this.bumpSpace()
            if (this.isEof()) {
                return Result.Err(this.unclosedClassError())
            }
            val c = this.char()
            when {
                c == ch('[') -> {
                    // If we've already parsed the opening bracket, then
                    // attempt to treat this as the beginning of an ASCII
                    // class. If ASCII class parsing fails, then the parser
                    // backs up to `[`.
                    if (this.parser().stackClass.withBorrow { it.isNotEmpty() }) {
                        val cls = this.maybeParseAsciiClass()
                        if (cls != null) {
                            union.push(ClassSetItem.Ascii(cls))
                            continue
                        }
                    }
                    union = when (val r = this.pushClassOpen(union)) {
                        is Result.Ok -> r.value
                        is Result.Err -> return r
                    }
                }
                c == ch(']') -> when (val popped = when (val r = this.popClass(union)) {
                    is Result.Ok -> r.value
                    is Result.Err -> return r
                }) {
                    is Either.Left -> union = popped.value
                    is Either.Right -> return Result.Ok(popped.value)
                }
                c == ch('&') && this.peek() == ch('&') -> {
                    check(this.bumpIf("&&"))
                    union = this.pushClassOp(ClassSetBinaryOpKind.Intersection, union)
                }
                c == ch('-') && this.peek() == ch('-') -> {
                    check(this.bumpIf("--"))
                    union = this.pushClassOp(ClassSetBinaryOpKind.Difference, union)
                }
                c == ch('~') && this.peek() == ch('~') -> {
                    check(this.bumpIf("~~"))
                    union = this.pushClassOp(ClassSetBinaryOpKind.SymmetricDifference, union)
                }
                else -> {
                    val item = when (val r = this.parseSetClassRange()) {
                        is Result.Ok -> r.value
                        is Result.Err -> return r
                    }
                    union.push(item)
                }
            }
        }
    }

    /**
     * Parse a single primitive item in a character class set. The item to
     * be parsed can either be one of a simple literal character, a range
     * between two simple literal characters or a "primitive" character
     * class like \w or \p{Greek}.
     *
     * If an invalid escape is found, or if a character class is found where
     * a simple literal is expected (e.g., in a range), then an error is
     * returned.
     */
    internal fun parseSetClassRange(): ParseResult<ClassSetItem> {
        val prim1 = when (val r = this.parseSetClassItem()) {
            is Result.Ok -> r.value
            is Result.Err -> return r
        }
        this.bumpSpace()
        if (this.isEof()) {
            return Result.Err(this.unclosedClassError())
        }
        // If the next char isn't a `-`, then we don't have a range.
        // There are two exceptions. If the char after a `-` is a `]`, then
        // `-` is interpreted as a literal `-`. Alternatively, if the char
        // after a `-` is a `-`, then `- -` corresponds to a "difference"
        // operation.
        if (this.char() != ch('-') || this.peekSpace() == ch(']') || this.peekSpace() == ch('-')) {
            return prim1.intoClassSetItem(this)
        }
        // OK, now we're parsing a range, so bump past the `-` and parse the
        // second half of the range.
        if (!this.bumpAndBumpSpace()) {
            return Result.Err(this.unclosedClassError())
        }
        val prim2 = when (val r = this.parseSetClassItem()) {
            is Result.Ok -> r.value
            is Result.Err -> return r
        }
        val startLit = when (val r = prim1.intoClassLiteral(this)) {
            is Result.Ok -> r.value
            is Result.Err -> return r
        }
        val endLit = when (val r = prim2.intoClassLiteral(this)) {
            is Result.Ok -> r.value
            is Result.Err -> return r
        }
        val range = ClassSetRange(span = Span.new(prim1.span().start, prim2.span().end), start = startLit, end = endLit)
        if (!range.isValid()) {
            return Result.Err(this.error(range.span, ErrorKind.ClassRangeInvalid))
        }
        return Result.Ok(ClassSetItem.Range(range))
    }

    /**
     * Parse a single item in a character class as a primitive, where the
     * primitive either consists of a verbatim literal or a single escape
     * sequence.
     *
     * This assumes the parser is positioned at the beginning of a primitive,
     * and advances the parser to the first position after the primitive if
     * successful.
     *
     * Note that it is the caller's responsibility to report an error if an
     * illegal primitive was parsed.
     */
    internal fun parseSetClassItem(): ParseResult<Primitive> =
        if (this.char() == ch('\\')) {
            this.parseEscape()
        } else {
            val x = Primitive.Literal(AstLiteral(span = this.spanChar(), kind = LiteralKind.Verbatim, c = this.char()))
            this.bump()
            Result.Ok(x)
        }

    /**
     * Parses the opening of a character class set. This includes the opening
     * bracket along with `^` if present to indicate negation. This also
     * starts parsing the opening set of unioned items if applicable, since
     * there are special rules applied to certain characters in the opening
     * of a character class. For example, `[^]]` is the class of all
     * characters not equal to `]`. (`]` would need to be escaped in any other
     * position.) Similarly for `-`.
     *
     * In all cases, the op inside the returned `ast.ClassBracketed` is an
     * empty union. This empty union should be replaced with the actual item
     * when it is popped from the parser's stack.
     *
     * This assumes the parser is positioned at the opening `[` and advances
     * the parser to the first non-special byte of the character class.
     *
     * An error is returned if EOF is found.
     */
    internal fun parseSetClassOpen(): ParseResult<Pair<ClassBracketed, ClassSetUnion>> {
        check(this.char() == ch('['))
        val start = this.pos()
        if (!this.bumpAndBumpSpace()) {
            return Result.Err(this.error(Span.new(start, this.pos()), ErrorKind.ClassUnclosed))
        }
        val negated =
            if (this.char() != ch('^')) {
                false
            } else {
                if (!this.bumpAndBumpSpace()) {
                    return Result.Err(this.error(Span.new(start, this.pos()), ErrorKind.ClassUnclosed))
                }
                true
            }
        // Accept any number of `-` as literal `-`.
        val union = ClassSetUnion(span = this.span(), items = emptyList())
        while (this.char() == ch('-')) {
            union.push(ClassSetItem.Literal(AstLiteral(span = this.spanChar(), kind = LiteralKind.Verbatim, c = ch('-'))))
            if (!this.bumpAndBumpSpace()) {
                return Result.Err(this.error(Span.new(start, start), ErrorKind.ClassUnclosed))
            }
        }
        // If `]` is the *first* char in a set, then interpret it as a literal
        // `]`. That is, an empty class is impossible to write.
        if (union.items.isEmpty() && this.char() == ch(']')) {
            union.push(ClassSetItem.Literal(AstLiteral(span = this.spanChar(), kind = LiteralKind.Verbatim, c = ch(']'))))
            if (!this.bumpAndBumpSpace()) {
                return Result.Err(this.error(Span.new(start, this.pos()), ErrorKind.ClassUnclosed))
            }
        }
        val set = ClassBracketed(span = Span.new(start, this.pos()), negated = negated, kind = ClassSet.union(ClassSetUnion(span = Span.new(union.span.start, union.span.start), items = emptyList())))
        return Result.Ok(Pair(set, union))
    }

    /**
     * Attempt to parse an ASCII character class, e.g., `[: alnum:]`.
     *
     * This assumes the parser is positioned at the opening `[`.
     *
     * If no valid ASCII character class could be found, then this does not
     * advance the parser and `None` is returned. Otherwise, the parser is
     * advanced to the first byte following the closing `]` and the
     * corresponding ASCII class is returned.
     */
    internal fun maybeParseAsciiClass(): ClassAscii? {
        // ASCII character classes are interesting from a parsing perspective
        // because parsing cannot fail with any interesting error. For example,
        // in order to use an ASCII character class, it must be enclosed in
        // double brackets, e.g., `[[: alnum:]]`. Alternatively, you might think
        // of it as "ASCII character classes have the syntax `[: NAME:]` which
        // can only appear within character brackets." This means that things
        // like `[[: lower:] A]` are legal constructs.
        //
        // However, if one types an incorrect ASCII character class, e.g.,
        // `[[: loower:]]`, then we treat that as a normal nested character
        // class containing the characters `: elorw`. One might argue that we
        // should return an error instead since the repeated colons give away
        // the intent to write an ASCII class. But what if the user typed
        // `[[: lower]]` instead? How can we tell that was intended to be an
        // ASCII class and not just a normal nested class?
        //
        // Reasonable people can probably disagree over this, but for better
        // or worse, we implement semantics that never fails at the expense
        // of better failure modes.
        check(this.char() == ch('['))
        // If parsing fails, then we back up the parser to this starting point.
        val start = this.pos()
        var negated = false
        if (!this.bump() || this.char() != ch(':')) {
            this.parser().pos.set(start)
            return null
        }
        if (!this.bump()) {
            this.parser().pos.set(start)
            return null
        }
        if (this.char() == ch('^')) {
            negated = true
            if (!this.bump()) {
                this.parser().pos.set(start)
                return null
            }
        }
        val nameStart = this.offset()
        while (this.char() != ch(':') && this.bump()) {}
        if (this.isEof()) {
            this.parser().pos.set(start)
            return null
        }
        val name = this.utf8Bytes.decodeToString(nameStart, this.offset())
        if (!this.bumpIf(":]")) {
            this.parser().pos.set(start)
            return null
        }
        val kind1 = ClassAsciiKind.fromName(name)
            ?: run {
                this.parser().pos.set(start)
                return null
            }
        return ClassAscii(span = Span.new(start, this.pos()), kind = kind1, negated = negated)
    }

    /**
     * Parse a Unicode class in either the single character notation, `\pN`
     * or the multi-character bracketed notation, `\p{Greek}`. This assumes
     * the parser is positioned at the `p` (or `P` for negation) and will
     * advance the parser to the character immediately following the class.
     *
     * Note that this does not check whether the class name is valid or not.
     */
    internal fun parseUnicodeClass(): ParseResult<ClassUnicode> {
        check(this.char() == ch('p') || this.char() == ch('P'))
        val negated = this.char() == ch('P')
        if (!this.bumpAndBumpSpace()) {
            return Result.Err(this.error(this.span(), ErrorKind.EscapeUnexpectedEof))
        }
        return this.parser().scratch.withBorrowMut { guard ->
            val scratch = guard.value
            scratch.clear()
            val (start2, kind1) =
                if (this.char() == ch('{')) {
                    val start = this.spanChar().end
                    while (this.bumpAndBumpSpace() && this.char() != ch('}')) {
                        scratch.appendScalar(this.char())
                    }
                    if (this.isEof()) {
                        return Result.Err(this.error(this.span(), ErrorKind.EscapeUnexpectedEof))
                    }
                    check(this.char() == ch('}'))
                    this.bump()
                    val name = scratch.toString()
                    when (val i2 = name.indexOf("!=").takeIf { it >= 0 }) {
                        null -> {
                            when (val i1 = name.indexOf(':').takeIf { it >= 0 }) {
                                null -> {
                                    when (val i = name.indexOf('=').takeIf { it >= 0 }) {
                                        null -> Pair(start, ClassUnicodeKind.Named(name))
                                        else -> Pair(
                                            start,
                                            ClassUnicodeKind.NamedValue(
                                                op = ClassUnicodeOpKind.Equal,
                                                name = name.substring(0, i),
                                                value = name.substring(i + 1),
                                            ),
                                        )
                                    }
                                }
                                else -> Pair(
                                    start,
                                    ClassUnicodeKind.NamedValue(
                                        op = ClassUnicodeOpKind.Colon,
                                        name = name.substring(0, i1),
                                        value = name.substring(i1 + 1),
                                    ),
                                )
                            }
                        }
                        else -> Pair(
                            start,
                            ClassUnicodeKind.NamedValue(
                                op = ClassUnicodeOpKind.NotEqual,
                                name = name.substring(0, i2),
                                value = name.substring(i2 + 2),
                            ),
                        )
                    }
                } else {
                    val start1 = this.pos()
                    val c = this.char()
                    if (c == ch('\\')) {
                        return Result.Err(this.error(this.spanChar(), ErrorKind.UnicodeClassInvalid))
                    }
                    this.bumpAndBumpSpace()
                    val kind = ClassUnicodeKind.OneLetter(c)
                    Pair(start1, kind)
                }
            Result.Ok(ClassUnicode(span = Span.new(start2, this.pos()), negated = negated, kind = kind1))
        }
    }

    /**
     * Parse a Perl character class, e.g., `\d` or `\W`. This assumes the
     * parser is currently at a valid character class name and will be
     * advanced to the character immediately following the class.
     */
    internal fun parsePerlClass(): ClassPerl {
        val c = this.char()
        val span = this.spanChar()
        this.bump()
        val (negated, kind) =
            when (c) {
                ch('d') -> Pair(false, ClassPerlKind.Digit)
                ch('D') -> Pair(true, ClassPerlKind.Digit)
                ch('s') -> Pair(false, ClassPerlKind.Space)
                ch('S') -> Pair(true, ClassPerlKind.Space)
                ch('w') -> Pair(false, ClassPerlKind.Word)
                ch('W') -> Pair(true, ClassPerlKind.Word)
                else -> error("expected valid Perl class but got '$c'")
            }
        return ClassPerl(span = span, kind = kind, negated = negated)
    }

    internal companion object {
        /**
         * Build an internal parser from a parser configuration and a pattern.
         */
        internal fun new(parser: Parser, pattern: String): ParserI =
            ParserI(parserRef = parser, patternStr = pattern)
    }
}

/**
 * GroupState represents a single stack frame while parsing nested groups
 * and alternations. Each frame records the state up to an opening parenthesis
 * or a alternating bracket `|`.
 */
internal sealed class GroupState {
    /**
     * This state is pushed whenever an opening group is found.
     */
    internal data class Group(internal var concat: Concat, internal var group: AstGroup, internal var ignoreWhitespace: Boolean) : GroupState()
    /**
     * This state is pushed whenever a new alternation branch is found. If
     * an alternation branch is found and this state is at the top of the
     * stack, then this state should be modified to include the new
     * alternation.
     */
    internal data class Alternation(internal var value: AstAlternation) : GroupState()
}

/**
 * ClassState represents a single stack frame while parsing character classes.
 * Each frame records the state up to an intersection, difference, symmetric
 * difference or nested class.
 *
 * Note that a parser's character class stack is only non-empty when parsing
 * a character class. In all other cases, it is empty.
 */
internal sealed class ClassState {
    /**
     * This state is pushed whenever an opening bracket is found.
     */
    internal data class Open(internal var union: ClassSetUnion, internal var set: ClassBracketed) : ClassState()
    /**
     * This state is pushed when a operator is seen. When popped, the stored
     * set becomes the left hand side of the operator.
     */
    internal data class Op(internal var kind: ClassSetBinaryOpKind, internal var lhs: ClassSet) : ClassState()
}

/**
 * A type that traverses a fully parsed Ast and checks whether its depth
 * exceeds the specified nesting limit. If it does, then an error is returned.
 */
internal open class NestLimiter(
    /**
     * The parser that is checking the nest limit.
     */
    internal var p: ParserI,
    /**
     * The current depth while walking an Ast.
     */
    internal var depth: UInt,
) : Visitor<Unit, Error> {
    internal open fun check(ast: Ast): ParseResult<Unit> = visit(ast, this)

    internal open fun incrementDepth(span: Span): ParseResult<Unit> {
        val next =
            if (this.depth == UInt.MAX_VALUE) {
                return Result.Err(this.p.error(span.copy(), ErrorKind.NestLimitExceeded(UInt.MAX_VALUE)))
            } else {
                this.depth + 1u
            }
        val limit = this.p.parser().nestLimit
        if (next > limit) {
            return Result.Err(this.p.error(span.copy(), ErrorKind.NestLimitExceeded(limit)))
        }
        this.depth = next
        return Result.Ok(Unit)
    }

    internal open fun decrementDepth() {
        // Assuming the correctness of the visitor, this should never drop
        // below 0.
        check(this.depth > 0u) { "nest depth underflow" }
        this.depth -= 1u
    }

    override fun finish(): ParseResult<Unit> = Result.Ok(Unit)

    override fun visitPre(ast: Ast): ParseResult<Unit> {
        val span =
            when (ast) {
                is Ast.Empty,
                is Ast.Flags,
                is Ast.Literal,
                is Ast.Dot,
                is Ast.Assertion,
                is Ast.ClassUnicode,
                is Ast.ClassPerl, -> {
                    // These are all base cases, so we don't increment depth.
                    return Result.Ok(Unit)
                }
                is Ast.ClassBracketed -> {
                    val (x) = ast
                    x.span
                }
                is Ast.Repetition -> {
                    val (x1) = ast
                    x1.span
                }
                is Ast.Group -> {
                    val (x2) = ast
                    x2.span
                }
                is Ast.Alternation -> {
                    val (x3) = ast
                    x3.span
                }
                is Ast.Concat -> {
                    val (x4) = ast
                    x4.span
                }
            }
        return this.incrementDepth(span)
    }

    override fun visitPost(ast: Ast): ParseResult<Unit> =
        when (ast) {
            is Ast.Empty,
            is Ast.Flags,
            is Ast.Literal,
            is Ast.Dot,
            is Ast.Assertion,
            is Ast.ClassUnicode,
            is Ast.ClassPerl, -> {
                // These are all base cases, so we don't decrement depth.
                Result.Ok(Unit)
            }
            is Ast.ClassBracketed,
            is Ast.Repetition,
            is Ast.Group,
            is Ast.Alternation,
            is Ast.Concat, -> {
                this.decrementDepth()
                Result.Ok(Unit)
            }
        }

    override fun visitClassSetItemPre(ast: ClassSetItem): ParseResult<Unit> {
        val span =
            when (ast) {
                is ClassSetItem.Empty,
                is ClassSetItem.Literal,
                is ClassSetItem.Range,
                is ClassSetItem.Ascii,
                is ClassSetItem.Unicode,
                is ClassSetItem.Perl, -> {
                    // These are all base cases, so we don't increment depth.
                    return Result.Ok(Unit)
                }
                is ClassSetItem.Bracketed -> {
                    val (x) = ast
                    x.span
                }
                is ClassSetItem.Union -> {
                    val (x1) = ast
                    x1.span
                }
            }
        return this.incrementDepth(span)
    }

    override fun visitClassSetItemPost(ast: ClassSetItem): ParseResult<Unit> =
        when (ast) {
            is ClassSetItem.Empty,
            is ClassSetItem.Literal,
            is ClassSetItem.Range,
            is ClassSetItem.Ascii,
            is ClassSetItem.Unicode,
            is ClassSetItem.Perl, -> {
                // These are all base cases, so we don't decrement depth.
                Result.Ok(Unit)
            }
            is ClassSetItem.Bracketed,
            is ClassSetItem.Union, -> {
                this.decrementDepth()
                Result.Ok(Unit)
            }
        }

    override fun visitClassSetBinaryOpPre(ast: ClassSetBinaryOp): ParseResult<Unit> =
        this.incrementDepth(ast.span)

    override fun visitClassSetBinaryOpPost(ast: ClassSetBinaryOp): ParseResult<Unit> {
        this.decrementDepth()
        return Result.Ok(Unit)
    }

    internal companion object {
        internal fun new(p: ParserI): NestLimiter = NestLimiter(p = p, depth = 0u)
    }
}

/**
 * When the result is an error, transforms the ast.ErrorKind from the source
 * Result into another one. This function is used to return clearer error
 * messages when possible.
 */
internal fun <T> specializeErr(result: ParseResult<T>, from: ErrorKind, to: ErrorKind): ParseResult<T> =
    when (result) {
        is Result.Err -> {
            val e = result.error
            if (e.kind == from) {
                Result.Err(Error(kind = to, pattern = e.pattern, span = e.span))
            } else {
                result
            }
        }
        is Result.Ok -> result
    }
