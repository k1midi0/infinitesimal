package io.github.k1midi0.infinitesimal.npc

import io.github.k1midi0.infinitesimal.config.ContextSettings
import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.Active
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.Note
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **笔记那一节在提示词里长什么样**（§6.3、§6.5、§6.6）。
 *
 * 引擎那一侧（写得进、上限、替代）在 `NpcTest` 里。这一支只管**读**——因为它管的是这一节的三条有意的
 * 选择，而三条错了都不会报错：
 *
 *  1. **常驻**：每一轮的提示词里都有它，而且**与压缩无关**——历史被压掉多少轮，它都在。
 *  2. **被替代的不注入**：两条互相矛盾的话同时出现时，模型会两条都信（§6.4）。
 *  3. **截断看得见**（§6.5）：静默消失会让它以为"我没记过这件事"，然后重新写一条——越记越多，
 *     越多越看不见。
 */
class NpcNotePromptTest {

    private fun config() = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    /**
     * 把矮人换成一份**带着指定笔记、而且已经脱轨**的。**其余实体原样留着**——开局世界那几张图要被
     * 画得出来。
     *
     * **脱轨是必须的**（§3.2.1）：在走循环的角色看不见自己的笔记（保留但不注入），所以不脱轨的话
     * 这一整个测试类问的都会是同一句"那些不在这儿"。
     */
    private fun worldWith(notes: List<Note>, clock: WorldTime = World.StartingClock): World {
        val base = StartingWorld.tavern().first { it.id == "dwarf" }
        val dwarf = base.copy(
            notes = notes,
            active = Active(since = clock, target = base.cycle.beatAt(clock)),
        )
        val snap = StartingWorld.snapshot()
        return World.from(
            snap.copy(
                entities = snap.entities.map { if (it.id == "dwarf") dwarf else it },
                clock = clock,
            ),
        )
    }

    private fun originOf(world: World): ChunkOrigin =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun note(id: String, text: String, tag: String = "", supersedes: String? = null) =
        Note(id = id, text = text, tag = tag, at = WorldTime.of(1, 17), supersedes = supersedes)

    private fun npcOf(script: Scripted, settings: ContextSettings = ContextSettings()) = Npc(
        LlmClient(config(), script.client),
        Judge(LlmClient(config(), script.client)),
        settings = settings,
    )

    /** 提示词里【你记下的事】那一节，掐到下一节开头为止。**只在这一节里断言**，免得别处的字被误当成它。 */
    private fun notesSectionOf(body: String): String =
        body.substringAfter("【你记下的事】").substringBefore("【这一片里刚刚发生的事】")

    private suspend fun bodyOf(notes: List<Note>, settings: ContextSettings = ContextSettings()): String {
        val script = Scripted(listOf(stop()))
        val world = worldWith(notes)
        npcOf(script, settings).wake(world, Waking("dwarf", Heard.Spoken("player", "你", "你好啊。"), originOf(world)))
        return script.bodies.single()
    }

    @Test
    fun theSectionIsAlwaysThereEvenWithNothingWritten() = runBlocking {
        val section = notesSectionOf(bodyOf(emptyList()))

        assertTrue("还没有" in section, "空的时候也要有这一节——不然它不知道有这只手")
        assertTrue(NpcTools.NOTE in section, "空的那一行要告诉它那只手叫什么")
    }

    @Test
    fun notesComeBackOldestFirstWithTheirTagsAndTheirIds() = runBlocking {
        val section = notesSectionOf(
            bodyOf(
                listOf(
                    note("n1", "玩家欠我一杯酒。", tag = "约"),
                    note("n2", "东街木匠说下周有货。", tag = "事"),
                ),
            ),
        )

        val first = section.indexOf("玩家欠我一杯酒。")
        val second = section.indexOf("东街木匠说下周有货。")
        assertTrue(first in 0..<second, "老的先出现、新的在后（时间顺序）")
        assertTrue("n1" in section && "n2" in section, "编号要在——点名替代一条旧笔记全靠它")
        assertTrue("（约）" in section && "（事）" in section, "标签要跟着一起进去")
    }

    @Test
    fun asupersededNoteIsNotInjected() = runBlocking {
        val section = notesSectionOf(
            bodyOf(
                listOf(
                    note("n1", "玩家欠我一杯酒。"),
                    note("n2", "那杯酒他已经还了。", supersedes = "n1"),
                ),
            ),
        )

        // **两条同时出现 = 模型两条都信**（§6.4）。这一条断言是整个 supersede 机制存在的理由。
        assertTrue("那杯酒他已经还了。" in section)
        assertFalse("玩家欠我一杯酒。" in section, "被替代的不注入")
    }

    @Test
    fun onlyUnsupersededNotesCountAgainstTheBudget() = runBlocking {
        // 三条，第三条替代第一条：还算数的只有两条，所以它们该**都**进来。
        val section = notesSectionOf(
            bodyOf(
                listOf(
                    note("n1", "旧的那条已经不成立了。"),
                    note("n2", "第二条。"),
                    note("n3", "第三条。", supersedes = "n1"),
                ),
            ),
        )

        assertTrue("第二条。" in section && "第三条。" in section, "限额算的是活笔记")
        assertFalse("旧的那条" in section)
        assertFalse("还有" in section, "两条没超预算——不该出现截断标记")
    }

    @Test
    fun truncationKeepsTheNewestAndSaysSo() = runBlocking {
        val notes = listOf(
            note("n1", "甲".repeat(80)),
            note("n2", "乙".repeat(80)),
            note("n3", "丙".repeat(80)),
        )
        // 预算刚好只装得下一条：每行约 90 字。**从最老的那头截**——最近记的还热着。
        val section = notesSectionOf(bodyOf(notes, ContextSettings(noteBudgetChars = 100)))

        assertTrue("丙" in section, "最新的那条要在")
        assertFalse("甲" in section, "最老的那条装不下")
        assertTrue("还有 2 条" in section, "截掉了几条要说出来（§6.5：可见的截断标记）")
    }

    @Test
    fun notesSurviveTheCompactionSection() = runBlocking {
        // 阈值调到极小：这一轮提示词里会出现「该收一收了」。
        val body = bodyOf(
            listOf(note("n1", "玩家欠我一杯酒。")),
            ContextSettings(compressAfterTokens = 1),
        )

        assertTrue("该收一收了" in body, "阈值 1 之下压缩那段确实出现了")
        val section = notesSectionOf(body)
        assertTrue("玩家欠我一杯酒。" in section, "笔记不参与压缩——压缩那段出现与否都不影响它")
    }
}
