package io.github.k1midi0.infinitesimal.image

import java.io.File

internal actual fun readBytesOrNull(path: String): ByteArray? = try {
    File(path).takeIf { it.isFile }?.readBytes()
} catch (cause: Throwable) {
    // 读不出来（不存在、是目录、没有权限）就是「这里没有东西」。目录是「文件系统拒绝了」，
    // 那一种会从上面抛出去。
    null
}
