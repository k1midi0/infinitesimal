package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 一次判定被切成两段（§4.7 纠正预览）之后，**那两段之间还连着的东西**。
 *
 * 最容易悄悄坏掉的就是扩大预算：它在第一步扩过，第二步必须拿到**已经大了的那个框**——预算是这次
 * 动作的属性，不是某一步的属性（§4.6）。这件事在界面上看不出来（结果照样是一个"成立"或"驳回"），
 * 只有把两次请求体摆在一起才看得见。
 */
class JudgeTurnTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    private fun tavern(): World = World.from(WorldSnapshot(StartingWorld.tavern()))

    @Test
    fun theWidenedFrameCarriesFromAlignIntoFinish() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", JudgeTools.WIDEN_VIEW, "{}"),
                turn("c2", JudgeTools.TARGET, """{"id":"wine_cup"}"""),
                turn("c3", JudgeTools.CHANGE_APPEARANCE, """{"id":"wine_cup"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, script.client))
        val world = tavern()
        val turn = judge.beginTurn(world, request("我把桌上那杯麦酒擦了擦"))

        assertEquals(TargetAlignment.Aligned(listOf("wine_cup")), turn.align())
        assertEquals(2, script.bodies.size, "第一步里扩了一次，所以是两次请求")

        // **第二步这一眼必须落在扩大后的框里**，而不是退回去看默认那一片。
        turn.finish(listOf("wine_cup"))

        assertEquals(3, script.bodies.size)
        assertTrue(script.bodies[0].contains("x 9..15"), "第一眼该是 7 格宽：${script.bodies[0]}")
        assertTrue(script.bodies[2].contains("x 6..18"), "第二步该拿到扩大后的框：${script.bodies[2]}")
    }

    @Test
    fun alignDoesNotTouchTheWorld() = runBlocking {
        // 预览能停下来的前提就是它：第一步只对齐，不改世界。代码里靠"第一步的工具表里没有改世界的
        // 手"保证，这条钉住那个保证。
        val script = Scripted(listOf(turn("c1", JudgeTools.TARGET, """{"id":"oak_table"}""")))
        val judge = Judge(LlmClient(config, script.client))
        val world = tavern()
        val before = world.all().map { it.id to it.text() }.toMap()

        judge.beginTurn(world, request("我掀桌子")).align()

        assertEquals(before, world.all().map { it.id to it.text() }.toMap(), "第一步不该改世界")
    }

    private fun request(intention: String) = JudgeRequest(
        actorId = "player",
        actorLabel = "你",
        intention = intention,
        selected = emptyList(),
        centers = emptyList(),
    )
}
