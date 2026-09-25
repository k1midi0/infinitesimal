package io.github.k1midi0.infinitesimal.judge

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf

/**
 * 假端点：逐轮吐出写好的 SSE 帧，并把每次请求体原样存下来。
 *
 * 它自己**不是被测对象**，被测的是"请求体里到底有什么"。两个测试类都要它——`JudgeWidenTest` 验
 * 那个回合的协议（tool 结果配不配得上 `tool_call_id`、assistant 带没带 `reasoning_content`），
 * `CharacterCardPromptTest` 验提示词里拼进了什么。放在这里一份，两边都不必自己写一遍。
 */
internal class Scripted(private val frames: List<String>) {
    val bodies = mutableListOf<String>()
    private var served = 0

    val client: HttpClient = HttpClient(
        MockEngine { request ->
            bodies += (request.body as? TextContent)?.text.orEmpty()
            val body = frames[minOf(served, frames.size - 1)]
            served++
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        },
    ) {
        expectSuccess = false
    }
}

internal fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

internal fun sse(vararg frames: String) = frames.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n"

/**
 * 终点在最后一帧报的用量。
 *
 * 真端点在最后一个内容块之后、`data: [DONE]` 之前总会补一块 usage（§1.5）。假端点要能照样补，
 * 否则"思维链 token 有没有记进账本"这件事在离线测试里根本无从验起——而那正是延迟那一节
 * （§14.22）唯一能拆开「等」的量。
 */
internal data class Tokens(
    val prompt: Int,
    val cacheHit: Int = 0,
    val completion: Int,
    val reasoning: Int = 0,
) {
    fun frame(): String =
        """{"choices":[],"usage":{"prompt_tokens":$prompt,"completion_tokens":$completion,""" +
            """"total_tokens":${prompt + completion},"prompt_cache_hit_tokens":$cacheHit,""" +
            """"prompt_cache_miss_tokens":${prompt - cacheHit},""" +
            """"completion_tokens_details":{"reasoning_tokens":$reasoning}}}"""
}

/** 一轮工具调用。`reasoning` 非空时会带上 `reasoning_content`——那正是回传时的硬约束。 */
internal fun turn(
    id: String,
    name: String,
    args: String,
    reasoning: String = "",
    tokens: Tokens? = null,
): String {
    val r = if (reasoning.isEmpty()) "" else "\"reasoning_content\":\"${esc(reasoning)}\","
    val frames = mutableListOf(
        """{"choices":[{"delta":{$r"tool_calls":[{"index":0,"id":"$id","function":{"name":"$name","arguments":"${esc(args)}"}}]}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
    )
    tokens?.let { frames += it.frame() }
    return sse(*frames.toTypedArray())
}

/** 一轮里同时调好几只手。 */
internal fun multiTurn(vararg calls: Triple<String, String, String>): String {
    val body = calls.mapIndexed { i, (id, name, args) ->
        """{"index":$i,"id":"$id","function":{"name":"$name","arguments":"${esc(args)}"}}"""
    }.joinToString(",")
    return sse(
        """{"choices":[{"delta":{"reasoning_content":"先想一下。","tool_calls":[$body]}}]}""",
        """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
    )
}

/**
 * 一轮**没有任何工具调用**：模型说完了。
 *
 * 对判定那是「这一轮没有查询手」，对导演与生成器那是「这一轮没有手」——两边都由这一帧表示收工，
 * 而两种情况的意思完全不同，所以两边都得能造出它来。
 */
internal fun stop(content: String = ""): String = sse(
    """{"choices":[{"delta":{"content":"${esc(content)}"}}]}""",
    """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
)
