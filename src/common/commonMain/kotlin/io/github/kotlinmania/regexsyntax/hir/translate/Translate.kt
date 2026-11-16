// port-lint: source hir/translate.rs
package io.github.kotlinmania.regexsyntax.hir.translate

import io.github.kotlinmania.regexsyntax.ast.Assertion
import io.github.kotlinmania.regexsyntax.ast.AssertionKind
import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.ClassAscii
import io.github.kotlinmania.regexsyntax.ast.ClassAsciiKind
import io.github.kotlinmania.regexsyntax.ast.ClassPerl
import io.github.kotlinmania.regexsyntax.ast.ClassPerlKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOp
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOpKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetItem
import io.github.kotlinmania.regexsyntax.ast.ClassUnicodeKind
import io.github.kotlinmania.regexsyntax.ast.Flag
import io.github.kotlinmania.regexsyntax.ast.FlagsItemKind
import io.github.kotlinmania.regexsyntax.ast.Group
import io.github.kotlinmania.regexsyntax.ast.GroupKind
import io.github.kotlinmania.regexsyntax.ast.Literal
import io.github.kotlinmania.regexsyntax.ast.Repetition
import io.github.kotlinmania.regexsyntax.ast.RepetitionKind
import io.github.kotlinmania.regexsyntax.ast.RepetitionRange
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.visitor.Visitor
import io.github.kotlinmania.regexsyntax.ast.visitor.visit
import io.github.kotlinmania.regexsyntax.either.Either
import io.github.kotlinmania.regexsyntax.encodeScalarUtf8List
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
import io.github.kotlinmania.regexsyntax.scalarIsAscii
import io.github.kotlinmania.regexsyntax.unicode.ClassQuery
import io.github.kotlinmania.regexsyntax.unicode.SimpleCaseFolder
import io.github.kotlinmania.regexsyntax.unicode.cls
import io.github.kotlinmania.regexsyntax.unicode.perlDigit
import io.github.kotlinmania.regexsyntax.unicode.perlSpace
import io.github.kotlinmania.regexsyntax.unicode.perlWord
import io.github.kotlinmania.regexsyntax.withBorrow
import io.github.kotlinmania.regexsyntax.withBorrowMut
import io.github.kotlinmania.rustlang.core.cell.Cell
import io.github.kotlinmania.rustlang.core.cell.RefCell
import io.github.kotlinmania.rustlang.core.result.Result
import io.github.kotlinmania.regexsyntax.unicode.Error as UnicodeError

// Defines a translator that converts an `Ast` to an `Hir`.

/**
 * A builder for constructing an AST->HIR translator.
 */
public open class TranslatorBuilder internal constructor(
    internal var utf8: Boolean,
    internal var lineTerminator: UByte,
    internal var flags: Flags,
) {
    /**
     * Build a translator using the current configuration.
     */
    public open fun build(): Translator =
        Translator(
            stackCtorArg = RefCell.new(mutableListOf()),
            flagsCtorArg = Cell.new(this.flags.copy()),
            utf8 = this.utf8,
            lineTerminator = this.lineTerminator,
        )

    /**
     * When disabled, translation will permit the construction of a regular
     * expression that may include invalid UTF-8.
     */
    public open fun utf8(yes: Boolean): TranslatorBuilder {
        this.utf8 = yes
        return this
    }

    /**
     * Sets the line terminator for use with `(?u-s:.)` and `(?-us:.)`.
     */
    public open fun lineTerminator(byte: UByte): TranslatorBuilder {
        this.lineTerminator = byte
        return this
    }

    /**
     * Enable or disable the case insensitive flag (`i`) by default.
     */
    public open fun caseInsensitive(yes: Boolean): TranslatorBuilder {
        this.flags.caseInsensitive = if (yes) true else null
        return this
    }

    /**
     * Enable or disable the multi-line matching flag (`m`) by default.
     */
    public open fun multiLine(yes: Boolean): TranslatorBuilder {
        this.flags.multiLine = if (yes) true else null
        return this
    }

    /**
     * Enable or disable the dot-matches-newline flag (`s`) by default.
     */
    public open fun dotMatchesNewLine(yes: Boolean): TranslatorBuilder {
        this.flags.dotMatchesNewLine = if (yes) true else null
        return this
    }

    /**
     * Enable or disable the CRLF mode flag (`R`) by default.
     */
    public open fun crlf(yes: Boolean): TranslatorBuilder {
        this.flags.crlf = if (yes) true else null
        return this
    }

    /**
     * Enable or disable the swap-greed flag (`U`) by default.
     */
    public open fun swapGreed(yes: Boolean): TranslatorBuilder {
        this.flags.swapGreed = if (yes) true else null
        return this
    }

    /**
     * Enable or disable the Unicode flag (`u`) by default.
     */
    public open fun unicode(yes: Boolean): TranslatorBuilder {
        this.flags.unicode = if (yes) null else false
        return this
    }

    public companion object {
        public fun default(): TranslatorBuilder = TranslatorBuilder.new()

        /**
         * Create a new translator builder with a default configuration.
         */
        public fun new(): TranslatorBuilder =
            TranslatorBuilder(utf8 = true, lineTerminator = 10u.toUByte(), flags = Flags.default())
    }
}

/**
 * A translator maps abstract syntax to a high level intermediate
 * representation.
 *
 * A translator may be reused across many abstract syntax trees.
 */
public open class Translator internal constructor(
    stackCtorArg: RefCell<MutableList<HirFrame>>,
    flagsCtorArg: Cell<Flags>,
    internal var utf8: Boolean,
    internal var lineTerminator: UByte,
) {
    /**
     * Our call stack on the heap.
     */
    internal var stack: RefCell<MutableList<HirFrame>> = stackCtorArg

    /**
     * The current flag settings.
     */
    internal var flags: Cell<Flags> = flagsCtorArg

    /**
     * Translate the given abstract syntax tree (AST) into a high level
     * intermediate representation (HIR).
     */
    public open fun translate(pattern: String, ast: Ast): Result<Hir, Error> =
        visit(ast, TranslatorI.new(this, pattern))

    public companion object {
        internal operator fun invoke(
            stack: RefCell<MutableList<HirFrame>>,
            flags: Cell<Flags>,
            utf8: Boolean,
            lineTerminator: UByte,
        ): Translator =
            Translator(
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
 */
internal sealed class HirFrame {
    internal data class Expr(
        val value: Hir,
    ) : HirFrame()

    internal data class Literal(
        val value: MutableList<UByte>,
    ) : HirFrame()

    internal data class ClassUnicode(
        val value: io.github.kotlinmania.regexsyntax.hir.ClassUnicode,
    ) : HirFrame()

    internal data class ClassBytes(
        val value: io.github.kotlinmania.regexsyntax.hir.ClassBytes,
    ) : HirFrame()

    internal data object Repetition : HirFrame()

    internal data class Group(
        val oldFlags: Flags,
    ) : HirFrame()

    internal data object Concat : HirFrame()

    internal data object Alternation : HirFrame()

    /**
     * Popped after each branch expression until an `Alternation` frame is
     * observed during post-visit on an alternation.
     */
    internal data object AlternationBranch : HirFrame()

    internal fun unwrapExpr(): Hir =
        when (this) {
            is HirFrame.Expr -> this.value
            is HirFrame.Literal -> Hir.literal(this.value)
            else -> error("tried to unwrap expr from HirFrame, got: $this")
        }

    internal fun unwrapClassUnicode(): io.github.kotlinmania.regexsyntax.hir.ClassUnicode =
        when (this) {
            is HirFrame.ClassUnicode -> this.value
            else -> error("tried to unwrap Unicode class from HirFrame, got: $this")
        }

    internal fun unwrapClassBytes(): io.github.kotlinmania.regexsyntax.hir.ClassBytes =
        when (this) {
            is HirFrame.ClassBytes -> this.value
            else -> error("tried to unwrap byte class from HirFrame, got: $this")
        }

    internal fun unwrapRepetition() {
        if (this !is HirFrame.Repetition) {
            error("tried to unwrap repetition from HirFrame, got: $this")
        }
    }

    internal fun unwrapGroup(): Flags =
        when (this) {
            is HirFrame.Group -> this.oldFlags
            else -> error("tried to unwrap group from HirFrame, got: $this")
        }

    internal fun unwrapAlternationPipe() {
        if (this !is HirFrame.AlternationBranch) {
            error("tried to unwrap alt pipe from HirFrame, got: $this")
        }
    }
}

/**
 * The internal implementation of a translator.
 */
internal class TranslatorI(
    internal var trans: Translator,
    internal var pattern: String,
) : Visitor<Hir, Error> {
    override fun start() {
        this.trans().stack.withBorrowMut { guard ->
            guard.value.clear()
        }
    }

    override fun finish(): Result<Hir, Error> {
        val len = this.trans().stack.withBorrow { it.size }
        check(len == 1)
        return Result.Ok(this.pop()!!.unwrapExpr())
    }

    override fun visitPre(ast: Ast): Result<Unit, Error> {
        when (ast) {
            is Ast.ClassBracketed -> {
                if (this.flags().unicode()) {
                    this.push(HirFrame.ClassUnicode(ClassUnicode.empty()))
                } else {
                    this.push(HirFrame.ClassBytes(ClassBytes.empty()))
                }
            }
            is Ast.Repetition -> this.push(HirFrame.Repetition)
            is Ast.Group -> {
                val x = ast.value
                val astFlags = x.flags()
                val oldFlags = if (astFlags != null) this.setFlags(astFlags) else this.flags()
                this.push(HirFrame.Group(oldFlags = oldFlags))
            }
            is Ast.Concat -> {
                this.push(HirFrame.Concat)
            }
            is Ast.Alternation -> {
                val x1 = ast.value
                this.push(HirFrame.Alternation)
                if (x1.asts.isNotEmpty()) {
                    this.push(HirFrame.AlternationBranch)
                }
            }
            else -> {}
        }
        return Result.Ok(Unit)
    }

    override fun visitPost(ast: Ast): Result<Unit, Error> {
        when (ast) {
            is Ast.Empty -> {
                this.push(HirFrame.Expr(Hir.empty()))
            }
            is Ast.Flags -> {
                this.setFlags(ast.value.flags)
                this.push(HirFrame.Expr(Hir.empty()))
            }
            is Ast.Literal -> {
                val lit = ast.value
                val scalarOrByte =
                    when (val res = this.astLiteralToScalar(lit)) {
                        is Result.Ok -> res.value
                        is Result.Err -> return Result.Err(res.error)
                    }
                when (scalarOrByte) {
                    is Either.Right -> this.pushByte(scalarOrByte.value)
                    is Either.Left -> {
                        val ch = scalarOrByte.value
                        val folded =
                            when (val res = this.caseFoldChar(lit.span, ch)) {
                                is Result.Ok -> res.value
                                is Result.Err -> return Result.Err(res.error)
                            }
                        if (folded == null) {
                            this.pushChar(ch)
                        } else {
                            this.push(HirFrame.Expr(folded))
                        }
                    }
                }
            }
            is Ast.Dot -> {
                val dotHir =
                    when (val res = this.hirDot(ast.value)) {
                        is Result.Ok -> res.value
                        is Result.Err -> return Result.Err(res.error)
                    }
                this.push(HirFrame.Expr(dotHir))
            }
            is Ast.Assertion -> {
                val asstHir =
                    when (val res = this.hirAssertion(ast.value)) {
                        is Result.Ok -> res.value
                        is Result.Err -> return Result.Err(res.error)
                    }
                this.push(HirFrame.Expr(asstHir))
            }
            is Ast.ClassPerl -> {
                val x3 = ast.value
                if (this.flags().unicode()) {
                    val cls =
                        when (val res = this.hirPerlUnicodeClass(x3)) {
                            is Result.Ok -> res.value
                            is Result.Err -> return Result.Err(res.error)
                        }
                    this.push(HirFrame.Expr(Hir.cls(Class.Unicode(cls))))
                } else {
                    val cls1 =
                        when (val res = this.hirPerlByteClass(x3)) {
                            is Result.Ok -> res.value
                            is Result.Err -> return Result.Err(res.error)
                        }
                    this.push(HirFrame.Expr(Hir.cls(Class.Bytes(cls1))))
                }
            }
            is Ast.ClassUnicode -> {
                val cls2 =
                    when (val res = this.hirUnicodeClass(ast.value)) {
                        is Result.Ok -> res.value
                        is Result.Err -> return Result.Err(res.error)
                    }
                this.push(HirFrame.Expr(Hir.cls(Class.Unicode(cls2))))
            }
            is Ast.ClassBracketed -> {
                val ast1 = ast.value
                if (this.flags().unicode()) {
                    val cls3 = this.pop()!!.unwrapClassUnicode()
                    when (val res = this.unicodeFoldAndNegate(ast1.span, ast1.negated, cls3)) {
                        is Result.Ok -> {}
                        is Result.Err -> return Result.Err(res.error)
                    }
                    this.push(HirFrame.Expr(Hir.cls(Class.Unicode(cls3))))
                } else {
                    val cls4 = this.pop()!!.unwrapClassBytes()
                    when (val res = this.bytesFoldAndNegate(ast1.span, ast1.negated, cls4)) {
                        is Result.Ok -> {}
                        is Result.Err -> return Result.Err(res.error)
                    }
                    this.push(HirFrame.Expr(Hir.cls(Class.Bytes(cls4))))
                }
            }
            is Ast.Repetition -> {
                val expr3 = this.pop()!!.unwrapExpr()
                this.pop()!!.unwrapRepetition()
                this.push(HirFrame.Expr(this.hirRepetition(ast.value, expr3)))
            }
            is Ast.Group -> {
                val expr4 = this.pop()!!.unwrapExpr()
                val oldFlags = this.pop()!!.unwrapGroup()
                this.trans().flags.set(oldFlags)
                this.push(HirFrame.Expr(this.hirCapture(ast.value, expr4)))
            }
            is Ast.Concat -> {
                val exprs = mutableListOf<Hir>()
                while (true) {
                    val expr5 = this.popConcatExpr() ?: break
                    if (expr5.kind() != HirKind.Empty) {
                        exprs.add(expr5)
                    }
                }
                exprs.reverse()
                this.push(HirFrame.Expr(Hir.concat(exprs)))
            }
            is Ast.Alternation -> {
                val exprs1 = mutableListOf<Hir>()
                while (true) {
                    val expr6 = this.popAltExpr() ?: break
                    this.pop()!!.unwrapAlternationPipe()
                    exprs1.add(expr6)
                }
                exprs1.reverse()
                this.push(HirFrame.Expr(Hir.alternation(exprs1)))
            }
        }
        return Result.Ok(Unit)
    }

    override fun visitAlternationIn(): Result<Unit, Error> {
        this.push(HirFrame.AlternationBranch)
        return Result.Ok(Unit)
    }

    override fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit, Error> {
        if (ast is ClassSetItem.Bracketed) {
            if (this.flags().unicode()) {
                this.push(HirFrame.ClassUnicode(ClassUnicode.empty()))
            } else {
                this.push(HirFrame.ClassBytes(ClassBytes.empty()))
            }
        }
        return Result.Ok(Unit)
    }

    override fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit, Error> {
        when (ast) {
            is ClassSetItem.Empty -> {}
            is ClassSetItem.Literal -> {
                val x = ast.value
                if (this.flags().unicode()) {
                    val cls = this.pop()!!.unwrapClassUnicode()
                    cls.push(ClassUnicodeRange.new(x.c, x.c))
                    this.push(HirFrame.ClassUnicode(cls))
                } else {
                    val cls1 = this.pop()!!.unwrapClassBytes()
                    val byte =
                        when (val res = this.classLiteralByte(x)) {
                            is Result.Ok -> res.value
                            is Result.Err -> return Result.Err(res.error)
                        }
                    cls1.push(ClassBytesRange.new(byte, byte))
                    this.push(HirFrame.ClassBytes(cls1))
                }
            }
            is ClassSetItem.Range -> {
                val x1 = ast.value
                if (this.flags().unicode()) {
                    val cls2 = this.pop()!!.unwrapClassUnicode()
                    cls2.push(ClassUnicodeRange.new(x1.start.c, x1.end.c))
                    this.push(HirFrame.ClassUnicode(cls2))
                } else {
                    val cls3 = this.pop()!!.unwrapClassBytes()
                    val start =
                        when (val res = this.classLiteralByte(x1.start)) {
                            is Result.Ok -> res.value
                            is Result.Err -> return Result.Err(res.error)
                        }
                    val end =
                        when (val res = this.classLiteralByte(x1.end)) {
                            is Result.Ok -> res.value
                            is Result.Err -> return Result.Err(res.error)
                        }
                    cls3.push(ClassBytesRange.new(start, end))
                    this.push(HirFrame.ClassBytes(cls3))
                }
            }
            is ClassSetItem.Ascii -> {
                val x2 = ast.value
                if (this.flags().unicode()) {
                    val xcls =
                        when (val res = this.hirAsciiUnicodeClass(x2)) {
                            is Result.Ok -> res.value
                            is Result.Err -> return Result.Err(res.error)
                        }
                    val cls4 = this.pop()!!.unwrapClassUnicode()
                    cls4.union(xcls)
                    this.push(HirFrame.ClassUnicode(cls4))
                } else {
                    val xcls1 =
                        when (val res = this.hirAsciiByteClass(x2)) {
                            is Result.Ok -> res.value
                            is Result.Err -> return Result.Err(res.error)
                        }
                    val cls5 = this.pop()!!.unwrapClassBytes()
                    cls5.union(xcls1)
                    this.push(HirFrame.ClassBytes(cls5))
                }
            }
            is ClassSetItem.Unicode -> {
                val xcls2 =
                    when (val res = this.hirUnicodeClass(ast.value)) {
                        is Result.Ok -> res.value
                        is Result.Err -> return Result.Err(res.error)
                    }
                val cls6 = this.pop()!!.unwrapClassUnicode()
                cls6.union(xcls2)
                this.push(HirFrame.ClassUnicode(cls6))
            }
            is ClassSetItem.Perl -> {
                val x4 = ast.value
                if (this.flags().unicode()) {
                    val xcls3 =
                        when (val res = this.hirPerlUnicodeClass(x4)) {
                            is Result.Ok -> res.value
                            is Result.Err -> return Result.Err(res.error)
                        }
                    val cls7 = this.pop()!!.unwrapClassUnicode()
                    cls7.union(xcls3)
                    this.push(HirFrame.ClassUnicode(cls7))
                } else {
                    val xcls4 =
                        when (val res = this.hirPerlByteClass(x4)) {
                            is Result.Ok -> res.value
                            is Result.Err -> return Result.Err(res.error)
                        }
                    val cls8 = this.pop()!!.unwrapClassBytes()
                    cls8.union(xcls4)
                    this.push(HirFrame.ClassBytes(cls8))
                }
            }
            is ClassSetItem.Bracketed -> {
                val ast1 = ast.value
                if (this.flags().unicode()) {
                    val cls1 = this.pop()!!.unwrapClassUnicode()
                    when (val res = this.unicodeFoldAndNegate(ast1.span, ast1.negated, cls1)) {
                        is Result.Ok -> {}
                        is Result.Err -> return Result.Err(res.error)
                    }
                    val cls2 = this.pop()!!.unwrapClassUnicode()
                    cls2.union(cls1)
                    this.push(HirFrame.ClassUnicode(cls2))
                } else {
                    val cls11 = this.pop()!!.unwrapClassBytes()
                    when (val res = this.bytesFoldAndNegate(ast1.span, ast1.negated, cls11)) {
                        is Result.Ok -> {}
                        is Result.Err -> return Result.Err(res.error)
                    }
                    val cls21 = this.pop()!!.unwrapClassBytes()
                    cls21.union(cls11)
                    this.push(HirFrame.ClassBytes(cls21))
                }
            }
            is ClassSetItem.Union -> {}
        }
        return Result.Ok(Unit)
    }

    override fun visitClassSetBinaryOpPre(ast: ClassSetBinaryOp): Result<Unit, Error> {
        if (this.flags().unicode()) {
            this.push(HirFrame.ClassUnicode(ClassUnicode.empty()))
        } else {
            this.push(HirFrame.ClassBytes(ClassBytes.empty()))
        }
        return Result.Ok(Unit)
    }

    override fun visitClassSetBinaryOpIn(ast: ClassSetBinaryOp): Result<Unit, Error> {
        if (this.flags().unicode()) {
            this.push(HirFrame.ClassUnicode(ClassUnicode.empty()))
        } else {
            this.push(HirFrame.ClassBytes(ClassBytes.empty()))
        }
        return Result.Ok(Unit)
    }

    override fun visitClassSetBinaryOpPost(ast: ClassSetBinaryOp): Result<Unit, Error> {
        if (this.flags().unicode()) {
            val rhs = this.pop()!!.unwrapClassUnicode()
            val lhs = this.pop()!!.unwrapClassUnicode()
            val cls = this.pop()!!.unwrapClassUnicode()
            if (this.flags().caseInsensitive()) {
                when (rhs.tryCaseFoldSimple()) {
                    is Result.Ok -> {}
                    is Result.Err -> return Result.Err(this.error(ast.rhs.span().copy(), ErrorKind.UnicodeCaseUnavailable))
                }
                when (lhs.tryCaseFoldSimple()) {
                    is Result.Ok -> {}
                    is Result.Err -> return Result.Err(this.error(ast.lhs.span().copy(), ErrorKind.UnicodeCaseUnavailable))
                }
            }
            when (ast.kind) {
                ClassSetBinaryOpKind.Intersection -> lhs.intersect(rhs)
                ClassSetBinaryOpKind.Difference -> lhs.difference(rhs)
                ClassSetBinaryOpKind.SymmetricDifference -> lhs.symmetricDifference(rhs)
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
            when (ast.kind) {
                ClassSetBinaryOpKind.Intersection -> lhs1.intersect(rhs1)
                ClassSetBinaryOpKind.Difference -> lhs1.difference(rhs1)
                ClassSetBinaryOpKind.SymmetricDifference -> lhs1.symmetricDifference(rhs1)
            }
            cls1.union(lhs1)
            this.push(HirFrame.ClassBytes(cls1))
        }
        return Result.Ok(Unit)
    }

    internal fun trans(): Translator = this.trans

    internal fun push(frame: HirFrame) {
        this.trans().stack.withBorrowMut { guard ->
            guard.value.add(frame)
        }
    }

    internal fun pushChar(ch: UInt) {
        val bytes = encodeScalarUtf8List(ch)
        this.trans().stack.withBorrowMut { guard ->
            val stack = guard.value
            val top = stack.lastOrNull()
            if (top is HirFrame.Literal) {
                top.value.addAll(bytes)
            } else {
                stack.add(HirFrame.Literal(bytes.toMutableList()))
            }
        }
    }

    internal fun pushByte(byte: UByte) {
        this.trans().stack.withBorrowMut { guard ->
            val stack = guard.value
            val top = stack.lastOrNull()
            if (top is HirFrame.Literal) {
                top.value.add(byte)
            } else {
                stack.add(HirFrame.Literal(mutableListOf(byte)))
            }
        }
    }

    internal fun pop(): HirFrame? =
        this.trans().stack.withBorrowMut { guard ->
            guard.value.removeLastOrNull()
        }

    internal fun popConcatExpr(): Hir? {
        val frame = this.pop() ?: return null
        return when (frame) {
            HirFrame.Concat -> null
            is HirFrame.Expr -> frame.value
            is HirFrame.Literal -> Hir.literal(frame.value)
            is HirFrame.ClassUnicode -> error("expected expr or concat, got Unicode class")
            is HirFrame.ClassBytes -> error("expected expr or concat, got byte class")
            HirFrame.Repetition -> error("expected expr or concat, got repetition")
            is HirFrame.Group -> error("expected expr or concat, got group")
            HirFrame.Alternation -> error("expected expr or concat, got alt marker")
            HirFrame.AlternationBranch -> error("expected expr or concat, got alt branch marker")
        }
    }

    internal fun popAltExpr(): Hir? {
        val frame = this.pop() ?: return null
        return when (frame) {
            HirFrame.Alternation -> null
            is HirFrame.Expr -> frame.value
            is HirFrame.Literal -> Hir.literal(frame.value)
            is HirFrame.ClassUnicode -> error("expected expr or alt, got Unicode class")
            is HirFrame.ClassBytes -> error("expected expr or alt, got byte class")
            HirFrame.Repetition -> error("expected expr or alt, got repetition")
            is HirFrame.Group -> error("expected expr or alt, got group")
            HirFrame.Concat -> error("expected expr or alt, got concat marker")
            HirFrame.AlternationBranch -> error("expected expr or alt, got alt branch marker")
        }
    }

    internal fun error(span: Span, kind: ErrorKind): Error =
        Error(kind = kind, pattern = this.pattern, span = span)

    internal fun flags(): Flags =
        this
            .trans()
            .flags
            .get()
            .copy()

    internal fun setFlags(astFlags: io.github.kotlinmania.regexsyntax.ast.Flags): Flags {
        val oldFlags = this.flags()
        val newFlags = Flags.fromAst(astFlags)
        newFlags.merge(oldFlags)
        this.trans().flags.set(newFlags)
        return oldFlags
    }

    internal fun astLiteralToScalar(lit: Literal): Result<Either<UInt, UByte>, Error> {
        if (this.flags().unicode()) {
            return Result.Ok(Either.Left(lit.c))
        }
        val byte1 = lit.byte() ?: return Result.Ok(Either.Left(lit.c))
        if (byte1 <= 0x7Fu.toUByte()) {
            return Result.Ok(Either.Left(byte1.toUInt()))
        }
        if (this.trans().utf8) {
            return Result.Err(this.error(lit.span, ErrorKind.InvalidUtf8))
        }
        return Result.Ok(Either.Right(byte1))
    }

    internal fun caseFoldChar(span: Span, c: UInt): Result<Hir?, Error> {
        if (!this.flags().caseInsensitive()) {
            return Result.Ok(null)
        }
        return if (this.flags().unicode()) {
            val overlaps =
                when (val folderRes = SimpleCaseFolder.new()) {
                    is Result.Ok -> folderRes.value.overlaps(c, c)
                    is Result.Err -> return Result.Err(this.error(span, ErrorKind.UnicodeCaseUnavailable))
                }
            if (!overlaps) {
                return Result.Ok(null)
            }
            val cls = ClassUnicode.new(listOf(ClassUnicodeRange.new(c, c)))
            when (cls.tryCaseFoldSimple()) {
                is Result.Ok -> {}
                is Result.Err -> return Result.Err(this.error(span, ErrorKind.UnicodeCaseUnavailable))
            }
            Result.Ok(Hir.cls(Class.Unicode(cls)))
        } else {
            if (!scalarIsAscii(c)) {
                return Result.Ok(null)
            }
            when (c) {
                in 'A'.code.toUInt()..'Z'.code.toUInt(),
                in 'a'.code.toUInt()..'z'.code.toUInt(),
                -> {}
                else -> return Result.Ok(null)
            }
            val cls1 = ClassBytes.new(listOf(ClassBytesRange.new(c.toUByte(), c.toUByte())))
            cls1.caseFoldSimple()
            Result.Ok(Hir.cls(Class.Bytes(cls1)))
        }
    }

    internal fun hirDot(span: Span): Result<Hir, Error> {
        val utf8 = this.trans().utf8
        val lineterm = this.trans().lineTerminator
        val flags = this.flags()
        if (utf8 && (!flags.unicode() || !scalarIsAscii(lineterm.toUInt()))) {
            return Result.Err(this.error(span, ErrorKind.InvalidUtf8))
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
                        if (!scalarIsAscii(lineterm.toUInt())) {
                            return Result.Err(this.error(span, ErrorKind.InvalidLineTerminator))
                        }
                        Dot.AnyCharExcept(lineterm.toUInt())
                    }
                } else {
                    if (flags.crlf()) {
                        Dot.AnyByteExceptCRLF
                    } else {
                        Dot.AnyByteExcept(lineterm)
                    }
                }
            }
        return Result.Ok(Hir.dot(dot))
    }

    internal fun hirAssertion(asst: Assertion): Result<Hir, Error> {
        val unicode = this.flags().unicode()
        val multiLine = this.flags().multiLine()
        val crlf = this.flags().crlf()
        return Result.Ok(
            when (asst.kind) {
                AssertionKind.StartLine ->
                    Hir.look(
                        if (multiLine) {
                            if (crlf) Look.StartCRLF else Look.StartLF
                        } else {
                            Look.Start
                        },
                    )
                AssertionKind.EndLine ->
                    Hir.look(
                        if (multiLine) {
                            if (crlf) Look.EndCRLF else Look.EndLF
                        } else {
                            Look.End
                        },
                    )
                AssertionKind.StartText -> Hir.look(Look.Start)
                AssertionKind.EndText -> Hir.look(Look.End)
                AssertionKind.WordBoundary ->
                    Hir.look(if (unicode) Look.WordUnicode else Look.WordAscii)
                AssertionKind.NotWordBoundary ->
                    Hir.look(if (unicode) Look.WordUnicodeNegate else Look.WordAsciiNegate)
                AssertionKind.WordBoundaryStart, AssertionKind.WordBoundaryStartAngle ->
                    Hir.look(if (unicode) Look.WordStartUnicode else Look.WordStartAscii)
                AssertionKind.WordBoundaryEnd, AssertionKind.WordBoundaryEndAngle ->
                    Hir.look(if (unicode) Look.WordEndUnicode else Look.WordEndAscii)
                AssertionKind.WordBoundaryStartHalf ->
                    Hir.look(if (unicode) Look.WordStartHalfUnicode else Look.WordStartHalfAscii)
                AssertionKind.WordBoundaryEndHalf ->
                    Hir.look(if (unicode) Look.WordEndHalfUnicode else Look.WordEndHalfAscii)
            },
        )
    }

    internal fun hirCapture(group: Group, expr: Hir): Hir {
        val (index1, name1) =
            when (val kind = group.kind) {
                is GroupKind.CaptureIndex -> Pair(kind.value, null)
                is GroupKind.CaptureName -> Pair(kind.name.index, kind.name.name)
                is GroupKind.NonCapturing -> return expr
            }
        return Hir.capture(Capture(index = index1, name = name1, sub = expr))
    }

    internal fun hirRepetition(rep: Repetition, expr: Hir): Hir {
        val (min, max) =
            when (val kind = rep.op.kind) {
                RepetitionKind.ZeroOrOne -> Pair(0u, 1u)
                RepetitionKind.ZeroOrMore -> Pair(0u, null)
                RepetitionKind.OneOrMore -> Pair(1u, null)
                is RepetitionKind.Range ->
                    when (val range = kind.value) {
                        is RepetitionRange.Exactly -> Pair(range.value, range.value)
                        is RepetitionRange.AtLeast -> Pair(range.value, null)
                        is RepetitionRange.Bounded -> Pair(range.field0, range.field1)
                    }
            }
        val greedy = if (this.flags().swapGreed()) !rep.greedy else rep.greedy
        return Hir.repetition(
            io.github.kotlinmania.regexsyntax.hir.Repetition(
                min = min,
                max = max,
                greedy = greedy,
                sub = expr,
            ),
        )
    }

    internal fun hirUnicodeClass(
        astClass: io.github.kotlinmania.regexsyntax.ast.ClassUnicode,
    ): Result<ClassUnicode, Error> {
        if (!this.flags().unicode()) {
            return Result.Err(this.error(astClass.span, ErrorKind.UnicodeNotAllowed))
        }
        val query =
            when (val kind = astClass.kind) {
                is ClassUnicodeKind.OneLetter -> ClassQuery.OneLetter(kind.value)
                is ClassUnicodeKind.Named -> ClassQuery.Binary(kind.value)
                is ClassUnicodeKind.NamedValue ->
                    ClassQuery.ByValue(propertyName = kind.name, propertyValue = kind.value)
            }
        val clsRes = this.convertUnicodeClassError(astClass.span, cls(query))
        val clsValue =
            when (clsRes) {
                is Result.Ok -> clsRes.value
                is Result.Err -> return clsRes
            }
        when (val res = this.unicodeFoldAndNegate(astClass.span, astClass.isNegated(), clsValue)) {
            is Result.Ok -> {}
            is Result.Err -> return Result.Err(res.error)
        }
        return Result.Ok(clsValue)
    }

    internal fun hirAsciiUnicodeClass(ast: ClassAscii): Result<ClassUnicode, Error> {
        val cls = ClassUnicode.new(asciiClassAsChars(ast.kind).map { (s, e) -> ClassUnicodeRange.new(s, e) })
        when (val res = this.unicodeFoldAndNegate(ast.span, ast.negated, cls)) {
            is Result.Ok -> {}
            is Result.Err -> return Result.Err(res.error)
        }
        return Result.Ok(cls)
    }

    internal fun hirAsciiByteClass(ast: ClassAscii): Result<ClassBytes, Error> {
        val cls = ClassBytes.new(asciiClass(ast.kind).map { (s, e) -> ClassBytesRange.new(s, e) })
        when (val res = this.bytesFoldAndNegate(ast.span, ast.negated, cls)) {
            is Result.Ok -> {}
            is Result.Err -> return Result.Err(res.error)
        }
        return Result.Ok(cls)
    }

    internal fun hirPerlUnicodeClass(astClass: ClassPerl): Result<ClassUnicode, Error> {
        check(this.flags().unicode())
        val raw =
            when (astClass.kind) {
                ClassPerlKind.Digit -> perlDigit()
                ClassPerlKind.Space -> perlSpace()
                ClassPerlKind.Word -> perlWord()
            }
        val cls =
            when (val res = this.convertUnicodeClassError(astClass.span, raw)) {
                is Result.Ok -> res.value
                is Result.Err -> return Result.Err(res.error)
            }
        if (astClass.negated) {
            cls.negate()
        }
        return Result.Ok(cls)
    }

    internal fun hirPerlByteClass(astClass: ClassPerl): Result<ClassBytes, Error> {
        check(!this.flags().unicode())
        val cls =
            when (astClass.kind) {
                ClassPerlKind.Digit -> hirAsciiClassBytes(ClassAsciiKind.Digit)
                ClassPerlKind.Space -> hirAsciiClassBytes(ClassAsciiKind.Space)
                ClassPerlKind.Word -> hirAsciiClassBytes(ClassAsciiKind.Word)
            }
        if (astClass.negated) {
            cls.negate()
        }
        if (this.trans().utf8 && !cls.isAscii()) {
            return Result.Err(this.error(astClass.span, ErrorKind.InvalidUtf8))
        }
        return Result.Ok(cls)
    }

    internal fun convertUnicodeClassError(
        span: Span,
        result: Result<ClassUnicode, UnicodeError>,
    ): Result<ClassUnicode, Error> =
        when (result) {
            is Result.Ok -> Result.Ok(result.value)
            is Result.Err -> {
                val sp = span.copy()
                val kind =
                    when (result.error) {
                        UnicodeError.PropertyNotFound -> ErrorKind.UnicodePropertyNotFound
                        UnicodeError.PropertyValueNotFound -> ErrorKind.UnicodePropertyValueNotFound
                        UnicodeError.PerlClassNotFound -> ErrorKind.UnicodePerlClassNotFound
                    }
                Result.Err(this.error(sp, kind))
            }
        }

    internal fun unicodeFoldAndNegate(
        span: Span,
        negated: Boolean,
        cls: ClassUnicode,
    ): Result<Unit, Error> {
        // Apply case folding before negation so `(?i)[^x]` does not collapse to any scalar.
        if (this.flags().caseInsensitive()) {
            when (cls.tryCaseFoldSimple()) {
                is Result.Ok -> {}
                is Result.Err -> return Result.Err(this.error(span.copy(), ErrorKind.UnicodeCaseUnavailable))
            }
        }
        if (negated) {
            cls.negate()
        }
        return Result.Ok(Unit)
    }

    internal fun bytesFoldAndNegate(
        span: Span,
        negated: Boolean,
        cls: ClassBytes,
    ): Result<Unit, Error> {
        // Apply case folding before negation so `(?i)[^x]` does not collapse to any byte.
        if (this.flags().caseInsensitive()) {
            cls.caseFoldSimple()
        }
        if (negated) {
            cls.negate()
        }
        if (this.trans().utf8 && !cls.isAscii()) {
            return Result.Err(this.error(span.copy(), ErrorKind.InvalidUtf8))
        }
        return Result.Ok(Unit)
    }

    internal fun classLiteralByte(ast: Literal): Result<UByte, Error> =
        when (val res = this.astLiteralToScalar(ast)) {
            is Result.Ok ->
                when (val scalarOrByte = res.value) {
                    is Either.Right -> Result.Ok(scalarOrByte.value)
                    is Either.Left -> {
                        val ch = scalarOrByte.value
                        if (scalarIsAscii(ch)) {
                            Result.Ok(ch.toUByte())
                        } else {
                            Result.Err(this.error(ast.span, ErrorKind.UnicodeNotAllowed))
                        }
                    }
                }
            is Result.Err -> Result.Err(res.error)
        }

    internal companion object {
        internal fun new(trans: Translator, pattern: String): TranslatorI =
            TranslatorI(trans = trans, pattern = pattern)
    }
}

/**
 * A translator's representation of a regular expression's flags at any given
 * moment in time.
 */
internal data class Flags(
    internal var caseInsensitive: Boolean? = null,
    internal var multiLine: Boolean? = null,
    internal var dotMatchesNewLine: Boolean? = null,
    internal var swapGreed: Boolean? = null,
    internal var unicode: Boolean? = null,
    internal var crlf: Boolean? = null,
) {
    internal fun merge(previous: Flags) {
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

    internal fun caseInsensitive(): Boolean = this.caseInsensitive ?: false

    internal fun multiLine(): Boolean = this.multiLine ?: false

    internal fun dotMatchesNewLine(): Boolean = this.dotMatchesNewLine ?: false

    internal fun swapGreed(): Boolean = this.swapGreed ?: false

    internal fun unicode(): Boolean = this.unicode ?: true

    internal fun crlf(): Boolean = this.crlf ?: false

    internal companion object {
        internal fun default(): Flags = Flags()

        internal fun fromAst(ast: io.github.kotlinmania.regexsyntax.ast.Flags): Flags {
            val flags = Flags.default()
            var enable = true
            for (item in ast.items) {
                when (val kind = item.kind) {
                    FlagsItemKind.Negation -> enable = false
                    is FlagsItemKind.Flag ->
                        when (kind.value) {
                            Flag.CaseInsensitive -> flags.caseInsensitive = enable
                            Flag.MultiLine -> flags.multiLine = enable
                            Flag.DotMatchesNewLine -> flags.dotMatchesNewLine = enable
                            Flag.SwapGreed -> flags.swapGreed = enable
                            Flag.Unicode -> flags.unicode = enable
                            Flag.CRLF -> flags.crlf = enable
                            Flag.IgnoreWhitespace -> {}
                        }
                }
            }
            return flags
        }
    }
}

internal fun hirAsciiClassBytes(kind: ClassAsciiKind): ClassBytes {
    val ranges = asciiClass(kind).map { (s, e) -> ClassBytesRange.new(s, e) }
    return ClassBytes.new(ranges)
}

internal fun asciiClass(kind: ClassAsciiKind): List<Pair<UByte, UByte>> =
    when (kind) {
        ClassAsciiKind.Alnum -> listOf(Pair(48u.toUByte(), 57u.toUByte()), Pair(65u.toUByte(), 90u.toUByte()), Pair(97u.toUByte(), 122u.toUByte()))
        ClassAsciiKind.Alpha -> listOf(Pair(65u.toUByte(), 90u.toUByte()), Pair(97u.toUByte(), 122u.toUByte()))
        ClassAsciiKind.Ascii -> listOf(Pair(0u.toUByte(), 127u.toUByte()))
        ClassAsciiKind.Blank -> listOf(Pair(9u.toUByte(), 9u.toUByte()), Pair(32u.toUByte(), 32u.toUByte()))
        ClassAsciiKind.Cntrl -> listOf(Pair(0u.toUByte(), 31u.toUByte()), Pair(127u.toUByte(), 127u.toUByte()))
        ClassAsciiKind.Digit -> listOf(Pair(48u.toUByte(), 57u.toUByte()))
        ClassAsciiKind.Graph -> listOf(Pair(33u.toUByte(), 126u.toUByte()))
        ClassAsciiKind.Lower -> listOf(Pair(97u.toUByte(), 122u.toUByte()))
        ClassAsciiKind.Print -> listOf(Pair(32u.toUByte(), 126u.toUByte()))
        ClassAsciiKind.Punct -> listOf(Pair(33u.toUByte(), 47u.toUByte()), Pair(58u.toUByte(), 64u.toUByte()), Pair(91u.toUByte(), 96u.toUByte()), Pair(123u.toUByte(), 126u.toUByte()))
        ClassAsciiKind.Space -> listOf(Pair(9u.toUByte(), 9u.toUByte()), Pair(10u.toUByte(), 10u.toUByte()), Pair(11u.toUByte(), 11u.toUByte()), Pair(12u.toUByte(), 12u.toUByte()), Pair(13u.toUByte(), 13u.toUByte()), Pair(32u.toUByte(), 32u.toUByte()))
        ClassAsciiKind.Upper -> listOf(Pair(65u.toUByte(), 90u.toUByte()))
        ClassAsciiKind.Word -> listOf(Pair(48u.toUByte(), 57u.toUByte()), Pair(65u.toUByte(), 90u.toUByte()), Pair(95u.toUByte(), 95u.toUByte()), Pair(97u.toUByte(), 122u.toUByte()))
        ClassAsciiKind.Xdigit -> listOf(Pair(48u.toUByte(), 57u.toUByte()), Pair(65u.toUByte(), 70u.toUByte()), Pair(97u.toUByte(), 102u.toUByte()))
    }

internal fun asciiClassAsChars(kind: ClassAsciiKind): List<Pair<UInt, UInt>> =
    asciiClass(kind).map { (s, e) -> Pair(s.toUInt(), e.toUInt()) }
