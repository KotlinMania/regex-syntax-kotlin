// port-lint: source ast/visitor.rs
package io.github.kotlinmania.regexsyntax.ast.visitor

import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.ClassBracketed
import io.github.kotlinmania.regexsyntax.ast.ClassSet
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOp
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOpKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetItem
import io.github.kotlinmania.rustlang.core.result.Result
import io.github.kotlinmania.regexsyntax.ast.Group as AstGroup
import io.github.kotlinmania.regexsyntax.ast.Repetition as AstRepetition

/**
 * A trait for visiting an abstract syntax tree (AST) in depth first order.
 *
 * The principle aim of this trait is to enable callers to perform case
 * analysis on an abstract syntax tree without necessarily using recursion.
 * In particular, this permits callers to do case analysis with constant stack
 * usage, which can be important since the size of an abstract syntax tree
 * may be proportional to end user input.
 *
 * Typical usage of this trait involves providing an implementation and then
 * running it using the [visit] function.
 *
 * Note that the abstract syntax tree for a regular expression is quite
 * complex. Unless you specifically need it, you might be able to use the much
 * simpler [high-level intermediate representation](hir.Hir) and its
 * [corresponding `Visitor` trait](hir.Visitor) instead.
 */
public interface Visitor<Output, Err> {
    /**
     * All implementors of `Visitor` must provide a `finish` method, which
     * yields the result of visiting the AST or an error.
     */
    public fun finish(): Result<Output, Err>

    /**
     * This method is called before beginning traversal of the AST.
     */
    public fun start() {}

    /**
     * This method is called on an `Ast` before descending into child `Ast`
     * nodes.
     */
    public fun visitPre(ast: Ast): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called on an `Ast` after descending all of its child
     * `Ast` nodes.
     */
    public fun visitPost(ast: Ast): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called between child nodes of an
     * [`Alternation`](ast.Alternation).
     */
    public fun visitAlternationIn(): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called between child nodes of a concatenation.
     */
    public fun visitConcatIn(): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called on every [`ClassSetItem`](ast.ClassSetItem)
     * before descending into child nodes.
     */
    public fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called on every [`ClassSetItem`](ast.ClassSetItem)
     * after descending into child nodes.
     */
    public fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called on every
     * [`ClassSetBinaryOp`](ast.ClassSetBinaryOp) before descending into
     * child nodes.
     */
    public fun visitClassSetBinaryOpPre(ast: ClassSetBinaryOp): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called on every
     * [`ClassSetBinaryOp`](ast.ClassSetBinaryOp) after descending into child
     * nodes.
     */
    public fun visitClassSetBinaryOpPost(ast: ClassSetBinaryOp): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called between the left hand and right hand child nodes
     * of a [`ClassSetBinaryOp`](ast.ClassSetBinaryOp).
     */
    public fun visitClassSetBinaryOpIn(ast: ClassSetBinaryOp): Result<Unit, Err> = Result.Ok(Unit)
}

/**
 * Executes an implementation of `Visitor` in constant stack space.
 *
 * This function will visit every node in the given `Ast` while calling the
 * appropriate methods provided by the [Visitor] trait.
 *
 * The primary use case for this method is when one wants to perform case
 * analysis over an `Ast` without using a stack size proportional to the depth
 * of the `Ast`. Namely, this method will instead use constant stack size, but
 * will use heap space proportional to the size of the `Ast`. This may be
 * desirable in cases where the size of `Ast` is proportional to end user
 * input.
 *
 * If the visitor returns an error at any point, then visiting is stopped and
 * the error is returned.
 */
public fun <Output, Err, V : Visitor<Output, Err>> visit(ast: Ast, visitor: V): Result<Output, Err> =
    HeapVisitor.new().visit(ast, visitor)

/**
 * HeapVisitor visits every item in an `Ast` recursively using constant stack
 * size and a heap size proportional to the size of the `Ast`.
 */
internal class HeapVisitor(
    /**
     * A stack of `Ast` nodes. This is roughly analogous to the call stack
     * used in a typical recursive visitor.
     */
    internal var stack: MutableList<Pair<Ast, Frame>>,
    /**
     * Similar to the `Ast` stack above, but is used only for character
     * classes. In particular, character classes embed their own mini
     * recursive syntax.
     */
    internal var stackClass: MutableList<Pair<ClassInduct, ClassFrame>>,
) {
    internal fun <Output, Err, V : Visitor<Output, Err>> visit(ast: Ast, visitor: V): Result<Output, Err> {
        var ast1 = ast
        this.stack.clear()
        this.stackClass.clear()
        visitor.start()
        while (true) {
            when (val res = visitor.visitPre(ast1)) {
                is Result.Err -> return res
                is Result.Ok -> {}
            }
            val inducted =
                when (val res = this.induct(ast1, visitor)) {
                    is Result.Err -> return res
                    is Result.Ok -> res.value
                }
            if (inducted != null) {
                val child = inducted.child()
                this.stack.add(Pair(ast1, inducted))
                ast1 = child
                continue
            }
            // No induction means we have a base case, so we can post visit
            // it now.
            when (val res = visitor.visitPost(ast1)) {
                is Result.Err -> return res
                is Result.Ok -> {}
            }
            // At this point, we now try to pop our call stack until it is
            // either empty or we hit another inductive case.
            while (true) {
                val (postAst1, frame1) =
                    when (val matchVal1 = this.stack.removeLastOrNull()) {
                        null -> return visitor.finish()
                        else -> matchVal1
                    }
                // If this is a concat/alternate, then we might have additional
                // inductive steps to process.
                val nextFrame = this.pop(frame1)
                if (nextFrame != null) {
                    when (nextFrame) {
                        is Frame.Alternation -> {
                            when (val res = visitor.visitAlternationIn()) {
                                is Result.Err -> return res
                                is Result.Ok -> {}
                            }
                        }
                        is Frame.Concat -> {
                            when (val res = visitor.visitConcatIn()) {
                                is Result.Err -> return res
                                is Result.Ok -> {}
                            }
                        }
                        else -> {}
                    }
                    ast1 = nextFrame.child()
                    this.stack.add(Pair(postAst1, nextFrame))
                    break
                }
                // Otherwise, we've finished visiting all the child nodes for
                // this AST, so we can post visit it now.
                when (val res = visitor.visitPost(postAst1)) {
                    is Result.Err -> return res
                    is Result.Ok -> {}
                }
            }
        }
    }

    /**
     * Build a stack frame for the given AST if one is needed (which occurs if
     * and only if there are child nodes in the AST). Otherwise, return None.
     *
     * If this visits a class, then the underlying visitor implementation may
     * return an error which will be passed on here.
     */
    internal fun <Output, Err, V : Visitor<Output, Err>> induct(ast: Ast, visitor: V): Result<Frame?, Err> {
        val frame: Frame? =
            when (ast) {
                is Ast.ClassBracketed -> {
                    when (val res = this.visitClass(ast.value, visitor)) {
                        is Result.Err -> return res
                        is Result.Ok -> {}
                    }
                    null
                }
                is Ast.Repetition -> Frame.Repetition(ast.value)
                is Ast.Group -> Frame.Group(ast.value)
                is Ast.Concat -> {
                    val asts = ast.value.asts
                    if (asts.isEmpty()) null else Frame.Concat(head = asts[0], tail = asts.subList(1, asts.size))
                }
                is Ast.Alternation -> {
                    val asts = ast.value.asts
                    if (asts.isEmpty()) null else Frame.Alternation(head = asts[0], tail = asts.subList(1, asts.size))
                }
                else -> null
            }
        return Result.Ok(frame)
    }

    /**
     * Pops the given frame. If the frame has an additional inductive step,
     * then return it, otherwise return `None`.
     */
    internal fun pop(induct: Frame): Frame? =
        when (induct) {
            is Frame.Repetition -> null
            is Frame.Group -> null
            is Frame.Concat -> {
                val tail = induct.tail
                if (tail.isEmpty()) {
                    null
                } else {
                    Frame.Concat(head = tail[0], tail = tail.subList(1, tail.size))
                }
            }
            is Frame.Alternation -> {
                val tail = induct.tail
                if (tail.isEmpty()) {
                    null
                } else {
                    Frame.Alternation(head = tail[0], tail = tail.subList(1, tail.size))
                }
            }
        }

    internal fun <Output, Err, V : Visitor<Output, Err>> visitClass(ast: ClassBracketed, visitor: V): Result<Unit, Err> {
        var ast2 = ClassInduct.fromBracketed(ast)
        while (true) {
            when (val res = this.visitClassPre(ast2, visitor)) {
                is Result.Err -> return res
                is Result.Ok -> {}
            }
            val inducted = this.inductClass(ast2)
            if (inducted != null) {
                val child = inducted.child()
                this.stackClass.add(Pair(ast2, inducted))
                ast2 = child
                continue
            }
            when (val res = this.visitClassPost(ast2, visitor)) {
                is Result.Err -> return res
                is Result.Ok -> {}
            }
            // At this point, we now try to pop our call stack until it is
            // either empty or we hit another inductive case.
            while (true) {
                val (postAst1, frame1) =
                    when (val matchVal1 = this.stackClass.removeLastOrNull()) {
                        null -> return Result.Ok(Unit)
                        else -> matchVal1
                    }
                // If this is a union or a binary op, then we might have
                // additional inductive steps to process.
                val nextFrame = this.popClass(frame1)
                if (nextFrame != null) {
                    if (nextFrame is ClassFrame.BinaryRHS) {
                        when (val res = visitor.visitClassSetBinaryOpIn(nextFrame.op)) {
                            is Result.Err -> return res
                            is Result.Ok -> {}
                        }
                    }
                    ast2 = nextFrame.child()
                    this.stackClass.add(Pair(postAst1, nextFrame))
                    break
                }
                // Otherwise, we've finished visiting all the child nodes for
                // this class node, so we can post visit it now.
                when (val res = this.visitClassPost(postAst1, visitor)) {
                    is Result.Err -> return res
                    is Result.Ok -> {}
                }
            }
        }
    }

    /**
     * Call the appropriate `Visitor` methods given an inductive step.
     */
    internal fun <Output, Err, V : Visitor<Output, Err>> visitClassPre(ast: ClassInduct, visitor: V): Result<Unit, Err> =
        when (ast) {
            is ClassInduct.Item -> visitor.visitClassSetItemPre(ast.value)
            is ClassInduct.BinaryOp -> visitor.visitClassSetBinaryOpPre(ast.value)
        }

    /**
     * Call the appropriate `Visitor` methods given an inductive step.
     */
    internal fun <Output, Err, V : Visitor<Output, Err>> visitClassPost(ast: ClassInduct, visitor: V): Result<Unit, Err> =
        when (ast) {
            is ClassInduct.Item -> visitor.visitClassSetItemPost(ast.value)
            is ClassInduct.BinaryOp -> visitor.visitClassSetBinaryOpPost(ast.value)
        }

    /**
     * Build a stack frame for the given class node if one is needed (which
     * occurs if and only if there are child nodes). Otherwise, return None.
     */
    internal fun inductClass(ast: ClassInduct): ClassFrame? =
        when (ast) {
            is ClassInduct.Item -> {
                when (val item = ast.value) {
                    is ClassSetItem.Bracketed -> {
                        when (val kind = item.value.kind) {
                            is ClassSet.Item -> ClassFrame.Union(head = kind.value, tail = emptyList())
                            is ClassSet.BinaryOp -> ClassFrame.Binary(op = kind.value)
                        }
                    }
                    is ClassSetItem.Union -> {
                        val items = item.value.items
                        if (items.isEmpty()) null else ClassFrame.Union(head = items[0], tail = items.subList(1, items.size))
                    }
                    else -> null
                }
            }
            is ClassInduct.BinaryOp -> {
                val op = ast.value
                ClassFrame.BinaryLHS(op = op, lhs = op.lhs, rhs = op.rhs)
            }
        }

    /**
     * Pops the given frame. If the frame has an additional inductive step,
     * then return it, otherwise return `None`.
     */
    internal fun popClass(induct: ClassFrame): ClassFrame? =
        when (induct) {
            is ClassFrame.Union -> {
                val tail = induct.tail
                if (tail.isEmpty()) {
                    null
                } else {
                    ClassFrame.Union(head = tail[0], tail = tail.subList(1, tail.size))
                }
            }
            is ClassFrame.Binary -> null
            is ClassFrame.BinaryLHS -> ClassFrame.BinaryRHS(op = induct.op, rhs = induct.rhs)
            is ClassFrame.BinaryRHS -> null
        }

    internal companion object {
        internal fun new(): HeapVisitor = HeapVisitor(stack = mutableListOf(), stackClass = mutableListOf())
    }
}

/**
 * Represents a single stack frame while performing structural induction over
 * an `Ast`.
 */
internal sealed class Frame {
    /**
     * A stack frame allocated just before descending into a repetition
     * operator's child node.
     */
    internal data class Repetition(
        internal var value: AstRepetition,
    ) : Frame()

    /**
     * A stack frame allocated just before descending into a group's child
     * node.
     */
    internal data class Group(
        internal var value: AstGroup,
    ) : Frame()

    /**
     * The stack frame used while visiting every child node of a concatenation
     * of expressions.
     */
    internal data class Concat(
        internal var head: Ast,
        internal var tail: List<Ast>,
    ) : Frame()

    /**
     * The stack frame used while visiting every child node of an alternation
     * of expressions.
     */
    internal data class Alternation(
        internal var head: Ast,
        internal var tail: List<Ast>,
    ) : Frame()

    /**
     * Perform the next inductive step on this frame and return the next
     * child AST node to visit.
     */
    internal fun child(): Ast =
        when (this) {
            is Frame.Repetition -> this.value.ast
            is Frame.Group -> this.value.ast
            is Frame.Concat -> this.head
            is Frame.Alternation -> this.head
        }
}

/**
 * Represents a single stack frame while performing structural induction over
 * a character class.
 */
internal sealed class ClassFrame {
    /**
     * The stack frame used while visiting every child node of a union of
     * character class items.
     */
    internal data class Union(
        internal var head: ClassSetItem,
        internal var tail: List<ClassSetItem>,
    ) : ClassFrame()

    /**
     * The stack frame used while a binary class operation.
     */
    internal data class Binary(
        internal var op: ClassSetBinaryOp,
    ) : ClassFrame()

    /**
     * A stack frame allocated just before descending into a binary operator's
     * left hand child node.
     */
    internal data class BinaryLHS(
        internal var op: ClassSetBinaryOp,
        internal var lhs: ClassSet,
        internal var rhs: ClassSet,
    ) : ClassFrame()

    /**
     * A stack frame allocated just before descending into a binary operator's
     * right hand child node.
     */
    internal data class BinaryRHS(
        internal var op: ClassSetBinaryOp,
        internal var rhs: ClassSet,
    ) : ClassFrame()

    /**
     * Perform the next inductive step on this frame and return the next
     * child class node to visit.
     */
    internal fun child(): ClassInduct =
        when (this) {
            is ClassFrame.Union -> ClassInduct.Item(this.head)
            is ClassFrame.Binary -> ClassInduct.BinaryOp(this.op)
            is ClassFrame.BinaryLHS -> ClassInduct.fromSet(this.lhs)
            is ClassFrame.BinaryRHS -> ClassInduct.fromSet(this.rhs)
        }

    internal fun fmt(f: StringBuilder) {
        val x =
            when (this) {
                is ClassFrame.Union -> "Union"
                is ClassFrame.Binary -> "Binary"
                is ClassFrame.BinaryLHS -> "BinaryLHS"
                is ClassFrame.BinaryRHS -> "BinaryRHS"
            }
        f.append(x)
    }
}

/**
 * A representation of the inductive step when performing structural induction
 * over a character class.
 *
 * Note that there is no analogous explicit type for the inductive step for
 * `Ast` nodes because the inductive step is just an `Ast`. For character
 * classes, the inductive step can produce one of two possible child nodes:
 * an item or a binary operation. (An item cannot be a binary operation
 * because that would imply binary operations can be unioned in the concrete
 * syntax, which is not possible.)
 */
internal sealed class ClassInduct {
    internal data class Item(
        internal var value: ClassSetItem,
    ) : ClassInduct()
    internal data class BinaryOp(
        internal var value: ClassSetBinaryOp,
    ) : ClassInduct()

    internal fun fmt(f: StringBuilder) {
        val x =
            when (this) {
                is ClassInduct.Item -> {
                    when (this.value) {
                        is ClassSetItem.Empty -> "Item(Empty)"
                        is ClassSetItem.Literal -> "Item(Literal)"
                        is ClassSetItem.Range -> "Item(Range)"
                        is ClassSetItem.Ascii -> "Item(Ascii)"
                        is ClassSetItem.Perl -> "Item(Perl)"
                        is ClassSetItem.Unicode -> "Item(Unicode)"
                        is ClassSetItem.Bracketed -> "Item(Bracketed)"
                        is ClassSetItem.Union -> "Item(Union)"
                    }
                }
                is ClassInduct.BinaryOp -> {
                    when (this.value.kind) {
                        ClassSetBinaryOpKind.Intersection -> "BinaryOp(Intersection)"
                        ClassSetBinaryOpKind.Difference -> "BinaryOp(Difference)"
                        ClassSetBinaryOpKind.SymmetricDifference -> "BinaryOp(SymmetricDifference)"
                    }
                }
            }
        f.append(x)
    }

    internal companion object {
        internal fun fromBracketed(ast: ClassBracketed): ClassInduct = ClassInduct.fromSet(ast.kind)

        internal fun fromSet(ast: ClassSet): ClassInduct =
            when (ast) {
                is ClassSet.Item -> ClassInduct.Item(ast.value)
                is ClassSet.BinaryOp -> ClassInduct.BinaryOp(ast.value)
            }
    }
}
