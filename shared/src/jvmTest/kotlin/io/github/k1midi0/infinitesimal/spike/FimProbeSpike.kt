package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.FimClient
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * FIM 的隔离探针：**一次只改一个变量**。
 *
 * 两次真实尝试都失败了，而失败方式不同（假标记 `<cont=12>`、空返回），且都发生在"富提示词"下；
 * 探针里的极简形状三次全好。所以现在要问的不是"FIM 行不行"，而是"**提示词的哪一部分把它弄坏了**"。
 *
 * 每一格只加一样东西：指令、物体信息、后缀。原始返回逐字打出，连同 finish_reason。
 */
class FimProbeSpike {

    private val keyFile: File?
        get() = System.getProperty("infinitesimal.liveKeyFile")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    /** 真实的基线：木桌的三句固化描述。 */
    private val baseline = listOf(
        "深褐色的橡木桌，桌面平整没有明显裂纹，尺寸约两米长一米宽，高及常人腰际。",
        "桌角微微磨圆，边缘带着一层浅淡的光泽。",
        "四条腿为直柱形，落地沉稳无晃动，木纹呈细密的直纹延展。",
    )

    private val info = """
        对象：木桌（种类：家具）
        坐标：(12, 4, 0)
        所在：酒馆大厅
        已知属性：
          材质 = 橡木
          状态 = 完好
          尺寸 = 2米 × 1米 × 0.8米
    """.trimIndent()

    private val instruction = "给这个物体补一句新的细节，插在下面这段话中间。不得与已有描述矛盾，不得复述已有句子。"

    private val suffix: String get() = baseline.drop(1).joinToString("")

    @Test
    fun isolatePromptVariables() {
        val file = keyFile
        if (file == null || !file.isFile) {
            println("[probe 未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }
        val client = FimClient(
            EndpointConfig(
                baseUrl = "https://api.deepseek.com",
                apiKey = file.readText().trim(),
                model = "deepseek-flash",
                thinking = ThinkingMode.Never,
            ),
        )

        runBlocking {
            probe(client, "H 只有前缀（无指令、无物体信息）", baseline[0], suffix)
            probe(client, "E 指令 + 前缀", "$instruction\n${baseline[0]}", suffix)
            probe(client, "G 物体信息 + 前缀（无指令）", "$info\n${baseline[0]}", suffix)
            probe(client, "F 指令 + 物体信息 + 前缀（真实形状）", "$instruction\n$info\n${baseline[0]}", suffix)
            probe(client, "F2 同 F，但物体信息压成一行", "$instruction\n对象：木桌（家具），在酒馆大厅，材质橡木，状态完好，尺寸 2×1×0.8 米。\n${baseline[0]}", suffix)
        }
    }

    private suspend fun probe(client: FimClient, label: String, prompt: String, suffix: String?) {
        println()
        println("─".repeat(90))
        println("▸ $label")
        println("  prompt 行数 ${prompt.lines().size}｜末尾：…${prompt.takeLast(36).replace("\n", "⏎")}")
        val result = try {
            client.complete(prompt = prompt, suffix = suffix, maxTokens = 200)
        } catch (cause: Throwable) {
            println("  抛错：${cause::class.simpleName} — ${cause.message?.take(200)}")
            return
        }
        println("  返回（逐字，含边界）：【${result.text}】")
        println(
            "  长度 ${result.text.length}｜finish_reason=${result.finishReason}｜" +
                "usage=${result.usage?.let { "命中${it.promptCacheHitTokens}/未命中${it.promptCacheMissTokens}/出${it.completionTokens}" } ?: "(无)"}",
        )
    }
}
