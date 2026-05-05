// port-lint: source src/debug.rs
package io.github.kotlinmania.regexsyntax.debug

/*
 * Copyright (c) The rust-lang regex contributors.
 * Licensed under either of Apache-2.0 OR MIT.
 */

/**
 * A type that wraps a single byte with a convenient debug rendering that
 * escapes the byte.
 */
internal data class Byte(val value: kotlin.Byte) {
    fun fmt(): String = toString()

    override fun toString(): String {
        // Special case ASCII space. It's too hard to read otherwise, so
        // put quotes around it. I sometimes wonder whether just "\u005Cx20" would
        // be better...
        if (value == ' '.code.toByte()) {
            return "' '"
        }
        // 10 bytes is enough to cover any output from asciiEscapeDefault.
        val out = StringBuilder()
        for ((i, raw) in asciiEscapeDefault(value).withIndex()) {
            var b = raw
            // capitalize \xab to \xAB
            if (i >= 2 && b in 'a'.code.toByte()..'f'.code.toByte()) {
                b = (b - 32).toByte()
            }
            out.append(b.toInt().toChar())
        }
        return out.toString()
    }
}

/**
 * A type that provides a human readable debug rendering for arbitrary bytes.
 *
 * This generally works best when the bytes are presumed to be mostly UTF-8,
 * but will work for anything.
 *
 * N.B. This is copied nearly verbatim from regex-automata. Sigh.
 */
internal class Bytes(val bytes: ByteArray) {
    fun fmt(): String = toString()

    override fun toString(): String {
        val out = StringBuilder()
        out.append('"')
        // This is a sad re-implementation of a similar impl found in bstr.
        var current = bytes
        while (true) {
            val result = utf8Decode(current) ?: break
            when (result) {
                is Utf8Decoded.Failed -> {
                    out.append("\\x").append(byteToHex2(result.byte))
                    current = current.copyOfRange(1, current.size)
                }
                is Utf8Decoded.Ok -> {
                    val ch = result.codepoint
                    current = current.copyOfRange(ch.len(), current.size)
                    when (ch) {
                        0 -> out.append("\\0")
                        // ASCII control characters except \0, \n, \r, \t
                        in 0x01..0x08, 0x0b, 0x0c, in 0x0e..0x19, 0x7f -> {
                            out.append("\\x").append(byteToHex2(ch.toByte()))
                        }
                        else -> {
                            out.append(charEscapeDebug(ch))
                        }
                    }
                }
            }
        }
        out.append('"')
        return out.toString()
    }
}

/** Result of attempting to decode a UTF-8 codepoint at the start of a byte slice. */
internal sealed class Utf8Decoded {
    /** Successfully decoded codepoint. */
    class Ok(val codepoint: Int) : Utf8Decoded()
    /** Bad byte; the value is the offending byte. */
    class Failed(val byte: kotlin.Byte) : Utf8Decoded()
}

/**
 * Decodes the next UTF-8 encoded codepoint from the given byte slice.
 *
 * If no valid encoding of a codepoint exists at the beginning of the given
 * byte slice, then the first byte is returned instead.
 *
 * This returns null if and only if `bytes` is empty.
 */
internal fun utf8Decode(bytes: ByteArray): Utf8Decoded? {
    fun len(byte: kotlin.Byte): Int? {
        val b = byte.toInt() and 0xFF
        return when {
            b <= 0x7F -> 1
            (b and 0b1100_0000) == 0b1000_0000 -> null
            b <= 0b1101_1111 -> 2
            b <= 0b1110_1111 -> 3
            b <= 0b1111_0111 -> 4
            else -> null
        }
    }

    if (bytes.isEmpty()) return null
    val first = bytes[0]
    val n = len(first)
    return when {
        n == null -> Utf8Decoded.Failed(first)
        n > bytes.size -> Utf8Decoded.Failed(first)
        n == 1 -> Utf8Decoded.Ok((first.toInt() and 0xFF))
        else -> {
            val s = bytes.copyOfRange(0, n).decodeToString(throwOnInvalidSequence = true)
            try {
                Utf8Decoded.Ok(s.codePointAtCompat(0))
            } catch (_: Throwable) {
                Utf8Decoded.Failed(first)
            }
        }
    }
}

/**
 * The byte length of a Unicode codepoint when encoded as UTF-8.
 *
 * This is the Kotlin equivalent of Rust's `char.lenUtf8()`.
 */
internal fun Int.len(): Int {
    return when {
        this < 0x80 -> 1
        this < 0x800 -> 2
        this < 0x10000 -> 3
        else -> 4
    }
}

/**
 * Replicates Rust's ASCII "escape default" behavior: for any byte, produce the
 * escape sequence that a debug formatter would emit.
 */
internal fun asciiEscapeDefault(b: kotlin.Byte): ByteArray {
    val v = b.toInt() and 0xFF
    return when {
        v == 0x09 -> "\\t".encodeToByteArray()
        v == 0x0a -> "\\n".encodeToByteArray()
        v == 0x0d -> "\\r".encodeToByteArray()
        v == 0x22 -> "\\\"".encodeToByteArray()
        v == 0x27 -> "\\'".encodeToByteArray()
        v == 0x5c -> "\\\\".encodeToByteArray()
        v in 0x20..0x7e -> byteArrayOf(b)
        else -> {
            val hex = "0123456789abcdef"
            byteArrayOf(
                '\\'.code.toByte(),
                'x'.code.toByte(),
                hex[v ushr 4].code.toByte(),
                hex[v and 0xF].code.toByte(),
            )
        }
    }
}

/** Format a byte as two lowercase hex characters. */
internal fun byteToHex2(b: kotlin.Byte): String {
    val v = b.toInt() and 0xFF
    val hex = "0123456789abcdef"
    return charArrayOf(hex[v ushr 4], hex[v and 0xF]).concatToString()
}

/** Replicates Rust's "escape debug" behavior: produce the human-readable escape for a codepoint. */
internal fun charEscapeDebug(codepoint: Int): String {
    return when (codepoint) {
        '\t'.code -> "\\t"
        '\n'.code -> "\\n"
        '\r'.code -> "\\r"
        '\''.code -> "\\'"
        '"'.code -> "\\\""
        '\\'.code -> "\\\\"
        else -> {
            // Render printable characters directly, codepoints above as `\u{...}`.
            if (codepoint in 0x20..0x7e) {
                codepoint.toChar().toString()
            } else {
                "\\u{${codepoint.toString(16)}}"
            }
        }
    }
}

private fun String.codePointAtCompat(index: Int): Int {
    val first = this[index]
    if (first.isHighSurrogate() && index + 1 < this.length) {
        val second = this[index + 1]
        if (second.isLowSurrogate()) {
            return ((first.code - 0xD800) shl 10) + (second.code - 0xDC00) + 0x10000
        }
    }
    return first.code
}
