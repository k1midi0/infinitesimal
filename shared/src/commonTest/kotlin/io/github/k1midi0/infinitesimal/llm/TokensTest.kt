package io.github.k1midi0.infinitesimal.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * token 估算（§6.1、§14.39）。
 *
 * 这一支测试存在的理由只有一条：**不能照抄 dsh 那个「四字符一 token」**。它对 CJK 严重低估（它自己的
 * README 写的），而我们的提示词通篇是中文——照抄的话，用户定的 400K 阈值对中文实际是约 96 万真实
 * token，已经贴着 1M 窗口了。所以这里把那两档密度分别钉住。
 */
class TokensTest {

    @Test
    fun chineseIsMuchDearerPerCharacterThanLatin() {
        val cjk = estimateTokens("一二三四五六七八九十一二三四五六七八九十")
        val latin = estimateTokens("abcdefghijklmnopqrst")

        assertTrue(cjk > latin, "同样 20 个字符，中文要贵得多（$cjk vs $latin）")
    }

    @Test
    fun theDensityForChineseIsAboutSixTenthsPerCharacter() {
        // 接口实测：一个汉字约 0.6 token。一百个汉字因此约 60。
        assertEquals(60, estimateTokens("汉".repeat(100)))
    }

    @Test
    fun latinStaysAtFourCharactersPerToken() {
        // 拉丁那一档 dsh 是对的——花括号、英文键名、坐标数字都吃这一档。
        assertEquals(25, estimateTokens("a".repeat(100)))
    }

    @Test
    fun chinesePunctuationCountsAsChinese() {
        // 「、。」这类中文标点在实际文本里不少，把它们算成"四字符一 token"会少算一截。
        assertEquals(estimateTokens("汉".repeat(10)), estimateTokens("、".repeat(10)))
    }

    @Test
    fun anEmptyStringCostsNothing() {
        assertEquals(0, estimateTokens(""))
        assertEquals(0, estimateTokens("", ))
    }

    @Test
    fun aMessageCostsMoreThanItsTextBecauseOfTheRoleFraming() {
        // 每条消息都带角色标记与分隔符，**它不随正文长短变化**——所以正文估算里看不见它，而几十条消息
        // 加起来不是小数目。
        assertTrue(
            estimateTokens(ChatMessage.user("")) > 0,
            "空消息也要算上角色开销",
        )
    }

    @Test
    fun toolArgumentsAreCounted() {
        val bare = ChatMessage.assistant(content = null, toolCalls = emptyList())
        val withCall = ChatMessage.assistant(
            content = null,
            toolCalls = listOf(
                ToolCall("c1", function = FunctionCall("say", """{"text":"这是一句挺长的话，长到足够被数出来。"}""")),
            ),
        )
        assertTrue(estimateTokens(withCall) > estimateTokens(bare), "工具参数要算——它每一轮都发回去")
    }
}
