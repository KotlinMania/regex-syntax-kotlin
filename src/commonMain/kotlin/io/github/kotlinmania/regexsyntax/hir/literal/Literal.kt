// port-lint: source hir/literal.rs
package io.github.kotlinmania.regexsyntax.hir.literal

import io.github.kotlinmania.regexsyntax.debug.Bytes as DebugBytes
import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Class
import io.github.kotlinmania.regexsyntax.hir.ClassBytes
import io.github.kotlinmania.regexsyntax.hir.ClassUnicode
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.regexsyntax.hir.codepointUtf8Len
import io.github.kotlinmania.regexsyntax.hir.encodeUtf8
import io.github.kotlinmania.regexsyntax.rank.BYTE_FREQUENCIES

/**
 * Provides literal extraction from [Hir] expressions.
 *
 * An [Extractor] pulls literals out of [Hir] expressions and returns a [Seq]
 * of [Literal]s.
 */
class Extractor private constructor(
    private var kind: ExtractKind,
    private var limitClass: Int,
    private var limitRepeat: Int,
    private var limitLiteralLen: Int,
    private var limitTotal: Int,
) {
    companion object {
        /** Create a new extractor with a default configuration. */
        fun new(): Extractor = Extractor(
            kind = ExtractKind.Prefix,
            limitClass = 10,
            limitRepeat = 10,
            limitLiteralLen = 100,
            limitTotal = 250,
        )
    }

    /** Execute the extractor and return a sequence of literals. */
    fun extract(hir: Hir): Seq {
        return when (val kind = hir.kind()) {
            is HirKind.Empty,
            is HirKind.Look -> Seq.singleton(Literal.exact(byteArrayOf()))
            is HirKind.Literal -> {
                val seq = Seq.singleton(Literal.exact(kind.value.bytes.copyOf()))
                enforceLiteralLen(seq)
                seq
            }
            is HirKind.Class -> when (val cls = kind.value) {
                is Class.Unicode -> extractClassUnicode(cls.value)
                is Class.Bytes -> extractClassBytes(cls.value)
            }
            is HirKind.Repetition -> extractRepetition(kind.value)
            is HirKind.Capture -> extract(kind.value.sub)
            is HirKind.Concat -> when (this.kind) {
                ExtractKind.Prefix -> extractConcat(kind.items)
                ExtractKind.Suffix -> extractConcat(kind.items.asReversed())
            }
            is HirKind.Alternation -> extractAlternation(kind.items)
        }
    }

    /**
     * Set the kind of literal sequence to extract from a [Hir] expression.
     *
     * The default is to extract prefixes, but suffixes can be selected
     * instead.
     */
    fun kind(kind: ExtractKind): Extractor {
        this.kind = kind
        return this
    }

    /** Configure a limit on the length of the sequence permitted for a character class. */
    fun limitClass(limit: Int): Extractor {
        this.limitClass = limit
        return this
    }

    /** Configure a limit on the total number of repetitions permitted before extraction stops. */
    fun limitRepeat(limit: Int): Extractor {
        this.limitRepeat = limit
        return this
    }

    /** Configure a limit on the maximum length of any literal in a sequence. */
    fun limitLiteralLen(limit: Int): Extractor {
        this.limitLiteralLen = limit
        return this
    }

    /** Configure a limit on the total number of literals returned. */
    fun limitTotal(limit: Int): Extractor {
        this.limitTotal = limit
        return this
    }

    /** Extract a sequence from the given concatenation. */
    private fun extractConcat(hirs: Iterable<Hir>): Seq {
        var seq = Seq.singleton(Literal.exact(byteArrayOf()))
        for (hir in hirs) {
            if (seq.isInexact()) break
            seq = cross(seq, extract(hir))
        }
        return seq
    }

    /** Extract a sequence from the given alternation. */
    private fun extractAlternation(hirs: Iterable<Hir>): Seq {
        var seq = Seq.empty()
        for (hir in hirs) {
            if (!seq.isFinite()) break
            seq = union(seq, extract(hir))
        }
        return seq
    }

    /** Extract a sequence of literals from the given repetition. */
    private fun extractRepetition(rep: Repetition): Seq {
        val subseq = extract(rep.sub)
        if (rep.min == 0u) {
            val repeated = subseq
            if (rep.max != 1u) repeated.makeInexact()
            val empty = Seq.singleton(Literal.exact(byteArrayOf()))
            return if (rep.greedy) {
                union(repeated, empty)
            } else {
                union(empty, repeated)
            }
        }
        val limit = limitRepeat.toUInt()
        var seq = Seq.singleton(Literal.exact(byteArrayOf()))
        val repeat = if (rep.min > limit) limitRepeat else rep.min.toInt()
        for (i in 0 until repeat) {
            if (seq.isInexact()) break
            seq = cross(seq, subseq.clone())
        }
        if (rep.max == null || rep.max != rep.min || rep.min > limit) {
            seq.makeInexact()
        }
        return seq
    }

    /** Convert the given Unicode class into a sequence of literals if small enough. */
    private fun extractClassUnicode(cls: ClassUnicode): Seq {
        if (classOverLimitUnicode(cls)) return Seq.infinite()
        val seq = Seq.empty()
        val iter = cls.iter()
        while (iter.hasNext()) {
            val range = iter.next()
            var codepoint = range.start()
            val end = range.end()
            while (codepoint <= end) {
                seq.push(Literal.fromCodepoint(codepoint))
                codepoint = if (codepoint == 0xD7FF) 0xE000 else codepoint + 1
            }
        }
        enforceLiteralLen(seq)
        return seq
    }

    /** Convert the given byte class into a sequence of literals if small enough. */
    private fun extractClassBytes(cls: ClassBytes): Seq {
        if (classOverLimitBytes(cls)) return Seq.infinite()
        val seq = Seq.empty()
        val iter = cls.iter()
        while (iter.hasNext()) {
            val range = iter.next()
            var byte = range.start().toInt() and 0xFF
            val end = range.end().toInt() and 0xFF
            while (byte <= end) {
                seq.push(Literal.fromByte(byte.toByte()))
                byte++
            }
        }
        enforceLiteralLen(seq)
        return seq
    }

    /** Returns true if the given Unicode class exceeds the configured limits. */
    private fun classOverLimitUnicode(cls: ClassUnicode): Boolean {
        var count = 0
        val iter = cls.iter()
        while (iter.hasNext()) {
            if (count > limitClass) return true
            count += iter.next().len()
        }
        return count > limitClass
    }

    /** Returns true if the given byte class exceeds the configured limits. */
    private fun classOverLimitBytes(cls: ClassBytes): Boolean {
        var count = 0
        val iter = cls.iter()
        while (iter.hasNext()) {
            if (count > limitClass) return true
            count += iter.next().len()
        }
        return count > limitClass
    }

    /** Compute the cross product of the two sequences if within configured limits. */
    private fun cross(seq1: Seq, seq2: Seq): Seq {
        if (seq1.maxCrossLen(seq2)?.let { it > limitTotal } == true) {
            seq2.makeInfinite()
        }
        if (kind == ExtractKind.Suffix) {
            seq1.crossReverse(seq2)
        } else {
            seq1.crossForward(seq2)
        }
        check(seq1.len()?.let { it <= limitTotal } ?: true)
        enforceLiteralLen(seq1)
        return seq1
    }

    /** Union two sequences if within configured limits. */
    private fun union(seq1: Seq, seq2: Seq): Seq {
        if (seq1.maxUnionLen(seq2)?.let { it > limitTotal } == true) {
            if (kind == ExtractKind.Prefix) {
                seq1.keepFirstBytes(4)
                seq2.keepFirstBytes(4)
            } else {
                seq1.keepLastBytes(4)
                seq2.keepLastBytes(4)
            }
            seq1.dedup()
            seq2.dedup()
            if (seq1.maxUnionLen(seq2)?.let { it > limitTotal } == true) {
                seq2.makeInfinite()
            }
        }
        seq1.union(seq2)
        check(seq1.len()?.let { it <= limitTotal } ?: true)
        return seq1
    }

    /** Applies the literal length limit to the given sequence. */
    private fun enforceLiteralLen(seq: Seq) {
        if (kind == ExtractKind.Prefix) {
            seq.keepFirstBytes(limitLiteralLen)
        } else {
            seq.keepLastBytes(limitLiteralLen)
        }
    }
}

/** The kind of literals to extract from a [Hir] expression. */
enum class ExtractKind {
    /** Extracts only prefix literals from a regex. */
    Prefix,
    /** Extracts only suffix literals from a regex. */
    Suffix;

    /** Returns true if this kind is the [Prefix] variant. */
    fun isPrefix(): Boolean = this == Prefix

    /** Returns true if this kind is the [Suffix] variant. */
    fun isSuffix(): Boolean = this == Suffix
}

/**
 * A sequence of literals.
 *
 * A [Seq] is very much like a set in that it represents a union of its
 * members. It is also unlike a set in that multiple identical literals may
 * appear, and the order of the literals in the [Seq] matters.
 */
class Seq private constructor(
    private var literals: MutableList<Literal>?,
) {
    companion object {
        /** Returns an empty sequence. */
        fun empty(): Seq = Seq(mutableListOf())

        /** Returns a sequence of literals without a finite size and may contain any literal. */
        fun infinite(): Seq = Seq(null)

        /** Returns a sequence containing a single literal. */
        fun singleton(lit: Literal): Seq = Seq(mutableListOf(lit))

        /** Returns a sequence of exact literals from the given byte strings. */
        fun new(bytes: Iterable<ByteArray>): Seq {
            val seq = empty()
            for (literalBytes in bytes) {
                seq.push(Literal.exact(literalBytes))
            }
            return seq
        }

        /** Build a sequence from extracted literals. */
        fun fromIter(literals: Iterable<Literal>): Seq {
            val seq = empty()
            for (literal in literals) {
                seq.push(literal)
            }
            return seq
        }
    }

    /** If this is a finite sequence, return its members. */
    fun literals(): List<Literal>? = literals

    /** Push a literal to the end of this sequence. */
    fun push(lit: Literal) {
        val lits = literals ?: return
        if (lits.lastOrNull() == lit) return
        lits.add(lit)
    }

    /** Make all of the literals in this sequence inexact. */
    fun makeInexact() {
        val lits = literals ?: return
        for (lit in lits) {
            lit.makeInexact()
        }
    }

    /** Converts this sequence to an infinite sequence. */
    fun makeInfinite() {
        literals = null
    }

    /** Modify this sequence to contain the forward cross product with [other]. */
    fun crossForward(other: Seq) {
        val lits2 = other.literals
        if (lits2 == null) {
            if (minLiteralLen() == 0) {
                makeInfinite()
            } else {
                makeInexact()
            }
            return
        }
        val lits1 = literals
        if (lits1 == null) {
            lits2.clear()
            return
        }
        val selfLits = lits1.toList()
        lits1.clear()
        for (selfLit in selfLits) {
            if (!selfLit.isExact()) {
                lits1.add(selfLit)
                continue
            }
            for (otherLit in lits2) {
                val newLit = Literal.exact(ByteArray(0))
                newLit.extend(selfLit)
                newLit.extend(otherLit)
                if (!otherLit.isExact()) newLit.makeInexact()
                lits1.add(newLit)
            }
        }
        lits2.clear()
        dedup()
    }

    /** Modify this sequence to contain the reverse cross product with [other]. */
    fun crossReverse(other: Seq) {
        val lits2 = other.literals
        if (lits2 == null) {
            if (minLiteralLen() == 0) {
                makeInfinite()
            } else {
                makeInexact()
            }
            return
        }
        val lits1 = literals
        if (lits1 == null) {
            lits2.clear()
            return
        }
        val selfLits = lits1.toList()
        lits1.clear()
        for ((i, otherLit) in lits2.withIndex()) {
            for (selfLit in selfLits) {
                if (!selfLit.isExact()) {
                    if (i == 0) lits1.add(selfLit.clone())
                    continue
                }
                val newLit = Literal.exact(ByteArray(0))
                newLit.extend(otherLit)
                newLit.extend(selfLit)
                if (!otherLit.isExact()) newLit.makeInexact()
                lits1.add(newLit)
            }
        }
        lits2.clear()
        dedup()
    }

    /** Unions [other] into this sequence. */
    fun union(other: Seq) {
        val lits2 = other.literals
        if (lits2 == null) {
            makeInfinite()
            return
        }
        val drained = lits2.toList()
        lits2.clear()
        val lits1 = literals ?: return
        lits1.addAll(drained)
        dedup()
    }

    /** Unions [other] into this one at the position of the first zero-length literal. */
    fun unionIntoEmpty(other: Seq) {
        val otherLits = other.literals
        val drained = if (otherLits == null) {
            null
        } else {
            val copy = otherLits.toList()
            otherLits.clear()
            copy
        }
        val lits1 = literals ?: return
        val firstEmpty = lits1.indexOfFirst { it.isEmpty() }
        if (firstEmpty < 0) return
        if (drained == null) {
            literals = null
            return
        }
        lits1.removeAll { it.isEmpty() }
        lits1.addAll(firstEmpty, drained)
        dedup()
    }

    /** Deduplicate adjacent equivalent literals in this sequence. */
    fun dedup() {
        val lits = literals ?: return
        if (lits.size <= 1) return
        val deduped = ArrayList<Literal>(lits.size)
        for (lit in lits) {
            val last = deduped.lastOrNull()
            if (last != null && last.bytesEquals(lit)) {
                if (last.isExact() != lit.isExact()) {
                    last.makeInexact()
                }
            } else {
                deduped.add(lit)
            }
        }
        lits.clear()
        lits.addAll(deduped)
    }

    /** Sorts this sequence of literals lexicographically. */
    fun sort() {
        literals?.sort()
    }

    /** Reverses all of the literals in this sequence. */
    fun reverseLiterals() {
        literals?.forEach { it.reverse() }
    }

    /** Shrinks this seq to its minimal size while respecting preference order. */
    fun minimizeByPreference() {
        literals?.let { PreferenceTrie.minimize(it, keepExact = false) }
    }

    /** Trims all literals in this seq such that only the first [len] bytes remain. */
    fun keepFirstBytes(len: Int) {
        literals?.forEach { it.keepFirstBytes(len) }
    }

    /** Trims all literals in this seq such that only the last [len] bytes remain. */
    fun keepLastBytes(len: Int) {
        literals?.forEach { it.keepLastBytes(len) }
    }

    /** Returns true if this sequence is finite. */
    fun isFinite(): Boolean = literals != null

    /** Returns true if and only if this sequence is finite and empty. */
    fun isEmpty(): Boolean = len() == 0

    /** Returns the number of literals in this sequence if finite. */
    fun len(): Int? = literals?.size

    /** Returns true if and only if all literals in this sequence are exact. */
    fun isExact(): Boolean = literals?.all { it.isExact() } ?: false

    /** Returns true if and only if all literals in this sequence are inexact. */
    fun isInexact(): Boolean = literals?.all { !it.isExact() } ?: true

    /** Return the maximum length of the sequence resulting from union. */
    fun maxUnionLen(other: Seq): Int? {
        val len1 = len() ?: return null
        val len2 = other.len() ?: return null
        return saturatingAdd(len1, len2)
    }

    /** Return the maximum length of the sequence resulting from cross product. */
    fun maxCrossLen(other: Seq): Int? {
        val len1 = len() ?: return null
        val len2 = other.len() ?: return null
        return saturatingMul(len1, len2)
    }

    /** Returns the length of the shortest literal in this sequence. */
    fun minLiteralLen(): Int? = literals?.minOfOrNull { it.len() }

    /** Returns the length of the longest literal in this sequence. */
    fun maxLiteralLen(): Int? = literals?.maxOfOrNull { it.len() }

    /** Returns the longest common prefix from this seq. */
    fun longestCommonPrefix(): ByteArray? {
        val lits = literals ?: return null
        if (lits.isEmpty()) return null
        val base = lits[0].asBytes()
        var len = base.size
        for (lit in lits.drop(1)) {
            val bytes = lit.asBytes()
            var common = 0
            val max = minOf(len, bytes.size)
            while (common < max && bytes[common] == base[common]) {
                common++
            }
            len = common
            if (len == 0) return ByteArray(0)
        }
        return base.copyOfRange(0, len)
    }

    /** Returns the longest common suffix from this seq. */
    fun longestCommonSuffix(): ByteArray? {
        val lits = literals ?: return null
        if (lits.isEmpty()) return null
        val base = lits[0].asBytes()
        var len = base.size
        for (lit in lits.drop(1)) {
            val bytes = lit.asBytes()
            var common = 0
            val max = minOf(len, bytes.size)
            while (
                common < max &&
                bytes[bytes.size - 1 - common] == base[base.size - 1 - common]
            ) {
                common++
            }
            len = common
            if (len == 0) return ByteArray(0)
        }
        return base.copyOfRange(base.size - len, base.size)
    }

    /** Optimizes this seq while treating its literals as prefixes. */
    fun optimizeForPrefixByPreference() {
        optimizeByPreference(prefix = true)
    }

    /** Optimizes this seq while treating its literals as suffixes. */
    fun optimizeForSuffixByPreference() {
        optimizeByPreference(prefix = false)
    }

    private fun optimizeByPreference(prefix: Boolean) {
        val origLen = len() ?: return
        if (minLiteralLen() == 0) {
            makeInfinite()
            return
        }
        if (prefix) {
            literals?.let { PreferenceTrie.minimize(it, keepExact = true) }
        }
        val fix = if (prefix) longestCommonPrefix() else longestCommonSuffix()
        if (fix != null) {
            if (prefix && origLen > 1 && fix.isNotEmpty() && fix.size <= 3 && rank(fix[0]) < 200) {
                keepFirstBytes(1)
                dedup()
                return
            }
            val isFast = isExact() && (len()?.let { it <= 16 } == true)
            val useFix = fix.size > 4 || (fix.size > 1 && !isFast)
            if (useFix) {
                if (prefix) {
                    keepFirstBytes(fix.size)
                } else {
                    keepLastBytes(fix.size)
                }
                dedup()
                check(len() == 1)
            }
        }
        val exact = if (isExact()) clone() else null
        val attempts = listOf(5 to 10, 4 to 10, 3 to 64, 2 to 64, 1 to 10)
        for ((keep, limit) in attempts) {
            val currentLen = len() ?: break
            if (currentLen <= limit) break
            if (prefix) {
                keepFirstBytes(keep)
            } else {
                keepLastBytes(keep)
            }
            if (prefix) {
                literals?.let { PreferenceTrie.minimize(it, keepExact = true) }
            }
        }
        val lits = literals()
        if (lits != null && lits.any { it.isPoisonous() }) {
            makeInfinite()
        }
        if (exact != null) {
            if (!isFinite()) {
                copyFrom(exact)
                return
            }
            if (minLiteralLen()?.let { it <= 2 } != false) {
                copyFrom(exact)
                return
            }
            if (len()?.let { it > 64 } != false) {
                copyFrom(exact)
                return
            }
        }
    }

    fun clone(): Seq = Seq(literals?.map { it.clone() }?.toMutableList())

    private fun copyFrom(other: Seq) {
        literals = other.literals?.map { it.clone() }?.toMutableList()
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Seq) return false
        val lits1 = literals
        val lits2 = other.literals
        if (lits1 == null || lits2 == null) return lits1 == null && lits2 == null
        return lits1 == lits2
    }

    override fun hashCode(): Int = literals?.hashCode() ?: 0

    override fun toString(): String {
        val lits = literals ?: return "Seq[∞]"
        return lits.joinToString(prefix = "Seq[", postfix = "]")
    }
}

/**
 * A single literal extracted from a [Hir] expression.
 *
 * A literal is composed of bytes and whether the literal is exact or not.
 */
class Literal private constructor(
    private val bytes: MutableList<Byte>,
    private var exact: Boolean,
) : Comparable<Literal> {
    companion object {
        /** Returns a new exact literal containing the bytes given. */
        fun exact(bytes: ByteArray): Literal = Literal(bytes.toMutableList(), exact = true)

        /** Returns a new exact literal containing the bytes given. */
        fun exact(bytes: String): Literal = exact(bytes.encodeToByteArray())

        /** Returns a new inexact literal containing the bytes given. */
        fun inexact(bytes: ByteArray): Literal = Literal(bytes.toMutableList(), exact = false)

        /** Returns a new inexact literal containing the bytes given. */
        fun inexact(bytes: String): Literal = inexact(bytes.encodeToByteArray())

        /** Returns a new exact literal from one byte. */
        fun fromByte(byte: Byte): Literal = exact(byteArrayOf(byte))

        /** Returns a new exact literal from one Unicode scalar value. */
        fun fromCodepoint(codepoint: Int): Literal {
            val bytes = ByteArray(codepointUtf8Len(codepoint))
            encodeUtf8(codepoint, bytes)
            return exact(bytes)
        }
    }

    /** Returns the bytes in this literal. */
    fun asBytes(): ByteArray = bytes.toByteArray()

    /** Yields ownership of the bytes inside this literal. */
    fun intoBytes(): ByteArray = asBytes()

    /** Returns the length of this literal in bytes. */
    fun len(): Int = bytes.size

    /** Returns true if and only if this literal has zero bytes. */
    fun isEmpty(): Boolean = len() == 0

    /** Returns true if and only if this literal is exact. */
    fun isExact(): Boolean = exact

    /** Marks this literal as inexact. */
    fun makeInexact() {
        exact = false
    }

    /** Reverse the bytes in this literal. */
    fun reverse() {
        bytes.reverse()
    }

    /** Extend this literal with the literal given. */
    fun extend(lit: Literal) {
        if (!isExact()) return
        bytes.addAll(lit.bytes)
    }

    /** Trims this literal such that only the first [len] bytes remain. */
    fun keepFirstBytes(len: Int) {
        if (len >= this.len()) return
        makeInexact()
        while (bytes.size > len) {
            bytes.removeAt(bytes.lastIndex)
        }
    }

    /** Trims this literal such that only the last [len] bytes remain. */
    fun keepLastBytes(len: Int) {
        if (len >= this.len()) return
        makeInexact()
        val remove = this.len() - len
        bytes.subList(0, remove).clear()
    }

    /** Returns true if this literal is likely to match very frequently. */
    internal fun isPoisonous(): Boolean =
        isEmpty() || (len() == 1 && rank(bytes[0]) >= 250)

    internal fun bytesEquals(other: Literal): Boolean = bytes == other.bytes

    fun clone(): Literal = Literal(bytes.toMutableList(), exact)

    override fun compareTo(other: Literal): Int {
        val max = minOf(bytes.size, other.bytes.size)
        for (i in 0 until max) {
            val byte1 = bytes[i].toInt() and 0xFF
            val byte2 = other.bytes[i].toInt() and 0xFF
            if (byte1 != byte2) return byte1.compareTo(byte2)
        }
        if (bytes.size != other.bytes.size) {
            return bytes.size.compareTo(other.bytes.size)
        }
        return exact.compareTo(other.exact)
    }

    override fun equals(other: Any?): Boolean =
        other is Literal && exact == other.exact && bytes == other.bytes

    override fun hashCode(): Int = 31 * bytes.hashCode() + exact.hashCode()

    override fun toString(): String {
        val tag = if (exact) "E" else "I"
        return "$tag(${DebugBytes(asBytes())})"
    }
}

/**
 * A preference trie that rejects literals that will never match when executing
 * a leftmost first or preference search.
 */
private class PreferenceTrie {
    private val states: MutableList<State> = mutableListOf()
    private val matches: MutableList<Int?> = mutableListOf()
    private var nextLiteralIndex: Int = 1

    companion object {
        /** Minimizes the given sequence while preserving preference order semantics. */
        fun minimize(literals: MutableList<Literal>, keepExact: Boolean) {
            val trie = PreferenceTrie()
            val retained = ArrayList<Literal>(literals.size)
            val makeInexact = mutableListOf<Int>()
            for (lit in literals) {
                when (val inserted = trie.insert(lit.asBytes())) {
                    is Inserted.Ok -> retained.add(lit)
                    is Inserted.Err -> if (!keepExact) makeInexact.add(inserted.index - 1)
                }
            }
            literals.clear()
            literals.addAll(retained)
            for (index in makeInexact) {
                if (index in literals.indices) {
                    literals[index].makeInexact()
                }
            }
        }
    }

    private fun insert(bytes: ByteArray): Inserted {
        var prev = root()
        matches[prev]?.let { return Inserted.Err(it) }
        for (byte in bytes) {
            val transitions = states[prev].transitions
            val transitionIndex = transitions.binarySearchByByte(byte)
            if (transitionIndex >= 0) {
                prev = transitions[transitionIndex].state
                matches[prev]?.let { return Inserted.Err(it) }
            } else {
                val insertion = -transitionIndex - 1
                val next = createState()
                transitions.add(insertion, Transition(byte, next))
                prev = next
            }
        }
        val index = nextLiteralIndex
        nextLiteralIndex += 1
        matches[prev] = index
        return Inserted.Ok(index)
    }

    private fun root(): Int = if (states.isNotEmpty()) 0 else createState()

    private fun createState(): Int {
        val id = states.size
        states.add(State())
        matches.add(null)
        return id
    }
}

private sealed class Inserted {
    class Ok(val index: Int) : Inserted()
    class Err(val index: Int) : Inserted()
}

private class State(
    val transitions: MutableList<Transition> = mutableListOf(),
)

private data class Transition(val byte: Byte, val state: Int)

private fun MutableList<Transition>.binarySearchByByte(byte: Byte): Int {
    val needle = byte.toInt() and 0xFF
    var left = 0
    var right = size
    while (left < right) {
        val mid = (left + right) ushr 1
        val value = this[mid].byte.toInt() and 0xFF
        when {
            value < needle -> left = mid + 1
            value > needle -> right = mid
            else -> return mid
        }
    }
    return -left - 1
}

/**
 * Returns the rank of the given byte.
 *
 * The minimum rank value is `0` and the maximum rank value is `255`.
 */
fun rank(byte: Byte): Int = BYTE_FREQUENCIES[byte.toInt() and 0xFF].toInt() and 0xFF

private fun saturatingAdd(a: Int, b: Int): Int {
    val sum = a.toLong() + b.toLong()
    return if (sum > Int.MAX_VALUE) Int.MAX_VALUE else sum.toInt()
}

private fun saturatingMul(a: Int, b: Int): Int {
    val product = a.toLong() * b.toLong()
    return if (product > Int.MAX_VALUE) Int.MAX_VALUE else product.toInt()
}
