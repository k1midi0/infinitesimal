package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `widen_view` 那个回合，用假端点钉住。
 *
 * 这是唯一一处**不需要联网就能验、而联网反而验不稳**的地方：它要验的三件事都是"请求体里到底有什么"
 * ——一个 tool 结果是否对上了 `tool_call_id`、assistant 那条是否带上了 `reasoning_content`、模型第二
 * 次看到的是不是扩大后的范围。让真模型来触发这些，等于把一条协议约束交给运气。
 */
class JudgeWidenTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    private fun tavern(): World = World.from(WorldSnapshot(StartingWorld.tavern()))

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun sse(vararg frames: String) = frames.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

    /** 一轮工具调用。`reasoning` 非空时会带上 `reasoning_content`——那正是回传时的硬约束。 */
    private fun turn(id: String, name: String, args: String, reasoning: String = ""): String {
        val r = if (reasoning.isEmpty()) "" else "\"reasoning_content\":\"${esc(reasoning)}\","
        return sse(
            """{"choices":[{"delta":{$r"tool_calls":[{"index":0,"id":"$id","function":{"name":"$name","arguments":"${esc(args)}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
    }

    /** 一轮里同时调好几只手。 */
    private fun multiTurn(vararg calls: Triple<String, String, String>): String {
        val body = calls.mapIndexed { i, (id, name, args) ->
            """{"index":$i,"id":"$id","function":{"name":"$name","arguments":"${esc(args)}"}}"""
        }.joinToString(",")
        return sse(
            """{"choices":[{"delta":{"reasoning_content":"先想一下。","tool_calls":[$body]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
    }

    /** 逐轮吐出写好的响应，并把每次请求体原样存下来。 */
    private class Scripted(private val frames: List<String>) {
        val bodies = mutableListOf<String>()
        private var served = 0

        val client: HttpClient = HttpClient(
            MockEngine { request ->
                bodies += (request.body as? TextContent)?.text.orEmpty()
                val body = frames[minOf(served, frames.size - 1)]
                served++
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        ) {
            expectSuccess = false
        }
    }

    @Test
    fun wideningContinuesTheSameTurnAndReplaysTheProtocolCorrectly() = runBlocking {
        val script = Scripted(
            listOf(
                turn("call_w", JudgeTools.WIDEN_VIEW, "{}", reasoning = "这一片太小了。"),
                turn("call_t", JudgeTools.TARGET, """{"id":"wine_cup"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, script.client))

        val result = judge.alignTargets(tavern(), request("我拿起桌上那杯麦酒"))

        assertEquals(TargetAlignment.Aligned(listOf("wine_cup")), result)
        assertEquals(2, judge.requestCount, "扩一次就该正好多一次请求")
        assertEquals(2, script.bodies.size)

        val second = script.bodies[1]
        // ① 每条 tool 结果都要对上 tool_call_id，端点是这么要求配对的。
        assertTrue(second.contains("\"tool_call_id\":\"call_w\""), "第二次请求里没有对应的 tool 结果：$second")
        // ② 带 tools 的请求里，每条 assistant 都必须带 reasoning_content（§1.2.1）。
        assertTrue(second.contains("\"reasoning_content\""), "第二次请求里的 assistant 丢了 reasoning_content：$second")
        // ③ 这一轮 assistant 的 tool_calls 要如实回传——它不是"我们发起的"，是模型说的。
        assertTrue(second.contains("\"tool_calls\""), second)

        // ④ 模型第二次看到的必须是**扩大后**的范围，而不是同一片。
        assertTrue(script.bodies[0].contains("x 9..15"), "第一次该是 7 格宽：${script.bodies[0]}")
        assertTrue(second.contains("x 6..18"), "扩过之后该是 13 格宽：$second")
    }

    @Test
    fun wideningOneDirectionMovesOnlyThatEdge() = runBlocking {
        // 方向参数是**可选**的：填了只抬那一个扇区。这条要钉住的是"只抬一个"真的只动一条边——
        // 不然模型读到「在东南 8 格外」、要求看东南，拿回来的却是四面八方都变大，
        // 多烧的那三倍射线不会有人去对账。
        val script = Scripted(
            listOf(
                turn("call_w", JudgeTools.WIDEN_VIEW, """{"direction":"南"}"""),
                turn("call_t", JudgeTools.TARGET, """{"id":"wine_cup"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, script.client))

        val result = judge.alignTargets(tavern(), request("我拿起桌上那杯麦酒"))

        assertEquals(TargetAlignment.Aligned(listOf("wine_cup")), result)
        assertEquals(2, judge.requestCount, "定向扩大同样要多走一趟")

        // 第一次请求的工具 schema 里，`direction` **不该出现在任何 required 里**——它是唯一的可选参数。
        val requiredBlocks = Regex(""""required":\[[^\]]*\]""")
            .findAll(script.bodies[0]).map { it.value }.toList()
        assertTrue(requiredBlocks.isNotEmpty(), "请求体里该有工具 schema：${script.bodies[0]}")
        assertTrue(
            requiredBlocks.none { it.contains("direction") },
            "direction 不该是必填：$requiredBlocks",
        )

        // 第二眼：南边该变远，**另外三条边一格不动**。
        val (firstX, firstY) = lastRange(script.bodies[0])
        val (secondX, secondY) = lastRange(script.bodies[1])
        assertEquals(firstX, secondX, "看南边不该动东西向跨度：$firstX → $secondX")
        assertEquals(firstY.first, secondY.first, "北边不该动：$firstY → $secondY")
        assertTrue(secondY.last > firstY.last, "南边该变远：$firstY → $secondY")
    }

    /**
     * 请求体里**最后**一次出现的范围。
     *
     * 必须取最后一次：第二眼那条请求里同时带着旧范围（user 消息）和新范围（tool 结果）。
     */
    private fun lastRange(body: String): Pair<IntRange, IntRange> {
        fun pick(axis: String): IntRange {
            val m = Regex("$axis (-?\\d+)\\.\\.(-?\\d+)").findAll(body).last()
            return IntRange(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }
        return pick("x") to pick("y")
    }

    @Test
    fun aProposalMadeInTheSameTurnAsWideningIsNotLost() = runBlocking {
        // 模型可以在同一次响应里既报一个东西、又要看更大范围。**那个报的东西不能丢**——它是模型
        // 已经做出的判断，而这一轮之后它没有理由再报一次。
        val script = Scripted(
            listOf(
                multiTurn(
                    Triple("c1", JudgeTools.WIDEN_VIEW, "{}"),
                    Triple("c2", JudgeTools.TARGET, """{"id":"wine_cup"}"""),
                ),
                turn("c3", JudgeTools.TARGET, """{"id":"oil_lamp"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, script.client))

        val result = judge.alignTargets(tavern(), request("我把桌上的东西全扫到地上"))

        assertEquals(TargetAlignment.Aligned(listOf("wine_cup", "oil_lamp")), result)
        // 同一轮里的两只手，**每只**都要有一条 tool 结果，否则下一轮请求就不合法了。
        assertTrue(script.bodies[1].contains("\"tool_call_id\":\"c1\""), script.bodies[1])
        assertTrue(script.bodies[1].contains("\"tool_call_id\":\"c2\""), script.bodies[1])
    }

    @Test
    fun aTurnWithNoWideningCostsExactlyOneRequest() = runBlocking {
        // 不调就不该多花一次——这条是那个工具说明里"不调就是现在这些够了"的技术底座。
        val script = Scripted(listOf(turn("c1", JudgeTools.TARGET, """{"id":"oak_table"}""")))
        val judge = Judge(LlmClient(config, script.client))

        val result = judge.alignTargets(tavern(), request("我掀桌子"))

        assertEquals(TargetAlignment.Aligned(listOf("oak_table")), result)
        assertEquals(1, judge.requestCount)
        assertEquals(1, script.bodies.size)
    }

    @Test
    fun theBudgetIsSharedAndUncapped() {
        val world = tavern()
        val view = ViewBudget(world, "player", listOf("wine_cup"))

        assertEquals(0, view.widenings)
        assertEquals(7, view.region().width)
        // **7 不是 8，这是球面采样的代价。** 老的整数 18 向里有恰好朝南的 (0,1,0) 与 (0,1,±1)；
        // 球面采样里没有完全对齐轴的方向，于是朝南那条射线在 t=1 就被桌上的油灯挡下了
        // （旧的 (0,1,1) 从灯上方飞过去，能摸到 y=7）。极值方向不再有一条**恰好**对齐的射线兜底。
        assertEquals(7, view.region().height)

        view.widen()
        assertEquals(1, view.widenings)
        assertEquals(13, view.region().width)

        view.widen()
        view.widen()
        view.widen()
        // **不封顶**（用户定的）。这里只钉住"它确实一次比一次大"，不钉住它会在哪里停——
        // 那个数字是跑出来看的，不是设计出来的。
        assertEquals(4, view.widenings)
        assertTrue(view.region().width > 25, "扩了四次还只有 ${view.region().width} 格宽")
    }

    @Test
    fun theActorIsAlwaysOneOfTheCenters() {
        // 框里不含发起者的话，它拿到的坐标范围里就没有它自己——那是"坐标是编的"最容易复发的地方。
        val world = tavern()
        val view = ViewBudget(world, "player", emptyList())
        assertTrue(view.region().covers(world.byId("player")!!), "${view.region()}")
    }

    private fun request(intention: String, selected: List<String> = emptyList()) = JudgeRequest(
        actorId = "player",
        actorLabel = "你",
        intention = intention,
        selected = selected,
        centers = selected,
    )
}
