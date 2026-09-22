package io.github.k1midi0.infinitesimal.llm

import io.github.k1midi0.infinitesimal.image.sniffImageFormat
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 一条要发出去的消息。
 *
 * 与 [ChatMessage] 只差一处，而那一处是协议的形状：**有图时 `content` 是一个块数组，没图时是一个
 * 字符串**。同一个字段两种形状没法用两个属性表达，所以分成两层——应用里那一层（[ChatMessage]，图按
 * 文件名给）与线上这一层（图已经是数据 URL）。
 */
@Serializable
data class WireMessage(
    val role: String,
    val content: JsonElement? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

/**
 * 把一条消息翻成线上那一份。
 *
 * [readImage] 是按文件名取字节，注入进来是为了让这一层不碰磁盘：测试递一个假的读取器，就能把块数组
 * 与数据 URL 逐字节钉住，而线上那一份由调用方指向数据目录（见 LlmClient）。
 *
 * 取不到字节、或者取到的已经不是那四种图片里的一种时**抛**，不沉默跳过：那条消息里的图是对方发来的
 * 东西，悄悄少一张等于让模型以为反面从来没发过图。抛出会带着文件名，人能看懂是哪一张。
 */
@OptIn(ExperimentalEncodingApi::class)
fun ChatMessage.toWire(readImage: (String) -> ByteArray?): WireMessage {
    require(images.isEmpty() || role == ChatMessage.ROLE_USER) {
        "只有 user 消息可以带图，这一条是 $role——端点在 system 与 assistant 上会直接返回 400"
    }

    return WireMessage(
        role = role,
        content = contentOf(readImage),
        reasoningContent = reasoningContent,
        toolCalls = toolCalls,
        toolCallId = toolCallId,
    )
}

@OptIn(ExperimentalEncodingApi::class)
private fun ChatMessage.contentOf(readImage: (String) -> ByteArray?): JsonElement? {
    if (images.isEmpty()) return content?.let { JsonPrimitive(it) }

    val blocks = buildList {
        // 文本块在前、图在后。两种顺序端点都收，而先给字再给图，与人对着一张图开口的顺序一致。
        if (!content.isNullOrEmpty()) {
            add(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(content))))
        }

        for (fileName in images) {
            val bytes = readImage(fileName) ?: error("附图 $fileName 已经不在数据目录里了")
            val format = sniffImageFormat(bytes)
                ?: error("附图 $fileName 已经不是那四种图片里的一种了")

            add(
                JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("image_url"),
                        "image_url" to JsonObject(
                            mapOf(
                                "url" to JsonPrimitive(
                                    "data:${format.mimeType};base64,${Base64.Default.encode(bytes)}",
                                ),
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    return JsonArray(blocks)
}
