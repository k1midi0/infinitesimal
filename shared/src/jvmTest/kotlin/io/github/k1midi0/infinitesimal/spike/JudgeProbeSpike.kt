package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.judge.Consequence
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.judge.Stage
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 两步判定的联机验收。
 *
 * **前两个案例是这次改造的全部理由**：玩家在界面上选的只是一个猜测，第一步负责把它对齐。
 * 少了要补、多了要剔——而这两件事以前是第二步顺手做的（做不做得到全看运气）。
 *
 * 后面三个压的是第二步：仅仅外表变化 / 彻底改变 / 世界内驳回。
 *
 * 案例串在同一个世界上按顺序跑——这本身验了判定读的是**当前**世界。顺序不能随便调：
 * 擦干净必须在劈成两半**之前**，否则桌子已经没了。
 *
 * 跑法：`-PinfinitesimalLiveKeyFile=<路径> --tests "*JudgeProbeSpike*"`
 */
class JudgeProbeSpike {

    private val worldFile = "build/spike/judge-world.json"

    /**
     * 把一条后果写全。**坐标必须打出来。**
     *
     * 这一整轮改动的起因就是"模型给的坐标是编的"——油灯从 (12,5) 层1 被挪到 (2,0) 层0，而层降对了、
     * 绝对值是它自己编的。只打一句 `Move → oil_lamp` 是看不出那件事修没修的。
     */
    private fun describe(c: Consequence): String = when (c) {
        is Consequence.Move -> "Move → ${c.id}  到 (${c.x}, ${c.y}) 层${c.z}  [${c.level}]"
        is Consequence.Reparent ->
            "Reparent → ${c.id}  挂到 ${c.parent?.let { "「$it」" } ?: "（什么都不挂）"}  [${c.level}]"
        is Consequence.SetAttribute -> "SetAttribute → ${c.id}  ${c.key}=${c.value}  [${c.level}]"
        is Consequence.ChangeAppearance -> "ChangeAppearance → ${c.id}  [${c.level}]"
        is Consequence.Destroy -> "Destroy → ${c.id}  [${c.level}]"
        is Consequence.Spawn ->
            "Spawn → ${c.entity.id}「${c.entity.label}」(${c.entity.x}, ${c.entity.y}) 层${c.entity.z}  [${c.level}]"
        is Consequence.Replace ->
            "Replace → ${c.oldId} 变成 ${c.entity.id}「${c.entity.label}」" +
                "(${c.entity.x}, ${c.entity.y}) 层${c.entity.z}  [${c.level}]"
    }

    @Test
    fun twoStepJudgment() {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[judge 探针未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }
        val world = ProbeSupport.seed(worldFile, seedWorld())
        val judge = Judge(ProbeSupport.configOf(apiKey))

        lateinit var filledIn: Judgment
        lateinit var trimmed: Judgment
        lateinit var wiped: Judgment
        lateinit var split: Judgment
        lateinit var reached: Judgment
        lateinit var widened: Judgment
        var widenedWidenings = 0

        runBlocking {
            header()
            // ① 选少了：玩家什么都没选，但动作明显指向桌上的杯子。
            filledIn = run(judge, world, "我拿起桌上那杯麦酒。", selected = emptyList())
            // ② 选多了：动作只碰杯子，油灯是多余的。
            trimmed = run(judge, world, "我把杯里的酒一口喝掉。", selected = listOf("wine_cup", "oil_lamp"))
            // ③ 仅仅外表变化。
            wiped = run(judge, world, "我掏出身上的抹布，把这张木桌的桌面擦干净。", selected = listOf("oak_table"))
            // ④ 彻底改变（之后 oak_table 就没了）。
            split = run(judge, world, "我举起腰间那把短柄斧，把这张木桌从中间劈成两半。", selected = listOf("oak_table"))
            // ⑤ 世界内驳回。
            reached = run(judge, world, "我伸手把三条街外那家店里的杯子拿过来。", selected = emptyList())
            // ⑥ **唯一一个只有 widen_view 能解决的局面**：扫帚在 (16,3)，比默认那一片（x 9..15）
            //    刚好差一格。框外那句话被删掉之后，它**在提示词里一个字都没有**——模型想报出它，
            //    除了调 widen_view 把框扩过去，没有第二条路。
            world.put(Entity("broom", "器具", "靠在墙上的扫帚", 16, 3, 0))
            widened = run(judge, world, "我拿起靠在墙上那把扫帚。", selected = emptyList())
            widenedWidenings = judge.viewWidenings
            println("═".repeat(100))
        }

        // ── 第一步：对齐（这次改造的全部理由） ──────────────────────────────────────

        assertTrue(filledIn is Judgment.Applied, "① 应当成立，实际：$filledIn")
        val filledTargets = (filledIn as Judgment.Applied).targets
        assertTrue(
            "wine_cup" in filledTargets,
            "① 玩家什么都没选，第一步**必须补出**酒杯，实际对齐到：$filledTargets",
        )

        assertTrue(trimmed is Judgment.Applied, "② 应当成立，实际：$trimmed")
        val trimmedTargets = (trimmed as Judgment.Applied).targets
        assertTrue("wine_cup" in trimmedTargets, "② 酒杯该留下，实际：$trimmedTargets")
        assertTrue(
            "oil_lamp" !in trimmedTargets,
            "② 玩家多选了油灯，第一步**必须剔除**——喝掉杯里的酒碰不到油灯，实际：$trimmedTargets",
        )

        // ── 第二步：后果的形状 ─────────────────────────────────────────────────────

        assertTrue(wiped is Judgment.Applied, "③ 擦桌子应当成立，实际：$wiped")
        val wipedEffects = (wiped as Judgment.Applied).consequences
        assertTrue(
            wipedEffects.any { it is Consequence.ChangeAppearance || it is Consequence.SetAttribute },
            "③ 只是外表变化，实际：${wipedEffects.map { it::class.simpleName }}",
        )
        assertTrue(wipedEffects.none { it is Consequence.Replace }, "③ 不该换掉桌子——它只是被擦干净了")

        assertTrue(split is Judgment.Applied, "④ 劈开桌子应当成立，实际：$split")
        val splitEffects = (split as Judgment.Applied).consequences
        // **不断言它必须用 Replace。** 实测里模型选了 Destroy + Spawn×2——从世界状态看结果完全正确，
        // 而且比 Replace 更准：桌子劈开确实变成了**两个**东西，而 Replace 只支持"一变一"。
        // 要断言的是**世界级的结果**，不是它挑了哪只工具。
        assertTrue(
            splitEffects.any { it is Consequence.Destroy || it is Consequence.Replace },
            "④ 旧桌子必须被收走（Destroy 或 Replace），实际：${splitEffects.map { it::class.simpleName }}",
        )
        assertTrue(world.byId("oak_table") == null, "④ 劈开之后旧的 oak_table 不该还在世界里")
        assertTrue(
            splitEffects.any { it is Consequence.Spawn || it is Consequence.Replace },
            "④ 必须出现由它变成的东西，实际：${splitEffects.map { it::class.simpleName }}",
        )

        assertTrue(reached is Judgment.RejectedWorld, "⑤ 够不着，必须被世界内驳回，实际：$reached")
        val rejected = reached as Judgment.RejectedWorld
        assertTrue(
            listOf("API", "请求", "参数", "工具", "模型").none { rejected.reason.contains(it) },
            "⑤ 驳回理由是给玩家看的世界内措辞，不该出现系统词：${rejected.reason}",
        )

        // ⑥ **不断言模型必须扩。** 它不扩也是一种可观测的结果（说明这一片够用，或者它没抓住）。
        //    要断言的是那条硬约束：扫帚的 id 只可能出现在扩大之后的清单里，所以模型**若**报出了它，
        //    就一定扩过；报出来了却没扩过，只能说明 id 是从别处漏出去的——那是个真 bug。
        if (widened is Judgment.Applied) {
            val targets = (widened as Judgment.Applied).targets
            if ("broom" in targets) {
                assertTrue(widenedWidenings >= 1, "⑥ 报出了扫帚（$targets）却没扩过——这个 id 是从哪儿来的？")
            } else {
                println("⑥ 成立但没指到扫帚，对齐到了：$targets")
            }
        } else {
            println("⑥ 没有成立：$widened（这是一次观测，不是失败）")
        }

        println()
        println("【两步的证据】")
        println("  ① 玩家选 [] -> 第一步补成 $filledTargets")
        println("  ② 玩家选 [wine_cup, oil_lamp] -> 第一步改成 $trimmedTargets")
        println("  ⑤ 驳回发生在哪一步：${rejected.stage}")
    }

    private suspend fun run(
        judge: Judge,
        world: World,
        intention: String,
        selected: List<String>,
    ): Judgment {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val requestsBefore = judge.requestCount
        val judgment = judge.judgeAndCommit(
            world,
            JudgeRequest(
                actorId = "player",
                actorLabel = "你",
                intention = intention,
                selected = selected,
                centers = selected,
            ),
        )
        val elapsed = mark.elapsedNow().inWholeMilliseconds

        println()
        println("▸ 动作：$intention")
        println("  玩家选的：${if (selected.isEmpty()) "（什么都没选）" else selected.joinToString("、")}")
        println(
            "  请求 ${judge.requestCount - requestsBefore} 次｜看大 ${judge.viewWidenings} 次｜${elapsed} ms",
        )
        when (judgment) {
            is Judgment.Applied -> {
                val changed = judgment.targets != selected
                println("  第一步对齐到：${judgment.targets.ifEmpty { listOf("（不作用于具体的东西）") }.joinToString("、")}"
                    + if (changed) "   ← **与玩家选的不同**" else "")
                println("  ✅ 落库 ${judgment.consequences.size} 条后果（尝试 ${judgment.attempts} 次）")
                judgment.consequences.forEach { println("     · ${describe(it)}") }
                if (judgment.affected.isNotEmpty()) println("     受影响：${judgment.affected}")
            }

            is Judgment.RejectedWorld -> {
                println("  ⛔ 世界内驳回（第 ${judgment.stage} 步，尝试 ${judgment.attempts} 次）：")
                println("     「${judgment.reason}」")
            }

            is Judgment.FailedProtocol -> {
                println("  ⚠ 协议内失败（尝试 ${judgment.attempts} 次）——**不示给玩家**：")
                judgment.problems.forEach { println("     · $it") }
            }
        }
        println("  总用时 $elapsed ms｜世界现在有：${world.all().joinToString("、") { it.id }}")
        return judgment
    }

    private fun header() {
        println()
        println("═".repeat(100))
        println("两步判定验收：补对象 → 剔对象 → 仅仅外表变化 → 彻底改变 → 世界内驳回")
        println("═".repeat(100))
    }

    private fun seedWorld(): List<Entity> = listOf(
        Entity(
            id = "tavern_hall", kind = "地点", label = "酒馆大厅", x = 10, y = 0, z = 0,
            attributes = mapOf("地面" to "夯土", "光照" to "几盏油灯"),
            background = """
                这里是「醉猫」酒馆，边境小镇灰渡唯一还在营业的酒馆，木石结构，建成约二十年。
                眼下是深秋傍晚，屋里点了四盏油灯，客人比往常少。
            """.trimIndent(),
        ),
        Entity(
            id = "oak_table", kind = "家具", label = "木桌", x = 12, y = 4, z = 0,
            parent = "tavern_hall",
            attributes = mapOf("材质" to "橡木", "状态" to "完好"),
            sentences = listOf("橡木桌呈深褐色，表面打磨得光滑，木纹清晰而紧密地延展。", "四条桌腿厚实笔直，整体高约零点八米。"),
        ),
        Entity(
            id = "wine_cup", kind = "器具", label = "酒杯", x = 12, y = 4, z = 1,
            parent = "oak_table",
            attributes = mapOf("材质" to "深色木制", "内容物" to "半杯麦酒"),
            sentences = listOf("深色木制杯身，表面可见木纹，杯口边缘有几道划痕。"),
        ),
        Entity(
            id = "oil_lamp", kind = "器具", label = "油灯", x = 12, y = 5, z = 1,
            parent = "oak_table",
            attributes = mapOf("材质" to "铁与玻璃", "状态" to "点着"),
            sentences = listOf("铁架上扣着一只发黄的玻璃罩，罩口有烟熏的痕迹。"),
        ),
        Entity(
            id = "player", kind = "角色", label = "你", x = 12, y = 3, z = 0,
            parent = "tavern_hall",
            attributes = mapOf("两手" to "空着", "腰间" to "挂着一把短柄斧"),
        ),
        // **身上带着的东西也是实体**，靠 parent 挂在角色下面——统一 Actor 的用法（§3.1）。
        Entity(
            id = "axe", kind = "器具", label = "短柄斧", x = 12, y = 3, z = 1,
            parent = "player", attributes = mapOf("状态" to "刃口有些卷"),
        ),
        Entity(
            id = "rag", kind = "器具", label = "抹布", x = 12, y = 3, z = 1,
            parent = "player", attributes = mapOf("状态" to "半干"),
        ),
    )
}
