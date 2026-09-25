package io.github.k1midi0.infinitesimal.npc

import io.github.k1midi0.infinitesimal.config.ContextSettings
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.FunctionCall
import io.github.k1midi0.infinitesimal.llm.ToolCall
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

    // ── 压缩（§6.1、§14.40）────────────────────────────────────────────────────────

    private fun call(id: String) = ChatMessage.assistant(
        content = null,
        toolCalls = listOf(ToolCall(id, function = FunctionCall("say", """{"text":"$id"}"""))),
    )

    private fun result(id: String) = ChatMessage.toolResult(id, "好")

    private fun Conversation.fill(vararg messages: ChatMessage) = commit(messages.toList())

    @Test
    fun aCompactedRangeBecomesOneSummaryThatSaysItIsNotAnybodysWords() {
        val conversation = Conversation("dwarf")
        repeat(8) { conversation.fill(user("第 ${it + 1} 条")) }
        val before = conversation.size
        val beforeMessages = conversation.history().size

        val refused = conversation.compact(1, 8, "开头那一段", "他把酒喝了，然后走了。")

        assertEquals(null, refused, "八条、位置齐全，该压得成")
        assertTrue(conversation.size < before, "段数要变少")
        assertTrue(conversation.history().size < beforeMessages, "摊平之后的消息也要变少——那才是压缩")
        assertEquals(1, conversation.summaryCount)

        val rendered = conversation.history().single().content.orEmpty()
        // **标源行不是客套**：摘要可能写着"玩家答应给矮人一坛酒"这种义务句，而那行字的作用是阻止模型
        // 把它当成玩家的原话直接执行（§12 第 47 项）。
        assertTrue(ConversationTestGuard in rendered, "摘要前面要有标源行")
        assertTrue("h1–h8" in rendered, "要写清它盖住了哪一段")
        assertTrue("他把酒喝了，然后走了。" in rendered)
    }

    @Test
    fun compactingASummaryMakesTheNextTier() {
        val conversation = Conversation("dwarf")
        repeat(12) { conversation.fill(user("第 ${it + 1} 条")) }

        assertEquals(null, conversation.compact(1, 8, "前八条", "前八条里发生的事。"))
        // 再压一次，这一次范围里装的是**上一条摘要**加上几条原文。
        assertEquals(null, conversation.compact(1, 10, "更早的一切", "把更早的那些合成一句话。"))

        val renderings = conversation.history().mapNotNull { it.content }
        assertTrue(
            renderings.any { "第 2 级" in it },
            "压摘要压出来的是第 2 级——「自然遗忘」的层次就是这么来的：${renderings.size} 条",
        )
    }

    @Test
    fun youCannotCutBetweenAToolCallAndItsResult() {
        val conversation = Conversation("dwarf")
        // h1..h10，其中 h6/h7 是一对「调用 + 结果」
        conversation.fill(user("一"), user("二"), user("三"), user("四"), user("五"))
        conversation.fill(call("c1"), result("c1"))
        conversation.fill(user("六"), user("七"), user("八"))

        // h1..h6 正好停在"调用"上，而它的结果 h7 还在后面——那会让消息数组悬空，而 DeepSeek 对它的
        // 反应是 400（§14.31 踩过一次空 `tool_calls`）。
        val refused = conversation.compact(1, 6, "", "一段够长的摘要，写满了事实，不会因为太短被退。")

        assertTrue(refused is Conversation.Refused.Bad, "切在调用中间必须被挡住")
        assertTrue("悬空" in (refused as Conversation.Refused.Bad).why, "理由要说清怎么改：${refused.why}")
        assertEquals(0, conversation.summaryCount, "被拒之后一段都不该压掉")
        assertEquals(10, conversation.history().size, "历史一个字都不该动")
    }

    @Test
    fun youCannotStartOnAStrandedToolResult() {
        val conversation = Conversation("dwarf")
        conversation.fill(call("c1"), result("c1"))
        conversation.fill(user("一"), user("二"), user("三"), user("四"), user("五"), user("六"), user("七"), user("八"))

        val refused = conversation.compact(2, 10, "", "一段够长的摘要，写满了事实，不会因为太短被退。")

        assertTrue(refused is Conversation.Refused.Bad)
        assertTrue("往前挪" in (refused as Conversation.Refused.Bad).why, "理由要可执行：${refused.why}")
    }

    @Test
    fun aRangeThatIsTooShortIsRefusedWithSomethingTheModelCanActOn() {
        val conversation = Conversation("dwarf")
        repeat(10) { conversation.fill(user("第 ${it + 1} 条")) }

        val refused = conversation.compact(1, 3, "", "太短的一段。")

        assertTrue(refused is Conversation.Refused.Bad)
        assertTrue("太短" in (refused as Conversation.Refused.Bad).why)
        assertTrue("往更早" in refused.why, "要告诉它怎么办，不能只说不行")
    }

    @Test
    fun anEmptySummaryIsRefusedBecauseThatWouldJustDeleteTheRange() {
        val conversation = Conversation("dwarf")
        repeat(10) { conversation.fill(user("第 ${it + 1} 条")) }

        val refused = conversation.compact(1, 8, "", "   ")

        assertTrue(refused is Conversation.Refused.Bad)
        assertTrue("空" in (refused as Conversation.Refused.Bad).why)
    }

    @Test
    fun aPositionThatDoesNotExistIsRefused() {
        val conversation = Conversation("dwarf")
        repeat(10) { conversation.fill(user("第 ${it + 1} 条")) }

        assertTrue(conversation.compact(1, 999, "", "够长的摘要，写满了事实。") is Conversation.Refused.Bad)
        assertTrue(conversation.compact(999, 999, "", "够长的摘要，写满了事实。") is Conversation.Refused.Bad)
    }

    @Test
    fun theNewestFewAreNeverInTheSuggestedRange() {
        val conversation = Conversation("dwarf")
        repeat(12) { conversation.fill(user("第 ${it + 1} 条")) }

        val range = conversation.suggestRange()

        assertTrue(range != null, "十二条了，该能建议一段")
        // **最新几条一律不动**（§6.1 的裁剪规则之二）：丢掉正在被回应的东西，那不是更小的请求，
        // 是另一个请求。
        assertEquals(12L - Conversation.PRESERVE, range!!.to, "建议的终点要停在保护线之前")
        assertEquals(1L, range.from, "最老的优先")
        assertEquals(1, range.tier, "压原文出第 1 级")
    }

    @Test
    fun aHistoryTooShortToBeWorthCompactingSuggestsNothing() {
        val conversation = Conversation("dwarf")
        repeat(5) { conversation.fill(user("第 ${it + 1} 条")) }

        // 历史还短的时候提示词里一个字都不该提——那段话本身也是开销。
        assertEquals(null, conversation.suggestRange())
    }

    @Test
    fun compactingShrinksWhatTheNextRequestCosts() {
        val conversation = Conversation("dwarf")
        repeat(12) { conversation.fill(user("第 ${it + 1} 条".repeat(20))) }
        val before = conversation.estimate(system(), emptyList())

        conversation.compact(1, 8, "前八条", "前八条合成一句。")

        assertTrue(
            conversation.estimate(system(), emptyList()) < before,
            "压完估算要变小（$before → ${conversation.estimate(system(), emptyList())}）",
        )
    }
}

/**
 * 标源行的开头。测试里只断言开头那一段——**整句英文抄一遍容易抄错，而抄错了测试就变成在验自己**。
 */
private const val ConversationTestGuard = "[Model-written summary"
