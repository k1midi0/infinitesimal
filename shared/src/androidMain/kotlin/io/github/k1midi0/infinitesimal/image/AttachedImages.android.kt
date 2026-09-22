package io.github.k1midi0.infinitesimal.image

import java.io.File

internal actual fun readBytesOrNull(path: String): ByteArray? = try {
    File(path).takeIf { it.isFile }?.readBytes()
} catch (cause: Throwable) {
    null
}
