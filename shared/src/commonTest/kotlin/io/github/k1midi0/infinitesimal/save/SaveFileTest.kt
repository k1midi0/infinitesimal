package io.github.k1midi0.infinitesimal.save

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.npc.Conversations
import io.github.k1midi0.infinitesimal.npc.Npc
import io.github.k1midi0.infinitesimal.schedule.Scheduling
import io.github.k1midi0.infinitesimal.session.GameSession
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.CycleCatchUp
import io.github.k1midi0.infinitesimal.world.Note
import io.github.k1midi0.infinitesimal.world.Preset
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import io.github.k1midi0.infinitesimal.world.refreshCyclesToClock
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * **存档**（§14.49）。
 *
 * 这一支钉四件事：
 *
 *  1. 一份存档写出去、读回来之后，**世界里那些不能丢的东西还在**（笔记、时间轴、时钟、发言名单、
 *     事件记录的编号）。
 *  2. **对话那一份整份带回**（原文 + 摘要 + 位置号/块号/锚）。摘要是模型给的，重算不出来。
 *  3. **读坏档留下的是一句人话**，不是一个 `SerializationException`：版本比程序新、比程序旧、文件是
 *     空的、压根不是 JSON——四种各一条。
 *  4. `LastSave` 那一格：记不住、或者记的那一份已经不在了，就是"没有上次那一份"。
 *
 * 落盘用的是**真的文件**（`build/savetest/` 下面），不是内存里对一个字符串做往返——因为这一刀要验的
 * 那一半正是"读盘写盘"本身（`readTextOrNull` / `writeTextFile` 那一对平台函数）。
 */
class SaveFileTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    /** 一次测试用的那个文件。名字里带它自己的名字，于是同一支里几个路径不会撞。 */
    private fun path(name: String): String {
        val file = File("build/savetest/$name")
        file.parentFile?.mkdirs()
        file.delete()
        return file.path
    }

    /** 判定器与对话都不联网。读档那一支要用它。 */
    private val schedulerWithNothing = Scheduling { world -> CycleCatchUp(world.refreshCyclesToClock()) }

    // ── 世界那一份 ────────────────────────────────────────────────────────────────

    @Test
    fun aWorldComesBackWithEverythingThatCannotBeLost() = runBlocking {
        val world = World.from(StartingWorld.snapshot())
        // 这五样是"丢了也不会报错、但读回来就不是那一局了"的东西：时钟、时间轴、笔记、发言名单，
        // 以及事件记录的编号（它决定"上回合从哪算"）。
        world.clock = WorldTime.of(day = 3, hour = 9)
        world.timeline.put(
            io.github.k1midi0.infinitesimal.world.TimelineEntry(
                id = "later",
                dueAt = WorldTime.of(day = 5, hour = 4),
                what = "北门有一支队伍经过",
            ),
        )
        val dwarf = world.byId("dwarf")!!
        world.put(
            dwarf.copy(
                notes = listOf(Note(id = "n1", text = "阿黛尔欠他三枚银币。", tag = "账", at = WorldTime.of(2, 8))),
            ),
        )
        world.speakQueue = listOf("dwarf")

        val path = path("world.json")
        SaveFile.save(path, SaveGame(world = world.snapshot()))

        val loaded = assertNotNull(SaveFile.load(path) as? SaveLoad.Loaded).game
        val back = World.from(loaded.world)

        assertEquals(11, back.all().size, "开局世界是十一个实体")
        assertEquals(WorldTime.of(day = 3, hour = 9), back.clock, "时钟")
        assertEquals(1, back.timeline.all().size, "时间轴")
        assertEquals("北门有一支队伍经过", back.timeline.all().first().what)
        assertEquals(listOf("dwarf"), back.speakQueue, "发言名单")
        assertEquals(
            "阿黛尔欠他三枚银币。",
            back.byId("dwarf")!!.notes.single().text,
            "笔记",
        )
        assertEquals(2, back.byId("dwarf")!!.notes.single().at.day, "笔记上那个时刻")
    }

    @Test
    fun theHeaderIsReadableWithoutParsingTheWholeFile() = runBlocking {
        val path = path("head.json")
        SaveFile.save(path, SaveGame(game = "0.1.0", savedAt = 1_700_000_000_000L, world = World.empty().snapshot()))

        val header = assertNotNull(SaveFile.peek(path))
        assertEquals(SaveGame.FORMAT, header.format)
        assertEquals("0.1.0", header.game)
        assertEquals(1_700_000_000_000L, header.savedAt)
    }

    // ── 对话那一份 ────────────────────────────────────────────────────────────────

    @Test
    fun conversationsComeBackWithTheirPositionNumbersAndTheirAnchor() = runBlocking {
        val conversations = Conversations()
        val dwarf = conversations.of("dwarf")
        dwarf.commit(listOf(ChatMessage.user("你好啊。"), ChatMessage.assistant("……")))
        dwarf.anchor(promptTokens = 12_345, covered = 0)
        assertEquals(2, dwarf.size)

        val path = path("conversations.json")
        SaveFile.save(path, SaveGame(conversations = conversations.snapshot()))

        val loaded = (SaveFile.load(path) as SaveLoad.Loaded).game
        val back = Conversations()
        back.restore(loaded.conversations)

        assertEquals(listOf("dwarf"), back.ownerIds(), "在册的是谁也要带回来")
        val restored = back.of("dwarf")
        assertEquals(2, restored.rawCount, "两条原文")
        assertEquals(
            listOf("你好啊。", "……"),
            restored.history().map { it.content },
            "原话要一字不差",
        )

        // **位置号接着往下走**，不是从 h1 重来：模型上一轮看到的是 h2，而它下一轮还该看到 h3。
        restored.commit(listOf(ChatMessage.user("那三枚银币呢？")))
        assertEquals(3, restored.size, "新那一条是第三段")
        assertEquals("h3", "h${restored.summaries().size + restored.rawCount}")

        // **锚也要在**：它是"上一次请求到底多大"那个真数。丢了它，读回来的第一轮会把上下文估小，
        // 而那个错的估计会一路带着走（§6.1：估算只管增量，绝对量靠锚）。
        val system = ChatMessage.system("你是矮人。")
        val anchored = restored.estimate(system, emptyList())
        assertTrue(anchored >= 12_345, "带上锚之后，估计不该掉回几千：$anchored")
    }

    @Test
    fun aSummaryComesBackAsASummaryAndNotAsRawText() = runBlocking {
        val conversations = Conversations()
        val conversation = conversations.of("dwarf")
        val many = (1..10).map { ChatMessage.user("第 $it 句。") }
        conversation.commit(many)
        // 压最老的那一段（下限是 6 条）。**它必须真的压成了一条摘要**，否则这一支验不到东西。
        assertNull(conversation.compact(from = 1, to = 8, topic = "寒暄", text = "头八句是在寒暄。"), "前提：压成了")
        assertEquals(1, conversation.summaryCount, "前提：真的压出一条摘要")

        val path = path("summary.json")
        SaveFile.save(path, SaveGame(conversations = conversations.snapshot()))
        val loaded = (SaveFile.load(path) as SaveLoad.Loaded).game

        val back = Conversations()
        back.restore(loaded.conversations)
        val restored = back.of("dwarf")

        assertEquals(1, restored.summaryCount, "摘要仍然是一条摘要（不是被摊平成原文）")
        val summary = restored.summaries().single()
        assertEquals("寒暄", summary.topic)
        assertEquals(1, summary.tier, "第几级也要带回来——二次压缩靠它")
        assertEquals(8, summary.covered, "盖住几条原文：二级压缩的下限按它算")
        assertTrue("头八句是在寒暄。" in summary.text)
        // **那行标源要跟着它**：摘要是模型写的，不能让它读起来像玩家原话。
        assertTrue(
            restored.history().any { it.content?.contains("Model-written summary") == true },
            "摘要渲染出来的那条消息要带标源行：${restored.history().map { it.content }}",
        )
    }

    @Test
    fun theBlockNumberKeepsCountingAfterAReload() = runBlocking {
        val conversations = Conversations()
        val conversation = conversations.of("dwarf")
        conversation.commit((1..10).map { ChatMessage.user("第 $it 句。") })
        conversation.commit((11..14).map { ChatMessage.user("第 $it 句。") })
        assertNull(conversation.compact(from = 1, to = 8, topic = "", text = "第一块。"), "前提：压成了")
        assertEquals("b1", conversation.summaries().single().blockId)

        val path = path("blocks.json")
        SaveFile.save(path, SaveGame(conversations = conversation.snapshot().let { listOf(it) }))
        val loaded = (SaveFile.load(path) as SaveLoad.Loaded).game

        val restored = Conversations()
        restored.restore(loaded.conversations)
        val back = restored.of("dwarf")
        // 再压一次：这次压的是那条摘要自己（tier 2）。**它必须是 b2**——重头数的话又会出来一个 b1，
        // 而两个块号撞在一起之后，"刚才压的是哪一块"就说不清了（§14.41：块号是压缩的入口）。
        assertNull(back.compact(from = 1, to = 1, topic = "", text = "第二块。"), "第二压也该成")
        assertTrue(
            back.summaries().any { it.blockId == "b2" },
            "块号要接着数：${back.summaries().map { it.blockId }}",
        )
        assertEquals(2, back.summaries().single().tier, "压摘要出来的是第 2 级")
    }

    // ── 坏档留下的是一句人话 ──────────────────────────────────────────────────────

    @Test
    fun aSaveFromANewerFormatIsRefusedWithAReason() = runBlocking {
        val path = path("newer.json")
        File(path).parentFile?.mkdirs()
        File(path).writeText("""{"format":${SaveGame.FORMAT + 1},"game":"9.9.9","savedAt":1,"world":{}}""")

        val failure = assertNotNull(SaveFile.load(path) as? SaveLoad.Failed)
        assertTrue("更新" in failure.why, "理由要说清是哪一边新：${failure.why}")
        assertTrue("${SaveGame.FORMAT + 1}" in failure.why)
    }

    @Test
    fun aSaveFromAnOlderFormatIsRefusedToo() = runBlocking {
        val path = path("older.json")
        File(path).parentFile?.mkdirs()
        File(path).writeText("""{"format":0,"game":"0.0.1","savedAt":1,"world":{}}""")

        val failure = assertNotNull(SaveFile.load(path) as? SaveLoad.Failed)
        assertTrue("旧" in failure.why, "理由要说清是哪一边旧：${failure.why}")
    }

    @Test
    fun anEmptyOrMissingFileSaysSo() = runBlocking {
        val missing = path("missing.json")
        assertTrue("读不到" in assertNotNull(SaveFile.load(missing) as? SaveLoad.Failed).why)
        assertNull(SaveFile.peek(missing), "扫一眼失败是静默的——主界面那个按钮不该因此报错")

        val empty = path("empty.json")
        File(empty).parentFile?.mkdirs()
        File(empty).writeText("   ")
        assertTrue("空的" in assertNotNull(SaveFile.load(empty) as? SaveLoad.Failed).why)
    }

    @Test
    fun somethingThatIsNotASaveAtAllSaysSo() = runBlocking {
        val path = path("garbage.json")
        File(path).parentFile?.mkdirs()
        File(path).writeText("这不是 JSON，是一句话。")

        val failure = assertNotNull(SaveFile.load(path) as? SaveLoad.Failed)
        assertTrue("不像是这个游戏的存档" in failure.why, "理由要指到「开头读不出格式版本」：${failure.why}")
    }

    @Test
    fun aTruncatedSaveSaysWhichStepBroke() = runBlocking {
        val good = path("good.json")
        SaveFile.save(good, SaveGame(world = World.from(StartingWorld.snapshot()).snapshot()))
        val text = File(good).readText()

        val cut = path("cut.json")
        File(cut).parentFile?.mkdirs()
        // 头三格留着、后面砍掉：版本这一关过得了，于是错落在"解"这一步——而那一步也要有一句人话。
        File(cut).writeText(text.take(text.length / 2))

        val failure = assertNotNull(SaveFile.load(cut) as? SaveLoad.Failed)
        assertTrue("读坏了" in failure.why, "理由要说清是解到一半坏的：${failure.why}")
    }

    // ── 整局：存 → 读回一个会话 ────────────────────────────────────────────────────

    @Test
    fun aWholeSessionComesBackWithItsMemoryAndItsPresets() = runBlocking {
        val script = Scripted(listOf(stop()))
        val npc = Npc(LlmClient(config, script.client), Judge(LlmClient(config, script.client)))
        val session = GameSession(
            config,
            presets = PresetLibrary(),
            judge = Judge(LlmClient(config, script.client)),
            npc = npc,
            scheduler = schedulerWithNothing,
        )
        val path = path("session.json")

        // 说一句话（进它的历史）、造一件东西（进生成包）、再存。
        npc.conversations.of("dwarf").commit(listOf(ChatMessage.user("你好啊。"), ChatMessage.assistant("……")))
        session.presets.restore(
            listOf(Preset(name = "老槐树", brief = "村口那棵", shape = io.github.k1midi0.infinitesimal.world.VoxelShape())),
        )
        session.saveTo(path)

        val loaded = assertNotNull(SaveFile.load(path) as? SaveLoad.Loaded).game
        val library = PresetLibrary()
        // **这里刻意用"按配置造"的那条构造器**：它会把 `presets` 真的交给判定器与建模器——而这条
        // 恰好是刚才那个静默错位（`judge = Judge(config)` 不带库）会踩的地方。
        val back = GameSession.restore(
            config,
            loaded,
            presets = library,
            npc = Npc(config, library),
            judge = Judge(config, presets = library),
            scheduler = schedulerWithNothing,
        )

        assertEquals(2, back.conversations.of("dwarf").rawCount, "它有记忆")
        assertEquals(1, back.presets.generatedPresets().size, "生成包带回来了")
        assertEquals("老槐树", back.presets.generatedPresets().single().name)
        // **读回来的这一局还没有绑在哪个文件上**：绑哪一个是调用点的决定（主界面那一边读完之后调
        // `autoSaveTo(path)`），而 `restore` 不该替它猜——猜错就是把这一局写向玩家的另一份存档。
        assertNull(back.savingTo, "restore 不替调用点选文件")
        back.autoSaveTo(path)
        assertEquals(path, back.savingTo, "绑上之后每一回合都存向它")
    }

    @Test
    fun aFreshSessionSavesNowhereAndThatIsNotAnError() = runBlocking {
        val script = Scripted(listOf(stop()))
        val session = GameSession(
            config,
            npc = Npc(LlmClient(config, script.client), Judge(LlmClient(config, script.client))),
            scheduler = schedulerWithNothing,
        )
        assertNull(session.savingTo, "一局刚开始还没有选定文件——那是正常状态，不是错误")
    }

    // ── 上次那一份 ────────────────────────────────────────────────────────────────

    @Test
    fun theLastSaveIsRememberedAndForgottenWhenTheFileGoesAway() = runBlocking {
        withOwnLastSave {
            val path = path("remembered.json")
            SaveFile.save(path, SaveGame(world = World.empty().snapshot()))
            LastSave.remember(path)
            assertEquals(path, LastSave.path(), "记下了就是它")

            // 文件被删掉之后，那一格要变回"没有上次那一份"——否则主界面那个继续按钮会亮着，
            // 而点下去读不到。
            File(path).delete()
            assertNull(LastSave.path(), "那一份不在了，就不该还说记得它")
        }
    }

    @Test
    fun theLastSaveIsForgottenWhenTheSmallFileIsGarbage() = runBlocking {
        withOwnLastSave {
            val file = File(LastSave.file)
            file.parentFile?.mkdirs()
            file.writeText("这不 JSON。")
            assertNull(LastSave.path(), "读不出来的小文件 = 没有上次那一份（不报错）")
        }
    }

    /**
     * 把「上次那一份」那一格挪到一个测试自己的目录里，用完收回来。
     *
     * **不这么做的话，这几支测试会去改玩家真实主目录下那个文件**——而它记的正是玩家"上次玩的是哪一
     * 局"。一支测试把别人正在玩的那一局指到一个刚被删掉的文件上，是那种跑完很久才有人发现的事。
     */
    private suspend fun withOwnLastSave(block: suspend () -> Unit) {
        val directory = File("build/savetest/lastsave").also { it.mkdirs() }
        LastSave.overrideDirectory = directory.path
        try {
            block()
        } finally {
            LastSave.overrideDirectory = null
            directory.deleteRecursively()
        }
    }

    // ── 每回合自动存 ──────────────────────────────────────────────────────────────

    @Test
    fun aTurnWritesTheSaveItIsBoundToWithoutBeingAskedTo() = runBlocking {
        val script = Scripted(
            listOf(
                turn("t1", "address", """{"id":"dwarf"}"""),
                turn("c1", "say", """{"text":"……"}"""),
                stop(),
            ),
        )
        val session = GameSession(
            config,
            judge = Judge(LlmClient(config, script.client)),
            npc = Npc(LlmClient(config, script.client), Judge(LlmClient(config, script.client))),
            scheduler = schedulerWithNothing,
        )
        val path = path("autosave.json")
        // **自己起一个 scope**：`runTurn(scope = null)` 那条路是给探针用的，它不走 `run`，于是也没有
        // "回合末尾"那一下（自动存档挂在那里）。真实入口给的是一个与界面同命的 scope。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        session.autoSaveTo(path)

        session.runTurn("你好啊。", "", scope)
        // 自动存档起在会话那一条协程上——等它把文件写出来。**等的是文件，不是 sleep 一个猜出来的
        // 时长**：写盘有多快取决于机器，而"文件在不在"是那件事本身。
        val wrote = waitForFile(path)
        scope.cancel()
        assertTrue(
            wrote,
            "回合结束后那个文件该出现了（它现在说什么：${session.notice ?: "没有话说"}；" +
                "世界里有 ${session.world.all().size} 样东西）",
        )

        val loaded = assertNotNull(SaveFile.load(path) as? SaveLoad.Loaded).game
        assertTrue(loaded.world.entities.isNotEmpty(), "存下来的世界不是空的")
    }

    private suspend fun waitForFile(path: String): Boolean {
        repeat(200) {
            if (File(path).isFile && File(path).length() > 0) return true
            kotlinx.coroutines.delay(25)
        }
        return File(path).isFile && File(path).length() > 0
    }

    // ── 量一量：一份存档到底多大、写一次多久（§14.49 那笔没量的账） ────────────────

    /**
     * **这一支不断言，只印数**。
     *
     * 它要回答的是设计文档里那句"段表才是它真正的大小"到底是多少——开局那份世界算下来是
     * **12,544 列 / 37,632 段**地形，而它写出来多少字节、写一次多少毫秒，在写这一刀的时候没有人量过。
     * 一个不断言的测试可以被当成一次可以重跑的读数：数字变了，说明世界大了。
     */
    @Test
    fun howBigASaveIsAndHowLongItTakesToWrite() = runBlocking {
        val world = World.from(StartingWorld.snapshot())
        val conversations = Conversations()
        // 一份有点内容的对话：二十条原文 + 一条摘要。**空对话会让这一格读成 0**，而那不是真实情形。
        val conversation = conversations.of("dwarf")
        conversation.commit((1..20).map { ChatMessage.user("第 $it 句话，长短与真说话差不多。") })
        assertNull(conversation.compact(from = 1, to = 8, topic = "寒暄", text = "头八句是在寒暄。"))
        val presets = PresetLibrary()
        presets.restore(
            listOf(
                Preset(
                    name = "老槐树",
                    brief = "村口那棵",
                    shape = io.github.k1midi0.infinitesimal.world.VoxelShape(),
                ),
            ),
        )

        val save = SaveGame(
            game = "0.1.0",
            savedAt = System.currentTimeMillis(),
            world = world.snapshot(),
            conversations = conversations.snapshot(),
            presets = presets.generatedPresets(),
        )

        val whole = SaveFile.json.encodeToString(SaveGame.serializer(), save)
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val path = path("measure.json")
        SaveFile.save(path, save)
        val wrote = mark.elapsedNow().inWholeMilliseconds
        val onDisk = File(path).length()

        fun size(what: String, text: String) = println("   $what：${text.toByteArray().size} 字节")
        println("【一份存档到底多大（开局世界 + 20 条对话 + 1 条摘要 + 1 条生成包）】")
        println("   写出来的文件：$onDisk 字节（${onDisk / 1024} KB）｜字符串 ${whole.length} 字符")
        println("   写盘一次：$wrote 毫秒")
        println("   读回来一次：${measureRead(path)} 毫秒")
        size(
            "地形那一段（它才是主体）",
            SaveFile.json.encodeToString(
                io.github.k1midi0.infinitesimal.world.TerrainSnapshot.serializer(),
                world.snapshot().terrain,
            ),
        )
        size(
            "对话那一段",
            SaveFile.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(
                    io.github.k1midi0.infinitesimal.npc.ConversationSnapshot.serializer(),
                ),
                save.conversations,
            ),
        )
        println("   列数 ${world.snapshot().terrain.columns.size}｜段数 ${world.terrain.runCount}｜方格数 ${world.terrain.cellCount}")
    }

    private fun measureRead(path: String): Long {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        SaveFile.load(path)
        return mark.elapsedNow().inWholeMilliseconds
    }
}
