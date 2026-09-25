package io.github.k1midi0.infinitesimal.npc

import io.github.k1midi0.infinitesimal.config.ContextSettings
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 累积的对话历史（§6.1、§14.39）。
 *
 * 最该钉住的是**锚与增量不许重复计数**。这个错很安静：估算虚高，阈值提前触发，而症状只是"它怎么这么
 * 早就开始压了"——没有任何东西会报。一个 1.5K 的 prompt 在 3K 的请求上被算两次，就是五成的虚高。
 */
class ConversationTest {

    private fun system() = ChatMessage.system("你扮演这个世界里的一个人。")
    private fun user(text: String) = ChatMessage.user(text)

    @Test
    fun theRequestIsSystemThenHistoryThenThisTurn() {
        val conversation = Conversation("dwarf")
        conversation.commit(listOf(user("上一轮")))
        conversation.commit(listOf(user("上上轮")))

        val messages = conversation.request(system(), listOf(user("这一轮")))

        assertEquals("system", messages.first().role)
        assertEquals("这一轮", messages.last().content)
        assertEquals(4, messages.size, "system + 两条历史 + 这一条")
    }

    @Test
    fun theHistoryOnlyGrows() {
        val conversation = Conversation("dwarf")
        conversation.commit(listOf(user("一")))
        assertEquals(1, conversation.size)
        conversation.commit(listOf(user("二"), user("三")))
        assertEquals(3, conversation.size)
    }

    @Test
    fun whatTheAnchorAlreadyCoveredIsNotCountedAgain() {
        val conversation = Conversation("dwarf")
        val fresh = listOf(user("你好啊。"))

        conversation.request(system(), fresh)
        // 提供方说这一次的 prompt 是 1000 token——**那句话已经算在里面了**。
        conversation.anchor(promptTokens = 1000, covered = fresh.size)
        conversation.commit(fresh)

        // 所以下一轮估算必须还是 1000。再加一遍就是那个安静的虚高。
        assertEquals(1000, conversation.estimate(system(), emptyList()))
    }

    @Test
    fun whateverTheAnchorDidNotCoverIsAdded() {
        val conversation = Conversation("dwarf")
        val fresh = listOf(user("你好啊。"))
        conversation.request(system(), fresh)
        conversation.anchor(promptTokens = 1000, covered = fresh.size)
        conversation.commit(fresh)

        // 锚之后模型回了一句——**它没被算进那个 1000**，所以要加上。
        conversation.commit(listOf(ChatMessage.assistant(content = "……嗯。")))

        assertTrue(
            conversation.estimate(system(), emptyList()) > 1000,
            "自锚点以来的增量要算进去，实得 ${conversation.estimate(system(), emptyList())}",
        )
    }

    @Test
    fun beforeAnyAnchorItEstimatesTheWholeThing() {
        val conversation = Conversation("dwarf")
        conversation.commit(listOf(user("汉".repeat(100))))

        val estimate = conversation.estimate(system(), listOf(user("你好啊。")))

        // 整体估一遍：100 个汉字约 60，再加上 system、这一条、以及每条的角色开销。至少要比 60 大得多。
        assertTrue(estimate > 60, "没锚的时候要整体估，实得 $estimate")
    }

    @Test
    fun theThresholdIsADecisionThatCanBeMadePerTurn() {
        val conversation = Conversation("dwarf")
        val fresh = listOf(user("你好啊。"))
        conversation.commit(fresh)

        val tight = ContextSettings(compressAfterTokens = 10)
        val loose = ContextSettings(compressAfterTokens = ContextSettings.DEFAULT_COMPRESS_AFTER)

        assertTrue(conversation.overThreshold(tight, system(), emptyList()), "阈值调到 10 就该触发")
        assertTrue(!conversation.overThreshold(loose, system(), emptyList()), "默认 400K 离得很远")
    }

    @Test
    fun everyOwnerGetsItsOwnHistory() {
        val all = Conversations()
        all.of("dwarf").commit(listOf(user("矮人的一句")))
        all.of("bard").commit(listOf(user("诗人的一句")))

        assertEquals(1, all.of("dwarf").size)
        assertEquals(1, all.of("bard").size)
        // 取同一份两次拿到的必须是同一份——否则"累积"每轮都会从头来过，而那看起来完全正常。
        assertEquals("矮人的一句", all.of("dwarf").history().single().content)
    }
}
