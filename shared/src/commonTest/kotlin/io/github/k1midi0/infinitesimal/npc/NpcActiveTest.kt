package io.github.k1midi0.infinitesimal.npc

import io.github.k1midi0.infinitesimal.config.ContextSettings
import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.Active
import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Note
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **活跃那一档在角色这一层的样子**（§3.2.1、§12 第 46 项）。
 *
 * 三件要钉住的事，彼此是同一句话的三面：
 *
 *  1. **在循环里看不见自己的笔记**（保留但不注入）——而这一条反过来决定了 `note` 那只手也得打回。
 *  2. **脱轨之后都回来了**：笔记回到眼前、`note` 作数、`arrive` 出现。
 *  3. **回归由它自己宣告，而代码只确认它踩在那一格上**——时刻不对就驳回，理由要写清现在该在哪儿。
 *
 * 唤醒那一条**故意不在这里**：它没有变，醒来的判据仍然只有"被判定点名"（`GameSession.hearAndReact`）。
 * 这一刀不动它，所以这里没有任何"活跃就会自己醒"的断言。
 */
class NpcActiveTest {

    private fun config() = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    /**
     * 那个矮人，带着指定的笔记与活跃状态。**其余实体原样留着**——几张图要被画得出来。
     *
     * 开局是第 1 天 18:00，正是它循环里 `Beat(18*60, "站在柜台后面招呼客人…")` 那一格。
     */
    private fun worldOf(notes: List<Note> = emptyList(), active: Active? = null): World {
        val dwarf = StartingWorld.tavern().first { it.id == "dwarf" }.copy(notes = notes, active = active)
        val snap = StartingWorld.snapshot()
        return World.from(snap.copy(entities = snap.entities.map { if (it.id == "dwarf") dwarf else it }))
    }

    private fun originOf(world: World): ChunkOrigin =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun npcOf(script: Scripted) = Npc(
        LlmClient(config(), script.client),
        Judge(LlmClient(config(), script.client)),
        settings = ContextSettings(),
    )

    private fun greeted(text: String) = Heard.Spoken("player", "你", text)

    /** 一次唤醒发出来的请求体。**不看固定下标**——一次唤醒发几个请求是不定的。 */
    private fun bodiesIn(script: Scripted) = script.bodies

    /**
     * **这一次请求新拼的那一段提示词。**
     *
     * 一个请求体里装着整串历史，而历史里存着**之前几次请求的提示词**（它们本身就是消息）。所以"这一次
     * 给它的提示词里有没有某一节"不能拿整个请求体去查——那会查到旧的、甚至查到一次还没有笔记时的自己。
     */
    private fun lastPromptIn(body: String): String = body.substringAfterLast("\"role\":\"user\"")

    /** 【你记下的事】那一节。**断言要落在这里面**：正文还会出现在历史与工具结果里，那是记录，不是注入。 */
    private fun notesSectionIn(body: String): String =
        lastPromptIn(body).substringAfter("【你记下的事】").substringBefore("【这一片里刚刚发生的事】")

    private fun note(id: String, text: String) =
        Note(id = id, text = text, at = WorldTime.of(1, 17))

    /**
     * 开局那一刻那一格（18:00 的"招呼客人"）。
     *
     * **坐标要与矮人真正站的地方一致**（§14.44）：`canArrive` 现在查两件事——时刻对不对、**人是不是
     * 真的在那一格的地点附近**。少了坐标（默认 0,0,0），一个站在酒馆里的矮人会被判成"还没到"。
     */
    private val nowBeat = Beat(
        atMinute = 18 * 60,
        state = "站在柜台后面招呼客人，手里那块布一直没停过。",
        x = 1000,
        y = 200,
        z = 12800,
    )

    // ── 在循环里：笔记保留但不注入 ──────────────────────────────────────────────────

    @Test
    fun inTheCycleTheNotesAreKeptButNotInjected() = runBlocking {
        val script = Scripted(listOf(stop()))
        val world = worldOf(notes = listOf(note("n1", "玩家欠我一杯酒。")), active = null)

        npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        val body = bodiesIn(script).last()
        assertTrue("【你记下的事】" in body, "那一节永远在——它得知道有这回事")
        // **§3.2.1：保留但不注入。** 库里一直在，只是不装进提示词——而这一条与"唤醒"是两件事。
        assertFalse("玩家欠我一杯酒。" in notesSectionIn(body), "在走循环的时候，笔记不进提示词那一节")
        // 但它**还在库里**。少写这一条断言，这个测试就会在"笔记被删了"时照样通过。
        assertEquals(1, world.byId("dwarf")?.notes?.size)
    }

    @Test
    fun writingANoteWhileInTheCycleIsRefused() = runBlocking {
        val script = Scripted(listOf(turn("c1", "note", """{"text":"顺手记一条。"}"""), stop()))
        val world = worldOf(active = null)

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        // **能看见才写得了。** 不活跃时写下来的东西它自己看不见，那等于把记忆丢进一个抽屉——而它会
        // 以为记下了。这与上面那一节是同一件事的两面。
        assertTrue(world.byId("dwarf")?.notes.orEmpty().isEmpty(), "什么都没写进去")
        assertTrue(reaction.problems.isEmpty(), "这不是协议问题，是世界在说'这会儿不成'")
        assertTrue("循环" in bodiesIn(script).last(), "退回去的话要说清为什么")
    }

    // ── 脱轨之后 ──────────────────────────────────────────────────────────────────

    @Test
    fun onceItIsOffItsCycleTheNotesComeBackAndTheWayHomeAppears() = runBlocking {
        val script = Scripted(listOf(stop()))
        val world = worldOf(
            notes = listOf(note("n1", "玩家欠我一杯酒。")),
            active = Active(since = WorldTime.of(1, 18), target = nowBeat),
        )

        npcOf(script).wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))

        val body = bodiesIn(script).last()
        assertTrue("玩家欠我一杯酒。" in notesSectionIn(body), "活跃的时候，笔记回到眼前")
        // **回去的路必须写出来**：没有这一节，一个被叫住的矮人只会一直待在外面——它不知道自己还能回去。
        assertTrue("【你现在没有在走你平常的那个循环】" in body)
        assertTrue(NpcTools.ARRIVE in body, "要说清用哪只手回去")
    }

    @Test
    fun itCanGoHomeOnTheBeatItNamed() = runBlocking {
        val script = Scripted(listOf(turn("c1", "arrive", """{"state":"站在柜台后面招呼客人，手里那块布一直没停过。"}"""), stop()))
        val world = worldOf(active = Active(since = WorldTime.of(1, 18), target = nowBeat))

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("没事了。"), originOf(world)))

        // **代码确认之后才清掉。** 活跃是一个存下来的事实，而"我到了"是它说的一句话——两件事都得成立。
        assertNull(world.byId("dwarf")?.active, "回到了循环上")
        // 而 `arrive` **不是世界事件**，所以这件事只有 `Reaction` 这一格读得出来——探针与日志都靠它。
        assertTrue(reaction.arrived, "这一趟是它自己回去的")
    }

    @Test
    fun goingHomeBeforeTheBeatComesIsRefusedWithTheBeatItIsOn() = runBlocking {
        val script = Scripted(listOf(turn("c1", "arrive", """{"state":"招呼客人"}"""), stop()))
        val world = worldOf(
            // 目标是**中午那一格**（12:00 吃午饭），而现在是 18:00——它还没到那一刻。
            active = Active(
                since = WorldTime.of(1, 18),
                target = Beat(12 * 60, "坐在柜台后面吃自己那份午饭，谁也不理。", 1150, 120, 12800),
            ),
        )

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("没事了。"), originOf(world)))

        assertTrue(world.byId("dwarf")?.active != null, "还不该清掉")
        assertTrue(reaction.problems.isEmpty(), "它说错了时刻不是协议问题")
        assertFalse(reaction.arrived, "没回去——那一格还没到")
        val back = bodiesIn(script).last()
        // **理由要可执行**（§1.3）：它得知道现在该在哪一格、要等什么。
        assertTrue("还不到那一格" in back)
        assertTrue("站在柜台后面招呼客人" in back, "要把它此刻该在的那一格原文给它")
    }

    @Test
    fun goingHomeWhileStillInTheCycleIsRefused() = runBlocking {
        val script = Scripted(listOf(turn("c1", "arrive", """{"state":"招呼客人"}"""), stop()))
        val world = worldOf(active = null)

        val reaction = npcOf(script).wake(world, Waking("dwarf", greeted("没事了。"), originOf(world)))

        // 「我回到我本来就在的地方」不是一次回归。放过它，活跃这个状态就由一次误调用决定了。
        assertNull(world.byId("dwarf")?.active)
        assertFalse(reaction.arrived, "它本来就没脱轨，谈不上回去")
        assertTrue("本来就走在自己的循环上" in bodiesIn(script).last())
    }

    // ── 两条线放在一起：一整趟 ───────────────────────────────────────────────────────

    /**
     * 一场完整的脱轨——**在循环里 → 被叫住 → 记一条 → 回家 → 又看不见了**。
     *
     * 四步走一遍是有必要的：单看任何一步都对，而"回到家之后笔记还在库里、只是不再进提示词"这件事
     * 只有在第四步才看得出来。**它答不出来才说明这台机器是活的**：笔记没被删，也没有留在眼前。
     */
    @Test
    fun awholeTripOutAndBack() = runBlocking {
        val script = Scripted(
            listOf(
                stop(), // ① 它还在循环里
                turn("c1", "note", """{"text":"他托我记着这杯酒。"}"""), // ② 脱轨了，记一条
                stop(),
                turn("c2", "arrive", """{"state":"站在柜台后面招呼客人，手里那块布一直没停过。"}"""), // ③ 回家
                stop(),
                stop(), // ④ 回到家之后再看一眼
            ),
        )
        val world = worldOf(active = null)
        val npc = npcOf(script)

        // ① 在循环里
        val step1 = script.bodies.size
        npc.wake(world, Waking("dwarf", greeted("你好啊。"), originOf(world)))
        val afterFirst = script.bodies.size

        // ② 有人把它叫住了（这一刀里由导演或玩家标上，不是它自己）
        world.put(world.byId("dwarf")!!.copy(active = Active(since = world.clock, target = nowBeat)))
        npc.wake(world, Waking("dwarf", greeted("你替我记着。"), originOf(world)))
        val afterSecond = script.bodies.size

        assertEquals(1, world.byId("dwarf")?.notes?.size, "脱轨之后那条写得进去了")

        // ③ 它自己宣告回家（时刻正好对得上）
        npc.wake(world, Waking("dwarf", greeted("去吧。"), originOf(world)))
        val afterThird = script.bodies.size
        assertNull(world.byId("dwarf")?.active, "回到了循环上")

        // ④ 回到家之后：那条**还在**，而**不再进提示词**
        npc.wake(world, Waking("dwarf", greeted("还在么？"), originOf(world)))

        // **每一段要按唤醒切开再断言。** 拿 `drop(N).last()` 会取到**整个列表**的最后一个——那时已经
        // 跑到后面几次唤醒了，于是"它眼前有没有那条笔记"问的是四轮之后的世界，看着还挺像对的。
        //
        // 每一段里取**最后一次**请求：那是"这一轮结尾时它眼前是什么"。而写笔记那一段里有两次请求，
        // 取第一次会落到"它还没写"的那个时刻上——**而"它刚写下的，这一次唤醒里就该看得见"正是这一条
        // 测试要钉住的东西**（提示词每轮重拼，见 `Npc.once`）。
        val first = bodiesIn(script).drop(step1).take(afterFirst - step1).last()
        val second = bodiesIn(script).drop(afterFirst).take(afterSecond - afterFirst).last()
        val fourth = bodiesIn(script).drop(afterThird).last()

        assertFalse("他托我记着这杯酒。" in notesSectionIn(first), "① 在循环里：笔记不进提示词")
        assertTrue("他托我记着这杯酒。" in notesSectionIn(second), "② 脱轨之后：写下来的同一轮就在眼前")
        assertEquals(1, world.byId("dwarf")?.notes?.size, "③④ 而它一直没被删掉（§3.2.1：保留）")
        assertFalse("他托我记着这杯酒。" in notesSectionIn(fourth), "④ 回到家：又看不见了")
        // 而那一节本身也不该在——**要落在这一次的提示词里查**：请求体里带着历史，而历史里存着之前
        // 几次请求的提示词，其中就有它还在外面游荡时的那一份。
        assertFalse(
            "【你现在没有在走你平常的那个循环】" in lastPromptIn(fourth),
            "④ 它已经在循环里了",
        )
    }
}
