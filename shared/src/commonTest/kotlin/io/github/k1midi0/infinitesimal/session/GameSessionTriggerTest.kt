package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.generate.TerrainGenerator
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeTools
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.world.TerrainSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.coroutines.coroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/**
 * 一轮之后那两条触发路径，以及任务清单（§14.33、§12 第 41 项）。
 *
 * 这一层验的不是模型有多聪明，而是**会话有没有在该动手的时候动手、该说的时候说**：
 *
 * - 硬圈缺了地形，**代码**要发现它（不是模型说了算），而且补不上时要说出来；
 * - 地形齐着的时候，**一个字都不该多说**——"什么都没发生"与"我没检查"在日志里长得一样，那是
 *   这一层最该避免的混淆；
 * - 后台那一看要出现在任务清单上、跑完要自己下来，而它**不挡玩家**（`busy` 一动不动）。
 *
 * 最后那一条没法靠"跑完再看一眼"验：后台那一条是排队跑的，等它跑完时它早就从清单上下来了。所以这里
 * 的假端点会在**每一次请求的当口**读一次会话状态——那是唯一一个确定落在"它正跑着"里头的时刻。
 */
class GameSessionTriggerTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    /**
     * 假端点，外加一个**每次请求都会被调到的钩子**。
     *
     * [onRequest] 就是上面那段注释说的那个当口：帧是模型说的话，而这里要读的是**说话那一刻会话长什么
     * 样子**。
     */
    private class Recording(private val frames: List<String>) {
        var onRequest: (() -> Unit)? = null
        val bodies = mutableListOf<String>()
        private var served = 0

        val client: HttpClient = HttpClient(
            MockEngine { request ->
                bodies += (request.body as? TextContent)?.text.orEmpty()
                onRequest?.invoke()
                val body = frames[minOf(served, frames.size - 1)]
                served++
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        ) {
            expectSuccess = false
        }
    }

    /**
     * 开局那片地，但**一块都没标"已经有地形"**。
     *
     * 于是硬圈整整 49 块都缺——第一轮就会触发那条同步的路，而且不需要先让玩家走 8 米跨过一块。走这条
     * 路更快也更稳：搬坐标是另一支测试的事，这里验的是触发本身。
     */
    private fun gappedGround(): TerrainSnapshot = StartingWorld.ground.copy(generated = emptyList())

    /** 判定与导演共用同一条假端点，于是帧按调用发生的先后被依次发出去。 */
    private fun session(rec: Recording, ground: TerrainSnapshot): GameSession = GameSession(
        config = config,
        ground = ground,
        judge = Judge(LlmClient(config, rec.client)),
        director = Director(
            LlmClient(config, rec.client),
            TerrainGenerator(LlmClient(config, rec.client)),
        ),
    )

    /** 两步判定的两帧：对齐到玩家自己、然后什么也没改变。 */
    private fun aTurnThatChangesNothing(): List<String> = listOf(
        turn("c1", JudgeTools.TARGET, """{"id":"player"}"""),
        turn("c2", JudgeTools.NO_CHANGE, """{"note":"你低头把衣襟理平了。"}"""),
    )

    /** 把"我理了理衣襟"跑完：**先勾上玩家自己**，否则第一步会对齐出一个与勾选不符的集合、停在预览那一步。 */
    private suspend fun GameSession.tidyYourCollar(scope: CoroutineScope? = null) {
        click(PLAYER)
        runTurn("", "我低头理了理衣襟", scope)
    }

    /** 等所有排出去的活干完——包括那些**在干的过程中又排出去的**，所以是一轮一轮收。 */
    private suspend fun settle() {
        while (true) {
            val kids = coroutineContext.job.children.toList()
            if (kids.isEmpty()) return
            kids.forEach { it.join() }
        }
    }

    @Test
    fun aHoleInTheHardRingThatCannotBeFilledIsSaidOutLoud() = runBlocking {
        val rec = Recording(aTurnThatChangesNothing() + listOf(stop("我什么也不干。")))
        val session = session(rec, gappedGround())

        session.tidyYourCollar()

        // 导演两轮都不动手，而代码要的是"那 49 块真的写进地形"——它没写，于是这一次没成。
        assertNotNull(session.notice, "世界少了一块地，玩家可能正站在缺口边上，必须弹出来")
        assertTrue(
            session.log.any { it.kind == LogLine.Kind.ERROR && it.text.contains("没能长出来") },
            "日志里那条要写清楚是硬圈那几块地、试了几次",
        )
    }

    /**
     * **地形齐着的时候，一个字都不该多说。**
     *
     * 这一支和上面那一支是同一段代码的两条路：判据是"硬圈里有没有还没生成的块"，而开局那片地正好
     * 填满硬圈。少这一支的话，"没检查"与"检查了、没事"在测试里就没法区分。
     */
    @Test
    fun groundThatIsAlreadyThereIsNotWorthMentioning() = runBlocking {
        val rec = Recording(aTurnThatChangesNothing())
        val session = session(rec, StartingWorld.ground)

        session.tidyYourCollar()

        assertNull(session.notice)
        assertTrue(session.log.none { it.text.contains("没能长出来") })
        // **两帧用完就完了**：地形齐着，那次补地根本不该发生——多花一次请求就说明判据写错了。
        assertEquals(2, rec.bodies.size, "对齐一次、后果一次，没有第三次")
    }

    /**
     * 任务清单：**前台那一轮与后台那一看都要在清单上，而后台那一看不该让 `busy` 变真**。
     *
     * 后台那一看不挡玩家，所以玩家唯一能知道它在跑的地方就是这份清单——而"不挡玩家"正是 `busy` 为假
     * 那一刻的含义：输入框还开着、别的按钮还能按。
     */
    @Test
    fun bothTheTurnAndTheWatchAreOnTheListAndTheWatchDoesNotBlock() = runBlocking {
        val rec = Recording(aTurnThatChangesNothing() + listOf(stop("看着呢。")))
        lateinit var session: GameSession

        var sawTurn = false
        var sawWatch = false
        var watchWasBlocking = false
        rec.onRequest = {
            val texts = session.tasks.map { it.text }
            // 玩家那一轮的行**是会改字的**（「跑一轮…」→「判定中…」→「算后果…」），所以判据是
            // "清单上有一条不是后台那一看"——那正是"一件事往前走"的写法，而不是每换一步就多一行。
            if (texts.any { !it.contains("导演在看着") }) sawTurn = true
            if (texts.any { it.contains("导演在看着") }) {
                sawWatch = true
                if (session.busy) watchWasBlocking = true
            }
        }

        session = session(rec, StartingWorld.ground)
        session.click(PLAYER)
        session.submit(this, "", "我低头理了理衣襟")
        settle()

        assertTrue(sawTurn, "玩家那一轮在跑的时候，清单上要有它")
        assertTrue(sawWatch, "后台那一看在跑的时候，清单上要有它")
        assertTrue(!watchWasBlocking, "后台那一看不挡玩家——它在跑的时候 busy 必须是假的")
        assertEquals(emptyList(), session.tasks, "跑完了不下来的话，界面上会永远留着一件'正在跑'的事")
    }
}
