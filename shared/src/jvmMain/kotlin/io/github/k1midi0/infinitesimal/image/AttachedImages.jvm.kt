package io.github.k1midi0.infinitesimal.image

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun readBytesOrNull(path: String): ByteArray? = try {
    File(path).takeIf { it.isFile }?.readBytes()
} catch (cause: Throwable) {
    // 读不出来（不存在、是目录、没有权限）就是「这里没有东西」。目录是「文件系统拒绝了」，
    // 那一种会从上面抛出去。
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
        // **先写临时文件、再原子改名**：写到一半断电/被杀，留下的是一份完整的旧档，而不是一份半截的
        // 新档——而半截的新档读回来是一句解析错误，玩家看到的是"存档坏了"。
        val temporary = File(file.parentFile, "${file.name}.writing")
        file.parentFile?.mkdirs()
        temporary.writeText(text)
        if (!temporary.renameTo(file)) {
            // 有些文件系统上跨越已存在目标的改名会失败（Windows 上一般不失败，但这不是保证）。
            // 退一步：删掉旧的再改名——这一步比"写不进去"轻，因为它只在那一条路上发生。
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
