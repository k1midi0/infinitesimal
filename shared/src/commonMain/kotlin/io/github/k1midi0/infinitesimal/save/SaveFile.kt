package io.github.k1midi0.infinitesimal.save

import io.github.k1midi0.infinitesimal.image.readTextOrNull
import io.github.k1midi0.infinitesimal.image.writeTextFile
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.ScriptLibrary
import kotlinx.serialization.json.Json

/**
 * **存档的读写**：整份读回来、整份写出去、以及"只扫一眼头部"（§14.49）。
 *
 * ## 三件事各占一个函数，而错误都落在返回值上
 *
 * 存档是这个应用里**唯一一处会读到"别人写的东西"**的地方（玩家可能拷过来一份、可能是上一版写的、
 * 可能拷坏了）。所以这三个函数**一个异常都不往外抛**：读到坏档时抛出去的话，调用点只能接住一个
 * `SerializationException`，而那句话对玩家没有意义。这里把"哪一步坏的"翻成人话（§1.5 第三条：驳回
 * 理由不撒谎）。
 *
 * ## 为什么是紧凑 JSON
 *
 * 开局那份世界算下来是 **12,544 列 / 37,632 段地形**（§14.49 记了算式）。`prettyPrint` 会把文件撑到
 * 好几 MB 而只有人看得出差别——那不是给人读的东西，是给程序读的。
 */
object SaveFile {

    /**
     * 读写用的那一份 `Json`。
     *
     * `encodeDefaults = true`：存档要**写明每一个默认值**，而不是靠读的那一边去补。理由是版本——将来
     * 改了一个默认值，读回来的旧档不该跟着变（写明的那些是当时的事实）。
     *
     * `ignoreUnknownKeys = true`：读到**比我们新**的一份存档时，多出来的字段跳过而不是报错。加上
     * `encodeDefaults`，这让"新版本写的档能被旧版本读"也成立。
     */
    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * 扫一眼头部：那一份存档是哪个格式版本写的、什么时候存的、游戏版本是什么（§14.49）。
     *
     * 读不出来（文件不在、坏了、不是存档）就是 null——**这一条不报错**，因为它的用途是"主界面上那个
     * 继续按钮要不要亮、上面写什么"，而那件事的失败是静默的。
     *
     * 做法是**拿正则从文件开头那一小块里抠出三个字段**，而不是解整份 JSON：`kotlinx.serialization`
     * 按声明的顺序写字段，而 `format` / `game` / `savedAt` 是前三格，所以它们必出现在开头。真正的校验
     * 在 [load] 那一边（它会整份解一遍）。
     */
    fun peek(path: String): SaveHeader? {
        val head = readTextOrNull(path)?.take(HEAD_CHARS) ?: return null
        if (!head.startsWith("{")) return null
        return SaveHeader(
            format = number(head, "format") ?: return null,
            game = string(head, "game").orEmpty(),
            savedAt = long(head, "savedAt") ?: 0L,
        )
    }

    /** 整份读回来。读不了就是 [SaveLoad.Failed]，理由是一句给人看的话。 */
    fun load(path: String): SaveLoad {
        val text = readTextOrNull(path)
            ?: return SaveLoad.Failed("读不到这个文件：$path")
        if (text.isBlank()) return SaveLoad.Failed("这个文件是空的：$path")

        val header = peek(path) ?: return SaveLoad.Failed("这不像是这个游戏的存档（开头读不出格式版本）：$path")
        if (header.format > SaveGame.FORMAT) {
            return SaveLoad.Failed(
                "这一份存档是**更新**的版本写的（格式 ${header.format}，这个程序只认到 ${SaveGame.FORMAT}）——" +
                    "把游戏更新到比它新的那一版再读。",
            )
        }
        if (header.format < SaveGame.FORMAT) {
            return SaveLoad.Failed(
                "这一份存档的格式（${header.format}）比这个程序认的（${SaveGame.FORMAT}）旧，读不了。",
            )
        }

        return try {
            SaveLoad.Loaded(json.decodeFromString(SaveGame.serializer(), text))
        } catch (cause: Throwable) {
            SaveLoad.Failed("这一份存档读坏了，解到一半就出错：${cause.message ?: cause::class.simpleName}")
        }
    }

    /**
     * 整份写出去。写不进去会抛（磁盘满、路径是目录、没有权限）——见 [writeTextFile] 那一段。
     *
     * [savedAt] 由调用点给（"现在几点"是调用点的事，而这一层不做时间）。
     */
    suspend fun save(path: String, game: SaveGame) {
        writeTextFile(path, json.encodeToString(SaveGame.serializer(), game))
    }

    /**
     * 把生成包装进 [library]（读档那条路要用）。
     *
     * 它在这里而不是在 `GameSession` 里，是因为**"生成包怎么装"这件事只有 `PresetLibrary` 知道**
     * （它内部是个保序的 `LinkedHashMap`，见 `world/PresetLibrary.kt:32` 那段注释）。
     */
    fun installPresets(library: PresetLibrary, game: SaveGame) = library.restore(game.presets)

    /**
     * 把参考剧本库装回 [library]（读档那条路要用，§14.55）。
     *
     * 与 [installPresets] 同一个理由：**"怎么装"只有库自己知道**（[ScriptLibrary.restore] 是合并而不是
     * 清空——出厂那份在存档之外，一份没有参考剧本的旧档不该把它一起清掉）。
     */
    fun installReferences(library: ScriptLibrary, game: SaveGame) = library.restore(game.references)

    /**
     * 从文件开头往下读多少**字符**去找那三个头字段。
     *
     * 不是"读文件开头多少字节"：`readTextOrNull` 整份读进来，这一刀只是别拿一段地形去正则匹配。
     * 512 个字符够放下 `{"format":1,"game":"0.1.0","savedAt":1789...` 这一整行，而它后面紧跟着的就是
     * 那一大片地形。
     */
    private const val HEAD_CHARS = 512

    private fun number(head: String, key: String): Int? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull()

    private fun string(head: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(head)?.groupValues?.get(1)

    /** 与 [number] 分开，因为 `savedAt` 是毫秒时间戳——它超过 `Int`。 */
    private fun long(head: String, key: String): Long? =
        Regex("\"$key\"\\s*:\\s*(-?\\d+)").find(head)?.groupValues?.get(1)?.toLongOrNull()
}

/** 读一份存档的结果。**失败是一句话**，不是一个异常——它要出现在界面上的那一条通知里。 */
sealed interface SaveLoad {

    data class Loaded(val game: SaveGame) : SaveLoad

    data class Failed(val why: String) : SaveLoad
}
