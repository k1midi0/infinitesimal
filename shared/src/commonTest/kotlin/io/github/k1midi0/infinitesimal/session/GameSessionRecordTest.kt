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
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.EventKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 会话往**记录层**里写什么（§6.1、§14.37）。
 *
 * 这一层要钉住三条，每一条都对应一处"看起来对、其实漏了"的写法：
 *
 * 1. **一句话与一个动作是两条记录**，而后果挂在动作那一条上。
 * 2. **同处一片的人收得到**——不等判定报出它，那是投递。
 * 3. **动作被驳回时那句话照样留下**。第一版把记录写在"对齐成功"那一支里，于是"说了句话 + 做了个
 *    不可能的动作"这一轮里，那句话连同记录一起消失了——而它明明已经说出口了。
 */
class GameSessionRecordTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun sessionOf(script: Scripted, seed: List<Entity> = StartingWorld.tavern()) = GameSession(
        config,
        seed = seed,
        judge = Judge(LlmClient(config, script.client)),
        npc = Npc(LlmClient(config, script.client), Judge(LlmClient(config, script.client))),
    )

    @Test
    fun aSaidSentenceAndAnActionBecomeTwoRecordsAndTheEffectsRideOnTheAction() = runBlocking {
        val script = Scripted(
            listOf(
                multiTurn(
                    Triple("t1", "target", """{"id":"oak_table"}"""),
                    Triple("t1b", "address", """{"id":"dwarf"}"""),
                ),
                turn("e1", "change_appearance", """{"id":"oak_table"}"""),
                turn("c1", "say", """{"text":"擦就擦吧。"}"""),
                stop(),
            ),
        )
        val session = sessionOf(script)
        session.click("oak_table")

        session.runTurn("你好啊。", "我把桌子擦了擦。")

        val events = session.world.log.all()
        assertEquals(
            listOf(EventKind.SAY, EventKind.ACT, EventKind.SAY),
            events.map { it.kind },
            "玩家一句、玩家一个动作、矮人一句",
        )
        assertEquals("你好啊。", events[0].text)
        assertEquals("我把桌子擦了擦。", events[1].text)
        assertEquals(1, events[1].effects.size, "动作那条要带上它真的改了什么")
        assertTrue("木桌" in events[1].effects.single())
        assertEquals("擦就擦吧。", events[2].text)
    }

    @Test
    fun theDwarfReceivesWhatHappenedInItsFrameWithoutTheJudgeSayingSo() = runBlocking {
        val script = Scripted(
            listOf(
                // **只报动作的目标，一个 address 都不给。** 矮人不是被指向的人——它照样该收到这一轮。
                multiTurn(
                    Triple("t1", "target", """{"id":"oak_table"}"""),
                ),
                turn("e1", "change_appearance", """{"id":"oak_table"}"""),
                stop(),
            ),
        )
        val session = sessionOf(script)
        session.click("oak_table")

        session.runTurn("", "我把桌子擦了擦。")

        val window = session.world.log.windowOf("dwarf", 10)
        assertEquals(1, window.size, "矮人离玩家两米出头，在同一个框里，它收得到：${window.map { it.text }}")
        assertEquals(EventKind.ACT, window.single().kind)
    }

    @Test
    fun aRejectedActionStillLeavesTheSentenceInTheRecord() = runBlocking {
        val script = Scripted(
            listOf(turn("t1", "reject_targets", """{"reason":"这一片里没有你说的那样东西。"}""")),
        )
        val session = sessionOf(script)
        session.click("oak_table")

        session.runTurn("你好啊。", "我把三条街外那张桌子擦了擦。")

        val said = session.world.log.all().filter { it.kind == EventKind.SAY }
        assertEquals(1, said.size, "动作对齐不上，改变不了「他说过这句话」")
        assertEquals("你好啊。", said.single().text)
        // 而对齐没成功时 `address` 没有答案，所以**没有人被唤醒**——这一片里的人收到的是投递。
        assertEquals(listOf(Spoken(PLAYER, "你好啊。")), session.speech)
    }
}
