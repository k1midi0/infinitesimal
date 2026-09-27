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

    /**
     * **这些请求里，手都递过去过哪些**（每次请求一组，按顺序）。
     *
     * ## 为什么不能对整个请求体数 `"name"`
     *
     * 因为**请求体里装着整串历史，而历史里存着之前几次请求的工具调用**——对整个体数，会把上一轮递过
     * 的手也算进来，于是"这一次有没有把某只手给它"变成一个永远为真的问题。这与 §14.43 那条差一是
     * 同一个坑（`substringAfter` 取到了最早那一节）。
     *
     * 所以只认 `"tools":[` 到 `"messages":` 之间那一段：那是这一次真正递给它的手。
     */
    fun offeredTools(): List<Set<String>> = bodies.map { body ->
        val from = body.indexOf("\"tools\":")
        val to = body.indexOf("\"messages\":")
        val tools = if (from < 0) "" else body.substring(from, if (to > from) to else body.length)
        Regex("\"name\"\\s*:\\s*\"([a-z_]+)\"").findAll(tools).map { it.groupValues[1] }.toSet()
    }

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

/**
 * 把一个字符串塞进 JSON 字面量里。
 *
 * **换行也必须转义**（2026-10-01 修的）：以前只处理 `\` 与 `"`，而 JSON 的字符串里**不许出现真的换行**
 * ——`DirectorTools.WRITE_CYCLE` 是第一个收多行文本的工具，`\n` 没转义时那条假端点吐出来的分片整个
 * 解不开（`LlmError.Parse`），症状是"这一支莫名其妙地炸在流解析上"，而不是任何一条断言。
 */
internal fun esc(s: String) = s
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

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
