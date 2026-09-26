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
import io.github.k1midi0.infinitesimal.world.BUFFER_RADIUS_CHUNKS
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 导演的模型段：两档、三只手、以及"什么都没做"的两重含义（§8、§14.33）。
 *
 * 假端点验的不是模型有多聪明，而是**这一层的接线**：参数写错时它能不能自己改回来、换档有没有真的
 * 只换参数、以及那两条端点的硬约束（不设 `max_tokens`、assistant 必须带 `reasoning_content`）有没有
 * 被这一层破坏。
 */
class DirectorArrangeTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    /**
     * **规则书要一起传。** `WorldSnapshot` 的 `rulebook` 默认是空的——不传，世界规则就不在世界里，
     * 而那正好是这一支要验的东西之一（第一版就写漏了，被断言当场逮住）。
     */
    private fun world() = World.from(WorldSnapshot(StartingWorld.tavern(), StartingWorld.rules()))

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun arranging(world: World) = Waking(Wake.Arrange, "player", origin(world))

    /** 模型说"我干完了"，一个工具都不调。**这就是这一层的终止信号。** */
    private fun doneSaying(text: String = "排完了。") = sse(
        """{"choices":[{"delta":{"content":"${text}"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
    )

    /**
     * 导演 + 生成器各拿一个客户端，但共用同一条假端点。
     *
     * 生成器这一刀在测试里从不真的被调到（除非某一支特意填了 `grow_terrain`），而它一旦被调到，
     * 用的就是同一个脚本——这正是我们想验的：导演那一层有没有把范围、必需区块、点名要放的东西
     * 原样递下去。
     */
    private fun director(script: Scripted) = Director(
        LlmClient(config, script.client),
        TerrainGenerator(LlmClient(config, script.client)),
    )

    @Test
    fun itSchedulesWhatTheModelAskedForAndStopsWhenTheModelGoesQuiet() = runBlocking {
        val script = Scripted(
            listOf(
                turn(
                    "c1",
                    DirectorTools.SCHEDULE,
                    """{"id":"lamps_burn_low","day":2,"hour":3,"minute":0,"what":"四盏油灯里的油烧得差不多了"}""",
                    reasoning = "先排一件最近的事。",
                ),
                doneSaying(),
            ),
        )
        val world = world()

        val result = director(script).wake(world, arranging(world))

        assertTrue(result is Awakening.Done, "实得 $result")
        assertEquals(1, (result as Awakening.Done).scheduled.size)
        assertEquals(listOf("lamps_burn_low"), world.timeline.all().map { it.id })
        assertEquals(WorldTime.of(2, 3), world.timeline.byId("lamps_burn_low")?.dueAt)
    }

    /** 一件事一个来回也算正常进度——排十件就是十来个来回，那不是失败（对照附录 A.14 那条）。 */
    @Test
    fun severalRoundsOfSchedulingAreAllKept() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", DirectorTools.SCHEDULE, """{"id":"a","day":1,"hour":22,"minute":30,"what":"矮人把最后一桶酒搬进地窖"}"""),
                turn("c2", DirectorTools.SCHEDULE, """{"id":"b","day":2,"hour":3,"minute":0,"what":"油灯里的油烧得差不多了"}"""),
                doneSaying(),
            ),
        )
        val world = world()

        val result = director(script).wake(world, arranging(world))

        assertEquals(2, (result as Awakening.Done).scheduled.size)
        assertEquals(listOf("a", "b"), world.timeline.all().map { it.id })
    }

    /**
     * **已经过去的时刻排不出事**，而理由是当场退回给它的。
     *
     * 退回去而不是让整轮失败，是 §1.3 那条纪律：参数不对是模型的错，而它拿着"那个时刻已经过去了"
     * 这条信息，修得比谁都快。
     */
    @Test
    fun aTimeInThePastIsRefusedAndTheModelGetsToFixIt() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", DirectorTools.SCHEDULE, """{"id":"stale","day":1,"hour":9,"minute":0,"what":"今天早上该发生的事"}"""),
                turn("c2", DirectorTools.SCHEDULE, """{"id":"stale","day":2,"hour":9,"minute":0,"what":"改成明天早上"}"""),
                doneSaying(),
            ),
        )
        val world = world()

        val result = director(script).wake(world, arranging(world))

        assertTrue(result is Awakening.Done, "实得 $result")
        assertEquals(1, world.timeline.all().size)
        assertEquals(WorldTime.of(2, 9), world.timeline.byId("stale")?.dueAt)
        // 第一次那条被退回去了，而退回的话必须出现在第二次请求的历史里——否则模型修不了。
        assertTrue(script.bodies[1].contains("已经过去了"), "退回的理由要进历史：\n${script.bodies[1]}")
    }

    /** 一件都没排等于什么都没安排：它该重来一次，而且重来的那一次要带着这条理由。 */
    @Test
    fun sayingNothingOnTheFirstRoundIsRetriedWithAReason() = runBlocking {
        val script = Scripted(
            listOf(
                doneSaying("我觉得现在没什么好安排的。"),
                turn("c1", DirectorTools.SCHEDULE, """{"id":"door","day":1,"hour":20,"minute":0,"what":"有人推门进来"}"""),
                doneSaying(),
            ),
        )
        val world = world()

        val result = director(script).wake(world, arranging(world))

        assertTrue(result is Awakening.Done, "实得 $result")
        assertEquals(2, (result as Awakening.Done).attempts, "第二次尝试才排上")
        assertEquals(listOf("door"), world.timeline.all().map { it.id })
        assertTrue(script.bodies[1].contains("一件都没排"), "第二次要说清为什么重来：\n${script.bodies[1]}")
    }

    /** 两次都一件不排，就是协议内失败——而**理由要原样留着**，日志里靠它分辨是哪一种失败。 */
    @Test
    fun neverSchedulingAnythingFailsWithTheReasonKept() = runBlocking {
        val script = Scripted(listOf(doneSaying("无事可排。")))

        val world = world()
        val result = director(script).wake(world, arranging(world))

        assertTrue(result is Awakening.FailedProtocol, "实得 $result")
        assertEquals(2, (result as Awakening.FailedProtocol).attempts)
        assertTrue(result.problems.any { it.contains("一件都没排") })
    }

    /** 叫一只不存在的工具：退回去告诉它有哪些，而不是把整轮炸掉。 */
    @Test
    fun anUnknownToolIsAnsweredWithTheListOfRealOnes() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "烧掉酒馆", """{}"""),
                turn("c2", DirectorTools.SCHEDULE, """{"id":"a","day":2,"hour":6,"what":"天亮了"}"""),
                doneSaying(),
            ),
        )
        val world = world()

        val result = director(script).wake(world, arranging(world))

        assertTrue(result is Awakening.Done, "实得 $result")
        assertTrue(script.bodies[1].contains("没有叫"), "要告诉它哪只手不存在：\n${script.bodies[1]}")
        assertTrue(script.bodies[1].contains(DirectorTools.SCHEDULE), "还要告诉它有哪些能用")
    }

    // ── 两档 ───────────────────────────────────────────────────────────────────────

    /**
     * **快速档只有一只手，而且它不思考。**
     *
     * `thinking: disabled` 是钱与延迟一起省下来的那一半：快速档每回合都要跑一次（§14.33），开着思考
     * 就是每回合白烧一次思维链（§14.9 那条纪律）。
     */
    @Test
    fun theFastTierThinksNotAndSeesOnlyItsOneHand() = runBlocking {
        val script = Scripted(listOf(doneSaying("看着呢。"), doneSaying("看着呢。")))
        val world = world()

        director(script).wake(world, Waking(Wake.Watching("我把那杯麦酒端起来。"), "player", origin(world)))

        val body = script.bodies.first()
        assertTrue("\"thinking\":{\"type\":\"disabled\"}" in body, "快速档不思考：\n$body")
        assertTrue("reasoning_effort" !in body, "关着思考时不能发档位：\n$body")
        assertTrue("\"name\":\"${DirectorTools.WAKE_THINKING}\"" in body, "快速档要有唤醒思考档那只手：\n$body")
        // **按工具声明断言，不按整段文本**：system 里提到 `schedule` 是应该的（它在讲思考档干什么），
        // 断言整段里有没有这个词会在这里假报一次。
        assertTrue("\"name\":\"${DirectorTools.SCHEDULE}\"" !in body, "快速档没有写权限：\n$body")
        assertTrue("\"name\":\"${DirectorTools.GROW_TERRAIN}\"" !in body, "快速档不能生成：\n$body")
        assertTrue("\"name\":\"${DirectorTools.WRITE_CYCLE}\"" !in body, "快速档不能写作息：\n$body")
        assertTrue("\"name\":\"${DirectorTools.SPAWN_CHARACTER}\"" !in body, "快速档不能放人出来：\n$body")
    }

    /** 快速档什么都不做**是合法结果**——这正是它存在的样子，不该被判成失败。 */
    @Test
    fun watchingAndDoingNothingIsALegalOutcome() = runBlocking {
        val script = Scripted(listOf(doneSaying("没什么要动的。")))
        val world = world()

        val result = director(script).wake(
            world,
            Waking(Wake.Watching("我看了看四周。"), "player", origin(world)),
        )

        assertTrue(result is Awakening.Done, "实得 $result")
        assertEquals(Tier.FAST, (result as Awakening.Done).tier)
        assertTrue(result.scheduled.isEmpty())
    }

    /**
     * **换档只换参数，历史一段不重建。**
     *
     * 这是 §8.5 与缓存那两条的交点：第二次请求必须是同一个 messages 往前长，而不是新起一段——否则
     * 前缀缓存全丢（§14.5），而导演的上下文本来就有一万 token 上下。
     */
    @Test
    fun wakingTheThinkingTierKeepsTheSameHistoryAndOnlyChangesTheParameters() = runBlocking {
        val script = Scripted(
            listOf(
                turn(
                    "c1",
                    DirectorTools.WAKE_THINKING,
                    """{"why":"玩家正往东走进一片还没有地形的地方"}""",
                    reasoning = "",
                ),
                turn("c2", DirectorTools.SCHEDULE, """{"id":"a","day":1,"hour":21,"what":"天黑了"}"""),
                doneSaying(),
            ),
        )
        val world = world()

        val result = director(script).wake(
            world,
            Waking(Wake.Watching("我往东走了几步。"), "player", origin(world)),
        )

        assertTrue(result is Awakening.Done, "实得 $result")
        assertEquals(Tier.THINKING, (result as Awakening.Done).tier)

        val second = script.bodies[1]
        assertTrue("\"thinking\":{\"type\":\"enabled\"}" in second, "换档之后要思考：\n$second")
        assertTrue("reasoning_effort" in second, "思考档要带档位：\n$second")
        assertTrue(DirectorTools.GROW_TERRAIN in second, "思考档那几只一次给全：\n$second")
        assertTrue(DirectorTools.WRITE_CYCLE in second, "写作息那只手也在里面：\n$second")
        assertTrue(DirectorTools.SPAWN_CHARACTER in second, "放人那只手也在里面：\n$second")
        // 快速档那一段原样还在（同一段历史），而思考档该看的东西补在了末尾。
        assertTrue("我往东走了几步" in second, "快速档那一段不能被丢掉：\n$second")
        assertTrue("【地图】" in second, "换档要补上地图：\n$second")
        // 那条硬约束：换档那次 assistant 消息也得回传 reasoning_content（这里是空串）。
        assertTrue("\"reasoning_content\":\"\"" in second, "空思考要给空串：\n$second")
    }

    /**
     * **硬圈缺口：必需的那几块没写进地形就是没完成。**
     *
     * 它是这一层唯一一条"模型说了不算"的判据——从世界状态上读，不从回答里读（§14.33）。
     */
    @Test
    fun aHardGapIsNotDoneUntilTheChunksExistOnTheGround() = runBlocking {
        val script = Scripted(listOf(doneSaying("我看过了，不用长。")))
        val world = world()
        val required = listOf(ChunkKey(7, 4))

        val result = director(script).wake(
            world,
            Waking(Wake.HardGap(required), "player", origin(world)),
        )

        assertTrue(result is Awakening.FailedProtocol, "实得 $result")
        assertEquals(2, (result as Awakening.FailedProtocol).attempts)
        assertTrue(
            result.problems.any { it.contains("必需的那几块") },
            "理由要说清是哪几块：${result.problems}",
        )
        assertTrue(script.bodies[1].contains("必需的那几块"), "重来那一次要带着理由")
    }

    /**
     * **快速档叫起来的那一次，什么都不做是合法的**——与上一支恰好相反。
     *
     * 两条路对玩家的意义是反的：硬圈那条必须挡住他（他正走进一片还没有地图的地方），这一条不能挡
     * （他只是在走路）。判据因此不能是"空手"，只能是"这一次唤醒当初要的是什么"（§14.33）。
     */
    @Test
    fun beingCalledUpAndDoingNothingIsAlsoLegal() = runBlocking {
        val script = Scripted(listOf(doneSaying("这一带还不用长。")))
        val world = world()

        val result = director(script).wake(
            world,
            Waking(Wake.Called("玩家往东走了一段"), "player", origin(world)),
        )

        assertTrue(result is Awakening.Done, "实得 $result")
        assertTrue((result as Awakening.Done).scheduled.isEmpty())
        assertEquals(1, result.attempts, "不该重试")
    }

    // ── 生成那只手 ─────────────────────────────────────────────────────────────────

    /**
     * **缓冲圈是硬边界。** 填到圈外的坐标当场被驳回，而且理由要可执行——把数字退回去是不够的，模型
     * 需要知道"远"本身就是它不该做的事（§14.33）。
     */
    @Test
    fun growingOutsideTheBufferIsRefusedWithAUsableReason() = runBlocking {
        val far = BUFFER_RADIUS_CHUNKS + 5
        val script = Scripted(
            listOf(
                turn(
                    "c1",
                    DirectorTools.GROW_TERRAIN,
                    """{"requirement":"一片荒地","chunks":"(1,1) ($far,1)"}""",
                ),
                turn("c2", DirectorTools.SCHEDULE, """{"id":"a","day":2,"hour":6,"what":"天亮了"}"""),
                doneSaying(),
            ),
        )
        val world = world()

        director(script).wake(world, arranging(world))

        val answer = script.bodies[1]
        assertTrue(answer.contains("缓冲圈以外"), "驳回理由要出现在历史里：\n$answer")
        assertTrue(answer.contains("$BUFFER_RADIUS_CHUNKS"), "理由里要有能长的半径：\n$answer")
    }

    /** 点名要放的东西写坏一行，**不能静默丢掉**——退回去说清是第几行（§1.3）。 */
    @Test
    fun aMalformedPlacementLineIsReportedNotDropped() = runBlocking {
        val script = Scripted(
            listOf(
                turn(
                    "c1",
                    DirectorTools.GROW_TERRAIN,
                    """{"requirement":"一片荒地","chunks":"(1,1)","placements":"没有分隔符的一行"}""",
                ),
                turn("c2", DirectorTools.SCHEDULE, """{"id":"a","day":2,"hour":6,"what":"天亮了"}"""),
                doneSaying(),
            ),
        )
        val world = world()

        director(script).wake(world, arranging(world))

        val answer = script.bodies[1]
        assertTrue(answer.contains("第 1 行"), "要说清是第几行：\n$answer")
        assertTrue(answer.contains("放什么｜在哪一块｜默认描述"), "还要给出可照抄的格式：\n$answer")
    }

    // ── 端点的两条硬约束 ───────────────────────────────────────────────────────────

    /**
     * **不设 `max_tokens`。**
     *
     * 用户对这件事的裁定是「不要限制思考，防止逻辑出错，包括导演系统同理」（§12 第 16 项）。任何一个小
     * 数字都会把想得久的那一次截断，而症状是"它什么都没排出来"，与"它觉得无事可排"长得一模一样。
     */
    @Test
    fun nothingBoundsTheThinkingBudget() = runBlocking {
        val script = Scripted(listOf(doneSaying(), doneSaying()))
        val world = world()
        director(script).wake(world, arranging(world))

        val body = script.bodies.first()
        assertTrue("max_tokens" !in body, "不该发 max_tokens：\n$body")
        assertTrue("\"thinking\":{\"type\":\"enabled\"}" in body, "导演是思考档：\n$body")
        assertTrue("reasoning_effort" in body, "档位要发出去：\n$body")
    }

    /**
     * **带 tools 的请求里，每条 assistant 都必须回传 `reasoning_content`**（§1.2.1），而且每条 tool
     * 结果都要对得上 `tool_call_id`。
     *
     * 这一条只在"一轮里有多次请求"时才暴露——而导演恰恰是多轮的，所以它是这一层最容易踩到的那个 400。
     */
    @Test
    fun theAssistantTurnsCarryTheirReasoningAndTheToolResultsPairUp() = runBlocking {
        val script = Scripted(
            listOf(
                turn(
                    "c1",
                    DirectorTools.SCHEDULE,
                    """{"id":"a","day":2,"hour":6,"what":"天亮了"}""",
                    reasoning = "先想一下要排什么。",
                ),
                doneSaying(),
            ),
        )
        val world = world()
        director(script).wake(world, arranging(world))

        val second = script.bodies[1]
        assertTrue("\"reasoning_content\":\"先想一下要排什么。\"" in second, "思考内容要回传：\n$second")
        assertTrue("\"tool_call_id\":\"c1\"" in second, "每条 tool 结果都要对上 id：\n$second")
    }

    // ── 提示词里有什么 ─────────────────────────────────────────────────────────────

    @Test
    fun thePromptCarriesTheHourTheNeighbourhoodAndWhatIsAlreadyScheduled() = runBlocking {
        val script = Scripted(listOf(doneSaying(), doneSaying()))
        val world = world()
        world.timeline.put(
            io.github.k1midi0.infinitesimal.world.TimelineEntry(
                id = "fire",
                dueAt = WorldTime.of(2, 2),
                what = "邻居家着了火",
            ),
        )

        director(script).wake(world, arranging(world))

        val body = script.bodies.first()
        assertTrue("第 1 天 傍晚（18:00）" in body, "现在是什么时候必须写着：\n$body")
        assertTrue("【空间框】" in body, "身边那一片要给它看：\n$body")
        assertTrue("木桌" in body, "那一片里的东西要列出来：\n$body")
        assertTrue("邻居家着了火" in body, "已经排了什么也要给它看，否则它会从零重排一遍：\n$body")
        assertTrue("火会烧起来" in body, "世界的规矩要给它看：\n$body")
    }

    /** 思考档要看得见地图，而**玩家开局在 (1,1)**——那是 [ChunkOrigin] 那一条规则的样子。 */
    @Test
    fun theThinkingTierSeesTheChunkMapWithThePlayerAtOneOne() = runBlocking {
        val script = Scripted(listOf(doneSaying(), doneSaying()))
        val world = world()

        director(script).wake(world, arranging(world))

        val body = script.bodies.first()
        assertTrue("【地图】" in body, "思考档要看地图：\n$body")
        assertTrue("y=+00" in body, "行头就是数轴：\n$body")
    }

    /**
     * **地点本身不在框里**——这一条是实测撞上的，不是设计如此。
     *
     * `tavern_hall` 没有形状（附录 A.13），于是它按"没有形状的东西只占自己那一格"算（`occupiedCells`），
     * 而它那一格是 (10,0)：从玩家 (12,3) 往西两格、往南三格，**超出了 3 格的射线**。所以「酒馆大厅」
     * 这个名字既不在框里、也不在清单里——判定的处境与它一样。
     *
     * 这一支把它记下来，是为了让它是**一条被看见的已知**，而不是某天变成"模型怎么不知道自己在哪"。
     */
    @Test
    fun thePlaceItselfFallsOutsideTheFrameBecauseItHasNoShape() = runBlocking {
        val script = Scripted(listOf(doneSaying(), doneSaying()))
        val world = world()
        director(script).wake(world, arranging(world))

        val body = script.bodies.first()
        assertTrue("酒馆大厅" !in body, "它现在确实看不见；哪天看得见了，是地点有了形状，这条断言该改")
        assertTrue("id=tavern_hall" !in body)
    }

    /**
     * 第二次调用时，**上一次排的还在时间轴上**，于是它会出现在提示词里。
     *
     * 这是"导演看得见自己上次干了什么"唯一的机制。少了它，每一次安排都会从头重排一遍。
     */
    @Test
    fun aLaterCallSeesWhatTheEarlierCallScheduled() = runBlocking {
        val first = Scripted(
            listOf(
                turn("c1", DirectorTools.SCHEDULE, """{"id":"door","day":1,"hour":20,"what":"有人推门进来"}"""),
                doneSaying(),
            ),
        )
        val world = world()
        director(first).wake(world, arranging(world))

        val second = Scripted(listOf(doneSaying(), doneSaying()))
        director(second).wake(world, arranging(world))

        assertTrue("有人推门进来" in second.bodies.first(), "上一次排的要在这一次的提示词里")
    }
}
