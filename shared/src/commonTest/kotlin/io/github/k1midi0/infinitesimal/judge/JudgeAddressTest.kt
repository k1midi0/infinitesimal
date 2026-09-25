package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 第一步的两栏：**动作指向的东西**与**这句话说给谁听**（§14.37）。
 *
 * 分开是有代价的（多一只工具、多一栏要解析），所以这里要把"分开买到了什么"钉住：
 *
 *  - `target` 只装动作作用到的东西，**纠正预览比对的就是它**（§4.7）。
 *  - `address` 只装这句话指向的人。
 *  - 两栏在**同一次第一步**里给出——早先说话要单独再跑一次对齐去找听者。
 */
class JudgeAddressTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun judgeOf(script: Scripted) = Judge(LlmClient(config, script.client))

    private fun world(): World = World.from(StartingWorld.snapshot())

    @Test
    fun theTwoColumnsStayApart() = runBlocking {
        val script = Scripted(
            listOf(
                multiTurn(
                    Triple("t1", "target", """{"id":"oak_table"}"""),
                    Triple("t2", "address", """{"id":"dwarf"}"""),
                ),
            ),
        )

        val turn = judgeOf(script).beginTurn(
            world(),
            JudgeRequest(
                actorId = "player",
                actorLabel = "你",
                intention = "我把桌子擦了擦，扭头对矮人说了一句。",
                selected = listOf("oak_table"),
                centers = listOf("oak_table"),
                said = "借过。",
            ),
        )

        val aligned = turn.align() as TargetAlignment.Aligned
        assertEquals(listOf("oak_table"), aligned.targets, "动作指向的东西只有桌子")
        assertEquals(listOf("dwarf"), aligned.addressed, "这句话说给矮人听")
    }

    @Test
    fun bothTheSpeechAndTheColumnAreInTheFirstStepPrompt() = runBlocking {
        val script = Scripted(
            listOf(
                multiTurn(
                    Triple("t1", "target", """{"id":"oak_table"}"""),
                    Triple("t2", "address", """{"id":"dwarf"}"""),
                ),
            ),
        )

        judgeOf(script).beginTurn(
            world(),
            JudgeRequest("player", "你", "我把桌子擦了擦。", listOf("oak_table"), listOf("oak_table"), said = "借过。"),
        ).align()

        val body = script.bodies.single()
        assertTrue("借过。" in body, "原话要进第一步的提示词，它才判得了指向谁")
        assertTrue("\"name\":\"address\"" in body, "这一轮说了话，address 那只手要在表里")
    }

    @Test
    fun withNothingSaidTheModelIsToldNotToUseItAtAll() = runBlocking {
        val script = Scripted(listOf(turn("t1", "target", """{"id":"oak_table"}""")))

        judgeOf(script).beginTurn(
            world(),
            JudgeRequest("player", "你", "我把桌子擦了擦。", listOf("oak_table"), listOf("oak_table")),
        ).align()

        val body = script.bodies.single()
        // 这只手仍然在工具表里（表是固定的），教它"这时候别用"只能靠提示词。
        assertTrue("没有说话" in body, "没说话那一轮要明说 address 一次都别调")
        // 而没有 address 时那一栏就是空的——不是"报了个空字符串"。
        assertEquals(emptyList(), run {
            val only = Scripted(listOf(turn("t1", "target", """{"id":"oak_table"}""")))
            val aligned = judgeOf(only).beginTurn(
                world(),
                JudgeRequest("player", "你", "我把桌子擦了擦。", listOf("oak_table"), listOf("oak_table")),
            ).align() as TargetAlignment.Aligned
            aligned.addressed
        })
    }
}
