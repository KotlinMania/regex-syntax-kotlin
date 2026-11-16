// port-lint: source error.rs
package io.github.kotlinmania.regexsyntax.error

import io.github.kotlinmania.regexsyntax.ast.Error as AstError
import io.github.kotlinmania.regexsyntax.ast.ErrorKind as AstErrorKind
import io.github.kotlinmania.regexsyntax.ast.Span
import io.github.kotlinmania.regexsyntax.hir.Error as HirError
import io.github.kotlinmania.regexsyntax.hir.ErrorKind as HirErrorKind
import io.github.kotlinmania.regexsyntax.repeatChar

/**
 * This error type encompasses any error that can be returned by this crate.
 *
 * This error type is marked as `nonExhaustive`. This means that adding a
 * new variant is not considered a breaking change.
 */
public sealed class Error : Exception() {
    /**
     * An error that occurred while translating concrete syntax into abstract
     * syntax (AST).
     */
    public data class Parse(public var value: AstError) : Error()

    /**
     * An error that occurred while translating abstract syntax into a high
     * level intermediate representation (HIR).
     */
    public data class Translate(public var value: HirError) : Error()

    override val message: String
        get() = toString()

    public open fun fmt(f: StringBuilder) {
        when (this) {
            is Parse -> this.value.fmt(f)
            is Translate -> this.value.fmt(f)
        }
    }

    override fun toString(): String = buildString { fmt(this) }

    public companion object {
        public fun from(err: AstError): Error = Parse(err)

        public fun from(err: HirError): Error = Translate(err)
    }
}

/**
 * A helper type for formatting nice error messages.
 *
 * This type is responsible for reporting regex parse errors in a nice human
 * readable format. Most of its complexity is from interspersing notational
 * markers pointing out the position where an error occurred.
 */
internal class Formatter<E>(
    /**
     * The original regex pattern in which the error occurred.
     */
    internal var pattern: String,
    /**
     * The error kind. It must implement formatting via toString.
     */
    internal var err: E,
    /**
     * The primary span of the error.
     */
    internal var span: Span,
    /**
     * An auxiliary and optional span, in case the error needs to point to
     * two locations (e.g., when reporting a duplicate capture group name).
     */
    internal var auxSpan: Span?,
) {
    internal fun fmt(f: StringBuilder) {
        val spans = Spans.fromFormatter(this)
        if (this.pattern.contains('\n')) {
            val divider = repeatChar('~', 79)
            f.appendLine("regex parse error:")
            f.appendLine(divider)
            f.append(spans.notate())
            f.appendLine(divider)
            // If we have error spans that cover multiple lines, then we just
            // note the line numbers.
            if (spans.multiLine.isNotEmpty()) {
                val notes = mutableListOf<String>()
                for (span in spans.multiLine) {
                    notes.add(
                        "on line ${span.start.line} " +
                            "(column ${span.start.column}) through line ${span.end.line} " +
                            "(column ${span.end.column - 1})",
                    )
                }
                f.appendLine(notes.joinToString("\n"))
            }
            f.append("error: ${this.err}")
        } else {
            f.appendLine("regex parse error:")
            f.append(spans.notate())
            f.append("error: ${this.err}")
        }
    }

    override fun toString(): String = buildString { fmt(this) }
}

internal fun fromFormatterErrorKind(err: AstError): Formatter<AstErrorKind> =
    Formatter(
        pattern = err.pattern(),
        err = err.kind(),
        span = err.span(),
        auxSpan = err.auxiliarySpan(),
    )

internal fun fromFormatterErrorKind(err: HirError): Formatter<HirErrorKind> =
    Formatter(
        pattern = err.pattern(),
        err = err.kind(),
        span = err.span(),
        auxSpan = null,
    )

private fun rustLines(s: String): List<String> {
    if (s.isEmpty()) {
        return emptyList()
    }
    val lines = s.lines()
    return if (s.endsWith('\n')) lines.dropLast(1) else lines
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
internal class Spans(
    /**
     * The original regex pattern string.
     */
    internal var pattern: String,
    /**
     * The total width that should be used for line numbers. The width is
     * used for left padding the line numbers for alignment.
     *
     * A value of `0` means line numbers should not be displayed. That is,
     * the pattern is itself only one line.
     */
    internal var lineNumberWidth: Int,
    /**
     * All error spans that occur on a single line. This sequence always has
     * length equivalent to the number of lines in `pattern`, where the index
     * of the sequence represents a line number, starting at `0`. The spans
     * in each line are sorted in ascending order.
     */
    internal var byLine: MutableList<MutableList<Span>>,
    /**
     * All error spans that occur over one or more lines. That is, the start
     * and end position of the span have different line numbers. The spans are
     * sorted in ascending order.
     */
    internal var multiLine: MutableList<Span>,
) {
    /**
     * Add the given span to this sequence, putting it in the right place.
     */
    internal fun add(span: Span) {
        // This is grossly inefficient since we sort after each add, but right
        // now, we only ever add two spans at most.
        if (span.isOneLine()) {
            // Subtract 1 because lines are 1-indexed.
            val i = (span.start.line - 1).coerceAtLeast(0)
            while (this.byLine.size <= i) {
                this.byLine.add(mutableListOf())
            }
            this.byLine[i].add(span)
            this.byLine[i].sortWith(compareBy({ it.start }, { it.end }))
        } else {
            this.multiLine.add(span)
            this.multiLine.sortWith(compareBy({ it.start }, { it.end }))
        }
    }

    /**
     * Notate the pattern string with carets (`^`) pointing at each span
     * location. This only applies to spans that occur within a single line.
     */
    internal fun notate(): String {
        val notated = StringBuilder()
        for ((i, line) in rustLines(this.pattern).withIndex()) {
            if (this.lineNumberWidth > 0) {
                notated.append(this.leftPadLineNumber(i + 1))
                notated.append(": ")
            } else {
                notated.append("    ")
            }
            notated.append(line)
            notated.append('\n')
            val notes = this.notateLine(i)
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
    internal fun notateLine(i: Int): String? {
        val spans = this.byLine[i]
        if (spans.isEmpty()) {
            return null
        }
        val notes = StringBuilder()
        repeat(this.lineNumberPadding()) {
            notes.append(' ')
        }
        var pos = 0
        for (span in spans) {
            for (unused in pos until (span.start.column - 1)) {
                notes.append(' ')
                pos += 1
            }
            val noteLen = (span.end.column - span.start.column).coerceAtLeast(0)
            repeat(maxOf(1, noteLen)) {
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
    internal fun leftPadLineNumber(n: Int): String {
        val nStr = n.toString()
        val pad = (this.lineNumberWidth - nStr.length).coerceAtLeast(0)
        return repeatChar(' ', pad) + nStr
    }

    /**
     * Return the line number padding beginning at the start of each line of
     * the pattern.
     *
     * If the pattern is only one line, then this returns a fixed padding
     * for visual indentation.
     */
    internal fun lineNumberPadding(): Int =
        if (this.lineNumberWidth == 0) {
            4
        } else {
            2 + this.lineNumberWidth
        }

    internal companion object {
        /**
         * Build a sequence of spans from a formatter.
         */
        internal fun <E> fromFormatter(fmter: Formatter<E>): Spans {
            var lineCount = rustLines(fmter.pattern).size
            // If the pattern ends with a `\n` literal, then our line count is
            // off by one, since a span can occur immediately after the last `\n`,
            // which is considered to be an additional line.
            if (fmter.pattern.endsWith('\n')) {
                lineCount += 1
            }
            val lineNumberWidth =
                if (lineCount <= 1) {
                    0
                } else {
                    lineCount.toString().length
                }
            val spans =
                Spans(
                    pattern = fmter.pattern,
                    lineNumberWidth = lineNumberWidth,
                    byLine = MutableList(lineCount) { mutableListOf() },
                    multiLine = mutableListOf(),
                )
            spans.add(fmter.span.copy())
            val aux = fmter.auxSpan
            if (aux != null) {
                spans.add(aux.copy())
            }
            return spans
        }
    }
}
