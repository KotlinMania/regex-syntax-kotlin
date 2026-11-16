package io.github.kotlinmania.regexsyntax.hir

import io.github.kotlinmania.regexsyntax.hir.interval.IntervalSetIter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IteratorExhaustionTest {
    @Test
    fun allRangeIteratorAdaptersRejectEmptyNext() {
        val iterators: List<Iterator<*>> =
            listOf(
                ClassUnicode.empty().iter().iterator(),
                ClassBytes.empty().iter().iterator(),
                IntervalSetIter(emptyList<Int>().iterator()).iterator(),
            )
        for (iterator in iterators) {
            assertFalse(iterator.hasNext())
            assertFailsWith<NoSuchElementException> { iterator.next() }
        }
    }

    @Test
    fun classRangeIteratorsPreserveBoundsAndExhaustion() {
        val unicode = ClassUnicode.new(listOf(ClassUnicodeRange.new('a'.code.toUInt(), 'c'.code.toUInt()))).iter()
        val unicodeIter = unicode.iterator()
        assertTrue(unicodeIter.hasNext())
        assertTrue(unicodeIter.hasNext())
        val unicodeRange = unicodeIter.next()
        assertEquals('a'.code.toUInt(), unicodeRange.start())
        assertEquals('c'.code.toUInt(), unicodeRange.end())
        assertFalse(unicodeIter.hasNext())
        assertFailsWith<NoSuchElementException> { unicodeIter.next() }

        val bytes = ClassBytes.new(listOf(ClassBytesRange.new(1u.toUByte(), 3u.toUByte()))).iter()
        val bytesIter = bytes.iterator()
        assertTrue(bytesIter.hasNext())
        assertTrue(bytesIter.hasNext())
        val byteRange = bytesIter.next()
        assertEquals(1u.toUByte(), byteRange.start())
        assertEquals(3u.toUByte(), byteRange.end())
        assertFalse(bytesIter.hasNext())
        assertFailsWith<NoSuchElementException> { bytesIter.next() }
    }

    @Test
    fun genericIntervalIteratorPreservesNullableValues() {
        val iterator = IntervalSetIter(listOf<Int?>(null, 7).iterator()).iterator()
        assertTrue(iterator.hasNext())
        assertTrue(iterator.hasNext())
        assertNull(iterator.next())
        assertEquals(7, iterator.next())
        assertFalse(iterator.hasNext())
        assertFailsWith<NoSuchElementException> { iterator.next() }
    }
}
