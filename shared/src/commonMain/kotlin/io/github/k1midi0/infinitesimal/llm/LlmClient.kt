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

    private fun extractApiMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        val parsed = try {
            JSON.decodeFromString(ApiErrorEnvelope.serializer(), body)
        } catch (cause: Throwable) {
            // Not every endpoint returns the documented error envelope. Showing the
            // raw body is more useful to whoever has to diagnose this than showing
            // nothing, so the text is passed through truncated rather than dropped.
            return body.take(MAX_DETAIL_LENGTH)
        }
        return parsed.error?.message?.take(MAX_DETAIL_LENGTH)
            ?: body.take(MAX_DETAIL_LENGTH)
    }

    companion object {
        private const val READ_BUFFER_SIZE = 8192
        private const val MAX_DETAIL_LENGTH = 300

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
