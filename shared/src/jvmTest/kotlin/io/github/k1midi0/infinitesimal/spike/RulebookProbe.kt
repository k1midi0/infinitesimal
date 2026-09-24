package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.judge.Consequence
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.RuleEntry
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.World
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * 规则书模块在真实端点上的验收（§14.19）。
 *
 * ## 离线测不到的那三件事
 *
 * `RulebookViewTest` 只钉得住代码那一半：排序、分片、覆盖、空片不出现。剩下三条只有联机能回答，
 * 而它们正是这个模块值不值的全部：
 *
 *  1. **冲突时它采信哪一条。** 世界规则（优先级 10）说"火会烧起来"，木桌自己的规则（优先级 30）
 *     说"这张桌子上过防火漆，点不着"。**桌子不该被点着**——而丙是同一句话、同一个世界，
 *     只少了规则书，所以两行能直接对着看。
 *  2. **它会不会用 `calc`。** 这只工具存在的理由只有一个：心算错了看不出来。
 *  3. **`no_change` 有没有真的省掉那次重试。** 丁在 §14.18 里中位 6669 ms、请求 2–3 次。
 *
 * ## 它顺带暴露的那个旋钮
 *
 * 每一行都记 `reasoning_tokens` 与 `finish_reason`。§14.19 第一次跑（第二步上限 2000）时，四分之一
 * 的第二步**一个工具都没调**——那时这两个字段还没被留下来，"被截断"和"模型拒绝回答"长得一模一样。
 * 留下来之后它们是同一个旋钮的两个刻度：上限太小 → 截断；上限太大 + `high` → 单次跑两分钟。
 *
 * ## 跑法
 *
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*RulebookProbe*" --rerun `
 *   -PinfinitesimalLiveKeyFile="<密钥文件>"
 * ```
 *
 * 换第二步的思考强度：先设环境变量 `INFINITESIMAL_EFFORT=LOW`（或 `MAX`）再跑。
 */
class RulebookProbe {

    private val rounds = 4

    private data class Row(
        val scenario: String,
        val round: Int,
        val requests: Int,
        /** 这次判定里模型算过的式子。`—` 表示一次都没算。 */
        val calc: String,
        val outcome: String,
        /** 符合这个场景预期吗。判据由 [Scenario.expected] 给。 */
        val ok: Boolean,
        val reasoningTokens: Int,
        val finishReason: String,
        val ms: Long,
        val detail: String,
    )

    private class Scenario(
        val name: String,
        val utterance: String,
        /** `false` = 这个世界里一条规则书都没有，用来当对照。 */
        val ruled: Boolean,
        val extra: List<Entity> = emptyList(),
        /** 什么样的结论算"对了"。 */
        val expected: (Judgment) -> Boolean,
    )

    /**
     * 「桌子没被点着」。
     *
     * **甲与丙共用它**，所以那两行能直接对着看：规则书在，桌子就只是熏黑；规则书不在，它就烧起来。
     * 这条判据不看措辞，只看结论——`Destroy` / `Replace` / `状态=正在燃烧` 里任何一个出现，都算烧着了。
     */
    private fun tableNotBurning(j: Judgment): Boolean = when (j) {
        is Judgment.RejectedWorld -> true

        is Judgment.Applied -> j.consequences.none { c ->
            c is Consequence.Destroy ||
                c is Consequence.Replace ||
                (c is Consequence.SetAttribute && (c.value.contains("燃") || c.value.contains("烧")))
        }

        else -> false
    }

    private val scenarios = listOf(
        Scenario(
            name = "甲 规则冲突（世界10 vs 桌子30）",
            utterance = "我把油灯凑到桌面上，想把这张桌子点着。",
            ruled = true,
            expected = ::tableNotBurning,
        ),
        Scenario(
            name = "乙 够得着要算",
            utterance = "我伸手去拿靠在墙根的那把扫帚。",
            ruled = true,
            extra = listOf(Entity("broom", "器具", "靠在墙根上的扫帚", 1400, 300, ProbeSupport.GROUND)),
            expected = { it is Judgment.RejectedWorld },
        ),
        Scenario(
            name = "丙 同甲·但没有规则书（对照）",
            utterance = "我把油灯凑到桌面上，想把这张桌子点着。",
            ruled = false,
            expected = ::tableNotBurning,
        ),
        Scenario(
            name = "丁 只落在自己身上",
            utterance = "我低头理了理衣襟。",
            ruled = true,
            expected = { it is Judgment.Unchanged },
        ),
    )

    @Test
    fun rulebookBehaviour() {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[规则书探针未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }
        // **不传 `-PinfinitesimalEffort` 时用产品自己的默认**——采样表上印的是 `judge.effort`，
        // 所以表头永远不会与真实跑的那个档位不一致（第一版这里复制了一份常量，于是"默认"那一跑
        // 其实跑的是 HIGH，而表头还写着 HIGH、看起来一切正常）。
        val judge = ProbeSupport.effortFromCommandLine()
            ?.let { Judge(ProbeSupport.configOf(apiKey), effort = it) }
            ?: Judge(ProbeSupport.configOf(apiKey))
        val effort = judge.effort
        val rows = mutableListOf<Row>()

        runBlocking {
            println()
            println("═".repeat(140))
            println("规则书模块：${scenarios.size} 个场景 × $rounds 轮，每轮开新世界")
            println("第二步 thinking=enabled，reasoning_effort=$effort，**不设 max_tokens**")
            println("═".repeat(140))
            println(
                "%-28s %-4s %-6s %-14s %-14s %-8s %-9s %-8s %s".format(
                    "场景", "轮", "请求", "算过的式子", "结果", "符合预期", "推理 token", "停顿", "细节",
                ),
            )
            println("─".repeat(140))

            for (scenario in scenarios) {
                for (round in 1..rounds) {
                    val world = build(scenario)
                    val before = judge.requestCount
                    val mark = TimeSource.Monotonic.markNow()
                    val judgment = judge.judgeAndCommit(
                        world,
                        JudgeRequest(
                            actorId = "player",
                            actorLabel = "你",
                            intention = scenario.utterance,
                            selected = emptyList(),
                            centers = emptyList(),
                        ),
                    )
                    val ms = mark.elapsedNow().inWholeMilliseconds
                    val detail = detailOf(judgment)
                    val expressions = judge.calcExpressions
                    val row = Row(
                        scenario = scenario.name,
                        round = round,
                        requests = judge.requestCount - before,
                        calc = if (expressions.isEmpty()) "—" else expressions.joinToString("；"),
                        outcome = outcomeOf(judgment),
                        ok = scenario.expected(judgment),
                        reasoningTokens = judge.lastUsage?.completionTokensDetails?.reasoningTokens ?: -1,
                        finishReason = judge.lastFinishReason ?: "（没有）",
                        ms = ms,
                        detail = detail,
                    )
                    rows += row
                    println(
                        "%-28s %-4d %-6d %-14s %-14s %-8s %-9s %-8s %s".format(
                            row.scenario, row.round, row.requests, row.calc.take(14), row.outcome,
                            if (row.ok) "是" else "**否**",
                            if (row.reasoningTokens < 0) "—" else row.reasoningTokens.toString(),
                            "${ms}ms",
                            row.detail.take(40),
                        ),
                    )
                }
            }

            println("─".repeat(140))
            aggregate(rows)
        }
    }

    /**
     * 一个场景一份新世界。
     *
     * **甲与丙的差别只有规则书那一行**——实体、位置、那句话都一样。差一样东西，两次读数就不能
     * 放在同一张表里。
     */
    private fun build(scenario: Scenario): World {
        val base = ProbeSupport.tavernSeed().map { entity ->
            when {
                !scenario.ruled -> entity

                entity.id == "player" -> entity.copy(
                    rulebook = Rulebook(
                        listOf(
                            RuleEntry("你身上没有火种，火柴和打火石都没带。"),
                            RuleEntry("你伸得着大约一格以内的东西——一臂之遥。", values = mapOf("够得着" to "1")),
                        ),
                    ),
                )

                entity.id == "oak_table" -> entity.copy(
                    rulebook = Rulebook(
                        listOf(
                            RuleEntry(
                                "这张桌子上过一层防火的漆，火苗燎上去只会留下烟痕，点不着。",
                                values = mapOf("硬度" to "6"),
                            ),
                        ),
                    ),
                )

                else -> entity
            }
        }
        val world = ProbeSupport.seed("build/spike/rulebook-world-${scenario.name.take(1)}.json", base + scenario.extra)
        // 世界那一份：**它比任何实体那份都低**，正是"世界说会烧、桌子说点不着"那条冲突的来源。
        if (scenario.ruled) {
            world.rulebook = Rulebook(
                listOf(
                    RuleEntry("火会烧起来：木头碰到火先冒烟，再烧着。"),
                    RuleEntry("水往低处流，不会自己往上走。"),
                ),
            )
        }
        return world
    }

    private fun outcomeOf(j: Judgment): String = when (j) {
        is Judgment.Applied -> "成立"
        is Judgment.RejectedWorld -> "驳回@${j.stage}"
        is Judgment.FailedProtocol -> "协议失败"
        is Judgment.Nothing -> "无对象"
        is Judgment.Unchanged -> "成立·没改变"
    }

    private fun detailOf(j: Judgment): String = when (j) {
        is Judgment.Applied ->
            "对齐 ${j.targets.joinToString("、")}｜" +
                j.consequences.joinToString("；") { describe(it) }.ifEmpty { "（一条后果都没有）" }

        is Judgment.RejectedWorld -> j.reason.replace('\n', ' ')
        is Judgment.FailedProtocol -> j.problems.firstOrNull().orEmpty().replace('\n', ' ')
        is Judgment.Nothing -> "第一步一个目标都没报，第二步没有跑"
        is Judgment.Unchanged -> "对齐 ${j.targets.joinToString("、")}｜${j.note}".replace('\n', ' ')
    }

    private fun describe(c: Consequence): String = when (c) {
        is Consequence.Move -> "移动 ${c.id}→(${c.x},${c.y},${c.z})"
        is Consequence.SetAttribute -> "改属性 ${c.id}.${c.key}=${c.value}"
        is Consequence.ChangeAppearance -> "外表变了 ${c.id}"
        is Consequence.Destroy -> "摧毁 ${c.id}"
        is Consequence.Spawn -> "新增 ${c.entity.id}"
        is Consequence.Replace -> "${c.oldId} 换成 ${c.entity.id}"
        is Consequence.SetShape -> "造了形状 ${c.id}（${c.shape.cellCount} 格）"
    }

    private fun aggregate(rows: List<Row>) {
        println()
        println("═".repeat(140))
        println("汇总（每个场景 $rounds 次）")
        println("═".repeat(140))
        println(
            "%-28s %-10s %-10s %-8s %-10s %-12s %-12s %s".format(
                "场景", "算了", "驳回", "成立", "没改变", "符合预期", "推理 token", "中位/最大 总耗时",
            ),
        )
        println("─".repeat(140))
        for ((name, mine) in rows.groupBy { it.scenario }) {
            val sorted = mine.map { it.ms }.sorted()
            val tokens = mine.map { it.reasoningTokens }.filter { it >= 0 }.sorted()
            println(
                "%-28s %-10s %-10d %-8d %-10d %-12s %-12s %s".format(
                    name,
                    "${mine.count { it.calc != "—" }}/${mine.size}",
                    mine.count { it.outcome.startsWith("驳回") },
                    mine.count { it.outcome == "成立" },
                    mine.count { it.outcome == "成立·没改变" },
                    "${mine.count { it.ok }}/${mine.size}",
                    if (tokens.isEmpty()) "—" else "中位 ${tokens[tokens.size / 2]}／最大 ${tokens.last()}",
                    "${sorted[sorted.size / 2]} / ${sorted.last()} ms｜${sorted.sum() / 1000} 秒",
                ),
            )
        }
        println("─".repeat(140))
        val all = rows.map { it.ms }.sorted()
        println(
            "全部 ${rows.size} 次：算了 ${rows.count { it.calc != "—" }} 次｜" +
                "总耗时 ${all.sum() / 1000} 秒｜单次 ${all.first()}–${all.last()} ms",
        )
        val expressions = rows.filter { it.calc != "—" }.map { it.calc }
        println(
            if (expressions.isEmpty()) {
                "模型一次都没用 calc——**这只工具在联机里是死的**"
            } else {
                "算过的式子：${expressions.joinToString("｜")}"
            },
        )
        val lengths = rows.count { it.finishReason == "length" }
        println(
            "被截断（finish_reason=length）$lengths/${rows.size} 次" +
                if (lengths == 0) "——没有上限，它没有被谁打断过" else "——**还有别的东西在限它**",
        )
        println("═".repeat(140))
    }
}
