package io.github.kotlinmania.regexsyntax.hir

import io.github.kotlinmania.regexsyntax.debug.utf8Decode
import io.github.kotlinmania.regexsyntax.scalarLenUtf8
import io.github.kotlinmania.rustlang.core.result.Result

/**
 * Given a sequence of HIR values where each value corresponds to a Unicode
 * class (or an all-ASCII byte class), return a single Unicode class
 * corresponding to the union of the classes found.
 */
internal fun classChars(hirs: List<Hir>): Class? {
    val cls = ClassUnicode.new(emptyList())
    for (hir in hirs) {
        when (val kind = hir.kind()) {
            is HirKind.Class -> {
                when (val inner = kind.value) {
                    is Class.Unicode -> cls.union(inner.value)
                    is Class.Bytes -> cls.union(inner.value.toUnicodeClass() ?: return null)
                }
            }
            else -> return null
        }
    }
    return Class.Unicode(cls)
}

/**
 * Given a sequence of HIR values where each value corresponds to a byte class
 * (or an all-ASCII Unicode class), return a single byte class corresponding
 * to the union of the classes found.
 */
internal fun classBytes(hirs: List<Hir>): Class? {
    val cls = ClassBytes.new(emptyList())
    for (hir in hirs) {
        when (val kind = hir.kind()) {
            is HirKind.Class -> {
                when (val inner = kind.value) {
                    is Class.Unicode -> cls.union(inner.value.toByteClass() ?: return null)
                    is Class.Bytes -> cls.union(inner.value)
                }
            }
            else -> return null
        }
    }
    return Class.Bytes(cls)
}

/**
 * Given a sequence of HIR values where each value corresponds to a literal
 * that is a single Unicode scalar, return that sequence of scalars. Otherwise return
 * null. No deduplication is done.
 */
internal fun singletonChars(hirs: List<Hir>): List<UInt>? {
    val singletons = mutableListOf<UInt>()
    for (hir in hirs) {
        val literal =
            when (val kind = hir.kind()) {
                is HirKind.Literal -> kind.value.value
                else -> return null
            }
        val ch =
            when (val decoded = utf8Decode(literal)) {
                null -> return null
                is Result.Err -> return null
                is Result.Ok -> decoded.value
            }
        if (literal.size != scalarLenUtf8(ch)) {
            return null
        }
        singletons.add(ch)
    }
    return singletons
}

/**
 * Given a sequence of HIR values where each value corresponds to a literal
 * that is a single byte, return that sequence of bytes. Otherwise return
 * null. No deduplication is done.
 */
internal fun singletonBytes(hirs: List<Hir>): List<UByte>? {
    val singletons = mutableListOf<UByte>()
    for (hir in hirs) {
        val literal =
            when (val kind = hir.kind()) {
                is HirKind.Literal -> kind.value.value
                else -> return null
            }
        if (literal.size != 1) {
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
 * all branches are `Concat` expressions. It also doesn't do well with
 * literals. For example, given `foofoo|foobar`, it will not refactor it to
 * `foo(?:foo|bar)` because literals are flattened into their own special
 * concatenation. (One wonders if perhaps `Literal` should be a single atom
 * instead of a string of bytes because of this. Otherwise, handling the
 * current representation in this routine will be pretty gnarly. Sigh.)
 */
internal fun liftCommonPrefix(hirs: MutableList<Hir>): Result<Hir, MutableList<Hir>> {
    if (hirs.size <= 1) {
        return Result.Err(hirs)
    }
    var prefix =
        when (val kind = hirs[0].kind()) {
            is HirKind.Concat -> kind.value
            else -> return Result.Err(hirs)
        }
    if (prefix.isEmpty()) {
        return Result.Err(hirs)
    }
    for (i in 1 until hirs.size) {
        val concat =
            when (val kind = hirs[i].kind()) {
                is HirKind.Concat -> kind.value
                else -> return Result.Err(hirs)
            }
        var commonLen = 0
        val limit = minOf(prefix.size, concat.size)
        while (commonLen < limit && prefix[commonLen] == concat[commonLen]) {
            commonLen += 1
        }
        prefix = prefix.subList(0, commonLen)
        if (prefix.isEmpty()) {
            return Result.Err(hirs)
        }
    }
    val len = prefix.size
    check(0 != len)
    var prefixConcat: List<Hir>? = null
    val suffixAlts = mutableListOf<Hir>()
    for (h in hirs) {
        val concat =
            when (val kind = h.intoKind()) {
                is HirKind.Concat -> kind.value
                // We required all sub-expressions to be
                // concats above, so we're only here if we
                // have a concat.
                else -> error("unreachable")
            }
        suffixAlts.add(Hir.concat(concat.subList(len, concat.size).toList()))
        if (prefixConcat == null) {
            prefixConcat = concat.subList(0, len)
        }
    }
    return Result.Ok(Hir.concat(prefixConcat.orEmpty() + Hir.alternation(suffixAlts)))
}
