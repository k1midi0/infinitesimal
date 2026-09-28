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
import io.github.k1midi0.infinitesimal.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **人设追加那只手**（§14.52）。
 *
 * 这一支钉四件事：
 *
 *  1. 那一次写真的落库了，而且答复里说得出"现在是哪几张卡"（§1.5 第三条：理由不撒谎）。
 *  2. 驳回**在落库之前**发生，且理由是可执行的（看不见的人写不了）。
 *  3. 思考档的提示词里**列出了各人现有卡的标题**——`write_card` 是按标题改的，不列标题它只能猜，
 *     而猜错的后果是在原来那张旁边又添一张互相矛盾的卡。被叫起来时还要点名"这一次看的人"。
 *  4. 那只手**只在思考档**（快速档给不得）。
 */
class DirectorCardTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun world(): World = World.from(StartingWorld.snapshot())

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: io.github.k1midi0.infinitesimal.world.ChunkKey(0, 0))

    /** **它从氛围角色变成了活跃角色**那一次唤醒（§14.52）：理由里点名是谁、这一段说的是补背景。 */
    private fun turningActive(world: World, id: String = "dwarf") = Waking(
        Wake.Called(
            why = "「杜林」（id 是「$id」）刚刚从**氛围角色变成了活跃角色**：有人跟它说上话了。",
            aboutId = id,
        ),
        "player",
        origin(world),
    )

    private fun director(script: Scripted) = Director(
        LlmClient(config, script.client),
        TerrainGenerator(LlmClient(config, script.client)),
    )

    /** 一次 `write_card` 调用。`cards` 是模型会写的那种多行文本。 */
    private fun writeCard(
        cards: String,
        id: String = "dwarf",
        callId: String = "c1",
    ) = turn(callId, DirectorTools.WRITE_CARD, """{"id":"$id","cards":"${esc(cards)}"}""")

    private fun doneSaying(text: String = "写好了。") = sse(
        """{"choices":[{"delta":{"content":"${text}"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
    )

    @Test
    fun aCardIsWrittenAndTheAnswerSaysWhatItHasNow() = runBlocking {
        val script = Scripted(
            listOf(
                writeCard("经历｜你替那个走南闯北的过路人挡了一刀，疤留在左臂上。"),
                doneSaying("写好了。"),
            ),
        )
        val world = world()

        val result = director(script).wake(world, turningActive(world))

        assertTrue(result is Awakening.Done, "写过就算完成，实得 $result")
        val cards = world.byId("dwarf")!!.cards
        assertEquals("经历", cards.last().title, "新的一张添在末尾")
        assertTrue("挡了一刀" in cards.last().content)
    }

    @Test
    fun rewritingAnExistingCardShowsUpInTheWorld() = runBlocking {
        val script = Scripted(
            listOf(
                writeCard("口癖｜你现在说到钱的时候不哼了。"),
                doneSaying(),
            ),
        )
        val world = world()

        director(script).wake(world, turningActive(world))

        val cards = world.byId("dwarf")!!.cards
        assertEquals(4, cards.size, "改写不添新卡")
        assertEquals("你现在说到钱的时候不哼了。", cards.first { it.title == "口癖" }.content)
    }

    /**
     * 看不见的人写不了——而且**世界一个字都没动**（驳回发生在落库之前）。
     */
    @Test
    fun somebodyTheDirectorCannotSeeIsRefusedBeforeAnythingChanges() = runBlocking {
        // 把矮人挪到很远的地方（八千米外），于是它不在玩家那一片的凸包里。
        val world = world()
        val dwarf = world.byId("dwarf")!!
        world.put(dwarf.copy(x = 80_000, y = 80_000))
        val script = Scripted(
            listOf(
                writeCard("经历｜编的。"),
                doneSaying(),
            ),
        )

        director(script).wake(world, turningActive(world))

        assertEquals(
            listOf("身份", "性格", "交互范围", "口癖"),
            world.byId("dwarf")!!.cards.map { it.title },
            "驳回之后一张卡都没动",
        )
        // 而理由要说清为什么：看不见它，写出来只能是编的。
        val toolResult = script.bodies[1]
        assertTrue("看不见" in toolResult || "看得见" in toolResult, "理由要说得具体")
    }

    @Test
    fun theThinkingPromptListsEveryVisibleCharactersCards() = runBlocking {
        val script = Scripted(listOf(doneSaying("什么都不用写。")))
        val world = world()

        director(script).wake(world, turningActive(world))

        val body = script.bodies.first()
        // **按标题改**要求它先知道有哪些标题——这一节就是把那件事做掉的地方。
        assertTrue("人设：" in body, "看得见的角色那一节要列出各人的卡：${body.take(400)}")
        assertTrue("身份" in body && "口癖" in body, "要列出矮人现有的标题")
        // 而被叫起来的理由里点了名的那个，要再出现一次（它才是这一次要看的人）。
        assertTrue("【这一次要看的人】" in body, "要点名这一次看的人")
        assertTrue("write_card" in body, "要告诉它用哪只手写")
    }

    @Test
    fun theFastTierDoesNotHaveThisHand() {
        val fast = DirectorTools.of(Tier.FAST).map { it.function.name }
        assertFalse(DirectorTools.WRITE_CARD in fast, "快速档不该有它：$fast")
        assertTrue(DirectorTools.WRITE_CARD in DirectorTools.of(Tier.THINKING).map { it.function.name })
    }

    /**
     * 那一只手**不要求**它必须写点什么：`Called` 的 `unmet` 是空的（§14.28 那条的反面——"什么都没做"
     * 在硬圈缺口那里是失败，在这里是一个合法的判断）。
     */
    @Test
    fun doingNothingIsAFinishedAnswer() = runBlocking {
        val script = Scripted(listOf(doneSaying("这一趟什么都没改变它。")))
        val world = world()

        val result = director(script).wake(world, turningActive(world))

        assertTrue(result is Awakening.Done, "沉默也算干完了，实得 $result")
        assertEquals(4, world.byId("dwarf")!!.cards.size, "什么都没写")
    }
}
