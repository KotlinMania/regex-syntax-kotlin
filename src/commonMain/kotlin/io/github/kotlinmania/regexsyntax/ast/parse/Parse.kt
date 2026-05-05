// port-lint: source src/ast/parse.rs
package io.github.kotlinmania.regexsyntax.ast.parse

/*
 * Copyright (c) The rust-lang regex contributors.
 * Licensed under either of Apache-2.0 OR MIT.
 */

import io.github.kotlinmania.regexsyntax.ast.*
import io.github.kotlinmania.regexsyntax.ast.Error as AstError
import io.github.kotlinmania.regexsyntax.ast.visitor.Visitor
import io.github.kotlinmania.regexsyntax.ast.visitor.visit

/**
 * A builder for configuring an abstract syntax tree parser.
 *
 * This builder permits modifying configuration options for the parser.
 */
class ParserBuilder {
    companion object {
        /** Create a new parser builder with a default configuration. */
        fun new(): ParserBuilder = ParserBuilder()
    }

    private var nestLimit: UInt = 250u
    private var octal: Boolean = false
    private var emptyMinRange: Boolean = false
    private var ignoreWhitespace: Boolean = false

    /**
     * Create a new builder with default settings.
     */
    constructor()

    /**
     * Build a new parser from the current configuration.
     */
    fun build(): Parser = Parser(
        nestLimit = nestLimit,
        octal = octal,
        emptyMinRange = emptyMinRange,
        initialIgnoreWhitespace = ignoreWhitespace,
    )

    /**
     * Set the nesting limit for this parser.
     *
     * The nesting limit controls how deep the abstract syntax tree is allowed
     * to be. If the limit is exceeded, then an error is returned.
     *
     * The limit is intended to prevent stack overflow when walking an AST.
     * Since the visitor for an AST uses recursion, a very deep AST can cause
     * a stack overflow.
     *
     * Note that a nest limit of `0` will refuse to parse any expression
     * that has any nesting. For example, `a` would be allowed, but `a+` or
     * `(a)` would not.
     *
     * The default limit is `250`.
     */
    fun nestLimit(limit: UInt): ParserBuilder {
        nestLimit = limit
        return this
    }

    /**
     * Whether to support octal syntax or not.
     *
     * When enabled, the parser will support three digit octal literals of the
     * form `\ooo`. (Octal literals of the form `\0oo` are always supported.)
     *
     * When disabled, the parser will treat `\1` through `\9` as backreferences
     * (which are currently not supported by this parser).
     *
     * For more information on octal syntax, see the
     * [documentation for `LiteralKind::Octal`](io.github.kotlinmania.regexsyntax.ast.LiteralKind.Octal).
     *
     * Octal syntax is disabled by default.
     */
    fun octal(yes: Boolean): ParserBuilder {
        octal = yes
        return this
    }

    /**
     * Whether to support empty min ranges in counted repetitions.
     *
     * When enabled, the parser will support empty min ranges in counted
     * repetitions. For example, `a{,5}` is equivalent to `a{0,5}`.
     *
     * When disabled, the parser will return an error when it encounters an
     * empty min range.
     *
     * Empty min ranges are disabled by default.
     */
    fun emptyMinRange(yes: Boolean): ParserBuilder {
        emptyMinRange = yes
        return this
    }

    /**
     * Whether to ignore whitespace in the regular expression.
     *
     * When enabled, whitespace characters in the regular expression are
     * ignored. This is equivalent to the `x` flag being enabled by default.
     *
     * Whitespace is ignored by default when the `x` flag is enabled. This
     * setting allows ignoring whitespace even when the `x` flag is disabled.
     *
     * Ignore whitespace is disabled by default.
     */
    fun ignoreWhitespace(yes: Boolean): ParserBuilder {
        ignoreWhitespace = yes
        return this
    }
}

/**
 * A parser for a single regular expression.
 *
 * A `Parser` can be configured via [ParserBuilder].
 *
 * A `Parser` maintains some state that is reused for every regex it parses.
 * However, a `Parser` is not thread safe.
 */
class Parser internal constructor(
    // ... (rest same)
    /** The nesting limit. */
    internal val nestLimit: UInt,
    /** Whether octal syntax is enabled. */
    internal val octal: Boolean,
    /** Whether empty min ranges are enabled. */
    internal val emptyMinRange: Boolean,
    /** The initial ignore whitespace setting. */
    internal val initialIgnoreWhitespace: Boolean,
) {
    /** The current position of the parser. */
    internal var pos: Position = Position(0, 1, 1)
    /** The current capture index. */
    internal var captureIndex: UInt = 0u
    /** Whether the parser should ignore whitespace. */
    internal var ignoreWhitespace: Boolean = initialIgnoreWhitespace
    /** The comments found in the regular expression. */
    internal val comments: MutableList<Comment> = mutableListOf()
    /** A stack of group states. */
    internal val stackGroup: MutableList<GroupState> = mutableListOf()
    /** A stack of class states. */
    internal val stackClass: MutableList<ClassState> = mutableListOf()
    /** A list of capturing group names. */
    internal val captureNames: MutableList<io.github.kotlinmania.regexsyntax.ast.CaptureName> = mutableListOf()
    /** A scratch buffer for names and other things. */
    internal val scratch: StringBuilder = StringBuilder()

    /**
     * Create a new parser with default settings.
     */
    constructor() : this(
        nestLimit = 250u,
        octal = false,
        emptyMinRange = false,
        initialIgnoreWhitespace = false,
    )

    /**
     * Parse the given regular expression into an abstract syntax tree.
     */
    fun parse(pattern: String): Result<Ast> =
        ParserI(this, pattern).parse()

    /**
     * Parse the given regular expression into an abstract syntax tree,
     * including comments.
     */
    fun parseWithComments(pattern: String): Result<WithComments> =
        ParserI(this, pattern).parseWithComments()

    /**
     * Reset the internal state of a parser.
     *
     * This is called at the beginning of every parse. This prevents the
     * parser from running with inconsistent state (say, if a previous
     * invocation returned an error and the parser is reused).
     */
    internal fun reset() {
        pos = Position(0, 1, 1)
        ignoreWhitespace = initialIgnoreWhitespace
        comments.clear()
        stackGroup.clear()
        stackClass.clear()
        captureNames.clear()
        scratch.setLength(0)
    }
}

/**
 * A type that represents an AST and its comments.
 */
data class WithComments(
    /** The AST. */
    val ast: Ast,
    /** The comments found in the regular expression. */
    val comments: List<Comment>,
)

/**
 * GroupState represents the state of a group while it is being parsed.
 */
internal sealed class GroupState {
    /** An alternation. */
    data class Alternation(val value: io.github.kotlinmania.regexsyntax.ast.Alternation) : GroupState()
    /** A group. */
    data class Group(
        val concat: io.github.kotlinmania.regexsyntax.ast.Concat,
        val group: io.github.kotlinmania.regexsyntax.ast.Group,
        val ignoreWhitespace: Boolean
    ) : GroupState()
}

/**
 * ClassState represents the state of a character class while it is being
 * parsed.
 */
internal sealed class ClassState {
    /** An opening bracket. */
    data class Open(
        val union: ClassSetUnion,
        val set: ClassBracketed
    ) : ClassState()
    /** A binary operation. */
    data class Op(
        val kind: ClassSetBinaryOpKind,
        val lhs: ClassSet
    ) : ClassState()
}

/**
 * Primitive is a base case of an AST.
 */
internal sealed class Primitive {
    /** A literal character. */
    data class Literal(val value: io.github.kotlinmania.regexsyntax.ast.Literal) : Primitive()
    /** A zero-width assertion. */
    data class Assertion(val value: io.github.kotlinmania.regexsyntax.ast.Assertion) : Primitive()
    /** A dot assertion. */
    data class Dot(val span: Span) : Primitive()
    /** A Perl character class. */
    data class Perl(val value: io.github.kotlinmania.regexsyntax.ast.ClassPerl) : Primitive()
    /** A Unicode character class. */
    data class Unicode(val value: io.github.kotlinmania.regexsyntax.ast.ClassUnicode) : Primitive()

    /** Return the span of this primitive. */
    fun span(): Span = when (this) {
        is Literal -> value.span
        is Assertion -> value.span
        is Dot -> span
        is Perl -> value.span
        is Unicode -> value.span
    }

    /** Convert this primitive into a proper AST. */
    fun intoAst(): Ast = when (this) {
        is Literal -> Ast.Literal(value)
        is Assertion -> Ast.Assertion(value)
        is Dot -> Ast.Dot(span)
        is Perl -> Ast.ClassPerl(value)
        is Unicode -> Ast.ClassUnicode(value)
    }

    /**
     * Convert this primitive into an item in a character class.
     *
     * If this primitive is not a legal item (i.e., an assertion or a dot),
     * then return an error.
     */
    fun intoClassSetItem(p: ParserI): Result<ClassSetItem> = when (this) {
        is Literal -> Result.success(ClassSetItem.Literal(value))
        is Perl -> Result.success(ClassSetItem.Perl(value))
        is Unicode -> Result.success(ClassSetItem.Unicode(value))
        else -> Result.failure(AstException(p.error(span(), ErrorKind.ClassEscapeInvalid)))
    }

    /**
     * Convert this primitive into a literal in a character class. In
     * particular, literals are the only valid items that can appear in
     * ranges.
     *
     * If this primitive is not a legal item (i.e., a class, assertion or a
     * dot), then return an error.
     */
    fun intoClassLiteral(p: ParserI): Result<io.github.kotlinmania.regexsyntax.ast.Literal> = when (this) {
        is Literal -> Result.success(value)
        else -> Result.failure(AstException(p.error(span(), ErrorKind.ClassRangeLiteral)))
    }
}

/**
 * ParserI is the internal workhorse for the parser.
 */
internal class ParserI(
    private val parser: Parser,
    private val pattern: String,
) {
    /** Return a reference to the parser state. */
    internal fun parser(): Parser = parser

    /** Return a reference to the pattern being parsed. */
    private fun pattern(): String = pattern

    /** Create a new error with the given span and error type. */
    internal fun error(span: Span, kind: ErrorKind): AstError =
        AstError(kind, pattern(), span)

    /**
     * Return the current offset of the parser.
     *
     * The offset starts at `0` from the beginning of the regular expression
     * pattern string.
     */
    private fun offset(): Int = parser().pos.offset

    /**
     * Return the current line number of the parser.
     *
     * The line number starts at `1`.
     */
    private fun line(): Int = parser().pos.line

    /**
     * Return the current column of the parser.
     *
     * The column number starts at `1` and is reset whenever a `\n` is seen.
     */
    private fun column(): Int = parser().pos.column

    /**
     * Return the next capturing index. Each subsequent call increments the
     * internal index.
     *
     * The span given should correspond to the location of the opening
     * parenthesis.
     *
     * If the capture limit is exceeded, then an error is returned.
     */
    private fun nextCaptureIndex(span: Span): Result<UInt> {
        val current = parser().captureIndex
        if (current == UInt.MAX_VALUE) {
            return Result.failure(AstException(error(span, ErrorKind.CaptureLimitExceeded)))
        }
        val i = current + 1u
        parser().captureIndex = i
        return Result.success(i)
    }

    /**
     * Adds the given capture name to this parser. If this capture name has
     * already been used, then an error is returned.
     */
    private fun addCaptureName(cap: io.github.kotlinmania.regexsyntax.ast.CaptureName): Result<Unit> {
        val names = parser().captureNames
        val result = names.binarySearchBy(cap.name) { it.name }
        return if (result < 0) {
            names.add(-(result + 1), cap)
            Result.success(Unit)
        } else {
            Result.failure(AstException(error(
                cap.span,
                ErrorKind.GroupNameDuplicate(names[result].span)
            )))
        }
    }

    /** Return whether the parser should ignore whitespace or not. */
    private fun ignoreWhitespace(): Boolean = parser().ignoreWhitespace

    /**
     * Return the character (codepoint) at the current position of the parser.
     *
     * This panics if the current position does not point to a valid char.
     */
    private fun char(): Int = charAt(offset())

    /**
     * Return the character (codepoint) at the given position.
     *
     * This panics if the given position does not point to a valid char.
     */
    private fun charAt(i: Int): Int {
        if (i >= pattern().length) throw IllegalStateException("expected char at offset $i")
        val high = pattern()[i]
        if (high.isHighSurrogate() && i + 1 < pattern().length) {
            val low = pattern()[i + 1]
            if (low.isLowSurrogate()) {
                return (high.code shl 10) + low.code - 0x35fdc00
            }
        }
        return high.code
    }

    /**
     * Bump the parser to the next Unicode scalar value.
     *
     * If the end of the input has been reached, then `false` is returned.
     */
    private fun bump(): Boolean {
        if (isEof()) {
            return false
        }
        val c = char()
        var (offset, line, column) = pos()
        if (c == '\n'.code) {
            line += 1
            column = 1
        } else {
            column += 1
        }
        offset += if (c > 0xFFFF) 2 else 1
        parser().pos = Position(offset, line, column)
        return offset < pattern().length
    }

    /**
     * If the substring starting at the current position of the parser has
     * the given prefix, then bump the parser to the character immediately
     * following the prefix and return true. Otherwise, don't bump the parser
     * and return false.
     */
    private fun bumpIf(prefix: String): Boolean {
        return if (pattern().substring(offset()).startsWith(prefix)) {
            repeat(prefix.length) { bump() }
            true
        } else {
            false
        }
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
    private fun isLookaroundPrefix(): Boolean {
        return bumpIf("?=") || bumpIf("?!") || bumpIf("?<=") || bumpIf("?<!")
    }

    /**
     * Bump the parser, and if the `x` flag is enabled, bump through any
     * subsequent spaces. Return true if and only if the parser is not at
     * EOF.
     */
    private fun bumpAndBumpSpace(): Boolean {
        if (!bump()) {
            return false
        }
        bumpSpace()
        return !isEof()
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
     * example, `{   5  , 6}` is equivalent to `{5,6}`.
     */
    private fun bumpSpace() {
        if (!ignoreWhitespace()) {
            return
        }
        while (!isEof()) {
            val c = char()
            if (c.toChar().isWhitespace()) {
                bump()
            } else if (c == '#'.code) {
                val start = pos()
                val commentText = StringBuilder()
                bump()
                while (!isEof()) {
                    val cc = char()
                    bump()
                    if (cc == '\n'.code) {
                        break
                    }
                    commentText.append(cc.toChar())
                }
                val comment = Comment(
                    span = Span(start, pos()),
                    comment = commentText.toString()
                )
                parser().comments.add(comment)
            } else {
                break
            }
        }
    }

    /**
     * Peek at the next character in the input without advancing the parser.
     *
     * If the input has been exhausted, then this returns `null`.
     */
    private fun peek(): Int? {
        if (isEof()) {
            return null
        }
        val nextOffset = offset() + (if (char() > 0xFFFF) 2 else 1)
        if (nextOffset >= pattern().length) {
            return null
        }
        return charAt(nextOffset)
    }
    /**
     * Like peek, but will ignore spaces when the parser is in whitespace
     * insensitive mode.
     */
    private fun peekSpace(): Int? {
        if (!ignoreWhitespace()) {
            return peek()
        }
        if (isEof()) {
            return null
        }
        var start = offset() + (if (char() > 0xFFFF) 2 else 1)
        var inComment = false
        var i = 0
        while (start + i < pattern().length) {
            val c = pattern()[start + i]
            if (c.isWhitespace()) {
                i += 1
                continue
            } else if (!inComment && c == '#') {
                inComment = true
                i += 1
            } else if (inComment && c == '\n') {
                inComment = false
                i += 1
            } else {
                break
            }
        }
        if (start + i >= pattern().length) {
            return null
        }
        return charAt(start + i)
    }


    /** Returns true if the next call to `bump` would return false. */
    private fun isEof(): Boolean = offset() == pattern().length

    /**
     * Return the current position of the parser, which includes the offset,
     * line and column.
     */
    private fun pos(): Position = parser().pos

    /**
     * Create a span at the current position of the parser. Both the start
     * and end of the span are set.
     */
    private fun span(): Span = Span.splat(pos())

    /** Create a span that covers the current character. */
    private fun spanChar(): Span {
        val c = char()
        var next = Position(
            offset = offset() + (if (c > 0xFFFF) 2 else 1),
            line = line(),
            column = column() + 1
        )
        if (c == '\n'.code) {
            next = next.copy(line = next.line + 1, column = 1)
        }
        return Span(pos(), next)
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
    private fun pushAlternate(concat: Concat): Result<Concat> {
        if (char() != '|'.code) throw IllegalStateException("expected |")
        val finalConcat = concat.copy(span = concat.span.copy(end = pos()))
        pushOrAddAlternation(finalConcat)
        bump()
        return Result.success(Concat(span(), mutableListOf()))
    }

    /**
     * Pushes or adds the given branch of an alternation to the parser's
     * internal stack of state.
     */
    private fun pushOrAddAlternation(concat: Concat) {
        val stack = parser().stackGroup
        val last = stack.lastOrNull()
        if (last is GroupState.Alternation) {
            last.value.asts.add(concat.intoAst())
            return
        }
        stack.add(GroupState.Alternation(io.github.kotlinmania.regexsyntax.ast.Alternation(
            span = Span(concat.span.start, pos()),
            asts = mutableListOf(concat.intoAst())
        )))
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
    private fun pushGroup(concat: Concat): Result<Concat> {
        if (char() != '('.code) throw IllegalStateException("expected (")
        return parseGroup().fold(
            onSuccess = { either ->
                when (either) {
                    is io.github.kotlinmania.regexsyntax.either.Either.Left -> {
                        val set = either.value
                        set.flags.flagState(Flag.IgnoreWhitespace)?.let {
                            parser().ignoreWhitespace = it
                        }
                        concat.asts.add(Ast.Flags(set))
                        Result.success(concat)
                    }
                    is io.github.kotlinmania.regexsyntax.either.Either.Right -> {
                        val group = either.value
                        val oldIgnoreWhitespace = ignoreWhitespace()
                        val newIgnoreWhitespace = group.flags()
                            ?.flagState(Flag.IgnoreWhitespace)
                            ?: oldIgnoreWhitespace
                        parser().stackGroup.add(GroupState.Group(
                            concat = concat,
                            group = group,
                            ignoreWhitespace = oldIgnoreWhitespace
                        ))
                        parser().ignoreWhitespace = newIgnoreWhitespace
                        Result.success(Concat(span(), mutableListOf()))
                    }
                }
            },
            onFailure = { Result.failure(it) }
        )
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
    private fun popGroup(groupConcat: Concat): Result<Concat> {
        if (char() != ')'.code) throw IllegalStateException("expected )")
        val stack = parser().stackGroup
        if (stack.isEmpty()) {
            return Result.failure(AstException(error(spanChar(), ErrorKind.GroupUnopened)))
        }

        val state = stack.removeAt(stack.size - 1)
        var priorConcat: Concat
        var group: Group
        var oldIgnoreWhitespace: Boolean
        var alt: io.github.kotlinmania.regexsyntax.ast.Alternation? = null

        when (state) {
            is GroupState.Group -> {
                priorConcat = state.concat
                group = state.group
                oldIgnoreWhitespace = state.ignoreWhitespace
            }
            is GroupState.Alternation -> {
                if (stack.isEmpty()) {
                    return Result.failure(AstException(error(spanChar(), ErrorKind.GroupUnopened)))
                }
                val nextState = stack.removeAt(stack.size - 1)
                if (nextState is GroupState.Group) {
                    priorConcat = nextState.concat
                    group = nextState.group
                    oldIgnoreWhitespace = nextState.ignoreWhitespace
                    alt = state.value
                } else {
                    return Result.failure(AstException(error(spanChar(), ErrorKind.GroupUnopened)))
                }
            }
        }

        parser().ignoreWhitespace = oldIgnoreWhitespace
        val finalGroupConcat = groupConcat.copy(span = groupConcat.span.copy(end = pos()))
        bump()
        
        val finalAst = if (alt != null) {
            val finalAlt = alt.copy(span = alt.span.copy(end = finalGroupConcat.span.end))
            finalAlt.asts.add(finalGroupConcat.intoAst())
            Ast.Alternation(finalAlt)
        } else {
            finalGroupConcat.intoAst()
        }
        
        val finalGroup = group.copy(span = group.span.copy(end = pos()), ast = finalAst)
        priorConcat.asts.add(Ast.Group(finalGroup))
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
    private fun popGroupEnd(concat: Concat): Result<Ast> {
        val finalConcat = concat.copy(span = concat.span.copy(end = pos()))
        val stack = parser().stackGroup
        val astResult = if (stack.isEmpty()) {
            Result.success(finalConcat.intoAst())
        } else {
            when (val state = stack.removeAt(stack.size - 1)) {
                is GroupState.Alternation -> {
                    val alt = state.value
                    val finalAlt = alt.copy(span = alt.span.copy(end = pos()))
                    finalAlt.asts.add(finalConcat.intoAst())
                    Result.success(Ast.Alternation(finalAlt))
                }
                is GroupState.Group -> {
                    Result.failure(AstException(error(state.group.span, ErrorKind.GroupUnclosed)))
                }
            }
        }

        return astResult.fold(
            onSuccess = { ast ->
                if (stack.isEmpty()) {
                    Result.success(ast)
                } else {
                    val nextState = stack.removeAt(stack.size - 1)
                    if (nextState is GroupState.Group) {
                        Result.failure(AstException(error(nextState.group.span, ErrorKind.GroupUnclosed)))
                    } else {
                        throw IllegalStateException("unreachable")
                    }
                }
            },
            onFailure = { Result.failure(it) }
        )
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
    private fun pushClassOpen(parentUnion: ClassSetUnion): Result<ClassSetUnion> {
        return parseSetClassOpen().map { (nestedSet, nestedUnion) ->
            parser().stackClass.add(ClassState.Open(union = parentUnion, set = nestedSet))
            nestedUnion
        }
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
    private fun popClass(nestedUnion: ClassSetUnion): Result<io.github.kotlinmania.regexsyntax.either.Either<ClassSetUnion, ClassBracketed>> {
        val item = ClassSet.Item(nestedUnion.intoItem())
        val prevSet = popClassOp(item)
        val stack = parser().stackClass
        return when (val state = stack.removeAt(stack.size - 1)) {
            is ClassState.Op -> throw IllegalStateException("unexpected ClassState::Op")
            is ClassState.Open -> {
                val set = state.set
                bump()
                val finalSet = set.copy(
                    span = set.span.copy(end = pos()),
                    kind = prevSet
                )
                if (stack.isEmpty()) {
                    Result.success(io.github.kotlinmania.regexsyntax.either.Either.Right(finalSet))
                } else {
                    state.union.push(ClassSetItem.Bracketed(finalSet))
                    Result.success(io.github.kotlinmania.regexsyntax.either.Either.Left(state.union))
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
    private fun unclosedClassError(): AstError {
        val stack = parser().stackClass
        for (i in stack.size - 1 downTo 0) {
            val state = stack[i]
            if (state is ClassState.Open) {
                return error(state.set.span, ErrorKind.ClassUnclosed)
            }
        }
        throw IllegalStateException("no open character class found")
    }

    /**
     * Push the current set of class items on to the class parser's stack as
     * the left hand side of the given operator.
     *
     * A fresh set union is returned, which should be used to build the right
     * hand side of this operator.
     */
    private fun pushClassOp(
        nextKind: ClassSetBinaryOpKind,
        nextUnion: ClassSetUnion
    ): ClassSetUnion {
        val item = ClassSet.Item(nextUnion.intoItem())
        val newLhs = popClassOp(item)
        parser().stackClass.add(ClassState.Op(kind = nextKind, lhs = newLhs))
        return ClassSetUnion(span(), mutableListOf())
    }

    /**
     * Pop a character class set from the character class parser stack. If the
     * top of the stack is just an item (not an operation), then return the
     * given set unchanged. If the top of the stack is an operation, then the
     * given set will be used as the rhs of the operation on the top of the
     * stack. In that case, the binary operation is returned as a set.
     */
    private fun popClassOp(rhs: ClassSet): ClassSet {
        val stack = parser().stackClass
        val state = stack.lastOrNull()
        return if (state is ClassState.Op) {
            stack.removeAt(stack.size - 1)
            ClassSet.BinaryOp(ClassSetBinaryOp(
                span = Span(state.lhs.span().start, rhs.span().end),
                kind = state.kind,
                lhs = state.lhs,
                rhs = rhs
            ))
        } else {
            rhs
        }
    }

    /**
     * Parse the regular expression.
     */
    fun parse(): Result<Ast> = parseWithComments().map { it.ast }

    /**
     * Parse the regular expression, including comments.
     */
    fun parseWithComments(): Result<WithComments> {
        if (offset() != 0) throw IllegalStateException("parser can only be used once")
        parser().reset()
        var concat = Concat(span(), mutableListOf())
        while (!isEof()) {
            bumpSpace()
            if (isEof()) {
                break
            }
            val res: Result<Concat> = when (char().toChar()) {
                '(' -> pushGroup(concat)
                ')' -> popGroup(concat)
                '|' -> pushAlternate(concat)
                '[' -> parseSetClass().map { cls ->
                    concat.asts.add(Ast.ClassBracketed(cls))
                    concat
                }
                '?' -> parseUncountedRepetition(concat, RepetitionKind.ZeroOrOne)
                '*' -> parseUncountedRepetition(concat, RepetitionKind.ZeroOrMore)
                '+' -> parseUncountedRepetition(concat, RepetitionKind.OneOrMore)
                '{' -> parseCountedRepetition(concat)
                else -> parsePrimitive().map { prim ->
                    concat.asts.add(prim.intoAst())
                    concat
                }
            }
            concat = res.fold(
                onSuccess = { it },
                onFailure = { return Result.failure(it) }
            )
        }
        return popGroupEnd(concat).fold(
            onSuccess = { ast ->
                NestLimiter(this).check(ast).fold(
                    onSuccess = {
                        Result.success(WithComments(
                            ast = ast,
                            comments = parser().comments.toList()
                        ))
                    },
                    onFailure = { Result.failure(it) }
                )
            },
            onFailure = { Result.failure(it) }
        )
    }

    /**
     * Parses an uncounted repetition operation.
     */
    private fun parseUncountedRepetition(
        concat: Concat,
        kind: RepetitionKind
    ): Result<Concat> {
        val start = pos()
        val ast = if (concat.asts.isNotEmpty()) {
            concat.asts.removeAt(concat.asts.size - 1)
        } else {
            return Result.failure(AstException(error(Span.splat(start), ErrorKind.RepetitionMissing)))
        }

        val opStart = pos()
        bump()
        val greedy = !bumpIf("?")
        val opSpan = Span(opStart, pos())
        val span = Span(ast.span().start, pos())
        concat.asts.add(Ast.Repetition(Repetition(
            span = span,
            op = RepetitionOp(opSpan, kind),
            greedy = greedy,
            ast = ast
        )))
        return Result.success(concat)
    }

    /**
     * Parses a counted repetition operation of the form {m,n}.
     */
    private fun parseCountedRepetition(concat: Concat): Result<Concat> {
        val start = pos()
        val ast = if (concat.asts.isNotEmpty()) {
            concat.asts.removeAt(concat.asts.size - 1)
        } else {
            return Result.failure(AstException(error(Span.splat(start), ErrorKind.RepetitionMissing)))
        }

        bump()
        bumpSpace()
        if (isEof()) {
            return Result.failure(AstException(error(Span(start, pos()), ErrorKind.RepetitionCountUnclosed)))
        }

        val startRangeResult = if (char().toChar().isDigit()) {
            parseDecimal()
        } else if (parser().emptyMinRange && char() == ','.code) {
            Result.success(0u)
        } else {
            Result.failure(AstException(error(Span.splat(pos()), ErrorKind.RepetitionCountDecimalEmpty)))
        }
        val startRange = startRangeResult.fold(
            onSuccess = { it },
            onFailure = { return Result.failure(it) }
        )

        bumpSpace()
        if (isEof()) {
            return Result.failure(AstException(error(Span(start, pos()), ErrorKind.RepetitionCountUnclosed)))
        }

        val rangeResult: Result<RepetitionRange> = if (bumpIf(",")) {
            bumpSpace()
            if (isEof()) {
                Result.failure(AstException(error(Span(start, pos()), ErrorKind.RepetitionCountUnclosed)))
            } else if (char().toChar().isDigit()) {
                parseDecimal().fold(
                    onSuccess = { end ->
                        if (startRange > end) {
                            Result.failure(AstException(error(Span(start, pos()), ErrorKind.RepetitionCountInvalid)))
                        } else {
                            Result.success(RepetitionRange.Bounded(startRange, end))
                        }
                    },
                    onFailure = { Result.failure(it) }
                )
            } else {
                Result.success(RepetitionRange.AtLeast(startRange))
            }
        } else {
            Result.success(RepetitionRange.Exactly(startRange))
        }
        val range = rangeResult.fold(
            onSuccess = { it },
            onFailure = { return Result.failure(it) }
        )

        bumpSpace()
        if (isEof() || char() != '}'.code) {
            return Result.failure(AstException(error(Span(start, pos()), ErrorKind.RepetitionCountUnclosed)))
        }
        bump()
        val greedy = !bumpIf("?")
        val opSpan = Span(start, pos())
        val span = Span(ast.span().start, pos())
        concat.asts.add(Ast.Repetition(Repetition(
            span = span,
            op = RepetitionOp(opSpan, RepetitionKind.Range(range)),
            greedy = greedy,
            ast = ast
        )))
        return Result.success(concat)
    }

    /**
     * Parses the beginning of a group or a set of flags.
     */
    private fun parseGroup(): Result<io.github.kotlinmania.regexsyntax.either.Either<SetFlags, Group>> {
        if (char() != '('.code) throw IllegalStateException("expected (")
        val openSpan = spanChar()
        bump()
        bumpSpace()
        if (isLookaroundPrefix()) {
            return Result.failure(
                AstException(
                    error(
                        Span(openSpan.start, span().end),
                        ErrorKind.UnsupportedLookAround
                    )
                )
            )
        }
        val innerSpan = span()
        var startsWithP = true
        return if (bumpIf("?P<") || run {
                startsWithP = false
                bumpIf("?<")
            }
        ) {
            val captureIndex = nextCaptureIndex(openSpan).getOrElse { return Result.failure(it) }
            val name = parseCaptureName(captureIndex).getOrElse { return Result.failure(it) }
            Result.success(io.github.kotlinmania.regexsyntax.either.Either.Right(Group(
                span = openSpan,
                kind = GroupKind.CaptureName(startsWithP = startsWithP, name = name),
                ast = Ast.Empty(span())
            )))
        } else if (bumpIf("?")) {
            if (isEof()) {
                return Result.failure(AstException(error(openSpan, ErrorKind.GroupUnclosed)))
            }
            val flags = parseFlags().getOrElse { return Result.failure(it) }
            val charEnd = char()
            bump()
            if (charEnd == ')'.code) {
                if (flags.items.isEmpty()) {
                    return Result.failure(AstException(error(innerSpan, ErrorKind.RepetitionMissing)))
                }
                Result.success(io.github.kotlinmania.regexsyntax.either.Either.Left(SetFlags(
                    span = Span(openSpan.start, pos()),
                    flags = flags
                )))
            } else {
                if (charEnd != ':'.code) throw IllegalStateException("expected :")
                Result.success(io.github.kotlinmania.regexsyntax.either.Either.Right(Group(
                    span = openSpan,
                    kind = GroupKind.NonCapturing(flags),
                    ast = Ast.Empty(span())
                )))
            }
        } else {
            val captureIndex = nextCaptureIndex(openSpan).getOrElse { return Result.failure(it) }
            Result.success(io.github.kotlinmania.regexsyntax.either.Either.Right(Group(
                span = openSpan,
                kind = GroupKind.CaptureIndex(captureIndex),
                ast = Ast.Empty(span())
            )))
        }
    }

    /**
     * Parses a capture group name.
     */
    private fun parseCaptureName(captureIndex: UInt): Result<io.github.kotlinmania.regexsyntax.ast.CaptureName> {
        if (isEof()) {
            return Result.failure(AstException(error(span(), ErrorKind.GroupNameUnexpectedEof)))
        }
        val start = pos()
        while (true) {
            if (char() == '>'.code) {
                break
            }
            if (!isCaptureChar(char(), pos() == start)) {
                return Result.failure(AstException(error(spanChar(), ErrorKind.GroupNameInvalid)))
            }
            if (!bump()) {
                break
            }
        }
        val end = pos()
        if (isEof()) {
            return Result.failure(AstException(error(span(), ErrorKind.GroupNameUnexpectedEof)))
        }
        if (char() != '>'.code) throw IllegalStateException("expected >")
        bump()
        val name = pattern().substring(start.offset, end.offset)
        if (name.isEmpty()) {
            return Result.failure(AstException(error(Span(start, start), ErrorKind.GroupNameEmpty)))
        }
        val capName = io.github.kotlinmania.regexsyntax.ast.CaptureName(
            span = Span(start, end),
            name = name,
            index = captureIndex
        )
        return addCaptureName(capName).fold(
            onSuccess = { Result.success(capName) },
            onFailure = { Result.failure(it) }
        )
    }

    /**
     * Parses a sequence of flags.
     */
    private fun parseFlags(): Result<Flags> {
        val start = pos()
        val items = mutableListOf<FlagsItem>()
        var negated = false
        while (!isEof()) {
            val c = char()
            if (c == '-'.code) {
                if (negated) {
                    val original = items.find { it.kind is FlagsItemKind.Negation }!!.span
                    return Result.failure(AstException(error(spanChar(), ErrorKind.FlagRepeatedNegation(original))))
                }
                items.add(FlagsItem(spanChar(), FlagsItemKind.Negation))
                negated = true
                bump()
            } else if (c == ':'.code || c == ')'.code) {
                break
            } else {
                val flagResult = parseFlag()
                if (flagResult.isFailure) return Result.failure(flagResult.exceptionOrNull()!!)
                val flag = flagResult.getOrThrow()
                items.find { it.kind is FlagsItemKind.Flag && it.kind.value == flag }?.let {
                    return Result.failure(AstException(error(spanChar(), ErrorKind.FlagDuplicate(it.span))))
                }
                items.add(FlagsItem(spanChar(), FlagsItemKind.Flag(flag)))
                bump()
            }
        }
        if (items.isEmpty()) {
            return Result.success(Flags(Span(pos(), pos()), items))
        }
        if (items.last().kind is FlagsItemKind.Negation) {
            return Result.failure(AstException(error(items.last().span, ErrorKind.FlagDanglingNegation)))
        }
        return Result.success(Flags(Span(start, pos()), items))
    }

    /**
     * Parses a single flag.
     */
    private fun parseFlag(): Result<Flag> {
        return when (char().toChar()) {
            'i' -> Result.success(Flag.CaseInsensitive)
            'm' -> Result.success(Flag.MultiLine)
            's' -> Result.success(Flag.DotMatchesNewLine)
            'U' -> Result.success(Flag.SwapGreed)
            'u' -> Result.success(Flag.Unicode)
            'R' -> Result.success(Flag.CRLF)
            'x' -> Result.success(Flag.IgnoreWhitespace)
            else -> Result.failure(AstException(error(spanChar(), ErrorKind.FlagUnrecognized)))
        }
    }

    /**
     * Parses a single primitive expression.
     */
    private fun parsePrimitive(): Result<Primitive> {
        val c = char()
        return when (c.toChar()) {
            '\\' -> parseEscape()
            '.' -> {
                val span = spanChar()
                bump()
                Result.success(Primitive.Dot(span))
            }
            '^' -> {
                val span = spanChar()
                bump()
                Result.success(Primitive.Assertion(Assertion(span, AssertionKind.StartLine)))
            }
            '$' -> {
                val span = spanChar()
                bump()
                Result.success(Primitive.Assertion(Assertion(span, AssertionKind.EndLine)))
            }
            else -> {
                val span = spanChar()
                bump()
                Result.success(Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(
                    span = span,
                    kind = LiteralKind.Verbatim,
                    c = c
                )))
            }
        }
    }

    /**
     * Parses an escape sequence.
     */
    private fun parseEscape(): Result<Primitive> {
        val start = pos()
        if (!bump()) {
            return Result.failure(AstException(error(Span(start, pos()), ErrorKind.EscapeUnexpectedEof)))
        }
        val c = char()
        val cChar = c.toChar()
        when (cChar) {
            in '0'..'7' -> {
                if (!parser().octal) {
                    return Result.failure(AstException(error(
                        Span(start, spanChar().end),
                        ErrorKind.UnsupportedBackreference
                    )))
                }
                val lit = parseOctal()
                val finalLit = lit.copy(span = lit.span.copy(start = start))
                return Result.success(Primitive.Literal(finalLit))
            }
            '8', '9' -> {
                if (!parser().octal) {
                    return Result.failure(AstException(error(
                        Span(start, spanChar().end),
                        ErrorKind.UnsupportedBackreference
                    )))
                }
            }
            'x', 'u', 'U' -> {
                return parseHex().fold(
                    onSuccess = { lit ->
                        val finalLit = lit.copy(span = lit.span.copy(start = start))
                        Result.success(Primitive.Literal(finalLit))
                    },
                    onFailure = { Result.failure(it) }
                )
            }
            'p', 'P' -> {
                return parseUnicodeClass().fold(
                    onSuccess = { cls ->
                        val finalCls = cls.copy(span = cls.span.copy(start = start))
                        Result.success(Primitive.Unicode(finalCls))
                    },
                    onFailure = { Result.failure(it) }
                )
            }
            'd', 's', 'w', 'D', 'S', 'W' -> {
                val cls = parsePerlClass()
                val finalCls = cls.copy(span = cls.span.copy(start = start))
                return Result.success(Primitive.Perl(finalCls))
            }
        }

        // Handle one-letter sequences
        bump()
        val span = Span(start, pos())
        if (io.github.kotlinmania.regexsyntax.isMetaCharacter(cChar)) {
            return Result.success(Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(
                span = span,
                kind = LiteralKind.Meta,
                c = c
            )))
        }
        if (io.github.kotlinmania.regexsyntax.isEscapeableCharacter(cChar)) {
            return Result.success(Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(
                span = span,
                kind = LiteralKind.Superfluous,
                c = c
            )))
        }

        return when (cChar) {
            'a' -> Result.success(Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(span, LiteralKind.Special(SpecialLiteralKind.Bell), 0x07)))
            'f' -> Result.success(Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(span, LiteralKind.Special(SpecialLiteralKind.FormFeed), 0x0C)))
            't' -> Result.success(Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(span, LiteralKind.Special(SpecialLiteralKind.Tab), 0x09)))
            'n' -> Result.success(Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(span, LiteralKind.Special(SpecialLiteralKind.LineFeed), 0x0A)))
            'r' -> Result.success(Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(span, LiteralKind.Special(SpecialLiteralKind.CarriageReturn), 0x0D)))
            'v' -> Result.success(Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(span, LiteralKind.Special(SpecialLiteralKind.VerticalTab), 0x0B)))
            'A' -> Result.success(Primitive.Assertion(Assertion(span, AssertionKind.StartText)))
            'z' -> Result.success(Primitive.Assertion(Assertion(span, AssertionKind.EndText)))
            'b' -> {
                var kind: AssertionKind = AssertionKind.WordBoundary
                if (!isEof() && char() == '{'.code) {
                    maybeParseSpecialWordBoundary(start).fold(
                        onSuccess = { it?.let { kind = it } },
                        onFailure = { return Result.failure(it) }
                    )
                }
                Result.success(Primitive.Assertion(Assertion(Span(start, pos()), kind)))
            }
            'B' -> Result.success(Primitive.Assertion(Assertion(span, AssertionKind.NotWordBoundary)))
            '<' -> Result.success(Primitive.Assertion(Assertion(span, AssertionKind.WordBoundaryStartAngle)))
            '>' -> Result.success(Primitive.Assertion(Assertion(span, AssertionKind.WordBoundaryEndAngle)))
            else -> Result.failure(AstException(error(span, ErrorKind.EscapeUnrecognized)))
        }
    }

    private fun maybeParseSpecialWordBoundary(wbStart: Position): Result<AssertionKind?> {
        val isValidChar = { c: Int ->
            val cc = c.toChar()
            cc in 'A'..'Z' || cc in 'a'..'z' || cc == '-'
        }
        
        val start = pos()
        if (!bumpAndBumpSpace()) {
            return Result.failure(AstException(error(
                Span(wbStart, pos()),
                ErrorKind.SpecialWordOrRepetitionUnexpectedEof
            )))
        }
        
        val startContents = pos()
        if (!isValidChar(char())) {
            parser().pos = start
            return Result.success(null)
        }
        
        parser().scratch.setLength(0)
        while (!isEof() && isValidChar(char())) {
            parser().scratch.append(char().toChar())
            if (!bumpAndBumpSpace()) break
        }
        
        if (isEof() || char() != '}'.code) {
            return Result.failure(AstException(error(
                Span(start, pos()),
                ErrorKind.SpecialWordBoundaryUnclosed
            )))
        }
        
        val end = pos()
        bump()
        val kind = when (parser().scratch.toString()) {
            "start" -> AssertionKind.WordBoundaryStart
            "end" -> AssertionKind.WordBoundaryEnd
            "start-half" -> AssertionKind.WordBoundaryStartHalf
            "end-half" -> AssertionKind.WordBoundaryEndHalf
            else -> return Result.failure(AstException(error(
                Span(startContents, end),
                ErrorKind.SpecialWordBoundaryUnrecognized
            )))
        }
        return Result.success(kind)
    }

    private fun parseOctal(): io.github.kotlinmania.regexsyntax.ast.Literal {
        val start = pos()
        var n = 0
        var i = 0
        while (i < 3 && !isEof()) {
            val c = char()
            if (c.toChar() in '0'..'7') {
                n = n * 8 + (c - '0'.code)
                bump()
                i++
            } else {
                break
            }
        }
        return io.github.kotlinmania.regexsyntax.ast.Literal(
            span = Span(start, pos()),
            kind = LiteralKind.Octal,
            c = n
        )
    }

    private fun parseHex(): Result<io.github.kotlinmania.regexsyntax.ast.Literal> {
        val start = pos()
        val c = char().toChar()
        bump()
        if (isEof()) {
            return Result.failure(AstException(error(Span(start, pos()), ErrorKind.EscapeUnexpectedEof)))
        }
        return when (c) {
            'x' -> if (char() == '{'.code) parseHexBrace(start, HexLiteralKind.X) else parseHexDigits(2, HexLiteralKind.X)
            'u' -> if (char() == '{'.code) parseHexBrace(start, HexLiteralKind.UnicodeShort) else parseHexDigits(4, HexLiteralKind.UnicodeShort)
            'U' -> if (char() == '{'.code) parseHexBrace(start, HexLiteralKind.UnicodeLong) else parseHexDigits(8, HexLiteralKind.UnicodeLong)
            else -> throw IllegalStateException("unreachable")
        }
    }

    private fun parseHexDigits(n: Int, kind: HexLiteralKind): Result<io.github.kotlinmania.regexsyntax.ast.Literal> {
        val start = pos()
        var i = 0
        var hex = 0
        while (i < n) {
            if (isEof()) {
                return Result.failure(AstException(error(Span(start, pos()), ErrorKind.EscapeUnexpectedEof)))
            }
            val d = char().toChar().digitToIntOrNull(16)
                ?: return Result.failure(AstException(error(spanChar(), ErrorKind.EscapeHexInvalidDigit)))
            hex = (hex shl 4) or d
            bump()
            i++
        }
        if (kind != HexLiteralKind.X) {
            if (hex > 0x10FFFF || (hex in 0xD800..0xDFFF)) {
                return Result.failure(AstException(error(Span(start, pos()), ErrorKind.EscapeHexInvalid)))
            }
        }
        return Result.success(io.github.kotlinmania.regexsyntax.ast.Literal(
            span = Span(start, pos()),
            kind = LiteralKind.HexFixed(kind),
            c = hex
        ))
    }

    private fun parseHexBrace(start: Position, kind: HexLiteralKind): Result<io.github.kotlinmania.regexsyntax.ast.Literal> {
        if (char() != '{'.code) throw IllegalStateException("expected {")
        bump()
        val startDigits = pos()
        var hex = 0
        var digits = 0
        while (!isEof() && char() != '}'.code) {
            val d = char().toChar().digitToIntOrNull(16)
                ?: return Result.failure(AstException(error(spanChar(), ErrorKind.EscapeHexInvalidDigit)))
            hex = (hex shl 4) or d
            if (hex > 0x10FFFF) {
                while (!isEof() && char() != '}'.code) bump()
                return Result.failure(AstException(error(Span(startDigits, pos()), ErrorKind.EscapeHexInvalid)))
            }
            bump()
            digits++
        }
        if (isEof()) {
            return Result.failure(AstException(error(Span(start, pos()), ErrorKind.EscapeUnexpectedEof)))
        }
        if (digits == 0) {
            return Result.failure(AstException(error(Span(start, pos()), ErrorKind.EscapeHexEmpty)))
        }
        if (hex in 0xD800..0xDFFF) {
            return Result.failure(AstException(error(Span(startDigits, pos()), ErrorKind.EscapeHexInvalid)))
        }
        bump() // '}'
        return Result.success(io.github.kotlinmania.regexsyntax.ast.Literal(
            span = Span(start, pos()),
            kind = LiteralKind.HexBrace(kind),
            c = hex
        ))
    }

    private fun parseDecimal(): Result<UInt> {
        val start = pos()
        var n = 0u
        var digits = 0
        while (!isEof()) {
            val d = char().toChar().digitToIntOrNull(10) ?: break
            val next = n.toLong() * 10 + d
            if (next > UInt.MAX_VALUE.toLong()) {
                while (!isEof() && char().toChar().isDigit()) bump()
                return Result.failure(AstException(error(Span(start, pos()), ErrorKind.DecimalInvalid)))
            }
            n = next.toUInt()
            bump()
            digits++
        }
        if (digits == 0) {
            return Result.failure(AstException(error(Span(start, pos()), ErrorKind.DecimalEmpty)))
        }
        return Result.success(n)
    }

    /**
     * Parses a range of characters in a character class.
     */
    private fun parseSetClassRange(): Result<ClassSetItem> {
        val prim1 = parseSetClassItem().getOrElse { return Result.failure(it) }
        bumpSpace()
        if (isEof()) {
            return Result.failure(AstException(unclosedClassError()))
        }
        val next = peekSpace()
        if (char() != '-'.code || next == ']'.code || next == '-'.code) {
            return prim1.intoClassSetItem(this)
        }
        if (!bumpAndBumpSpace()) {
            return Result.failure(AstException(unclosedClassError()))
        }
        val prim2 = parseSetClassItem().getOrElse { return Result.failure(it) }
        val range = ClassSetRange(
            span = Span(prim1.span().start, prim2.span().end),
            start = prim1.intoClassLiteral(this).getOrElse { return Result.failure(it) },
            end = prim2.intoClassLiteral(this).getOrElse { return Result.failure(it) }
        )
        if (!range.isValid()) {
            return Result.failure(AstException(error(range.span, ErrorKind.ClassRangeInvalid)))
        }
        return Result.success(ClassSetItem.Range(range))
    }

    private fun parseSetClass(): Result<ClassBracketed> {
        if (char() != '['.code) throw IllegalStateException("expected [")

        val openResult = parseSetClassOpen()
        if (openResult.isFailure) return Result.failure(openResult.exceptionOrNull()!!)
        val (set, union) = openResult.getOrThrow()
        
        parser().stackClass.add(ClassState.Open(
            union = union,
            set = set.copy()
        ))
        
        var currentUnion = union

        while (true) {
            bumpSpace()
            if (isEof()) {
                return Result.failure(AstException(unclosedClassError()))
            }
            val charResult: Result<ClassSetUnion> = when (char().toChar()) {
                '[' -> pushClassOpen(currentUnion)
                ']' -> {
                    val popRes = popClass(currentUnion)
                    if (popRes.isFailure) return Result.failure(popRes.exceptionOrNull()!!)
                    when (val either = popRes.getOrThrow()) {
                        is io.github.kotlinmania.regexsyntax.either.Either.Left -> Result.success(either.value)
                        is io.github.kotlinmania.regexsyntax.either.Either.Right -> return Result.success(either.value)
                    }
                }
                '&' -> {
                    if (peek() == '&'.code) {
                        bump()
                        bump()
                        Result.success(pushClassOp(ClassSetBinaryOpKind.Intersection, currentUnion))
                    } else {
                        parseSetClassRange().map { currentUnion.push(it); currentUnion }
                    }
                }
                '-' -> {
                    if (peek() == '-'.code) {
                        bump()
                        bump()
                        Result.success(pushClassOp(ClassSetBinaryOpKind.Difference, currentUnion))
                    } else {
                        parseSetClassRange().map { currentUnion.push(it); currentUnion }
                    }
                }
                '~' -> {
                    if (peek() == '~'.code) {
                        bump()
                        bump()
                        Result.success(pushClassOp(ClassSetBinaryOpKind.SymmetricDifference, currentUnion))
                    } else {
                        parseSetClassRange().map { currentUnion.push(it); currentUnion }
                    }
                }
                else -> {
                    parseSetClassRange().map { currentUnion.push(it); currentUnion }
                }
            }
            if (charResult.isFailure) return Result.failure(charResult.exceptionOrNull()!!)
            currentUnion = charResult.getOrThrow()
        }
        throw IllegalStateException("unreachable")
    }

    private fun parseSetClassOpen(): Result<Pair<ClassBracketed, ClassSetUnion>> {
        if (char() != '['.code) throw IllegalStateException("expected [")
        val start = pos()
        if (!bumpAndBumpSpace()) {
            return Result.failure(AstException(error(Span(start, pos()), ErrorKind.ClassUnclosed)))
        }

        val negated = if (char() != '^'.code) {
            false
        } else {
            if (!bumpAndBumpSpace()) {
                return Result.failure(AstException(error(Span(start, pos()), ErrorKind.ClassUnclosed)))
            }
            true
        }

        val union = ClassSetUnion(span(), mutableListOf())
        while (char() == '-'.code) {
            union.push(ClassSetItem.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(
                span = spanChar(),
                kind = LiteralKind.Verbatim,
                c = '-'.code
            )))
            if (!bumpAndBumpSpace()) {
                return Result.failure(AstException(error(Span.splat(start), ErrorKind.ClassUnclosed)))
            }
        }

        if (union.items.isEmpty() && char() == ']'.code) {
            union.push(ClassSetItem.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(
                span = spanChar(),
                kind = LiteralKind.Verbatim,
                c = ']'.code
            )))
            if (!bumpAndBumpSpace()) {
                return Result.failure(AstException(error(Span(start, pos()), ErrorKind.ClassUnclosed)))
            }
        }

        val set = ClassBracketed(
            span = Span(start, pos()),
            negated = negated,
            kind = ClassSet.Item(ClassSetItem.Union(ClassSetUnion(Span(union.span.start, union.span.start), mutableListOf())))
        )
        return Result.success(set to union)
    }

    private fun maybeParseAsciiClass(): ClassAscii? {
        if (char() != '['.code) throw IllegalStateException("expected [")
        val start = pos()
        var negated = false
        if (!bump() || char() != ':'.code) {
            parser().pos = start
            return null
        }
        if (!bump()) {
            parser().pos = start
            return null
        }
        if (char() == '^'.code) {
            negated = true
            if (!bump()) {
                parser().pos = start
                return null
            }
        }
        val nameStart = offset()
        while (char() != ':'.code && bump()) { }
        if (isEof()) {
            parser().pos = start
            return null
        }
        val name = pattern().substring(nameStart, offset())
        if (!bumpIf(":]")) {
            parser().pos = start
            return null
        }
        val kind = ClassAsciiKind.fromName(name) ?: run {
            parser().pos = start
            return null
        }
        return ClassAscii(Span(start, pos()), kind, negated)
    }

    private fun parseSetClassItem(): Result<Primitive> {
        return if (char() == '\\'.code) {
            parseEscape()
        } else {
            val x = Primitive.Literal(io.github.kotlinmania.regexsyntax.ast.Literal(
                span = spanChar(),
                kind = LiteralKind.Verbatim,
                c = char(),
            ))
            bump()
            Result.success(x)
        }
    }

    private fun parseSetClassPrimitive(): Result<Primitive> {
        return when (char().toChar()) {
            '\\' -> {
                val start = pos()
                bump()
                if (isEof()) {
                    Result.failure(AstException(error(Span(start, pos()), ErrorKind.EscapeUnexpectedEof)))
                } else {
                    val c = char().toChar()
                    when (c) {
                        'p', 'P' -> {
                            parseUnicodeClass().map { cls ->
                                val finalCls = cls.copy(span = cls.span.copy(start = start))
                                Primitive.Unicode(finalCls)
                            }
                        }
                        'd', 's', 'w', 'D', 'S', 'W' -> {
                            val cls = parsePerlClass()
                            val finalCls = cls.copy(span = cls.span.copy(start = start))
                            Result.success(Primitive.Perl(finalCls))
                        }
                        else -> {
                            parser().pos = start
                            parseEscape()
                        }
                    }
                }
            }
            else -> parsePrimitive()
        }
    }

    private fun parseUnicodeClass(): Result<ClassUnicode> {
        val start = pos()
        val c = char().toChar()
        val negated = c == 'P'
        bump()
        if (isEof()) {
            return Result.failure(AstException(error(Span.splat(pos()), ErrorKind.EscapeUnexpectedEof)))
        }

        return if (bumpIf("{")) {
            parser().scratch.setLength(0)
            while (!isEof() && char().toChar() != '}') {
                parser().scratch.append(char().toChar())
                bump()
            }
            if (isEof()) {
                Result.failure(AstException(error(Span.splat(pos()), ErrorKind.EscapeUnexpectedEof)))
            } else {
                val content = parser().scratch.toString()
                bump() // '}'

                val kind = if (content.contains(":")) {
                    val parts = content.split(":", limit = 2)
                    ClassUnicodeKind.NamedValue(ClassUnicodeOpKind.Colon, parts[0], parts[1])
                } else if (content.contains("=")) {
                    val parts = content.split("=", limit = 2)
                    ClassUnicodeKind.NamedValue(ClassUnicodeOpKind.Equal, parts[0], parts[1])
                } else if (content.contains("!=")) {
                    val parts = content.split("!=", limit = 2)
                    ClassUnicodeKind.NamedValue(ClassUnicodeOpKind.NotEqual, parts[0], parts[1])
                } else {
                    ClassUnicodeKind.Named(content)
                }
                Result.success(ClassUnicode(Span(start, pos()), negated, kind))
            }
        } else {
            val cp = char()
            val span = Span(start, spanChar().end)
            bump()
            Result.success(ClassUnicode(span, negated, ClassUnicodeKind.OneLetter(cp)))
        }
    }

    private fun parsePerlClass(): ClassPerl {
        val start = pos()
        val c = char().toChar()
        val negated = c.isUpperCase()
        val kind = when (c.lowercaseChar()) {
            'd' -> ClassPerlKind.Digit
            's' -> ClassPerlKind.Space
            'w' -> ClassPerlKind.Word
            else -> throw IllegalStateException("unreachable")
        }
        bump()
        return ClassPerl(Span(start, pos()), kind, negated)
    }

    private fun isCaptureChar(c: Int, first: Boolean): Boolean {
        if (c > 0xFFFF) {
            return false
        }
        val ch = c.toChar()
        return if (first) {
            ch == '_' || ch.isLetter()
        } else {
            ch == '_' || ch == '.' || ch == '[' || ch == ']' || ch.isLetterOrDigit()
        }
    }
}

/**
 * NestLimiter is a visitor that checks whether an AST exceeds the nesting
 * limit.
 */
internal class NestLimiter(private val p: ParserI) : Visitor<Unit, AstException> {
    private var depth = 0u

    fun check(ast: Ast): Result<Unit> {
        return visit(ast, this)
    }

    private fun incrementDepth(span: Span): Result<Unit> {
        val new = depth + 1u
        val limit = p.parser().nestLimit
        if (new > limit) {
            return Result.failure(AstException(p.error(span, ErrorKind.NestLimitExceeded(limit))))
        }
        depth = new
        return Result.success(Unit)
    }

    private fun decrementDepth() {
        depth -= 1u
    }

    override fun finish(): Result<Unit> = Result.success(Unit)

    override fun visitPre(ast: Ast): Result<Unit> {
        val span = when (ast) {
            is Ast.Empty, is Ast.Flags, is Ast.Literal, is Ast.Dot,
            is Ast.Assertion, is Ast.ClassUnicode, is Ast.ClassPerl -> return Result.success(Unit)
            is Ast.ClassBracketed -> ast.value.span
            is Ast.Repetition -> ast.value.span
            is Ast.Group -> ast.value.span
            is Ast.Alternation -> ast.value.span
            is Ast.Concat -> ast.value.span
        }
        return incrementDepth(span)
    }

    override fun visitPost(ast: Ast): Result<Unit> {
        when (ast) {
            is Ast.Empty, is Ast.Flags, is Ast.Literal, is Ast.Dot,
            is Ast.Assertion, is Ast.ClassUnicode, is Ast.ClassPerl -> { }
            else -> decrementDepth()
        }
        return Result.success(Unit)
    }

    override fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit> {
        val span = when (ast) {
            is ClassSetItem.Empty, is ClassSetItem.Literal, is ClassSetItem.Range,
            is ClassSetItem.Ascii, is ClassSetItem.Unicode, is ClassSetItem.Perl -> return Result.success(Unit)
            is ClassSetItem.Bracketed -> ast.value.span
            is ClassSetItem.Union -> ast.value.span
        }
        return incrementDepth(span)
    }

    override fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit> {
        when (ast) {
            is ClassSetItem.Empty, is ClassSetItem.Literal, is ClassSetItem.Range,
            is ClassSetItem.Ascii, is ClassSetItem.Unicode, is ClassSetItem.Perl -> { }
            else -> decrementDepth()
        }
        return Result.success(Unit)
    }

    override fun visitClassSetBinaryOpPre(ast: ClassSetBinaryOp): Result<Unit> {
        return incrementDepth(ast.span)
    }

    override fun visitClassSetBinaryOpPost(ast: ClassSetBinaryOp): Result<Unit> {
        decrementDepth()
        return Result.success(Unit)
    }
}

/**
 * A throwable wrapper for [AstError] values. The Kotlin [Result] machinery
 * requires a [Throwable] in the failure case.
 */
class AstException(val err: AstError) : Throwable(err.toString())

/**
 * When the result is an error, transforms the ast::ErrorKind from the source
 * Result into another one. This function is used to return clearer error
 * messages when possible.
 */
private fun <T> specializeErr(
    result: Result<T>,
    from: ErrorKind,
    to: ErrorKind
): Result<T> {
    return result.fold(
        onSuccess = { Result.success(it) },
        onFailure = { e ->
            if (e is AstException && e.err.kind() == from) {
                Result.failure(AstException(AstError(to, e.err.pattern(), e.err.span())))
            } else {
                Result.failure(e)
            }
        }
    )
}
