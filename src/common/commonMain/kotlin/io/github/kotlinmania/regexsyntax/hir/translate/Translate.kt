// port-lint: source hir/translate.rs
package io.github.kotlinmania.regexsyntax.hir.translate

import io.github.kotlinmania.regexsyntax.ast.Assertion
import io.github.kotlinmania.regexsyntax.ast.AssertionKind
import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.ClassAscii
import io.github.kotlinmania.regexsyntax.ast.ClassAsciiKind
import io.github.kotlinmania.regexsyntax.ast.ClassPerl
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOp
import io.github.kotlinmania.regexsyntax.ast.ClassSetItem
import io.github.kotlinmania.regexsyntax.ast.Flag
import io.github.kotlinmania.regexsyntax.ast.FlagsItemKind
import io.github.kotlinmania.regexsyntax.ast.Group
import io.github.kotlinmania.regexsyntax.ast.GroupKind
import io.github.kotlinmania.regexsyntax.ast.Literal
import io.github.kotlinmania.regexsyntax.ast.Repetition
import io.github.kotlinmania.regexsyntax.ast.RepetitionKind
import io.github.kotlinmania.regexsyntax.ast.RepetitionRange
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.visit
import io.github.kotlinmania.regexsyntax.either.Either
import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Class
import io.github.kotlinmania.regexsyntax.hir.ClassBytes
import io.github.kotlinmania.regexsyntax.hir.ClassBytesRange
import io.github.kotlinmania.regexsyntax.hir.ClassUnicode
import io.github.kotlinmania.regexsyntax.hir.ClassUnicodeRange
import io.github.kotlinmania.regexsyntax.hir.Dot
import io.github.kotlinmania.regexsyntax.hir.Error
import io.github.kotlinmania.regexsyntax.hir.ErrorKind
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.visitor.Visitor
import io.github.kotlinmania.regexsyntax.unicode.ClassQuery
import io.github.kotlinmania.regexsyntax.unicode.SimpleCaseFolder
import io.github.kotlinmania.regexsyntax.unicode.cls
import io.github.kotlinmania.regexsyntax.unicode.perlDigit
import io.github.kotlinmania.regexsyntax.unicode.perlSpace
import io.github.kotlinmania.regexsyntax.unicode.perlWord
import io.github.kotlinmania.regexsyntax.unicodetables.graphemeclusterbreak.T

// Defines a translator that converts an `Ast` to an `Hir`.

public typealias Output = Hir

public typealias Err = Error

public typealias Result<T> = kotlin.Result<T>

/**
 * A builder for constructing an AST->HIR translator.
 */
public open class TranslatorBuilder(
    public var utf8: Boolean,
    public var lineTerminator: UByte,
    public var flags: Flags,
) {
    /**
     * Build a translator using the current configuration.
     */
    public open fun build(): Translator = Translator(stack = RefCell.new(mutableListOf()), flags = Cell.new(this.flags), utf8 = this.utf8, lineTerminator = this.lineTerminator)

    /**
     * When disabled, translation will permit the construction of a regular
     * expression that may match invalid UTF-8.
     *
     * When enabled (the default), the translator is guaranteed to produce an
     * expression that, for non-empty matches, will only ever produce spans
     * that are entirely valid UTF-8 (otherwise, the translator will return an
     * error).
     *
     * Perhaps surprisingly, when UTF-8 is enabled, an empty regex or even
     * a negated ASCII word boundary (uttered as `(?-u:\B)` in the concrete
     * syntax) will be allowed even though they can produce matches that split
     * a UTF-8 encoded codepoint. This only applies to zero-width or "empty"
     * matches, and it is expected that the regex engine itself must handle
     * these cases if necessary (perhaps by suppressing any zero-width matches
     * that split a codepoint).
     */
    public open fun utf8(yes: Boolean): TranslatorBuilder {
        this.utf8 = yes
        return this
    }

    /**
     * Sets the line terminator for use with `(? u - s:.)` and `(? - us:.)`.
     *
     * Namely, instead of `.` (by default) matching everything except for `\n`,
     * this will cause `.` to match everything except for the byte given.
     *
     * If `.` is used in a context where Unicode mode is enabled and this byte
     * isn't ASCII, then an error will be returned. When Unicode mode is
     * disabled, then any byte is permitted, but will return an error if UTF-8
     * mode is enabled and it is a non-ASCII byte.
     *
     * In short, any ASCII value for a line terminator is always okay. But a
     * non-ASCII byte might result in an error depending on whether Unicode
     * mode or UTF-8 mode are enabled.
     *
     * Note that if `R` mode is enabled then it always takes precedence and
     * the line terminator will be treated as `\r` and `\n` simultaneously.
     *
     * Note also that this *doesn't* impact the look-around assertions
     * `(? m: ^)` and `(? m: $)`. That's usually controlled by additional
     * configuration in the regex engine itself.
     */
    public open fun lineTerminator(byte: UByte): TranslatorBuilder {
        this.lineTerminator = byte
        return this
    }

    /**
     * Enable or disable the case insensitive flag (`i`) by default.
     */
    public open fun caseInsensitive(yes: Boolean): TranslatorBuilder {
        this.flags.caseInsensitive = if (yes) {
            true
        } else {
            null
        }
        return this
    }

    /**
     * Enable or disable the multi-line matching flag (`m`) by default.
     */
    public open fun multiLine(yes: Boolean): TranslatorBuilder {
        this.flags.multiLine = if (yes) {
            true
        } else {
            null
        }
        return this
    }

    /**
     * Enable or disable the "dot matches any character" flag (`s`) by
     * default.
     */
    public open fun dotMatchesNewLine(yes: Boolean): TranslatorBuilder {
        this.flags.dotMatchesNewLine = if (yes) {
            true
        } else {
            null
        }
        return this
    }

    /**
     * Enable or disable the CRLF mode flag (`R`) by default.
     */
    public open fun crlf(yes: Boolean): TranslatorBuilder {
        this.flags.crlf = if (yes) {
            true
        } else {
            null
        }
        return this
    }

    /**
     * Enable or disable the "swap greed" flag (`U`) by default.
     */
    public open fun swapGreed(yes: Boolean): TranslatorBuilder {
        this.flags.swapGreed = if (yes) {
            true
        } else {
            null
        }
        return this
    }

    /**
     * Enable or disable the Unicode flag (`u`) by default.
     */
    public open fun unicode(yes: Boolean): TranslatorBuilder {
        this.flags.unicode = if (yes) {
            null
        } else {
            false
        }
        return this
    }

    public companion object {
        public fun default(): TranslatorBuilder = TranslatorBuilder.new()

        /**
         * Create a new translator builder with a default configuration.
         */
        public fun new(): TranslatorBuilder = TranslatorBuilder(utf8 = true, lineTerminator = 10u, flags = Flags.default())
    }
}

/**
 * A translator maps abstract syntax to a high level intermediate
 * representation.
 *
 * A translator may be benefit from reuse. That is, a translator can translate
 * many abstract syntax trees.
 *
 * A `Translator` can be configured in more detail via a
 * [TranslatorBuilder].
 */
public open class Translator internal constructor(
    stackCtorArg: RefCell<List<HirFrame>>,
    flagsCtorArg: Cell<Flags>,
    /**
     * Whether we're allowed to produce HIR that can match arbitrary bytes.
     */
    public var utf8: Boolean,
    /**
     * The line terminator to use for `.`.
     */
    public var lineTerminator: UByte,
) {
    /**
     * Our call stack, but on the heap.
     */
    public var stack: RefCell<List<HirFrame>> = stackCtorArg
        internal set

    /**
     * The current flag settings.
     */
    public var flags: Cell<Flags> = flagsCtorArg
        internal set

    /**
     * Translate the given abstract syntax tree (AST) into a high level
     * intermediate representation (HIR).
     *
     * If there was a problem doing the translation, then an HIR-specific
     * error is returned.
     *
     * The original pattern string used to produce the `Ast` *must* also be
     * provided. The translator does not use the pattern string during any
     * correct translation, but is used for error reporting.
     */
    public open fun translate(pattern: String, ast: Ast): Result<Hir> = visit(ast, TranslatorI.new(this, pattern))

    public companion object {
        public operator fun invoke(
            stack: RefCell<List<HirFrame>>,
            flags: Cell<Flags>,
            utf8: Boolean,
            lineTerminator: UByte,
        ): Translator = Translator(
            stackCtorArg = stack,
            flagsCtorArg = flags,
            utf8 = utf8,
            lineTerminator = lineTerminator,
        )

        /**
         * Create a new translator using the default configuration.
         */
        public fun new(): Translator = TranslatorBuilder.new().build()
    }
}

/**
 * An HirFrame is a single stack frame, represented explicitly, which is
 * created for each item in the Ast that we traverse.
 *
 * Note that technically, this type doesn't represent our entire stack
 * frame. In particular, the Ast visitor represents any state associated with
 * traversing the Ast itself.
 */
public sealed class HirFrame {
    /**
     * An arbitrary HIR expression. These get pushed whenever we hit a base
     * case in the Ast. They get popped after an inductive (i.e., recursive)
     * step is complete.
     */
    public data class Expr(public var value: Hir) : HirFrame()
    /**
     * A literal that is being constructed, character by character, from the
     * AST. We need this because the AST gives each individual character its
     * own node. So as we see characters, we peek at the top-most HirFrame.
     * If it's a literal, then we add to it. Otherwise, we push a new literal.
     * When it comes time to pop it, we convert it to an Hir via Hir.literal.
     */
    public data class Literal(public var value: List<UByte>) : HirFrame()
    /**
     * A Unicode character class. This frame is mutated as we descend into
     * the Ast of a character class (which is itself its own mini recursive
     * structure).
     */
    public data class ClassUnicode(public var value: ClassUnicode) : HirFrame()
    /**
     * A byte-oriented character class. This frame is mutated as we descend
     * into the Ast of a character class (which is itself its own mini
     * recursive structure).
     *
     * Byte character classes are created when Unicode mode (`u`) is disabled.
     * If `utf8` is enabled (the default), then a byte character is only
     * permitted to match ASCII text.
     */
    public data class ClassBytes(public var value: ClassBytes) : HirFrame()
    /**
     * This is pushed whenever a repetition is observed. After visiting every
     * sub-expression in the repetition, the translator's stack is expected to
     * have this sentinel at the top.
     *
     * This sentinel only exists to stop other things (like flattening
     * literals) from reaching across repetition operators.
     */
    public data object Repetition : HirFrame()
    /**
     * This is pushed on to the stack upon first seeing any kind of capture,
     * indicated by parentheses (including non-capturing groups). It is popped
     * upon leaving a group.
     */
    public data class Group(public var oldFlags: Flags) : HirFrame()
    /**
     * This is pushed whenever a concatenation is observed. After visiting
     * every sub-expression in the concatenation, the translator's stack is
     * popped until it sees a Concat frame.
     */
    public data object Concat : HirFrame()
    /**
     * This is pushed whenever an alternation is observed. After visiting
     * every sub-expression in the alternation, the translator's stack is
     * popped until it sees an Alternation frame.
     */
    public data object Alternation : HirFrame()
    /**
     * This is pushed immediately before each sub-expression in an
     * alternation. This separates the branches of an alternation on the
     * stack and prevents literal flattening from reaching across alternation
     * branches.
     *
     * It is popped after each expression in a branch until an 'Alternation'
     * frame is observed when doing a post visit on an alternation.
     */
    public data object AlternationBranch : HirFrame()

    /**
     * Assert that the current stack frame is an Hir expression and return it.
     */
    public open fun unwrapExpr(): Hir =
        when (this) {
            is HirFrame.Expr -> {
                val (expr) = this
                expr
            }
            is HirFrame.Literal -> {
                val (lit) = this
                Hir.literal(lit)
            }
            else -> error("tried to unwrap expr from HirFrame, got: $this")
        }

    /**
     * Assert that the current stack frame is a Unicode class expression and
     * return it.
     */
    public open fun unwrapClassUnicode(): ClassUnicode =
        when (this) {
            is HirFrame.ClassUnicode -> {
                val (cls) = this
                cls
            }
            else -> error("tried to unwrap Unicode class from HirFrame, got: $this")
        }

    /**
     * Assert that the current stack frame is a byte class expression and
     * return it.
     */
    public open fun unwrapClassBytes(): ClassBytes =
        when (this) {
            is HirFrame.ClassBytes -> {
                val (cls) = this
                cls
            }
            else -> error("tried to unwrap byte class from HirFrame, got: $this")
        }

    /**
     * Assert that the current stack frame is a repetition sentinel. If it
     * isn't, then panic.
     */
    public open fun unwrapRepetition() {
        when (this) {
            HirFrame.Repetition -> {}
            else -> {
                error("tried to unwrap repetition from HirFrame, got: $this")
            }
        }
    }

    /**
     * Assert that the current stack frame is a group indicator and return
     * its corresponding flags (the flags that were active at the time the
     * group was entered).
     */
    public open fun unwrapGroup(): Flags =
        when (this) {
            is HirFrame.Group -> {
                val oldFlags = this.oldFlags
                oldFlags
            }
            else -> {
                error("tried to unwrap group from HirFrame, got: $this")
            }
        }

    /**
     * Assert that the current stack frame is an alternation pipe sentinel. If
     * it isn't, then panic.
     */
    public open fun unwrapAlternationPipe() {
        when (this) {
            HirFrame.AlternationBranch -> {}
            else -> {
                error("tried to unwrap alt pipe from HirFrame, got: $this")
            }
        }
    }
}

/**
 * The internal implementation of a translator.
 *
 * This type is responsible for carrying around the original pattern string,
 * which is not tied to the internal state of a translator.
 *
 * A TranslatorI exists for the time it takes to translate a single Ast.
 */
public open class TranslatorI(
    public var trans: Translator,
    public var pattern: String,
) : Visitor {
    public open fun finish(): Result<Hir> {
        // ... otherwise, we should have exactly one HIR on the stack.
        check(this.trans().stack.borrow().len() == 1)
        return Result.success(this.pop()!!.unwrapExpr())
    }

    public open fun visitPre(ast: Ast): Result<Unit> {
        when (ast) {
            is Ast.ClassBracketed -> {
                if (this.flags().unicode()) {
                    val cls = ClassUnicode.empty()
                    this.push(HirFrame.ClassUnicode(cls))
                } else {
                    val cls1 = ClassBytes.empty()
                    this.push(HirFrame.ClassBytes(cls1))
                }
            }
            is Ast.Repetition -> this.push(HirFrame.Repetition)
            is Ast.Group -> {
                val (x) = ast
                val oldFlags = x.flags().map({ ast1 -> this.setFlags(ast1) }).unwrapOrElse({ this.flags() })
                this.push(HirFrame.Group(oldFlags = oldFlags))
            }
            is Ast.Concat -> {
                this.push(HirFrame.Concat)
            }
            is Ast.Alternation -> {
                val (x1) = ast
                this.push(HirFrame.Alternation)
                if (!x1.asts.isEmpty()) {
                    this.push(HirFrame.AlternationBranch)
                }
            }
            else -> {}
        }
        return Result.success(Unit)
    }

    public open fun visitPost(ast: Ast): Result<Unit> {
        when (ast) {
            is Ast.Empty -> {
                this.push(HirFrame.Expr(Hir.empty()))
            }
            is Ast.Flags -> {
                val (x) = ast
                this.setFlags(x.flags)
                // Flags in the AST are generally considered directives and
                // not actual sub-expressions. However, they can be used in
                // the concrete syntax like `((? i))`, and we need some kind of
                // indication of an expression there, and Empty is the correct
                // choice.
                //
                // There can also be things like `(? i) +`, but we rule those out
                // in the parser. In the future, we might allow them for
                // consistency sake.
                this.push(HirFrame.Expr(Hir.empty()))
            }
            is Ast.Literal -> {
                val (x1) = ast
                when (val matchVal = this.astLiteralToScalar(x1).getOrElse { return Result.failure(it) }) {
                    is Either.Right -> {
                        val (byte) = matchVal
                        this.pushByte(byte)
                    }
                    is Either.Left -> {
                        val (ch) = matchVal
                        when (val matchVal1 = this.caseFoldChar(x1.span, ch).getOrElse { return Result.failure(it) }) {
                            null -> this.pushChar(ch)
                            else -> {
                                val expr = matchVal1
                                this.push(HirFrame.Expr(expr))
                            }
                        }
                    }
                }
            }
            is Ast.Dot -> {
                val (span) = ast
                this.push(HirFrame.Expr(this.hirDot(span).getOrElse { return Result.failure(it) }))
            }
            is Ast.Assertion -> {
                val (x2) = ast
                this.push(HirFrame.Expr(this.hirAssertion(x2).getOrElse { return Result.failure(it) }))
            }
            is Ast.ClassPerl -> {
                val (x3) = ast
                if (this.flags().unicode()) {
                    val cls = this.hirPerlUnicodeClass(x3).getOrElse { return Result.failure(it) }
                    val hcls = Class.Unicode(cls)
                    this.push(HirFrame.Expr(Hir.cls(hcls)))
                } else {
                    val cls1 = this.hirPerlByteClass(x3).getOrElse { return Result.failure(it) }
                    val hcls1 = Class.Bytes(cls1)
                    this.push(HirFrame.Expr(Hir.cls(hcls1)))
                }
            }
            is Ast.ClassUnicode -> {
                val (x4) = ast
                val cls2 = Class.Unicode(this.hirUnicodeClass(x4).getOrElse { return Result.failure(it) })
                this.push(HirFrame.Expr(Hir.cls(cls2)))
            }
            is Ast.ClassBracketed -> {
                val (ast1) = ast
                if (this.flags().unicode()) {
                    val cls3 = this.pop()!!.unwrapClassUnicode()
                    this.unicodeFoldAndNegate(ast1.span, ast1.negated, cls3).getOrElse { return Result.failure(it) }
                    val expr1 = Hir.cls(Class.Unicode(cls3))
                    this.push(HirFrame.Expr(expr1))
                } else {
                    val cls4 = this.pop()!!.unwrapClassBytes()
                    this.bytesFoldAndNegate(ast1.span, ast1.negated, cls4).getOrElse { return Result.failure(it) }
                    val expr2 = Hir.cls(Class.Bytes(cls4))
                    this.push(HirFrame.Expr(expr2))
                }
            }
            is Ast.Repetition -> {
                val (x5) = ast
                val expr3 = this.pop()!!.unwrapExpr()
                this.pop()!!.unwrapRepetition()
                this.push(HirFrame.Expr(this.hirRepetition(x5, expr3)))
            }
            is Ast.Group -> {
                val (x6) = ast
                val expr4 = this.pop()!!.unwrapExpr()
                val oldFlags = this.pop()!!.unwrapGroup()
                this.trans().flags.set(oldFlags)
                this.push(HirFrame.Expr(this.hirCapture(x6, expr4)))
            }
            is Ast.Concat -> {
                val exprs = mutableListOf()
                while (true) {
                    val matchVal2 = this.popConcatExpr()
                    if (matchVal2 == null) break
                    val expr5 = matchVal2
                    if (!when (expr5.kind()) {
                        HirKind.Empty -> true
                        else -> false
                    }) {
                        exprs.add(expr5)
                    }
                }
                exprs.reverse()
                this.push(HirFrame.Expr(Hir.concat(exprs)))
            }
            is Ast.Alternation -> {
                val exprs1 = mutableListOf()
                while (true) {
                    val matchVal3 = this.popAltExpr()
                    if (matchVal3 == null) break
                    val expr6 = matchVal3
                    this.pop()!!.unwrapAlternationPipe()
                    exprs1.add(expr6)
                }
                exprs1.reverse()
                this.push(HirFrame.Expr(Hir.alternation(exprs1)))
            }
        }
        return Result.success(Unit)
    }

    public open fun visitAlternationIn(): Result<Unit> {
        this.push(HirFrame.AlternationBranch)
        return Result.success(Unit)
    }

    public open fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit> {
        when (ast) {
            is ClassSetItem.Bracketed -> {
                if (this.flags().unicode()) {
                    val cls = ClassUnicode.empty()
                    this.push(HirFrame.ClassUnicode(cls))
                } else {
                    val cls1 = ClassBytes.empty()
                    this.push(HirFrame.ClassBytes(cls1))
                }
            }
            // We needn't handle the Union case here since the visitor will
            // do it for us.
            else -> {}
        }
        return Result.success(Unit)
    }

    public open fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit> {
        when (ast) {
            is ClassSetItem.Empty -> {}
            is ClassSetItem.Literal -> {
                val (x) = ast
                if (this.flags().unicode()) {
                    val cls = this.pop()!!.unwrapClassUnicode()
                    cls.push(ClassUnicodeRange.new(x.c, x.c))
                    this.push(HirFrame.ClassUnicode(cls))
                } else {
                    val cls1 = this.pop()!!.unwrapClassBytes()
                    val byte = this.classLiteralByte(x).getOrElse { return Result.failure(it) }
                    cls1.push(ClassBytesRange.new(byte, byte))
                    this.push(HirFrame.ClassBytes(cls1))
                }
            }
            is ClassSetItem.Range -> {
                val (x1) = ast
                if (this.flags().unicode()) {
                    val cls2 = this.pop()!!.unwrapClassUnicode()
                    cls2.push(ClassUnicodeRange.new(x1.start.c, x1.end.c))
                    this.push(HirFrame.ClassUnicode(cls2))
                } else {
                    val cls3 = this.pop()!!.unwrapClassBytes()
                    val start = this.classLiteralByte(x1.start).getOrElse { return Result.failure(it) }
                    val end = this.classLiteralByte(x1.end).getOrElse { return Result.failure(it) }
                    cls3.push(ClassBytesRange.new(start, end))
                    this.push(HirFrame.ClassBytes(cls3))
                }
            }
            is ClassSetItem.Ascii -> {
                val (x2) = ast
                if (this.flags().unicode()) {
                    val xcls = this.hirAsciiUnicodeClass(x2).getOrElse { return Result.failure(it) }
                    val cls4 = this.pop()!!.unwrapClassUnicode()
                    cls4.union(xcls)
                    this.push(HirFrame.ClassUnicode(cls4))
                } else {
                    val xcls1 = this.hirAsciiByteClass(x2).getOrElse { return Result.failure(it) }
                    val cls5 = this.pop()!!.unwrapClassBytes()
                    cls5.union(xcls1)
                    this.push(HirFrame.ClassBytes(cls5))
                }
            }
            is ClassSetItem.Unicode -> {
                val (x3) = ast
                val xcls2 = this.hirUnicodeClass(x3).getOrElse { return Result.failure(it) }
                var cls6 = this.pop()!!.unwrapClassUnicode()
                cls6.union(xcls2)
                this.push(HirFrame.ClassUnicode(cls6))
            }
            is ClassSetItem.Perl -> {
                val (x4) = ast
                if (this.flags().unicode()) {
                    val xcls3 = this.hirPerlUnicodeClass(x4).getOrElse { return Result.failure(it) }
                    val cls7 = this.pop()!!.unwrapClassUnicode()
                    cls7.union(xcls3)
                    this.push(HirFrame.ClassUnicode(cls7))
                } else {
                    val xcls4 = this.hirPerlByteClass(x4).getOrElse { return Result.failure(it) }
                    val cls8 = this.pop()!!.unwrapClassBytes()
                    cls8.union(xcls4)
                    this.push(HirFrame.ClassBytes(cls8))
                }
            }
            is ClassSetItem.Bracketed -> {
                val (ast1) = ast
                if (this.flags().unicode()) {
                    val cls1 = this.pop()!!.unwrapClassUnicode()
                    this.unicodeFoldAndNegate(ast1.span, ast1.negated, cls1).getOrElse { return Result.failure(it) }
                    val cls2 = this.pop()!!.unwrapClassUnicode()
                    cls2.union(cls1)
                    this.push(HirFrame.ClassUnicode(cls2))
                } else {
                    val cls11 = this.pop()!!.unwrapClassBytes()
                    this.bytesFoldAndNegate(ast1.span, ast1.negated, cls11).getOrElse { return Result.failure(it) }
                    val cls21 = this.pop()!!.unwrapClassBytes()
                    cls21.union(cls11)
                    this.push(HirFrame.ClassBytes(cls21))
                }
            }
            // This is handled automatically by the visitor.
            is ClassSetItem.Union -> {}
        }
        return Result.success(Unit)
    }

    public open fun visitClassSetBinaryOpPre(op: ClassSetBinaryOp): Result<Unit> {
        if (this.flags().unicode()) {
            val cls = ClassUnicode.empty()
            this.push(HirFrame.ClassUnicode(cls))
        } else {
            val cls1 = ClassBytes.empty()
            this.push(HirFrame.ClassBytes(cls1))
        }
        return Result.success(Unit)
    }

    public open fun visitClassSetBinaryOpIn(op: ClassSetBinaryOp): Result<Unit> {
        if (this.flags().unicode()) {
            val cls = ClassUnicode.empty()
            this.push(HirFrame.ClassUnicode(cls))
        } else {
            val cls1 = ClassBytes.empty()
            this.push(HirFrame.ClassBytes(cls1))
        }
        return Result.success(Unit)
    }

    public open fun visitClassSetBinaryOpPost(op: ClassSetBinaryOp): Result<Unit> {
        if (this.flags().unicode()) {
            val rhs = this.pop()!!.unwrapClassUnicode()
            val lhs = this.pop()!!.unwrapClassUnicode()
            val cls = this.pop()!!.unwrapClassUnicode()
            if (this.flags().caseInsensitive()) {
                rhs.tryCaseFoldSimple().mapErr({
                    this.error(op.rhs.span().clone(), ErrorKind.UnicodeCaseUnavailable)
                }).getOrElse { return Result.failure(it) }
                lhs.tryCaseFoldSimple().mapErr({
                    this.error(op.lhs.span().clone(), ErrorKind.UnicodeCaseUnavailable)
                }).getOrElse { return Result.failure(it) }
            }
            when (op.kind) {
                Intersection -> lhs.intersect(rhs)
                Difference -> lhs.difference(rhs)
                SymmetricDifference -> lhs.symmetricDifference(rhs)
            }
            cls.union(lhs)
            this.push(HirFrame.ClassUnicode(cls))
        } else {
            val rhs1 = this.pop()!!.unwrapClassBytes()
            val lhs1 = this.pop()!!.unwrapClassBytes()
            val cls1 = this.pop()!!.unwrapClassBytes()
            if (this.flags().caseInsensitive()) {
                rhs1.caseFoldSimple()
                lhs1.caseFoldSimple()
            }
            when (op.kind) {
                Intersection -> lhs1.intersect(rhs1)
                Difference -> lhs1.difference(rhs1)
                SymmetricDifference -> lhs1.symmetricDifference(rhs1)
            }
            cls1.union(lhs1)
            this.push(HirFrame.ClassBytes(cls1))
        }
        return Result.success(Unit)
    }

    /**
     * Return a reference to the underlying translator.
     */
    public open fun trans(): Translator = this.trans

    /**
     * Push the given frame on to the call stack.
     */
    public open fun push(frame: HirFrame) {
        this.trans().stack.borrowMut().push(frame)
    }

    /**
     * Push the given literal char on to the call stack.
     *
     * If the top-most element of the stack is a literal, then the char
     * is appended to the end of that literal. Otherwise, a new literal
     * containing just the given char is pushed to the top of the stack.
     */
    public open fun pushChar(ch: Char) {
        val buf = List(4) { 0 }
        val bytes = ch.encodeUtf8(buf).encodeToByteArray().map { it.toUByte() }
        val stack = this.trans().stack.borrowMut()
        when (val matchVal = stack.lastMut()) {
            is Some -> {
                val (literal) = matchVal as HirFrame.Literal
                literal.extendFromSlice(bytes)
            }
            else -> {
                stack.push(HirFrame.Literal(bytes.toVec()))
            }
        }
    }

    /**
     * Push the given literal byte on to the call stack.
     *
     * If the top-most element of the stack is a literal, then the byte
     * is appended to the end of that literal. Otherwise, a new literal
     * containing just the given byte is pushed to the top of the stack.
     */
    public open fun pushByte(byte: UByte) {
        val stack = this.trans().stack.borrowMut()
        when (val matchVal = stack.lastMut()) {
            is Some -> {
                val (literal) = matchVal as HirFrame.Literal
                literal.push(byte)
            }
            else -> {
                stack.push(HirFrame.Literal(mutableListOf(byte)))
            }
        }
    }

    /**
     * Pop the top of the call stack. If the call stack is empty, return None.
     */
    public open fun pop(): HirFrame? = this.trans().stack.borrowMut().pop()

    /**
     * Pop an HIR expression from the top of the stack for a concatenation.
     *
     * This returns None if the stack is empty or when a concat frame is seen.
     * Otherwise, it panics if it could not find an HIR expression.
     */
    public open fun popConcatExpr(): Hir? {
        val frame = (this.pop() ?: return null)
        return when (frame) {
            HirFrame.Concat -> null
            is HirFrame.Expr -> {
                val (expr) = frame
                expr
            }
            is HirFrame.Literal -> {
                val (lit) = frame
                Hir.literal(lit)
            }
            is HirFrame.ClassUnicode -> {
                error("expected expr or concat, got Unicode class")
            }
            is HirFrame.ClassBytes -> {
                error("expected expr or concat, got byte class")
            }
            HirFrame.Repetition -> {
                error("expected expr or concat, got repetition")
            }
            is HirFrame.Group -> {
                error("expected expr or concat, got group")
            }
            HirFrame.Alternation -> {
                error("expected expr or concat, got alt marker")
            }
            HirFrame.AlternationBranch -> {
                error("expected expr or concat, got alt branch marker")
            }
        }
    }

    /**
     * Pop an HIR expression from the top of the stack for an alternation.
     *
     * This returns None if the stack is empty or when an alternation frame is
     * seen. Otherwise, it panics if it could not find an HIR expression.
     */
    public open fun popAltExpr(): Hir? {
        val frame = (this.pop() ?: return null)
        return when (frame) {
            HirFrame.Alternation -> null
            is HirFrame.Expr -> {
                val (expr) = frame
                expr
            }
            is HirFrame.Literal -> {
                val (lit) = frame
                Hir.literal(lit)
            }
            is HirFrame.ClassUnicode -> {
                error("expected expr or alt, got Unicode class")
            }
            is HirFrame.ClassBytes -> {
                error("expected expr or alt, got byte class")
            }
            HirFrame.Repetition -> {
                error("expected expr or alt, got repetition")
            }
            is HirFrame.Group -> {
                error("expected expr or alt, got group")
            }
            HirFrame.Concat -> {
                error("expected expr or alt, got concat marker")
            }
            HirFrame.AlternationBranch -> {
                error("expected expr or alt, got alt branch marker")
            }
        }
    }

    /**
     * Create a new error with the given span and error type.
     */
    public open fun error(span: Span, kind: ErrorKind): Error = Error(kind = kind, pattern = this.pattern.toString(), span = span)

    /**
     * Return a copy of the active flags.
     */
    public open fun flags(): Flags = this.trans().flags.get()

    /**
     * Set the flags of this translator from the flags set in the given AST.
     * Then, return the old flags.
     */
    public open fun setFlags(astFlags: io.github.kotlinmania.regexsyntax.ast.Flags): Flags {
        val oldFlags = this.flags()
        val newFlags = Flags.fromAst(astFlags)
        newFlags.merge(oldFlags)
        this.trans().flags.set(newFlags)
        return oldFlags
    }

    /**
     * Convert an Ast literal to its scalar representation.
     *
     * When Unicode mode is enabled, then this always succeeds and returns a
     * `char` (Unicode scalar value).
     *
     * When Unicode mode is disabled, then a `char` will still be returned
     * whenever possible. A byte is returned only when invalid UTF-8 is
     * allowed and when the byte is not ASCII. Otherwise, a non-ASCII byte
     * will result in an error when invalid UTF-8 is not allowed.
     */
    public open fun astLiteralToScalar(lit: Literal): Result<Either<Char, UByte>> {
        if (this.flags().unicode()) {
            return Result.success(Either.Left(lit.c))
        }
        val byte1 =
            when (val matchVal = lit.byte()) {
                null -> { return Result.success(Either.Left(lit.c)) }
                else -> {
                    val byte = matchVal
                    byte
                }
            }
        if (byte1 <= 0x7F) {
            return Result.success(Either.Left(Char.tryFrom(byte1).unwrap()))
        }
        if (this.trans().utf8) {
            return Err(this.error(lit.span, ErrorKind.InvalidUtf8))
        }
        return Result.success(Either.Right(byte1))
    }

    public open fun caseFoldChar(span: Span, c: Char): Result<Hir?> {
        if (!this.flags().caseInsensitive()) {
            return Result.success(null)
        }
        return if (this.flags().unicode()) {
            // If case folding won't do anything, then don't bother trying.
            val map =
                SimpleCaseFolder.new().map({ f -> f.overlaps(c, c) }).mapErr({
                    this.error(span, ErrorKind.UnicodeCaseUnavailable)
                }).getOrElse { return Result.failure(it) }
            if (!map) {
                return Result.success(null)
            }
            val cls = ClassUnicode.new(mutableListOf(ClassUnicodeRange.new(c, c)))
            cls.tryCaseFoldSimple().mapErr({
                this.error(span, ErrorKind.UnicodeCaseUnavailable)
            }).getOrElse { return Result.failure(it) }
            Result.success(Hir.cls(Class.Unicode(cls)))
        } else {
            if (!c.isAscii()) {
                return Result.success(null)
            }
            // If case folding won't do anything, then don't bother trying.
            when (c) {
                in 'A'..'Z', in 'a'..'z' -> {}
                else -> { return Result.success(null) }
            }
            val cls1 = ClassBytes.new(mutableListOf(ClassBytesRange.new(UByte.tryFrom(c).unwrap(), UByte.tryFrom(c).unwrap())))
            cls1.caseFoldSimple()
            Result.success(Hir.cls(Class.Bytes(cls1)))
        }
    }

    public open fun hirDot(span: Span): Result<Hir> {
        val (utf8, lineterm, flags) = Triple(this.trans().utf8, this.trans().lineTerminator, this.flags())
        if (utf8 && (!flags.unicode() || !lineterm.isAscii())) {
            return Err(this.error(span, ErrorKind.InvalidUtf8))
        }
        val dot =
            if (flags.dotMatchesNewLine()) {
                if (flags.unicode()) {
                    Dot.AnyChar
                } else {
                    Dot.AnyByte
                }
            } else {
                if (flags.unicode()) {
                    if (flags.crlf()) {
                        Dot.AnyCharExceptCRLF
                    } else {
                        if (!lineterm.isAscii()) {
                            return Err(this.error(span, ErrorKind.InvalidLineTerminator))
                        }
                        Dot.AnyCharExcept(Char.from(lineterm))
                    }
                } else {
                    if (flags.crlf()) {
                        Dot.AnyByteExceptCRLF
                    } else {
                        Dot.AnyByteExcept(lineterm)
                    }
                }
            }
        return Result.success(Hir.dot(dot))
    }

    public open fun hirAssertion(asst: Assertion): Result<Hir> {
        val unicode = this.flags().unicode()
        val multiLine = this.flags().multiLine()
        val crlf = this.flags().crlf()
        return Result.success(when (asst.kind) {
            AssertionKind.StartLine -> Hir.look(if (multiLine) {
                if (crlf) {
                    Look.StartCRLF
                } else {
                    Look.StartLF
                }
            } else {
                Look.Start
            })
            AssertionKind.EndLine -> Hir.look(if (multiLine) {
                if (crlf) {
                    Look.EndCRLF
                } else {
                    Look.EndLF
                }
            } else {
                Look.End
            })
            AssertionKind.StartText -> Hir.look(Look.Start)
            AssertionKind.EndText -> Hir.look(Look.End)
            AssertionKind.WordBoundary -> Hir.look(if (unicode) {
                Look.WordUnicode
            } else {
                Look.WordAscii
            })
            AssertionKind.NotWordBoundary -> Hir.look(if (unicode) {
                Look.WordUnicodeNegate
            } else {
                Look.WordAsciiNegate
            })
            AssertionKind.WordBoundaryStart, AssertionKind.WordBoundaryStartAngle -> {
                Hir.look(if (unicode) {
                    Look.WordStartUnicode
                } else {
                    Look.WordStartAscii
                })
            }
            AssertionKind.WordBoundaryEnd, AssertionKind.WordBoundaryEndAngle -> {
                Hir.look(if (unicode) {
                    Look.WordEndUnicode
                } else {
                    Look.WordEndAscii
                })
            }
            AssertionKind.WordBoundaryStartHalf -> {
                Hir.look(if (unicode) {
                    Look.WordStartHalfUnicode
                } else {
                    Look.WordStartHalfAscii
                })
            }
            AssertionKind.WordBoundaryEndHalf -> Hir.look(if (unicode) {
                Look.WordEndHalfUnicode
            } else {
                Look.WordEndHalfAscii
            })
        })
    }

    public open fun hirCapture(group: Group, expr: Hir): Hir {
        val (index1, name1) =
            when (val matchVal = group.kind) {
                is GroupKind.CaptureIndex -> {
                    val (index) = matchVal
                    Pair(index, null)
                }
                is GroupKind.CaptureName -> {
                    val name = matchVal.name
                    Pair(name.index, name.name.clone().intoBoxedStr())
                }
                // The HIR doesn't need to use non-capturing groups, since the way
                // in which the data type is defined handles this automatically.
                is GroupKind.NonCapturing -> { return expr }
            }
        return Hir.capture(Capture(index = index1, name = name1, sub = expr))
    }

    public open fun hirRepetition(rep: Repetition, expr: Hir): Hir {
        val (min, max) =
            run {
                val matchVal = rep.op.kind
                when {
                    matchVal == RepetitionKind.ZeroOrOne -> Pair(0, 1)
                    matchVal == RepetitionKind.ZeroOrMore -> Pair(0, null)
                    matchVal == RepetitionKind.OneOrMore -> Pair(1, null)
                    matchVal is RepetitionKind.Range && matchVal.value is RepetitionRange.Exactly -> {
                        val (m) = matchVal.value as RepetitionRange.Exactly
                        Pair(m, m)
                    }
                    matchVal is RepetitionKind.Range && matchVal.value is RepetitionRange.AtLeast -> {
                        val (m1) = matchVal.value as RepetitionRange.AtLeast
                        Pair(m1, null)
                    }
                    else -> {
                        val (m2, n) = matchVal.value as RepetitionRange.Bounded
                        Pair(m2, n)
                    }
                }
            }
        val greedy =
            if (this.flags().swapGreed()) {
                !rep.greedy
            } else {
                rep.greedy
            }
        return Hir.repetition(io.github.kotlinmania.regexsyntax.hir.Repetition(min = min, max = max, greedy = greedy, sub = expr))
    }

    public open fun hirUnicodeClass(astClass: io.github.kotlinmania.regexsyntax.ast.ClassUnicode): Result<ClassUnicode> {
        if (!this.flags().unicode()) {
            return Err(this.error(astClass.span, ErrorKind.UnicodeNotAllowed))
        }
        val query =
            when (val matchVal = astClass.kind) {
                is OneLetter -> {
                    val (name) = matchVal
                    ClassQuery.OneLetter(name)
                }
                is Named -> {
                    val (name1) = matchVal
                    ClassQuery.Binary(name1)
                }
                is NamedValue -> {
                    val name2 = matchVal.name
                    val value = matchVal.value
                    ClassQuery.ByValue(propertyName = name2, propertyValue = value)
                }
            }
        val result = this.convertUnicodeClassError(astClass.span, cls(query))
        if (result is Ok) {
            val (cls) = result
            this.unicodeFoldAndNegate(astClass.span, astClass.isNegated(), cls).getOrElse { return Result.failure(it) }
        }
        return result
    }

    public open fun hirAsciiUnicodeClass(ast: ClassAscii): Result<ClassUnicode> {
        val cls = ClassUnicode.new(asciiClassAsChars(ast.kind).map({ (s, e) -> ClassUnicodeRange.new(s, e) }))
        this.unicodeFoldAndNegate(ast.span, ast.negated, cls).getOrElse { return Result.failure(it) }
        return Result.success(cls)
    }

    public open fun hirAsciiByteClass(ast: ClassAscii): Result<ClassBytes> {
        val cls = ClassBytes.new(asciiClass(ast.kind).map({ (s, e) -> ClassBytesRange.new(s, e) }))
        this.bytesFoldAndNegate(ast.span, ast.negated, cls).getOrElse { return Result.failure(it) }
        return Result.success(cls)
    }

    public open fun hirPerlUnicodeClass(astClass: ClassPerl): Result<ClassUnicode> {
        check(this.flags().unicode())
        val result =
            when (astClass.kind) {
                Digit -> perlDigit()
                Space -> perlSpace()
                Word -> perlWord()
            }
        val cls = this.convertUnicodeClassError(astClass.span, result).getOrElse { return Result.failure(it) }
        // We needn't apply case folding here because the Perl Unicode classes
        // are already closed under Unicode simple case folding.
        if (astClass.negated) {
            cls.negate()
        }
        return Result.success(cls)
    }

    public open fun hirPerlByteClass(astClass: ClassPerl): Result<ClassBytes> {
        check(!this.flags().unicode())
        var cls =
            when (astClass.kind) {
                Digit -> hirAsciiClassBytes(ClassAsciiKind.Digit)
                Space -> hirAsciiClassBytes(ClassAsciiKind.Space)
                Word -> hirAsciiClassBytes(ClassAsciiKind.Word)
            }
        // We needn't apply case folding here because the Perl ASCII classes
        // are already closed (under ASCII case folding).
        if (astClass.negated) {
            cls.negate()
        }
        // Negating a Perl byte class is likely to cause it to match invalid
        // UTF-8. That's only OK if the translator is configured to allow such
        // things.
        if (this.trans().utf8 && !cls.isAscii()) {
            return Err(this.error(astClass.span, ErrorKind.InvalidUtf8))
        }
        return Result.success(cls)
    }

    /**
     * Converts the given Unicode specific error to an HIR translation error.
     *
     * The span given should approximate the position at which an error would
     * occur.
     */
    public open fun convertUnicodeClassError(span: Span, result: kotlin.Result<ClassUnicode>): Result<ClassUnicode> =
        result.mapErr({ err ->
            val sp = span.clone()
            when (err) {
                io.github.kotlinmania.regexsyntax.unicode.Error.PropertyNotFound -> {
                    this.error(sp, ErrorKind.UnicodePropertyNotFound)
                }
                io.github.kotlinmania.regexsyntax.unicode.Error.PropertyValueNotFound -> {
                    this.error(sp, ErrorKind.UnicodePropertyValueNotFound)
                }
                io.github.kotlinmania.regexsyntax.unicode.Error.PerlClassNotFound -> {
                    this.error(sp, ErrorKind.UnicodePerlClassNotFound)
                }
            }
        })

    public open fun unicodeFoldAndNegate(span: Span, negated: Boolean, cls: ClassUnicode): Result<Unit> {
        // Note that we must apply case folding before negation!
        // Consider `(? i)[^ x]`. If we applied negation first, then
        // the result would be the character class that matched any
        // Unicode scalar value.
        if (this.flags().caseInsensitive()) {
            cls.tryCaseFoldSimple().mapErr({
                this.error(span.clone(), ErrorKind.UnicodeCaseUnavailable)
            }).getOrElse { return Result.failure(it) }
        }
        if (negated) {
            cls.negate()
        }
        return Result.success(Unit)
    }

    public open fun bytesFoldAndNegate(span: Span, negated: Boolean, cls: ClassBytes): Result<Unit> {
        // Note that we must apply case folding before negation!
        // Consider `(? i)[^ x]`. If we applied negation first, then
        // the result would be the character class that matched any
        // Unicode scalar value.
        if (this.flags().caseInsensitive()) {
            cls.caseFoldSimple()
        }
        if (negated) {
            cls.negate()
        }
        if (this.trans().utf8 && !cls.isAscii()) {
            return Err(this.error(span.clone(), ErrorKind.InvalidUtf8))
        }
        return Result.success(Unit)
    }

    /**
     * Return a scalar byte value suitable for use as a literal in a byte
     * character class.
     */
    public open fun classLiteralByte(ast: Literal): Result<UByte> {
        return when (val matchVal = this.astLiteralToScalar(ast).getOrElse { return Result.failure(it) }) {
            is Either.Right -> {
                val (byte) = matchVal
                Result.success(byte)
            }
            is Either.Left -> {
                val (ch) = matchVal
                if (ch.isAscii()) {
                    Result.success(UByte.tryFrom(ch).unwrap())
                } else {
                    // We can't feasibly support Unicode in
                    // byte oriented classes. Byte classes don't
                    // do Unicode case folding.
                    Err(this.error(ast.span, ErrorKind.UnicodeNotAllowed))
                }
            }
        }
    }

    public companion object {
        /**
         * Build a new internal translator.
         */
        public fun new(trans: Translator, pattern: String): TranslatorI = TranslatorI(trans = trans, pattern = pattern)
    }
}

/**
 * A translator's representation of a regular expression's flags at any given
 * moment in time.
 *
 * Each flag can be in one of three states: absent, present but disabled or
 * present but enabled.
 */
public open class Flags(
    public var caseInsensitive: Boolean?,
    public var multiLine: Boolean?,
    public var dotMatchesNewLine: Boolean?,
    public var swapGreed: Boolean?,
    public var unicode: Boolean?,
    public var crlf: Boolean?,
) {
    public open fun merge(previous: Flags) {
        if (this.caseInsensitive == null) {
            this.caseInsensitive = previous.caseInsensitive
        }
        if (this.multiLine == null) {
            this.multiLine = previous.multiLine
        }
        if (this.dotMatchesNewLine == null) {
            this.dotMatchesNewLine = previous.dotMatchesNewLine
        }
        if (this.swapGreed == null) {
            this.swapGreed = previous.swapGreed
        }
        if (this.unicode == null) {
            this.unicode = previous.unicode
        }
        if (this.crlf == null) {
            this.crlf = previous.crlf
        }
    }

    public open fun caseInsensitive(): Boolean = (this.caseInsensitive ?: false)

    public open fun multiLine(): Boolean = (this.multiLine ?: false)

    public open fun dotMatchesNewLine(): Boolean = (this.dotMatchesNewLine ?: false)

    public open fun swapGreed(): Boolean = (this.swapGreed ?: false)

    public open fun unicode(): Boolean = (this.unicode ?: true)

    public open fun crlf(): Boolean = (this.crlf ?: false)

    public companion object {
        public fun fromAst(ast: io.github.kotlinmania.regexsyntax.ast.Flags): Flags {
            val flags = Flags.default()
            var enable = true
            for (item in ast.items) {
                run {
                    val matchVal = item.kind
                    when {
                        matchVal == FlagsItemKind.Negation -> {
                            enable = false
                        }
                        matchVal is FlagsItemKind.Flag && matchVal.value == Flag.CaseInsensitive -> {
                            flags.caseInsensitive = enable
                        }
                        matchVal is FlagsItemKind.Flag && matchVal.value == Flag.MultiLine -> {
                            flags.multiLine = enable
                        }
                        matchVal is FlagsItemKind.Flag && matchVal.value == Flag.DotMatchesNewLine -> {
                            flags.dotMatchesNewLine = enable
                        }
                        matchVal is FlagsItemKind.Flag && matchVal.value == Flag.SwapGreed -> {
                            flags.swapGreed = enable
                        }
                        matchVal is FlagsItemKind.Flag && matchVal.value == Flag.Unicode -> {
                            flags.unicode = enable
                        }
                        matchVal is FlagsItemKind.Flag && matchVal.value == Flag.CRLF -> {
                            flags.crlf = enable
                        }
                        else -> {}
                    }
                }
            }
            return flags
        }
    }
}

public fun hirAsciiClassBytes(kind: ClassAsciiKind): ClassBytes {
    val ranges: Vec = asciiClass(kind).map({ (s, e) -> ClassBytesRange.new(s, e) }).collect()
    return ClassBytes.new(ranges)
}

public fun asciiClass(kind: ClassAsciiKind): Any {
    val slice: List<Pair<UByte, UByte>> =
        when (kind) {
            Alnum -> listOf(Pair(48u, 57u), Pair(65u, 90u), Pair(97u, 122u))
            Alpha -> listOf(Pair(65u, 90u), Pair(97u, 122u))
            Ascii -> listOf(Pair(0u, 127u))
            Blank -> listOf(Pair(9u, 9u), Pair(32u, 32u))
            Cntrl -> listOf(Pair(0u, 31u), Pair(127u, 127u))
            Digit -> listOf(Pair(48u, 57u))
            Graph -> listOf(Pair(33u, 126u))
            Lower -> listOf(Pair(97u, 122u))
            Print -> listOf(Pair(32u, 126u))
            Punct -> listOf(Pair(33u, 47u), Pair(58u, 64u), Pair(91u, 96u), Pair(123u, 126u))
            Space -> listOf(Pair(9u, 9u), Pair(10u, 10u), Pair(11u, 11u), Pair(12u, 12u), Pair(13u, 13u), Pair(32u, 32u))
            Upper -> listOf(Pair(65u, 90u))
            Word -> listOf(Pair(48u, 57u), Pair(65u, 90u), Pair(95u, 95u), Pair(97u, 122u))
            Xdigit -> listOf(Pair(48u, 57u), Pair(65u, 70u), Pair(97u, 102u))
        }
    return slice.iter().copied()
}

public fun asciiClassAsChars(kind: ClassAsciiKind): Any = asciiClass(kind).map({ (s, e) -> Pair(Char.from(s), Char.from(e)) })
