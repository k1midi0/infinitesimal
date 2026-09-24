package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.judge.ShapeModeler
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.world.ShapeGrid
import kotlin.test.Test
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking

/**
 * 建模那一段的联机探针（附录 A.9–A.11）。
 *
 * 它验的是**离线钉不住的那一半**：这段提示词到底有没有把网格语言教明白。离线测试只能验"层交上来
 * 有没有被拼成图、画错有没有被退回去"；"模型画得对不对"只有真跑才有答案。
 *
 * 四句要求都是这套东西真的会遇到的形状：一件**大的带腿家具**（它的网格最容易在腿那里画崩）、一件
 * **要挖空的容器**（杯子）、一件**只有一半的东西**（判定里"桌子被劈开"那条路）、一件**小到只能用
 * 质点**的东西（蓝莓）——最后这一件是在验"比一个格还小"那条路是不是真的走通了。
 *
 * 打印的东西里有两样是刻意的：
 *
 *  - **回画的网格**。它比任何一句"形状对了"都可靠——一眼就能看出腿在不在、杯子有没有底。
 *  - **缓存命中**。建模独占一个 `user_id`（[SystemId.MODELER]），而它的 system 每次都逐字相同，
 *    所以**第二次之后该稳定命中**。这一栏是那次"独占一池"的唯一凭据。
 *
 * 跑法：`.\gradlew.bat :shared:jvmTest --tests "*ShapeModelerProbe*" -PinfinitesimalLiveKeyFile="<密钥文件>"`
 */
class ShapeModelerProbe {

    @Test
    fun modelFourRealThings() {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[建模探针未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }

        val modeler = ShapeModeler(
            LlmClient(ProbeSupport.configOf(apiKey).copy(userId = SystemId.MODELER)),
        )

        val briefs = listOf(
            "一张橡木方桌，桌面一百二十厘米见方、八十厘米高，四条方腿，桌面厚五厘米",
            "一只深色木制小酒杯，口径八厘米、高十厘米",
            "一只装水的大木桶，口径六十厘米、高八十厘米",
            "被从中间劈开的橡木方桌的一半，断面粗糙，还连着一条桌腿",
            "一颗蓝莓，直径一厘米",
        )

        println("===== 建模探针 =====")
        var total = 0L

        for (brief in briefs) {
            println("\n──────── $brief")
            val before = modeler.requestCount
            var result: ShapeModeler.Result? = null
            val ms = measureTimeMillis { result = runBlocking { modeler.model(brief) } }
            total += ms

            when (val r = result!!) {
                is ShapeModeler.Result.Modeled -> {
                    val size = r.shape.extent
                    println("  ✓ ${r.name}  ${r.shape.cellCount} 格  质点格 ${r.shape.pointMass.size}")
                    println("  尺寸 ${size.x + 5} × ${size.y + 5} × ${size.z + 5} 厘米")
                    println(ShapeGrid.render(r.shape).prependIndent("    "))
                }

                is ShapeModeler.Result.Failed -> {
                    println("  ✗ 没画成：")
                    r.problems.forEach { println("      · $it") }
                }
            }

            println("  请求 ${modeler.requestCount - before} 次，用时 $ms ms")
            modeler.lastUsage?.let {
                println(
                    "  token：输入 ${it.promptTokens}（命中 ${it.promptCacheHitTokens} / " +
                        "未命中 ${it.promptCacheMissTokens}），输出 ${it.completionTokens}" +
                        "（推理想了 ${it.completionTokensDetails?.reasoningTokens ?: 0}）",
                )
            }
        }

        // 最后把**第一句要求原样再问一次**。用户定的缓存键就是那句要求，所以这一次该一次请求都不发——
        // 而它是这套东西最值钱的一条：一样东西在一局里会被造很多次，画一次是几十秒。
        println("\n──────── （重跑第一句要求，验缓存键）")
        val beforeRepeat = modeler.requestCount
        var repeated: ShapeModeler.Result? = null
        val repeatMs = measureTimeMillis { repeated = runBlocking { modeler.model(briefs.first()) } }
        val hit = (repeated as? ShapeModeler.Result.Modeled)?.cached == true
        println("  ${if (hit) "✓ 命中" else "✗ 没有命中"}  请求 ${modeler.requestCount - beforeRepeat} 次，用时 $repeatMs ms")
        println("  生成预设包里现在有 ${modeler.presets.generatedPresets().size} 条：")
        modeler.presets.generatedPresets().forEach {
            println("    · ${it.name}  ${it.shape.cellCount} 格  ← ${it.brief.take(30)}")
        }

        println("\n总用时 $total ms")
    }
}
