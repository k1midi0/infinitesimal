package io.github.k1midi0.infinitesimal.llm

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * **抄一份调用**这件事本身（§14.56）。
 *
 * 它钉住四件事，而四件的分量不同：
 *  1. 一趟成功的调用被完整抄下来（回答、思考、工具参数分片拼起来的那一份、token 账）；
 *  2. **失败的那一趟也抄**——那正是最需要看原文的时候；
 *  3. **没装观察者时行为与从前逐字一样**（这条是"零侵入"那一句的可验证形态）；
 *  4. **抄日志的人自己抛异常，不许弄坏这一轮**——诊断把游戏弄坏比没有诊断糟得多。
 *
 * 用假引擎跑，因为它要控制响应体里的每一个字节（真端点上没法造出"流到一半失败"）。
 */
class LlmTraceTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
        userId = SystemId.NPC,
    )

    private fun clientReturning(
        body: String = "",
        status: HttpStatusCode = HttpStatusCode.OK,
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
        expectSuccess = false
    }

    private fun sse(vararg frames: String): String =
        frames.joinToString(separator = "") { "data: $it\n\n" } + "data: [DONE]\n\n"

    /** **装了就要卸**：这一格是进程级的，漏掉一次后面每一条测试都会往那个 sink 里写。 */
    private suspend fun withSink(sink: (Exchange) -> Unit, block: suspend () -> Unit) {
        LlmTrace.install(sink)
        try {
            block()
        } finally {
            LlmTrace.uninstall()
        }
    }

    @Test
    fun aWholeCallIsCopiedDownToItsArgumentsAndItsTokenAccount() = runBlocking {
        val body = sse(
            """{"choices":[{"delta":{"reasoning_content":"先看它在不在。","content":null}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"say","arguments":""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"text\":"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"你好\"}"}}]},"finish_reason":"tool_calls"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":900,"completion_tokens":40,"total_tokens":940,"prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":100}}""",
        )

        val copied = mutableListOf<Exchange>()
        val tools = listOf(
            tool("say", "说一句话", text("text", "说什么")),
            tool("note", "记一条", text("text", "记什么")),
        )

        withSink({ copied += it }) {
            LlmClient(config, clientReturning(body = body))
                .streamChat(
                    listOf(ChatMessage.user("你在吗")),
                    tools = tools,
                    // 与产品里那几处逐字一样：思考档、强度 high。
                    thinking = ThinkingRequest.of(ThinkingMode.Always, ReasoningEffort.HIGH),
                )
                .toList()
        }

        val one = copied.single()
        assertEquals("inf-npc", one.pool, "落的是哪一池，它是「这一段是谁花的钱」的唯一线索")
        assertEquals("deepseek-flash", one.model)
        assertEquals("enabled", one.thinking)
        assertEquals("high", one.effort)
        assertEquals(listOf("你在吗"), one.messages.map { it.content })
        assertEquals(listOf("say", "note"), one.tools, "它当时看得见哪几只手")
        assertEquals("先看它在不在。", one.reasoning)
        assertEquals("", one.content, "思考档那一趟的自由文本本来就是空的")
        assertEquals(1, one.calls.size)
        assertEquals("say", one.calls.single().function.name)
        assertEquals("""{"text":"你好"}""", one.calls.single().function.arguments)
        assertEquals(800, one.usage?.promptCacheHitTokens)
        assertEquals(100, one.usage?.promptCacheMissTokens)
        assertNull(one.failure)
        assertTrue(one.elapsedMs >= 0)
    }

    /**
     * **递进来的那张表是活的**：调用方常常一轮一轮往同一个 `mutableListOf` 里加（`ShapeModeler` 就是），
     * 而这份记录是在另一个线程上、稍后才序列化的。所以要的是**发出去那一刻的快照**——不拍这一下，症状
     * 是账里那一行被 `ConcurrentModificationException` 吃掉（§14.56 真踩到过：五趟建模调用只剩一句报错）。
     */
    @Test
    fun theRecordedMessagesAreASnapshotNotALiveList() = runBlocking {
        val body = sse(
            """{"choices":[{"delta":{"content":"好"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )

        val live = mutableListOf(ChatMessage.user("第一轮"))
        val copied = mutableListOf<Exchange>()

        withSink({ copied += it }) {
            LlmClient(config, clientReturning(body = body)).streamChat(live).toList()
            // 请求发完之后调用方接着往同一张表里加——记录里**不该**看得见这一条。
            live += ChatMessage.user("第二轮")
        }

        assertEquals(
            listOf("第一轮"),
            copied.single().messages.map { it.content },
            "记录要的是「发出去那一刻」的那一份，而不是一个会跟着变的引用",
        )
    }

    @Test
    fun aCallThatFailsIsStillCopied() = runBlocking {
        val copied = mutableListOf<Exchange>()

        withSink({ copied += it }) {
            val thrown = try {
                LlmClient(config, clientReturning(status = HttpStatusCode.TooManyRequests, body = "{}"))
                    .streamChat(listOf(ChatMessage.user("在吗")))
                    .toList()
                null
            } catch (error: LlmError) {
                error
            }
            assertNotNull(thrown, "端点拒绝时这条路本来就该抛，抄一份不许把它吞掉")
        }

        val one = copied.single()
        assertNotNull(one.failure, "失败的那一趟也要留下原文")
        assertNull(one.usage, "账不全就是账不全——不拿零去冒充它")
        assertEquals(listOf("在吗"), one.messages.map { it.content })
    }

    @Test
    fun withNobodyListeningTheCallIsExactlyWhatItWas() = runBlocking {
        val body = sse(
            """{"choices":[{"delta":{"content":"你好"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )

        assertEquals(null, LlmTrace.sink, "默认没装——这是「零侵入」那句的前提")

        val events = LlmClient(config, clientReturning(body = body))
            .streamChat(listOf(ChatMessage.user("hi")))
            .toList()

        assertEquals("你好", events.filterIsInstance<LlmEvent.Content>().single().text)
    }

    @Test
    fun aSinkThatThrowsDoesNotBreakTheTurn() = runBlocking {
        val body = sse(
            """{"choices":[{"delta":{"content":"你好"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )

        withSink({ error("写账的东西自己坏了") }) {
            val events = LlmClient(config, clientReturning(body = body))
                .streamChat(listOf(ChatMessage.user("hi")))
                .toList()

            assertEquals("你好", events.filterIsInstance<LlmEvent.Content>().single().text)
        }
    }
}
