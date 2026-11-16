// port-lint: tests hir/literal.rs
package io.github.kotlinmania.regexsyntax.hir.literal

import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.parser.ParserBuilder
import io.github.kotlinmania.regexsyntax.unwrap

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

public fun parse(pattern: String): Hir = ParserBuilder.new().utf8(false).build().parse(pattern).unwrap()

public fun prefixes(pattern: String): Seq = Extractor.new().kind(ExtractKind.Prefix).extract(parse(pattern))

public fun suffixes(pattern: String): Seq = Extractor.new().kind(ExtractKind.Suffix).extract(parse(pattern))

public fun e(pattern: String): Pair<Seq, Seq> = Pair(prefixes(pattern), suffixes(pattern))

public fun eLit(x: String): Literal = Literal.exact(x.encodeToByteArray().map { it.toUByte() })

public fun E(x: String): Literal = eLit(x)

public fun i(x: String): Literal = Literal.inexact(x.encodeToByteArray().map { it.toUByte() })

public fun I(x: String): Literal = i(x)

public fun seq(it: List<Literal>): Seq = Seq.fromIter(it)

public fun infinite(): Pair<Seq, Seq> = Pair(Seq.infinite(), Seq.infinite())

public fun inexact(it1: List<Literal>, it2: List<Literal>): Pair<Seq, Seq> = Pair(Seq.fromIter(it1), Seq.fromIter(it2))

public fun exact(it: List<String>): Pair<Seq, Seq> {
    val s1 = Seq.fromStrings(it)
    val s2 = s1.clone()
    return Pair(s1, s2)
}

public fun exactBytes(it: List<List<UByte>>): Pair<Seq, Seq> {
    val s1 = Seq.new(it)
    val s2 = s1.clone()
    return Pair(s1, s2)
}

public fun opt(it: List<String>): Pair<Seq, Seq> {
    val (p, s) = exact(it)
    p.optimizeForPrefixByPreference()
    s.optimizeForSuffixByPreference()
    return Pair(p, s)
}

@kotlin.test.Test
public fun literal() {
    check(exact(listOf("a")) == e("a"))
    check(exact(listOf("aaaaa")) == e("aaaaa"))
    check(exact(listOf("A", "a")) == e("(?i-u)a"))
    check(exact(listOf("AB", "Ab", "aB", "ab")) == e("(?i-u)ab"))
    check(exact(listOf("abC", "abc")) == e("ab(?i-u)c"))
    check(exactBytes(listOf(listOf(0xFFu.toUByte()))) == e("(?-u:\\xFF)"))
    run {
        check(exact(listOf("☃")) == e("☃"))
        check(exact(listOf("☃")) == e("(?i)☃"))
        check(exact(listOf("☃☃☃☃☃")) == e("☃☃☃☃☃"))
        check(exact(listOf("Δ")) == e("Δ"))
        check(exact(listOf("δ")) == e("δ"))
        check(exact(listOf("Δ", "δ")) == e("(?i)Δ"))
        check(exact(listOf("Δ", "δ")) == e("(?i)δ"))
        check(exact(listOf("S", "s", "ſ")) == e("(?i)S"))
        check(exact(listOf("S", "s", "ſ")) == e("(?i)s"))
        check(exact(listOf("S", "s", "ſ")) == e("(?i)ſ"))
    }
    val letters = "ͱͳͷΐάέήίΰαβγδεζηθικλμνξοπρςστυφχψωϊϋ"
    check(exact(listOf(letters)) == e(letters))
}

@kotlin.test.Test
public fun cls() {
    check(exact(listOf("a", "b", "c")) == e("[abc]"))
    check(exact(listOf("a1b", "a2b", "a3b")) == e("a[123]b"))
    check(exact(listOf("δ", "ε")) == e("[εδ]"))
    run {
        check(exact(listOf("Δ", "Ε", "δ", "ε", "ϵ")) == e("(?i)[εδ]"))
    }
}

@kotlin.test.Test
public fun look() {
    check(exact(listOf("ab")) == e("a\\Ab"))
    check(exact(listOf("ab")) == e("a\\zb"))
    check(exact(listOf("ab")) == e("a(?m:^)b"))
    check(exact(listOf("ab")) == e("a(?m:\$)b"))
    check(exact(listOf("ab")) == e("a\\bb"))
    check(exact(listOf("ab")) == e("a\\Bb"))
    check(exact(listOf("ab")) == e("a(?-u:\\b)b"))
    check(exact(listOf("ab")) == e("a(?-u:\\B)b"))
    check(exact(listOf("ab")) == e("^ab"))
    check(exact(listOf("ab")) == e("\$ab"))
    check(exact(listOf("ab")) == e("(?m:^)ab"))
    check(exact(listOf("ab")) == e("(?m:\$)ab"))
    check(exact(listOf("ab")) == e("\\bab"))
    check(exact(listOf("ab")) == e("\\Bab"))
    check(exact(listOf("ab")) == e("(?-u:\\b)ab"))
    check(exact(listOf("ab")) == e("(?-u:\\B)ab"))
    check(exact(listOf("ab")) == e("ab^"))
    check(exact(listOf("ab")) == e("ab\$"))
    check(exact(listOf("ab")) == e("ab(?m:^)"))
    check(exact(listOf("ab")) == e("ab(?m:\$)"))
    check(exact(listOf("ab")) == e("ab\\b"))
    check(exact(listOf("ab")) == e("ab\\B"))
    check(exact(listOf("ab")) == e("ab(?-u:\\b)"))
    check(exact(listOf("ab")) == e("ab(?-u:\\B)"))
    val expected = Pair(seq(listOf(I("aZ"), E("ab"))), seq(listOf(I("Zb"), E("ab"))))
    check(expected == e("^aZ*b"))
}

@kotlin.test.Test
public fun repetition() {
    check(exact(listOf("a", "")) == e("a?"))
    check(exact(listOf("", "a")) == e("a??"))
    check(inexact(listOf(I("a"), E("")), listOf(I("a"), E(""))) == e("a*"))
    check(inexact(listOf(E(""), I("a")), listOf(E(""), I("a"))) == e("a*?"))
    check(inexact(listOf(I("a")), listOf(I("a"))) == e("a+"))
    check(inexact(listOf(I("a")), listOf(I("a"))) == e("(a+)+"))
    check(exact(listOf("ab")) == e("aZ{0}b"))
    check(exact(listOf("aZb", "ab")) == e("aZ?b"))
    check(exact(listOf("ab", "aZb")) == e("aZ??b"))
    check(inexact(listOf(I("aZ"), E("ab")), listOf(I("Zb"), E("ab"))) == e("aZ*b"))
    check(inexact(listOf(E("ab"), I("aZ")), listOf(E("ab"), I("Zb"))) == e("aZ*?b"))
    check(inexact(listOf(I("aZ")), listOf(I("Zb"))) == e("aZ+b"))
    check(inexact(listOf(I("aZ")), listOf(I("Zb"))) == e("aZ+?b"))
    check(exact(listOf("aZZb")) == e("aZ{2}b"))
    check(inexact(listOf(I("aZZ")), listOf(I("ZZb"))) == e("aZ{2,3}b"))
    check(exact(listOf("abc", "")) == e("(abc)?"))
    check(exact(listOf("", "abc")) == e("(abc)??"))
    check(inexact(listOf(I("a"), E("b")), listOf(I("ab"), E("b"))) == e("a*b"))
    check(inexact(listOf(E("b"), I("a")), listOf(E("b"), I("ab"))) == e("a*?b"))
    check(inexact(listOf(I("ab")), listOf(I("b"))) == e("ab+"))
    check(inexact(listOf(I("a"), I("b")), listOf(I("b"))) == e("a*b+"))
    // Note: The suffixes for this don't look quite right to me. I think
    // the right suffixes would be: [I(ac), I(bc), E(c)]. The main issue I
    // think is that suffixes are computed by iterating over concatenations
    // in reverse, and then [bc, ac, c] ordering is indeed correct from
    // that perspective. We also test a few more equivalent regexes, and
    // we get the same result, so it is consistent at least I suppose.
    //
    // The reason why this isn't an issue is that it only messes up
    // preference order, and currently, suffixes are never used in a
    // context where preference order matters. For prefixes it matters
    // because we sometimes want to use prefilters without confirmation
    // when all of the literals are exact (and there's no look-around). But
    // we never do that for suffixes. Any time we use suffixes, we always
    // include a confirmation step. If that ever changes, then it's likely
    // this bug will need to be fixed, but last time I looked, it appears
    // hard to do so.
    check(inexact(listOf(I("a"), I("b"), E("c")), listOf(I("bc"), I("ac"), E("c"))) == e("a*b*c"))
    check(inexact(listOf(I("a"), I("b"), E("c")), listOf(I("bc"), I("ac"), E("c"))) == e("(a+)?(b+)?c"))
    check(inexact(listOf(I("a"), I("b"), E("c")), listOf(I("bc"), I("ac"), E("c"))) == e("(a+|)(b+|)c"))
    // A few more similarish but not identical regexes. These may have a
    // similar problem as above.
    check(inexact(listOf(I("a"), I("b"), I("c"), E("")), listOf(I("c"), I("b"), I("a"), E(""))) == e("a*b*c*"))
    check(inexact(listOf(I("a"), I("b"), I("c")), listOf(I("c"))) == e("a*b*c+"))
    check(inexact(listOf(I("a"), I("b")), listOf(I("bc"))) == e("a*b+c"))
    check(inexact(listOf(I("a"), I("b")), listOf(I("c"), I("b"))) == e("a*b+c*"))
    check(inexact(listOf(I("ab"), E("a")), listOf(I("b"), E("a"))) == e("ab*"))
    check(inexact(listOf(I("ab"), E("ac")), listOf(I("bc"), E("ac"))) == e("ab*c"))
    check(inexact(listOf(I("ab")), listOf(I("b"))) == e("ab+"))
    check(inexact(listOf(I("ab")), listOf(I("bc"))) == e("ab+c"))
    check(inexact(listOf(I("z"), E("azb")), listOf(I("zazb"), E("azb"))) == e("z*azb"))
    val expected = exact(listOf("aaa", "aab", "aba", "abb", "baa", "bab", "bba", "bbb"))
    check(expected == e("[ab]{3}"))
    val expected1 = inexact(listOf(I("aaa"), I("aab"), I("aba"), I("abb"), I("baa"), I("bab"), I("bba"), I("bbb")), listOf(I("aaa"), I("aab"), I("aba"), I("abb"), I("baa"), I("bab"), I("bba"), I("bbb")))
    check(expected1 == e("[ab]{3,4}"))
}

@kotlin.test.Test
public fun concat() {
    val empty: List<String> = listOf()
    check(exact(listOf("abcxyz")) == e("abc()xyz"))
    check(exact(listOf("abcxyz")) == e("(abc)(xyz)"))
    check(exact(listOf("abcmnoxyz")) == e("abc()mno()xyz"))
    check(exact(empty) == e("abc[a&&b]xyz"))
    check(exact(listOf("abcxyz")) == e("abc[a&&b]*xyz"))
}

@kotlin.test.Test
public fun alternation() {
    check(exact(listOf("abc", "mno", "xyz")) == e("abc|mno|xyz"))
    check(inexact(listOf(E("abc"), I("mZ"), E("mo"), E("xyz")), listOf(E("abc"), I("Zo"), E("mo"), E("xyz"))) == e("abc|mZ*o|xyz"))
    check(exact(listOf("abc", "xyz")) == e("abc|M[a&&b]N|xyz"))
    check(exact(listOf("abc", "MN", "xyz")) == e("abc|M[a&&b]*N|xyz"))
    check(exact(listOf("aaa", "aaaaa")) == e("(?:|aa)aaa"))
    check(inexact(listOf(I("aaa"), E(""), I("aaaaa"), E("aa")), listOf(I("aaa"), E(""), E("aa"))) == e("(?:|aa)(?:aaa)*"))
    check(inexact(listOf(E(""), I("aaa"), E("aa"), I("aaaaa")), listOf(E(""), I("aaa"), E("aa"))) == e("(?:|aa)(?:aaa)*?"))
    check(inexact(listOf(E("a"), I("b"), E("")), listOf(E("a"), I("b"), E(""))) == e("a|b*"))
    check(inexact(listOf(E("a"), I("b")), listOf(E("a"), I("b"))) == e("a|b+"))
    check(inexact(listOf(I("a"), E("b"), E("c")), listOf(I("ab"), E("b"), E("c"))) == e("a*b|c"))
    check(inexact(listOf(E("a"), E("b"), I("c"), E("")), listOf(E("a"), E("b"), I("c"), E(""))) == e("a|(?:b|c*)"))
    check(inexact(listOf(I("a"), I("b"), E("c"), I("a"), I("ab"), E("c")), listOf(I("ac"), I("bc"), E("c"), I("ac"), I("abc"), E("c"))) == e("(a|b)*c|(a|ab)*c"))
    check(exact(listOf("abef", "abgh", "cdef", "cdgh")) == e("(ab|cd)(ef|gh)"))
    check(exact(listOf("abefij", "abefkl", "abghij", "abghkl", "cdefij", "cdefkl", "cdghij", "cdghkl")) == e("(ab|cd)(ef|gh)(ij|kl)"))
    check(inexact(listOf(E("abab")), listOf(E("abab"))) == e("(ab){2}"))
    check(inexact(listOf(I("abab")), listOf(I("abab"))) == e("(ab){2,3}"))
    check(inexact(listOf(I("abab")), listOf(I("abab"))) == e("(ab){2,}"))
}

@kotlin.test.Test
public fun impossible() {
    val empty: List<String> = listOf()
    check(exact(empty) == e("[a&&b]"))
    check(exact(empty) == e("a[a&&b]"))
    check(exact(empty) == e("[a&&b]b"))
    check(exact(empty) == e("a[a&&b]b"))
    check(exact(listOf("a", "b")) == e("a|[a&&b]|b"))
    check(exact(listOf("a", "b")) == e("a|c[a&&b]|b"))
    check(exact(listOf("a", "b")) == e("a|[a&&b]d|b"))
    check(exact(listOf("a", "b")) == e("a|c[a&&b]d|b"))
    check(exact(listOf("")) == e("[a&&b]*"))
    check(exact(listOf("MN")) == e("M[a&&b]*N"))
}

// This tests patterns that contain something that defeats literal
// detection, usually because it would blow some limit on the total number
// of literals that can be returned.
//
// The main idea is that when literal extraction sees something that
// it knows will blow a limit, it replaces it with a marker that says
// "any literal will match here." While not necessarily true, the
// over-estimation is just fine for the purposes of literal extraction,
// because the imprecision doesn't matter: too big is too big.
//
// This is one of the trickier parts of literal extraction, since we need
// to make sure all of our literal extraction operations correctly compose
// with the markers.
@kotlin.test.Test
public fun anything() {
    check(infinite() == e("."))
    check(infinite() == e("(?s)."))
    check(infinite() == e("[A-Za-z]"))
    check(infinite() == e("[A-Z]"))
    check(exact(listOf("")) == e("[A-Z]{0}"))
    check(infinite() == e("[A-Z]?"))
    check(infinite() == e("[A-Z]*"))
    check(infinite() == e("[A-Z]+"))
    check(Pair(seq(listOf(I("1"))), Seq.infinite()) == e("1[A-Z]"))
    check(Pair(seq(listOf(I("1"))), seq(listOf(I("2")))) == e("1[A-Z]2"))
    check(Pair(Seq.infinite(), seq(listOf(I("123")))) == e("[A-Z]+123"))
    check(infinite() == e("[A-Z]+123[A-Z]+"))
    check(infinite() == e("1|[A-Z]|3"))
    check(Pair(seq(listOf(E("1"), I("2"), E("3"))), Seq.infinite()) == e("1|2[A-Z]|3"))
    check(Pair(Seq.infinite(), seq(listOf(E("1"), I("2"), E("3")))) == e("1|[A-Z]2|3"))
    check(Pair(seq(listOf(E("1"), I("2"), E("4"))), seq(listOf(E("1"), I("3"), E("4")))) == e("1|2[A-Z]3|4"))
    check(Pair(Seq.infinite(), seq(listOf(I("2")))) == e("(?:|1)[A-Z]2"))
    check(inexact(listOf(I("a")), listOf(I("z"))) == e("a.z"))
}

// Like the 'anything' test, but it uses smaller limits in order to test
// the logic for effectively aborting literal extraction when the seqs get
// too big.
@kotlin.test.Test
public fun anythingSmallLimits() {
    fun prefixes(pattern: String): Seq = Extractor.new().kind(ExtractKind.Prefix).limitTotal(10).extract(parse(pattern))
    fun suffixes(pattern: String): Seq = Extractor.new().kind(ExtractKind.Suffix).limitTotal(10).extract(parse(pattern))
    fun e(pattern: String): Pair<Seq, Seq> = Pair(prefixes(pattern), suffixes(pattern))
    check(Pair(seq(listOf(I("aaa"), I("aab"), I("aba"), I("abb"), I("baa"), I("bab"), I("bba"), I("bbb"))), seq(listOf(I("aaa"), I("aab"), I("aba"), I("abb"), I("baa"), I("bab"), I("bba"), I("bbb")))) == e("[ab]{3}{3}"))
    check(infinite() == e("ab|cd|ef|gh|ij|kl|mn|op|qr|st|uv|wx|yz"))
}

@kotlin.test.Test
public fun empty() {
    check(exact(listOf("")) == e(""))
    check(exact(listOf("")) == e("^"))
    check(exact(listOf("")) == e("\$"))
    check(exact(listOf("")) == e("(?m:^)"))
    check(exact(listOf("")) == e("(?m:\$)"))
    check(exact(listOf("")) == e("\\b"))
    check(exact(listOf("")) == e("\\B"))
    check(exact(listOf("")) == e("(?-u:\\b)"))
    check(exact(listOf("")) == e("(?-u:\\B)"))
}

@kotlin.test.Test
public fun oddsAndEnds() {
    check(Pair(Seq.infinite(), seq(listOf(I("a")))) == e(".a"))
    check(Pair(seq(listOf(I("a"))), Seq.infinite()) == e("a."))
    check(infinite() == e("a|."))
    check(infinite() == e(".|a"))
    val pat = "M[ou]'?am+[ae]r .*([AEae]l[- ])?[GKQ]h?[aeu]+([dtz][dhz]?)+af[iy]"
    val expected = inexact(listOf("Mo'am", "Moam", "Mu'am", "Muam").map(::i), listOf("ddafi", "ddafy", "dhafi", "dhafy", "dzafi", "dzafy", "dafi", "dafy", "tdafi", "tdafy", "thafi", "thafy", "tzafi", "tzafy", "tafi", "tafy", "zdafi", "zdafy", "zhafi", "zhafy", "zzafi", "zzafy", "zafi", "zafy").map(::i))
    check(expected == e(pat))
    check(Pair(seq(listOf("fn is_", "fn as_").map(::i)), Seq.infinite()) == e("fn is_([A-Z]+)|fn as_([A-Z]+)"))
    check(inexact(listOf(I("foo")), listOf(I("quux"))) == e("foo[A-Z]+bar[A-Z]+quux"))
    check(infinite() == e("[A-Z]+bar[A-Z]+"))
    check(exact(listOf("Sherlock Holmes")) == e("(?m)^Sherlock Holmes|Sherlock Holmes\$"))
    check(exact(listOf("sa", "sb")) == e("\\bs(?:[ab])"))
}

// This tests a specific regex along with some heuristic steps to reduce
// the sequences extracted. This is meant to roughly correspond to the
// types of heuristics used to shrink literal sets in practice. (Shrinking
// is done because you want to balance "spend too much work looking for
// too many literals" and "spend too much work processing false positive
// matches from short literals.")
@kotlin.test.Test
public fun holmes() {
    val expected = inexact(listOf("HOL", "HOl", "HoL", "Hol", "hOL", "hOl", "hoL", "hol").map(::i), listOf("MES", "MEs", "Eſ", "MeS", "Mes", "eſ", "mES", "mEs", "meS", "mes").map(::i))
    var (prefixes, suffixes) = e("(?i)Holmes")
    prefixes.keepFirstBytes(3)
    suffixes.keepLastBytes(3)
    prefixes.minimizeByPreference()
    suffixes.minimizeByPreference()
    check(expected == Pair(prefixes, suffixes))
}

// This tests that we get some kind of literals extracted for a beefier
// alternation with case insensitive mode enabled. At one point during
// development, this returned nothing, and motivated some special case
// code in Extractor.union to try and trim down the literal sequences
// if the union would blow the limits set.
@kotlin.test.Test
public fun holmesAlt() {
    val pre = prefixes("(?i)Sherlock|Holmes|Watson|Irene|Adler|John|Baker")
    check(pre.len()!! > 0)
    pre.optimizeForPrefixByPreference()
    check(pre.len()!! > 0)
}

// See: https://github.com/rust-lang/regex/security/advisories/GHSA-m5pq-gvj9-9vr8
// See: CVE-2022-24713
//
// We test this here to ensure literal extraction completes in reasonable
// time and isn't materially impacted by these sorts of pathological
// repeats.
@kotlin.test.Test
public fun crazyRepeats() {
    check(inexact(listOf(E("")), listOf(E(""))) == e("(?:){4294967295}"))
    check(inexact(listOf(E("")), listOf(E(""))) == e("(?:){64}{64}{64}{64}{64}{64}"))
    check(inexact(listOf(E("")), listOf(E(""))) == e("x{0}{4294967295}"))
    check(inexact(listOf(E("")), listOf(E(""))) == e("(?:|){4294967295}"))
    check(inexact(listOf(E("")), listOf(E(""))) == e("(?:){8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}"))
    val repa = "a".repeat(100)
    check(inexact(listOf(I(repa)), listOf(I(repa))) == e("a{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}"))
}

@kotlin.test.Test
public fun huge() {
    val pat = "(?-u)\n        2(?:\n          [45]\\d{3}|\n          7(?:\n            1[0-267]|\n            2[0-289]|\n            3[0-29]|\n            4[01]|\n            5[1-3]|\n            6[013]|\n            7[0178]|\n            91\n          )|\n          8(?:\n            0[125]|\n            [139][1-6]|\n            2[0157-9]|\n            41|\n            6[1-35]|\n            7[1-5]|\n            8[1-8]|\n            90\n          )|\n          9(?:\n            0[0-2]|\n            1[0-4]|\n            2[568]|\n            3[3-6]|\n            5[5-7]|\n            6[0167]|\n            7[15]|\n            8[0146-9]\n          )\n        )\\d{4}|\n        3(?:\n          12?[5-7]\\d{2}|\n          0(?:\n            2(?:\n              [025-79]\\d|\n              [348]\\d{1,2}\n            )|\n            3(?:\n              [2-4]\\d|\n              [56]\\d?\n            )\n          )|\n          2(?:\n            1\\d{2}|\n            2(?:\n              [12]\\d|\n              [35]\\d{1,2}|\n              4\\d?\n            )\n          )|\n          3(?:\n            1\\d{2}|\n            2(?:\n              [2356]\\d|\n              4\\d{1,2}\n            )\n          )|\n          4(?:\n            1\\d{2}|\n            2(?:\n              2\\d{1,2}|\n              [47]|\n              5\\d{2}\n            )\n          )|\n          5(?:\n            1\\d{2}|\n            29\n          )|\n          [67]1\\d{2}|\n          8(?:\n            1\\d{2}|\n            2(?:\n              2\\d{2}|\n              3|\n              4\\d\n            )\n          )\n        )\\d{3}|\n        4(?:\n          0(?:\n            2(?:\n              [09]\\d|\n              7\n            )|\n            33\\d{2}\n          )|\n          1\\d{3}|\n          2(?:\n            1\\d{2}|\n            2(?:\n              [25]\\d?|\n              [348]\\d|\n              [67]\\d{1,2}\n            )\n          )|\n          3(?:\n            1\\d{2}(?:\n              \\d{2}\n            )?|\n            2(?:\n              [045]\\d|\n              [236-9]\\d{1,2}\n            )|\n            32\\d{2}\n          )|\n          4(?:\n            [18]\\d{2}|\n            2(?:\n              [2-46]\\d{2}|\n              3\n            )|\n            5[25]\\d{2}\n          )|\n          5(?:\n            1\\d{2}|\n            2(?:\n              3\\d|\n              5\n            )\n          )|\n          6(?:\n            [18]\\d{2}|\n            2(?:\n              3(?:\n                \\d{2}\n              )?|\n              [46]\\d{1,2}|\n              5\\d{2}|\n              7\\d\n            )|\n            5(?:\n              3\\d?|\n              4\\d|\n              [57]\\d{1,2}|\n              6\\d{2}|\n              8\n            )\n          )|\n          71\\d{2}|\n          8(?:\n            [18]\\d{2}|\n            23\\d{2}|\n            54\\d{2}\n          )|\n          9(?:\n            [18]\\d{2}|\n            2[2-5]\\d{2}|\n            53\\d{1,2}\n          )\n        )\\d{3}|\n        5(?:\n          02[03489]\\d{2}|\n          1\\d{2}|\n          2(?:\n            1\\d{2}|\n            2(?:\n              2(?:\n                \\d{2}\n              )?|\n              [457]\\d{2}\n            )\n          )|\n          3(?:\n            1\\d{2}|\n            2(?:\n              [37](?:\n                \\d{2}\n              )?|\n              [569]\\d{2}\n            )\n          )|\n          4(?:\n            1\\d{2}|\n            2[46]\\d{2}\n          )|\n          5(?:\n            1\\d{2}|\n            26\\d{1,2}\n          )|\n          6(?:\n            [18]\\d{2}|\n            2|\n            53\\d{2}\n          )|\n          7(?:\n            1|\n            24\n          )\\d{2}|\n          8(?:\n            1|\n            26\n          )\\d{2}|\n          91\\d{2}\n        )\\d{3}|\n        6(?:\n          0(?:\n            1\\d{2}|\n            2(?:\n              3\\d{2}|\n              4\\d{1,2}\n            )\n          )|\n          2(?:\n            2[2-5]\\d{2}|\n            5(?:\n              [3-5]\\d{2}|\n              7\n            )|\n            8\\d{2}\n          )|\n          3(?:\n            1|\n            2[3478]\n          )\\d{2}|\n          4(?:\n            1|\n            2[34]\n          )\\d{2}|\n          5(?:\n            1|\n            2[47]\n          )\\d{2}|\n          6(?:\n            [18]\\d{2}|\n            6(?:\n              2(?:\n                2\\d|\n                [34]\\d{2}\n              )|\n              5(?:\n                [24]\\d{2}|\n                3\\d|\n                5\\d{1,2}\n              )\n            )\n          )|\n          72[2-5]\\d{2}|\n          8(?:\n            1\\d{2}|\n            2[2-5]\\d{2}\n          )|\n          9(?:\n            1\\d{2}|\n            2[2-6]\\d{2}\n          )\n        )\\d{3}|\n        7(?:\n          (?:\n            02|\n            [3-589]1|\n            6[12]|\n            72[24]\n          )\\d{2}|\n          21\\d{3}|\n          32\n        )\\d{3}|\n        8(?:\n          (?:\n            4[12]|\n            [5-7]2|\n            1\\d?\n          )|\n          (?:\n            0|\n            3[12]|\n            [5-7]1|\n            217\n          )\\d\n        )\\d{4}|\n        9(?:\n          [35]1|\n          (?:\n            [024]2|\n            81\n          )\\d|\n          (?:\n            1|\n            [24]1\n          )\\d{2}\n        )\\d{3}\n        "
    // Note: This is a good candidate of a seq of literals that could be
    // shrunk quite a bit and still be very productive with respect to
    // literal optimizations.
    val (prefixes, suffixes) = e(pat)
    check(!suffixes.isFinite())
    check(243 == prefixes.len())
}

@kotlin.test.Test
public fun optimize() {
    // This gets a common prefix that isn't too short.
    val (p, s) = opt(listOf("foobarfoobar", "foobar", "foobarzfoobar", "foobarfoobar"))
    check(seq(listOf(I("foobar"))) == p)
    check(seq(listOf(I("foobar"))) == s)
    // This also finds a common prefix, but since it's only one byte, it
    // prefers the multiple literals.
    val (p1, s1) = opt(listOf("abba", "akka", "abccba"))
    check(exact(listOf("abba", "akka", "abccba")) == Pair(p1, s1))
    val (p2, s2) = opt(listOf("sam", "samwise"))
    check(Pair(seq(listOf(E("sam"))), seq(listOf(E("sam"), E("samwise")))) == Pair(p2, s2))
    // The empty string is poisonous, so our seq becomes infinite, even
    // though all literals are exact.
    val (p3, s3) = opt(listOf("foobarfoo", "foo", "", "foozfoo", "foofoo"))
    check(!p3.isFinite())
    check(!s3.isFinite())
    // A space is also poisonous, so our seq becomes infinite. But this
    // only gets triggered when we don't have a completely exact sequence.
    // When the sequence is exact, spaces are okay, since we presume that
    // any prefilter will match a space more quickly than the regex engine.
    // (When the sequence is exact, there's a chance of the prefilter being
    // used without needing the regex engine at all.)
    val p4 = seq(listOf(E("foobarfoo"), I("foo"), E(" "), E("foofoo")))
    p4.optimizeForPrefixByPreference()
    check(!p4.isFinite())
}
