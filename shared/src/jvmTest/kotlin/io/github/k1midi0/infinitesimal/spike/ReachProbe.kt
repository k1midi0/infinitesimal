package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * 「够得着」那条线到底有没有生效（§3.5 的角色卡）。
 *
 * ## 为什么要用**勾选**来测
 *
 * `JudgeWidenProbe` 的乙那一格是"没勾任何东西"的形状，于是"够不着"与"看不见"混在一趟里：
 * 模型可能根本没看到扫帚，那次驳回就不能算到够得着头上。这里把扫帚**勾上**——它成了框的中心，
 * 一定在框里、一定有坐标，于是这一趟里唯一的变量就是**判定有没有读那张「交互范围」卡**。
 *
 * ## 对照就是这张卡本身
 *
 * 同一句话、同一个世界，一版给玩家带上卡、一版把卡摘掉。两版的差就是那张卡的作用——比跨批次
 * 比数字可靠，因为模型是随机的。
 *
 * 跑法：`-PinfinitesimalLiveKeyFile=<路径> --tests "*ReachProbe*"`
 */
class ReachProbe {

    private val rounds = 4

    private data class Say(
        val scenario: String,
        val utterance: String,
        val selected: List<String>,
    )

    private val cases = listOf(
        // 扫帚在 (16,3)，玩家在 (12,3)：4 格 ≈ 4 米，一臂之遥够不着。
        Say("甲 够不着（扫帚 4 格外）", "我伸手把靠在墙上那把扫帚拿过来。", listOf("broom")),
        // 酒杯在 (12,4) 层1，玩家在 (12,3)：1 格 ≈ 1 米，伸手就够得着。这一格是防误伤的对照。
        Say("乙 够得着（酒杯 1 格）", "我拿起桌上那杯麦酒。", listOf("wine_cup")),
        // 酒壶在 (12,1) 层1，玩家在 (12,3)：**2 格 ≈ 2 米**。这一格是分界——
        // 4 格与 1 格太悬殊，有没有那张卡模型都判得对；真正要看的是这里。
        Say("丙 分界（酒壶 2 格外）", "我伸手把那张矮凳上的酒壶拿过来。", listOf("jug")),
    )

    @Test
    fun reach() {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[reach 探针未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }
        val judge = Judge(ProbeSupport.configOf(apiKey))

        runBlocking {
            println()
            println("═".repeat(104))
            println("「够得着」$rounds 轮 × ${cases.size} 个场景 × 有卡/无卡两版；扫帚与酒杯都**勾上**，所以一定在框里")
            println("═".repeat(104))
            println("%-26s %-6s %-8s %-12s %-9s %s".format("场景", "版", "轮", "结果", "耗时", "细节"))
            println("─".repeat(104))

            val tally = mutableMapOf<String, IntArray>() // key -> [成立, 驳回, 协议失败]
            repeat(rounds) { r ->
                for (c in cases) {
                    for (withCard in listOf(true, false)) {
                        val world = world(withCard)
                        val mark = TimeSource.Monotonic.markNow()
                        val j = judge.judgeAndCommit(
                            world,
                            JudgeRequest(
                                actorId = PLAYER,
                                actorLabel = "你",
                                intention = c.utterance,
                                selected = c.selected,
                                centers = c.selected,
                            ),
                        )
                        val ms = mark.elapsedNow().inWholeMilliseconds
                        val version = if (withCard) "有卡" else "无卡"
                        println(
                            "%-26s %-6s %-8d %-12s %-9s %s".format(
                                c.scenario, version, r + 1, outcomeOf(j), "${ms}ms",
                                detailOf(j).take(52),
                            ),
                        )
                        val key = "${c.scenario}｜$version"
                        val slot = when (j) {
                            is Judgment.Applied -> 0
                            is Judgment.RejectedWorld -> 1
                            is Judgment.FailedProtocol -> 2
                            is Judgment.Nothing -> 3
                            is Judgment.Unchanged -> 4
                        }
                        tally.getOrPut(key) { IntArray(5) }[slot]++
                    }
                }
            }
            println("─".repeat(104))
            println()
            println("═".repeat(104))
            println("汇总（每个组合 $rounds 次）")
            println("═".repeat(104))
            println("%-40s %-8s %-8s %-8s %-8s %-8s".format("场景｜版", "成立", "驳回", "协议失败", "无对象", "没改变"))
            println("─".repeat(104))
            for ((key, v) in tally) {
                println("%-40s %-8d %-8d %-8d %-8d %-8d".format(key, v[0], v[1], v[2], v[3], v[4]))
            }
            println("═".repeat(104))
        }
    }

    /**
     * 开局世界 + 靠在墙上的扫帚。`withCard = false` 时**只把玩家那一张卡摘掉**，其余一字不动。
     */
    private fun world(withCard: Boolean): World {
        val entities = StartingWorld.tavern() + listOf(
            Entity("broom", "器具", "靠在墙上的扫帚", 16, 3, 0, parent = "tavern_hall"),
            Entity("stool", "家具", "矮凳", 12, 1, 0, parent = "tavern_hall"),
            Entity("jug", "器具", "酒壶", 12, 1, 1, parent = "stool"),
        )
        return World.from(
            WorldSnapshot(
                if (withCard) entities
                else entities.map { if (it.id == PLAYER) it.copy(cards = emptyList()) else it },
            ),
        )
    }

    private fun outcomeOf(j: Judgment): String = when (j) {
        is Judgment.Applied -> "成立"
        is Judgment.RejectedWorld -> "驳回@${j.stage}"
        is Judgment.FailedProtocol -> "协议失败"
        is Judgment.Nothing -> "无对象（第二步没跑）"
        is Judgment.Unchanged -> "成立·没改变"
    }

    private fun detailOf(j: Judgment): String = when (j) {
        is Judgment.Applied -> "对齐 ${j.targets.joinToString("、")}｜后果 ${j.consequences.size} 条"
        is Judgment.RejectedWorld -> j.reason.replace('\n', ' ')
        is Judgment.FailedProtocol -> j.problems.firstOrNull().orEmpty().replace('\n', ' ')
        is Judgment.Nothing -> "第一步一个目标都没报"
        is Judgment.Unchanged -> "对齐 ${j.targets.joinToString("、")}｜${j.note}".replace('\n', ' ')
    }

    private companion object {
        private const val PLAYER = "player"
    }
}
