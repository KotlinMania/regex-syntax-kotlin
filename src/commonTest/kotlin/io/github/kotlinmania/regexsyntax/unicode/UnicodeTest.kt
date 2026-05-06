// port-lint: source unicode.rs
package io.github.kotlinmania.regexsyntax.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun simpleFoldOk(c: Int): IntArray {
    return SimpleCaseFolder.new().getOrThrow().mapping(c)
}

private fun containsCaseMap(start: Int, end: Int): Boolean {
    return SimpleCaseFolder.new().getOrThrow().overlaps(start, end)
}

class UnicodeTest {
    @Test
    fun `simple_fold_k`() {
        assertEquals(intArrayOf('K'.code, 'K'.code).toList(), simpleFoldOk('k'.code).toList())
        assertEquals(intArrayOf('k'.code, 'K'.code).toList(), simpleFoldOk('K'.code).toList())
        assertEquals(intArrayOf('K'.code, 'k'.code).toList(), simpleFoldOk('K'.code).toList())
    }

    @Test
    fun `simple_fold_a`() {
        assertEquals(intArrayOf('A'.code).toList(), simpleFoldOk('a'.code).toList())
        assertEquals(intArrayOf('a'.code).toList(), simpleFoldOk('A'.code).toList())
    }

    @Test
    fun `simple_fold_disabled`() {
        // In upstream Rust this is only compiled when the `unicode-case`
        // feature is disabled. This Kotlin port always ships with the Unicode
        // case folding tables, so construction should always succeed.
        assertTrue(SimpleCaseFolder.new().isSuccess)
    }

    @Test
    fun `range_contains`() {
        assertTrue(containsCaseMap('A'.code, 'A'.code))
        assertTrue(containsCaseMap('Z'.code, 'Z'.code))
        assertTrue(containsCaseMap('A'.code, 'Z'.code))
        assertTrue(containsCaseMap('@'.code, 'A'.code))
        assertTrue(containsCaseMap('Z'.code, '['.code))
        assertTrue(containsCaseMap('☃'.code, 'Ⰰ'.code))

        assertFalse(containsCaseMap('['.code, '['.code))
        assertFalse(containsCaseMap('['.code, '`'.code))

        assertFalse(containsCaseMap('☃'.code, '☃'.code))
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
