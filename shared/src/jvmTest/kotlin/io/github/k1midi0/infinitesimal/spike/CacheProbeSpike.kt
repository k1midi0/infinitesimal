package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.LlmEvent
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.render.Renderer
import io.github.k1midi0.infinitesimal.world.Entity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * 缓存探针：**结论已经量出来了，这里留着的是它的证据。**（设计文档 §14.5）
 *
 * 三条已知结论，各由一个方法钉住：
 *
 *  - 短请求（54–198 token）命中恒为 0——包括完全相同的请求
 *  - 长前缀（2799 token）稳定命中 2560，**换个后缀也命中** → 缓存按固定 token 间隔落盘
 *  - 导演给的背景没能把渲染器推过门槛（前缀 277 token，同地点四个物体命中全 0）
 *
 * 于是那条规则是：**动静分层只在上下文超过一个缓存单元的系统上才有收益。** 渲染器不在其中。
 */
class CacheProbeSpike {

    /** 固定不变，好让两个请求之间只差 user_id 或末尾追加。 */
    private val system = "你是一个观察器。你只描述被观察的对象本身。用中文，两到四句，直接给出描述。"
    private val user = "对象：木桌。材质橡木，尺寸 2 米 × 1 米 × 0.8 米，状态完好。请描述它。"

    @Test
    fun probeCachePools() {
        val apiKey = ProbeSupport.keyOrNull() ?: return skip("缓存池探针")
        val base = ProbeSupport.configOf(apiKey)

        runBlocking {
            val renderer = LlmClient(base.copy(userId = SystemId.RENDERER))
            val director = LlmClient(base.copy(userId = SystemId.DIRECTOR))
            val unlayered = LlmClient(base)

            header("缓存池探针：同一池连发 / 跨池连发 / 前缀延伸")
            call(renderer, "① RENDERER 首次（负责落盘）")
            println("     …等 6 秒（缓存构建是秒级的，官方原文）")
            delay(6_000)
            call(renderer, "② RENDERER 完全相同再来一次")
            call(director, "③ DIRECTOR 完全相同（分层了，该不该命中？）")
            call(unlayered, "④ 不带 user_id 完全相同")
            call(renderer, "⑤ RENDERER 第三次")
            call(renderer, "⑥ RENDERER 前缀延伸（末尾追加一句）", append = "\n再补一句：桌面上还放着什么？")
            call(renderer, "⑦ RENDERER 回到原请求")
            footer()
        }
    }

    /**
     * 长度假设。
     *
     * 文档说"在**长输入或长输出**中，系统会以一定的 token 数量为间隔，截取缓存前缀单元"——而前面所有
     * 请求都只有 54–198 token。所以"命中恒为 0"有两种解释，结论**正好相反**：缓存对该账号未生效，
     * 还是**前缀太短、压根没有落盘**。
     *
     * 实测是后者：2799 token 的前缀命中 2560，而且**换一个后缀照样命中 2560**。
     */
    @Test
    fun probeLongPrefix() {
        val apiKey = ProbeSupport.keyOrNull() ?: return skip("长前缀探针")
        val renderer = LlmClient(ProbeSupport.configOf(apiKey).copy(userId = SystemId.RENDERER))

        // 逐字相同的一大段。内容不重要，重要的是长度，以及它每次都一模一样。
        val filler = "这里是一段逐字重复的静态文本，用来把前缀撑到足够长；它本身没有任何含义。".repeat(120)

        runBlocking {
            header("长前缀探针：静态前缀约 ${filler.length} 字")
            call(renderer, "① 长前缀首发（落盘）", staticPrefix = filler)
            println("     …等 8 秒")
            delay(8_000)
            call(renderer, "② 长前缀 + 相同问题", staticPrefix = filler)
            call(renderer, "③ 长前缀 + 不同问题（同前缀应已落盘）", staticPrefix = filler, append = "\n换个问题：它是新的还是旧的？")
            call(renderer, "④ 长前缀 + 又回到第一个问题", staticPrefix = filler)
            footer()
        }
    }

    /**
     * 导演给的背景，能不能成为渲染器**可复用的共享前缀**。
     *
     * 渲染器自己那段 system 只有约 130 token，够不着门槛。导演写的背景把前缀撑长了，而且**同一地点
     * 下的所有物体共享它**——所以设计上它不只是"让描述不脱离世界"，它本来还可能顺带解决缓存。
     *
     * **实测：不能。** 前缀到 277 token，同地点四个不同物体命中全为 0。
     */
    @Test
    fun probeSharedBackgroundPrefix() {
        val apiKey = ProbeSupport.keyOrNull() ?: return skip("共享背景前缀探针")
        val world = ProbeSupport.seed("build/spike/cache-world.json", tavernSeed())
        val renderer = Renderer(ProbeSupport.configOf(apiKey), world)

        runBlocking {
            header("共享背景前缀：同一地点下三个不同物体，第三次是判决点")
            println("  ▸ 木桌：${renderer.observe("oak_table").usageLine()}")
            println("     …等 8 秒")
            delay(8_000)
            println("  ▸ 酒杯：${renderer.observe("wine_cup").usageLine()}")
            println("  ▸ 木凳（判决点）：${renderer.observe("stool").usageLine()}")
            println("  ▸ 油灯：${renderer.observe("oil_lamp").usageLine()}")
            println("  ▸ 另一个地点的木桌（背景不同）：${renderer.observe("yard_table").usageLine()}")
            footer()
        }
    }

    private fun Renderer.Observation.usageLine(): String {
        val hit = usage?.promptCacheHitTokens
        val miss = usage?.promptCacheMissTokens
        return if (usage == null) "固化命中（未发请求）"
        else "命中 $hit ｜ 未命中 $miss ｜ 共 ${usage.promptTokens} ｜ ${elapsedMs} ms"
    }

    private suspend fun call(
        client: LlmClient,
        label: String,
        staticPrefix: String? = null,
        append: String = "",
    ) {
        val events = client.streamChat(
            messages = listOf(
                ChatMessage.system(staticPrefix ?: system),
                ChatMessage.user(user + append),
            ),
            thinking = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.LOW),
        ).toList()

        val usage = events.filterIsInstance<LlmEvent.Finished>().lastOrNull()?.usage
        val verdict = when {
            usage == null -> "拿不到 usage"
            usage.promptCacheHitTokens == 0 -> "未命中"
            usage.promptCacheHitTokens >= usage.promptTokens -> "全命中"
            else -> "部分命中"
        }
        println(
            "  %-44s 命中 %4d ｜ 未命中 %4d ｜ 共 %4d  → %s".format(
                label,
                usage?.promptCacheHitTokens ?: -1,
                usage?.promptCacheMissTokens ?: -1,
                usage?.promptTokens ?: -1,
                verdict,
            ),
        )
    }

    private fun skip(what: String) {
        println("[$what 未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
    }

    private fun header(title: String) {
        println()
        println("═".repeat(104))
        println(title)
        println("═".repeat(104))
    }

    private fun footer() {
        println("═".repeat(104))
    }

    private fun tavernSeed(): List<Entity> = listOf(
        Entity(
            id = "tavern_hall", kind = "地点", label = "酒馆大厅", x = 1000, y = 0, z = 0,
            background = """
                这里是「醉猫」酒馆，边境小镇灰渡唯一还在营业的酒馆，木石结构，建成约二十年，墙角常年返潮，北侧的窗子有一扇关不严。
                三天前镇东的粮仓失火，烧掉了半个冬天的存粮；酒馆老板在那之后就没再露面，如今由一个年长的伙计照看店面。
                眼下是深秋傍晚，屋里点了四盏油灯，客人比往常少，靠门的两张桌子空着。
            """.trimIndent(),
        ),
        Entity(
            id = "oak_table", kind = "家具", label = "木桌", x = 1200, y = 400, z = 0, parent = "tavern_hall",
            attributes = mapOf("材质" to "橡木", "状态" to "完好", "尺寸" to "2米 × 1米 × 0.8米"),
        ),
        Entity(
            id = "wine_cup", kind = "器具", label = "酒杯", x = 1200, y = 400, z = 80, parent = "oak_table",
            attributes = mapOf("材质" to "深色木制", "内容物" to "半杯麦酒"),
        ),
        Entity(
            id = "stool", kind = "家具", label = "木凳", x = 1300, y = 400, z = 0, parent = "tavern_hall",
            attributes = mapOf("材质" to "榆木", "状态" to "凳面有裂"),
        ),
        Entity(
            id = "oil_lamp", kind = "器具", label = "油灯", x = 1200, y = 500, z = 80, parent = "oak_table",
            attributes = mapOf("材质" to "铁与玻璃", "状态" to "点着"),
        ),
        Entity(
            id = "yard", kind = "地点", label = "后院", x = 300, y = 900, z = 0,
            background = "灰渡酒馆的后院，地上散着劈好的柴，靠墙堆着几只空酒桶，雨下过不久，泥地还没干。",
        ),
        Entity(
            id = "yard_table", kind = "家具", label = "木桌", x = 300, y = 900, z = 0, parent = "yard",
            attributes = mapOf("材质" to "松木", "状态" to "被雨淋过"),
        ),
    )
}
