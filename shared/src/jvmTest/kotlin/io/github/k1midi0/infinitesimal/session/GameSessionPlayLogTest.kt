package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.schedule.Scheduling
import io.github.k1midi0.infinitesimal.save.PlayLog
import io.github.k1midi0.infinitesimal.world.CycleCatchUp
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/**
 * **会话这一侧的三个出口真的落到账里了**（§14.56）：分段头（每一次玩家动作开一段，带世界钟）、世界里
 * 发生的每一行（就是玩家在界面「发生了什么」那一栏看到的东西）、以及那些必须弹出界面的通知。
 *
 * ## 为什么这一支一次模型调用都不花
 *
 * 走的是「等…」那条路，而开局的时间轴是空的：代码在这一步**只推时钟**，然后说一句"等不到什么"。所以
 * 这一支能验那条接线却不碰端点（`Scheduling` 那一个空实现把哨兵那一路也钉死成零调用）。
 *
 * ## 它钉的是"接上了没有"，不是"写得好不好看"
 *
 * 格式由 `save/PlayLogTest` 管；这里只要三样东西都出现在文件里。
 */
class GameSessionPlayLogTest {

    private val dir = File("build/playlogtest/session")

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
    }

    private fun sessionOf(playLog: PlayLog?) = GameSession(
        config = config,
        playLog = playLog,
        // 零调用：到点就位那一路被换成一个空实现（它本来也不该在这条路上被问到）。
        scheduler = Scheduling { CycleCatchUp(emptyList()) },
    )

    /** 等所有排出去的活干完——`run(...)` 是在传进去的那个 scope 上起协程的。 */
    private suspend fun settle() {
        while (true) {
            val kids = coroutineContext.job.children.toList()
            if (kids.isEmpty()) return
            kids.forEach { it.join() }
        }
    }

    @Test
    fun theAccountCarriesTheSectionsTheLinesAndTheAnnouncements() = runBlocking {
        // **账的写者用一个独立的 scope**：它一直活着（等队列关掉），挂在下面那个 job 上的话
        // `settle()` 会永远等不完——§14.56 第一次就是这么挂住的（七分钟没回来）。
        val log = PlayLog.beside("${dir.absolutePath}/world.infsave.json", CoroutineScope(Dispatchers.Default))
        val session = sessionOf(log)

        session.announce("存好了：world.infsave.json")
        session.waitUntilNext(CoroutineScope(coroutineContext))
        settle()
        log.flush()

        val text = File(log.logPath).readText()

        assertTrue(
            "[通知] 存好了：world.infsave.json" in text,
            "必须弹出的那几句话也要进账——一局玩完只剩账，而它们往往正是「为什么出了事」的答案",
        )
        assertTrue("── 等…｜" in text, "每一次玩家动作开一段：段头就是「等…」那一个词")
        assertTrue("18:00" in text, "段头要带世界钟——账里那一格只有对着这一局才读得懂")
        assertTrue(
            "[记] （时间轴上没有将来要发生的事，等不到什么）" in text,
            "世界里那一行与界面上那一行是同一句（同一个 say 漏斗）",
        )
    }

    /** **没给账就不落盘**：这是"默认零侵入"那一句在会话这一侧的形态（测试与探针都走这条路）。 */
    @Test
    fun withNoAccountNothingIsWritten() = runBlocking {
        val session = sessionOf(null)

        session.announce("存好了")
        session.waitUntilNext(CoroutineScope(coroutineContext))
        settle()

        assertTrue(
            !File("${dir.absolutePath}/world.infsave.log").exists() &&
                !File("${dir.absolutePath}/world.infsave.trace.jsonl").exists(),
            "没有账本就不该有任何文件冒出来",
        )
        assertTrue(session.log.isNotEmpty(), "界面那一栏照旧——账是额外的一份，不是它的替代品")
    }
}
