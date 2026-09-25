package io.github.k1midi0.infinitesimal.llm

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 助手消息上线的形状。
 *
 * 序列化器的配置与 [LlmClient] 那边**逐字一致**：`encodeDefaults = true; explicitNulls = false`。
 * 换掉它，同一个对象会拼出不同的请求体，而那种差别只会表现为「某几条消息偶尔被拒」。
 */
class ChatMessageWireTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private fun wire(message: ChatMessage): String =
        json.encodeToString(WireMessage.serializer(), message.toWire { null })

    /**
     * **这一条是一个真 bug 的回归。**
     *
     * 建模那一层在「模型一个工具都没调」时会把助手消息原样记进历史（它接着要拿这条历史去追问），
     * 于是 `tool_calls` 是一个**空数组**。端点不接受它：
     *
     *     Invalid 'messages[N].tool_calls': empty array.
     *     Expected an array with minimum length 1, but got an empty array instead.
     *
     * 字段该整个不出现。它在真机上发作过一次，症状是整次生成 400。
     */
    @Test
    fun anAssistantWithNoToolCallsDoesNotCarryTheFieldAtAll() {
        val text = wire(ChatMessage.assistant(content = "没什么要做的。", reasoningContent = ""))

        assertFalse(text.contains("tool_calls"), text)
    }

    @Test
    fun anAssistantThatDidCallSomethingCarriesThem() {
        val text = wire(
            ChatMessage.assistant(
                reasoningContent = "",
                toolCalls = listOf(ToolCall("c1", function = FunctionCall("fill_terrain", "{}"))),
            ),
        )

        assertTrue(text.contains("\"tool_calls\":["), text)
        assertTrue(text.contains("fill_terrain"), text)
    }

    @Test
    fun anExplicitEmptyListIsTreatedTheSameWay() {
        val text = wire(ChatMessage.assistant(content = "嗯。", toolCalls = emptyList()))

        assertFalse(text.contains("tool_calls"), text)
    }

    /** 没有思考时给空串、不给 null：带 tools 的请求里这个字段必须在（§1.2.1）。 */
    @Test
    fun theReasoningFieldIsWrittenEvenWhenItIsEmpty() {
        val text = wire(ChatMessage.assistant(content = "嗯。", reasoningContent = ""))

        assertTrue(text.contains("\"reasoning_content\":\"\""), text)
    }
}
