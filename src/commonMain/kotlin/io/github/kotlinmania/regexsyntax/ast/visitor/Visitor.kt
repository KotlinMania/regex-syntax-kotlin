// port-lint: source src/ast/visitor.rs
package io.github.kotlinmania.regexsyntax.ast.visitor

/*
 * Copyright (c) The rust-lang regex contributors.
 * Licensed under either of Apache-2.0 OR MIT.
 */

import io.github.kotlinmania.regexsyntax.ast.Alternation
import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.ClassBracketed
import io.github.kotlinmania.regexsyntax.ast.ClassSet
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOp
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOpKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetItem
import io.github.kotlinmania.regexsyntax.ast.Concat
import io.github.kotlinmania.regexsyntax.ast.Group
import io.github.kotlinmania.regexsyntax.ast.Repetition

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
 * simpler [high-level intermediate representation][io.github.kotlinmania.regexsyntax.hir.Hir] and its
 * [corresponding `Visitor` trait][io.github.kotlinmania.regexsyntax.hir.visitor.Visitor] instead.
 */
interface Visitor<Output, Err> {
    /**
     * All implementors of [Visitor] must provide a [finish] method, which
     * yields the result of visiting the AST or an error.
     */
    fun finish(): Result<Output>

    /** This method is called before beginning traversal of the AST. */
    fun start() {}

    /**
     * This method is called on an [Ast] before descending into child [Ast]
     * nodes.
     */
    fun visitPre(ast: Ast): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on an [Ast] after descending all of its child
     * [Ast] nodes.
     */
    fun visitPost(ast: Ast): Result<Unit> = Result.success(Unit)

    /** This method is called between child nodes of an [Alternation]. */
    fun visitAlternationIn(): Result<Unit> = Result.success(Unit)

    /** This method is called between child nodes of a concatenation. */
    fun visitConcatIn(): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on every [ClassSetItem] before descending into
     * child nodes.
     */
    fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on every [ClassSetItem] after descending into
     * child nodes.
     */
    fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on every [ClassSetBinaryOp] before descending into
     * child nodes.
     */
    fun visitClassSetBinaryOpPre(ast: ClassSetBinaryOp): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on every [ClassSetBinaryOp] after descending into child
     * nodes.
     */
    fun visitClassSetBinaryOpPost(ast: ClassSetBinaryOp): Result<Unit> = Result.success(Unit)

    /**
     * This method is called between the left hand and right hand child nodes
     * of a [ClassSetBinaryOp].
     */
    fun visitClassSetBinaryOpIn(ast: ClassSetBinaryOp): Result<Unit> = Result.success(Unit)
}

/**
 * Executes an implementation of [Visitor] in constant stack space.
 *
 * This function will visit every node in the given [Ast] while calling the
 * appropriate methods provided by the [Visitor] trait.
 *
 * The primary use case for this method is when one wants to perform case
 * analysis over an [Ast] without using a stack size proportional to the depth
 * of the [Ast]. Namely, this method will instead use constant stack size, but
 * will use heap space proportional to the size of the [Ast]. This may be
 * desirable in cases where the size of [Ast] is proportional to end user
 * input.
 *
 * If the visitor returns an error at any point, then visiting is stopped and
 * the error is returned.
 */
fun <Output, Err> visit(ast: Ast, visitor: Visitor<Output, Err>): Result<Output> {
    return HeapVisitor().visit(ast, visitor)
}

/**
 * [HeapVisitor] visits every item in an [Ast] recursively using constant stack
 * size and a heap size proportional to the size of the [Ast].
 */
private class HeapVisitor {
    /**
     * A stack of [Ast] nodes. This is roughly analogous to the call stack
     * used in a typical recursive visitor.
     */
    private val stack: MutableList<Pair<Ast, Frame>> = mutableListOf()

    /**
     * Similar to the [Ast] stack above, but is used only for character
     * classes. In particular, character classes embed their own mini
     * recursive syntax.
     */
    private val stackClass: MutableList<Pair<ClassInduct, ClassFrame>> = mutableListOf()

    fun <Output, Err> visit(start: Ast, visitor: Visitor<Output, Err>): Result<Output> {
        stack.clear()
        stackClass.clear()
        var ast = start

        visitor.start()
        while (true) {
            val pre = visitor.visitPre(ast)
            if (pre.isFailure) return Result.failure(pre.exceptionOrNull()!!)
            val ind = induct(ast, visitor)
            if (ind.isFailure) return Result.failure(ind.exceptionOrNull()!!)
            val x = ind.getOrNull()
            if (x != null) {
                val child = x.child()
                stack.add(Pair(ast, x))
                ast = child
                continue
            }
            // No induction means we have a base case, so we can post visit
            // it now.
            val post = visitor.visitPost(ast)
            if (post.isFailure) return Result.failure(post.exceptionOrNull()!!)

            // At this point, we now try to pop our call stack until it is
            // either empty or we hit another inductive case.
            innerLoop@ while (true) {
                if (stack.isEmpty()) return visitor.finish()
                val (postAst, frame) = stack.removeAt(stack.lastIndex)
                // If this is a concat/alternate, then we might have additional
                // inductive steps to process.
                val next = pop(frame)
                if (next != null) {
                    when (next) {
                        is Frame.Alternation -> {
                            val r = visitor.visitAlternationIn()
                            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                        }
                        is Frame.Concat -> {
                            val r = visitor.visitConcatIn()
                            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                        }
                        else -> {}
                    }
                    ast = next.child()
                    stack.add(Pair(postAst, next))
                    break@innerLoop
                }
                // Otherwise, we've finished visiting all the child nodes for
                // this AST, so we can post visit it now.
                val pp = visitor.visitPost(postAst)
                if (pp.isFailure) return Result.failure(pp.exceptionOrNull()!!)
            }
        }
    }

    /**
     * Build a stack frame for the given AST if one is needed (which occurs if
     * and only if there are child nodes in the AST). Otherwise, return null.
     *
     * If this visits a class, then the underlying visitor implementation may
     * return an error which will be passed on here.
     */
    private fun <Output, Err> induct(ast: Ast, visitor: Visitor<Output, Err>): Result<Frame?> {
        return when (ast) {
            is Ast.ClassBracketed -> {
                val r = visitClass(ast.value, visitor)
                if (r.isFailure) Result.failure(r.exceptionOrNull()!!)
                else Result.success(null)
            }
            is Ast.Repetition -> Result.success(Frame.Repetition(ast.value))
            is Ast.Group -> Result.success(Frame.Group(ast.value))
            is Ast.Concat -> if (ast.value.asts.isEmpty()) Result.success(null) else
                Result.success(Frame.Concat(head = ast.value.asts[0], tail = ast.value.asts.subList(1, ast.value.asts.size)))
            is Ast.Alternation -> if (ast.value.asts.isEmpty()) Result.success(null) else
                Result.success(Frame.Alternation(head = ast.value.asts[0], tail = ast.value.asts.subList(1, ast.value.asts.size)))
            else -> Result.success(null)
        }
    }

    /**
     * Pops the given frame. If the frame has an additional inductive step,
     * then return it, otherwise return `null`.
     */
    private fun pop(induct: Frame): Frame? {
        return when (induct) {
            is Frame.Repetition -> null
            is Frame.Group -> null
            is Frame.Concat -> if (induct.tail.isEmpty()) null else
                Frame.Concat(head = induct.tail[0], tail = induct.tail.subList(1, induct.tail.size))
            is Frame.Alternation -> if (induct.tail.isEmpty()) null else
                Frame.Alternation(head = induct.tail[0], tail = induct.tail.subList(1, induct.tail.size))
        }
    }

    private fun <Output, Err> visitClass(bracketed: ClassBracketed, visitor: Visitor<Output, Err>): Result<Unit> {
        var ast: ClassInduct = ClassInduct.fromBracketed(bracketed)
        while (true) {
            val pre = visitClassPre(ast, visitor)
            if (pre.isFailure) return Result.failure(pre.exceptionOrNull()!!)
            val x = inductClass(ast)
            if (x != null) {
                val child = x.child()
                stackClass.add(Pair(ast, x))
                ast = child
                continue
            }
            val post = visitClassPost(ast, visitor)
            if (post.isFailure) return Result.failure(post.exceptionOrNull()!!)

            // At this point, we now try to pop our call stack until it is
            // either empty or we hit another inductive case.
            innerLoop@ while (true) {
                if (stackClass.isEmpty()) return Result.success(Unit)
                val (postAst, frame) = stackClass.removeAt(stackClass.lastIndex)
                // If this is a union or a binary op, then we might have
                // additional inductive steps to process.
                val next = popClass(frame)
                if (next != null) {
                    if (next is ClassFrame.BinaryRHS) {
                        val r = visitor.visitClassSetBinaryOpIn(next.op)
                        if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
                    }
                    ast = next.child()
                    stackClass.add(Pair(postAst, next))
                    break@innerLoop
                }
                // Otherwise, we've finished visiting all the child nodes for
                // this class node, so we can post visit it now.
                val pp = visitClassPost(postAst, visitor)
                if (pp.isFailure) return Result.failure(pp.exceptionOrNull()!!)
            }
        }
    }

    /** Call the appropriate [Visitor] methods given an inductive step. */
    private fun <Output, Err> visitClassPre(ast: ClassInduct, visitor: Visitor<Output, Err>): Result<Unit> {
        return when (ast) {
            is ClassInduct.Item -> visitor.visitClassSetItemPre(ast.item)
            is ClassInduct.BinaryOp -> visitor.visitClassSetBinaryOpPre(ast.op)
        }
    }

    /** Call the appropriate [Visitor] methods given an inductive step. */
    private fun <Output, Err> visitClassPost(ast: ClassInduct, visitor: Visitor<Output, Err>): Result<Unit> {
        return when (ast) {
            is ClassInduct.Item -> visitor.visitClassSetItemPost(ast.item)
            is ClassInduct.BinaryOp -> visitor.visitClassSetBinaryOpPost(ast.op)
        }
    }

    /**
     * Build a stack frame for the given class node if one is needed (which
     * occurs if and only if there are child nodes). Otherwise, return null.
     */
    private fun inductClass(ast: ClassInduct): ClassFrame? {
        return when (ast) {
            is ClassInduct.Item -> when (val item = ast.item) {
                is ClassSetItem.Bracketed -> when (val k = item.value.kind) {
                    is ClassSet.Item -> ClassFrame.Union(head = k.value, tail = emptyList())
                    is ClassSet.BinaryOp -> ClassFrame.Binary(op = k.value)
                }
                is ClassSetItem.Union -> if (item.value.items.isEmpty()) null else
                    ClassFrame.Union(head = item.value.items[0], tail = item.value.items.subList(1, item.value.items.size))
                else -> null
            }
            is ClassInduct.BinaryOp -> ClassFrame.BinaryLHS(op = ast.op, lhs = ast.op.lhs, rhs = ast.op.rhs)
        }
    }

    /**
     * Pops the given frame. If the frame has an additional inductive step,
     * then return it, otherwise return `null`.
     */
    private fun popClass(induct: ClassFrame): ClassFrame? {
        return when (induct) {
            is ClassFrame.Union -> if (induct.tail.isEmpty()) null else
                ClassFrame.Union(head = induct.tail[0], tail = induct.tail.subList(1, induct.tail.size))
            is ClassFrame.Binary -> null
            is ClassFrame.BinaryLHS -> ClassFrame.BinaryRHS(op = induct.op, rhs = induct.rhs)
            is ClassFrame.BinaryRHS -> null
        }
    }
}

/**
 * Represents a single stack frame while performing structural induction over
 * an [Ast].
 */
private sealed class Frame {
    /**
     * A stack frame allocated just before descending into a repetition
     * operator's child node.
     */
    class Repetition(val rep: io.github.kotlinmania.regexsyntax.ast.Repetition) : Frame()

    /**
     * A stack frame allocated just before descending into a group's child
     * node.
     */
    class Group(val group: io.github.kotlinmania.regexsyntax.ast.Group) : Frame()

    /**
     * The stack frame used while visiting every child node of a concatenation
     * of expressions.
     */
    class Concat(
        /** The child node we are currently visiting. */
        val head: Ast,
        /** The remaining child nodes to visit (which may be empty). */
        val tail: List<Ast>,
    ) : Frame()

    /**
     * The stack frame used while visiting every child node of an alternation
     * of expressions.
     */
    class Alternation(
        /** The child node we are currently visiting. */
        val head: Ast,
        /** The remaining child nodes to visit (which may be empty). */
        val tail: List<Ast>,
    ) : Frame()

    /**
     * Perform the next inductive step on this frame and return the next
     * child AST node to visit.
     */
    fun child(): Ast = when (this) {
        is Repetition -> rep.ast
        is Group -> group.ast
        is Concat -> head
        is Alternation -> head
    }
}

/**
 * Represents a single stack frame while performing structural induction over
 * a character class.
 */
private sealed class ClassFrame {
    /**
     * The stack frame used while visiting every child node of a union of
     * character class items.
     */
    class Union(
        /** The child node we are currently visiting. */
        val head: ClassSetItem,
        /** The remaining child nodes to visit (which may be empty). */
        val tail: List<ClassSetItem>,
    ) : ClassFrame()

    /** The stack frame used while a binary class operation. */
    class Binary(val op: ClassSetBinaryOp) : ClassFrame()

    /**
     * A stack frame allocated just before descending into a binary operator's
     * left hand child node.
     */
    class BinaryLHS(
        val op: ClassSetBinaryOp,
        val lhs: ClassSet,
        val rhs: ClassSet,
    ) : ClassFrame()

    /**
     * A stack frame allocated just before descending into a binary operator's
     * right hand child node.
     */
    class BinaryRHS(val op: ClassSetBinaryOp, val rhs: ClassSet) : ClassFrame()

    /**
     * Perform the next inductive step on this frame and return the next
     * child class node to visit.
     */
    fun child(): ClassInduct = when (this) {
        is Union -> ClassInduct.Item(head)
        is Binary -> ClassInduct.BinaryOp(op)
        is BinaryLHS -> ClassInduct.fromSet(lhs)
        is BinaryRHS -> ClassInduct.fromSet(rhs)
    }

    override fun toString(): String = when (this) {
        is Union -> "Union"
        is Binary -> "Binary"
        is BinaryLHS -> "BinaryLHS"
        is BinaryRHS -> "BinaryRHS"
    }
}

/**
 * A representation of the inductive step when performing structural induction
 * over a character class.
 *
 * Note that there is no analogous explicit type for the inductive step for
 * [Ast] nodes because the inductive step is just an [Ast]. For character
 * classes, the inductive step can produce one of two possible child nodes:
 * an item or a binary operation. (An item cannot be a binary operation
 * because that would imply binary operations can be unioned in the concrete
 * syntax, which is not possible.)
 */
private sealed class ClassInduct {
    class Item(val item: ClassSetItem) : ClassInduct()
    class BinaryOp(val op: ClassSetBinaryOp) : ClassInduct()

    override fun toString(): String = when (this) {
        is Item -> when (item) {
            is ClassSetItem.Empty -> "Item(Empty)"
            is ClassSetItem.Literal -> "Item(Literal)"
            is ClassSetItem.Range -> "Item(Range)"
            is ClassSetItem.Ascii -> "Item(Ascii)"
            is ClassSetItem.Perl -> "Item(Perl)"
            is ClassSetItem.Unicode -> "Item(Unicode)"
            is ClassSetItem.Bracketed -> "Item(Bracketed)"
            is ClassSetItem.Union -> "Item(Union)"
        }
        is BinaryOp -> when (op.kind) {
            ClassSetBinaryOpKind.Intersection -> "BinaryOp(Intersection)"
            ClassSetBinaryOpKind.Difference -> "BinaryOp(Difference)"
            ClassSetBinaryOpKind.SymmetricDifference -> "BinaryOp(SymmetricDifference)"
        }
    }

    companion object {
        fun fromBracketed(ast: ClassBracketed): ClassInduct = fromSet(ast.kind)

        fun fromSet(ast: ClassSet): ClassInduct = when (ast) {
            is ClassSet.Item -> Item(ast.value)
            is ClassSet.BinaryOp -> BinaryOp(ast.value)
        }
    }
}
