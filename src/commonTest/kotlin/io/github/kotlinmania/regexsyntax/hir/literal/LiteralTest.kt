// port-lint: source hir/literal.rs
package io.github.kotlinmania.regexsyntax.hir.literal

import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.parser.ParserBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiteralTest {
    private fun parse(pattern: String): Hir =
        ParserBuilder.new().utf8(false).build().parse(pattern).getOrThrow()

    private fun prefixes(pattern: String): Seq =
        Extractor.new().kind(ExtractKind.Prefix).extract(parse(pattern))

    private fun suffixes(pattern: String): Seq =
        Extractor.new().kind(ExtractKind.Suffix).extract(parse(pattern))

    private fun e(pattern: String): Pair<Seq, Seq> = prefixes(pattern) to suffixes(pattern)

    private fun E(x: String): Literal = Literal.exact(x)

    private fun E(x: ByteArray): Literal = Literal.exact(x)

    private fun I(x: String): Literal = Literal.inexact(x)

    private fun seq(literals: Iterable<Literal>): Seq = Seq.fromIter(literals)

    private fun seq(vararg literals: Literal): Seq = Seq.fromIter(literals.asIterable())

    private fun infinite(): Pair<Seq, Seq> = Seq.infinite() to Seq.infinite()

    private fun inexact(prefixes: Iterable<Literal>, suffixes: Iterable<Literal>): Pair<Seq, Seq> =
        Seq.fromIter(prefixes) to Seq.fromIter(suffixes)

    private fun exact(strings: Iterable<String>): Pair<Seq, Seq> {
        val seq = Seq.fromIter(strings.map { E(it) })
        return seq to seq.clone()
    }

    private fun exact(vararg strings: String): Pair<Seq, Seq> = exact(strings.asIterable())

    private fun exactBytes(vararg bytes: ByteArray): Pair<Seq, Seq> {
        val seq = Seq.fromIter(bytes.map { E(it) })
        return seq to seq.clone()
    }

    private fun opt(strings: Iterable<String>): Pair<Seq, Seq> {
        val (prefixes, suffixes) = exact(strings)
        prefixes.optimizeForPrefixByPreference()
        suffixes.optimizeForSuffixByPreference()
        return prefixes to suffixes
    }

    @Test
    fun literal() {
        assertEquals(exact("a"), e("a"))
        assertEquals(exact("aaaaa"), e("aaaaa"))
        assertEquals(exact("A", "a"), e("(?i-u)a"))
        assertEquals(exact("AB", "Ab", "aB", "ab"), e("(?i-u)ab"))
        assertEquals(exact("abC", "abc"), e("ab(?i-u)c"))

        assertEquals(exactBytes(byteArrayOf(0xFF.toByte())), e("""(?-u:\xFF)"""))

        assertEquals(exact("☃"), e("☃"))
        assertEquals(exact("☃"), e("(?i)☃"))
        assertEquals(exact("☃☃☃☃☃"), e("☃☃☃☃☃"))

        assertEquals(exact("Δ"), e("Δ"))
        assertEquals(exact("δ"), e("δ"))
        assertEquals(exact("Δ", "δ"), e("(?i)Δ"))
        assertEquals(exact("Δ", "δ"), e("(?i)δ"))

        assertEquals(exact("S", "s", "ſ"), e("(?i)S"))
        assertEquals(exact("S", "s", "ſ"), e("(?i)s"))
        assertEquals(exact("S", "s", "ſ"), e("(?i)ſ"))

        val letters = "ͱͳͷΐάέήίΰαβγδεζηθικλμνξοπρςστυφχψωϊϋ"
        assertEquals(exact(letters), e(letters))
    }

    @Test
    fun `class`() {
        assertEquals(exact("a", "b", "c"), e("[abc]"))
        assertEquals(exact("a1b", "a2b", "a3b"), e("a[123]b"))
        assertEquals(exact("δ", "ε"), e("[εδ]"))
        assertEquals(exact("Δ", "Ε", "δ", "ε", "ϵ"), e("""(?i)[εδ]"""))
    }

    @Test
    fun look() {
        assertEquals(exact("ab"), e("""a\Ab"""))
        assertEquals(exact("ab"), e("""a\zb"""))
        assertEquals(exact("ab"), e("""a(?m:^)b"""))
        assertEquals(exact("ab"), e("""a(?m:$)b"""))
        assertEquals(exact("ab"), e("""a\bb"""))
        assertEquals(exact("ab"), e("""a\Bb"""))
        assertEquals(exact("ab"), e("""a(?-u:\b)b"""))
        assertEquals(exact("ab"), e("""a(?-u:\B)b"""))

        assertEquals(exact("ab"), e("""^ab"""))
        assertEquals(exact("ab"), e("""${'$'}ab"""))
        assertEquals(exact("ab"), e("""(?m:^)ab"""))
        assertEquals(exact("ab"), e("""(?m:$)ab"""))
        assertEquals(exact("ab"), e("""\bab"""))
        assertEquals(exact("ab"), e("""\Bab"""))
        assertEquals(exact("ab"), e("""(?-u:\b)ab"""))
        assertEquals(exact("ab"), e("""(?-u:\B)ab"""))

        assertEquals(exact("ab"), e("""ab^"""))
        assertEquals(exact("ab"), e("""ab$"""))
        assertEquals(exact("ab"), e("""ab(?m:^)"""))
        assertEquals(exact("ab"), e("""ab(?m:$)"""))
        assertEquals(exact("ab"), e("""ab\b"""))
        assertEquals(exact("ab"), e("""ab\B"""))
        assertEquals(exact("ab"), e("""ab(?-u:\b)"""))
        assertEquals(exact("ab"), e("""ab(?-u:\B)"""))

        val expected = seq(I("aZ"), E("ab")) to seq(I("Zb"), E("ab"))
        assertEquals(expected, e("""^aZ*b"""))
    }

    @Test
    fun repetition() {
        assertEquals(exact("a", ""), e("""a?"""))
        assertEquals(exact("", "a"), e("""a??"""))
        assertEquals(inexact(listOf(I("a"), E("")), listOf(I("a"), E(""))), e("""a*"""))
        assertEquals(inexact(listOf(E(""), I("a")), listOf(E(""), I("a"))), e("""a*?"""))
        assertEquals(inexact(listOf(I("a")), listOf(I("a"))), e("""a+"""))
        assertEquals(inexact(listOf(I("a")), listOf(I("a"))), e("""(a+)+"""))

        assertEquals(exact("ab"), e("""aZ{0}b"""))
        assertEquals(exact("aZb", "ab"), e("""aZ?b"""))
        assertEquals(exact("ab", "aZb"), e("""aZ??b"""))
        assertEquals(
            inexact(listOf(I("aZ"), E("ab")), listOf(I("Zb"), E("ab"))),
            e("""aZ*b"""),
        )
        assertEquals(
            inexact(listOf(E("ab"), I("aZ")), listOf(E("ab"), I("Zb"))),
            e("""aZ*?b"""),
        )
        assertEquals(inexact(listOf(I("aZ")), listOf(I("Zb"))), e("""aZ+b"""))
        assertEquals(inexact(listOf(I("aZ")), listOf(I("Zb"))), e("""aZ+?b"""))

        assertEquals(exact("aZZb"), e("""aZ{2}b"""))
        assertEquals(inexact(listOf(I("aZZ")), listOf(I("ZZb"))), e("""aZ{2,3}b"""))

        assertEquals(exact("abc", ""), e("""(abc)?"""))
        assertEquals(exact("", "abc"), e("""(abc)??"""))

        assertEquals(inexact(listOf(I("a"), E("b")), listOf(I("ab"), E("b"))), e("""a*b"""))
        assertEquals(inexact(listOf(E("b"), I("a")), listOf(E("b"), I("ab"))), e("""a*?b"""))
        assertEquals(inexact(listOf(I("ab")), listOf(I("b"))), e("""ab+"""))
        assertEquals(inexact(listOf(I("a"), I("b")), listOf(I("b"))), e("""a*b+"""))

        // FIXME: The suffixes for this don't look quite right to me. I think
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
        assertEquals(
            inexact(listOf(I("a"), I("b"), E("c")), listOf(I("bc"), I("ac"), E("c"))),
            e("""a*b*c"""),
        )
        assertEquals(
            inexact(listOf(I("a"), I("b"), E("c")), listOf(I("bc"), I("ac"), E("c"))),
            e("""(a+)?(b+)?c"""),
        )
        assertEquals(
            inexact(listOf(I("a"), I("b"), E("c")), listOf(I("bc"), I("ac"), E("c"))),
            e("""(a+|)(b+|)c"""),
        )
        assertEquals(
            inexact(listOf(I("a"), I("b"), I("c"), E("")), listOf(I("c"), I("b"), I("a"), E(""))),
            e("""a*b*c*"""),
        )
        assertEquals(inexact(listOf(I("a"), I("b"), I("c")), listOf(I("c"))), e("""a*b*c+"""))
        assertEquals(inexact(listOf(I("a"), I("b")), listOf(I("bc"))), e("""a*b+c"""))
        assertEquals(inexact(listOf(I("a"), I("b")), listOf(I("c"), I("b"))), e("""a*b+c*"""))
        assertEquals(inexact(listOf(I("ab"), E("a")), listOf(I("b"), E("a"))), e("""ab*"""))
        assertEquals(
            inexact(listOf(I("ab"), E("ac")), listOf(I("bc"), E("ac"))),
            e("""ab*c"""),
        )
        assertEquals(inexact(listOf(I("ab")), listOf(I("b"))), e("""ab+"""))
        assertEquals(inexact(listOf(I("ab")), listOf(I("bc"))), e("""ab+c"""))

        assertEquals(
            inexact(listOf(I("z"), E("azb")), listOf(I("zazb"), E("azb"))),
            e("""z*azb"""),
        )

        val expected = exact("aaa", "aab", "aba", "abb", "baa", "bab", "bba", "bbb")
        assertEquals(expected, e("""[ab]{3}"""))
        val expectedInexact = inexact(
            listOf(I("aaa"), I("aab"), I("aba"), I("abb"), I("baa"), I("bab"), I("bba"), I("bbb")),
            listOf(I("aaa"), I("aab"), I("aba"), I("abb"), I("baa"), I("bab"), I("bba"), I("bbb")),
        )
        assertEquals(expectedInexact, e("""[ab]{3,4}"""))
    }

    @Test
    fun concat() {
        assertEquals(exact("abcxyz"), e("""abc()xyz"""))
        assertEquals(exact("abcxyz"), e("""(abc)(xyz)"""))
        assertEquals(exact("abcmnoxyz"), e("""abc()mno()xyz"""))
        assertEquals(exact(), e("""abc[a&&b]xyz"""))
        assertEquals(exact("abcxyz"), e("""abc[a&&b]*xyz"""))
    }

    @Test
    fun alternation() {
        assertEquals(exact("abc", "mno", "xyz"), e("""abc|mno|xyz"""))
        assertEquals(
            inexact(listOf(E("abc"), I("mZ"), E("mo"), E("xyz")), listOf(E("abc"), I("Zo"), E("mo"), E("xyz"))),
            e("""abc|mZ*o|xyz"""),
        )
        assertEquals(exact("abc", "xyz"), e("""abc|M[a&&b]N|xyz"""))
        assertEquals(exact("abc", "MN", "xyz"), e("""abc|M[a&&b]*N|xyz"""))

        assertEquals(exact("aaa", "aaaaa"), e("""(?:|aa)aaa"""))
        assertEquals(
            inexact(listOf(I("aaa"), E(""), I("aaaaa"), E("aa")), listOf(I("aaa"), E(""), E("aa"))),
            e("""(?:|aa)(?:aaa)*"""),
        )
        assertEquals(
            inexact(listOf(E(""), I("aaa"), E("aa"), I("aaaaa")), listOf(E(""), I("aaa"), E("aa"))),
            e("""(?:|aa)(?:aaa)*?"""),
        )

        assertEquals(inexact(listOf(E("a"), I("b"), E("")), listOf(E("a"), I("b"), E(""))), e("""a|b*"""))
        assertEquals(inexact(listOf(E("a"), I("b")), listOf(E("a"), I("b"))), e("""a|b+"""))

        assertEquals(
            inexact(listOf(I("a"), E("b"), E("c")), listOf(I("ab"), E("b"), E("c"))),
            e("""a*b|c"""),
        )

        assertEquals(
            inexact(listOf(E("a"), E("b"), I("c"), E("")), listOf(E("a"), E("b"), I("c"), E(""))),
            e("""a|(?:b|c*)"""),
        )

        assertEquals(
            inexact(
                listOf(I("a"), I("b"), E("c"), I("a"), I("ab"), E("c")),
                listOf(I("ac"), I("bc"), E("c"), I("ac"), I("abc"), E("c")),
            ),
            e("""(a|b)*c|(a|ab)*c"""),
        )

        assertEquals(exact("abef", "abgh", "cdef", "cdgh"), e("""(ab|cd)(ef|gh)"""))
        assertEquals(
            exact("abefij", "abefkl", "abghij", "abghkl", "cdefij", "cdefkl", "cdghij", "cdghkl"),
            e("""(ab|cd)(ef|gh)(ij|kl)"""),
        )

        assertEquals(inexact(listOf(E("abab")), listOf(E("abab"))), e("""(ab){2}"""))
        assertEquals(inexact(listOf(I("abab")), listOf(I("abab"))), e("""(ab){2,3}"""))
        assertEquals(inexact(listOf(I("abab")), listOf(I("abab"))), e("""(ab){2,}"""))
    }

    @Test
    fun impossible() {
        assertEquals(exact(), e("""[a&&b]"""))
        assertEquals(exact(), e("""a[a&&b]"""))
        assertEquals(exact(), e("""[a&&b]b"""))
        assertEquals(exact(), e("""a[a&&b]b"""))
        assertEquals(exact("a", "b"), e("""a|[a&&b]|b"""))
        assertEquals(exact("a", "b"), e("""a|c[a&&b]|b"""))
        assertEquals(exact("a", "b"), e("""a|[a&&b]d|b"""))
        assertEquals(exact("a", "b"), e("""a|c[a&&b]d|b"""))
        assertEquals(exact(""), e("""[a&&b]*"""))
        assertEquals(exact("MN"), e("""M[a&&b]*N"""))
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
    @Test
    fun anything() {
        assertEquals(infinite(), e("."))
        assertEquals(infinite(), e("""(?s)."""))
        assertEquals(infinite(), e("""[A-Za-z]"""))
        assertEquals(infinite(), e("""[A-Z]"""))
        assertEquals(exact(""), e("""[A-Z]{0}"""))
        assertEquals(infinite(), e("""[A-Z]?"""))
        assertEquals(infinite(), e("""[A-Z]*"""))
        assertEquals(infinite(), e("""[A-Z]+"""))
        assertEquals(seq(I("1")) to Seq.infinite(), e("""1[A-Z]"""))
        assertEquals(seq(I("1")) to seq(I("2")), e("""1[A-Z]2"""))
        assertEquals(Seq.infinite() to seq(I("123")), e("""[A-Z]+123"""))
        assertEquals(infinite(), e("""[A-Z]+123[A-Z]+"""))
        assertEquals(infinite(), e("""1|[A-Z]|3"""))
        assertEquals(
            seq(E("1"), I("2"), E("3")) to Seq.infinite(),
            e("""1|2[A-Z]|3"""),
        )
        assertEquals(
            Seq.infinite() to seq(E("1"), I("2"), E("3")),
            e("""1|[A-Z]2|3"""),
        )
        assertEquals(
            seq(E("1"), I("2"), E("4")) to seq(E("1"), I("3"), E("4")),
            e("""1|2[A-Z]3|4"""),
        )
        assertEquals(Seq.infinite() to seq(I("2")), e("""(?:|1)[A-Z]2"""))
        assertEquals(inexact(listOf(I("a")), listOf(I("z"))), e("""a.z"""))
    }

    // Like the 'anything' test, but it uses smaller limits in order to test
    // the logic for effectively aborting literal extraction when the seqs get
    // too big.
    @Test
    fun anything_small_limits() {
        fun prefixes(pattern: String): Seq =
            Extractor.new()
                .kind(ExtractKind.Prefix)
                .limitTotal(10)
                .extract(parse(pattern))

        fun suffixes(pattern: String): Seq =
            Extractor.new()
                .kind(ExtractKind.Suffix)
                .limitTotal(10)
                .extract(parse(pattern))

        fun e(pattern: String): Pair<Seq, Seq> = prefixes(pattern) to suffixes(pattern)

        assertEquals(
            seq(I("aaa"), I("aab"), I("aba"), I("abb"), I("baa"), I("bab"), I("bba"), I("bbb")) to
                seq(I("aaa"), I("aab"), I("aba"), I("abb"), I("baa"), I("bab"), I("bba"), I("bbb")),
            e("""[ab]{3}{3}"""),
        )

        assertEquals(infinite(), e("""ab|cd|ef|gh|ij|kl|mn|op|qr|st|uv|wx|yz"""))
    }

    @Test
    fun empty() {
        assertEquals(exact(""), e(""""""))
        assertEquals(exact(""), e("""^"""))
        assertEquals(exact(""), e("""$"""))
        assertEquals(exact(""), e("""(?m:^)"""))
        assertEquals(exact(""), e("""(?m:$)"""))
        assertEquals(exact(""), e("""\b"""))
        assertEquals(exact(""), e("""\B"""))
        assertEquals(exact(""), e("""(?-u:\b)"""))
        assertEquals(exact(""), e("""(?-u:\B)"""))
    }

    @Test
    fun odds_and_ends() {
        assertEquals(Seq.infinite() to seq(I("a")), e(""".a"""))
        assertEquals(seq(I("a")) to Seq.infinite(), e("""a."""))
        assertEquals(infinite(), e("""a|."""))
        assertEquals(infinite(), e(""".|a"""))

        val pat = """M[ou]'?am+[ae]r .*([AEae]l[- ])?[GKQ]h?[aeu]+([dtz][dhz]?)+af[iy]"""
        val expected = inexact(
            listOf("Mo'am", "Moam", "Mu'am", "Muam").map { I(it) },
            listOf(
                "ddafi", "ddafy", "dhafi", "dhafy", "dzafi", "dzafy", "dafi",
                "dafy", "tdafi", "tdafy", "thafi", "thafy", "tzafi", "tzafy",
                "tafi", "tafy", "zdafi", "zdafy", "zhafi", "zhafy", "zzafi",
                "zzafy", "zafi", "zafy",
            ).map { I(it) },
        )
        assertEquals(expected, e(pat))

        assertEquals(
            seq(listOf("fn is_", "fn as_").map { I(it) }) to Seq.infinite(),
            e("""fn is_([A-Z]+)|fn as_([A-Z]+)"""),
        )
        assertEquals(inexact(listOf(I("foo")), listOf(I("quux"))), e("""foo[A-Z]+bar[A-Z]+quux"""))
        assertEquals(infinite(), e("""[A-Z]+bar[A-Z]+"""))
        assertEquals(exact("Sherlock Holmes"), e("""(?m)^Sherlock Holmes|Sherlock Holmes$"""))

        assertEquals(exact("sa", "sb"), e("""\bs(?:[ab])"""))
    }

    // This tests a specific regex along with some heuristic steps to reduce
    // the sequences extracted. This is meant to roughly correspond to the
    // types of heuristics used to shrink literal sets in practice. (Shrinking
    // is done because you want to balance "spend too much work looking for
    // too many literals" and "spend too much work processing false positive
    // matches from short literals.")
    @Test
    fun holmes() {
        val expected = inexact(
            listOf("HOL", "HOl", "HoL", "Hol", "hOL", "hOl", "hoL", "hol").map { I(it) },
            listOf("MES", "MEs", "Eſ", "MeS", "Mes", "eſ", "mES", "mEs", "meS", "mes").map { I(it) },
        )
        val (prefixes, suffixes) = e("""(?i)Holmes""")
        prefixes.keepFirstBytes(3)
        suffixes.keepLastBytes(3)
        prefixes.minimizeByPreference()
        suffixes.minimizeByPreference()
        assertEquals(expected, prefixes to suffixes)
    }

    // This tests that we get some kind of literals extracted for a beefier
    // alternation with case insensitive mode enabled. At one point during
    // development, this returned nothing, and motivated some special case
    // code in Extractor::union to try and trim down the literal sequences
    // if the union would blow the limits set.
    @Test
    fun holmes_alt() {
        val pre = prefixes("""(?i)Sherlock|Holmes|Watson|Irene|Adler|John|Baker""")
        assertTrue(pre.len()!! > 0)
        pre.optimizeForPrefixByPreference()
        assertTrue(pre.len()!! > 0)
    }

    // See: https://github.com/rust-lang/regex/security/advisories/GHSA-m5pq-gvj9-9vr8
    // See: CVE-2022-24713
    //
    // We test this here to ensure literal extraction completes in reasonable
    // time and isn't materially impacted by these sorts of pathological
    // repeats.
    @Test
    fun crazy_repeats() {
        assertEquals(inexact(listOf(E("")), listOf(E(""))), e("""(?:){4294967295}"""))
        assertEquals(inexact(listOf(E("")), listOf(E(""))), e("""(?:){64}{64}{64}{64}{64}{64}"""))
        assertEquals(inexact(listOf(E("")), listOf(E(""))), e("""x{0}{4294967295}"""))
        assertEquals(inexact(listOf(E("")), listOf(E(""))), e("""(?:|){4294967295}"""))

        assertEquals(
            inexact(listOf(E("")), listOf(E(""))),
            e("""(?:){8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}"""),
        )
        val repa = "a".repeat(100)
        assertEquals(
            inexact(listOf(I(repa)), listOf(I(repa))),
            e("""a{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}{8}"""),
        )
    }

    @Test
    fun huge() {
        val pat = """(?-u)
        2(?:
          [45]\d{3}|
          7(?:
            1[0-267]|
            2[0-289]|
            3[0-29]|
            4[01]|
            5[1-3]|
            6[013]|
            7[0178]|
            91
          )|
          8(?:
            0[125]|
            [139][1-6]|
            2[0157-9]|
            41|
            6[1-35]|
            7[1-5]|
            8[1-8]|
            90
          )|
          9(?:
            0[0-2]|
            1[0-4]|
            2[568]|
            3[3-6]|
            5[5-7]|
            6[0167]|
            7[15]|
            8[0146-9]
          )
        )\d{4}|
        3(?:
          12?[5-7]\d{2}|
          0(?:
            2(?:
              [025-79]\d|
              [348]\d{1,2}
            )|
            3(?:
              [2-4]\d|
              [56]\d?
            )
          )|
          2(?:
            1\d{2}|
            2(?:
              [12]\d|
              [35]\d{1,2}|
              4\d?
            )
          )|
          3(?:
            1\d{2}|
            2(?:
              [2356]\d|
              4\d{1,2}
            )
          )|
          4(?:
            1\d{2}|
            2(?:
              2\d{1,2}|
              [47]|
              5\d{2}
            )
          )|
          5(?:
            1\d{2}|
            29
          )|
          [67]1\d{2}|
          8(?:
            1\d{2}|
            2(?:
              2\d{2}|
              3|
              4\d
            )
          )
        )\d{3}|
        4(?:
          0(?:
            2(?:
              [09]\d|
              7
            )|
            33\d{2}
          )|
          1\d{3}|
          2(?:
            1\d{2}|
            2(?:
              [25]\d?|
              [348]\d|
              [67]\d{1,2}
            )
          )|
          3(?:
            1\d{2}(?:
              \d{2}
            )?|
            2(?:
              [045]\d|
              [236-9]\d{1,2}
            )|
            32\d{2}
          )|
          4(?:
            [18]\d{2}|
            2(?:
              [2-46]\d{2}|
              3
            )|
            5[25]\d{2}
          )|
          5(?:
            1\d{2}|
            2(?:
              3\d|
              5
            )
          )|
          6(?:
            [18]\d{2}|
            2(?:
              3(?:
                \d{2}
              )?|
              [46]\d{1,2}|
              5\d{2}|
              7\d
            )|
            5(?:
              3\d?|
              4\d|
              [57]\d{1,2}|
              6\d{2}|
              8
            )
          )|
          71\d{2}|
          8(?:
            [18]\d{2}|
            23\d{2}|
            54\d{2}
          )|
          9(?:
            [18]\d{2}|
            2[2-5]\d{2}|
            53\d{1,2}
          )
        )\d{3}|
        5(?:
          02[03489]\d{2}|
          1\d{2}|
          2(?:
            1\d{2}|
            2(?:
              2(?:
                \d{2}
              )?|
              [457]\d{2}
            )
          )|
          3(?:
            1\d{2}|
            2(?:
              [37](?:
                \d{2}
              )?|
              [569]\d{2}
            )
          )|
          4(?:
            1\d{2}|
            2[46]\d{2}
          )|
          5(?:
            1\d{2}|
            26\d{1,2}
          )|
          6(?:
            [18]\d{2}|
            2|
            53\d{2}
          )|
          7(?:
            1|
            24
          )\d{2}|
          8(?:
            1|
            26
          )\d{2}|
          91\d{2}
        )\d{3}|
        6(?:
          0(?:
            1\d{2}|
            2(?:
              3\d{2}|
              4\d{1,2}
            )
          )|
          2(?:
            2[2-5]\d{2}|
            5(?:
              [3-5]\d{2}|
              7
            )|
            8\d{2}
          )|
          3(?:
            1|
            2[3478]
          )\d{2}|
          4(?:
            1|
            2[34]
          )\d{2}|
          5(?:
            1|
            2[47]
          )\d{2}|
          6(?:
            [18]\d{2}|
            6(?:
              2(?:
                2\d|
                [34]\d{2}
              )|
              5(?:
                [24]\d{2}|
                3\d|
                5\d{1,2}
              )
            )
          )|
          72[2-5]\d{2}|
          8(?:
            1\d{2}|
            2[2-5]\d{2}
          )|
          9(?:
            1\d{2}|
            2[2-6]\d{2}
          )
        )\d{3}|
        7(?:
          (?:
            02|
            [3-589]1|
            6[12]|
            72[24]
          )\d{2}|
          21\d{3}|
          32
        )\d{3}|
        8(?:
          (?:
            4[12]|
            [5-7]2|
            1\d?
          )|
          (?:
            0|
            3[12]|
            [5-7]1|
            217
          )\d
        )\d{4}|
        9(?:
          [35]1|
          (?:
            [024]2|
            81
          )\d|
          (?:
            1|
            [24]1
          )\d{2}
        )\d{3}
        """
        // TODO: This is a good candidate of a seq of literals that could be
        // shrunk quite a bit and still be very productive with respect to
        // literal optimizations.
        val (prefixes, suffixes) = e(pat)
        assertFalse(suffixes.isFinite())
        assertEquals(243, prefixes.len())
    }

    @Test
    fun optimize() {
        // This gets a common prefix that isn't too short.
        var (p, s) = opt(listOf("foobarfoobar", "foobar", "foobarzfoobar", "foobarfoobar"))
        assertEquals(seq(I("foobar")), p)
        assertEquals(seq(I("foobar")), s)

        // This also finds a common prefix, but since it's only one byte, it
        // prefers the multiple literals.
        var optimized = opt(listOf("abba", "akka", "abccba"))
        assertEquals(exact("abba", "akka", "abccba"), optimized)

        optimized = opt(listOf("sam", "samwise"))
        assertEquals(seq(E("sam")) to seq(E("sam"), E("samwise")), optimized)

        // The empty string is poisonous, so our seq becomes infinite, even
        // though all literals are exact.
        optimized = opt(listOf("foobarfoo", "foo", "", "foozfoo", "foofoo"))
        assertFalse(optimized.first.isFinite())
        assertFalse(optimized.second.isFinite())

        // A space is also poisonous, so our seq becomes infinite. But this
        // only gets triggered when we don't have a completely exact sequence.
        // When the sequence is exact, spaces are okay, since we presume that
        // any prefilter will match a space more quickly than the regex engine.
        // (When the sequence is exact, there's a chance of the prefilter being
        // used without needing the regex engine at all.)
        p = seq(E("foobarfoo"), I("foo"), E(" "), E("foofoo"))
        p.optimizeForPrefixByPreference()
        assertFalse(p.isFinite())
    }
}
