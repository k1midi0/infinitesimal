package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.judge.Consequence
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.judge.Scene
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 判定系统的联机验收：**模型段 + 代码段接起来跑**。
 *
 * 四个案例压四条设计线，而且**串在同一个世界上按顺序跑**——这本身验了一件事：判定读的是**当前的**
 * 世界，不是它开始时的那个。实测里最后一条驳回的理由会提到前面几条改过的东西。
 *
 * 顺序不能随便调：`擦干净` 必须在 `劈成两半` **之前**，否则桌子已经没了，前者自然不成立
 * （第一版就是这么错的——驳回本身是对的，是场景错了）。
 *
 * 跑法：`-PinfinitesimalLiveKeyFile=<路径> --tests "*JudgeProbeSpike*"`
 */
class JudgeProbeSpike {

    private val worldFile = "build/spike/judge-world.json"

    @Test
    fun judgeFourKindsOfAction() {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[judge 探针未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }
        val world = ProbeSupport.seed(worldFile, seedWorld())
        val judge = Judge(ProbeSupport.configOf(apiKey))

        lateinit var swept: Judgment
        lateinit var wiped: Judgment
        lateinit var split: Judgment
        lateinit var reached: Judgment

        runBlocking {
            header()
            swept = run(
                judge, world,
                intention = "我用胳臂横扫整个桌面，把桌上的东西都扫到地上。",
                selected = listOf("oak_table"),
            )
            wiped = run(
                judge, world,
                intention = "我掏出身上的抹布，把这张木桌的桌面擦干净。",
                selected = listOf("oak_table"),
            )
            split = run(
                judge, world,
                intention = "我举起腰间那把短柄斧，把这张木桌从中间劈成两半。",
                selected = listOf("oak_table"),
            )
            reached = run(
                judge, world,
                intention = "我伸手把三条街外那家店里的杯子拿过来。",
                selected = emptyList(),
            )
            println("═".repeat(100))
        }

        // ── 断言压的是**形状**，不是措辞（措辞每次都会变） ───────────────────────────

        // A 一次意图、多条后果（§4.1）：桌上的两样东西都得被处理到，一个都不能漏。
        assertTrue(swept is Judgment.Applied, "A 扫桌面应当成立，实际：$swept")
        val sweptEffects = (swept as Judgment.Applied).consequences
        listOf("wine_cup", "oil_lamp").forEach { id ->
            assertTrue(
                sweptEffects.any { it.targetId == id },
                "A 漏了桌上的 $id —— 这正是第一版暴露过的问题（Scene 没标出谁挂在谁下面）",
            )
        }

        // D 仅仅外表变化（§7.1）：id 不变，只是描述作废。
        assertTrue(wiped is Judgment.Applied, "D 擦桌面应当成立，实际：$wiped")
        val wipedEffects = (wiped as Judgment.Applied).consequences
        assertTrue(
            wipedEffects.any { it is Consequence.ChangeAppearance || it is Consequence.SetAttribute },
            "D 只是外表变化，该走 ChangeAppearance 或 SetAttribute，实际：${wipedEffects.map { it::class.simpleName }}",
        )
        assertTrue(
            wipedEffects.none { it is Consequence.Replace },
            "D 不该换掉桌子——它只是被擦干净了，还是原来那张",
        )

        // B 彻底改变（§7.1）：必须走 Replace，而且世界里真的换了身份。
        assertTrue(split is Judgment.Applied, "B 劈开桌子应当成立，实际：$split")
        val splitEffects = (split as Judgment.Applied).consequences
        assertTrue(
            splitEffects.any { it is Consequence.Replace },
            "B 是彻底改变，必须走 Replace，实际：${splitEffects.map { it::class.simpleName }}",
        )
        assertTrue(world.byId("oak_table") == null, "劈开之后旧的 oak_table 不该还在世界里")
        assertTrue(
            world.all().any { it.kind == "家具" || it.label.contains("木") },
            "世界上该留下某个由它变成的东西，实际：${world.all().map { it.id }}",
        )

        // C 世界内驳回（§4.3 第一层）：理由要给玩家看，而且**不能是系统词**。
        assertTrue(reached is Judgment.RejectedWorld, "C 够不着，必须被世界内驳回，实际：$reached")
        val reason = (reached as Judgment.RejectedWorld).reason
        assertTrue(reason.isNotBlank(), "驳回必须给理由")
        assertTrue(
            listOf("API", "请求", "参数", "工具", "模型").none { reason.contains(it) },
            "驳回理由是给玩家看的世界内措辞，不该出现系统词：$reason",
        )

        // 串行同一个世界的收益：最后的驳回理由应当提到前面改过的世界，而不是初始状态。
        println()
        println("【串行的证据】最后一条驳回的理由里提到的是**当前**世界：")
        println("  「$reason」")
    }

    private suspend fun run(
        judge: Judge,
        world: World,
        intention: String,
        selected: List<String>,
    ): Judgment {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val judgment = judge.judgeAndCommit(
            world,
            JudgeRequest(
                actorId = "player",
                actorLabel = "你",
                intention = intention,
                selected = selected,
                scene = Scene.of(world, "player"),
            ),
        )
        val elapsed = mark.elapsedNow().inWholeMilliseconds

        println()
        println("▸ 动作：$intention")
        println("  用时 $elapsed ms")
        when (judgment) {
            is Judgment.Applied -> {
                println("  ✅ 落库 ${judgment.consequences.size} 条后果（尝试 ${judgment.attempts} 次）")
                judgment.consequences.forEach {
                    println("     · ${it::class.simpleName} → ${it.targetId}  [${it.level}]")
                }
                if (judgment.affected.isNotEmpty()) println("     受影响：${judgment.affected}")
            }

            is Judgment.RejectedWorld -> {
                println("  ⛔ 世界内驳回（尝试 ${judgment.attempts} 次）：")
                println("     「${judgment.reason}」")
            }

            is Judgment.FailedProtocol -> {
                println("  ⚠ 协议内失败（尝试 ${judgment.attempts} 次）——**不示给玩家**：")
                judgment.problems.forEach { println("     · $it") }
            }
        }
        println("  世界现在有：${world.all().joinToString("、") { "${it.id}(${it.label})" }}")
        return judgment
    }

    private fun header() {
        println()
        println("═".repeat(100))
        println("判定系统联机验收：一次意图多条后果 → 仅仅外表变化 → 彻底改变 → 世界内驳回")
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
        // 判定看得见它们，"举起斧子/掏出抹布"才成立；看不见的话它会正确地驳回（实测到过两次）。
        Entity(
            id = "axe", kind = "器具", label = "短柄斧", x = 12, y = 3, z = 1,
            parent = "player",
            attributes = mapOf("状态" to "刃口有些卷"),
        ),
        Entity(
            id = "rag", kind = "器具", label = "抹布", x = 12, y = 3, z = 1,
            parent = "player",
            attributes = mapOf("状态" to "半干"),
        ),
    )
}
