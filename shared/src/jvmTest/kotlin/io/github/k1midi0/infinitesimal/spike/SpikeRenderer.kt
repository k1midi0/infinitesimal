package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.LlmEvent
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.Usage
import kotlinx.coroutines.flow.toList
import kotlin.system.measureTimeMillis

/**
 * 观察器的最小原型：`观察(X) → 描述(X)`，读或创建并写回。
 *
 * 它只做设计 §7 里那三件事，其余都不做：
 *  - 固化：第一次观察生成并落库；再观察直接读库，**不发起请求**
 *  - 细化只叠加：要求更精细时把现有描述一起发过去，并明确要求"只增不改"
 *  - 无旁白：system 提示词把叙述、动作、心情全部排除
 */
class SpikeRenderer(
    private val client: LlmClient,
    private val world: SpikeWorld,
) {

    /** 真正发出去的请求数。缓存命中不该让它增加——这是"固化"唯一的客观证据。 */
    var requestCount = 0
        private set

    data class Outcome(
        val text: String,
        val fromCache: Boolean,
        val elapsedMs: Long,
        val usage: Usage?,
        val finishReason: String?,
    )

    suspend fun observe(id: String, refine: Boolean = false): Outcome {
        val entity = requireNotNull(world.byId(id)) { "世界里没有这个物体：$id" }

        // 固化命中：只要不是"要求更精细"，已有描述就直接还回去，一个请求都不发。
        if (!refine) {
            entity.description?.let { frozen ->
                return Outcome(frozen, fromCache = true, elapsedMs = 0, usage = null, finishReason = "cache")
            }
        }

        val previous = if (refine) entity.description else null
        val messages = listOf(
            ChatMessage.system(SYSTEM_PROMPT),
            ChatMessage.user(userPrompt(entity, previous)),
        )

        var events: List<LlmEvent> = emptyList()
        val elapsed = measureTimeMillis {
            requestCount++
            events = client
                .streamChat(
                    messages = messages,
                    // 快速档：描述一个物体不需要推理。而且只有这一档 temperature 才生效
                    // （思考模式下它被端点忽略），文风多样性只在这里拿得到。
                    thinking = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.LOW),
                )
                .toList()
        }

        val text = events.filterIsInstance<LlmEvent.Content>().joinToString("") { it.text }.trim()
        val finished = events.filterIsInstance<LlmEvent.Finished>().lastOrNull()
        check(text.isNotEmpty()) { "端点返回了空的 content，finish_reason=${finished?.reason}" }

        world.put(entity.copy(description = text, detailLevel = entity.detailLevel + 1))
        return Outcome(
            text = text,
            fromCache = false,
            elapsedMs = elapsed,
            usage = finished?.usage,
            finishReason = finished?.reason,
        )
    }

    private fun userPrompt(entity: SpikeEntity, previous: String?): String = buildString {
        appendLine("对象：${entity.label}（id=${entity.id}）")
        appendLine("类型：${entity.kind}")
        appendLine("坐标：(${entity.x}, ${entity.y}, ${entity.z}) 层=${entity.z}")
        entity.parent?.let { parentId ->
            val parent = world.byId(parentId)
            appendLine("所在：${parent?.label ?: parentId}（id=$parentId）")
        }
        if (entity.attributes.isNotEmpty()) {
            appendLine("属性：")
            entity.attributes.forEach { (k, v) -> appendLine("  $k = $v") }
        }
        if (previous == null) {
            appendLine("观察精度：粗略")
        } else {
            appendLine("观察精度：精细（第 ${entity.detailLevel + 1} 次观察）")
            appendLine()
            appendLine("已有描述：")
            appendLine(previous)
            appendLine()
            appendLine("这一轮要求更精细：在已有描述的基础上补充细节。**只增不改**——不得与已有描述矛盾，不得推翻其中任何一句。")
        }
    }

    companion object {

        /**
         * 永远不变的那一段。
         *
         * 它必须是**逐字固定**的：任何插值都会让前缀变样，缓存按 token 前缀完整匹配，一改就全灭。
         * 而且它是这个请求里唯一能跨物体复用的部分——可变的东西全部落在后面的 user 消息里。
         */
        val SYSTEM_PROMPT: String = """
            你是一个观察器。你只描述被观察的对象本身。

            规则：
            1. 只写这个对象看上去是什么样：材质、形态、磨损、颜色、纹理、气味、触感。
            2. 不写动作、不写情节、不写心情，不写“你走进房间”这类叙述。你不是旁白者。
            3. 不推测没有给出的事实。看不到的东西不要编。
            4. 用中文，两到四句，直接给出描述本身，不要开场白，不要复述对象的名称与坐标。
        """.trimIndent()
    }
}
