// port-lint: source src/hir/interval.rs
package io.github.kotlinmania.regexsyntax.hir.interval

/*
 * Copyright (c) The rust-lang regex contributors.
 * Licensed under either of Apache-2.0 OR MIT.
 */

// This module contains an *internal* implementation of interval sets.
//
// The primary invariant that interval sets guards is canonical ordering. That
// is, every interval set contains an ordered sequence of intervals where
// no two intervals are overlapping or adjacent. While this invariant is
// occasionally broken within the implementation, it should be impossible for
// callers to observe it.
//
// Since case folding (as implemented below) breaks that invariant, we roll
// that into this API even though it is a little out of place in an otherwise
// generic interval set. (Hence the reason why the `unicode` module is imported
// here.)
//
// Some of the implementation complexity here is a result of me wanting to
// preserve the sequential representation without using additional memory.
// In many cases, we do use linear extra memory, but it is at most 2x and it
// is amortized. If we relaxed the memory requirements, this implementation
// could become much simpler. The extra memory is honestly probably OK, but
// character classes (especially of the Unicode variety) can become quite
// large, and it would be nice to keep regex compilation snappy even in debug
// builds. (In the past, I have been careless with this area of code and it has
// caused slow regex compilations in debug mode, so this isn't entirely
// unwarranted.)
//
// Tests on this are relegated to the public API of HIR in src/hir.rs.

/**
 * An interval set, parameterized over the interval type [I] and its bound
 * type [B].
 *
 * Construction takes an [IntervalFactory] because some operations
 * ([negate] in particular) need to fabricate intervals out of the bound
 * type's min/max values without an existing interval to dispatch through.
 *
 * The upstream source associates the bound type to [Interval] itself; Kotlin
 * has no associated types, so the factory carries the bound-side helpers
 * ([IntervalFactory.minBound], [IntervalFactory.maxBound],
 * [IntervalFactory.increment], [IntervalFactory.decrement],
 * [IntervalFactory.boundAsInt]) that [Bound] exposed as bound-type operations.
 */
class IntervalSet<I : Interval<I, B>, B> internal constructor(
    private val factory: IntervalFactory<I, B>,
    /** A sorted set of non-overlapping ranges. */
    private val ranges: MutableList<I>,
    /**
     * While not required at all for correctness, we keep track of whether an
     * interval set has been case folded or not. This helps us avoid doing
     * redundant work if, for example, a set has already been cased folded.
     * And note that whether a set is folded or not is preserved through
     * all of the pairwise set operations. That is, if both interval sets
     * have been case folded, then any of difference, union, intersection or
     * symmetric difference all produce a case folded set.
     *
     * Note that when this is true, it *must* be the case that the set is case
     * folded. But when it's false, the set *may* be case folded. In other
     * words, we only set this to true when we know it to be case, but we're
     * okay with it being false if it would otherwise be costly to determine
     * whether it should be true. This means code cannot assume that a false
     * value necessarily indicates that the set is not case folded.
     *
     * Bottom line: this is a performance optimization.
     */
    private var folded: Boolean,
) {
    companion object {
        /**
         * Create a new set from a sequence of intervals. Each interval is
         * specified as a pair of bounds, where both bounds are inclusive.
         *
         * The given ranges do not need to be in any specific order, and ranges
         * may overlap.
         */
        fun <I : Interval<I, B>, B> new(
            factory: IntervalFactory<I, B>,
            intervals: Iterable<I>,
        ): IntervalSet<I, B> {
            val ranges: MutableList<I> = intervals.toMutableList()
            // An empty set is case folded.
            val folded = ranges.isEmpty()
            val set = IntervalSet(factory, ranges, folded)
            set.canonicalize()
            return set
        }
    }

    /** Add a new interval to this set. */
    fun push(interval: I) {
        // Future work: This could be faster. e.g., Push the interval such that
        // it preserves canonicalization.
        ranges.add(interval)
        canonicalize()
        // We don't know whether the new interval added here is considered
        // case folded, so we conservatively assume that the entire set is
        // no longer case folded if it was previously.
        folded = false
    }

    /**
     * Return an iterator over all intervals in this set.
     *
     * The iterator yields intervals in ascending order.
     */
    fun iter(): IntervalSetIter<I> = IntervalSetIter(ranges.iterator())

    /**
     * Return an immutable list of intervals in this set.
     *
     * The sequence returned is in canonical ordering.
     */
    fun intervals(): List<I> = ranges

    /**
     * Expand this interval set such that it contains all case folded
     * characters. For example, if this class consists of the range `a-z`,
     * then applying case folding will result in the class containing both the
     * ranges `a-z` and `A-Z`.
     *
     * This returns an error if the necessary case mapping data is not
     * available.
     */
    fun caseFoldSimple(): Result<Unit> {
        if (folded) {
            return Result.success(Unit)
        }
        val len = ranges.size
        for (i in 0 until len) {
            val range = ranges[i]
            val r = range.caseFoldSimple(ranges)
            if (r.isFailure) {
                canonicalize()
                return r
            }
        }
        canonicalize()
        folded = true
        return Result.success(Unit)
    }

    /** Union this set with the given set, in place. */
    fun union(other: IntervalSet<I, B>) {
        if (other.ranges.isEmpty() || ranges == other.ranges) {
            return
        }
        // This could almost certainly be done more efficiently.
        ranges.addAll(other.ranges)
        canonicalize()
        folded = folded && other.folded
    }

    /** Intersect this set with the given set, in place. */
    fun intersect(other: IntervalSet<I, B>) {
        if (ranges.isEmpty()) {
            return
        }
        if (other.ranges.isEmpty()) {
            ranges.clear()
            // An empty set is case folded.
            folded = true
            return
        }

        // There should be a way to do this in-place with constant memory,
        // but I couldn't figure out a simple way to do it. So just append
        // the intersection to the end of this range, and then drain it before
        // we're done.
        val drainEnd = ranges.size

        var a = 0
        var b = 0
        val aEnd = drainEnd
        val bEnd = other.ranges.size
        while (true) {
            val ab = ranges[a].intersect(other.ranges[b])
            if (ab != null) {
                ranges.add(ab)
            }
            if (factory.cmp(ranges[a].upper(), other.ranges[b].upper()) < 0) {
                a += 1
                if (a >= aEnd) break
            } else {
                b += 1
                if (b >= bEnd) break
            }
        }
        repeat(drainEnd) { ranges.removeAt(0) }
        folded = folded && other.folded
    }

    /** Subtract the given set from this set, in place. */
    fun difference(other: IntervalSet<I, B>) {
        if (ranges.isEmpty() || other.ranges.isEmpty()) {
            return
        }

        // This algorithm is (to me) surprisingly complex. A search of the
        // interwebs indicate that this is a potentially interesting problem.
        // Folks seem to suggest interval or segment trees, but I'd like to
        // avoid the overhead (both runtime and conceptual) of that.
        //
        // The following is basically my Shitty First Draft. Therefore, in
        // order to grok it, you probably need to read each line carefully.
        // Simplifications are most welcome!
        //
        // Remember, we can assume the canonical format invariant here, which
        // says that all ranges are sorted, not overlapping and not adjacent in
        // each class.
        val drainEnd = ranges.size
        var a = 0
        var b = 0
        loopA@ while (a < drainEnd && b < other.ranges.size) {
            // Basically, the easy cases are when neither range overlaps with
            // each other. If the `b` range is less than our current `a`
            // range, then we can skip it and move on.
            if (factory.cmp(other.ranges[b].upper(), ranges[a].lower()) < 0) {
                b += 1
                continue
            }
            // ... similarly for the `a` range. If it's less than the smallest
            // `b` range, then we can add it as-is.
            if (factory.cmp(ranges[a].upper(), other.ranges[b].lower()) < 0) {
                val range = ranges[a]
                ranges.add(range)
                a += 1
                continue
            }
            // Otherwise, we have overlapping ranges.
            check(!ranges[a].isIntersectionEmpty(other.ranges[b]))

            // This part is tricky and was non-obvious to me without looking
            // at explicit examples (see the tests). The trickiness stems from
            // two things: 1) subtracting a range from another range could
            // yield two ranges and 2) after subtracting a range, it's possible
            // that future ranges can have an impact. The loop below advances
            // the `b` ranges until they can't possible impact the current
            // range.
            //
            // For example, if our `a` range is `a-t` and our next three `b`
            // ranges are `a-c`, `g-i`, `r-t` and `x-z`, then we need to apply
            // subtraction three times before moving on to the next `a` range.
            var range: I = ranges[a]
            while (b < other.ranges.size && !range.isIntersectionEmpty(other.ranges[b])) {
                val oldRange = range
                val (d1, d2) = range.difference(other.ranges[b])
                range = when {
                    d1 == null && d2 == null -> {
                        // We lost the entire range, so move on to the next
                        // without adding this one.
                        a += 1
                        continue@loopA
                    }
                    d1 != null && d2 == null -> d1
                    d1 == null && d2 != null -> d2
                    else -> {
                        ranges.add(d1!!)
                        d2!!
                    }
                }
                // It's possible that the `b` range has more to contribute
                // here. In particular, if it is greater than the original
                // range, then it might impact the next `a` range *and* it
                // has impacted the current `a` range as much as possible,
                // so we can quit. We don't bump `b` so that the next `a`
                // range can apply it.
                if (factory.cmp(other.ranges[b].upper(), oldRange.upper()) > 0) {
                    break
                }
                // Otherwise, the next `b` range might apply to the current
                // `a` range.
                b += 1
            }
            ranges.add(range)
            a += 1
        }
        while (a < drainEnd) {
            val range = ranges[a]
            ranges.add(range)
            a += 1
        }
        repeat(drainEnd) { ranges.removeAt(0) }
        folded = folded && other.folded
    }

    /**
     * Compute the symmetric difference of the two sets, in place.
     *
     * This computes the symmetric difference of two interval sets. This
     * removes all elements in this set that are also in the given set,
     * but also adds all elements from the given set that aren't in this
     * set. That is, the set will contain all elements in either set,
     * but will not contain any elements that are in both sets.
     */
    fun symmetricDifference(other: IntervalSet<I, B>) {
        // Future work (burntsushi): Fix this so that it amortizes allocation.
        val intersection = IntervalSet(factory, ranges.toMutableList(), folded)
        intersection.intersect(other)
        union(other)
        difference(intersection)
    }

    /**
     * Negate this interval set.
     *
     * For all `x` where `x` is any element, if `x` was in this set, then it
     * will not be in this set after negation.
     */
    fun negate() {
        if (ranges.isEmpty()) {
            val min = factory.minBound()
            val max = factory.maxBound()
            ranges.add(factory.create(min, max))
            // The set containing everything must case folded.
            folded = true
            return
        }

        // There should be a way to do this in-place with constant memory,
        // but I couldn't figure out a simple way to do it. So just append
        // the negation to the end of this range, and then drain it before
        // we're done.
        val drainEnd = ranges.size

        // We do checked arithmetic below because of the canonical ordering
        // invariant.
        if (factory.cmp(ranges[0].lower(), factory.minBound()) > 0) {
            val upper = factory.decrement(ranges[0].lower())
            ranges.add(factory.create(factory.minBound(), upper))
        }
        for (i in 1 until drainEnd) {
            val lower = factory.increment(ranges[i - 1].upper())
            val upper = factory.decrement(ranges[i].lower())
            ranges.add(factory.create(lower, upper))
        }
        if (factory.cmp(ranges[drainEnd - 1].upper(), factory.maxBound()) < 0) {
            val lower = factory.increment(ranges[drainEnd - 1].upper())
            ranges.add(factory.create(lower, factory.maxBound()))
        }
        repeat(drainEnd) { ranges.removeAt(0) }
        // We don't need to update whether this set is folded or not, because
        // it is conservatively preserved through negation. Namely, if a set
        // is not folded, then it is possible that its negation is folded, for
        // example, [^☃]. But we're fine with assuming that the set is not
        // folded in that case. (`folded` permits false negatives but not false
        // positives.)
        //
        // But what about when a set is folded, is its negation also
        // necessarily folded? Yes. Because if a set is folded, then for every
        // character in the set, it necessarily included its equivalence class
        // of case folded characters. Negating it in turn means that all
        // equivalence classes in the set are negated, and any equivalence
        // class that was previously not in the set is now entirely in the set.
    }

    /** Converts this set into a canonical ordering. */
    private fun canonicalize() {
        if (isCanonical()) {
            return
        }
        ranges.sort()
        check(ranges.isNotEmpty())

        // Is there a way to do this in-place with constant memory? I couldn't
        // figure out a way to do it. So just append the canonicalization to
        // the end of this range, and then drain it before we're done.
        val drainEnd = ranges.size
        for (oldi in 0 until drainEnd) {
            // If we've added at least one new range, then check if we can
            // merge this range in the previously added range.
            if (ranges.size > drainEnd) {
                val lastIdx = ranges.size - 1
                val merged = ranges[lastIdx].union(ranges[oldi])
                if (merged != null) {
                    ranges[lastIdx] = merged
                    continue
                }
            }
            val range = ranges[oldi]
            ranges.add(range)
        }
        repeat(drainEnd) { ranges.removeAt(0) }
    }

    /** Returns true if and only if this class is in a canonical ordering. */
    private fun isCanonical(): Boolean {
        for (i in 0 until ranges.size - 1) {
            val a = ranges[i]
            val b = ranges[i + 1]
            if (a >= b) {
                return false
            }
            if (a.isContiguous(b)) {
                return false
            }
        }
        return true
    }

    // PartialEq is implemented manually so that we don't consider the set's
    // internal `folded` property to be part of its identity. The `folded`
    // property is strictly an optimization.
    override fun equals(other: Any?): Boolean =
        other is IntervalSet<*, *> && ranges == other.ranges

    override fun hashCode(): Int = ranges.hashCode()

    override fun toString(): String = "IntervalSet(${ranges})"
}

/** An iterator over intervals. */
class IntervalSetIter<I> internal constructor(
    private val inner: Iterator<I>,
) : Iterator<I> {
    override fun hasNext(): Boolean = inner.hasNext()
    override fun next(): I = inner.next()
}

/**
 * Carries the bound-type-static operations [Bound] exposes
 * (min/max value, increment, decrement, the unsigned-32 view) along with
 * a smart constructor for intervals over that bound. Each [Interval]
 * implementation supplies one of these so that [IntervalSet] can fabricate
 * intervals out of bounds without holding a sample interval — the case handled
 * by [IntervalFactory.create] with [IntervalFactory.minBound] and
 * [IntervalFactory.maxBound].
 *
 * [boundAsInt] returns the bound as its u32 representation; all bound
 * comparisons in this module flow through [cmp] (which compares u32 views)
 * because Kotlin's [Byte] type uses signed comparison and would mis-order
 * unsigned bytes above 0x7F.
 */
interface IntervalFactory<I : Interval<I, B>, B> {
    /** Create a new interval. */
    fun create(lower: B, upper: B): I

    fun minBound(): B
    fun maxBound(): B
    fun boundAsInt(b: B): Int
    fun increment(b: B): B
    fun decrement(b: B): B

    /** Compare two bound values using their unsigned u32 representation. */
    fun cmp(a: B, b: B): Int = boundAsInt(a).compareTo(boundAsInt(b))
}

/**
 * Kotlin interface corresponding to the upstream `Interval` abstraction.
 *
 * The interface is F-bounded (`I : Interval<I, B>`) so that overrides of
 * [caseFoldSimple] can take a `MutableList<I>` of the concrete self-type.
 * The bound-type-static operations are reachable via [factory].
 */
interface Interval<I : Interval<I, B>, B> : Comparable<I> {
    fun lower(): B
    fun upper(): B
    fun setLower(bound: B)
    fun setUpper(bound: B)
    fun caseFoldSimple(intervals: MutableList<I>): Result<Unit>

    /** The factory carrying this interval's bound-type-static operations. */
    fun factory(): IntervalFactory<I, B>

    /**
     * Union the given overlapping range into this range.
     *
     * If the two ranges aren't contiguous, then this returns `null`.
     */
    fun union(other: I): I? {
        if (!isContiguous(other)) {
            return null
        }
        val f = factory()
        val lower = if (f.cmp(lower(), other.lower()) <= 0) lower() else other.lower()
        val upper = if (f.cmp(upper(), other.upper()) >= 0) upper() else other.upper()
        return f.create(lower, upper)
    }

    /**
     * Intersect this range with the given range and return the result.
     *
     * If the intersection is empty, then this returns `null`.
     */
    fun intersect(other: I): I? {
        val f = factory()
        val lower = if (f.cmp(lower(), other.lower()) >= 0) lower() else other.lower()
        val upper = if (f.cmp(upper(), other.upper()) <= 0) upper() else other.upper()
        return if (f.cmp(lower, upper) <= 0) f.create(lower, upper) else null
    }

    /**
     * Subtract the given range from this range and return the resulting
     * ranges.
     *
     * If subtraction would result in an empty range, then no ranges are
     * returned.
     */
    fun difference(other: I): Pair<I?, I?> {
        val f = factory()
        if (isSubset(other)) {
            return Pair(null, null)
        }
        if (isIntersectionEmpty(other)) {
            return Pair(f.create(lower(), upper()), null)
        }
        val addLower = f.cmp(other.lower(), lower()) > 0
        val addUpper = f.cmp(other.upper(), upper()) < 0
        // We know this because !this.isSubset(other) and the ranges have
        // a non-empty intersection.
        check(addLower || addUpper)
        var first: I? = null
        var second: I? = null
        if (addLower) {
            val upper = f.decrement(other.lower())
            first = f.create(lower(), upper)
        }
        if (addUpper) {
            val lower = f.increment(other.upper())
            val range = f.create(lower, upper())
            if (first == null) {
                first = range
            } else {
                second = range
            }
        }
        return Pair(first, second)
    }

    /**
     * Returns true if and only if the two ranges are contiguous. Two ranges
     * are contiguous if and only if the ranges are either overlapping or
     * adjacent.
     */
    fun isContiguous(other: I): Boolean {
        val f = factory()
        val lower1 = f.boundAsInt(lower()).toLong() and 0xFFFF_FFFFL
        val upper1 = f.boundAsInt(upper()).toLong() and 0xFFFF_FFFFL
        val lower2 = f.boundAsInt(other.lower()).toLong() and 0xFFFF_FFFFL
        val upper2 = f.boundAsInt(other.upper()).toLong() and 0xFFFF_FFFFL
        val maxLower = if (lower1 >= lower2) lower1 else lower2
        val minUpper = if (upper1 <= upper2) upper1 else upper2
        // Saturating add by 1 — if minUpper is at the max u32 value, the
        // increment saturates instead of wrapping.
        val saturated = if (minUpper == 0xFFFF_FFFFL) minUpper else minUpper + 1L
        return maxLower <= saturated
    }

    /**
     * Returns true if and only if the intersection of this range and the
     * other range is empty.
     */
    fun isIntersectionEmpty(other: I): Boolean {
        val f = factory()
        val maxLower = if (f.cmp(lower(), other.lower()) >= 0) lower() else other.lower()
        val minUpper = if (f.cmp(upper(), other.upper()) <= 0) upper() else other.upper()
        return f.cmp(maxLower, minUpper) > 0
    }

    /** Returns true if and only if this range is a subset of the other range. */
    fun isSubset(other: I): Boolean {
        val f = factory()
        val lower1 = lower()
        val upper1 = upper()
        val lower2 = other.lower()
        val upper2 = other.upper()
        return (f.cmp(lower2, lower1) <= 0 && f.cmp(lower1, upper2) <= 0) &&
            (f.cmp(lower2, upper1) <= 0 && f.cmp(upper1, upper2) <= 0)
    }
}

/**
 * Kotlin interface corresponding to the upstream `Bound` abstraction.
 *
 * The upstream source expressed the bound-type-static operations as a separate
 * abstraction so generic interval code could request min/max values for the
 * bound type. Kotlin lacks associated types, so [IntervalFactory] folds these
 * operations in alongside the interval smart constructor — but the [Bound]
 * surface is preserved here for callers that want to operate on a bound type
 * alone.
 */
interface Bound<B> {
    fun minValue(): B
    fun maxValue(): B
    fun asInt(b: B): Int
    fun increment(b: B): B
    fun decrement(b: B): B
}

/** Bound implementation for an unsigned byte stored in a Kotlin signed [Byte]. */
object ByteBound : Bound<Byte> {
    override fun minValue(): Byte = 0
    override fun maxValue(): Byte = -1 // 0xFF as signed Byte

    override fun asInt(b: Byte): Int = b.toInt() and 0xFF

    override fun increment(b: Byte): Byte {
        val v = b.toInt() and 0xFF
        check(v < 0xFF) { "ByteBound.increment overflow" }
        return (v + 1).toByte()
    }

    override fun decrement(b: Byte): Byte {
        val v = b.toInt() and 0xFF
        check(v > 0) { "ByteBound.decrement underflow" }
        return (v - 1).toByte()
    }
}

/**
 * Bound implementation for a Unicode scalar value.
 *
 * The upstream character-bound implementation lives here. Kotlin's [Char] is
 * a UTF-16 code unit, not a Unicode scalar value, so the port stores codepoints in [Int]
 * and skips the surrogate gap (U+D800..U+DFFF) on increment/decrement just as
 * the upstream source does.
 */
object CharBound : Bound<Int> {
    override fun minValue(): Int = 0x00
    override fun maxValue(): Int = 0x10FFFF

    override fun asInt(b: Int): Int = b

    override fun increment(b: Int): Int = when (b) {
        0xD7FF -> 0xE000
        else -> {
            check(b < 0x10FFFF) { "CharBound.increment overflow" }
            b + 1
        }
    }

    override fun decrement(b: Int): Int = when (b) {
        0xE000 -> 0xD7FF
        else -> {
            check(b > 0) { "CharBound.decrement underflow" }
            b - 1
        }
    }
}

// Tests for interval sets are written in src/hir.rs against the public API.
