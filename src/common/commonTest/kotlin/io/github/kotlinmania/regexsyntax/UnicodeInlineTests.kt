// port-lint: tests unicode.rs
package io.github.kotlinmania.regexsyntax.unicode

public fun simpleFoldOk(c: Char): Any = SimpleCaseFolder.new().unwrap().mapping(c).iter().copied()

public fun containsCaseMap(start: Char, end: Char): Boolean = SimpleCaseFolder.new().unwrap().overlaps(start, end)

@kotlin.test.Test
public fun simpleFoldK() {
    val xs: List<Char> = simpleFoldOk('k').collect()
    check(xs == mutableListOf('K', '\u212a'))
    val xs1: List<Char> = simpleFoldOk('K').collect()
    check(xs1 == mutableListOf('k', '\u212a'))
    val xs2: List<Char> = simpleFoldOk('\u212a').collect()
    check(xs2 == mutableListOf('K', 'k'))
}

@kotlin.test.Test
public fun simpleFoldA() {
    val xs: List<Char> = simpleFoldOk('a').collect()
    check(xs == mutableListOf('A'))
    val xs1: List<Char> = simpleFoldOk('A').collect()
    check(xs1 == mutableListOf('a'))
}

@kotlin.test.Test
public fun rangeContains() {
    check(containsCaseMap('A', 'A'))
    check(containsCaseMap('Z', 'Z'))
    check(containsCaseMap('A', 'Z'))
    check(containsCaseMap('@', 'A'))
    check(containsCaseMap('Z', '['))
    check(containsCaseMap('\u2603', '\u2c00'))
    check(!containsCaseMap('[', '['))
    check(!containsCaseMap('[', '`'))
    check(!containsCaseMap('\u2603', '\u2603'))
}

@kotlin.test.Test
public fun regression466() {
    val q = ClassQuery.OneLetter('C')
    check(q.canonicalize().unwrap() == CanonicalClassQuery.GeneralCategory("Other"))
}

@kotlin.test.Test
public fun symNormalize() {
    val symNorm = ::symbolicNameNormalize
    check(symNorm("Line_Break") == "linebreak")
    check(symNorm("Line-break") == "linebreak")
    check(symNorm("linebreak") == "linebreak")
    check(symNorm("BA") == "ba")
    check(symNorm("ba") == "ba")
    check(symNorm("Greek") == "greek")
    check(symNorm("isGreek") == "greek")
    check(symNorm("IS_Greek") == "greek")
    check(symNorm("isc") == "isc")
    check(symNorm("is c") == "isc")
    check(symNorm("is_c") == "isc")
}

@kotlin.test.Test
public fun validUtf8Symbolic() {
    val x = "abcÿxyz".encodeToByteArray().toVec()
    val y = symbolicNameNormalizeBytes(x)
    check(y == "abcxyz".encodeToByteArray())
}
