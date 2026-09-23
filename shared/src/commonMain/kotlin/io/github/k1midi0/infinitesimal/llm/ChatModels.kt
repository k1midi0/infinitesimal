package io.github.k1midi0.infinitesimal.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for the OpenAI-compatible chat completions API.
 *
 * These mirror the JSON exactly. Anything the app reasons about is a separate
 * type, so a change in the wire format cannot silently change app behaviour.
 */

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    /**
     * The model's deliberation, when it produced any.
     *
     * 它必须跟着这条消息一起回传。端点会拒绝一个「接着思考模式的一轮往下走、却没有带上它」的
     * 请求——原话是「The `reasoning_content` in the thinking mode must be passed back to the
     * API」，这是在真机上跑出来的，不是猜的。它不是关于这条消息的推理，它是这条消息的一部分。
     *
     * 这件事原先看不出来，因为一次普通开口只需要一次请求就结束了；`channel` 不终结回合之后，
     * 每一次开口都至少要两次请求，于是它必现。
     */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    /**
     * 这条消息带着的附图，按数据目录 `images/` 里的**文件名**给。
     *
     * **字节不在这里，也不能在这里。** 两个理由都是硬的：token 估算按字符数走（一张几 MB 的图会被
     * 算成几百万 token，保证线立刻触发），而消息还会整轮落进 SQLite 的文本列。真正那个块数组
     * ——`image_url` 加 base64 数据 URL——是在发请求那一刻拼出来的，见 [toWire]。
     *
     * **只有 user 消息可以有图。** system 或 assistant 带图端点是 400，那是协议的形状而不是我们的
     * 选择；[toWire] 会把它当成一个错误拦下来。
     */
    val images: List<String> = emptyList(),
) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_TOOL = "tool"

        fun system(content: String) = ChatMessage(ROLE_SYSTEM, content)

        fun user(content: String, images: List<String> = emptyList()) =
            ChatMessage(ROLE_USER, content, images = images)
        fun assistant(
            content: String? = null,
            reasoningContent: String? = null,
            toolCalls: List<ToolCall>? = null,
        ) = ChatMessage(ROLE_ASSISTANT, content, reasoningContent, toolCalls)

        fun toolResult(toolCallId: String, content: String) =
            ChatMessage(ROLE_TOOL, content, toolCallId = toolCallId)
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionSpec,
)

@Serializable
data class FunctionSpec(
    val name: String,
    val description: String,
    val parameters: JsonSchema,
)

/**
 * A JSON Schema for a tool's parameters.
 *
 * Only the subset the API needs is modelled. `properties` is kept as ordered
 * pairs because field order matters for streaming: some endpoints emit tool call
 * arguments in schema order, and the dialogue field is declared first on purpose.
 */
@Serializable
data class JsonSchema(
    val type: String = "object",
    val properties: Map<String, PropertySchema> = emptyMap(),
    val required: List<String> = emptyList(),
)

@Serializable
data class PropertySchema(
    val type: String,
    val description: String,
)

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean = true,
    /**
     * 采样温度。端点收 0 到 2，默认 1。
     *
     * **思考模式下它不生效**（官方文档明说），所以它不是「一定生效」的那一类参数。仍然每次都按配置
     * 发：配置文件里写了什么，请求里就带什么——改完从下一次请求起生效，不必重新装配运行时。反过来
     * （不调就不发）会让「界面上的数」与「请求里的数」分成两处，而对不上的时候没有任何东西会报错。
     */
    val temperature: Double? = null,
    val tools: List<ToolDefinition>? = null,
    val thinking: ThinkingOptions? = null,
    /**
     * 想多久，与开关分开。`null` 表示这一项根本不发。
     *
     * 名字是端点认的那一个：OpenAI 格式下它叫 `reasoning_effort`，平铺在请求体里，而不像开关
     * 那样包在 `thinking` 里面。
     */
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    /**
     * 这一条请求属于哪个缓存池。`null` 表示不分层，由端点把它归进"空 id"那个特殊池。
     *
     * 端点的原话是它用于 KVCache 隔离、内容安全隔离与调度隔离（《限速与隔离》）。名字是线上的
     * `user_id`——平铺在请求体里，与 `thinking` 无关。约束：`[a-zA-Z0-9\-_]+`，最长 512。
     */
    @SerialName("user_id") val userId: String? = null,
    /**
     * 输出上限。`null` 表示不设——**而那时端点的默认是思考模式 64K**。
     *
     * 这不是省钱用的（只按实际生成计费），是**给延迟设一个上限**：一次判定只该吐出几条工具调用，
     * 给它 64K 的余地，只会让偶发的长篇大论拖住整轮。
     */
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

/**
 * Whether the model deliberates before answering.
 *
 * The accepted values were read off the endpoint by sending an invalid one and
 * reading the rejection, which named all three: `adaptive`, `enabled`, `disabled`.
 * Left unset, the endpoint applies its own default.
 *
 * A binary classification task — judging whether a loop is stuck, for instance —
 * gains nothing from deliberation, so it is worth turning off explicitly there.
 */
@Serializable
data class ThinkingOptions(val type: String) {
    companion object {
        /** The model decides per request. */
        val Adaptive = ThinkingOptions("adaptive")

        val Enabled = ThinkingOptions("enabled")

        val Disabled = ThinkingOptions("disabled")
    }
}

/** A single chunk of a streamed response. */
@Serializable
data class StreamChunk(
    val choices: List<StreamChoice> = emptyList(),
    val usage: Usage? = null,
)

@Serializable
data class StreamChoice(
    val delta: StreamDelta = StreamDelta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class StreamDelta(
    val content: String? = null,
    /**
     * A reasoning model's deliberation, streamed in a field of its own rather than
     * mixed into the answer.
     *
     * Measured against deepseek-flash: when it appears, every token of thinking
     * arrives here, while the answer arrives in [content] or, when a tool is used,
     * only inside the tool arguments. The deliberation is not necessarily in the
     * same language as the conversation.
     *
     * It is not guaranteed to appear. The same prompt shape has been observed both
     * with and without it, and [Usage.completionTokensDetails] reports zero
     * reasoning tokens when it is absent. Code must treat reasoning as something
     * that may be there, never as something to wait for.
     *
     * This is internal. It belongs in the logic layer, and it must never reach the
     * dialogue layer.
     */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDelta>? = null,
)

/**
 * Token accounting, sent as the final frame before the sentinel.
 *
 * The cache fields are worth keeping: a prompt that hits the cache costs a
 * fraction of one that misses, and without recording it a later performance
 * question cannot be answered after the fact.
 */
@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int = 0,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int = 0,
    @SerialName("completion_tokens_details") val completionTokensDetails: CompletionTokensDetails? = null,
)

@Serializable
data class CompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int = 0,
)

/**
 * Tool call fragments arrive incrementally: the first fragment for an index
 * carries the id and function name, later ones append to `arguments`.
 */
@Serializable
data class ToolCallDelta(
    val index: Int = 0,
    val id: String? = null,
    val function: FunctionCallDelta? = null,
)

@Serializable
data class FunctionCallDelta(
    val name: String? = null,
    val arguments: String? = null,
)

/** Error body shape, used to surface a readable reason instead of a bare status. */
@Serializable
data class ApiErrorEnvelope(
    val error: ApiError? = null,
)

@Serializable
data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null,
)
