package io.github.k1midi0.infinitesimal.config

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 到达模型端点所需要的全部东西。
 *
 * **这里没有任何默认值，也没有任何静默兜底**：缺一个字段就是一条报出来的错，而不是悄悄连去
 * 一个使用者没有选过的地方。
 *
 * 分两层：
 *  - [baseUrl]、[apiKey]、[model]、[thinking] 没有默认值。文件里少了它们，kotlinx.serialization
 *    会带着字段名拒绝解析；文件里写着空串，则由 [missingFields] 按名字报出来。
 *  - [reasoningEffort]、[temperature] 是**可选的**，null 的含义是「这一项根本不发」，不是「发一个
 *    默认值」。它们必须带默认值，而不是「必填但可为空」：本类型的 Json 开着 `explicitNulls =
 *    false`，为 null 的字段写出去时就不会出现，于是「必填」的文件一旦写出来就再也读不回去。
 */
@Serializable
data class EndpointConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    /**
     * 逻辑层的思考模式。
     *
     * 它决定请求里 `thinking.type` 发什么。强度那一项的规则不在这里，在 `ThinkingRequest.of`
     * 一处：思考关着的时候 `reasoning_effort` 整个不发。
     */
    val thinking: ThinkingMode,
    /**
     * 想多久。只在 [thinking] 是 [ThinkingMode.Always] 时才会出现在请求里。
     *
     * null 表示这一项不发。
     */
    val reasoningEffort: ReasoningEffort? = null,
    /**
     * 采样温度。端点在思考模式下不认它，但配置文件里写了什么，请求里就带什么。
     *
     * null 表示这一项不发，由端点用自己的默认。
     */
    val temperature: Double? = null,
) {

    /** Names of the fields that still need a value. Empty when usable. */
    fun missingFields(): List<String> = buildList {
        if (baseUrl.isBlank()) add("baseUrl")
        if (apiKey.isBlank()) add("apiKey")
        if (model.isBlank()) add("model")
    }

    val isComplete: Boolean get() = missingFields().isEmpty()

    /**
     * 账户余额那一页。
     *
     * 它不是 OpenAI 兼容协议的一部分，是 DeepSeek 自己的一页。别的端点可能没有，那时这里会
     * 回 404——而那是要如实报出来的结果，不是要兜住的情况：读不出余额的端点与余额为零的账户
     * 是两件事。
     */
    val balanceUrl: String
        get() = "${baseUrl.trimEnd('/')}/user/balance"

    /** The chat completions endpoint, with any trailing slash normalised away. */
    val chatCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/chat/completions"

    /**
     * FIM 补全那一页（`/beta/completions`）。
     *
     * `/beta` 由这里加上，**不让使用者配第二个 baseUrl**：配置里出现两个 baseUrl，迟早有一个是旧的，
     * 而症状是"聊天好用、补全忽然 404"——两件事都对，只是说的不是同一个地方。
     */
    val fimCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/beta/completions"

    companion object {

        /**
         * 读配置文件用的那一份 Json。
         *
         * `encodeDefaults = true`：等于默认值的字段也要写出来（尤其是 `stream`，端点会把它当成
         * false 而回一整段非流式的回答）。
         *
         * `explicitNulls = false`：null 不是「一个空值」，而是「这一项不发」——端点在一条不是
         * 工具结果的消息上会拒绝 `tool_call_id`，在带工具调用的消息上会拒绝 `content`。
         */
        @OptIn(ExperimentalSerializationApi::class)
        val ConfigJson: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}
