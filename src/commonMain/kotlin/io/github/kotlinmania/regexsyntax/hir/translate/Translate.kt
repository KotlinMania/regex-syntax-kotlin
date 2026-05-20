// port-lint: source hir/translate.rs
/**
 * Defines a translator that converts an [Ast] to an [Hir].
 */
package io.github.kotlinmania.regexsyntax.hir.translate

import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.AssertionKind
import io.github.kotlinmania.regexsyntax.ast.ClassAsciiKind
import io.github.kotlinmania.regexsyntax.ast.ClassPerlKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOp
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOpKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetItem
import io.github.kotlinmania.regexsyntax.ast.ClassUnicodeKind
import io.github.kotlinmania.regexsyntax.ast.Flag
import io.github.kotlinmania.regexsyntax.ast.FlagsItemKind
import io.github.kotlinmania.regexsyntax.ast.GroupKind
import io.github.kotlinmania.regexsyntax.ast.RepetitionKind
import io.github.kotlinmania.regexsyntax.ast.RepetitionRange
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.Assertion as AstAssertion
import io.github.kotlinmania.regexsyntax.ast.ClassAscii as AstClassAscii
import io.github.kotlinmania.regexsyntax.ast.ClassPerl as AstClassPerl
import io.github.kotlinmania.regexsyntax.ast.ClassUnicode as AstClassUnicode
import io.github.kotlinmania.regexsyntax.ast.Flags as AstFlags
import io.github.kotlinmania.regexsyntax.ast.Group as AstGroup
import io.github.kotlinmania.regexsyntax.ast.Literal as AstLiteral
import io.github.kotlinmania.regexsyntax.ast.Repetition as AstRepetition
import io.github.kotlinmania.regexsyntax.ast.visitor.Visitor
import io.github.kotlinmania.regexsyntax.ast.visitor.visit
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
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.regexsyntax.unicode.ClassQuery
import io.github.kotlinmania.regexsyntax.unicode.SimpleCaseFolder
import io.github.kotlinmania.regexsyntax.unicode.UnicodeErrorException
import io.github.kotlinmania.regexsyntax.unicode.perlDigit
import io.github.kotlinmania.regexsyntax.unicode.perlSpace
import io.github.kotlinmania.regexsyntax.unicode.perlWord
import io.github.kotlinmania.regexsyntax.unicode.unicodeClass

/** A builder for constructing an AST->HIR translator. */
class TranslatorBuilder {
    companion object {
        /** Create a new translator builder with a default configuration. */
        fun new(): TranslatorBuilder = TranslatorBuilder()

        /** Create a new translator builder with a default configuration. */
        fun default(): TranslatorBuilder = new()
    }

    private var utf8: Boolean = true
    private var lineTerminator: Byte = '\n'.code.toByte()
    private var flags: Flags = Flags()

    /** Build a translator using the current configuration. */
    fun build(): Translator =
        Translator(
            stack = mutableListOf(),
            flagsState = StateCell(flags),
            utf8 = utf8,
            lineTerminator = lineTerminator,
        )

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
    fun utf8(yes: Boolean): TranslatorBuilder {
        utf8 = yes
        return this
    }

    /**
     * Sets the line terminator for use with `(?u-s:.)` and `(?-us:.)`.
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
     * `(?m:^)` and `(?m:$)`. That's usually controlled by additional
     * configuration in the regex engine itself.
     */
    fun lineTerminator(byte: Byte): TranslatorBuilder {
        lineTerminator = byte
        return this
    }

    /** Enable or disable the case insensitive flag (`i`) by default. */
    fun caseInsensitive(yes: Boolean): TranslatorBuilder {
        flags = flags.copy(caseInsensitive = if (yes) true else null)
        return this
    }

    /** Enable or disable the multi-line matching flag (`m`) by default. */
    fun multiLine(yes: Boolean): TranslatorBuilder {
        flags = flags.copy(multiLine = if (yes) true else null)
        return this
    }

    /**
     * Enable or disable the "dot matches any character" flag (`s`) by
     * default.
     */
    fun dotMatchesNewLine(yes: Boolean): TranslatorBuilder {
        flags = flags.copy(dotMatchesNewLine = if (yes) true else null)
        return this
    }

    /** Enable or disable the CRLF mode flag (`R`) by default. */
    fun crlf(yes: Boolean): TranslatorBuilder {
        flags = flags.copy(crlf = if (yes) true else null)
        return this
    }

    /** Enable or disable the "swap greed" flag (`U`) by default. */
    fun swapGreed(yes: Boolean): TranslatorBuilder {
        flags = flags.copy(swapGreed = if (yes) true else null)
        return this
    }

    /** Enable or disable the Unicode flag (`u`) by default. */
    fun unicode(yes: Boolean): TranslatorBuilder {
        flags = flags.copy(unicode = if (yes) null else false)
        return this
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
class Translator internal constructor(
    /** Our call stack, but on the heap. */
    internal val stack: MutableList<HirFrame>,
    /** The current flag settings. */
    internal val flagsState: StateCell<Flags>,
    /** Whether we're allowed to produce HIR that can match arbitrary bytes. */
    internal val utf8: Boolean,
    /** The line terminator to use for `.`. */
    internal val lineTerminator: Byte,
) {
    /** Create a new translator using the default configuration. */
    constructor() : this(
        stack = mutableListOf(),
        flagsState = StateCell(Flags()),
        utf8 = true,
        lineTerminator = '\n'.code.toByte(),
    )

    /**
     * Translate the given abstract syntax tree (AST) into a high level
     * intermediate representation (HIR).
     *
     * If there was a problem doing the translation, then an HIR-specific
     * error is returned.
     *
     * The original pattern string used to produce the [Ast] *must* also be
     * provided. The translator does not use the pattern string during any
     * correct translation, but is used for error reporting.
     */
    fun translate(pattern: String, ast: Ast): Result<Hir> =
        visit(ast, TranslatorI(this, pattern))
}

/**
 * A small mutable single-slot holder. The translator uses this to mutate
 * its active flag set in place as it walks the AST.
 */
internal class StateCell<T>(var value: T) {
    fun get(): T = value
    fun set(v: T) { value = v }
}

/**
 * An [HirFrame] is a single stack frame, represented explicitly, which is
 * created for each item in the [Ast] that we traverse.
 *
 * Note that technically, this type doesn't represent our entire stack
 * frame. In particular, the [Ast] visitor represents any state associated
 * with traversing the [Ast] itself.
 */
internal sealed class HirFrame {
    /**
     * An arbitrary HIR expression. These get pushed whenever we hit a base
     * case in the [Ast]. They get popped after an inductive (i.e., recursive)
     * step is complete.
     */
    data class Expr(val expr: Hir) : HirFrame()

    /**
     * A literal that is being constructed, character by character, from the
     * [Ast]. We need this because the [Ast] gives each individual character
     * its own node. So as we see characters, we peek at the top-most
     * [HirFrame]. If it's a literal, then we add to it. Otherwise, we push a
     * new literal. When it comes time to pop it, we convert it to an [Hir]
     * via [Hir.literal].
     */
    class Literal(val bytes: MutableList<Byte>) : HirFrame()

    /**
     * A Unicode character class. This frame is mutated as we descend into
     * the [Ast] of a character class (which is itself its own mini recursive
     * structure).
     */
    data class ClassUnicodeFrame(val cls: ClassUnicode) : HirFrame()

    /**
     * A byte-oriented character class. This frame is mutated as we descend
     * into the [Ast] of a character class (which is itself its own mini
     * recursive structure).
     *
     * Byte character classes are created when Unicode mode (`u`) is disabled.
     * If `utf8` is enabled (the default), then a byte character is only
     * permitted to match ASCII text.
     */
    data class ClassBytesFrame(val cls: ClassBytes) : HirFrame()

    /**
     * This is pushed whenever a repetition is observed. After visiting every
     * sub-expression in the repetition, the translator's stack is expected to
     * have this sentinel at the top.
     *
     * This sentinel only exists to stop other things (like flattening
     * literals) from reaching across repetition operators.
     */
    object Repetition : HirFrame()

    /**
     * This is pushed on to the stack upon first seeing any kind of capture,
     * indicated by parentheses (including non-capturing groups). It is popped
     * upon leaving a group.
     */
    data class Group(
        /**
         * The old active flags when this group was opened.
         *
         * If this group sets flags, then the new active flags are set to the
         * result of merging the old flags with the flags introduced by this
         * group. If the group doesn't set any flags, then this is simply
         * equivalent to whatever flags were set when the group was opened.
         *
         * When this group is popped, the active flags should be restored to
         * the flags set here.
         *
         * The "active" flags correspond to whatever flags are set in the
         * [Translator].
         */
        val oldFlags: Flags,
    ) : HirFrame()

    /**
     * This is pushed whenever a concatenation is observed. After visiting
     * every sub-expression in the concatenation, the translator's stack is
     * popped until it sees a [Concat] frame.
     */
    object Concat : HirFrame()

    /**
     * This is pushed whenever an alternation is observed. After visiting
     * every sub-expression in the alternation, the translator's stack is
     * popped until it sees an [Alternation] frame.
     */
    object Alternation : HirFrame()

    /**
     * This is pushed immediately before each sub-expression in an
     * alternation. This separates the branches of an alternation on the
     * stack and prevents literal flattening from reaching across alternation
     * branches.
     *
     * It is popped after each expression in a branch until an [Alternation]
     * frame is observed when doing a post visit on an alternation.
     */
    object AlternationBranch : HirFrame()

    /** Assert that the current stack frame is an [Hir] expression and return it. */
    fun unwrapExpr(): Hir = when (this) {
        is Expr -> expr
        is Literal -> Hir.literal(bytes.toByteArray())
        else -> error("tried to unwrap expr from HirFrame, got: $this")
    }

    /**
     * Assert that the current stack frame is a Unicode class expression and
     * return it.
     */
    fun unwrapClassUnicode(): ClassUnicode = when (this) {
        is ClassUnicodeFrame -> cls
        else -> error("tried to unwrap Unicode class from HirFrame, got: $this")
    }

    /**
     * Assert that the current stack frame is a byte class expression and
     * return it.
     */
    fun unwrapClassBytes(): ClassBytes = when (this) {
        is ClassBytesFrame -> cls
        else -> error("tried to unwrap byte class from HirFrame, got: $this")
    }

    /**
     * Assert that the current stack frame is a repetition sentinel. If it
     * isn't, then panic.
     */
    fun unwrapRepetition() {
        if (this !is Repetition) {
            error("tried to unwrap repetition from HirFrame, got: $this")
        }
    }

    /**
     * Assert that the current stack frame is a group indicator and return
     * its corresponding flags (the flags that were active at the time the
     * group was entered).
     */
    fun unwrapGroup(): Flags = when (this) {
        is Group -> oldFlags
        else -> error("tried to unwrap group from HirFrame, got: $this")
    }

    /**
     * Assert that the current stack frame is an alternation pipe sentinel.
     * If it isn't, then panic.
     */
    fun unwrapAlternationPipe() {
        if (this !is AlternationBranch) {
            error("tried to unwrap alt pipe from HirFrame, got: $this")
        }
    }
}

/**
 * The internal implementation of a translator.
 *
 * This type is responsible for carrying around the original pattern string,
 * which is not tied to the internal state of a translator.
 *
 * A [TranslatorI] exists for the time it takes to translate a single [Ast].
 */
internal class TranslatorI(
    private val transRef: Translator,
    private val pattern: String,
) : Visitor<Hir, Error> {

    override fun finish(): Result<Hir> {
        // ... otherwise, we should have exactly one HIR on the stack.
        check(trans().stack.size == 1)
        return Result.success(pop()!!.unwrapExpr())
    }

    override fun visitPre(ast: Ast): Result<Unit> {
        when (ast) {
            is Ast.ClassBracketed -> {
                if (flags().unicode()) {
                    val cls = ClassUnicode.empty()
                    push(HirFrame.ClassUnicodeFrame(cls))
                } else {
                    val cls = ClassBytes.empty()
                    push(HirFrame.ClassBytesFrame(cls))
                }
            }
            is Ast.Repetition -> push(HirFrame.Repetition)
            is Ast.Group -> {
                val grpFlags = ast.value.flags()
                val oldFlags = if (grpFlags != null) setFlags(grpFlags) else flags()
                push(HirFrame.Group(oldFlags = oldFlags))
            }
            is Ast.Concat -> {
                push(HirFrame.Concat)
            }
            is Ast.Alternation -> {
                push(HirFrame.Alternation)
                if (ast.value.asts.isNotEmpty()) {
                    push(HirFrame.AlternationBranch)
                }
            }
            else -> {}
        }
        return Result.success(Unit)
    }

    override fun visitPost(ast: Ast): Result<Unit> {
        when (ast) {
            is Ast.Empty -> {
                push(HirFrame.Expr(Hir.empty()))
            }
            is Ast.Flags -> {
                setFlags(ast.value.flags)
                // Flags in the AST are generally considered directives and
                // not actual sub-expressions. However, they can be used in
                // the concrete syntax like `((?i))`, and we need some kind of
                // indication of an expression there, and Empty is the correct
                // choice.
                //
                // There can also be things like `(?i)+`, but we rule those out
                // in the parser. In the future, we might allow them for
                // consistency sake.
                push(HirFrame.Expr(Hir.empty()))
            }
            is Ast.Literal -> {
                val scalarRes = astLiteralToScalar(ast.value)
                if (scalarRes.isFailure) return Result.failure(scalarRes.exceptionOrNull()!!)
                when (val scalar = scalarRes.getOrNull()!!) {
                    is Either.Right -> {
                        pushByte(scalar.value)
                    }
                    is Either.Left -> {
                        val ch = scalar.value
                        val foldRes = caseFoldChar(ast.value.span, ch)
                        if (foldRes.isFailure) return Result.failure(foldRes.exceptionOrNull()!!)
                        when (val folded = foldRes.getOrNull()) {
                            null -> pushChar(ch)
                            else -> push(HirFrame.Expr(folded))
                        }
                    }
                }
            }
            is Ast.Dot -> {
                val r = hirDot(ast.span)
                if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                push(HirFrame.Expr(r.getOrNull()!!))
            }
            is Ast.Assertion -> {
                val r = hirAssertion(ast.value)
                if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                push(HirFrame.Expr(r.getOrNull()!!))
            }
            is Ast.ClassPerl -> {
                if (flags().unicode()) {
                    val r = hirPerlUnicodeClass(ast.value)
                    if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                    val hcls = Class.Unicode(r.getOrNull()!!)
                    push(HirFrame.Expr(Hir.classOfHir(hcls)))
                } else {
                    val r = hirPerlByteClass(ast.value)
                    if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                    val hcls = Class.Bytes(r.getOrNull()!!)
                    push(HirFrame.Expr(Hir.classOfHir(hcls)))
                }
            }
            is Ast.ClassUnicode -> {
                val r = hirUnicodeClass(ast.value)
                if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                val cls = Class.Unicode(r.getOrNull()!!)
                push(HirFrame.Expr(Hir.classOfHir(cls)))
            }
            is Ast.ClassBracketed -> {
                if (flags().unicode()) {
                    val cls = pop()!!.unwrapClassUnicode()
                    val r = unicodeFoldAndNegate(ast.value.span, ast.value.negated, cls)
                    if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                    val expr = Hir.classOfHir(Class.Unicode(cls))
                    push(HirFrame.Expr(expr))
                } else {
                    val cls = pop()!!.unwrapClassBytes()
                    val r = bytesFoldAndNegate(ast.value.span, ast.value.negated, cls)
                    if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                    val expr = Hir.classOfHir(Class.Bytes(cls))
                    push(HirFrame.Expr(expr))
                }
            }
            is Ast.Repetition -> {
                val expr = pop()!!.unwrapExpr()
                pop()!!.unwrapRepetition()
                push(HirFrame.Expr(hirRepetition(ast.value, expr)))
            }
            is Ast.Group -> {
                val expr = pop()!!.unwrapExpr()
                val oldFlags = pop()!!.unwrapGroup()
                trans().flagsState.set(oldFlags)
                push(HirFrame.Expr(hirCapture(ast.value, expr)))
            }
            is Ast.Concat -> {
                val exprs = mutableListOf<Hir>()
                while (true) {
                    val expr = popConcatExpr() ?: break
                    if (expr.kind() !is HirKind.Empty) {
                        exprs.add(expr)
                    }
                }
                exprs.reverse()
                push(HirFrame.Expr(Hir.concat(exprs)))
            }
            is Ast.Alternation -> {
                val exprs = mutableListOf<Hir>()
                while (true) {
                    val expr = popAltExpr() ?: break
                    pop()!!.unwrapAlternationPipe()
                    exprs.add(expr)
                }
                exprs.reverse()
                push(HirFrame.Expr(Hir.alternation(exprs)))
            }
        }
        return Result.success(Unit)
    }

    override fun visitAlternationIn(): Result<Unit> {
        push(HirFrame.AlternationBranch)
        return Result.success(Unit)
    }

    override fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit> {
        when (ast) {
            is ClassSetItem.Bracketed -> {
                if (flags().unicode()) {
                    val cls = ClassUnicode.empty()
                    push(HirFrame.ClassUnicodeFrame(cls))
                } else {
                    val cls = ClassBytes.empty()
                    push(HirFrame.ClassBytesFrame(cls))
                }
            }
            // We needn't handle the Union case here since the visitor will
            // do it for us.
            else -> {}
        }
        return Result.success(Unit)
    }

    override fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit> {
        when (ast) {
            is ClassSetItem.Empty -> {}
            is ClassSetItem.Literal -> {
                if (flags().unicode()) {
                    val cls = pop()!!.unwrapClassUnicode()
                    cls.push(ClassUnicodeRange.new(ast.value.c, ast.value.c))
                    push(HirFrame.ClassUnicodeFrame(cls))
                } else {
                    val cls = pop()!!.unwrapClassBytes()
                    val r = classLiteralByte(ast.value)
                    if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                    val byte = r.getOrNull()!!
                    cls.push(ClassBytesRange.new(byte, byte))
                    push(HirFrame.ClassBytesFrame(cls))
                }
            }
            is ClassSetItem.Range -> {
                if (flags().unicode()) {
                    val cls = pop()!!.unwrapClassUnicode()
                    cls.push(ClassUnicodeRange.new(ast.value.start.c, ast.value.end.c))
                    push(HirFrame.ClassUnicodeFrame(cls))
                } else {
                    val cls = pop()!!.unwrapClassBytes()
                    val sr = classLiteralByte(ast.value.start)
                    if (sr.isFailure) return Result.failure(sr.exceptionOrNull()!!)
                    val er = classLiteralByte(ast.value.end)
                    if (er.isFailure) return Result.failure(er.exceptionOrNull()!!)
                    cls.push(ClassBytesRange.new(sr.getOrNull()!!, er.getOrNull()!!))
                    push(HirFrame.ClassBytesFrame(cls))
                }
            }
            is ClassSetItem.Ascii -> {
                if (flags().unicode()) {
                    val xclsRes = hirAsciiUnicodeClass(ast.value)
                    if (xclsRes.isFailure) return Result.failure(xclsRes.exceptionOrNull()!!)
                    val xcls = xclsRes.getOrNull()!!
                    val cls = pop()!!.unwrapClassUnicode()
                    cls.union(xcls)
                    push(HirFrame.ClassUnicodeFrame(cls))
                } else {
                    val xclsRes = hirAsciiByteClass(ast.value)
                    if (xclsRes.isFailure) return Result.failure(xclsRes.exceptionOrNull()!!)
                    val xcls = xclsRes.getOrNull()!!
                    val cls = pop()!!.unwrapClassBytes()
                    cls.union(xcls)
                    push(HirFrame.ClassBytesFrame(cls))
                }
            }
            is ClassSetItem.Unicode -> {
                val xclsRes = hirUnicodeClass(ast.value)
                if (xclsRes.isFailure) return Result.failure(xclsRes.exceptionOrNull()!!)
                val xcls = xclsRes.getOrNull()!!
                val cls = pop()!!.unwrapClassUnicode()
                cls.union(xcls)
                push(HirFrame.ClassUnicodeFrame(cls))
            }
            is ClassSetItem.Perl -> {
                if (flags().unicode()) {
                    val xclsRes = hirPerlUnicodeClass(ast.value)
                    if (xclsRes.isFailure) return Result.failure(xclsRes.exceptionOrNull()!!)
                    val xcls = xclsRes.getOrNull()!!
                    val cls = pop()!!.unwrapClassUnicode()
                    cls.union(xcls)
                    push(HirFrame.ClassUnicodeFrame(cls))
                } else {
                    val xclsRes = hirPerlByteClass(ast.value)
                    if (xclsRes.isFailure) return Result.failure(xclsRes.exceptionOrNull()!!)
                    val xcls = xclsRes.getOrNull()!!
                    val cls = pop()!!.unwrapClassBytes()
                    cls.union(xcls)
                    push(HirFrame.ClassBytesFrame(cls))
                }
            }
            is ClassSetItem.Bracketed -> {
                if (flags().unicode()) {
                    val cls1 = pop()!!.unwrapClassUnicode()
                    val r = unicodeFoldAndNegate(ast.value.span, ast.value.negated, cls1)
                    if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)

                    val cls2 = pop()!!.unwrapClassUnicode()
                    cls2.union(cls1)
                    push(HirFrame.ClassUnicodeFrame(cls2))
                } else {
                    val cls1 = pop()!!.unwrapClassBytes()
                    val r = bytesFoldAndNegate(ast.value.span, ast.value.negated, cls1)
                    if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)

                    val cls2 = pop()!!.unwrapClassBytes()
                    cls2.union(cls1)
                    push(HirFrame.ClassBytesFrame(cls2))
                }
            }
            // This is handled automatically by the visitor.
            is ClassSetItem.Union -> {}
        }
        return Result.success(Unit)
    }

    override fun visitClassSetBinaryOpPre(ast: ClassSetBinaryOp): Result<Unit> {
        if (flags().unicode()) {
            val cls = ClassUnicode.empty()
            push(HirFrame.ClassUnicodeFrame(cls))
        } else {
            val cls = ClassBytes.empty()
            push(HirFrame.ClassBytesFrame(cls))
        }
        return Result.success(Unit)
    }

    override fun visitClassSetBinaryOpIn(ast: ClassSetBinaryOp): Result<Unit> {
        if (flags().unicode()) {
            val cls = ClassUnicode.empty()
            push(HirFrame.ClassUnicodeFrame(cls))
        } else {
            val cls = ClassBytes.empty()
            push(HirFrame.ClassBytesFrame(cls))
        }
        return Result.success(Unit)
    }

    override fun visitClassSetBinaryOpPost(ast: ClassSetBinaryOp): Result<Unit> {
        val op = ast
        if (flags().unicode()) {
            val rhs = pop()!!.unwrapClassUnicode()
            val lhs = pop()!!.unwrapClassUnicode()
            val cls = pop()!!.unwrapClassUnicode()
            if (flags().caseInsensitive()) {
                val rRhs = rhs.tryCaseFoldSimple()
                if (rRhs.isFailure) {
                    return Result.failure(error(op.rhs.span(), ErrorKind.UnicodeCaseUnavailable))
                }
                val rLhs = lhs.tryCaseFoldSimple()
                if (rLhs.isFailure) {
                    return Result.failure(error(op.lhs.span(), ErrorKind.UnicodeCaseUnavailable))
                }
            }
            when (op.kind) {
                ClassSetBinaryOpKind.Intersection -> lhs.intersect(rhs)
                ClassSetBinaryOpKind.Difference -> lhs.difference(rhs)
                ClassSetBinaryOpKind.SymmetricDifference -> lhs.symmetricDifference(rhs)
            }
            cls.union(lhs)
            push(HirFrame.ClassUnicodeFrame(cls))
        } else {
            val rhs = pop()!!.unwrapClassBytes()
            val lhs = pop()!!.unwrapClassBytes()
            val cls = pop()!!.unwrapClassBytes()
            if (flags().caseInsensitive()) {
                rhs.caseFoldSimple()
                lhs.caseFoldSimple()
            }
            when (op.kind) {
                ClassSetBinaryOpKind.Intersection -> lhs.intersect(rhs)
                ClassSetBinaryOpKind.Difference -> lhs.difference(rhs)
                ClassSetBinaryOpKind.SymmetricDifference -> lhs.symmetricDifference(rhs)
            }
            cls.union(lhs)
            push(HirFrame.ClassBytesFrame(cls))
        }
        return Result.success(Unit)
    }

    /** Return a reference to the underlying translator. */
    private fun trans(): Translator = transRef

    /** Push the given frame on to the call stack. */
    private fun push(frame: HirFrame) {
        trans().stack.add(frame)
    }

    /**
     * Push the given literal char on to the call stack.
     *
     * If the top-most element of the stack is a literal, then the char is
     * appended to the end of that literal. Otherwise, a new literal
     * containing just the given char is pushed to the top of the stack.
     */
    private fun pushChar(ch: Int) {
        val bytes = encodeUtf8(ch)
        val stack = trans().stack
        val top = stack.lastOrNull()
        if (top is HirFrame.Literal) {
            for (b in bytes) top.bytes.add(b)
        } else {
            stack.add(HirFrame.Literal(bytes.toMutableList()))
        }
    }

    /**
     * Push the given literal byte on to the call stack.
     *
     * If the top-most element of the stack is a literal, then the byte is
     * appended to the end of that literal. Otherwise, a new literal
     * containing just the given byte is pushed to the top of the stack.
     */
    private fun pushByte(byte: Byte) {
        val stack = trans().stack
        val top = stack.lastOrNull()
        if (top is HirFrame.Literal) {
            top.bytes.add(byte)
        } else {
            stack.add(HirFrame.Literal(mutableListOf(byte)))
        }
    }

    /** Pop the top of the call stack. If the call stack is empty, return null. */
    private fun pop(): HirFrame? {
        val s = trans().stack
        return if (s.isEmpty()) null else s.removeAt(s.lastIndex)
    }

    /**
     * Pop an HIR expression from the top of the stack for a concatenation.
     *
     * This returns null if the stack is empty or when a concat frame is
     * seen. Otherwise, it panics if it could not find an HIR expression.
     */
    private fun popConcatExpr(): Hir? {
        val frame = pop() ?: return null
        return when (frame) {
            is HirFrame.Concat -> null
            is HirFrame.Expr -> frame.expr
            is HirFrame.Literal -> Hir.literal(frame.bytes.toByteArray())
            is HirFrame.ClassUnicodeFrame ->
                error("expected expr or concat, got Unicode class")
            is HirFrame.ClassBytesFrame ->
                error("expected expr or concat, got byte class")
            is HirFrame.Repetition ->
                error("expected expr or concat, got repetition")
            is HirFrame.Group ->
                error("expected expr or concat, got group")
            is HirFrame.Alternation ->
                error("expected expr or concat, got alt marker")
            is HirFrame.AlternationBranch ->
                error("expected expr or concat, got alt branch marker")
        }
    }

    /**
     * Pop an HIR expression from the top of the stack for an alternation.
     *
     * This returns null if the stack is empty or when an alternation frame is
     * seen. Otherwise, it panics if it could not find an HIR expression.
     */
    private fun popAltExpr(): Hir? {
        val frame = pop() ?: return null
        return when (frame) {
            is HirFrame.Alternation -> null
            is HirFrame.Expr -> frame.expr
            is HirFrame.Literal -> Hir.literal(frame.bytes.toByteArray())
            is HirFrame.ClassUnicodeFrame ->
                error("expected expr or alt, got Unicode class")
            is HirFrame.ClassBytesFrame ->
                error("expected expr or alt, got byte class")
            is HirFrame.Repetition ->
                error("expected expr or alt, got repetition")
            is HirFrame.Group ->
                error("expected expr or alt, got group")
            is HirFrame.Concat ->
                error("expected expr or alt, got concat marker")
            is HirFrame.AlternationBranch ->
                error("expected expr or alt, got alt branch marker")
        }
    }

    /** Create a new error with the given span and error type. */
    private fun error(span: Span, kind: ErrorKind): HirException =
        HirException(Error(kind, pattern, span))

    /** Return a copy of the active flags. */
    private fun flags(): Flags = trans().flagsState.get()

    /**
     * Set the flags of this translator from the flags set in the given
     * [Ast]. Then, return the old flags.
     */
    private fun setFlags(astFlags: AstFlags): Flags {
        val oldFlags = flags()
        val newFlags = Flags.fromAst(astFlags).merge(oldFlags)
        trans().flagsState.set(newFlags)
        return oldFlags
    }

    /**
     * Convert an [Ast] literal to its scalar representation.
     *
     * When Unicode mode is enabled, then this always succeeds and returns a
     * `char` (Unicode scalar value).
     *
     * When Unicode mode is disabled, then a `char` will still be returned
     * whenever possible. A byte is returned only when invalid UTF-8 is
     * allowed and when the byte is not ASCII. Otherwise, a non-ASCII byte
     * will result in an error when invalid UTF-8 is not allowed.
     */
    private fun astLiteralToScalar(lit: AstLiteral): Result<Either<Int, Byte>> {
        if (flags().unicode()) {
            return Result.success(Either.Left(lit.c))
        }
        val byte = lit.byte() ?: return Result.success(Either.Left(lit.c))
        val byteInt = byte.toInt()
        if (byteInt <= 0x7F) {
            return Result.success(Either.Left(byteInt))
        }
        if (trans().utf8) {
            return Result.failure(error(lit.span, ErrorKind.InvalidUtf8))
        }
        return Result.success(Either.Right(byteInt.toByte()))
    }

    private fun caseFoldChar(span: Span, c: Int): Result<Hir?> {
        if (!flags().caseInsensitive()) {
            return Result.success(null)
        }
        if (flags().unicode()) {
            // If case folding won't do anything, then don't bother trying.
            val folderRes = SimpleCaseFolder.new()
            if (folderRes.isFailure) {
                return Result.failure(error(span, ErrorKind.UnicodeCaseUnavailable))
            }
            val map = folderRes.getOrNull()!!.overlaps(c, c)
            if (!map) {
                return Result.success(null)
            }
            val cls = ClassUnicode.new(listOf(ClassUnicodeRange.new(c, c)))
            val r = cls.tryCaseFoldSimple()
            if (r.isFailure) {
                return Result.failure(error(span, ErrorKind.UnicodeCaseUnavailable))
            }
            return Result.success(Hir.classOfHir(Class.Unicode(cls)))
        } else {
            if (c > 0x7F) {
                return Result.success(null)
            }
            // If case folding won't do anything, then don't bother trying.
            val isCased =
                (c in 'A'.code..'Z'.code) || (c in 'a'.code..'z'.code)
            if (!isCased) {
                return Result.success(null)
            }
            val cls = ClassBytes.new(listOf(ClassBytesRange.new(
                // OK because the codepoint is `<= 0x7F`, which in turn
                // implies that it fits in a single byte (i.e., is ASCII).
                c.toByte(),
                c.toByte(),
            )))
            cls.caseFoldSimple()
            return Result.success(Hir.classOfHir(Class.Bytes(cls)))
        }
    }

    private fun hirDot(span: Span): Result<Hir> {
        val utf8 = trans().utf8
        val lineterm = trans().lineTerminator
        val flagsLocal = flags()
        if (utf8 && (!flagsLocal.unicode() || !lineterm.isAscii())) {
            return Result.failure(error(span, ErrorKind.InvalidUtf8))
        }
        val dot: Dot = if (flagsLocal.dotMatchesNewLine()) {
            if (flagsLocal.unicode()) Dot.AnyChar else Dot.AnyByte
        } else {
            if (flagsLocal.unicode()) {
                if (flagsLocal.crlf()) {
                    Dot.AnyCharExceptCRLF
                } else {
                    if (!lineterm.isAscii()) {
                        return Result.failure(error(span, ErrorKind.InvalidLineTerminator))
                    }
                    Dot.AnyCharExcept(lineterm.toInt() and 0xFF)
                }
            } else {
                if (flagsLocal.crlf()) {
                    Dot.AnyByteExceptCRLF
                } else {
                    Dot.AnyByteExcept(lineterm)
                }
            }
        }
        return Result.success(Hir.dot(dot))
    }

    private fun hirAssertion(asst: AstAssertion): Result<Hir> {
        val unicode = flags().unicode()
        val multiLine = flags().multiLine()
        val crlf = flags().crlf()
        val hir = when (asst.kind) {
            AssertionKind.StartLine -> Hir.look(if (multiLine) {
                if (crlf) Look.StartCRLF else Look.StartLF
            } else Look.Start)
            AssertionKind.EndLine -> Hir.look(if (multiLine) {
                if (crlf) Look.EndCRLF else Look.EndLF
            } else Look.End)
            AssertionKind.StartText -> Hir.look(Look.Start)
            AssertionKind.EndText -> Hir.look(Look.End)
            AssertionKind.WordBoundary -> Hir.look(if (unicode) Look.WordUnicode else Look.WordAscii)
            AssertionKind.NotWordBoundary -> Hir.look(if (unicode) Look.WordUnicodeNegate else Look.WordAsciiNegate)
            AssertionKind.WordBoundaryStart, AssertionKind.WordBoundaryStartAngle ->
                Hir.look(if (unicode) Look.WordStartUnicode else Look.WordStartAscii)
            AssertionKind.WordBoundaryEnd, AssertionKind.WordBoundaryEndAngle ->
                Hir.look(if (unicode) Look.WordEndUnicode else Look.WordEndAscii)
            AssertionKind.WordBoundaryStartHalf ->
                Hir.look(if (unicode) Look.WordStartHalfUnicode else Look.WordStartHalfAscii)
            AssertionKind.WordBoundaryEndHalf ->
                Hir.look(if (unicode) Look.WordEndHalfUnicode else Look.WordEndHalfAscii)
        }
        return Result.success(hir)
    }

    private fun hirCapture(group: AstGroup, expr: Hir): Hir {
        val (index, name) = when (val k = group.kind) {
            is GroupKind.CaptureIndex -> Pair(k.value, null)
            is GroupKind.CaptureName -> Pair(k.name.index, k.name.name)
            // The HIR doesn't need to use non-capturing groups, since the way
            // in which the data type is defined handles this automatically.
            is GroupKind.NonCapturing -> return expr
        }
        return Hir.capture(Capture(index = index, name = name, sub = expr))
    }

    private fun hirRepetition(rep: AstRepetition, expr: Hir): Hir {
        val (min, max) = when (val k = rep.op.kind) {
            is RepetitionKind.ZeroOrOne -> Pair(0u, 1u as UInt?)
            is RepetitionKind.ZeroOrMore -> Pair(0u, null as UInt?)
            is RepetitionKind.OneOrMore -> Pair(1u, null as UInt?)
            is RepetitionKind.Range -> when (val r = k.value) {
                is RepetitionRange.Exactly -> Pair(r.value, r.value as UInt?)
                is RepetitionRange.AtLeast -> Pair(r.value, null as UInt?)
                is RepetitionRange.Bounded -> Pair(r.start, r.end as UInt?)
            }
        }
        val greedy = if (flags().swapGreed()) !rep.greedy else rep.greedy
        return Hir.repetition(Repetition(
            min = min,
            max = max,
            greedy = greedy,
            sub = expr,
        ))
    }

    private fun hirUnicodeClass(astClass: AstClassUnicode): Result<ClassUnicode> {
        if (!flags().unicode()) {
            return Result.failure(error(astClass.span, ErrorKind.UnicodeNotAllowed))
        }
        val query: ClassQuery = when (val k = astClass.kind) {
            is ClassUnicodeKind.OneLetter -> ClassQuery.OneLetter(k.value)
            is ClassUnicodeKind.Named -> ClassQuery.Binary(k.value)
            is ClassUnicodeKind.NamedValue -> ClassQuery.ByValue(
                propertyName = k.name,
                propertyValue = k.value,
            )
        }
        val result = convertUnicodeClassError(astClass.span, unicodeClass(query))
        if (result.isSuccess) {
            val cls = result.getOrNull()!!
            val r = unicodeFoldAndNegate(astClass.span, astClass.isNegated(), cls)
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        }
        return result
    }

    private fun hirAsciiUnicodeClass(ast: AstClassAscii): Result<ClassUnicode> {
        val cls = ClassUnicode.new(
            asciiClassAsChars(ast.kind).map { (s, e) -> ClassUnicodeRange.new(s, e) }.toList()
        )
        val r = unicodeFoldAndNegate(ast.span, ast.negated, cls)
        if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        return Result.success(cls)
    }

    private fun hirAsciiByteClass(ast: AstClassAscii): Result<ClassBytes> {
        val cls = ClassBytes.new(
            asciiClass(ast.kind).map { (s, e) -> ClassBytesRange.new(s, e) }.toList()
        )
        val r = bytesFoldAndNegate(ast.span, ast.negated, cls)
        if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        return Result.success(cls)
    }

    private fun hirPerlUnicodeClass(astClass: AstClassPerl): Result<ClassUnicode> {
        check(flags().unicode())
        val result = when (astClass.kind) {
            ClassPerlKind.Digit -> perlDigit()
            ClassPerlKind.Space -> perlSpace()
            ClassPerlKind.Word -> perlWord()
        }
        val cvt = convertUnicodeClassError(astClass.span, result)
        if (cvt.isFailure) return cvt
        val cls = cvt.getOrNull()!!
        // We needn't apply case folding here because the Perl Unicode classes
        // are already closed under Unicode simple case folding.
        if (astClass.negated) {
            cls.negate()
        }
        return Result.success(cls)
    }

    private fun hirPerlByteClass(astClass: AstClassPerl): Result<ClassBytes> {
        check(!flags().unicode())
        val cls = when (astClass.kind) {
            ClassPerlKind.Digit -> hirAsciiClassBytes(ClassAsciiKind.Digit)
            ClassPerlKind.Space -> hirAsciiClassBytes(ClassAsciiKind.Space)
            ClassPerlKind.Word -> hirAsciiClassBytes(ClassAsciiKind.Word)
        }
        // We needn't apply case folding here because the Perl ASCII classes
        // are already closed (under ASCII case folding).
        if (astClass.negated) {
            cls.negate()
        }
        // Negating a Perl byte class is likely to cause it to match invalid
        // UTF-8. That's only OK if the translator is configured to allow such
        // things.
        if (trans().utf8 && !cls.isAscii()) {
            return Result.failure(error(astClass.span, ErrorKind.InvalidUtf8))
        }
        return Result.success(cls)
    }

    /**
     * Converts the given Unicode specific error to an HIR translation error.
     *
     * The span given should approximate the position at which an error would
     * occur.
     */
    private fun convertUnicodeClassError(
        span: Span,
        result: Result<ClassUnicode>,
    ): Result<ClassUnicode> {
        if (result.isSuccess) return result
        val err = result.exceptionOrNull()
        val sp = span
        val kind = if (err is UnicodeErrorException) {
            when (err.error) {
                io.github.kotlinmania.regexsyntax.unicode.Error.PropertyNotFound -> ErrorKind.UnicodePropertyNotFound
                io.github.kotlinmania.regexsyntax.unicode.Error.PropertyValueNotFound -> ErrorKind.UnicodePropertyValueNotFound
                io.github.kotlinmania.regexsyntax.unicode.Error.PerlClassNotFound -> ErrorKind.UnicodePerlClassNotFound
            }
        } else {
            ErrorKind.UnicodePerlClassNotFound
        }
        return Result.failure(error(sp, kind))
    }

    private fun unicodeFoldAndNegate(
        span: Span,
        negated: Boolean,
        cls: ClassUnicode,
    ): Result<Unit> {
        // Note that we must apply case folding before negation!
        // Consider `(?i)[^x]`. If we applied negation first, then
        // the result would be the character class that matched any
        // Unicode scalar value.
        if (flags().caseInsensitive()) {
            val r = cls.tryCaseFoldSimple()
            if (r.isFailure) {
                return Result.failure(error(span, ErrorKind.UnicodeCaseUnavailable))
            }
        }
        if (negated) {
            cls.negate()
        }
        return Result.success(Unit)
    }

    private fun bytesFoldAndNegate(
        span: Span,
        negated: Boolean,
        cls: ClassBytes,
    ): Result<Unit> {
        // Note that we must apply case folding before negation!
        // Consider `(?i)[^x]`. If we applied negation first, then
        // the result would be the character class that matched any
        // Unicode scalar value.
        if (flags().caseInsensitive()) {
            cls.caseFoldSimple()
        }
        if (negated) {
            cls.negate()
        }
        if (trans().utf8 && !cls.isAscii()) {
            return Result.failure(error(span, ErrorKind.InvalidUtf8))
        }
        return Result.success(Unit)
    }

    /**
     * Return a scalar byte value suitable for use as a literal in a byte
     * character class.
     */
    private fun classLiteralByte(ast: AstLiteral): Result<Byte> {
        val r = astLiteralToScalar(ast)
        if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
        return when (val v = r.getOrNull()!!) {
            is Either.Right -> {
                Result.success(v.value)
            }
            is Either.Left -> {
                val ch = v.value
                if (ch <= 0x7F) {
                    Result.success(ch.toByte())
                } else {
                    // We can't feasibly support Unicode in
                    // byte oriented classes. Byte classes don't
                    // do Unicode case folding.
                    Result.failure(error(ast.span, ErrorKind.UnicodeNotAllowed))
                }
            }
        }
    }
}

/**
 * A translator's representation of a regular expression's flags at any given
 * moment in time.
 *
 * Each flag can be in one of three states: absent, present but disabled or
 * present but enabled.
 */
internal data class Flags(
    val caseInsensitive: Boolean? = null,
    val multiLine: Boolean? = null,
    val dotMatchesNewLine: Boolean? = null,
    val swapGreed: Boolean? = null,
    val unicode: Boolean? = null,
    val crlf: Boolean? = null,
    // Note that `ignoreWhitespace` is omitted here because it is handled
    // entirely in the parser.
) {
    fun merge(previous: Flags): Flags = Flags(
        caseInsensitive = caseInsensitive ?: previous.caseInsensitive,
        multiLine = multiLine ?: previous.multiLine,
        dotMatchesNewLine = dotMatchesNewLine ?: previous.dotMatchesNewLine,
        swapGreed = swapGreed ?: previous.swapGreed,
        unicode = unicode ?: previous.unicode,
        crlf = crlf ?: previous.crlf,
    )

    fun caseInsensitive(): Boolean = caseInsensitive ?: false
    fun multiLine(): Boolean = multiLine ?: false
    fun dotMatchesNewLine(): Boolean = dotMatchesNewLine ?: false
    fun swapGreed(): Boolean = swapGreed ?: false
    fun unicode(): Boolean = unicode ?: true
    fun crlf(): Boolean = crlf ?: false

    companion object {
        fun fromAst(ast: AstFlags): Flags {
            var flags = Flags()
            var enable = true
            for (item in ast.items) {
                when (val k = item.kind) {
                    is FlagsItemKind.Negation -> {
                        enable = false
                    }
                    is FlagsItemKind.Flag -> when (k.value) {
                        Flag.CaseInsensitive -> flags = flags.copy(caseInsensitive = enable)
                        Flag.MultiLine -> flags = flags.copy(multiLine = enable)
                        Flag.DotMatchesNewLine -> flags = flags.copy(dotMatchesNewLine = enable)
                        Flag.SwapGreed -> flags = flags.copy(swapGreed = enable)
                        Flag.Unicode -> flags = flags.copy(unicode = enable)
                        Flag.CRLF -> flags = flags.copy(crlf = enable)
                        Flag.IgnoreWhitespace -> {}
                    }
                }
            }
            return flags
        }
    }
}

private fun hirAsciiClassBytes(kind: ClassAsciiKind): ClassBytes {
    val ranges = asciiClass(kind).map { (s, e) -> ClassBytesRange.new(s, e) }.toList()
    return ClassBytes.new(ranges)
}

private fun asciiClass(kind: ClassAsciiKind): Sequence<Pair<Byte, Byte>> {
    val slice: Array<Pair<Byte, Byte>> = when (kind) {
        ClassAsciiKind.Alnum -> arrayOf(
            '0'.code.toByte() to '9'.code.toByte(),
            'A'.code.toByte() to 'Z'.code.toByte(),
            'a'.code.toByte() to 'z'.code.toByte(),
        )
        ClassAsciiKind.Alpha -> arrayOf(
            'A'.code.toByte() to 'Z'.code.toByte(),
            'a'.code.toByte() to 'z'.code.toByte(),
        )
        ClassAsciiKind.Ascii -> arrayOf(
            0x00.toByte() to 0x7F.toByte(),
        )
        ClassAsciiKind.Blank -> arrayOf(
            '\t'.code.toByte() to '\t'.code.toByte(),
            ' '.code.toByte() to ' '.code.toByte(),
        )
        ClassAsciiKind.Cntrl -> arrayOf(
            0x00.toByte() to 0x1F.toByte(),
            0x7F.toByte() to 0x7F.toByte(),
        )
        ClassAsciiKind.Digit -> arrayOf(
            '0'.code.toByte() to '9'.code.toByte(),
        )
        ClassAsciiKind.Graph -> arrayOf(
            '!'.code.toByte() to '~'.code.toByte(),
        )
        ClassAsciiKind.Lower -> arrayOf(
            'a'.code.toByte() to 'z'.code.toByte(),
        )
        ClassAsciiKind.Print -> arrayOf(
            ' '.code.toByte() to '~'.code.toByte(),
        )
        ClassAsciiKind.Punct -> arrayOf(
            '!'.code.toByte() to '/'.code.toByte(),
            ':'.code.toByte() to '@'.code.toByte(),
            '['.code.toByte() to '`'.code.toByte(),
            '{'.code.toByte() to '~'.code.toByte(),
        )
        ClassAsciiKind.Space -> arrayOf(
            '\t'.code.toByte() to '\t'.code.toByte(),
            '\n'.code.toByte() to '\n'.code.toByte(),
            0x0B.toByte() to 0x0B.toByte(),
            0x0C.toByte() to 0x0C.toByte(),
            '\r'.code.toByte() to '\r'.code.toByte(),
            ' '.code.toByte() to ' '.code.toByte(),
        )
        ClassAsciiKind.Upper -> arrayOf(
            'A'.code.toByte() to 'Z'.code.toByte(),
        )
        ClassAsciiKind.Word -> arrayOf(
            '0'.code.toByte() to '9'.code.toByte(),
            'A'.code.toByte() to 'Z'.code.toByte(),
            '_'.code.toByte() to '_'.code.toByte(),
            'a'.code.toByte() to 'z'.code.toByte(),
        )
        ClassAsciiKind.Xdigit -> arrayOf(
            '0'.code.toByte() to '9'.code.toByte(),
            'A'.code.toByte() to 'F'.code.toByte(),
            'a'.code.toByte() to 'f'.code.toByte(),
        )
    }
    return slice.asSequence()
}

private fun asciiClassAsChars(kind: ClassAsciiKind): Sequence<Pair<Int, Int>> =
    asciiClass(kind).map { (s, e) -> Pair(s.toInt() and 0xFF, e.toInt() and 0xFF) }

/**
 * Returns true if and only if the given byte represents an ASCII codepoint
 * (i.e., its unsigned value is at most `0x7F`). The line terminator is held
 * as an unsigned byte but is checked against the ASCII range in several
 * places.
 */
private fun Byte.isAscii(): Boolean = (this.toInt() and 0xFF) <= 0x7F

/**
 * A throwable wrapper for [Error] values. The Kotlin [Result] machinery
 * requires a [Throwable] in the failure case; this wrapper carries the
 * original [Error] so callers can recover it via [HirException.err].
 */
class HirException(val err: Error) : Throwable(err.toString())

/**
 * Encode a Unicode scalar value (stored as an [Int] codepoint) into its
 * UTF-8 byte sequence.
 */
private fun encodeUtf8(c: Int): ByteArray {
    return when {
        c <= 0x7F -> byteArrayOf(c.toByte())
        c <= 0x7FF -> byteArrayOf(
            (0xC0 or (c ushr 6)).toByte(),
            (0x80 or (c and 0x3F)).toByte(),
        )
        c <= 0xFFFF -> byteArrayOf(
            (0xE0 or (c ushr 12)).toByte(),
            (0x80 or ((c ushr 6) and 0x3F)).toByte(),
            (0x80 or (c and 0x3F)).toByte(),
        )
        else -> byteArrayOf(
            (0xF0 or (c ushr 18)).toByte(),
            (0x80 or ((c ushr 12) and 0x3F)).toByte(),
            (0x80 or ((c ushr 6) and 0x3F)).toByte(),
            (0x80 or (c and 0x3F)).toByte(),
        )
    }
}
