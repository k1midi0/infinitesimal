package io.github.k1midi0.infinitesimal.npc

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
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.EventKind
import io.github.k1midi0.infinitesimal.world.Spot
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime

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
}
