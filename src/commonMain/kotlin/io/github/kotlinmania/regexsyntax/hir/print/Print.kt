// port-lint: source hir/print.rs
package io.github.kotlinmania.regexsyntax.hir.print

/*
 * This module provides a regular expression printer for [Hir].
 */

import io.github.kotlinmania.regexsyntax.hir.Capture
import io.github.kotlinmania.regexsyntax.hir.Class
import io.github.kotlinmania.regexsyntax.hir.Hir
import io.github.kotlinmania.regexsyntax.hir.HirKind
import io.github.kotlinmania.regexsyntax.hir.Look
import io.github.kotlinmania.regexsyntax.hir.Repetition
import io.github.kotlinmania.regexsyntax.hir.visitor.Visitor
import io.github.kotlinmania.regexsyntax.hir.visitor.visit
import io.github.kotlinmania.regexsyntax.isMetaCharacter

/**
 * A builder for constructing a printer.
 *
 * Note that since a printer doesn't have any configuration knobs, this type
 * remains unexported.
 */
private class PrinterBuilder {
    fun build(): Printer = Printer()
}

/**
 * A printer for a regular expression's high-level intermediate
 * representation.
 *
 * A printer converts a high-level intermediate representation (HIR) to a
 * regular expression pattern string. This particular printer uses constant
 * stack space and heap space proportional to the size of the HIR.
 *
 * Since this printer is only using the HIR, the pattern it prints will
 * likely not resemble the original pattern at all. For example, a pattern
 * like `\pL` will have its entire class written out.
 *
 * The purpose of this printer is to provide a means to mutate an HIR and
 * then build a regular expression from the result of that mutation. (A
 * regex library could provide a constructor from this HIR explicitly, but
 * that creates an unnecessary public coupling between the regex library
 * and this specific HIR representation.)
 */
class Printer {

    /**
     * Print the given [Hir] to the given writer.
     *
     * Typical implementations of [Appendable] that can be used here are a
     * [StringBuilder] or any other character-sink type. The returned
     * [Result] propagates any failure surfaced by the visitor.
     */
    fun print(hir: Hir, wtr: Appendable): Result<Unit> =
        visit(hir, Writer(wtr))

    companion object {
        /** Create a new printer. */
        fun new(): Printer = PrinterBuilder().build()
    }
}

private class Writer(val wtr: Appendable) : Visitor<Unit, Throwable> {
    override fun finish(): Result<Unit> = Result.success(Unit)

    override fun visitPre(hir: Hir): Result<Unit> {
        when (val k = hir.kind()) {
            HirKind.Empty -> {
                // Technically an empty sub-expression could be "printed" by
                // just ignoring it, but in practice, you could have a
                // repetition operator attached to an empty expression, and
                // you really need something in the concrete syntax to make
                // that work as you would expect.
                wtr.append("(?:)")
            }
            // Repetition operators are strictly suffix oriented.
            is HirKind.Repetition -> {}
            is HirKind.Literal -> {
                // See the comment on the Concat and Alternation case below
                // for why we put parens here. Literals are, conceptually,
                // a special case of concatenation where each element is a
                // character. The HIR flattens this into a byte array, but
                // we still need to treat it like a concatenation for
                // correct printing. As a special case, we don't write
                // parens if there is only one character. One character
                // means there is no concat so we don't need parens. Adding
                // parens would still be correct, but we drop them here
                // because it tends to create rather noisy regexes even in
                // simple cases.
                val bytes = k.value.bytes
                val asStr: String? = decodeUtf8OrNull(bytes)
                val len = asStr?.let { codepointCount(it) } ?: bytes.size
                if (len > 1) {
                    wtr.append("(?:")
                }
                if (asStr != null) {
                    var i = 0
                    while (i < asStr.length) {
                        val cp = asStr.codePointAt32(i)
                        writeLiteralChar(wtr, cp)
                        i += if (cp > 0xFFFF) 2 else 1
                    }
                } else {
                    for (b in bytes) {
                        writeLiteralByte(wtr, b)
                    }
                }
                if (len > 1) {
                    wtr.append(")")
                }
            }
            is HirKind.Class -> when (val cls = k.value) {
                is Class.Unicode -> {
                    if (cls.value.ranges().isEmpty()) {
                        wtr.append("[a&&b]")
                        return Result.success(Unit)
                    }
                    wtr.append("[")
                    for (range in cls.value.iter()) {
                        when {
                            range.start() == range.end() -> {
                                writeLiteralChar(wtr, range.start())
                            }
                            range.start() + 1 == range.end() -> {
                                writeLiteralChar(wtr, range.start())
                                writeLiteralChar(wtr, range.end())
                            }
                            else -> {
                                writeLiteralChar(wtr, range.start())
                                wtr.append("-")
                                writeLiteralChar(wtr, range.end())
                            }
                        }
                    }
                    wtr.append("]")
                }
                is Class.Bytes -> {
                    if (cls.value.ranges().isEmpty()) {
                        wtr.append("[a&&b]")
                        return Result.success(Unit)
                    }
                    wtr.append("(?-u:[")
                    for (range in cls.value.iter()) {
                        when {
                            range.start() == range.end() -> {
                                writeLiteralClassByte(wtr, range.start())
                            }
                            (range.start().toInt() and 0xFF) + 1 == (range.end().toInt() and 0xFF) -> {
                                writeLiteralClassByte(wtr, range.start())
                                writeLiteralClassByte(wtr, range.end())
                            }
                            else -> {
                                writeLiteralClassByte(wtr, range.start())
                                wtr.append("-")
                                writeLiteralClassByte(wtr, range.end())
                            }
                        }
                    }
                    wtr.append("])")
                }
            }
            is HirKind.Look -> when (k.value) {
                Look.Start -> wtr.append("\\A")
                Look.End -> wtr.append("\\z")
                Look.StartLF -> wtr.append("(?m:^)")
                Look.EndLF -> wtr.append("(?m:$)")
                Look.StartCRLF -> wtr.append("(?mR:^)")
                Look.EndCRLF -> wtr.append("(?mR:$)")
                Look.WordAscii -> wtr.append("(?-u:\\b)")
                Look.WordAsciiNegate -> wtr.append("(?-u:\\B)")
                Look.WordUnicode -> wtr.append("\\b")
                Look.WordUnicodeNegate -> wtr.append("\\B")
                Look.WordStartAscii -> wtr.append("(?-u:\\b{start})")
                Look.WordEndAscii -> wtr.append("(?-u:\\b{end})")
                Look.WordStartUnicode -> wtr.append("\\b{start}")
                Look.WordEndUnicode -> wtr.append("\\b{end}")
                Look.WordStartHalfAscii -> wtr.append("(?-u:\\b{start-half})")
                Look.WordEndHalfAscii -> wtr.append("(?-u:\\b{end-half})")
                Look.WordStartHalfUnicode -> wtr.append("\\b{start-half}")
                Look.WordEndHalfUnicode -> wtr.append("\\b{end-half}")
            }
            is HirKind.Capture -> {
                val capture: Capture = k.value
                wtr.append("(")
                val name = capture.name
                if (name != null) {
                    wtr.append("?P<").append(name).append(">")
                }
            }
            // Why do this? Wrapping concats and alts in non-capturing
            // groups is not *always* necessary, but is sometimes necessary.
            // For example, concat(a, alt(b, c)) should be written as
            // a(?:b|c) and not ab|c. The former is clearly the intended
            // meaning, but the latter is actually alt(concat(a, b), c).
            //
            // It would be possible to only group these things in cases
            // where it's strictly necessary, but it requires knowing the
            // parent expression. And since this technique is simpler and
            // always correct, we take this route. More to the point, it is
            // a non-goal of an HIR printer to show a nice easy-to-read
            // regex. Indeed, its construction forbids it from doing so.
            // Therefore, inserting extra groups where they aren't
            // necessary is perfectly okay.
            is HirKind.Concat, is HirKind.Alternation -> {
                wtr.append("(?:")
            }
        }
        return Result.success(Unit)
    }

    override fun visitPost(hir: Hir): Result<Unit> {
        when (val k = hir.kind()) {
            // Handled during visitPre.
            HirKind.Empty,
            is HirKind.Literal,
            is HirKind.Class,
            is HirKind.Look -> {}
            is HirKind.Repetition -> {
                val rep: Repetition = k.value
                val min = rep.min
                val max = rep.max
                when {
                    min == 0u && max == 1u -> {
                        wtr.append("?")
                    }
                    min == 0u && max == null -> {
                        wtr.append("*")
                    }
                    min == 1u && max == null -> {
                        wtr.append("+")
                    }
                    min == 1u && max == 1u -> {
                        // a{1} and a{1}? are exactly equivalent to a.
                        return Result.success(Unit)
                    }
                    max == null -> {
                        wtr.append("{").append(min.toString()).append(",}")
                    }
                    min == max -> {
                        wtr.append("{").append(min.toString()).append("}")
                        // a{m} and a{m}? are always exactly equivalent.
                        return Result.success(Unit)
                    }
                    else -> {
                        wtr.append("{").append(min.toString()).append(",")
                            .append(max.toString()).append("}")
                    }
                }
                if (!rep.greedy) {
                    wtr.append("?")
                }
            }
            is HirKind.Capture, is HirKind.Concat, is HirKind.Alternation -> {
                wtr.append(")")
            }
        }
        return Result.success(Unit)
    }

    override fun visitAlternationIn(): Result<Unit> {
        wtr.append("|")
        return Result.success(Unit)
    }

    private fun writeLiteralChar(wtr: Appendable, c: Int) {
        if (c <= 0xFFFF && isMetaCharacter(c.toChar())) {
            wtr.append("\\")
        }
        if (c <= 0xFFFF) {
            wtr.append(c.toChar())
        } else {
            // Surrogate pair encoding for codepoints above the BMP.
            val cp = c - 0x10000
            val hi = 0xD800 + (cp ushr 10)
            val lo = 0xDC00 + (cp and 0x3FF)
            wtr.append(hi.toChar()).append(lo.toChar())
        }
    }

    private fun writeLiteralByte(wtr: Appendable, b: Byte) {
        val v = b.toInt() and 0xFF
        if (v <= 0x7F && !isAsciiControl(v) && !isAsciiWhitespace(v)) {
            writeLiteralChar(wtr, v)
        } else {
            wtr.append("(?-u:\\x").append(twoHexUpper(v)).append(")")
        }
    }

    private fun writeLiteralClassByte(wtr: Appendable, b: Byte) {
        val v = b.toInt() and 0xFF
        if (v <= 0x7F && !isAsciiControl(v) && !isAsciiWhitespace(v)) {
            writeLiteralChar(wtr, v)
        } else {
            wtr.append("\\x").append(twoHexUpper(v))
        }
    }
}

// ---- helpers ----

private fun isAsciiControl(b: Int): Boolean =
    b < 0x20 || b == 0x7F

private fun isAsciiWhitespace(b: Int): Boolean = when (b) {
    0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20 -> true
    else -> false
}

private fun twoHexUpper(b: Int): String {
    val s = b.toString(16).uppercase()
    return if (s.length == 1) "0$s" else s
}

private fun codepointCount(s: String): Int {
    var count = 0
    var i = 0
    while (i < s.length) {
        val c = s[i]
        count++
        i += if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
    }
    return count
}

private fun String.codePointAt32(index: Int): Int {
    val hi = this[index]
    if (hi.isHighSurrogate() && index + 1 < this.length) {
        val lo = this[index + 1]
        if (lo.isLowSurrogate()) {
            return 0x10000 + ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00)
        }
    }
    return hi.code
}

/**
 * Decode the given bytes as UTF-8 and return the resulting [String], or
 * `null` if the bytes are not valid UTF-8.
 */
private fun decodeUtf8OrNull(bytes: ByteArray): String? {
    // Walk the byte sequence and reject invalid UTF-8 leading or
    // continuation bytes. On the first failure, return null so the caller
    // falls back to per-byte rendering.
    var i = 0
    val out = StringBuilder()
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val (cp, len) = when {
            b0 < 0x80 -> Pair(b0, 1)
            b0 in 0xC2..0xDF -> {
                if (i + 1 >= bytes.size) return null
                val b1 = bytes[i + 1].toInt() and 0xFF
                if (b1 and 0xC0 != 0x80) return null
                Pair(((b0 and 0x1F) shl 6) or (b1 and 0x3F), 2)
            }
            b0 in 0xE0..0xEF -> {
                if (i + 2 >= bytes.size) return null
                val b1 = bytes[i + 1].toInt() and 0xFF
                val b2 = bytes[i + 2].toInt() and 0xFF
                if (b1 and 0xC0 != 0x80 || b2 and 0xC0 != 0x80) return null
                val v = ((b0 and 0x0F) shl 12) or ((b1 and 0x3F) shl 6) or (b2 and 0x3F)
                if (v < 0x800 || v in 0xD800..0xDFFF) return null
                Pair(v, 3)
            }
            b0 in 0xF0..0xF4 -> {
                if (i + 3 >= bytes.size) return null
                val b1 = bytes[i + 1].toInt() and 0xFF
                val b2 = bytes[i + 2].toInt() and 0xFF
                val b3 = bytes[i + 3].toInt() and 0xFF
                if (b1 and 0xC0 != 0x80 || b2 and 0xC0 != 0x80 || b3 and 0xC0 != 0x80) return null
                val v = ((b0 and 0x07) shl 18) or ((b1 and 0x3F) shl 12) or
                    ((b2 and 0x3F) shl 6) or (b3 and 0x3F)
                if (v < 0x10000 || v > 0x10FFFF) return null
                Pair(v, 4)
            }
            else -> return null
        }
        if (cp <= 0xFFFF) {
            out.append(cp.toChar())
        } else {
            val n = cp - 0x10000
            out.append((0xD800 + (n ushr 10)).toChar())
            out.append((0xDC00 + (n and 0x3FF)).toChar())
        }
        i += len
    }
    return out.toString()
}
