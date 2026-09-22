package io.github.k1midi0.infinitesimal.llm

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * 两条只在**请求体**里看得出来的规则，用一个 mock 端点逐字钉住。
 *
 * 它们都不报错，也不会让哪一次调用失败得不一样——错了的症状是「端点拒绝了整条请求」或者
 * 「端点用了自己那个默认值」，而两者都要等到账单或真机上才看得见。
 */
class ToolRequestReasoningTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
        reasoningEffort = ReasoningEffort.MAX,
    )

    private class Recorder {
        var body: String? = null
    }

    private fun recordingClient(recorder: Recorder): HttpClient = HttpClient(
        MockEngine { request ->
            recorder.body = (request.body as? TextContent)?.text
            respond(
                content = "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\ndata: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
            )
        },
    ) {
        expectSuccess = false
    }

    private fun oneTool(): List<ToolDefinition> = listOf(
        ToolDefinition(
            function = FunctionSpec(
                name = "say",
                description = "说一句话",
                parameters = JsonSchema(),
            ),
        ),
    )

    private fun assistantWithoutReasoning(): ChatMessage = ChatMessage.assistant(
        content = null,
        toolCalls = listOf(ToolCall(id = "call_1", function = FunctionCall(name = "say", arguments = "{}"))),
    )

    /**
     * 带 tools 的请求里，一条没有思考的 assistant 消息也必须带上 `reasoning_content`，值是空串。
     *
     * 「不发这一项」与「发一个空值」对端点是两件事：前者会让整条请求被拒，而拒绝的原因与
     * 「这一轮没有思考」看不出任何关系。
     */
    @Test
    fun everyAssistantMessageCarriesReasoningContentWhenToolsAreSent() = runBlocking {
        val recorder = Recorder()

        LlmClient(config, recordingClient(recorder))
            .streamChat(
                messages = listOf(
                    ChatMessage.user("你好"),
                    assistantWithoutReasoning(),
                    ChatMessage.toolResult("call_1", "ok"),
                ),
                tools = oneTool(),
            )
            .toList()

        val body = recorder.body.orEmpty()
        assertContains(body, """"role":"assistant"""", message = body)
        assertContains(body, """"reasoning_content":"""", message = body)
    }

    /**
     * 不发 tools 的请求照旧可以缺这一项——端点对那种形状没有这条要求，多发一项就是多一句
     * 它没准备听的话。
     */
    @Test
    fun theFieldIsNotAddedWhenNoToolIsSent() = runBlocking {
        val recorder = Recorder()

        LlmClient(config, recordingClient(recorder))
            .streamChat(listOf(ChatMessage.user("你好"), assistantWithoutReasoning()))
            .toList()

        assertFalse(recorder.body.orEmpty().contains("reasoning_content"), recorder.body.orEmpty())
    }

    /**
     * 思考关着的时候，强度那一项根本不出现在请求体里，而不是出现成 null。
     */
    @Test
    fun aDisabledThinkingModeLeavesTheEffortOutOfTheBodyEntirely() = runBlocking {
        val recorder = Recorder()

        LlmClient(config, recordingClient(recorder))
            .streamChat(
                messages = listOf(ChatMessage.user("你好")),
                thinking = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.MAX),
            )
            .toList()

        val body = recorder.body.orEmpty()
        assertContains(body, """"type":"disabled"""", message = body)
        assertFalse(body.contains("reasoning_effort"), body)
    }
}
