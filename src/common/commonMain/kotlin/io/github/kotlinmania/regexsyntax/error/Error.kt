// port-lint: source error.rs
package io.github.kotlinmania.regexsyntax.error

/**
 * This error type encompasses any error that can be returned by this crate.
 *
 * This error type is marked as `nonExhaustive`. This means that adding a
 * new variant is not considered a breaking change.
 */
public sealed class Error : From<Error>, Error {
    /**
     * An error that occurred while translating concrete syntax into abstract
     * syntax (AST).
     */
    public data class Parse(public var value: Error) : Error()
    /**
     * An error that occurred while translating abstract syntax into a high
     * level intermediate representation (HIR).
     */
    public data class Translate(public var value: Error) : Error()

    public fun rustEquals(other: Error): Boolean = this == other

    public open fun fmt(f: StringBuilder) {
        when (this) {
            is Error.Parse -> {
                val (x) = this
                x.fmt(f)
            }
            is Error.Translate -> {
                val (x1) = this
                x1.fmt(f)
            }
        }
    }

    public companion object {
        public fun from(err: Error): Error = Error.Parse(err)

        public fun fromError(err: Error): Error = Error.Translate(err)
    }
}

/**
 * A helper type for formatting nice error messages.
 *
 * This type is responsible for reporting regex parse errors in a nice human
 * readable format. Most of its complexity is from interspersing notational
 * markers pointing out the position where an error occurred.
 */
public open class Formatter<E>(
    /**
     * The original regex pattern in which the error occurred.
     */
    public var pattern: String,
    /**
     * The error kind. It must impl fmt.Display.
     */
    public var err: E,
    /**
     * The primary span of the error.
     */
    public var span: Span,
    /**
     * An auxiliary and optional span, in case the error needs to point to
     * two locations (e.g., when reporting a duplicate capture group name).
     */
    public var auxSpan: Span?,
) {
    public open fun fmt(f: StringBuilder) {
        val spans = Spans.fromFormatter(this)
        if (this.pattern.contains('\n')) {
            val divider = repeatChar('~', 79)
            f.appendLine("regex parse error:").getOrElse { return Result.failure(it) }
            f.appendLine("$divider").getOrElse { return Result.failure(it) }
            val notated = spans.notate()
            f.append("$notated").getOrElse { return Result.failure(it) }
            f.appendLine("$divider").getOrElse { return Result.failure(it) }
            // If we have error spans that cover multiple lines, then we just
            // note the line numbers.
            if (!spans.multiLine.isEmpty()) {
                val notes = mutableListOf()
                for (span in spans.multiLine) {
                    notes.add("on line ${span.start.line} (column ${span.start.column}) through line ${span.end.line} (column ${span.end.column - 1})")
                }
                f.appendLine((notes.join("\n")).toString()).getOrElse { return Result.failure(it) }
            }
            f.append("error: ${this.err}").getOrElse { return Result.failure(it) }
        } else {
            f.appendLine("regex parse error:").getOrElse { return Result.failure(it) }
            f.append("$notated").getOrElse { return Result.failure(it) }
            f.append("error: ${this.err}").getOrElse { return Result.failure(it) }
        }
        Result.success(Unit)
    }
}

public fun fromFormatterErrorKind(err: Error): Formatter<ErrorKind> = Formatter(pattern = err.pattern(), err = err.kind(), span = err.span(), auxSpan = err.auxiliarySpan())

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
public open class Spans(
    /**
     * The original regex pattern string.
     */
    public var pattern: String,
    /**
     * The total width that should be used for line numbers. The width is
     * used for left padding the line numbers for alignment.
     *
     * A value of `0` means line numbers should not be displayed. That is,
     * the pattern is itself only one line.
     */
    public var lineNumberWidth: Int,
    /**
     * All error spans that occur on a single line. This sequence always has
     * length equivalent to the number of lines in `pattern`, where the index
     * of the sequence represents a line number, starting at `0`. The spans
     * in each line are sorted in ascending order.
     */
    public var byLine: List<List<Span>>,
    /**
     * All error spans that occur over one or more lines. That is, the start
     * and end position of the span have different line numbers. The spans are
     * sorted in ascending order.
     */
    public var multiLine: MutableList<Span>,
) {
    /**
     * Add the given span to this sequence, putting it in the right place.
     */
    public open fun add(span: Span) {
        // This is grossly inefficient since we sort after each add, but right
        // now, we only ever add two spans at most.
        if (span.isOneLine()) {
            val i = span.start.line - 1
            // because lines are 1-indexed
            this.byLine[i].push(span)
            this.byLine[i].sort()
        } else {
            this.multiLine.add(span)
            this.multiLine.sort()
        }
    }

    /**
     * Notate the pattern string with carets (`^`) pointing at each span
     * location. This only applies to spans that occur within a single line.
     */
    public open fun notate(): String {
        val notated = String.new()
        for ((i, line) in this.pattern.lines().enumerate()) {
            if (this.lineNumberWidth > 0) {
                notated.pushStr(this.leftPadLineNumber(i + 1))
                notated.pushStr(": ")
            } else {
                notated.pushStr("    ")
            }
            notated.pushStr(line)
            notated.push('\n')
            when (val matchVal = this.notateLine(i)) {
                null -> {}
                else -> {
                    val notes = matchVal
                    notated.pushStr(notes)
                    notated.push('\n')
                }
            }
        }
        return notated
    }

    /**
     * Return notes for the line indexed at `i` (zero-based). If there are no
     * spans for the given line, then `None` is returned. Otherwise, an
     * appropriately space padded string with correctly positioned `^` is
     * returned, accounting for line numbers.
     */
    public open fun notateLine(i: Int): String? {
        val spans = this.byLine[i]
        if (spans.isEmpty()) {
            return null
        }
        val notes = String.new()
        for (unused in 0 until this.lineNumberPadding()) {
            notes.push(' ')
        }
        var pos = 0
        for (span in spans) {
            for (unused1 in pos until (span.start.column - 1)) {
                notes.push(' ')
                pos += 1
            }
            val noteLen = span.end.column.saturatingSub(span.start.column)
            for (unused2 in 0 until max(1, noteLen)) {
                notes.push('^')
                pos += 1
            }
        }
        return notes
    }

    /**
     * Left pad the given line number with spaces such that it is aligned with
     * other line numbers.
     */
    public open fun leftPadLineNumber(n: Int): String {
        val n1 = n.toString()
        val pad = (((this.lineNumberWidth).toLong() - (n1.len()).toLong()).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt())!!
        val result = repeatChar(' ', pad)
        result.pushStr(n1)
        return result
    }

    /**
     * Return the line number padding beginning at the start of each line of
     * the pattern.
     *
     * If the pattern is only one line, then this returns a fixed padding
     * for visual indentation.
     */
    public open fun lineNumberPadding(): Int =
        if (this.lineNumberWidth == 0) {
            4
        } else {
            2 + this.lineNumberWidth
        }

    public companion object {
        /**
         * Build a sequence of spans from a formatter.
         */
        public fun <E> fromFormatter(fmter: Formatter<E>): Spans {
            var lineCount = fmter.pattern.lines().count()
            // If the pattern ends with a `\n` literal, then our line count is
            // off by one, since a span can occur immediately after the last `\n`,
            // which is consider to be an additional line.
            if (fmter.pattern.endsWith('\n')) {
                lineCount += 1
            }
            val lineNumberWidth =
                if (lineCount <= 1) {
                    0
                } else {
                    lineCount.toString().len()
                }
            val spans = Spans(pattern = fmter.pattern, lineNumberWidth = lineNumberWidth, byLine = mutableListOf(mutableListOf(), lineCount), multiLine = mutableListOf())
            spans.add(fmter.span.clone())
            when (val matchVal = fmter.auxSpan) {
                null -> {}
                else -> {
                    val span = matchVal
                    spans.add(span.clone())
                }
            }
            return spans
        }
    }
}

public fun repeatChar(c: Char, count: Int): String = repeat(c).take(count).collect()
