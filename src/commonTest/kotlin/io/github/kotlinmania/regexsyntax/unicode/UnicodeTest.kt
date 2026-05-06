// port-lint: source unicode.rs
package io.github.kotlinmania.regexsyntax.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnicodeTest {
    private fun `simple_fold_ok`(c: Int): IntArray {
        return SimpleCaseFolder.new().getOrThrow().mapping(c)
    }

    private fun `contains_case_map`(start: Int, end: Int): Boolean {
        return SimpleCaseFolder.new().getOrThrow().overlaps(start, end)
    }

    @Test
    fun `simple_fold_k`() {
        assertEquals(intArrayOf('K'.code, 'K'.code).toList(), `simple_fold_ok`('k'.code).toList())
        assertEquals(intArrayOf('k'.code, 'K'.code).toList(), `simple_fold_ok`('K'.code).toList())
        assertEquals(intArrayOf('K'.code, 'k'.code).toList(), `simple_fold_ok`('K'.code).toList())
    }

    @Test
    fun `simple_fold_a`() {
        assertEquals(intArrayOf('A'.code).toList(), `simple_fold_ok`('a'.code).toList())
        assertEquals(intArrayOf('a'.code).toList(), `simple_fold_ok`('A'.code).toList())
    }

    @Test
    fun `range_contains`() {
        assertTrue(`contains_case_map`('A'.code, 'A'.code))
        assertTrue(`contains_case_map`('Z'.code, 'Z'.code))
        assertTrue(`contains_case_map`('A'.code, 'Z'.code))
        assertTrue(`contains_case_map`('@'.code, 'A'.code))
        assertTrue(`contains_case_map`('Z'.code, '['.code))
        assertTrue(`contains_case_map`('☃'.code, 'Ⰰ'.code))

        assertFalse(`contains_case_map`('['.code, '['.code))
        assertFalse(`contains_case_map`('['.code, '`'.code))

        assertFalse(`contains_case_map`('☃'.code, '☃'.code))
    }

    @Test
    fun `regression_466`() {
        val q = ClassQuery.OneLetter('C'.code)
        assertEquals(CanonicalClassQuery.GeneralCategory("Other"), q.canonicalize().getOrThrow())
    }

    @Test
    fun `sym_normalize`() {
        val symNorm = ::symbolicNameNormalize

        assertEquals("linebreak", symNorm("Line_Break"))
        assertEquals("linebreak", symNorm("Line-break"))
        assertEquals("linebreak", symNorm("linebreak"))
        assertEquals("ba", symNorm("BA"))
        assertEquals("ba", symNorm("ba"))
        assertEquals("greek", symNorm("Greek"))
        assertEquals("greek", symNorm("isGreek"))
        assertEquals("greek", symNorm("IS_Greek"))
        assertEquals("isc", symNorm("isc"))
        assertEquals("isc", symNorm("is c"))
        assertEquals("isc", symNorm("is_c"))
    }

    @Test
    fun `valid_utf8_symbolic`() {
        val x = byteArrayOf(
            'a'.code.toByte(),
            'b'.code.toByte(),
            'c'.code.toByte(),
            0xFF.toByte(),
            'x'.code.toByte(),
            'y'.code.toByte(),
            'z'.code.toByte(),
        )
        val len = symbolicNameNormalizeBytes(x)
        assertEquals("abcxyz", x.decodeToString(0, len))
    }
}
