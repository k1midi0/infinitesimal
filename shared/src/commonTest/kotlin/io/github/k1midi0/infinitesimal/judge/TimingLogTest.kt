package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 时间账：**每一次模型调用恰好一条，且两步分得开**。
 *
 * 这里不验证"花了多少毫秒"——假端点是瞬时的，毫秒数没有意义。验的是账本的结构：条数等于请求数、
 * 阶段分得对、重试与查询轮分得开、三笔钱（总/等/生成）互相对得上。**结构错了，真机上的数就是错的。**
 */
class TimingLogTest {

    private val config = EndpointConfig(
        baseUrl = "https://example.invalid",
        apiKey = "test",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    private fun tavern(): World = World.from(WorldSnapshot(StartingWorld.tavern()))

    private fun request(intention: String) = JudgeRequest(
        actorId = "player",
        actorLabel = "你",
        intention = intention,
        selected = emptyList(),
        centers = emptyList(),
    )

    /** 一轮什么都没调——用来看"首次尝试白跑、重试才照做"这条路上账怎么记。 */
    private val saidNothing = sse(
        """{"choices":[{"delta":{"content":"我不知道该做什么。"},"finish_reason":"stop"}]}""",
    )

    private suspend fun judge(vararg frames: String): Judge {
        val scripted = Scripted(frames.toList())
        return Judge(LlmClient(config, scripted.client), maxAttempts = 2)
    }

    @Test
    fun everyRequestGetsExactlyOneEntryAndTheTwoStepsAreToldApart() = runBlocking {
        val judge = judge(
            turn("t1", JudgeTools.TARGET, """{"id":"player"}"""),
            turn("e1", JudgeTools.NO_CHANGE, """{"note":"衣襟本来就不乱。"}"""),
        )

        judge.judgeAndCommit(tavern(), request("我低头理了理衣襟"))

        val timing = judge.timing
        assertEquals(2, timing.requests, "两步各一次调用，就该只有两条账")
        assertEquals(judge.requestCount, timing.requests, "账本条数必须等于请求数——否则真机上的数就是错的")
        assertEquals(1, timing.of(Stage.ALIGN).size)
        assertEquals(1, timing.of(Stage.EFFECT).size)
        assertEquals(0, timing.retries)
        assertEquals(0, timing.queryRounds)

        // **三笔钱必须对得上**：总 = 等 + 生成。这一条是这张表能被信的前提。
        assertEquals(timing.totalMs, timing.waitingMs + timing.thinkingMs)
    }

    @Test
    fun aSecondAttemptIsMarkedAsARetryAndTheFirstIsNot() = runBlocking {
        val judge = judge(
            saidNothing,
            turn("t1", JudgeTools.TARGET, """{"id":"player"}"""),
            turn("e1", JudgeTools.NO_CHANGE, """{"note":"衣襟本来就不乱。"}"""),
        )

        judge.judgeAndCommit(tavern(), request("我低头理了理衣襟"))

        val timing = judge.timing
        assertEquals(3, timing.requests)
        assertEquals(1, timing.retries, "第二步只试了一次，那次重试只能是第一步的")
        assertEquals(2, timing.of(Stage.ALIGN).size)

        val align = timing.of(Stage.ALIGN)
        assertTrue(!align[0].isRetry, "首次尝试不是重试")
        assertTrue(align[1].isRetry, "第二次尝试是重试")
        assertTrue(!align[1].isQueryRound, "重试与查询多要的那一轮不是一回事")
    }

    @Test
    fun aQueryHandCostsAnExtraRoundAndItIsCountedSeparately() = runBlocking {
        val judge = judge(
            turn("w1", JudgeTools.WIDEN_VIEW, """{"direction":"南"}"""),
            turn("t1", JudgeTools.TARGET, """{"id":"player"}"""),
            turn("e1", JudgeTools.NO_CHANGE, """{"note":"衣襟本来就不乱。"}"""),
        )

        judge.judgeAndCommit(tavern(), request("我低头理了理衣襟"))

        val timing = judge.timing
        assertEquals(3, timing.requests)
        assertEquals(0, timing.retries, "这一次没有重试，多出来的一轮是查询手要的")
        assertEquals(1, timing.queryRounds)

        val align = timing.of(Stage.ALIGN)
        assertEquals(2, align.size)
        assertEquals(JudgeTools.WIDEN_VIEW, align[0].tools.single(), "账上要留下它调了哪只手")
        assertEquals(2, align[1].round, "第二轮的来编号是 2")
    }

    @Test
    fun theReportAgreesWithTheNumbers() = runBlocking {
        val judge = judge(
            turn("t1", JudgeTools.TARGET, """{"id":"player"}"""),
            turn("e1", JudgeTools.NO_CHANGE, """{"note":"衣襟本来就不乱。"}"""),
        )

        judge.judgeAndCommit(tavern(), request("我低头理了理衣襟"))

        val report = judge.timing.report()
        assertTrue(report.startsWith("2 次调用"), "报告的开头就是这张账的摘要：$report")
        assertTrue(report.contains("重试 0 次"), report)
        assertTrue(report.contains("ALIGN"), report)
        assertTrue(report.contains("EFFECT"), report)
    }

    @Test
    fun aSecondJudgmentDoesNotCarryTheFirstOnesAccount() = runBlocking {
        // 每一步只喂一帧，`Scripted` 会把最后一帧重复给后面的请求——两次判定因此都能跑完。
        val judge = judge(
            turn("t1", JudgeTools.TARGET, """{"id":"player"}"""),
            turn("e1", JudgeTools.NO_CHANGE, """{"note":"衣襟本来就不乱。"}"""),
        )

        judge.judgeAndCommit(tavern(), request("我低头理了理衣襟"))
        judge.judgeAndCommit(tavern(), request("我低头理了理衣襟"))

        assertEquals(2, judge.timing.requests, "账是这一次判定的，不是这一局的")
    }

    @Test
    fun theLedgerKeepsTheTokensTheEndpointReported() = runBlocking {
        val judge = judge(
            turn(
                "t1",
                JudgeTools.TARGET,
                """{"id":"player"}""",
                tokens = Tokens(prompt = 1737, cacheHit = 1536, completion = 36),
            ),
            turn(
                "e1",
                JudgeTools.NO_CHANGE,
                """{"note":"衣襟本来就不乱。"}""",
                tokens = Tokens(prompt = 2840, cacheHit = 2688, completion = 145, reasoning = 73),
            ),
        )

        judge.judgeAndCommit(tavern(), request("我低头理了理衣襟"))

        val timing = judge.timing
        assertEquals(1737 + 2840, timing.promptTokens)
        assertEquals(1536 + 2688, timing.cacheHitTokens, "命中率是省钱那一半，也必须记全")
        assertEquals(36 + 145, timing.completionTokens)
        // **这一条是这一节存在的理由。** 实测（§14.22）里"等"占了 99%，而能把它拆开的只有这个数：
        // 12 秒的那次若报着 1400 个思维 token，那 12 秒就是模型在想；若报着 30 个，就是在排队。
        assertEquals(73, timing.reasoningTokens)

        val effect = timing.of(Stage.EFFECT).single()
        assertEquals(73, effect.reasoningTokens, "用量要跟在它那一次调用上，不能全堆在账尾")
        assertEquals(2688, effect.cacheHitTokens)

        val align = timing.of(Stage.ALIGN).single()
        assertEquals(0, align.reasoningTokens, "第一步关着思考，它的思维链就该是 0")
    }

    @Test
    fun theReportCarriesTheTokensToo() = runBlocking {
        val judge = judge(
            turn(
                "t1",
                JudgeTools.TARGET,
                """{"id":"player"}""",
                tokens = Tokens(prompt = 1737, cacheHit = 1536, completion = 36),
            ),
            turn(
                "e1",
                JudgeTools.NO_CHANGE,
                """{"note":"衣襟本来就不乱。"}""",
                tokens = Tokens(prompt = 2840, cacheHit = 2688, completion = 145, reasoning = 73),
            ),
        )

        judge.judgeAndCommit(tavern(), request("我低头理了理衣襟"))

        val report = judge.timing.report()
        assertTrue(report.contains("思维链 73"), report)
        assertTrue(report.contains("思 73"), report)
    }
}
