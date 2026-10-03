package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.generate.TerrainGenerator
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.sse
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.Active
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **归宿的另一半：升成剧情角色**（§14.53、§3.2.1 的第 2 件事）。
 *
 * 这一支钉四件事：
 *
 *  1. 那一只手真的落库：**作息清空 + 活跃标记清掉，而位置不动**（升剧情不是传送）。
 *  2. 五道门：空 id、世界没有、不是角色、**本来就是剧情角色**、看不见。
 *  3. 只在思考档（快速档给不得）。
 *  4. 答复里说得出"它现在是剧情角色了"——以及要给它重新过日子该用哪只手。
 */
class DirectorPromoteTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun world(): World = World.from(StartingWorld.snapshot())

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: io.github.k1midi0.infinitesimal.world.ChunkKey(0, 0))

    /** 它此刻是"活跃角色"：刚从自己的日子上被拿下来。 */
    private fun turningActive(world: World, id: String = "dwarf") = Waking(
        Wake.Called(why = "「杜林」刚刚从氛围角色变成了活跃角色。", aboutId = id),
        "player",
        origin(world),
    )

    private fun director(script: Scripted) = Director(
        LlmClient(config, script.client),
        TerrainGenerator(LlmClient(config, script.client)),
    )

    private fun promote(id: String = "dwarf", callId: String = "c1") =
        turn(callId, DirectorTools.PROMOTE_CHARACTER, """{"id":"$id"}""")

    private fun doneSaying(text: String = "升好了。") = sse(
        """{"choices":[{"delta":{"content":"${text}"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
    )

    /**
     * 把矮人设成"活跃角色"——它离开了柜台，而**那一步要小**：可见范围比想象的紧（挪到离玩家 3.4 米的
     * 地方就已经出界了，见这一支的历史：判词是"不在你这一轮看得见的范围里"）。
     */
    private fun World.takeItOffItsRoutine(x: Int = 1250, y: Int = 350): World {
        put(
            byId("dwarf")!!.copy(
                x = x,
                y = y,
                active = Active(since = clock, target = byId("dwarf")!!.cycle.beatAt(clock)),
            ),
        )
        return this
    }

    @Test
    fun promotedItLosesItsRoutineAndItsActiveMarkButStaysWhereItStands() = runBlocking {
        val script = Scripted(listOf(promote(), doneSaying()))
        val world = world().takeItOffItsRoutine()
        val before = world.byId("dwarf")!!

        val result = director(script).wake(world, turningActive(world))

        assertTrue(result is Awakening.Done, "有手可用就该完成，实得 $result")
        val after = world.byId("dwarf")!!
        val verdict = Regex("""没升成：(.{0,240})""", RegexOption.DOT_MATCHES_ALL)
            .find(script.bodies[1])?.groupValues?.get(1)
            ?: (if ("升好了" in script.bodies[1]) "升好了" else "（既没有『没升成』也没有『升好了』——那只手大概没被调用）")
        assertTrue(after.cycle.isEmpty, "作息清空了——它现在是剧情角色｜判词：$verdict")
        assertEquals(null, after.active, "活跃标记也清掉：它不再是'脱轨的人'")
        assertEquals(before.x to before.y, after.x to after.y, "位置不动——升剧情不是传送")
    }

    @Test
    fun theAnswerSaysItIsAStoryCharacterNow() = runBlocking {
        val script = Scripted(listOf(promote(), doneSaying()))
        val world = world().takeItOffItsRoutine()

        director(script).wake(world, turningActive(world))

        // 答复（作为工具结果回到第二次请求里）要说清代价与回头路。
        val back = script.bodies[1]
        assertTrue("剧情角色" in back, "答复里要说清它现在是什么")
        assertTrue(DirectorTools.WRITE_CYCLE in back, "还要指出要它重新过日子该用哪只手：${back.take(400)}")
    }

    @Test
    fun somebodyWhoHasNoRoutineAlreadyIsRefused() = runBlocking {
        val script = Scripted(listOf(promote(), doneSaying()))
        val world = world()
        // 先把它变成剧情角色，再叫一次——第二次该被驳回。
        CyclePlanner.dropActiveCycle(world, "dwarf")

        director(script).wake(world, turningActive(world))

        val back = script.bodies[1]
        assertTrue("本来就是剧情角色" in back, "理由是它已经没作息了：${back.take(400)}")
    }

    @Test
    fun somebodyYouCannotSeeIsRefused() = runBlocking {
        val script = Scripted(listOf(promote(), doneSaying()))
        val world = world()
        world.put(world.byId("dwarf")!!.copy(x = 80_000, y = 80_000))

        director(script).wake(world, turningActive(world))

        val after = world.byId("dwarf")!!
        assertFalse(after.cycle.isEmpty, "驳回之后它的作息一个字没动")
        assertTrue("看不见" in script.bodies[1] || "看得见" in script.bodies[1])
    }

    @Test
    fun somethingThatIsNotACharacterIsRefused() = runBlocking {
        val script = Scripted(listOf(promote("wine_cup"), doneSaying()))
        val world = world()

        director(script).wake(world, turningActive(world, id = "wine_cup"))

        assertTrue("不是角色" in script.bodies[1], "酒杯不是角色：${script.bodies[1].take(400)}")
    }

    @Test
    fun aMissingIdIsRefused() = runBlocking {
        val script = Scripted(listOf(promote("nobody"), doneSaying()))

        director(script).wake(world(), turningActive(world(), id = "nobody"))

        assertTrue("世界里没有" in script.bodies[1], script.bodies[1].take(400))
    }

    @Test
    fun theFastTierDoesNotHaveThisHand() {
        val fast = DirectorTools.of(Tier.FAST).map { it.function.name }
        assertFalse(DirectorTools.PROMOTE_CHARACTER in fast, "快速档不该有它：$fast")
        assertTrue(DirectorTools.PROMOTE_CHARACTER in DirectorTools.of(Tier.THINKING).map { it.function.name })
    }

    /**
     * **它自己不回去也行**——`Called` 的 `unmet` 是空的，"什么都不做"是合法结果（§14.28 那条的反面）。
     * 而这一条正是用户 2026-09-28 定的：**回不回由它自己**（导演没有"放它回去"的动作）。
     */
    @Test
    fun leavingItAloneIsAFinishedAnswer() = runBlocking {
        val script = Scripted(listOf(doneSaying("它自己想回去时会说。")))
        val world = world().takeItOffItsRoutine()

        val result = director(script).wake(world, turningActive(world))

        assertTrue(result is Awakening.Done, "沉默也算干完了，实得 $result")
        val after = world.byId("dwarf")!!
        assertFalse(after.cycle.isEmpty, "它照样有自己的日子")
        assertTrue(after.active != null, "也照样是活跃角色——等它自己 arrive")
    }
}
