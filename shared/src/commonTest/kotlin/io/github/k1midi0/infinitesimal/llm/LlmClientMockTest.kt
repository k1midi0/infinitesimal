package io.github.k1midi0.infinitesimal.llm

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Exercises the client without a network.
 *
 * Error mapping and cancellation have to be checked against every status code and
 * in the middle of a live stream, which is not something a test against a real
 * endpoint can do reliably. A mock engine makes those deterministic.
 */
class LlmClientMockTest {

    /**
     * 一份可用的端点配置。EndpointConfig 一个默认值都没有，所以这里每一项都写出来——
     * 少一项不是「用默认」，而是一条带字段名的错。
     */
    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    private fun clientReturning(
        status: HttpStatusCode = HttpStatusCode.OK,
        body: String = "",
    ): HttpClient = HttpClient(
        MockEngine {
            respond(
                content = body,
                status = status,
                headers = headersOf(
                    HttpHeaders.ContentType,
                    if (status == HttpStatusCode.OK) "text/event-stream" else "application/json",
                ),
            )
        },
    ) {
        // The real client is built the same way: a non-2xx response is data to
        // classify, not an exception to throw.
        expectSuccess = false
    }

    private fun sse(vararg frames: String): String =
        frames.joinToString(separator = "") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private suspend fun errorFrom(client: HttpClient): LlmError {
        val thrown = try {
            LlmClient(config, client).streamChat(listOf(ChatMessage.user("hi"))).toList()
            null
        } catch (error: LlmError) {
            error
        }
        return assertNotNull(thrown, "预期抛出 LlmError，实际没有抛出")
    }

    /**
     * 端点改过之后，下一次请求就要用新的。
     *
     * 这就是「界面上能改端点」的全部内容：配置不再按值传进客户端，而是每次请求问一次。
     * 少了这一条，改完就只有重启才生效，而重启要被重建的是整个运行时。
     */
    @Test
    fun asksForTheConfigurationOnEveryRequest() = runBlocking {
        val seen = mutableListOf<Pair<String, String>>()
        val client = HttpClient(
            MockEngine { request ->
                seen += request.url.toString() to (request.headers[HttpHeaders.Authorization] ?: "")
                respond(
                    content = sse("""{"choices":[{"delta":{"content":"好"}}]}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        ) {
            expectSuccess = false
        }

        var now = config.copy(baseUrl = "https://first.example", apiKey = "第一把钥匙")
        val llm = LlmClient({ now }, client)

        llm.streamChat(listOf(ChatMessage.user("你好"))).toList()

        now = config.copy(baseUrl = "https://second.example", apiKey = "第二把钥匙")
        llm.streamChat(listOf(ChatMessage.user("你好"))).toList()

        assertEquals(2, seen.size)
        assertTrue(seen[0].first.startsWith("https://first.example"), seen[0].first)
        assertTrue(seen[1].first.startsWith("https://second.example"), seen[1].first)
        assertEquals("Bearer 第一把钥匙", seen[0].second)
        assertEquals("Bearer 第二把钥匙", seen[1].second)
    }

    /**
     * 采样温度按配置发出去，字段名与值是端点认的那一个。
     *
     * 名字发错不会报错——端点只会用自己那个默认值——而「以为调了、其实没调」正是这个项目一直在
     * 防的那一类开关。所以这一条看的是请求体里的字面量。
     */
    @Test
    fun carriesTheConfiguredTemperature() = runBlocking {
        var body: String? = null
        val client = HttpClient(
            MockEngine { request ->
                body = (request.body as? TextContent)?.text
                respond(
                    content = sse("""{"choices":[{"delta":{"content":"好"}}]}"""),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        ) {
            expectSuccess = false
        }

        LlmClient(
            config.copy(apiKey = "k", temperature = 0.7),
            client,
        ).streamChat(listOf(ChatMessage.user("你好"))).toList()

        assertTrue(body.orEmpty().contains("\"temperature\":0.7"), body.orEmpty())
    }

    @Test
    fun mapsEachStatusToItsOwnErrorType() = runBlocking {
        // The element type is spelled out because the class literals are of
        // different subtypes and would otherwise not unify.
        val cases: List<Pair<HttpStatusCode, KClass<out LlmError>>> = listOf(
            HttpStatusCode.Unauthorized to LlmError.Unauthorized::class,
            HttpStatusCode.Forbidden to LlmError.Unauthorized::class,
            HttpStatusCode.TooManyRequests to LlmError.RateLimited::class,
            HttpStatusCode.BadRequest to LlmError.BadRequest::class,
            HttpStatusCode.NotFound to LlmError.BadRequest::class,
            HttpStatusCode.InternalServerError to LlmError.ServerError::class,
            HttpStatusCode.BadGateway to LlmError.ServerError::class,
            HttpStatusCode.Conflict to LlmError.UnexpectedStatus::class,
        )

        for ((status, expected) in cases) {
            val error = errorFrom(clientReturning(status, """{"error":{"message":"拒绝"}}"""))
            assertEquals(expected, error::class, "状态码 $status 映射到了错误的类型：$error")
        }
    }

    @Test
    fun carriesTheEndpointMessageIntoTheError() = runBlocking {
        val client = clientReturning(
            HttpStatusCode.BadRequest,
            """{"error":{"message":"Model Not Exist"}}""",
        )

        val error = errorFrom(client)

        assertTrue(
            error.message!!.contains("Model Not Exist"),
            "错误里应当带上端点给的原因，实际为：${error.message}",
        )
    }

    @Test
    fun stillReportsTheStatusWhenTheErrorBodyIsNotTheDocumentedShape() = runBlocking {
        val client = clientReturning(HttpStatusCode.BadGateway, "<html>bad gateway</html>")

        val error = errorFrom(client)

        assertTrue(error is LlmError.ServerError)
        assertEquals(502, error.status)
    }

    @Test
    fun assemblesToolCallFragmentsIntoOneCall() = runBlocking {
        val body = sse(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"say","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"text\":"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"你好\"}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":2,"total_tokens":12}}""",
        )

        val events = LlmClient(config, clientReturning(body = body))
            .streamChat(listOf(ChatMessage.user("hi")))
            .toList()

        val started = events.filterIsInstance<LlmEvent.ToolCallStarted>().single()
        assertEquals("call_1", started.id)
        assertEquals("say", started.name)

        val arguments = events.filterIsInstance<LlmEvent.ToolCallArguments>()
            .joinToString(separator = "") { it.fragment }
        assertEquals("""{"text":"你好"}""", arguments)

        val finished = events.filterIsInstance<LlmEvent.Finished>().single()
        assertEquals("tool_calls", finished.reason)
        assertEquals(12, finished.usage?.totalTokens)
    }

    @Test
    fun separatesReasoningFromContent() = runBlocking {
        val body = sse(
            """{"choices":[{"delta":{"reasoning_content":"想想","content":null}}]}""",
            """{"choices":[{"delta":{"content":"你好"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )

        val events = LlmClient(config, clientReturning(body = body))
            .streamChat(listOf(ChatMessage.user("hi")))
            .toList()

        assertEquals("想想", events.filterIsInstance<LlmEvent.Reasoning>().single().text)
        assertEquals("你好", events.filterIsInstance<LlmEvent.Content>().single().text)
    }

    @Test
    fun reportsAnUnparseableFrameRatherThanSkippingIt() = runBlocking {
        val body = sse("""{"choices":[{"delta":{"content":"ok"}}]}""", """not json at all""")

        val error = errorFrom(clientReturning(body = body))

        assertTrue(error is LlmError.Parse, "无法解析的分片必须报错，实际为：$error")
    }

    /**
     * A body with several frames, collected with take(1), shows the flow stops as
     * soon as the collector stops asking rather than draining the response first.
     */
    @Test
    fun stopsReadingAsSoonAsTheCollectorTakesOne() = runBlocking {
        val body = sse(
            """{"choices":[{"delta":{"content":"第一段"}}]}""",
            """{"choices":[{"delta":{"content":"第二段"}}]}""",
            """{"choices":[{"delta":{"content":"第三段"}}]}""",
        )

        val events = withTimeout(10_000) {
            LlmClient(config, clientReturning(body = body))
                .streamChat(listOf(ChatMessage.user("hi")))
                .take(1)
                .toList()
        }

        assertEquals(1, events.size)
        assertEquals("第一段", (events.single() as LlmEvent.Content).text)
    }

    /**
     * Cancels while the request is still in flight, which is what a user pressing
     * stop before the endpoint has answered does. The timeout turns a failure to
     * notice cancellation into a failed test rather than a stuck run.
     *
     * Cancelling while the endpoint is silent mid stream is not covered here: a
     * mock engine materialises the whole body before handing back a response, so it
     * cannot produce a stream that stays open. That path is Ktor's own cancellable
     * read and is exercised against the live endpoint instead.
     */
    @Test
    fun cancellingWhileTheRequestIsStillInFlightEndsTheJob() = runBlocking {
        val client = HttpClient(
            MockEngine {
                delay(60_000)
                respond("", HttpStatusCode.OK)
            },
        ) { expectSuccess = false }

        val job = launch {
            LlmClient(config, client).streamChat(listOf(ChatMessage.user("hi"))).collect { }
        }

        delay(300)
        withTimeout(10_000) { job.cancelAndJoin() }

        assertTrue(job.isCancelled, "取消后任务应当处于已取消状态")
    }
}
