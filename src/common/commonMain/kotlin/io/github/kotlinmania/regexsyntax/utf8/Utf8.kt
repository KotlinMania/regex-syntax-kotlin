// port-lint: source utf8.rs
@file:OptIn(ExperimentalUnsignedTypes::class)

package io.github.kotlinmania.regexsyntax.utf8

import io.github.kotlinmania.regexsyntax.encodeScalarUtf8
import io.github.kotlinmania.regexsyntax.formatHexUpper
import io.github.kotlinmania.regexsyntax.scalarFromU32
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout

// Converts ranges of Unicode scalar values to equivalent ranges of UTF-8 bytes.
//
// This is sub-module is useful for constructing byte based automatons that need
// to embed UTF-8 decoding. The most common use of this module is in conjunction
// with the [`hir.ClassUnicodeRange`](hir.ClassUnicodeRange) type.
//
// See the documentation on the `Utf8Sequences` iterator for more details and
// an example.
//
// # Wait, what is this?
//
// This is simplest to explain with an example. Let's say you wanted to test
// whether a particular byte sequence was a Cyrillic character. One possible
// scalar value range is `[0400 - 04FF]`. The set of allowed bytes for this
// range can be expressed as a sequence of byte ranges:
//
// ```text
// [D0-D3][80-BF]
// ```
//
// This is simple enough: simply encode the boundaries, `0400` encodes to
// `D0 80` and `04FF` encodes to `D3 BF`, and create ranges from each
// corresponding pair of bytes: `D0` to `D3` and `80` to `BF`.
//
// However, what if you wanted to add the Cyrillic Supplementary characters to
// your range? Your range might then become `[0400 - 052F]`. The same procedure
// as above doesn't quite work because `052F` encodes to `D4 AF`. The byte ranges
// you'd get from the previous transformation would be `[D0 - D4][80 - AF]`. However,
// this isn't quite correct because this range doesn't capture many characters,
// for example, `04FF` (because its last byte, `BF` isn't in the range `80 - AF`).
//
// Instead, you need multiple sequences of byte ranges:
//
// ```text
// [D0-D3][80-BF]  # matches codepoints 0400-04FF
// [D4][80-AF]     # matches codepoints 0500-052F
// ```
//
// This gets even more complicated if you want bigger ranges, particularly if
// they naively contain surrogate codepoints. For example, the sequence of byte
// ranges for the basic multilingual plane (`[0000 - FFFF]`) look like this:
//
// ```text
// [0-7F]
// [C2-DF][80-BF]
// [E0][A0-BF][80-BF]
// [E1-EC][80-BF][80-BF]
// [ED][80-9F][80-BF]
// [EE-EF][80-BF][80-BF]
// ```
//
// Note that the byte ranges above will *not* accept any erroneous encoding of
// UTF-8, including encodings of surrogate codepoints.
//
// And, of course, for all of Unicode (`[000000 - 10FFFF]`):
//
// ```text
// [0-7F]
// [C2-DF][80-BF]
// [E0][A0-BF][80-BF]
// [E1-EC][80-BF][80-BF]
// [ED][80-9F][80-BF]
// [EE-EF][80-BF][80-BF]
// [F0][90-BF][80-BF][80-BF]
// [F1-F3][80-BF][80-BF][80-BF]
// [F4][80-8F][80-BF][80-BF]
// ```
//
// This module automates the process of creating these byte ranges from ranges of
// Unicode scalar values.
//
// # Lineage
//
// I got the idea and general implementation strategy from Russ Cox in his
// [article on regexps](https://web.archive.org/web/20160404141123/https://swtch.com/~rsc/regexp/regexp3.html) and RE2.
// Russ Cox got it from Ken Thompson's `grep` (no source, folk lore?).
// I also got the idea from
// [Lucene](https://github.com/apache/lucene-solr/blob/ae93f4e7ac6a3908046391de35d4f50a0d3c59ca/lucene/core/src/java/org/apache/lucene/util/automaton/UTF32ToUTF8.java),
// which uses it for executing automata on their term index.

public val MAX_UTF8_BYTES: Int = 4

/**
 * Utf8Sequence represents a sequence of byte ranges.
 *
 * To satisfy a Utf8Sequence, a candidate byte sequence must satisfy each
 * successive range.
 *
 * For example, if there are two ranges, `[C2 - DF][80 - BF]`, then the byte
 * sequence `\xDD\x61` would not be accepted because `0x61<0x80`.
 */
public sealed class Utf8Sequence : Comparable<Utf8Sequence>, Iterable<Utf8Range> {
    /**
     * One byte range.
     */
    public data class One(public var value: Utf8Range) : Utf8Sequence()
    /**
     * Two successive byte ranges.
     */
    public data class Two(public var value: List<Utf8Range>) : Utf8Sequence()
    /**
     * Three successive byte ranges.
     */
    public data class Three(public var value: List<Utf8Range>) : Utf8Sequence()
    /**
     * Four successive byte ranges.
     */
    public data class Four(public var value: List<Utf8Range>) : Utf8Sequence()

    public fun rustEquals(other: Utf8Sequence): Boolean = this == other

    private fun variantRank(): Int =
        when (this) {
            is One -> 0
            is Two -> 1
            is Three -> 2
            is Four -> 3
        }

    override fun compareTo(other: Utf8Sequence): Int {
        val rankCmp = this.variantRank().compareTo(other.variantRank())
        if (rankCmp != 0) return rankCmp
        val a = this.asSlice()
        val b = other.asSlice()
        for (i in 0 until a.size) {
            val cmp = a[i].compareTo(b[i])
            if (cmp != 0) return cmp
        }
        return 0
    }

    public fun copy(): Utf8Sequence =
        when (this) {
            is One -> One(value.copy())
            is Two -> Two(value.map { it.copy() })
            is Three -> Three(value.map { it.copy() })
            is Four -> Four(value.map { it.copy() })
        }

    override fun iterator(): kotlin.collections.Iterator<Utf8Range> = this.asSlice().iterator()

    /**
     * Returns the underlying sequence of byte ranges as a slice.
     */
    public open fun asSlice(): List<Utf8Range> =
        when (this) {
            is One -> listOf(value)
            is Two -> value
            is Three -> value
            is Four -> value
        }

    /**
     * Returns the number of byte ranges in this sequence.
     *
     * The length is guaranteed to be in the closed interval `[1, 4]`.
     */
    public open fun len(): Int = this.asSlice().size

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
     * This is useful when one is constructing a UTF-8 automaton to accept
     * character classes in reverse.
     */
    public open fun reverse() {
        when (this) {
            is Utf8Sequence.One -> {}
            is Utf8Sequence.Two -> value = value.reversed()
            is Utf8Sequence.Three -> value = value.reversed()
            is Utf8Sequence.Four -> value = value.reversed()
        }
    }

    /**
     * Returns true if and only if a prefix of `bytes` matches this sequence
     * of byte ranges.
     */
    public open fun matches(bytes: List<UByte>): Boolean {
        if (bytes.size < this.len()) {
            return false
        }
        for ((b, r) in bytes.zip(this.asSlice())) {
            if (!r.matches(b)) {
                return false
            }
        }
        return true
    }

    public open fun intoIter(): ArrayDeque<Utf8Range> = ArrayDeque(this.asSlice())

    public open fun fmt(f: StringBuilder) {
        when (this) {
            is One -> {
                val (r) = this
                f.append("$r")
            }
            is Two -> {
                val (r1) = this
                f.append("${r1[0]}${r1[1]}")
            }
            is Three -> {
                val (r2) = this
                f.append("${r2[0]}${r2[1]}${r2[2]}")
            }
            is Four -> {
                val (r3) = this
                f.append("${r3[0]}${r3[1]}${r3[2]}${r3[3]}")
            }
        }
    }

    override fun toString(): String = buildString { fmt(this) }

    public companion object {
        /**
         * Creates a new UTF-8 sequence from the encoded bytes of a scalar value
         * range.
         *
         * This assumes that `start` and `end` have the same length.
         */
        public fun fromEncodedRange(start: List<UByte>, end: List<UByte>): Utf8Sequence {
            check(start.size == end.size)
            return when (val matchVal = start.size) {
                2 -> Utf8Sequence.Two(listOf(Utf8Range.new(start[0], end[0]), Utf8Range.new(start[1], end[1])))
                3 -> Utf8Sequence.Three(listOf(Utf8Range.new(start[0], end[0]), Utf8Range.new(start[1], end[1]), Utf8Range.new(start[2], end[2])))
                4 -> Utf8Sequence.Four(listOf(Utf8Range.new(start[0], end[0]), Utf8Range.new(start[1], end[1]), Utf8Range.new(start[2], end[2]), Utf8Range.new(start[3], end[3])))
                else -> {
                    val n = matchVal
                    error("invalid encoded length: $n")
                }
            }
        }
    }
}

/**
 * A single inclusive range of UTF-8 bytes.
 */
public data class Utf8Range(
    /**
     * Start of byte range (inclusive).
     */
    public var start: UByte,
    /**
     * End of byte range (inclusive).
     */
    public var end: UByte,
) : Comparable<Utf8Range> {
    override fun compareTo(other: Utf8Range): Int {
        val startCmp = this.start.compareTo(other.start)
        if (startCmp != 0) return startCmp
        return this.end.compareTo(other.end)
    }

    /**
     * Returns true if and only if the given byte is in this range.
     */
    public fun matches(b: UByte): Boolean = this.start <= b && b <= this.end

    public fun fmt(f: StringBuilder) {
        if (this.start == this.end) {
            f.append("[${formatHexUpper(this.start.toUInt())}]")
        } else {
            f.append("[${formatHexUpper(this.start.toUInt())}-${formatHexUpper(this.end.toUInt())}]")
        }
    }

    override fun toString(): String = buildString { fmt(this) }

    public companion object {
        public fun new(start: UByte, end: UByte): Utf8Range = Utf8Range(start = start, end = end)
    }
}

/**
 * An iterator over ranges of matching UTF-8 byte sequences.
 *
 * The iteration represents an alternation of comprehensive byte sequences
 * that cover precisely the set of UTF-8 encoded scalar values.
 *
 * A byte sequence corresponds to one of the scalar values in the range given
 * if and only if it completely satisfies exactly one of the sequences of byte
 * ranges produced by this iterator.
 *
 * Each sequence of byte ranges accepts a unique set of bytes. That is, no two
 * sequences will accept the same bytes.
 *
 * # Example
 *
 * This shows how to test an arbitrary byte sequence against a range of
 * scalar values.
 *
 * ```kotlin
 * import regexsyntax.utf8.Utf8Sequence
 * import regexsyntax.utf8.Utf8Sequences
 *
 * fun matches(seqs: List<Utf8Sequence>, bytes: List<UByte>): Boolean {
 *     for (range in seqs) {
 *         if (range.matches(bytes)) {
 *             return true
 *         }
 *     }
 *     return false
 * }
 * // Test the basic multilingual plane.
 * val seqs = Utf8Sequences.new(0x0000u, 0xFFFFu).toList()
 * // UTF-8 encoding of `a`.
 * check(matches(seqs, listOf(0x61u)))
 * // UTF-8 encoding of snowman (`\u{2603}`).
 * check(matches(seqs, listOf(0xE2u, 0x98u, 0x83u)))
 * // UTF-8 encoding of `\u{10348}` (outside the BMP).
 * check(!matches(seqs, listOf(0xF0u, 0x90u, 0x8Du, 0x88u)))
 * // Tries to test against a UTF-8 encoding of a surrogate codepoint,
 * // which is invalid UTF-8, and therefore fails, despite the fact that
 * // the corresponding codepoint (0xD800) falls in the range given.
 * check(!matches(seqs, listOf(0xED, 0xA0, 0x80)))
 * // And fails against plain old invalid UTF-8.
 * check(!matches(seqs, listOf(0xFF, 0xFF)))
 * ```
 *
 * If this example seems circuitous, that's because it is! It's meant to be
 * illustrative. In practice, you could just try to decode your byte sequence
 * and compare it with the scalar value range directly. However, this is not
 * always possible (for example, in a byte based automaton).
 */
public open class Utf8Sequences internal constructor(
    private val rangeStack: MutableList<ScalarRange>,
) : Iterable<Utf8Sequence> {
    override fun iterator(): kotlin.collections.Iterator<Utf8Sequence> = object : AbstractIterator<Utf8Sequence>() {
        override fun computeNext() {
            when (val v = this@Utf8Sequences.next()) {
                null -> done()
                else -> setNext(v)
            }
        }
    }

    /**
     * reset resets the scalar value range.
     * Any existing state is cleared, but resources may be reused.
     *
     * N.B. Benchmarks say that this method is dubious.
     */
    public open fun reset(start: UInt, end: UInt) {
        this.rangeStack.clear()
        this.push(start, end)
    }

    internal open fun push(start: UInt, end: UInt) {
        this.rangeStack.add(ScalarRange(start = start, end = end))
    }

    public open fun next(): Utf8Sequence? {
        top@ while (true) {
            val r = this.rangeStack.removeLastOrNull() ?: break
            inner@ while (true) {
                val splitPair = r.split()
                if (splitPair != null) {
                    val (r1, r2) = splitPair
                    this.push(r2.start, r2.end)
                    r.start = r1.start
                    r.end = r1.end
                    continue@inner
                }
                if (!r.isValid()) {
                    continue@top
                }
                for (i in 1 until MAX_UTF8_BYTES) {
                    val max = maxScalarValue(i)
                    if (r.start <= max && max < r.end) {
                        this.push(max + 1u, r.end)
                        r.end = max
                        continue@inner
                    }
                }
                val asciiRange = r.asAscii()
                if (asciiRange != null) {
                    return Utf8Sequence.One(asciiRange)
                }
                for (i1 in 1 until MAX_UTF8_BYTES) {
                    val m = (1u shl (6 * i1)) - 1u
                    if ((r.start and m.inv()) != (r.end and m.inv())) {
                        if ((r.start and m) != 0u) {
                            this.push((r.start or m) + 1u, r.end)
                            r.end = r.start or m
                            continue@inner
                        }
                        if ((r.end and m) != m) {
                            this.push(r.end and m.inv(), r.end)
                            r.end = (r.end and m.inv()) - 1u
                            continue@inner
                        }
                    }
                }
                val start = UByteArray(MAX_UTF8_BYTES)
                val end = UByteArray(MAX_UTF8_BYTES)
                val n = r.encode(start, end)
                return Utf8Sequence.fromEncodedRange(
                    start.slice(0 until n),
                    end.slice(0 until n),
                )
            }
        }
        return null
    }

    public companion object {
        /**
         * Create a new iterator over UTF-8 byte ranges for the scalar value range
         * given.
         */
        public fun new(start: UInt, end: UInt): Utf8Sequences {
            val range = ScalarRange(start = start, end = end)
            return Utf8Sequences(rangeStack = mutableListOf(range))
        }
    }
}

internal data class ScalarRange(
    internal var start: UInt,
    internal var end: UInt,
) {
    internal fun fmt(f: StringBuilder) {
        f.append("ScalarRange(${this.start}, ${this.end})")
    }

    override fun toString(): String = buildString { fmt(this) }

    /**
     * split splits this range if it overlaps with a surrogate codepoint.
     *
     * Either or both ranges may be invalid.
     */
    internal fun split(): Pair<ScalarRange, ScalarRange>? =
        if (this.start < 0xE000u && this.end > 0xD7FFu) {
            Pair(ScalarRange(start = this.start, end = 0xD7FFu), ScalarRange(start = 0xE000u, end = this.end))
        } else {
            null
        }

    /**
     * isValid returns true if and only if start <= end.
     */
    internal fun isValid(): Boolean = this.start <= this.end

    /**
     * asAscii returns this range as a Utf8Range if and only if all scalar
     * values in this range can be encoded as a single byte.
     */
    internal fun asAscii(): Utf8Range? =
        if (this.isAscii()) {
            Utf8Range.new(this.start.toUByte(), this.end.toUByte())
        } else {
            null
        }

    /**
     * isAscii returns true if the range is ASCII only (i.e., takes a single
     * byte to encode any scalar value).
     */
    internal fun isAscii(): Boolean = this.isValid() && this.end <= 0x7fu

    /**
     * encode writes the UTF-8 encoding of the start and end of this range
     * to the corresponding destination slices, and returns the number of
     * bytes written.
     *
     * The slices should have room for at least 4 bytes.
     */
    internal fun encode(start: UByteArray, end: UByteArray): Int {
        val cs = scalarFromU32(this.start)!!
        val ce = scalarFromU32(this.end)!!
        val ss = encodeScalarUtf8(cs, start)
        val se = encodeScalarUtf8(ce, end)
        check(ss == se)
        return ss
    }
}

public fun maxScalarValue(nbytes: Int): UInt =
    when (nbytes) {
        1 -> 0x007Fu
        2 -> 0x07FFu
        3 -> 0xFFFFu
        4 -> 0x0010FFFFu
        else -> error("invalid UTF-8 byte sequence size")
    }
