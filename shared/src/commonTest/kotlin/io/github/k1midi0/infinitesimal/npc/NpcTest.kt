package io.github.k1midi0.infinitesimal.npc

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * AI 角色那一次唤醒（§5、§14.36）。
 *
 * 两件最该钉住的事：
 *
 * 1. **「什么都不做」是合法的。** 这是这一层与非导演几处最容易做错的地方——把"空手"统一当成失败，
 *    会让每个矮人都被逼着答话，而"不想理你就是不想理"是一个真实的反应。
 * 2. **「做」真的走判定、真的落库。** 不是"它说自己做了什么"，而是世界真的变了。这一条如果只是
 *    读代码看不出来——它跨了三个对象（角色 → 判定 → 提交层），所以这里用假端点把整条链跑一遍。
 */
class NpcTest {

    private fun config() = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun world(seed: List<io.github.k1midi0.infinitesimal.world.Entity> = StartingWorld.tavern()): World =
        World.from(WorldSnapshot(seed))

    private fun originOf(world: World): ChunkOrigin =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun greeted(text: String) = Heard.Spoken("player", "你", text)

    /** 造一个角色，它的两条客户端都打在同一个假端点上，于是帧的顺序就是真正的调用顺序。 */
    private fun npcOf(script: Scripted): Npc = Npc(
        LlmClient(config(), script.client),
        Judge(LlmClient(config(), script.client)),
    )

    @Test
    fun itSaysSomethingAndTheSentenceIsRecorded() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "say", """{"text":"……抬眼看了看他。"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        assertEquals(listOf(Spoken("dwarf", "……抬眼看了看他。")), reaction.said)
        assertTrue(reaction.acted.isEmpty())
        assertTrue(reaction.problems.isEmpty())
        assertFalse(reaction.silent)
    }

    @Test
    fun sayingNothingIsALegalOutcome() = runBlocking {
        val script = Scripted(listOf(stop()))
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        assertTrue(reaction.silent, "不理人是一个真实的反应，不是一次失败")
        assertTrue(reaction.problems.isEmpty())
        assertEquals(1, reaction.rounds, "一个来回就该收工——空手就是它说完了")
    }

    @Test
    fun actingGoesThroughTheJudgeAndTheWorldReallyChanges() = runBlocking {
        val script = Scripted(
            listOf(
                // ① 角色提议做一个动作
                turn("c1", "act", """{"intention":"我端起自己那只杯子喝一口。"}"""),
                // ② 判定第一步：这件事落在谁身上
                turn("t1", "target", """{"id":"dwarf"}"""),
                // ③ 判定第二步：后果。**它一被调用，这一步就收工**（不是查询手）
                turn("e1", "set_attribute", """{"id":"dwarf","key":"神色","value":"有点上头"}"""),
                // ④ 角色那一轮接着跑，这一次它没有手要调了
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("来一口？"), originOf(world)))

        assertEquals(1, reaction.acted.size)
        assertTrue(reaction.acted.single() is Judgment.Applied, "实际拿到的是 ${reaction.acted.single()}")
        assertEquals(
            "有点上头",
            world.byId("dwarf")?.attributes?.get("神色"),
            "「做」必须真的落库，而不是它说自己做成了",
        )
    }

    @Test
    fun anEmptySentenceComesBackAsSomethingItCanFix() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "say", """{"text":"   "}"""),
                // 退回去的那句话要真的回到上下文里——它下一轮才有依据改
                turn("c2", "say", """{"text":"……嗯。"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        assertEquals(listOf(Spoken("dwarf", "……嗯。")), reaction.said)
        assertEquals(1, reaction.problems.size)
        assertTrue("text" in reaction.problems.single())
    }

    @Test
    fun anUnknownToolNameIsToldWhatItCanUse() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "destroy", """{"id":"oak_table"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        assertTrue(reaction.silent)
        assertTrue("destroy" in reaction.problems.single())
        // **它手里没有那只手。** 角色要是能自己删东西，那它说的就成了事实，世界就没人复核了（§1）。
        assertTrue(world.byId("oak_table") != null)
    }

    @Test
    fun wakingSomebodyWhoIsNotInTheWorldIsReportedRatherThanThrown() = runBlocking {
        val script = Scripted(listOf(stop()))
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("nobody", greeted("你好啊。"), originOf(world)))

        assertTrue(reaction.silent)
        assertEquals(1, reaction.problems.size)
        assertTrue("nobody" in reaction.problems.single())
    }

    @Test
    fun thePromptCarriesItsPersonaItsStateAndItsSight() = runBlocking {
        val script = Scripted(listOf(stop()))
        val world = world()

        npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        val body = script.bodies.single()
        // 人设卡的内容进去，**标题不进去**（§3.5）。用「口癖」而不是「身份」验：后者恰好也出现在
        // 角色那份 system 的正文里（「你的身份、脾气……」），拿它断言会假报。
        assertTrue("杜林" in body, "人设卡的内容该在提示词里")
        assertTrue("哼一声" in body, "每一张卡的内容都该进去，不是只进第一张")
        assertFalse("口癖" in body, "卡的标题永不进提示词")
        // 结构化属性进去——它是"世界状态"，与卡是两回事
        assertTrue("半醉" in body)
        // 视野：区块地图那一段，以及它自己的位置
        assertTrue("【你这双眼睛看得见的一片】" in body)
        assertTrue("图外面有什么，你不知道" in body)
        // 听见的那句话原样进去，不做任何处理（§5.1）
        assertTrue("你好啊。" in body)
        assertTrue("玩家说的话" !in body, "话不做改写——原样交给它")
    }
}
