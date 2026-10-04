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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.k1midi0.infinitesimal.save.LastSave
import io.github.k1midi0.infinitesimal.save.SaveFile
import io.github.k1midi0.infinitesimal.save.SaveLoad
import io.github.k1midi0.infinitesimal.session.GameSession
import io.github.k1midi0.infinitesimal.ui.GameScreen
import io.github.k1midi0.infinitesimal.world.BuiltinPresets
import io.github.k1midi0.infinitesimal.world.BuiltinScripts
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.ScriptLibrary
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

/**
 * 桌面端的入口。
 *
 * 端点、模型、密钥都**不写死在代码里**：密钥先看 `-Dinfinitesimal.liveKeyFile=<路径>`（和测试用的是
 * 同一个开关），再看环境变量 `INFINITESIMAL_API_KEY`，都没有就在界面上要一次。**它只留在内存里**，
 * 不落盘、不写日志。
 *
 * 密钥没配好时不该让玩家对着一个按不动的按钮发呆，所以缺密钥就先给一个要密钥的界面。
 *
 * ## 三层：密钥 → 出厂预设包 → **主界面**
 *
 * §14.49 之前这里是"读到预设包就进酒馆"。现在中间多一个主界面（新游戏 / 继续 / 读取存档）——因为一局
 * 游戏现在可以从一个文件回来，而"从哪个文件回来"是玩家要在进游戏之前回答的问题。
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
                        onSuccess = { library ->
                            // **参考剧本库是另一份资源，而它读不出来不挡路**（§14.55）：一份能玩的游戏没有
                            // 参考剧本，只是导演不会自己排线。所以失败在这里被翻成一句通知，而不是等待界面。
                            val loaded = runCatching { ScriptLibrary(BuiltinScripts.load()) }
                            PresetState.Ready(
                                library = library,
                                references = loaded.getOrElse { ScriptLibrary() },
                                referencesProblem = loaded.exceptionOrNull()?.let {
                                    "参考剧本库读不出来（${it.message ?: it::class.simpleName}）——这一局导演不会自己排线。" +
                                        "它在 shared/src/commonMain/composeResources/files/builtin-scripts.json，" +
                                        "跑一次 spike/ScriptDigest 就能重新生成。"
                                },
                            )
                        },
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

                is PresetState.Ready -> {
                    /*
                     * **换了密钥就是一个新的世界**：整棵界面树跟着 key 重建，上一局那个会话（以及它记着的
                     * 存档路径）一律不作数。
                     */
                    key(key) {
                        AppRoot(
                            config = configOf(key),
                            library = current.library,
                            references = current.references,
                            openingNotice = current.referencesProblem,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一个进程里的两层界面：**主界面**与**游戏**（§14.49）。
 *
 * ## 会话是可空的一个"当前那一局"
 *
 * 用户定的形状是"读档 = 重建一个会话"（`world` 是 `val`，就地换世界要重置十几处派生状态）。所以这里
 * 持有一个 `GameSession?`：为 null 就是主界面，不为 null 就是游戏。
 *
 * ## 挑文件那两个函数从哪儿来
 *
 * 从这一层（桌面端）来——`shared` 那一侧不知道 `java.awt.FileDialog` 存在。传下去的是两个返回路径的
 * 挂起函数，`GameScreen` 只负责在按钮被按下时调用它们。
 */
@Composable
private fun AppRoot(
    config: EndpointConfig,
    library: PresetLibrary,
    references: ScriptLibrary,
    /** 进主界面时先说一句（现在只有一条：参考剧本库读不出来）。 */
    openingNotice: String? = null,
) {
    var session by remember { mutableStateOf<GameSession?>(null) }
    var menuNotice by remember { mutableStateOf(openingNotice) }
    val scope = rememberCoroutineScope()

    // **每次回到主界面都要重新读一遍"上次那一份"**：游戏里刚存过，那一条就变了。
    var lastPath by remember(session) { mutableStateOf(LastSave.path()) }
    var lastHeader by remember(session) { mutableStateOf(lastPath?.let { SaveFile.peek(it) }) }

    /** 开一局新的：**先问存到哪**，选定之后每一回合都会自动存向它。 */
    fun startNewGame() {
        scope.launch {
            val path = pickSavePath() ?: return@launch
            // 参考剧本库那一份挂到会话上（§14.55）：导演要照它填出这一局的线。
            val fresh = GameSession(config, presets = library, references = references)
            // **开局那一批线在进游戏之前排**（§14.55、用户定的"开局填一批，其余留空由导演途中补"）：
            // 玩家等这一次思考档调用，换到的是"第一句话之前，这一带已经有三件事在等着"。
            // 它要一分钟上下，所以界面上先说一句——与后面的通知共用 [menuNotice] 那一行。
            // **排不成不挡人**：世界照样能玩，只是这一局暂时没有线（会话日志里已经写了那一句）。
            menuNotice = "导演正在安排这一局的线……"
            val opened = runCatching { fresh.fillOpeningScripts() }
            menuNotice = opened.exceptionOrNull()?.let {
                "这一局开起来了，但开局那几条线没排出来：${it.message ?: it::class.simpleName}"
            }
            runCatching { fresh.saveTo(path) }
                .onFailure {
                    menuNotice = "这一局开起来了，但第一份存档没写成：${it.message ?: it::class.simpleName}"
                }
            runCatching { fresh.saveTo(path) }
                .onFailure {
                    menuNotice = "这一局开起来了，但第一份存档没写成：${it.message ?: it::class.simpleName}"
                }
            session = fresh
        }
    }

    /** 从一份存档开一局。读不了就留在主界面上，把那句理由说出来。 */
    fun continueFrom(path: String) {
        scope.launch {
            when (val loaded = withContext(Dispatchers.IO) { SaveFile.load(path) }) {
                is SaveLoad.Failed -> menuNotice = loaded.why
                is SaveLoad.Loaded -> {
                    val restored = GameSession.restore(
                        config,
                        loaded.game,
                        presets = library,
                        references = references,
                    )
                    restored.autoSaveTo(path)
                    menuNotice = null
                    session = restored
                }
            }
        }
    }

    val current = session
    if (current == null) {
        MainMenu(
            notice = menuNotice,
            onDismissNotice = { menuNotice = null },
            lastPath = lastPath,
            lastWhen = lastHeader?.savedAt?.let(::stampOf),
            onNewGame = ::startNewGame,
            onContinue = { lastPath?.let(::continueFrom) },
            onOpen = {
                scope.launch {
                    val path = pickOpenPath()
                    if (path != null) continueFrom(path)
                }
            },
        )
    } else {
        GameScreen(
            session = current,
            onPickSavePath = { pickSavePath() },
        )
    }
}

/**
 * **主界面**（§14.49）：三个按钮。
 *
 * 「继续」与「读取存档」的差别只有一处：前一个用的是上次那一份（记在
 * [LastSave] 里），后一个每次问玩家。那一份已经被删掉/搬走时，前一个按钮**是禁的**——让玩家点下去
 * 才发现读不到，不如一开始就说"还没有上次那一份"。
 */
@Composable
private fun MainMenu(
    notice: String?,
    onDismissNotice: () -> Unit,
    lastPath: String?,
    lastWhen: String?,
    onNewGame: () -> Unit,
    onContinue: () -> Unit,
    onOpen: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Card {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("无限小", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "一个以文字为无限精度的世界。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(20.dp))

                    Button(onClick = onNewGame, modifier = Modifier.width(220.dp)) {
                        Text("新游戏")
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onContinue, enabled = lastPath != null) {
                        Text("继续")
                    }
                    Text(
                        if (lastPath == null) {
                            "还没有上次那一份——开一局新的，或者自己挑一个存档文件。"
                        } else {
                            "上次那一份：${shortName(lastPath)}${lastWhen?.let { "（$it）" } ?: ""}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpen) {
                        Text("读取存档")
                    }

                    notice?.let {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onDismissNotice) { Text("知道了") }
                    }
                }
            }
        }
    }
}

private fun shortName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 一个毫秒时间戳，给"上次那一份是什么时候存的"用。 */
private fun stampOf(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(STAMP)

/**
 * 进游戏之前那个很短的状态。
 *
 * 它有两种用法，而两句话都必须是实话：**还在读**与**读坏了**。合成一个"请稍候"会让一次打包错误看起来
 * 像一次慢启动，而这两件事要做的下一步完全不同。
 *
 * ## 两份资源：一份挡路，一份不挡
 *
 * [Ready] 带着出厂预设包与参考剧本库（§14.55）。但两者失败的后果不同：**预设包读不出来就不该进游戏**
 * （那会让每一样东西都现场建模，一次几十秒），而**参考剧本库读不出来照样能玩**——只是导演不会自己排线。
 * 所以后者不挡路，而是变成主界面上的一句 [Ready.referencesProblem]。
 */
private sealed interface PresetState {
    data object Loading : PresetState
    data class Ready(
        val library: PresetLibrary,
        val references: ScriptLibrary,
        val referencesProblem: String? = null,
    ) : PresetState
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

// ── 挑文件（只在桌面端） ──────────────────────────────────────────────────────────

/** 存档的默认扩展名。它只是一个建议——对话框里玩家想写什么都行。 */
private const val SAVE_SUFFIX = ".infsave.json"

/**
 * 问玩家"存到哪"。
 *
 * ## 为什么要在 `Dispatchers.Swing` 上做
 *
 * `java.awt.FileDialog` 与 Swing 一样**只能在事件分发线程上碰**，而 Compose Desktop 的事件线程就是
 * 那一条。在协程里（可能是别的线程）直接 new 一个 `FileDialog` 会得到一个行为未定义的窗口。所以这一
 * 步切到 [Dispatchers.Swing] 上，而它是一个**模态**对话框——切过去之后那一整个事件循环都在等玩家，
 * 这正是模态的意思。
 */
private suspend fun pickSavePath(): String? = withContext(Dispatchers.Swing) {
    java.awt.FileDialog(null as java.awt.Dialog?, "存到哪", java.awt.FileDialog.SAVE).use {
        it.file = "world$SAVE_SUFFIX"
        it.isVisible = true
        it.file?.let { chosen -> "${it.directory}$chosen" }
    }
}

/** 问玩家"读哪一份"。同一个对话框，换成打开模式。 */
private suspend fun pickOpenPath(): String? = withContext(Dispatchers.Swing) {
    java.awt.FileDialog(null as java.awt.Dialog?, "读哪一份", java.awt.FileDialog.LOAD).use {
        it.file = "*.json"
        it.isVisible = true
        it.file?.let { chosen -> "${it.directory}$chosen" }
    }
}

/**
 * `FileDialog` 没有实现 `AutoCloseable`；这一个扩展只是让上面那两处能写成 `use { }`。
 *
 * 关掉它的理由是**模态对话框会占着事件线程**：一次都不关，连开几次之后那个窗口就堆在那儿。
 */
private inline fun <T> java.awt.FileDialog.use(block: (java.awt.FileDialog) -> T): T = try {
    block(this)
} finally {
    dispose()
}
