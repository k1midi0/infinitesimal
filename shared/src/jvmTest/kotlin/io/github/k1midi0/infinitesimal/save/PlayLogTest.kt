package io.github.k1midi0.infinitesimal.save

import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.Exchange
import io.github.k1midi0.infinitesimal.llm.FunctionCall
import io.github.k1midi0.infinitesimal.llm.ToolCall
import io.github.k1midi0.infinitesimal.llm.Usage
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * **一局的账**（§14.56）落盘那一侧：两份文件的名字、分段结算、以及"一行一个 JSON 对象"这条纪律。
 *
 * 它跑在 jvmTest 上，因为它要**读回那两个文件**看写成什么样——而"账写得对不对"只有把文件读回来才
 * 算验过（账的全部价值就是事后能读）。
 */
class PlayLogTest {

    private val dir = File("build/playlogtest")

    @BeforeTest
    fun clean() {
        dir.deleteRecursively()
        dir.mkdirs()
    }

    private fun scope() = CoroutineScope(Dispatchers.Default)

    private fun exchange(
        pool: String = "inf-npc",
        content: String = "你好",
        usage: Usage? = Usage(
            promptTokens = 900,
            completionTokens = 40,
            totalTokens = 940,
            promptCacheHitTokens = 800,
            promptCacheMissTokens = 100,
        ),
        failure: String? = null,
    ) = Exchange(
        startedAtMs = 1_700_000_000_000,
        elapsedMs = 1234,
        pool = pool,
        model = "deepseek-flash",
        thinking = "disabled",
        effort = null,
        messages = listOf(ChatMessage.user("你在吗"), ChatMessage.assistant(content)),
        tools = listOf("say"),
        content = content,
        reasoning = "先看它在不在。",
        calls = listOf(ToolCall(id = "call_1", function = FunctionCall(name = "say", arguments = """{"text":"你好"}"""))),
        usage = usage,
        failure = failure,
    )

    /**
     * 三个文件同名前缀——**这是用户拍的那一条**（与存档同目录、同名前缀）。
     *
     * 存档名以 `.json` 结尾时把它去掉，否则那两份会变成 `world.infsave.json.log`，看起来像另一份东西
     * 的附属品。
     */
    @Test
    fun theThreeFilesShareOneName() {
        val save = "${dir.absolutePath}/world.infsave.json"
        val log = PlayLog.beside(save, scope())

        assertEquals("${dir.absolutePath}/world.infsave.log", log.logPath)
        assertEquals("${dir.absolutePath}/world.infsave.trace.jsonl", log.tracePath)

        val bare = PlayLog.beside("${dir.absolutePath}/no-suffix", scope())
        assertEquals("${dir.absolutePath}/no-suffix.log", bare.logPath, "没有 .json 后缀就原样接上")
    }

    @Test
    fun eachSectionIsSettledAndTheWorldsLinesAreInIt() = runBlocking {
        val log = PlayLog.beside("${dir.absolutePath}/world.infsave.json", scope())
        log.line("===== 新的一局 =====")
        log.section("跑一轮…｜第 1 天 18:00")
        log.line("[说] 你说：「这酒怎么这么淡？」")
        log.exchange(exchange())
        log.section("过一格…｜第 1 天 18:15")
        log.line("[记] （矮人到了柜台后面）")
        log.flush()

        val text = File(log.logPath).readText()

        assertTrue("跑一轮…｜第 1 天 18:00" in text, "分段头要带世界钟，账才与这一局对得上")
        assertTrue("[说] 你说：「这酒怎么这么淡？」" in text, "玩家看得见的那一栏就是账里的一栏")
        assertTrue("[记] （矮人到了柜台后面）" in text)
        assertTrue("inf-npc" in text && "1234 ms" in text, "每一趟调用一行，带落的是哪一池与多久")
        assertTrue("提示 900（命中 800 / 未命中 100）｜输出 40" in text, "token 账要写清命中与未命中")
        assertTrue("say" in text, "这一趟调了哪只手")
        assertTrue("（这一段：1 次调用" in text, "开新段的时候，上一段要结算成一行")
        assertTrue("到此刻合计：1 次调用" in text, "flush 之后能看见这一局到此刻的合计")
    }

    /**
     * **一行一个 JSON 对象**是那一份文件唯一的纪律。
     *
     * 提示词里有换行、有引号、有全角标点；它们必须被转义掉，否则"一行一次调用"就不成立，而 grep 它
     * 的人（包括我）会在第一个换行处看到一个半截的 JSON。
     */
    @Test
    fun theTraceIsOneJsonObjectPerLineAndCarriesTheWholePrompt() = runBlocking {
        val log = PlayLog.beside("${dir.absolutePath}/world.infsave.json", scope())
        val tricky = "第一行\n第二行 \"带引号\" 与反斜杠 \\ 收尾"
        log.exchange(exchange(content = tricky))
        log.exchange(exchange(pool = "inf-judge", content = "另一个池"))
        log.flush()

        val lines = File(log.tracePath).readLines().filter { it.isNotBlank() }
        assertEquals(2, lines.size, "两次调用两行——换行必须被转义在字符串里面")

        val json = Json { ignoreUnknownKeys = true }
        val first = json.decodeFromString(Exchange.serializer(), lines[0])
        assertEquals("inf-npc", first.pool)
        assertEquals(tricky, first.content, "原文要一字不差地回来")
        assertEquals("你在吗", first.messages.first().content)
        assertEquals("""{"text":"你好"}""", first.calls.single().function.arguments)
        assertEquals(800, first.usage?.promptCacheHitTokens)

        val second = json.decodeFromString(Exchange.serializer(), lines[1])
        assertEquals("inf-judge", second.pool)
    }

    @Test
    fun aFailedCallIsWrittenDownAsFailed() = runBlocking {
        val log = PlayLog.beside("${dir.absolutePath}/world.infsave.json", scope())
        log.exchange(exchange(usage = null, failure = "429 限流"))
        log.flush()

        val text = File(log.logPath).readText()
        assertTrue("**失败**：429 限流" in text, "失败要说得出是哪一句")
        assertTrue("（没回 token）" in text, "账不全就写账不全，不拿零冒充")

        val record = Json { ignoreUnknownKeys = true }
            .decodeFromString(Exchange.serializer(), File(log.tracePath).readLines().first { it.isNotBlank() })
        assertEquals("429 限流", record.failure)
    }

    /**
     * 钱是估的，而估法要钉住：**命中 0.04 / 未命中 2 / 输出 8**（元每百万，高峰价，§10.2）。
     *
     * 两格缺的时候按"全部未命中"算——**偏贵的那一边**：读数用来做决定，宁可估高。
     */
    @Test
    fun theCostIsEstimatedFromTheEndpointsOwnNumbers() {
        val usage = Usage(
            promptTokens = 1_000_000,
            completionTokens = 100_000,
            totalTokens = 1_100_000,
            promptCacheHitTokens = 500_000,
            promptCacheMissTokens = 500_000,
        )
        // 500k × 0.04 + 500k × 2 + 100k × 8 = 0.02 + 1 + 0.8
        assertEquals(1.82, PlayLog.costOf(usage), 1e-9)

        assertEquals(500_000, PlayLog.missOf(usage))
        assertEquals(
            1_000_000,
            PlayLog.missOf(Usage(promptTokens = 1_000_000)),
            "两格都缺时按全部未命中算（偏贵的那一边）",
        )
        assertEquals(
            1_000_000,
            PlayLog.missOf(Usage(promptTokens = 1_000_000, promptCacheHitTokens = 0)),
            "只报了命中而没有未命中：剩下的都按未命中算",
        )
        assertEquals(0.0, PlayLog.costOf(null), "没回 token 的一趟不算钱——但总账会少，所以日志里写着（没回 token）")
    }

    @Test
    fun nothingIsWrittenBeforeAnythingHappens() {
        val log = PlayLog.beside("${dir.absolutePath}/world.infsave.json", scope())
        assertTrue(!File(log.logPath).exists(), "还没写就不该有一个空文件——空的与「没跑过」是两件事")
        assertNotNull(log.logPath)
    }
}
