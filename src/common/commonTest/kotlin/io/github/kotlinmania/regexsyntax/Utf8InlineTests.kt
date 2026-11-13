// port-lint: tests utf8.rs
package io.github.kotlinmania.regexsyntax.utf8

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
// Note that the byte ranges above will *not* match any erroneous encoding of
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

public fun rutf8(s: UByte, e: UByte): Utf8Range = Utf8Range.new(s, e)

public fun neverAcceptsSurrogateCodepoints(start: Char, end: Char) {
    for (cp in 0xD800 until 0xE000) {
        val buf = encodeSurrogate(cp)
        for (r in Utf8Sequences.new(start, end)) {
            if (r.matches(buf)) {
                error("Sequence (${UInt.from(start)}, ${UInt.from(end)}) contains range $r, which matches surrogate code point $cp with encoded bytes $buf")
            }
        }
    }
}

@kotlin.test.Test
public fun codepointsNoSurrogates() {
    neverAcceptsSurrogateCodepoints('\u0000', '\uffff')
    neverAcceptsSurrogateCodepoints('\u0000', 0x10ffff)
    neverAcceptsSurrogateCodepoints('\u0000', 0x10fffe)
    neverAcceptsSurrogateCodepoints('\u0080', 0x10ffff)
    neverAcceptsSurrogateCodepoints('\ud7ff', '\ue000')
}

@kotlin.test.Test
public fun singleCodepointOneSequence() {
    // Tests that every range of scalar values that contains a single
    // scalar value is recognized by one sequence of byte ranges.
    for (i in 0x0..0x0010FFFF) {
        val c1 =
            when (val matchVal = Char.fromU32(i)) {
                null -> { continue }
                else -> {
                    val c = matchVal
                    c
                }
            }
        val seqs: Vec = Utf8Sequences.new(c1, c1).collect()
        check(seqs.len() == 1)
    }
}

@kotlin.test.Test
public fun bmp() {
    val seqs = Utf8Sequences.new('\u0000', '\uffff').collect<Vec>()
    check(seqs == mutableListOf(One(rutf8(0x0, 0x7F)), Two(listOf(rutf8(0xC2, 0xDF), rutf8(0x80, 0xBF))), Three(listOf(rutf8(0xE0, 0xE0), rutf8(0xA0, 0xBF), rutf8(0x80, 0xBF))), Three(listOf(rutf8(0xE1, 0xEC), rutf8(0x80, 0xBF), rutf8(0x80, 0xBF))), Three(listOf(rutf8(0xED, 0xED), rutf8(0x80, 0x9F), rutf8(0x80, 0xBF))), Three(listOf(rutf8(0xEE, 0xEF), rutf8(0x80, 0xBF), rutf8(0x80, 0xBF)))))
}

@kotlin.test.Test
public fun reverse() {
    val s = One(rutf8(0xA, 0xB))
    s.reverse()
    check(s.asSlice() == listOf(rutf8(0xA, 0xB)))
    val s1 = Two(listOf(rutf8(0xA, 0xB), rutf8(0xB, 0xC)))
    s1.reverse()
    check(s1.asSlice() == listOf(rutf8(0xB, 0xC), rutf8(0xA, 0xB)))
    val s2 = Three(listOf(rutf8(0xA, 0xB), rutf8(0xB, 0xC), rutf8(0xC, 0xD)))
    s2.reverse()
    check(s2.asSlice() == listOf(rutf8(0xC, 0xD), rutf8(0xB, 0xC), rutf8(0xA, 0xB)))
    val s3 = Four(listOf(rutf8(0xA, 0xB), rutf8(0xB, 0xC), rutf8(0xC, 0xD), rutf8(0xD, 0xE)))
    s3.reverse()
    check(s3.asSlice() == listOf(rutf8(0xD, 0xE), rutf8(0xC, 0xD), rutf8(0xB, 0xC), rutf8(0xA, 0xB)))
}

public fun encodeSurrogate(cp: UInt): List<UByte> {
    val TAG_CONT: UByte = 0x80u
    val TAG_THREE_B: UByte = 0xE0u
    check(0xD800u <= cp && cp < 0xE000u)
    val dst = List(3) { 0 }
    dst[0] = UByte.tryFrom(cp shr 12 and 0x0Fu).unwrap() or TAG_THREE_B
    dst[1] = UByte.tryFrom(cp shr 6 and 0x3Fu).unwrap() or TAG_CONT
    dst[2] = UByte.tryFrom(cp and 0x3Fu).unwrap() or TAG_CONT
    return dst
}
