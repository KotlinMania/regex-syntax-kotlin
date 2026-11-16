package io.github.kotlinmania.regexsyntax.utf8

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Utf8ReverseParityTest {
    private fun range(value: Int): Utf8Range = Utf8Range(value.toUByte(), value.toUByte())

    @Test
    fun reverseMutatesEveryVariantAndReturnsUnit() {
        val variants =
            listOf(
                Utf8Sequence.One(range(1)),
                Utf8Sequence.Two(listOf(range(1), range(2))),
                Utf8Sequence.Three(listOf(range(1), range(2), range(3))),
                Utf8Sequence.Four(listOf(range(1), range(2), range(3), range(4))),
            )
        for (sequence in variants) {
            val before = sequence.asSlice().toList()
            val result: Any = sequence.reverse()
            assertEquals(Unit, result)
            assertEquals(before.reversed(), sequence.asSlice())
            sequence.reverse()
            assertEquals(before, sequence.asSlice())
        }
    }

    @Test
    fun copiesKeepTheirOriginalRangeOrder() {
        val original = Utf8Sequence.Two(listOf(range(1), range(2)))
        val copy = original.copy()
        original.reverse()
        assertEquals(listOf(range(2), range(1)), original.asSlice())
        assertEquals(listOf(range(1), range(2)), copy.asSlice())
        assertTrue(original != copy)
        copy.reverse()
        assertEquals(original, copy)
    }

    @Test
    fun matchingUsesTheMutatedRangesAndStillAcceptsPrefixes() {
        val sequence = Utf8Sequence.Two(listOf(Utf8Range(0xd0u, 0xd3u), Utf8Range(0x80u, 0xbfu)))
        val forward = listOf(0xd1u.toUByte(), 0x90u.toUByte())
        val backward = forward.reversed()
        assertTrue(sequence.matches(forward))
        assertFalse(sequence.matches(backward))
        sequence.reverse()
        assertTrue(sequence.matches(backward))
        assertTrue(sequence.matches(backward + 0u.toUByte()))
        assertFalse(sequence.matches(forward))
        assertFalse(sequence.matches(listOf(0x90u.toUByte())))
    }
}
