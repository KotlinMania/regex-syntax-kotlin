package io.github.kotlinmania.regexsyntax.hir

import io.github.kotlinmania.regexsyntax.debug.utf8Decode

/**
 * Given a sequence of HIR values where each value corresponds to a Unicode
 * class (or an all-ASCII byte class), return a single Unicode class
 * corresponding to the union of the classes found.
 */
public fun classChars(hirs: List<Hir>): Class? {
    val cls = ClassUnicode.new(mutableListOf())
    for (hir in hirs.iter()) {
        run {
            val matchVal = hir.kind()
            when {
                matchVal is HirKind.Class && matchVal.value is Class.Unicode -> {
                    val (cls2) = matchVal.value as Class.Unicode
                    cls.union(cls2)
                }
                matchVal is HirKind.Class && matchVal.value is Class.Bytes -> {
                    val (cls21) = matchVal.value as Class.Bytes
                    cls.union((cls21.toUnicodeClass() ?: return null))
                }
                else -> { return null }
            }
        }
    }
    return Class.Unicode(cls)
}

/**
 * Given a sequence of HIR values where each value corresponds to a byte class
 * (or an all-ASCII Unicode class), return a single byte class corresponding
 * to the union of the classes found.
 */
public fun classBytes(hirs: List<Hir>): Class? {
    val cls = ClassBytes.new(mutableListOf())
    for (hir in hirs.iter()) {
        run {
            val matchVal = hir.kind()
            when {
                matchVal is HirKind.Class && matchVal.value is Class.Unicode -> {
                    val (cls2) = matchVal.value as Class.Unicode
                    cls.union((cls2.toByteClass() ?: return null))
                }
                matchVal is HirKind.Class && matchVal.value is Class.Bytes -> {
                    val (cls21) = matchVal.value as Class.Bytes
                    cls.union(cls21)
                }
                else -> { return null }
            }
        }
    }
    return Class.Bytes(cls)
}

/**
 * Given a sequence of HIR values where each value corresponds to a literal
 * that is a single `char`, return that sequence of `char`s. Otherwise return
 * None. No deduplication is done.
 */
public fun singletonChars(hirs: List<Hir>): List<Char>? {
    val singletons = mutableListOf()
    for (hir in hirs.iter()) {
        val literal =
            run {
                val matchVal = hir.kind()
                when {
                    matchVal is HirKind.Literal && matchVal.value is Literal -> {
                        val (bytes) = matchVal.value as Literal
                        bytes
                    }
                    else -> { return null }
                }
            }
        val ch1 =
            when (val matchVal1 = utf8Decode(literal)) {
                null -> { return null }
                is Err -> { return null }
                is Ok -> {
                    val (ch) = matchVal1 as Ok
                    ch
                }
            }
        if (literal.len() != ch1.lenUtf8()) {
            return null
        }
        singletons.add(ch1)
    }
    return singletons
}

/**
 * Given a sequence of HIR values where each value corresponds to a literal
 * that is a single byte, return that sequence of bytes. Otherwise return
 * None. No deduplication is done.
 */
public fun singletonBytes(hirs: List<Hir>): List<UByte>? {
    val singletons = mutableListOf()
    for (hir in hirs.iter()) {
        val literal =
            run {
                val matchVal = hir.kind()
                when {
                    matchVal is HirKind.Literal && matchVal.value is Literal -> {
                        val (bytes) = matchVal.value as Literal
                        bytes
                    }
                    else -> { return null }
                }
            }
        if (literal.len() != 1) {
            return null
        }
        singletons.add(literal[0])
    }
    return singletons
}

/**
 * Looks for a common prefix in the list of alternation branches given. If one
 * is found, then an equivalent but (hopefully) simplified Hir is returned.
 * Otherwise, the original given list of branches is returned unmodified.
 *
 * This is not quite as good as it could be. Right now, it requires that
 * all branches are 'Concat' expressions. It also doesn't do well with
 * literals. For example, given foofoo|foobar', it will not refactor it to
 * foo(?:foo|bar)' because literals are flattened into their own special
 * concatenation. (One wonders if perhaps 'Literal' should be a single atom
 * instead of a string of bytes because of this. Otherwise, handling the
 * current representation in this routine will be pretty gnarly. Sigh.)
 */
public fun liftCommonPrefix(hirs: List<Hir>): Result<Hir> {
    if (hirs.size <= 1) {
        return Result.failure(hirs)
    }
    var prefix =
        when (val matchVal = hirs[0].kind()) {
            is HirKind.Concat -> {
                val (xs) = matchVal
                xs
            }
            else -> { return Result.failure(hirs) }
        }
    if (prefix.isEmpty()) {
        return Result.failure(hirs)
    }
    for (h in hirs.iter().skip(1)) {
        val concat =
            when (val matchVal1 = h.kind()) {
                is HirKind.Concat -> {
                    val (xs1) = matchVal1
                    xs1
                }
                else -> { return Result.failure(hirs) }
            }
        val commonLen = (prefix.iter().zip(concat.iter())).takeWhile({ (x, y) -> x == y }).count()
        prefix = prefix[0 until commonLen]
        if (prefix.isEmpty()) {
            return Result.failure(hirs)
        }
    }
    val len = prefix.len()
    check(0 != len)
    var prefixConcat = mutableListOf()
    val suffixAlts = mutableListOf()
    for (h1 in hirs) {
        var concat1 =
            when (val matchVal2 = h1.intoKind()) {
                is HirKind.Concat -> {
                    val (xs2) = matchVal2
                    xs2
                }
                // We required all sub-expressions to be
                // concats above, so we're only here if we
                // have a concat.
                else -> error("unreachable")
            }
        suffixAlts.add(Hir.concat(concat1.splitOff(len)))
        if (prefixConcat.isEmpty()) {
            prefixConcat = concat1
        }
    }
    val concat2 = prefixConcat
    concat2.push(Hir.alternation(suffixAlts))
    return Result.success(Hir.concat(concat2))
}
