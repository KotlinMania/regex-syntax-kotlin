package io.github.kotlinmania.regexsyntax.utf8

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Utf8OrderingParityTest {
    private fun range(start: Int, end: Int = start): Utf8Range = Utf8Range(start.toUByte(), end.toUByte())

    @Test
    fun variantsOrderBeforeTheirByteRanges() {
        val variants =
            listOf(
                Utf8Sequence.One(range(255)),
                Utf8Sequence.Two(listOf(range(128), range(255))),
                Utf8Sequence.Three(listOf(range(1), range(255), range(255))),
                Utf8Sequence.Four(listOf(range(0), range(0), range(0), range(0))),
            )
        for (left in variants.indices) {
            for (right in variants.indices) {
                assertEquals(left.compareTo(right), variants[left].compareTo(variants[right]).compareTo(0))
            }
        }
        assertEquals(variants, variants.reversed().sorted())
    }

    @Test
    fun equalVariantsOrderTheirUnsignedRangesLexicographically() {
        val low = Utf8Sequence.Two(listOf(range(127), range(255)))
        val high = Utf8Sequence.Two(listOf(range(128), range(0)))
        assertTrue(low < high)
        assertTrue(Utf8Sequence.One(range(128, 129)) < Utf8Sequence.One(range(128, 130)))
        assertEquals(0, low.compareTo(low.copy()))
    }

    @Test
    fun realUtf8RangesRemainInVariantOrderAfterReversal() {
        val one = Utf8Sequence.One(range(0, 127))
        val two = Utf8Sequence.Two(listOf(range(0xc2, 0xdf), range(0x80, 0xbf)))
        val three = Utf8Sequence.Three(listOf(range(0xe0), range(0xa0, 0xbf), range(0x80, 0xbf)))
        val four = Utf8Sequence.Four(listOf(range(0xf0), range(0x90, 0xbf), range(0x80, 0xbf), range(0x80, 0xbf)))
        assertEquals(listOf(one, two, three, four), listOf(four, two, three, one).sorted())
        two.reverse()
        three.reverse()
        assertTrue(two < three)
    }
}
