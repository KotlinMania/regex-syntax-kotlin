// port-lint: source hir/interval.rs
package io.github.kotlinmania.regexsyntax.hir.interval

import io.github.kotlinmania.regexsyntax.scalarFromU32
import io.github.kotlinmania.regexsyntax.unicode.CaseFoldError
import io.github.kotlinmania.rustlang.core.result.Result

// This module contains an internal implementation of interval sets.
//
// The primary invariant that interval sets guards is canonical ordering. That
// is, every interval set contains an ordered sequence of intervals where
// no two intervals are overlapping or adjacent. While this invariant is
// occasionally broken within the implementation, it should be impossible for
// callers to observe it.
//
// Since case folding (as implemented below) breaks that invariant, we roll
// that into this API even though it is a little out of place in an otherwise
// generic interval set.

internal class IntervalSet<I : Comparable<I>, B : Comparable<B>>(
    /**
     * A sorted set of non-overlapping ranges.
     */
    internal var ranges: MutableList<I>,
    /**
     * Tracks whether an interval set has been case folded or not.
     */
    internal var folded: Boolean,
    internal val ops: Interval<I, B>,
) {
    // Do not consider internal `folded` optimization state in equality.
    override fun equals(other: Any?): Boolean =
        this === other || (other is IntervalSet<*, *> && this.ranges == other.ranges)

    override fun hashCode(): Int = this.ranges.hashCode()

    internal fun copySet(): IntervalSet<I, B> =
        IntervalSet(
            ranges = this.ranges.mapTo(ArrayList(this.ranges.size)) { this.ops.copyInterval(it) },
            folded = this.folded,
            ops = this.ops,
        )

    /**
     * Add a new interval to this set.
     */
    internal fun push(interval: I) {
        this.ranges.add(interval)
        this.canonicalize()
        this.folded = false
    }

    /**
     * Return an iterator over all intervals in this set.
     */
    internal fun iter(): IntervalSetIter<I> = IntervalSetIter(this.ranges.toList(), 0)

    /**
     * Return an immutable slice of intervals in this set.
     */
    internal fun intervals(): List<I> = this.ranges

    /**
     * Expand this interval set such that it contains all case folded
     * characters.
     */
    internal fun caseFoldSimple(): Result<Unit, CaseFoldError> {
        if (this.folded) {
            return Result.Ok(Unit)
        }
        val len = this.ranges.size
        for (i in 0 until len) {
            val range = this.ranges[i]
            when (val res = this.ops.caseFoldSimple(range, this.ranges)) {
                is Result.Err -> {
                    this.canonicalize()
                    return Result.Err(res.error)
                }
                is Result.Ok -> {}
            }
        }
        this.canonicalize()
        this.folded = true
        return Result.Ok(Unit)
    }

    /**
     * Union this set with the given set, in place.
     */
    internal fun union(other: IntervalSet<I, B>) {
        if (other.ranges.isEmpty() || this.ranges == other.ranges) {
            return
        }
        this.ranges.addAll(other.ranges)
        this.canonicalize()
        this.folded = this.folded && other.folded
    }

    /**
     * Intersect this set with the given set, in place.
     */
    internal fun intersect(other: IntervalSet<I, B>) {
        if (this.ranges.isEmpty()) {
            return
        }
        if (other.ranges.isEmpty()) {
            this.ranges.clear()
            this.folded = true
            return
        }
        val drainEnd = this.ranges.size
        var a = 0
        var b = 0
        while (true) {
            val ab = this.ops.intersect(this.ranges[a], other.ranges[b])
            if (ab != null) {
                this.ranges.add(ab)
            }
            if (this.ops.upper(this.ranges[a]) < this.ops.upper(other.ranges[b])) {
                a += 1
                if (a >= drainEnd) {
                    break
                }
            } else {
                b += 1
                if (b >= other.ranges.size) {
                    break
                }
            }
        }
        this.ranges.subList(0, drainEnd).clear()
        this.folded = this.folded && other.folded
    }

    /**
     * Subtract the given set from this set, in place.
     */
    internal fun difference(other: IntervalSet<I, B>) {
        if (this.ranges.isEmpty() || other.ranges.isEmpty()) {
            return
        }
        val drainEnd = this.ranges.size
        var a = 0
        var b = 0
        outer@ while (a < drainEnd && b < other.ranges.size) {
            if (this.ops.upper(other.ranges[b]) < this.ops.lower(this.ranges[a])) {
                b += 1
                continue
            }
            if (this.ops.upper(this.ranges[a]) < this.ops.lower(other.ranges[b])) {
                this.ranges.add(this.ranges[a])
                a += 1
                continue
            }
            check(!this.ops.isIntersectionEmpty(this.ranges[a], other.ranges[b]))
            var range = this.ranges[a]
            while (b < other.ranges.size && !this.ops.isIntersectionEmpty(range, other.ranges[b])) {
                val oldRange = range
                val (r1, r2) = this.ops.difference(range, other.ranges[b])
                range =
                    when {
                        r1 == null && r2 == null -> {
                            a += 1
                            continue@outer
                        }
                        r1 != null && r2 == null -> r1
                        r1 == null && r2 != null -> r2
                        else -> {
                            this.ranges.add(r1!!)
                            r2!!
                        }
                    }
                if (this.ops.upper(other.ranges[b]) > this.ops.upper(oldRange)) {
                    break
                }
                b += 1
            }
            this.ranges.add(range)
            a += 1
        }
        while (a < drainEnd) {
            this.ranges.add(this.ranges[a])
            a += 1
        }
        this.ranges.subList(0, drainEnd).clear()
        this.folded = this.folded && other.folded
    }

    /**
     * Compute the symmetric difference of the two sets, in place.
     */
    internal fun symmetricDifference(other: IntervalSet<I, B>) {
        val intersection = this.copySet()
        intersection.intersect(other)
        this.union(other)
        this.difference(intersection)
    }

    /**
     * Negate this interval set.
     */
    internal fun negate() {
        val bound = this.ops.boundOps()
        if (this.ranges.isEmpty()) {
            val min = bound.minValue()
            val max = bound.maxValue()
            this.ranges.add(this.ops.create(min, max))
            this.folded = true
            return
        }
        val drainEnd = this.ranges.size
        if (this.ops.lower(this.ranges[0]) > bound.minValue()) {
            val upper = bound.decrement(this.ops.lower(this.ranges[0]))
            this.ranges.add(this.ops.create(bound.minValue(), upper))
        }
        for (i in 1 until drainEnd) {
            val lower = bound.increment(this.ops.upper(this.ranges[i - 1]))
            val upper = bound.decrement(this.ops.lower(this.ranges[i]))
            this.ranges.add(this.ops.create(lower, upper))
        }
        if (this.ops.upper(this.ranges[drainEnd - 1]) < bound.maxValue()) {
            val lower = bound.increment(this.ops.upper(this.ranges[drainEnd - 1]))
            this.ranges.add(this.ops.create(lower, bound.maxValue()))
        }
        this.ranges.subList(0, drainEnd).clear()
    }

    /**
     * Converts this set into a canonical ordering.
     */
    internal fun canonicalize() {
        if (this.isCanonical()) {
            return
        }
        this.ranges.sort()
        check(this.ranges.isNotEmpty())
        val drainEnd = this.ranges.size
        for (oldi in 0 until drainEnd) {
            if (this.ranges.size > drainEnd) {
                val lastIdx = this.ranges.size - 1
                val merged = this.ops.union(this.ranges[lastIdx], this.ranges[oldi])
                if (merged != null) {
                    this.ranges[lastIdx] = merged
                    continue
                }
            }
            val range = this.ranges[oldi]
            this.ranges.add(range)
        }
        this.ranges.subList(0, drainEnd).clear()
    }

    /**
     * Returns true if and only if this class is in a canonical ordering.
     */
    internal fun isCanonical(): Boolean {
        for (i in 0 until this.ranges.size - 1) {
            val a = this.ranges[i]
            val b = this.ranges[i + 1]
            if (a >= b) {
                return false
            }
            if (this.ops.isContiguous(a, b)) {
                return false
            }
        }
        return true
    }

    internal companion object {
        internal fun <I : Comparable<I>, B : Comparable<B>> new(
            intervals: Iterable<I>,
            ops: Interval<I, B>,
        ): IntervalSet<I, B> {
            val ranges = intervals.toMutableList()
            val folded = ranges.isEmpty()
            val set = IntervalSet(ranges = ranges, folded = folded, ops = ops)
            set.canonicalize()
            return set
        }
    }
}

/**
 * An iterator over intervals.
 */
internal class IntervalSetIter<I>(
    internal val items: List<I>,
    internal var index: Int = 0,
) : Iterable<I> {
    internal constructor(iter: Iterator<I>) : this(iter.asSequence().toList(), 0)
    override fun iterator(): Iterator<I> =
        object : AbstractIterator<I>() {
            override fun computeNext() {
                if (this@IntervalSetIter.index < this@IntervalSetIter.items.size) {
                    setNext(this@IntervalSetIter.items[this@IntervalSetIter.index++])
                } else {
                    done()
                }
            }
        }

    internal fun next(): I? =
        if (this.index < this.items.size) {
            this.items[this.index++]
        } else {
            null
        }
}

internal interface Interval<T : Comparable<T>, B : Comparable<B>> {
    fun boundOps(): Bound<B>

    fun lower(range: T): B

    fun upper(range: T): B

    fun setLower(range: T, bound: B)

    fun setUpper(range: T, bound: B)

    fun copyInterval(range: T): T

    fun defaultInterval(): T

    fun caseFoldSimple(range: T, intervals: MutableList<T>): Result<Unit, CaseFoldError>

    /**
     * Create a new interval.
     */
    fun create(lower: B, upper: B): T {
        val int = defaultInterval()
        if (lower <= upper) {
            setLower(int, lower)
            setUpper(int, upper)
        } else {
            setLower(int, upper)
            setUpper(int, lower)
        }
        return int
    }

    /**
     * Union the given overlapping range into this range.
     *
     * If the two ranges are not contiguous, then this returns `null`.
     */
    fun union(range: T, other: T): T? {
        if (!isContiguous(range, other)) {
            return null
        }
        val lo = minOf(lower(range), lower(other))
        val hi = maxOf(upper(range), upper(other))
        return create(lo, hi)
    }

    /**
     * Intersect this range with the given range and return the result.
     *
     * If the intersection is empty, then this returns `null`.
     */
    fun intersect(range: T, other: T): T? {
        val lo = maxOf(lower(range), lower(other))
        val hi = minOf(upper(range), upper(other))
        return if (lo <= hi) {
            create(lo, hi)
        } else {
            null
        }
    }

    /**
     * Subtract the given range from this range and return the resulting
     * ranges.
     */
    fun difference(range: T, other: T): Pair<T?, T?> {
        if (isSubset(range, other)) {
            return Pair(null, null)
        }
        if (isIntersectionEmpty(range, other)) {
            return Pair(copyInterval(range), null)
        }
        val ops = boundOps()
        val addLower = lower(other) > lower(range)
        val addUpper = upper(other) < upper(range)
        check(addLower || addUpper)
        var first: T? = null
        var second: T? = null
        if (addLower) {
            val upper = ops.decrement(lower(other))
            first = create(lower(range), upper)
        }
        if (addUpper) {
            val lower = ops.increment(upper(other))
            val nextRange = create(lower, upper(range))
            if (first == null) {
                first = nextRange
            } else {
                second = nextRange
            }
        }
        return Pair(first, second)
    }

    /**
     * Returns true if and only if the two ranges are contiguous. Two ranges
     * are contiguous if and only if the ranges are either overlapping or
     * adjacent.
     */
    fun isContiguous(range: T, other: T): Boolean {
        val ops = boundOps()
        val lower1 = ops.asU32(lower(range))
        val upper1 = ops.asU32(upper(range))
        val lower2 = ops.asU32(lower(other))
        val upper2 = ops.asU32(upper(other))
        val minUpper = minOf(upper1, upper2)
        val plusOne = if (minUpper == UInt.MAX_VALUE) minUpper else minUpper + 1u
        return maxOf(lower1, lower2) <= plusOne
    }

    /**
     * Returns true if and only if the intersection of this range and the
     * other range is empty.
     */
    fun isIntersectionEmpty(range: T, other: T): Boolean {
        val lower1 = lower(range)
        val upper1 = upper(range)
        val lower2 = lower(other)
        val upper2 = upper(other)
        return maxOf(lower1, lower2) > minOf(upper1, upper2)
    }

    /**
     * Returns true if and only if this range is a subset of the other range.
     */
    fun isSubset(range: T, other: T): Boolean {
        val lower1 = lower(range)
        val upper1 = upper(range)
        val lower2 = lower(other)
        val upper2 = upper(other)
        return (lower2 <= lower1 && lower1 <= upper2) && (lower2 <= upper1 && upper1 <= upper2)
    }
}

internal interface Bound<T : Comparable<T>> {
    fun minValue(): T

    fun maxValue(): T

    fun asU32(value: T): UInt

    fun increment(value: T): T

    fun decrement(value: T): T
}

internal object UByteBound : Bound<UByte> {
    override fun minValue(): UByte = UByte.MIN_VALUE

    override fun maxValue(): UByte = UByte.MAX_VALUE

    override fun asU32(value: UByte): UInt = value.toUInt()

    override fun increment(value: UByte): UByte {
        check(value < UByte.MAX_VALUE) { "byte bound overflow" }
        return (value.toInt() + 1).toUByte()
    }

    override fun decrement(value: UByte): UByte {
        check(value > UByte.MIN_VALUE) { "byte bound underflow" }
        return (value.toInt() - 1).toUByte()
    }
}

internal object ScalarBound : Bound<UInt> {
    override fun minValue(): UInt = 0x00u

    override fun maxValue(): UInt = 0x10FFFFu

    override fun asU32(value: UInt): UInt = value

    override fun increment(value: UInt): UInt =
        when (value) {
            0xD7FFu -> 0xE000u
            else -> {
                check(value < 0x10FFFFu) { "scalar bound overflow" }
                scalarFromU32(value + 1u) ?: error("invalid scalar increment")
            }
        }

    override fun decrement(value: UInt): UInt =
        when (value) {
            0xE000u -> 0xD7FFu
            else -> {
                check(value > 0u) { "scalar bound underflow" }
                scalarFromU32(value - 1u) ?: error("invalid scalar decrement")
            }
        }
}
