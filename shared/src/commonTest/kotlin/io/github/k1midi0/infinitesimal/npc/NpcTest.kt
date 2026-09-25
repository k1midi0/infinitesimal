package io.github.k1midi0.infinitesimal.npc

import io.github.k1midi0.infinitesimal.config.ContextSettings
import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.Active
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.EventKind
import io.github.k1midi0.infinitesimal.world.Note
import io.github.k1midi0.infinitesimal.world.Spot
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import io.github.k1midi0.infinitesimal.world.live

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * AI 角色那一次唤醒（§5、§14.36）。
 *
 * 两件最该钉住的事：
 *
 * 1. **「什么都不做」是合法的。** 这是这一层与非导演几处最容易做错的地方——把"空手"统一当成失败，
 *    会让每个矮人都被逼着答话，而"不想理你就是不想理"是一个真实的反应。
 * 2. **「做」真的走判定、真的落库。** 不是"它说自己做了什么"，而是世界真的变了。这一条如果只是
 *    读代码看不出来——它跨了三个对象（角色 → 判定 → 提交层），所以这里用假端点把整条链跑一遍。
 */
class NpcTest {

    private fun config() = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    /**
     * 开局世界。**用 `StartingWorld.snapshot()`，不要手写 `WorldSnapshot(tavern())`**——后者看着对，
     * 而它漏掉规则书、地形、区划三样（§14.36 里那个坑，探针第一版就这么栽过）。
     */
    private fun world(seed: List<Entity> = StartingWorld.tavern()): World =
        World.from(StartingWorld.snapshot().copy(entities = seed))

    private fun originOf(world: World): ChunkOrigin =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    /**
     * 同上，但那个矮人**已经脱轨**（§3.2.1）。
     *
     * 笔记那几只手的测试全要它：**在循环里的角色写不了笔记**（那一道闸与"在循环里看不见自己的笔记"
     * 是同一件事的两面）。目标那一格取它此刻本来就在的那一格——18:00 开局，`arrive` 因此随时能成。
     */
    private fun offCycle(world: World): World {
        val dwarf = world.byId("dwarf") ?: return world
        world.put(
            dwarf.copy(
                active = Active(
                    since = world.clock,
                    target = dwarf.cycle.beatAt(world.clock),
                ),
            ),
        )
        return world
    }

    private fun greeted(text: String) = Heard.Spoken("player", "你", text)

    /** 造一个角色，它的两条客户端都打在同一个假端点上，于是帧的顺序就是真正的调用顺序。 */
    private fun npcOf(script: Scripted): Npc = Npc(
        LlmClient(config(), script.client),
        Judge(LlmClient(config(), script.client)),
    )

    @Test
    fun itSaysSomethingAndTheSentenceIsRecorded() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "say", """{"text":"……抬眼看了看他。"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        assertEquals(listOf(Spoken("dwarf", "……抬眼看了看他。")), reaction.said)
        assertTrue(reaction.acted.isEmpty())
        assertTrue(reaction.problems.isEmpty())
        assertFalse(reaction.silent)
    }

    @Test
    fun sayingNothingIsALegalOutcome() = runBlocking {
        val script = Scripted(listOf(stop()))
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        assertTrue(reaction.silent, "不理人是一个真实的反应，不是一次失败")
        assertTrue(reaction.problems.isEmpty())
        assertEquals(1, reaction.rounds, "一个来回就该收工——空手就是它说完了")
    }

    @Test
    fun actingGoesThroughTheJudgeAndTheWorldReallyChanges() = runBlocking {
        val script = Scripted(
            listOf(
                // ① 角色提议做一个动作
                turn("c1", "act", """{"intention":"我端起自己那只杯子喝一口。"}"""),
                // ② 判定第一步：这件事落在谁身上
                turn("t1", "target", """{"id":"dwarf"}"""),
                // ③ 判定第二步：后果。**它一被调用，这一步就收工**（不是查询手）
                turn("e1", "set_attribute", """{"id":"dwarf","key":"神色","value":"有点上头"}"""),
                // ④ 角色那一轮接着跑，这一次它没有手要调了
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("来一口？"), originOf(world)))

        assertEquals(1, reaction.acted.size)
        assertTrue(reaction.acted.single() is Judgment.Applied, "实际拿到的是 ${reaction.acted.single()}")
        assertEquals(
            "有点上头",
            world.byId("dwarf")?.attributes?.get("神色"),
            "「做」必须真的落库，而不是它说自己做成了",
        )
    }

    @Test
    fun anEmptySentenceComesBackAsSomethingItCanFix() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "say", """{"text":"   "}"""),
                // 退回去的那句话要真的回到上下文里——它下一轮才有依据改
                turn("c2", "say", """{"text":"……嗯。"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        assertEquals(listOf(Spoken("dwarf", "……嗯。")), reaction.said)
        assertEquals(1, reaction.problems.size)
        assertTrue("text" in reaction.problems.single())
    }

    @Test
    fun anUnknownToolNameIsToldWhatItCanUse() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "destroy", """{"id":"oak_table"}"""),
                stop(),
            ),
        )
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        assertTrue(reaction.silent)
        assertTrue("destroy" in reaction.problems.single())
        // **它手里没有那只手。** 角色要是能自己删东西，那它说的就成了事实，世界就没人复核了（§1）。
        assertTrue(world.byId("oak_table") != null)
    }

    @Test
    fun wakingSomebodyWhoIsNotInTheWorldIsReportedRatherThanThrown() = runBlocking {
        val script = Scripted(listOf(stop()))
        val world = world()

        val reaction = npcOf(script).wake(world, Waking("nobody", greeted("你好啊。"), originOf(world)))

        assertTrue(reaction.silent)
        assertEquals(1, reaction.problems.size)
        assertTrue("nobody" in reaction.problems.single())
    }

    @Test
    fun thePromptCarriesItsPersonaItsStateAndItsSight() = runBlocking {
        val script = Scripted(listOf(stop()))
        val world = world()

        npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        val body = script.bodies.single()
        // 人设卡的内容进去，**标题不进去**（§3.5）。用「口癖」而不是「身份」验：后者恰好也出现在
        // 角色那份 system 的正文里（「你的身份、脾气……」），拿它断言会假报。
        assertTrue("杜林" in body, "人设卡的内容该在提示词里")
        assertTrue("哼一声" in body, "每一张卡的内容都该进去，不是只进第一张")
        assertFalse("口癖" in body, "卡的标题永不进提示词")
        // 结构化属性进去——它是"世界状态"，与卡是两回事
        assertTrue("半醉" in body)
        // 视野：两张图，各一个尺度（§14.36）
        assertTrue("【你这双眼睛看得见的一片】" in body)
        assertTrue("远的那一张" in body && "近的那一张" in body, "粗的看地方，细的看手边")
        assertTrue("两张图之外有什么，你不知道" in body)
        // 细的那张要真的带出它手边的东西——不然两张都只是背景
        assertTrue("【这一块里的东西】" in body)
        // 听见的那句话原样进去，不做任何处理（§5.1）
        assertTrue("你好啊。" in body)
        assertTrue("玩家说的话" !in body, "话不做改写——原样交给它")
    }

    @Test
    fun thePromptCarriesItsRhythmAndItsPendingErrand() = runBlocking {
        val script = Scripted(listOf(stop()))
        val world = world()

        npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        val body = script.bodies.single()
        // **状态循环**（§3.2.5）：基线由代码查表得出，**零模型调用**；被唤醒时注入一行，否则一个正在
        // 打烊的酒保会表现得像个一直在等你来的人。开局是 18:00，正落在"招呼客人"那一格里。
        assertTrue("【没人打扰的时候，你此刻本来在做什么】" in body, "循环要进提示词")
        assertTrue("招呼客人" in body, "而且要是那一刻那一格，不是随便一格")
        // **待办**（§3.2.5）：到点了**不是自动发生，而是提醒它**——所以它得看见。
        assertTrue("【你打算做的事（已经到点了）】" in body)
        assertTrue("塞上" in body, "开局那条待办是 17:30 的，18:00 已经到点")
    }

    @Test
    fun thePromptCarriesThisTurnsBusAndNothingOlder() = runBlocking {
        val script = Scripted(listOf(stop()))
        val world = world()

        // **上一回合的事。** 它进得了记录层，但**不进总线**——总线只装上回合（§6.1、§14.39）。
        val older = world.log.append(
            at = world.clock,
            kind = EventKind.SAY,
            actorId = "player",
            actorLabel = "你",
            text = "昨天你还欠我一杯酒。",
            spot = Spot(1200, 300, 0),
            to = listOf("dwarf"),
        )
        // 这一回合的两件：一件**指着它说**，一件它只是**恰好在那一片**。
        world.log.append(
            at = world.clock,
            kind = EventKind.SAY,
            actorId = "player",
            actorLabel = "你",
            text = "矮人，来杯酒。",
            addressedTo = listOf("dwarf"),
            spot = Spot(1200, 300, 0),
            to = listOf("dwarf"),
        )
        world.log.append(
            at = world.clock,
            kind = EventKind.ACT,
            actorId = "bard",
            actorLabel = "吟游诗人",
            text = "他把琴挪了挪。",
            // **细节在这几条里**——而它们才是"全文"与"标题"真正的分野（见下面那句断言的理由）。
            effects = listOf("琴 挪到了 (1100, 400, 0) 厘米"),
            spot = Spot(1100, 400, 0),
            to = listOf("dwarf"),
        )

        npcOf(script).wake(
            world,
            Waking("dwarf", greeted("你好啊。"), originOf(world), sinceSeq = older.seq + 1),
        )

        val body = script.bodies.single()
        assertTrue("【这一片里刚刚发生的事】" in body, "总线要进提示词")
        // **被指向的给全文**：那是冲它说的。
        assertTrue("矮人，来杯酒。" in body, "被指向的那句要原文进去")
        // **没被指向的只给标题。** 标题是一行 gist，所以**短句的 gist 约等于原话**——这是"一行标题"固有
        // 的性质，不是漏洞。真正的分野在**后果那几条**：那是细节，只在 `look` 里给。
        assertTrue("他把琴挪了挪" in body, "标题要有一行，否则它不知道有什么可查的")
        assertTrue("挪到了" !in body, "没被指向的**不给后果那几条**——细节要自己 look")
        // **上回合之前的不进来**——那就是「自然遗忘」，而底层还留着（记录层不注入）。
        assertTrue("昨天你还欠我一杯酒。" !in body, "更早的不进总线")
        assertEquals(1, world.log.byId(older.seq)?.let { listOf(it) }?.size ?: 0, "但记录层里还在")
    }

    @Test
    fun itCanScheduleSomethingForItself() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "plan", """{"day":2,"hour":9,"minute":30,"what":"去东街找那个木匠"}"""),
                stop(),
            ),
        )
        val world = world()

        npcOf(script).wake(world, Waking("dwarf", greeted("你明天有事么？"), originOf(world)))

        // 开局那条 17:30 的待办还在，新的挂在它后面——**两条都在**，不是被顶掉。
        val todos = world.byId("dwarf")?.todos.orEmpty()
        assertEquals(2, todos.size)
        assertEquals("去东街找那个木匠", todos.last().what)
        assertEquals(WorldTime.of(2, 9, 30), todos.last().at)
    }

    @Test
    fun whatItDidLastTimeIsStillInTheHistoryThisTime() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "say", """{"text":"……嗯。"}"""),
                stop(),
                stop(),
            ),
        )
        val world = world()
        val npc = npcOf(script)

        npc.wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))
        val afterFirst = npc.conversations.of("dwarf").size
        npc.wake(world, Waking("dwarf", greeted("又来一句。"), originOf(world)))
        val afterSecond = npc.conversations.of("dwarf").size

        // **历史只增不减**（§6.1）：第二次唤醒是接在第一次后面的，不是新开一段。
        assertTrue(afterSecond > afterFirst, "历史要累积（$afterFirst → $afterSecond）")
        // 而客观证据是**请求体本身**：第二次唤醒发的那个 prompt 里应当带着上一轮的台词。
        // 帧的顺序是 [第一次唤醒 r1, 第一次唤醒 r2, 第二次唤醒 r1]。
        assertTrue("……嗯。" in script.bodies[2], "上一轮的台词要在这一次的请求里")
    }

    @Test
    fun lookFetchesTheDetailThatTheBusOnlyHintedAt() = runBlocking {
        val script = Scripted(listOf(turn("c1", "look", """{"id":"e1"}"""), stop()))
        val world = world()
        // 别人做的事，而且**不是冲它说的**——所以总线上只有一行标题。
        val event = world.log.append(
            at = world.clock,
            kind = EventKind.ACT,
            actorId = "bard",
            actorLabel = "吟游诗人",
            text = "他把琴挪了挪。",
            effects = listOf("琴 挪到了 (1100, 400, 0) 厘米"),
            spot = Spot(1100, 400, 0),
        )

        npcOf(script).wake(
            world,
            Waking("dwarf", greeted("你好啊。"), originOf(world), sinceSeq = event.seq),
        )

        assertEquals(2, script.bodies.size, "它调了 look，所以要第二个来回把结果递回去")
        assertTrue("琴 挪到了" in script.bodies[1], "look 的全文要真的回到模型手上")
    }

    @Test
    fun lookingAtSomethingOutsideTheBusSaysSoInsteadOfFailing() = runBlocking {
        val script = Scripted(listOf(turn("c1", "look", """{"id":"e1"}"""), stop()))
        val world = world()
        val older = world.log.append(
            at = world.clock,
            kind = EventKind.SAY,
            actorId = "player",
            actorLabel = "你",
            text = "上一回合的秘密",
            spot = Spot(1200, 300, 0),
        )
        world.log.append(
            at = world.clock,
            kind = EventKind.SAY,
            actorId = "player",
            actorLabel = "你",
            text = "这一回合的话",
            spot = Spot(1200, 300, 0),
        )

        val reaction = npcOf(script).wake(
            world,
            Waking("dwarf", greeted("你好啊。"), originOf(world), sinceSeq = older.seq + 1),
        )

        // **不是失败，是世界的一个正常结果**（§4.3）：那句话得说得像世界在说话。
        assertTrue("这一回合的总线上没有" in script.bodies[1], "查不到要说清楚，不能编")
        assertTrue("上一回合的秘密" !in script.bodies[1], "更早的绝不漏出去——那正是自然遗忘")
        assertTrue(reaction.problems.isEmpty(), "查不到不是协议问题")
    }

    // ── 笔记（§6.3、§6.4、§6.6） ────────────────────────────────────────────────────

    /**
     * 一次唤醒发出来的那些请求体。
     *
     * **不从某个固定下标取**：一次唤醒发几个请求是不定的（每调一次手就多一个来回，最后一次总是模型空手
     * 收工），照下标取会随脚本改动而错位，而且错了看着还挺像对的。
     *
     * 它把工具结果那几条消息也算进来——那是**故意的**：被退回去的话就写在工具结果里，而"它有没有收到
     * 那句话"正是要验的。代价是断言用的字符串必须足够特别，不能是提示词里到处都有的词。
     */
    private fun bodiesIn(script: Scripted, from: Int = 0) = script.bodies.drop(from)

    /** 同上，但只留**提示词**那几条（`user`）——要看"模型当下眼前有什么"时用这个。 */
    private fun promptsIn(script: Scripted, from: Int = 0) =
        bodiesIn(script, from).filter { "\"role\":\"user\"" in it }

    /**
     * **这一次请求新拼的那一段提示词。**
     *
     * 一个请求体里装着整串历史，而历史里存着**之前几次请求的提示词**（它们本身就是消息）。所以"这一次
     * 给它的提示词里有没有某一节"不能拿整个请求体去查——那会查到旧的、甚至查到一次还没有笔记时的自己。
     * 最后一次 `user` 消息之后的那一截才是这一次新拼的。
     */
    private fun lastPromptIn(body: String): String =
        body.substringAfterLast("\"role\":\"user\"")

    /** 【你记下的事】那一节，掐到下一节开头。**只在这一次的提示词里找**。 */
    private fun notesSectionIn(body: String): String =
        lastPromptIn(body).substringAfter("【你记下的事】").substringBefore("【这一片里刚刚发生的事】")

    @Test
    fun itCanWriteDownSomethingItMustNotForget() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "note", """{"text":"玩家欠我一杯酒，说下次给钱。","tag":"约"}"""),
                stop(),
            ),
        )
        val world = offCycle(world())

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("酒先记着。"), originOf(world)))

        assertTrue(reaction.problems.isEmpty(), "写笔记不是协议问题")
        val notes = world.byId("dwarf")?.notes.orEmpty()
        assertEquals(1, notes.size)
        assertEquals("玩家欠我一杯酒，说下次给钱。", notes.single().text)
        assertEquals("约", notes.single().tag)
        assertEquals("n1", notes.single().id)
        assertEquals(world.clock, notes.single().at, "笔记要落上写下来的时刻")
        assertEquals(null, notes.single().supersedes)
    }

    @Test
    fun anoteItWroteComesBackOnTheNextWaking() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "note", """{"text":"玩家欠我一杯酒。"}"""),
                stop(),
                // 第二次唤醒：它什么都不用做，这一帧只是让请求发出去
                stop(),
            ),
        )
        val world = offCycle(world())
        val npc = npcOf(script)

        npc.wake(world, Waking("dwarf", greeted("记着。"), originOf(world)))
        npc.wake(world, Waking("dwarf", greeted("刚才说的事呢？"), originOf(world)))

        // **常驻注入**（§6.3）：第二次唤醒发给模型的提示词里要有它——请求体是客观证据，不是我们自己的账。
        val second = bodiesIn(script).last()
        val section = notesSectionIn(second)
        assertTrue("【你记下的事】" in lastPromptIn(second), "笔记那一节每轮都要在")
        assertTrue("玩家欠我一杯酒。" in section, "上一轮记下的事这一轮要回到眼前")
        assertTrue("n1" in section, "编号要在——它是点名替代一条旧笔记时唯一的抓手")
    }

    @Test
    fun replacingANoteRetiresTheOldOneWithoutDeletingIt() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "note", """{"text":"玩家欠我一杯酒。"}"""),
                stop(),
                turn("c2", "note", """{"text":"那杯酒他已经还了。","supersedes":"n1"}"""),
                stop(),
                // **第三次唤醒只为看一眼**：前两次里各写一条，而"被替代的那条不再注入"要在**下一次**
                // 唤醒的提示词里才看得出来——写它的那一次，提示词早就拼好了。
                stop(),
            ),
        )
        val world = offCycle(world())
        val npc = npcOf(script)

        npc.wake(world, Waking("dwarf", greeted("记着。"), originOf(world)))
        npc.wake(world, Waking("dwarf", greeted("还你酒。"), originOf(world)))
        val beforeThirdWake = script.bodies.size
        npc.wake(world, Waking("dwarf", greeted("还有事么？"), originOf(world)))

        val notes = world.byId("dwarf")?.notes.orEmpty()
        // **两条都在**（§6.4：永不删除），而只有新的那条还算数。
        assertEquals(2, notes.size)
        assertEquals("n1", notes.last().supersedes)
        assertEquals(listOf("n2"), notes.live().map { it.id })

        // 而"还算数"这件事在提示词里看得见：新的在、旧的**不在**。两条同时出现的话，模型会两条都信。
        //
        // **只看这一次唤醒新拼的那一段**：请求体里装着历史，而历史里存着之前几次的提示词——拿整个
        // 请求体去查，会查到一次还没有笔记（或者还有旧笔记）时的自己。
        val fresh = bodiesIn(script, from = beforeThirdWake).map { lastPromptIn(it) }
        assertTrue(fresh.isNotEmpty(), "第三次唤醒应当发出请求")
        assertTrue(fresh.any { "那杯酒他已经还了。" in it }, "新的那条要在眼前")
        assertTrue(fresh.none { "玩家欠我一杯酒。" in it }, "被替代的不再注入——那正是 §6.4 要防的那类 bug")
    }

    @Test
    fun pointingAtANoteThatIsNotThereIsRefusedRatherThanSilentlyTreatedAsNew() = runBlocking {
        val script = Scripted(
            listOf(
                // 身上一条笔记都没有，却点名要替代 n7。
                turn("c1", "note", """{"text":"那件事算了。","supersedes":"n7"}"""),
                stop(),
            ),
        )
        val world = offCycle(world())

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("算了。"), originOf(world)))

        // **拒绝，而不是顺手当成"新添一条"。** 后者会让它以为自己撤掉了一句过期的话，而那句其实还在
        // 眼前——于是它同时相信两条（§6.4 里最难查的那类 bug）。
        assertTrue(world.byId("dwarf")?.notes.orEmpty().isEmpty(), "什么都没写进去")
        val afterRefusal = bodiesIn(script).last()
        assertTrue("n7" in afterRefusal, "退回去的话要把它写的编号还给它，它才知道错在哪")
        assertTrue(reaction.problems.isEmpty(), "参数没错，是那条笔记不存在——不是协议问题")
    }

    @Test
    fun onlyOneNewNotePerWaking() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "note", """{"text":"第一件要紧事。"}"""),
                turn("c2", "note", """{"text":"第二件要紧事。"}"""),
                stop(),
            ),
        )
        val world = offCycle(world())

        npcOf(script).wake(world, Waking("dwarf", greeted("有件事。"), originOf(world)))

        // §6.6 那道频率闸：**每回合至多一条新的**，否则一次唤醒就能把上限写满。
        val notes = world.byId("dwarf")?.notes.orEmpty()
        assertEquals(1, notes.size)
        assertEquals("第一件要紧事。", notes.single().text)
        assertEquals(3, script.bodies.size, "它调了第二次 note，所以要第三个来回把结果递回去")
        assertTrue("已经记下一条" in script.bodies[2], "第二次要被打回，而理由是可执行的")
    }

    @Test
    fun afullNotebookRefusesUntilItRetiresOne() = runBlocking {
        // **先把上限写满。** 用一份只有两条的笔记与一个上限 2 的设置，省得造二十条。
        val full = offCycle(world()).byId("dwarf")!!.copy(
            notes = listOf(
                Note("n1", "第一件旧事。", at = WorldTime.of(1, 17)),
                Note("n2", "第二件旧事。", at = WorldTime.of(1, 17)),
            ),
        )
        val world = World.from(
            StartingWorld.snapshot().let { snap ->
                snap.copy(entities = snap.entities.map { if (it.id == "dwarf") full else it })
            },
        )
        val script = Scripted(
            listOf(
                turn("c1", "note", """{"text":"第三件事。"}"""),
                stop(),
            ),
        )

        val npc = Npc(
            LlmClient(config(), script.client),
            Judge(LlmClient(config(), script.client)),
            settings = ContextSettings(noteLimit = 2),
        )
        val reaction = npc.wake(world, Waking("dwarf", greeted("还有件事。"), originOf(world)))

        assertEquals(2, world.byId("dwarf")?.notes?.size, "到顶了就不许再添")
        assertTrue(reaction.problems.isEmpty())
        // 打回的理由要**可执行**（§1.3）：说清哪几条在、该怎么办。到顶时静默丢弃是最坏的做法——
        // 它以为记下了，而那条从来没进过提示词。
        val back = bodiesIn(script).last()
        assertTrue("记满" in back, "要说清为什么不行")
        assertTrue("n1" in back && "第二件旧事。" in back, "还要把现在这几条摊给它看")
        assertTrue("supersedes" in back, "以及它该怎么做")
    }

    @Test
    fun retiringOneAndWritingOneFitsThroughTheSameWaking() = runBlocking {
        val full = offCycle(world()).byId("dwarf")!!.copy(
            notes = listOf(
                Note("n1", "第一件旧事。", at = WorldTime.of(1, 17)),
                Note("n2", "第二件旧事。", at = WorldTime.of(1, 17)),
            ),
        )
        val world = World.from(
            StartingWorld.snapshot().let { snap ->
                snap.copy(entities = snap.entities.map { if (it.id == "dwarf") full else it })
            },
        )
        val script = Scripted(
            listOf(
                // 「旧事不作数了，同时记一件新的」——那是同一件事的两面，不该被频率闸或上限挡住。
                turn("c1", "note", """{"text":"旧事已经了了。","supersedes":"n1"}"""),
                stop(),
            ),
        )

        val npc = Npc(
            LlmClient(config(), script.client),
            Judge(LlmClient(config(), script.client)),
            settings = ContextSettings(noteLimit = 2),
        )
        npc.wake(world, Waking("dwarf", greeted("那事完了。"), originOf(world)))

        val notes = world.byId("dwarf")?.notes.orEmpty()
        assertEquals(3, notes.size, "新的那一条写进去了")
        assertEquals(listOf("n2", "n3"), notes.live().map { it.id }, "而 n1 从此不算数")
    }

    @Test
    fun aNoteTooLongToBeANoteIsRefused() = runBlocking {
        val long = "甲".repeat(201)
        val script = Scripted(
            listOf(
                turn("c1", "note", """{"text":"$long"}"""),
                stop(),
            ),
        )
        val world = offCycle(world())

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("听着。"), originOf(world)))

        assertTrue(world.byId("dwarf")?.notes.orEmpty().isEmpty())
        assertTrue("太长" in bodiesIn(script).last(), "退回去的话要带上字数，它才知道该缩到多短")
        // **它确实被记成了一次协议问题**：那条太长的笔记谁都没写下去，而这件事不能静默——静默的话
        // 它以为记下了，而那一刻从来没进过笔记。与 `say` 的空台词走的是同一条路。
        assertEquals(1, reaction.problems.size)
        assertTrue("200" in reaction.problems.single(), "诊断里要说清上限是多少")
    }

    @Test
    fun anEmptyNoteComesBackAsSomethingItCanFix() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", "note", """{"text":"   "}"""),
                stop(),
            ),
        )
        val world = offCycle(world())

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("记一下。"), originOf(world)))

        assertTrue(world.byId("dwarf")?.notes.orEmpty().isEmpty())
        assertEquals(1, reaction.problems.size)
        assertTrue("text" in reaction.problems.single())
    }
}
