// port-lint: source debug.rs
package io.github.kotlinmania.regexsyntax.debug

import io.github.kotlinmania.rustlang.core.mem.PrimitiveLayouts
import io.github.kotlinmania.rustlang.core.mem.TypeLayout

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
        if (this.value == 32u) {
            return f.append("' '")
        }
        // 10 bytes is enough to cover any output from ascii.escapeDefault.
        val bytes = List(10) { 0u.toUByte() }
        var len = 0
        for ((i, b) in escapeDefault(this.value).enumerate()) {
            // capitalize \xab to \xAB
            if (i >= 2 && 97u <= b && b <= 102u) {
                b -= 32
            }
            bytes[len] = b
            len += 1
        }
        f.append("${String.fromUtf8(bytes[0 until len]).unwrap()}")
    }

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
        f.append("\"").getOrElse { return Result.failure(it) }
        // This is a sad re-implementation of a similar impl found in bstr.
        var bytes = this.value
        while (true) {
            val matchVal = utf8Decode(bytes)
            if (matchVal == null) break
            val result = matchVal
            val ch1 =
                if (result.isSuccess) {
                    val ch = result.getOrThrow()
                    ch
                } else {
                    val byte = result.exceptionOrNull()!!
                    f.append("\\x$byte").getOrElse { return Result.failure(it) }
                    bytes = bytes[1 until bytes.size]
                    continue
                }
            bytes = bytes[ch1.lenUtf8() until bytes.size]
            when (ch1) {
                '\u0000' -> { f.append("\\0").getOrElse { return Result.failure(it) } }
                // ASCII control characters except \0, \n, \r, \t
                in '\u0001'..'\b', '\u000b', '\u000c', in '\u000e'..'\u0019', '\u007f' -> {
                    f.append("\\x${UInt.from(ch1)}").getOrElse { return Result.failure(it) }
                }
                '\n', '\r', '\t' -> {
                    f.append("${ch1.escapeDebug()}").getOrElse { return Result.failure(it) }
                }
            }
        }
        f.append("\"").getOrElse { return Result.failure(it) }
        Result.success(Unit)
    }
}

/**
 * Decodes the next UTF-8 encoded codepoint from the given byte slice.
 *
 * If no valid encoding of a codepoint exists at the beginning of the given
 * byte slice, then the first byte is returned instead.
 *
 * This returns `None` if and only if `bytes` is empty.
 */
internal fun utf8Decode(bytes: List<UByte>): Result<Char>? {
    fun len(byte: UByte): Int? {
        return if (byte <= 0x7Fu.toUByte()) {
            return 1
        } else if (byte and 0xC0u.toUByte() == 0x80u.toUByte()) {
            return null
        } else if (byte <= 0xDFu.toUByte()) {
            2
        } else if (byte <= 0xEFu.toUByte()) {
            3
        } else if (byte <= 0xF7u.toUByte()) {
            4
        } else {
            null
        }
    }
    if (bytes.isEmpty()) {
        return null
    }
    val len2 =
        run {
            val matchVal = len(bytes[0])
            when {
                matchVal == null -> { return Result.failure(bytes[0]) }
                matchVal != null && matchVal > bytes.size -> {
                    val len = matchVal
                    return Result.failure(bytes[0])
                }
                matchVal != null && matchVal == 1 -> { return Result.success(Char.from(bytes[0])) }
                else -> {
                    val len1 = matchVal
                    len1
                }
            }
        }
    return run {
        val matchVal1 = String.fromUtf8(bytes[0 until len2])
        if (matchVal1.isSuccess) {
            val s = matchVal1.getOrThrow()
            Result.success(s.chars().next()!!)
        } else {
            Result.failure(bytes[0])
        }
    }
}
