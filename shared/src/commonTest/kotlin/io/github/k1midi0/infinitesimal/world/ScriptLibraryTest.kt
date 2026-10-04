package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * **参考剧本库**（§14.55）：第一层的那一份素材。
 *
 * 这里钉的是四件事：**顺序**（清单进提示词，读起来该稳定）、**同一个 id 只有一个**、**存档读回来是
 * 合并而不是清空**（旧档不能把出厂那份一起清掉——那件事不会报错，只会让导演从此看不见素材），以及
 * **"填过没有"只有一个判据**（世界里那些剧本的 `source`）。
 */
class ScriptLibraryTest {

    private fun reference(id: String, summary: String = "概括", from: String = "") = ReferenceScript(
        id = id,
        title = id,
        summary = summary,
        body = "骨架：$id",
        from = from,
    )

    private fun worldWith(vararg sources: String?) = World.from(
        WorldSnapshot(
            entities = emptyList(),
            scripts = sources.mapIndexed { index, source ->
                Script(
                    id = "line$index",
                    title = "线$index",
                    summary = "这一条线",
                    body = "正文",
                    source = source,
                )
            },
        ),
    )

    @Test
    fun theLibraryKeepsTheOrderItWasLoadedIn() {
        val library = ScriptLibrary(listOf(reference("a"), reference("b"), reference("c")))

        assertEquals(listOf("a", "b", "c"), library.all().map { it.id })
        assertEquals(3, library.size)
        assertEquals(setOf("a", "b", "c"), library.ids())
    }

    /** 同一个 id 的第二条**替换**第一条：id 就是它的身份（与预设库同一条）。 */
    @Test
    fun learningTheSameIdTwiceKeepsOneAndTheSecondWins() {
        val library = ScriptLibrary(listOf(reference("a", summary = "旧")))

        library.learn(reference("a", summary = "新"))

        assertEquals(1, library.size)
        assertEquals("新", library.find("a")?.summary)
    }

    /**
     * **读档是合并不是清空。**
     *
     * 出厂那份在存档之外（它在资源文件里，读档之前已经装进这个库了）。若 `restore` 清空重填，一份
     * **没有参考剧本的旧档**会把出厂那份一起清掉——而症状是"导演从此看不见任何素材"，一句话都不报。
     */
    @Test
    fun restoreMergesInsteadOfClearingSoAnOldSaveCannotWipeTheShippedLibrary() {
        val library = ScriptLibrary(listOf(reference("出厂一"), reference("出厂二")))

        library.restore(listOf(reference("存档带的")))

        assertEquals(listOf("出厂一", "出厂二", "存档带的"), library.all().map { it.id })
    }

    /** 同一个 id 上，**存档那份赢**：那是当时的事实，而出厂那份只是"现在我们随游戏带的是这样"。 */
    @Test
    fun restoreLetsTheSaveWinOnTheSameId() {
        val library = ScriptLibrary(listOf(reference("a", summary = "出厂的")))

        library.restore(listOf(reference("a", summary = "存档里的")))

        assertEquals("存档里的", library.find("a")?.summary)
        assertEquals(1, library.size)
    }

    @Test
    fun findTrimsAndAnswersNullForSomethingThatIsNotThere() {
        val library = ScriptLibrary(listOf(reference("a")))

        assertEquals("a", library.find("  a  ")?.id)
        assertNull(library.find("没有这一条"))
    }

    /**
     * **"填过没有"只有一个判据**：世界里那些剧本的 `source`。
     *
     * 不另存一张"已用"的表——那张表一旦与池子漂开，就会出现"库里说没填过、池子里明明有一条"的两个真相。
     */
    @Test
    fun pendingInIsEverythingTheWorldHasNotFilledYet() {
        val library = ScriptLibrary(listOf(reference("a"), reference("b"), reference("c")))
        val world = worldWith("a")

        assertEquals(listOf("b", "c"), library.pendingIn(world).map { it.id })
    }

    /** 导演自己想出来的线（`source` 空着）**不算填过谁**——库里的每一条都还在等着。 */
    @Test
    fun aScriptWithoutASourceDoesNotMarkAnythingAsUsed() {
        val library = ScriptLibrary(listOf(reference("a"), reference("b")))
        val world = worldWith(null, null)

        assertEquals(listOf("a", "b"), library.pendingIn(world).map { it.id })
    }

    @Test
    fun theListingIsEitherAnEmptySentenceOrTheCodesTitlesAndSummaries() {
        assertEquals("（参考剧本库里一条都没有）", emptyList<ReferenceScript>().listing())

        val listed = listOf(reference("一笔到期的账", summary = "有人欠着东西")).listing()
        assertTrue("一笔到期的账" in listed, listed)
        assertTrue("有人欠着东西" in listed, listed)
        // **骨架不在清单里**（§8.4 那条成本纪律：清单只放常驻提示词的那一段）。
        assertTrue("骨架：" !in listed, listed)
    }

    /** 库的文件形状能往返（出厂那份资源文件与存档里那份走的都是这条路）。 */
    @Test
    fun thePackSurvivesJson() {
        val pack = ReferencePack(
            from = "某本小说.txt",
            chunksDone = 7,
            scripts = listOf(reference("a", from = "某本小说.txt")),
        )

        val text = Json.encodeToString(ReferencePack.serializer(), pack)
        val back = Json.decodeFromString(ReferencePack.serializer(), text)

        assertEquals(pack, back)
    }
}
