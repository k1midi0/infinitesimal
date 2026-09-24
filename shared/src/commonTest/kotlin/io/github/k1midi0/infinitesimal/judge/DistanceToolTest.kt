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
 * `distance` 那只手：**它是查询类，答案要灌回历史，两步都拿得到**。
 *
 * 加它的理由写在 [JudgeTools.DISTANCE] 与 [io.github.k1midi0.infinitesimal.spatial.Distance] 上——
 * 一句话：算得对不该那么贵（§14.24 里模型自己推式子，烧了四千多个思维 token、22 秒）。
 */
class DistanceToolTest {

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

    private suspend fun judge(vararg frames: String): Judge =
        Judge(LlmClient(config, Scripted(frames.toList()).client), maxAttempts = 2)

    /**
     * 第一步问了一次距离，**那一次不能算成一条后果**——它没提议改世界。
     *
     * 这是最要紧的一条：`isQuery` 漏掉一只查询手时，它会被当成"报了个东西"，整轮判定从此错得很安静
     * （把 `calc` 加进来那天就踩过同一个坑，见 `Judge.call` 的注释）。
     */
    @Test
    fun askingForADistanceIsAQueryNotAProposal() = runBlocking {
        val judge = judge(
            turn("d1", JudgeTools.DISTANCE, """{"a":"player","b":"wine_cup"}"""),
            turn("t1", JudgeTools.TARGET, """{"id":"wine_cup"}"""),
            turn("e1", JudgeTools.NO_CHANGE, """{"note":"够得着，但你没真伸出去。"}"""),
        )

        val judgment = judge.judgeAndCommit(tavern(), request("我把桌上那只酒杯端起来"))

        assertTrue(judgment is Judgment.Unchanged, "应当时成立未变，实得 $judgment")
        // 三步：问距离 + 第一步报对象 + 第二步列后果。distance 多要的那一轮**不算多一次判定**。
        assertEquals(3, judge.timing.requests)
        assertEquals(1, judge.timing.queryRounds, "多出来的那一轮是查询手要的")
        assertEquals(0, judge.timing.retries, "这不是重试")
    }

    @Test
    fun thePairsItAskedAboutAreRecorded() = runBlocking {
        val judge = judge(
            turn("d1", JudgeTools.DISTANCE, """{"a":"player","b":"wine_cup"}"""),
            turn("t1", JudgeTools.TARGET, """{"id":"wine_cup"}"""),
            turn("e1", JudgeTools.NO_CHANGE, """{"note":"够得着。"}"""),
        )

        judge.judgeAndCommit(tavern(), request("我把桌上那只酒杯端起来"))

        // 这一栏本身就是诊断：判"够不够得着"而这一栏是空的，说明它又在自己推式子了。
        assertEquals(listOf("player→wine_cup"), judge.distancePairs)
    }

    /** 答案真的回到了模型手里：那一句里必须有格距、高度差、直线距离这三个数。 */
    @Test
    fun theAnswerCarriesAllThreeNumbers() = runBlocking {
        val scripted = Scripted(
            listOf(
                turn("d1", JudgeTools.DISTANCE, """{"a":"player","b":"wine_cup"}"""),
                turn("t1", JudgeTools.TARGET, """{"id":"wine_cup"}"""),
                turn("e1", JudgeTools.NO_CHANGE, """{"note":"够得着。"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, scripted.client), maxAttempts = 2)

        judge.judgeAndCommit(tavern(), request("我把桌上那只酒杯端起来"))

        // 第二帧的请求体里带着第一轮的助手消息与那条 tool 结果。
        val followUp = scripted.bodies[1]
        assertTrue(followUp.contains("格距 1 格"), "第一个数：格距（「一格以内」说的就是它）\n$followUp")
        assertTrue(followUp.contains("高度差 80 厘米"), "第二个数：高度差\n$followUp")
        assertTrue(followUp.contains("直线距离 128 厘米"), "第三个数：直线距离\n$followUp")
    }

    /** 第二步也拿得到这只手——"够不着"正是在那一步判的。 */
    @Test
    fun theEffectStepOffersItToo() = runBlocking {
        assertTrue(JudgeTools.EFFECTS.any { it.function.name == JudgeTools.DISTANCE })
        assertTrue(JudgeTools.TARGETING.any { it.function.name == JudgeTools.DISTANCE })
    }

    /**
     * **第一步问过的，第二步不必再问。**
     *
     * 这一条是拿真机读数换来的（§14.25）：带上它之前，同一次判定里 `player→wine_cup` 会被问两遍，
     * 多烧整整一次往返——而两步看的是同一个世界，答案不可能变。
     */
    @Test
    fun theSecondStepAlreadyHasTheDistancesTheFirstStepAskedAbout() = runBlocking {
        val scripted = Scripted(
            listOf(
                turn("d1", JudgeTools.DISTANCE, """{"a":"player","b":"wine_cup"}"""),
                turn("t1", JudgeTools.TARGET, """{"id":"wine_cup"}"""),
                turn("e1", JudgeTools.NO_CHANGE, """{"note":"够得着。"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, scripted.client), maxAttempts = 2)

        judge.judgeAndCommit(tavern(), request("我把桌上那只酒杯端起来"))

        // 第 1 次是第一步问距离、第 2 次是第一步报对象、第 3 次就是第二步。
        val effect = scripted.bodies[2]
        assertTrue(effect.contains("这一步之前已经问过的距离"), effect)
        assertTrue(effect.contains("格距 1 格"), "答案原文也要在，只写「问过」是不够的：\n$effect")
    }

    /** id 抄错了是模型下一轮自己能修的一步，**不是协议内失败**（与 `calc` 同一条理由）。 */
    @Test
    fun anUnknownIdComesBackAsAnAnswerNotAFailure() = runBlocking {
        val judge = judge(
            turn("d1", JudgeTools.DISTANCE, """{"a":"player","b":"no_such_thing"}"""),
            turn("t1", JudgeTools.TARGET, """{"id":"player"}"""),
            turn("e1", JudgeTools.NO_CHANGE, """{"note":"衣襟本来就不乱。"}"""),
        )

        val judgment = judge.judgeAndCommit(tavern(), request("我低头理了理衣襟"))

        assertTrue(judgment is Judgment.Unchanged, "整轮不该因为一个抄错的 id 重来，实得 $judgment")
        assertEquals(0, judge.timing.retries)
    }
}
