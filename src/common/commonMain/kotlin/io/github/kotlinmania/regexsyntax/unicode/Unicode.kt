// port-lint: source unicode.rs
package io.github.kotlinmania.regexsyntax.unicode

import io.github.kotlinmania.regexsyntax.hir.ClassUnicode
import io.github.kotlinmania.regexsyntax.hir.ClassUnicodeRange
import io.github.kotlinmania.regexsyntax.isWordByte
import io.github.kotlinmania.regexsyntax.scalarToString
import io.github.kotlinmania.regexsyntax.unicodetables.age.BY_NAME as AGE_BY_NAME
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
import io.github.kotlinmania.regexsyntax.unicodetables.casefoldingsimple.CASE_FOLDING_SIMPLE
import io.github.kotlinmania.regexsyntax.unicodetables.generalcategory.BY_NAME as GENCAT_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.generalcategory.DECIMAL_NUMBER
import io.github.kotlinmania.regexsyntax.unicodetables.graphemeclusterbreak.BY_NAME as GCB_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.perlword.PERL_WORD
import io.github.kotlinmania.regexsyntax.unicodetables.propertybool.BY_NAME as PROP_BOOL_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.propertybool.WHITE_SPACE
import io.github.kotlinmania.regexsyntax.unicodetables.propertynames.PROPERTY_NAMES
import io.github.kotlinmania.regexsyntax.unicodetables.propertyvalues.PROPERTY_VALUES
import io.github.kotlinmania.regexsyntax.unicodetables.script.BY_NAME as SCRIPT_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.scriptextension.BY_NAME as SCRIPT_EXT_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.sentencebreak.BY_NAME as SB_BY_NAME
import io.github.kotlinmania.regexsyntax.unicodetables.wordbreak.BY_NAME as WB_BY_NAME
import io.github.kotlinmania.rustlang.core.result.Result

/**
 * An inclusive range of codepoints from a generated table.
 */
internal typealias Range = List<Pair<UInt, UInt>>

/**
 * An error that occurs when dealing with Unicode.
 *
 * These always get converted into other public errors.
 */
internal sealed class Error {
    internal data object PropertyNotFound : Error()

    internal data object PropertyValueNotFound : Error()

    internal data object PerlClassNotFound : Error()
}

/**
 * An error that occurs when Unicode-aware simple case folding fails.
 *
 * This error can occur when the case mapping tables necessary for Unicode
 * aware case folding are unavailable. This only occurs when the
 * `unicode-case` feature is disabled. (The feature is enabled by default.)
 */
public class CaseFoldError : Exception() {
    override val message: String
        get() = toString()

    public fun fmt(f: StringBuilder) {
        f.append(
            "Unicode-aware case folding is not available " +
                "(probably because the unicode-case feature is not enabled)",
        )
    }

    override fun toString(): String = buildString { fmt(this) }

    override fun equals(other: Any?): Boolean = other is CaseFoldError

    override fun hashCode(): Int = 0
}

/**
 * An error that occurs when the Unicode-aware `\w` class is unavailable.
 *
 * This error can occur when the data tables necessary for the Unicode aware
 * Perl character class `\w` are unavailable. This only occurs when the
 * `unicode-perl` feature is disabled. (The feature is enabled by default.)
 */
public class UnicodeWordError : Exception() {
    override val message: String
        get() = toString()

    public fun fmt(f: StringBuilder) {
        f.append(
            "Unicode-aware \\w class is not available " +
                "(probably because the unicode-perl feature is not enabled)",
        )
    }

    override fun toString(): String = buildString { fmt(this) }

    override fun equals(other: Any?): Boolean = other is UnicodeWordError

    override fun hashCode(): Int = 0
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
internal class SimpleCaseFolder(
    /**
     * The simple case fold table. It is a sorted association list, where the
     * keys are Unicode scalar values and the values are the corresponding
     * equivalence class (not including the key) of the "simple" case folded
     * Unicode scalar values.
     */
    internal var table: List<Pair<UInt, List<UInt>>>,
    /**
     * The last codepoint that was used for a lookup.
     */
    internal var last: UInt?,
    /**
     * The index to the entry in `table` corresponding to the smallest key `k`
     * such that `k > k0`, where `k0` is the most recent key lookup. Note that
     * in particular, `k0` may not be in the table.
     */
    internal var next: Int,
) {
    /**
     * Return the equivalence class of case folded codepoints for the given
     * codepoint. The equivalence class returned never includes the codepoint
     * given. If the given codepoint has no case folded codepoints (i.e.,
     * no entry in the underlying case folding table), then this returns an
     * empty list.
     *
     * Throws when called with a `c` that is less than or equal to the
     * previous call. Callers must pass strictly increasing values of `c`.
     */
    internal fun mapping(c: UInt): List<UInt> {
        val prev = this.last
        if (prev != null) {
            check(prev < c) {
                "got codepoint U+${c.toString(16).uppercase()} which occurs before " +
                    "last codepoint U+${prev.toString(16).uppercase()}"
            }
        }
        this.last = c
        if (this.next >= this.table.size) {
            return emptyList()
        }
        val (k, v) = this.table[this.next]
        if (k == c) {
            this.next += 1
            return v
        }
        return when (val r = this.get(c)) {
            is Result.Err -> {
                this.next = r.error
                emptyList()
            }
            is Result.Ok -> {
                val i = r.value
                // Since we require lookups to proceed in order, anything we find
                // should be after whatever we thought might be next.
                check(i > this.next)
                this.next = i + 1
                this.table[i].second
            }
        }
    }

    /**
     * Returns true if and only if the given range overlaps with any region
     * of the underlying case folding table. That is, when true, there exists
     * at least one codepoint in the inclusive range `[start, end]` that has
     * a non-trivial equivalence class of case folded codepoints.
     *
     * Panics when `end < start`.
     */
    internal fun overlaps(start: UInt, end: UInt): Boolean {
        check(start <= end)
        val idx =
            this.table.binarySearch { (c, _) ->
                when {
                    start <= c && c <= end -> 0
                    c > end -> 1
                    else -> -1
                }
            }
        return idx >= 0
    }

    /**
     * Returns the index at which `c` occurs in the simple case fold table. If
     * `c` does not occur, returns `Result.Err(i)` where `i` is the insertion index.
     */
    internal fun get(c: UInt): Result<Int, Int> {
        val idx = this.table.binarySearch { (k, _) -> k.compareTo(c) }
        return if (idx >= 0) Result.Ok(idx) else Result.Err(-idx - 1)
    }

    internal companion object {
        /**
         * Create a new simple case folder.
         */
        internal fun new(): Result<SimpleCaseFolder, CaseFoldError> =
            Result.Ok(
                SimpleCaseFolder(
                    table = CASE_FOLDING_SIMPLE,
                    last = null,
                    next = 0,
                ),
            )
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
 */
internal sealed class ClassQuery {
    /**
     * Return a class corresponding to a Unicode binary property, named by
     * a single letter.
     */
    internal data class OneLetter(internal var value: UInt) : ClassQuery()

    /**
     * Return a class corresponding to a Unicode binary property.
     *
     * Note that, by special exception (see UTS#18, Section 1.2), both
     * general category values and script values are permitted here as if
     * they were a binary property.
     */
    internal data class Binary(internal var value: String) : ClassQuery()

    /**
     * Return a class corresponding to all codepoints whose property
     * (identified by `propertyName`) corresponds to the given value
     * (identified by `propertyValue`).
     */
    internal data class ByValue(
        internal var propertyName: String,
        internal var propertyValue: String,
    ) : ClassQuery()

    internal fun canonicalize(): Result<CanonicalClassQuery, Error> =
        when (this) {
            is OneLetter -> this.canonicalBinary(scalarToString(this.value))
            is Binary -> this.canonicalBinary(this.value)
            is ByValue -> {
                val propNorm = symbolicNameNormalize(this.propertyName)
                val valNorm = symbolicNameNormalize(this.propertyValue)
                val canonName =
                    when (val r = canonicalProp(propNorm)) {
                        is Result.Err -> return r
                        is Result.Ok -> r.value ?: return Result.Err(Error.PropertyNotFound)
                    }
                when (canonName) {
                    "General_Category" -> {
                        val canon =
                            when (val r = canonicalGencat(valNorm)) {
                                is Result.Err -> return r
                                is Result.Ok -> r.value ?: return Result.Err(Error.PropertyValueNotFound)
                            }
                        Result.Ok(CanonicalClassQuery.GeneralCategory(canon))
                    }
                    "Script" -> {
                        val canon =
                            when (val r = canonicalScript(valNorm)) {
                                is Result.Err -> return r
                                is Result.Ok -> r.value ?: return Result.Err(Error.PropertyValueNotFound)
                            }
                        Result.Ok(CanonicalClassQuery.Script(canon))
                    }
                    else -> {
                        val vals =
                            when (val r = propertyValues(canonName)) {
                                is Result.Err -> return r
                                is Result.Ok -> r.value ?: return Result.Err(Error.PropertyValueNotFound)
                            }
                        val canonVal =
                            canonicalValue(vals, valNorm)
                                ?: return Result.Err(Error.PropertyValueNotFound)
                        Result.Ok(
                            CanonicalClassQuery.ByValue(
                                propertyName = canonName,
                                propertyValue = canonVal,
                            ),
                        )
                    }
                }
            }
        }

    internal fun canonicalBinary(name: String): Result<CanonicalClassQuery, Error> {
        val norm = symbolicNameNormalize(name)
        // This is a special case where `cf` refers to the `Format` general
        // category, but where the `cf` abbreviation is also an abbreviation
        // for the `CaseFolding` property. We treat it as a general category.
        // Also `sc` refers to the CurrencySymbol general category rather
        // than normalizing to Script, and `lc` refers to CasedLetter.
        if (norm != "cf" && norm != "sc" && norm != "lc") {
            when (val r = canonicalProp(norm)) {
                is Result.Err -> return r
                is Result.Ok -> {
                    val canon = r.value
                    if (canon != null) {
                        return Result.Ok(CanonicalClassQuery.Binary(canon))
                    }
                }
            }
        }
        when (val r = canonicalGencat(norm)) {
            is Result.Err -> return r
            is Result.Ok -> {
                val canon = r.value
                if (canon != null) {
                    return Result.Ok(CanonicalClassQuery.GeneralCategory(canon))
                }
            }
        }
        when (val r = canonicalScript(norm)) {
            is Result.Err -> return r
            is Result.Ok -> {
                val canon = r.value
                if (canon != null) {
                    return Result.Ok(CanonicalClassQuery.Script(canon))
                }
            }
        }
        return Result.Err(Error.PropertyNotFound)
    }
}

/**
 * Like ClassQuery, but its parameters have been canonicalized. This also
 * differentiates binary properties from flattened general categories and
 * scripts.
 */
internal sealed class CanonicalClassQuery {
    /**
     * The canonical binary property name.
     */
    internal data class Binary(internal var value: String) : CanonicalClassQuery()

    /**
     * The canonical general category name.
     */
    internal data class GeneralCategory(internal var value: String) : CanonicalClassQuery()

    /**
     * The canonical script name.
     */
    internal data class Script(internal var value: String) : CanonicalClassQuery()

    /**
     * An arbitrary association between property and value, both of which
     * have been canonicalized.
     */
    internal data class ByValue(
        internal var propertyName: String,
        internal var propertyValue: String,
    ) : CanonicalClassQuery()
}

/**
 * Looks up a Unicode class given a query. If one does not exist, then
 * an error is returned.
 */
internal fun cls(query: ClassQuery): Result<ClassUnicode, Error> {
    val q =
        when (val r = query.canonicalize()) {
            is Result.Err -> return r
            is Result.Ok -> r.value
        }
    return when (q) {
        is CanonicalClassQuery.Binary -> boolProperty(q.value)
        is CanonicalClassQuery.GeneralCategory -> gencat(q.value)
        is CanonicalClassQuery.Script -> script(q.value)
        is CanonicalClassQuery.ByValue -> {
            when (q.propertyName) {
                "Age" -> {
                    val setList =
                        when (val r = ages(q.propertyValue)) {
                            is Result.Err -> return r
                            is Result.Ok -> r.value
                        }
                    val out = ClassUnicode.empty()
                    for (set in setList) {
                        out.union(hirClass(set))
                    }
                    Result.Ok(out)
                }
                "Script_Extensions" -> scriptExtension(q.propertyValue)
                "Grapheme_Cluster_Break" -> gcb(q.propertyValue)
                "Sentence_Break" -> sb(q.propertyValue)
                "Word_Break" -> wb(q.propertyValue)
                else -> Result.Err(Error.PropertyNotFound)
            }
        }
    }
}

/**
 * Returns a Unicode aware class for `\w`.
 */
internal fun perlWord(): Result<ClassUnicode, Error> = Result.Ok(hirClass(PERL_WORD))

/**
 * Returns a Unicode aware class for `\s`.
 */
internal fun perlSpace(): Result<ClassUnicode, Error> = Result.Ok(hirClass(WHITE_SPACE))

/**
 * Returns a Unicode aware class for `\d`.
 */
internal fun perlDigit(): Result<ClassUnicode, Error> = Result.Ok(hirClass(DECIMAL_NUMBER))

/**
 * Build a Unicode HIR class from a sequence of Unicode scalar value ranges.
 */
internal fun hirClass(ranges: List<Pair<UInt, UInt>>): ClassUnicode {
    val hirRanges = ranges.map { (s, e) -> ClassUnicodeRange.new(s, e) }
    return ClassUnicode.new(hirRanges)
}

/**
 * Returns true only if the given codepoint is in the `\w` character class.
 */
internal fun isWordCharacter(c: UInt): Result<Boolean, UnicodeWordError> {
    if (c <= 0xFFu && isWordByte(c.toUByte())) {
        return Result.Ok(true)
    }
    val idx =
        PERL_WORD.binarySearch { (start, end) ->
            when {
                start <= c && c <= end -> 0
                start > c -> 1
                else -> -1
            }
        }
    return Result.Ok(idx >= 0)
}

/**
 * A mapping of property values for a specific property.
 */
internal typealias PropertyValues = List<Pair<String, String>>

internal fun canonicalGencat(normalizedValue: String): Result<String?, Error> =
    Result.Ok(
        when (normalizedValue) {
            "any" -> "Any"
            "assigned" -> "Assigned"
            "ascii" -> "ASCII"
            else -> {
                val gencats =
                    when (val r = propertyValues("General_Category")) {
                        is Result.Err -> return r
                        is Result.Ok -> r.value!!
                    }
                canonicalValue(gencats, normalizedValue)
            }
        },
    )

internal fun canonicalScript(normalizedValue: String): Result<String?, Error> {
    val scripts =
        when (val r = propertyValues("Script")) {
            is Result.Err -> return r
            is Result.Ok -> r.value!!
        }
    return Result.Ok(canonicalValue(scripts, normalizedValue))
}

/**
 * Find the canonical property name for the given normalized property name.
 */
internal fun canonicalProp(normalizedName: String): Result<String?, Error> {
    val idx = PROPERTY_NAMES.binarySearch { (n, _) -> n.compareTo(normalizedName) }
    return Result.Ok(if (idx >= 0) PROPERTY_NAMES[idx].second else null)
}

/**
 * Find the canonical property value for the given normalized property
 * value.
 */
internal fun canonicalValue(
    vals: PropertyValues,
    normalizedValue: String,
): String? {
    val idx = vals.binarySearch { (n, _) -> n.compareTo(normalizedValue) }
    return if (idx >= 0) vals[idx].second else null
}

/**
 * Return the table of property values for the given property name.
 */
internal fun propertyValues(canonicalPropertyName: String): Result<PropertyValues?, Error> {
    val idx = PROPERTY_VALUES.binarySearch { (n, _) -> n.compareTo(canonicalPropertyName) }
    return Result.Ok(if (idx >= 0) PROPERTY_VALUES[idx].second else null)
}

internal fun propertySet(
    nameMap: List<Pair<String, Range>>,
    canonical: String,
): Range? {
    val idx = nameMap.binarySearch { (n, _) -> n.compareTo(canonical) }
    return if (idx >= 0) nameMap[idx].second else null
}

/**
 * Returns the Unicode Age sets up to the given revision in chronological order.
 */
internal fun ages(canonicalAge: String): Result<List<Range>, Error> {
    val ageList: List<Pair<String, Range>> =
        listOf(
            Pair("V1_1", V1_1),
            Pair("V2_0", V2_0),
            Pair("V2_1", V2_1),
            Pair("V3_0", V3_0),
            Pair("V3_1", V3_1),
            Pair("V3_2", V3_2),
            Pair("V4_0", V4_0),
            Pair("V4_1", V4_1),
            Pair("V5_0", V5_0),
            Pair("V5_1", V5_1),
            Pair("V5_2", V5_2),
            Pair("V6_0", V6_0),
            Pair("V6_1", V6_1),
            Pair("V6_2", V6_2),
            Pair("V6_3", V6_3),
            Pair("V7_0", V7_0),
            Pair("V8_0", V8_0),
            Pair("V9_0", V9_0),
            Pair("V10_0", V10_0),
            Pair("V11_0", V11_0),
            Pair("V12_0", V12_0),
            Pair("V12_1", V12_1),
            Pair("V13_0", V13_0),
            Pair("V14_0", V14_0),
            Pair("V15_0", V15_0),
            Pair("V15_1", V15_1),
            Pair("V16_0", V16_0),
        )
    check(ageList.size == AGE_BY_NAME.size) { "ages are out of sync" }
    val pos = ageList.indexOfFirst { (age, _) -> canonicalAge == age }
    return if (pos < 0) {
        Result.Err(Error.PropertyValueNotFound)
    } else {
        Result.Ok(ageList.subList(0, pos + 1).map { (_, classes) -> classes })
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given general category.
 */
internal fun gencat(canonicalName: String): Result<ClassUnicode, Error> {
    if (canonicalName == "Decimal_Number") {
        return perlDigit()
    }
    return when (canonicalName) {
        "ASCII" -> Result.Ok(hirClass(listOf(Pair(0x00u, 0x7Fu))))
        "Any" -> Result.Ok(hirClass(listOf(Pair(0x00u, 0x10FFFFu))))
        "Assigned" -> {
            val c =
                when (val r = gencat("Unassigned")) {
                    is Result.Err -> return r
                    is Result.Ok -> r.value
                }
            c.negate()
            Result.Ok(c)
        }
        else -> {
            val range = propertySet(GENCAT_BY_NAME, canonicalName)
            if (range != null) {
                Result.Ok(hirClass(range))
            } else {
                Result.Err(Error.PropertyValueNotFound)
            }
        }
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given script.
 */
internal fun script(canonicalName: String): Result<ClassUnicode, Error> {
    val range = propertySet(SCRIPT_BY_NAME, canonicalName)
    return if (range != null) {
        Result.Ok(hirClass(range))
    } else {
        Result.Err(Error.PropertyValueNotFound)
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given script extension.
 */
internal fun scriptExtension(canonicalName: String): Result<ClassUnicode, Error> {
    val range = propertySet(SCRIPT_EXT_BY_NAME, canonicalName)
    return if (range != null) {
        Result.Ok(hirClass(range))
    } else {
        Result.Err(Error.PropertyValueNotFound)
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given Unicode boolean
 * property.
 */
internal fun boolProperty(canonicalName: String): Result<ClassUnicode, Error> =
    when (canonicalName) {
        "Decimal_Number" -> perlDigit()
        "White_Space" -> perlSpace()
        else -> {
            val range = propertySet(PROP_BOOL_BY_NAME, canonicalName)
            if (range != null) {
                Result.Ok(hirClass(range))
            } else {
                Result.Err(Error.PropertyNotFound)
            }
        }
    }

/**
 * Returns the Unicode HIR class corresponding to the given grapheme cluster
 * break property.
 */
internal fun gcb(canonicalName: String): Result<ClassUnicode, Error> {
    val range = propertySet(GCB_BY_NAME, canonicalName)
    return if (range != null) {
        Result.Ok(hirClass(range))
    } else {
        Result.Err(Error.PropertyValueNotFound)
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given word break
 * property.
 */
internal fun wb(canonicalName: String): Result<ClassUnicode, Error> {
    val range = propertySet(WB_BY_NAME, canonicalName)
    return if (range != null) {
        Result.Ok(hirClass(range))
    } else {
        Result.Err(Error.PropertyValueNotFound)
    }
}

/**
 * Returns the Unicode HIR class corresponding to the given sentence
 * break property.
 */
internal fun sb(canonicalName: String): Result<ClassUnicode, Error> {
    val range = propertySet(SB_BY_NAME, canonicalName)
    return if (range != null) {
        Result.Ok(hirClass(range))
    } else {
        Result.Err(Error.PropertyValueNotFound)
    }
}

/**
 * Like [symbolicNameNormalizeBytes], but operates on a string.
 */
internal fun symbolicNameNormalize(x: String): String {
    val tmp = x.encodeToByteArray().map { it.toUByte() }.toMutableList()
    val normalized = symbolicNameNormalizeBytes(tmp)
    return ByteArray(normalized.size) { normalized[it].toByte() }.decodeToString()
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
internal fun symbolicNameNormalizeBytes(slice: MutableList<UByte>): MutableList<UByte> {
    var start = 0
    var startsWithIs = false
    if (slice.size >= 2) {
        // Ignore any "is" prefix.
        val b0 = slice[0].toInt()
        val b1 = slice[1].toInt()
        startsWithIs = (b0 == 'i'.code || b0 == 'I'.code) && (b1 == 's'.code || b1 == 'S'.code)
        if (startsWithIs) {
            start = 2
        }
    }
    var nextWrite = 0
    for (i in start until slice.size) {
        // To guarantee that the resulting slice is valid UTF-8, we ensure
        // that the slice contains only ASCII bytes and drop non-ASCII bytes.
        val b = slice[i]
        val bi = b.toInt()
        if (bi == ' '.code || bi == '_'.code || bi == '-'.code) {
            continue
        } else if (bi in 'A'.code..'Z'.code) {
            slice[nextWrite] = (bi + ('a'.code - 'A'.code)).toUByte()
            nextWrite += 1
        } else if (bi <= 0x7F) {
            slice[nextWrite] = b
            nextWrite += 1
        }
    }
    // Special case: IsoComment has an `isc` abbreviation. Ignoring the `is`
    // prefix leaves `c`, which would mistakenly normalize to `Other`.
    if (startsWithIs && nextWrite == 1 && slice[0] == 'c'.code.toUByte()) {
        slice[0] = 'i'.code.toUByte()
        slice[1] = 's'.code.toUByte()
        slice[2] = 'c'.code.toUByte()
        nextWrite = 3
    }
    return slice.subList(0, nextWrite)
}
