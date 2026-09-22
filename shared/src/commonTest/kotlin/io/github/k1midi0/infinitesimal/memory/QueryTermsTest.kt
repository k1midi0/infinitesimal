package io.github.k1midi0.infinitesimal.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryTermsTest {

    /**
     * The case this whole file exists for. Written the way Chinese normally is —
     * without spaces — the sentence is one token under whitespace splitting, and
     * `LIKE '%我今天喝了无糖乌龙茶%'` reaches nothing.
     */
    @Test
    fun breaksASentenceWrittenWithoutSpacesIntoSomethingMatchable() {
        assertEquals(
            listOf("我今", "今天", "天喝", "喝了", "了无", "无糖", "糖乌", "乌龙", "龙茶"),
            queryTerms("我今天喝了无糖乌龙茶"),
        )
    }

    @Test
    fun keepsLatinWordsWhole() {
        assertEquals(listOf("tea", "time"), queryTerms("tea time"))
    }

    @Test
    fun splitsOnChinesePunctuation() {
        assertEquals(listOf("无糖", "乌龙"), queryTerms("无糖，乌龙"))
    }

    @Test
    fun keepsASingleChineseCharacter() {
        assertEquals(listOf("茶"), queryTerms("茶"))
    }

    @Test
    fun handlesMixedTextRunByRun() {
        assertEquals(listOf("乌龙", "龙茶", "tea"), queryTerms("乌龙茶 tea"))
    }

    @Test
    fun returnsNothingForBlankInput() {
        assertTrue(queryTerms("   ").isEmpty())
    }

    @Test
    fun keepsOnlyOneCopyOfARepeatedTerm() {
        assertEquals(listOf("乌龙"), queryTerms("乌龙 乌龙"))
    }

    @Test
    fun stopsAtTheTermBudget() {
        // 200 distinct characters give 199 distinct bigrams, which is past the cap.
        val long = (0 until 200).joinToString("") { index ->
            ('\u4E00'.code + index).toChar().toString()
        }

        assertEquals(MAX_QUERY_TERMS, queryTerms(long).size)
    }
}
