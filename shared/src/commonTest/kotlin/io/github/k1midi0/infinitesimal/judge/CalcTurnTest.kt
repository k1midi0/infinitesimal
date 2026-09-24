package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.RuleEntry
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * `calc` 接进那个回合循环之后的样子（§12 第 12 项）。
 *
 * 它和 `widen_view` 是同一类手——**调用之后这一轮接着往下走**——但有一处不一样，而那一处正是这个
 * 测试要钉的：`widen_view` 之前，循环的判据是写死的 `it.name != WIDEN_VIEW`。加 `calc` 那天要是
 * 忘了改，模型算的那个式子会被当成**一条后果**，而 `parseEffect` 不认识它，整轮判定直接报协议内
 * 失败。**离线能抓住这个，联机只会看到一次莫名其妙的失败。**
 */
class CalcTurnTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    /** 开局世界，外加一张"硬度是 6"的规则书条目——变量要有个来源。 */
    private fun world(): World {
        val entities = StartingWorld.tavern().map {
            if (it.id == "oak_table") {
                it.copy(rulebook = Rulebook(listOf(RuleEntry("这是一张橡木桌。", values = mapOf("硬度" to "6")))))
            } else {
                it
            }
        }
        return World.from(WorldSnapshot(entities))
    }

    private fun request(intention: String, selected: List<String> = emptyList()) = JudgeRequest(
        actorId = "player",
        actorLabel = "你",
        intention = intention,
        selected = selected,
        centers = emptyList(),
    )

    @Test
    fun calculatingContinuesTheTurnInsteadOfEndingIt() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", JudgeTools.TARGET, """{"id":"oak_table"}"""),
                // 同一轮里既算了一个式子、又报了一条后果。**两条都不能丢**：算的那条要被执行，
                // 报的那条是模型已经做出的判断（它没有理由再说一次）。
                multiTurn(
                    Triple("c2", JudgeTools.CALC, """{"expression":"oak_table.硬度 * 2"}"""),
                    Triple("c3", JudgeTools.CHANGE_APPEARANCE, """{"id":"oak_table"}"""),
                ),
                // 第三轮模型什么也没调（想完了）。**这一轮结束，前两轮攒下的东西一件都不能少。**
                sse(),
            ),
        )
        val judge = Judge(LlmClient(config, script.client))

        val judgment = judge.judgeAndCommit(world(), request("我擦了擦桌子", selected = listOf("oak_table")))

        assertEquals(3, judge.requestCount, "对齐一次、算数一次、收尾一次")
        assertEquals(listOf("oak_table.硬度 * 2"), judge.calcExpressions)
        assertTrue(judgment is Judgment.Applied, "实际：$judgment")
        assertEquals(1, (judgment as Judgment.Applied).consequences.size, "算数不该被当成一条后果")
        // 第三次请求里要能看见算式与结果——模型是照着这句话往下走的。
        assertTrue(
            script.bodies[2].contains("oak_table.硬度 * 2 = 12"),
            "算式与结果要回灌给模型：\n${script.bodies[2].take(600)}",
        )
    }

    @Test
    fun aBadExpressionIsAnsweredNotTreatedAsFailure() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", JudgeTools.TARGET, """{"id":"oak_table"}"""),
                turn("c2", JudgeTools.CALC, """{"expression":"oak_tabel.硬度 + 1"}"""),
                turn("c3", JudgeTools.CHANGE_APPEARANCE, """{"id":"oak_table"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, script.client))

        val judgment = judge.judgeAndCommit(world(), request("我擦了擦桌子", selected = listOf("oak_table")))

        assertTrue(judgment is Judgment.Applied, "打错一个名字不该让整轮判定失败，实际：$judgment")
        assertTrue(
            script.bodies[2].contains("oak_tabel.硬度"),
            "打错的名字要原样回给模型，它才知道改哪儿：\n${script.bodies[2].take(600)}",
        )
    }

    @Test
    fun theAvailableNumbersAreListedInThePrompt() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", JudgeTools.TARGET, """{"id":"oak_table"}"""),
                turn("c2", JudgeTools.CHANGE_APPEARANCE, """{"id":"oak_table"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, script.client))

        // **第一步的数来自「发起者 + 玩家勾的那些」**——那时还不知道要对齐到谁。所以勾上桌子，
        // 它那条 `硬度=6` 才在清单里。
        judge.judgeAndCommit(world(), request("我擦了擦桌子", selected = listOf("oak_table")))

        // **清单是变量的唯一来源**：工具 schema 是静态的，列不出动态的名字，只能写进提示词。
        assertTrue(script.bodies[0].contains("【可以算的数】"), script.bodies[0].takeLast(900))
        assertTrue(script.bodies[0].contains("oak_table.硬度=6"), script.bodies[0].takeLast(900))
        // 「材质=橡木」不是数，所以它不出现在清单里。
        assertTrue(!script.bodies[0].contains("oak_table.材质"), script.bodies[0].takeLast(900))
    }
}
