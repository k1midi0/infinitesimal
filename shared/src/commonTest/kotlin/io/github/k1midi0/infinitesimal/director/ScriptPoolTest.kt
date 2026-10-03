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
import io.github.k1midi0.infinitesimal.world.Script
import io.github.k1midi0.infinitesimal.world.ScriptState
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **剧本池**（§8.4、§14.54）。
 *
 * 这一支钉四件事：
 *
 *  1. **池子是真世界状态**：开局就有一条种子（`StartingWorld.scripts()`），它随快照往返。
 *  2. **五态照字读**（"未安排/已安排/运行/中断/完结"，也认枚举名），读不懂就驳回。
 *  3. **两手上限**：份数 8、摘要 200 字、正文 2000 字；**改写已有的那一条不受份数限制**（那正是
 *     "线太多"的出路）。
 *  4. **批注只追加**：写正文**保留**批注；批注满了没有删除这条路，所以驳回的理由是"这条线该完结了"。
 */
class ScriptPoolTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun world(): World = World.from(StartingWorld.snapshot())

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: io.github.k1midi0.infinitesimal.world.ChunkKey(0, 0))

    private fun director(script: Scripted) = Director(
        LlmClient(config, script.client),
        TerrainGenerator(LlmClient(config, script.client)),
    )

    private fun watching(world: World) = Waking(Wake.Watching("玩家动了动。"), "player", origin(world))

    private fun doneSaying(text: String = "好。") = sse(
        """{"choices":[{"delta":{"content":"${text}"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
    )

    // ── 数据层 ─────────────────────────────────────────────────────────────────────

    @Test
    fun theOpeningWorldCarriesOneSeedScript() {
        val pool = world().scripts
        assertEquals(1, pool.size, "开局挂一条种子——空池子的话，剧本池在真游戏里一次都不会被走到")
        assertEquals("地窖底下那条道", pool.single().id)
        assertEquals(ScriptState.UNARRANGED, pool.single().state, "舞台还没搭，所以是未安排")
        assertTrue(pool.single().body.length > pool.single().summary.length, "正文比摘要长")
    }

    @Test
    fun thePoolRidesTheSnapshot() {
        val w = world()
        ScriptPlanner.annotate(w, "地窖底下那条道", "玩家往下看了一眼。", WorldTime(600))
        val back = World.from(w.snapshot())
        assertEquals(1, back.scripts.single().notes.size, "批注随快照往返")
        assertEquals("玩家往下看了一眼。", back.scripts.single().notes.single().text)
    }

    @Test
    fun theFiveStatesReadFromTheirLabels() {
        assertEquals(ScriptState.UNARRANGED, ScriptState.of("未安排"))
        assertEquals(ScriptState.ARRANGED, ScriptState.of("已安排"))
        assertEquals(ScriptState.RUNNING, ScriptState.of("运行"))
        assertEquals(ScriptState.PAUSED, ScriptState.of("中断"))
        assertEquals(ScriptState.DONE, ScriptState.of("完结"))
        assertEquals(ScriptState.DONE, ScriptState.of("done"), "枚举名也认")
        assertEquals(null, ScriptState.of("放弃"), "去掉的那一态不认")
        assertEquals(null, ScriptState.of(""))
    }

    // ── 校验 ───────────────────────────────────────────────────────────────────────

    private fun write(
        id: String = "圣剑择主",
        title: String = "圣剑拔出的日子",
        summary: String = "公告上贴着日子，而圣剑只认勇者。玩家可能去拔，也可能走掉。",
        body: String = "镇公告栏上多了一张纸……",
        state: ScriptState = ScriptState.UNARRANGED,
    ) = ScriptWrite(id, title, summary, body, state)

    @Test
    fun aScriptWithoutASummaryIsRefused() {
        val problems = ScriptPlanner.problems(world(), listOf(write(summary = "")))
        assertTrue(problems.single().contains("摘要"), problems.single())
    }

    @Test
    fun aSummaryThatIsTooLongIsRefused() {
        val problems = ScriptPlanner.problems(world(), listOf(write(summary = "字".repeat(201))))
        assertTrue(problems.single().contains("摘要太长了"), problems.single())
        assertTrue("201" in problems.single() && "200" in problems.single(), problems.single())
    }

    @Test
    fun thePoolHasRoomForEightAndRewritingIsAlwaysAllowed() {
        val w = world()
        // 把池子填满（种子之外再添七条）。
        repeat(7) { i ->
            ScriptPlanner.apply(w, listOf(write(id = "线$i", title = "线 $i")))
        }
        assertEquals(8, w.scripts.size)

        val refused = ScriptPlanner.problems(w, listOf(write(id = "线9", title = "第九条")))
        assertTrue(refused.single().contains("添不下新的"), refused.single())
        assertTrue("完结" in refused.single(), "出路要在理由里：${refused.single()}")

        // **改写已有的那一条永远可以**——那正是"线太多"的出路之一。
        assertTrue(ScriptPlanner.problems(w, listOf(write(id = "线3", title = "线 3 改了"))).isEmpty())
    }

    @Test
    fun rewritingKeepsTheNotes() {
        val w = world()
        ScriptPlanner.annotate(w, "地窖底下那条道", "玩家扒了两块砖。", WorldTime(600))
        ScriptPlanner.apply(w, listOf(write(id = "地窖底下那条道", state = ScriptState.RUNNING)))

        val after = w.scripts.first { it.id == "地窖底下那条道" }
        assertEquals(ScriptState.RUNNING, after.state, "状态换掉了")
        assertEquals(1, after.notes.size, "**批注留着**——正文与批注是两份东西（§8.4）")
        assertEquals("玩家扒了两块砖。", after.notes.single().text)
    }

    @Test
    fun aScriptThatIsNotThereCannotBeAnnotated() {
        val problems = ScriptPlanner.noteProblems(world(), "没有这条", "记一句。")
        assertTrue(problems.single().contains("没有 id 是"), problems.single())
    }

    @Test
    fun aNoteThatIsTooLongIsRefused() {
        val problems = ScriptPlanner.noteProblems(world(), "地窖底下那条道", "字".repeat(201))
        assertTrue(problems.single().contains("批注太长了"), problems.single())
    }

    @Test
    fun aFullLogOfNotesSaysTheLineShouldBeFinished() {
        val w = world()
        repeat(ScriptPlanner.MAX_SCRIPT_NOTES) { i ->
            ScriptPlanner.annotate(w, "地窖底下那条道", "第 $i 条。", WorldTime(600L + i))
        }
        val problems = ScriptPlanner.noteProblems(w, "地窖底下那条道", "再来一条。")
        assertTrue(problems.single().contains("记满"), problems.single())
        assertTrue("完结" in problems.single(), "批注删不了，所以出路只能是完结它：${problems.single()}")
    }

    // ── 导演那一侧 ─────────────────────────────────────────────────────────────────

    @Test
    fun aScriptIsWrittenAndTheStateIsRead() = runBlocking {
        val script = Scripted(
            listOf(
                turn(
                    "c1",
                    DirectorTools.WRITE_SCRIPT,
                    """{"id":"圣剑择主","title":"${esc("圣剑拔出的日子")}",""" +
                        """"summary":"${esc("公告上贴着日子，而圣剑只认勇者。")}",""" +
                        """"body":"${esc("镇公告栏上多了一张纸……")}","state":"未安排"}""",
                ),
                doneSaying(),
            ),
        )
        val world = world()
        director(script).wake(world, Waking(Wake.Arrange, "player", origin(world)))

        val fresh = world.scripts.first { it.id == "圣剑择主" }
        assertEquals("圣剑拔出的日子", fresh.title)
        assertEquals(ScriptState.UNARRANGED, fresh.state)
        assertEquals(2, world.scripts.size, "种子之外添了一条")
    }

    @Test
    fun aStateThatCannotBeReadIsRefusedWithTheFiveOptions() = runBlocking {
        val script = Scripted(
            listOf(
                turn(
                    "c1",
                    DirectorTools.WRITE_SCRIPT,
                    """{"id":"x","title":"甲","summary":"乙","body":"丙","state":"放弃"}""",
                ),
                doneSaying(),
            ),
        )
        val world = world()
        director(script).wake(world, Waking(Wake.Arrange, "player", origin(world)))

        assertEquals(1, world.scripts.size, "驳回之后一条都没添")
        assertTrue("五态是" in script.bodies[1], "理由要把五个状态写出来：${script.bodies[1].take(200)}")
    }

    @Test
    fun lookingAtAScriptGivesTheBodyAndTheNotes() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", DirectorTools.LOOK_SCRIPT, """{"id":"地窖底下那条道"}"""),
                doneSaying(),
            ),
        )
        val world = world()
        director(script).wake(world, Waking(Wake.Arrange, "player", origin(world)))

        val back = script.bodies[1]
        assertTrue("旧排水沟" in back || "醉猫" in back, "正文要读得到：${back.take(300)}")
        assertTrue("未安排" in back, "状态也要带上")
    }

    @Test
    fun theFastTierMayAnnotateButMayNotRewrite() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", DirectorTools.ANNOTATE_SCRIPT, """{"id":"地窖底下那条道","text":"玩家往地窖看了一眼。"}"""),
                doneSaying(),
            ),
        )
        val world = world()
        director(script).wake(world, watching(world))

        val after = world.scripts.first { it.id == "地窖底下那条道" }
        assertEquals(1, after.notes.size, "快速档能往里追加")
        assertEquals(WorldTime.of(1, 18), after.notes.single().at, "时刻由代码取（此刻的世界钟）")

        val fast = DirectorTools.of(Tier.FAST).map { it.function.name }
        assertTrue(DirectorTools.ANNOTATE_SCRIPT in fast, "快速档的那只批注手：$fast")
        assertFalse(DirectorTools.WRITE_SCRIPT in fast, "**改写不给快速档**（§1.4）")
        assertFalse(DirectorTools.LOOK_SCRIPT in fast, "看正文也不给——快速档只看着")
    }

    @Test
    fun bothTiersSeeThePoolButNotTheBody() = runBlocking {
        val arrange = Scripted(listOf(doneSaying()))
        val w1 = world()
        director(arrange).wake(w1, Waking(Wake.Arrange, "player", origin(w1)))
        assertTrue("【剧本池】" in arrange.bodies.first(), "思考档要看池子")
        assertTrue("旧排水沟" in arrange.bodies.first(), "标题与摘要要在清单里")
        assertFalse("醉猫的地窖底下" in arrange.bodies.first(), "**正文不在提示词里**（§8.4）")

        val fast = Scripted(listOf(doneSaying("看着呢。")))
        val w2 = world()
        director(fast).wake(w2, watching(w2))
        assertTrue("【剧本池】" in fast.bodies.first(), "快速档也要看池子（它要判断该不该记批注）")
    }

    @Test
    fun onlyOneToolOfTheFastTierTouchesTheWorld() {
        val fast = DirectorTools.of(Tier.FAST).map { it.function.name }
        assertEquals(listOf(DirectorTools.WAKE_THINKING, DirectorTools.ANNOTATE_SCRIPT), fast)
    }
}
