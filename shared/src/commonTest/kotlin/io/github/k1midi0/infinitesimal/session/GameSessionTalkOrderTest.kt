package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.multiTurn
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.npc.Npc
import io.github.k1midi0.infinitesimal.npc.Spoken
import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.insertIntoQueue
import io.github.k1midi0.infinitesimal.world.joinTheQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **一次点到两个人时，谁先开口**（用户定，2026-09-26）。
 *
 * 这一条要钉住的是**顺序真的由名单决定**，而不是碰巧对：判定的 `address` 报出来的那几个 id
 * 是**无序**的（它是模型写的一栏），代码必须自己排。所以每一支都用两个角色，而它们的先后关系在
 * 名单里被明确写成与 id 序**相反**——不然"按 id 排"与"按名单排"会给出同一个答案，测试就成了摆设。
 */
class GameSessionTalkOrderTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private val bard = Entity(
        id = "bard",
        kind = "角色",
        label = "弹琴的吟游诗人",
        x = 1100, y = 500, z = StartingWorld.tavern().first { it.id == PLAYER }.z,
        attributes = mapOf("两手" to "抱着一张琴"),
        cards = listOf(CharacterCard("身份", "你在各个镇子之间卖唱，琴弹得一般。")),
    )

    private fun npcOf(script: Scripted) = Npc(
        LlmClient(config, script.client),
        Judge(LlmClient(config, script.client)),
    )

    private fun sessionOf(script: Scripted) = GameSession(
        config,
        seed = StartingWorld.tavern() + bard,
        judge = Judge(LlmClient(config, script.client)),
        npc = npcOf(script),
    )

    /** 一次点到**两个人**的那一帧：两个 `address` 同一轮里报出来。 */
    private fun bothAddressed() = multiTurn(
        Triple("t1", "address", """{"id":"dwarf"}"""),
        Triple("t2", "address", """{"id":"bard"}"""),
    )

    /**
     * 一个角色的两帧：说一句、然后收工。
     *
     * **两个人的台词是分开的四句**（「甲乙」「丙丁」）而不是两句：`multiTurn` 把两个 `address` 放在
     * **同一帧**里，所以帧的顺序说明不了谁先说——顺序只能从**台词**读出来。
     */
    private fun says(id: String, text: String) = listOf(
        turn(id, "say", """{"text":"$text"}"""),
        stop(),
    )

    @Test
    fun theQueueDecidesWhoSpeaksFirst() = runBlocking {
        val script = Scripted(
            listOf(bothAddressed()) + says("c1", "甲乙") + says("c2", "丙丁"),
        )
        val session = sessionOf(script)
        // **吟游诗人排在最前面**——而按 id 排它本来就先（bard < dwarf），所以这一支两个答案一致，
        // 是下面那一支的对照组。
        session.world.insertIntoQueue("bard", 0)
        session.world.joinTheQueue("dwarf")

        session.runTurn("你们好啊。", "")

        assertEquals(
            listOf(Spoken(PLAYER, "你们好啊。"), Spoken("bard", "甲乙"), Spoken("dwarf", "丙丁")),
            session.speech,
        )
    }

    @Test
    fun theQueueBeatsTheIdOrder() = runBlocking {
        val script = Scripted(
            listOf(bothAddressed()) + says("c1", "甲乙") + says("c2", "丙丁"),
        )
        val session = sessionOf(script)
        // **矮人插到最前面**，而按 id 排它本该在后面（bard < dwarf）。于是只有"按名单排"才会给出
        // 矮人先说这个答案——这一支就是那条规则唯一能露馅的地方。
        session.world.insertIntoQueue("dwarf", 0)
        session.world.joinTheQueue("bard")

        session.runTurn("你们好啊。", "")

        assertEquals(
            listOf(Spoken(PLAYER, "你们好啊。"), Spoken("dwarf", "甲乙"), Spoken("bard", "丙丁")),
            session.speech,
        )
        // **顺序也要写进日志。** 它是代码排的，而排错了的症状是"某个人怎么老先说"——那种事没有
        // 一行日志就查不出，而这一行的顺序正是上面那个断言依赖的东西。
        assertTrue(
            session.log.any { it.text == "（这一轮依次开口：酒馆里的矮人、弹琴的吟游诗人）" },
            "日志里要看得见这一轮的发言顺序：${session.log.map { it.text }}",
        )
    }

    @Test
    fun somebodyWhoNeverQueuedSpeaksAfterThoseWhoDid() = runBlocking {
        val script = Scripted(
            listOf(bothAddressed()) + says("c1", "甲乙") + says("c2", "丙丁"),
        )
        val session = sessionOf(script)
        // 只有吟游诗人排过队。矮人**照样能说话**（世界不必先给人排座次），只是排在最后——而按 id
        // 排它本该先。所以这一支量的是"没排队的排最后"，不是"按 id 排"。
        //
        // **先清空**：§14.51 起会话的默认发言名单里已经有玩家与矮人（那是开局内容的一部分），不清的话
        // 矮人就是排过队的，而这一支要的正是"没排过队的那个人"。
        session.world.speakQueue = emptyList()
        session.world.joinTheQueue("bard")

        session.runTurn("你们好啊。", "")

        assertEquals(
            listOf(Spoken(PLAYER, "你们好啊。"), Spoken("bard", "甲乙"), Spoken("dwarf", "丙丁")),
            session.speech,
        )
    }

    @Test
    fun anEmptyQueueStillLetsPeopleTalk() = runBlocking {
        val script = Scripted(
            listOf(bothAddressed()) + says("c1", "甲乙") + says("c2", "丙丁"),
        )
        val session = sessionOf(script)
        // **开局那份快照把矮人排进了队首**，所以这里先把它清空——量的是"一份完全没排过座次的世界
        // 照样跑得起来"。两个人都没排队时按 id 排，于是吟游诗人先。
        session.world.speakQueue = emptyList()

        session.runTurn("你们好啊。", "")

        assertEquals(
            listOf(Spoken(PLAYER, "你们好啊。"), Spoken("bard", "甲乙"), Spoken("dwarf", "丙丁")),
            session.speech,
        )
    }

    @Test
    fun eachOfThemIsWokenExactlyOnce() = runBlocking {
        val script = Scripted(
            listOf(bothAddressed()) + says("c1", "甲乙") + says("c2", "丙丁"),
        )
        val session = sessionOf(script)

        session.runTurn("你们好啊。", "")

        // **1 次对齐 + 每人 2 次 = 5。** 多出来的任何一次都是"NPC 自己说的话又唤醒了一次"——
        // 那是个会悄悄烧钱的环（深度钉在 1）。
        assertEquals(5, script.bodies.size, "每个角色这一回合只被唤醒一次")
        assertEquals(2, session.speech.count { it.whoId != PLAYER }, "两个人各说了一句")
        assertTrue(
            session.log.none { it.text.contains("没醒过来") },
            "名单排序不该让谁醒不过来：${session.log.map { it.text }}",
        )
    }
}
