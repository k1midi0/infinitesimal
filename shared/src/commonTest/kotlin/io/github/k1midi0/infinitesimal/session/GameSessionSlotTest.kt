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
import io.github.k1midi0.infinitesimal.schedule.Scheduler
import io.github.k1midi0.infinitesimal.schedule.SentryJudge
import io.github.k1midi0.infinitesimal.world.Active
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.Cycle
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.TimelineEntry
import io.github.k1midi0.infinitesimal.world.WorldDepth
import io.github.k1midi0.infinitesimal.world.WorldTime
import io.github.k1midi0.infinitesimal.world.insertIntoQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **一回合 = 一格**（§14.51）。
 *
 * 这一刀把三样东西一起改了，而这一支要钉住的正是它们之间的关系：
 *
 *  1. **时间会走了**：一格 = `1440 / turnsPerDay` 分钟，默认十五分钟。「下一轮」推一格，「过 N 格」推
 *     N 格（碰上时间轴上有事到期就停在那一刻）。
 *  2. **一格里谁行动**：玩家 + 活跃的角色 + 剧情角色，**按发言队列的顺序**依次；气氛角色（有循环又
 *     没被叫住）**一律不参与**——那一格不该问他们任何事。
 *  3. **说话不占回合**：即时问答、不推时间；而**它说过的话下一格周围的人看得到**（总线往前一格）。
 *
 * 这一支**不联网**：判定与 NPC 都接在 [Scripted] 那个假端点上，而**哨兵也接在它上面**（不是塞一个假
 * 的 `Scheduling`）——因为这里好几支要断言"一次调用都没发生"，而那一句话只有在真 Scheduler 真的跑过
 * 一遍之后才算数。
 */
class GameSessionSlotTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private val ground = WorldDepth().surfaceCm

    private fun sessionOf(script: Scripted, settings: ScheduleSettings = ScheduleSettings()) = GameSession(
        config,
        judge = Judge(LlmClient(config, script.client)),
        npc = Npc(LlmClient(config, script.client), Judge(LlmClient(config, script.client))),
        // **导演也要接在假端点上**（§14.52 才发现这一格漏了）：人设追加那条路会同步叫思考档，而默认
        // 那个 `Director(config, …)` 会拿真 key 去碰端点——症状是"人设没写成：认证失败"，看着像逻辑错。
        director = Director(LlmClient(config, script.client), TerrainGenerator(LlmClient(config, script.client))),
        scheduler = Scheduler(SentryJudge(LlmClient(config, script.client))),
        scheduleSettings = settings,
    )

    /** 把矮人从它的日子上拉下来：脱轨的人**参与**这一格。 */
    private fun wakeTheDwarfUp(session: GameSession) {
        val dwarf = session.world.byId("dwarf")!!
        session.world.put(
            dwarf.copy(
                active = Active(since = session.world.clock, target = dwarf.cycle.beatAt(session.world.clock)),
            ),
        )
    }

    /** 放一个没有循环的角色进来：**剧情 NPC 也参与**（它与活跃角色的区别只是"没有日子可回"）。 */
    private fun putAStoryNpc(session: GameSession, id: String = "bard") {
        session.world.put(
            Entity(
                id = id,
                kind = CHARACTER_KIND,
                label = "弹琴的吟游诗人",
                x = 1300, y = 500, z = ground,
                cycle = Cycle(),
            ),
        )
    }

    /** 把一串请求体压成一行，好让断言失败时说得出"多出来的是哪一次"。 */
    private fun Scripted.summary(): String = bodies.mapIndexed { i, body ->
        val cut = body.indexOf("\"messages\"").let { if (it < 0) body.length else it }
        val tools = Regex("\"name\"\\s*:\\s*\"([a-z_]+)\"").findAll(body.take(cut))
            .map { it.groupValues[1] }.toSet().sorted()
        val kind = when {
            "这一格到了" in body -> "tick"
            "【你为什么现在醒着】" in body -> "npc"
            "判定" in body || "这次动作" in body -> "judge"
            else -> "?"
        }
        "$i=$kind${tools}"
    }.joinToString("｜")

    // ── 谁参与这一格 ───────────────────────────────────────────────────────────────

    /**
     * **气氛角色不在这一格里。** 矮人此刻在走它自己的循环（18:00 那一格，而它就站在那一格的坐标上），
     * 所以"这一格要做什么"这个问题不该问它——一次调用都不该有。
     *
     * 这条同时是"时间开始走"之后最容易悄悄破掉的：只要谁往那一格里多叫一声，成本就从"零"变成"每人每格
     * 一次"，而症状是钱在烧、画面上什么都看不出来。
     */
    @Test
    fun aRoundWithNobodyAwakeCostsNothing() = runBlocking {
        val script = Scripted(listOf(stop()))
        val session = sessionOf(script)

        session.nextTurnNow("")

        assertEquals(WorldTime.of(1, 18) + 15, session.world.clock, "一格 = 十五分钟")
        assertEquals(0, script.bodies.size, "矮人在走循环 → 这一格一次调用都没有")
    }

    @Test
    fun anActiveNpcGetsItsOwnTurnAndTheReasonIsTheNewTickOne() = runBlocking {
        val script = Scripted(listOf(stop()))
        val session = sessionOf(script)
        wakeTheDwarfUp(session)

        session.nextTurnNow("")

        assertEquals(1, script.bodies.size, "活跃的矮人参与这一格")
        assertTrue(
            "这一格到了" in script.bodies.single(),
            "它该知道自己是为什么醒的（不是有人在对它说话）：${script.bodies.single().take(300)}",
        )
    }

    @Test
    fun aStoryNpcIsPartOfTheRoundToo() = runBlocking {
        val script = Scripted(listOf(stop()))
        val session = sessionOf(script)
        putAStoryNpc(session)

        session.nextTurnNow("")

        assertEquals(1, script.bodies.size, "剧情角色（没有循环）也过它这一格")
        assertTrue("这一格到了" in script.bodies.single())
    }

    /**
     * **顺序由那个队列定，而玩家也在队列里。**
     *
     * 两种排法各跑一次：玩家在前，则判定先跑（他的动作先落库）；矮人在前，则它的这一格先跑。这是"玩家
     * 也是角色的一员，也有自己在顺序里的位置"那一条落成的形状。
     */
    @Test
    fun theQueueDecidesWhetherThePlayerActsBeforeOrAfterThem() = runBlocking {
        val sameIntent = "我擦一下桌子"
        // **判定第一步那只手是 `target`，而且勾选要真的包含它**：不然 `alignment.targets` 与玩家勾的
        // 对不上，判定会挂起纠正预览（世界还没动，而这一格也没走完）。
        val actFrames = listOf(
            turn("t1", "target", """{"id":"oak_table"}"""),
            turn("e1", "set_attribute", """{"id":"oak_table","key":"状态","value":"被擦过"}"""),
        )
        fun looksLikeTheJudge(body: String) = sameIntent in body
        fun looksLikeTheTick(body: String) = "这一格到了" in body

        // ① 玩家排第一（`StartingWorld` 现在就是这么排的）
        val playerFirst = Scripted(actFrames + listOf(stop()))
        val first = sessionOf(playerFirst)
        first.click("oak_table")
        wakeTheDwarfUp(first)
        first.nextTurnNow(sameIntent)
        assertEquals(3, playerFirst.bodies.size, "玩家判定两步 + 矮人一次：${playerFirst.summary()}")
        assertTrue(looksLikeTheJudge(playerFirst.bodies[0]), "玩家先")
        assertTrue(looksLikeTheTick(playerFirst.bodies[2]), "矮人在后")

        // ② 把矮人插到玩家前面（`insertIntoQueue`：先摘掉再插，后面自动顺延）
        val dwarfFirst = Scripted(listOf(stop()) + actFrames)
        val second = sessionOf(dwarfFirst)
        second.click("oak_table")
        wakeTheDwarfUp(second)
        second.world.insertIntoQueue("dwarf", 0)
        second.nextTurnNow(sameIntent)
        assertEquals(3, dwarfFirst.bodies.size)
        assertTrue(looksLikeTheTick(dwarfFirst.bodies[0]), "矮人先")
        assertTrue(looksLikeTheJudge(dwarfFirst.bodies[1]), "玩家在后")
    }

    // ── 说话：即时、不推时间、下一格看得见 ─────────────────────────────────────────

    @Test
    fun talkingIsImmediateAndDoesNotMoveTheClock() = runBlocking {
        val script = Scripted(
            listOf(
                turn("t1", "address", """{"id":"dwarf"}"""),
                turn("c1", "say", """{"text":"……"}"""),
                stop(),
            ),
        )
        val session = sessionOf(script)
        val before = session.world.clock

        session.sayNowNow("你好啊。")

        assertEquals(before, session.world.clock, "说话不过轮次：世界停在原处")
        // **三次是下限，不是上限漏了**：一次找听者（判定第一步），然后被指向的人**两次**——它先出手
        // 说那句话（一次工具调用），而工具调用之后那一轮请求是收尾（§5 那一轮的结构）。
        assertEquals(3, script.bodies.size, "一次找听者 +（出手 + 收尾）")
        // 原话原样进了记录层（用户裁定：「结构化一行，原话原样，代码拼」）——最后那个非空记录是矮人
        // 那一句，所以这里找的是"有没有"。
        assertTrue(
            session.world.log.all().any { it.text == "你好啊。" },
            "说过的话要在记录层里：${session.world.log.all().map { it.text }}",
        )
    }

    /**
     * **被指向之后它就不走循环了**（§3.2.1）。
     *
     * 这一条从前从来没做过：说完话它下一轮照样被作息挪走。而时间开始走之后，那意味着**说着说着人去
     * 后厨了**——所以它现在要变活跃、留在原地。
     */
    @Test
    fun beingAddressedTakesItOffItsCycle() = runBlocking {
        val script = Scripted(
            listOf(
                turn("t1", "address", """{"id":"dwarf"}"""),
                turn("c1", "say", """{"text":"……"}"""),
                stop(),
            ),
        )
        val session = sessionOf(script)

        session.sayNowNow("你站住。")

        assertTrue(session.world.byId("dwarf")!!.active != null, "它被叫住了，于是不再走循环")
    }

    /**
     * **它自己回到循环上那一刻，导演把它这一趟写进人设**（§14.52、§3.2.1 的第 1 件事）。
     *
     * 这是"人设追加"那条链在会话这一层的全貌：角色在一格里用 `arrive` 宣告回去 → 会话看到
     * `Reaction.arrived` → **同步叫一次思考档导演**（`Wake.Called`，理由里点名是谁）→ 导演用
     * `write_card` 写 → 会话记一行"它的人设变了什么"。
     *
     * 那一行日志是有用的：卡变没变只有代码读得出来，而"这一次回归在它人设上留了什么"不该只能靠翻
     * 提示词才知道。
     */
    @Test
    fun whenSomebodyComesHomeTheDirectorWritesThatTripIntoItsCards() = runBlocking {
        val session = sessionOf(Scripted(listOf(stop())))
        wakeTheDwarfUp(session)
        val beat = session.world.byId("dwarf")!!.cycle.beatAt(session.world.clock)!!

        // 帧要按调用顺序：① 矮人过它这一格 → 用 arrive 宣告回去（出手 + 收尾两次请求）；
        // ② 会话因此叫导演 → 导演 write_card（出手 + 收尾两次请求）。
        val script = Scripted(
            listOf(
                turn("a1", NpcTools.ARRIVE, """{"state":"${esc(beat.state)}"}"""),
                stop(),
                turn(
                    "w1",
                    DirectorTools.WRITE_CARD,
                    """{"id":"dwarf","cards":"${esc("经历｜这一趟你在外头待了很久，回来时把门口那把椅子扶正了。")}"}""",
                ),
                stop(),
            ),
        )
        val live = sessionOf(script)
        wakeTheDwarfUp(live)
        val before = live.world.byId("dwarf")!!.cards.size

        live.nextTurnNow("")

        val after = live.world.byId("dwarf")!!
        assertEquals(null, after.active, "它回去了")
        assertEquals(before + 1, after.cards.size, "人设多了一张：${live.log.map { it.text }}")
        assertEquals("经历", after.cards.last().title)
        assertTrue(
            live.log.any { "它的人设" in it.text },
            "会话里要说得出人设变了什么：${live.log.map { it.text }}",
        )
        assertTrue(
            script.bodies.any { "把它这一趟的经历写进它的人设" in it },
            "叫导演那一次的理由里要点名这件事",
        )
    }

    /**
     * **这一格说的话，下一格周围的人看得到**（用户定的）。
     *
     * 形状上的要害是那条总线的起点：给"这一格"的唤醒用的是**上一格**的起点（`previousTurnStartSeq`）。
     * 只用本格起点的话，上一格说过的话对这个角色**不存在**——`world.bus` 是按 seq 截的，而那一条已经
     * 落在截断之前。
     */
    @Test
    fun theNextRoundSeesWhatWasSaidInThisOne() = runBlocking {
        val sentence = "记住：阿黛尔答应周一还我三枚银币"
        val script = Scripted(
            listOf(
                turn("t1", "address", """{"id":"dwarf"}"""),   // 说话：找听者
                turn("c1", "say", """{"text":"记下了。"}"""),   // 矮人回一句
                stop(),
                stop(),                                        // 下一格：矮人过它这一格
            ),
        )
        val session = sessionOf(script)

        session.sayNowNow(sentence)
        session.nextTurnNow("")

        val tick = script.bodies.last()
        assertTrue("这一格到了" in tick, "最后那一次是矮人过这一格")
        assertTrue(
            sentence in tick,
            "上一格说过的话要在它的总线里：${tick.substringAfter("【这一片里刚刚发生的事】", "").take(200)}",
        )
    }

    // ── 过 N 格 ────────────────────────────────────────────────────────────────────

    @Test
    fun passingSlotsIsFreeAndJustMovesTheClock() = runBlocking {
        val script = Scripted(listOf(stop()))
        val session = sessionOf(script)

        session.passSlotsNow(4)

        assertEquals(WorldTime.of(1, 18) + 60, session.world.clock, "四格 = 一小时")
        assertEquals(0, script.bodies.size, "过时间不叫任何人")
    }

    /**
     * **碰上第一件到期的事就停下**（用户定的）。
     *
     * 与 [FastForward.run] 那句"停下来意味着该有人看一眼了"是同一条：剩下的格子留给玩家——他得先看
     * 一眼发生了什么，再决定要不要接着过。
     */
    @Test
    fun passingSlotsStopsAtTheNextThingThatFallsDue() = runBlocking {
        val script = Scripted(listOf(stop()))
        val session = sessionOf(script)
        val due = WorldTime.of(1, 18) + 20
        session.world.timeline.put(TimelineEntry(id = "t1", dueAt = due, what = "北门有一支队伍经过"))

        session.passSlotsNow(4)

        assertEquals(due, session.world.clock, "停在那一刻，而不是走满四格")
        assertTrue(
            session.log.any { "北门有一支队伍经过" in it.text },
            "到期的那些要报出来：${session.log.map { it.text }}",
        )
        assertEquals(0, script.bodies.size, "过时间仍然一次调用都没有")
    }

    // ── 那个换算本身 ───────────────────────────────────────────────────────────────

    @Test
    fun aDayIsSplitIntoWholeMinutes() {
        assertEquals(15L, ScheduleSettings(turnsPerDay = 96).minutesPerTurn)
        assertEquals(60L, ScheduleSettings(turnsPerDay = 24).minutesPerTurn)
        assertEquals(1440L, ScheduleSettings(turnsPerDay = 1).minutesPerTurn)

        // **除不尽要当面拒绝**：1440 / 7 = 205.7，而"约 205 分钟"会让作息表与世界钟对不上，且那种错位
        // 是静默的（作息按相对分钟查表，不报错）。
        val refused = assertFailsWith<IllegalArgumentException> { ScheduleSettings(turnsPerDay = 7) }
        assertTrue("1440" in refused.message.orEmpty(), "理由要说清是哪条约束：${refused.message}")
    }
}
