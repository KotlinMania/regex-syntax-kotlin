package io.github.kotlinmania.regexsyntax.hir

// Defines a high-level intermediate (HIR) representation for regular expressions.
//
// The HIR is represented by the [Hir] type, and it principally constructed via
// [translation](translate) from an [`Ast`](ast.Ast). Alternatively, users
// may use the smart constructors defined on `Hir` to build their own by hand. The
// smart constructors simultaneously simplify and "optimize" the HIR, and are also
// the same routines used by translation.
//
// Most regex engines only have an HIR like this, and usually construct it
// directly from the concrete syntax. This crate however first parses the
// concrete syntax into an `Ast`, and only then creates the HIR from the `Ast`,
// as mentioned above. It's done this way to facilitate better error reporting,
// and to have a structured representation of a regex that faithfully represents
// its concrete syntax. Namely, while an `Hir` value can be converted back to an
// equivalent regex pattern string, it is unlikely to look like the original due
// to its simplified structure.

public fun up(start: Char, end: Char): Pair<UInt, UInt> = Pair(start.code.toUInt(), end.code.toUInt())

public fun up(start: Char, end: UInt): Pair<UInt, UInt> = Pair(start.code.toUInt(), end)

public fun up(start: UInt, end: UInt): Pair<UInt, UInt> = Pair(start, end)

public fun uclass(ranges: List<Pair<UInt, UInt>>): ClassUnicode {
    val ranges1: List<ClassUnicodeRange> = ranges.map { (s, e) -> ClassUnicodeRange.new(s, e) }
    return ClassUnicode.new(ranges1)
}

public fun bclass(ranges: List<Pair<UInt, UInt>>): ClassBytes {
    val ranges1: List<ClassBytesRange> = ranges.map { (s, e) -> ClassBytesRange.new(s.toUByte(), e.toUByte()) }
    return ClassBytes.new(ranges1)
}

public fun uranges(cls: ClassUnicode): List<Pair<UInt, UInt>> = cls.iter().map { x -> Pair(x.start(), x.end()) }

public fun ucasefold(cls: ClassUnicode): ClassUnicode {
    val cls = cls.clone()
    cls.caseFoldSimple()
    return cls
}

public fun uunion(cls1: ClassUnicode, cls2: ClassUnicode): ClassUnicode {
    val cls = cls1.clone()
    cls.union(cls2)
    return cls
}

public fun uintersect(cls1: ClassUnicode, cls2: ClassUnicode): ClassUnicode {
    val cls = cls1.clone()
    cls.intersect(cls2)
    return cls
}

public fun udifference(cls1: ClassUnicode, cls2: ClassUnicode): ClassUnicode {
    val cls = cls1.clone()
    cls.difference(cls2)
    return cls
}

public fun usymdifference(cls1: ClassUnicode, cls2: ClassUnicode): ClassUnicode {
    val cls = cls1.clone()
    cls.symmetricDifference(cls2)
    return cls
}

public fun unegate(cls: ClassUnicode): ClassUnicode {
    val cls = cls.clone()
    cls.negate()
    return cls
}

public fun branges(cls: ClassBytes): List<Pair<UInt, UInt>> = cls.iter().map { x -> Pair(x.start().toUInt(), x.end().toUInt()) }

public fun bcasefold(cls: ClassBytes): ClassBytes {
    val cls = cls.clone()
    cls.caseFoldSimple()
    return cls
}

public fun bunion(cls1: ClassBytes, cls2: ClassBytes): ClassBytes {
    val cls = cls1.clone()
    cls.union(cls2)
    return cls
}

public fun bintersect(cls1: ClassBytes, cls2: ClassBytes): ClassBytes {
    val cls = cls1.clone()
    cls.intersect(cls2)
    return cls
}

public fun bdifference(cls1: ClassBytes, cls2: ClassBytes): ClassBytes {
    val cls = cls1.clone()
    cls.difference(cls2)
    return cls
}

public fun bsymdifference(cls1: ClassBytes, cls2: ClassBytes): ClassBytes {
    val cls = cls1.clone()
    cls.symmetricDifference(cls2)
    return cls
}

public fun bnegate(cls: ClassBytes): ClassBytes {
    val cls = cls.clone()
    cls.negate()
    return cls
}

public class HirModInlineTests {
    @kotlin.test.Test
    public fun classRangeCanonicalUnicode() {
        val range = ClassUnicodeRange.new(0x00ffu, 0x0000u)
        check(0x0000u == range.start())
        check(0x00ffu == range.end())
    }

    @kotlin.test.Test
    public fun classRangeCanonicalBytes() {
        val range = ClassBytesRange.new(255u.toUByte(), 0u.toUByte())
        check(0u.toUByte() == range.start())
        check(255u.toUByte() == range.end())
    }

    @kotlin.test.Test
    public fun classCanonicalizeUnicode() {
        val cls = uclass(listOf(up('a', 'c'), up('x', 'z')))
        val expected = mutableListOf(up('a', 'c'), up('x', 'z'))
        check(expected == uranges(cls))
        val cls1 = uclass(listOf(up('x', 'z'), up('a', 'c')))
        val expected1 = mutableListOf(up('a', 'c'), up('x', 'z'))
        check(expected1 == uranges(cls1))
        val cls2 = uclass(listOf(up('x', 'z'), up('w', 'y')))
        val expected2 = mutableListOf(up('w', 'z'))
        check(expected2 == uranges(cls2))
        val cls3 = uclass(listOf(up('c', 'f'), up('a', 'g'), up('d', 'j'), up('a', 'c'), up('m', 'p'), up('l', 's')))
        val expected3 = mutableListOf(up('a', 'j'), up('l', 's'))
        check(expected3 == uranges(cls3))
        val cls4 = uclass(listOf(up('x', 'z'), up('u', 'w')))
        val expected4 = mutableListOf(up('u', 'z'))
        check(expected4 == uranges(cls4))
        val cls5 = uclass(listOf(up('\u0000', 0x10ffffu), up('\u0000', 0x10ffffu)))
        val expected5 = mutableListOf(up('\u0000', 0x10ffffu))
        check(expected5 == uranges(cls5))
        val cls6 = uclass(listOf(up('a', 'a'), up('b', 'b')))
        val expected6 = mutableListOf(up('a', 'b'))
        check(expected6 == uranges(cls6))
    }

    @kotlin.test.Test
    public fun classCanonicalizeBytes() {
        val cls = bclass(listOf(Pair(97u, 99u), Pair(120u, 122u)))
        val expected = mutableListOf(Pair(97u, 99u), Pair(120u, 122u))
        check(expected == branges(cls))
        val cls1 = bclass(listOf(Pair(120u, 122u), Pair(97u, 99u)))
        val expected1 = mutableListOf(Pair(97u, 99u), Pair(120u, 122u))
        check(expected1 == branges(cls1))
        val cls2 = bclass(listOf(Pair(120u, 122u), Pair(119u, 121u)))
        val expected2 = mutableListOf(Pair(119u, 122u))
        check(expected2 == branges(cls2))
        val cls3 = bclass(listOf(Pair(99u, 102u), Pair(97u, 103u), Pair(100u, 106u), Pair(97u, 99u), Pair(109u, 112u), Pair(108u, 115u)))
        val expected3 = mutableListOf(Pair(97u, 106u), Pair(108u, 115u))
        check(expected3 == branges(cls3))
        val cls4 = bclass(listOf(Pair(120u, 122u), Pair(117u, 119u)))
        val expected4 = mutableListOf(Pair(117u, 122u))
        check(expected4 == branges(cls4))
        val cls5 = bclass(listOf(Pair(0u, 255u), Pair(0u, 255u)))
        val expected5 = mutableListOf(Pair(0u, 255u))
        check(expected5 == branges(cls5))
        val cls6 = bclass(listOf(Pair(97u, 97u), Pair(98u, 98u)))
        val expected6 = mutableListOf(Pair(97u, 98u))
        check(expected6 == branges(cls6))
    }

    @kotlin.test.Test
    public fun classCaseFoldUnicode() {
        val cls = uclass(listOf(up('C', 'F'), up('A', 'G'), up('D', 'J'), up('A', 'C'), up('M', 'P'), up('L', 'S'), up('c', 'f')))
        val expected = uclass(listOf(up('A', 'J'), up('L', 'S'), up('a', 'j'), up('l', 's'), up('\u017f', '\u017f')))
        check(expected == ucasefold(cls))
        val cls1 = uclass(listOf(up('A', 'Z')))
        val expected1 = uclass(listOf(up('A', 'Z'), up('a', 'z'), up('\u017f', '\u017f'), up('\u212a', '\u212a')))
        check(expected1 == ucasefold(cls1))
        val cls2 = uclass(listOf(up('a', 'z')))
        val expected2 = uclass(listOf(up('A', 'Z'), up('a', 'z'), up('\u017f', '\u017f'), up('\u212a', '\u212a')))
        check(expected2 == ucasefold(cls2))
        val cls3 = uclass(listOf(up('A', 'A'), up('_', '_')))
        val expected3 = uclass(listOf(up('A', 'A'), up('_', '_'), up('a', 'a')))
        check(expected3 == ucasefold(cls3))
        val cls4 = uclass(listOf(up('A', 'A'), up('=', '=')))
        val expected4 = uclass(listOf(up('=', '='), up('A', 'A'), up('a', 'a')))
        check(expected4 == ucasefold(cls4))
        val cls5 = uclass(listOf(up('\u0000', '\u0010')))
        check(cls5 == ucasefold(cls5))
        val cls6 = uclass(listOf(up('k', 'k')))
        val expected5 = uclass(listOf(up('K', 'K'), up('k', 'k'), up('\u212a', '\u212a')))
        check(expected5 == ucasefold(cls6))
        val cls7 = uclass(listOf(up('@', '@')))
        check(cls7 == ucasefold(cls7))
    }

    @kotlin.test.Test
    public fun classCaseFoldBytes() {
        val cls = bclass(listOf(Pair(67u, 70u), Pair(65u, 71u), Pair(68u, 74u), Pair(65u, 67u), Pair(77u, 80u), Pair(76u, 83u), Pair(99u, 102u)))
        val expected = bclass(listOf(Pair(65u, 74u), Pair(76u, 83u), Pair(97u, 106u), Pair(108u, 115u)))
        check(expected == bcasefold(cls))
        val cls1 = bclass(listOf(Pair(65u, 90u)))
        val expected1 = bclass(listOf(Pair(65u, 90u), Pair(97u, 122u)))
        check(expected1 == bcasefold(cls1))
        val cls2 = bclass(listOf(Pair(97u, 122u)))
        val expected2 = bclass(listOf(Pair(65u, 90u), Pair(97u, 122u)))
        check(expected2 == bcasefold(cls2))
        val cls3 = bclass(listOf(Pair(65u, 65u), Pair(95u, 95u)))
        val expected3 = bclass(listOf(Pair(65u, 65u), Pair(95u, 95u), Pair(97u, 97u)))
        check(expected3 == bcasefold(cls3))
        val cls4 = bclass(listOf(Pair(65u, 65u), Pair(61u, 61u)))
        val expected4 = bclass(listOf(Pair(61u, 61u), Pair(65u, 65u), Pair(97u, 97u)))
        check(expected4 == bcasefold(cls4))
        val cls5 = bclass(listOf(Pair(0u, 16u)))
        check(cls5 == bcasefold(cls5))
        val cls6 = bclass(listOf(Pair(107u, 107u)))
        val expected5 = bclass(listOf(Pair(75u, 75u), Pair(107u, 107u)))
        check(expected5 == bcasefold(cls6))
        val cls7 = bclass(listOf(Pair(64u, 64u)))
        check(cls7 == bcasefold(cls7))
    }

    @kotlin.test.Test
    public fun classNegateUnicode() {
        val cls = uclass(listOf(up('a', 'a')))
        val expected = uclass(listOf(up('\u0000', '`'), up('b', 0x10ffffu)))
        check(expected == unegate(cls))
        val cls1 = uclass(listOf(up('a', 'a'), up('b', 'b')))
        val expected1 = uclass(listOf(up('\u0000', '`'), up('c', 0x10ffffu)))
        check(expected1 == unegate(cls1))
        val cls2 = uclass(listOf(up('a', 'c'), up('x', 'z')))
        val expected2 = uclass(listOf(up('\u0000', '`'), up('d', 'w'), up('{', 0x10ffffu)))
        check(expected2 == unegate(cls2))
        val cls3 = uclass(listOf(up('\u0000', 'a')))
        val expected3 = uclass(listOf(up('b', 0x10ffffu)))
        check(expected3 == unegate(cls3))
        val cls4 = uclass(listOf(up('a', 0x10ffffu)))
        val expected4 = uclass(listOf(up('\u0000', '`')))
        check(expected4 == unegate(cls4))
        val cls5 = uclass(listOf(up('\u0000', 0x10ffffu)))
        val expected5 = uclass(listOf())
        check(expected5 == unegate(cls5))
        val cls6 = uclass(listOf())
        val expected6 = uclass(listOf(up('\u0000', 0x10ffffu)))
        check(expected6 == unegate(cls6))
        val cls7 = uclass(listOf(up('\u0000', 0x10fffdu), up(0x10ffffu, 0x10ffffu)))
        val expected7 = uclass(listOf(up(0x10fffeu, 0x10fffeu)))
        check(expected7 == unegate(cls7))
        val cls8 = uclass(listOf(up('\u0000', '\ud7ff')))
        val expected8 = uclass(listOf(up('\ue000', 0x10ffffu)))
        check(expected8 == unegate(cls8))
        val cls9 = uclass(listOf(up('\u0000', '\ud7fe')))
        val expected9 = uclass(listOf(up('\ud7ff', 0x10ffffu)))
        check(expected9 == unegate(cls9))
        val cls10 = uclass(listOf(up('\ue000', 0x10ffffu)))
        val expected10 = uclass(listOf(up('\u0000', '\ud7ff')))
        check(expected10 == unegate(cls10))
        val cls11 = uclass(listOf(up('\ue001', 0x10ffffu)))
        val expected11 = uclass(listOf(up('\u0000', '\ue000')))
        check(expected11 == unegate(cls11))
    }

    @kotlin.test.Test
    public fun classNegateBytes() {
        val cls = bclass(listOf(Pair(97u, 97u)))
        val expected = bclass(listOf(Pair(0u, 96u), Pair(98u, 255u)))
        check(expected == bnegate(cls))
        val cls1 = bclass(listOf(Pair(97u, 97u), Pair(98u, 98u)))
        val expected1 = bclass(listOf(Pair(0u, 96u), Pair(99u, 255u)))
        check(expected1 == bnegate(cls1))
        val cls2 = bclass(listOf(Pair(97u, 99u), Pair(120u, 122u)))
        val expected2 = bclass(listOf(Pair(0u, 96u), Pair(100u, 119u), Pair(123u, 255u)))
        check(expected2 == bnegate(cls2))
        val cls3 = bclass(listOf(Pair(0u, 97u)))
        val expected3 = bclass(listOf(Pair(98u, 255u)))
        check(expected3 == bnegate(cls3))
        val cls4 = bclass(listOf(Pair(97u, 255u)))
        val expected4 = bclass(listOf(Pair(0u, 96u)))
        check(expected4 == bnegate(cls4))
        val cls5 = bclass(listOf(Pair(0u, 255u)))
        val expected5 = bclass(listOf())
        check(expected5 == bnegate(cls5))
        val cls6 = bclass(listOf())
        val expected6 = bclass(listOf(Pair(0u, 255u)))
        check(expected6 == bnegate(cls6))
        val cls7 = bclass(listOf(Pair(0u, 253u), Pair(255u, 255u)))
        val expected7 = bclass(listOf(Pair(254u, 254u)))
        check(expected7 == bnegate(cls7))
    }

    @kotlin.test.Test
    public fun classUnionUnicode() {
        val cls1 = uclass(listOf(up('a', 'g'), up('m', 't'), up('A', 'C')))
        val cls2 = uclass(listOf(up('a', 'z')))
        val expected = uclass(listOf(up('a', 'z'), up('A', 'C')))
        check(expected == uunion(cls1, cls2))
    }

    @kotlin.test.Test
    public fun classUnionBytes() {
        val cls1 = bclass(listOf(Pair(97u, 103u), Pair(109u, 116u), Pair(65u, 67u)))
        val cls2 = bclass(listOf(Pair(97u, 122u)))
        val expected = bclass(listOf(Pair(97u, 122u), Pair(65u, 67u)))
        check(expected == bunion(cls1, cls2))
    }

    @kotlin.test.Test
    public fun classIntersectUnicode() {
        val cls1 = uclass(listOf())
        val cls2 = uclass(listOf(up('a', 'a')))
        val expected = uclass(listOf())
        check(expected == uintersect(cls1, cls2))
        val cls11 = uclass(listOf(up('a', 'a')))
        val cls21 = uclass(listOf(up('a', 'a')))
        val expected1 = uclass(listOf(up('a', 'a')))
        check(expected1 == uintersect(cls11, cls21))
        val cls12 = uclass(listOf(up('a', 'a')))
        val cls22 = uclass(listOf(up('b', 'b')))
        val expected2 = uclass(listOf())
        check(expected2 == uintersect(cls12, cls22))
        val cls13 = uclass(listOf(up('a', 'a')))
        val cls23 = uclass(listOf(up('a', 'c')))
        val expected3 = uclass(listOf(up('a', 'a')))
        check(expected3 == uintersect(cls13, cls23))
        val cls14 = uclass(listOf(up('a', 'b')))
        val cls24 = uclass(listOf(up('a', 'c')))
        val expected4 = uclass(listOf(up('a', 'b')))
        check(expected4 == uintersect(cls14, cls24))
        val cls15 = uclass(listOf(up('a', 'b')))
        val cls25 = uclass(listOf(up('b', 'c')))
        val expected5 = uclass(listOf(up('b', 'b')))
        check(expected5 == uintersect(cls15, cls25))
        val cls16 = uclass(listOf(up('a', 'b')))
        val cls26 = uclass(listOf(up('c', 'd')))
        val expected6 = uclass(listOf())
        check(expected6 == uintersect(cls16, cls26))
        val cls17 = uclass(listOf(up('b', 'c')))
        val cls27 = uclass(listOf(up('a', 'd')))
        val expected7 = uclass(listOf(up('b', 'c')))
        check(expected7 == uintersect(cls17, cls27))
        val cls18 = uclass(listOf(up('a', 'b'), up('d', 'e'), up('g', 'h')))
        val cls28 = uclass(listOf(up('a', 'h')))
        val expected8 = uclass(listOf(up('a', 'b'), up('d', 'e'), up('g', 'h')))
        check(expected8 == uintersect(cls18, cls28))
        val cls19 = uclass(listOf(up('a', 'b'), up('d', 'e'), up('g', 'h')))
        val cls29 = uclass(listOf(up('a', 'b'), up('d', 'e'), up('g', 'h')))
        val expected9 = uclass(listOf(up('a', 'b'), up('d', 'e'), up('g', 'h')))
        check(expected9 == uintersect(cls19, cls29))
        val cls110 = uclass(listOf(up('a', 'b'), up('g', 'h')))
        val cls210 = uclass(listOf(up('d', 'e'), up('k', 'l')))
        val expected10 = uclass(listOf())
        check(expected10 == uintersect(cls110, cls210))
        val cls111 = uclass(listOf(up('a', 'b'), up('d', 'e'), up('g', 'h')))
        val cls211 = uclass(listOf(up('h', 'h')))
        val expected11 = uclass(listOf(up('h', 'h')))
        check(expected11 == uintersect(cls111, cls211))
        val cls112 = uclass(listOf(up('a', 'b'), up('e', 'f'), up('i', 'j')))
        val cls212 = uclass(listOf(up('c', 'd'), up('g', 'h'), up('k', 'l')))
        val expected12 = uclass(listOf())
        check(expected12 == uintersect(cls112, cls212))
        val cls113 = uclass(listOf(up('a', 'b'), up('c', 'd'), up('e', 'f')))
        val cls213 = uclass(listOf(up('b', 'c'), up('d', 'e'), up('f', 'g')))
        val expected13 = uclass(listOf(up('b', 'f')))
        check(expected13 == uintersect(cls113, cls213))
    }

    @kotlin.test.Test
    public fun classIntersectBytes() {
        val cls1 = bclass(listOf())
        val cls2 = bclass(listOf(Pair(97u, 97u)))
        val expected = bclass(listOf())
        check(expected == bintersect(cls1, cls2))
        val cls11 = bclass(listOf(Pair(97u, 97u)))
        val cls21 = bclass(listOf(Pair(97u, 97u)))
        val expected1 = bclass(listOf(Pair(97u, 97u)))
        check(expected1 == bintersect(cls11, cls21))
        val cls12 = bclass(listOf(Pair(97u, 97u)))
        val cls22 = bclass(listOf(Pair(98u, 98u)))
        val expected2 = bclass(listOf())
        check(expected2 == bintersect(cls12, cls22))
        val cls13 = bclass(listOf(Pair(97u, 97u)))
        val cls23 = bclass(listOf(Pair(97u, 99u)))
        val expected3 = bclass(listOf(Pair(97u, 97u)))
        check(expected3 == bintersect(cls13, cls23))
        val cls14 = bclass(listOf(Pair(97u, 98u)))
        val cls24 = bclass(listOf(Pair(97u, 99u)))
        val expected4 = bclass(listOf(Pair(97u, 98u)))
        check(expected4 == bintersect(cls14, cls24))
        val cls15 = bclass(listOf(Pair(97u, 98u)))
        val cls25 = bclass(listOf(Pair(98u, 99u)))
        val expected5 = bclass(listOf(Pair(98u, 98u)))
        check(expected5 == bintersect(cls15, cls25))
        val cls16 = bclass(listOf(Pair(97u, 98u)))
        val cls26 = bclass(listOf(Pair(99u, 100u)))
        val expected6 = bclass(listOf())
        check(expected6 == bintersect(cls16, cls26))
        val cls17 = bclass(listOf(Pair(98u, 99u)))
        val cls27 = bclass(listOf(Pair(97u, 100u)))
        val expected7 = bclass(listOf(Pair(98u, 99u)))
        check(expected7 == bintersect(cls17, cls27))
        val cls18 = bclass(listOf(Pair(97u, 98u), Pair(100u, 101u), Pair(103u, 104u)))
        val cls28 = bclass(listOf(Pair(97u, 104u)))
        val expected8 = bclass(listOf(Pair(97u, 98u), Pair(100u, 101u), Pair(103u, 104u)))
        check(expected8 == bintersect(cls18, cls28))
        val cls19 = bclass(listOf(Pair(97u, 98u), Pair(100u, 101u), Pair(103u, 104u)))
        val cls29 = bclass(listOf(Pair(97u, 98u), Pair(100u, 101u), Pair(103u, 104u)))
        val expected9 = bclass(listOf(Pair(97u, 98u), Pair(100u, 101u), Pair(103u, 104u)))
        check(expected9 == bintersect(cls19, cls29))
        val cls110 = bclass(listOf(Pair(97u, 98u), Pair(103u, 104u)))
        val cls210 = bclass(listOf(Pair(100u, 101u), Pair(107u, 108u)))
        val expected10 = bclass(listOf())
        check(expected10 == bintersect(cls110, cls210))
        val cls111 = bclass(listOf(Pair(97u, 98u), Pair(100u, 101u), Pair(103u, 104u)))
        val cls211 = bclass(listOf(Pair(104u, 104u)))
        val expected11 = bclass(listOf(Pair(104u, 104u)))
        check(expected11 == bintersect(cls111, cls211))
        val cls112 = bclass(listOf(Pair(97u, 98u), Pair(101u, 102u), Pair(105u, 106u)))
        val cls212 = bclass(listOf(Pair(99u, 100u), Pair(103u, 104u), Pair(107u, 108u)))
        val expected12 = bclass(listOf())
        check(expected12 == bintersect(cls112, cls212))
        val cls113 = bclass(listOf(Pair(97u, 98u), Pair(99u, 100u), Pair(101u, 102u)))
        val cls213 = bclass(listOf(Pair(98u, 99u), Pair(100u, 101u), Pair(102u, 103u)))
        val expected13 = bclass(listOf(Pair(98u, 102u)))
        check(expected13 == bintersect(cls113, cls213))
    }

    @kotlin.test.Test
    public fun classDifferenceUnicode() {
        val cls1 = uclass(listOf(up('a', 'a')))
        val cls2 = uclass(listOf(up('a', 'a')))
        val expected = uclass(listOf())
        check(expected == udifference(cls1, cls2))
        val cls11 = uclass(listOf(up('a', 'a')))
        val cls21 = uclass(listOf())
        val expected1 = uclass(listOf(up('a', 'a')))
        check(expected1 == udifference(cls11, cls21))
        val cls12 = uclass(listOf())
        val cls22 = uclass(listOf(up('a', 'a')))
        val expected2 = uclass(listOf())
        check(expected2 == udifference(cls12, cls22))
        val cls13 = uclass(listOf(up('a', 'z')))
        val cls23 = uclass(listOf(up('a', 'a')))
        val expected3 = uclass(listOf(up('b', 'z')))
        check(expected3 == udifference(cls13, cls23))
        val cls14 = uclass(listOf(up('a', 'z')))
        val cls24 = uclass(listOf(up('z', 'z')))
        val expected4 = uclass(listOf(up('a', 'y')))
        check(expected4 == udifference(cls14, cls24))
        val cls15 = uclass(listOf(up('a', 'z')))
        val cls25 = uclass(listOf(up('m', 'm')))
        val expected5 = uclass(listOf(up('a', 'l'), up('n', 'z')))
        check(expected5 == udifference(cls15, cls25))
        val cls16 = uclass(listOf(up('a', 'c'), up('g', 'i'), up('r', 't')))
        val cls26 = uclass(listOf(up('a', 'z')))
        val expected6 = uclass(listOf())
        check(expected6 == udifference(cls16, cls26))
        val cls17 = uclass(listOf(up('a', 'c'), up('g', 'i'), up('r', 't')))
        val cls27 = uclass(listOf(up('d', 'v')))
        val expected7 = uclass(listOf(up('a', 'c')))
        check(expected7 == udifference(cls17, cls27))
        val cls18 = uclass(listOf(up('a', 'c'), up('g', 'i'), up('r', 't')))
        val cls28 = uclass(listOf(up('b', 'g'), up('s', 'u')))
        val expected8 = uclass(listOf(up('a', 'a'), up('h', 'i'), up('r', 'r')))
        check(expected8 == udifference(cls18, cls28))
        val cls19 = uclass(listOf(up('a', 'c'), up('g', 'i'), up('r', 't')))
        val cls29 = uclass(listOf(up('b', 'd'), up('e', 'g'), up('s', 'u')))
        val expected9 = uclass(listOf(up('a', 'a'), up('h', 'i'), up('r', 'r')))
        check(expected9 == udifference(cls19, cls29))
        val cls110 = uclass(listOf(up('x', 'z')))
        val cls210 = uclass(listOf(up('a', 'c'), up('e', 'g'), up('s', 'u')))
        val expected10 = uclass(listOf(up('x', 'z')))
        check(expected10 == udifference(cls110, cls210))
        val cls111 = uclass(listOf(up('a', 'z')))
        val cls211 = uclass(listOf(up('a', 'c'), up('e', 'g'), up('s', 'u')))
        val expected11 = uclass(listOf(up('d', 'd'), up('h', 'r'), up('v', 'z')))
        check(expected11 == udifference(cls111, cls211))
    }

    @kotlin.test.Test
    public fun classDifferenceBytes() {
        val cls1 = bclass(listOf(Pair(97u, 97u)))
        val cls2 = bclass(listOf(Pair(97u, 97u)))
        val expected = bclass(listOf())
        check(expected == bdifference(cls1, cls2))
        val cls11 = bclass(listOf(Pair(97u, 97u)))
        val cls21 = bclass(listOf())
        val expected1 = bclass(listOf(Pair(97u, 97u)))
        check(expected1 == bdifference(cls11, cls21))
        val cls12 = bclass(listOf())
        val cls22 = bclass(listOf(Pair(97u, 97u)))
        val expected2 = bclass(listOf())
        check(expected2 == bdifference(cls12, cls22))
        val cls13 = bclass(listOf(Pair(97u, 122u)))
        val cls23 = bclass(listOf(Pair(97u, 97u)))
        val expected3 = bclass(listOf(Pair(98u, 122u)))
        check(expected3 == bdifference(cls13, cls23))
        val cls14 = bclass(listOf(Pair(97u, 122u)))
        val cls24 = bclass(listOf(Pair(122u, 122u)))
        val expected4 = bclass(listOf(Pair(97u, 121u)))
        check(expected4 == bdifference(cls14, cls24))
        val cls15 = bclass(listOf(Pair(97u, 122u)))
        val cls25 = bclass(listOf(Pair(109u, 109u)))
        val expected5 = bclass(listOf(Pair(97u, 108u), Pair(110u, 122u)))
        check(expected5 == bdifference(cls15, cls25))
        val cls16 = bclass(listOf(Pair(97u, 99u), Pair(103u, 105u), Pair(114u, 116u)))
        val cls26 = bclass(listOf(Pair(97u, 122u)))
        val expected6 = bclass(listOf())
        check(expected6 == bdifference(cls16, cls26))
        val cls17 = bclass(listOf(Pair(97u, 99u), Pair(103u, 105u), Pair(114u, 116u)))
        val cls27 = bclass(listOf(Pair(100u, 118u)))
        val expected7 = bclass(listOf(Pair(97u, 99u)))
        check(expected7 == bdifference(cls17, cls27))
        val cls18 = bclass(listOf(Pair(97u, 99u), Pair(103u, 105u), Pair(114u, 116u)))
        val cls28 = bclass(listOf(Pair(98u, 103u), Pair(115u, 117u)))
        val expected8 = bclass(listOf(Pair(97u, 97u), Pair(104u, 105u), Pair(114u, 114u)))
        check(expected8 == bdifference(cls18, cls28))
        val cls19 = bclass(listOf(Pair(97u, 99u), Pair(103u, 105u), Pair(114u, 116u)))
        val cls29 = bclass(listOf(Pair(98u, 100u), Pair(101u, 103u), Pair(115u, 117u)))
        val expected9 = bclass(listOf(Pair(97u, 97u), Pair(104u, 105u), Pair(114u, 114u)))
        check(expected9 == bdifference(cls19, cls29))
        val cls110 = bclass(listOf(Pair(120u, 122u)))
        val cls210 = bclass(listOf(Pair(97u, 99u), Pair(101u, 103u), Pair(115u, 117u)))
        val expected10 = bclass(listOf(Pair(120u, 122u)))
        check(expected10 == bdifference(cls110, cls210))
        val cls111 = bclass(listOf(Pair(97u, 122u)))
        val cls211 = bclass(listOf(Pair(97u, 99u), Pair(101u, 103u), Pair(115u, 117u)))
        val expected11 = bclass(listOf(Pair(100u, 100u), Pair(104u, 114u), Pair(118u, 122u)))
        check(expected11 == bdifference(cls111, cls211))
    }

    @kotlin.test.Test
    public fun classSymmetricDifferenceUnicode() {
        val cls1 = uclass(listOf(up('a', 'm')))
        val cls2 = uclass(listOf(up('g', 't')))
        val expected = uclass(listOf(up('a', 'f'), up('n', 't')))
        check(expected == usymdifference(cls1, cls2))
    }

    @kotlin.test.Test
    public fun classSymmetricDifferenceBytes() {
        val cls1 = bclass(listOf(Pair(97u, 109u)))
        val cls2 = bclass(listOf(Pair(103u, 116u)))
        val expected = bclass(listOf(Pair(97u, 102u), Pair(110u, 116u)))
        check(expected == bsymdifference(cls1, cls2))
    }

    // KMP commonTest has no portable 16 KiB OS thread spawner; verify iterative Hir.drop() handles deep nesting directly.
    @kotlin.test.Test
    public fun noStackOverflowOnDrop() {
        var expr = Hir.empty()
        for (unused in 0 until 100) {
            expr = Hir.capture(
                Capture(
                    index = 1u,
                    name = null,
                    sub = expr,
                ),
            )
            expr = Hir.repetition(
                Repetition(
                    min = 0u,
                    max = 1u,
                    greedy = true,
                    sub = expr,
                ),
            )
            expr = Hir(
                kind = HirKind.Concat(listOf(expr)),
                props = Properties.empty(),
            )
            expr = Hir(
                kind = HirKind.Alternation(listOf(expr)),
                props = Properties.empty(),
            )
        }
        check(expr.kind() !is HirKind.Empty)
        expr.drop()
    }

    @kotlin.test.Test
    public fun lookSetIter() {
        val set = LookSet.empty()
        check(0 == set.iter().count())
        val set1 = LookSet.full()
        check(18 == set1.iter().count())
        val set2 = LookSet.empty().insert(Look.StartLF).insert(Look.WordUnicode)
        check(2 == set2.iter().count())
        val set3 = LookSet.empty().insert(Look.StartLF)
        check(1 == set3.iter().count())
        val set4 = LookSet.empty().insert(Look.WordAsciiNegate)
        check(1 == set4.iter().count())
    }

    @kotlin.test.Test
    public fun lookSetDebug() {
        val res = "${LookSet.empty()}"
        check("∅" == res)
        val res1 = "${LookSet.full()}"
        check("Az^\$rRbB𝛃𝚩<>〈〉◁▷◀▶" == res1)
    }
}
