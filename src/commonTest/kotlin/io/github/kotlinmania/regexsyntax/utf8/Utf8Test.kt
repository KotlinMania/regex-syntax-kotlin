// port-lint: source utf8.rs
package io.github.kotlinmania.regexsyntax.utf8

import kotlin.test.Test
import kotlin.test.assertEquals

class Utf8Test {
    private fun collect(start: Int, end: Int): List<Utf8Sequence> {
        val it = Utf8Sequences(start, end)
        val out = ArrayList<Utf8Sequence>()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    private fun rutf8(s: Int, e: Int): Utf8Range = Utf8Range.new(s.toByte(), e.toByte())

    private fun neverAcceptsSurrogateCodepoints(start: Int, end: Int) {
        for (cp in 0xD800 until 0xE000) {
            val buf = encodeSurrogate(cp)
            for (r in collect(start, end)) {
                if (r.matches(buf)) {
                    error(
                        "Sequence (${start.toString(16).uppercase()}, ${end.toString(16).uppercase()}) contains range $r, " +
                            "which accepts surrogate code point ${cp.toString(16).uppercase()} with encoded bytes ${buf.toList()}",
                    )
                }
            }
        }
    }

    @Test
    fun codepointsNoSurrogates() {
        neverAcceptsSurrogateCodepoints(0x0, 0xFFFF)
        neverAcceptsSurrogateCodepoints(0x0, 0x10FFFF)
        neverAcceptsSurrogateCodepoints(0x0, 0x10FFFE)
        neverAcceptsSurrogateCodepoints(0x80, 0x10FFFF)
        neverAcceptsSurrogateCodepoints(0xD7FF, 0xE000)
    }

    @Test
    fun singleCodepointOneSequence() {
        for (cp in 0x0..0x10FFFF) {
            if (!isUnicodeScalarValue(cp)) continue
            val seqs = collect(cp, cp)
            assertEquals(1, seqs.size)
        }
    }

    @Test
    fun bmp() {
        val seqs = collect(0x0, 0xFFFF)
        assertEquals(
            listOf(
                Utf8Sequence.One(rutf8(0x0, 0x7F)),
                Utf8Sequence.Two(listOf(rutf8(0xC2, 0xDF), rutf8(0x80, 0xBF))),
                Utf8Sequence.Three(listOf(rutf8(0xE0, 0xE0), rutf8(0xA0, 0xBF), rutf8(0x80, 0xBF))),
                Utf8Sequence.Three(listOf(rutf8(0xE1, 0xEC), rutf8(0x80, 0xBF), rutf8(0x80, 0xBF))),
                Utf8Sequence.Three(listOf(rutf8(0xED, 0xED), rutf8(0x80, 0x9F), rutf8(0x80, 0xBF))),
                Utf8Sequence.Three(listOf(rutf8(0xEE, 0xEF), rutf8(0x80, 0xBF), rutf8(0x80, 0xBF))),
            ),
            seqs,
        )
    }

    @Test
    fun reverse() {
        var s: Utf8Sequence = Utf8Sequence.One(rutf8(0xA, 0xB))
        s = s.reverse()
        assertEquals(listOf(rutf8(0xA, 0xB)), s.asSlice())

        s = Utf8Sequence.Two(listOf(rutf8(0xA, 0xB), rutf8(0xB, 0xC)))
        s = s.reverse()
        assertEquals(listOf(rutf8(0xB, 0xC), rutf8(0xA, 0xB)), s.asSlice())

        s = Utf8Sequence.Three(listOf(rutf8(0xA, 0xB), rutf8(0xB, 0xC), rutf8(0xC, 0xD)))
        s = s.reverse()
        assertEquals(listOf(rutf8(0xC, 0xD), rutf8(0xB, 0xC), rutf8(0xA, 0xB)), s.asSlice())

        s = Utf8Sequence.Four(listOf(rutf8(0xA, 0xB), rutf8(0xB, 0xC), rutf8(0xC, 0xD), rutf8(0xD, 0xE)))
        s = s.reverse()
        assertEquals(listOf(rutf8(0xD, 0xE), rutf8(0xC, 0xD), rutf8(0xB, 0xC), rutf8(0xA, 0xB)), s.asSlice())
    }

    private fun isUnicodeScalarValue(cp: Int): Boolean {
        if (cp < 0 || cp > 0x10FFFF) return false
        return cp !in 0xD800..0xDFFF
    }

    private fun encodeSurrogate(cp: Int): ByteArray {
        val tagCont = 0b1000_0000
        val tagThreeB = 0b1110_0000

        check(cp in 0xD800 until 0xE000)
        val dst = ByteArray(3)
        dst[0] = ((cp ushr 12) and 0x0F or tagThreeB).toByte()
        dst[1] = ((cp ushr 6) and 0x3F or tagCont).toByte()
        dst[2] = ((cp and 0x3F) or tagCont).toByte()
        return dst
    }
}
