package io.github.kotlinmania.regexsyntax

import io.github.kotlinmania.regexsyntax.hir.Class
import io.github.kotlinmania.regexsyntax.hir.HirKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Issue135NegatedUnicodeClassSyntaxTest {
    @Test
    fun parsesNegatedControlCategory() {
        val hir = parse("\\P{Cc}").getOrThrow()
        val kind = hir.kind()
        assertTrue(kind is HirKind.Class)
        val cls = kind.value
        assertTrue(cls is Class.Unicode)
        val ranges = cls.value.ranges()
        // Control characters U+0000..=U+001F and U+007F..=U+009F must be excluded.
        assertFalse(ranges.any { r -> r.start() <= 0x00u && 0x00u <= r.end() })
        assertFalse(ranges.any { r -> r.start() <= 0x0Au && 0x0Au <= r.end() })
        assertFalse(ranges.any { r -> r.start() <= 0x7Fu && 0x7Fu <= r.end() })
        assertTrue(ranges.any { r -> r.start() <= 'a'.code.toUInt() && 'a'.code.toUInt() <= r.end() })
        assertTrue(hir.properties().isUtf8())
    }

    @Test
    fun parsesNegatedUnicodeClassIntersectionWithNegatedNewlines() {
        val hir = parse("[\\P{Cc}&&[^\\r\\n]]").getOrThrow()
        val kind = hir.kind()
        assertTrue(kind is HirKind.Class)
        val cls = kind.value
        assertTrue(cls is Class.Unicode)
        val ranges = cls.value.ranges()
        assertFalse(ranges.any { r -> r.start() <= '\r'.code.toUInt() && '\r'.code.toUInt() <= r.end() })
        assertFalse(ranges.any { r -> r.start() <= '\n'.code.toUInt() && '\n'.code.toUInt() <= r.end() })
        assertTrue(ranges.any { r -> r.start() <= 'Z'.code.toUInt() && 'Z'.code.toUInt() <= r.end() })
    }

    @Test
    fun parsesCyrillicScriptAndCaseInsensitiveNegatedUppercase() {
        val cyr = parse("\\p{Cyrillic}").getOrThrow()
        assertTrue(cyr.kind() is HirKind.Class)
        assertTrue((cyr.properties().minimumLen() ?: 0) > 0)

        val notUpper = parse("(?i)\\P{Lu}").getOrThrow()
        assertTrue(notUpper.kind() is HirKind.Class)
        assertTrue(notUpper.properties().isUtf8())
    }
}
