package io.github.k1midi0.infinitesimal.image

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun readBytesOrNull(path: String): ByteArray? = try {
    File(path).takeIf { it.isFile }?.readBytes()
} catch (cause: Throwable) {
    null
}

internal actual fun readTextOrNull(path: String): String? = try {
    File(path).takeIf { it.isFile }?.readText()
} catch (cause: Throwable) {
    null
}

internal actual suspend fun writeTextFile(path: String, text: String) {
    withContext(Dispatchers.IO) {
        val file = File(path)
        val temporary = File(file.parentFile, "${file.name}.writing")
        file.parentFile?.mkdirs()
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            file.delete()
            if (!temporary.renameTo(file)) {
                temporary.delete()
                error("存档写不进去：$path")
            }
        }
    }
}

internal actual fun appendTextToFile(path: String, text: String) {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.appendText(text)
}
