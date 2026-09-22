package io.github.k1midi0.infinitesimal.llm

import io.github.k1midi0.infinitesimal.image.ImageFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 附图在请求里长什么样。
 *
 * 这是整件事里最要紧的一处：**有图时 `content` 必须是一个块数组，没图时仍然是一个字符串**。搞错
 * 的代价由端点说出来（400），而那一次请求里还有一整套对话——所以这里逐字节钉住形状。
 */
class WireMessagesTest {

    @Test
    fun aMessageWithoutImagesStaysPlainText() {
        val wire = ChatMessage.user("在吗").toWire { null }

        assertEquals(JsonPrimitive("在吗"), wire.content, "没有图时 content 仍然是字符串")
    }

    @Test
    fun aMessageWithImagesBecomesBlocksWithDataUrls() {
        val wire = ChatMessage.user("看这个", images = listOf("a.png")).toWire { PNG }

        val blocks = assertIs<JsonArray>(wire.content)
        assertEquals(2, blocks.size, "一句字加一张图，两个块")

        val text = assertIs<JsonObject>(blocks[0])
        assertEquals("text", (text["type"] as JsonPrimitive).content)
        assertEquals("看这个", (text["text"] as JsonPrimitive).content)

        val image = assertIs<JsonObject>(blocks[1])
        assertEquals("image_url", (image["type"] as JsonPrimitive).content)
        val url = ((image["image_url"] as JsonObject)["url"] as JsonPrimitive).content
        assertTrue(url.startsWith("data:image/png;base64,"), url)
        assertTrue(url.endsWith(base64(PNG)), "数据 URL 里必须是那个文件逐字节的 base64：$url")
    }

    /**
     * 只有图、没有字的时候，块数组里就只有一个块。
     *
     * 这是真会发生的：人把一张截图拖进来就按发送，一个字都不打。
     */
    @Test
    fun imagesWithoutAnyTextAreStillABlockArray() {
        val wire = ChatMessage.user("", images = listOf("a.png")).toWire { PNG }

        val blocks = assertIs<JsonArray>(wire.content)
        assertEquals(1, blocks.size)
        assertEquals("image_url", ((blocks[0] as JsonObject)["type"] as JsonPrimitive).content)
    }

    @Test
    fun everyFormatGetsItsOwnMimeType() {
        for (format in ImageFormat.entries) {
            val wire = ChatMessage.user("", images = listOf("a." + format.extension)).toWire {
                when (format) {
                    ImageFormat.Png -> PNG
                    ImageFormat.Jpeg -> byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)
                    ImageFormat.Gif -> "GIF89a".encodeToByteArray()
                    ImageFormat.Webp -> "RIFF".encodeToByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".encodeToByteArray()
                }
            }

            val blocks = assertIs<JsonArray>(wire.content)
            val url = (((blocks[0] as JsonObject)["image_url"] as JsonObject)["url"] as JsonPrimitive).content
            assertTrue(url.startsWith("data:${format.mimeType};base64,"), url)
        }
    }

    /**
     * 图只能跟着 user 消息走。
     *
     * 这不是保守，是协议：system 或 assistant 带图，端点是直接 400。把它挡在这里，错误就落在一个
     * 读得懂的地方，而不是落在一段 SSE 的错误载荷里。
     */
    @Test
    fun onlyUserMessagesMayCarryImages() {
        val assistant = ChatMessage(ChatMessage.ROLE_ASSISTANT, "嗯", images = listOf("a.png"))
        val system = ChatMessage(ChatMessage.ROLE_SYSTEM, "说明", images = listOf("a.png"))

        val failure = assertFailsWith<IllegalArgumentException> { assistant.toWire { PNG } }
        assertTrue(failure.message?.contains("user") == true, failure.message)
        assertFailsWith<IllegalArgumentException> { system.toWire { PNG } }
    }

    /**
     * 图没了就当场说清楚是哪一张。
     *
     * 沉默跳过会让模型以为对方从来没发过图，而那与真相差得很远——它会把接下来那句话建立在「没有图」
     * 的前提上，而没有任何东西会报错。
     */
    @Test
    fun aMissingImageStopsTheRequestAndNamesItself() {
        val failure = assertFailsWith<IllegalStateException> {
            ChatMessage.user("看这个", images = listOf("gone.png")).toWire { null }
        }

        assertTrue(failure.message?.contains("gone.png") == true, failure.message)
    }

    @Test
    fun aFileThatIsNoLongerAnImageIsRefusedToo() {
        val failure = assertFailsWith<IllegalStateException> {
            ChatMessage.user("看这个", images = listOf("a.png")).toWire { "已经不是图了".encodeToByteArray() }
        }

        assertTrue(failure.message?.contains("a.png") == true, failure.message)
    }

    private fun base64(bytes: ByteArray): String {
        @OptIn(ExperimentalEncodingApi::class)
        return Base64.Default.encode(bytes)
    }

    private companion object {
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
        )
    }
}
