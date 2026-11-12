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
                ClassUnicode.empty().iter(),
                ClassBytes.empty().iter(),
                IntervalSetIter(emptyList<Int>().iterator()),
            )
        for (iterator in iterators) {
            assertFalse(iterator.hasNext())
            assertFailsWith<NoSuchElementException> { iterator.next() }
        }
    }

    @Test
    fun classRangeIteratorsPreserveBoundsAndExhaustion() {
        val unicode = ClassUnicode.new(listOf(ClassUnicodeRange.new('a'.code, 'c'.code))).iter()
        assertTrue(unicode.hasNext())
        assertTrue(unicode.hasNext())
        val unicodeRange = unicode.next()
        assertEquals('a'.code, unicodeRange.start())
        assertEquals('c'.code, unicodeRange.end())
        assertFalse(unicode.hasNext())
        assertFailsWith<NoSuchElementException> { unicode.next() }

        val bytes = ClassBytes.new(listOf(ClassBytesRange.new(1.toByte(), 3.toByte()))).iter()
        assertTrue(bytes.hasNext())
        assertTrue(bytes.hasNext())
        val byteRange = bytes.next()
        assertEquals(1.toByte(), byteRange.start())
        assertEquals(3.toByte(), byteRange.end())
        assertFalse(bytes.hasNext())
        assertFailsWith<NoSuchElementException> { bytes.next() }
    }

    @Test
    fun genericIntervalIteratorPreservesNullableValues() {
        val iterator = IntervalSetIter(listOf<Int?>(null, 7).iterator())
        assertTrue(iterator.hasNext())
        assertTrue(iterator.hasNext())
        assertNull(iterator.next())
        assertEquals(7, iterator.next())
        assertFalse(iterator.hasNext())
        assertFailsWith<NoSuchElementException> { iterator.next() }
    }
}
