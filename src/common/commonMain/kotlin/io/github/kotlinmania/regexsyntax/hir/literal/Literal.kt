// port-lint: source hir/literal.rs
package io.github.kotlinmania.regexsyntax.hir.literal

import io.github.kotlinmania.regexsyntax.debug.Bytes
import io.github.kotlinmania.regexsyntax.encodeScalarUtf8List
import io.github.kotlinmania.regexsyntax.hir.Class
import io.github.kotlinmania.regexsyntax.hir.ClassBytes
import io.github.kotlinmania.regexsyntax.hir.ClassUnicode
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.regexsyntax.normalizeUByteList
import io.github.kotlinmania.regexsyntax.rank.BYTE_FREQUENCIES
import io.github.kotlinmania.regexsyntax.scalarFromU32
import io.github.kotlinmania.rustlang.core.result.Result

// Provides literal extraction from `Hir` expressions.
//
// An [Extractor] pulls literals out of [Hir] expressions and returns a
// [Seq] of [Literal]s.
//
// The purpose of literal extraction is generally to provide avenues for
// optimizing regex searches. The main idea is that substring searches can be an
// order of magnitude faster than a regex search. Therefore, if one can execute
// a substring search to find candidate locations and only run the regex
// search at those locations, then it is possible for huge improvements in
// performance to be realized.

internal class DebugTuple(
    private val out: StringBuilder,
    private val name: String,
) {
    private val fields: MutableList<String> = mutableListOf()

    public fun field(value: Any?): DebugTuple =
        apply {
            fields.add(value.toString())
        }

    public fun finish() {
        if (fields.isEmpty()) {
            out.append(name)
        } else if (name.isEmpty()) {
            out.append("(${fields.joinToString(", ")})")
        } else {
            out.append("$name(${fields.joinToString(", ")})")
        }
    }
}

/**
 * Extracts prefix or suffix literal sequences from [Hir] expressions.
 */
public open class Extractor internal constructor(
    internal var kind: ExtractKind,
    internal var limitClass: Int,
    internal var limitRepeat: Int,
    internal var limitLiteralLen: Int,
    internal var limitTotal: Int,
) {
    /**
     * Execute the extractor and return a sequence of literals.
     */
    public open fun extract(hir: Hir): Seq =
        when (val kind = hir.kind()) {
            HirKind.Empty -> Seq.singleton(Literal.exact(emptyList()))
            is HirKind.Look -> Seq.singleton(Literal.exact(emptyList()))
            is HirKind.Literal -> {
                val seq = Seq.singleton(Literal.exact(kind.value.value))
                this.enforceLiteralLen(seq)
                seq
            }
            is HirKind.Class ->
                when (val cls = kind.value) {
                    is Class.Unicode -> this.extractClassUnicode(cls.value)
                    is Class.Bytes -> this.extractClassBytes(cls.value)
                }
            is HirKind.Repetition -> this.extractRepetition(kind.value)
            is HirKind.Capture -> this.extract(kind.value.sub)
            is HirKind.Concat ->
                when (this.kind) {
                    ExtractKind.Prefix -> this.extractConcat(kind.value)
                    ExtractKind.Suffix -> this.extractConcat(kind.value.asReversed())
                }
            is HirKind.Alternation -> this.extractAlternation(kind.value)
        }

    /**
     * Set the kind of literal sequence to extract from an [Hir] expression.
     */
    public open fun kind(kind: ExtractKind): Extractor {
        this.kind = kind
        return this
    }

    /**
     * Configure a limit on the length of the sequence that is permitted for
     * a character class. If a character class exceeds this limit, then the
     * sequence returned for it is infinite.
     */
    public open fun limitClass(limit: Int): Extractor {
        this.limitClass = limit
        return this
    }

    /**
     * Configure a limit on the total number of repetitions that is permitted
     * before literal extraction is stopped.
     */
    public open fun limitRepeat(limit: Int): Extractor {
        this.limitRepeat = limit
        return this
    }

    /**
     * Configure a limit on the maximum length of any literal in a sequence.
     */
    public open fun limitLiteralLen(limit: Int): Extractor {
        this.limitLiteralLen = limit
        return this
    }

    /**
     * Configure a limit on the total number of literals that will be
     * returned.
     */
    public open fun limitTotal(limit: Int): Extractor {
        this.limitTotal = limit
        return this
    }

    /**
     * Extract a sequence from the given concatenation. Sequences from each of
     * the child HIR expressions are combined via cross product.
     */
    internal fun extractConcat(hirs: List<Hir>): Seq {
        var seq = Seq.singleton(Literal.exact(emptyList()))
        for (hir in hirs) {
            if (seq.isInexact()) {
                break
            }
            seq = this.cross(seq, this.extract(hir))
        }
        return seq
    }

    /**
     * Extract a sequence from the given alternation.
     */
    internal fun extractAlternation(hirs: List<Hir>): Seq {
        var seq = Seq.empty()
        for (hir in hirs) {
            if (!seq.isFinite()) {
                break
            }
            seq = this.union(seq, this.extract(hir))
        }
        return seq
    }

    /**
     * Extract a sequence of literals from the given repetition.
     *
     * Examples:
     *   a* => [inexact(a), exact("")]
     *   a*? => [exact(""), inexact(a)]
     *   a+ => [inexact(a)]
     *   a{3} => [exact(aaa)]
     *   a{3,5} => [inexact(aaa)]
     */
    internal fun extractRepetition(rep: Repetition): Seq {
        val subseq = this.extract(rep.sub)
        return when {
            rep.min == 0u -> {
                if (rep.max != 1u) {
                    subseq.makeInexact()
                }
                val empty = Seq.singleton(Literal.exact(emptyList()))
                if (!rep.greedy) {
                    this.union(empty, subseq)
                } else {
                    this.union(subseq, empty)
                }
            }
            rep.max != null && rep.min == rep.max -> {
                check(rep.min > 0u)
                val limit = if (this.limitRepeat >= 0) this.limitRepeat.toUInt() else UInt.MAX_VALUE
                var seq = Seq.singleton(Literal.exact(emptyList()))
                val steps = minOf(rep.min, limit)
                var i = 0u
                while (i < steps) {
                    if (seq.isInexact()) {
                        break
                    }
                    seq = this.cross(seq, subseq.clone())
                    i += 1u
                }
                if (rep.min > Int.MAX_VALUE.toUInt() || rep.min > limit) {
                    seq.makeInexact()
                }
                seq
            }
            else -> {
                check(rep.min > 0u)
                val limit = if (this.limitRepeat >= 0) this.limitRepeat.toUInt() else UInt.MAX_VALUE
                var seq = Seq.singleton(Literal.exact(emptyList()))
                val steps = minOf(rep.min, limit)
                var i = 0u
                while (i < steps) {
                    if (seq.isInexact()) {
                        break
                    }
                    seq = this.cross(seq, subseq.clone())
                    i += 1u
                }
                seq.makeInexact()
                seq
            }
        }
    }

    /**
     * Convert the given Unicode class into a sequence of literals if the
     * class is small enough. If the class is too big, return an infinite
     * sequence.
     */
    internal fun extractClassUnicode(cls: ClassUnicode): Seq {
        if (this.classOverLimitUnicode(cls)) {
            return Seq.infinite()
        }
        val seq = Seq.empty()
        for (r in cls.iter()) {
            var cp = r.start()
            val end = r.end()
            while (true) {
                val scalar = scalarFromU32(cp)
                if (scalar != null) {
                    seq.push(Literal.fromScalar(scalar))
                }
                if (cp >= end) break
                cp += 1u
            }
        }
        this.enforceLiteralLen(seq)
        return seq
    }

    /**
     * Convert the given byte class into a sequence of literals if the class
     * is small enough. If the class is too big, return an infinite sequence.
     */
    internal fun extractClassBytes(cls: ClassBytes): Seq {
        if (this.classOverLimitBytes(cls)) {
            return Seq.infinite()
        }
        val seq = Seq.empty()
        for (r in cls.iter()) {
            var b = r.start().toInt()
            val end = r.end().toInt()
            while (b <= end) {
                seq.push(Literal.from(b.toUByte()))
                b += 1
            }
        }
        this.enforceLiteralLen(seq)
        return seq
    }

    /**
     * Returns true if the given Unicode class exceeds the configured limits
     * on this extractor.
     */
    internal fun classOverLimitUnicode(cls: ClassUnicode): Boolean {
        var count = 0
        for (r in cls.iter()) {
            if (count > this.limitClass) {
                return true
            }
            count += r.len()
        }
        return count > this.limitClass
    }

    /**
     * Returns true if the given byte class exceeds the configured limits on
     * this extractor.
     */
    internal fun classOverLimitBytes(cls: ClassBytes): Boolean {
        var count = 0
        for (r in cls.iter()) {
            if (count > this.limitClass) {
                return true
            }
            count += r.len()
        }
        return count > this.limitClass
    }

    /**
     * Compute the cross product of the two sequences if the result would be
     * within configured limits. Otherwise, make `seq2` infinite and cross the
     * infinite sequence with `seq1`.
     */
    internal fun cross(seq1: Seq, seq2: Seq): Seq {
        if (seq1.maxCrossLen(seq2)?.let { len -> len > this.limitTotal } ?: false) {
            seq2.makeInfinite()
        }
        if (this.kind == ExtractKind.Suffix) {
            seq1.crossReverse(seq2)
        } else {
            seq1.crossForward(seq2)
        }
        check(seq1.len()?.let { x -> x <= this.limitTotal } ?: true)
        this.enforceLiteralLen(seq1)
        return seq1
    }

    /**
     * Union the two sequences if the result would be within configured
     * limits. Otherwise, make `seq2` infinite and union the infinite sequence
     * with `seq1`.
     */
    internal fun union(seq1: Seq, seq2: Seq): Seq {
        if (seq1.maxUnionLen(seq2)?.let { len -> len > this.limitTotal } ?: false) {
            when (this.kind) {
                ExtractKind.Prefix -> {
                    seq1.keepFirstBytes(4)
                    seq2.keepFirstBytes(4)
                }
                ExtractKind.Suffix -> {
                    seq1.keepLastBytes(4)
                    seq2.keepLastBytes(4)
                }
            }
            seq1.dedup()
            seq2.dedup()
            if (seq1.maxUnionLen(seq2)?.let { len -> len > this.limitTotal } ?: false) {
                seq2.makeInfinite()
            }
        }
        seq1.union(seq2)
        check(seq1.len()?.let { x -> x <= this.limitTotal } ?: true)
        return seq1
    }

    /**
     * Applies the literal length limit to the given sequence. If none of the
     * literals in the sequence exceed the limit, then this is a no-op.
     */
    internal fun enforceLiteralLen(seq: Seq) {
        val len = this.limitLiteralLen
        when (this.kind) {
            ExtractKind.Prefix -> seq.keepFirstBytes(len)
            ExtractKind.Suffix -> seq.keepLastBytes(len)
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other || (
            other is Extractor &&
                this.kind == other.kind &&
                this.limitClass == other.limitClass &&
                this.limitRepeat == other.limitRepeat &&
                this.limitLiteralLen == other.limitLiteralLen &&
                this.limitTotal == other.limitTotal
            )

    override fun hashCode(): Int {
        var result = this.kind.hashCode()
        result = 31 * result + this.limitClass
        result = 31 * result + this.limitRepeat
        result = 31 * result + this.limitLiteralLen
        result = 31 * result + this.limitTotal
        return result
    }

    public companion object {
        /**
         * Create a new extractor with a default configuration.
         *
         * The extractor can be optionally configured before calling
         * [Extractor.extract] to get a literal sequence.
         */
        public fun new(): Extractor =
            Extractor(kind = ExtractKind.Prefix, limitClass = 10, limitRepeat = 10, limitLiteralLen = 100, limitTotal = 250)

        public fun default(): Extractor = Extractor.new()
    }
}

/**
 * The kind of literals to extract from an [Hir] expression.
 *
 * The default extraction kind is `Prefix`.
 */
public sealed class ExtractKind {
    /**
     * Extracts only prefix literals from a regex.
     */
    public data object Prefix : ExtractKind()

    /**
     * Extracts only suffix literals from a regex.
     */
    public data object Suffix : ExtractKind()

    /**
     * Returns true if this kind is the `Prefix` variant.
     */
    public open fun isPrefix(): Boolean = this == ExtractKind.Prefix

    /**
     * Returns true if this kind is the `Suffix` variant.
     */
    public open fun isSuffix(): Boolean = this == ExtractKind.Suffix

    public companion object {
        public fun default(): ExtractKind = ExtractKind.Prefix
    }
}

/**
 * A sequence of literals.
 */
public open class Seq internal constructor(
    internal var literals: MutableList<Literal>?,
) {
    internal fun clone(): Seq =
        Seq(literals = this.literals?.mapTo(mutableListOf()) { it.clone() })

    override fun equals(other: Any?): Boolean =
        this === other || (other is Seq && this.literals == other.literals)

    override fun hashCode(): Int = this.literals?.hashCode() ?: 0

    override fun toString(): String = buildString { fmt(this) }

    /**
     * If this is a finite sequence, return its members as a slice of
     * literals.
     */
    public open fun literals(): List<Literal>? = this.literals

    /**
     * Push a literal to the end of this sequence.
     */
    public open fun push(lit: Literal) {
        val lits = this.literals ?: return
        if (lits.lastOrNull() == lit) {
            return
        }
        lits.add(lit)
    }

    /**
     * Make all of the literals in this sequence inexact.
     */
    public open fun makeInexact() {
        val lits = this.literals ?: return
        for (lit in lits) {
            lit.makeInexact()
        }
    }

    /**
     * Converts this sequence to an infinite sequence.
     */
    public open fun makeInfinite() {
        this.literals = null
    }

    /**
     * Modify this sequence to contain the cross product between it and the
     * sequence given.
     */
    public open fun crossForward(other: Seq) {
        val (lits1, lits2) = this.crossPreamble(other) ?: return
        val newcap = lits1.size.saturatingMul(lits2.size)
        val selfLits = lits1.toList()
        lits1.clear()
        if (newcap > lits1.size) {
            (lits1 as? ArrayList<Literal>)?.ensureCapacity(newcap)
        }
        for (selflit in selfLits) {
            if (!selflit.isExact()) {
                lits1.add(selflit)
                continue
            }
            for (otherlit in lits2) {
                val newlit = Literal.exactWithCapacity(selflit.len() + otherlit.len())
                newlit.extend(selflit)
                newlit.extend(otherlit)
                if (!otherlit.isExact()) {
                    newlit.makeInexact()
                }
                lits1.add(newlit)
            }
        }
        lits2.clear()
        this.dedup()
    }

    /**
     * Modify this sequence to contain the cross product between it and
     * the sequence given, where the sequences are treated as suffixes
     * instead of prefixes.
     */
    public open fun crossReverse(other: Seq) {
        val (lits1, lits2) = this.crossPreamble(other) ?: return
        // Except for iterating `other` as outer loop and `self` as inner loop,
        // this mirrors `crossForward`: `self` holds suffixes and `other` is
        // prepended to those suffixes.
        val newcap = lits1.size.saturatingMul(lits2.size)
        val selflits = lits1.toList()
        lits1.clear()
        if (newcap > lits1.size) {
            (lits1 as? ArrayList<Literal>)?.ensureCapacity(newcap)
        }
        val otherlits = lits2.toList()
        lits2.clear()
        for ((i, otherlit) in otherlits.withIndex()) {
            for (selflit in selflits) {
                if (!selflit.isExact()) {
                    if (i == 0) {
                        lits1.add(selflit.clone())
                    }
                    continue
                }
                val newlit = Literal.exactWithCapacity(otherlit.len() + selflit.len())
                newlit.extend(otherlit)
                newlit.extend(selflit)
                if (!otherlit.isExact()) {
                    newlit.makeInexact()
                }
                lits1.add(newlit)
            }
        }
        this.dedup()
    }

    /**
     * A helper function for the preamble of both `crossForward` and `crossReverse`.
     */
    internal fun crossPreamble(other: Seq): Pair<MutableList<Literal>, MutableList<Literal>>? {
        val lits2 =
            when (val otherLits = other.literals) {
                null -> {
                    // If this seq contains the empty string and `other` accepts
                    // any literal, then this seq also accepts any literal.
                    if (this.minLiteralLen() == 0) {
                        this.makeInfinite()
                    } else {
                        this.makeInexact()
                    }
                    return null
                }
                else -> otherLits
            }
        val lits1 =
            when (val selfLits = this.literals) {
                null -> {
                    lits2.clear()
                    return null
                }
                else -> selfLits
            }
        return Pair(lits1, lits2)
    }

    /**
     * Unions the `other` sequence into this one.
     */
    public open fun union(other: Seq) {
        val lits2 =
            when (val otherLits = other.literals) {
                null -> {
                    this.makeInfinite()
                    return
                }
                else -> {
                    val drained = otherLits.toList()
                    otherLits.clear()
                    drained
                }
            }
        val lits1 = this.literals ?: return
        lits1.addAll(lits2)
        this.dedup()
    }

    /**
     * Unions the `other` sequence into this one by splicing the `other`
     * sequence at the position of the first zero-length literal.
     */
    public open fun unionIntoEmpty(other: Seq) {
        val lits2 =
            other.literals?.let { lits ->
                val drained = lits.toList()
                lits.clear()
                drained
            }
        val lits1 = this.literals ?: return
        val firstEmpty = lits1.indexOfFirst { it.isEmpty() }
        if (firstEmpty < 0) {
            return
        }
        if (lits2 == null) {
            this.literals = null
            return
        }
        // Clearing out empties before splicing ensures newly spliced empties are
        // retained while preserving `firstEmpty` as the insertion index.
        lits1.removeAll { it.isEmpty() }
        lits1.addAll(firstEmpty, lits2)
        this.dedup()
    }

    /**
     * Deduplicate adjacent equivalent literals in this sequence.
     */
    public open fun dedup() {
        val lits = this.literals ?: return
        if (lits.size <= 1) return
        var write = 1
        for (read in 1 until lits.size) {
            val prev = lits[write - 1]
            val curr = lits[read]
            if (prev.asBytes() == curr.asBytes()) {
                if (prev.isExact() != curr.isExact()) {
                    prev.makeInexact()
                }
            } else {
                if (write != read) {
                    lits[write] = curr
                }
                write += 1
            }
        }
        if (write < lits.size) {
            lits.subList(write, lits.size).clear()
        }
    }

    /**
     * Sorts this sequence of literals lexicographically.
     */
    public open fun sort() {
        this.literals?.sort()
    }

    /**
     * Reverses all of the literals in this sequence.
     */
    public open fun reverseLiterals() {
        val lits = this.literals ?: return
        for (lit in lits) {
            lit.reverse()
        }
    }

    /**
     * Shrinks this seq to its minimal size while respecting the preference
     * order of its literals.
     */
    public open fun minimizeByPreference() {
        val lits = this.literals ?: return
        PreferenceTrie.minimize(lits, false)
    }

    /**
     * Trims all literals in this seq such that only the first `len` bytes
     * remain.
     */
    public open fun keepFirstBytes(len: Int) {
        val lits = this.literals ?: return
        for (m in lits) {
            m.keepFirstBytes(len)
        }
    }

    /**
     * Trims all literals in this seq such that only the last `len` bytes
     * remain.
     */
    public open fun keepLastBytes(len: Int) {
        val lits = this.literals ?: return
        for (m in lits) {
            m.keepLastBytes(len)
        }
    }

    /**
     * Returns true if this sequence is finite.
     */
    public open fun isFinite(): Boolean = this.literals != null

    /**
     * Returns true if and only if this sequence is finite and empty.
     */
    public open fun isEmpty(): Boolean = this.len() == 0

    /**
     * Returns the number of literals in this sequence if the sequence is
     * finite. If the sequence is infinite, then `None` is returned.
     */
    public open fun len(): Int? = this.literals?.size

    /**
     * Returns true if and only if all literals in this sequence are exact.
     */
    public open fun isExact(): Boolean = this.literals()?.all { it.isExact() } ?: false

    /**
     * Returns true if and only if all literals in this sequence are inexact.
     */
    public open fun isInexact(): Boolean = this.literals()?.all { !it.isExact() } ?: true

    /**
     * Return the maximum length of the sequence that would result from
     * unioning `self` with `other`. If either set is infinite, then this
     * returns `None`.
     */
    public open fun maxUnionLen(other: Seq): Int? {
        val len1 = this.len() ?: return null
        val len2 = other.len() ?: return null
        return len1.saturatingAdd(len2)
    }

    /**
     * Return the maximum length of the sequence that would result from the
     * cross product of `self` with `other`. If either set is infinite, then
     * this returns `None`.
     */
    public open fun maxCrossLen(other: Seq): Int? {
        val len1 = this.len() ?: return null
        val len2 = other.len() ?: return null
        return len1.saturatingMul(len2)
    }

    /**
     * Returns the length of the shortest literal in this sequence.
     */
    public open fun minLiteralLen(): Int? =
        this.literals?.minOfOrNull { it.len() }

    /**
     * Returns the length of the longest literal in this sequence.
     */
    public open fun maxLiteralLen(): Int? =
        this.literals?.maxOfOrNull { it.len() }

    /**
     * Returns the longest common prefix from this seq.
     */
    public open fun longestCommonPrefix(): List<UByte>? {
        val lits = this.literals ?: return null
        if (lits.isEmpty()) {
            return null
        }
        val base = lits[0].asBytes()
        var len = base.size
        for (idx in 1 until lits.size) {
            val bytes = lits[idx].asBytes()
            val limit = minOf(bytes.size, len)
            var matched = 0
            while (matched < limit && bytes[matched] == base[matched]) {
                matched += 1
            }
            len = matched
            if (len == 0) {
                return emptyList()
            }
        }
        return base.subList(0, len).toList()
    }

    /**
     * Returns the longest common suffix from this seq.
     */
    public open fun longestCommonSuffix(): List<UByte>? {
        val lits = this.literals ?: return null
        if (lits.isEmpty()) {
            return null
        }
        val base = lits[0].asBytes()
        var len = base.size
        for (idx in 1 until lits.size) {
            val bytes = lits[idx].asBytes()
            val limit = minOf(bytes.size, len)
            var matched = 0
            while (matched < limit && bytes[bytes.size - 1 - matched] == base[base.size - 1 - matched]) {
                matched += 1
            }
            len = matched
            if (len == 0) {
                return emptyList()
            }
        }
        return base.subList(base.size - len, base.size).toList()
    }

    /**
     * Optimizes this seq while treating its literals as prefixes and
     * respecting the preference order of its literals.
     */
    public open fun optimizeForPrefixByPreference() {
        this.optimizeByPreference(true)
    }

    /**
     * Optimizes this seq while treating its literals as suffixes and
     * respecting the preference order of its literals.
     */
    public open fun optimizeForSuffixByPreference() {
        this.optimizeByPreference(false)
    }

    internal fun optimizeByPreference(prefix: Boolean) {
        val attempts = listOf(Pair(5, 10), Pair(4, 10), Pair(3, 64), Pair(2, 64), Pair(1, 10))
        val origlen = this.len() ?: return
        if (this.minLiteralLen()?.let { it == 0 } ?: false) {
            this.makeInfinite()
            return
        }
        if (prefix) {
            this.literals?.let { lits -> PreferenceTrie.minimize(lits, true) }
        }
        val fix =
            if (prefix) {
                this.longestCommonPrefix()
            } else {
                this.longestCommonSuffix()
            }
        if (fix != null) {
            if (prefix && origlen > 1 && fix.size in 1..3 && rank(fix[0]) < 200u.toUByte()) {
                this.keepFirstBytes(1)
                this.dedup()
                return
            }
            val isfast = this.isExact() && (this.len()?.let { it <= 16 } ?: false)
            val usefix = fix.size > 4 || (fix.size > 1 && !isfast)
            if (usefix) {
                if (prefix) {
                    this.keepFirstBytes(fix.size)
                } else {
                    this.keepLastBytes(fix.size)
                }
                this.dedup()
                check(this.len() == 1)
            }
        }
        val exact = if (this.isExact()) this.clone() else null
        for ((keep, limit) in attempts) {
            val len2 = this.len() ?: break
            if (len2 <= limit) {
                break
            }
            if (prefix) {
                this.keepFirstBytes(keep)
            } else {
                this.keepLastBytes(keep)
            }
            if (prefix) {
                this.literals?.let { lits -> PreferenceTrie.minimize(lits, true) }
            }
        }
        this.literals()?.let { lits ->
            if (lits.any { it.isPoisonous() }) {
                this.makeInfinite()
            }
        }
        if (exact != null) {
            if (!this.isFinite()) {
                this.literals = exact.literals
                return
            }
            if (this.minLiteralLen()?.let { it <= 2 } ?: true) {
                this.literals = exact.literals
                return
            }
            if (this.len()?.let { it > 64 } ?: true) {
                this.literals = exact.literals
                return
            }
        }
    }

    public open fun fmt(f: StringBuilder) {
        f.append("Seq")
        when (val lits = this.literals()) {
            null -> f.append("[∞]")
            else -> f.append(lits.joinToString(", ", "[", "]"))
        }
    }

    public companion object {
        /**
         * Returns an empty sequence.
         */
        public fun empty(): Seq = Seq(literals = mutableListOf())

        /**
         * Returns a sequence of literals without a finite size and may contain
         * any literal.
         */
        public fun infinite(): Seq = Seq(literals = null)

        /**
         * Returns a sequence containing a single literal.
         */
        public fun singleton(lit: Literal): Seq = Seq(literals = mutableListOf(lit))

        /**
         * Returns a sequence of exact literals from the given byte strings.
         */
        public fun new(items: List<List<UByte>>): Seq =
            fromIter(items.map { Literal.exact(it.normalizeUByteList()) })

        internal fun fromStrings(items: Iterable<String>): Seq =
            fromIter(items.map { s -> Literal.exact(s.encodeToByteArray().map { it.toUByte() }) })

        internal fun fromIter(it: Iterable<Literal>): Seq {
            val seq = Seq.empty()
            for (literal in it) {
                seq.push(literal)
            }
            return seq
        }
    }
}

/**
 * A single literal extracted from an [Hir] expression.
 */
public open class Literal internal constructor(
    internal var bytes: MutableList<UByte>,
    internal var exact: Boolean,
) : Comparable<Literal> {
    internal fun clone(): Literal = Literal(bytes = this.bytes.toMutableList(), exact = this.exact)

    override fun compareTo(other: Literal): Int {
        val minSize = minOf(this.bytes.size, other.bytes.size)
        for (i in 0 until minSize) {
            val cmp = this.bytes[i].compareTo(other.bytes[i])
            if (cmp != 0) return cmp
        }
        return this.bytes.size.compareTo(other.bytes.size)
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is Literal && this.exact == other.exact && this.bytes == other.bytes)

    override fun hashCode(): Int {
        var result = this.bytes.hashCode()
        result = 31 * result + this.exact.hashCode()
        return result
    }

    override fun toString(): String = buildString { fmt(this) }

    /**
     * Returns the bytes in this literal.
     */
    public open fun asBytes(): List<UByte> = this.bytes

    /**
     * Yields ownership of the bytes inside this literal.
     */
    public open fun intoBytes(): List<UByte> = this.bytes

    /**
     * Returns the length of this literal in bytes.
     */
    public open fun len(): Int = this.bytes.size

    /**
     * Returns true if and only if this literal has zero bytes.
     */
    public open fun isEmpty(): Boolean = this.len() == 0

    /**
     * Returns true if and only if this literal is exact.
     */
    public open fun isExact(): Boolean = this.exact

    /**
     * Marks this literal as inexact.
     */
    public open fun makeInexact() {
        this.exact = false
    }

    /**
     * Reverse the bytes in this literal.
     */
    public open fun reverse() {
        this.bytes.reverse()
    }

    /**
     * Extend this literal with the literal given.
     *
     * If this literal is inexact, then this is a no-op.
     */
    public open fun extend(lit: Literal) {
        if (!this.isExact()) {
            return
        }
        this.bytes.addAll(lit.bytes)
    }

    /**
     * Trims this literal such that only the first `len` bytes remain.
     */
    public open fun keepFirstBytes(len: Int) {
        if (len >= this.len()) {
            return
        }
        this.makeInexact()
        this.bytes.subList(len, this.bytes.size).clear()
    }

    /**
     * Trims this literal such that only the last `len` bytes remain.
     */
    public open fun keepLastBytes(len: Int) {
        if (len >= this.len()) {
            return
        }
        this.makeInexact()
        this.bytes.subList(0, this.len() - len).clear()
    }

    /**
     * Returns true if it is believed that this literal is likely to match very
     * frequently, and is thus not a good candidate for a prefilter.
     */
    public open fun isPoisonous(): Boolean =
        this.isEmpty() || (this.len() == 1 && rank(this.bytes[0]) >= 250u.toUByte())

    public open fun asRef(): List<UByte> = this.bytes

    public open fun fmt(f: StringBuilder) {
        val tag = if (this.exact) "E" else "I"
        DebugTuple(f, tag).field(Bytes(this.bytes)).finish()
    }

    public companion object {
        internal fun exactWithCapacity(capacity: Int): Literal =
            Literal(bytes = ArrayList(capacity), exact = true)

        /**
         * Returns a new exact literal containing the bytes given.
         */
        public fun exact(bytes: List<UByte>): Literal =
            Literal(bytes = bytes.normalizeUByteList().toMutableList(), exact = true)

        /**
         * Returns a new inexact literal containing the bytes given.
         */
        public fun inexact(bytes: List<UByte>): Literal =
            Literal(bytes = bytes.normalizeUByteList().toMutableList(), exact = false)

        internal fun exact(text: String): Literal =
            Literal(bytes = text.encodeToByteArray().mapTo(mutableListOf()) { it.toUByte() }, exact = true)

        internal fun inexact(text: String): Literal =
            Literal(bytes = text.encodeToByteArray().mapTo(mutableListOf()) { it.toUByte() }, exact = false)

        public fun from(byte: UByte): Literal = Literal.exact(listOf(byte))

        internal fun fromScalar(ch: UInt): Literal = Literal.exact(encodeScalarUtf8List(ch))
    }
}

/**
 * A preference trie that rejects literals that will never be reached when
 * executing a leftmost-first preference search.
 */
internal class PreferenceTrie(
    internal var states: MutableList<State>,
    internal var matches: MutableList<Int?>,
    internal var nextLiteralIndex: Int,
) {
    /**
     * Returns `Ok` if the given byte string is accepted into this trie and
     * `Err` otherwise. The index for the success case corresponds to the
     * index of the literal added. The index for the error case corresponds to
     * the 1-based index of the literal already in the trie that prevented the
     * given byte string from being added.
     */
    internal fun insert(bytes: List<UByte>): Result<Int, Int> {
        var prev = this.root()
        val rootMatch = this.matches[prev]
        if (rootMatch != null) {
            return Result.Err(rootMatch)
        }
        for (b in bytes) {
            val trans = this.states[prev].trans
            val foundIdx = trans.binarySearchBy(b) { it.first }
            if (foundIdx >= 0) {
                prev = trans[foundIdx].second
                val stateMatch = this.matches[prev]
                if (stateMatch != null) {
                    return Result.Err(stateMatch)
                }
            } else {
                val insertAt = -(foundIdx + 1)
                val next = this.createState()
                this.states[prev].trans.add(insertAt, Pair(b, next))
                prev = next
            }
        }
        val idx2 = this.nextLiteralIndex
        this.nextLiteralIndex += 1
        this.matches[prev] = idx2
        return Result.Ok(idx2)
    }

    internal fun root(): Int =
        if (this.states.isNotEmpty()) {
            0
        } else {
            this.createState()
        }

    internal fun createState(): Int {
        val id = this.states.size
        this.states.add(State.default())
        this.matches.add(null)
        return id
    }

    internal companion object {
        internal fun minimize(literals: MutableList<Literal>, keepExact: Boolean) {
            val trie = PreferenceTrie(states = mutableListOf(), matches = mutableListOf(), nextLiteralIndex = 1)
            val makeInexact = mutableListOf<Int>()
            val it = literals.iterator()
            while (it.hasNext()) {
                val lit = it.next()
                when (val res = trie.insert(lit.asBytes())) {
                    is Result.Ok -> {}
                    is Result.Err -> {
                        if (!keepExact) {
                            makeInexact.add(res.error - 1)
                        }
                        it.remove()
                    }
                }
            }
            for (i in makeInexact) {
                literals[i].makeInexact()
            }
        }
    }
}

internal class State(
    internal var trans: MutableList<Pair<UByte, Int>>,
) {
    internal companion object {
        internal fun default(): State = State(trans = mutableListOf())
    }
}

/**
 * Returns the "rank" of the given byte.
 */
public fun rank(byte: UByte): UByte = BYTE_FREQUENCIES[byte.toInt() and 0xFF]

private fun Int.saturatingAdd(other: Int): Int {
    val sum = this.toLong() + other.toLong()
    return when {
        sum > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
        sum < Int.MIN_VALUE.toLong() -> Int.MIN_VALUE
        else -> sum.toInt()
    }
}

private fun Int.saturatingMul(other: Int): Int {
    val prod = this.toLong() * other.toLong()
    return when {
        prod > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
        prod < Int.MIN_VALUE.toLong() -> Int.MIN_VALUE
        else -> prod.toInt()
    }
}
