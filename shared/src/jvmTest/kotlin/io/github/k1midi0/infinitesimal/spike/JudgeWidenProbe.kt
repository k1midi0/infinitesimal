package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * 「看大一点」在真实端点上的行为，攒样本用。
 *
 * ## 它要回答的那一个问题
 *
 * `ViewBudget` **不封顶**（只记数、不设限）。于是「三条街外」那条驳回从 ALIGN 步 997 ms 退成了
 * EFFECT 步 5270 ms——模型现在有理由先扩一眼再判。一次读数不能当结论，所以这里把同一批场景
 * 跑 [rounds] 轮，每轮**开一个全新的世界**。
 *
 * ## 为什么每轮都要新世界
 *
 * 因为判定是**会改世界**的：`JudgeProbeSpike` 把六个案例串在同一个世界上按顺序跑（那是有意的，
 * 它验的是"判定读的是当前世界"）。这里问的是另一个问题——「同一句话，重复问，答案稳不稳」。
 * 世界一旦被前一轮改过，第二轮问的就不是同一件事了。
 *
 * ## 四个场景各自钉的是什么
 *
 * - 甲：嘴上说的目标**根本不存在**。它扩过就是白扩——这是「模型乱扩」的直接证据。
 * - 乙：目标真的存在，但**正好差一格在框外**（扫帚在 (16,3)，默认那一片是 x 9..15）。
 *   这是唯一一个"扩了才有答案"的局面。
 * - 丙：目标在**手边**（桌上的酒杯）。对照组——它不该扩。
 * - 丁：目标在**八格开外**（井边的杯子）。扩一次够不够，看这一行。
 *
 * 跑法：`-PinfinitesimalLiveKeyFile=<路径> --tests "*JudgeWidenProbe*"`
 */
class JudgeWidenProbe {

    private val rounds = 5

    /** 一轮里的一个读数。 */
    private data class Row(
        val scenario: String,
        val round: Int,
        val requests: Int,
        val widenings: Int,
        /** 模型填了哪些方向。`—` 表示一次都没扩。 */
        val asked: String,
        /** 第一步报了什么。`—` 是驳回或重试用尽，`（无）` 是一个都没报。 */
        val aligned: String,
        val outcome: String,
        val ms: Long,
        val detail: String,
    )

    private class Scenario(
        val name: String,
        val utterance: String,
        /** 这个场景额外放进世界的东西。 */
        val extra: List<Entity>,
    )

    private val scenarios = listOf(
        Scenario("甲 三条街外（不存在）", "我伸手把三条街外那家店里的杯子拿过来。", emptyList()),
        Scenario(
            "乙 差一格在框外",
            "我拿起靠在墙上那把扫帚。",
            listOf(Entity("broom", "器具", "靠在墙上的扫帚", 1600, 300, ProbeSupport.GROUND)),
        ),
        Scenario("丙 手边（对照）", "我拿起桌上那杯麦酒。", emptyList()),
        // **只落在发起者自己身上的动作。** 它验的是「发起者本人也是对象」那条新规则：第一步该报
        // 玩家自己，而不是报空（报空在代码里等于协议内失败）。顺带把它第二步的产物暴露出来——
        // 这类动作往往**没有后果**，而「没有后果」现在是一条协议内失败。
        Scenario("戊 只落在自己身上", "我低头理了理衣襟。", emptyList()),
        Scenario(
            "丁 八格开外",
            "我把街心那口井边放着的杯子拿过来。",
            listOf(
                Entity("town_well", "地点", "街心的水井", 2000, 800, 0, attributes = mapOf("井水" to "冰凉")),
                // 井沿一米高，杯子搁在井沿上。
                Entity("well_cup", "器具", "粗陶杯", 2000, 800, 100),
            ),
        ),
    )

    @Test
    fun widenBehaviour() {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[widen 探针未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }
        val judge = Judge(ProbeSupport.configOf(apiKey))
        val rows = mutableListOf<Row>()

        runBlocking {
            println()
            println("═".repeat(112))
            println("「看大一点」重复采样：${scenarios.size} 个场景 × $rounds 轮，每轮开新世界")
            println("═".repeat(112))
            println(
                "%-22s %-4s %-6s %-6s %-16s %-18s %-14s %-8s %s".format(
                    "场景", "轮", "请求", "看大", "填的方向", "第一步报的", "结果", "耗时", "细节",
                ),
            )
            println("─".repeat(132))

            repeat(rounds) { r ->
                for (s in scenarios) {
                    // 每轮一个全新世界：判定会改世界，不隔离的话第二轮问的就不是同一件事。
                    val world = World.from(WorldSnapshot(seedWorld() + s.extra))
                    val requestsBefore = judge.requestCount
                    val mark = TimeSource.Monotonic.markNow()
                    val judgment = judge.judgeAndCommit(
                        world,
                        JudgeRequest(
                            actorId = "player",
                            actorLabel = "你",
                            intention = s.utterance,
                            selected = emptyList(),
                            centers = emptyList(),
                        ),
                    )
                    val ms = mark.elapsedNow().inWholeMilliseconds
                    val row = Row(
                        scenario = s.name,
                        round = r + 1,
                        requests = judge.requestCount - requestsBefore,
                        widenings = judge.viewWidenings,
                        asked = askedOf(judge),
                        aligned = alignedOf(judge),
                        outcome = outcomeOf(judgment),
                        ms = ms,
                        detail = detailOf(judgment),
                    )
                    rows += row
                    println(
                        "%-22s %-4d %-6d %-6d %-16s %-18s %-14s %-8s %s".format(
                            row.scenario, row.round, row.requests, row.widenings, row.asked,
                            row.aligned, row.outcome, "${row.ms}ms", row.detail,
                        ),
                    )
                }
            }
            println("─".repeat(112))
        }

        aggregate(rows)
    }

    /** 这一次判定里模型填了哪些方向。**`—` 是一次都没扩。** */
    private fun askedOf(judge: Judge): String =
        judge.viewDirections.joinToString("、") { it?.label ?: "整体" }.ifEmpty { "—" }

    /**
     * 第一步**报了什么**。**`—` 是驳回或重试用尽；`（无）` 是一个都没报。**
     *
     * 「报空」与「驳回」在结果那一列长得一样（都让第二步白跑一趟），所以必须分开看。
     */
    private fun alignedOf(judge: Judge): String = when (val a = judge.alignedTargets) {
        null -> "—"
        else -> a.ifEmpty { listOf("（无）") }.joinToString("、")
    }

    private fun outcomeOf(j: Judgment): String = when (j) {
        is Judgment.Applied -> "成立"
        is Judgment.RejectedWorld -> "驳回@${j.stage}"
        is Judgment.FailedProtocol -> "协议失败"
        is Judgment.Nothing -> "无对象"
        is Judgment.Unchanged -> "成立·没改变"
    }

    private fun detailOf(j: Judgment): String = when (j) {
        is Judgment.Applied -> "对齐 ${j.targets.ifEmpty { listOf("（无）") }.joinToString("、")}｜后果 ${j.consequences.size} 条"
        is Judgment.RejectedWorld -> j.reason.replace('\n', ' ').take(46)
        is Judgment.FailedProtocol -> j.problems.firstOrNull().orEmpty().take(46)
        is Judgment.Nothing -> "第一步一个目标都没报，第二步没有跑"
        is Judgment.Unchanged -> "对齐 ${j.targets.ifEmpty { listOf("（无）") }.joinToString("、")}｜${j.note}".replace('\n', ' ').take(46)
    }

    /** 每个场景一行汇总。**要看的就三件**：扩过几次、在哪一步驳回、耗时的中位数。 */
    private fun aggregate(rows: List<Row>) {
        println()
        println("═".repeat(112))
        println("汇总（每个场景 $rounds 次）")
        println("═".repeat(112))
        println(
            "%-22s %-9s %-11s %-11s %-11s %-8s %-8s %s".format(
                "场景", "扩过", "填报了方向", "驳回@ALIGN", "驳回@EFFECT", "成立", "没改变", "耗时中位/最大",
            ),
        )
        println("─".repeat(118))
        for (s in scenarios) {
            val mine = rows.filter { it.scenario == s.name }
            val sorted = mine.map { it.ms }.sorted()
            val median = sorted[sorted.size / 2]
            println(
                "%-22s %-9s %-11s %-11s %-11s %-8s %-8s %s".format(
                    s.name,
                    "${mine.count { it.widenings > 0 }}/${mine.size}",
                    "${mine.count { it.asked != "—" }}/${mine.size}",
                    mine.count { it.outcome == "驳回@ALIGN" },
                    mine.count { it.outcome == "驳回@EFFECT" },
                    mine.count { it.outcome == "成立" },
                    mine.count { it.outcome == "成立·没改变" },
                    "$median / ${sorted.last()} ms",
                ),
            )
        }
        println("─".repeat(118))
        val all = rows.map { it.ms }.sorted()
        val widenTotal = rows.sumOf { it.widenings }
        val asked = rows.flatMap { it.asked.split("、") }.filter { it != "—" }
        println(
            "全部 ${rows.size} 次：扩过 ${rows.count { it.widenings > 0 }} 次｜累计看大 $widenTotal 次｜" +
                "总耗时 ${all.sum() / 1000} 秒｜单次 ${all.first()}–${all.last()} ms",
        )
        println("模型这 $widenTotal 次扩大里填了方向的：${asked.size} 次" + if (asked.isEmpty()) {
            "——**一个都没填**"
        } else {
            "（${asked.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                .joinToString("、") { "${it.key} ${it.value} 次" }}）"
        })
        println("═".repeat(118))
    }

    /** 与世界其余探针同一份种子，好让读数能横向比。**放在 `ProbeSupport` 里，不在这里。** */
    private fun seedWorld(): List<Entity> = ProbeSupport.tavernSeed()
}
