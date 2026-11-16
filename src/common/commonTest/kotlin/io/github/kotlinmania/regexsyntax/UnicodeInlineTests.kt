// port-lint: tests unicode.rs
package io.github.kotlinmania.regexsyntax.unicode

import io.github.kotlinmania.regexsyntax.unwrap

public fun simpleFoldOk(c: UInt): List<UInt> = SimpleCaseFolder.new().unwrap().mapping(c)

public fun containsCaseMap(start: UInt, end: UInt): Boolean =
    SimpleCaseFolder.new().unwrap().overlaps(start, end)

// Upstream unicode-case disabled test omitted because unicode-case is always enabled in KMP.

public class UnicodeInlineTests {
    @kotlin.test.Test
    public fun simpleFoldK() {
        val xs = simpleFoldOk('k'.code.toUInt())
        check(xs == listOf('K'.code.toUInt(), 0x212Au))
        val xs1 = simpleFoldOk('K'.code.toUInt())
        check(xs1 == listOf('k'.code.toUInt(), 0x212Au))
        val xs2 = simpleFoldOk(0x212Au)
        check(xs2 == listOf('K'.code.toUInt(), 'k'.code.toUInt()))
    }

    @kotlin.test.Test
    public fun simpleFoldA() {
        val xs = simpleFoldOk('a'.code.toUInt())
        check(xs == listOf('A'.code.toUInt()))
        val xs1 = simpleFoldOk('A'.code.toUInt())
        check(xs1 == listOf('a'.code.toUInt()))
    }

    @kotlin.test.Test
    public fun rangeContains() {
        check(containsCaseMap('A'.code.toUInt(), 'A'.code.toUInt()))
        check(containsCaseMap('Z'.code.toUInt(), 'Z'.code.toUInt()))
        check(containsCaseMap('A'.code.toUInt(), 'Z'.code.toUInt()))
        check(containsCaseMap('@'.code.toUInt(), 'A'.code.toUInt()))
        check(containsCaseMap('Z'.code.toUInt(), '['.code.toUInt()))
        check(containsCaseMap(0x2603u, 0x2c00u))
        check(!containsCaseMap('['.code.toUInt(), '['.code.toUInt()))
        check(!containsCaseMap('['.code.toUInt(), '`'.code.toUInt()))
        check(!containsCaseMap(0x2603u, 0x2603u))
    }

    @kotlin.test.Test
    public fun regression466() {
        val q = ClassQuery.OneLetter('C'.code.toUInt())
        check(q.canonicalize().unwrap() == CanonicalClassQuery.GeneralCategory("Other"))
    }

    @kotlin.test.Test
    public fun symNormalize() {
        check(symbolicNameNormalize("Line_Break") == "linebreak")
        check(symbolicNameNormalize("Line-break") == "linebreak")
        check(symbolicNameNormalize("linebreak") == "linebreak")
        check(symbolicNameNormalize("BA") == "ba")
        check(symbolicNameNormalize("ba") == "ba")
        check(symbolicNameNormalize("Greek") == "greek")
        check(symbolicNameNormalize("isGreek") == "greek")
        check(symbolicNameNormalize("IS_Greek") == "greek")
        check(symbolicNameNormalize("isc") == "isc")
        check(symbolicNameNormalize("is c") == "isc")
        check(symbolicNameNormalize("is_c") == "isc")
    }

    @kotlin.test.Test
    public fun validUtf8Symbolic() {
        val x = "abcÿxyz".encodeToByteArray().map { it.toUByte() }.toMutableList()
        val y = symbolicNameNormalizeBytes(x)
        check(y == "abcxyz".encodeToByteArray().map { it.toUByte() })
    }
}
