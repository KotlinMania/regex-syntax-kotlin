// port-lint: source error.rs
package io.github.kotlinmania.regexsyntax

import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.ast.Error as AstError
import io.github.kotlinmania.regexsyntax.ast.ErrorKind as AstErrorKind
import io.github.kotlinmania.regexsyntax.hir.Error as HirError
import io.github.kotlinmania.regexsyntax.hir.ErrorKind as HirErrorKind
import kotlin.math.max

/**
 * This error type encompasses any error that can be returned by this crate.
 *
 * This error type is "non-exhaustive" upstream. This means that adding a
 * new variant is not considered a breaking change.
 */
sealed class Error {
    /**
     * An error that occurred while translating concrete syntax into abstract
     * syntax (AST).
     */
    data class Parse(val value: AstError) : Error()

    /**
     * An error that occurred while translating abstract syntax into a high
     * level intermediate representation (HIR).
     */
    data class Translate(val value: HirError) : Error()

    override fun toString(): String = when (this) {
        is Parse -> value.toString()
        is Translate -> value.toString()
    }

    fun fmt(): String = toString()

    companion object {
        fun from(err: AstError): Error = Parse(err)

        fun from(err: HirError): Error = Translate(err)

        fun fromAstError(err: AstError): Error = Parse(err)

        fun fromHirError(err: HirError): Error = Translate(err)
    }
}

/**
 * A helper type for formatting nice error messages.
 *
 * This type is responsible for reporting regex parse errors in a nice human
 * readable format. Most of its complexity is from interspersing notational
 * markers pointing out the position where an error occurred.
 */
class Formatter<E : Any>(
    /** The original regex pattern in which the error occurred. */
    val pattern: String,
    /** The error kind. Its [toString] is used for the message. */
    val err: E,
    /** The primary span of the error. */
    val span: Span,
    /**
     * An auxiliary and optional span, in case the error needs to point to
     * two locations (e.g., when reporting a duplicate capture group name).
     */
    val auxSpan: Span?,
) {
    fun fmt(): String = toString()

    override fun toString(): String {
        val out = StringBuilder()
        val spans = Spans.fromFormatter(this)
        if ('\n' in pattern) {
            val divider = repeatChar('~', 79)

            out.append("regex parse error:\n")
            out.append(divider).append('\n')
            val notated = spans.notate()
            out.append(notated)
            out.append(divider).append('\n')
            // If we have error spans that cover multiple lines, then we just
            // note the line numbers.
            if (spans.multiLine.isNotEmpty()) {
                val notes = mutableListOf<String>()
                for (s in spans.multiLine) {
                    notes.add(
                        "on line ${s.start.line} (column ${s.start.column}) " +
                            "through line ${s.end.line} (column ${s.end.column - 1})"
                    )
                }
                out.append(notes.joinToString("\n")).append('\n')
            }
            out.append("error: ").append(err.toString())
        } else {
            out.append("regex parse error:\n")
            val notated = Spans.fromFormatter(this).notate()
            out.append(notated)
            out.append("error: ").append(err.toString())
        }
        return out.toString()
    }

    companion object {
        fun fromAstError(err: AstError): Formatter<AstErrorKind> = Formatter(
            pattern = err.pattern(),
            err = err.kind(),
            span = err.span(),
            auxSpan = err.auxiliarySpan(),
        )

        fun fromHirError(err: HirError): Formatter<HirErrorKind> = Formatter(
            pattern = err.pattern(),
            err = err.kind(),
            span = err.span(),
            auxSpan = null,
        )
    }
}

/**
 * This type represents an arbitrary number of error spans in a way that makes
 * it convenient to notate the regex pattern. ("Notate" means "point out
 * exactly where the error occurred in the regex pattern.")
 *
 * Technically, we can only ever have two spans given our current error
 * structure. However, after toiling with a specific algorithm for handling
 * two spans, it became obvious that an algorithm to handle an arbitrary
 * number of spans was actually much simpler.
 */
private class Spans(
    /** The original regex pattern string. */
    val pattern: String,
    /**
     * The total width that should be used for line numbers. The width is
     * used for left padding the line numbers for alignment.
     *
     * A value of `0` means line numbers should not be displayed. That is,
     * the pattern is itself only one line.
     */
    val lineNumberWidth: Int,
    /**
     * All error spans that occur on a single line. This sequence always has
     * length equivalent to the number of lines in [pattern], where the index
     * of the sequence represents a line number, starting at `0`. The spans
     * in each line are sorted in ascending order.
     */
    val byLine: MutableList<MutableList<Span>>,
    /**
     * All error spans that occur over one or more lines. That is, the start
     * and end position of the span have different line numbers. The spans are
     * sorted in ascending order.
     */
    val multiLine: MutableList<Span>,
) {
    /**
     * Add the given span to this sequence, putting it in the right place.
     */
    fun add(span: Span) {
        // This is grossly inefficient since we sort after each add, but right
        // now, we only ever add two spans at most.
        if (span.isOneLine()) {
            val i = span.start.line - 1 // because lines are 1-indexed
            byLine[i].add(span)
            byLine[i].sort()
        } else {
            multiLine.add(span)
            multiLine.sort()
        }
    }

    /**
     * Notate the pattern string with carets (`^`) pointing at each span
     * location. This only applies to spans that occur within a single line.
     */
    fun notate(): String {
        val notated = StringBuilder()
        for ((i, line) in patternLines(pattern).withIndex()) {
            if (lineNumberWidth > 0) {
                notated.append(leftPadLineNumber(i + 1))
                notated.append(": ")
            } else {
                notated.append("    ")
            }
            notated.append(line)
            notated.append('\n')
            val notes = notateLine(i)
            if (notes != null) {
                notated.append(notes)
                notated.append('\n')
            }
        }
        return notated.toString()
    }

    /**
     * Return notes for the line indexed at `i` (zero-based). If there are no
     * spans for the given line, then `null` is returned. Otherwise, an
     * appropriately space padded string with correctly positioned `^` is
     * returned, accounting for line numbers.
     */
    fun notateLine(i: Int): String? {
        val spans = byLine[i]
        if (spans.isEmpty()) {
            return null
        }
        val notes = StringBuilder()
        repeat(lineNumberPadding()) {
            notes.append(' ')
        }
        var pos = 0
        for (span in spans) {
            for (j in pos until (span.start.column - 1)) {
                notes.append(' ')
                pos += 1
            }
            val noteLen = (span.end.column - span.start.column).coerceAtLeast(0)
            repeat(max(1, noteLen)) {
                notes.append('^')
                pos += 1
            }
        }
        return notes.toString()
    }

    /**
     * Left pad the given line number with spaces such that it is aligned with
     * other line numbers.
     */
    fun leftPadLineNumber(n: Int): String {
        val s = n.toString()
        val pad = lineNumberWidth - s.length
        val result = StringBuilder()
        result.append(repeatChar(' ', pad))
        result.append(s)
        return result.toString()
    }

    /**
     * Return the line number padding beginning at the start of each line of
     * the pattern.
     *
     * If the pattern is only one line, then this returns a fixed padding
     * for visual indentation.
     */
    fun lineNumberPadding(): Int = if (lineNumberWidth == 0) 4 else 2 + lineNumberWidth

    companion object {
        /** Build a sequence of spans from a formatter. */
        fun <E : Any> fromFormatter(fmter: Formatter<E>): Spans {
            var lineCount = patternLines(fmter.pattern).size
            // If the pattern ends with a `\n` literal, then our line count is
            // off by one, since a span can occur immediately after the last
            // `\n`, which is considered to be an additional line.
            if (fmter.pattern.endsWith('\n')) {
                lineCount += 1
            }
            val lineNumberWidth = if (lineCount <= 1) 0 else lineCount.toString().length
            val byLine = MutableList(lineCount) { mutableListOf<Span>() }
            val spans = Spans(
                pattern = fmter.pattern,
                lineNumberWidth = lineNumberWidth,
                byLine = byLine,
                multiLine = mutableListOf(),
            )
            spans.add(fmter.span)
            val aux = fmter.auxSpan
            if (aux != null) {
                spans.add(aux)
            }
            return spans
        }
    }
}

/**
 * Split the pattern into lines using `\n` as the terminator. Each terminating
 * `\n` (and a preceding `\r`) is stripped, and a trailing `\n` does not
 * produce an extra empty line.
 */
private fun patternLines(pattern: String): List<String> {
    if (pattern.isEmpty()) return listOf("")
    val lines = mutableListOf<String>()
    var start = 0
    var i = 0
    while (i < pattern.length) {
        if (pattern[i] == '\n') {
            var end = i
            if (end > start && pattern[end - 1] == '\r') {
                end -= 1
            }
            lines.add(pattern.substring(start, end))
            start = i + 1
        }
        i += 1
    }
    if (start < pattern.length) {
        lines.add(pattern.substring(start))
    }
    return lines
}

private fun repeatChar(c: Char, count: Int): String {
    if (count <= 0) return ""
    val sb = StringBuilder(count)
    repeat(count) { sb.append(c) }
    return sb.toString()
}
