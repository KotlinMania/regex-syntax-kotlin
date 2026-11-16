// port-lint: source debug.rs
@file:OptIn(ExperimentalUnsignedTypes::class)

package io.github.kotlinmania.regexsyntax.debug

import io.github.kotlinmania.regexsyntax.asciiEscapeDefault
import io.github.kotlinmania.regexsyntax.formatHexLower2
import io.github.kotlinmania.regexsyntax.scalarAtUtf16
import io.github.kotlinmania.regexsyntax.scalarEscapeDebug
import io.github.kotlinmania.regexsyntax.scalarLenUtf8
import io.github.kotlinmania.rustlang.core.result.Result
import io.github.kotlinmania.rustlang.core.str.fromUtf8

/**
 * A type that wraps a single byte with a convenient fmt.Debug impl that
 * escapes the byte.
 */
internal open class Byte(
    internal var value: UByte,
) {
    public var field0: UByte
        get() = value
        set(v) {
            value = v
        }

    public open fun fmt(f: StringBuilder) {
        // Special case ASCII space. It's too hard to read otherwise, so
        // put quotes around it. I sometimes wonder whether just '\x20' would
        // be better...
        if (this.value == 32u.toUByte()) {
            f.append("' '")
            return
        }
        // 10 bytes is enough to cover any output from ascii.escapeDefault.
        val bytes = UByteArray(10)
        var len = 0
        for ((i, bRaw) in asciiEscapeDefault(this.value).withIndex()) {
            var b = bRaw
            // capitalize \xab to \xAB
            if (i >= 2 && 97u.toUByte() <= b && b <= 102u.toUByte()) {
                b = (b.toUInt() - 32u).toUByte()
            }
            bytes[len] = b
            len += 1
        }
        f.append(fromUtf8(bytes.slice(0 until len)).unwrap())
    }

    override fun toString(): String = buildString { fmt(this) }

    public companion object {
        public fun typeLayout(): io.github.kotlinmania.rustlang.core.mem.TypeLayout<Byte> =
            object : io.github.kotlinmania.rustlang.core.mem.TypeLayout<Byte> {
                override val alignBytes: Long = 1L
                override val sizeBytes: Long = 1L
                override fun readNative(addr: Long): Byte =
                    Byte(
                        (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.U8).readNative(addr),
                    )
                override fun writeNative(addr: Long, value: Byte) {
                    (io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts.U8).writeNative(addr, value.value)
                }
            }
    }
}

/**
 * A type that provides a human readable debug impl for arbitrary bytes.
 *
 * This generally works best when the bytes are presumed to be mostly UTF-8,
 * but will work for anything.
 *
 * N.B. This is copied nearly verbatim from regex-automata. Sigh.
 */
internal open class Bytes(
    internal var value: List<UByte>,
) {
    public var field0: List<UByte>
        get() = value
        set(v) {
            value = v
        }

    public open fun fmt(f: StringBuilder) {
        f.append('"')
        // This is a sad re-implementation of a similar impl found in bstr.
        var bytes = this.value
        while (true) {
            val result = utf8Decode(bytes) ?: break
            val ch1 =
                when (result) {
                    is Result.Ok -> result.value
                    is Result.Err -> {
                        val byte = result.error
                        f.append("\\x${formatHexLower2(byte.toUInt())}")
                        bytes = bytes.subList(1, bytes.size)
                        continue
                    }
                }
            bytes = bytes.subList(scalarLenUtf8(ch1), bytes.size)
            when (ch1) {
                0u -> f.append("\\0")
                // ASCII control characters except \0, \n, \r, \t
                in 1u..8u, 11u, 12u, in 14u..25u, 127u -> {
                    f.append("\\x${formatHexLower2(ch1)}")
                }
                else -> {
                    f.append(scalarEscapeDebug(ch1))
                }
            }
        }
        f.append('"')
    }

    override fun toString(): String = buildString { fmt(this) }
}

/**
 * Decodes the next UTF-8 encoded codepoint from the given byte slice.
 *
 * If no valid encoding of a codepoint exists at the beginning of the given
 * byte slice, then the first byte is returned instead.
 *
 * This returns `None` if and only if `bytes` is empty.
 */
internal fun utf8Decode(bytes: List<UByte>): Result<UInt, UByte>? {
    fun len(byte: UByte): Int? =
        when {
            byte <= 0x7Fu.toUByte() -> 1
            (byte and 0xC0u.toUByte()) == 0x80u.toUByte() -> null
            byte <= 0xDFu.toUByte() -> 2
            byte <= 0xEFu.toUByte() -> 3
            byte <= 0xF7u.toUByte() -> 4
            else -> null
        }

    if (bytes.isEmpty()) {
        return null
    }
    val len2 =
        len(bytes[0]) ?: return Result.Err(bytes[0])
    if (len2 > bytes.size) {
        return Result.Err(bytes[0])
    }
    if (len2 == 1) {
        return Result.Ok(bytes[0].toUInt())
    }
    return when (val r = fromUtf8(bytes.subList(0, len2))) {
        is Result.Ok -> Result.Ok(scalarAtUtf16(r.value, 0))
        is Result.Err -> Result.Err(bytes[0])
    }
}
