// port-lint: source unicode.rs
package io.github.kotlinmania.regexsyntax.unicode

import io.github.kotlinmania.regexsyntax.isWordByte
import io.github.kotlinmania.regexsyntax.unicodetables.age.V10_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V11_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V12_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V12_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V13_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V14_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V15_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V15_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V16_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V1_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V2_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V2_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V3_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V3_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V3_2
import io.github.kotlinmania.regexsyntax.unicodetables.age.V4_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V4_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V5_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V5_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V5_2
import io.github.kotlinmania.regexsyntax.unicodetables.age.V6_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V6_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V6_2
import io.github.kotlinmania.regexsyntax.unicodetables.age.V6_3
import io.github.kotlinmania.regexsyntax.unicodetables.age.V7_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V8_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V9_0
import io.github.kotlinmania.regexsyntax.unicodetables.generalcategory.BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.generalcategory.DECIMAL_NUMBER
import io.github.kotlinmania.regexsyntax.unicodetables.perlword.PERL_WORD
import io.github.kotlinmania.regexsyntax.unicodetables.propertybool.WHITE_SPACE
import io.github.kotlinmania.regexsyntax.unicodetables.propertynames.PROPERTY_NAMES
import io.github.kotlinmania.regexsyntax.unicodetables.propertyvalues.PROPERTY_VALUES
import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout

/**
 * An inclusive range of codepoints from a generated file (hence the static
 * scope).
 */
public typealias Range = List<Pair<Char, Char>>

/**
 * An error that occurs when dealing with Unicode.
 *
 * We don't impl the Error trait here because these always get converted
 * into other public errors. (This error type isn't exported.)
 */
public sealed class Error {
    public data object PropertyNotFound : Error()
    public data object PropertyValueNotFound : Error()
    // Not used when unicode-perl is enabled.
    public data object PerlClassNotFound : Error()
}

/**
 * An error that occurs when Unicode-aware simple case folding fails.
 *
 * This error can occur when the case mapping tables necessary for Unicode
 * aware case folding are unavailable. This only occurs when the
 * `unicode - case` feature is disabled. (The feature is enabled by default.)
 */
public open class CaseFoldError(
    public var value: Unit,
) : Error {
    public var field0: Unit
        get() = value
        set(v) {
            value = v
        }

    public open fun fmt(f: StringBuilder) {
        f.append("Unicode-aware case folding is not available (probably because the unicode-case feature is not enabled)")
    }

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<CaseFoldError> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<CaseFoldError> {
                override val alignBytes: Long = 1L
                override val sizeBytes: Long = 0L
                override fun readNative(addr: Long): CaseFoldError =
                    CaseFoldError(
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.UNIT).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: CaseFoldError) {
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.UNIT).writeNative(addr, value.value)
                }
            }
    }
}

/**
 * An error that occurs when the Unicode-aware `\w` class is unavailable.
 *
 * This error can occur when the data tables necessary for the Unicode aware
 * Perl character class `\w` are unavailable. This only occurs when the
 * `unicode - perl` feature is disabled. (The feature is enabled by default.)
 */
public open class UnicodeWordError(
    public var value: Unit,
) : Error {
    public var field0: Unit
        get() = value
        set(v) {
            value = v
        }

    public open fun fmt(f: StringBuilder) {
        f.append("Unicode-aware \\w class is not available (probably because the unicode-perl feature is not enabled)")
    }

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<UnicodeWordError> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<UnicodeWordError> {
                override val alignBytes: Long = 1L
                override val sizeBytes: Long = 0L
                override fun readNative(addr: Long): UnicodeWordError =
                    UnicodeWordError(
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.UNIT).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: UnicodeWordError) {
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.UNIT).writeNative(addr, value.value)
                }
            }
    }
}

/**
 * A state oriented traverser of the simple case folding table.
 *
 * A case folder can be constructed via `SimpleCaseFolder.new()`, which will
 * return an error if the underlying case folding table is unavailable.
 *
 * After construction, it is expected that callers will use
 * `SimpleCaseFolder.mapping` by calling it with codepoints in strictly
 * increasing order. For example, calling it on `b` and then on `a` is illegal
 * and will result in a panic.
 *
 * The main idea of this type is that it tries hard to make mapping lookups
 * fast by exploiting the structure of the underlying table, and the ordering
 * assumption enables this.
 */
public open class SimpleCaseFolder(
    /**
     * The simple case fold table. It's a sorted association list, where the
     * keys are Unicode scalar values and the values are the corresponding
     * equivalence class (not including the key) of the "simple" case folded
     * Unicode scalar values.
     */
    public var table: List<Pair<Char, List<Char>>>,
    /**
     * The last codepoint that was used for a lookup.
     */
    public var last: Char?,
    /**
     * The index to the entry in `table` corresponding to the smallest key `k`
     * such that `k> k0`, where `k0` is the most recent key lookup. Note that
     * in particular, `k0` may not be in the table!
     */
    public var next: Int,
) {
    /**
     * Return the equivalence class of case folded codepoints for the given
     * codepoint. The equivalence class returned never includes the codepoint
     * given. If the given codepoint has no case folded codepoints (i.e.,
     * no entry in the underlying case folding table), then this returns an
     * empty slice.
     *
     * # Panics
     *
     * This panics when called with a `c` that is less than or equal to the
     * previous call. In other words, callers need to use this method with
     * strictly increasing values of `c`.
     */
    public open fun mapping(c: Char): List<Char> {
        when (val matchVal = this.last) {
            null -> {}
            else -> {
                val last = matchVal
                check(last < c) { "got codepoint U+${UInt.from(c)} which occurs before last codepoint U+${UInt.from(last)}" }
            }
        }
        this.last = c
        if (this.next >= this.table.len()) {
            return listOf()
        }
        val (k, v) = this.table[this.next]
        if (k == c) {
            this.next += 1
            return v
        }
        return run {
            val matchVal1 = this.get(c)
            if (matchVal1.isSuccess) {
                val i = matchVal1.getOrThrow()
                // Since we require lookups to proceed
                // in order, anything we find should be
                // after whatever we thought might be
                // next. Otherwise, the caller is either
                // going out of order or we would have
                // found our next key at self.next'.
                check(i > this.next)
                this.next = i + 1
                this.table[i].second
            } else {
                val i1 = matchVal1.exceptionOrNull()!!
                this.next = i1
                listOf()
            }
        }
    }

    /**
     * Returns true if and only if the given range overlaps with any region
     * of the underlying case folding table. That is, when true, there exists
     * at least one codepoint in the inclusive range `[start, end]` that has
     * a non-trivial equivalence class of case folded codepoints. Conversely,
     * when this returns false, all codepoints in the range `[start, end]`
     * correspond to the trivial equivalence class of case folded codepoints,
     * i.e., itself.
     *
     * This is useful to call before iterating over the codepoints in the
     * range and looking up the mapping for each. If you know none of the
     * mappings will return anything, then you might be able to skip doing it
     * altogether.
     *
     * # Panics
     *
     * This panics when `end<start`.
     */
    public open fun overlaps(start: Char, end: Char): Boolean {
        check(start <= end)
        return this.table.binarySearchBy({ (c, _) ->
            if (start <= c && c <= end) {
                Ordering.Equal
            } else if (c > end) {
                Ordering.Greater
            } else {
                Ordering.Less
            }
        }).isOk()
    }

    /**
     * Returns the index at which `c` occurs in the simple case fold table. If
     * `c` does not occur, then this returns an `i` such that `table[i-1].0 <
     * c`and`table[i].0 > c`.
     */
    public open fun get(c: Char): Result<Int> = this.table.binarySearchByKey(c, { (c1, _) -> c1 })

    public companion object {
        /**
         * Create a new simple case folder, returning an error if the underlying
         * case folding table is unavailable.
         */
        public fun new(): Result<SimpleCaseFolder> = Result.failure(CaseFoldError())
    }
}

/**
 * A query for finding a character class defined by Unicode. This supports
 * either use of a property name directly, or lookup by property value. The
 * former generally refers to Binary properties (see UTS#44, Table 8), but
 * as a special exception (see UTS#18, Section 1.2) both general categories
 * (an enumeration) and scripts (a catalog) are supported as if each of their
 * possible values were a binary property.
 *
 * In all circumstances, property names and values are normalized and
 * canonicalized. That is, `GC == gc == GeneralCategory == generalCategory`.
 *
 * The scope `a` refers to the shorter of the scopes of property name
 * and property value.
 */
public sealed class ClassQuery {
    /**
     * Return a class corresponding to a Unicode binary property, named by
     * a single letter.
     */
    public data class OneLetter(public var value: Char) : ClassQuery()
    /**
     * Return a class corresponding to a Unicode binary property.
     *
     * Note that, by special exception (see UTS#18, Section 1.2), both
     * general category values and script values are permitted here as if
     * they were a binary property.
     */
    public data class Binary(public var value: String) : ClassQuery()
    /**
     * Return a class corresponding to all codepoints whose property
     * (identified by `propertyName`) corresponds to the given value
     * (identified by `propertyValue`).
     */
    public data class ByValue(public var propertyName: String, public var propertyValue: String) : ClassQuery()

    public open fun canonicalize(): Result<CanonicalClassQuery> {
        return when (this) {
            is ClassQuery.OneLetter -> {
                val (c) = this
                this.canonicalBinary(c.toString())
            }
            is ClassQuery.Binary -> {
                val (name) = this
                this.canonicalBinary(name)
            }
            is ClassQuery.ByValue -> {
                val propertyName = this.propertyName
                val propertyValue = this.propertyValue
                val propertyName1 = symbolicNameNormalize(propertyName)
                val propertyValue1 = symbolicNameNormalize(propertyValue)
                val canonName1 =
                    when (val matchVal = canonicalProp(propertyName1).getOrElse { return Result.failure(it) }) {
                        null -> { return Result.failure(Error.PropertyNotFound) }
                        else -> {
                            val canonName = matchVal
                            canonName
                        }
                    }
                Result.success(when (canonName1) {
                    "General_Category" -> {
                        val canon1 =
                            when (val matchVal1 = canonicalGencat(propertyValue1).getOrElse { return Result.failure(it) }) {
                                null -> { return Result.failure(Error.PropertyValueNotFound) }
                                else -> {
                                    val canon = matchVal1
                                    canon
                                }
                            }
                        CanonicalClassQuery.GeneralCategory(canon1)
                    }
                    "Script" -> {
                        val canon3 =
                            when (val matchVal2 = canonicalScript(propertyValue1).getOrElse { return Result.failure(it) }) {
                                null -> { return Result.failure(Error.PropertyValueNotFound) }
                                else -> {
                                    val canon2 = matchVal2
                                    canon2
                                }
                            }
                        CanonicalClassQuery.Script(canon3)
                    }
                    else -> {
                        val vals1 =
                            when (val matchVal3 = propertyValues(canonName1).getOrElse { return Result.failure(it) }) {
                                null -> { return Result.failure(Error.PropertyValueNotFound) }
                                else -> {
                                    val vals = matchVal3
                                    vals
                                }
                            }
                        val canonVal1 =
                            when (val matchVal4 = canonicalValue(vals1, propertyValue1)) {
                                null -> {
                                    return Result.failure(Error.PropertyValueNotFound)
                                }
                                else -> {
                                    val canonVal = matchVal4
                                    canonVal
                                }
                            }
                        CanonicalClassQuery.ByValue(propertyName = canonName1, propertyValue = canonVal1)
                    }
                })
            }
        }
    }

    public open fun canonicalBinary(name: String): Result<CanonicalClassQuery> {
        val norm = symbolicNameNormalize(name)
        // This is a special case where 'cf' refers to the 'Format' general
        // category, but where the 'cf' abbreviation is also an abbreviation
        // for the 'CaseFolding' property. But we want to treat it as
        // a general category. (Currently, we don't even support the
        // 'CaseFolding' property. But if we do in the future, users will be
        // required to spell it out.)
        //
        // Also 'sc' refers to the 'CurrencySymbol' general category, but is
        // also the abbreviation for the 'Script' property. So we avoid calling
        // 'canonicalProp' for it too, which would erroneously normalize it
        // to 'Script'.
        //
        // Another case: 'lc' is an abbreviation for the 'CasedLetter'
        // general category, but is also an abbreviation for the 'LowercaseMapping'
        // property. We don't currently support the latter, so as with 'cf'
        // above, we treat 'lc' as 'CasedLetter'.
        if (norm != "cf" && norm != "sc" && norm != "lc") {
            when (val matchVal = canonicalProp(norm).getOrElse { return Result.failure(it) }) {
                null -> {}
                else -> {
                    val canon = matchVal
                    return Result.success(CanonicalClassQuery.Binary(canon))
                }
            }
        }
        when (val matchVal1 = canonicalGencat(norm).getOrElse { return Result.failure(it) }) {
            null -> {}
            else -> {
                val canon1 = matchVal1
                return Result.success(CanonicalClassQuery.GeneralCategory(canon1))
            }
        }
        when (val matchVal2 = canonicalScript(norm).getOrElse { return Result.failure(it) }) {
            null -> {}
            else -> {
                val canon2 = matchVal2
                return Result.success(CanonicalClassQuery.Script(canon2))
            }
        }
        return Result.failure(Error.PropertyNotFound)
    }
}

/**
 * Like ClassQuery, but its parameters have been canonicalized. This also
 * differentiates binary properties from flattened general categories and
 * scripts.
 */
public sealed class CanonicalClassQuery {
    /**
     * The canonical binary property name.
     */
    public data class Binary(public var value: String) : CanonicalClassQuery()
    /**
     * The canonical general category name.
     */
    public data class GeneralCategory(public var value: String) : CanonicalClassQuery()
    /**
     * The canonical script name.
     */
    public data class Script(public var value: String) : CanonicalClassQuery()
    /**
     * An arbitrary association between property and value, both of which
     * have been canonicalized.
     *
     * Note that by construction, the property name of ByValue will never
     * be GeneralCategory or Script. Those two cases are subsumed by the
     * eponymous variants.
     */
    public data class ByValue(public var propertyName: String, public var propertyValue: String) : CanonicalClassQuery()

    public fun rustEquals(other: CanonicalClassQuery): Boolean = this == other
}

/**
 * Looks up a Unicode class given a query. If one doesn't exist, then
 * `None` is returned.
 */
public fun cls(query: ClassQuery): Result<ClassUnicode> {
    return run {
        val matchVal = query.canonicalize().getOrElse { return Result.failure(it) }
        when {
            matchVal is Binary -> {
                val (name) = matchVal
                boolProperty(name)
            }
            matchVal is GeneralCategory -> {
                val (name1) = matchVal
                gencat(name1)
            }
            matchVal is Script -> {
                val (name2) = matchVal
                script(name2)
            }
            matchVal is ByValue && matchVal.propertyName == "Age" -> {
                val propertyValue = matchVal.propertyValue
                var cls = hir.ClassUnicode.empty()
                for (set in ages(propertyValue).getOrElse { return Result.failure(it) }) {
                    cls.union(hirClass(set))
                }
                Result.success(cls)
            }
            matchVal is ByValue && matchVal.propertyName == "Script_Extensions" -> {
                val propertyValue1 = matchVal.propertyValue
                scriptExtension(propertyValue1)
            }
            matchVal is ByValue && matchVal.propertyName == "Grapheme_Cluster_Break" -> {
                val propertyValue2 = matchVal.propertyValue
                gcb(propertyValue2)
            }
            matchVal is ByValue && matchVal.propertyName == "Sentence_Break" -> {
                val propertyValue3 = matchVal.propertyValue
                sb(propertyValue3)
            }
            matchVal is ByValue && matchVal.propertyName == "Word_Break" -> {
                val propertyValue4 = matchVal.propertyValue
                wb(propertyValue4)
            }
            else -> {
                // What else should we support?
                Result.failure(Error.PropertyNotFound)
            }
        }
    }
}

/**
 * Returns a Unicode aware class for \w.
 *
 * This returns an error if the data is not available for \w.
 */
public fun perlWord(): Result<ClassUnicode> {
    fun imp(): Result<ClassUnicode> = Result.success(hirClass(PERL_WORD))
    return imp()
}

/**
 * Returns a Unicode aware class for \s.
 *
 * This returns an error if the data is not available for \s.
 */
public fun perlSpace(): Result<ClassUnicode> {
    fun imp(): Result<ClassUnicode> = Result.success(hirClass(WHITE_SPACE))
    return imp()
}

/**
 * Returns a Unicode aware class for \d.
 *
 * This returns an error if the data is not available for \d.
 */
public fun perlDigit(): Result<ClassUnicode> {
    fun imp(): Result<ClassUnicode> = Result.success(hirClass(DECIMAL_NUMBER))
    return imp()
}

/**
 * Build a Unicode HIR class from a sequence of Unicode scalar value ranges.
 */
public fun hirClass(ranges: List<Pair<Char, Char>>): ClassUnicode {
    val hirRanges: List<ClassUnicodeRange> = ranges.map({ (s, e) -> hir.ClassUnicodeRange.new(s, e) }).collect()
    return hir.ClassUnicode.new(hirRanges)
}

/**
 * Returns true only if the given codepoint is in the `\w` character class.
 *
 * If the `unicode - perl` feature is not enabled, then this returns an error.
 */
public fun isWordCharacter(c: Char): Result<Boolean> {
    fun imp(c1: Char): Result<Boolean> {
        if (UByte.tryFrom(c1).mapOr(false, isWordByte)) {
            return Result.success(true)
        }
        return Result.success(PERL_WORD.binarySearchBy({ (start, end) ->

            if (start <= c1 && c1 <= end) {
                Ordering.Equal
            } else if (start > c1) {
                Ordering.Greater
            } else {
                Ordering.Less
            }
        }).isOk())
    }
    return imp(c)
}

/**
 * A mapping of property values for a specific property.
 *
 * The first element of each tuple is a normalized property value while the
 * second element of each tuple is the corresponding canonical property
 * value.
 */
public typealias PropertyValues = List<Pair<String, String>>

public fun canonicalGencat(normalizedValue: String): Result<String?> {
    return Result.success(when (normalizedValue) {
        "any" -> "Any"
        "assigned" -> "Assigned"
        "ascii" -> "ASCII"
        else -> {
            val gencats = (propertyValues("General_Category").getOrElse { return Result.failure(it) })!!
            canonicalValue(gencats, normalizedValue)
        }
    })
}

public fun canonicalScript(normalizedValue: String): Result<String?> {
    val scripts = (propertyValues("Script").getOrElse { return Result.failure(it) })!!
    return Result.success(canonicalValue(scripts, normalizedValue))
}

/**
 * Find the canonical property name for the given normalized property name.
 *
 * If no such property exists, then `None` is returned.
 *
 * The normalized property name must have been normalized according to
 * UAX44 LM3, which can be done using `symbolicNameNormalize`.
 *
 * If the property names data is not available, then an error is returned.
 */
public fun canonicalProp(normalizedName: String): Result<String?> {
    fun imp(name: String): Result<String?> = Result.success(PROPERTY_NAMES.binarySearchByKey(name, { (n, _) -> n }).ok().map({ i -> PROPERTY_NAMES[i].second }))
    return imp(normalizedName)
}

/**
 * Find the canonical property value for the given normalized property
 * value.
 *
 * The given property values should correspond to the values for the property
 * under question, which can be found using `propertyValues`.
 *
 * If no such property value exists, then `None` is returned.
 *
 * The normalized property value must have been normalized according to
 * UAX44 LM3, which can be done using `symbolicNameNormalize`.
 */
public fun canonicalValue(vals: PropertyValues, normalizedValue: String): String? = vals.binarySearchByKey(normalizedValue, { (n, _) -> n }).ok().map({ i -> vals[i].second })

/**
 * Return the table of property values for the given property name.
 *
 * If the property values data is not available, then an error is returned.
 */
public fun propertyValues(canonicalPropertyName: String): Result<PropertyValues?> {
    fun imp(name: String): Result<PropertyValues?> = Result.success(PROPERTY_VALUES.binarySearchByKey(name, { (n, _) -> n }).ok().map({ i -> PROPERTY_VALUES[i].second }))
    return imp(canonicalPropertyName)
}

// This is only used in some cases, but small enough to just let it be dead
// instead of figuring out (and maintaining) the right set of features.
public fun propertySet(nameMap: List<Pair<String, Range>>, canonical: String): Range? = nameMap.binarySearchByKey(canonical, { x -> x.first }).ok().map({ i -> nameMap[i].second })

/**
 * Returns an iterator over Unicode Age sets. Each item corresponds to a set
 * of codepoints that were added in a particular revision of Unicode. The
 * iterator yields items in chronological order.
 *
 * If the given age value isn't valid or if the data isn't available, then an
 * error is returned instead.
 */
public fun ages(canonicalAge: String): Result<Any> {
    fun imp(canonicalAge1: String): Result<Any> {
        val AGES: List<Pair<String, Range>> = listOf(Pair("V1_1", V1_1), Pair("V2_0", V2_0), Pair("V2_1", V2_1), Pair("V3_0", V3_0), Pair("V3_1", V3_1), Pair("V3_2", V3_2), Pair("V4_0", V4_0), Pair("V4_1", V4_1), Pair("V5_0", V5_0), Pair("V5_1", V5_1), Pair("V5_2", V5_2), Pair("V6_0", V6_0), Pair("V6_1", V6_1), Pair("V6_2", V6_2), Pair("V6_3", V6_3), Pair("V7_0", V7_0), Pair("V8_0", V8_0), Pair("V9_0", V9_0), Pair("V10_0", V10_0), Pair("V11_0", V11_0), Pair("V12_0", V12_0), Pair("V12_1", V12_1), Pair("V13_0", V13_0), Pair("V14_0", V14_0), Pair("V15_0", V15_0), Pair("V15_1", V15_1), Pair("V16_0", V16_0))
        check(AGES.len() == BY_NAME.len())
        val pos = AGES.iter().position({ (age, _) -> canonicalAge1 == age })
        return when (pos) {
            null -> Result.failure(Error.PropertyValueNotFound)
            else -> {
                val i = pos
                Result.success(AGES[0..i].map({ (_, classes) -> classes }))
            }
        }
    }
    return imp(canonicalAge)
}

/**
 * Returns the Unicode HIR class corresponding to the given general category.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given general category could not be found, or if the general
 * category data is not available, then an error is returned.
 */
public fun gencat(canonicalName: String): Result<ClassUnicode> {
    fun imp(name: String): Result<ClassUnicode> {
        return when (name) {
            "ASCII" -> Result.success(hirClass(listOf(Pair('\u0000', '\u007f'))))
            "Any" -> Result.success(hirClass(listOf(Pair('\u0000', 0x10ffff))))
            "Assigned" -> {
                val cls = gencat("Unassigned").getOrElse { return Result.failure(it) }
                cls.negate()
                Result.success(cls)
            }
            else -> {
                val name1 = name
                propertySet(BY_NAME, name1)?.let(::hirClass).okOr(Error.PropertyValueNotFound)
            }
        }
    }
    return when (canonicalName) {
        "Decimal_Number" -> perlDigit()
        else -> {
            val name = canonicalName
            imp(name)
        }
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given script.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given script could not be found, or if the script data is not
 * available, then an error is returned.
 */
public fun script(canonicalName: String): Result<ClassUnicode> {
    fun imp(name: String): Result<ClassUnicode> = propertySet(BY_NAME, name)?.let(::hirClass).okOr(Error.PropertyValueNotFound)
    return imp(canonicalName)
}

/**
 * Returns the Unicode HIR class corresponding to the given script extension.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given script extension could not be found, or if the script data is
 * not available, then an error is returned.
 */
public fun scriptExtension(canonicalName: String): Result<ClassUnicode> {
    fun imp(name: String): Result<ClassUnicode> = propertySet(BY_NAME, name)?.let(::hirClass).okOr(Error.PropertyValueNotFound)
    return imp(canonicalName)
}

/**
 * Returns the Unicode HIR class corresponding to the given Unicode boolean
 * property.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given boolean property could not be found, or if the boolean
 * property data is not available, then an error is returned.
 */
public fun boolProperty(canonicalName: String): Result<ClassUnicode> {
    fun imp(name: String): Result<ClassUnicode> = propertySet(BY_NAME, name)?.let(::hirClass).okOr(Error.PropertyNotFound)
    return when (canonicalName) {
        "Decimal_Number" -> perlDigit()
        "White_Space" -> perlSpace()
        else -> {
            val name = canonicalName
            imp(name)
        }
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given grapheme cluster
 * break property.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given property could not be found, or if the corresponding data is
 * not available, then an error is returned.
 */
public fun gcb(canonicalName: String): Result<ClassUnicode> {
    fun imp(name: String): Result<ClassUnicode> = propertySet(BY_NAME, name)?.let(::hirClass).okOr(Error.PropertyValueNotFound)
    return imp(canonicalName)
}

/**
 * Returns the Unicode HIR class corresponding to the given word break
 * property.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given property could not be found, or if the corresponding data is
 * not available, then an error is returned.
 */
public fun wb(canonicalName: String): Result<ClassUnicode> {
    fun imp(name: String): Result<ClassUnicode> = propertySet(BY_NAME, name)?.let(::hirClass).okOr(Error.PropertyValueNotFound)
    return imp(canonicalName)
}

/**
 * Returns the Unicode HIR class corresponding to the given sentence
 * break property.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given property could not be found, or if the corresponding data is
 * not available, then an error is returned.
 */
public fun sb(canonicalName: String): Result<ClassUnicode> {
    fun imp(name: String): Result<ClassUnicode> = propertySet(BY_NAME, name)?.let(::hirClass).okOr(Error.PropertyValueNotFound)
    return imp(canonicalName)
}

/**
 * Like symbolicNameNormalizeBytes, but operates on a string.
 */
public fun symbolicNameNormalize(x: String): String {
    val tmp = x.encodeToByteArray().map { it.toUByte() }.toVec()
    val len = symbolicNameNormalizeBytes(tmp).size
    tmp.truncate(len)
    // This should always succeed because `symbolicNameNormalizeBytes`
    // guarantees that `tmp[.. len]` is always valid UTF-8.
    //
    // N.B. We could avoid the additional UTF-8 check here, but it's unlikely
    // to be worth skipping the additional safety check. A benchmark must
    // justify it first.
    return String.fromUtf8(tmp).unwrap()
}

/**
 * Normalize the given symbolic name in place according to UAX44-LM3.
 *
 * A "symbolic name" typically corresponds to property names and property
 * value aliases. Note, though, that it should not be applied to property
 * string values.
 *
 * The slice returned is guaranteed to be valid UTF-8 for all possible values
 * of `slice`.
 *
 * See: https://unicode.org/reports/tr44/#UAX44-LM3
 */
public fun symbolicNameNormalizeBytes(slice: List<UByte>): List<UByte> {
    // I couldn't find a place in the standard that specified that property
    // names/aliases had a particular structure (unlike character names), but
    // we assume that it's ASCII only and drop anything that isn't ASCII.
    var start = 0
    var startsWithIs = false
    if (slice.size >= 2) {
        // Ignore any "is" prefix.
        startsWithIs = slice[0 until 2] == "is".encodeToByteArray() || slice[0 until 2] == "IS".encodeToByteArray() || slice[0 until 2] == "iS".encodeToByteArray() || slice[0 until 2] == "Is".encodeToByteArray()
        if (startsWithIs) {
            start = 2
        }
    }
    var nextWrite = 0
    for (i in start until slice.size) {
        // VALIDITY ARGUMENT: To guarantee that the resulting slice is valid
        // UTF-8, we ensure that the slice contains only ASCII bytes. In
        // particular, we drop every non-ASCII byte from the normalized string.
        val b = slice[i]
        if (b == 32u || b == 95u || b == 45u) {
            continue
        } else if (65u <= b && b <= 90u) {
            slice[nextWrite] = (b + (97u - 65u)).toUByte()
            nextWrite += 1
        } else if (b <= 0x7Fu.toUByte()) {
            slice[nextWrite] = b
            nextWrite += 1
        }
    }
    // Special case: IsoComment has a 'isc' abbreviation. Since we generally
    // ignore 'is' prefixes, the 'isc' abbreviation gets caught in the cross
    // fire and ends up creating an alias for 'c' to 'IsoComment', but it
    // is actually an alias for the 'Other' general category.
    if (startsWithIs && nextWrite == 1 && slice[0] == 99u) {
        slice[0] = 105u
        slice[1] = 115u
        slice[2] = 99u
        nextWrite = 3
    }
    return slice[0 until nextWrite]
}
