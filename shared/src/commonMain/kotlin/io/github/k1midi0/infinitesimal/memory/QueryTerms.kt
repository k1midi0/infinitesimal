package io.github.k1midi0.infinitesimal.memory

/**
 * Turns what the person typed into the terms matched against the store.
 *
 * Splitting on whitespace and punctuation is not enough for Chinese, which is
 * normally written without spaces. A whole sentence becomes one term, and
 * `LIKE '%我今天喝了无糖乌龙茶%'` matches nothing even while the store holds
 * "喜欢无糖乌龙茶" — the memory is right there and the lookup never reaches it.
 * Keyword matching has to be handed units of the right size before it works at all.
 *
 * Character bigrams are those units. They are a crude segmentation, deliberately:
 * a false positive costs one extra row in a list that is ranked and truncated
 * anyway, while a false negative is a memory that silently never comes back. Of
 * the two ways to be wrong, only one of them is invisible.
 *
 * Latin text stays as whole words. Splitting "tea" into "te" and "ea" would match
 * half the store to no purpose.
 */
internal fun queryTerms(query: String, maxTerms: Int = MAX_QUERY_TERMS): List<String> {
    val terms = LinkedHashSet<String>()

    for (token in query.split(*QUERY_SEPARATORS)) {
        if (token.isEmpty()) continue

        var index = 0
        while (index < token.length) {
            val start = index
            val isCjkRun = isCjk(token[index])
            while (index < token.length && isCjk(token[index]) == isCjkRun) index += 1
            val run = token.substring(start, index)

            if (!isCjkRun) {
                val word = run.trim()
                if (word.isNotEmpty()) terms += word
            } else if (run.length == 1) {
                // A single character has no bigram, and dropping it would lose a
                // one-character query entirely.
                terms += run
            } else {
                for (offset in 0 until run.length - 1) {
                    terms += run.substring(offset, offset + 2)
                }
            }

            if (terms.size >= maxTerms) return terms.take(maxTerms)
        }
    }

    return terms.take(maxTerms)
}

/**
 * Wraps a term for use as a LIKE pattern, escaping the wildcards.
 *
 * Without this, searching for `%` matches every row in the table, and a search for
 * something containing an underscore silently over-matches. The backslash has to be
 * escaped first, or it would escape the escapes.
 *
 * It lives beside [queryTerms] rather than inside whichever repository happens to search
 * first, because the two are one rule: a query becomes terms, and a term becomes a pattern.
 * A second copy of this would be a second set of escaping behaviour to get wrong, and the
 * way it would go wrong is a search that quietly returns everything.
 */
internal fun likePattern(term: String): String {
    val escaped = term
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")
    return "%$escaped%"
}

/**
 * Upper bound on the database round trips a single lookup may make.
 *
 * One bigram per adjacent character pair means the cost of a lookup would otherwise
 * scale with how much the person typed. Hitting the bound drops the remaining
 * terms, so it can only lose matches towards the end of a long message — the
 * beginning, which is where the subject usually is, has already been seen.
 */
internal const val MAX_QUERY_TERMS = 64

private val QUERY_SEPARATORS = charArrayOf(
    ' ', '\t', '\n', '\r', '|',
    ',', '，', '、', ';', '；',
    '.', '。', '!', '！', '?', '？', ':', '：',
)

private fun isCjk(character: Char): Boolean {
    val code = character.code
    return code in 0x4E00..0x9FFF || // CJK Unified Ideographs
        code in 0x3400..0x4DBF || // Extension A
        code in 0xF900..0xFAFF // Compatibility Ideographs
}
