package io.github.k1midi0.infinitesimal.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 按内容认格式。
 *
 * 官方文档写着格式由文件实际内容判断，而不是文件名或声明的 MIME 类型——所以这一层只看开头那几个
 * 字节。认错的代价是一次 400，而认错的方式很具体：一个扩展名是 .png 而内容是文本的文件。
 */
class ImageFormatTest {

    @Test
    fun recognisesTheFourFormatsByTheirFirstBytes() {
        assertEquals(ImageFormat.Png, sniffImageFormat(PNG))
        assertEquals(ImageFormat.Jpeg, sniffImageFormat(JPEG))
        assertEquals(ImageFormat.Gif, sniffImageFormat(GIF_87A))
        assertEquals(ImageFormat.Gif, sniffImageFormat(GIF_89A))
        assertEquals(ImageFormat.Webp, sniffImageFormat(WEBP))
    }

    @Test
    fun refusesAnythingElse() {
        assertNull(sniffImageFormat("这不是图，只是一句话。".encodeToByteArray()), "文本不是图")
        assertNull(sniffImageFormat(ByteArray(0)), "空文件什么都不是")
        assertNull(sniffImageFormat(byteArrayOf(0x89.toByte(), 0x50, 0x4E)), "太短，读不出魔数就不是")
        assertNull(sniffImageFormat(byteArrayOf(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x41, 0x56, 0x49, 0x20)), "RIFF 里不是 WEBP")
    }

    @Test
    fun theExtensionsMatchTheFormatsTheEndpointAccepts() {
        assertEquals("image/jpeg", ImageFormat.Jpeg.mimeType)
        assertEquals("image/png", ImageFormat.Png.mimeType)
        assertEquals("image/gif", ImageFormat.Gif.mimeType)
        assertEquals("image/webp", ImageFormat.Webp.mimeType)
    }

    private companion object {
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13,
        )
        val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16)
        val GIF_87A = "GIF87a".encodeToByteArray() + byteArrayOf(1, 0, 1, 0)
        val GIF_89A = "GIF89a".encodeToByteArray() + byteArrayOf(1, 0, 1, 0)
        val WEBP = "RIFF".encodeToByteArray() + byteArrayOf(0x24, 0, 0, 0) + "WEBP".encodeToByteArray()
    }
}
