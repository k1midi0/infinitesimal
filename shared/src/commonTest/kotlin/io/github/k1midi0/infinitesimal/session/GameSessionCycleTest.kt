package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.npc.Npc
import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.Cycle
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * **每轮开头那一次"到点了就位"真的挂在会话上**（§14.44）。
 *
 * 刷新自己那一层由 `CycleRefreshTest` 钉住，这一支钉的是**它被调到**：`runTurn` 一开头就该把还在走
 * 循环的人挪到此刻那一格上。少了这一处接线，症状是"改了作息、人也到了点，可它一直在原地站着"——
 * 而那种失败看不出来（世界没报错，只是没动）。
 *
 * ## 时间在这一支里是"绝对"的，而作息里的时间是"相对"的
 *
 * 这一支手写的作息刻意把 [Cycle.bornAt] 设成**世界起点**（`WorldTime(0)`），于是作息里的相对分钟与
 * 世界时间直接对上——诊断时不用在脑子里做减法。**导演写的那一份不是这样**：它的 `bornAt` 取"写下去
 * 那一刻"，于是同一个 `0｜…` 在两种情况下落在完全不同的时刻（`DirectorCycleTest` 钉的是那一种）。
 *
 * 为什么挂在"每轮开头"而不是"到点那一瞬间"：快进会跳过时刻，没有任何代码在那一瞬看着。这一支因此
 * 用的就是最朴素的那条路——先把时钟挪到点上，再跑一轮。
 */
class GameSessionCycleTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    /**
     * 一圈 1000 分钟：**从诞生起第 300 分钟到第 500 分钟**在柜台后面（1000,200），之后到后厨（900,320）。
     *
     * 第一格刻意**不落在 0**（`Cycle.problems` 会驳回那种），于是相位 `[0, 300)` 那一段绕给最后一格
     * ——那是"上一圈还没走完"，在这个 fixture 里就是它刚被写下的那一小段。
     */
    private fun routine(ground: Int) = Cycle(
        listOf(
            Beat(300, "站在柜台后面招呼客人。", x = 1000, y = 200, z = ground),
            Beat(500, "在后厨生火、把杯子擦干净。", x = 900, y = 320, z = ground),
        ),
        bornAt = WorldTime(0),
    )

    private fun seedWithRoutine(): List<Entity> {
        val dwarf = StartingWorld.tavern().first { it.id == "dwarf" }
        return StartingWorld.tavern().map { entity ->
            if (entity.id == "dwarf") entity.copy(cycle = routine(dwarf.z)) else entity
        }
    }

    private fun sessionOf(script: Scripted) = GameSession(
        config,
        seed = seedWithRoutine(),
        judge = Judge(LlmClient(config, script.client)),
        npc = Npc(LlmClient(config, script.client), Judge(LlmClient(config, script.client))),
    )

    /** 两个回合各要一次对齐与一次答话；`stop()` 收尾之后，多出来的请求都拿到同一帧。 */
    private fun twoTurns() = Scripted(
        listOf(
            turn("t1", "address", """{"id":"dwarf"}"""),
            turn("c1", "say", """{"text":"……嗯。"}"""),
            stop(),
            turn("t2", "address", """{"id":"dwarf"}"""),
            turn("c2", "say", """{"text":"……嗯。"}"""),
            stop(),
        ),
    )

    @Test
    fun theTurnMovesWhoeverTheCycleSaysHasReachedTheNextBeat() = runBlocking {
        val session = sessionOf(twoTurns())
        // 开局是第 1 天 18:00；把时钟挪到第一格刚过去的相位上。
        session.world.clock = WorldTime(400)
        assertEquals(WorldTime(400), session.world.clock, "设进去就该是它")

        session.runTurn("你好啊。", "")

        val dwarf = session.world.byId("dwarf")!!
        assertEquals(1000, dwarf.x, "相位 400 落在第一格里")
        assertEquals(200, dwarf.y)
    }

    @Test
    fun crossingABeatMovesItOnTheNextTurn() = runBlocking {
        val session = sessionOf(twoTurns())

        session.world.clock = WorldTime(400) // 第一格里
        session.runTurn("你好啊。", "")
        assertEquals(1000, session.world.byId("dwarf")!!.x)

        // 一圈 = 最后一格的时刻 = 500。相位 600 % 500 = 100，已经绕回最后一格（后厨）了。
        session.world.clock = WorldTime(600)
        session.runTurn("还在忙？", "")
        assertEquals(900, session.world.byId("dwarf")!!.x, "过了那一格就该挪到后厨")
        assertEquals(320, session.world.byId("dwarf")!!.y)
    }

    /** 活跃期的人**不**被作息拉走（§3.2.1）——这一条在会话这一层也要成立，不只是刷新那一层。 */
    @Test
    fun somebodyOffTheirCycleIsNotDraggedBackByTheTurn() = runBlocking {
        val session = sessionOf(twoTurns())
        session.world.put(
            session.world.byId("dwarf")!!.copy(
                x = 5000,
                y = 5000,
                active = io.github.k1midi0.infinitesimal.world.Active(since = WorldTime(600)),
            ),
        )
        session.world.clock = WorldTime(600)

        session.runTurn("你好啊。", "")

        assertEquals(5000, session.world.byId("dwarf")!!.x, "它没在走循环，就不该被挪回去")
    }
}
