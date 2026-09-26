package io.github.k1midi0.infinitesimal.schedule

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.Cycle
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.TerrainBox
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import io.github.k1midi0.infinitesimal.world.terrainCellOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **哨兵那一半**：地形过了之后，那一格还做不做得了（§14.45）。
 *
 * ## 时间在这一支里要按相位算，不能写死
 *
 * 开局那份作息的 [Cycle.bornAt] 是**世界起点**（§14.44），而它的第一格落在第 420 分钟——所以
 * `clock = 600` 的相位其实是 `600 - bornAt`，读出来是**早上那一格**，不是傍晚那一格。写死一个钟点
 * 就等于把"相位"这件事交给运气（这一支第一版就是这么错的：断言写 (1000, 200)，实得 (900, 320)）。
 * [phaseClock] 反过来算：**我要相位 600，时钟就该是这个数。**
 *
 * 这一支钉五件事：
 *
 *  1. 它说"做得了" → 人挪过去（那一次调用仍然发生了，只是结果是放行）。
 *  2. 它说"做不了" → 人**不挪**，而它给的那句理由进结果、交给导演。
 *  3. 它没给答复（协议空转、解析失败）→ **按"做不了"算**。反过来算的话，一个进不去的人会每一轮
 *     都被重新问一次，症状是钱在烧而世界一动不动。
 *  4. 请求里**思考是明确关掉的**，而且只有一个工具——它不是判定，也不是导演。
 *  5. **地形那一半先跑**：站不住的人一次都不问。
 */
class SchedulerTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    /** 让某个人那份循环的相位落在 [phase] 上。 */
    private fun phaseClock(world: World, id: String, phase: Int): WorldTime {
        val cycle = world.byId(id)!!.cycle
        return cycle.bornAt + phase.toLong() % cycle.periodMinutes
    }

    /** 相位 600 落在哪一格——断言里的坐标全部从这里来，不写死。 */
    private fun beatAt(world: World, id: String, phase: Int): Beat =
        world.byId(id)!!.cycle.beatAt(phaseClock(world, id, phase))!!

    /**
     * 开局那份世界，时钟拨到相位 600，而**人站在那一格旁边**（一百厘米）。
     *
     * 位置不能再写 (5000, 5000)：§14.47 之后，那一格离得太远、又不在那一片的凸包里时，代码**直接放行、
     * 一次都不问**。要问出哨兵那一句，人必须在"就在它眼前"的范围里——而那正是这条判据真正管着的情形。
     */
    private fun world(): World {
        val w = World.from(StartingWorld.snapshot())
        val target = beatAt(w, "dwarf", 600)
        w.put(w.byId("dwarf")!!.copy(x = target.x + 100, y = target.y, z = target.z))
        w.clock = phaseClock(w, "dwarf", 600)
        return w
    }

    /** 人在五公里外的那一份：这是 §14.47 那道门槛说的"那一格不在它眼前"。 */
    private fun worldFarAway(): World {
        val w = World.from(StartingWorld.snapshot())
        w.put(w.byId("dwarf")!!.copy(x = 5000, y = 5000))
        w.clock = phaseClock(w, "dwarf", 600)
        return w
    }

    private fun schedulerOf(script: Scripted) =
        Scheduler(SentryJudge(LlmClient(config, script.client)))

    private fun report(possible: Boolean, reason: String) =
        turn("s1", "report", """{"possible":$possible,"reason":"${reason}"}""")

    @Test
    fun whenItSaysTheBeatStillWorksTheWalkIsClearedToHappen() = runBlocking {
        val script = Scripted(listOf(report(true, "没什么不对的。")))
        val world = world()
        val target = beatAt(world, "dwarf", 600)

        val caught = schedulerOf(script).catchUp(world)

        // **放行 = 这一条说"该挪过去"**，而真正落坐标的是会话那一层（§14.45）。
        assertTrue(caught.stuck.isEmpty())
        assertEquals(1, caught.moved.size)
        assertEquals(target.x, caught.moved.first().beat.x)
        assertEquals(target.x + 100, world.byId("dwarf")!!.x, "刷新只报不写")
        assertEquals(null, world.byId("dwarf")!!.active, "它还在走循环，没被拿下来")
    }

    @Test
    fun whenItSaysThePlaceIsGoneTheReasonIsCarriedOut() = runBlocking {
        val script = Scripted(listOf(report(false, "酒馆的大厅已经烧成一片焦黑，屋梁塌在门口。")))
        val world = world()
        val target = beatAt(world, "dwarf", 600)

        val caught = schedulerOf(script).catchUp(world)

        val dwarf = world.byId("dwarf")!!
        assertEquals(target.x + 100, dwarf.x, "**没去成就不挪**——放到那一格上是会话那一层的事")
        assertEquals(1, caught.stuck.size)
        val reason = caught.stuck.first().reason.orEmpty()
        assertTrue("酒馆的大厅已经烧成一片焦黑" in reason, "那句理由要原样带着：$reason")
        assertTrue("${target.x}, ${target.y}" in reason, "还要说清那一格在哪儿：$reason")
        assertEquals(null, dwarf.active, "变活跃不是它的事——那是会话那一层")
    }

    @Test
    fun anAnswerItCannotParseCountsAsNo() = runBlocking {
        // 一帧**没有工具调用**：模型只说了一句话。协议上它是空转，而这里没有重试。
        val script = Scripted(listOf(stop("我不知道。")))
        val world = world()
        val before = world.byId("dwarf")!!.x

        val caught = schedulerOf(script).catchUp(world)

        assertEquals(before, world.byId("dwarf")!!.x, "空转之后它还在原地")
        assertEquals(1, caught.stuck.size)
        assertTrue("先按进不去算" in caught.stuck.first().reason.orEmpty())
    }

    @Test
    fun theRequestIsASingleToolWithThinkingExplicitlyOff() = runBlocking {
        val script = Scripted(listOf(report(true, "没事。")))
        schedulerOf(script).catchUp(world())

        val body = script.bodies.first()
        assertTrue("\"report\"" in body, "要带着那只手")
        assertTrue("\"type\":\"disabled\"" in body, "思考必须显式关掉（§14.12）")
        assertEquals(1, Regex("\"type\":\"function\"").findAll(body).count(), "只有一只手——它不是判定，也不是导演")
    }

    @Test
    fun aDwarfWhoIsAlreadyThereIsNotAskedAtAll() = runBlocking {
        val script = Scripted(listOf(report(false, "这一步永远不会被问到。")))
        val world = World.from(StartingWorld.snapshot())
        // 相位 0 落在**最后一格**上（23:00、1100,260），所以站得正好的位置是那一格，不是开局那个点。
        world.clock = phaseClock(world, "dwarf", 0)
        val last = beatAt(world, "dwarf", 0)
        val dwarf = world.byId("dwarf")!!
        world.put(dwarf.copy(x = last.x, y = last.y, z = last.z))

        val caught = schedulerOf(script).catchUp(world)

        assertTrue(caught.placements.isEmpty(), "没变化就没有 placements")
        assertEquals(0, script.bodies.size, "**一次都没问**——这是成本闸")
    }

    @Test
    fun aCharacterWithoutACycleIsNeverAsked() = runBlocking {
        val script = Scripted(listOf(report(true, "没事。")))
        val world = World.empty()
        world.put(Entity(id = "dwarf", kind = CHARACTER_KIND, label = "矮人", x = 0, y = 0, z = 0, cycle = Cycle()))

        val caught = schedulerOf(script).catchUp(world)

        assertTrue(caught.placements.isEmpty())
        assertEquals(0, script.bodies.size)
    }

    @Test
    fun theTerrainHalfBlocksBeforeASingleCall() = runBlocking {
        // 地形先判：站不住的人**一次都不问**（那一半是免费的）。
        val script = Scripted(listOf(report(true, "这一步永远不会被问到。")))
        val world = world()
        val target = beatAt(world, "dwarf", 600)
        val x = terrainCellOf(target.x)
        val y = terrainCellOf(target.y)
        val z = terrainCellOf(target.z)
        world.terrain.dig(TerrainBox(x1 = x, y1 = y, z1 = 0, x2 = x, y2 = y, z2 = z), materials = world.materials)
        world.terrain.fill(
            TerrainBox(x1 = x, y1 = y, z1 = z, x2 = x, y2 = y, z2 = z),
            materialId = "water",
            materials = world.materials,
        )

        val caught = schedulerOf(script).catchUp(world)

        assertEquals(1, caught.stuck.size, "它该被拦下来")
        assertEquals(0, script.bodies.size, "地形拦下了就不该再花一次调用")
        assertTrue("站不住" in caught.stuck.first().reason.orEmpty())
    }

    // ── 那一格不在它眼前（§14.47） ────────────────────────────────────────────────────

    /**
     * **那一格在远处时，代码直接放行、一次都不问。**
     *
     * 这是实测逮到的读错：人在酒馆里，而那一格在镇子另一头——哨兵答"这一格上立着酒馆的柜台，
     * 摆不开一个鱼摊"，它拿**脚下那一格**去答了"那一格做得了吗"。提示词压不住它（它并不觉得自己
     * 看不到：眼前明明有一片清单），所以那条规矩改由代码执行。
     *
     * 放行的正当性在于：**那一格本身站不住不归它管**——那是免费的那一半（`canStandAt`），跑在它之前。
     * 它只管"那一格就在它眼前，而眼前看得出这件事做不了了"。
     */
    @Test
    fun aBeatThatIsNowhereNearItsEyesIsClearedWithoutAskingAnyone() = runBlocking {
        val script = Scripted(listOf(report(false, "这一帧永远不该被用到。")))
        val world = worldFarAway()

        val caught = schedulerOf(script).catchUp(world)

        assertTrue(caught.stuck.isEmpty(), "看不到就不当它出事")
        assertEquals(1, caught.moved.size, "该挪过去还是挪过去")
        assertEquals(0, script.bodies.size, "**一次都没问**")
    }

    @Test
    fun theBeatAndWhereItStandsAreBothNamedInThePrompt() = runBlocking {
        val script = Scripted(listOf(report(true, "没什么不对的。")))
        val world = world()
        val target = beatAt(world, "dwarf", 600)
        val dwarf = world.byId("dwarf")!!

        schedulerOf(script).catchUp(world)

        val body = script.bodies.first()
        assertTrue("【先说清你在看哪一处】" in body, "两处要先分开写：\n$body")
        assertTrue("(${target.x}, ${target.y}, ${target.z})" in body, "那一处要有坐标：\n$body")
        assertTrue("(${dwarf.x}, ${dwarf.y}, ${dwarf.z})" in body, "它此刻在哪儿也要有：\n$body")
        assertTrue("判的是那一处，不是它脚下那一格" in body, "规矩要写在它读得到的地方：\n$body")
    }
}
