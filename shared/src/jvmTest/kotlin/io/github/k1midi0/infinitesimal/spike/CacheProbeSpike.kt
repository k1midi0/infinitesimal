package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.LlmEvent
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * 缓存探针。
 *
 * 前面约十五次请求的 `prompt_cache_hit_tokens` 恒为 0。官方《上下文硬盘缓存》把机制讲得很具体，
 * 而它给出的两个例子正好能套到我们的序列上——**所以这件事不是"运气不好"，是可以判定的**：
 *
 * - 缓存前缀是**独立的完整单元**，后续请求只有**完整匹配某个单元**才算命中
 * - 每次请求的**用户输入结束位置**会产生一个单元
 * - 两个请求只有公共前缀时，第二轮**不命中**；系统随后把那个公共前缀单独落盘，**第三轮才命中**
 * - 缓存构建是**秒级**的
 *
 * 于是这个探针按"同一池内连发、跨池连发、前缀延伸"三种情形分开量。有了 [SystemId] 分层，
 * "同一池内也不命中"与"跨池本就不该命中"才分得开——这正是分层买到的第三样东西。
 *
 * 跑法：`-PinfinitesimalLiveKeyFile=<路径> --tests "*CacheProbeSpike*"`
 */
class CacheProbeSpike {

    private val keyFile: File?
        get() = System.getProperty("infinitesimal.liveKeyFile")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    @Test
    fun probeCachePools() {
        val file = keyFile
        if (file == null || !file.isFile) {
            println("[cache probe 未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }

        val base = EndpointConfig(
            baseUrl = "https://api.deepseek.com",
            apiKey = file.readText().trim(),
            model = "deepseek-flash",
            thinking = ThinkingMode.Never,
        )

        runBlocking {
            val renderer = LlmClient(base.copy(userId = SystemId.RENDERER))
            val director = LlmClient(base.copy(userId = SystemId.DIRECTOR))
            val unlayered = LlmClient(base)

            println()
            println("═".repeat(104))
            println("缓存探针：同一池内连发 / 跨池连发 / 前缀延伸")
            println("═".repeat(104))

            call(renderer, "① RENDERER 首次（这一次负责落盘）")
            println("     …等 6 秒，缓存构建是秒级的（官方原文）")
            delay(6_000)

            call(renderer, "② RENDERER 完全相同再来一次")
            call(director, "③ DIRECTOR 完全相同（分层了，该不该命中？）")
            call(unlayered, "④ 不带 user_id 完全相同")
            call(renderer, "⑤ RENDERER 第三次")
            call(renderer, "⑥ RENDERER 前缀延伸（末尾追加一句）", append = "\n再补一句：桌面上还放着什么？")
            call(renderer, "⑦ RENDERER 回到原请求（不含追加）")

            println("═".repeat(104))
        }
    }

    /**
     * 长度假设。
     *
     * 文档说"在**长输入或长输出**中，系统会以一定的 token 数量为间隔，截取缓存前缀单元"——而前面所有
     * 请求都只有 54–198 token。所以"命中恒为 0"有两种解释，结论**正好相反**：
     *
     *  - 缓存对该账号/模型未生效 → 分层与动静分层都是白做
     *  - **前缀太短，压根没有落盘** → 长上下文的系统（NPC、导演）会受益，而短前缀的渲染器本来就用不上
     *
     * 这次拿一个三千 token 的静态前缀，把长度这一个变量单拎出来。
     */
    @Test
    fun probeLongPrefix() {
        val file = keyFile
        if (file == null || !file.isFile) {
            println("[长前缀探针未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }
        val base = EndpointConfig(
            baseUrl = "https://api.deepseek.com",
            apiKey = file.readText().trim(),
            model = "deepseek-flash",
            thinking = ThinkingMode.Never,
        )

        // 逐字相同的一大段。内容不重要，重要的是长度，以及它每次都一模一样。
        val filler = "这里是一段逐字重复的静态文本，用来把前缀撑到足够长；它本身没有任何含义。".repeat(120)

        runBlocking {
            val renderer = LlmClient(base.copy(userId = SystemId.RENDERER))
            println()
            println("═".repeat(104))
            println("长前缀探针：静态前缀约 ${filler.length} 字（≈ ${filler.length * 6 / 10} token）")
            println("═".repeat(104))

            call(renderer, "① 长前缀首发（落盘）", staticPrefix = filler)
            println("     …等 8 秒")
            delay(8_000)
            call(renderer, "② 长前缀 + 相同问题", staticPrefix = filler)
            call(renderer, "③ 长前缀 + 不同问题（同前缀应已落盘）", staticPrefix = filler, append = "\n换个问题：它是新的还是旧的？")
            call(renderer, "④ 长前缀 + 又回到第一个问题", staticPrefix = filler)

            println("═".repeat(104))
        }
    }

    private suspend fun call(
        client: LlmClient,
        label: String,
        staticPrefix: String? = null,
        append: String = "",
    ) {
        val messages = buildList {
            add(ChatMessage.system(staticPrefix ?: SYSTEM))
            add(ChatMessage.user(USER + append))
        }
        val events = client.streamChat(
            messages = messages,
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
            "%-46s 命中 %4d ｜ 未命中 %4d ｜ 共 %4d  → %s".format(
                label,
                usage?.promptCacheHitTokens ?: -1,
                usage?.promptCacheMissTokens ?: -1,
                usage?.promptTokens ?: -1,
                verdict,
            ),
        )
    }

    private companion object {
        /** 固定不变，好让两个请求之间只差 user_id 或末尾追加。 */
        const val SYSTEM = "你是一个观察器。你只描述被观察的对象本身。用中文，两到四句，直接给出描述。"
        const val USER = "对象：木桌。材质橡木，尺寸 2 米 × 1 米 × 0.8 米，状态完好。请描述它。"
    }
}
