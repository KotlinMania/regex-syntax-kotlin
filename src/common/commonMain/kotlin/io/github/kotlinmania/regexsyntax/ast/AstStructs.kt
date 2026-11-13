package io.github.kotlinmania.regexsyntax.ast

import io.github.kotlinmania.regexsyntax.error.fromFormatterErrorKind
import io.github.kotlinmania.rustlang.core.cmp.Ordering
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout

/**
 * An error that occurred while parsing a regular expression into an abstract
 * syntax tree.
 *
 * Note that not all ASTs represents a valid regular expression. For example,
 * an AST is constructed without error for `\p{Quux}`, but `Quux` is not a
 * valid Unicode property name. That particular error is reported when
 * translating an AST to the high-level intermediate representation (`HIR`).
 */
public data class Error(
    /**
     * The kind of error.
     */
    public var kind: ErrorKind,
    /**
     * The original pattern that the parser generated the error from. Every
     * span in an error is a valid range into this string.
     */
    public var pattern: String,
    /**
     * The span of this error.
     */
    public var span: Span,
) {
    /**
     * Return the type of this error.
     */
    public fun kind(): ErrorKind = this.kind

    /**
     * The original pattern string in which this error occurred.
     *
     * Every span reported by this error is reported in terms of this string.
     */
    public fun pattern(): String = this.pattern

    /**
     * Return the span at which this error occurred.
     */
    public fun span(): Span = this.span

    /**
     * Return an auxiliary span. This span exists only for some errors that
     * benefit from being able to point to two locations in the original
     * regular expression. For example, "duplicate" errors will have the
     * main error position set to the duplicate occurrence while its
     * auxiliary span will be set to the initial occurrence.
     */
    public fun auxiliarySpan(): Span? =
        when (val matchVal = this.kind) {
            is ErrorKind.FlagDuplicate -> matchVal.original
            is ErrorKind.FlagRepeatedNegation -> matchVal.original
            is ErrorKind.GroupNameDuplicate -> matchVal.original
            else -> null
        }

    public fun fmt(f: StringBuilder) {
        fromFormatterErrorKind(this).fmt(f)
    }

    override fun toString(): String = buildString { fmt(this) }
}

/**
 * Span represents the position information of a single AST item.
 *
 * All span positions are absolute byte offsets that can be used on the
 * original regular expression that was parsed.
 */
public data class Span(
    /**
     * The start byte offset.
     */
    public var start: Position,
    /**
     * The end byte offset.
     */
    public var end: Position,
) : Comparable<Span> {
    public fun fmt(f: StringBuilder) {
        f.append("Span(${this.start}, ${this.end})")
    }

    override fun toString(): String = buildString { fmt(this) }

    override fun compareTo(other: Span): Int {
        val startCmp = this.start.compareTo(other.start)
        return if (startCmp != 0) startCmp else this.end.compareTo(other.end)
    }

    public fun cmp(other: Span): Ordering = Ordering.fromCompareResult(this.compareTo(other))

    public fun partialCmp(other: Span): Ordering? = this.cmp(other)

    /**
     * Create a new span by replacing the starting the position with the one
     * given.
     */
    public fun withStart(pos: Position): Span = this.copy(start = pos)

    /**
     * Create a new span by replacing the ending the position with the one
     * given.
     */
    public fun withEnd(pos: Position): Span = this.copy(end = pos)

    /**
     * Returns true if and only if this span occurs on a single line.
     */
    public fun isOneLine(): Boolean = this.start.line == this.end.line

    /**
     * Returns true if and only if this span is empty. That is, it points to
     * a single position in the concrete syntax of a regular expression.
     */
    public fun isEmpty(): Boolean = this.start.offset == this.end.offset

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<Span> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<Span> {
                override val alignBytes: Long = 8L
                override val sizeBytes: Long = 48L
                override fun readNative(addr: Long): Span =
                    Span(
                        (Position.typeLayout()).readNative(addr),
                        (Position.typeLayout()).readNative(addr + 24L),
                    )
                override fun writeNative(addr: Long, value: Span) {
                    (Position.typeLayout()).writeNative(addr, value.start)
                    (Position.typeLayout()).writeNative(addr + 24L, value.end)
                }
            }

        /**
         * Create a new span with the given positions.
         */
        public fun new(start: Position, end: Position): Span = Span(start = start, end = end)

        /**
         * Create a new span using the given position as the start and end.
         */
        public fun splat(pos: Position): Span = Span.new(pos, pos)
    }
}

/**
 * A single position in a regular expression.
 *
 * A position encodes one half of a span, and include the byte offset, line
 * number and column number.
 */
public data class Position(
    /**
     * The absolute offset of this position, starting at `0` from the
     * beginning of the regular expression pattern string.
     */
    public var offset: Int,
    /**
     * The line number, starting at `1`.
     */
    public var line: Int,
    /**
     * The approximate column number, starting at `1`.
     */
    public var column: Int,
) : Comparable<Position> {
    public fun fmt(f: StringBuilder) {
        f.append("Position(o: ${this.offset}, l: ${this.line}, c: ${this.column})")
    }

    override fun toString(): String = buildString { fmt(this) }

    override fun compareTo(other: Position): Int = this.offset.compareTo(other.offset)

    public fun cmp(other: Position): Ordering = Ordering.fromCompareResult(this.compareTo(other))

    public fun partialCmp(other: Position): Ordering? = this.cmp(other)

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<Position> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<Position> {
                override val alignBytes: Long = 8L
                override val sizeBytes: Long = 24L
                override fun readNative(addr: Long): Position =
                    Position(
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.USIZE64).readNative(addr),
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.USIZE64).readNative(addr + 8L),
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.USIZE64).readNative(addr + 16L),
                    )
                override fun writeNative(addr: Long, value: Position) {
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.USIZE64).writeNative(addr, value.offset)
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.USIZE64).writeNative(addr + 8L, value.line)
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.USIZE64).writeNative(addr + 16L, value.column)
                }
            }

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
        public fun new(offset: Int, line: Int, column: Int): Position = Position(offset = offset, line = line, column = column)
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
public data class WithComments(
    /**
     * The actual ast.
     */
    public var ast: Ast,
    /**
     * All comments found in the original regular expression.
     */
    public var comments: List<Comment>,
)

/**
 * A comment from a regular expression with an associated span.
 *
 * A regular expression can only contain comments when the `x` flag is
 * enabled.
 */
public data class Comment(
    /**
     * The span of this comment, including the beginning `#` and ending `\n`.
     */
    public var span: Span,
    /**
     * The comment text, starting with the first character following the `#`
     * and ending with the last character preceding the `\n`.
     */
    public var comment: String,
)

/**
 * An alternation of regular expressions.
 */
public class Alternation(
    /**
     * The span of this alternation.
     */
    public var span: Span,
    /**
     * The alternate regular expressions.
     */
    asts: List<Ast>,
) {
    internal val mutableAsts: MutableList<Ast> = asts.toMutableList()

    /**
     * The alternate regular expressions.
     */
    public var asts: List<Ast>
        get() = this.mutableAsts
        set(value) {
            this.mutableAsts.clear()
            this.mutableAsts.addAll(value)
        }

    public fun copy(
        span: Span = this.span,
        asts: List<Ast> = this.asts.toList(),
    ): Alternation = Alternation(span = span, asts = asts)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Alternation && this.span == other.span && this.asts == other.asts)

    override fun hashCode(): Int = 31 * this.span.hashCode() + this.asts.hashCode()

    override fun toString(): String = "Alternation(span=${this.span}, asts=${this.asts})"

    /**
     * Return this alternation as an AST.
     *
     * If this alternation contains zero ASTs, then `Ast.empty` is returned.
     * If this alternation contains exactly 1 AST, then the corresponding AST
     * is returned. Otherwise, `Ast.alternation` is returned.
     */
    public fun intoAst(): Ast =
        when (this.mutableAsts.size) {
            0 -> Ast.empty(this.span)
            1 -> this.mutableAsts.removeLast()
            else -> Ast.Alternation(this)
        }
}

/**
 * A concatenation of regular expressions.
 */
public class Concat(
    /**
     * The span of this concatenation.
     */
    public var span: Span,
    /**
     * The concatenation regular expressions.
     */
    asts: List<Ast>,
) {
    internal val mutableAsts: MutableList<Ast> = asts.toMutableList()

    /**
     * The concatenation regular expressions.
     */
    public var asts: List<Ast>
        get() = this.mutableAsts
        set(value) {
            this.mutableAsts.clear()
            this.mutableAsts.addAll(value)
        }

    public fun copy(
        span: Span = this.span,
        asts: List<Ast> = this.asts.toList(),
    ): Concat = Concat(span = span, asts = asts)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Concat && this.span == other.span && this.asts == other.asts)

    override fun hashCode(): Int = 31 * this.span.hashCode() + this.asts.hashCode()

    override fun toString(): String = "Concat(span=${this.span}, asts=${this.asts})"

    /**
     * Return this concatenation as an AST.
     *
     * If this alternation contains zero ASTs, then `Ast.empty` is returned.
     * If this alternation contains exactly 1 AST, then the corresponding AST
     * is returned. Otherwise, `Ast.concat` is returned.
     */
    public fun intoAst(): Ast =
        when (this.mutableAsts.size) {
            0 -> Ast.empty(this.span)
            1 -> this.mutableAsts.removeLast()
            else -> Ast.Concat(this)
        }
}

/**
 * A single literal expression.
 *
 * A literal corresponds to a single Unicode scalar value. Literals may be
 * represented in their literal form, e.g., `a` or in their escaped form,
 * e.g., `\x61`.
 */
public data class Literal(
    /**
     * The span of this literal.
     */
    public var span: Span,
    /**
     * The kind of this literal.
     */
    public var kind: LiteralKind,
    /**
     * The Unicode scalar value corresponding to this literal.
     */
    public var c: UInt,
) {
    /**
     * If this literal was written as a `\x` hex escape, then this returns
     * the corresponding byte value. Otherwise, this returns `None`.
     */
    public fun byte(): UByte? {
        val matchVal = this.kind
        return when {
            matchVal is LiteralKind.HexFixed && matchVal.value == HexLiteralKind.X && this.c <= 0xFFu ->
                this.c.toUByte()
            else -> null
        }
    }
}

/**
 * A Perl character class.
 */
public data class ClassPerl(
    /**
     * The span of this class.
     */
    public var span: Span,
    /**
     * The kind of Perl class.
     */
    public var kind: ClassPerlKind,
    /**
     * Whether the class is negated or not. e.g., `\d` is not negated but
     * `\D` is.
     */
    public var negated: Boolean,
)

/**
 * An ASCII character class.
 */
public data class ClassAscii(
    /**
     * The span of this class.
     */
    public var span: Span,
    /**
     * The kind of ASCII class.
     */
    public var kind: ClassAsciiKind,
    /**
     * Whether the class is negated or not. e.g., `[[: alpha:]]` is not negated
     * but `[[: ^ alpha:]]` is.
     */
    public var negated: Boolean,
)

/**
 * A Unicode character class.
 */
public data class ClassUnicode(
    /**
     * The span of this class.
     */
    public var span: Span,
    /**
     * Whether this class is negated or not.
     *
     * Note: be careful when using this attribute. This specifically refers
     * to whether the class is written as `\p` or `\P`, where the latter
     * is `negated = true`. However, it also possible to write something like
     * `\P{scx!=Katakana}` which is actually equivalent to
     * `\p{scx=Katakana}` and is therefore not actually negated even though
     * `negated = true` here. To test whether this class is truly negated
     * or not, use the `isNegated` method.
     */
    public var negated: Boolean,
    /**
     * The kind of Unicode class.
     */
    public var kind: ClassUnicodeKind,
) {
    /**
     * Returns true if this class has been negated.
     *
     * Note that this takes the Unicode op into account, if it's present.
     * e.g., `isNegated` for `\P{scx!=Katakana}` will return `false`.
     */
    public fun isNegated(): Boolean {
        val matchVal = this.kind
        return when {
            matchVal is ClassUnicodeKind.NamedValue && matchVal.op == ClassUnicodeOpKind.NotEqual -> !this.negated
            else -> this.negated
        }
    }
}

/**
 * A bracketed character class, e.g., `[a - z0 - 9]`.
 */
public data class ClassBracketed(
    /**
     * The span of this class.
     */
    public var span: Span,
    /**
     * Whether this class is negated or not. e.g., `[a]` is not negated but
     * `[^ a]` is.
     */
    public var negated: Boolean,
    /**
     * The type of this set. A set is either a normal union of things, e.g.,
     * `[abc]` or a result of applying set operations, e.g., `[\pL--c]`.
     */
    public var kind: ClassSet,
)

/**
 * A single character class range in a set.
 */
public data class ClassSetRange(
    /**
     * The span of this range.
     */
    public var span: Span,
    /**
     * The start of this range.
     */
    public var start: Literal,
    /**
     * The end of this range.
     */
    public var end: Literal,
) {
    /**
     * Returns true if and only if this character class range is valid.
     *
     * The only case where a range is invalid is if its start is greater than
     * its end.
     */
    public fun isValid(): Boolean = this.start.c <= this.end.c
}

/**
 * A union of items inside a character class set.
 */
public class ClassSetUnion(
    /**
     * The span of the items in this operation. e.g., the `a - z0 - 9` in
     * `[^ a - z0 - 9]`
     */
    public var span: Span,
    /**
     * The sequence of items that make up this union.
     */
    items: List<ClassSetItem>,
) {
    internal val mutableItems: MutableList<ClassSetItem> = items.toMutableList()

    /**
     * The sequence of items that make up this union.
     */
    public var items: List<ClassSetItem>
        get() = this.mutableItems
        set(value) {
            this.mutableItems.clear()
            this.mutableItems.addAll(value)
        }

    public fun copy(
        span: Span = this.span,
        items: List<ClassSetItem> = this.items.toList(),
    ): ClassSetUnion = ClassSetUnion(span = span, items = items)

    override fun equals(other: Any?): Boolean =
        this === other || (other is ClassSetUnion && this.span == other.span && this.items == other.items)

    override fun hashCode(): Int = 31 * this.span.hashCode() + this.items.hashCode()

    override fun toString(): String = "ClassSetUnion(span=${this.span}, items=${this.items})"

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
    public fun push(item: ClassSetItem) {
        if (this.mutableItems.isEmpty()) {
            this.span.start = item.span().start
        }
        this.span.end = item.span().end
        this.mutableItems.add(item)
    }

    /**
     * Return this union as a character class set item.
     *
     * If this union contains zero items, then an empty union is
     * returned. If this concatenation contains exactly 1 item, then the
     * corresponding item is returned. Otherwise, ClassSetItem.Union is
     * returned.
     */
    public fun intoItem(): ClassSetItem =
        when (this.mutableItems.size) {
            0 -> ClassSetItem.Empty(this.span)
            1 -> this.mutableItems.removeLast()
            else -> ClassSetItem.Union(this)
        }
}

/**
 * A Unicode character class set operation.
 */
public data class ClassSetBinaryOp(
    /**
     * The span of this operation. e.g., the `a - z - - [h - p]` in `[a - z - - h - p]`.
     */
    public var span: Span,
    /**
     * The type of this set operation.
     */
    public var kind: ClassSetBinaryOpKind,
    /**
     * The left hand side of the operation.
     */
    public var lhs: ClassSet,
    /**
     * The right hand side of the operation.
     */
    public var rhs: ClassSet,
)

/**
 * A single zero-width assertion.
 */
public data class Assertion(
    /**
     * The span of this assertion.
     */
    public var span: Span,
    /**
     * The assertion kind, e.g., `\b` or `^`.
     */
    public var kind: AssertionKind,
)

/**
 * A repetition operation applied to a regular expression.
 */
public data class Repetition(
    /**
     * The span of this operation.
     */
    public var span: Span,
    /**
     * The actual operation.
     */
    public var op: RepetitionOp,
    /**
     * Whether this operation was applied greedily or not.
     */
    public var greedy: Boolean,
    /**
     * The regular expression under repetition.
     */
    public var ast: Ast,
)

/**
 * The repetition operator itself.
 */
public data class RepetitionOp(
    /**
     * The span of this operator. This includes things like `+`, `* ?` and
     * `{ m, n }`.
     */
    public var span: Span,
    /**
     * The type of operation.
     */
    public var kind: RepetitionKind,
)

/**
 * A grouped regular expression.
 *
 * This includes both capturing and non-capturing groups. This does **not**
 * include flag-only groups like `(? is)`, but does contain any group that
 * contains a sub-expression, e.g., `(a)`, `(? P<name> a)`, `(?: a)` and
 * `(? is: a)`.
 */
public data class Group(
    /**
     * The span of this group.
     */
    public var span: Span,
    /**
     * The kind of this group.
     */
    public var kind: GroupKind,
    /**
     * The regular expression in this group.
     */
    public var ast: Ast,
) {
    /**
     * If this group is non-capturing, then this returns the (possibly empty)
     * set of flags. Otherwise, `None` is returned.
     */
    public fun flags(): Flags? =
        when (val matchVal = this.kind) {
            is GroupKind.NonCapturing -> {
                val (flags) = matchVal
                flags
            }
            else -> null
        }

    /**
     * Returns true if and only if this group is capturing.
     */
    public fun isCapturing(): Boolean =
        when (this.kind) {
            is GroupKind.CaptureIndex -> true
            is GroupKind.CaptureName -> true
            is GroupKind.NonCapturing -> false
        }

    /**
     * Returns the capture index of this group, if this is a capturing group.
     *
     * This returns a capture index precisely when `isCapturing` is `true`.
     */
    public fun captureIndex(): UInt? =
        when (val matchVal = this.kind) {
            is GroupKind.CaptureIndex -> {
                val (i) = matchVal
                i
            }
            is GroupKind.CaptureName -> {
                val name = matchVal.name
                name.index
            }
            is GroupKind.NonCapturing -> null
        }
}

/**
 * A capture name.
 *
 * This corresponds to the name itself between the angle brackets in, e.g.,
 * `(? P<foo> expr)`.
 */
public data class CaptureName(
    /**
     * The span of this capture name.
     */
    public var span: Span,
    /**
     * The capture name.
     */
    public var name: String,
    /**
     * The capture index.
     */
    public var index: UInt,
)

/**
 * A group of flags that is not applied to a particular regular expression.
 */
public data class SetFlags(
    /**
     * The span of these flags, including the grouping parentheses.
     */
    public var span: Span,
    /**
     * The actual sequence of flags.
     */
    public var flags: Flags,
)

/**
 * A group of flags.
 *
 * This corresponds only to the sequence of flags themselves, e.g., `is - u`.
 */
public class Flags(
    /**
     * The span of this group of flags.
     */
    public var span: Span,
    /**
     * A sequence of flag items. Each item is either a flag or a negation
     * operator.
     */
    items: List<FlagsItem>,
) {
    internal val mutableItems: MutableList<FlagsItem> = items.toMutableList()

    /**
     * A sequence of flag items. Each item is either a flag or a negation
     * operator.
     */
    public var items: List<FlagsItem>
        get() = this.mutableItems
        set(value) {
            this.mutableItems.clear()
            this.mutableItems.addAll(value)
        }

    public fun copy(
        span: Span = this.span,
        items: List<FlagsItem> = this.items.toList(),
    ): Flags = Flags(span = span, items = items)

    override fun equals(other: Any?): Boolean =
        this === other || (other is Flags && this.span == other.span && this.items == other.items)

    override fun hashCode(): Int = 31 * this.span.hashCode() + this.items.hashCode()

    override fun toString(): String = "Flags(span=${this.span}, items=${this.items})"

    /**
     * Add the given item to this sequence of flags.
     *
     * If the item was added successfully, then `None` is returned. If the
     * given item is a duplicate, then `Some(i)` is returned, where
     * `items[i].kind == item.kind`.
     */
    public fun addItem(item: FlagsItem): Int? {
        for ((i, x) in this.mutableItems.withIndex()) {
            if (x.kind == item.kind) {
                return i
            }
        }
        this.mutableItems.add(item)
        return null
    }

    /**
     * Returns the state of the given flag in this set.
     *
     * If the given flag is in the set but is negated, then `Some(false)` is
     * returned.
     *
     * If the given flag is in the set and is not negated, then `Some(true)`
     * is returned.
     *
     * Otherwise, `None` is returned.
     */
    public fun flagState(flag: Flag): Boolean? {
        var negated = false
        for (x in this.mutableItems) {
            val matchVal = x.kind
            when {
                matchVal == FlagsItemKind.Negation -> {
                    negated = true
                }
                matchVal is FlagsItemKind.Flag && matchVal.value == flag -> {
                    return !negated
                }
                else -> {}
            }
        }
        return null
    }
}

/**
 * A single item in a group of flags.
 */
public data class FlagsItem(
    /**
     * The span of this item.
     */
    public var span: Span,
    /**
     * The kind of this item.
     */
    public var kind: FlagsItemKind,
)
