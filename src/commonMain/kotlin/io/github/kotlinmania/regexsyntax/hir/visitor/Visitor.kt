// port-lint: source src/hir/visitor.rs
package io.github.kotlinmania.regexsyntax.hir.visitor

import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Repetition

/**
 * An interface for visiting the high-level IR (HIR) in depth first order.
 *
 * The principle aim of this interface is to enable callers to perform case
 * analysis on a high-level intermediate representation of a regular
 * expression without necessarily using recursion. In particular, this permits
 * callers to do case analysis with constant stack usage, which can be
 * important since the size of an HIR may be proportional to end user input.
 *
 * Typical usage of this interface involves providing an implementation and then
 * running it using the [visit] function.
 */
interface Visitor<Output, Err> {
    /**
     * All implementors of [Visitor] must provide a [finish] method, which
     * yields the result of visiting the HIR or an error.
     */
    fun finish(): Result<Output>

    /** This method is called before beginning traversal of the HIR. */
    fun start() {}

    /**
     * This method is called on an [Hir] before descending into child [Hir]
     * nodes.
     */
    fun visitPre(hir: Hir): Result<Unit> = Result.success(Unit)

    /**
     * This method is called on an [Hir] after descending all of its child
     * [Hir] nodes.
     */
    fun visitPost(hir: Hir): Result<Unit> = Result.success(Unit)

    /** This method is called between child nodes of an alternation. */
    fun visitAlternationIn(): Result<Unit> = Result.success(Unit)

    /** This method is called between child nodes of a concatenation. */
    fun visitConcatIn(): Result<Unit> = Result.success(Unit)
}

/**
 * Executes an implementation of [Visitor] in constant stack space.
 *
 * This function will visit every node in the given [Hir] while calling
 * appropriate methods provided by the [Visitor] interface.
 *
 * The primary use case for this method is when one wants to perform case
 * analysis over an [Hir] without using a stack size proportional to the depth
 * of the [Hir]. Namely, this method will instead use constant stack space,
 * but will use heap space proportional to the size of the [Hir]. This may be
 * desirable in cases where the size of [Hir] is proportional to end user
 * input.
 *
 * If the visitor returns an error at any point, then visiting is stopped and
 * the error is returned.
 */
fun <Output, Err> visit(hir: Hir, visitor: Visitor<Output, Err>): Result<Output> {
    return HeapVisitor.new().visit(hir, visitor)
}

/**
 * [HeapVisitor] visits every item in an [Hir] recursively using constant stack
 * size and a heap size proportional to the size of the [Hir].
 */
private class HeapVisitor {
    /**
     * A stack of [Hir] nodes. This is roughly analogous to the call stack
     * used in a typical recursive visitor.
     */
    private val stack: MutableList<Pair<Hir, Frame>> = mutableListOf()

    companion object {
        fun new(): HeapVisitor = HeapVisitor()
    }

    fun <Output, Err> visit(start: Hir, visitor: Visitor<Output, Err>): Result<Output> {
        stack.clear()
        var hir = start

        visitor.start()
        while (true) {
            val pre = visitor.visitPre(hir)
            if (pre.isFailure) return Result.failure(pre.exceptionOrNull()!!)
            val x = induct(hir)
            if (x != null) {
                val child = x.child()
                stack.add(Pair(hir, x))
                hir = child
                continue
            }
            // No induction means we have a base case, so we can post visit
            // it now.
            val post = visitor.visitPost(hir)
            if (post.isFailure) return Result.failure(post.exceptionOrNull()!!)

            // At this point, we now try to pop our call stack until it is
            // either empty or we hit another inductive case.
            innerLoop@ while (true) {
                if (stack.isEmpty()) return visitor.finish()
                val (postHir, frame) = stack.removeAt(stack.lastIndex)
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
                    hir = next.child()
                    stack.add(Pair(postHir, next))
                    break@innerLoop
                }
                // Otherwise, we've finished visiting all the child nodes for
                // this HIR, so we can post visit it now.
                val pp = visitor.visitPost(postHir)
                if (pp.isFailure) return Result.failure(pp.exceptionOrNull()!!)
            }
        }
    }

    /**
     * Build a stack frame for the given HIR if one is needed (which occurs if
     * and only if there are child nodes in the HIR). Otherwise, return null.
     */
    private fun induct(hir: Hir): Frame? {
        return when (val k = hir.kind()) {
            is HirKind.Repetition -> Frame.Repetition(k.value)
            is HirKind.Capture -> Frame.Capture(k.value)
            is HirKind.Concat -> if (k.items.isEmpty()) null else
                Frame.Concat(head = k.items[0], tail = k.items.subList(1, k.items.size))
            is HirKind.Alternation -> if (k.items.isEmpty()) null else
                Frame.Alternation(head = k.items[0], tail = k.items.subList(1, k.items.size))
            else -> null
        }
    }

    /**
     * Pops the given frame. If the frame has an additional inductive step,
     * then return it, otherwise return null.
     */
    private fun pop(frame: Frame): Frame? {
        return when (frame) {
            is Frame.Repetition -> null
            is Frame.Capture -> null
            is Frame.Concat -> if (frame.tail.isEmpty()) null else
                Frame.Concat(head = frame.tail[0], tail = frame.tail.subList(1, frame.tail.size))
            is Frame.Alternation -> if (frame.tail.isEmpty()) null else
                Frame.Alternation(head = frame.tail[0], tail = frame.tail.subList(1, frame.tail.size))
        }
    }
}

/**
 * Represents a single stack frame while performing structural induction over
 * an [Hir].
 */
private sealed class Frame {
    /**
     * A stack frame allocated just before descending into a repetition
     * operator's child node.
     */
    class Repetition(val rep: io.github.kotlinmania.regexsyntax.hir.Repetition) : Frame()

    /**
     * A stack frame allocated just before descending into a capture's child
     * node.
     */
    class Capture(val capture: io.github.kotlinmania.regexsyntax.hir.Capture) : Frame()

    /**
     * The stack frame used while visiting every child node of a concatenation
     * of expressions.
     */
    class Concat(
        /** The child node we are currently visiting. */
        val head: Hir,
        /** The remaining child nodes to visit (which may be empty). */
        val tail: List<Hir>,
    ) : Frame()

    /**
     * The stack frame used while visiting every child node of an alternation
     * of expressions.
     */
    class Alternation(
        /** The child node we are currently visiting. */
        val head: Hir,
        /** The remaining child nodes to visit (which may be empty). */
        val tail: List<Hir>,
    ) : Frame()

    /**
     * Perform the next inductive step on this frame and return the next
     * child HIR node to visit.
     */
    fun child(): Hir = when (this) {
        is Repetition -> rep.sub
        is Capture -> capture.sub
        is Concat -> head
        is Alternation -> head
    }
}
