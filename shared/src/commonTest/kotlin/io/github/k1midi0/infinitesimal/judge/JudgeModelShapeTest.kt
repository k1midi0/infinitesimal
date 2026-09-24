package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 判定里那条"生出新东西 → 给它建模 → 形状落到它身上"的路（附录 A.9）。
 *
 * 用两个假端点：一个当判定，一个当建模。能这么测，是因为建模**本来就是一个独立的模型段**——如果它
 * 是判定那一轮里的一个函数，这条路就没法离线钉住，只能靠联网跑探针看运气。
 *
 * 钉住的三件事，每一件坏了都会表现成同一句"新东西没有形状"：
 *
 *  1. `model_shape` 是查询类的手，调用它这一轮要接着往下走，而不是当场收工；
 *  2. 造出来的形状**由代码补成一条后果**，和模型给的后果一起走 [Committer]，不是直接写进世界；
 *  3. 补的那条要排在**后面**（[ShapeLog.consequences] 那条注释）。
 */
class JudgeModelShapeTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    private val brief = "被从中间劈开的橡木方桌的一半，断面粗糙"

    private fun tavern(): World = World.from(WorldSnapshot(StartingWorld.tavern()))

    private fun request(intention: String, selected: List<String> = emptyList()) = JudgeRequest(
        actorId = "player",
        actorLabel = "你",
        intention = intention,
        selected = selected,
        centers = selected,
    )

    @Test
    fun aModeledShapeLandsOnTheThingTheJudgeJustMade() = runBlocking {
        val judgeScript = Scripted(
            listOf(
                turn("t1", JudgeTools.TARGET, """{"id":"oak_table"}"""),
                turn("m1", JudgeTools.MODEL_SHAPE, """{"id":"table_half","brief":"$brief"}"""),
                turn("s1", JudgeTools.SPAWN, """{"id":"table_half","kind":"家具","label":"半张木桌","x":1200,"y":400,"z":0}"""),
            ),
        )
        val judge = Judge(
            LlmClient(config, judgeScript.client),
            2,
            ReasoningEffort.HIGH,
            ShapeModeler(
                LlmClient(
                    config,
                    Scripted(
                        listOf(
                            turn("f0", "voxel_floor", """{"z":0,"rows":"###\n###\n###"}"""),
                            turn("done", "voxel_done", """{"name":"half_oak_table"}"""),
                        ),
                    ).client,
                ),
            ),
        )

        val world = tavern()
        val judgment = judge.judgeAndCommit(world, request("我一斧头把桌子劈成两半"))

        val applied = assertIs<Judgment.Applied>(judgment)
        // 世界级结果：新东西在，而且**它有形状**。
        val half = assertNotNull(world.byId("table_half"), "新东西没有进世界")
        assertEquals(9, half.shape?.cellCount, "半张桌子该是 3×3 一格厚：${half.shape}")

        // 两条后果：模型给的那条，加上代码补的形状。**形状排在后面。**
        assertEquals(2, applied.consequences.size)
        assertIs<Consequence.Spawn>(applied.consequences.first())
        assertIs<Consequence.SetShape>(applied.consequences.last())
        // 判定跑了三次：对齐一次、建模后接着干两次。
        assertEquals(3, judge.requestCount)

        // 模型必须**看得见**形状造好了，否则它会再调一次 model_shape。
        val third = judgeScript.bodies[2]
        assertTrue(third.contains("造好了"), "第三次请求里没有建模的结果：$third")
    }

    @Test
    fun whenModelingFailsTheJudgeIsToldAndTheNewThingStillGetsNoShape() = runBlocking {
        val judgeScript = Scripted(
            listOf(
                turn("t1", JudgeTools.TARGET, """{"id":"oak_table"}"""),
                turn("m1", JudgeTools.MODEL_SHAPE, """{"id":"table_half","brief":"$brief"}"""),
                turn("s1", JudgeTools.SPAWN, """{"id":"table_half","kind":"家具","label":"半张木桌","x":1200,"y":400,"z":0}"""),
            ),
        )
        val judge = Judge(
            LlmClient(config, judgeScript.client),
            2,
            ReasoningEffort.HIGH,
            // 建模那边直接放弃。
            ShapeModeler(
                LlmClient(
                    config,
                    Scripted(listOf(turn("give", "give_up", """{"reason":"说不清断面"}"""))).client,
                ),
            ),
        )

        val world = tavern()
        val judgment = judge.judgeAndCommit(world, request("我一斧头把桌子劈成两半"))

        assertIs<Judgment.Applied>(judgment)
        val half = assertNotNull(world.byId("table_half"))
        // **造不出来不是协议内失败**：模型可以绕开它继续列后果，世界照常改。
        assertNull(half.shape)
        assertEquals(3, judge.requestCount)

        val third = judgeScript.bodies[2]
        assertTrue(third.contains("没有造出形状"), "模型没有被告知建模失败：$third")
        assertTrue(third.contains("说不清断面"), third)
    }

    @Test
    fun modelingIsOfferedAsAQueryHandSoTheTurnKeepsGoing() = runBlocking {
        // 这一条钉的是 `isQuery`：少了它，`model_shape` 会被当成一条后果，
        // 然后整轮判定在解析那一步报"不认识的提议"——症状是新东西没有形状、外加一次协议内失败。
        assertTrue(
            JudgeTools.EFFECTS.any { it.function.name == JudgeTools.MODEL_SHAPE },
            "第二步的工具里没有 model_shape",
        )
        assertTrue(
            JudgeTools.TARGETING.none { it.function.name == JudgeTools.MODEL_SHAPE },
            "第一步不该有 model_shape：那时还不知道会生出什么新东西",
        )
    }
}
