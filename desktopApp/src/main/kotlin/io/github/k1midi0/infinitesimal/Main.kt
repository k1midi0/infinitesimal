package io.github.k1midi0.infinitesimal

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * 桌面端的入口。
 *
 * 窗口里现在只有名字。真正的东西住在 shared 里，这一层只负责把窗口开出来。
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "无限小",
    ) {
        Text("无限小")
    }
}
