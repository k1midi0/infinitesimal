package io.github.k1midi0.infinitesimal.llm

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.image.AttachedImages
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * Talks to an OpenAI-compatible chat completions endpoint and turns the raw
 * response stream into [LlmEvent]s.
 *
 * The client knows nothing about tools, memory or the dialogue channel. It sends
 * messages and reports what came back.
 */
class LlmClient(
    /**
     * 每次请求问一次，而不是装配时读一次。
     *
     * 端点现在可以在界面上改，而改完应当从下一次请求起生效。按值传进来的话，改完要么不
     * 生效，要么得把整个运行时重建一遍——而运行时的数据库只能开一次、界面手里的回合入口
     * 只能接一次，那两条都不是能让出去的。
     */
    private val configuration: () -> EndpointConfig,
    private val httpClient: HttpClient = createHttpClient(),
) {

    /**
     * 给配置确实是固定值的地方用，主要是测试。
     *
     * 它不改变上面那条：能变的还是能变，这里只是把「不会变」这件事明说出来。
     */
    constructor(config: EndpointConfig, httpClient: HttpClient = createHttpClient()) :
        this({ config }, httpClient)

    /**
     * 读一次账户余额。
     *
     * 走端点自己的那一页，而不是从用量里推算——「还剩多少钱」只有端点知道，应用算不出来。
     *
     * 状态码用的是与发消息同一套分类（见 LlmError.fromStatus）。别的端点没有这一页时会回 404，
     * 那会被归成 BadRequest 并带着端点的原话报上来，而不是被压成一句「读不到余额」。
     */
    suspend fun readBalance(): Balance {
        val config = configuration()
        val missing = config.missingFields()
        if (missing.isNotEmpty()) throw LlmError.NotConfigured(missing)

        val response = try {
            httpClient.get(config.balanceUrl) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                header(HttpHeaders.Accept, "application/json")
            }
        } catch (cause: Throwable) {
            throw LlmError.Network(cause)
        }

        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw LlmError.fromStatus(response.status.value, body)
        }

        val parsed = try {
            JSON.decodeFromString(BalanceResponse.serializer(), body)
        } catch (cause: Throwable) {
            throw LlmError.Parse(body, cause)
        }

        return parsed.toBalance()
    }
    fun streamChat(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        thinking: ThinkingRequest? = null,
        maxTokens: Int? = null,
    ): Flow<LlmEvent> {
        // **没装观察者时走的就是原来那条路**（§14.56）：一次判断，然后一字不差地是原来那个流。
        val sink = LlmTrace.sink ?: return streamChatRaw(messages, tools, thinking, maxTokens)
        return recordedStream(messages, tools, thinking, maxTokens, sink)
    }

    /**
     * 一边把事件原样发下去，一边抄一份给 [LlmTrace]（§14.56）。
     *
     * 三件事按重要程度排：**抄的东西绝不能改变这一轮**（事件逐个原样 `emit`，一个字节都不动）、
     * **抄失败绝不能影响这一轮**（观察者抛出来的异常被吞掉）、**失败的那一趟也要抄**（配置没配好、
     * 端点拒绝、流断在中途——那些正是最需要看原文的时候，所以记录写在 `finally` 里）。
     *
     * 抄的是**传进来那串 messages**，不是 `toWire` 之后的那一份：图片在那里被展开成 base64，而一条
     * 记录不该被一张图撑成几兆（见 [Exchange] 的注释）。
     */
    private fun recordedStream(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>?,
        thinking: ThinkingRequest?,
        maxTokens: Int?,
        sink: (Exchange) -> Unit,
    ): Flow<LlmEvent> = flow {
        val startedAt = System.currentTimeMillis()
        val config = configuration()

        val content = StringBuilder()
        val reasoning = StringBuilder()
        val drafts = LinkedHashMap<Int, CallDraft>()
        var usage: Usage? = null
        var failure: String? = null

        try {
            streamChatRaw(messages, tools, thinking, maxTokens).collect { event ->
                when (event) {
                    is LlmEvent.Content -> content.append(event.text)
                    is LlmEvent.Reasoning -> reasoning.append(event.text)
                    is LlmEvent.ToolCallStarted ->
                        drafts.getOrPut(event.index) { CallDraft() }.apply {
                            id = event.id
                            name = event.name
                        }

                    is LlmEvent.ToolCallArguments ->
                        drafts.getOrPut(event.index) { CallDraft() }.apply {
                            arguments.append(event.fragment)
                        }

                    is LlmEvent.Finished -> usage = event.usage
                }
                emit(event)
            }
        } catch (cause: Throwable) {
            failure = cause.message ?: cause::class.simpleName
            throw cause
        } finally {
            // 顺序就是 index，于是同一趟里调了两只工具时它们按模型给的顺序落下来。
            val calls = drafts.entries
                .sortedBy { it.key }
                .mapNotNull { (_, draft) ->
                    val name = draft.name ?: return@mapNotNull null
                    ToolCall(
                        id = draft.id ?: "",
                        function = FunctionCall(name = name, arguments = draft.arguments.toString()),
                    )
                }

            val record = Exchange(
                startedAtMs = startedAt,
                elapsedMs = System.currentTimeMillis() - startedAt,
                pool = config.userId?.userId.orEmpty(),
                model = config.model,
                thinking = thinking?.options?.type.orEmpty(),
                effort = thinking?.effort,
                messages = messages,
                tools = tools.orEmpty().map { it.function.name },
                content = content.toString(),
                reasoning = reasoning.toString(),
                calls = calls,
                usage = usage,
                failure = failure,
            )
            try {
                sink(record)
            } catch (cause: Throwable) {
                // 抄日志这件事本身失败（磁盘满、路径没了），**不许影响这一轮**。
                println("[账没抄下来] ${cause.message ?: cause::class.simpleName}")
            }
        }
    }

    /** 抄一份的过程中，一只工具的参数还在分片里慢慢拼——拼完才变成 `ToolCall`。 */
    private class CallDraft {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    private fun streamChatRaw(
        messages: List<ChatMessage>,
        tools: List<ToolDefinition>? = null,
        thinking: ThinkingRequest? = null,
        maxTokens: Int? = null,
    ): Flow<LlmEvent> = flow {
        val config = configuration()
        val missing = config.missingFields()
        if (missing.isNotEmpty()) throw LlmError.NotConfigured(missing)

        // 附图在**这一处**才展开成数据 URL：字节按文件名从数据目录读出来，块数组在这里拼。应用
        // 里那一层（ChatMessage）只留文件名，理由见它自己的注释。
        val wireMessages = messages
            .map { it.toWire(AttachedImages::bytesOf) }
            .map { wire ->
                // **携带 tools 的请求里，每一条 assistant 消息都必须回传 reasoning_content**：
                // 没有思考时是空串，而不是 null、更不是不发。端点的原话是「The `reasoning_content`
                // in the thinking mode must be passed back to the API」，而在带工具的回路里这条规则
                // 对每一条 assistant 消息都成立——包括那一轮其实没有思考的。缺了它整条请求会被拒，
                // 而症状是「这一轮忽然发不出去」，看不出与思考有什么关系。
                //
                // 只在发了 tools 的请求上补：不发 tools 时那一项照旧可以不出现。
                if (tools.isNullOrEmpty() || wire.role != ChatMessage.ROLE_ASSISTANT) {
                    wire
                } else {
                    wire.copy(reasoningContent = wire.reasoningContent ?: "")
                }
            }

        val request = ChatRequest(
            model = config.model,
            messages = wireMessages,
            stream = true,
            // null 表示这一项根本不发（见 EndpointConfig.temperature），而不是发一个 null。
            temperature = config.temperature,
            tools = tools,
            thinking = thinking?.options,
            reasoningEffort = thinking?.effort,
            // 分层：这一路请求属于哪个系统，它就用哪个缓存池。null 时整项不发。
            userId = config.userId?.userId,
            maxTokens = maxTokens,
        )

        val response = try {
            httpClient.preparePost(config.chatCompletionsUrl) {
                header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(JSON.encodeToString(ChatRequest.serializer(), request))
            }.execute()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            throw LlmError.Network(cause)
        }

        if (!response.status.isSuccess()) {
            // The body is already in hand; a failure to read it must not replace
            // the status code, which is the more useful half of the diagnosis.
            val body = try {
                response.bodyAsText()
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                null
            }
            throw LlmError.fromStatus(response.status.value, extractApiMessage(body))
        }

        emitStream(response.bodyAsChannel(), this)
    }

    private suspend fun emitStream(
        channel: ByteReadChannel,
        collector: FlowCollector<LlmEvent>,
    ) {
        val parser = SseParser()
        val buffer = ByteArray(READ_BUFFER_SIZE)

        var usage: Usage? = null
        var finishReason: String? = null

        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue

            for (event in parser.feed(buffer.copyOf(read))) {
                when (event) {
                    SseParser.Event.Done -> Unit
                    is SseParser.Event.Data -> {
                        val chunk = decodeChunk(event.payload)
                        chunk.usage?.let { usage = it }

                        val choice = chunk.choices.firstOrNull() ?: continue
                        choice.finishReason?.let { finishReason = it }

                        val delta = choice.delta
                        delta.reasoningContent?.takeIf(String::isNotEmpty)?.let {
                            collector.emit(LlmEvent.Reasoning(it))
                        }
                        delta.content?.takeIf(String::isNotEmpty)?.let {
                            collector.emit(LlmEvent.Content(it))
                        }
                        delta.toolCalls?.forEach { fragment ->
                            emitToolCallFragment(fragment, collector)
                        }
                    }
                }
            }
        }

        collector.emit(LlmEvent.Finished(finishReason, usage))
    }

    private suspend fun emitToolCallFragment(
        fragment: ToolCallDelta,
        collector: FlowCollector<LlmEvent>,
    ) {
        val id = fragment.id
        val name = fragment.function?.name

        // The identifier and name are sent once, in the first fragment for this
        // index. Later fragments carry arguments only.
        if (id != null && name != null) {
            collector.emit(LlmEvent.ToolCallStarted(fragment.index, id, name))
        }
        fragment.function?.arguments?.takeIf(String::isNotEmpty)?.let {
            collector.emit(LlmEvent.ToolCallArguments(fragment.index, it))
        }
    }

    private fun decodeChunk(payload: String): StreamChunk = try {
        JSON.decodeFromString(StreamChunk.serializer(), payload)
    } catch (cause: Throwable) {
        throw LlmError.Parse("流式分片不是预期的结构：${payload.take(200)}", cause)
    }

    companion object {
        private const val READ_BUFFER_SIZE = 8192

        /**
         * Request bodies must carry fields that happen to equal their defaults —
         * notably `stream`, which the endpoint would otherwise treat as false and
         * answer with a single non-streamed response.
         *
         * Nulls are omitted instead, because the API rejects `tool_call_id` on a
         * message that is not a tool result and `content` on one that carries tool
         * calls.
         */
        @OptIn(ExperimentalSerializationApi::class)
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        /**
         * Timeouts are set for a stream, not for a request: the overall request
         * timeout is disabled because a long answer legitimately takes minutes,
         * while the socket timeout still catches a connection that stopped
         * delivering without closing.
         */
        fun createHttpClient(): HttpClient = HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = null
                socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            }
        }

        private const val SOCKET_TIMEOUT_MILLIS = 180_000L
        private const val CONNECT_TIMEOUT_MILLIS = 30_000L
    }
}

private const val MAX_DETAIL_LENGTH = 300

@OptIn(ExperimentalSerializationApi::class)
private val ERROR_JSON: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * 从错误响应体里取出人能读的那一句。
 *
 * **它在文件级，不在任何一个客户端里**：聊天与 FIM 是两条协议、两个客户端，但端点的错误信封只有
 * 一种形状。写成某个客户端的成员，第二个客户端就只能要么复制一份、要么绕道拿到第一个客户端的
 * 实例——前者迟早与后者漂移，而漂移的症状是"同一个 429，一条路说是限流、另一条路说是未知"。
 */
internal fun extractApiMessage(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val parsed = try {
        ERROR_JSON.decodeFromString(ApiErrorEnvelope.serializer(), body)
    } catch (cause: Throwable) {
        // 不是每个端点都返回文档里那个错误信封。把原文截断后交出去，比什么都不给更有用于诊断。
        return body.take(MAX_DETAIL_LENGTH)
    }
    return parsed.error?.message?.take(MAX_DETAIL_LENGTH)
        ?: body.take(MAX_DETAIL_LENGTH)
}
