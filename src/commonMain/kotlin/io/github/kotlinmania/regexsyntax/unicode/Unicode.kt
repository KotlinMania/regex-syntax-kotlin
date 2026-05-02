// port-lint: source unicode.rs
package io.github.kotlinmania.regexsyntax.unicode

import io.github.kotlinmania.regexsyntax.hir.ClassUnicode
import io.github.kotlinmania.regexsyntax.hir.ClassUnicodeRange
import io.github.kotlinmania.regexsyntax.unicodetables.age.BY_NAME as AGE_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.age.V1_1 as AGE_V1_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V2_0 as AGE_V2_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V2_1 as AGE_V2_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V3_0 as AGE_V3_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V3_1 as AGE_V3_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V3_2 as AGE_V3_2
import io.github.kotlinmania.regexsyntax.unicodetables.age.V4_0 as AGE_V4_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V4_1 as AGE_V4_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V5_0 as AGE_V5_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V5_1 as AGE_V5_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V5_2 as AGE_V5_2
import io.github.kotlinmania.regexsyntax.unicodetables.age.V6_0 as AGE_V6_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V6_1 as AGE_V6_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V6_2 as AGE_V6_2
import io.github.kotlinmania.regexsyntax.unicodetables.age.V6_3 as AGE_V6_3
import io.github.kotlinmania.regexsyntax.unicodetables.age.V7_0 as AGE_V7_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V8_0 as AGE_V8_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V9_0 as AGE_V9_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V10_0 as AGE_V10_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V11_0 as AGE_V11_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V12_0 as AGE_V12_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V12_1 as AGE_V12_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V13_0 as AGE_V13_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V14_0 as AGE_V14_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V15_0 as AGE_V15_0
import io.github.kotlinmania.regexsyntax.unicodetables.age.V15_1 as AGE_V15_1
import io.github.kotlinmania.regexsyntax.unicodetables.age.V16_0 as AGE_V16_0
import io.github.kotlinmania.regexsyntax.unicodetables.casefoldingsimple.CASE_FOLDING_SIMPLE
import io.github.kotlinmania.regexsyntax.unicodetables.generalcategory.BY_NAME as GENCAT_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.graphemeclusterbreak.BY_NAME as GCB_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.perlword.PERL_WORD
import io.github.kotlinmania.regexsyntax.unicodetables.propertybool.BY_NAME as PROPBOOL_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.propertybool.WHITE_SPACE as PROPBOOL_WHITE_SPACE
import io.github.kotlinmania.regexsyntax.unicodetables.propertynames.PROPERTY_NAMES
import io.github.kotlinmania.regexsyntax.unicodetables.propertyvalues.PROPERTY_VALUES
import io.github.kotlinmania.regexsyntax.unicodetables.script.BY_NAME as SCRIPT_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.scriptextension.BY_NAME as SCRIPTEXT_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.sentencebreak.BY_NAME as SB_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.wordbreak.BY_NAME as WB_BY_NAME

/**
 * An inclusive range of codepoints from a generated file.
 *
 * In the Kotlin port a range is encoded as an `IntArray` of size 2: `[start, end]`.
 */
internal typealias UnicodeRangeTable = Array<IntArray>

/**
 * An error that occurs when dealing with Unicode.
 *
 * We don't impl the Error trait here because these always get converted
 * into other public errors. (This error type isn't exported.)
 */
internal enum class UnicodeError {
    PROPERTY_NOT_FOUND,
    PROPERTY_VALUE_NOT_FOUND,
    PERL_CLASS_NOT_FOUND,
}

/**
 * An error that occurs when Unicode-aware simple case folding fails.
 *
 * This error can occur when the case mapping tables necessary for Unicode
 * aware case folding are unavailable. This only occurs when the
 * `unicode-case` feature is disabled. (The feature is enabled by default.)
 */
class CaseFoldError internal constructor() : Throwable() {
    override val message: String
        get() = "Unicode-aware case folding is not available " +
            "(probably because the unicode-case feature is not enabled)"
}

/**
 * An error that occurs when the Unicode-aware `\w` class is unavailable.
 *
 * This error can occur when the data tables necessary for the Unicode aware
 * Perl character class `\w` are unavailable. This only occurs when the
 * `unicode-perl` feature is disabled. (The feature is enabled by default.)
 */
class UnicodeWordError internal constructor() : Throwable() {
    override val message: String
        get() = "Unicode-aware \\w class is not available " +
            "(probably because the unicode-perl feature is not enabled)"
}

/**
 * A state oriented traverser of the simple case folding table.
 *
 * A case folder can be constructed via [SimpleCaseFolder.new], which will
 * return an error if the underlying case folding table is unavailable.
 *
 * After construction, it is expected that callers will use
 * [SimpleCaseFolder.mapping] by calling it with codepoints in strictly
 * increasing order. For example, calling it on `b` and then on `a` is illegal
 * and will result in a panic.
 *
 * The main idea of this type is that it tries hard to make mapping lookups
 * fast by exploiting the structure of the underlying table, and the ordering
 * assumption enables this.
 */
class SimpleCaseFolder private constructor(
    /**
     * The simple case fold table. It's a sorted association list, where the
     * keys are Unicode scalar values and the values are the corresponding
     * equivalence class (not including the key) of the "simple" case folded
     * Unicode scalar values.
     */
    private val table: Array<Pair<Int, IntArray>>,
) {
    /** The last codepoint that was used for a lookup. */
    private var last: Int? = null

    /**
     * The index to the entry in [table] corresponding to the smallest key `k`
     * such that `k > k0`, where `k0` is the most recent key lookup. Note that
     * in particular, `k0` may not be in the table!
     */
    private var next: Int = 0

    /**
     * Return the equivalence class of case folded codepoints for the given
     * codepoint. The equivalence class returned never includes the codepoint
     * given. If the given codepoint has no case folded codepoints (i.e.,
     * no entry in the underlying case folding table), then this returns an
     * empty array.
     *
     * Panics:
     *
     * This panics when called with a `c` that is less than or equal to the
     * previous call. In other words, callers need to use this method with
     * strictly increasing values of `c`.
     */
    fun mapping(c: Int): IntArray {
        val l = last
        if (l != null) {
            check(l < c) {
                "got codepoint U+${c.toString(16).uppercase()} which occurs before " +
                    "last codepoint U+${l.toString(16).uppercase()}"
            }
        }
        last = c
        if (next >= table.size) {
            return EMPTY_MAPPING
        }
        val (k, v) = table[next]
        if (k == c) {
            next += 1
            return v
        }
        val r = get(c)
        return when (r) {
            is BinarySearchResult.Err -> {
                next = r.index
                EMPTY_MAPPING
            }
            is BinarySearchResult.Ok -> {
                // Since we require lookups to proceed
                // in order, anything we find should be
                // after whatever we thought might be
                // next. Otherwise, the caller is either
                // going out of order or we would have
                // found our next key at `next`.
                check(r.index > next)
                next = r.index + 1
                table[r.index].second
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
     * Panics:
     *
     * This panics when `end < start`.
     */
    fun overlaps(start: Int, end: Int): Boolean {
        check(start <= end)
        val r = binarySearchBy(table.size) { i ->
            val c = table[i].first
            when {
                start <= c && c <= end -> 0
                c > end -> 1
                else -> -1
            }
        }
        return r is BinarySearchResult.Ok
    }

    /**
     * Returns the index at which `c` occurs in the simple case fold table. If
     * `c` does not occur, then this returns an `i` such that `table[i-1].0 <
     * c` and `table[i].0 > c`.
     */
    private fun get(c: Int): BinarySearchResult {
        return binarySearchByKey(table.size, c) { i -> table[i].first }
    }

    companion object {
        private val EMPTY_MAPPING = IntArray(0)

        /**
         * Create a new simple case folder, returning an error if the underlying
         * case folding table is unavailable.
         */
        fun new(): Result<SimpleCaseFolder> =
            Result.success(SimpleCaseFolder(CASE_FOLDING_SIMPLE))
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
 * canonicalized. That is, `GC == gc == GeneralCategory == general_category`.
 */
sealed class ClassQuery {
    /**
     * Return a class corresponding to a Unicode binary property, named by
     * a single letter.
     */
    data class OneLetter(val c: Int) : ClassQuery()

    /**
     * Return a class corresponding to a Unicode binary property.
     *
     * Note that, by special exception (see UTS#18, Section 1.2), both
     * general category values and script values are permitted here as if
     * they were a binary property.
     */
    data class Binary(val name: String) : ClassQuery()

    /**
     * Return a class corresponding to all codepoints whose property
     * (identified by [propertyName]) corresponds to the given value
     * (identified by [propertyValue]).
     */
    data class ByValue(
        /** A property name. */
        val propertyName: String,
        /** A property value. */
        val propertyValue: String,
    ) : ClassQuery()

    internal fun canonicalize(): Result<CanonicalClassQuery> {
        return when (this) {
            is OneLetter -> {
                val s = StringBuilder().appendCodePoint(this.c).toString()
                canonicalBinary(s)
            }
            is Binary -> canonicalBinary(this.name)
            is ByValue -> {
                val propertyName = symbolicNameNormalize(this.propertyName)
                val propertyValue = symbolicNameNormalize(this.propertyValue)

                val canonNameRes = canonicalProp(propertyName)
                if (canonNameRes.isFailure) return Result.failure(canonNameRes.exceptionOrNull()!!)
                val canonName = canonNameRes.getOrNull()
                    ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_NOT_FOUND))
                Result.success(when (canonName) {
                    "General_Category" -> {
                        val canonRes = canonicalGencat(propertyValue)
                        if (canonRes.isFailure) return Result.failure(canonRes.exceptionOrNull()!!)
                        val canon = canonRes.getOrNull()
                            ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
                        CanonicalClassQuery.GeneralCategory(canon)
                    }
                    "Script" -> {
                        val canonRes = canonicalScript(propertyValue)
                        if (canonRes.isFailure) return Result.failure(canonRes.exceptionOrNull()!!)
                        val canon = canonRes.getOrNull()
                            ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
                        CanonicalClassQuery.Script(canon)
                    }
                    else -> {
                        val valsRes = propertyValues(canonName)
                        if (valsRes.isFailure) return Result.failure(valsRes.exceptionOrNull()!!)
                        val vals = valsRes.getOrNull()
                            ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
                        val canonVal = canonicalValue(vals, propertyValue)
                            ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
                        CanonicalClassQuery.ByValue(
                            propertyName = canonName,
                            propertyValue = canonVal,
                        )
                    }
                })
            }
        }
    }

    private fun canonicalBinary(name: String): Result<CanonicalClassQuery> {
        val norm = symbolicNameNormalize(name)

        // This is a special case where 'cf' refers to the 'Format' general
        // category, but where the 'cf' abbreviation is also an abbreviation
        // for the 'Case_Folding' property. But we want to treat it as
        // a general category. (Currently, we don't even support the
        // 'Case_Folding' property. But if we do in the future, users will be
        // required to spell it out.)
        //
        // Also 'sc' refers to the 'Currency_Symbol' general category, but is
        // also the abbreviation for the 'Script' property. So we avoid calling
        // [canonicalProp] for it too, which would erroneously normalize it
        // to 'Script'.
        //
        // Another case: 'lc' is an abbreviation for the 'Cased_Letter'
        // general category, but is also an abbreviation for the 'Lowercase_Mapping'
        // property. We don't currently support the latter, so as with 'cf'
        // above, we treat 'lc' as 'Cased_Letter'.
        if (norm != "cf" && norm != "sc" && norm != "lc") {
            val r = canonicalProp(norm)
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
            val canon = r.getOrNull()
            if (canon != null) {
                return Result.success(CanonicalClassQuery.Binary(canon))
            }
        }
        run {
            val r = canonicalGencat(norm)
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
            val canon = r.getOrNull()
            if (canon != null) {
                return Result.success(CanonicalClassQuery.GeneralCategory(canon))
            }
        }
        run {
            val r = canonicalScript(norm)
            if (r.isFailure) return Result.failure(r.exceptionOrNull()!!)
            val canon = r.getOrNull()
            if (canon != null) {
                return Result.success(CanonicalClassQuery.Script(canon))
            }
        }
        return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_NOT_FOUND))
    }
}

/**
 * Like [ClassQuery], but its parameters have been canonicalized. This also
 * differentiates binary properties from flattened general categories and
 * scripts.
 */
internal sealed class CanonicalClassQuery {
    /** The canonical binary property name. */
    data class Binary(val name: String) : CanonicalClassQuery()

    /** The canonical general category name. */
    data class GeneralCategory(val name: String) : CanonicalClassQuery()

    /** The canonical script name. */
    data class Script(val name: String) : CanonicalClassQuery()

    /**
     * An arbitrary association between property and value, both of which
     * have been canonicalized.
     *
     * Note that by construction, the property name of [ByValue] will never
     * be `General_Category` or `Script`. Those two cases are subsumed by the
     * eponymous variants.
     */
    data class ByValue(
        /** The canonical property name. */
        val propertyName: String,
        /** The canonical property value. */
        val propertyValue: String,
    ) : CanonicalClassQuery()
}

/** Wraps [UnicodeError] for `Result` propagation. */
internal class UnicodeErrorException(val error: UnicodeError) : Throwable(error.name)

/**
 * Looks up a Unicode class given a query. If one doesn't exist, then
 * the result carries [UnicodeError.PROPERTY_NOT_FOUND].
 */
fun unicodeClass(query: ClassQuery): Result<ClassUnicode> {
    val canonRes = query.canonicalize()
    if (canonRes.isFailure) return Result.failure(canonRes.exceptionOrNull()!!)
    return when (val canon = canonRes.getOrThrow()) {
        is CanonicalClassQuery.Binary -> boolProperty(canon.name)
        is CanonicalClassQuery.GeneralCategory -> gencat(canon.name)
        is CanonicalClassQuery.Script -> script(canon.name)
        is CanonicalClassQuery.ByValue -> when (canon.propertyName) {
            "Age" -> {
                val cls = ClassUnicode.empty()
                val agesRes = ages(canon.propertyValue)
                if (agesRes.isFailure) return Result.failure(agesRes.exceptionOrNull()!!)
                for (set in agesRes.getOrThrow()) {
                    cls.union(hirClass(set))
                }
                Result.success(cls)
            }
            "Script_Extensions" -> scriptExtension(canon.propertyValue)
            "Grapheme_Cluster_Break" -> gcb(canon.propertyValue)
            "Sentence_Break" -> sb(canon.propertyValue)
            "Word_Break" -> wb(canon.propertyValue)
            else -> {
                // What else should we support?
                Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_NOT_FOUND))
            }
        }
    }
}

/**
 * Returns a Unicode aware class for `\w`.
 *
 * This returns an error if the data is not available for `\w`.
 */
fun perlWord(): Result<ClassUnicode> = Result.success(hirClass(PERL_WORD))

/**
 * Returns a Unicode aware class for `\s`.
 *
 * This returns an error if the data is not available for `\s`.
 */
fun perlSpace(): Result<ClassUnicode> = Result.success(hirClass(PROPBOOL_WHITE_SPACE))

/**
 * Returns a Unicode aware class for `\d`.
 *
 * This returns an error if the data is not available for `\d`.
 */
fun perlDigit(): Result<ClassUnicode> {
    val decimal = propertySet(GENCAT_BY_NAME, "Decimal_Number")
        ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
    return Result.success(hirClass(decimal))
}

/** Build a Unicode HIR class from a sequence of Unicode scalar value ranges. */
fun hirClass(ranges: Array<IntArray>): ClassUnicode {
    val hirRanges = ranges.map { ClassUnicodeRange.new(it[0], it[1]) }
    return ClassUnicode.new(hirRanges)
}

/**
 * Returns true only if the given codepoint is in the `\w` character class.
 *
 * If the `unicode-perl` feature is not enabled, then this returns an error.
 */
fun isWordCharacter(c: Int): Result<Boolean> {
    if (c in 0..127 && isWordByte(c.toByte())) {
        return Result.success(true)
    }
    val r = binarySearchBy(PERL_WORD.size) { i ->
        val start = PERL_WORD[i][0]
        val end = PERL_WORD[i][1]
        when {
            start <= c && c <= end -> 0
            start > c -> 1
            else -> -1
        }
    }
    return Result.success(r is BinarySearchResult.Ok)
}

private fun isWordByte(b: Byte): Boolean {
    val v = b.toInt() and 0xFF
    return v == '_'.code ||
        (v in '0'.code..'9'.code) ||
        (v in 'A'.code..'Z'.code) ||
        (v in 'a'.code..'z'.code)
}

private fun canonicalGencat(normalizedValue: String): Result<String?> {
    return when (normalizedValue) {
        "any" -> Result.success("Any")
        "assigned" -> Result.success("Assigned")
        "ascii" -> Result.success("ASCII")
        else -> {
            val gencatsRes = propertyValues("General_Category")
            if (gencatsRes.isFailure) return Result.failure(gencatsRes.exceptionOrNull()!!)
            val gencats = gencatsRes.getOrThrow()!!
            Result.success(canonicalValue(gencats, normalizedValue))
        }
    }
}

private fun canonicalScript(normalizedValue: String): Result<String?> {
    val scriptsRes = propertyValues("Script")
    if (scriptsRes.isFailure) return Result.failure(scriptsRes.exceptionOrNull()!!)
    val scripts = scriptsRes.getOrThrow()!!
    return Result.success(canonicalValue(scripts, normalizedValue))
}

/**
 * Find the canonical property name for the given normalized property name.
 *
 * If no such property exists, then `null` is returned (wrapped in success).
 *
 * The normalized property name must have been normalized according to
 * UAX44 LM3, which can be done using [symbolicNameNormalize].
 *
 * If the property names data is not available, then a failure is returned.
 */
private fun canonicalProp(normalizedName: String): Result<String?> {
    val r = binarySearchBy(PROPERTY_NAMES.size) { i ->
        PROPERTY_NAMES[i].first.compareTo(normalizedName)
    }
    return Result.success(when (r) {
        is BinarySearchResult.Ok -> PROPERTY_NAMES[r.index].second
        is BinarySearchResult.Err -> null
    })
}

/**
 * Find the canonical property value for the given normalized property
 * value.
 *
 * The given property values should correspond to the values for the property
 * under question, which can be found using [propertyValues].
 *
 * If no such property value exists, then `null` is returned.
 *
 * The normalized property value must have been normalized according to
 * UAX44 LM3, which can be done using [symbolicNameNormalize].
 */
private fun canonicalValue(
    vals: Array<Pair<String, String>>,
    normalizedValue: String,
): String? {
    val r = binarySearchBy(vals.size) { i -> vals[i].first.compareTo(normalizedValue) }
    return when (r) {
        is BinarySearchResult.Ok -> vals[r.index].second
        is BinarySearchResult.Err -> null
    }
}

/**
 * Return the table of property values for the given property name.
 *
 * If the property values data is not available, then a failure is returned.
 */
private fun propertyValues(canonicalPropertyName: String): Result<Array<Pair<String, String>>?> {
    val r = binarySearchBy(PROPERTY_VALUES.size) { i ->
        PROPERTY_VALUES[i].first.compareTo(canonicalPropertyName)
    }
    return Result.success(when (r) {
        is BinarySearchResult.Ok -> PROPERTY_VALUES[r.index].second
        is BinarySearchResult.Err -> null
    })
}

/** Look up a property by canonical name in a `BY_NAME` table. */
private fun propertySet(
    nameMap: Array<Pair<String, Array<IntArray>>>,
    canonical: String,
): Array<IntArray>? {
    val r = binarySearchBy(nameMap.size) { i -> nameMap[i].first.compareTo(canonical) }
    return when (r) {
        is BinarySearchResult.Ok -> nameMap[r.index].second
        is BinarySearchResult.Err -> null
    }
}

/**
 * Returns an iterator over Unicode Age sets. Each item corresponds to a set
 * of codepoints that were added in a particular revision of Unicode. The
 * iterator yields items in chronological order.
 *
 * If the given age value isn't valid or if the data isn't available, then a
 * failure is returned instead.
 */
private fun ages(canonicalAge: String): Result<List<Array<IntArray>>> {
    val ages: List<Pair<String, Array<IntArray>>> = listOf(
        "V1_1" to AGE_V1_1,
        "V2_0" to AGE_V2_0,
        "V2_1" to AGE_V2_1,
        "V3_0" to AGE_V3_0,
        "V3_1" to AGE_V3_1,
        "V3_2" to AGE_V3_2,
        "V4_0" to AGE_V4_0,
        "V4_1" to AGE_V4_1,
        "V5_0" to AGE_V5_0,
        "V5_1" to AGE_V5_1,
        "V5_2" to AGE_V5_2,
        "V6_0" to AGE_V6_0,
        "V6_1" to AGE_V6_1,
        "V6_2" to AGE_V6_2,
        "V6_3" to AGE_V6_3,
        "V7_0" to AGE_V7_0,
        "V8_0" to AGE_V8_0,
        "V9_0" to AGE_V9_0,
        "V10_0" to AGE_V10_0,
        "V11_0" to AGE_V11_0,
        "V12_0" to AGE_V12_0,
        "V12_1" to AGE_V12_1,
        "V13_0" to AGE_V13_0,
        "V14_0" to AGE_V14_0,
        "V15_0" to AGE_V15_0,
        "V15_1" to AGE_V15_1,
        "V16_0" to AGE_V16_0,
    )
    check(ages.size == AGE_BY_NAME.size) { "ages are out of sync" }

    val pos = ages.indexOfFirst { it.first == canonicalAge }
    return if (pos < 0) {
        Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
    } else {
        Result.success(ages.subList(0, pos + 1).map { it.second })
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given general category.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given general category could not be found, or if the general
 * category data is not available, then a failure is returned.
 */
private fun gencat(canonicalName: String): Result<ClassUnicode> {
    return when (canonicalName) {
        "Decimal_Number" -> perlDigit()
        else -> when (canonicalName) {
            "ASCII" -> Result.success(hirClass(arrayOf(intArrayOf(0, 0x7F))))
            "Any" -> Result.success(hirClass(arrayOf(intArrayOf(0, 0x10FFFF))))
            "Assigned" -> {
                val r = gencat("Unassigned")
                if (r.isFailure) return r
                val cls = r.getOrThrow()
                cls.negate()
                Result.success(cls)
            }
            else -> {
                val set = propertySet(GENCAT_BY_NAME, canonicalName)
                    ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
                Result.success(hirClass(set))
            }
        }
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given script.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given script could not be found, or if the script data is not
 * available, then a failure is returned.
 */
private fun script(canonicalName: String): Result<ClassUnicode> {
    val set = propertySet(SCRIPT_BY_NAME, canonicalName)
        ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
    return Result.success(hirClass(set))
}

/**
 * Returns the Unicode HIR class corresponding to the given script extension.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given script extension could not be found, or if the script data is
 * not available, then a failure is returned.
 */
private fun scriptExtension(canonicalName: String): Result<ClassUnicode> {
    val set = propertySet(SCRIPTEXT_BY_NAME, canonicalName)
        ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
    return Result.success(hirClass(set))
}

/**
 * Returns the Unicode HIR class corresponding to the given Unicode boolean
 * property.
 *
 * Name canonicalization is assumed to be performed by the caller.
 *
 * If the given boolean property could not be found, or if the boolean
 * property data is not available, then a failure is returned.
 */
private fun boolProperty(canonicalName: String): Result<ClassUnicode> {
    return when (canonicalName) {
        "Decimal_Number" -> perlDigit()
        "White_Space" -> perlSpace()
        else -> {
            val set = propertySet(PROPBOOL_BY_NAME, canonicalName)
                ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_NOT_FOUND))
            Result.success(hirClass(set))
        }
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given grapheme cluster
 * break property.
 */
private fun gcb(canonicalName: String): Result<ClassUnicode> {
    val set = propertySet(GCB_BY_NAME, canonicalName)
        ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
    return Result.success(hirClass(set))
}

/**
 * Returns the Unicode HIR class corresponding to the given word break
 * property.
 */
private fun wb(canonicalName: String): Result<ClassUnicode> {
    val set = propertySet(WB_BY_NAME, canonicalName)
        ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
    return Result.success(hirClass(set))
}

/**
 * Returns the Unicode HIR class corresponding to the given sentence
 * break property.
 */
private fun sb(canonicalName: String): Result<ClassUnicode> {
    val set = propertySet(SB_BY_NAME, canonicalName)
        ?: return Result.failure(UnicodeErrorException(UnicodeError.PROPERTY_VALUE_NOT_FOUND))
    return Result.success(hirClass(set))
}

/** Like [symbolicNameNormalizeBytes], but operates on a string. */
internal fun symbolicNameNormalize(x: String): String {
    val tmp = x.encodeToByteArray()
    val len = symbolicNameNormalizeBytes(tmp)
    return tmp.decodeToString(0, len)
}

/**
 * Normalize the given symbolic name in place according to UAX44-LM3.
 *
 * A "symbolic name" typically corresponds to property names and property
 * value aliases. Note, though, that it should not be applied to property
 * string values.
 *
 * Returns the new logical length; the prefix `slice[0 until length]` is the
 * normalized form and is guaranteed to be valid UTF-8.
 *
 * See: https://unicode.org/reports/tr44/#UAX44-LM3
 */
internal fun symbolicNameNormalizeBytes(slice: ByteArray): Int {
    // I couldn't find a place in the standard that specified that property
    // names/aliases had a particular structure (unlike character names), but
    // we assume that it's ASCII only and drop anything that isn't ASCII.
    var start = 0
    var startsWithIs = false
    if (slice.size >= 2) {
        // Ignore any "is" prefix.
        val p0 = slice[0]; val p1 = slice[1]
        startsWithIs = (p0 == 'i'.code.toByte() && p1 == 's'.code.toByte()) ||
            (p0 == 'I'.code.toByte() && p1 == 'S'.code.toByte()) ||
            (p0 == 'i'.code.toByte() && p1 == 'S'.code.toByte()) ||
            (p0 == 'I'.code.toByte() && p1 == 's'.code.toByte())
        if (startsWithIs) {
            start = 2
        }
    }
    var nextWrite = 0
    for (i in start until slice.size) {
        // VALIDITY ARGUMENT: To guarantee that the resulting slice is valid
        // UTF-8, we ensure that the slice contains only ASCII bytes. In
        // particular, we drop every non-ASCII byte from the normalized string.
        val b = slice[i].toInt() and 0xFF
        if (b == ' '.code || b == '_'.code || b == '-'.code) {
            continue
        } else if (b in 'A'.code..'Z'.code) {
            slice[nextWrite] = (b + ('a'.code - 'A'.code)).toByte()
            nextWrite += 1
        } else if (b <= 0x7F) {
            slice[nextWrite] = b.toByte()
            nextWrite += 1
        }
    }
    // Special case: ISO_Comment has a 'isc' abbreviation. Since we generally
    // ignore 'is' prefixes, the 'isc' abbreviation gets caught in the cross
    // fire and ends up creating an alias for 'c' to 'ISO_Comment', but it
    // is actually an alias for the 'Other' general category.
    if (startsWithIs && nextWrite == 1 && slice[0] == 'c'.code.toByte()) {
        slice[0] = 'i'.code.toByte()
        slice[1] = 's'.code.toByte()
        slice[2] = 'c'.code.toByte()
        nextWrite = 3
    }
    return nextWrite
}

// --- Binary search helpers (mirroring Rust's `slice::binary_search_by`) ---

internal sealed class BinarySearchResult {
    data class Ok(val index: Int) : BinarySearchResult()
    data class Err(val index: Int) : BinarySearchResult()
}

/**
 * Mirrors Rust's `slice::binary_search_by`.
 *
 * `compare(i)` should return a value < 0 if `slice[i] < target`,
 * 0 if equal, and > 0 if `slice[i] > target`.
 */
internal inline fun binarySearchBy(size: Int, compare: (Int) -> Int): BinarySearchResult {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        val c = compare(mid)
        when {
            c < 0 -> lo = mid + 1
            c > 0 -> hi = mid
            else -> return BinarySearchResult.Ok(mid)
        }
    }
    return BinarySearchResult.Err(lo)
}

/** Mirrors Rust's `slice::binary_search_by_key`. */
internal inline fun <K : Comparable<K>> binarySearchByKey(
    size: Int,
    key: K,
    extract: (Int) -> K,
): BinarySearchResult {
    return binarySearchBy(size) { i -> extract(i).compareTo(key) }
}

private fun StringBuilder.appendCodePoint(cp: Int): StringBuilder {
    if (cp <= 0xFFFF) {
        append(cp.toChar())
    } else {
        val v = cp - 0x10000
        append((0xD800 or (v ushr 10)).toChar())
        append((0xDC00 or (v and 0x3FF)).toChar())
    }
    return this
}
