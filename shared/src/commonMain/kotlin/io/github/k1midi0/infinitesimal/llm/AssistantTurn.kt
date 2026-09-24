package io.github.k1midi0.infinitesimal.llm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * 这一轮模型点名的**一只手**：叫什么、参数是什么。
 *
 * `id` 不是装饰：回灌结果时要原样对回去（`tool_call_id`），对不上的结果会被端点丢掉。
 */
class ToolCallRequest(val id: String, val name: String, val arguments: String)

/**
 * 一轮流式响应拼起来的样子。
 *
 * ## 为什么必须留着 `reasoning` 与 `id`
 *
 * 两样都是端点要求的，不是给人看的：
 *
 * - **带 `tools` 的请求里，每条 assistant 消息都必须回传 `reasoning_content`**，没有思考时给空串
 *   （§1.2.1）。只收 name 与 arguments 的拼装器做不了这件事——它连这条消息该长什么样都拼不出来。
 * - **每条 tool 结果都要对上 `tool_call_id`。** 只收 name，回灌时就没东西可对。
 *
 * ## 为什么必须留着 `finishReason`
 *
 * §14.5 记过 Pulsus 把它丢掉，于是「被截断」与「正常说完」在日志里长得一模一样——第二步一个工具都
 * 没调时，光有"没调"这三个字，日志里那条失败**与被截断、被拒绝、模型跑偏三种情况长得一样**。
 */
class AssistantTurn(
    val content: String,
    val reasoning: String,
    val calls: List<ToolCallRequest>,
    val finishReason: String?,
    val usage: Usage?,
) {

    fun asAssistantMessage() = ChatMessage.assistant(
        content = content.ifEmpty { null },
        // 没有思考时给空串、不给 null：带 tools 的请求里这个字段必须在。
        reasoningContent = reasoning,
        toolCalls = calls.map { c ->
            ToolCall(id = c.id, function = FunctionCall(c.name, c.arguments.ifEmpty { "{}" }))
        },
    )

    /** 出问题的时候，日志里唯一能拿来分辨"是哪一种失败"的那句话。 */
    fun diagnosis(): String = buildString {
        val said = content.trim()
        if (said.isNotEmpty()) append("；它说的是：「${said.take(200)}」")
        append("；finish_reason=${finishReason ?: "（这次没有收到）"}")
        usage?.completionTokensDetails?.let { append("；推理想了 ${it.reasoningTokens} token") }
    }

    companion object {

        /** 按 index 把流式的工具调用片段拼起来。 */
        fun of(events: List<LlmEvent>): AssistantTurn {
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val ids = mutableMapOf<Int, String>()
            val names = mutableMapOf<Int, String>()
            val arguments = mutableMapOf<Int, StringBuilder>()
            var finish: String? = null
            var usage: Usage? = null

            for (event in events) {
                when (event) {
                    is LlmEvent.Content -> content.append(event.text)
                    is LlmEvent.Reasoning -> reasoning.append(event.text)
                    is LlmEvent.ToolCallStarted -> {
                        ids[event.index] = event.id
                        names[event.index] = event.name
                    }

                    is LlmEvent.ToolCallArguments ->
                        arguments.getOrPut(event.index) { StringBuilder() }.append(event.fragment)

                    is LlmEvent.Finished -> {
                        finish = event.reason
                        usage = event.usage
                    }
                }
            }

            val calls = names.keys.sorted().map { index ->
                ToolCallRequest(
                    ids[index].orEmpty(),
                    names.getValue(index),
                    arguments[index]?.toString().orEmpty(),
                )
            }
            return AssistantTurn(content.toString(), reasoning.toString(), calls, finish, usage)
        }
    }
}

/**
 * 把一只手带来的 JSON 参数解开。**解不开返回 `null`，不抛异常。**
 *
 * 官方自己警告过「模型并不总是生成有效的 JSON」（§1.3），所以参数解不开是**常态**，不是异常情况：
 * 它该变成一次工具失败被退回给模型（模型拿着"哪只手的哪个参数不对"这条信息，修得比谁都快），
 * 而不是把整轮炸掉。
 */
fun <T> decodeCall(serializer: KSerializer<T>, call: ToolCallRequest): T? = try {
    CALL_JSON.decodeFromString(serializer, call.arguments)
} catch (_: Throwable) {
    null
}

/**
 * 工具参数用。配置与判定那边搬过来之前**逐字一致**（`ignoreUnknownKeys` 是唯一的那一条）——
 * 换掉它，同一批参数会解出不同的结果，而那种差别只会表现为"某几只工具偶尔不灵"。
 */
private val CALL_JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
