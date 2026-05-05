// port-lint: source utf8.rs
package io.github.kotlinmania.regexsyntax.utf8

/*
 * Copyright (c) The rust-lang regex contributors.
 * Licensed under either of Apache-2.0 OR MIT.
 */

/**
 * Converts ranges of Unicode scalar values to equivalent ranges of UTF-8 bytes.
 *
 * This sub-module is useful for constructing byte based automatons that need
 * to embed UTF-8 decoding. The most common use of this module is in conjunction
 * with the [io.github.kotlinmania.regexsyntax.hir.ClassUnicodeRange] type from
 * this crate's HIR.
 *
 * See the documentation on the [Utf8Sequences] iterator for more details and
 * an example.
 *
 * # Wait, what is this?
 *
 * This is simplest to explain with an example. Let's say you wanted to test
 * whether a particular byte sequence was a Cyrillic character. One possible
 * scalar value range is `[0400-04FF]`. The set of allowed bytes for this
 * range can be expressed as a sequence of byte ranges:
 *
 * ```text
 * [D0-D3][80-BF]
 * ```
 *
 * This is simple enough: simply encode the boundaries, `0400` encodes to
 * `D0 80` and `04FF` encodes to `D3 BF`, and create ranges from each
 * corresponding pair of bytes: `D0` to `D3` and `80` to `BF`.
 *
 * However, what if you wanted to add the Cyrillic Supplementary characters to
 * your range? Your range might then become `[0400-052F]`. The same procedure
 * as above doesn't quite work because `052F` encodes to `D4 AF`. The byte ranges
 * you'd get from the previous transformation would be `[D0-D4][80-AF]`. However,
 * this isn't quite correct because this range doesn't capture many characters,
 * for example, `04FF` (because its last byte, `BF` isn't in the range `80-AF`).
 *
 * Instead, you need multiple sequences of byte ranges:
 *
 * ```text
 * [D0-D3][80-BF]  # matches codepoints 0400-04FF
 * [D4][80-AF]     # matches codepoints 0500-052F
 * ```
 *
 * This gets even more complicated if you want bigger ranges, particularly if
 * they naively contain surrogate codepoints. For example, the sequence of byte
 * ranges for the basic multilingual plane (`[0000-FFFF]`) look like this:
 *
 * ```text
 * [0-7F]
 * [C2-DF][80-BF]
 * [E0][A0-BF][80-BF]
 * [E1-EC][80-BF][80-BF]
 * [ED][80-9F][80-BF]
 * [EE-EF][80-BF][80-BF]
 * ```
 *
 * Note that the byte ranges above will *not* match any erroneous encoding of
 * UTF-8, including encodings of surrogate codepoints.
 *
 * And, of course, for all of Unicode (`[000000-10FFFF]`):
 *
 * ```text
 * [0-7F]
 * [C2-DF][80-BF]
 * [E0][A0-BF][80-BF]
 * [E1-EC][80-BF][80-BF]
 * [ED][80-9F][80-BF]
 * [EE-EF][80-BF][80-BF]
 * [F0][90-BF][80-BF][80-BF]
 * [F1-F3][80-BF][80-BF][80-BF]
 * [F4][80-8F][80-BF][80-BF]
 * ```
 *
 * This module automates the process of creating these byte ranges from ranges of
 * Unicode scalar values.
 *
 * # Lineage
 *
 * The idea and general implementation strategy come from Russ Cox's article on
 * regexps and from RE2. Russ Cox got it from Ken Thompson's `grep` (no source,
 * folk lore?). The same trick is also used by Apache Lucene's UTF32ToUTF8.
 */

private const val MAX_UTF8_BYTES: Int = 4

/**
 * [Utf8Sequence] represents a sequence of byte ranges.
 *
 * To match a [Utf8Sequence], a candidate byte sequence must match each
 * successive range.
 *
 * For example, if there are two ranges, `[C2-DF][80-BF]`, then the byte
 * sequence `\xDD\x61` would not match because `0x61 < 0x80`.
 */
sealed class Utf8Sequence : Comparable<Utf8Sequence>, Iterable<Utf8Range> {
    /** One byte range. */
    data class One(val r: Utf8Range) : Utf8Sequence()
    /** Two successive byte ranges. */
    data class Two(val rs: Array<Utf8Range>) : Utf8Sequence() {
        init { check(rs.size == 2) }
        override fun equals(other: Any?): Boolean = other is Two && rs.contentEquals(other.rs)
        override fun hashCode(): Int = rs.contentHashCode()
    }
    /** Three successive byte ranges. */
    data class Three(val rs: Array<Utf8Range>) : Utf8Sequence() {
        init { check(rs.size == 3) }
        override fun equals(other: Any?): Boolean = other is Three && rs.contentEquals(other.rs)
        override fun hashCode(): Int = rs.contentHashCode()
    }
    /** Four successive byte ranges. */
    data class Four(val rs: Array<Utf8Range>) : Utf8Sequence() {
        init { check(rs.size == 4) }
        override fun equals(other: Any?): Boolean = other is Four && rs.contentEquals(other.rs)
        override fun hashCode(): Int = rs.contentHashCode()
    }

    companion object {
        /**
         * Creates a new UTF-8 sequence from the encoded bytes of a scalar value
         * range.
         *
         * This assumes that `start` and `end` have the same length.
         */
        internal fun fromEncodedRange(start: ByteArray, startLen: Int, end: ByteArray, endLen: Int): Utf8Sequence {
            check(startLen == endLen)
            return when (startLen) {
                2 -> Two(arrayOf(
                    Utf8Range.new(start[0], end[0]),
                    Utf8Range.new(start[1], end[1]),
                ))
                3 -> Three(arrayOf(
                    Utf8Range.new(start[0], end[0]),
                    Utf8Range.new(start[1], end[1]),
                    Utf8Range.new(start[2], end[2]),
                ))
                4 -> Four(arrayOf(
                    Utf8Range.new(start[0], end[0]),
                    Utf8Range.new(start[1], end[1]),
                    Utf8Range.new(start[2], end[2]),
                    Utf8Range.new(start[3], end[3]),
                ))
                else -> error("invalid encoded length: $startLen")
            }
        }
    }

    /** Returns the underlying sequence of byte ranges as a list. */
    fun asSlice(): List<Utf8Range> = when (this) {
        is One -> listOf(r)
        is Two -> rs.toList()
        is Three -> rs.toList()
        is Four -> rs.toList()
    }

    /**
     * Kotlin equivalent of Rust's `IntoIterator for &Utf8Sequence`.
     *
     * This returns an iterator over the underlying byte ranges.
     */
    fun intoIter(): Iterator<Utf8Range> = iterator()

    /**
     * Returns the number of byte ranges in this sequence.
     *
     * The length is guaranteed to be in the closed interval `[1, 4]`.
     */
    fun len(): Int = asSlice().size

    /**
     * Reverses the ranges in this sequence.
     *
     * For example, if this corresponds to the following sequence:
     *
     * ```text
     * [D0-D3][80-BF]
     * ```
     *
     * Then after reversal, it will be
     *
     * ```text
     * [80-BF][D0-D3]
     * ```
     *
     * This is useful when one is constructing a UTF-8 automaton to match
     * character classes in reverse.
     */
    fun reverse(): Utf8Sequence = when (this) {
        is One -> this
        is Two -> Two(arrayOf(rs[1], rs[0]))
        is Three -> Three(arrayOf(rs[2], rs[1], rs[0]))
        is Four -> Four(arrayOf(rs[3], rs[2], rs[1], rs[0]))
    }

    /**
     * Returns true if and only if a prefix of [bytes] matches this sequence
     * of byte ranges.
     */
    fun matches(bytes: ByteArray): Boolean {
        if (bytes.size < len()) return false
        val rs = asSlice()
        for (i in rs.indices) {
            if (!rs[i].matches(bytes[i])) return false
        }
        return true
    }

    override fun iterator(): Iterator<Utf8Range> = asSlice().iterator()

    /**
     * Kotlin equivalent of Rust's `fmt::Debug` implementation.
     *
     * This matches [toString].
     */
    fun fmt(): String = toString()

    override fun toString(): String = when (this) {
        is One -> r.toString()
        is Two -> "${rs[0]}${rs[1]}"
        is Three -> "${rs[0]}${rs[1]}${rs[2]}"
        is Four -> "${rs[0]}${rs[1]}${rs[2]}${rs[3]}"
    }

    override fun compareTo(other: Utf8Sequence): Int {
        val a = asSlice()
        val b = other.asSlice()
        for (i in 0 until minOf(a.size, b.size)) {
            val c = a[i].compareTo(b[i])
            if (c != 0) return c
        }
        return a.size.compareTo(b.size)
    }
}

/** A single inclusive range of UTF-8 bytes. */
data class Utf8Range(
    /** Start of byte range (inclusive). */
    val start: Byte,
    /** End of byte range (inclusive). */
    val end: Byte,
) : Comparable<Utf8Range> {
    companion object {
        internal fun new(start: Byte, end: Byte): Utf8Range = Utf8Range(start, end)
    }

    /** Returns true if and only if the given byte is in this range. */
    fun matches(b: Byte): Boolean {
        val s = start.toInt() and 0xFF
        val e = end.toInt() and 0xFF
        val v = b.toInt() and 0xFF
        return s <= v && v <= e
    }

    override fun toString(): String {
        val s = start.toInt() and 0xFF
        val e = end.toInt() and 0xFF
        return if (s == e) {
            "[${s.toString(16).uppercase()}]"
        } else {
            "[${s.toString(16).uppercase()}-${e.toString(16).uppercase()}]"
        }
    }

    override fun compareTo(other: Utf8Range): Int {
        val a = start.toInt() and 0xFF
        val b = other.start.toInt() and 0xFF
        if (a != b) return a.compareTo(b)
        val c = end.toInt() and 0xFF
        val d = other.end.toInt() and 0xFF
        return c.compareTo(d)
    }
}

/**
 * An iterator over ranges of matching UTF-8 byte sequences.
 *
 * The iteration represents an alternation of comprehensive byte sequences
 * that match precisely the set of UTF-8 encoded scalar values.
 *
 * A byte sequence corresponds to one of the scalar values in the range given
 * if and only if it completely matches exactly one of the sequences of byte
 * ranges produced by this iterator.
 *
 * Each sequence of byte ranges matches a unique set of bytes. That is, no two
 * sequences will match the same bytes.
 */
class Utf8Sequences(start: Int, end: Int) : Iterator<Utf8Sequence> {
    private val rangeStack: MutableList<ScalarRange> = mutableListOf(ScalarRange(start, end))
    private var nextItem: Utf8Sequence? = null
    private var ready: Boolean = false

    /** Reset resets the scalar value range. Any existing state is cleared. */
    fun reset(start: Int, end: Int) {
        rangeStack.clear()
        push(start, end)
        ready = false
        nextItem = null
    }

    private fun push(start: Int, end: Int) {
        rangeStack.add(ScalarRange(start, end))
    }

    override fun hasNext(): Boolean {
        if (!ready) {
            nextItem = computeNext()
            ready = true
        }
        return nextItem != null
    }

    override fun next(): Utf8Sequence {
        if (!ready) nextItem = computeNext()
        val item = nextItem ?: throw NoSuchElementException()
        ready = false
        nextItem = null
        return item
    }

    private fun computeNext(): Utf8Sequence? {
        topLoop@ while (rangeStack.isNotEmpty()) {
            var r = rangeStack.removeAt(rangeStack.lastIndex)
            innerLoop@ while (true) {
                val split = r.split()
                if (split != null) {
                    val (r1, r2) = split
                    push(r2.start, r2.end)
                    r = ScalarRange(r1.start, r1.end)
                    continue@innerLoop
                }
                if (!r.isValid()) {
                    continue@topLoop
                }
                for (i in 1 until MAX_UTF8_BYTES) {
                    val max = maxScalarValue(i)
                    if (r.start <= max && max < r.end) {
                        push(max + 1, r.end)
                        r = ScalarRange(r.start, max)
                        continue@innerLoop
                    }
                }
                val asciiRange = r.asAscii()
                if (asciiRange != null) {
                    return Utf8Sequence.One(asciiRange)
                }
                for (i in 1 until MAX_UTF8_BYTES) {
                    val m = (1 shl (6 * i)) - 1
                    if ((r.start and m.inv()) != (r.end and m.inv())) {
                        if ((r.start and m) != 0) {
                            push((r.start or m) + 1, r.end)
                            r = ScalarRange(r.start, r.start or m)
                            continue@innerLoop
                        }
                        if ((r.end and m) != m) {
                            push(r.end and m.inv(), r.end)
                            r = ScalarRange(r.start, (r.end and m.inv()) - 1)
                            continue@innerLoop
                        }
                    }
                }
                val start = ByteArray(MAX_UTF8_BYTES)
                val end = ByteArray(MAX_UTF8_BYTES)
                val n = r.encode(start, end)
                return Utf8Sequence.fromEncodedRange(start, n, end, n)
            }
        }
        return null
    }
}

/** A range over scalar (Unicode codepoint) values. */
internal data class ScalarRange(var start: Int, var end: Int) {
    /**
     * `split` splits this range if it overlaps with a surrogate codepoint.
     *
     * Either or both ranges may be invalid.
     */
    fun split(): Pair<ScalarRange, ScalarRange>? {
        return if (start < 0xE000 && end > 0xD7FF) {
            Pair(
                ScalarRange(start, 0xD7FF),
                ScalarRange(0xE000, end),
            )
        } else null
    }

    /** isValid returns true if and only if start <= end. */
    fun isValid(): Boolean = start <= end

    /**
     * `asAscii` returns this range as a [Utf8Range] if and only if all scalar
     * values in this range can be encoded as a single byte.
     */
    fun asAscii(): Utf8Range? {
        return if (isAscii()) {
            Utf8Range.new(start.toByte(), end.toByte())
        } else null
    }

    /**
     * isAscii returns true if the range is ASCII only (i.e., takes a single
     * byte to encode any scalar value).
     */
    fun isAscii(): Boolean = isValid() && end <= 0x7f

    /**
     * encode writes the UTF-8 encoding of the start and end of this range
     * to the corresponding destination arrays, and returns the number of
     * bytes written.
     *
     * The arrays should have room for at least [MAX_UTF8_BYTES].
     */
    fun encode(start: ByteArray, end: ByteArray): Int {
        val ss = encodeUtf8(this.start, start)
        val se = encodeUtf8(this.end, end)
        check(ss == se)
        return ss
    }
}

private fun maxScalarValue(nbytes: Int): Int {
    return when (nbytes) {
        1 -> 0x007F
        2 -> 0x07FF
        3 -> 0xFFFF
        4 -> 0x0010_FFFF
        else -> error("invalid UTF-8 byte sequence size")
    }
}

/**
 * Encode a Unicode codepoint as UTF-8 into [dest], returning the number of bytes written.
 */
private fun encodeUtf8(codepoint: Int, dest: ByteArray): Int {
    return when {
        codepoint < 0x80 -> {
            dest[0] = codepoint.toByte()
            1
        }
        codepoint < 0x800 -> {
            dest[0] = (0xC0 or (codepoint ushr 6)).toByte()
            dest[1] = (0x80 or (codepoint and 0x3F)).toByte()
            2
        }
        codepoint < 0x10000 -> {
            dest[0] = (0xE0 or (codepoint ushr 12)).toByte()
            dest[1] = (0x80 or ((codepoint ushr 6) and 0x3F)).toByte()
            dest[2] = (0x80 or (codepoint and 0x3F)).toByte()
            3
        }
        else -> {
            dest[0] = (0xF0 or (codepoint ushr 18)).toByte()
            dest[1] = (0x80 or ((codepoint ushr 12) and 0x3F)).toByte()
            dest[2] = (0x80 or ((codepoint ushr 6) and 0x3F)).toByte()
            dest[3] = (0x80 or (codepoint and 0x3F)).toByte()
            4
        }
    }
}
