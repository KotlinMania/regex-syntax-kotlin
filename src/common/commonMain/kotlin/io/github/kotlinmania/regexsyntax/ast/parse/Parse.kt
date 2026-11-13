// port-lint: source ast/parse.rs
package io.github.kotlinmania.regexsyntax.ast.parse

import io.github.kotlinmania.regexsyntax.ast.Alternation
import io.github.kotlinmania.regexsyntax.ast.Assertion
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
import io.github.kotlinmania.regexsyntax.ast.Group
import io.github.kotlinmania.regexsyntax.ast.GroupKind
import io.github.kotlinmania.regexsyntax.ast.HexLiteralKind
import io.github.kotlinmania.regexsyntax.ast.Literal
import io.github.kotlinmania.regexsyntax.ast.LiteralKind
import io.github.kotlinmania.regexsyntax.ast.Position
import io.github.kotlinmania.regexsyntax.ast.Repetition
import io.github.kotlinmania.regexsyntax.ast.RepetitionKind
import io.github.kotlinmania.regexsyntax.ast.RepetitionOp
import io.github.kotlinmania.regexsyntax.ast.RepetitionRange
import io.github.kotlinmania.regexsyntax.ast.SetFlags
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.SpecialLiteralKind
import io.github.kotlinmania.regexsyntax.ast.Visitor
import io.github.kotlinmania.regexsyntax.ast.WithComments
import io.github.kotlinmania.regexsyntax.ast.visit
import io.github.kotlinmania.regexsyntax.either.Either
import io.github.kotlinmania.regexsyntax.isEscapeableCharacter
import io.github.kotlinmania.regexsyntax.isMetaCharacter
import io.github.kotlinmania.regexsyntax.unicodetables.graphemeclusterbreak.T
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout

// This module provides a regular expression parser.

public typealias Output = Unit

public typealias Err = Error

public typealias Result<T> = kotlin.Result<T>

/**
 * A primitive is an expression with no sub-expressions. This includes
 * literals, assertions and non-set character classes. This representation
 * is used as intermediate state in the parser.
 *
 * This does not include ASCII character classes, since they can only appear
 * within a set character class.
 */
public sealed class Primitive {
    public data class Literal(public var value: Literal) : Primitive()
    public data class Assertion(public var value: Assertion) : Primitive()
    public data class Dot(public var value: Span) : Primitive()
    public data class Perl(public var value: ClassPerl) : Primitive()
    public data class Unicode(public var value: ClassUnicode) : Primitive()

    public fun rustEquals(other: Primitive): Boolean = this == other

    /**
     * Return the span of this primitive.
     */
    public open fun span(): Span =
        when (this) {
            is Primitive.Literal -> {
                val (x) = this
                x.span
            }
            is Primitive.Assertion -> {
                val (x1) = this
                x1.span
            }
            is Primitive.Dot -> {
                val (span) = this
                span
            }
            is Primitive.Perl -> {
                val (x2) = this
                x2.span
            }
            is Primitive.Unicode -> {
                val (x3) = this
                x3.span
            }
        }

    /**
     * Convert this primitive into a proper AST.
     */
    public open fun intoAst(): Ast =
        when (this) {
            is Primitive.Literal -> {
                val (lit) = this
                Ast.literal(lit)
            }
            is Primitive.Assertion -> {
                val (assert) = this
                Ast.assertion(assert)
            }
            is Primitive.Dot -> {
                val (span) = this
                Ast.dot(span)
            }
            is Primitive.Perl -> {
                val (cls) = this
                Ast.classPerl(cls)
            }
            is Primitive.Unicode -> {
                val (cls1) = this
                Ast.classUnicode(cls1)
            }
        }

    /**
     * Convert this primitive into an item in a character class.
     *
     * If this primitive is not a legal item (i.e., an assertion or a dot),
     * then return an error.
     */
    public open fun <P : Borrow<Parser>> intoClassSetItem(p: ParserI<P>): Result<ClassSetItem> =
        when (this) {
            is Literal -> {
                val (lit) = this
                Result.success(ClassSetItem.Literal(lit))
            }
            is Perl -> {
                val (cls) = this
                Result.success(ClassSetItem.Perl(cls))
            }
            is Unicode -> {
                val (cls1) = this
                Result.success(ClassSetItem.Unicode(cls1))
            }
            else -> {
                val x = this
                Err(p.error(x.span(), ErrorKind.ClassEscapeInvalid))
            }
        }

    /**
     * Convert this primitive into a literal in a character class. In
     * particular, literals are the only valid items that can appear in
     * ranges.
     *
     * If this primitive is not a legal item (i.e., a class, assertion or a
     * dot), then return an error.
     */
    public open fun <P : Borrow<Parser>> intoClassLiteral(p: ParserI<P>): Result<Literal> =
        when (this) {
            is Literal -> {
                val (lit) = this
                Result.success(lit)
            }
            else -> {
                val x = this
                Err(p.error(x.span(), ErrorKind.ClassRangeLiteral))
            }
        }
}

/**
 * Returns true if the given character is a hexadecimal digit.
 */
public fun isHex(c: Char): Boolean = ('0' <= c && c <= '9') || ('a' <= c && c <= 'f') || ('A' <= c && c <= 'F')

/**
 * Returns true if the given character is a valid in a capture group name.
 *
 * If `first` is true, then `c` is treated as the first character in the
 * group name (which must be alphabetic or underscore).
 */
public fun isCaptureChar(c: Char, first: Boolean): Boolean =
    if (first) {
        c == '_' || c.isAlphabetic()
    } else {
        c == '_' || c == '.' || c == '[' || c == ']' || c.isAlphanumeric()
    }

/**
 * A builder for a regular expression parser.
 *
 * This builder permits modifying configuration options for the parser.
 */
public open class ParserBuilder(
    public var ignoreWhitespace: Boolean,
    public var nestLimit: UInt,
    public var octal: Boolean,
    public var emptyMinRange: Boolean,
) {
    /**
     * Build a parser from this configuration with the given pattern.
     */
    public open fun build(): Parser = Parser(pos = Cell.new(Position(offset = 0, line = 1, column = 1)), captureIndex = Cell.new(0), nestLimit = this.nestLimit, octal = this.octal, emptyMinRange = this.emptyMinRange, initialIgnoreWhitespace = this.ignoreWhitespace, ignoreWhitespace = Cell.new(this.ignoreWhitespace), comments = RefCell.new(mutableListOf()), stackGroup = RefCell.new(mutableListOf()), stackClass = RefCell.new(mutableListOf()), captureNames = RefCell.new(mutableListOf()), scratch = RefCell.new(String.new()))

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
    public open fun nestLimit(limit: UInt): ParserBuilder {
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
    public open fun octal(yes: Boolean): ParserBuilder {
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
    public open fun ignoreWhitespace(yes: Boolean): ParserBuilder {
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
    public open fun emptyMinRange(yes: Boolean): ParserBuilder {
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
public open class Parser internal constructor(
    posCtorArg: Cell<Position>,
    captureIndexCtorArg: Cell<UInt>,
    /**
     * The maximum number of open parens/brackets allowed. If the parser
     * exceeds this number, then an error is returned.
     */
    public var nestLimit: UInt,
    /**
     * Whether to support octal syntax or not. When `false`, the parser will
     * return an error helpfully pointing out that backreferences are not
     * supported.
     */
    public var octal: Boolean,
    /**
     * The initial setting for `ignoreWhitespace` as provided by
     * `ParserBuilder`. It is used when resetting the parser's state.
     */
    public var initialIgnoreWhitespace: Boolean,
    /**
     * Whether the parser supports `{ , n }` repetitions as an equivalent to
     * `{ 0, n }.`
     */
    public var emptyMinRange: Boolean,
    ignoreWhitespaceCtorArg: Cell<Boolean>,
    commentsCtorArg: RefCell<List<Comment>>,
    stackGroupCtorArg: RefCell<List<GroupState>>,
    stackClassCtorArg: RefCell<List<ClassState>>,
    captureNamesCtorArg: RefCell<List<CaptureName>>,
    scratchCtorArg: RefCell<String>,
) {
    /**
     * The current position of the parser.
     */
    public var pos: Cell<Position> = posCtorArg
        internal set

    /**
     * The current capture index.
     */
    public var captureIndex: Cell<UInt> = captureIndexCtorArg
        internal set

    /**
     * Whether whitespace should be ignored. When enabled, comments are
     * also permitted.
     */
    public var ignoreWhitespace: Cell<Boolean> = ignoreWhitespaceCtorArg
        internal set

    /**
     * A list of comments, in order of appearance.
     */
    public var comments: RefCell<List<Comment>> = commentsCtorArg
        internal set

    /**
     * A stack of grouped sub-expressions, including alternations.
     */
    public var stackGroup: RefCell<List<GroupState>> = stackGroupCtorArg
        internal set

    /**
     * A stack of nested character classes. This is only non-empty when
     * parsing a class.
     */
    public var stackClass: RefCell<List<ClassState>> = stackClassCtorArg
        internal set

    /**
     * A sorted sequence of capture names. This is used to detect duplicate
     * capture names and report an error if one is detected.
     */
    public var captureNames: RefCell<List<CaptureName>> = captureNamesCtorArg
        internal set

    /**
     * A scratch buffer used in various places. Mostly this is used to
     * accumulate relevant characters from parts of a pattern.
     */
    public var scratch: RefCell<String> = scratchCtorArg
        internal set

    /**
     * Parse the regular expression into an abstract syntax tree.
     */
    public open fun parse(pattern: String): Result<Ast> = ParserI.new(this, pattern).parse()

    /**
     * Parse the regular expression and return an abstract syntax tree with
     * all of the comments found in the pattern.
     */
    public open fun parseWithComments(pattern: String): Result<WithComments> = ParserI.new(this, pattern).parseWithComments()

    /**
     * Reset the internal state of a parser.
     *
     * This is called at the beginning of every parse. This prevents the
     * parser from running with inconsistent state (say, if a previous
     * invocation returned an error and the parser is reused).
     */
    public open fun reset() {
        // These settings should be in line with the construction
        // in `ParserBuilder.build`.
        this.pos.set(Position(offset = 0, line = 1, column = 1))
        this.ignoreWhitespace.set(this.initialIgnoreWhitespace)
        this.comments.borrowMut().clear()
        this.stackGroup.borrowMut().clear()
        this.stackClass.borrowMut().clear()
    }

    public companion object {
        public operator fun invoke(
            pos: Cell<Position>,
            captureIndex: Cell<UInt>,
            nestLimit: UInt,
            octal: Boolean,
            initialIgnoreWhitespace: Boolean,
            emptyMinRange: Boolean,
            ignoreWhitespace: Cell<Boolean>,
            comments: RefCell<List<Comment>>,
            stackGroup: RefCell<List<GroupState>>,
            stackClass: RefCell<List<ClassState>>,
            captureNames: RefCell<List<CaptureName>>,
            scratch: RefCell<String>,
        ): Parser = Parser(
            posCtorArg = pos,
            captureIndexCtorArg = captureIndex,
            nestLimit = nestLimit,
            octal = octal,
            initialIgnoreWhitespace = initialIgnoreWhitespace,
            emptyMinRange = emptyMinRange,
            ignoreWhitespaceCtorArg = ignoreWhitespace,
            commentsCtorArg = comments,
            stackGroupCtorArg = stackGroup,
            stackClassCtorArg = stackClass,
            captureNamesCtorArg = captureNames,
            scratchCtorArg = scratch,
        )

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
 *
 * This type also lets us use `ParserI<Parser>` in production code while
 * retaining the convenience of `ParserI<Parser>` for tests, which sometimes
 * work against the internal interface of the parser.
 */
public open class ParserI<P>(
    /**
     * The parser state/configuration.
     */
    public var parser: P,
    /**
     * The full regular expression provided by the user.
     */
    public var pattern: String,
) {
    /**
     * Return a reference to the parser state.
     */
    public open fun parser(): Parser = this.parser.borrow()

    /**
     * Return a reference to the pattern being parsed.
     */
    public open fun pattern(): String = this.pattern

    /**
     * Create a new error with the given span and error type.
     */
    public open fun error(span: Span, kind: ErrorKind): Error = Error(kind = kind, pattern = this.pattern().toString(), span = span)

    /**
     * Return the current offset of the parser.
     *
     * The offset starts at `0` from the beginning of the regular expression
     * pattern string.
     */
    public open fun offset(): Int = this.parser().pos.get().offset

    /**
     * Return the current line number of the parser.
     *
     * The line number starts at `1`.
     */
    public open fun line(): Int = this.parser().pos.get().line

    /**
     * Return the current column of the parser.
     *
     * The column number starts at `1` and is reset whenever a `\n` is seen.
     */
    public open fun column(): Int = this.parser().pos.get().column

    /**
     * Return the next capturing index. Each subsequent call increments the
     * internal index.
     *
     * The span given should correspond to the location of the opening
     * parenthesis.
     *
     * If the capture limit is exceeded, then an error is returned.
     */
    public open fun nextCaptureIndex(span: Span): Result<UInt> {
        val current = this.parser().captureIndex.get()
        val i =
            ((current).toLong() + (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt().okOrElse({
                this.error(span, ErrorKind.CaptureLimitExceeded)
            }).getOrElse { return Result.failure(it) }
        this.parser().captureIndex.set(i)
        return Result.success(i)
    }

    /**
     * Adds the given capture name to this parser. If this capture name has
     * already been used, then an error is returned.
     */
    public open fun addCaptureName(cap: CaptureName): Result<Unit> {
        val names = this.parser().captureNames.borrowMut()
        return when (val matchVal = names.binarySearchByKey(cap.name.asStr(), { c -> c.name.asStr() })) {
            is Err -> {
                val (i) = matchVal
                names.insert(i, cap.clone())
                Result.success(Unit)
            }
            is Ok -> {
                val (i1) = matchVal
                Err(this.error(cap.span, ErrorKind.GroupNameDuplicate(original = names[i1].span)))
            }
        }
    }

    /**
     * Return whether the parser should ignore whitespace or not.
     */
    public open fun ignoreWhitespace(): Boolean = this.parser().ignoreWhitespace.get()

    /**
     * Return the character at the current position of the parser.
     *
     * This panics if the current position does not point to a valid char.
     */
    public open fun char(): Char = this.charAt(this.offset())

    /**
     * Return the character at the given position.
     *
     * This panics if the given position does not point to a valid char.
     */
    public open fun charAt(i: Int): Char = (this.pattern()[i until this.pattern().size].chars().next() ?: error("expected char at offset $i"))

    /**
     * Bump the parser to the next Unicode scalar value.
     *
     * If the end of the input has been reached, then `false` is returned.
     */
    public open fun bump(): Boolean {
        if (this.isEof()) {
            return false
        }
        val structVal = this.pos()
        var offset = structVal.offset
        var line = structVal.line
        var column = structVal.column
        if (this.char() == '\n') {
            line = (((line).toLong() + (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!
            column = 1
        } else {
            column = (((column).toLong() + (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!
        }
        offset += this.char().lenUtf8()
        this.parser().pos.set(Position(offset = offset, line = line, column = column))
        return this.pattern()[this.offset() until this.pattern().size].chars().next() != null
    }

    /**
     * If the substring starting at the current position of the parser has
     * the given prefix, then bump the parser to the character immediately
     * following the prefix and return true. Otherwise, don't bump the parser
     * and return false.
     */
    public open fun bumpIf(prefix: String): Boolean =
        if (this.pattern()[this.offset() until this.pattern().size].startsWith(prefix)) {
            for (unused in 0 until prefix.chars().count()) {
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
    public open fun isLookaroundPrefix(): Boolean = this.bumpIf("?=") || this.bumpIf("?!") || this.bumpIf("?<=") || this.bumpIf("?<!")

    /**
     * Bump the parser, and if the `x` flag is enabled, bump through any
     * subsequent spaces. Return true if and only if the parser is not at
     * EOF.
     */
    public open fun bumpAndBumpSpace(): Boolean {
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
    public open fun bumpSpace() {
        if (!this.ignoreWhitespace()) {
            return
        }
        while (!this.isEof()) {
            if (this.char().isWhitespace()) {
                this.bump()
            } else if (this.char() == '#') {
                val start = this.pos()
                val commentText = String.new()
                this.bump()
                while (!this.isEof()) {
                    val c = this.char()
                    this.bump()
                    if (c == '\n') {
                        break
                    }
                    commentText.push(c)
                }
                val comment = Comment(span = Span.new(start, this.pos()), comment = commentText)
                this.parser().comments.borrowMut().push(comment)
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
    public open fun peek(): Char? {
        if (this.isEof()) {
            return null
        }
        return this.pattern()[this.offset() + this.char().lenUtf8() until this.pattern().size].chars().next()
    }

    /**
     * Like peek, but will ignore spaces when the parser is in whitespace
     * insensitive mode.
     */
    public open fun peekSpace(): Char? {
        if (!this.ignoreWhitespace()) {
            return this.peek()
        }
        if (this.isEof()) {
            return null
        }
        var start = this.offset() + this.char().lenUtf8()
        var inComment = false
        for ((i, c) in this.pattern()[start until this.pattern().size].charIndices()) {
            if (c.isWhitespace()) {
                continue
            } else if (!inComment && c == '#') {
                inComment = true
            } else if (inComment && c == '\n') {
                inComment = false
            } else {
                start += i
                break
            }
        }
        return this.pattern()[start until this.pattern().size].chars().next()
    }

    /**
     * Returns true if the next call to `bump` would return false.
     */
    public open fun isEof(): Boolean = this.offset() == this.pattern().len()

    /**
     * Return the current position of the parser, which includes the offset,
     * line and column.
     */
    public open fun pos(): Position = this.parser().pos.get()

    /**
     * Create a span at the current position of the parser. Both the start
     * and end of the span are set.
     */
    public open fun span(): Span = Span.splat(this.pos())

    /**
     * Create a span that covers the current character.
     */
    public open fun spanChar(): Span {
        val next = Position(offset = (((this.offset()).toLong() + (this.char().lenUtf8()).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!, line = this.line(), column = (((this.column()).toLong() + (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!)
        if (this.char() == '\n') {
            next.line += 1
            next.column = 1
        }
        return Span.new(this.pos(), next)
    }

    /**
     * Parse and push a single alternation on to the parser's internal stack.
     * If the top of the stack already has an alternation, then add to that
     * instead of pushing a new one.
     *
     * The concatenation given corresponds to a single alternation branch.
     * The concatenation returned starts the next branch and is empty.
     *
     * This assumes the parser is currently positioned at `|` and will advance
     * the parser to the character following `|`.
     */
    public open fun pushAlternate(concat: Concat): Result<Concat> {
        check(this.char() == '|')
        concat.span.end = this.pos()
        this.pushOrAddAlternation(concat)
        this.bump()
        return Result.success(Concat(span = this.span(), asts = mutableListOf()))
    }

    /**
     * Pushes or adds the given branch of an alternation to the parser's
     * internal stack of state.
     */
    public open fun pushOrAddAlternation(concat: Concat) {
        val stack = this.parser().stackGroup.borrowMut()
        when (val matchVal = stack.lastMut()) {
            is Some -> {
                val (alts) = matchVal as Alternation
                alts.asts.push(concat.intoAst())
                return
            }
            else -> {}
        }
        stack.push(Alternation(Alternation(span = Span.new(concat.span.start, this.pos()), asts = mutableListOf(concat.intoAst()))))
    }

    /**
     * Parse and push a group AST (and its parent concatenation) on to the
     * parser's internal stack. Return a fresh concatenation corresponding
     * to the group's sub-AST.
     *
     * If a set of flags was found (with no group), then the concatenation
     * is returned with that set of flags added.
     *
     * This assumes that the parser is currently positioned on the opening
     * parenthesis. It advances the parser to the character at the start
     * of the sub-expression (or adjoining expression).
     *
     * If there was a problem parsing the start of the group, then an error
     * is returned.
     */
    public open fun pushGroup(concat: Concat): Result<Concat> {
        check(this.char() == '(')
        return when (val matchVal = this.parseGroup().getOrElse { return Result.failure(it) }) {
            is Either.Left -> {
                val (set) = matchVal
                val ignore = set.flags.flagState(Flag.IgnoreWhitespace)
                if (ignore != null) {
                    val v = ignore
                    this.parser().ignoreWhitespace.set(v)
                }
                concat.asts.push(Ast.flags(set))
                Result.success(concat)
            }
            is Either.Right -> {
                val (group) = matchVal
                val oldIgnoreWhitespace = this.ignoreWhitespace()
                val newIgnoreWhitespace = group.flags().andThen({ f -> f.flagState(Flag.IgnoreWhitespace) }).unwrapOr(oldIgnoreWhitespace)
                this.parser().stackGroup.borrowMut().push(GroupState.Group(concat = concat, group = group, ignoreWhitespace = oldIgnoreWhitespace))
                this.parser().ignoreWhitespace.set(newIgnoreWhitespace)
                Result.success(Concat(span = this.span(), asts = mutableListOf()))
            }
        }
    }

    /**
     * Pop a group AST from the parser's internal stack and set the group's
     * AST to the given concatenation. Return the concatenation containing
     * the group.
     *
     * This assumes that the parser is currently positioned on the closing
     * parenthesis and advances the parser to the character following the `)`.
     *
     * If no such group could be popped, then an unopened group error is
     * returned.
     */
    public open fun popGroup(groupConcat: Concat): Result<Concat> {
        check(this.char() == ')')
        val stack = this.parser().stackGroup.borrowMut()
        var (priorConcat, group2, ignoreWhitespace2, alt1) =
            when (val matchVal = stack.pop()) {
                is Group -> {
                    val concat = (matchVal as Group).concat
                    val group = (matchVal as Group).group
                    val ignoreWhitespace = (matchVal as Group).ignoreWhitespace
                    listOf(concat, group, ignoreWhitespace, null)
                }
                is Alternation -> {
                    val (alt) = matchVal as Alternation
                    when (val matchVal1 = stack.pop()) {
                        is Group -> {
                            val concat1 = (matchVal1 as Group).concat
                            val group1 = (matchVal1 as Group).group
                            val ignoreWhitespace1 = (matchVal1 as Group).ignoreWhitespace
                            listOf(concat1, group1, ignoreWhitespace1, alt)
                        }
                        null -> {
                            return Err(this.error(this.spanChar(), ErrorKind.GroupUnopened))
                        }
                        is Alternation -> {
                            return Err(this.error(this.spanChar(), ErrorKind.GroupUnopened))
                        }
                    }
                }
                null -> {
                    return Err(this.error(this.spanChar(), ErrorKind.GroupUnopened))
                }
            }
        this.parser().ignoreWhitespace.set(ignoreWhitespace2)
        groupConcat.span.end = this.pos()
        this.bump()
        group2.span.end = this.pos()
        when (alt1) {
            null -> {
                group2.ast = groupConcat.intoAst()
            }
            else -> {
                val alt2 = alt1
                alt2.span.end = groupConcat.span.end
                alt2.asts.push(groupConcat.intoAst())
                group2.ast = alt2.intoAst()
            }
        }
        priorConcat.asts.push(Ast.group(group2))
        return Result.success(priorConcat)
    }

    /**
     * Pop the last state from the parser's internal stack, if it exists, and
     * add the given concatenation to it. There either must be no state or a
     * single alternation item on the stack. Any other scenario produces an
     * error.
     *
     * This assumes that the parser has advanced to the end.
     */
    public open fun popGroupEnd(concat: Concat): Result<Ast> {
        concat.span.end = this.pos()
        val stack = this.parser().stackGroup.borrowMut()
        val ast =
            when (val matchVal = stack.pop()) {
                null -> Result.success(concat.intoAst())
                is GroupState.Alternation -> {
                    val (alt) = matchVal as GroupState.Alternation
                    alt.span.end = this.pos()
                    alt.asts.push(concat.intoAst())
                    Result.success(Ast.alternation(alt))
                }
                is GroupState.Group -> {
                    val group = (matchVal as GroupState.Group).group
                    return Err(this.error(group.span, ErrorKind.GroupUnclosed))
                }
            }
        // If we try to pop again, there should be nothing.
        return when (val matchVal1 = stack.pop()) {
            null -> ast
            is GroupState.Alternation -> {
                // This unreachable is unfortunate. This case can't happen
                // because the only way we can be here is if there were two
                // `GroupState.Alternation`s adjacent in the parser's stack,
                // which we guarantee to never happen because we never push a
                // `GroupState.Alternation` if one is already at the top of
                // the stack.
                error("unreachable")
            }
            is GroupState.Group -> {
                val group1 = (matchVal1 as GroupState.Group).group
                Err(this.error(group1.span, ErrorKind.GroupUnclosed))
            }
        }
    }

    /**
     * Parse the opening of a character class and push the current class
     * parsing context onto the parser's stack. This assumes that the parser
     * is positioned at an opening `[`. The given union should correspond to
     * the union of set items built up before seeing the `[`.
     *
     * If there was a problem parsing the opening of the class, then an error
     * is returned. Otherwise, a new union of set items for the class is
     * returned (which may be populated with either a `]` or a `-`).
     */
    public open fun pushClassOpen(parentUnion: ClassSetUnion): Result<ClassSetUnion> {
        check(this.char() == '[')
        val (nestedSet, nestedUnion) = this.parseSetClassOpen().getOrElse { return Result.failure(it) }
        this.parser().stackClass.borrowMut().push(ClassState.Open(union = parentUnion, set = nestedSet))
        return Result.success(nestedUnion)
    }

    /**
     * Parse the end of a character class set and pop the character class
     * parser stack. The union given corresponds to the last union built
     * before seeing the closing `]`. The union returned corresponds to the
     * parent character class set with the nested class added to it.
     *
     * This assumes that the parser is positioned at a `]` and will advance
     * the parser to the byte immediately following the `]`.
     *
     * If the stack is empty after popping, then this returns the final
     * "top-level" character class AST (where a "top-level" character class
     * is one that is not nested inside any other character class).
     *
     * If there is no corresponding opening bracket on the parser's stack,
     * then an error is returned.
     */
    public open fun popClass(nestedUnion: ClassSetUnion): Result<Either<ClassSetUnion, ClassBracketed>> {
        check(this.char() == ']')
        val item = ClassSet.Item(nestedUnion.intoItem())
        val prevset = this.popClassOp(item)
        val stack = this.parser().stackClass.borrowMut()
        return when (val matchVal = stack.pop()) {
            null -> {
                // We can never observe an empty stack:
                //
                // 1) We are guaranteed to start with a non-empty stack since
                // the character class parser is only initiated when it sees
                // a `[`.
                // 2) If we ever observe an empty stack while popping after
                // seeing a `]`, then we signal the character class parser
                // to terminate.
                error("unexpected empty character class stack")
            }
            is ClassState.Op -> {
                // This panic is unfortunate, but this case is impossible
                // since we already popped the Op state if one exists above.
                // Namely, every push to the class parser stack is guarded by
                // whether an existing Op is already on the top of the stack.
                // If it is, the existing Op is modified. That is, the stack
                // can never have consecutive Op states.
                error("unexpected ClassState::Op")
            }
            is ClassState.Open -> {
                val union = (matchVal as ClassState.Open).union
                val set = (matchVal as ClassState.Open).set
                this.bump()
                set.span.end = this.pos()
                set.kind = prevset
                if (stack.isEmpty()) {
                    Result.success(Either.Right(set))
                } else {
                    union.push(ClassSetItem.Bracketed(set))
                    Result.success(Either.Left(union))
                }
            }
        }
    }

    /**
     * Return an "unclosed class" error whose span points to the most
     * recently opened class.
     *
     * This should only be called while parsing a character class.
     */
    public open fun unclosedClassError(): Error {
        for (state in this.parser().stackClass.borrow().iter().rev()) {
            when (val matchVal = state) {
                is ClassState.Open -> {
                    val set = matchVal.set
                    return this.error(set.span, ErrorKind.ClassUnclosed)
                }
                else -> {}
            }
        }
        // We are guaranteed to have a non-empty stack with at least
        // one open bracket, so we should never get here.
        error("no open character class found")
    }

    /**
     * Push the current set of class items on to the class parser's stack as
     * the left hand side of the given operator.
     *
     * A fresh set union is returned, which should be used to build the right
     * hand side of this operator.
     */
    public open fun pushClassOp(nextKind: ClassSetBinaryOpKind, nextUnion: ClassSetUnion): ClassSetUnion {
        val item = ClassSet.Item(nextUnion.intoItem())
        val newLhs = this.popClassOp(item)
        this.parser().stackClass.borrowMut().push(ClassState.Op(kind = nextKind, lhs = newLhs))
        return ClassSetUnion(span = this.span(), items = mutableListOf())
    }

    /**
     * Pop a character class set from the character class parser stack. If the
     * top of the stack is just an item (not an operation), then return the
     * given set unchanged. If the top of the stack is an operation, then the
     * given set will be used as the rhs of the operation on the top of the
     * stack. In that case, the binary operation is returned as a set.
     */
    public open fun popClassOp(rhs: ClassSet): ClassSet {
        val stack = this.parser().stackClass.borrowMut()
        val (kind1, lhs1) =
            when (val matchVal = stack.pop()) {
                is ClassState.Op -> {
                    val kind = (matchVal as ClassState.Op).kind
                    val lhs = (matchVal as ClassState.Op).lhs
                    Pair(kind, lhs)
                }
                is ClassState.Open -> {
                    val state = matchVal
                    stack.push(state)
                    return rhs
                }
                null -> error("unreachable")
            }
        val span = Span.new(lhs1.span().start, rhs.span().end)
        return ClassSet.BinaryOp(ClassSetBinaryOp(span = span, kind = kind1, lhs = lhs1, rhs = rhs))
    }

    /**
     * Parse the regular expression into an abstract syntax tree.
     */
    public open fun parse(): Result<Ast> = this.parseWithComments().map({ astc -> astc.ast })

    /**
     * Parse the regular expression and return an abstract syntax tree with
     * all of the comments found in the pattern.
     */
    public open fun parseWithComments(): Result<WithComments> {
        check(this.offset() == 0)
        this.parser().reset()
        var concat = Concat(span = this.span(), asts = mutableListOf())
        while (true) {
            this.bumpSpace()
            if (this.isEof()) {
                break
            }
            when (this.char()) {
                '(' -> concat = this.pushGroup(concat).getOrElse { return Result.failure(it) }
                ')' -> concat = this.popGroup(concat).getOrElse { return Result.failure(it) }
                '|' -> concat = this.pushAlternate(concat).getOrElse { return Result.failure(it) }
                '[' -> {
                    val cls = this.parseSetClass().getOrElse { return Result.failure(it) }
                    concat.asts.push(Ast.classBracketed(cls))
                }
                '?' -> {
                    concat = this.parseUncountedRepetition(concat, RepetitionKind.ZeroOrOne).getOrElse { return Result.failure(it) }
                }
                '*' -> {
                    concat = this.parseUncountedRepetition(concat, RepetitionKind.ZeroOrMore).getOrElse { return Result.failure(it) }
                }
                '+' -> {
                    concat = this.parseUncountedRepetition(concat, RepetitionKind.OneOrMore).getOrElse { return Result.failure(it) }
                }
                '{' -> {
                    concat = this.parseCountedRepetition(concat).getOrElse { return Result.failure(it) }
                }
                else -> concat.asts.push(this.parsePrimitive().getOrElse { return Result.failure(it) }.intoAst())
            }
        }
        val ast = this.popGroupEnd(concat).getOrElse { return Result.failure(it) }
        NestLimiter.new(this).check(ast).getOrElse { return Result.failure(it) }
        return Result.success(WithComments(ast = ast, comments = mem.replace(this.parser().comments.borrowMut(), mutableListOf())))
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
    public open fun parseUncountedRepetition(concat: Concat, kind: RepetitionKind): Result<Concat> {
        check(this.char() == '?' || this.char() == '*' || this.char() == '+')
        val opStart = this.pos()
        val ast1 =
            when (val matchVal = concat.asts.pop()) {
                null -> {
                    return Err(this.error(this.span(), ErrorKind.RepetitionMissing))
                }
                else -> {
                    val ast = matchVal
                    ast
                }
            }
        when (ast1) {
            is Ast.Empty -> {
                return Err(this.error(this.span(), ErrorKind.RepetitionMissing))
            }
            is Ast.Flags -> {
                return Err(this.error(this.span(), ErrorKind.RepetitionMissing))
            }
            else -> {}
        }
        var greedy = true
        if (this.bump() && this.char() == '?') {
            greedy = false
            this.bump()
        }
        concat.asts.push(Ast.repetition(Repetition(span = ast1.span().withEnd(this.pos()), op = RepetitionOp(span = Span.new(opStart, this.pos()), kind = kind), greedy = greedy, ast = ast1)))
        return Result.success(concat)
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
    public open fun parseCountedRepetition(concat: Concat): Result<Concat> {
        check(this.char() == '{')
        val start = this.pos()
        val ast1 =
            when (val matchVal = concat.asts.pop()) {
                null -> {
                    return Err(this.error(this.span(), ErrorKind.RepetitionMissing))
                }
                else -> {
                    val ast = matchVal
                    ast
                }
            }
        when (ast1) {
            is Ast.Empty -> {
                return Err(this.error(this.span(), ErrorKind.RepetitionMissing))
            }
            is Ast.Flags -> {
                return Err(this.error(this.span(), ErrorKind.RepetitionMissing))
            }
            else -> {}
        }
        if (!this.bumpAndBumpSpace()) {
            return Err(this.error(Span.new(start, this.pos()), ErrorKind.RepetitionCountUnclosed))
        }
        val countStart = specializeErr(this.parseDecimal(), ErrorKind.DecimalEmpty, ErrorKind.RepetitionCountDecimalEmpty)
        if (this.isEof()) {
            return Err(this.error(Span.new(start, this.pos()), ErrorKind.RepetitionCountUnclosed))
        }
        val range =
            if (this.char() == ',') {
                if (!this.bumpAndBumpSpace()) {
                    return Err(this.error(Span.new(start, this.pos()), ErrorKind.RepetitionCountUnclosed))
                }
                if (this.char() != '}') {
                    val countStart1 =
                        when {
                            countStart is Ok -> {
                                val (c) = countStart
                                c
                            }
                            countStart is Err && (countStart as Err).value.kind == ErrorKind.RepetitionCountDecimalEmpty -> {
                                val (err) = countStart
                                if (this.parser().emptyMinRange) {
                                    0
                                } else {
                                    return Err(err)
                                }
                            }
                            else -> {
                                val err1 = countStart
                                err1.getOrElse { return Result.failure(it) }
                            }
                        }
                    val countEnd = specializeErr(this.parseDecimal(), ErrorKind.DecimalEmpty, ErrorKind.RepetitionCountDecimalEmpty).getOrElse { return Result.failure(it) }
                    RepetitionRange.Bounded(countStart1, countEnd)
                } else {
                    RepetitionRange.AtLeast(countStart.getOrElse { return Result.failure(it) })
                }
            } else {
                RepetitionRange.Exactly(countStart.getOrElse { return Result.failure(it) })
            }
        if (this.isEof() || this.char() != '}') {
            return Err(this.error(Span.new(start, this.pos()), ErrorKind.RepetitionCountUnclosed))
        }
        var greedy = true
        if (this.bumpAndBumpSpace() && this.char() == '?') {
            greedy = false
            this.bump()
        }
        val opSpan = Span.new(start, this.pos())
        if (!range.isValid()) {
            return Err(this.error(opSpan, ErrorKind.RepetitionCountInvalid))
        }
        concat.asts.push(Ast.repetition(Repetition(span = ast1.span().withEnd(this.pos()), op = RepetitionOp(span = opSpan, kind = RepetitionKind.Range(range)), greedy = greedy, ast = ast1)))
        return Result.success(concat)
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
    public open fun parseGroup(): Result<Either<SetFlags, Group>> {
        check(this.char() == '(')
        val openSpan = this.spanChar()
        this.bump()
        this.bumpSpace()
        if (this.isLookaroundPrefix()) {
            return Err(this.error(Span.new(openSpan.start, this.span().end), ErrorKind.UnsupportedLookAround))
        }
        val innerSpan = this.span()
        var startsWithP = true
        return if (this.bumpIf("?P<") ||
            run {
                startsWithP = false
                this.bumpIf("?<")
            }) {
            val captureIndex = this.nextCaptureIndex(openSpan).getOrElse { return Result.failure(it) }
            val name = this.parseCaptureName(captureIndex).getOrElse { return Result.failure(it) }
            Result.success(Either.Right(Group(span = openSpan, kind = GroupKind.CaptureName(startsWithP = startsWithP, name = name), ast = Ast.empty(this.span()))))
        } else if (this.bumpIf("?")) {
            if (this.isEof()) {
                return Err(this.error(openSpan, ErrorKind.GroupUnclosed))
            }
            val flags = this.parseFlags().getOrElse { return Result.failure(it) }
            val charEnd = this.char()
            this.bump()
            if (charEnd == ')') {
                // We don't allow empty flags, e.g., `(?)`. We instead
                // interpret it as a repetition operator missing its argument.
                if (flags.items.isEmpty()) {
                    return Err(this.error(innerSpan, ErrorKind.RepetitionMissing))
                }
                Result.success(Either.Left(SetFlags(span = openSpan.copy(end = this.pos()), flags = flags)))
            } else {
                check(charEnd == ':')
                Result.success(Either.Right(Group(span = openSpan, kind = GroupKind.NonCapturing(flags), ast = Ast.empty(this.span()))))
            }
        } else {
            val captureIndex1 = this.nextCaptureIndex(openSpan).getOrElse { return Result.failure(it) }
            Result.success(Either.Right(Group(span = openSpan, kind = GroupKind.CaptureIndex(captureIndex1), ast = Ast.empty(this.span()))))
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
    public open fun parseCaptureName(captureIndex: UInt): Result<CaptureName> {
        if (this.isEof()) {
            return Err(this.error(this.span(), ErrorKind.GroupNameUnexpectedEof))
        }
        val start = this.pos()
        while (true) {
            if (this.char() == '>') {
                break
            }
            if (!isCaptureChar(this.char(), this.pos() == start)) {
                return Err(this.error(this.spanChar(), ErrorKind.GroupNameInvalid))
            }
            if (!this.bump()) {
                break
            }
        }
        val end = this.pos()
        if (this.isEof()) {
            return Err(this.error(this.span(), ErrorKind.GroupNameUnexpectedEof))
        }
        check(this.char() == '>')
        this.bump()
        val name = this.pattern()[start.offset until end.offset]
        if (name.isEmpty()) {
            return Err(this.error(Span.new(start, start), ErrorKind.GroupNameEmpty))
        }
        val capname = CaptureName(span = Span.new(start, end), name = name.toString(), index = captureIndex)
        this.addCaptureName(capname).getOrElse { return Result.failure(it) }
        return Result.success(capname)
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
    public open fun parseFlags(): Result<Flags> {
        val flags = Flags(span = this.span(), items = mutableListOf())
        var lastWasNegation = null
        while (this.char() != ':' && this.char() != ')') {
            if (this.char() == '-') {
                lastWasNegation = this.spanChar()
                val item = FlagsItem(span = this.spanChar(), kind = FlagsItemKind.Negation)
                when (val matchVal = flags.addItem(item)) {
                    null -> {}
                    else -> {
                        val i = matchVal
                        return Err(this.error(this.spanChar(), ErrorKind.FlagRepeatedNegation(original = flags.items[i].span)))
                    }
                }
            } else {
                lastWasNegation = null
                val item1 = FlagsItem(span = this.spanChar(), kind = FlagsItemKind.Flag(this.parseFlag().getOrElse { return Result.failure(it) }))
                when (val matchVal1 = flags.addItem(item1)) {
                    null -> {}
                    else -> {
                        val i1 = matchVal1
                        return Err(this.error(this.spanChar(), ErrorKind.FlagDuplicate(original = flags.items[i1].span)))
                    }
                }
            }
            if (!this.bump()) {
                return Err(this.error(this.span(), ErrorKind.FlagUnexpectedEof))
            }
        }
        if (lastWasNegation != null) {
            val span = lastWasNegation
            return Err(this.error(span, ErrorKind.FlagDanglingNegation))
        }
        flags.span.end = this.pos()
        return Result.success(flags)
    }

    /**
     * Parse the current character as a flag. Do not advance the parser.
     *
     * # Errors
     *
     * If the flag is not recognized, then an error is returned.
     */
    public open fun parseFlag(): Result<Flag> =
        when (this.char()) {
            'i' -> Result.success(Flag.CaseInsensitive)
            'm' -> Result.success(Flag.MultiLine)
            's' -> Result.success(Flag.DotMatchesNewLine)
            'U' -> Result.success(Flag.SwapGreed)
            'u' -> Result.success(Flag.Unicode)
            'R' -> Result.success(Flag.CRLF)
            'x' -> Result.success(Flag.IgnoreWhitespace)
            else -> {
                Err(this.error(this.spanChar(), ErrorKind.FlagUnrecognized))
            }
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
    public open fun parsePrimitive(): Result<Primitive> =
        when (val matchVal = this.char()) {
            '\\' -> this.parseEscape()
            '.' -> {
                val ast = Primitive.Dot(this.spanChar())
                this.bump()
                Result.success(ast)
            }
            '^' -> {
                val ast1 = Primitive.Assertion(Assertion(span = this.spanChar(), kind = AssertionKind.StartLine))
                this.bump()
                Result.success(ast1)
            }
            '$' -> {
                val ast2 = Primitive.Assertion(Assertion(span = this.spanChar(), kind = AssertionKind.EndLine))
                this.bump()
                Result.success(ast2)
            }
            else -> {
                val c = matchVal
                val ast3 = Primitive.Literal(Literal(span = this.spanChar(), kind = LiteralKind.Verbatim, c = c))
                this.bump()
                Result.success(ast3)
            }
        }

    /**
     * Parse an escape sequence as a primitive AST.
     *
     * This assumes the parser is positioned at the start of the escape
     * sequence, i.e., `\`. It advances the parser to the first position
     * immediately following the escape sequence.
     */
    public open fun parseEscape(): Result<Primitive> {
        check(this.char() == '\\')
        val start = this.pos()
        if (!this.bump()) {
            return Err(this.error(Span.new(start, this.pos()), ErrorKind.EscapeUnexpectedEof))
        }
        val c = this.char()
        // Put some of the more complicated routines into helpers.
        when {
            c in '0'..'7' -> {
                if (!this.parser().octal) {
                    return Err(this.error(Span.new(start, this.spanChar().end), ErrorKind.UnsupportedBackreference))
                }
                val lit = this.parseOctal()
                lit.span.start = start
                return Result.success(Primitive.Literal(lit))
            }
            c in '8'..'9' && !this.parser().octal -> {
                return Err(this.error(Span.new(start, this.spanChar().end), ErrorKind.UnsupportedBackreference))
            }
            c == 'x' || c == 'u' || c == 'U' -> {
                val lit1 = this.parseHex().getOrElse { return Result.failure(it) }
                lit1.span.start = start
                return Result.success(Primitive.Literal(lit1))
            }
            c == 'p' || c == 'P' -> {
                val cls = this.parseUnicodeClass().getOrElse { return Result.failure(it) }
                cls.span.start = start
                return Result.success(Primitive.Unicode(cls))
            }
            c == 'd' || c == 's' || c == 'w' || c == 'D' || c == 'S' || c == 'W' -> {
                val cls1 = this.parsePerlClass()
                cls1.span.start = start
                return Result.success(Primitive.Perl(cls1))
            }
            else -> {}
        }
        // Handle all of the one letter sequences inline.
        this.bump()
        val span = Span.new(start, this.pos())
        if (isMetaCharacter(c)) {
            return Result.success(Primitive.Literal(Literal(span = span, kind = LiteralKind.Meta, c = c)))
        }
        if (isEscapeableCharacter(c)) {
            return Result.success(Primitive.Literal(Literal(span = span, kind = LiteralKind.Superfluous, c = c)))
        }
        val special =
            { kind, c1 ->
                Result.success(Primitive.Literal(Literal(span = span, kind = LiteralKind.Special(kind), c = c1)))
            }
        return when (c) {
            'a' -> special(SpecialLiteralKind.Bell, '\u0007')
            'f' -> special(SpecialLiteralKind.FormFeed, '\u000c')
            't' -> special(SpecialLiteralKind.Tab, '\t')
            'n' -> special(SpecialLiteralKind.LineFeed, '\n')
            'r' -> special(SpecialLiteralKind.CarriageReturn, '\r')
            'v' -> special(SpecialLiteralKind.VerticalTab, '\u000b')
            'A' -> Result.success(Primitive.Assertion(Assertion(span = span, kind = AssertionKind.StartText)))
            'z' -> Result.success(Primitive.Assertion(Assertion(span = span, kind = AssertionKind.EndText)))
            'b' -> {
                val wb = Assertion(span = span, kind = AssertionKind.WordBoundary)
                // After a \b, we "try" to parse things like \b{start} for
                // special word boundary assertions.
                if (!this.isEof() && this.char() == '{') {
                    when (val matchVal = this.maybeParseSpecialWordBoundary(start).getOrElse { return Result.failure(it) }) {
                        null -> {}
                        else -> {
                            val kind = matchVal
                            wb.kind = kind
                            wb.span.end = this.pos()
                        }
                    }
                }
                Result.success(Primitive.Assertion(wb))
            }
            'B' -> Result.success(Primitive.Assertion(Assertion(span = span, kind = AssertionKind.NotWordBoundary)))
            '<' -> Result.success(Primitive.Assertion(Assertion(span = span, kind = AssertionKind.WordBoundaryStartAngle)))
            '>' -> Result.success(Primitive.Assertion(Assertion(span = span, kind = AssertionKind.WordBoundaryEndAngle)))
            else -> Err(this.error(span, ErrorKind.EscapeUnrecognized))
        }
    }

    /**
     * Attempt to parse a specialty word boundary. That is, `\b{start}`,
     * `\b{end}`, `\b{start-half}` or `\b{end-half}`.
     *
     * This is similar to `maybeParseAsciiClass` in that, in most cases,
     * if it fails it will just return `None` with no error. This is done
     * because `\b{5}` is a valid expression and we want to let that be parsed
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
    public open fun maybeParseSpecialWordBoundary(wbStart: Position): Result<AssertionKind?> {
        check(this.char() == '{')
        val isValidChar =
            { c -> when (c) {
                in 'A'..'Z', in 'a'..'z', '-' -> true
                else -> false
            } }
        val start = this.pos()
        if (!this.bumpAndBumpSpace()) {
            return Err(this.error(Span.new(wbStart, this.pos()), ErrorKind.SpecialWordOrRepetitionUnexpectedEof))
        }
        val startContents = this.pos()
        // This is one of the critical bits: if the first non-whitespace
        // character isn't in [-A-Za-z] (i.e., this can't be a special word
        // boundary), then we bail and let the counted repetition parser deal
        // with this.
        if (!isValidChar(this.char())) {
            this.parser().pos.set(start)
            return Result.success(null)
        }
        // Now collect up our chars until we see a '}'.
        val scratch = this.parser().scratch.borrowMut()
        scratch.clear()
        while (!this.isEof() && isValidChar(this.char())) {
            scratch.push(this.char())
            this.bumpAndBumpSpace()
        }
        if (this.isEof() || this.char() != '}') {
            return Err(this.error(Span.new(start, this.pos()), ErrorKind.SpecialWordBoundaryUnclosed))
        }
        val end = this.pos()
        this.bump()
        val kind =
            when (scratch.asStr()) {
                "start" -> AssertionKind.WordBoundaryStart
                "end" -> AssertionKind.WordBoundaryEnd
                "start-half" -> AssertionKind.WordBoundaryStartHalf
                "end-half" -> AssertionKind.WordBoundaryEndHalf
                else -> {
                    return Err(this.error(Span.new(startContents, end), ErrorKind.SpecialWordBoundaryUnrecognized))
                }
            }
        return Result.success(kind)
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
    public open fun parseOctal(): Literal {
        check(this.parser().octal)
        check('0' <= this.char() && this.char() <= '7')
        val start = this.pos()
        // Parse up to two more digits.
        while (this.bump() && '0' <= this.char() && this.char() <= '7' && this.pos().offset - start.offset <= 2) {}
        val end = this.pos()
        val octal = this.pattern()[start.offset until end.offset]
        // Parsing the octal should never fail since the above guarantees a
        // valid number.
        val codepoint = UInt.fromStrRadix(octal, 8).expect("valid octal number")
        // The max value for 3 digit octal is 0777 = 511 and [0, 511] has no
        // invalid Unicode scalar values.
        val c = Char.fromU32(codepoint).expect("Unicode scalar value")
        return Literal(span = Span.new(start, end), kind = LiteralKind.Octal, c = c)
    }

    /**
     * Parse a hex representation of a Unicode codepoint. This handles both
     * hex notations, i.e., `\xFF` and `\x{FFFF}`. This expects the parser to
     * be positioned at the `x`, `u` or `U` prefix. The parser is advanced to
     * the first character immediately following the hexadecimal literal.
     */
    public open fun parseHex(): Result<Literal> {
        check(this.char() == 'x' || this.char() == 'u' || this.char() == 'U')
        val hexKind =
            when (this.char()) {
                'x' -> HexLiteralKind.X
                'u' -> HexLiteralKind.UnicodeShort
                else -> HexLiteralKind.UnicodeLong
            }
        if (!this.bumpAndBumpSpace()) {
            return Err(this.error(this.span(), ErrorKind.EscapeUnexpectedEof))
        }
        return if (this.char() == '{') {
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
    public open fun parseHexDigits(kind: HexLiteralKind): Result<Literal> {
        val scratch = this.parser().scratch.borrowMut()
        scratch.clear()
        val start = this.pos()
        for (i in 0 until kind.digits()) {
            if (i > 0 && !this.bumpAndBumpSpace()) {
                return Err(this.error(this.span(), ErrorKind.EscapeUnexpectedEof))
            }
            if (!isHex(this.char())) {
                return Err(this.error(this.spanChar(), ErrorKind.EscapeHexInvalidDigit))
            }
            scratch.push(this.char())
        }
        // The final bump just moves the parser past the literal, which may
        // be EOF.
        this.bumpAndBumpSpace()
        val end = this.pos()
        val hex = scratch.asStr()
        return when (val matchVal = UInt.fromStrRadix(hex, 16).ok().andThen(Char::fromU32)) {
            null -> Err(this.error(Span.new(start, end), ErrorKind.EscapeHexInvalid))
            else -> {
                val c = matchVal
                Result.success(Literal(span = Span.new(start, end), kind = LiteralKind.HexFixed(kind), c = c))
            }
        }
    }

    /**
     * Parse a hex representation of any Unicode scalar value. This expects
     * the parser to be positioned at the opening brace `{` and will advance
     * the parser to the first character following the closing brace `}`.
     */
    public open fun parseHexBrace(kind: HexLiteralKind): Result<Literal> {
        val scratch = this.parser().scratch.borrowMut()
        scratch.clear()
        val bracePos = this.pos()
        val start = this.spanChar().end
        while (this.bumpAndBumpSpace() && this.char() != '}') {
            if (!isHex(this.char())) {
                return Err(this.error(this.spanChar(), ErrorKind.EscapeHexInvalidDigit))
            }
            scratch.push(this.char())
        }
        if (this.isEof()) {
            return Err(this.error(Span.new(bracePos, this.pos()), ErrorKind.EscapeUnexpectedEof))
        }
        val end = this.pos()
        val hex = scratch.asStr()
        check(this.char() == '}')
        this.bumpAndBumpSpace()
        if (hex.isEmpty()) {
            return Err(this.error(Span.new(bracePos, this.pos()), ErrorKind.EscapeHexEmpty))
        }
        return when (val matchVal = UInt.fromStrRadix(hex, 16).ok().andThen(Char::fromU32)) {
            null -> Err(this.error(Span.new(start, end), ErrorKind.EscapeHexInvalid))
            else -> {
                val c = matchVal
                Result.success(Literal(span = Span.new(start, this.pos()), kind = LiteralKind.HexBrace(kind), c = c))
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
    public open fun parseDecimal(): Result<UInt> {
        val scratch = this.parser().scratch.borrowMut()
        scratch.clear()
        while (!this.isEof() && this.char().isWhitespace()) {
            this.bump()
        }
        val start = this.pos()
        while (!this.isEof() && '0' <= this.char() && this.char() <= '9') {
            scratch.push(this.char())
            this.bumpAndBumpSpace()
        }
        val span = Span.new(start, this.pos())
        while (!this.isEof() && this.char().isWhitespace()) {
            this.bumpAndBumpSpace()
        }
        val digits = scratch.asStr()
        if (digits.isEmpty()) {
            return Err(this.error(span, ErrorKind.DecimalEmpty))
        }
        return when (val matchVal = UInt.fromStrRadix(digits, 10).ok()) {
            null -> Err(this.error(span, ErrorKind.DecimalInvalid))
            else -> {
                val n = matchVal
                Result.success(n)
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
    public open fun parseSetClass(): Result<ClassBracketed> {
        check(this.char() == '[')
        var union = ClassSetUnion(span = this.span(), items = mutableListOf())
        while (true) {
            this.bumpSpace()
            if (this.isEof()) {
                return Err(this.unclosedClassError())
            }
            run {
                val matchVal = this.char()
                when {
                    matchVal == '[' -> {
                        // If we've already parsed the opening bracket, then
                        // attempt to treat this as the beginning of an ASCII
                        // class. If ASCII class parsing fails, then the parser
                        // backs up to `[`.
                        if (!this.parser().stackClass.borrow().isEmpty()) {
                            when (val matchVal1 = this.maybeParseAsciiClass()) {
                                null -> {}
                                else -> {
                                    val cls = matchVal1
                                    union.push(ClassSetItem.Ascii(cls))
                                    continue
                                }
                            }
                        }
                        union = this.pushClassOpen(union).getOrElse { return Result.failure(it) }
                    }
                    matchVal == ']' -> when (val matchVal2 = this.popClass(union).getOrElse { return Result.failure(it) }) {
                        is Either.Left -> {
                            val (nestedUnion) = matchVal2
                            union = nestedUnion
                        }
                        is Either.Right -> {
                            val (cls) = matchVal2
                            return Result.success(cls)
                        }
                    }
                    matchVal == '&' && this.peek() == '&' -> {
                        check(this.bumpIf("&&"))
                        union = this.pushClassOp(ClassSetBinaryOpKind.Intersection, union)
                    }
                    matchVal == '-' && this.peek() == '-' -> {
                        check(this.bumpIf("--"))
                        union = this.pushClassOp(ClassSetBinaryOpKind.Difference, union)
                    }
                    matchVal == '~' && this.peek() == '~' -> {
                        check(this.bumpIf("~~"))
                        union = this.pushClassOp(ClassSetBinaryOpKind.SymmetricDifference, union)
                    }
                    else -> {
                        union.push(this.parseSetClassRange().getOrElse { return Result.failure(it) })
                    }
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
    public open fun parseSetClassRange(): Result<ClassSetItem> {
        val prim1 = this.parseSetClassItem().getOrElse { return Result.failure(it) }
        this.bumpSpace()
        if (this.isEof()) {
            return Err(this.unclosedClassError())
        }
        // If the next char isn't a `-`, then we don't have a range.
        // There are two exceptions. If the char after a `-` is a `]`, then
        // `-` is interpreted as a literal `-`. Alternatively, if the char
        // after a `-` is a `-`, then `- -` corresponds to a "difference"
        // operation.
        if (this.char() != '-' || this.peekSpace() == ']' || this.peekSpace() == '-') {
            return prim1.intoClassSetItem(this)
        }
        // OK, now we're parsing a range, so bump past the `-` and parse the
        // second half of the range.
        if (!this.bumpAndBumpSpace()) {
            return Err(this.unclosedClassError())
        }
        val prim2 = this.parseSetClassItem().getOrElse { return Result.failure(it) }
        val range = ClassSetRange(span = Span.new(prim1.span().start, prim2.span().end), start = prim1.intoClassLiteral(this).getOrElse { return Result.failure(it) }, end = prim2.intoClassLiteral(this).getOrElse { return Result.failure(it) })
        if (!range.isValid()) {
            return Err(this.error(range.span, ErrorKind.ClassRangeInvalid))
        }
        return Result.success(ClassSetItem.Range(range))
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
    public open fun parseSetClassItem(): Result<Primitive> =
        if (this.char() == '\\') {
            this.parseEscape()
        } else {
            val x = Primitive.Literal(Literal(span = this.spanChar(), kind = LiteralKind.Verbatim, c = this.char()))
            this.bump()
            Result.success(x)
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
    public open fun parseSetClassOpen(): Result<Pair<ClassBracketed, ClassSetUnion>> {
        check(this.char() == '[')
        val start = this.pos()
        if (!this.bumpAndBumpSpace()) {
            return Err(this.error(Span.new(start, this.pos()), ErrorKind.ClassUnclosed))
        }
        val negated =
            if (this.char() != '^') {
                false
            } else {
                if (!this.bumpAndBumpSpace()) {
                    return Err(this.error(Span.new(start, this.pos()), ErrorKind.ClassUnclosed))
                }
                true
            }
        // Accept any number of `-` as literal `-`.
        val union = ClassSetUnion(span = this.span(), items = mutableListOf())
        while (this.char() == '-') {
            union.push(ClassSetItem.Literal(Literal(span = this.spanChar(), kind = LiteralKind.Verbatim, c = '-')))
            if (!this.bumpAndBumpSpace()) {
                return Err(this.error(Span.new(start, start), ErrorKind.ClassUnclosed))
            }
        }
        // If `]` is the *first* char in a set, then interpret it as a literal
        // `]`. That is, an empty class is impossible to write.
        if (union.items.isEmpty() && this.char() == ']') {
            union.push(ClassSetItem.Literal(Literal(span = this.spanChar(), kind = LiteralKind.Verbatim, c = ']')))
            if (!this.bumpAndBumpSpace()) {
                return Err(this.error(Span.new(start, this.pos()), ErrorKind.ClassUnclosed))
            }
        }
        val set = ClassBracketed(span = Span.new(start, this.pos()), negated = negated, kind = ClassSet.union(ClassSetUnion(span = Span.new(union.span.start, union.span.start), items = mutableListOf())))
        return Result.success(Pair(set, union))
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
    public open fun maybeParseAsciiClass(): ClassAscii? {
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
        check(this.char() == '[')
        // If parsing fails, then we back up the parser to this starting point.
        val start = this.pos()
        var negated = false
        if (!this.bump() || this.char() != ':') {
            this.parser().pos.set(start)
            return null
        }
        if (!this.bump()) {
            this.parser().pos.set(start)
            return null
        }
        if (this.char() == '^') {
            negated = true
            if (!this.bump()) {
                this.parser().pos.set(start)
                return null
            }
        }
        val nameStart = this.offset()
        while (this.char() != ':' && this.bump()) {}
        if (this.isEof()) {
            this.parser().pos.set(start)
            return null
        }
        val name = this.pattern()[nameStart until this.offset()]
        if (!this.bumpIf(":]")) {
            this.parser().pos.set(start)
            return null
        }
        val kind1 =
            when (val matchVal = ClassAsciiKind.fromName(name)) {
                null -> {
                    this.parser().pos.set(start)
                    return null
                }
                else -> {
                    val kind = matchVal
                    kind
                }
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
    public open fun parseUnicodeClass(): Result<ClassUnicode> {
        check(this.char() == 'p' || this.char() == 'P')
        val scratch = this.parser().scratch.borrowMut()
        scratch.clear()
        val negated = this.char() == 'P'
        if (!this.bumpAndBumpSpace()) {
            return Err(this.error(this.span(), ErrorKind.EscapeUnexpectedEof))
        }
        val (start2, kind1) =
            if (this.char() == '{') {
                val start = this.spanChar().end
                while (this.bumpAndBumpSpace() && this.char() != '}') {
                    scratch.push(this.char())
                }
                if (this.isEof()) {
                    return Err(this.error(this.span(), ErrorKind.EscapeUnexpectedEof))
                }
                check(this.char() == '}')
                this.bump()
                val name = scratch.asStr()
                when (val matchVal2 = name.indexOf("!=").takeIf { it >= 0 }) {
                    null -> {
                        when (val matchVal1 = name.indexOf(':').takeIf { it >= 0 }) {
                            null -> {
                                when (val matchVal = name.indexOf('=').takeIf { it >= 0 }) {
                                    null -> {
                                        Pair(start, ClassUnicodeKind.Named(name.toString()))
                                    }
                                    else -> {
                                        val i = matchVal
                                        Pair(start, ClassUnicodeKind.NamedValue(op = ClassUnicodeOpKind.Equal, name = name[0 until i].toString(), value = name[i + 1 until name.size].toString()))
                                    }
                                }
                            }
                            else -> {
                                val i1 = matchVal1
                                Pair(start, ClassUnicodeKind.NamedValue(op = ClassUnicodeOpKind.Colon, name = name[0 until i1].toString(), value = name[i1 + 1 until name.size].toString()))
                            }
                        }
                    }
                    else -> {
                        val i2 = matchVal2
                        Pair(start, ClassUnicodeKind.NamedValue(op = ClassUnicodeOpKind.NotEqual, name = name[0 until i2].toString(), value = name[i2 + 2 until name.size].toString()))
                    }
                }
            } else {
                val start1 = this.pos()
                val c = this.char()
                if (c == '\\') {
                    return Err(this.error(this.spanChar(), ErrorKind.UnicodeClassInvalid))
                }
                this.bumpAndBumpSpace()
                val kind = ClassUnicodeKind.OneLetter(c)
                Pair(start1, kind)
            }
        return Result.success(ClassUnicode(span = Span.new(start2, this.pos()), negated = negated, kind = kind1))
    }

    /**
     * Parse a Perl character class, e.g., `\d` or `\W`. This assumes the
     * parser is currently at a valid character class name and will be
     * advanced to the character immediately following the class.
     */
    public open fun parsePerlClass(): ClassPerl {
        val c = this.char()
        val span = this.spanChar()
        this.bump()
        val (negated, kind) =
            when (c) {
                'd' -> Pair(false, ClassPerlKind.Digit)
                'D' -> Pair(true, ClassPerlKind.Digit)
                's' -> Pair(false, ClassPerlKind.Space)
                'S' -> Pair(true, ClassPerlKind.Space)
                'w' -> Pair(false, ClassPerlKind.Word)
                'W' -> Pair(true, ClassPerlKind.Word)
                else -> {
                    val c1 = c
                    error("expected valid Perl class but got '$c'")
                }
            }
        return ClassPerl(span = span, kind = kind, negated = negated)
    }

    public companion object {
        /**
         * Build an internal parser from a parser configuration and a pattern.
         */
        public fun <P> new(parser: P, pattern: String): ParserI<P> = ParserI(parser = parser, pattern = pattern)
    }
}

/**
 * GroupState represents a single stack frame while parsing nested groups
 * and alternations. Each frame records the state up to an opening parenthesis
 * or a alternating bracket `|`.
 */
public sealed class GroupState {
    /**
     * This state is pushed whenever an opening group is found.
     */
    public data class Group(public var concat: Concat, public var group: Group, public var ignoreWhitespace: Boolean) : GroupState()
    /**
     * This state is pushed whenever a new alternation branch is found. If
     * an alternation branch is found and this state is at the top of the
     * stack, then this state should be modified to include the new
     * alternation.
     */
    public data class Alternation(public var value: Alternation) : GroupState()
}

/**
 * ClassState represents a single stack frame while parsing character classes.
 * Each frame records the state up to an intersection, difference, symmetric
 * difference or nested class.
 *
 * Note that a parser's character class stack is only non-empty when parsing
 * a character class. In all other cases, it is empty.
 */
public sealed class ClassState {
    /**
     * This state is pushed whenever an opening bracket is found.
     */
    public data class Open(public var union: ClassSetUnion, public var set: ClassBracketed) : ClassState()
    /**
     * This state is pushed when a operator is seen. When popped, the stored
     * set becomes the left hand side of the operator.
     */
    public data class Op(public var kind: ClassSetBinaryOpKind, public var lhs: ClassSet) : ClassState()
}

/**
 * A type that traverses a fully parsed Ast and checks whether its depth
 * exceeds the specified nesting limit. If it does, then an error is returned.
 */
public open class NestLimiter<P>(
    /**
     * The parser that is checking the nest limit.
     */
    public var p: ParserI<P>,
    /**
     * The current depth while walking an Ast.
     */
    public var depth: UInt,
) : Visitor {
    public open fun check(ast: Ast): Result<Unit> = visit(ast, this)

    public open fun incrementDepth(span: Span): Result<Unit> {
        val new =
            ((this.depth).toLong() + (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt().okOrElse({
                this.p.error(span.clone(), ErrorKind.NestLimitExceeded(UInt.MAX_VALUE))
            }).getOrElse { return Result.failure(it) }
        val limit = this.p.parser().nestLimit
        if (new > limit) {
            return Err(this.p.error(span.clone(), ErrorKind.NestLimitExceeded(limit)))
        }
        this.depth = new
        return Result.success(Unit)
    }

    public open fun decrementDepth() {
        // Assuming the correctness of the visitor, this should never drop
        // below 0.
        this.depth = (((this.depth).toLong() - (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!
    }

    public open fun finish(): Result<Unit> = Result.success(Unit)

    public open fun visitPre(ast: Ast): Result<Unit> {
        val span =
            when (ast) {
                is Ast.Empty -> {
                    // These are all base cases, so we don't increment depth.
                    return Result.success(Unit)
                }
                is Ast.Flags -> {
                    return Result.success(Unit)
                }
                is Ast.Literal -> {
                    return Result.success(Unit)
                }
                is Ast.Dot -> {
                    return Result.success(Unit)
                }
                is Ast.Assertion -> {
                    return Result.success(Unit)
                }
                is Ast.ClassUnicode -> {
                    return Result.success(Unit)
                }
                is Ast.ClassPerl -> {
                    return Result.success(Unit)
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

    public open fun visitPost(ast: Ast): Result<Unit> =
        when (ast) {
            is Ast.Empty -> {
                // These are all base cases, so we don't decrement depth.
                Result.success(Unit)
            }
            is Ast.Flags -> {
                Result.success(Unit)
            }
            is Ast.Literal -> {
                Result.success(Unit)
            }
            is Ast.Dot -> {
                Result.success(Unit)
            }
            is Ast.Assertion -> {
                Result.success(Unit)
            }
            is Ast.ClassUnicode -> {
                Result.success(Unit)
            }
            is Ast.ClassPerl -> {
                Result.success(Unit)
            }
            is Ast.ClassBracketed -> {
                this.decrementDepth()
                Result.success(Unit)
            }
            is Ast.Repetition -> {
                this.decrementDepth()
                Result.success(Unit)
            }
            is Ast.Group -> {
                this.decrementDepth()
                Result.success(Unit)
            }
            is Ast.Alternation -> {
                this.decrementDepth()
                Result.success(Unit)
            }
            is Ast.Concat -> {
                this.decrementDepth()
                Result.success(Unit)
            }
        }

    public open fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit> {
        val span =
            when (ast) {
                is ClassSetItem.Empty -> {
                    // These are all base cases, so we don't increment depth.
                    return Result.success(Unit)
                }
                is ClassSetItem.Literal -> {
                    return Result.success(Unit)
                }
                is ClassSetItem.Range -> {
                    return Result.success(Unit)
                }
                is ClassSetItem.Ascii -> {
                    return Result.success(Unit)
                }
                is ClassSetItem.Unicode -> {
                    return Result.success(Unit)
                }
                is ClassSetItem.Perl -> {
                    return Result.success(Unit)
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

    public open fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit> =
        when (ast) {
            is ClassSetItem.Empty -> {
                // These are all base cases, so we don't decrement depth.
                Result.success(Unit)
            }
            is ClassSetItem.Literal -> {
                Result.success(Unit)
            }
            is ClassSetItem.Range -> {
                Result.success(Unit)
            }
            is ClassSetItem.Ascii -> {
                Result.success(Unit)
            }
            is ClassSetItem.Unicode -> {
                Result.success(Unit)
            }
            is ClassSetItem.Perl -> {
                Result.success(Unit)
            }
            is ClassSetItem.Bracketed -> {
                this.decrementDepth()
                Result.success(Unit)
            }
            is ClassSetItem.Union -> {
                this.decrementDepth()
                Result.success(Unit)
            }
        }

    public open fun visitClassSetBinaryOpPre(ast: ClassSetBinaryOp): Result<Unit> = this.incrementDepth(ast.span)

    public open fun visitClassSetBinaryOpPost(ast: ClassSetBinaryOp): Result<Unit> {
        this.decrementDepth()
        return Result.success(Unit)
    }

    public companion object {
        public fun <P> new(p: ParserI<P>): NestLimiter<P> = NestLimiter(p = p, depth = 0u)
    }
}

/**
 * When the result is an error, transforms the ast.ErrorKind from the source
 * Result into another one. This function is used to return clearer error
 * messages when possible.
 */
public fun <T> specializeErr(result: Result<T>, from: ErrorKind, to: ErrorKind): Result<T> =
    if (result is Err) {
        val (e) = result
        if (e.kind == from) {
            Err(Error(kind = to, pattern = e.pattern, span = e.span))
        } else {
            Err(e)
        }
    } else {
        result
    }
