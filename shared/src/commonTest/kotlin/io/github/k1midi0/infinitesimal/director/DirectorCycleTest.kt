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
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 导演写作息那只手（§8.3、§14.44）。
 *
 * 离线这一批要钉的是**接线与那五道门**：写进去的整张替换、`bornAt` 由代码取、一刻都没写进去时世界
 * 一个字不动、读不懂的行原样退回去、看不见的人写不了。**它写出来的作息合不合用**，那是
 * `DirectorProbe` 那支联机探头的事（离线这批一个字都证明不了，与 §8 那次同一个理由）。
 *
 * ## 两条贯穿这一支的纪律
 *
 *  - **一个请求体里装着整串历史**，而历史里存着之前几次请求的提示词（它们本身就是消息）。所以"证据在
 *    第几次请求里"这件事要**指对地方**——断言取 `script.bodies[n]` 时数的是**请求**，不是"消息"
 *    （§14.43 那个差一）。
 *  - **驳回也不改世界。** 每一支都顺手断言目标身上的作息没被动过——"看着像成了、其实没落库"是这个
 *    项目里最难查的一类失败（§1.5 第三条）。
 */
class DirectorCycleTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    /**
     * **地形与区划也要一起传。** `WorldSnapshot` 的那两项默认是空的，而少了它们，身边的人那一片会以
     * 一片空白地形去算——§14.35 那种"探针少传一半、读数全是错的"就是这样来的。
     */
    private fun world() = World.from(StartingWorld.snapshot())

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun arranging(world: World) = Waking(Wake.Arrange, "player", origin(world))

    private fun doneSaying(text: String = "写完了。") = sse(
        """{"choices":[{"delta":{"content":"${text}"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
    )

    private fun director(script: Scripted) = Director(
        LlmClient(config, script.client),
        TerrainGenerator(LlmClient(config, script.client)),
    )

    /** 这只手收的是 `id` 与一段多行文本——造它的时候要把换行与全角竖线一起 escape 过去。 */
    private fun writeCycleTurn(id: String, target: String, beats: String, callId: String = "c1") = turn(
        callId,
        DirectorTools.WRITE_CYCLE,
        """{"id":"$target","beats":"${esc(beats)}"}""",
    )

    /**
     * 一份好作息：三格，最后一格决定一圈多长。
     *
     * **第一格刻意不落在 0**（`Cycle.problems` 会驳回那种）：一圈的长度是首尾两格之差，第一格在 0 时
     * 最后一格的窗口会退化成零宽。
     */
    private val goodBeats = """
        420｜900,320,12800｜在后厨生火、把杯子一只只擦干净。
        720｜1150,120,12800｜坐在柜台后面吃自己那份午饭，谁也不理。
        1380｜900,320,12800｜灭了灯，把椅子倒扣在桌上。
    """.trimIndent()

    /** 排下一件时间轴的事，好让 `Wake.Arrange` 的契约（至少排一件）满足。 */
    private fun scheduleTurn(id: String = "lamps", callId: String = "c9") = turn(
        callId,
        DirectorTools.SCHEDULE,
        """{"id":"$id","day":2,"hour":3,"minute":0,"what":"四盏油灯里的油烧得差不多了"}""",
    )

    @Test
    fun itWritesTheCycleTheModelAskedForOntoTheTargetItNamed() = runBlocking {
        val script = Scripted(listOf(writeCycleTurn("c1", "dwarf", goodBeats), scheduleTurn(), doneSaying()))
        val world = world()

        val result = director(script).wake(world, arranging(world))

        assertTrue(result is Awakening.Done, "实得 $result")
        val dwarf = world.byId("dwarf")!!
        assertEquals(3, dwarf.cycle.beats.size)
        assertEquals(listOf(420, 720, 1380), dwarf.cycle.beats.map { it.atMinute })
        assertEquals("在后厨生火、把杯子一只只擦干净。", dwarf.cycle.beats.first().state)

        // **`bornAt` 由代码取"提交那一刻"**，不是模型填的——它手里根本没有"现在几点"这个数。
        assertEquals(world.clock, dwarf.cycle.bornAt)
        assertEquals(WorldTime.of(1, 18), dwarf.cycle.bornAt)

        // 一圈 = 最后一格的时刻 + 1 = 1381 分钟。
        assertEquals(1381, dwarf.cycle.periodMinutes)

        // **写完立刻按当前相位就位**：bornAt 就是此刻，所以相位 0 → 绕给最后一格（1380 那一格）。
        assertEquals(900, dwarf.x)
        assertEquals(320, dwarf.y)
    }

    @Test
    fun aSecondWriteReplacesTheWholeThing() = runBlocking {
        val first = Scripted(listOf(writeCycleTurn("c1", "dwarf", goodBeats), scheduleTurn(), doneSaying()))
        val world = world()
        director(first).wake(world, arranging(world))
        assertEquals(3, world.byId("dwarf")!!.cycle.beats.size)

        val second = Scripted(
            listOf(
                writeCycleTurn("c1", "dwarf", "60｜1000,200,12800｜在柜台后面擦杯子。\n600｜1100,260,12800｜扫地。"),
                scheduleTurn(callId = "c9"),
                doneSaying(),
            ),
        )
        director(second).wake(world, arranging(world))

        // **旧的一格不留**：作息是一张周期表，半张读出来是错的答案（§14.44）。
        val dwarf = world.byId("dwarf")!!
        assertEquals(listOf(60, 600), dwarf.cycle.beats.map { it.atMinute })
        // 刚写下的那一瞬相位 0，而相位 0 早于第一格（60）→ 绕给最后一格。这是"刚写完那一小段"的
        // 读法，不是错。
        assertEquals("扫地。", dwarf.cycle.stateAt(dwarf.cycle.bornAt))
    }

    /**
     * **看不见的人写不了。** 这一条的坏法很实在：它不知道那人在哪，只会编一个坐标出来——而"编出来的
     * 坐标"这个坑这个项目已经踩过一次（§14.18 那盏油灯）。
     */
    @Test
    fun someoneItCannotSeeIsRefusedAndTheWorldIsUntouched() = runBlocking {
        val world = world()
        // 一个站在很远的地方的角色：既不在身边那一片里，也不在那张地图的默认范围里。
        world.put(
            Entity(
                id = "farmer",
                kind = "角色",
                label = "远处的农夫",
                x = 900_000,
                y = 900_000,
                z = 12_800,
            ),
        )
        val before = world.byId("farmer")!!.cycle.beats

        val script = Scripted(
            listOf(
                writeCycleTurn("c1", "farmer", goodBeats),
                scheduleTurn(callId = "c9"),
                doneSaying(),
            ),
        )
        director(script).wake(world, arranging(world))

        assertEquals(before, world.byId("farmer")!!.cycle.beats, "驳回了就一个字都不能动")
        assertTrue(
            "不在你这一轮看得见的范围里" in script.bodies[1],
            "理由要说清是「看不见」这一条：\n${script.bodies[1]}",
        )
    }

    /** 世界没有的 id：理由要指出"眼前清单里的才是能写的"，而不是只说一句"不行"。 */
    @Test
    fun anIdTheWorldDoesNotHaveIsRefused() = runBlocking {
        val script = Scripted(
            listOf(writeCycleTurn("c1", "ghost", goodBeats), scheduleTurn(), doneSaying()),
        )
        val world = world()

        director(script).wake(world, arranging(world))

        assertTrue("世界里没有 id 是「ghost」" in script.bodies[1], "理由要指对：\n${script.bodies[1]}")
    }

    /** 不是角色的东西没有作息可写。 */
    @Test
    fun somethingThatIsNotACharacterIsRefused() = runBlocking {
        val script = Scripted(
            listOf(writeCycleTurn("c1", "oak_table", goodBeats), scheduleTurn(), doneSaying()),
        )
        val world = world()

        director(script).wake(world, arranging(world))

        assertTrue("不是角色" in script.bodies[1], "理由要指对：\n${script.bodies[1]}")
    }

    /**
     * **读不懂的那一行原样退回去**（§1.3：静默丢弃 = 死循环）。
     *
     * 退的是"第几行、原文是什么、该写成什么形状"——它下一轮只改这一行就能过。
     */
    @Test
    fun aLineItCannotReadIsQuotedBackInsteadOfDropped() = runBlocking {
        val script = Scripted(
            listOf(
                writeCycleTurn("c1", "dwarf", "0｜900,320,12800｜生火。\n没有分隔符的一行"),
                scheduleTurn(callId = "c9"),
                doneSaying(),
            ),
        )
        val world = world()
        val before = world.byId("dwarf")!!.cycle.beats

        director(script).wake(world, arranging(world))

        val answer = script.bodies[1]
        assertTrue("第 2 行" in answer, "要说清是第几行：\n$answer")
        assertTrue("没有分隔符的一行" in answer, "那一行的原文要退回去：\n$answer")
        assertEquals(before, world.byId("dwarf")!!.cycle.beats, "那一份整张都没落进去")
    }

    /** 一条坏坐标也退回去，不静默当零。 */
    @Test
    fun aCoordinateThatIsNotThreeIntegersIsRefused() = runBlocking {
        val script = Scripted(
            listOf(
                writeCycleTurn("c1", "dwarf", "0｜九百,三百｜生火。\n600｜1000,200,12800｜扫地。"),
                scheduleTurn(callId = "c9"),
                doneSaying(),
            ),
        )
        val world = world()

        director(script).wake(world, arranging(world))

        assertTrue("坐标" in script.bodies[1], "理由要指出是坐标那一段：\n${script.bodies[1]}")
    }

    /** 结构立不住的一份（一格、或者最后一格为零）也退回去，而理由说的是那一条。 */
    @Test
    fun aCycleThatCannotStandOnItsOwnIsRefused() = runBlocking {
        val script = Scripted(
            listOf(
                writeCycleTurn("c1", "dwarf", "0｜1000,200,12800｜一直在擦杯子。"),
                scheduleTurn(callId = "c9"),
                doneSaying(),
            ),
        )
        val world = world()
        val before = world.byId("dwarf")!!.cycle.beats

        director(script).wake(world, arranging(world))

        assertTrue("至少两格" in script.bodies[1], "理由要给那一条：\n${script.bodies[1]}")
        assertEquals(before, world.byId("dwarf")!!.cycle.beats)
    }

    /** 同一分钟两格 = 那一刻它在做什么没有答案。 */
    @Test
    fun twoBeatsOnTheSameMinuteAreRefused() = runBlocking {
        val script = Scripted(
            listOf(
                writeCycleTurn("c1", "dwarf", "0｜900,320,12800｜生火。\n0｜1000,200,12800｜扫地。"),
                scheduleTurn(callId = "c9"),
                doneSaying(),
            ),
        )
        val world = world()

        director(script).wake(world, arranging(world))

        assertTrue("同一分钟" in script.bodies[1], "理由要给那一条：\n${script.bodies[1]}")
    }

    /** 写成功之后，那一轮它就看得见自己刚写的作息——否则它会怀疑没写进去。 */
    @Test
    fun theAnswerQuotesBackWhateverItWrote() = runBlocking {
        val script = Scripted(listOf(writeCycleTurn("c1", "dwarf", goodBeats), scheduleTurn(), doneSaying()))
        val world = world()

        director(script).wake(world, arranging(world))

        val answer = script.bodies[1]
        assertTrue("写好了" in answer, "答复要说清世界动了：\n$answer")
        assertTrue("3 格" in answer, "要说清写了几格：\n$answer")
        assertTrue("1380" in answer, "要把它写的那几行原样念回去：\n$answer")
    }

    @Test
    fun theThinkingTierIsToldWhoItCanSee() = runBlocking {
        val script = Scripted(listOf(doneSaying(), doneSaying()))
        val world = world()

        director(script).wake(world, arranging(world))

        val body = script.bodies.first()
        assertTrue("【你这一轮看得见、因而能写作息的角色】" in body, "要给它能写的人：\n$body")
        assertTrue("酒馆里的矮人" in body, "矮人就在玩家身边，该在名单里：\n$body")
    }

    /** 快速档**没有**这只手——§1.4：便宜的档位没有写权限（§8.5 那张表）。 */
    @Test
    fun theFastTierHasNoCycleHand() = runBlocking {
        val script = Scripted(listOf(doneSaying("看着呢。"), doneSaying("看着呢。")))
        val world = world()

        director(script).wake(
            world,
            Waking(Wake.Watching("我把那杯麦酒端起来。"), "player", origin(world)),
        )

        val body = script.bodies.first()
        assertTrue(
            "\"name\":\"${DirectorTools.WRITE_CYCLE}\"" !in body,
            "快速档不能写作息：\n$body",
        )
    }
}
