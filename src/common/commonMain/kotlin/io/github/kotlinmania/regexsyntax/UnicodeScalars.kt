@file:OptIn(ExperimentalUnsignedTypes::class)

package io.github.kotlinmania.regexsyntax

import io.github.kotlinmania.regexsyntax.unicodetables.propertybool.WHITE_SPACE

internal const val UNICODE_MAX: UInt = 0x10FFFFu

internal fun isValidScalar(cp: UInt): Boolean =
    cp <= UNICODE_MAX && (cp < 0xD800u || cp > 0xDFFFu)

internal fun scalarFromU32(cp: UInt): UInt? =
    if (isValidScalar(cp)) cp else null

internal fun scalarLenUtf8(cp: UInt): Int =
    when {
        cp <= 0x7Fu -> 1
        cp <= 0x7FFu -> 2
        cp <= 0xFFFFu -> 3
        else -> 4
    }

internal fun encodeScalarUtf8(cp: UInt, dst: UByteArray, startIndex: Int = 0): Int {
    val v = cp.toInt()
    return when {
        cp <= 0x7Fu -> {
            dst[startIndex] = v.toUByte()
            1
        }
        cp <= 0x7FFu -> {
            dst[startIndex] = (0xC0 or (v ushr 6)).toUByte()
            dst[startIndex + 1] = (0x80 or (v and 0x3F)).toUByte()
            2
        }
        cp <= 0xFFFFu -> {
            dst[startIndex] = (0xE0 or (v ushr 12)).toUByte()
            dst[startIndex + 1] = (0x80 or ((v ushr 6) and 0x3F)).toUByte()
            dst[startIndex + 2] = (0x80 or (v and 0x3F)).toUByte()
            3
        }
        else -> {
            dst[startIndex] = (0xF0 or (v ushr 18)).toUByte()
            dst[startIndex + 1] = (0x80 or ((v ushr 12) and 0x3F)).toUByte()
            dst[startIndex + 2] = (0x80 or ((v ushr 6) and 0x3F)).toUByte()
            dst[startIndex + 3] = (0x80 or (v and 0x3F)).toUByte()
            4
        }
    }
}

internal fun encodeScalarUtf8List(cp: UInt): List<UByte> {
    val buf = UByteArray(4)
    val len = encodeScalarUtf8(cp, buf)
    return List(len) { buf[it] }
}

internal fun StringBuilder.appendScalar(cp: UInt) {
    val v = cp.toInt()
    if (v <= 0xFFFF) {
        append(v.toChar())
    } else {
        val u = v - 0x10000
        append(((u ushr 10) + 0xD800).toChar())
        append(((u and 0x3FF) + 0xDC00).toChar())
    }
}

internal fun scalarToString(cp: UInt): String =
    buildString { appendScalar(cp) }

internal fun String.unicodeScalars(): List<UInt> {
    val out = mutableListOf<UInt>()
    var i = 0
    while (i < length) {
        val hi = this[i]
        if (hi.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            val lo = this[i + 1]
            val cp = 0x10000 + ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00)
            out.add(cp.toUInt())
            i += 2
        } else {
            out.add(hi.code.toUInt())
            i += 1
        }
    }
    return out
}

internal fun scalarAtUtf16(s: String, index: Int): UInt {
    val hi = s[index]
    if (hi.isHighSurrogate() && index + 1 < s.length && s[index + 1].isLowSurrogate()) {
        val lo = s[index + 1]
        return (0x10000 + ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00)).toUInt()
    }
    return hi.code.toUInt()
}

internal fun scalarUtf16Step(cp: UInt): Int =
    if (cp > 0xFFFFu) 2 else 1

internal fun scalarIsAscii(cp: UInt): Boolean = cp <= 0x7Fu

internal fun scalarIsControl(cp: UInt): Boolean =
    cp <= 0x1Fu || (cp >= 0x7Fu && cp <= 0x9Fu)

internal fun binarySearchRanges(ranges: List<Pair<UInt, UInt>>, c: UInt): Boolean {
    var lo = 0
    var hi = ranges.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val (start, end) = ranges[mid]
        when {
            c < start -> hi = mid - 1
            c > end -> lo = mid + 1
            else -> return true
        }
    }
    return false
}

internal fun scalarIsWhitespace(cp: UInt): Boolean =
    when {
        cp <= 0x7Fu -> cp == 0x20u || (cp >= 0x09u && cp <= 0x0Du)
        else -> binarySearchRanges(WHITE_SPACE, cp)
    }

internal fun formatHexLower2(v: UInt): String {
    val digits = "0123456789abcdef"
    val b = v.toInt() and 0xFF
    return "${digits[b ushr 4]}${digits[b and 0xF]}"
}

internal fun formatHexLower(v: UInt): String =
    v.toString(16)

internal fun formatHexUpper(v: UInt): String =
    v.toString(16).uppercase()

internal fun asciiEscapeDefault(byte: UByte): List<UByte> {
    val b = byte.toInt() and 0xFF
    return when (b) {
        '\t'.code -> listOf('\\'.code.toUByte(), 't'.code.toUByte())
        '\r'.code -> listOf('\\'.code.toUByte(), 'r'.code.toUByte())
        '\n'.code -> listOf('\\'.code.toUByte(), 'n'.code.toUByte())
        '\\'.code -> listOf('\\'.code.toUByte(), '\\'.code.toUByte())
        '\''.code -> listOf('\\'.code.toUByte(), '\''.code.toUByte())
        '"'.code -> listOf('\\'.code.toUByte(), '"'.code.toUByte())
        in 0x20..0x7E -> listOf(byte)
        else -> {
            val hex = formatHexLower2(b.toUInt())
            listOf('\\'.code.toUByte(), 'x'.code.toUByte(), hex[0].code.toUByte(), hex[1].code.toUByte())
        }
    }
}

internal fun scalarEscapeUnicode(cp: UInt): String =
    "\\u{${formatHexLower(cp)}}"

internal fun scalarEscapeDebug(cp: UInt): String =
    when (cp.toInt()) {
        0 -> "\\0"
        '\t'.code -> "\\t"
        '\r'.code -> "\\r"
        '\n'.code -> "\\n"
        '\\'.code -> "\\\\"
        '\''.code -> "\\'"
        '"'.code -> "\\\""
        in 0x20..0x7E -> cp.toInt().toChar().toString()
        else -> {
            if (scalarIsControl(cp)) {
                scalarEscapeUnicode(cp)
            } else {
                scalarToString(cp)
            }
        }
    }

internal fun repeatChar(c: Char, count: Int): String {
    if (count <= 0) return ""
    return buildString(count) {
        repeat(count) { append(c) }
    }
}

/**
 * Error marker used by text formatting routines when appending fails.
 */
public data object FmtError
