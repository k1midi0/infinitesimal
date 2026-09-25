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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 对话系统接在会话上之后的样子（§5、§14.36、§14.37）。
 *
 * 这一层要钉住的是**因果的形状**，不是模型说的话好不好：
 *
 * - **「这句话说给谁听」由判定裁**（`address` 那一栏），代码不划任何半径。所以"没人应声"必须真的是
 *   一次调用之后的结果，而不是代码先按距离筛没了。而**投递**（谁收到了这件事）是另一回事，归代码
 *   ——那一条在 `EventLogTest` 里验。
 * - **玩家等**：被指向的人一个一个反应，反应完这一轮才结束。
 * - **深度钉在 1**：NPC 说的话不再唤醒别人。少了这一条，两个话多的角色就能一直接下去，而那正是
 *   一个会悄悄烧钱的环。
 */
class GameSessionSpeechTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun npcOf(script: Scripted) = Npc(
        LlmClient(config, script.client),
        Judge(LlmClient(config, script.client)),
    )

    private fun sessionOf(script: Scripted, seed: List<Entity> = StartingWorld.tavern()) = GameSession(
        config,
        seed = seed,
        judge = Judge(LlmClient(config, script.client)),
        npc = npcOf(script),
    )

    @Test
    fun speakingWakesWhoeverTheJudgeSaysCanHear() = runBlocking {
        val script = Scripted(
            listOf(
                // 判定的第一步：**这句话说给谁听**（§14.37）。它是单独一栏，不是 `target`——
                // "指向"与"听见"是两件事，混在一栏里之后"跟同伴闲聊"与"大吼一嗓子"就分不开了。
                turn("t1", "address", """{"id":"dwarf"}"""),
                // 矮人答话
                turn("c1", "say", """{"text":"……嗯。"}"""),
                stop(),
            ),
        )
        val session = sessionOf(script)

        session.runTurn("你好啊。", "")

        assertEquals(
            listOf(Spoken(PLAYER, "你好啊。"), Spoken("dwarf", "……嗯。")),
            session.speech,
        )
        // 那句话本身要送给判定——判定是按"这次动作指向谁"来对齐的，它读不到那句话就无从裁起。
        assertTrue("你好啊。" in script.bodies.first())
        // 玩家自己那句也要在日志里（他得看见自己说了什么，以及后面有没有人接）
        assertTrue(session.log.any { it.text.contains("你说：「你好啊。」") })
    }

    @Test
    fun whenNobodyIsAroundTheTurnSaysSoAndNobodyIsWoken() = runBlocking {
        val script = Scripted(listOf(turn("t1", "target", """{"id":"player"}""")))
        val session = sessionOf(script, seed = StartingWorld.tavern().filter { it.id != "dwarf" })

        session.runTurn("有人在吗？", "")

        assertEquals(listOf(Spoken(PLAYER, "有人在吗？")), session.speech)
        // **判定只报发起者自己** = 这一片里没有别人。这一轮就到这里，不再多花一次调用。
        assertEquals(1, script.bodies.size)
        assertTrue(session.log.any { it.text.contains("没有人应声") })
    }

    @Test
    fun anNpcsOwnWordsDoNotWakeAnybodyElse() = runBlocking {
        val bard = Entity(
            id = "bard",
            kind = "角色",
            label = "弹琴的吟游诗人",
            x = 1100, y = 500, z = StartingWorld.tavern().first { it.id == PLAYER }.z,
            attributes = mapOf("两手" to "抱着一张琴"),
            cards = listOf(CharacterCard("身份", "你在各个镇子之间卖唱，琴弹得一般。")),
        )
        val script = Scripted(
            listOf(
                multiTurn(
                    Triple("t1", "address", """{"id":"dwarf"}"""),
                    Triple("t2", "address", """{"id":"bard"}"""),
                ),
                turn("c1", "say", """{"text":"……嗯。"}"""),
                stop(),
                turn("c2", "say", """{"text":"客人，听个曲儿么？"}"""),
                stop(),
            ),
        )
        val session = sessionOf(script, seed = StartingWorld.tavern() + bard)

        session.runTurn("你们好啊。", "")

        assertEquals(3, session.speech.size, "玩家一句 + 两个人各一句：${session.speech}")
        // **1 次对齐 + 每人 2 次 = 5。**多出来的任何一次都是"NPC 的话又唤醒了谁"——那是一个环。
        assertEquals(5, script.bodies.size, "NPC 自己说的话不该再唤醒别人（深度钉在 1）")
    }

    @Test
    fun anNpcsActReachesTheWorldThroughTheSameJudge() = runBlocking {
        val script = Scripted(
            listOf(
                turn("t1", "address", """{"id":"dwarf"}"""),                                  // 这句话说给谁听
                turn("c1", "act", """{"intention":"我端起自己那只杯子喝一口。"}"""),           // 它决定动手
                turn("t2", "target", """{"id":"dwarf"}"""),                                   // 判定对齐
                turn("e1", "set_attribute", """{"id":"dwarf","key":"神色","value":"有点上头"}"""),
                stop(),
            ),
        )
        val session = sessionOf(script)

        session.runTurn("来一口？", "")

        assertEquals(
            "有点上头",
            session.world.byId("dwarf")?.attributes?.get("神色"),
            "NPC 的「做」必须真的落库",
        )
        // **界面上的东西也要跟着变。**世界不是可观察的，`entities` 是会话推给界面的那一份；
        // 它忘了刷，症状就是"NPC 做了事，地图上什么都没变"。
        assertEquals(
            "有点上头",
            session.entities.first { it.id == "dwarf" }.attributes["神色"],
        )
    }

    @Test
    fun sayingAndDoingInTheSameTurnBothLand() = runBlocking {
        val script = Scripted(
            listOf(
                // **说与做共用这一次第一步**（§14.37）。早先它们是两次：动作对齐一次、说话再对齐一次
                // 去找听者。现在一帧里给两栏——`target` 是动作作用到的东西（也是纠正预览要比对的那
                // 一份），`address` 是这句话说给谁听。
                multiTurn(
                    Triple("t1", "target", """{"id":"oak_table"}"""),
                    Triple("t1b", "address", """{"id":"dwarf"}"""),
                ),
                turn("e1", "change_appearance", """{"id":"oak_table"}"""),           // 动作的后果
                turn("c1", "say", """{"text":"擦桌子？随你。"}"""),                     // 矮人答话
                stop(),
            ),
        )
        val session = sessionOf(script)
        // **先勾上桌子。**不勾的话判定的对齐结果与勾选不一致，`judgeAndCommit` 会挂起等玩家确认
        // （§4.7），这一轮的"说"那一半根本走不到。
        session.click("oak_table")

        session.runTurn("你好啊。", "我把桌子擦了擦。")

        assertEquals(listOf(Spoken(PLAYER, "你好啊。"), Spoken("dwarf", "擦桌子？随你。")), session.speech)
        assertTrue(session.log.any { it.text.contains("成立") }, "做的那一半也要照常走完")
    }
}
