package io.github.kotlinmania.regexsyntax.ast.parse

import io.github.kotlinmania.regexsyntax.ast.ClassAsciiKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AsciiClassScanRegressionTest {
    @Test
    fun scanStopsAtTerminatorAndRestoresPositionOnEof() {
        val terminated = ParserI(Parser(), "[:alpha:]tail")
        val parsed = assertNotNull(terminated.maybeParseAsciiClass())
        assertEquals(ClassAsciiKind.Alpha, parsed.kind)
        assertFalse(parsed.negated)
        assertEquals("[:alpha:]".length, terminated.offset())

        val incomplete = ParserI(Parser(), "[:" + "x".repeat(4096))
        assertNull(incomplete.maybeParseAsciiClass())
        assertEquals(0, incomplete.offset())
    }
}
