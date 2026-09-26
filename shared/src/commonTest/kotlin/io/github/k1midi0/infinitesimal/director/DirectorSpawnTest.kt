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
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.World
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **导演那只"叫一个人来"的手**（§8.5、§14.46）。
 *
 * 这一支钉的是接线，不是模型的判断力：
 *
 *  1. 放下去的人**真的在世界里**（位置、名字、卡），而答复里那句"放好了"只在落库之后出现；
 *  2. **id 已经有人用过 → 当场驳回，而世界一个字不动**——这只手只放新人（用户定的那一条）；
 *  3. 有作息的人**出生那一刻就站在第一格上**；没作息的人 `cycle` 是空的（剧情角色那个档）；
 *  4. `background` 由代码从它站的地方推出来（照抄那一句，不编）。
 *
 * 更深的那几条门（看不见的位置、作息里每一格都得看得见、第一格必须就是它站的地方）在
 * [CharacterPlannerTest] 里各自钉住——那一层不必过模型。
 */
class DirectorSpawnTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun world(): World = World.from(StartingWorld.snapshot())

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun arranging(world: World) = Waking(Wake.Arrange, "player", origin(world))

    private fun director(script: Scripted) = Director(
        LlmClient(config, script.client),
        TerrainGenerator(LlmClient(config, script.client)),
    )

    private fun doneSaying(text: String = "放好了。") = sse(
        """{"choices":[{"delta":{"content":"${text}"}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
    )

    /** 一次 `spawn_character` 调用。参数逐字写成模型会写的那种多行文本。 */
    private fun spawn(
        id: String,
        cards: String,
        at: String,
        beats: String = "",
        label: String = "铁匠格里姆",
        callId: String = "c1",
    ) = turn(
        callId,
        DirectorTools.SPAWN_CHARACTER,
        """{"id":"$id","label":"$label","cards":"${esc(cards)}","at":"$at","beats":"${esc(beats)}"}""",
    )

    /**
     * 排一件将来的事。
     *
     * **`Arrange` 那一次唤醒要求至少排一件**（§14.28：空手等于什么都没安排），所以"只放了一个人"那
     * 支会以 `FailedProtocol` 收场——那是判据，不是放人失败。这一支要验的是放人，所以顺手排一件。
     */
    private fun schedule(id: String, hour: Int = 22, callId: String = "c9") = turn(
        callId,
        DirectorTools.SCHEDULE,
        """{"id":"$id","day":1,"hour":$hour,"minute":30,"what":"收工了"}""",
    )

    @Test
    fun aStoryCharacterWithNoCycleIsPlacedAndWaits() = runBlocking {
        val script = Scripted(
            listOf(
                spawn(
                    id = "smith",
                    cards = "身份｜你是铁匠格里姆，四十来岁，左腿有点跛，镇上大半的钉子都是你打的。",
                    at = "1000,200,12800",
                ),
                schedule("smith_done"),
                doneSaying(),
            ),
        )
        val world = world()

        val result = director(script).wake(world, arranging(world))

        assertTrue(result is Awakening.Done, "实得 $result")
        val smith = world.byId("smith")
        assertTrue(smith != null, "人要在世界里——不能只是回话说放好了")
        assertEquals(CHARACTER_KIND, smith.kind)
        assertEquals("铁匠格里姆", smith.label)
        assertEquals(1000, smith.x)
        assertEquals(12800, smith.z)
        assertEquals(1, smith.cards.size)
        assertEquals("身份", smith.cards.first().title)
        assertTrue(smith.cards.first().content.contains("钉子"), "卡的内容要逐字写进去")
        assertTrue(smith.cycle.isEmpty, "**没写作息＝剧情角色**：它待在原地，被叫到才动")
        assertTrue(
            smith.background?.contains("铁匠格里姆") == true,
            "background 由代码从它站的地方推：实得 ${smith.background}",
        )
    }

    @Test
    fun aCharacterWithACycleStartsOnItsFirstBeat() = runBlocking {
        val script = Scripted(
            listOf(
                spawn(
                    id = "smith",
                    cards = "身份｜你是铁匠格里姆。",
                    at = "1000,200,12800",
                    beats = "0｜1000,200,12800｜在铺子里拉风箱。\n300｜1300,100,12800｜去地窖拿炭。",
                ),
                schedule("smith_done"),
                doneSaying(),
            ),
        )
        val world = world()

        val result = director(script).wake(world, arranging(world))

        assertTrue(result is Awakening.Done, "实得 $result")
        val smith = world.byId("smith")!!
        assertEquals(listOf(0, 300), smith.cycle.beats.map { it.atMinute })
        assertEquals(1000, smith.x, "出生那一刻就在第一格上")
        assertEquals(200, smith.y)
    }

    @Test
    fun anIdThatIsAlreadyTakenIsRefusedAndNothingMoves() = runBlocking {
        val script = Scripted(
            listOf(
                spawn(
                    id = "dwarf",
                    cards = "身份｜你是另一个人，也叫杜林。",
                    at = "1000,200,12800",
                    label = "另一个杜林",
                ),
                turn("c2", DirectorTools.SCHEDULE, """{"id":"a","day":2,"hour":6,"what":"天亮了"}"""),
                doneSaying(),
            ),
        )
        val world = world()
        val before = world.byId("dwarf")!!

        director(script).wake(world, arranging(world))

        assertEquals(before, world.byId("dwarf"), "**世界一个字不动**：这只手只放新人")
        assertTrue(script.bodies[1].contains("已经有 id"), "驳回理由要进历史：\n${script.bodies[1].take(600)}")
        assertTrue(script.bodies[1].contains("write_cycle"), "还要告诉它改日子走哪只手")
    }

    /**
     * 它在提示词里看得见那些人**现在有没有作息**，而它自己手里那只手也在。
     *
     * 两者都不是装饰：名单决定它能给谁写作息（§14.44 那道门），而"谁没有作息"正是它决定要不要放一个
     * 氛围角色的依据（§14.46）。
     */
    @Test
    fun thePromptListsTheVisibleCharactersAndTheirCycles() = runBlocking {
        val script = Scripted(listOf(doneSaying(), doneSaying()))
        val world = world()

        director(script).wake(world, arranging(world))

        val body = script.bodies.first()
        assertTrue("【你这一轮看得见的角色" in body, "名单要有：\n$body")
        assertTrue("酒馆里的矮人" in body, "看得见的角色要列出来")
        assertTrue("有作息（4 格）" in body, "要写得出它有没有作息：\n$body")
        assertTrue("\"name\":\"${DirectorTools.SPAWN_CHARACTER}\"" in body, "这只手要真的给它：\n$body")
    }
}
