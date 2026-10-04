package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.generate.TerrainGenerator
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.save.LastSave
import io.github.k1midi0.infinitesimal.schedule.Scheduling
import io.github.k1midi0.infinitesimal.world.CycleCatchUp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * **中止这一格**（§14.58）：用户定的形状是"一只手动按钮，按下 = 回上一份存档"。
 *
 * ## 它最要紧的那一条：中止**不许写盘**
 *
 * "回上一格"能成立的全部前提是**盘上那一份还是上一格的样子**。而 `run(...)` 的 `finally` 里本来就会
 * 自动存档一次——在那里写下去，就等于把"上一格"换成一份半成品（这一格里的改动是就地提交的，半路没有
 * 干净的落脚点）。所以这一支钉的是这个：
 *
 *  - 取消之后这一格收场（`busy` 回到假，按钮能再按）；
 *  - **盘上那份存档一个字都没变**；
 *  - 日志里**没有"出错了"**——取消不是出错。
 */
class GameSessionAbortTest {

    private val dir = File("build/playlogtest/abort")

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    @BeforeTest
    fun clean() {
        dir.deleteRecursively()
        dir.mkdirs()
        // `saveTo` 会顺手记一句"上次玩的是这一份"（`LastSave`）——把它指到 build 里，**别碰玩家的主目录**。
        LastSave.overrideDirectory = "${dir.absolutePath}/lastsave"
    }

    @AfterTest
    fun restore() {
        LastSave.overrideDirectory = null
    }

    /**
     * 一个**装死的端点**：这一格会停在"等端点"那一步，直到被取消。
     *
     * 它用 `delay` 而不是 `awaitCancellation()`：前者是**可取消**的挂起，后者在 MockEngine 里不是
     * （第一次写这一支就踩到了——那一版无论怎么取消，这一格都收不了场）。
     */
    private fun aSilentEndpoint(): HttpClient = HttpClient(
        MockEngine {
            delay(60_000)
            respond(
                content = "",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        },
    ) {
        // 结构与真客户端一致：非 2xx 是数据，不是异常（这里根本不会走到）。
        expectSuccess = false
    }

    /**
     * ## 这一支为什么要给上开局那片地
     *
     * 因为导演那一段提示词里带着**身边那一片与一张地图**，而它是**同步拼**出来的（中间没有挂起点）。
     * 空地形下第一次写这一支时，`arrange` 会停在"在一个空世界上画几千块 chunk"那一步——**纯 CPU，
     * 取消不进去**，于是"中止"看起来完全没生效（我照着这条查了一轮）。给上 `StartingWorld.ground`
     * 之后它几毫秒就拼完，这一支测的才是"取消一个挂起中的等待"。
     *
     * 这条也给产品留了一句实话：**中止只能取消挂起中的等待**，正在纯 CPU 里拼东西的那一段它进不去。
     */
    private fun sessionOf(client: HttpClient) = GameSession(
        config = config,
        ground = StartingWorld.ground,
        director = Director(LlmClient(config, client), TerrainGenerator(LlmClient(config, client))),
        scheduler = Scheduling { CycleCatchUp(emptyList()) },
    )

    /** 等这一格收场（取消之后的 `finally` 也要跑完）。 */
    private suspend fun settled(session: GameSession) {
        withTimeout(5_000) { while (session.busy) delay(10) }
    }

    /**
     * **按下按钮之后马上又按中止也要收得了场**（这一支是那个真 bug 的回归测试）。
     *
     * `launch` 默认是把活排到 dispatcher 上、稍后才开始跑，而 `busy` 是立刻置上的。所以在"还没轮到开始
     * 跑"那一小段里取消，取消掉的是一个**从未进入 `try/finally`** 的协程——而收场那几行（`busy = false`、
     * 把任务从清单上摘掉）全在 `finally` 里。症状是按钮永远按不动。
     */
    @Test
    fun abortingRightAfterStartingStillLetsTheLevelSettle() = runBlocking {
        val session = sessionOf(aSilentEndpoint())

        session.arrange(this)
        assertTrue(session.busy, "按下之后这一格就是在跑的")
        session.abortTurn()
        settled(session)

        assertTrue(!session.busy, "立刻中止也要收场——不然那个按钮就永远锁着了")
        assertEquals(emptyList(), session.tasks)
    }

    @Test
    fun abortingLeavesTheLastSaveOnDiskUntouched() = runBlocking {
        val path = "${dir.absolutePath}/world.infsave.json"
        val session = sessionOf(aSilentEndpoint())

        // 上一份干净的存档（这就是"上一格"）。
        session.saveTo(path)
        val before = File(path).readText()
        assertTrue(before.isNotEmpty())

        // 这一格：让导演去干活，而端点装死。
        session.arrange(this)
        assertTrue(session.busy, "按下之后这一格就是在跑的")
        assertTrue(session.tasks.isNotEmpty(), "任务清单上要看得见它——玩家正是照那一行字决定要不要中止")

        session.abortTurn()
        settled(session)

        assertTrue(!session.busy, "取消之后这一格要收场，那个按钮要能再按")
        assertEquals(emptyList(), session.tasks, "跑完了不下来的话，界面上会永远留着一件正在跑的事")
        assertEquals(
            before,
            File(path).readText(),
            "中止不许把这一格的世界写进那份存档——「上一格」就是它",
        )
        assertTrue(
            session.log.none { it.text.contains("出错了") },
            "取消不是出错，日志里不该留那句吓人的话：${session.log.map { it.text }}",
        )
    }

    @Test
    fun abortingALevelThatIsNotRunningIsHarmless() = runBlocking {
        val session = sessionOf(aSilentEndpoint())

        session.abortTurn()
        settled(session)

        assertTrue(!session.busy)
        assertTrue(session.log.isEmpty(), "什么都没跑的时候按它，一个字都不该说")
    }
}
