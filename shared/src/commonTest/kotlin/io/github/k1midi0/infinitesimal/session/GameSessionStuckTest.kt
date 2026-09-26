package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.director.DirectorTools
import io.github.k1midi0.infinitesimal.generate.TerrainGenerator
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.esc
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.npc.Npc
import io.github.k1midi0.infinitesimal.schedule.Scheduler
import io.github.k1midi0.infinitesimal.schedule.SentryJudge
import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **到点却进不去 → 变活跃 → 叫导演**（§14.45），整条线在会话这一层。
 *
 * 四件事按顺序验：
 *
 *  1. 它被放到**那一格的坐标**上（"站在门口"），并且带上 `active`——**而 `target` 是它这一趟想去的那
 *     一格**（§14.45 起"回哪一格"由循环说，这个字段只作归因）。
 *  2. 会话里写一行——`arrive` 那条路同一种做法：世界本身没被谁改过，玩家该看见它没进去。
 *  3. **导演被同步叫醒**，而且拿到的是"谁、哪一格、为什么"。
 *  4. 导演写完新的作息，那个人的**活跃会被放掉**（新作息从此刻起跑，它不再脱轨），而**世界记录里
 *     一个字都没有**（§14.44）。
 */
class GameSessionStuckTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun sessionOf(script: Scripted) = GameSession(
        config,
        judge = Judge(LlmClient(config, script.client)),
        npc = Npc(LlmClient(config, script.client), Judge(LlmClient(config, script.client))),
        director = Director(LlmClient(config, script.client), TerrainGenerator(LlmClient(config, script.client))),
        scheduler = Scheduler(SentryJudge(LlmClient(config, script.client))),
    )

    /** 它到点该去的那一格：开局那份作息的某一格（相位算出来的那一格）。 */
    private fun wantedBeat(session: GameSession, phase: Long): Beat {
        val cycle = session.world.byId("dwarf")!!.cycle
        return cycle.beatAt(cycle.bornAt + phase % cycle.periodMinutes)!!
    }

    private fun phase600(session: GameSession): WorldTime {
        val cycle = session.world.byId("dwarf")!!.cycle
        return cycle.bornAt + 600L % cycle.periodMinutes
    }

    private fun stuckScript(newBeats: String) = Scripted(
        listOf(
            // ① 哨兵：那一格现在做不了。
            turn("s1", "report", """{"possible":false,"reason":"酒馆的大厅已经烧成一片焦黑，屋梁塌在门口。"}"""),
            // ② 导演被叫起来，给它写一份新的作息。
            turn("c1", DirectorTools.WRITE_CYCLE, """{"id":"dwarf","beats":"${esc(newBeats)}"}"""),
            // ③ 导演干完了。
            stop(),
            // ④ 玩家这一轮：对齐 + 矮人答话。
            turn("t1", "address", """{"id":"dwarf"}"""),
            turn("c2", "say", """{"text":"……这地方暂时回不去了。"}"""),
            stop(),
        ),
    )

    @Test
    fun aStuckDwarfIsTakenOffItsCycleAndTheDirectorIsCalled() = runBlocking {
        // 导演写的新作息里**留着那一格的时刻**（它只是把地点换到了门房那边）——于是"它想去哪一格"那条
        // 归因还作数，而它照样是活跃的。
        val script = stuckScript("420｜1300,100,12800｜在门房那边守着。\n900｜1100,260,12800｜扫地。")
        val session = sessionOf(script)
        val dwarf = session.world.byId("dwarf")!!
        val clock = phase600(session)
        val wanted = wantedBeat(session, 600)
        // 把它挪到别处，并把时钟拨到相位 600——到点这一下就真的要发生了。
        session.world.put(dwarf.copy(x = 5000, y = 5000))
        session.world.clock = clock

        session.runTurn("你好啊。", "")

        val after = session.world.byId("dwarf")!!
        assertEquals(
            wanted.x,
            after.x,
            "**它站到了那一格的门口**（§14.45：没进去也要站在那儿）",
        )
        assertEquals(wanted.y, after.y)
        val active = assertNotNull(after.active, "它此刻没在走循环了")
        assertEquals(wanted.atMinute, active.target?.atMinute, "归因那一栏留着它这一趟想去的那一格")
        assertEquals(clock, session.world.clock, "时钟没被这一下改动")

        // **会话里有一行**（`arrive` 那条路同一种做法）：玩家该看见它没进去。
        assertTrue(
            session.log.any { "却没能进去" in it.text },
            "日志里要有那件事：${session.log.map { it.text }}",
        )
        // **而世界记录里一个字都没有**（§14.44）：脱轨不是"发生过的一件事"。
        assertTrue(
            session.world.log.all().none { "没能进去" in it.text || "站住" in it.text },
            "世界记录不该留痕",
        )

        // 导演写的那一份**真的落了**：从当下起跑，而且不再指向那个进不去的地方。
        assertEquals(clock, after.cycle.bornAt, "新作息从写下去那一刻起跑")
        assertEquals(listOf(420, 900), after.cycle.beats.map { it.atMinute })
        assertTrue(after.cycle.beats.none { it.x == wanted.x && it.y == wanted.y }, "新作息里不该还有那个点")
    }

    @Test
    fun whenTheNewCycleForgetsThatBeatTheStaleTargetIsCleared() = runBlocking {
        // 导演写的新作息里**没有那一格的时刻**了：那个"想去哪一格"就永远等不到，必须清掉——否则它会
        // 一直指着一个不存在的时刻，而"回到循环上"那条路就永远等不到那一刻。
        val script = stuckScript("60｜1000,200,12800｜在柜台后面擦杯子。\n600｜1100,260,12800｜扫地。")
        val session = sessionOf(script)
        val dwarf = session.world.byId("dwarf")!!
        val clock = phase600(session)
        session.world.put(dwarf.copy(x = 5000, y = 5000))
        session.world.clock = clock

        session.runTurn("你好啊。", "")

        val after = session.world.byId("dwarf")!!
        val active = assertNotNull(after.active, "它仍然是脱轨的——导演只改了作息，没有把它放回去")
        assertEquals(null, active.target, "那一格在新作息里没有了，归因那一栏就归零")
    }

    @Test
    fun whenTheDirectorWritesNothingTheDwarfStaysOffItsCycle() = runBlocking {
        // 导演被叫醒却一个字都不写（沉默 = "我看过了"），而那**不满足这一次的契约**：那个人还在脱轨。
        val script = Scripted(
            listOf(
                turn("s1", "report", """{"possible":false,"reason":"那间屋子塌了。"}"""),
                stop(), // 第一次尝试：什么都没写
                stop(), // 第二次尝试（maxAttempts = 2）
                turn("t1", "address", """{"id":"dwarf"}"""),
                turn("c2", "say", """{"text":"……"}"""),
                stop(),
            ),
        )
        val session = sessionOf(script)
        val dwarf = session.world.byId("dwarf")!!
        val phase600 = dwarf.cycle.bornAt + 600L % dwarf.cycle.periodMinutes
        val wanted = dwarf.cycle.beatAt(phase600)!!
        session.world.put(dwarf.copy(x = 5000, y = 5000))
        session.world.clock = phase600

        session.runTurn("你好啊。", "")

        val after = session.world.byId("dwarf")!!
        assertNotNull(after.active, "**导演没改成，它就留在脱轨里**——那比悄悄清掉它诚实")
        assertEquals(wanted.atMinute, after.cycle.beatAt(session.world.clock)?.atMinute, "它的循环还是原来那一份")
        assertEquals(dwarf.cycle.bornAt, after.cycle.bornAt)
        // 而这一次唤醒的失败理由要能看见（协议不撒谎）。
        assertTrue(
            session.log.any { "没改成他们的作息" in it.text },
            "失败要说出来：${session.log.map { it.text }}",
        )
    }

    @Test
    fun somebodyWhoseBeatIsFineIsNotTakenOffAnything() = runBlocking {
        val script = Scripted(
            listOf(
                turn("s1", "report", """{"possible":true,"reason":"没什么不对的。"}"""),
                turn("t1", "address", """{"id":"dwarf"}"""),
                turn("c2", "say", """{"text":"……"}"""),
                stop(),
            ),
        )
        val session = sessionOf(script)
        val dwarf = session.world.byId("dwarf")!!
        session.world.put(dwarf.copy(x = 5000, y = 5000))
        session.world.clock = dwarf.cycle.bornAt + 600L % dwarf.cycle.periodMinutes

        session.runTurn("你好啊。", "")

        val after = session.world.byId("dwarf")!!
        assertEquals(null, after.active, "放行了就还在走循环")
        assertTrue(session.log.none { "却没能进去" in it.text }, "不该有那一行")
    }
}
