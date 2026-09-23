package io.github.k1midi0.infinitesimal.text

import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceSegmenterTest {

    @Test
    fun splitsChineseSentencesKeepingTheirPunctuation() {
        assertEquals(
            listOf("你好呀！", "我在呢。", "今天过得怎么样？"),
            SentenceSegmenter.split("你好呀！我在呢。今天过得怎么样？"),
        )
    }

    @Test
    fun splitsEnglishSentences() {
        assertEquals(
            listOf("Hello there!", "I am here.", "How was your day?"),
            SentenceSegmenter.split("Hello there! I am here. How was your day?"),
        )
    }

    @Test
    fun treatsARunOfTerminatorsAsOneBoundary() {
        assertEquals(
            listOf("真的吗？！", "我不信。"),
            SentenceSegmenter.split("真的吗？！我不信。"),
        )
    }

    @Test
    fun keepsAnEllipsisTogether() {
        assertEquals(
            listOf("等等……", "让我想想。"),
            SentenceSegmenter.split("等等……让我想想。"),
        )
    }

    @Test
    fun absorbsAClosingQuoteIntoTheSentenceItCloses() {
        assertEquals(
            listOf("他说：“你好。”", "然后走了。"),
            SentenceSegmenter.split("他说：“你好。”然后走了。"),
        )
    }

    @Test
    fun emitsATrailingSentenceWithoutPunctuation() {
        assertEquals(
            listOf("第一句。", "这句没有标点"),
            SentenceSegmenter.split("第一句。这句没有标点"),
        )
    }

    @Test
    fun doesNotSplitOnADecimalPoint() {
        assertEquals(
            listOf("圆周率是 3.14 左右。"),
            SentenceSegmenter.split("圆周率是 3.14 左右。"),
        )
    }

    @Test
    fun treatsALineBreakAsABoundaryEvenWithoutPunctuation() {
        assertEquals(
            listOf("第一部分", "第二部分"),
            SentenceSegmenter.split("第一部分\n第二部分"),
        )
    }

    @Test
    fun ignoresSurroundingAndInteriorWhitespace() {
        assertEquals(
            listOf("你好。", "再见。"),
            SentenceSegmenter.split("  你好。   再见。  "),
        )
    }

    @Test
    fun returnsNothingForBlankInput() {
        assertEquals(emptyList(), SentenceSegmenter.split(""))
        assertEquals(emptyList(), SentenceSegmenter.split("   \n  \n "))
    }
}
