package io.github.k1midi0.infinitesimal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.session.GameSession
import io.github.k1midi0.infinitesimal.ui.GameScreen
import io.github.k1midi0.infinitesimal.world.BuiltinPresets
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import java.io.File

/**
 * 桌面端的入口。
 *
 * 端点、模型、密钥都**不写死在代码里**：密钥先看 `-Dinfinitesimal.liveKeyFile=<路径>`（和测试用的是
 * 同一个开关），再看环境变量 `INFINITESIMAL_API_KEY`，都没有就在界面上要一次。**它只留在内存里**，
 * 不落盘、不写日志。
 *
 * 密钥没配好时不该让玩家对着一个按不动的按钮发呆，所以缺密钥就先给一个要密钥的界面。
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "无限小",
        state = rememberWindowState(size = DpSize(1400.dp, 900.dp)),
    ) {
        var apiKey by remember { mutableStateOf(keyFromEnvironment()) }
        val key = apiKey
        if (key.isBlank()) {
            KeyScreen(onReady = { apiKey = it })
        } else {
            // 出厂预设包是**资源文件**，读它是挂起的，所以进游戏之前有一个很短的状态（§12 第 36 项）。
            // 它不该静默退回空表：出厂包空着与读不出来是两件完全不同的事（BuiltinPresets 的注释）。
            val state by produceState<PresetState>(PresetState.Loading, key) {
                value = runCatching { PresetLibrary(BuiltinPresets.load()) }
                    .fold(
                        onSuccess = { PresetState.Ready(it) },
                        onFailure = { PresetState.Failed(it.message ?: it.toString()) },
                    )
            }
            when (val current = state) {
                is PresetState.Loading -> WaitingScreen("正在读出厂预设包……")
                is PresetState.Failed -> WaitingScreen(
                    "出厂预设包读不出来：${current.why}\n\n" +
                        "它在 shared/src/commonMain/composeResources/files/builtin-presets.json，" +
                        "跑一次 spike/PresetSweep 就能重新生成。",
                )

                // **按密钥 remember**：换了密钥就是一个新的世界，不能把上一个会话接着用。
                is PresetState.Ready -> {
                    val session = remember(key, current.library) {
                        GameSession(configOf(key), presets = current.library)
                    }
                    GameScreen(session)
                }
            }
        }
    }
}

/**
 * 进游戏之前那个很短的状态。
 *
 * 它有两种用法，而两句话都必须是实话：**还在读**与**读坏了**。合成一个"请稍候"会让一次打包错误看起来
 * 像一次慢启动，而这两件事要做的下一步完全不同。
 */
private sealed interface PresetState {
    data object Loading : PresetState
    data class Ready(val library: PresetLibrary) : PresetState
    data class Failed(val why: String) : PresetState
}

@Composable
private fun WaitingScreen(message: String) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private const val BASE_URL = "https://api.deepseek.com"

/**
 * 只有 `deepseek-flash`。`deepseek-v4-pro` 正在退场，新项目不该绑它（§10.2）。
 *
 * `ThinkingMode.Never` 是显式的：**不传这个字段就是默认开启思考**，那会白烧近半输出——渲染这条路
 * 上没有任何需要推理的地方。
 */
private fun configOf(apiKey: String) = EndpointConfig(
    baseUrl = BASE_URL,
    apiKey = apiKey,
    model = "deepseek-flash",
    thinking = ThinkingMode.Never,
)

/** 密钥的来路，按优先级。返回空串表示没找到。 */
private fun keyFromEnvironment(): String {
    val path = System.getProperty("infinitesimal.liveKeyFile").orEmpty()
    if (path.isNotBlank()) {
        val file = File(path)
        if (!file.isFile) {
            // 说了路径却读不到，这是配置错误，不该静默退回"去界面上要一次"。
            error("infinitesimal.liveKeyFile 指向的不是一个文件：$path")
        }
        return file.readText().trim()
    }
    return System.getenv("INFINITESIMAL_API_KEY").orEmpty().trim()
}

@Composable
private fun KeyScreen(onReady: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card {
                Column(Modifier.padding(20.dp)) {
                    Text("无限小", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "还没有密钥。填一个，或者用下面任一种方式给它：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "· 启动参数 -Dinfinitesimal.liveKeyFile=<密钥文件路径>\n" +
                            "· 环境变量 INFINITESIMAL_API_KEY",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it },
                            modifier = Modifier.width(420.dp),
                            label = { Text("API key") },
                            singleLine = true,
                        )
                        Spacer(Modifier.width(10.dp))
                        Button(onClick = { onReady(text.trim()) }, enabled = text.isNotBlank()) {
                            Text("开始")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "它只留在内存里，不落盘。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
