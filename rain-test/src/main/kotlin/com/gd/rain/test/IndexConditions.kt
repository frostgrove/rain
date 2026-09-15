package com.gd.rain.test

/**
 * Reads the `Index Cond` text PostgreSQL's EXPLAIN prints for a b-tree scan, as far as criterion v1 needs it:
 * which key columns each clause compares and whether the clause is an equality.
 *
 * PostgreSQL deparses the index qualification as one parenthesised expression: a single clause
 * `(col op value)`, or `((clause) AND (clause) …)`. Each clause is `col op value`, `col = ANY (…)`,
 * `col IS NULL`, `col IS NOT NULL`, or `ROW(col, col…) op ROW(…)`, with the index column on the left. A
 * column may be qualified (`books.shelf`) and quoted (`"Weird Col"`). Anything else — an expression on the
 * left, text that does not split into clauses — is not read: [parse] answers `null`, and the criterion
 * refuses the scan rather than guess.
 */
internal object IndexConditions {
    enum class Kind { EQUALITY, RANGE }

    class Clause(
        val text: String,
        val columns: List<String>,
        val kind: Kind,
    )

    private const val OPERATOR_CHARACTERS = "+-*/<>=~!@#%^&|`?"

    fun parse(condition: String): List<Clause>? {
        val content = unwrap(condition.trim()) ?: return null
        val parts = splitTopLevel(content, " AND ") ?: return null
        val clauses = if (parts.size == 1) listOf(content) else parts.map { unwrap(it) ?: return null }
        return clauses.map { clause(it) ?: return null }
    }

    private fun clause(text: String): Clause? {
        val (columns, end) =
            if (text.startsWith("ROW(")) {
                val close = closing(text, 3)
                if (close < 0) return null
                val names = splitTopLevel(text.substring(4, close), ", ")?.map { columnName(it) ?: return null } ?: return null
                names to close + 1
            } else {
                val end = identifierChainEnd(text, 0)
                if (end <= 0) return null
                listOf(columnName(text.substring(0, end)) ?: return null) to end
            }
        if (text.getOrNull(end) != ' ') return null
        val rest = text.substring(end + 1)
        val kind =
            when (rest) {
                "IS NULL" -> {
                    Kind.EQUALITY
                }

                "IS NOT NULL" -> {
                    Kind.RANGE
                }

                else -> {
                    val operator = rest.takeWhile { it in OPERATOR_CHARACTERS }
                    if (operator.isEmpty() || rest.getOrNull(operator.length) != ' ') return null
                    if (operator == "=") Kind.EQUALITY else Kind.RANGE
                }
            }
        return Clause(text, columns, kind)
    }

    /** The last identifier of a qualified column reference that spans all of [reference], unquoted; `null` otherwise. */
    private fun columnName(reference: String): String? {
        if (identifierChainEnd(reference, 0) != reference.length) return null
        var index = 0
        var last = ""
        while (index < reference.length) {
            val end = identifierEnd(reference, index)
            last = unquote(reference.substring(index, end))
            index = end + 1
        }
        return last
    }

    /** The end of `identifier(.identifier)*` starting at [start], or [start] when there is none. */
    private fun identifierChainEnd(
        text: String,
        start: Int,
    ): Int {
        var end = identifierEnd(text, start)
        if (end == start) return start
        while (text.getOrNull(end) == '.') {
            val next = identifierEnd(text, end + 1)
            if (next == end + 1) return start
            end = next
        }
        return end
    }

    private fun identifierEnd(
        text: String,
        start: Int,
    ): Int {
        val first = text.getOrNull(start) ?: return start
        if (first == '"') {
            var index = start + 1
            while (index < text.length) {
                if (text[index] == '"') {
                    if (text.getOrNull(index + 1) == '"') index += 2 else return index + 1
                } else {
                    index++
                }
            }
            return start
        }
        if (!(first.isLetter() || first == '_')) return start
        var index = start + 1
        while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '_' || text[index] == '$')) index++
        return index
    }

    private fun unquote(identifier: String): String =
        if (identifier.startsWith('"')) identifier.substring(1, identifier.length - 1).replace("\"\"", "\"") else identifier

    /** [text] without its enclosing parentheses, when one pair encloses all of it; `null` otherwise. */
    private fun unwrap(text: String): String? =
        if (text.startsWith('(') && closing(text, 0) == text.length - 1) text.substring(1, text.length - 1) else null

    /** The index of the parenthesis closing the one at [open], skipping quoted text; -1 when it is not closed. */
    private fun closing(
        text: String,
        open: Int,
    ): Int {
        var depth = 0
        var index = open
        while (index < text.length) {
            when (text[index]) {
                '\'', '"' -> {
                    index = quotedEnd(text, index) ?: return -1
                    continue
                }

                '(', '[' -> {
                    depth++
                }

                ')', ']' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return -1
    }

    /** [text] split at [separator] where it stands outside quotes and brackets; `null` when the brackets do not balance. */
    private fun splitTopLevel(
        text: String,
        separator: String,
    ): List<String>? {
        val parts = mutableListOf<String>()
        var depth = 0
        var start = 0
        var index = 0
        while (index < text.length) {
            when {
                text[index] == '\'' || text[index] == '"' -> {
                    index = quotedEnd(text, index) ?: return null
                    continue
                }

                text[index] == '(' || text[index] == '[' -> {
                    depth++
                }

                text[index] == ')' || text[index] == ']' -> {
                    depth--
                    if (depth < 0) return null
                }

                depth == 0 && text.startsWith(separator, index) -> {
                    parts += text.substring(start, index)
                    index += separator.length
                    start = index
                    continue
                }
            }
            index++
        }
        if (depth != 0) return null
        parts += text.substring(start)
        return parts
    }

    /** The index after the quoted text starting at [open] (`'…'` or `"…"`, the quote doubled inside); `null` when unterminated. */
    private fun quotedEnd(
        text: String,
        open: Int,
    ): Int? {
        val quote = text[open]
        var index = open + 1
        while (index < text.length) {
            if (text[index] == quote) {
                if (text.getOrNull(index + 1) == quote) index += 2 else return index + 1
            } else {
                index++
            }
        }
        return null
    }
}
