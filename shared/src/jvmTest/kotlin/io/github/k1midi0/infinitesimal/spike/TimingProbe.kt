package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.RequestTime
import io.github.k1midi0.infinitesimal.judge.Stage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * **钱花在哪**——延迟第一次被拆开量。
 *
 * ## 为什么要有这一支
 *
 * 在此之前，手里关于延迟的读数只有"这一轮一共几秒"（§14.17：中位 3–7 秒、尾部 21 秒）。那是**一个
 * 总数**，它回答不了任何该动手的问题：第一步还是第二步贵？重试吃掉了多少？那 21 秒是模型在想，
 * 还是在等端点排队？三种病因、三种治法，而总数把它们混成一样。
 *
 * 这一支读 [Judge.timing]（每次模型调用一条，拆成"等"与"生成"两截），问三个问题：
 *
 * 1. **两步各占多少。** 第一步是 `ThinkingMode.Never`、第二步是 `Always`——如果第二步占九成，
 *    那要治的就是它的提示词与档位，而不是"判定慢"。
 * 2. **重试吃掉了多少。** 一次重试就是一整次调用。如果它占比可观，那治的是"模型第一次为什么
 *    没照做"，而那是一条能用测试钉住的提示词问题——不是玄学。
 * 3. **等与生成各占多少。** 等的那一截（发请求 → 第一个事件）与模型想多久无关：它是排队、预填充、
 *    以及端点那边的调度。那一截占得多，改提示词一点用都没有。
 *
 * ## 三个场景
 *
 * 甲最轻（只落在自己身上）、乙是常态（手边的东西）、丙应当被驳回（三条街以外）。**三种结果各一条
 * 路**，因为驳回、成立、无对象花的时间完全不同。三个都**不会触发建模**——建模是一次几十秒的独立
 * 调用（§14.21），混进来会把账搅浑，它单独量。
 *
 * 跑法：`.\gradlew.bat :shared:jvmTest --tests "*TimingProbe*" --rerun -PinfinitesimalLiveKeyFile="<路径>"`
 * 输出从 `shared/build/test-results/jvmTest/TEST-*TimingProbe*.xml` 的 `system-out` 读。
 */
class TimingProbe {

    private val rounds = 4

    private data class Row(
        val scenario: String,
        val round: Int,
        val outcome: String,
        val requests: Int,
        val alignMs: Long,
        val effectMs: Long,
        val waitingMs: Long,
        val thinkingMs: Long,
        val retries: Int,
        val queryRounds: Int,
        val wallMs: Long,
    )

    private class Scenario(val name: String, val utterance: String)

    private val scenarios = listOf(
        Scenario("甲 只落在自己身上", "我低头理了理衣襟。"),
        Scenario("乙 手边的东西", "我把桌上那杯麦酒端起来。"),
        Scenario("丙 三条街以外", "我把三条街外那家店里的杯子拿过来。"),
    )

    @Test
    fun whereTheTimeGoes() = runBlocking {
        val key = ProbeSupport.keyOrNull()
        if (key == null) {
            println("没有密钥（-PinfinitesimalLiveKeyFile），跳过。")
            return@runBlocking
        }
        val config = ProbeSupport.configOf(key)

        val rows = mutableListOf<Row>()
        val every = mutableListOf<RequestTime>()

        for (scenario in scenarios) {
            for (round in 1..rounds) {
                // **每轮一个新世界。** 判定是会改世界的，串在同一个世界上第二轮问的就不是同一件事。
                val world = World.from(WorldSnapshot(StartingWorld.tavern()))
                val judge = Judge(LlmClient(config))
                var judgment: Any? = null
                val wall = measureTimeMillis {
                    judgment = judge.judgeAndCommit(world, request(scenario.utterance))
                }
                val t = judge.timing
                every += t.all
                val row = Row(
                    scenario = scenario.name,
                    round = round,
                    outcome = outcomeName(judgment),
                    requests = t.requests,
                    alignMs = t.totalMs(Stage.ALIGN),
                    effectMs = t.totalMs(Stage.EFFECT),
                    waitingMs = t.waitingMs,
                    thinkingMs = t.thinkingMs,
                    retries = t.retries,
                    queryRounds = t.queryRounds,
                    wallMs = wall,
                )
                rows += row
                println(
                    "  ${pad(row.scenario, 20)} 第 $round 轮  ${pad(row.outcome, 10)}" +
                        "  请求 ${row.requests}" +
                        "  等 ${pad(row.waitingMs.toString(), 6)} 生成 ${pad(row.thinkingMs.toString(), 6)}" +
                        "  共 ${pad(row.wallMs.toString(), 6)} ms" +
                        "  重试 ${row.retries} 查询 ${row.queryRounds}",
                )
            }
        }

        println("\n════════ 逐轮明细 ════════")
        for (r in rows) {
            println(
                "  ${pad(r.scenario, 20)} 第 ${r.round} 轮" +
                    "  第一步 ${pad(r.alignMs.toString(), 6)}  第二步 ${pad(r.effectMs.toString(), 6)}" +
                    "  共 ${pad(r.wallMs.toString(), 6)} ms  ${r.outcome}",
            )
        }

        println("\n════════ 合计（${rows.size} 次判定）════════")
        val total = rows.sumOf { it.wallMs }
        val align = rows.sumOf { it.alignMs }
        val effect = rows.sumOf { it.effectMs }
        val waiting = rows.sumOf { it.waitingMs }
        val thinking = rows.sumOf { it.thinkingMs }
        println("  墙钟合计 $total ms（判定内部合计 ${rows.sumOf { it.alignMs + it.effectMs }} ms）")
        println("  第一步 $align ms（${pct(align, total)}）　第二步 $effect ms（${pct(effect, total)}）")
        println("  等 $waiting ms（${pct(waiting, total)}）　生成 $thinking ms（${pct(thinking, total)}）")
        println("  调用 ${every.size} 次　重试 ${rows.sumOf { it.retries }} 次　查询多要 ${rows.sumOf { it.queryRounds }} 轮")
        println("  单次调用中位 ${median(every.map { it.elapsedMs })} ms，最慢 ${every.maxOf { it.elapsedMs }} ms")
        println("  单次判定中位 ${median(rows.map { it.wallMs })} ms，最慢 ${rows.maxOf { it.wallMs }} ms")

        // **这一段才是这次探针真正的产出。** "等 / 生成"那个二分法在这条端点上名不副实：整条回复是
        // 攒到最后一次吐出来的，于是"等 9088 ms"里同时装着排队、预填充和整条思维链。能把它拆开的
        // 只有 token：思维链 token × 端点的吐字速度 ≈ 模型思考真正花掉的时间，剩下的才是排队。
        val reasoning = every.sumOf { it.reasoningTokens }
        val out = every.sumOf { it.completionTokens }
        val prompt = every.sumOf { it.promptTokens }
        val cacheHit = every.sumOf { it.cacheHitTokens }
        println("\n════════ 用量（拆「等」那一截的钥匙）════════")
        println("  输入 $prompt token（缓存命中 $cacheHit，命中率 ${pct(cacheHit.toLong(), prompt.toLong())}）")
        println("  输出 $out token，其中思维链 $reasoning（${pct(reasoning.toLong(), out.toLong())}）")
        println("  实测吞吐 ${rate(out, every.sumOf { it.elapsedMs })} token/s（含思维链）")
        println("  若吞吐按 100 token/s 算，思维链本身约 ${reasoning * 1000 / 100} ms" +
            "，占墙钟的 ${pct((reasoning * 1000 / 100).toLong(), total)}")

        println("\n════════ 每一次调用（按发生顺序）════════")
        every.forEachIndexed { i, c ->
            println(
                "  ${pad((i + 1).toString(), 3)} ${pad(c.stage.name, 6)} 尝试 ${c.attempt} 第 ${c.round} 来回" +
                    "  共 ${pad(c.elapsedMs.toString(), 6)} ms" +
                    "  入 ${pad(c.promptTokens.toString(), 6)}（中 ${pad(c.cacheHitTokens.toString(), 6)}）" +
                    "  出 ${pad(c.completionTokens.toString(), 5)}（思 ${pad(c.reasoningTokens.toString(), 5)}）" +
                    "  ${pad(rate(c.completionTokens, c.elapsedMs), 7)} token/s" +
                    "  ${c.tools.joinToString("+").ifEmpty { "（只说了话）" }}",
            )
        }

        println("\n════════ 最慢的六次调用 ════════")
        every.sortedByDescending { it.elapsedMs }.take(6).forEach {
            println(
                "  ${pad(it.stage.name, 6)} 尝试 ${it.attempt} 第 ${it.round} 来回" +
                    "  等 ${pad(it.firstEventMs.toString(), 6)} 生成 ${pad(it.thinkingMs.toString(), 6)}" +
                    "  共 ${pad(it.elapsedMs.toString(), 6)} ms" +
                    "  出 ${pad(it.completionTokens.toString(), 5)}（思 ${pad(it.reasoningTokens.toString(), 5)}）" +
                    "  ${pad(rate(it.completionTokens, it.elapsedMs), 7)} token/s" +
                    "  ${it.tools.joinToString("+").ifEmpty { "（只说了话）" }}",
            )
        }

        println("\n════════ 按场景 ════════")
        for (scenario in scenarios) {
            val mine = rows.filter { it.scenario == scenario.name }
            val calls = everyFor(every, rows, scenario.name)
            println(
                "  ${pad(scenario.name, 20)}" +
                    "  墙钟中位 ${pad(median(mine.map { it.wallMs }).toString(), 6)}" +
                    "  最慢 ${pad(mine.maxOf { it.wallMs }.toString(), 6)} ms" +
                    "  等 ${pad(calls.sumOf { it.firstEventMs }.toString(), 6)}" +
                    "  生成 ${pad(calls.sumOf { it.thinkingMs }.toString(), 6)}" +
                    "  调用 ${calls.size}",
            )
        }
    }

    /**
     * 把每一次调用按场景归堆。
     *
     * 归堆靠的是顺序：`every` 是按场景、按轮次追加的，所以第 i 个场景占的是连续的一段。
     * 这里不用更聪明的方法——**归错堆会让整张表都是错的**，而顺序法是唯一不需要额外记账的。
     */
    private fun everyFor(every: List<RequestTime>, rows: List<Row>, name: String): List<RequestTime> {
        var at = 0
        for (scenario in scenarios) {
            val count = rows.count { it.scenario == scenario.name }
            val callsPerRound = rows.filter { it.scenario == scenario.name }.map { it.requests }
            val span = callsPerRound.sum()
            if (scenario.name == name) return every.subList(at, at + span)
            at += span
        }
        return emptyList()
    }

    private fun request(intention: String) = JudgeRequest(
        actorId = "player",
        actorLabel = "你",
        intention = intention,
        selected = emptyList(),
        centers = emptyList(),
    )

    private fun outcomeName(judgment: Any?): String = when (judgment) {
        is io.github.k1midi0.infinitesimal.judge.Judgment.Applied -> "成立"
        is io.github.k1midi0.infinitesimal.judge.Judgment.Unchanged -> "成立未变"
        is io.github.k1midi0.infinitesimal.judge.Judgment.RejectedWorld -> "驳回"
        is io.github.k1midi0.infinitesimal.judge.Judgment.Nothing -> "无对象"
        is io.github.k1midi0.infinitesimal.judge.Judgment.FailedProtocol -> "协议失败"
        else -> "（没有结果）"
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun pct(part: Long, whole: Long): String =
        if (whole == 0L) "0%" else "${part * 100 / whole}%"

    /** 吐字速度，一位小数。**它是"瓶颈在不在模型吞吐上"的判据。** */
    private fun rate(tokens: Int, ms: Long): String =
        if (ms <= 0L) "—" else ((tokens * 1000.0 / ms) * 10).toInt().let { "${it / 10}.${it % 10}" }

    private fun pad(text: String, width: Int) = text.padEnd(width)
}
