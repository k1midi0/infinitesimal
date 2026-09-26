package io.github.k1midi0.infinitesimal.npc

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **一回合里说、做、移动各一次**（用户定，2026-09-26；§4.3 玩家那一轮的三样输入）。
 *
 * 这一刀要钉住的是一件很容易做错的事：**封的是"被记下的那几件事"，不是"它想了几回"。** 所以每一支
 * 里都有一条"被驳回之后还能再来"的断言——少了它，这次的改动就会顺手把判定那条"不成，因为…，换个
 * 做法再试"的回路（§1.3）掐掉，而那是一条已经量过、正在工作的路。
 */
class NpcPerTurnTest {

    private fun config() = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun npcOf(script: Scripted) = Npc(
        LlmClient(config(), script.client),
        Judge(LlmClient(config(), script.client)),
    )

    private fun world() = World.from(StartingWorld.snapshot())

    private fun originOf(world: World): ChunkOrigin =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun greeted(text: String) = Heard.Spoken("player", "你", text)

    private fun bodiesIn(script: Scripted) = script.bodies

    // ── say：一回合一句 ───────────────────────────────────────────────────────────

    @Test
    fun itSaysOneSentencePerTurn() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "say", """{"text":"外头来的？"}"""),
                turn("c2", "say", """{"text":"坐吧。"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好。"), originOf(world)))

        assertEquals(listOf(Spoken("dwarf", "外头来的？")), reaction.said, "第二句不该被记下来")
        assertTrue(reaction.problems.isEmpty(), "这不是协议问题，是世界在说'这一回合你只说一句'")
        assertTrue("一回合只说一句" in bodiesIn(script).last(), "退回去的话要说清为什么")
        // 而它照样要有下文可走——这一回合它还有两样没做。
        assertTrue("做一个动作" in bodiesIn(script).last() && "走一步" in bodiesIn(script).last())
    }

    @Test
    fun anEmptySentenceDoesNotEatTheOneSentenceOfTheTurn() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "say", """{"text":"   "}"""),   // 空话：参数问题，不是"说过一句"
                turn("c2", "say", """{"text":"外头来的？"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好。"), originOf(world)))

        // **失败的那一次不占名额。** 与"驳回不算"同一条理由：没有说出口的话不是说过的话。少了这一条，
        // 一次手滑的空参数就会把这一回合唯一的那句话吞掉。
        assertEquals(listOf(Spoken("dwarf", "外头来的？")), reaction.said)
    }

    // ── act / move：各一件，而驳回不算 ─────────────────────────────────────────────

    @Test
    fun itActsOncePerTurn() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "act", """{"intention":"我端起杯子喝一口。"}"""),
                turn("t1", "target", """{"id":"dwarf"}"""),
                turn("e1", "set_attribute", """{"id":"dwarf","key":"神色","value":"有点上头"}"""),
                turn("c2", "act", """{"intention":"我再喝一口。"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("来一口？"), originOf(world)))

        assertEquals("有点上头", world.byId("dwarf")?.attributes?.get("神色"), "第一件落定了")
        assertEquals(1, reaction.acted.size, "第二件根本不该走到判定：${reaction.acted}")
        assertTrue("已经做过一件事" in bodiesIn(script).last())
    }

    @Test
    fun aRefusedActCanBeTriedAgain() = runBlocking {
        val script = Scripted(
            listOf(
                // 第一次：判定第一步就驳回（世界说"不成，因为…"）。那只手叫 `reject_targets`——
                // **驳回与"报了几个目标"是互斥的两栏**，所以它有自己的名字，不跟 `target` 混。
                turn("c1", "act", """{"intention":"我把那只点着的灯挪过来。"}"""),
                turn("t1", "reject_targets", """{"reason":"那盏灯挂在高处，你够不着。"}"""),
                // 第二次：换个做法，这回成了
                turn("c2", "act", """{"intention":"我踩着凳子去够那盏灯。"}"""),
                turn("t2", "target", """{"id":"dwarf"}"""),
                turn("e1", "set_attribute", """{"id":"dwarf","key":"两手","value":"举着一盏灯"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("把灯点上。"), originOf(world)))

        // **这一条是这一刀最要紧的断言。** "各一次"不该把驳回之后的重试也封掉——那条回路是判定活着
        // 的方式，而驳回不是它做过的事。
        //
        // 两栏要分开读，这一条以前写错过一次：
        //  - [Reaction.acted] 收的是**它做过的每一次判定，含驳回的**（会话就是照这一栏把"不成，因为…"
        //    念给玩家听的）。
        //  - **名额闸**认的是另一件事：只有 `Applied` 与 `Unchanged` 落定（`Caps`），所以驳回之后那只手
        //    还是开的——这一支要量的正是它。
        assertEquals(2, reaction.acted.size, "两次都走到了判定：${reaction.acted}")
        assertTrue(
            reaction.acted.first() is io.github.k1midi0.infinitesimal.judge.Judgment.RejectedWorld,
            "第一次是被世界驳回的",
        )
        assertEquals("举着一盏灯", world.byId("dwarf")?.attributes?.get("两手"), "换个做法之后真成了")
        assertTrue(bodiesIn(script).any { it.contains("够不着") }, "驳回的理由要回到它手上")
        assertTrue(bodiesIn(script).any { it.contains("凳子") }, "它得真的换个做法再调一次")
    }

    @Test
    fun aRefusedMoveCanBeTriedAgain() = runBlocking {
        val script = Scripted(
            listOf(
                // 走不到：判定驳回
                turn("c1", "move", """{"intention":"我直接从窗口翻出去。"}"""),
                turn("t1", "reject_targets", """{"reason":"窗户闩着，你翻不出去。"}"""),
                // 换个走法：这回成了
                turn("c2", "move", """{"intention":"我绕到后门出去。"}"""),
                turn("t2", "target", """{"id":"dwarf"}"""),
                turn("e1", "move", """{"id":"dwarf","x":600,"y":200,"z":12800}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("出去。"), originOf(world)))

        // **走不动不占那个"走一次"的名额。** 少了这一条，一次驳回就把"这一天别想挪地方"钉死了，
        // 而它明明还有别的走法。
        assertTrue(reaction.moved, "换个走法之后真走了")
        assertEquals(600, world.byId("dwarf")?.x)
    }

    // ── move：位置由判定写 ─────────────────────────────────────────────────────────

    @Test
    fun itMovesByAskingTheJudge() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "move", """{"intention":"我绕到柜台后面去。"}"""),
                turn("t1", "target", """{"id":"dwarf"}"""),
                turn("e1", "move", """{"id":"dwarf","x":1400,"y":200,"z":12800}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("让开点。"), originOf(world)))

        // **坐标是判定写的，不是角色写的**（§3.2.5、§12 第 23 项）。角色那只手只给了一句意图，而"走
        // 到哪、走不走得到"由判定算——所以这里量的是"意图真的走到了判定、后果真的落了库"。
        assertEquals(1400, world.byId("dwarf")?.x, "位置由判定改")
        assertTrue(reaction.moved, "这一回合它确实走动了一次")
        assertEquals(1, reaction.acted.size, "它也是一条判定结果，所以也进那一栏")
    }

    @Test
    fun movingTwiceInOneTurnIsRefused() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "move", """{"intention":"我往门口走两步。"}"""),
                turn("t1", "target", """{"id":"dwarf"}"""),
                turn("e1", "move", """{"id":"dwarf","x":900,"y":200,"z":12800}"""),
                turn("c2", "move", """{"intention":"我再往门口走两步。"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("让开点。"), originOf(world)))

        assertEquals(900, world.byId("dwarf")?.x, "只走了第一次")
        assertEquals(1, reaction.acted.size)
        assertTrue("已经走动过一次" in bodiesIn(script).last())
    }

    @Test
    fun sayingActingAndMovingAllFitInOneTurn() = runBlocking {
        val script = Scripted(
            listOf(
                // 三样一起来——那正好是玩家一轮的全貌。
                turn("c1", "say", """{"text":"行，我过去看看。"}"""),
                turn("c2", "move", """{"intention":"我走到柜台那边。"}"""),
                turn("t1", "target", """{"id":"dwarf"}"""),
                turn("e1", "move", """{"id":"dwarf","x":1400,"y":200,"z":12800}"""),
                turn("c3", "act", """{"intention":"我把柜台上那摞杯子理一理。"}"""),
                turn("t2", "target", """{"id":"oak_table"}"""),
                turn("e2", "set_attribute", """{"id":"oak_table","key":"台面","value":"整整齐齐"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你顺手理理那边。"), originOf(world)))

        // **三样各自独立**：可以只说、只做、只走，也可以三样都有。这一支量的是最后那一种。
        assertEquals(listOf(Spoken("dwarf", "行，我过去看看。")), reaction.said)
        assertTrue(reaction.moved, "走了")
        assertEquals(2, reaction.acted.size, "走与做各一条判定结果")
        assertEquals("整整齐齐", world.byId("oak_table")?.attributes?.get("台面"))
        assertTrue(reaction.problems.isEmpty(), "三样都成的时候不该有任何问题：${reaction.problems}")
    }

    // ── 不占名额的那几只手 ─────────────────────────────────────────────────────────

    @Test
    fun lookingAroundDoesNotEatTheTurn() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "look", """{"id":"e1"}"""),
                turn("c2", "say", """{"text":"外头来的？"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好。"), originOf(world)))

        // `look`、`note`、`arrive` 都不是"玩家那一轮的三样输入"，所以它们不占名额——看一眼再决定说
        // 什么，是很正常的一回合。
        assertEquals(listOf(Spoken("dwarf", "外头来的？")), reaction.said)
        assertTrue(reaction.problems.isEmpty(), "${reaction.problems}")
    }
}
