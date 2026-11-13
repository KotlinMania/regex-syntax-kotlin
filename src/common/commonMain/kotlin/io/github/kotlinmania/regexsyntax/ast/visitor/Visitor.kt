// port-lint: source ast/visitor.rs
package io.github.kotlinmania.regexsyntax.ast.visitor

import io.github.kotlinmania.regexsyntax.ast.Ast
import io.github.kotlinmania.regexsyntax.ast.ClassBracketed
import io.github.kotlinmania.regexsyntax.ast.ClassSet
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOp
import io.github.kotlinmania.regexsyntax.ast.ClassSetBinaryOpKind
import io.github.kotlinmania.regexsyntax.ast.ClassSetItem
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
 * simpler [high-level intermediate representation](hir.Hir) and its
 * [corresponding `Visitor` trait](hir.Visitor) instead.
 */
public interface Visitor<Output, Err> {
    /**
     * All implementors of `Visitor` must provide a `finish` method, which
     * yields the result of visiting the AST or an error.
     */
    public fun finish(): Result<Output>

    /**
     * This method is called before beginning traversal of the AST.
     */
    public fun start() {}

    /**
     * This method is called on an `Ast` before descending into child `Ast`
     * nodes.
     */
    public fun visitPre(ast: Ast): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on an `Ast` after descending all of its child
     * `Ast` nodes.
     */
    public fun visitPost(ast: Ast): Result<Unit> = Result.success(Unit)

    /**
     * This method is called between child nodes of an
     * [`Alternation`](ast.Alternation).
     */
    public fun visitAlternationIn(): Result<Unit> = Result.success(Unit)

    /**
     * This method is called between child nodes of a concatenation.
     */
    public fun visitConcatIn(): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on every [`ClassSetItem`](ast.ClassSetItem)
     * before descending into child nodes.
     */
    public fun visitClassSetItemPre(ast: ClassSetItem): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on every [`ClassSetItem`](ast.ClassSetItem)
     * after descending into child nodes.
     */
    public fun visitClassSetItemPost(ast: ClassSetItem): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on every
     * [`ClassSetBinaryOp`](ast.ClassSetBinaryOp) before descending into
     * child nodes.
     */
    public fun visitClassSetBinaryOpPre(ast: ClassSetBinaryOp): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on every
     * [`ClassSetBinaryOp`](ast.ClassSetBinaryOp) after descending into child
     * nodes.
     */
    public fun visitClassSetBinaryOpPost(ast: ClassSetBinaryOp): Result<Unit> = Result.success(Unit)

    /**
     * This method is called between the left hand and right hand child nodes
     * of a [`ClassSetBinaryOp`](ast.ClassSetBinaryOp).
     */
    public fun visitClassSetBinaryOpIn(ast: ClassSetBinaryOp): Result<Unit> = Result.success(Unit)
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
public fun <Output, Err, V : Visitor> visit(ast: Ast, visitor: V): Result<Output> = HeapVisitor.new().visit(ast, visitor)

/**
 * HeapVisitor visits every item in an `Ast` recursively using constant stack
 * size and a heap size proportional to the size of the `Ast`.
 */
public open class HeapVisitor(
    /**
     * A stack of `Ast` nodes. This is roughly analogous to the call stack
     * used in a typical recursive visitor.
     */
    public var stack: MutableList<Pair<Ast, Frame>>,
    /**
     * Similar to the `Ast` stack above, but is used only for character
     * classes. In particular, character classes embed their own mini
     * recursive syntax.
     */
    public var stackClass: MutableList<Pair<ClassInduct, ClassFrame>>,
) {
    public open fun <Output, Err, V : Visitor> visit(ast: Ast, visitor: V): Result<Output> {
        var ast1 = ast
        this.stack.clear()
        this.stackClass.clear()
        visitor.start()
        while (true) {
            visitor.visitPre(ast1).getOrElse { return Result.failure(it) }
            when (val matchVal = this.induct(ast1, visitor).getOrElse { return Result.failure(it) }) {
                null -> {}
                else -> {
                    val x = matchVal
                    val child = x.child()
                    this.stack.add(Pair(ast1, x))
                    ast1 = child
                    continue
                }
            }
            // No induction means we have a base case, so we can post visit
            // it now.
            visitor.visitPost(ast1).getOrElse { return Result.failure(it) }
            // At this point, we now try to pop our call stack until it is
            // either empty or we hit another inductive case.
            while (true) {
                val (postAst1, frame1) =
                    when (val matchVal1 = this.stack.removeLastOrNull()) {
                        null -> { return visitor.finish() }
                        else -> {
                            val postAst = matchVal1.first
                            val frame = matchVal1.second
                            Pair(postAst, frame)
                        }
                    }
                // If this is a concat/alternate, then we might have additional
                // inductive steps to process.
                when (val matchVal2 = this.pop(frame1)) {
                    null -> {}
                    else -> {
                        val x1 = matchVal2
                        when (x1) {
                            is Frame.Alternation -> {
                                visitor.visitAlternationIn().getOrElse { return Result.failure(it) }
                            }
                            is Frame.Concat -> {
                                visitor.visitConcatIn().getOrElse { return Result.failure(it) }
                            }
                            else -> {}
                        }
                        ast1 = x1.child()
                        this.stack.add(Pair(postAst1, x1))
                        break
                    }
                }
                // Otherwise, we've finished visiting all the child nodes for
                // this AST, so we can post visit it now.
                visitor.visitPost(postAst1).getOrElse { return Result.failure(it) }
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
    public open fun <Output, Err, V : Visitor> induct(ast: Ast, visitor: V): Result<Frame?> {
        return Result.success(when {
            ast is Ast.ClassBracketed -> {
                val (x) = ast
                this.visitClass(x, visitor).getOrElse { return Result.failure(it) }
                null
            }
            ast is Ast.Repetition -> {
                val (x1) = ast
                Frame.Repetition(x1)
            }
            ast is Ast.Group -> {
                val (x2) = ast
                Frame.Group(x2)
            }
            ast is Ast.Concat && (ast as Ast.Concat).value.asts.isEmpty() -> {
                val (x3) = ast
                null
            }
            ast is Ast.Concat -> {
                val (x4) = ast
                Frame.Concat(head = x4.asts[0], tail = x4.asts[1 until x4.asts.size])
            }
            ast is Ast.Alternation && (ast as Ast.Alternation).value.asts.isEmpty() -> {
                val (x5) = ast
                null
            }
            ast is Ast.Alternation -> {
                val (x6) = ast
                Frame.Alternation(head = x6.asts[0], tail = x6.asts[1 until x6.asts.size])
            }
            else -> null
        })
    }

    /**
     * Pops the given frame. If the frame has an additional inductive step,
     * then return it, otherwise return `None`.
     */
    public open fun pop(induct: Frame): Frame? =
        when (induct) {
            is Frame.Repetition -> null
            is Frame.Group -> null
            is Frame.Concat -> {
                val tail = induct.tail
                if (tail.isEmpty()) {
                    null
                } else {
                    Frame.Concat(head = tail[0], tail = tail[1 until tail.size])
                }
            }
            is Frame.Alternation -> {
                val tail1 = induct.tail
                if (tail1.isEmpty()) {
                    null
                } else {
                    Frame.Alternation(head = tail1[0], tail = tail1[1 until tail1.size])
                }
            }
        }

    public open fun <Output, Err, V : Visitor> visitClass(ast: ClassBracketed, visitor: V): Result<Unit> {
        val ast1 = ast
        var ast2 = ClassInduct.fromBracketed(ast1)
        while (true) {
            this.visitClassPre(ast2, visitor).getOrElse { return Result.failure(it) }
            when (val matchVal = this.inductClass(ast2)) {
                null -> {}
                else -> {
                    val x = matchVal
                    val child = x.child()
                    this.stackClass.add(Pair(ast2, x))
                    ast2 = child
                    continue
                }
            }
            this.visitClassPost(ast2, visitor).getOrElse { return Result.failure(it) }
            // At this point, we now try to pop our call stack until it is
            // either empty or we hit another inductive case.
            while (true) {
                val (postAst1, frame1) =
                    when (val matchVal1 = this.stackClass.removeLastOrNull()) {
                        null -> { return Result.success(Unit) }
                        else -> {
                            val postAst = matchVal1.first
                            val frame = matchVal1.second
                            Pair(postAst, frame)
                        }
                    }
                // If this is a union or a binary op, then we might have
                // additional inductive steps to process.
                when (val matchVal2 = this.popClass(frame1)) {
                    null -> {}
                    else -> {
                        val x1 = matchVal2
                        if (x1 is ClassFrame.BinaryRHS) {
                            val op = x1.op
                            visitor.visitClassSetBinaryOpIn(op).getOrElse { return Result.failure(it) }
                        }
                        ast2 = x1.child()
                        this.stackClass.add(Pair(postAst1, x1))
                        break
                    }
                }
                // Otherwise, we've finished visiting all the child nodes for
                // this class node, so we can post visit it now.
                this.visitClassPost(postAst1, visitor).getOrElse { return Result.failure(it) }
            }
        }
    }

    /**
     * Call the appropriate `Visitor` methods given an inductive step.
     */
    public open fun <Output, Err, V : Visitor> visitClassPre(ast: ClassInduct, visitor: V): Result<Unit> {
        when (ast) {
            is ClassInduct.Item -> {
                val (item) = ast
                visitor.visitClassSetItemPre(item).getOrElse { return Result.failure(it) }
            }
            is ClassInduct.BinaryOp -> {
                val (op) = ast
                visitor.visitClassSetBinaryOpPre(op).getOrElse { return Result.failure(it) }
            }
        }
        return Result.success(Unit)
    }

    /**
     * Call the appropriate `Visitor` methods given an inductive step.
     */
    public open fun <Output, Err, V : Visitor> visitClassPost(ast: ClassInduct, visitor: V): Result<Unit> {
        when (ast) {
            is ClassInduct.Item -> {
                val (item) = ast
                visitor.visitClassSetItemPost(item).getOrElse { return Result.failure(it) }
            }
            is ClassInduct.BinaryOp -> {
                val (op) = ast
                visitor.visitClassSetBinaryOpPost(op).getOrElse { return Result.failure(it) }
            }
        }
        return Result.success(Unit)
    }

    /**
     * Build a stack frame for the given class node if one is needed (which
     * occurs if and only if there are child nodes). Otherwise, return None.
     */
    public open fun inductClass(ast: ClassInduct): ClassFrame? =
        when {
            ast is ClassInduct.Item && ast.value is ClassSetItem.Bracketed -> {
                val (x) = ast
                when (val matchVal = x.kind) {
                    is ClassSet.Item -> {
                        val (item) = matchVal
                        ClassFrame.Union(head = item, tail = listOf())
                    }
                    is ClassSet.BinaryOp -> {
                        val (op) = matchVal
                        ClassFrame.Binary(op = op)
                    }
                }
            }
            ast is ClassInduct.Item && ast.value is ClassSetItem.Union -> {
                val (x1) = ast
                if (x1.items.isEmpty()) {
                    null
                } else {
                    ClassFrame.Union(head = x1.items[0], tail = x1.items[1 until x1.items.size])
                }
            }
            ast is ClassInduct.BinaryOp -> {
                val (op1) = ast
                ClassFrame.BinaryLHS(op = op1, lhs = op1.lhs, rhs = op1.rhs)
            }
            else -> null
        }

    /**
     * Pops the given frame. If the frame has an additional inductive step,
     * then return it, otherwise return `None`.
     */
    public open fun popClass(induct: ClassFrame): ClassFrame? =
        when (induct) {
            is ClassFrame.Union -> {
                val tail = induct.tail
                if (tail.isEmpty()) {
                    null
                } else {
                    ClassFrame.Union(head = tail[0], tail = tail[1 until tail.size])
                }
            }
            is ClassFrame.Binary -> null
            is ClassFrame.BinaryLHS -> {
                val op = induct.op
                val rhs = induct.rhs
                ClassFrame.BinaryRHS(op = op, rhs = rhs)
            }
            is ClassFrame.BinaryRHS -> null
        }

    public companion object {
        public fun new(): HeapVisitor = HeapVisitor(stack = mutableListOf(), stackClass = mutableListOf())
    }
}

/**
 * Represents a single stack frame while performing structural induction over
 * an `Ast`.
 */
public sealed class Frame {
    /**
     * A stack frame allocated just before descending into a repetition
     * operator's child node.
     */
    public data class Repetition(public var value: Repetition) : Frame()
    /**
     * A stack frame allocated just before descending into a group's child
     * node.
     */
    public data class Group(public var value: Group) : Frame()
    /**
     * The stack frame used while visiting every child node of a concatenation
     * of expressions.
     */
    public data class Concat(public var head: Ast, public var tail: List<Ast>) : Frame()
    /**
     * The stack frame used while visiting every child node of an alternation
     * of expressions.
     */
    public data class Alternation(public var head: Ast, public var tail: List<Ast>) : Frame()

    /**
     * Perform the next inductive step on this frame and return the next
     * child AST node to visit.
     */
    public open fun child(): Ast =
        when (this) {
            is Frame.Repetition -> {
                val (rep) = this
                rep.ast
            }
            is Frame.Group -> {
                val (group) = this
                group.ast
            }
            is Frame.Concat -> {
                val head = this.head
                head
            }
            is Frame.Alternation -> {
                val head1 = this.head
                head1
            }
        }
}

/**
 * Represents a single stack frame while performing structural induction over
 * a character class.
 */
public sealed class ClassFrame {
    /**
     * The stack frame used while visiting every child node of a union of
     * character class items.
     */
    public data class Union(public var head: ClassSetItem, public var tail: List<ClassSetItem>) : ClassFrame()
    /**
     * The stack frame used while a binary class operation.
     */
    public data class Binary(public var op: ClassSetBinaryOp) : ClassFrame()
    /**
     * A stack frame allocated just before descending into a binary operator's
     * left hand child node.
     */
    public data class BinaryLHS(public var op: ClassSetBinaryOp, public var lhs: ClassSet, public var rhs: ClassSet) : ClassFrame()
    /**
     * A stack frame allocated just before descending into a binary operator's
     * right hand child node.
     */
    public data class BinaryRHS(public var op: ClassSetBinaryOp, public var rhs: ClassSet) : ClassFrame()

    /**
     * Perform the next inductive step on this frame and return the next
     * child class node to visit.
     */
    public open fun child(): ClassInduct =
        when (this) {
            is ClassFrame.Union -> {
                val head = this.head
                ClassInduct.Item(head)
            }
            is ClassFrame.Binary -> {
                val op = this.op
                ClassInduct.BinaryOp(op)
            }
            is ClassFrame.BinaryLHS -> {
                val lhs = this.lhs
                ClassInduct.fromSet(lhs)
            }
            is ClassFrame.BinaryRHS -> {
                val rhs = this.rhs
                ClassInduct.fromSet(rhs)
            }
        }

    public open fun fmt(f: StringBuilder) {
        val x =
            when (this) {
                is ClassFrame.Union -> "Union"
                is ClassFrame.Binary -> "Binary"
                is ClassFrame.BinaryLHS -> "BinaryLHS"
                is ClassFrame.BinaryRHS -> "BinaryRHS"
            }
        f.append("$x")
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
public sealed class ClassInduct {
    public data class Item(public var value: ClassSetItem) : ClassInduct()
    public data class BinaryOp(public var value: ClassSetBinaryOp) : ClassInduct()

    public open fun fmt(f: StringBuilder) {
        val x =
            when (this) {
                is ClassInduct.Item -> {
                    val (it) = this
                    when (it) {
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
                    val (it1) = this
                    when (it1.kind) {
                        ClassSetBinaryOpKind.Intersection -> {
                            "BinaryOp(Intersection)"
                        }
                        ClassSetBinaryOpKind.Difference -> {
                            "BinaryOp(Difference)"
                        }
                        ClassSetBinaryOpKind.SymmetricDifference -> {
                            "BinaryOp(SymmetricDifference)"
                        }
                    }
                }
            }
        f.append("$x")
    }

    public companion object {
        public fun fromBracketed(ast: ClassBracketed): ClassInduct = ClassInduct.fromSet(ast.kind)

        public fun fromSet(ast: ClassSet): ClassInduct =
            when (ast) {
                is ClassSet.Item -> {
                    val (item) = ast
                    ClassInduct.Item(item)
                }
                is ClassSet.BinaryOp -> {
                    val (op) = ast
                    ClassInduct.BinaryOp(op)
                }
            }
    }
}
