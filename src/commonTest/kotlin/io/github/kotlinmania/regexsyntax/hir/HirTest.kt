// port-lint: source hir/mod.rs
package io.github.kotlinmania.regexsyntax.hir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HirTest {
    private fun uclass(vararg ranges: Pair<Int, Int>): ClassUnicode =
        ClassUnicode.new(ranges.map { (start, end) -> ClassUnicodeRange.new(start, end) })

    private fun bclass(vararg ranges: Pair<Int, Int>): ClassBytes =
        ClassBytes.new(ranges.map { (start, end) -> ClassBytesRange.new(start.toByte(), end.toByte()) })

    private fun uranges(cls: ClassUnicode): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        val iter = cls.iter()
        while (iter.hasNext()) {
            val range = iter.next()
            ranges.add(range.start() to range.end())
        }
        return ranges
    }

    private fun ucasefold(cls: ClassUnicode): ClassUnicode {
        val cls_ = uclass(*uranges(cls).toTypedArray())
        cls_.caseFoldSimple()
        return cls_
    }

    private fun uunion(cls1: ClassUnicode, cls2: ClassUnicode): ClassUnicode {
        val cls_ = uclass(*uranges(cls1).toTypedArray())
        cls_.union(cls2)
        return cls_
    }

    private fun uintersect(cls1: ClassUnicode, cls2: ClassUnicode): ClassUnicode {
        val cls_ = uclass(*uranges(cls1).toTypedArray())
        cls_.intersect(cls2)
        return cls_
    }

    private fun udifference(cls1: ClassUnicode, cls2: ClassUnicode): ClassUnicode {
        val cls_ = uclass(*uranges(cls1).toTypedArray())
        cls_.difference(cls2)
        return cls_
    }

    private fun usymdifference(cls1: ClassUnicode, cls2: ClassUnicode): ClassUnicode {
        val cls_ = uclass(*uranges(cls1).toTypedArray())
        cls_.symmetricDifference(cls2)
        return cls_
    }

    private fun unegate(cls: ClassUnicode): ClassUnicode {
        val cls_ = uclass(*uranges(cls).toTypedArray())
        cls_.negate()
        return cls_
    }

    private fun branges(cls: ClassBytes): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        val iter = cls.iter()
        while (iter.hasNext()) {
            val range = iter.next()
            ranges.add((range.start().toInt() and 0xFF) to (range.end().toInt() and 0xFF))
        }
        return ranges
    }

    private fun bcasefold(cls: ClassBytes): ClassBytes {
        val cls_ = bclass(*branges(cls).toTypedArray())
        cls_.caseFoldSimple()
        return cls_
    }

    private fun bunion(cls1: ClassBytes, cls2: ClassBytes): ClassBytes {
        val cls_ = bclass(*branges(cls1).toTypedArray())
        cls_.union(cls2)
        return cls_
    }

    private fun bintersect(cls1: ClassBytes, cls2: ClassBytes): ClassBytes {
        val cls_ = bclass(*branges(cls1).toTypedArray())
        cls_.intersect(cls2)
        return cls_
    }

    private fun bdifference(cls1: ClassBytes, cls2: ClassBytes): ClassBytes {
        val cls_ = bclass(*branges(cls1).toTypedArray())
        cls_.difference(cls2)
        return cls_
    }

    private fun bsymdifference(cls1: ClassBytes, cls2: ClassBytes): ClassBytes {
        val cls_ = bclass(*branges(cls1).toTypedArray())
        cls_.symmetricDifference(cls2)
        return cls_
    }

    private fun bnegate(cls: ClassBytes): ClassBytes {
        val cls_ = bclass(*branges(cls).toTypedArray())
        cls_.negate()
        return cls_
    }

    private fun c(ch: Char): Int = ch.code

    @Test
    fun class_range_canonical_unicode() {
        val range = ClassUnicodeRange.new(0x00FF, 0)
        assertEquals(0, range.start())
        assertEquals(0x00FF, range.end())
    }

    @Test
    fun class_range_canonical_bytes() {
        val range = ClassBytesRange.new(0xFF.toByte(), 0)
        assertEquals(0, range.start().toInt() and 0xFF)
        assertEquals(0xFF, range.end().toInt() and 0xFF)
    }

    @Test
    fun class_canonicalize_unicode() {
        var cls = uclass(c('a') to c('c'), c('x') to c('z'))
        var expected = listOf(c('a') to c('c'), c('x') to c('z'))
        assertEquals(expected, uranges(cls))

        cls = uclass(c('x') to c('z'), c('a') to c('c'))
        expected = listOf(c('a') to c('c'), c('x') to c('z'))
        assertEquals(expected, uranges(cls))

        cls = uclass(c('x') to c('z'), c('w') to c('y'))
        expected = listOf(c('w') to c('z'))
        assertEquals(expected, uranges(cls))

        cls = uclass(c('c') to c('f'), c('a') to c('g'), c('d') to c('j'), c('a') to c('c'), c('m') to c('p'), c('l') to c('s'))
        expected = listOf(c('a') to c('j'), c('l') to c('s'))
        assertEquals(expected, uranges(cls))

        cls = uclass(c('x') to c('z'), c('u') to c('w'))
        expected = listOf(c('u') to c('z'))
        assertEquals(expected, uranges(cls))

        cls = uclass(0 to 0x10FFFF, 0 to 0x10FFFF)
        expected = listOf(0 to 0x10FFFF)
        assertEquals(expected, uranges(cls))

        cls = uclass(c('a') to c('a'), c('b') to c('b'))
        expected = listOf(c('a') to c('b'))
        assertEquals(expected, uranges(cls))
    }

    @Test
    fun class_canonicalize_bytes() {
        var cls = bclass(c('a') to c('c'), c('x') to c('z'))
        var expected = listOf(c('a') to c('c'), c('x') to c('z'))
        assertEquals(expected, branges(cls))

        cls = bclass(c('x') to c('z'), c('a') to c('c'))
        expected = listOf(c('a') to c('c'), c('x') to c('z'))
        assertEquals(expected, branges(cls))

        cls = bclass(c('x') to c('z'), c('w') to c('y'))
        expected = listOf(c('w') to c('z'))
        assertEquals(expected, branges(cls))

        cls = bclass(c('c') to c('f'), c('a') to c('g'), c('d') to c('j'), c('a') to c('c'), c('m') to c('p'), c('l') to c('s'))
        expected = listOf(c('a') to c('j'), c('l') to c('s'))
        assertEquals(expected, branges(cls))

        cls = bclass(c('x') to c('z'), c('u') to c('w'))
        expected = listOf(c('u') to c('z'))
        assertEquals(expected, branges(cls))

        cls = bclass(0 to 0xFF, 0 to 0xFF)
        expected = listOf(0 to 0xFF)
        assertEquals(expected, branges(cls))

        cls = bclass(c('a') to c('a'), c('b') to c('b'))
        expected = listOf(c('a') to c('b'))
        assertEquals(expected, branges(cls))
    }

    @Test
    fun class_case_fold_unicode() {
        var cls = uclass(c('C') to c('F'), c('A') to c('G'), c('D') to c('J'), c('A') to c('C'), c('M') to c('P'), c('L') to c('S'), c('c') to c('f'))
        var expected = uclass(c('A') to c('J'), c('L') to c('S'), c('a') to c('j'), c('l') to c('s'), 0x017F to 0x017F)
        assertEquals(expected, ucasefold(cls))

        cls = uclass(c('A') to c('Z'))
        expected = uclass(c('A') to c('Z'), c('a') to c('z'), 0x017F to 0x017F, 0x212A to 0x212A)
        assertEquals(expected, ucasefold(cls))

        cls = uclass(c('a') to c('z'))
        expected = uclass(c('A') to c('Z'), c('a') to c('z'), 0x017F to 0x017F, 0x212A to 0x212A)
        assertEquals(expected, ucasefold(cls))

        cls = uclass(c('A') to c('A'), c('_') to c('_'))
        expected = uclass(c('A') to c('A'), c('_') to c('_'), c('a') to c('a'))
        assertEquals(expected, ucasefold(cls))

        cls = uclass(c('A') to c('A'), c('=') to c('='))
        expected = uclass(c('=') to c('='), c('A') to c('A'), c('a') to c('a'))
        assertEquals(expected, ucasefold(cls))

        cls = uclass(0 to 0x10)
        assertEquals(cls, ucasefold(cls))

        cls = uclass(c('k') to c('k'))
        expected = uclass(c('K') to c('K'), c('k') to c('k'), 0x212A to 0x212A)
        assertEquals(expected, ucasefold(cls))

        cls = uclass(c('@') to c('@'))
        assertEquals(cls, ucasefold(cls))
    }

    @Test
    fun class_case_fold_unicode_disabled() {
        val cls = uclass(c('C') to c('F'), c('A') to c('G'), c('D') to c('J'), c('A') to c('C'), c('M') to c('P'), c('L') to c('S'), c('c') to c('f'))
        assertTrue(cls.tryCaseFoldSimple().isSuccess)
    }

    @Test
    fun class_case_fold_unicode_disabled_panics() {
        val cls = uclass(c('C') to c('F'), c('A') to c('G'), c('D') to c('J'), c('A') to c('C'), c('M') to c('P'), c('L') to c('S'), c('c') to c('f'))
        val expected = uclass(c('A') to c('J'), c('L') to c('S'), c('a') to c('j'), c('l') to c('s'), 0x017F to 0x017F)
        cls.caseFoldSimple()
        assertEquals(expected, cls)
    }

    @Test
    fun class_case_fold_bytes() {
        var cls = bclass(c('C') to c('F'), c('A') to c('G'), c('D') to c('J'), c('A') to c('C'), c('M') to c('P'), c('L') to c('S'), c('c') to c('f'))
        var expected = bclass(c('A') to c('J'), c('L') to c('S'), c('a') to c('j'), c('l') to c('s'))
        assertEquals(expected, bcasefold(cls))

        cls = bclass(c('A') to c('Z'))
        expected = bclass(c('A') to c('Z'), c('a') to c('z'))
        assertEquals(expected, bcasefold(cls))

        cls = bclass(c('a') to c('z'))
        expected = bclass(c('A') to c('Z'), c('a') to c('z'))
        assertEquals(expected, bcasefold(cls))

        cls = bclass(c('A') to c('A'), c('_') to c('_'))
        expected = bclass(c('A') to c('A'), c('_') to c('_'), c('a') to c('a'))
        assertEquals(expected, bcasefold(cls))

        cls = bclass(c('A') to c('A'), c('=') to c('='))
        expected = bclass(c('=') to c('='), c('A') to c('A'), c('a') to c('a'))
        assertEquals(expected, bcasefold(cls))

        cls = bclass(0 to 0x10)
        assertEquals(cls, bcasefold(cls))

        cls = bclass(c('k') to c('k'))
        expected = bclass(c('K') to c('K'), c('k') to c('k'))
        assertEquals(expected, bcasefold(cls))

        cls = bclass(c('@') to c('@'))
        assertEquals(cls, bcasefold(cls))
    }

    @Test
    fun class_negate_unicode() {
        var cls = uclass(c('a') to c('a'))
        var expected = uclass(0 to 0x60, c('b') to 0x10FFFF)
        assertEquals(expected, unegate(cls))

        cls = uclass(c('a') to c('a'), c('b') to c('b'))
        expected = uclass(0 to 0x60, c('c') to 0x10FFFF)
        assertEquals(expected, unegate(cls))

        cls = uclass(c('a') to c('c'), c('x') to c('z'))
        expected = uclass(0 to 0x60, c('d') to c('w'), c('{') to 0x10FFFF)
        assertEquals(expected, unegate(cls))

        cls = uclass(0 to c('a'))
        expected = uclass(c('b') to 0x10FFFF)
        assertEquals(expected, unegate(cls))

        cls = uclass(c('a') to 0x10FFFF)
        expected = uclass(0 to 0x60)
        assertEquals(expected, unegate(cls))

        cls = uclass(0 to 0x10FFFF)
        expected = uclass()
        assertEquals(expected, unegate(cls))

        cls = uclass()
        expected = uclass(0 to 0x10FFFF)
        assertEquals(expected, unegate(cls))

        cls = uclass(0 to 0x10FFFD, 0x10FFFF to 0x10FFFF)
        expected = uclass(0x10FFFE to 0x10FFFE)
        assertEquals(expected, unegate(cls))

        cls = uclass(0 to 0xD7FF)
        expected = uclass(0xE000 to 0x10FFFF)
        assertEquals(expected, unegate(cls))

        cls = uclass(0 to 0xD7FE)
        expected = uclass(0xD7FF to 0x10FFFF)
        assertEquals(expected, unegate(cls))

        cls = uclass(0xE000 to 0x10FFFF)
        expected = uclass(0 to 0xD7FF)
        assertEquals(expected, unegate(cls))

        cls = uclass(0xE001 to 0x10FFFF)
        expected = uclass(0 to 0xE000)
        assertEquals(expected, unegate(cls))
    }

    @Test
    fun class_negate_bytes() {
        var cls = bclass(c('a') to c('a'))
        var expected = bclass(0 to 0x60, c('b') to 0xFF)
        assertEquals(expected, bnegate(cls))

        cls = bclass(c('a') to c('a'), c('b') to c('b'))
        expected = bclass(0 to 0x60, c('c') to 0xFF)
        assertEquals(expected, bnegate(cls))

        cls = bclass(c('a') to c('c'), c('x') to c('z'))
        expected = bclass(0 to 0x60, c('d') to c('w'), c('{') to 0xFF)
        assertEquals(expected, bnegate(cls))

        cls = bclass(0 to c('a'))
        expected = bclass(c('b') to 0xFF)
        assertEquals(expected, bnegate(cls))

        cls = bclass(c('a') to 0xFF)
        expected = bclass(0 to 0x60)
        assertEquals(expected, bnegate(cls))

        cls = bclass(0 to 0xFF)
        expected = bclass()
        assertEquals(expected, bnegate(cls))

        cls = bclass()
        expected = bclass(0 to 0xFF)
        assertEquals(expected, bnegate(cls))

        cls = bclass(0 to 0xFD, 0xFF to 0xFF)
        expected = bclass(0xFE to 0xFE)
        assertEquals(expected, bnegate(cls))
    }

    @Test
    fun class_union_unicode() {
        val cls1 = uclass(c('a') to c('g'), c('m') to c('t'), c('A') to c('C'))
        val cls2 = uclass(c('a') to c('z'))
        val expected = uclass(c('a') to c('z'), c('A') to c('C'))
        assertEquals(expected, uunion(cls1, cls2))
    }

    @Test
    fun class_union_bytes() {
        val cls1 = bclass(c('a') to c('g'), c('m') to c('t'), c('A') to c('C'))
        val cls2 = bclass(c('a') to c('z'))
        val expected = bclass(c('a') to c('z'), c('A') to c('C'))
        assertEquals(expected, bunion(cls1, cls2))
    }

    @Test
    fun class_intersect_unicode() {
        var cls1 = uclass()
        var cls2 = uclass(c('a') to c('a'))
        var expected = uclass()
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('a'))
        cls2 = uclass(c('a') to c('a'))
        expected = uclass(c('a') to c('a'))
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('a'))
        cls2 = uclass(c('b') to c('b'))
        expected = uclass()
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('a'))
        cls2 = uclass(c('a') to c('c'))
        expected = uclass(c('a') to c('a'))
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('b'))
        cls2 = uclass(c('a') to c('c'))
        expected = uclass(c('a') to c('b'))
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('b'))
        cls2 = uclass(c('b') to c('c'))
        expected = uclass(c('b') to c('b'))
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('b'))
        cls2 = uclass(c('c') to c('d'))
        expected = uclass()
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('b') to c('c'))
        cls2 = uclass(c('a') to c('d'))
        expected = uclass(c('b') to c('c'))
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        cls2 = uclass(c('a') to c('h'))
        expected = uclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        cls2 = uclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        expected = uclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('b'), c('g') to c('h'))
        cls2 = uclass(c('d') to c('e'), c('k') to c('l'))
        expected = uclass()
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        cls2 = uclass(c('h') to c('h'))
        expected = uclass(c('h') to c('h'))
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('b'), c('e') to c('f'), c('i') to c('j'))
        cls2 = uclass(c('c') to c('d'), c('g') to c('h'), c('k') to c('l'))
        expected = uclass()
        assertEquals(expected, uintersect(cls1, cls2))

        cls1 = uclass(c('a') to c('b'), c('c') to c('d'), c('e') to c('f'))
        cls2 = uclass(c('b') to c('c'), c('d') to c('e'), c('f') to c('g'))
        expected = uclass(c('b') to c('f'))
        assertEquals(expected, uintersect(cls1, cls2))
    }

    @Test
    fun class_intersect_bytes() {
        var cls1 = bclass()
        var cls2 = bclass(c('a') to c('a'))
        var expected = bclass()
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('a'))
        cls2 = bclass(c('a') to c('a'))
        expected = bclass(c('a') to c('a'))
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('a'))
        cls2 = bclass(c('b') to c('b'))
        expected = bclass()
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('a'))
        cls2 = bclass(c('a') to c('c'))
        expected = bclass(c('a') to c('a'))
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('b'))
        cls2 = bclass(c('a') to c('c'))
        expected = bclass(c('a') to c('b'))
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('b'))
        cls2 = bclass(c('b') to c('c'))
        expected = bclass(c('b') to c('b'))
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('b'))
        cls2 = bclass(c('c') to c('d'))
        expected = bclass()
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('b') to c('c'))
        cls2 = bclass(c('a') to c('d'))
        expected = bclass(c('b') to c('c'))
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        cls2 = bclass(c('a') to c('h'))
        expected = bclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        cls2 = bclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        expected = bclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('b'), c('g') to c('h'))
        cls2 = bclass(c('d') to c('e'), c('k') to c('l'))
        expected = bclass()
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('b'), c('d') to c('e'), c('g') to c('h'))
        cls2 = bclass(c('h') to c('h'))
        expected = bclass(c('h') to c('h'))
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('b'), c('e') to c('f'), c('i') to c('j'))
        cls2 = bclass(c('c') to c('d'), c('g') to c('h'), c('k') to c('l'))
        expected = bclass()
        assertEquals(expected, bintersect(cls1, cls2))

        cls1 = bclass(c('a') to c('b'), c('c') to c('d'), c('e') to c('f'))
        cls2 = bclass(c('b') to c('c'), c('d') to c('e'), c('f') to c('g'))
        expected = bclass(c('b') to c('f'))
        assertEquals(expected, bintersect(cls1, cls2))
    }

    @Test
    fun class_difference_unicode() {
        var cls1 = uclass(c('a') to c('a'))
        var cls2 = uclass(c('a') to c('a'))
        var expected = uclass()
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass(c('a') to c('a'))
        cls2 = uclass()
        expected = uclass(c('a') to c('a'))
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass()
        cls2 = uclass(c('a') to c('a'))
        expected = uclass()
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass(c('a') to c('z'))
        cls2 = uclass(c('a') to c('a'))
        expected = uclass(c('b') to c('z'))
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass(c('a') to c('z'))
        cls2 = uclass(c('z') to c('z'))
        expected = uclass(c('a') to c('y'))
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass(c('a') to c('z'))
        cls2 = uclass(c('m') to c('m'))
        expected = uclass(c('a') to c('l'), c('n') to c('z'))
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass(c('a') to c('c'), c('g') to c('i'), c('r') to c('t'))
        cls2 = uclass(c('a') to c('z'))
        expected = uclass()
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass(c('a') to c('c'), c('g') to c('i'), c('r') to c('t'))
        cls2 = uclass(c('d') to c('v'))
        expected = uclass(c('a') to c('c'))
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass(c('a') to c('c'), c('g') to c('i'), c('r') to c('t'))
        cls2 = uclass(c('b') to c('g'), c('s') to c('u'))
        expected = uclass(c('a') to c('a'), c('h') to c('i'), c('r') to c('r'))
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass(c('a') to c('c'), c('g') to c('i'), c('r') to c('t'))
        cls2 = uclass(c('b') to c('d'), c('e') to c('g'), c('s') to c('u'))
        expected = uclass(c('a') to c('a'), c('h') to c('i'), c('r') to c('r'))
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass(c('x') to c('z'))
        cls2 = uclass(c('a') to c('c'), c('e') to c('g'), c('s') to c('u'))
        expected = uclass(c('x') to c('z'))
        assertEquals(expected, udifference(cls1, cls2))

        cls1 = uclass(c('a') to c('z'))
        cls2 = uclass(c('a') to c('c'), c('e') to c('g'), c('s') to c('u'))
        expected = uclass(c('d') to c('d'), c('h') to c('r'), c('v') to c('z'))
        assertEquals(expected, udifference(cls1, cls2))
    }

    @Test
    fun class_difference_bytes() {
        var cls1 = bclass(c('a') to c('a'))
        var cls2 = bclass(c('a') to c('a'))
        var expected = bclass()
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass(c('a') to c('a'))
        cls2 = bclass()
        expected = bclass(c('a') to c('a'))
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass()
        cls2 = bclass(c('a') to c('a'))
        expected = bclass()
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass(c('a') to c('z'))
        cls2 = bclass(c('a') to c('a'))
        expected = bclass(c('b') to c('z'))
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass(c('a') to c('z'))
        cls2 = bclass(c('z') to c('z'))
        expected = bclass(c('a') to c('y'))
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass(c('a') to c('z'))
        cls2 = bclass(c('m') to c('m'))
        expected = bclass(c('a') to c('l'), c('n') to c('z'))
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass(c('a') to c('c'), c('g') to c('i'), c('r') to c('t'))
        cls2 = bclass(c('a') to c('z'))
        expected = bclass()
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass(c('a') to c('c'), c('g') to c('i'), c('r') to c('t'))
        cls2 = bclass(c('d') to c('v'))
        expected = bclass(c('a') to c('c'))
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass(c('a') to c('c'), c('g') to c('i'), c('r') to c('t'))
        cls2 = bclass(c('b') to c('g'), c('s') to c('u'))
        expected = bclass(c('a') to c('a'), c('h') to c('i'), c('r') to c('r'))
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass(c('a') to c('c'), c('g') to c('i'), c('r') to c('t'))
        cls2 = bclass(c('b') to c('d'), c('e') to c('g'), c('s') to c('u'))
        expected = bclass(c('a') to c('a'), c('h') to c('i'), c('r') to c('r'))
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass(c('x') to c('z'))
        cls2 = bclass(c('a') to c('c'), c('e') to c('g'), c('s') to c('u'))
        expected = bclass(c('x') to c('z'))
        assertEquals(expected, bdifference(cls1, cls2))

        cls1 = bclass(c('a') to c('z'))
        cls2 = bclass(c('a') to c('c'), c('e') to c('g'), c('s') to c('u'))
        expected = bclass(c('d') to c('d'), c('h') to c('r'), c('v') to c('z'))
        assertEquals(expected, bdifference(cls1, cls2))
    }

    @Test
    fun class_symmetric_difference_unicode() {
        val cls1 = uclass(c('a') to c('m'))
        val cls2 = uclass(c('g') to c('t'))
        val expected = uclass(c('a') to c('f'), c('n') to c('t'))
        assertEquals(expected, usymdifference(cls1, cls2))
    }

    @Test
    fun class_symmetric_difference_bytes() {
        val cls1 = bclass(c('a') to c('m'))
        val cls2 = bclass(c('g') to c('t'))
        val expected = bclass(c('a') to c('f'), c('n') to c('t'))
        assertEquals(expected, bsymdifference(cls1, cls2))
    }

    // Rust uses a thread with an explicit stack size to test that its
    // destructor for Hir can handle arbitrarily sized expressions in constant
    // stack space. Kotlin commonTest has no portable explicit-stack thread
    // API, so this keeps the same nested expression construction in common
    // code.
    @Test
    fun no_stack_overflow_on_drop() {
        var expr = Hir.empty()
        for (i in 0 until 100) {
            expr = Hir.capture(Capture(index = 1u, name = null, sub = expr))
            expr = Hir.repetition(Repetition(min = 0u, max = 1u, greedy = true, sub = expr))

            expr = Hir(HirKind.Concat(listOf(expr)), Properties.empty())
            expr = Hir(HirKind.Alternation(listOf(expr)), Properties.empty())
        }
        assertTrue(expr.kind() !is HirKind.Empty)
    }

    @Test
    fun look_set_iter() {
        var set = LookSet.empty()
        assertEquals(0, set.count())

        set = LookSet.full()
        assertEquals(18, set.count())

        set = LookSet.empty().insert(Look.StartLF).insert(Look.WordUnicode)
        assertEquals(2, set.count())

        set = LookSet.empty().insert(Look.StartLF)
        assertEquals(1, set.count())

        set = LookSet.empty().insert(Look.WordAsciiNegate)
        assertEquals(1, set.count())
    }

    @Test
    fun look_set_debug() {
        var res = LookSet.empty().toString()
        assertEquals("∅", res)
        res = LookSet.full().toString()
        assertEquals("Az^${'$'}rRbB𝛃𝚩<>〈〉◁▷◀▶", res)
    }
}
