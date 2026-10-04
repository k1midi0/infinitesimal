package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.generate.TerrainGenerator
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.ReferenceScript
import io.github.k1midi0.infinitesimal.world.Script
import io.github.k1midi0.infinitesimal.world.ScriptLibrary
import io.github.k1midi0.infinitesimal.world.ScriptState
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **开局那一次填充**（§14.55）：导演照参考剧本，把它填成这个世界的线。
 *
 * 假端点验的是接线：骨架有没有跟着这一次的消息进去、`source` 打错会不会被拒、一条都没填算不算完成，
 * 以及**快档看不见参考剧本那一节**（它没有写剧本的手，看见也只是白花钱）。
 */
class DirectorOpeningTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun reference(id: String, title: String = id, body: String = "骨架：$id") = ReferenceScript(
        id = id,
        title = title,
        summary = "概括：$id",
        body = body,
        from = "某本小说.txt",
    )

    private fun world(vararg scripts: Script) = World.from(
        WorldSnapshot(StartingWorld.tavern(), StartingWorld.rules(), scripts = scripts.toList()),
    )

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun director(script: Scripted, library: ScriptLibrary) = Director(
        LlmClient(config, script.client),
        TerrainGenerator(LlmClient(config, script.client)),
        references = library,
    )

    /**
     * 一只 `write_script` 调用的参数。
     *
     * **一段一段拼，不写成一整段原始字符串**：原始字符串 `"""…"""` 以 `"` 结尾时，那个引号会被当成
     * 结束符的一部分吃掉（这一支第一版就是这么写坏的：`"body":"正文` 少了收尾的引号，症状是
     * "参数不是合法 JSON"——看着像模型写错了参数，其实是夹具写错了）。
     */
    private fun write(id: String, source: String? = null) = buildString {
        append("{\"id\":\"").append(id).append("\",\"title\":\"").append(id)
        append(" 的标题\",\"summary\":\"这一条线是什么\",\"body\":\"正文\"")
        if (source != null) append(",\"source\":\"").append(source).append("\"")
        append(",\"state\":\"未安排\"}")
    }

    /** 骨架要跟着这一次的消息进去，而填出来的线要记着自己是照哪一条填的。 */
    @Test
    fun theSkeletonGoesIntoTheMessageAndTheLineRemembersWhereItCameFrom() = runBlocking {
        val script = Scripted(listOf(turn("c1", DirectorTools.WRITE_SCRIPT, write("一笔到期的账", "一笔到期的账")), stop()))
        val library = ScriptLibrary(listOf(reference("一笔到期的账", body = "一个人欠着另一个人的东西。")))
        val world = world()

        val result = director(script, library).wake(
            world,
            Waking(Wake.Opening(library.all()), "player", origin(world)),
        )

        assertTrue(result is Awakening.Done, "实得 $result")
        assertEquals(1, world.scripts.size)
        assertEquals("一笔到期的账", world.scripts.first().source)
        val body = script.bodies.first()
        assertTrue("一个人欠着另一个人的东西。" in body, "骨架要在这一次的消息里：\n$body")
        assertTrue("一笔到期的账" in body, "代号也要在：\n$body")
    }

    /**
     * **一条都没填 = 没干完。**
     *
     * 用户定的口径是「开局填一批」——这一次唤醒是**要交差的**（与 `Arrange` 同一条），而"我看过了不用
     * 动"在这里不是合法结果。
     */
    @Test
    fun fillingNothingIsNotDoneAndTheReasonSaysWhichOnes() = runBlocking {
        val script = Scripted(listOf(stop("这一带放不下这些。"), stop("还是放不下。")))
        val library = ScriptLibrary(listOf(reference("一笔到期的账")))
        val world = world()

        val result = director(script, library).wake(
            world,
            Waking(Wake.Opening(library.all()), "player", origin(world)),
        )

        assertTrue(result is Awakening.FailedProtocol, "实得 $result")
        assertEquals(2, (result as Awakening.FailedProtocol).attempts)
        assertTrue(result.problems.any { "一条都没填" in it }, "${result.problems}")
        assertTrue("一笔到期的账" in script.bodies[1], "重来那一次要点明是哪些：\n${script.bodies[1]}")
    }

    /**
     * **`source` 打错要被拒。**
     *
     * 一个指不到原典的来源是**静默**的错误（"这条线是从哪儿来的"是日后唯一能查的东西），所以它过一道
     * 门：库里没有这一条就驳回，并且把库里现在有的列出来。
     */
    @Test
    fun aSourceThatIsNotInTheLibraryIsRefusedWithTheList() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", DirectorTools.WRITE_SCRIPT, write("一笔到期的账", "没有这一条")),
                turn("c2", DirectorTools.WRITE_SCRIPT, write("一笔到期的账", "一笔到期的账")),
                stop(),
            ),
        )
        val library = ScriptLibrary(listOf(reference("一笔到期的账")))
        val world = world()

        val result = director(script, library).wake(
            world,
            Waking(Wake.Opening(library.all()), "player", origin(world)),
        )

        assertTrue(result is Awakening.Done, "实得 $result")
        val answer = script.bodies[1]
        assertTrue("库里没有这一条" in answer, "驳回理由要进历史：\n$answer")
        assertTrue("一笔到期的账" in answer, "还要列出库里有的那些：\n$answer")
        assertEquals("一笔到期的账", world.scripts.first().source)
    }

    /** 打开一条参考剧本读骨架：正文要回给它，并且说清"那些人与地方还没有名字"。 */
    @Test
    fun lookReferenceHandsBackTheSkeleton() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", DirectorTools.LOOK_REFERENCE, """{"id":"一笔到期的账"}"""),
                turn("c2", DirectorTools.WRITE_SCRIPT, write("账", "一笔到期的账")),
                stop(),
            ),
        )
        val library = ScriptLibrary(listOf(reference("一笔到期的账", body = "债主来取那样东西。")))
        val world = world()

        director(script, library).wake(world, Waking(Wake.Opening(library.all()), "player", origin(world)))

        val answer = script.bodies[1]
        assertTrue("债主来取那样东西。" in answer, "骨架要回给它：\n$answer")
        assertTrue("还没有名字" in answer, "要提醒它那些人与地方是填的时候才有的：\n$answer")
    }

    /** 库里没有这个代号时，`look_reference` 的驳回要把库里有的列出来。 */
    @Test
    fun lookReferenceOnSomethingMissingIsRefusedWithTheList() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", DirectorTools.LOOK_REFERENCE, """{"id":"不存在"}"""),
                turn("c2", DirectorTools.WRITE_SCRIPT, write("账", "一笔到期的账")),
                stop(),
            ),
        )
        val library = ScriptLibrary(listOf(reference("一笔到期的账")))
        val world = world()

        director(script, library).wake(world, Waking(Wake.Opening(library.all()), "player", origin(world)))

        assertTrue("库里没有" in script.bodies[1], script.bodies[1])
        assertTrue("一笔到期的账" in script.bodies[1], script.bodies[1])
    }

    /**
     * **【参考剧本】那一节只列还没填过的那些。**
     *
     * 填过的已经在那份【剧本池】清单里了；再列一遍等于同一件事占两处提示词——而这一节存在的理由正是
     * "库里还剩什么能用"。
     */
    @Test
    fun onlyTheUnfilledReferencesAreListed() = runBlocking {
        val library = ScriptLibrary(listOf(reference("甲", title = "甲参考"), reference("乙", title = "乙参考")))
        val already = Script(
            id = "已经填过的那条线",
            title = "已经填过的那条线",
            summary = "这一条线是什么",
            body = "正文",
            state = ScriptState.UNARRANGED,
            source = "甲",
        )
        val script = Scripted(listOf(turn("c1", DirectorTools.WRITE_SCRIPT, write("乙 的线", "乙")), stop()))
        val world = world(already)

        director(script, library).wake(world, Waking(Wake.Opening(listOf(library.find("乙")!!)), "player", origin(world)))

        val body = script.bodies.first()
        assertTrue("乙参考" in body, "还没填的要列出来：\n$body")
        assertTrue("甲参考" !in body, "填过的不用再列一遍：\n$body")
    }

    /** **快档看不见参考剧本那一节**：它没有写剧本的手，看见了也只是白花钱（§8.5 那条权限划分）。 */
    @Test
    fun theFastTierDoesNotSeeTheReferenceSection() = runBlocking {
        val library = ScriptLibrary(listOf(reference("一笔到期的账")))
        val script = Scripted(listOf(stop("看着呢。")))
        val world = world()

        director(script, library).wake(
            world,
            Waking(Wake.Watching("我把那杯麦酒端起来。"), "player", origin(world)),
        )

        val body = script.bodies.first()
        assertTrue("【参考剧本" !in body, "快档不该看见它：\n$body")
        assertTrue("\"name\":\"${DirectorTools.LOOK_REFERENCE}\"" !in body, "快档也没有这只手：\n$body")
    }

    /** 思考档开工时看得见【参考剧本】那一节，且那只手也在它手上。 */
    @Test
    fun theThinkingTierSeesTheSectionAndHasTheHand() = runBlocking {
        val library = ScriptLibrary(listOf(reference("一笔到期的账", title = "一笔到期的账")))
        val script = Scripted(listOf(turn("c1", DirectorTools.WRITE_SCRIPT, write("账", "一笔到期的账")), stop()))
        val world = world()

        director(script, library).wake(
            world,
            Waking(Wake.Called("玩家往东走了一段"), "player", origin(world)),
        )

        val body = script.bodies.first()
        assertTrue("【参考剧本" in body, "思考档要看得到库里还剩什么：\n$body")
        assertTrue("一笔到期的账" in body, body)
        assertTrue("\"name\":\"${DirectorTools.LOOK_REFERENCE}\"" in body, "那只手要在它手上：\n$body")
    }

    /**
     * **改写世界里那条线时，`source` 空着不抹掉原来的来源。**
     *
     * 改写（换措辞、换走向）是常事，而"抹掉来源"在这条路上是静默的：下一次再也没人知道这一条是从哪儿
     * 来的。所以留空 = 保留，指明了一个新的 = 换成那个。
     */
    @Test
    fun rewritingWithoutASourceKeepsTheOriginalOne() = runBlocking {
        val library = ScriptLibrary(listOf(reference("一笔到期的账")))
        val already = Script(
            id = "账",
            title = "账",
            summary = "旧的摘要",
            body = "旧的正文",
            state = ScriptState.UNARRANGED,
            source = "一笔到期的账",
        )
        val script = Scripted(listOf(turn("c1", DirectorTools.WRITE_SCRIPT, write("账")), stop()))
        val world = world(already)

        val result = director(script, library).wake(
            world,
            Waking(Wake.Opening(library.all()), "player", origin(world)),
        )

        assertTrue(result is Awakening.Done, "实得 $result")
        assertEquals(1, world.scripts.size, "改写不是添一条新的")
        assertEquals("一笔到期的账", world.scripts.first().source)
        assertEquals("这一条线是什么", world.scripts.first().summary, "其余字段整张换掉")
    }
}
