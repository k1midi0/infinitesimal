package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.FimClient
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.Usage
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * spike 第二轮：**插入一句话，两种机制对比；以及固化循环本身。**
 *
 * 第一轮（单段文本 + 提示词求"只增不改"）已经证伪了那个做法：细化后的描述和原描述直接矛盾。
 * 这一轮改成"描述按句存、只能插入"，并让两条路在**同一份基线、同一个插入点**上各写一次。
 *
 * 跑法：
 *
 *     .\gradlew.bat :shared:jvmTest --tests "*ObserveFreezeSpike*" `
 *         -PinfinitesimalLiveKeyFile="C:\path\to\key.txt"
 */
class ObserveFreezeSpike {

    private val keyFile: File?
        get() = System.getProperty("infinitesimal.liveKeyFile")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    @Test
    fun freezeThenInsertByBothMechanisms() {
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
        val world = SpikeWorld(worldFile).load().apply { seed(seedWorld()) }
        val renderer = SpikeRenderer(LlmClient(config), world)

        val rows = mutableListOf<Row>()
        val toolWriter = ToolSentenceWriter(LlmClient(config))
        val fimWriter = FimSentenceWriter(FimClient(config))

        lateinit var baseline: List<String>
        lateinit var toolInsertion: Insertion
        lateinit var fimInsertion: Insertion
        var toolViolations: List<String> = emptyList()
        var fimViolations: List<String> = emptyList()

        runBlocking {
            // ── 固化循环 ──────────────────────────────────────────────────────────
            val first = renderer.observe("oak_table")
            rows += Row("① 首次观察木桌", first, renderer.requestCount)
            baseline = first.sentences

            val cached = renderer.observe("oak_table")
            rows += Row("② 再观察木桌（应命中固化）", cached, renderer.requestCount)

            val cup = renderer.observe("wine_cup")
            rows += Row("③ 观察酒杯", cup, renderer.requestCount)

            // ── 对比：同一基线、同一插入点，两条路各写一句 ──────────────────────────
            val context = renderer.contextOf(requireNotNull(world.byId("oak_table")))
            val hint = 1 // 插在第 1 句之后——中间插入，后缀条件化才有意义

            world.put(requireNotNull(world.byId("oak_table")).copy(id = "oak_table_tool", label = "木桌"))
            world.put(requireNotNull(world.byId("oak_table")).copy(id = "oak_table_fim", label = "木桌"))

            toolInsertion = toolWriter.insert(baseline, context, hint)
            toolViolations = renderer.applyInsertion("oak_table_tool", toolInsertion)

            fimInsertion = fimWriter.insert(baseline, context, hint)
            fimViolations = renderer.applyInsertion("oak_table_fim", fimInsertion)

            // ── 失效与重生成 ──────────────────────────────────────────────────────
            world.invalidate("oak_table")
            val afterChange = renderer.observe("oak_table")
            rows += Row("④ 木桌变了之后重观察", afterChange, renderer.requestCount)

            val cachedAgain = renderer.observe("oak_table")
            rows += Row("⑤ 再观察（固化又成立）", cachedAgain, renderer.requestCount)
        }

        val toolEntity = requireNotNull(world.byId("oak_table_tool"))
        val fimEntity = requireNotNull(world.byId("oak_table_fim"))

        report(rows, baseline, toolInsertion, toolViolations, toolEntity, fimInsertion, fimViolations, fimEntity)

        // ── 断言 ─────────────────────────────────────────────────────────────────
        assertEquals(3, renderer.requestCount, "整段渲染应当只发 3 次请求（①②③④⑤ 里只有 ①③④ 该发）")
        assertTrue(rows[1].outcome.fromCache, "② 应当命中固化")
        assertEquals(0L, rows[1].outcome.elapsedMs, "② 命中固化应当是零耗时")
        assertTrue(rows[4].outcome.fromCache, "⑤ 固化重新成立后应当再次命中")
        assertNotNull(rows[0].outcome.usage, "① 必须拿到 usage，否则成本无从核算")

        // 两条路都真的插进去了一句
        assertEquals(baseline.size + 1, toolEntity.sentences.size, "工具插入后句子数应当是基线 + 1")
        assertEquals(baseline.size + 1, fimEntity.sentences.size, "FIM 插入后句子数应当是基线 + 1")

        // **核心断言**：把新插的那一句拿掉，剩下的与基线逐字相同。
        // 这一条就是"只增不改"的证据——它不是提示词求来的，是数据模型给的。
        assertEquals(
            baseline,
            toolEntity.sentences.toMutableList().apply { removeAt(toolInsertion.afterIndex) },
            "工具插入动到了已有句子",
        )
        assertEquals(
            baseline,
            fimEntity.sentences.toMutableList().apply { removeAt(fimInsertion.afterIndex) },
            "FIM 插入动到了已有句子",
        )

        // 两条路都产出了一句（而不是一段）——守卫若报"含 N 个句子"就是这条失败。
        assertTrue(toolInsertion.sentence.isNotBlank(), "工具这条路没有产出句子")
        assertTrue(fimInsertion.sentence.isNotBlank(), "FIM 这条路没有产出句子")
    }

    private data class Row(val step: String, val outcome: SpikeRenderer.Outcome, val requestsSoFar: Int)

    private fun report(
        rows: List<Row>,
        baseline: List<String>,
        toolInsertion: Insertion,
        toolViolations: List<String>,
        toolEntity: SpikeEntity,
        fimInsertion: Insertion,
        fimViolations: List<String>,
        fimEntity: SpikeEntity,
    ) {
        println()
        println("═".repeat(100))
        println("spike 第二轮：描述按句固化 → 两个写入器在同一插入点各补一句")
        println("═".repeat(100))

        println("%-26s %-8s %8s %14s %8s".format("步骤", "固化命中", "耗时ms", "输入(命中/未命中)", "输出"))
        println("─".repeat(100))
        for (row in rows) {
            val usage = row.outcome.usage
            println(
                "%-26s %-8s %8d %14s %8s".format(
                    row.step,
                    if (row.outcome.fromCache) "是" else "否",
                    row.outcome.elapsedMs,
                    if (usage == null) "—" else "${usage.promptCacheHitTokens}/${usage.promptCacheMissTokens}",
                    if (usage == null) "—" else "${usage.completionTokens}",
                ),
            )
        }
        val billed = rows.mapNotNull { it.outcome.usage }
        if (billed.isNotEmpty()) {
            println("─".repeat(100))
            println("整段渲染合计：成本 高峰 %.5f 元 / 空闲 %.5f 元；平均 %.0f ms/次".format(
                billed.sumOf { costCny(it, peak = true) },
                billed.sumOf { costCny(it, peak = false) },
                rows.filter { it.outcome.usage != null }.map { it.outcome.elapsedMs }.average(),
            ))
        }

        println()
        println("【基线】${baseline.size} 句：")
        baseline.forEachIndexed { index, sentence -> println("  ${index + 1}. $sentence") }

        println()
        println("─".repeat(100))
        println("【对比】同一个插入点：第 1 句之后")
        println("─".repeat(100))
        println("▸ 工具插入（${toolInsertion.elapsedMs} ms，位置 ${toolInsertion.afterIndex}）")
        println("  新句：${toolInsertion.sentence}")
        if (toolViolations.isNotEmpty()) println("  守卫：${toolViolations.joinToString("；")}")
        toolEntity.sentences.forEachIndexed { index, sentence ->
            println("  ${index + 1}. ${if (index == toolInsertion.afterIndex) "★ $sentence" else sentence}")
        }

        println()
        println("▸ FIM 插入（${fimInsertion.elapsedMs} ms，位置 ${fimInsertion.afterIndex}）")
        println("  新句：${fimInsertion.sentence}")
        if (fimViolations.isNotEmpty()) println("  守卫：${fimViolations.joinToString("；")}")
        fimEntity.sentences.forEachIndexed { index, sentence ->
            println("  ${index + 1}. ${if (index == fimInsertion.afterIndex) "★ $sentence" else sentence}")
        }
        println("═".repeat(100))
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
            id = "tavern_hall", kind = "地点", label = "酒馆大厅", x = 10, y = 0, z = 0,
            attributes = mapOf("层高" to "4米", "地面" to "夯土", "光照" to "几盏油灯"),
            // 导演写的背景。渲染器读它，好让描述不脱离这个世界。
            background = """
                这里是「醉猫」酒馆，边境小镇灰渡唯一还在营业的酒馆，木石结构，建成约二十年，墙角常年返潮，北侧的窗子有一扇关不严。
                三天前镇东的粮仓失火，烧掉了半个冬天的存粮；酒馆老板在那之后就没再露面，如今由一个年长的伙计照看店面。
                眼下是深秋傍晚，屋里点了四盏油灯，客人比往常少，靠门的两张桌子空着。
            """.trimIndent(),
        ),
        SpikeEntity(
            id = "oak_table", kind = "家具", label = "木桌", x = 12, y = 4, z = 0,
            parent = "tavern_hall",
            attributes = mapOf("材质" to "橡木", "状态" to "完好", "尺寸" to "2米 × 1米 × 0.8米"),
        ),
        SpikeEntity(
            id = "wine_cup", kind = "器具", label = "酒杯", x = 12, y = 4, z = 1,
            parent = "oak_table",
            attributes = mapOf("材质" to "深色木制", "内容物" to "半杯麦酒", "状态" to "杯口有划痕"),
        ),
    )
}
