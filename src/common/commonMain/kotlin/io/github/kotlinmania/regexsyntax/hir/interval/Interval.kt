// port-lint: source hir/interval.rs
package io.github.kotlinmania.regexsyntax.hir.interval

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
public open class IntervalSet<I>(
    /**
     * A sorted set of non-overlapping ranges.
     */
    public var ranges: MutableList<I>,
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
    public var folded: Boolean,
) {
    // We implement PartialEq manually so that we don't consider the set's internal
    // 'folded' property to be part of its identity. The 'folded' property is
    // strictly an optimization.
    public open fun eq(other: IntervalSet<I>): Boolean = this.ranges.eq(other.ranges)

    /**
     * Add a new interval to this set.
     */
    public open fun push(interval: I) {
        // Note: This could be faster. e.g., Push the interval such that
        // it preserves canonicalization.
        this.ranges.add(interval)
        this.canonicalize()
        // We don't know whether the new interval added here is considered
        // case folded, so we conservatively assume that the entire set is
        // no longer case folded if it was previously.
        this.folded = false
    }

    /**
     * Return an iterator over all intervals in this set.
     *
     * The iterator yields intervals in ascending order.
     */
    public open fun iter(): IntervalSetIter<I> = IntervalSetIter(ArrayDeque(this.ranges))

    /**
     * Return an immutable slice of intervals in this set.
     *
     * The sequence returned is in canonical ordering.
     */
    public open fun intervals(): List<I> = this.ranges

    /**
     * Expand this interval set such that it contains all case folded
     * characters. For example, if this class consists of the range `a - z`,
     * then applying case folding will result in the class containing both the
     * ranges `a - z` and `A - Z`.
     *
     * This returns an error if the necessary case mapping data is not
     * available.
     */
    public open fun caseFoldSimple(): Result<Unit> {
        if (this.folded) {
            return Result.success(Unit)
        }
        val len = this.ranges.size
        for (i in 0 until len) {
            val range = this.ranges[i]
            when (val matchVal = range.caseFoldSimple(this.ranges)) {
                is Err -> {
                    val (err) = matchVal
                    this.canonicalize()
                    return Result.failure(err)
                }
                else -> {}
            }
        }
        this.canonicalize()
        this.folded = true
        return Result.success(Unit)
    }

    /**
     * Union this set with the given set, in place.
     */
    public open fun union(other: IntervalSet<I>) {
        if (other.ranges.isEmpty() || this.ranges == other.ranges) {
            return
        }
        // This could almost certainly be done more efficiently.
        this.ranges.extend(other.ranges)
        this.canonicalize()
        this.folded = this.folded && other.folded
    }

    /**
     * Intersect this set with the given set, in place.
     */
    public open fun intersect(other: IntervalSet<I>) {
        if (this.ranges.isEmpty()) {
            return
        }
        if (other.ranges.isEmpty()) {
            this.ranges.clear()
            // An empty set is case folded.
            this.folded = true
            return
        }
        // There should be a way to do this in-place with constant memory,
        // but I couldn't figure out a simple way to do it. So just append
        // the intersection to the end of this range, and then drain it before
        // we're done.
        val drainEnd = this.ranges.size
        val ita = 0 until drainEnd
        val itb = 0 until other.ranges.len()
        val a = ita.next()!!
        val b = itb.next()!!
        while (true) {
            when (val matchVal = this.ranges[a].intersect(other.ranges[b])) {
                null -> {}
                else -> {
                    val ab = matchVal
                    this.ranges.add(ab)
                }
            }
            val (it, aorb) =
                if (this.ranges[a].upper() < other.ranges[b].upper()) {
                    Pair(ita, a)
                } else {
                    Pair(itb, b)
                }
            when (val matchVal1 = it.next()) {
                null -> { break }
                else -> {
                    val v = matchVal1
                    aorb = v
                }
            }
        }
        this.ranges.drain(0 until drainEnd)
        this.folded = this.folded && other.folded
    }

    /**
     * Subtract the given set from this set, in place.
     */
    public open fun difference(other: IntervalSet<I>) {
        if (this.ranges.isEmpty() || other.ranges.isEmpty()) {
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
        val drainEnd = this.ranges.size
        var (a, b) = Pair(0, 0)
        while (a < drainEnd && b < other.ranges.len()) {
            // Basically, the easy cases are when neither range overlaps with
            // each other. If the `b` range is less than our current `a`
            // range, then we can skip it and move on.
            if (other.ranges[b].upper() < this.ranges[a].lower()) {
                b += 1
                continue
            }
            // ... similarly for the `a` range. If it's less than the smallest
            // `b` range, then we can add it as-is.
            if (this.ranges[a].upper() < other.ranges[b].lower()) {
                val range = this.ranges[a]
                this.ranges.add(range)
                a += 1
                continue
            }
            // Otherwise, we have overlapping ranges.
            check(!this.ranges[a].isIntersectionEmpty(other.ranges[b]))
            // This part is tricky and was non-obvious to me without looking
            // at explicit examples (see the tests). The trickiness stems from
            // two things: 1) subtracting a range from another range could
            // yield two ranges and 2) after subtracting a range, it's possible
            // that future ranges can have an impact. The loop below advances
            // the `b` ranges until they can't possible impact the current
            // range.
            //
            // For example, if our `a` range is `a - t` and our next three `b`
            // ranges are `a - c`, `g - i`, `r - t` and `x - z`, then we need to apply
            // subtraction three times before moving on to the next `a` range.
            var range1 = this.ranges[a]
            while (b < other.ranges.len() && !range1.isIntersectionEmpty(other.ranges[b])) {
                val oldRange = range1
                range1 = run {
                    val matchVal = range1.difference(other.ranges[b])
                    when {
                        matchVal.first == null && matchVal.second == null -> {
                            // We lost the entire range, so move on to the next
                            // without adding this one.
                            a += 1
                            continue
                        }
                        matchVal.first != null && matchVal.second == null -> {
                            val range1 = matchVal.first
                            range1
                        }
                        matchVal.first == null && matchVal.second != null -> {
                            val range11 = matchVal.second
                            range11
                        }
                        else -> {
                            val range12 = matchVal.first
                            val range2 = matchVal.second
                            this.ranges.add(range12)
                            range2
                        }
                    }
                }
                // It's possible that the `b` range has more to contribute
                // here. In particular, if it is greater than the original
                // range, then it might impact the next `a` range *and* it
                // has impacted the current `a` range as much as possible,
                // so we can quit. We don't bump `b` so that the next `a`
                // range can apply it.
                if (other.ranges[b].upper() > oldRange.upper()) {
                    break
                }
                // Otherwise, the next `b` range might apply to the current
                // `a` range.
                b += 1
            }
            this.ranges.add(range1)
            a += 1
        }
        while (a < drainEnd) {
            val range2 = this.ranges[a]
            this.ranges.add(range2)
            a += 1
        }
        this.ranges.drain(0 until drainEnd)
        this.folded = this.folded && other.folded
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
    public open fun symmetricDifference(other: IntervalSet<I>) {
        // Note(burntsushi): Fix this so that it amortizes allocation.
        val intersection = this.clone()
        intersection.intersect(other)
        this.union(other)
        this.difference(intersection)
    }

    /**
     * Negate this interval set.
     *
     * For all `x` where `x` is any element, if `x` was in this set, then it
     * will not be in this set after negation.
     */
    public open fun negate(createIFn: (Bound, Bound) -> I) {
        if (this.ranges.isEmpty()) {
            val (min, max) = Pair(I.Bound.minValue(), I.Bound.maxValue())
            this.ranges.add(createIFn(min, max))
            // The set containing everything must case folded.
            this.folded = true
            return
        }
        // There should be a way to do this in-place with constant memory,
        // but I couldn't figure out a simple way to do it. So just append
        // the negation to the end of this range, and then drain it before
        // we're done.
        val drainEnd = this.ranges.size
        // We do checked arithmetic below because of the canonical ordering
        // invariant.
        if (this.ranges[0].lower() > I.Bound.minValue()) {
            val upper = this.ranges[0].lower().decrement()
            this.ranges.add(createIFn(I.Bound.minValue(), upper))
        }
        for (i in 1 until drainEnd) {
            val lower = this.ranges[i - 1].upper().increment()
            val upper1 = this.ranges[i].lower().decrement()
            this.ranges.add(createIFn(lower, upper1))
        }
        if (this.ranges[drainEnd - 1].upper() < I.Bound.maxValue()) {
            val lower1 = this.ranges[drainEnd - 1].upper().increment()
            this.ranges.add(createIFn(lower1, I.Bound.maxValue()))
        }
        this.ranges.drain(0 until drainEnd)
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

    /**
     * Converts this set into a canonical ordering.
     */
    public open fun canonicalize() {
        if (this.isCanonical()) {
            return
        }
        this.ranges.sort()
        check(!this.ranges.isEmpty())
        // Is there a way to do this in-place with constant memory? I couldn't
        // figure out a way to do it. So just append the canonicalization to
        // the end of this range, and then drain it before we're done.
        val drainEnd = this.ranges.size
        for (oldi in 0 until drainEnd) {
            // If we've added at least one new range, then check if we can
            // merge this range in the previously added range.
            if (this.ranges.size > drainEnd) {
                val (last, rest) = this.ranges.splitLastMut().unwrap()
                when (val matchVal = last.union(rest[oldi])) {
                    null -> {}
                    else -> {
                        val union = matchVal
                        last = union
                        continue
                    }
                }
            }
            val range = this.ranges[oldi]
            this.ranges.add(range)
        }
        this.ranges.drain(0 until drainEnd)
    }

    /**
     * Returns true if and only if this class is in a canonical ordering.
     */
    public open fun isCanonical(): Boolean {
        for (pair in this.ranges.windows(2)) {
            if (pair[0] >= pair[1]) {
                return false
            }
            if (pair[0].isContiguous(pair[1])) {
                return false
            }
        }
        return true
    }

    public companion object {
        /**
         * Create a new set from a sequence of intervals. Each interval is
         * specified as a pair of bounds, where both bounds are inclusive.
         *
         * The given ranges do not need to be in any specific order, and ranges
         * may overlap.
         */
        public fun <Bound, I, T : Iterable<I>> new(intervals: T): IntervalSet<I> {
            val ranges: List<I> = intervals.iterator().collect()
            // An empty set is case folded.
            val folded = ranges.isEmpty()
            val set = IntervalSet(ranges = ranges, folded = folded)
            set.canonicalize()
            return set
        }
    }
}

/**
 * An iterator over intervals.
 */
public open class IntervalSetIter<I>(
    public var value: ArrayDeque<I>,
) : Iterable<I> {
    public var field0: ArrayDeque<I>
        get() = value
        set(v) {
            value = v
        }

    override fun iterator(): kotlin.collections.Iterator<I> = object : AbstractIterator<I>() {
        override fun computeNext() {
            when (val v = this@IntervalSetIter.next()) {
                null -> done()
                else -> setNext(v)
            }
        }
    }

    public open fun next(): I? = this.value.next()
}

public interface Interval<T, Bound> {
    public fun lower(): Bound

    public fun upper(): Bound

    public fun setLower(bound: Bound)

    public fun setUpper(bound: Bound)

    public fun caseFoldSimple(intervals: List<T>): Result<Unit>

    /**
     * Create a new interval.
     */
    public fun create(lower: Bound, upper: Bound): T {
        val int = T.default()
        if (lower <= upper) {
            int.setLower(lower)
            int.setUpper(upper)
        } else {
            int.setLower(upper)
            int.setUpper(lower)
        }
        return int
    }

    /**
     * Union the given overlapping range into this range.
     *
     * If the two ranges aren't contiguous, then this returns `None`.
     */
    public fun union(other: T): T? {
        if (!this.isContiguous(other)) {
            return null
        }
        val lower = minOf(this.lower(), other.lower())
        val upper = cmp.max(this.upper(), other.upper())
        return lower.create(upper)
    }

    /**
     * Intersect this range with the given range and return the result.
     *
     * If the intersection is empty, then this returns `None`.
     */
    public fun intersect(other: T): T? {
        val lower = cmp.max(this.lower(), other.lower())
        val upper = minOf(this.upper(), other.upper())
        return if (lower <= upper) {
            lower.create(upper)
        } else {
            null
        }
    }

    /**
     * Subtract the given range from this range and return the resulting
     * ranges.
     *
     * If subtraction would result in an empty range, then no ranges are
     * returned.
     */
    public fun difference(other: T): Pair<T?, T?> {
        if (this.isSubset(other)) {
            return Pair(null, null)
        }
        if (this.isIntersectionEmpty(other)) {
            return Pair(this.clone(), null)
        }
        val addLower = other.lower() > this.lower()
        val addUpper = other.upper() < this.upper()
        // We know this because !self.isSubset(other) and the ranges have
        // a non-empty intersection.
        check(addLower || addUpper)
        val ret = Pair(null, null)
        if (addLower) {
            val upper = other.lower().decrement()
            ret.first = this.lower().create(upper)
        }
        if (addUpper) {
            val lower = other.upper().increment()
            val range = lower.create(this.upper())
            if (ret.first == null) {
                ret.first = range
            } else {
                ret.second = range
            }
        }
        return ret
    }

    /**
     * Returns true if and only if the two ranges are contiguous. Two ranges
     * are contiguous if and only if the ranges are either overlapping or
     * adjacent.
     */
    public fun isContiguous(other: T): Boolean {
        val lower1 = this.lower().asU32()
        val upper1 = this.upper().asU32()
        val lower2 = other.lower().asU32()
        val upper2 = other.upper().asU32()
        return cmp.max(lower1, lower2) <= minOf(upper1, upper2).saturatingAdd(1)
    }

    /**
     * Returns true if and only if the intersection of this range and the
     * other range is empty.
     */
    public fun isIntersectionEmpty(other: T): Boolean {
        val (lower1, upper1) = Pair(this.lower(), this.upper())
        val (lower2, upper2) = Pair(other.lower(), other.upper())
        return cmp.max(lower1, lower2) > minOf(upper1, upper2)
    }

    /**
     * Returns true if and only if this range is a subset of the other range.
     */
    public fun isSubset(other: T): Boolean {
        val (lower1, upper1) = Pair(this.lower(), this.upper())
        val (lower2, upper2) = Pair(other.lower(), other.upper())
        return (lower2 <= lower1 && lower1 <= upper2) && (lower2 <= upper1 && upper1 <= upper2)
    }
}

public interface Bound<T> {
    public fun minValue(): T

    public fun maxValue(): T

    public fun asU32(): UInt

    public fun increment(): T

    public fun decrement(): T
}

public fun boundUByte(): UByte = UByte.MIN_VALUE

public fun UByte.asU32(): UInt = UInt.from(this)

public fun UByte.increment(): UByte = (((this).toLong() + (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!

public fun UByte.decrement(): UByte = (((this).toLong() - (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!

public fun boundChar(): Char = '\u0000'

public fun Char.asU32(): UInt = UInt.from(this)

public fun Char.increment(): Char =
    when (this) {
        '\ud7ff' -> '\ue000'
        else -> {
            val c = this
            Char.fromU32((((UInt.from(c)).toLong() + (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!).unwrap()
        }
    }

public fun Char.decrement(): Char =
    when (this) {
        '\ue000' -> '\ud7ff'
        else -> {
            val c = this
            Char.fromU32((((UInt.from(c)).toLong() - (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!).unwrap()
        }
    }
