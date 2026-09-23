package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.Usage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 首个 spike：**走到一个节点 → 渲染 → 落库 → 再看同一节点不重新生成。**
 *
 * 它同时验三件事（设计 §13）：延迟能不能忍、成本对不对、"渲染即固化"做不做得动。
 *
 * 跑法（密钥不进仓库，按次传入）：
 *
 *     .\gradlew.bat :shared:jvmTest --tests "*ObserveFreezeSpike*" `
 *         -PinfinitesimalLiveKeyFile="C:\path\to\key.txt" --rerun-tasks
 *
 * 不带那个参数时它什么都不做，只打印一行说明——和 Pulsus 的联机验证同一个约定。
 */
class ObserveFreezeSpike {

    private val keyFile: File?
        get() = System.getProperty("infinitesimal.liveKeyFile")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    @Test
    fun observeThenFreezeThenRefine() {
        val file = keyFile
        if (file == null || !file.isFile) {
            println("[spike 未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>，本次未访问端点")
            return
        }

        val config = EndpointConfig(
            baseUrl = "https://api.deepseek.com",
            apiKey = file.readText().trim(),
            model = "deepseek-flash",
            thinking = ThinkingMode.Never,
        )
        val worldFile = File("build/spike/world.json")
        worldFile.delete()
        val world = SpikeWorld(worldFile).load()
        world.seed(seedWorld())
        val renderer = SpikeRenderer(LlmClient(config), world)

        val rows = mutableListOf<Row>()

        runBlocking {
            // 1. 首次观察：世界里从没有过它的描述，必须发请求。
            val first = renderer.observe("oak_table")
            rows += Row("① 首次观察木桌", first, renderer.requestCount)

            // 2. 再观察同一个物体：这是整个 spike 的核心断言——不该发请求。
            val cached = renderer.observe("oak_table")
            rows += Row("② 再观察木桌（应命中固化）", cached, renderer.requestCount)

            // 3. 观察另一个物体：应该是新请求，但 system 前缀不变，缓存该命中一部分。
            val cup = renderer.observe("wine_cup")
            rows += Row("③ 观察酒杯", cup, renderer.requestCount)

            // 4. 要求更精细：走 §7.2 的"只增不改"。
            val refined = renderer.observe("oak_table", refine = true)
            rows += Row("④ 细化木桌（只增不改）", refined, renderer.requestCount)

            // 5. 物体变了 → 固化作废 → 下一次观察必须重新生成。
            world.invalidate("oak_table")
            val afterChange = renderer.observe("oak_table")
            rows += Row("⑤ 木桌变了之后再观察", afterChange, renderer.requestCount)

            // 6. 固化又成立了：这次应该再次命中。
            val cachedAgain = renderer.observe("oak_table")
            rows += Row("⑥ 再观察（固化又成立）", cachedAgain, renderer.requestCount)
        }

        report(rows)

        // ── 断言：这个 spike 要证明的就是下面这几条 ────────────────────────────────
        assertEquals(4, renderer.requestCount, "总共应当只发出 4 次请求（①②③④⑤⑥ 里只有 1/3/4/5 该发）")
        assertTrue(rows[1].outcome.fromCache, "② 应当命中固化，实际发起了请求")
        assertEquals(0L, rows[1].outcome.elapsedMs, "② 命中固化应当是零耗时")
        assertTrue(rows[5].outcome.fromCache, "⑥ 固化重新成立后应当再次命中")
        assertEquals("stop", rows[0].outcome.finishReason, "① 应当自然结束而不是被截断")
        assertEquals("stop", rows[3].outcome.finishReason, "④ 细化时不应当被 max_tokens 截断")
        assertNotNull(rows[0].outcome.usage, "① 必须拿到 usage，否则成本无从核算")

        // 细化只叠加：新描述不该比旧的短。这是弱检查，真正的判断在 report 打出的两段原文里。
        assertTrue(
            rows[3].outcome.text.length >= rows[0].outcome.text.length,
            "④ 细化后的描述比原来还短，说明它推翻了已有描述而不是叠加",
        )
    }

    private data class Row(val step: String, val outcome: SpikeRenderer.Outcome, val requestsSoFar: Int)

    private fun report(rows: List<Row>) {
        println()
        println("═".repeat(96))
        println("spike：观察 → 固化 → 再看 → 细化 → 失效 → 再看")
        println("═".repeat(96))
        println(
            "%-26s %-8s %8s %14s %8s %16s".format(
                "步骤", "固化命中", "耗时ms", "输入(命中/未命中)", "输出", "成本 元(高峰/空闲)",
            ),
        )
        println("─".repeat(96))
        for (row in rows) {
            val u = row.outcome.usage
            val input = if (u == null) "—" else "${u.promptCacheHitTokens}/${u.promptCacheMissTokens}"
            val output = if (u == null) "—" else "${u.completionTokens}"
            val cost = if (u == null) "—" else "%.6f / %.6f".format(costCny(u, peak = true), costCny(u, peak = false))
            println(
                "%-26s %-8s %8d %14s %8s %16s".format(
                    row.step,
                    if (row.outcome.fromCache) "是" else "否",
                    row.outcome.elapsedMs,
                    input,
                    output,
                    cost,
                ),
            )
        }
        println("─".repeat(96))
        println("实际发出的请求数：${rows.last().requestsSoFar}")

        val billed = rows.mapNotNull { it.outcome.usage }
        if (billed.isNotEmpty()) {
            val peak = billed.sumOf { costCny(it, peak = true) }
            val off = billed.sumOf { costCny(it, peak = false) }
            val totalPrompt = billed.sumOf { it.promptTokens }
            val totalHit = billed.sumOf { it.promptCacheHitTokens }
            println(
                "这一趟合计：输入 ${totalPrompt} token（其中命中缓存 $totalHit）｜" +
                    "成本 高峰 %.5f 元 / 空闲 %.5f 元".format(peak, off),
            )
            println("平均每次请求 %.0f ms".format(billed.indices.map { rows.filter { it.outcome.usage != null }[it].outcome.elapsedMs }.average()))
        }

        println()
        println("① 首次观察木桌：")
        println(rows[0].outcome.text)
        println()
        println("④ 细化木桌（应与上面一致而不是互相矛盾）：")
        println(rows[3].outcome.text)
        println()
        println("③ 酒杯：")
        println(rows[2].outcome.text)
        println("═".repeat(96))
    }

    /** flash 的价目（元/百万 token）。高峰：周一至周五 9:00-12:00、14:00-18:00。 */
    private fun costCny(usage: Usage, peak: Boolean): Double {
        val hit = if (peak) 0.04 else 0.02
        val miss = if (peak) 2.0 else 1.0
        val out = if (peak) 8.0 else 4.0
        return (
            usage.promptCacheHitTokens * hit +
                usage.promptCacheMissTokens * miss +
                usage.completionTokens * out
            ) / 1_000_000.0
    }

    private fun seedWorld(): List<SpikeEntity> = listOf(
        SpikeEntity(
            id = "tavern_hall",
            kind = "地点",
            label = "酒馆大厅",
            x = 10, y = 0, z = 0,
            attributes = mapOf("层高" to "4米", "地面" to "夯土", "光照" to "几盏油灯"),
        ),
        SpikeEntity(
            id = "oak_table",
            kind = "家具",
            label = "木桌",
            x = 12, y = 4, z = 0,
            parent = "tavern_hall",
            attributes = mapOf(
                "材质" to "橡木",
                "状态" to "完好",
                "尺寸" to "2米 × 1米 × 0.8米",
            ),
        ),
        SpikeEntity(
            id = "wine_cup",
            kind = "器具",
            label = "酒杯",
            x = 12, y = 4, z = 1,
            parent = "oak_table",
            attributes = mapOf(
                "材质" to "深色木制",
                "内容物" to "半杯麦酒",
                "状态" to "杯口有划痕",
            ),
        ),
    )
}
