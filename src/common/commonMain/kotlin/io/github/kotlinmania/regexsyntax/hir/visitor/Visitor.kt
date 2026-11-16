// port-lint: source hir/visitor.rs
package io.github.kotlinmania.regexsyntax.hir.visitor

import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.rustlang.core.result.Result

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
    public fun finish(): Result<Output, Err>

    /**
     * This method is called before beginning traversal of the HIR.
     */
    public fun start() {}

    /**
     * This method is called on an `Hir` before descending into child `Hir`
     * nodes.
     */
    public fun visitPre(hir: Hir): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called on an `Hir` after descending all of its child
     * `Hir` nodes.
     */
    public fun visitPost(hir: Hir): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called between child nodes of an alternation.
     */
    public fun visitAlternationIn(): Result<Unit, Err> = Result.Ok(Unit)

    /**
     * This method is called between child nodes of a concatenation.
     */
    public fun visitConcatIn(): Result<Unit, Err> = Result.Ok(Unit)
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
public fun <Output, Err, V : Visitor<Output, Err>> visit(hir: Hir, visitor: V): Result<Output, Err> =
    HeapVisitor.new().visit(hir, visitor)

/**
 * HeapVisitor visits every item in an `Hir` recursively using constant stack
 * size and a heap size proportional to the size of the `Hir`.
 */
internal class HeapVisitor(
    /**
     * A stack of `Hir` nodes. This is roughly analogous to the call stack
     * used in a typical recursive visitor.
     */
    internal var stack: MutableList<Pair<Hir, Frame>>,
) {
    internal fun <Output, Err, V : Visitor<Output, Err>> visit(hir: Hir, visitor: V): Result<Output, Err> {
        var hir1 = hir
        this.stack.clear()
        visitor.start()
        while (true) {
            when (val r = visitor.visitPre(hir1)) {
                is Result.Err -> return r
                is Result.Ok -> {}
            }
            val x = this.induct(hir1)
            if (x != null) {
                val child = x.child()
                this.stack.add(Pair(hir1, x))
                hir1 = child
                continue
            }
            // No induction means we have a base case, so we can post visit
            // it now.
            when (val r = visitor.visitPost(hir1)) {
                is Result.Err -> return r
                is Result.Ok -> {}
            }
            // At this point, we now try to pop our call stack until it is
            // either empty or we hit another inductive case.
            while (true) {
                val entry = this.stack.removeLastOrNull() ?: return visitor.finish()
                val postHir = entry.first
                val frame = entry.second
                // If this is a concat/alternate, then we might have additional
                // inductive steps to process.
                val nextFrame = this.pop(frame)
                if (nextFrame != null) {
                    when (nextFrame) {
                        is Frame.Alternation -> {
                            when (val r = visitor.visitAlternationIn()) {
                                is Result.Err -> return r
                                is Result.Ok -> {}
                            }
                        }
                        is Frame.Concat -> {
                            when (val r = visitor.visitConcatIn()) {
                                is Result.Err -> return r
                                is Result.Ok -> {}
                            }
                        }
                        else -> {}
                    }
                    hir1 = nextFrame.child()
                    this.stack.add(Pair(postHir, nextFrame))
                    break
                }
                // Otherwise, we've finished visiting all the child nodes for
                // this HIR, so we can post visit it now.
                when (val r = visitor.visitPost(postHir)) {
                    is Result.Err -> return r
                    is Result.Ok -> {}
                }
            }
        }
    }

    /**
     * Build a stack frame for the given HIR if one is needed (which occurs if
     * and only if there are child nodes in the HIR). Otherwise, return null.
     */
    internal fun induct(hir: Hir): Frame? =
        when (val kind = hir.kind()) {
            is HirKind.Repetition -> Frame.Repetition(kind.value)
            is HirKind.Capture -> Frame.Capture(kind.value)
            is HirKind.Concat -> {
                val subs = kind.value
                if (subs.isEmpty()) {
                    null
                } else {
                    Frame.Concat(head = subs[0], tail = subs.subList(1, subs.size))
                }
            }
            is HirKind.Alternation -> {
                val subs = kind.value
                if (subs.isEmpty()) {
                    null
                } else {
                    Frame.Alternation(head = subs[0], tail = subs.subList(1, subs.size))
                }
            }
            else -> null
        }

    /**
     * Pops the given frame. If the frame has an additional inductive step,
     * then return it, otherwise return `null`.
     */
    internal fun pop(induct: Frame): Frame? =
        when (induct) {
            is Frame.Repetition -> null
            is Frame.Capture -> null
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

    internal companion object {
        internal fun new(): HeapVisitor = HeapVisitor(stack = mutableListOf())
    }
}

/**
 * Represents a single stack frame while performing structural induction over
 * an `Hir`.
 */
internal sealed class Frame {
    /**
     * A stack frame allocated just before descending into a repetition
     * operator's child node.
     */
    internal data class Repetition(
        internal var value: io.github.kotlinmania.regexsyntax.hir.Repetition,
    ) : Frame()

    /**
     * A stack frame allocated just before descending into a capture's child
     * node.
     */
    internal data class Capture(
        internal var value: io.github.kotlinmania.regexsyntax.hir.Capture,
    ) : Frame()

    /**
     * The stack frame used while visiting every child node of a concatenation
     * of expressions.
     */
    internal data class Concat(
        internal var head: Hir,
        internal var tail: List<Hir>,
    ) : Frame()

    /**
     * The stack frame used while visiting every child node of an alternation
     * of expressions.
     */
    internal data class Alternation(
        internal var head: Hir,
        internal var tail: List<Hir>,
    ) : Frame()

    /**
     * Perform the next inductive step on this frame and return the next
     * child HIR node to visit.
     */
    internal fun child(): Hir =
        when (this) {
            is Repetition -> this.value.sub
            is Capture -> this.value.sub
            is Concat -> this.head
            is Alternation -> this.head
        }
}
