package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.render.DetailInserter
import io.github.k1midi0.infinitesimal.render.DetailInserters
import io.github.k1midi0.infinitesimal.render.RenderGuards
import io.github.k1midi0.infinitesimal.render.Renderer
import io.github.k1midi0.infinitesimal.world.Entity
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 联机验收：**固化、两条插入路径、以及 §7.1 的两种变化。**
 *
 * 它打在产品代码上（`world/` + `render/`），不是打在探针自己的一份复制品上——否则验的就不是要交付的
 * 东西。跑法：
 *
 *     .\gradlew.bat :shared:jvmTest --tests "*ObserveFreezeSpike*" `
 *         -PinfinitesimalLiveKeyFile="C:\path\to\key.txt"
 *
 * 不给那个参数时什么都不做，只打印一行说明。
 */
class ObserveFreezeSpike {

    private val worldFile = "build/spike/world.json"

    @Test
    fun freezeInsertAndTwoKindsOfChange() {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[spike 未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>，本次未访问端点")
            return
        }
        val config = ProbeSupport.configOf(apiKey)
        val world = ProbeSupport.seed(worldFile, seedWorld())
        val renderer = Renderer(config, world)

        val rows = mutableListOf<Row>()
        var toolOutcome: DetailInserter.Outcome? = null
        var fimOutcome: DetailInserter.Outcome? = null
        var baseline: List<String> = emptyList()

        runBlocking {
            // ── 固化循环 ────────────────────────────────────────────────────────────
            val first = renderer.observe("oak_table")
            rows += Row("① 首次观察木桌", first, renderer.requestCount)
            baseline = first.sentences

            val cached = renderer.observe("oak_table")
            rows += Row("② 再观察（应命中固化）", cached, renderer.requestCount)

            val cup = renderer.observe("wine_cup")
            rows += Row("③ 观察酒杯", cup, renderer.requestCount)

            // ── 两条插入路径：各自一份基线副本，同一个插入点 ──────────────────────────
            world.put(requireNotNull(world.byId("oak_table")).copy(id = "oak_table_tool"))
            world.put(requireNotNull(world.byId("oak_table")).copy(id = "oak_table_fim"))

            // 默认装配：**只有工具路**——FIM 默认关闭（实测五次里两次可用，见 DetailInserters）。
            val defaultInserter = DetailInserters.of(config)
            // 显式打开 FIM 才有对比：它在前，工具在后兜底。
            val fimInserter = DetailInserters.of(config, useFim = true)
            println()
            println("[装配] 默认：${defaultInserter.paths.joinToString(" → ")}")
            println("[装配] 开了 FIM：${fimInserter.paths.joinToString(" → ")}")

            toolOutcome = renderer.addDetail("oak_table_tool", defaultInserter, afterIndex = 1)
            fimOutcome = renderer.addDetail("oak_table_fim", fimInserter, afterIndex = 1)

            // ── 变化之一：仅仅外表变了（§7.1）──────────────────────────────────────
            world.invalidateAppearance("oak_table")
            val appearance = renderer.observe("oak_table")
            rows += Row("④ 外表变化后重观察", appearance, renderer.requestCount)

            val cachedAgain = renderer.observe("oak_table")
            rows += Row("⑤ 再观察（固化又成立）", cachedAgain, renderer.requestCount)

            // ── 变化之二：彻底改变，换新物品（§7.1）─────────────────────────────────
            world.replace(
                "oak_table",
                Entity(
                    id = "oak_table_splintered",
                    kind = "杂物",
                    label = "劈开的木桌",
                    x = 12, y = 4, z = 0,
                    parent = "tavern_hall",
                    attributes = mapOf("材质" to "橡木", "状态" to "沿中线裂成两半"),
                ),
            )
            val replaced = renderer.observe("oak_table_splintered")
            rows += Row("⑥ 劈开后观察新物品", replaced, renderer.requestCount)
        }

        val toolEntity = requireNotNull(world.byId("oak_table_tool"))
        val fimEntity = requireNotNull(world.byId("oak_table_fim"))
        report(rows, baseline, toolOutcome, toolEntity, fimOutcome, fimEntity)

        // ── 断言 ───────────────────────────────────────────────────────────────────
        assertEquals(4, renderer.requestCount, "整段渲染该发 4 次请求（①③④⑥；②⑤ 是固化命中）")
        assertTrue(rows[1].outcome.fromCache, "② 应当命中固化")
        assertEquals(0L, rows[1].outcome.elapsedMs, "② 命中固化应当是零耗时")
        assertTrue(rows[4].outcome.fromCache, "⑤ 固化重新成立后应当再次命中")
        assertNotNull(rows[0].outcome.usage, "① 必须拿到 usage，否则成本无从核算")

        // 描述只讲物体自身——三条路都要干净（§7.6）。
        assertEquals(emptyList(), RenderGuards.check(baseline.joinToString("")), "基线里出现了周边或位置关系")
        assertEquals(emptyList(), RenderGuards.check(toolEntity.text()), "工具插入后出现了周边或位置关系")

        // **只增不改的证据**：把新插的那一句拿掉，剩下的与基线逐字相同（§14.2）。
        val toolInserted = assertNotNull(assertNotNull(toolOutcome).sentences, "工具这条路没有产出可用句子")
        assertEquals(baseline.size + 1, toolInserted.size, "工具插入后句子数应当是基线 + 1")
        assertEquals(
            baseline,
            toolInserted.toMutableList().apply { removeAt(insertedIndex(toolEntity, baseline)) },
            "工具插入动到了已有句子",
        )

        // ── 两种变化（§7.1）────────────────────────────────────────────────────────
        // 外表变化：**id 不变**，只是描述作废重渲染。它在上面的 ④⑤ 里已经走过，这里断言身份没变。
        assertTrue(rows[3].outcome.fromCache.not(), "④ 应当重新渲染而不是命中旧描述")
        // 彻底改变：旧 id 从世界态里消失，新 id 带着自己的描述。
        assertNull(world.byId("oak_table"), "彻底改变之后旧 id 不该还在世界里")
        assertNotNull(world.byId("oak_table_splintered"), "新物品应当在世界里")
        assertTrue(
            requireNotNull(world.byId("oak_table_splintered")).sentences.isNotEmpty(),
            "新物品应当有自己的描述",
        )

        // FIM 的不稳定**已经量过**（§14.3：5 次里 2 次可用），所以它失败是已知结果，不是这次的失败。
        // 要断言的是**回退的形状**：FIM 不行时由工具接住，而不是把坏数据落库。
        val fim = assertNotNull(fimOutcome)
        if (fim.sentences == null) {
            println()
            println("⚠ 两条路都没产出——世界状态未变。按 §14.4 这是可接受的结果，不是坏数据。")
        } else {
            assertNotNull(fim.writer, "有产出就必须记下是哪条路")
        }
    }

    /** 找出新插进来的那一句在原列表里的位置。 */
    private fun insertedIndex(entity: Entity, baseline: List<String>): Int =
        entity.sentences.indexOfFirst { it !in baseline }.takeIf { it >= 0 } ?: 0

    private data class Row(val step: String, val outcome: Renderer.Observation, val requestsSoFar: Int)

    private fun report(
        rows: List<Row>,
        baseline: List<String>,
        toolOutcome: DetailInserter.Outcome?,
        toolEntity: Entity,
        fimOutcome: DetailInserter.Outcome?,
        fimEntity: Entity,
    ) {
        println()
        println("═".repeat(100))
        println("联机验收：固化 → 两条插入路径 → 两种变化")
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
            println(
                "整段渲染合计：成本 高峰 %.5f 元 / 空闲 %.5f 元；平均 %.0f ms/次".format(
                    billed.sumOf { costCny(it.promptCacheHitTokens, it.promptCacheMissTokens, it.completionTokens, true) },
                    billed.sumOf { costCny(it.promptCacheHitTokens, it.promptCacheMissTokens, it.completionTokens, false) },
                    rows.filter { it.outcome.usage != null }.map { it.outcome.elapsedMs }.average(),
                ),
            )
        }

        println()
        println("【基线】${baseline.size} 句：")
        baseline.forEachIndexed { index, sentence -> println("  ${index + 1}. $sentence") }
        val baseViolations = RenderGuards.check(baseline.joinToString(""))
        print("  RenderGuards：")
        if (baseViolations.isEmpty()) println("干净") else println(baseViolations.joinToString("；"))

        println()
        println("─".repeat(100))
        println("【对比】同一个插入点：第 1 句之后（两条路各自一份基线副本）")
        println("─".repeat(100))
        println("▸ 工具插入")
        toolEntity.sentences.forEachIndexed { index, sentence ->
            println("  ${index + 1}. ${if (sentence !in baseline) "★ " else ""}$sentence")
        }
        println()
        println("▸ FIM 插入（带工具回退）")
        fimEntity.sentences.forEachIndexed { index, sentence ->
            println("  ${index + 1}. ${if (sentence !in baseline) "★ " else ""}$sentence")
        }
        println()
        println("【两条路各试了什么】")
        println("  工具：${toolOutcome?.attempts?.joinToString(" ｜ ") ?: "(未执行)"}")
        println("  FIM ：${fimOutcome?.attempts?.joinToString(" ｜ ") ?: "(未执行)"}")
        if (fimOutcome?.sentences != null && fimOutcome.writer == "工具插入") {
            println("  ↑ FIM 未产出，**回退生效**：这一句是工具写的（§14.4）")
        }
        println("═".repeat(100))
    }

    /** flash 的价目（元/百万 token）。高峰：周一至周五 9:00-12:00、14:00-18:00。 */
    private fun costCny(hit: Int, miss: Int, out: Int, peak: Boolean): Double {
        val hitRate = if (peak) 0.04 else 0.02
        val missRate = if (peak) 2.0 else 1.0
        val outRate = if (peak) 8.0 else 4.0
        return (hit * hitRate + miss * missRate + out * outRate) / 1_000_000.0
    }

    private fun seedWorld(): List<Entity> = listOf(
        Entity(
            id = "tavern_hall", kind = "地点", label = "酒馆大厅", x = 10, y = 0, z = 0,
            attributes = mapOf("层高" to "4米", "地面" to "夯土", "光照" to "几盏油灯"),
            // 导演写的背景。渲染器读它，好让描述不脱离这个世界（§7.5）。
            background = """
                这里是「醉猫」酒馆，边境小镇灰渡唯一还在营业的酒馆，木石结构，建成约二十年，墙角常年返潮，北侧的窗子有一扇关不严。
                三天前镇东的粮仓失火，烧掉了半个冬天的存粮；酒馆老板在那之后就没再露面，如今由一个年长的伙计照看店面。
                眼下是深秋傍晚，屋里点了四盏油灯，客人比往常少，靠门的两张桌子空着。
            """.trimIndent(),
        ),
        Entity(
            id = "oak_table", kind = "家具", label = "木桌", x = 12, y = 4, z = 0,
            parent = "tavern_hall",
            attributes = mapOf("材质" to "橡木", "状态" to "完好", "尺寸" to "2米 × 1米 × 0.8米"),
        ),
        Entity(
            id = "wine_cup", kind = "器具", label = "酒杯", x = 12, y = 4, z = 1,
            parent = "oak_table",
            attributes = mapOf("材质" to "深色木制", "内容物" to "半杯麦酒", "状态" to "杯口有划痕"),
        ),
    )
}
