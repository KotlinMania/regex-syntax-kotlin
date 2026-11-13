// port-lint: source hir/visitor.rs
package io.github.kotlinmania.regexsyntax.hir.visitor

import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Repetition

/**
 * A trait for visiting the high-level IR (HIR) in depth first order.
 *
 * The principle aim of this trait is to enable callers to perform case
 * analysis on a high-level intermediate representation of a regular
 * expression without necessarily using recursion. In particular, this permits
 * callers to do case analysis with constant stack usage, which can be
 * important since the size of an HIR may be proportional to end user input.
 *
 * Typical usage of this trait involves providing an implementation and then
 * running it using the [visit] function.
 */
public interface Visitor<Output, Err> {
    /**
     * All implementors of `Visitor` must provide a `finish` method, which
     * yields the result of visiting the HIR or an error.
     */
    public fun finish(): Result<Output>

    /**
     * This method is called before beginning traversal of the HIR.
     */
    public fun start() {}

    /**
     * This method is called on an `Hir` before descending into child `Hir`
     * nodes.
     */
    public fun visitPre(hir: Hir): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on an `Hir` after descending all of its child
     * `Hir` nodes.
     */
    public fun visitPost(hir: Hir): Result<Unit> = Result.success(Unit)

    /**
     * This method is called between child nodes of an alternation.
     */
    public fun visitAlternationIn(): Result<Unit> = Result.success(Unit)

    /**
     * This method is called between child nodes of a concatenation.
     */
    public fun visitConcatIn(): Result<Unit> = Result.success(Unit)
}

/**
 * Executes an implementation of `Visitor` in constant stack space.
 *
 * This function will visit every node in the given `Hir` while calling
 * appropriate methods provided by the [Visitor] trait.
 *
 * The primary use case for this method is when one wants to perform case
 * analysis over an `Hir` without using a stack size proportional to the depth
 * of the `Hir`. Namely, this method will instead use constant stack space,
 * but will use heap space proportional to the size of the `Hir`. This may be
 * desirable in cases where the size of `Hir` is proportional to end user
 * input.
 *
 * If the visitor returns an error at any point, then visiting is stopped and
 * the error is returned.
 */
public fun <Output, Err, V : Visitor> visit(hir: Hir, visitor: V): Result<Output> = HeapVisitor.new().visit(hir, visitor)

/**
 * HeapVisitor visits every item in an `Hir` recursively using constant stack
 * size and a heap size proportional to the size of the `Hir`.
 */
public open class HeapVisitor(
    /**
     * A stack of `Hir` nodes. This is roughly analogous to the call stack
     * used in a typical recursive visitor.
     */
    public var stack: MutableList<Pair<Hir, Frame>>,
) {
    public open fun <Output, Err, V : Visitor> visit(hir: Hir, visitor: V): Result<Output> {
        var hir1 = hir
        this.stack.clear()
        visitor.start()
        while (true) {
            visitor.visitPre(hir1).getOrElse { return Result.failure(it) }
            when (val matchVal = this.induct(hir1)) {
                null -> {}
                else -> {
                    val x = matchVal
                    val child = x.child()
                    this.stack.add(Pair(hir1, x))
                    hir1 = child
                    continue
                }
            }
            // No induction means we have a base case, so we can post visit
            // it now.
            visitor.visitPost(hir1).getOrElse { return Result.failure(it) }
            // At this point, we now try to pop our call stack until it is
            // either empty or we hit another inductive case.
            while (true) {
                val (postHir1, frame1) =
                    when (val matchVal1 = this.stack.removeLastOrNull()) {
                        null -> { return visitor.finish() }
                        else -> {
                            val postHir = matchVal1.first
                            val frame = matchVal1.second
                            Pair(postHir, frame)
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
                        hir1 = x1.child()
                        this.stack.add(Pair(postHir1, x1))
                        break
                    }
                }
                // Otherwise, we've finished visiting all the child nodes for
                // this HIR, so we can post visit it now.
                visitor.visitPost(postHir1).getOrElse { return Result.failure(it) }
            }
        }
    }

    /**
     * Build a stack frame for the given HIR if one is needed (which occurs if
     * and only if there are child nodes in the HIR). Otherwise, return None.
     */
    public open fun induct(hir: Hir): Frame? =
        run {
            val matchVal = hir.kind()
            when {
                matchVal is HirKind.Repetition -> {
                    val (x) = matchVal
                    Frame.Repetition(x)
                }
                matchVal is HirKind.Capture -> {
                    val (x1) = matchVal
                    Frame.Capture(x1)
                }
                matchVal is HirKind.Concat && (matchVal as HirKind.Concat).value.isEmpty() -> {
                    val (x2) = matchVal
                    null
                }
                matchVal is HirKind.Concat -> {
                    val (x3) = matchVal
                    Frame.Concat(head = x3[0], tail = x3[1 until x3.size])
                }
                matchVal is HirKind.Alternation && (matchVal as HirKind.Alternation).value.isEmpty() -> {
                    val (x4) = matchVal
                    null
                }
                matchVal is HirKind.Alternation -> {
                    val (x5) = matchVal
                    Frame.Alternation(head = x5[0], tail = x5[1 until x5.size])
                }
                else -> null
            }
        }

    /**
     * Pops the given frame. If the frame has an additional inductive step,
     * then return it, otherwise return `None`.
     */
    public open fun pop(induct: Frame): Frame? =
        when (induct) {
            is Frame.Repetition -> null
            is Frame.Capture -> null
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

    public companion object {
        public fun new(): HeapVisitor = HeapVisitor(stack = mutableListOf())
    }
}

/**
 * Represents a single stack frame while performing structural induction over
 * an `Hir`.
 */
public sealed class Frame {
    /**
     * A stack frame allocated just before descending into a repetition
     * operator's child node.
     */
    public data class Repetition(public var value: Repetition) : Frame()
    /**
     * A stack frame allocated just before descending into a capture's child
     * node.
     */
    public data class Capture(public var value: Capture) : Frame()
    /**
     * The stack frame used while visiting every child node of a concatenation
     * of expressions.
     */
    public data class Concat(public var head: Hir, public var tail: List<Hir>) : Frame()
    /**
     * The stack frame used while visiting every child node of an alternation
     * of expressions.
     */
    public data class Alternation(public var head: Hir, public var tail: List<Hir>) : Frame()

    /**
     * Perform the next inductive step on this frame and return the next
     * child HIR node to visit.
     */
    public open fun child(): Hir =
        when (this) {
            is Frame.Repetition -> {
                val (rep) = this
                rep.sub
            }
            is Frame.Capture -> {
                val (capture) = this
                capture.sub
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
