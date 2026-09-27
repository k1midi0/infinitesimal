package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.generate.TerrainGenerator
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.esc
import io.github.k1midi0.infinitesimal.judge.sse
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.LONG_ABSENT_MARKER
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **`Wake.Stuck` 的契约**（§14.45）：这一次唤醒**有非做不可的事**。
 *
 * 与别的唤醒分开，是因为"什么都没做"在这里不是"我看过了不用动"（那是 `Wake.Called` 的合法结果），
 * 而是**那个人还卡着**。判据是「那一份作息出生时刻变了没有」——一段 `bornAt` 变了，就是改过了。
 *
 * 这一支钉两件事：
 *
 *  1. **它什么都不写 → 这次唤醒不算完成**（重试到 `maxAttempts`，然后报失败，理由要能看见）。
 *  2. **它写了 → 完成**，而且 `write_cycle` 那一段提示词里带着"谁卡了、它此刻在哪、那件事为什么
 *     进不去"。
 */
class DirectorStuckTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun world(): World {
        val w = World.from(StartingWorld.snapshot())
        // 让它先卡住：带上 active（它此刻没在走循环），而循环那一份的出生时刻是它自己原先那一份。
        val dwarf = w.byId("dwarf")!!
        w.put(dwarf.copy(active = io.github.k1midi0.infinitesimal.world.Active(since = w.clock)))
        return w
    }

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun stuck(world: World) = Waking(
        Wake.Stuck(
            required = listOf("dwarf"),
            why = listOf("「dwarf」到了「站在柜台后面招呼客人。」该在的地方 (1000, 200, 12800)，但那间屋子塌了。"),
            was = mapOf("dwarf" to world.byId("dwarf")!!.cycle.bornAt),
        ),
        "player",
        origin(world),
    )

    private fun director(script: Scripted) = Director(
        LlmClient(config, script.client),
        TerrainGenerator(LlmClient(config, script.client)),
    )

    private fun doneSaying(text: String = "写完了。") = sse(
        """{"choices":[{"delta":{"content":"${text}"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
    )

    private fun writeCycle(beats: String, callId: String = "c1") = turn(
        callId,
        DirectorTools.WRITE_CYCLE,
        """{"id":"dwarf","beats":"${esc(beats)}"}""",
    )

    @Test
    fun writingNothingDoesNotCountAsDone() = runBlocking {
        val script = Scripted(listOf(doneSaying(), doneSaying()))
        val world = world()
        val before = world.byId("dwarf")!!.cycle.beats

        val result = director(script).wake(world, stuck(world))

        assertTrue(result is Awakening.FailedProtocol, "**这一次有非做不可的事**，实得 $result")
        assertTrue(
            (result as Awakening.FailedProtocol).problems.any { "还是原来那一份作息" in it },
            "理由要说清它没改：${result.problems}",
        )
        assertEquals(before, world.byId("dwarf")!!.cycle.beats, "一个字都不能动")
    }

    @Test
    fun writingANewCycleSatisfiesIt() = runBlocking {
        val script = Scripted(listOf(writeCycle("420｜1300,100,12800｜在门房那边守着。\n900｜1100,260,12800｜扫地。")))
        val world = world()
        val at = world.clock

        val result = director(script).wake(world, stuck(world))

        assertTrue(result is Awakening.Done, "改过了就算完成，实得 $result")
        val after = world.byId("dwarf")!!
        assertEquals(listOf(420, 900), after.cycle.beats.map { it.atMinute })
        assertEquals(at, after.cycle.bornAt, "新的那一份从写下去那一刻起跑")
        assertTrue(after.active != null, "**活跃状态不动**（§14.45：导演只改作息）")
    }

    @Test
    fun thePromptSaysWhoIsStuckAndWhy() = runBlocking {
        val script = Scripted(listOf(writeCycle("420｜1300,100,12800｜在门房那边守着。\n900｜1100,260,12800｜扫地。")))
        val world = world()

        director(script).wake(world, stuck(world))

        val body = script.bodies.first()
        assertTrue("那间屋子塌了" in body, "哨兵给的理由要原样带到：${body.take(400)}")
        assertTrue("【这一次必须处理的人】" in body, "要点名是谁")
        assertTrue("酒馆里的矮人" in body, "要写得出名字")
        assertTrue("write_cycle" in body, "要告诉它用哪只手改")
    }

    /**
     * **被叫起来的那一段话要把两种情形都说出来**（§14.50）。
     *
     * 从前它只写一种（"到了那一格却没进得去，正停在门口"），而 §14.50 之后另一条兜底（"离开自己的日子
     * 太久了"）也走这一档。少了这一段，导演拿到一条"它回不去"的理由时**不知道自己该做什么**——它手上
     * 那一条规矩说的是"把那一格换成做得了的地方"，而这一种情形下那一格是好端端的，回不去的是那个人。
     */
    @Test
    fun thePromptAlsoCoversTheOneThatSimplyCannotGetBack() = runBlocking {
        val script = Scripted(listOf(writeCycle("0｜8000,8000,12800｜就在这儿过日子。\n600｜8100,8000,12800｜挪两步。")))
        val world = world()

        director(script).wake(
            world,
            Waking(
                Wake.Stuck(
                    required = listOf("dwarf"),
                    why = listOf("$LONG_ABSENT_MARKER 它被带走了，回不去。"),
                    was = mapOf("dwarf" to world.byId("dwarf")!!.cycle.bornAt),
                ),
                "player",
                ChunkOrigin.atPlayerStart(ChunkKey(1, 1)),
            ),
        )

        val body = script.bodies.first()
        assertTrue("离开自己的日子太久" in body, "要说得出第二种情形：${body.take(600)}")
        assertTrue("就地过日子" in body, "还要给出那一条正当的写法（写它此刻在的坐标）")
        assertTrue(LONG_ABSENT_MARKER in body, "那一段理由要原样带到")
    }
}
