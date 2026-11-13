// port-lint: source hir/literal.rs
package io.github.kotlinmania.regexsyntax.hir.literal

import io.github.kotlinmania.regexsyntax.ast.Concat
import io.github.kotlinmania.regexsyntax.debug.Bytes
import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Class
import io.github.kotlinmania.regexsyntax.hir.ClassBytes
import io.github.kotlinmania.regexsyntax.hir.ClassUnicode
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.regexsyntax.rank.BYTE_FREQUENCIES

// Provides literal extraction from `Hir` expressions.
//
// An [Extractor] pulls literals out of [Hir] expressions and returns a
// [Seq] of [Literal]s.
//
// The purpose of literal extraction is generally to provide avenues for
// optimizing regex searches. The main idea is that substring searches can be an
// order of magnitude faster than a regex search. Therefore, if one can execute
// a substring search to find candidate match locations and only run the regex
// search at those locations, then it is possible for huge improvements in
// performance to be realized.
//
// With that said, literal optimizations are generally a black art because even
// though substring search is generally faster, if the number of candidates
// produced is high, then it can create a lot of overhead by ping-ponging between
// the substring search and the regex search.
//
// Here are some heuristics that might be used to help increase the chances of
// effective literal optimizations:
//
// * Stick to small [Seq]s. If you search for too many literals, it's likely
// to lead to substring search that is only a little faster than a regex search,
// and thus the overhead of using literal optimizations in the first place might
// make things slower overall.
// * The literals in your [Seq] shouldn't be too short. In general, longer is
// better. A sequence corresponding to single bytes that occur frequently in the
// haystack, for example, is probably a bad literal optimization because it's
// likely to produce many false positive candidates. Longer literals are less
// likely to match, and thus probably produce fewer false positives.
// * If it's possible to estimate the approximate frequency of each byte according
// to some pre-computed background distribution, it is possible to compute a score
// of how "good" a `Seq` is. If a `Seq` isn't good enough, you might consider
// skipping the literal optimization and just use the regex engine.
//
// (It should be noted that there are always pathological cases that can make
// any kind of literal optimization be a net slower result. This is why it
// might be a good idea to be conservative, or to even provide a means for
// literal optimizations to be dynamically disabled if they are determined to be
// ineffective according to some measure.)
//
// You're encouraged to explore the methods on [Seq], which permit shrinking
// the size of sequences in a preference-order preserving fashion.
//
// Finally, note that it isn't strictly necessary to use an [Extractor]. Namely,
// an `Extractor` only uses public APIs of the [Seq] and [Literal] types,
// so it is possible to implement your own extractor. For example, for n-grams
// or "inner" literals (i.e., not prefix or suffix literals). The `Extractor`
// is mostly responsible for the case analysis over `Hir` expressions. Much of
// the "trickier" parts are how to combine literal sequences, and that is all
// implemented on [Seq].

public class DebugTuple(
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
 *
 * Literal extraction is based on the following observations:
 *
 * * Many regexes start with one or a small number of literals.
 * * Substring search for literals is often much faster (sometimes by an order
 * of magnitude) than a regex search.
 *
 * Thus, in many cases, one can search for literals to find candidate starting
 * locations of a match, and then only run the full regex engine at each such
 * location instead of over the full haystack.
 *
 * The main downside of literal extraction is that it can wind up causing a
 * search to be slower overall. For example, if there are many matches or if
 * there are many candidates that don't ultimately lead to a match, then a
 * lot of overhead will be spent in shuffling back-and-forth between substring
 * search and the regex engine. This is the fundamental reason why literal
 * optimizations for regex patterns is sometimes considered a "black art."
 *
 * # Look-around assertions
 *
 * Literal extraction treats all look-around assertions as-if they match every
 * empty string. So for example, the regex `\bquux\b` will yield a sequence
 * containing a single exact literal `quux`. However, not all occurrences
 * of `quux` correspond to a match a of the regex. For example, `\bquux\b`
 * does not match `ZquuxZ` anywhere because `quux` does not fall on a word
 * boundary.
 *
 * In effect, if your regex contains look-around assertions, then a match of
 * an exact literal does not necessarily mean the regex overall matches. So
 * you may still need to run the regex engine in such cases to confirm the
 * match.
 *
 * The precise guarantee you get from a literal sequence is: if every literal
 * in the sequence is exact and the original regex contains zero look-around
 * assertions, then a preference-order multi-substring search of those
 * literals will precisely match a preference-order search of the original
 * regex.
 *
 * # Example
 *
 * This shows how to extract prefixes:
 *
 * ```kotlin
 * import regexsyntax.hir.literal.Extractor
 * import regexsyntax.hir.literal.Literal
 * import regexsyntax.hir.literal.Seq
 * import regexsyntax.parse
 *
 * val hir = parse("(a|b|c)(x|y|z)[A-Z]+foo").getOrElse { return Result.failure(it) }
 * val got = Extractor.new().extract(hir)
 * // All literals returned are "inexact" because none of them reach the
 * // match state.
 * val expected = Seq.fromIter(listOf(Literal.inexact("ax"), Literal.inexact("ay"), Literal.inexact("az"), Literal.inexact("bx"), Literal.inexact("by"), Literal.inexact("bz"), Literal.inexact("cx"), Literal.inexact("cy"), Literal.inexact("cz")))
 * check(expected == got)
 * ```
 *
 * This shows how to extract suffixes:
 *
 * ```kotlin
 * import regexsyntax.hir.literal.ExtractKind
 * import regexsyntax.hir.literal.Extractor
 * import regexsyntax.hir.literal.Literal
 * import regexsyntax.hir.literal.Seq
 * import regexsyntax.parse
 *
 * val hir = parse("foo|[A-Z]+bar").getOrElse { return Result.failure(it) }
 * val got = Extractor.new().kind(ExtractKind.Suffix).extract(hir)
 * // Since 'foo' gets to a match state, it is considered exact. But 'bar'
 * // does not because of the '[A-Z]+', and thus is marked inexact.
 * val expected = Seq.fromIter(listOf(Literal.exact("foo"), Literal.inexact("bar")))
 * check(expected == got)
 * ```
 */
public open class Extractor(
    public var kind: ExtractKind,
    public var limitClass: Int,
    public var limitRepeat: Int,
    public var limitLiteralLen: Int,
    public var limitTotal: Int,
) {
    /**
     * Execute the extractor and return a sequence of literals.
     */
    public open fun extract(hir: Hir): Seq =
        run {
            val matchVal = hir.kind()
            when {
                matchVal == Empty -> Seq.singleton(Literal.exact(mutableListOf()))
                matchVal is Look -> Seq.singleton(Literal.exact(mutableListOf()))
                matchVal is Literal && matchVal.value is io.github.kotlinmania.regexsyntax.hir.Literal -> {
                    val (bytes) = matchVal.value as io.github.kotlinmania.regexsyntax.hir.Literal
                    var seq = Seq.singleton(Literal.exact(bytes.toVec()))
                    this.enforceLiteralLen(seq)
                    seq
                }
                matchVal is Class && matchVal.value is Class.Unicode -> {
                    val (cls) = matchVal.value as Class.Unicode
                    this.extractClassUnicode(cls)
                }
                matchVal is Class && matchVal.value is Class.Bytes -> {
                    val (cls1) = matchVal.value as Class.Bytes
                    this.extractClassBytes(cls1)
                }
                matchVal is Repetition -> {
                    val (rep) = matchVal
                    this.extractRepetition(rep)
                }
                matchVal is Capture && matchVal.value is Capture -> {
                    val sub = (matchVal.value as Capture).sub
                    this.extract(sub)
                }
                matchVal is Concat -> {
                    val (hirs) = matchVal
                    when (this.kind) {
                        ExtractKind.Prefix -> this.extractConcat(hirs.iter())
                        ExtractKind.Suffix -> this.extractConcat(hirs.iter().rev())
                    }
                }
                else -> {
                    val (hirs1) = matchVal
                    // Unlike concat, we always union starting from the beginning,
                    // since the beginning corresponds to the highest preference,
                    // which doesn't change based on forwards vs reverse.
                    this.extractAlternation(hirs1.iter())
                }
            }
        }

    /**
     * Set the kind of literal sequence to extract from an [Hir] expression.
     *
     * The default is to extract prefixes, but suffixes can be selected
     * instead. The contract for prefixes is that every match of the
     * corresponding `Hir` must start with one of the literals in the sequence
     * returned. Moreover, the _order_ of the sequence returned corresponds to
     * the preference order.
     *
     * Suffixes satisfy a similar contract in that every match of the
     * corresponding `Hir` must end with one of the literals in the sequence
     * returned. However, there is no guarantee that the literals are in
     * preference order.
     *
     * Remember that a sequence can be infinite. For example, unless the
     * limits are configured to be impractically large, attempting to extract
     * prefixes (or suffixes) for the pattern `[A - Z]` will return an infinite
     * sequence. Generally speaking, if the sequence returned is infinite,
     * then it is presumed to be unwise to do prefix (or suffix) optimizations
     * for the pattern.
     */
    public open fun kind(kind: ExtractKind): Extractor {
        this.kind = kind
        return this
    }

    /**
     * Configure a limit on the length of the sequence that is permitted for
     * a character class. If a character class exceeds this limit, then the
     * sequence returned for it is infinite.
     *
     * This prevents classes like `[A - Z]` or `\pL` from getting turned into
     * huge and likely unproductive sequences of literals.
     *
     * # Example
     *
     * This example shows how this limit can be lowered to decrease the tolerance
     * for character classes being turned into literal sequences.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Extractor
     * import regexsyntax.hir.literal.Seq
     * import regexsyntax.parse
     *
     * val hir = parse("[0-9]").getOrElse { return Result.failure(it) }
     * val got = Extractor.new().extract(hir)
     * val expected = Seq.new(listOf("0", "1", "2", "3", "4", "5", "6", "7", "8", "9"))
     * check(expected == got)
     * // Now let's shrink the limit and see how that changes things.
     * val got1 = Extractor.new().limitClass(4).extract(hir)
     * val expected1 = Seq.infinite()
     * check(expected1 == got1)
     * ```
     */
    public open fun limitClass(limit: Int): Extractor {
        this.limitClass = limit
        return this
    }

    /**
     * Configure a limit on the total number of repetitions that is permitted
     * before literal extraction is stopped.
     *
     * This is useful for limiting things like `(abcde) { 50 }`, or more
     * insidiously, `(?:) { 1000000000 }`. This limit prevents any one single
     * repetition from adding too much to a literal sequence.
     *
     * With this limit set, repetitions that exceed it will be stopped and any
     * literals extracted up to that point will be made inexact.
     *
     * # Example
     *
     * This shows how to decrease the limit and compares it with the default.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Extractor
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     * import regexsyntax.parse
     *
     * val hir = parse("(abc){8}").getOrElse { return Result.failure(it) }
     * val got = Extractor.new().extract(hir)
     * val expected = Seq.new(listOf("abcabcabcabcabcabcabcabc"))
     * check(expected == got)
     * // Now let's shrink the limit and see how that changes things.
     * val got1 = Extractor.new().limitRepeat(4).extract(hir)
     * val expected1 = Seq.fromIter(listOf(Literal.inexact("abcabcabcabc")))
     * check(expected1 == got1)
     * ```
     */
    public open fun limitRepeat(limit: Int): Extractor {
        this.limitRepeat = limit
        return this
    }

    /**
     * Configure a limit on the maximum length of any literal in a sequence.
     *
     * This is useful for limiting things like `(abcde) { 5 } { 5 } { 5 } { 5 }`. While
     * each repetition or literal in that regex is small, when all the
     * repetitions are applied, one ends up with a literal of length `5^4 =
     * 625`.
     *
     * With this limit set, literals that exceed it will be made inexact and
     * thus prevented from growing.
     *
     * # Example
     *
     * This shows how to decrease the limit and compares it with the default.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Extractor
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     * import regexsyntax.parse
     *
     * val hir = parse("(abc){2}{2}{2}").getOrElse { return Result.failure(it) }
     * val got = Extractor.new().extract(hir)
     * val expected = Seq.new(listOf("abcabcabcabcabcabcabcabc"))
     * check(expected == got)
     * // Now let's shrink the limit and see how that changes things.
     * val got1 = Extractor.new().limitLiteralLen(14).extract(hir)
     * val expected1 = Seq.fromIter(listOf(Literal.inexact("abcabcabcabcab")))
     * check(expected1 == got1)
     * ```
     */
    public open fun limitLiteralLen(limit: Int): Extractor {
        this.limitLiteralLen = limit
        return this
    }

    /**
     * Configure a limit on the total number of literals that will be
     * returned.
     *
     * This is useful as a practical measure for avoiding the creation of
     * large sequences of literals. While the extractor will automatically
     * handle local creations of large sequences (for example, `[A - Z]` yields
     * an infinite sequence by default), large sequences can be created
     * through non-local means as well.
     *
     * For example, `[ab] { 3 } { 3 }` would yield a sequence of length `512 = 2 ^ 9`
     * despite each of the repetitions being small on their own. This limit
     * thus represents a "catch all" for avoiding locally small sequences from
     * combining into large sequences.
     *
     * # Example
     *
     * This example shows how reducing the limit will change the literal
     * sequence returned.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Extractor
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     * import regexsyntax.parse
     *
     * val hir = parse("[ab]{2}{2}").getOrElse { return Result.failure(it) }
     * val got = Extractor.new().extract(hir)
     * val expected = Seq.new(listOf("aaaa", "aaab", "aaba", "aabb", "abaa", "abab", "abba", "abbb", "baaa", "baab", "baba", "babb", "bbaa", "bbab", "bbba", "bbbb"))
     * check(expected == got)
     * // The default limit is not too big, but big enough to extract all
     * // literals from '[ab]{2}{2}'. If we shrink the limit to less than 16,
     * // then we'll get a truncated set. Notice that it returns a sequence of
     * // length 4 even though our limit was 10. This is because the sequence
     * // is difficult to increase without blowing the limit. Notice also
     * // that every literal in the sequence is now inexact because they were
     * // stripped of some suffix.
     * val got1 = Extractor.new().limitTotal(10).extract(hir)
     * val expected1 = Seq.fromIter(listOf(Literal.inexact("aa"), Literal.inexact("ab"), Literal.inexact("ba"), Literal.inexact("bb")))
     * check(expected1 == got1)
     * ```
     */
    public open fun limitTotal(limit: Int): Extractor {
        this.limitTotal = limit
        return this
    }

    /**
     * Extract a sequence from the given concatenation. Sequences from each of
     * the child HIR expressions are combined via cross product.
     *
     * This short circuits once the cross product turns into a sequence
     * containing only inexact literals.
     */
    public open fun <I : Iterator<Hir>> extractConcat(it: I): Seq {
        var seq = Seq.singleton(Literal.exact(mutableListOf()))
        for (hir in it) {
            // If every element in the sequence is inexact, then a cross
            // product will always be a no-op. Thus, there is nothing else we
            // can add to it and can quit early. Note that this also includes
            // infinite sequences.
            if (seq.isInexact()) {
                break
            }
            // Note that 'cross' also dispatches based on whether we're
            // extracting prefixes or suffixes.
            seq = this.cross(seq, this.extract(hir))
        }
        return seq
    }

    /**
     * Extract a sequence from the given alternation.
     *
     * This short circuits once the union turns into an infinite sequence.
     */
    public open fun <I : Iterator<Hir>> extractAlternation(it: I): Seq {
        var seq = Seq.empty()
        for (hir in it) {
            // Once our 'seq' is infinite, every subsequent union
            // operation on it will itself always result in an
            // infinite sequence. Thus, it can never change and we can
            // short-circuit.
            if (!seq.isFinite()) {
                break
            }
            seq = this.union(seq, this.extract(hir))
        }
        return seq
    }

    /**
     * Extract a sequence of literals from the given repetition. We do our
     * best, Some examples:
     *
     *   a*'    => [inexact(a), exact("")]
     *   a*?'   => [exact(""), inexact(a)]
     *   a+'    => [inexact(a)]
     *   a{3}'  => [exact(aaa)]
     *   a{3,5} => [inexact(aaa)]
     *
     * The key here really is making sure we get the 'inexact' vs 'exact'
     * attributes correct on each of the literals we add. For example, the
     * fact that a*' gives us an inexact 'a' and an exact empty string means
     * that a regex like ab*c' will result in [inexact(ab), exact(ac)]
     * literals being extracted, which might actually be a better prefilter
     * than just 'a'.
     */
    public open fun extractRepetition(rep: Repetition): Seq {
        val subseq = this.extract(rep.sub)
        return when {
            rep is Repetition && rep.min == 0 -> {
                val max = rep.max
                val greedy = rep.greedy
                // When max=1', we can retain exactness, since a?' is
                // equivalent to a|'. Similarly below, a??' is equivalent to
                // '|a'.
                if (max != 1) {
                    subseq.makeInexact()
                }
                var empty = Seq.singleton(Literal.exact(mutableListOf()))
                if (!greedy) {
                    mem.swap(subseq, empty)
                }
                this.union(subseq, empty)
            }
            rep is Repetition && rep.max != null && (rep as Repetition).min == (rep as Repetition).max -> {
                val min = rep.min
                val max1 = rep.max
                check(min > 0)
                // handled above
                val limit = UInt.tryFrom(this.limitRepeat).unwrapOr(UInt.MAX_VALUE)
                var seq = Seq.singleton(Literal.exact(mutableListOf()))
                for (unused in 0 until minOf(min, limit)) {
                    if (seq.isInexact()) {
                        break
                    }
                    seq = this.cross(seq, subseq.clone())
                }
                if (Int.tryFrom(min).isErr() || min > limit) {
                    seq.makeInexact()
                }
                seq
            }
            else -> {
                val min1 = rep.min
                check(min1 > 0)
                // handled above
                val limit1 = UInt.tryFrom(this.limitRepeat).unwrapOr(UInt.MAX_VALUE)
                var seq1 = Seq.singleton(Literal.exact(mutableListOf()))
                for (unused1 in 0 until minOf(min1, limit1)) {
                    if (seq1.isInexact()) {
                        break
                    }
                    seq1 = this.cross(seq1, subseq.clone())
                }
                seq1.makeInexact()
                seq1
            }
        }
    }

    /**
     * Convert the given Unicode class into a sequence of literals if the
     * class is small enough. If the class is too big, return an infinite
     * sequence.
     */
    public open fun extractClassUnicode(cls: ClassUnicode): Seq {
        if (this.classOverLimitUnicode(cls)) {
            return Seq.infinite()
        }
        val seq = Seq.empty()
        for (r in cls.iter()) {
            for (ch in r.start()..r.end()) {
                seq.push(Literal.from(ch))
            }
        }
        this.enforceLiteralLen(seq)
        return seq
    }

    /**
     * Convert the given byte class into a sequence of literals if the class
     * is small enough. If the class is too big, return an infinite sequence.
     */
    public open fun extractClassBytes(cls: ClassBytes): Seq {
        if (this.classOverLimitBytes(cls)) {
            return Seq.infinite()
        }
        val seq = Seq.empty()
        for (r in cls.iter()) {
            for (b in r.start()..r.end()) {
                seq.push(Literal.from(b))
            }
        }
        this.enforceLiteralLen(seq)
        return seq
    }

    /**
     * Returns true if the given Unicode class exceeds the configured limits
     * on this extractor.
     */
    public open fun classOverLimitUnicode(cls: ClassUnicode): Boolean {
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
    public open fun classOverLimitBytes(cls: ClassBytes): Boolean {
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
    public open fun cross(seq1: Seq, seq2: Seq): Seq {
        if ((seq1.maxCrossLen(seq2)?.let({ len -> len > this.limitTotal }) ?: false)) {
            seq2.makeInfinite()
        }
        if (this.kind == ExtractKind.Suffix) {
            seq1.crossReverse(seq2)
        } else {
            seq1.crossForward(seq2)
        }
        check(seq1.len().mapOr(true, { x -> x <= this.limitTotal }))
        this.enforceLiteralLen(seq1)
        return seq1
    }

    /**
     * Union the two sequences if the result would be within configured
     * limits. Otherwise, make `seq2` infinite and union the infinite sequence
     * with `seq1`.
     */
    public open fun union(seq1: Seq, seq2: Seq): Seq {
        if ((seq1.maxUnionLen(seq2)?.let({ len -> len > this.limitTotal }) ?: false)) {
            // We try to trim our literal sequences to see if we can make
            // room for more literals. The idea is that we'd rather trim down
            // literals already in our sequence if it means we can add a few
            // more and retain a finite sequence. Otherwise, we'll union with
            // an infinite sequence and that infects everything and effectively
            // stops literal extraction in its tracks.
            //
            // We do we keep 4 bytes here? Well, it's a bit of an abstraction
            // leakage. Downstream, the literals may wind up getting fed to
            // the Teddy algorithm, which supports searching literals up to
            // length 4. So that's why we pick that number here. Arguably this
            // should be a tunable parameter, but it seems a little tricky to
            // describe. And I'm still unsure if this is the right way to go
            // about culling literal sequences.
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
            if ((seq1.maxUnionLen(seq2)?.let({ len -> len > this.limitTotal }) ?: false)) {
                seq2.makeInfinite()
            }
        }
        seq1.union(seq2)
        check(seq1.len().mapOr(true, { x -> x <= this.limitTotal }))
        return seq1
    }

    /**
     * Applies the literal length limit to the given sequence. If none of the
     * literals in the sequence exceed the limit, then this is a no-op.
     */
    public open fun enforceLiteralLen(seq: Seq) {
        val len = this.limitLiteralLen
        when (this.kind) {
            ExtractKind.Prefix -> seq.keepFirstBytes(len)
            ExtractKind.Suffix -> seq.keepLastBytes(len)
        }
    }

    public companion object {
        /**
         * Create a new extractor with a default configuration.
         *
         * The extractor can be optionally configured before calling
         * [Extractor.extract] to get a literal sequence.
         */
        public fun new(): Extractor = Extractor(kind = ExtractKind.Prefix, limitClass = 10, limitRepeat = 10, limitLiteralLen = 100, limitTotal = 250)

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
     *
     * Note that the sequence returned by suffix literals currently may
     * not correctly represent leftmost-first or "preference" order match
     * semantics.
     */
    public data object Suffix : ExtractKind()

    /**
     * Returns true if this kind is the `Prefix` variant.
     */
    public open fun isPrefix(): Boolean =
        when (this) {
            ExtractKind.Prefix -> true
            else -> false
        }

    /**
     * Returns true if this kind is the `Suffix` variant.
     */
    public open fun isSuffix(): Boolean =
        when (this) {
            ExtractKind.Suffix -> true
            else -> false
        }

    public companion object {
        public fun default(): ExtractKind = ExtractKind.Prefix
    }
}

/**
 * A sequence of literals.
 *
 * A `Seq` is very much like a set in that it represents a union of its
 * members. That is, it corresponds to a set of literals where at least one
 * must match in order for a particular [Hir] expression to match. (Whether
 * this corresponds to the entire `Hir` expression, a prefix of it or a suffix
 * of it depends on how the `Seq` was extracted from the `Hir`.)
 *
 * It is also unlike a set in that multiple identical literals may appear,
 * and that the order of the literals in the `Seq` matters. For example, if
 * the sequence is `[sam, samwise]` and leftmost-first matching is used, then
 * `samwise` can never match and the sequence is equivalent to `[sam]`.
 *
 * # States of a sequence
 *
 * A `Seq` has a few different logical states to consider:
 *
 * * The sequence can represent "any" literal. When this happens, the set does
 * not have a finite size. The purpose of this state is to inhibit callers
 * from making assumptions about what literals are required in order to match
 * a particular [Hir] expression. Generally speaking, when a set is in this
 * state, literal optimizations are inhibited. A good example of a regex that
 * will cause this sort of set to appear is `[A - Za - z]`. The character class
 * is just too big (and also too narrow) to be usefully expanded into 52
 * different literals. (Note that the decision for when a seq should become
 * infinite is determined by the caller. A seq itself has no hard-coded
 * limits.)
 * * The sequence can be empty, in which case, it is an affirmative statement
 * that there are no literals that can match the corresponding `Hir`.
 * Consequently, the `Hir` never matches any input. For example, `[a && b]`.
 * * The sequence can be non-empty, in which case, at least one of the
 * literals must match in order for the corresponding `Hir` to match.
 *
 * # Example
 *
 * This example shows how literal sequences can be simplified by stripping
 * suffixes and minimizing while maintaining preference order.
 *
 * ```kotlin
 * import regexsyntax.hir.literal.Literal
 * import regexsyntax.hir.literal.Seq
 *
 * var seq = Seq.new(listOf("farm", "appliance", "faraway", "apple", "fare", "gap", "applicant", "applaud"))
 * seq.keepFirstBytes(3)
 * seq.minimizeByPreference()
 * // Notice that 'far' comes before 'app', which matches the order in the
 * // original sequence. This guarantees that leftmost-first semantics are
 * // not altered by simplifying the set.
 * val expected = Seq.fromIter(listOf(Literal.inexact("far"), Literal.inexact("app"), Literal.exact("gap")))
 * check(expected == seq)
 * ```
 */
public open class Seq(
    /**
     * The members of this seq.
     *
     * When `None`, the seq represents all possible literals. That is, it
     * prevents one from making assumptions about specific literals in the
     * seq, and forces one to treat it as if any literal might be in the seq.
     *
     * Note that `Some(listOf())` is valid and corresponds to the empty seq of
     * literals, i.e., a regex that can never match. For example, `[a && b]`.
     * It is distinct from `Some(listOf(""))`, which corresponds to the seq
     * containing an empty string, which matches at every position.
     */
    public var literals: List<Literal>?,
) {
    /**
     * If this is a finite sequence, return its members as a slice of
     * literals.
     *
     * The slice returned may be empty, in which case, there are no literals
     * that can match this sequence.
     */
    public open fun literals(): List<Literal>? = this.literals

    /**
     * Push a literal to the end of this sequence.
     *
     * If this sequence is not finite, then this is a no-op.
     *
     * Similarly, if the most recently added item of this sequence is
     * equivalent to the literal given, then it is not added. This reflects
     * a `Seq`s "set like" behavior, and represents a practical trade off.
     * Namely, there is never any need to have two adjacent and equivalent
     * literals in the same sequence, _and_ it is easy to detect in some
     * cases.
     */
    public open fun push(lit: Literal) {
        val lits1 =
            when (val matchVal = this.literals) {
                null -> { return }
                else -> {
                    val lits = matchVal
                    lits
                }
            }
        if ((lits1.last()?.let({ m -> m == lit }) ?: false)) {
            return
        }
        lits1.push(lit)
    }

    /**
     * Make all of the literals in this sequence inexact.
     *
     * This is a no-op if this sequence is not finite.
     */
    public open fun makeInexact() {
        val lits1 =
            when (val matchVal = this.literals) {
                null -> { return }
                else -> {
                    val lits = matchVal
                    lits
                }
            }
        for (lit in lits1.iterMut()) {
            lit.makeInexact()
        }
    }

    /**
     * Converts this sequence to an infinite sequence.
     *
     * This is a no-op if the sequence is already infinite.
     */
    public open fun makeInfinite() {
        this.literals = null
    }

    /**
     * Modify this sequence to contain the cross product between it and the
     * sequence given.
     *
     * The cross product only considers literals in this sequence that are
     * exact. That is, inexact literals are not extended.
     *
     * The literals are always drained from `other`, even if none are used.
     * This permits callers to reuse the sequence allocation elsewhere.
     *
     * If this sequence is infinite, then this is a no-op, regardless of what
     * `other` contains (and in this case, the literals are still drained from
     * `other`). If `other` is infinite and this sequence is finite, then this
     * is a no-op, unless this sequence contains a zero-length literal. In
     * which case, the infiniteness of `other` infects this sequence, and this
     * sequence is itself made infinite.
     *
     * Like [Seq.union], this may attempt to deduplicate literals. See
     * [Seq.dedup] for how deduplication deals with exact and inexact
     * literals.
     *
     * # Example
     *
     * This example shows basic usage and how exact and inexact literals
     * interact.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.fromIter(listOf(Literal.exact("foo"), Literal.inexact("bar")))
     * var seq2 = Seq.fromIter(listOf(Literal.inexact("quux"), Literal.exact("baz")))
     * seq1.crossForward(seq2)
     * // The literals are pulled out of seq2.
     * check(0 == seq2.len())
     * val expected = Seq.fromIter(listOf(Literal.inexact("fooquux"), Literal.exact("foobaz"), Literal.inexact("bar")))
     * check(expected == seq1)
     * ```
     *
     * This example shows the behavior of when `other` is an infinite
     * sequence.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.fromIter(listOf(Literal.exact("foo"), Literal.inexact("bar")))
     * var seq2 = Seq.infinite()
     * seq1.crossForward(seq2)
     * // When seq2 is infinite, cross product doesn't add anything, but
     * // ensures all members of seq1 are inexact.
     * val expected = Seq.fromIter(listOf(Literal.inexact("foo"), Literal.inexact("bar")))
     * check(expected == seq1)
     * ```
     *
     * This example is like the one above, but shows what happens when this
     * sequence contains an empty string. In this case, an infinite `other`
     * sequence infects this sequence (because the empty string means that
     * there are no finite prefixes):
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.fromIter(listOf(Literal.exact("foo"), Literal.exact(""), Literal.inexact("bar")))
     * var seq2 = Seq.infinite()
     * seq1.crossForward(seq2)
     * // seq1 is now infinite!
     * check(!seq1.isFinite())
     * ```
     *
     * This example shows the behavior of this sequence is infinite.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.infinite()
     * var seq2 = Seq.fromIter(listOf(Literal.exact("foo"), Literal.inexact("bar")))
     * seq1.crossForward(seq2)
     * // seq1 remains unchanged.
     * check(!seq1.isFinite())
     * // Even though the literals in seq2 weren't used, it was still drained.
     * check(0 == seq2.len())
     * ```
     */
    public open fun crossForward(other: Seq) {
        val (lits11, lits21) =
            when (val matchVal = this.crossPreamble(other)) {
                null -> { return }
                else -> {
                    val lits1 = matchVal.first
                    val lits2 = matchVal.second
                    Pair(lits1, lits2)
                }
            }
        val newcap = lits11.len().saturatingMul(lits21.len())
        for (selflit in mem.replace(lits11, ArrayList(newcap))) {
            if (!selflit.isExact()) {
                lits11.push(selflit)
                continue
            }
            for (otherlit in lits21.iter()) {
                val newlit = Literal.exact(ArrayList(selflit.len() + otherlit.len()))
                newlit.extend(selflit)
                newlit.extend(otherlit)
                if (!otherlit.isExact()) {
                    newlit.makeInexact()
                }
                lits11.push(newlit)
            }
        }
        lits21.drain(0 until 0)
        this.dedup()
    }

    /**
     * Modify this sequence to contain the cross product between it and
     * the sequence given, where the sequences are treated as suffixes
     * instead of prefixes. Namely, the sequence `other` is *prepended*
     * to `self` (as opposed to `other` being *appended* to `self` in
     * [Seq.crossForward]).
     *
     * The cross product only considers literals in this sequence that are
     * exact. That is, inexact literals are not extended.
     *
     * The literals are always drained from `other`, even if none are used.
     * This permits callers to reuse the sequence allocation elsewhere.
     *
     * If this sequence is infinite, then this is a no-op, regardless of what
     * `other` contains (and in this case, the literals are still drained from
     * `other`). If `other` is infinite and this sequence is finite, then this
     * is a no-op, unless this sequence contains a zero-length literal. In
     * which case, the infiniteness of `other` infects this sequence, and this
     * sequence is itself made infinite.
     *
     * Like [Seq.union], this may attempt to deduplicate literals. See
     * [Seq.dedup] for how deduplication deals with exact and inexact
     * literals.
     *
     * # Example
     *
     * This example shows basic usage and how exact and inexact literals
     * interact.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.fromIter(listOf(Literal.exact("foo"), Literal.inexact("bar")))
     * var seq2 = Seq.fromIter(listOf(Literal.inexact("quux"), Literal.exact("baz")))
     * seq1.crossReverse(seq2)
     * // The literals are pulled out of seq2.
     * check(0 == seq2.len())
     * val expected = Seq.fromIter(listOf(Literal.inexact("quuxfoo"), Literal.inexact("bar"), Literal.exact("bazfoo")))
     * check(expected == seq1)
     * ```
     *
     * This example shows the behavior of when `other` is an infinite
     * sequence.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.fromIter(listOf(Literal.exact("foo"), Literal.inexact("bar")))
     * var seq2 = Seq.infinite()
     * seq1.crossReverse(seq2)
     * // When seq2 is infinite, cross product doesn't add anything, but
     * // ensures all members of seq1 are inexact.
     * val expected = Seq.fromIter(listOf(Literal.inexact("foo"), Literal.inexact("bar")))
     * check(expected == seq1)
     * ```
     *
     * This example is like the one above, but shows what happens when this
     * sequence contains an empty string. In this case, an infinite `other`
     * sequence infects this sequence (because the empty string means that
     * there are no finite suffixes):
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.fromIter(listOf(Literal.exact("foo"), Literal.exact(""), Literal.inexact("bar")))
     * var seq2 = Seq.infinite()
     * seq1.crossReverse(seq2)
     * // seq1 is now infinite!
     * check(!seq1.isFinite())
     * ```
     *
     * This example shows the behavior when this sequence is infinite.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.infinite()
     * var seq2 = Seq.fromIter(listOf(Literal.exact("foo"), Literal.inexact("bar")))
     * seq1.crossReverse(seq2)
     * // seq1 remains unchanged.
     * check(!seq1.isFinite())
     * // Even though the literals in seq2 weren't used, it was still drained.
     * check(0 == seq2.len())
     * ```
     */
    public open fun crossReverse(other: Seq) {
        val (lits11, lits21) =
            when (val matchVal = this.crossPreamble(other)) {
                null -> { return }
                else -> {
                    val lits1 = matchVal.first
                    val lits2 = matchVal.second
                    Pair(lits1, lits2)
                }
            }
        // We basically proceed as we do in 'crossForward' at this point,
        // except that the outer loop is now 'other' and the inner loop is now
        // 'self'. That's because 'self' corresponds to suffixes and 'other'
        // corresponds to the sequence we want to *prepend* to the suffixes.
        val newcap = lits11.len().saturatingMul(lits21.len())
        val selflits = mem.replace(lits11, ArrayList(newcap))
        for ((i, otherlit) in lits21.drain(0 until 0).enumerate()) {
            for (selflit in selflits.iter()) {
                if (!selflit.isExact()) {
                    // If the suffix isn't exact, then we can't prepend
                    // anything to it. However, we still want to keep it. But
                    // we only want to keep one of them, to avoid duplication.
                    // (The duplication is okay from a correctness perspective,
                    // but wasteful.)
                    if (i == 0) {
                        lits11.push(selflit.clone())
                    }
                    continue
                }
                val newlit = Literal.exact(ArrayList(otherlit.len() + selflit.len()))
                newlit.extend(otherlit)
                newlit.extend(selflit)
                if (!otherlit.isExact()) {
                    newlit.makeInexact()
                }
                lits11.push(newlit)
            }
        }
        this.dedup()
    }

    /**
     * A helper function the corresponds to the subtle preamble for both
     * `crossForward` and `crossReverse`. In effect, it handles the cases
     * of infinite sequences for both `self` and `other`, as well as ensuring
     * that literals from `other` are drained even if they aren't used.
     */
    public open fun crossPreamble(other: Seq): Pair<List<Literal>, List<Literal>>? {
        val lits2 =
            when (val matchVal = other.literals) {
                null -> {
                    // If our current seq contains the empty string and the seq
                    // we're adding matches any literal, then it follows that the
                    // current seq must now also match any literal.
                    //
                    // Otherwise, we just have to make sure everything in this
                    // sequence is inexact.
                    if (this.minLiteralLen() == 0) {
                        this = Seq.infinite()
                    } else {
                        this.makeInexact()
                    }
                    return null
                }
                else -> {
                    val lits = matchVal
                    lits
                }
            }
        val lits1 =
            when (val matchVal1 = this.literals) {
                null -> {
                    // If we aren't going to make it to the end of this routine
                    // where lits2 is drained, then we need to do it now.
                    lits2.drain(0 until 0)
                    return null
                }
                else -> {
                    val lits1 = matchVal1
                    lits1
                }
            }
        return Pair(lits1, lits2)
    }

    /**
     * Unions the `other` sequence into this one.
     *
     * The literals are always drained out of the given `other` sequence,
     * even if they are being unioned into an infinite sequence. This permits
     * the caller to reuse the `other` sequence in another context.
     *
     * Some literal deduping may be performed. If any deduping happens,
     * any leftmost-first or "preference" order match semantics will be
     * preserved.
     *
     * # Example
     *
     * This example shows basic usage.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.new(listOf("foo", "bar"))
     * var seq2 = Seq.new(listOf("bar", "quux", "foo"))
     * seq1.union(seq2)
     * // The literals are pulled out of seq2.
     * check(0 == seq2.len())
     * // Adjacent literals are deduped, but non-adjacent literals may not be.
     * check(Seq.new(listOf("foo", "bar", "quux", "foo")) == seq1)
     * ```
     *
     * This example shows that literals are drained from `other` even when
     * they aren't necessarily used.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.infinite()
     * // Infinite sequences have no finite length.
     * check(null == seq1.len())
     * var seq2 = Seq.new(listOf("bar", "quux", "foo"))
     * seq1.union(seq2)
     * // seq1 is still infinite and seq2 has been drained.
     * check(null == seq1.len())
     * check(0 == seq2.len())
     * ```
     */
    public open fun union(other: Seq) {
        val lits2 =
            when (val matchVal = other.literals) {
                null -> {
                    // Unioning with an infinite sequence always results in an
                    // infinite sequence.
                    this.makeInfinite()
                    return
                }
                else -> {
                    val lits = matchVal
                    lits.drain(0 until 0)
                }
            }
        val lits1 =
            when (val matchVal1 = this.literals) {
                null -> { return }
                else -> {
                    val lits1 = matchVal1
                    lits1
                }
            }
        lits1.extend(lits2)
        this.dedup()
    }

    /**
     * Unions the `other` sequence into this one by splice the `other`
     * sequence at the position of the first zero-length literal.
     *
     * This is useful for preserving preference order semantics when combining
     * two literal sequences. For example, in the regex `(a || f) + foo`, the
     * correct preference order prefix sequence is `[a, foo, f]`.
     *
     * The literals are always drained out of the given `other` sequence,
     * even if they are being unioned into an infinite sequence. This permits
     * the caller to reuse the `other` sequence in another context. Note that
     * the literals are drained even if no union is performed as well, i.e.,
     * when this sequence does not contain a zero-length literal.
     *
     * Some literal deduping may be performed. If any deduping happens,
     * any leftmost-first or "preference" order match semantics will be
     * preserved.
     *
     * # Example
     *
     * This example shows basic usage.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.new(listOf("a", "", "f", ""))
     * var seq2 = Seq.new(listOf("foo"))
     * seq1.unionIntoEmpty(seq2)
     * // The literals are pulled out of seq2.
     * check(0 == seq2.len())
     * // 'foo' gets spliced into seq1 where the first empty string occurs.
     * check(Seq.new(listOf("a", "foo", "f")) == seq1)
     * ```
     *
     * This example shows that literals are drained from `other` even when
     * they aren't necessarily used.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Seq
     *
     * var seq1 = Seq.new(listOf("foo", "bar"))
     * var seq2 = Seq.new(listOf("bar", "quux", "foo"))
     * seq1.unionIntoEmpty(seq2)
     * // seq1 has no zero length literals, so no splicing happens.
     * check(Seq.new(listOf("foo", "bar")) == seq1)
     * // Even though no splicing happens, seq2 is still drained.
     * check(0 == seq2.len())
     * ```
     */
    public open fun unionIntoEmpty(other: Seq) {
        val lits2 = other.literals?.let({ lits -> lits.drain(0 until 0) })
        val lits1 =
            when (val matchVal = this.literals) {
                null -> { return }
                else -> {
                    val lits = matchVal
                    lits
                }
            }
        val firstEmpty =
            when (val matchVal1 = lits1.iter().position({ m -> m.isEmpty() })) {
                null -> { return }
                else -> {
                    val i = matchVal1
                    i
                }
            }
        val lits21 =
            when (lits2) {
                null -> {
                    // Note that we are only here if we've found an empty literal,
                    // which implies that an infinite sequence infects this seq and
                    // also turns it into an infinite sequence.
                    this.literals = null
                    return
                }
                else -> {
                    val lits1 = lits2
                    lits1
                }
            }
        // Clearing out the empties needs to come before the splice because
        // the splice might add more empties that we don't want to get rid
        // of. Since we're splicing into the position of the first empty, the
        // 'firstEmpty' position computed above is still correct.
        lits1.retain({ m -> !m.isEmpty() })
        lits1.splice(firstEmpty until firstEmpty, lits21)
        this.dedup()
    }

    /**
     * Deduplicate adjacent equivalent literals in this sequence.
     *
     * If adjacent literals are equivalent strings but one is exact and the
     * other inexact, the inexact literal is kept and the exact one is
     * removed.
     *
     * Deduping an infinite sequence is a no-op.
     *
     * # Example
     *
     * This example shows how literals that are duplicate byte strings but
     * are not equivalent with respect to exactness are resolved.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq = Seq.fromIter(listOf(Literal.exact("foo"), Literal.inexact("foo")))
     * seq.dedup()
     * check(Seq.fromIter(listOf(Literal.inexact("foo"))) == seq)
     * ```
     */
    public open fun dedup() {
        when (val matchVal = this.literals) {
            null -> {}
            else -> {
                val lits = matchVal
                lits.dedupBy(closure@{ lit1, lit2 ->
                    if (lit1.encodeToByteArray().map { it.toUByte() } != lit2.encodeToByteArray().map { it.toUByte() }) {
                        return@closure false
                    }
                    if (lit1.isExact() != lit2.isExact()) {
                        lit1.makeInexact()
                        lit2.makeInexact()
                    }
                    true
                })
            }
        }
    }

    /**
     * Sorts this sequence of literals lexicographically.
     *
     * Note that if, before sorting, if a literal that is a prefix of another
     * literal appears after it, then after sorting, the sequence will not
     * represent the same preference order match semantics. For example,
     * sorting the sequence `[samwise, sam]` yields the sequence `[sam,
     * samwise]`. Under preference order semantics, the latter sequence will
     * never match `samwise` where as the first sequence can.
     *
     * # Example
     *
     * This example shows basic usage.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Seq
     *
     * var seq = Seq.new(listOf("foo", "quux", "bar"))
     * seq.sort()
     * check(Seq.new(listOf("bar", "foo", "quux")) == seq)
     * ```
     */
    public open fun sort() {
        when (val matchVal = this.literals) {
            null -> {}
            else -> {
                val lits = matchVal
                lits.sort()
            }
        }
    }

    /**
     * Reverses all of the literals in this sequence.
     *
     * The order of the sequence itself is preserved.
     *
     * # Example
     *
     * This example shows basic usage.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Seq
     *
     * var seq = Seq.new(listOf("oof", "rab"))
     * seq.reverseLiterals()
     * check(Seq.new(listOf("foo", "bar")) == seq)
     * ```
     */
    public open fun reverseLiterals() {
        when (val matchVal = this.literals) {
            null -> {}
            else -> {
                val lits = matchVal
                for (lit in lits.iterMut()) {
                    lit.reverse()
                }
            }
        }
    }

    /**
     * Shrinks this seq to its minimal size while respecting the preference
     * order of its literals.
     *
     * While this routine will remove duplicate literals from this seq, it
     * will also remove literals that can never match in a leftmost-first or
     * "preference order" search. Similar to [Seq.dedup], if a literal is
     * deduped, then the one that remains is made inexact.
     *
     * This is a no-op on seqs that are empty or not finite.
     *
     * # Example
     *
     * This example shows the difference between `{ sam, samwise }` and
     * `{ samwise, sam }`.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * // If 'sam' comes before 'samwise' and a preference order search is
     * // executed, then 'samwise' can never match.
     * var seq = Seq.new(listOf("sam", "samwise"))
     * seq.minimizeByPreference()
     * check(Seq.fromIter(listOf(Literal.inexact("sam"))) == seq)
     * // But if they are reversed, then it's possible for 'samwise' to match
     * // since it is given higher preference.
     * var seq1 = Seq.new(listOf("samwise", "sam"))
     * seq1.minimizeByPreference()
     * check(Seq.new(listOf("samwise", "sam")) == seq1)
     * ```
     *
     * This example shows that if an empty string is in this seq, then
     * anything that comes after it can never match.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * // An empty string is a prefix of all strings, so it automatically
     * // inhibits any subsequent strings from matching.
     * var seq = Seq.new(listOf("foo", "bar", "", "quux", "fox"))
     * seq.minimizeByPreference()
     * val expected = Seq.fromIter(listOf(Literal.exact("foo"), Literal.exact("bar"), Literal.inexact("")))
     * check(expected == seq)
     * // And of course, if it's at the beginning, then it makes it impossible
     * // for anything else to match.
     * var seq1 = Seq.new(listOf("", "foo", "quux", "fox"))
     * seq1.minimizeByPreference()
     * check(Seq.fromIter(listOf(Literal.inexact(""))) == seq1)
     * ```
     */
    public open fun minimizeByPreference() {
        when (val matchVal = this.literals) {
            null -> {}
            else -> {
                val lits = matchVal
                PreferenceTrie.minimize(lits, false)
            }
        }
    }

    /**
     * Trims all literals in this seq such that only the first `len` bytes
     * remain. If a literal has less than or equal to `len` bytes, then it
     * remains unchanged. Otherwise, it is trimmed and made inexact.
     *
     * # Example
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq = Seq.new(listOf("a", "foo", "quux"))
     * seq.keepFirstBytes(2)
     * val expected = Seq.fromIter(listOf(Literal.exact("a"), Literal.inexact("fo"), Literal.inexact("qu")))
     * check(expected == seq)
     * ```
     */
    public open fun keepFirstBytes(len: Int) {
        when (val matchVal = this.literals) {
            null -> {}
            else -> {
                val lits = matchVal
                for (m in lits.iterMut()) {
                    m.keepFirstBytes(len)
                }
            }
        }
    }

    /**
     * Trims all literals in this seq such that only the last `len` bytes
     * remain. If a literal has less than or equal to `len` bytes, then it
     * remains unchanged. Otherwise, it is trimmed and made inexact.
     *
     * # Example
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq = Seq.new(listOf("a", "foo", "quux"))
     * seq.keepLastBytes(2)
     * val expected = Seq.fromIter(listOf(Literal.exact("a"), Literal.inexact("oo"), Literal.inexact("ux")))
     * check(expected == seq)
     * ```
     */
    public open fun keepLastBytes(len: Int) {
        when (val matchVal = this.literals) {
            null -> {}
            else -> {
                val lits = matchVal
                for (m in lits.iterMut()) {
                    m.keepLastBytes(len)
                }
            }
        }
    }

    /**
     * Returns true if this sequence is finite.
     *
     * When false, this sequence is infinite and must be treated as if it
     * contains every possible literal.
     */
    public open fun isFinite(): Boolean = this.literals != null

    /**
     * Returns true if and only if this sequence is finite and empty.
     *
     * An empty sequence never matches anything. It can only be produced by
     * literal extraction when the corresponding regex itself cannot match.
     */
    public open fun isEmpty(): Boolean = this.len() == 0

    /**
     * Returns the number of literals in this sequence if the sequence is
     * finite. If the sequence is infinite, then `None` is returned.
     */
    public open fun len(): Int? = this.literals?.let({ lits -> lits.len() })

    /**
     * Returns true if and only if all literals in this sequence are exact.
     *
     * This returns false if the sequence is infinite.
     */
    public open fun isExact(): Boolean = (this.literals()?.let({ lits -> lits.iter().all({ x -> x.isExact() }) }) ?: false)

    /**
     * Returns true if and only if all literals in this sequence are inexact.
     *
     * This returns true if the sequence is infinite.
     */
    public open fun isInexact(): Boolean = (this.literals()?.let({ lits -> lits.iter().all({ x -> !x.isExact() }) }) ?: true)

    /**
     * Return the maximum length of the sequence that would result from
     * unioning `self` with `other`. If either set is infinite, then this
     * returns `None`.
     */
    public open fun maxUnionLen(other: Seq): Int? {
        val len1 = (this.len() ?: return null)
        val len2 = (other.len() ?: return null)
        return len1.saturatingAdd(len2)
    }

    /**
     * Return the maximum length of the sequence that would result from the
     * cross product of `self` with `other`. If either set is infinite, then
     * this returns `None`.
     */
    public open fun maxCrossLen(other: Seq): Int? {
        val len1 = (this.len() ?: return null)
        val len2 = (other.len() ?: return null)
        return len1.saturatingMul(len2)
    }

    /**
     * Returns the length of the shortest literal in this sequence.
     *
     * If the sequence is infinite or empty, then this returns `None`.
     */
    public open fun minLiteralLen(): Int? {
        return (this.literals ?: return null).iter().map({ x -> x.len() }).min()
    }

    /**
     * Returns the length of the longest literal in this sequence.
     *
     * If the sequence is infinite or empty, then this returns `None`.
     */
    public open fun maxLiteralLen(): Int? {
        return (this.literals ?: return null).iter().map({ x -> x.len() }).max()
    }

    /**
     * Returns the longest common prefix from this seq.
     *
     * If the seq matches any literal or other contains no literals, then
     * there is no meaningful prefix and this returns `None`.
     *
     * # Example
     *
     * This shows some example seqs and their longest common prefix.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Seq
     *
     * val seq = Seq.new(listOf("foo", "foobar", "fo"))
     * check("fo".encodeToByteArray() == seq.longestCommonPrefix())
     * val seq1 = Seq.new(listOf("foo", "foo"))
     * check("foo".encodeToByteArray() == seq1.longestCommonPrefix())
     * val seq2 = Seq.new(listOf("foo", "bar"))
     * check("".encodeToByteArray() == seq2.longestCommonPrefix())
     * val seq3 = Seq.new(listOf(""))
     * check("".encodeToByteArray() == seq3.longestCommonPrefix())
     * val seq4 = Seq.infinite()
     * check(null == seq4.longestCommonPrefix())
     * val seq5 = Seq.empty()
     * check(null == seq5.longestCommonPrefix())
     * ```
     */
    public open fun longestCommonPrefix(): List<UByte>? {
        // If we match everything or match nothing, then there's no meaningful
        // longest common prefix.
        val lits1 =
            when (val matchVal = this.literals) {
                null -> { return null }
                else -> {
                    val lits = matchVal
                    lits
                }
            }
        if (lits1.len() == 0) {
            return null
        }
        val base = lits1[0].encodeToByteArray().map { it.toUByte() }
        var len = base.size
        for (m in lits1.iter().skip(1)) {
            len = (m.encodeToByteArray().map { it.toUByte() }.zip(base[0 until len])).takeWhile({ (a, b) -> a == b }).count()
            if (len == 0) {
                return listOf()
            }
        }
        return base[0 until len]
    }

    /**
     * Returns the longest common suffix from this seq.
     *
     * If the seq matches any literal or other contains no literals, then
     * there is no meaningful suffix and this returns `None`.
     *
     * # Example
     *
     * This shows some example seqs and their longest common suffix.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Seq
     *
     * val seq = Seq.new(listOf("oof", "raboof", "of"))
     * check("of".encodeToByteArray() == seq.longestCommonSuffix())
     * val seq1 = Seq.new(listOf("foo", "foo"))
     * check("foo".encodeToByteArray() == seq1.longestCommonSuffix())
     * val seq2 = Seq.new(listOf("foo", "bar"))
     * check("".encodeToByteArray() == seq2.longestCommonSuffix())
     * val seq3 = Seq.new(listOf(""))
     * check("".encodeToByteArray() == seq3.longestCommonSuffix())
     * val seq4 = Seq.infinite()
     * check(null == seq4.longestCommonSuffix())
     * val seq5 = Seq.empty()
     * check(null == seq5.longestCommonSuffix())
     * ```
     */
    public open fun longestCommonSuffix(): List<UByte>? {
        // If we match everything or match nothing, then there's no meaningful
        // longest common suffix.
        val lits1 =
            when (val matchVal = this.literals) {
                null -> { return null }
                else -> {
                    val lits = matchVal
                    lits
                }
            }
        if (lits1.len() == 0) {
            return null
        }
        val base = lits1[0].encodeToByteArray().map { it.toUByte() }
        var len = base.size
        for (m in lits1.iter().skip(1)) {
            len = (m.encodeToByteArray().map { it.toUByte() }.iter().rev().zip(base[base.size - len until base.size].iter().rev())).takeWhile({ (a, b) -> a == b }).count()
            if (len == 0) {
                return listOf()
            }
        }
        return base[base.size - len until base.size]
    }

    /**
     * Optimizes this seq while treating its literals as prefixes and
     * respecting the preference order of its literals.
     *
     * The specific way "optimization" works is meant to be an implementation
     * detail, as it essentially represents a set of heuristics. The goal
     * that optimization tries to accomplish is to make the literals in this
     * set reflect inputs that will result in a more effective prefilter.
     * Principally by reducing the false positive rate of candidates found by
     * the literals in this sequence. That is, when a match of a literal is
     * found, we would like it to be a strong predictor of the overall match
     * of the regex. If it isn't, then much time will be spent starting and
     * stopping the prefilter search and attempting to confirm the match only
     * to have it fail.
     *
     * Some of those heuristics might be:
     *
     * * Identifying a common prefix from a larger sequence of literals, and
     * shrinking the sequence down to that single common prefix.
     * * Rejecting the sequence entirely if it is believed to result in very
     * high false positive rate. When this happens, the sequence is made
     * infinite.
     * * Shrinking the sequence to a smaller number of literals representing
     * prefixes, but not shrinking it so much as to make literals too short.
     * (A sequence with very short literals, of 1 or 2 bytes, will typically
     * result in a higher false positive rate.)
     *
     * Optimization should only be run once extraction is complete. Namely,
     * optimization may make assumptions that do not compose with other
     * operations in the middle of extraction. For example, optimization will
     * reduce `[E(sam), E(samwise)]` to `[E(sam)]`, but such a transformation
     * is only valid if no other extraction will occur. If other extraction
     * may occur, then the correct transformation would be to `[I(sam)]`.
     *
     * The [Seq.optimizeForSuffixByPreference] does the same thing, but
     * for suffixes.
     *
     * # Example
     *
     * This shows how optimization might transform a sequence. Note that
     * the specific behavior is not a documented guarantee. The heuristics
     * used are an implementation detail and may change over time in semver
     * compatible releases.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq = Seq.new(listOf("samantha", "sam", "samwise", "frodo"))
     * seq.optimizeForPrefixByPreference()
     * check(Seq.fromIter(listOf(Literal.exact("samantha"), Literal.exact("sam"), Literal.exact("frodo"))) == seq)
     * ```
     *
     * # Example: optimization may make the sequence infinite
     *
     * If the heuristics deem that the sequence could cause a very high false
     * positive rate, then it may make the sequence infinite, effectively
     * disabling its use as a prefilter.
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq = Seq.new(listOf("samantha", "", "sam", "samwise", "frodo"))
     * seq.optimizeForPrefixByPreference()
     * check(!seq.isFinite())
     * ```
     *
     * Do note that just because there is a `" "` in the sequence, that
     * doesn't mean the sequence will always be made infinite after it is
     * optimized. Namely, if the sequence is considered exact (any match
     * corresponds to an overall match of the original regex), then any match
     * is an overall match, and so the false positive rate is always `0`.
     *
     * To demonstrate this, we remove `samwise` from our sequence. This
     * results in no optimization happening and all literals remain exact.
     * Thus the entire sequence is exact, and it is kept as-is, even though
     * one is an ASCII space:
     *
     * ```kotlin
     * import regexsyntax.hir.literal.Literal
     * import regexsyntax.hir.literal.Seq
     *
     * var seq = Seq.new(listOf("samantha", " ", "sam", "frodo"))
     * seq.optimizeForPrefixByPreference()
     * check(seq.isFinite())
     * ```
     */
    public open fun optimizeForPrefixByPreference() {
        this.optimizeByPreference(true)
    }

    /**
     * Optimizes this seq while treating its literals as suffixes and
     * respecting the preference order of its literals.
     *
     * Optimization should only be run once extraction is complete.
     *
     * The [Seq.optimizeForPrefixByPreference] does the same thing, but
     * for prefixes. See its documentation for more explanation.
     */
    public open fun optimizeForSuffixByPreference() {
        this.optimizeByPreference(false)
    }

    public open fun optimizeByPreference(prefix: Boolean) {
        // Now we attempt to shorten the sequence. The idea here is that we
        // don't want to look for too many literals, but we want to shorten
        // our sequence enough to improve our odds of using better algorithms
        // downstream (such as Teddy).
        //
        // The pair of numbers in this list corresponds to the maximal prefix
        // (in bytes) to keep for all literals and the length of the sequence
        // at which to do it.
        //
        // So for example, the pair (3, 500) would mean, "if we have more than
        // 500 literals in our sequence, then truncate all of our literals
        // such that they are at most 3 bytes in length and the minimize the
        // sequence."
        val ATTEMPTS: List<Pair<Int, Int>> = listOf(Pair(5, 10), Pair(4, 10), Pair(3, 64), Pair(2, 64), Pair(1, 10))
        val origlen =
            when (val matchVal = this.len()) {
                null -> { return }
                else -> {
                    val len = matchVal
                    len
                }
            }
        // Just give up now if our sequence contains an empty string.
        if ((this.minLiteralLen()?.let({ len1 -> len1 == 0 }) ?: false)) {
            // We squash the sequence so that nobody else gets any bright
            // ideas to try and use it. An empty string implies a match at
            // every position. A prefilter cannot help you here.
            this.makeInfinite()
            return
        }
        // Make sure we start with the smallest sequence possible. We use a
        // special version of preference minimization that retains exactness.
        // This is legal because optimization is only expected to occur once
        // extraction is complete.
        if (prefix) {
            when (val matchVal1 = this.literals) {
                null -> {}
                else -> {
                    val lits = matchVal1
                    PreferenceTrie.minimize(lits, true)
                }
            }
        }
        // Look for a common prefix (or suffix). If we found one of those and
        // it's long enough, then it's a good bet that it will be our fastest
        // possible prefilter since single-substring search is so fast.
        val fix =
            if (prefix) {
                this.longestCommonPrefix()
            } else {
                this.longestCommonSuffix()
            }
        if (fix != null) {
            val fix1 = fix
            // As a special case, if we have a common prefix and the leading
            // byte of that prefix is one that we think probably occurs rarely,
            // then strip everything down to just that single byte. This should
            // promote the use of memchr.
            //
            // ... we only do this though if our sequence has more than one
            // literal. Otherwise, we'd rather just stick with a single literal
            // scan. That is, using memchr is probably better than looking
            // for 2 or more literals, but probably not as good as a straight
            // memmem search.
            //
            // ... and also only do this when the prefix is short and probably
            // not too discriminatory anyway. If it's longer, then it's
            // probably quite discriminatory and thus is likely to have a low
            // false positive rate.
            if (prefix && origlen > 1 && fix1.size >= 1 && fix1.size <= 3 && rank(fix1[0]) < 200u.toUByte()) {
                this.keepFirstBytes(1)
                this.dedup()
                return
            }
            // We only strip down to the common prefix/suffix if we think
            // the existing set of literals isn't great, or if the common
            // prefix/suffix is expected to be particularly discriminatory.
            val isfast = this.isExact() && this.len().mapOr(false, { len1 -> len1 <= 16 })
            val usefix = fix1.size > 4 || (fix1.size > 1 && !isfast)
            if (usefix) {
                // If we keep exactly the number of bytes equal to the length
                // of the prefix (or suffix), then by the definition of a
                // prefix, every literal in the sequence will be equivalent.
                // Thus, 'dedup' will leave us with one literal.
                //
                // We do it this way to avoid an alloc, but also to make sure
                // the exactness of literals is kept (or not).
                if (prefix) {
                    this.keepFirstBytes(fix1.size)
                } else {
                    this.keepLastBytes(fix1.size)
                }
                this.dedup()
                check(1 == this.len())
                // We still fall through here. In particular, we want our
                // longest common prefix to be subject to the poison check.
            }
        }
        // If we have an exact sequence, we *probably* just want to keep it
        // as-is. But there are some cases where we don't. So we save a copy of
        // the exact sequence now, and then try to do some more optimizations
        // below. If those don't work out, we go back to this exact sequence.
        //
        // The specific motivation for this is that we sometimes wind up with
        // an exact sequence with a hefty number of literals. Say, 100. If we
        // stuck with that, it would be too big for Teddy and would result in
        // using Aho-Corasick. Which is fine... but the lazy DFA is plenty
        // suitable in such cases. The real issue is that we will wind up not
        // using a fast prefilter at all. So in cases like this, even though
        // we have an exact sequence, it would be better to try and shrink the
        // sequence (which we do below) and use it as a prefilter that can
        // produce false positive matches.
        //
        // But if the shrinking below results in a sequence that "sucks," then
        // we don't want to use that because we already have an exact sequence
        // in hand.
        val exact: Seq? =
            if (this.isExact()) {
                this.clone()
            } else {
                null
            }
        for ((keep, limit) in ATTEMPTS) {
            val len2 =
                when (val matchVal2 = this.len()) {
                    null -> { break }
                    else -> {
                        val len1 = matchVal2
                        len1
                    }
                }
            if (len2 <= limit) {
                break
            }
            if (prefix) {
                this.keepFirstBytes(keep)
            } else {
                this.keepLastBytes(keep)
            }
            if (prefix) {
                when (val matchVal3 = this.literals) {
                    null -> {}
                    else -> {
                        val lits1 = matchVal3
                        PreferenceTrie.minimize(lits1, true)
                    }
                }
            }
        }
        // Check for a poison literal. A poison literal is one that is short
        // and is believed to have a very high match count. These poisons
        // generally lead to a prefilter with a very high false positive rate,
        // and thus overall worse performance.
        //
        // We do this last because we could have gone from a non-poisonous
        // sequence to a poisonous one. Perhaps we should add some code to
        // prevent such transitions in the first place, but then again, we
        // likely only made the transition in the first place if the sequence
        // was itself huge. And huge sequences are themselves poisonous. So...
        when (val matchVal4 = this.literals()) {
            null -> {}
            else -> {
                val lits2 = matchVal4
                if (lits2.iter().any({ lit -> lit.isPoisonous() })) {
                    this.makeInfinite()
                }
            }
        }
        // OK, if we had an exact sequence before attempting more optimizations
        // above and our post-optimized sequence sucks for some reason or
        // another, then we go back to the exact sequence.
        if (exact != null) {
            val exact1 = exact
            // If optimizing resulted in dropping our literals, then certainly
            // backup and use the exact sequence that we had.
            if (!this.isFinite()) {
                this = exact1
                return
            }
            // If our optimized sequence contains a short literal, then it's
            // *probably* not so great. So throw it away and revert to the
            // exact sequence.
            if ((this.minLiteralLen()?.let({ len3 -> len3 <= 2 }) ?: true)) {
                this = exact1
                return
            }
            // Finally, if our optimized sequence is "big" (i.e., can't use
            // Teddy), then also don't use it and rely on the exact sequence.
            if (this.len().mapOr(true, { len3 -> len3 > 64 })) {
                this = exact1
                return
            }
        }
    }

    public open fun fmt(f: StringBuilder) {
        f.append("Seq").getOrElse { return Result.failure(it) }
        when (val matchVal = this.literals()) {
            null -> {
                f.append("[∞]")
            }
            else -> {
                val lits = matchVal
                object { val entries = mutableListOf<Any?>(); fun entry(v: Any?) = apply { entries.add(v) }; fun entries(iter: Iterable<*>) = apply { for (e in iter) entries.add(e) }; fun finish() { f.append(entries.joinToString(", ", "[", "]")) } }.entries(lits.iter()).finish()
            }
        }
    }

    public companion object {
        /**
         * Returns an empty sequence.
         *
         * An empty sequence matches zero literals, and thus corresponds to a
         * regex that itself can never match.
         */
        public fun empty(): Seq = Seq(literals = mutableListOf())

        /**
         * Returns a sequence of literals without a finite size and may contain
         * any literal.
         *
         * A sequence without finite size does not reveal anything about the
         * characteristics of the literals in its set. There are no fixed prefixes
         * or suffixes, nor are lower or upper bounds on the length of the literals
         * in the set known.
         *
         * This is useful to represent constructs in a regex that are "too big"
         * to useful represent as a sequence of literals. For example, `[A - Za - z]`.
         * When sequences get too big, they lose their discriminating nature and
         * are more likely to produce false positives, which in turn makes them
         * less likely to speed up searches.
         *
         * More pragmatically, for many regexes, enumerating all possible literals
         * is itself not possible or might otherwise use too many resources. So
         * constraining the size of sets during extraction is a practical trade
         * off to make.
         */
        public fun infinite(): Seq = Seq(literals = null)

        /**
         * Returns a sequence containing a single literal.
         */
        public fun singleton(lit: Literal): Seq = Seq(literals = mutableListOf(lit))

        /**
         * Returns a sequence of exact literals from the given byte strings.
         */
        public fun <I, B> new(it: I): Seq where I : Iterable<B>, B : AsRef<List<UByte>> = it.iterator().map({ b -> Literal.exact(b.asRef()) }).collect()

        public fun <T : Iterable<Literal>> fromIter(it: T): Seq {
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
 *
 * A literal is composed of two things:
 *
 * * A sequence of bytes. No guarantees with respect to UTF-8 are provided.
 * In particular, even if the regex a literal is extracted from is UTF-8, the
 * literal extracted may not be valid UTF-8. (For example, if an [Extractor]
 * limit resulted in trimming a literal in a way that splits a codepoint.)
 * * Whether the literal is "exact" or not. An "exact" literal means that it
 * has not been trimmed, and may continue to be extended. If a literal is
 * "exact" after visiting the entire `Hir` expression, then this implies that
 * the literal leads to a match state. (Although it doesn't necessarily imply
 * all occurrences of the literal correspond to a match of the regex, since
 * literal extraction ignores look-around assertions.)
 */
public open class Literal(
    public var bytes: MutableList<UByte>,
    public var exact: Boolean,
) : From<UByte>, AsRef<List<UByte>> {
    /**
     * Returns the bytes in this literal.
     */
    public open fun asBytes(): List<UByte> = this.bytes

    /**
     * Yields ownership of the bytes inside this literal.
     *
     * Note that this throws away whether the literal is "exact" or not.
     */
    public open fun intoBytes(): List<UByte> = this.bytes

    /**
     * Returns the length of this literal in bytes.
     */
    public open fun len(): Int = this.encodeToByteArray().map { it.toUByte() }.size

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
     *
     * Inexact literals can never be extended. For example,
     * [Seq.crossForward] will not extend inexact literals.
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
        this.bytes.extendFromSlice(lit.bytes)
    }

    /**
     * Trims this literal such that only the first `len` bytes remain. If
     * this literal has fewer than `len` bytes, then it remains unchanged.
     * Otherwise, the literal is marked as inexact.
     */
    public open fun keepFirstBytes(len: Int) {
        if (len >= this.len()) {
            return
        }
        this.makeInexact()
        this.bytes.truncate(len)
    }

    /**
     * Trims this literal such that only the last `len` bytes remain. If this
     * literal has fewer than `len` bytes, then it remains unchanged.
     * Otherwise, the literal is marked as inexact.
     */
    public open fun keepLastBytes(len: Int) {
        if (len >= this.len()) {
            return
        }
        this.makeInexact()
        this.bytes.drain(0 until this.len() - len)
    }

    /**
     * Returns true if it is believe that this literal is likely to match very
     * frequently, and is thus not a good candidate for a prefilter.
     */
    public open fun isPoisonous(): Boolean = this.isEmpty() || (this.len() == 1 && rank(this.encodeToByteArray().map { it.toUByte() }[0]) >= 250u.toUByte())

    public open fun asRef(): List<UByte> = this.encodeToByteArray().map { it.toUByte() }

    public open fun fmt(f: StringBuilder) {
        val tag =
            if (this.exact) {
                "E"
            } else {
                "I"
            }
        DebugTuple(f, tag).field(Bytes(this.encodeToByteArray().map { it.toUByte() })).finish()
    }

    public companion object {
        /**
         * Returns a new exact literal containing the bytes given.
         */
        public fun exact(bytes: List<UByte>): Literal = Literal(bytes = bytes, exact = true)

        /**
         * Returns a new inexact literal containing the bytes given.
         */
        public fun inexact(bytes: List<UByte>): Literal = Literal(bytes = bytes, exact = false)

        public fun from(byte: UByte): Literal = Literal.exact(mutableListOf(byte))

        public fun from(ch: Char): Literal = Literal.exact(ch.encodeUtf8(List(4) { 0 }).toString())
    }
}

/**
 * A "preference" trie that rejects literals that will never match when
 * executing a leftmost first or "preference" search.
 *
 * For example, if 'sam' is inserted, then trying to insert 'samwise' will be
 * rejected because 'samwise' can never match since 'sam' will always take
 * priority. However, if 'samwise' is inserted first, then inserting 'sam'
 * after it is accepted. In this case, either 'samwise' or 'sam' can match in
 * a "preference" search.
 *
 * Note that we only use this trie as a "set." That is, given a sequence of
 * literals, we insert each one in order. An `insert` will reject a literal
 * if a prefix of that literal already exists in the trie. Thus, to rebuild
 * the "minimal" sequence, we simply only keep literals that were successfully
 * inserted. (Since we don't need traversal, one wonders whether we can make
 * some simplifications here, but I haven't given it a ton of thought and I've
 * never seen this show up on a profile. Because of the heuristic limits
 * imposed on literal extractions, the size of the inputs here is usually
 * very small.)
 */
public open class PreferenceTrie(
    /**
     * The states in this trie. The index of a state in this vector is its ID.
     */
    public var states: MutableList<State>,
    /**
     * This vec indicates which states are match states. It always has
     * the same length as `states` and is indexed by the same state ID.
     * A state with identifier `sid` is a match state if and only if
     * `matches[sid].isSome()`. The option contains the index of the literal
     * corresponding to the match. The index is offset by 1 so that it fits in
     * a NonZeroUsize.
     */
    public var matches: MutableList<NonZeroUsize?>,
    /**
     * The index to allocate to the next literal added to this trie. Starts at
     * 1 and increments by 1 for every literal successfully added to the trie.
     */
    public var nextLiteralIndex: Int,
) {
    /**
     * Returns `Ok` if the given byte string is accepted into this trie and
     * `Err` otherwise. The index for the success case corresponds to the
     * index of the literal added. The index for the error case corresponds to
     * the index of the literal already in the trie that prevented the given
     * byte string from being added. (Which implies it is a prefix of the one
     * given.)
     *
     * In short, the byte string given is accepted into the trie if and only
     * if it is possible for it to match when executing a preference order
     * search.
     */
    public open fun insert(bytes: List<UByte>): Result<Int> {
        var prev = this.root()
        when (val matchVal = this.matches[prev]) {
            null -> {}
            else -> {
                val idx = matchVal
                return Result.failure(idx.get())
            }
        }
        for (b in bytes) {
            run {
                val matchVal1 = this.states[prev].trans.binarySearchByKey(b, { t -> t.first })
                if (matchVal1.isSuccess) {
                    val i = matchVal1.getOrThrow()
                    prev = this.states[prev].trans[i].second
                    when (val matchVal2 = this.matches[prev]) {
                        null -> {}
                        else -> {
                            val idx1 = matchVal2
                            return Result.failure(idx1.get())
                        }
                    }
                } else {
                    val i1 = matchVal1.exceptionOrNull()!!
                    val next = this.createState()
                    this.states[prev].trans.insert(i1, Pair(b, next))
                    prev = next
                }
            }
        }
        val idx2 = this.nextLiteralIndex
        this.nextLiteralIndex += 1
        this.matches[prev] = NonZeroUsize.new(idx2)
        return Result.success(idx2)
    }

    /**
     * Returns the root state ID, and if it doesn't exist, creates it.
     */
    public open fun root(): Int =
        if (!this.states.isEmpty()) {
            0
        } else {
            this.createState()
        }

    /**
     * Creates a new empty state and returns its ID.
     */
    public open fun createState(): Int {
        val id = this.states.size
        this.states.add(State.default())
        this.matches.add(null)
        return id
    }

    public companion object {
        /**
         * Minimizes the given sequence of literals while preserving preference
         * order semantics.
         *
         * When `keepExact` is true, the exactness of every literal retained is
         * kept. This is useful when dealing with a fully extracted `Seq` that
         * only contains exact literals. In that case, we can keep all retained
         * literals as exact because we know we'll never need to match anything
         * after them and because any removed literals are guaranteed to never
         * match.
         */
        public fun minimize(literals: List<Literal>, keepExact: Boolean) {
            val trie = PreferenceTrie(states = mutableListOf(), matches = mutableListOf(), nextLiteralIndex = 1)
            val makeInexact = mutableListOf()
            literals.retainMut({ lit -> run {
                val matchVal = trie.insert(lit.encodeToByteArray().map { it.toUByte() })
                if (matchVal.isSuccess) {
                    true
                } else {
                    val i = matchVal.exceptionOrNull()!!
                    if (!keepExact) {
                        makeInexact.add((((i).toLong() - (1).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!)
                    }
                    false
                }
            } })
            for (i in makeInexact) {
                literals[i].makeInexact()
            }
        }
    }
}

/**
 * A single state in a trie. Uses a sparse representation for its transitions.
 */
public open class State(
    /**
     * Sparse representation of the transitions out of this state. Transitions
     * are sorted by byte. There is at most one such transition for any
     * particular byte.
     */
    public var trans: List<Pair<UByte, Int>>,
)

/**
 * Returns the "rank" of the given byte.
 *
 * The minimum rank value is `0` and the maximum rank value is `255`.
 *
 * The rank of a byte is derived from a heuristic background distribution of
 * relative frequencies of bytes. The heuristic says that lower the rank of a
 * byte, the less likely that byte is to appear in any arbitrary haystack.
 */
public fun rank(byte: UByte): UByte = BYTE_FREQUENCIES[Int.from(byte)]
