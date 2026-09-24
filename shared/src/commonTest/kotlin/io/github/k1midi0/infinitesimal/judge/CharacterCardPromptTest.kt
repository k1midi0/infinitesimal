package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import io.github.k1midi0.infinitesimal.world.rendered
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 角色卡怎么进提示词，用假端点钉住（§3.5）。
 *
 * 这件事**只有假端点验得稳**：真模型那边，"这一轮它有没有读到我给它的那张卡"只能靠它的输出猜；
 * 而这里问的是"请求体里有没有那几个字"，是个确定的问题。
 */
class CharacterCardPromptTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    private val drunkCard = "你是常来这家酒馆的矿工，说话很慢。"

    private fun world(): World = World.from(
        WorldSnapshot(
            StartingWorld.tavern() + Entity(
                id = "drunk", kind = "角色", label = "醉汉",
                x = 1200, y = 500, z = 0, parent = "tavern_hall",
                cards = listOf(CharacterCard("身份", drunkCard)),
            ),
        ),
    )

    @Test
    fun titlesAndEmptyCardsNeverEnterThePrompt() {
        // 标题是给写卡的人看的（「身份」还是「口癖」），模型不需要知道这一段被归为哪一类；
        // 空卡是刚加上还没写字的，它不该在提示词里留下几个空行。
        val text = listOf(
            CharacterCard("身份", "  第一段  "),
            CharacterCard("口癖", ""),
            CharacterCard("交互范围", "第二段"),
        ).rendered()

        assertEquals("第一段\n\n第二段", text)
        assertFalse(text.contains("身份"), text)
        assertFalse(text.contains("口癖"), text)
    }

    @Test
    fun theActorsCardIsInBothStepsAndTheTargetsOnlyInTheSecond() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", JudgeTools.TARGET, """{"id":"drunk"}"""),
                turn("c2", JudgeTools.REJECT, """{"reason":"你还没走过去。"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, script.client))
        val w = world()
        val request = JudgeRequest("player", "你", "我问那个醉汉几句话。", emptyList(), emptyList())

        judge.alignTargets(w, request)
        judge.proposeEffects(w, request, listOf("drunk"))

        val align = script.bodies[0]
        val effects = script.bodies[1]

        // 发起者自己的卡**两步都要在**——它拼在共用前缀里，而那一段两步一字不差，缓存才命中。
        assertTrue(align.contains("你伸得着大约一格以内的东西"), align)
        assertTrue(effects.contains("你伸得着大约一格以内的东西"), effects)

        // 被指向的角色的卡**只进第二步**：第一步还不知道要指向谁。
        assertFalse(align.contains(drunkCard), align)
        assertTrue(effects.contains(drunkCard), effects)

        // 标题仍然不进。
        assertFalse(effects.contains("交互范围"), effects)
    }
}
