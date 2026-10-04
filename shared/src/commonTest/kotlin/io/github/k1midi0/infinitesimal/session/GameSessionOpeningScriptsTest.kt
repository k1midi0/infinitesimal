package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.director.DirectorTools
import io.github.k1midi0.infinitesimal.generate.TerrainGenerator
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.multiTurn
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.world.ReferenceScript
import io.github.k1midi0.infinitesimal.world.Script
import io.github.k1midi0.infinitesimal.world.ScriptLibrary
import io.github.k1midi0.infinitesimal.world.ScriptState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **开局那一次填充的会话侧**（§14.55）：挑哪几条、什么时候挑、以及"库里没有剩下的"时一次调用都不花。
 *
 * 与导演那一侧的分工在这几支里看得很清楚：**挑**是会话的事（它知道这一局打算用哪几条），**填**是导演的
 * 事（它看得见这一带真的有什么）。
 */
class GameSessionOpeningScriptsTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun reference(id: String) = ReferenceScript(
        id = id,
        title = id,
        summary = "概括：$id",
        body = "骨架：$id",
        from = "某本小说.txt",
    )

    private fun write(id: String, source: String) =
        """{"id":"$id","title":"$id 的标题","summary":"这一条线是什么","body":"正文","source":"$source","state":"未安排"}"""

    /**
     * **只注入导演**：这一条路上只有它会碰端点（判定与扮演角色那两处一次都不会被走到），所以它们用默认
     * 那份、不注入——注入得越多，这一支将来跟着它们的构造函数一起改的地方就越多。
     */
    private fun session(script: Scripted, library: ScriptLibrary) = GameSession(
        config = config,
        references = library,
        director = Director(
            LlmClient(config, script.client),
            TerrainGenerator(LlmClient(config, script.client)),
            references = library,
        ),
    )

    /** 开局那一批填在**进游戏之前**，填完池子里就有线，而会话日志里有一行说排了几条。 */
    @Test
    fun theOpeningBatchIsFilledAndSaidInTheLog() = runBlocking {
        val library = ScriptLibrary(listOf(reference("一笔到期的账"), reference("翻上来的东西")))
        val script = Scripted(
            listOf(
                turn("c1", DirectorTools.WRITE_SCRIPT, write("账", "一笔到期的账")),
                stop(),
            ),
        )
        val session = session(script, library)

        session.fillOpeningScripts()

        assertEquals(1, session.world.scripts.size)
        assertEquals("一笔到期的账", session.world.scripts.first().source)
        assertTrue(session.log.any { "导演排下了这一局的线：1 条" in it.text }, "${session.log.map { it.text }}")
    }

    /**
     * **日志数的是"这一次真的添了几条"。**
     *
     * 它与"交给它了几条"不是同一个数：它的提示词里还有一节【参考剧本】列着库里**所有**还没填过的
     * （那一批只交给它三条），所以它可以挑一条这一批没给的来填。第一版按"这一批里的"数，于是联机探针
     * 上出现了"日志说 2 条、池子里多了 3 条"——一句会让人去查代码的假账。
     */
    @Test
    fun theLogCountsWhatWasActuallyAddedNotWhatWasOffered() = runBlocking {
        val library = ScriptLibrary(listOf(reference("参考一"), reference("参考二")))
        val script = Scripted(
            listOf(
                multiTurn(
                    Triple("c1", DirectorTools.WRITE_SCRIPT, write("线一", "参考一")),
                    // **第二条的来源不在这一批里**（`count = 1`）——它从【参考剧本】那一节看见的。
                    Triple("c2", DirectorTools.WRITE_SCRIPT, write("线二", "参考二")),
                ),
                stop(),
            ),
        )
        val session = session(script, library)

        session.fillOpeningScripts(count = 1)

        assertEquals(2, session.world.scripts.size)
        assertTrue(session.log.any { "导演排下了这一局的线：2 条" in it.text }, "${session.log.map { it.text }}")
    }

    /** 库里一条都没有 → **一次调用都不花**（空库是合法的：没有素材，导演自己看着办）。 */
    @Test
    fun anEmptyLibraryCostsNothing() = runBlocking {
        val script = Scripted(listOf(stop(), stop()))
        val session = session(script, ScriptLibrary())

        session.fillOpeningScripts()

        assertTrue(script.bodies.isEmpty(), "不该问端点：${script.bodies.size} 次")
        assertTrue(session.world.scripts.isEmpty())
        assertTrue(session.log.isEmpty(), "${session.log.map { it.text }}")
    }

    /** 已经填过的参考剧本**不再交给它**（这一次它只看得见还没填的那些）。 */
    @Test
    fun whatIsAlreadyFilledIsNotOfferedAgain() = runBlocking {
        val library = ScriptLibrary(listOf(reference("甲参考"), reference("乙参考")))
        val already = Script(
            id = "已经填过的那条线",
            title = "已经填过的那条线",
            summary = "这一条线是什么",
            body = "正文",
            state = ScriptState.UNARRANGED,
            source = "甲参考",
        )
        val script = Scripted(listOf(turn("c1", DirectorTools.WRITE_SCRIPT, write("乙线", "乙参考")), stop()))
        val session = GameSession(
            config = config,
            scripts = listOf(already),
            references = library,
            director = Director(
                LlmClient(config, script.client),
                TerrainGenerator(LlmClient(config, script.client)),
                references = library,
            ),
        )

        session.fillOpeningScripts()

        val body = script.bodies.first()
        assertTrue("───── 乙参考" in body, "还没填的要交给它：\n$body")
        assertTrue("───── 甲参考" !in body, "填过的不该再交给它：\n$body")
        assertEquals(listOf("已经填过的那条线", "乙线"), session.world.scripts.map { it.id })
    }

    /** 开局那一批有上限（[ScriptPlanner.OPENING_FILL] 条）：库里再多也只给这么多。 */
    @Test
    fun theOpeningBatchIsCapped() = runBlocking {
        val library = ScriptLibrary(listOf("参考一", "参考二", "参考三", "参考四", "参考五").map(::reference))
        val script = Scripted(listOf(turn("c1", DirectorTools.WRITE_SCRIPT, write("线一", "参考一")), stop()))
        val session = session(script, library)

        session.fillOpeningScripts()

        val body = script.bodies.first()
        assertTrue("───── 参考三" in body, "前三条要给：\n$body")
        assertTrue("───── 参考四" !in body, "第四条这一批不给：\n$body")
    }

    /**
     * **排不成不抛、也不挡人**：世界照样能玩，只是这一局暂时没有线——所以它写成日志里的一句问题。
     *
     * 与"世界少了一块地"那种失败不同（那是玩家看得见的东西），这一条不是。
     */
    @Test
    fun aFailedOpeningIsSaidInTheLogAndDoesNotThrow() = runBlocking {
        val library = ScriptLibrary(listOf(reference("一笔到期的账")))
        val script = Scripted(listOf(stop("放不下。"), stop("还是放不下。")))
        val session = session(script, library)

        session.fillOpeningScripts()

        assertTrue(
            session.log.any { "开局这几条线没能排出来" in it.text },
            "${session.log.map { it.text }}",
        )
        assertTrue(session.world.scripts.isEmpty())
    }
}
