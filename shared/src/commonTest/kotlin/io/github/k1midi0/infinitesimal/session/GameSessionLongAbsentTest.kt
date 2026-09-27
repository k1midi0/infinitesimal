package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ScheduleSettings
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
import io.github.k1midi0.infinitesimal.npc.NpcTools
import io.github.k1midi0.infinitesimal.schedule.Scheduling
import io.github.k1midi0.infinitesimal.world.Active
import io.github.k1midi0.infinitesimal.world.CycleCatchUp
import io.github.k1midi0.infinitesimal.world.LONG_ABSENT_MARKER
import io.github.k1midi0.infinitesimal.world.WorldTime
import io.github.k1midi0.infinitesimal.world.longAbsent
import io.github.k1midi0.infinitesimal.world.refreshCyclesToClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **它自己走不回去**（§14.50、设计文档 §3.2.2 那条兜底）。
 *
 * 到点却进不去那条路（§14.45）管的是"它到了那一格、而那一格做不了"。这一支管的是另一种：**玩家把它
 * 锁起来、带走了**——它根本到不了那一格，于是永远没有任何一格会"到点"。
 *
 * 四件事按顺序验：
 *
 *  1. **它不被挪走**。把它放到酒馆柜台后面正是它做不到的那件事；把它挪过去等于这次兜底把世界改错了。
 *  2. 它变活跃（如果它本来还没活跃）、会话里写一行、**同步叫导演**。
 *  3. 导演写完之后它**仍然是活跃的**（这个人回不回得去是它自己的事），而"脱轨太久"这一笔账结了。
 *  4. **下一轮不再叫第二遍**——否则一个被关着的人会每一轮都把导演叫起来一次（钱在烧、世界不动）。
 *
 * 这一支**不联网**：哨兵那一层由这里的假 [Scheduling] 顶掉（"谁在这个点上卡住"由测试点名），与
 * [GameSessionStuckTest] 用的是同一套做法。
 */
class GameSessionLongAbsentTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    /** 只把"脱轨太久"那一半留着——地形那一半照跑（它零调用），哨兵一次都不问。 */
    private val onlyLongAbsence = Scheduling { world ->
        CycleCatchUp(world.refreshCyclesToClock() + world.longAbsent(60))
    }

    private fun sessionOf(script: Scripted) = GameSession(
        config,
        judge = Judge(LlmClient(config, script.client)),
        npc = Npc(LlmClient(config, script.client), Judge(LlmClient(config, script.client))),
        director = Director(LlmClient(config, script.client), TerrainGenerator(LlmClient(config, script.client))),
        scheduler = onlyLongAbsence,
        scheduleSettings = ScheduleSettings(activeTooLongMinutes = 60),
    )

    /**
     * 把矮人做成"很久以前被带走了"：它站在别处，而 `since` 是两小时前。
     *
     * **玩家也跟着它**——而这一条是必须的，不是顺手：`write_cycle` 有一道"你看得见它吗"的门
     * （`Director.writeCycle` 里那句 `id !in visible`），而它**是对的**（看不见就写不出坐标，只能靠猜）。
     * "被带走"这件事本来就意味着玩家也在那儿：是玩家把它带走的。
     */
    private fun takenAway(session: GameSession) {
        val dwarf = session.world.byId("dwarf")!!
        val player = session.world.byId("player")!!
        session.world.clock = WorldTime(400)
        session.world.put(
            dwarf.copy(x = 8000, y = 8000, active = Active(since = WorldTime(100), target = null)),
        )
        session.world.put(player.copy(x = 8100, y = 8000))
    }

    /** 导演被叫起来之后写一份新作息，然后这一轮是玩家说话、矮人答一句。 */
    private fun script(newBeats: String) = Scripted(
        listOf(
            turn("c1", DirectorTools.WRITE_CYCLE, """{"id":"dwarf","beats":"${esc(newBeats)}"}"""),
            stop(),
            turn("t1", "address", """{"id":"dwarf"}"""),
            turn("c2", "say", """{"text":"……"}"""),
            stop(),
        ),
    )

    @Test
    fun someoneTakenAwayIsNotMovedAndTheDirectorIsCalled() = runBlocking {
        val session = sessionOf(script("420｜900,320,12800｜在后厨生火。\n900｜1100,260,12800｜扫地。"))
        takenAway(session)

        session.runTurn("你好啊。", "")

        val after = session.world.byId("dwarf")!!
        assertEquals(8000, after.x, "**它没被挪到那一格去**——那正是它做不到的事")
        assertEquals(8000, after.y)
        assertNotNull(after.active, "它仍然是活跃的（导演只改作息，不管它回不回得去）")

        assertTrue(
            session.log.any { "离开自己的日子太久了" in it.text },
            "日志里要有那件事：${session.log.map { it.text }}",
        )
        assertTrue(
            session.world.log.all().none { "离开自己的日子太久" in it.text },
            "世界记录里不该留痕（它不是世界里发生过的一件事）",
        )
        // 导演真的写了：新作息从那一刻起跑。
        assertEquals(listOf(420, 900), after.cycle.beats.map { it.atMinute }, "新作息落了库")
        assertEquals(WorldTime(400), after.cycle.bornAt, "从写下去那一刻起跑")
    }

    @Test
    fun theSecondTurnDoesNotCallTheDirectorAgain() = runBlocking {
        // **判据不能是"请求数"，也不能是"某只手被递过去过几次"**：
        //  - 快速档（`Watching`）每一轮都调一次导演（那是另一件事）；
        //  - 硬圈缺口那条路也会同步叫思考档，而它也带着 `write_cycle`；
        //  - 而对整个请求体数 `"name"` 会把历史里那些旧调用一起算进来（§14.43 那条差一）。
        //
        // 所以看**导演实际读到的那一段话**：那一条兜底叫它起来时，理由里必然写着"离开自己的日子太久"。
        val scriptOf = script("420｜900,320,12800｜在后厨生火。\n900｜1100,260,12800｜扫地。")
        val session = sessionOf(scriptOf)
        takenAway(session)
        session.runTurn("你好啊。", "")
        assertEquals(1, session.log.count { "离开自己的日子太久了" in it.text }, "第一轮该报一次")
        assertTrue(
            scriptOf.bodies.any { LONG_ABSENT_MARKER in it },
            "前提：第一轮导演真的读到了那一段理由",
        )
        val askedFirst = scriptOf.bodies.count { LONG_ABSENT_MARKER in it }

        session.runTurn("还在吗？", "")

        assertEquals(
            1,
            session.log.count { "离开自己的日子太久了" in it.text },
            "那一行只写一次：${session.log.map { it.text }}",
        )
        assertEquals(
            askedFirst,
            scriptOf.bodies.count { LONG_ABSENT_MARKER in it },
            "第二轮不该再为它叫一次导演（那条理由不该再出现）",
        )
    }

    @Test
    fun whenTheDirectorWritesNothingItWillBeAskedAgainNextTurn() = runBlocking {
        // 导演沉默两次 = 这一趟失败了。**失败之后那一笔账要还回去**：否则这个人从此再也不会被报，
        // 而"没人再管它"是这一条兜底最坏的一种坏法。
        val silent = Scripted(listOf(stop(), stop(), turn("t1", "address", """{"id":"dwarf"}"""), turn("c1", "say", """{"text":"……"}"""), stop()))
        val session = sessionOf(silent)
        takenAway(session)

        session.runTurn("你好啊。", "")

        assertNotNull(session.world.byId("dwarf")!!.active, "它还在脱轨里")
        assertTrue(
            session.log.any { "没改成" in it.text },
            "失败要说出来：${session.log.map { it.text }}",
        )
    }

    @Test
    fun someoneWhoComesHomeIsForgettableAgain() = runBlocking {
        // ① 先被拿走一次、导演换成一份新作息；② **它自己走回去**（`arrive` 成功那条路，而那一行
        // `forgotten -= id` 挂在会话看到的 `Reaction.arrived` 上）；③ 再把它带走一次——它该被重新报上去。
        //
        // 这一支验的是那条"还账"：没有它，同一个人**第二趟**脱轨时不会再有人替它叫导演，而症状是
        // "这一次它就一直待在那儿了"——一条只在第二次发生时才看得见的静默失效。
        val walkBackIn = Scripted(
            listOf(
                turn("c1", DirectorTools.WRITE_CYCLE, """{"id":"dwarf","beats":"${esc(NEW_ROUTINE)}"}"""),
                stop(),
                turn("t1", "address", """{"id":"dwarf"}"""),
                turn("c2", NpcTools.ARRIVE, """{"state":"${esc("站在门口等着。")}"}"""),
                stop(),
            ),
        )
        val session = sessionOf(walkBackIn)
        takenAway(session)
        session.runTurn("你好啊。", "")

        // 那一份新作息第一格就在它脚底下，所以它此刻正好站在那一格上——`arrive` 报那一格的原文就能过。
        assertTrue(
            session.log.any { "回到了自己的日子上" in it.text },
            "前提：它真的走回去了（${session.log.map { it.text }}）",
        )
        assertEquals(null, session.world.byId("dwarf")!!.active, "前提：它不再是活跃的")

        // ② 再带走一次：`since` 又是很久以前。
        val home = session.world.byId("dwarf")!!
        session.world.clock = WorldTime(900)
        session.world.put(home.copy(active = Active(since = WorldTime(200), target = null)))
        val askedBefore = walkBackIn.bodies.count { LONG_ABSENT_MARKER in it }

        session.runTurn("你还好吗？", "")

        assertTrue(
            session.log.any { "离开自己的日子太久了" in it.text },
            "第二趟它该被重新报上去：${session.log.map { it.text }}",
        )
        // **那一趟只报一次**（同一次脱轨里 `catchUpCycles` 会被调两遍），所以第二趟必然要多出**正好
        // 一次**——多出来的那一笔就是"账还掉了"的证据。
        assertTrue(
            walkBackIn.bodies.count { LONG_ABSENT_MARKER in it } > askedBefore,
            "第二趟该再叫一次导演：报过 $askedBefore 次，现在是 " +
                "${walkBackIn.bodies.count { LONG_ABSENT_MARKER in it }} 次",
        )
    }

    private companion object {
        /** 导演写的那一份新作息：**第一格就在它此刻站的地方**，于是它可以就地回去。 */
        const val NEW_ROUTINE = "0｜8000,8000,12800｜站在门口等着。\n600｜8100,8000,12800｜挪两步。"
    }
}
