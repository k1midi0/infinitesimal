package io.github.k1midi0.infinitesimal.save

import io.github.k1midi0.infinitesimal.image.readTextOrNull
import io.github.k1midi0.infinitesimal.image.writeTextFile
import kotlinx.serialization.Serializable

/**
 * **上次用的那个存档文件**（§14.49）——主界面那个「继续」按钮要靠它。
 *
 * 只有一格：[path]。它不是存档本身，只是一句"上次玩的是哪一份"。
 *
 * ## 为什么记在一个小文件里
 *
 * 用户的裁定是主界面三个按钮（新游戏 / 继续 / 读取存档），而"继续"要认得上次那一份。可选的做法有三种：
 * 扫应用目录下最近改动的存档（会在玩家手上有好几份时把他送进别人那一局）、写系统注册表（引入平台细节）、
 * 或者记一个小文件。选了最后一种：**它就是一份文本，玩家看得见、删得掉、搬得走**。
 *
 * 读不出来（第一次玩、文件被删了、内容坏了）就是 null——**它坏了不该报错**，因为那时候正确的行为就是
 * "没有上次那一份"，而那个界面状态本来就存在。
 */
object LastSave {

    /** 玩家主目录下那一个目录名。 */
    const val DIRECTORY_NAME: String = ".infinitesimal"

    /** 那个小文件名。 */
    const val FILE_NAME: String = "last.json"

    /**
     * [path] 的父目录，即 [DIRECTORY_NAME] 那个目录。
     *
     * 它**跟着用户主目录走**。`user.home` 是 JVM 的属性——桌面端就是 JVM，而安卓端将来要么给它一个
     * 真的目录（`Context.filesDir`），要么让那一侧自己实现一个 `expect val`。现在安卓端**没有进构建**
     * （`settings.gradle.kts` 里 `include(":androidApp")` 是注释掉的），所以这里不提前为它分叉；那一侧
     * 真的接起来时，这就是第一个该改的地方（它会取到 null，于是"上次那一份"永远记不住——不会崩，
     * 但会静默地不生效）。
     */
    val directory: String
        get() = overrideDirectory
            ?: "${System.getProperty("user.home").orEmpty().trimEnd('/', '\\')}/$DIRECTORY_NAME"

    /** 那个小文件的完整路径。 */
    val file: String get() = "$directory/$FILE_NAME"

    /**
     * **把那一格挪到别处**——测试用（§14.49）。
     *
     * 它存在的理由很实在：上面那两个路径指向**玩家真实的主目录**，而测试不该去改玩家正在玩的那一局
     * "上次是哪一份"。测的时候把它设成一个临时目录，用完设回 null。
     *
     * 这是**一个可变的全局**，而这类东西通常该避开。这里破例是因为它替掉的是一种更糟的东西：测试要么
     * 去动真文件（还要自己备份还原），要么就只能不测这一格——而"继续按钮指向哪一份"是会出错的那一格。
     */
    var overrideDirectory: String? = null

    /** 上次用的是哪一份存档。没记过、或者记的那一份已经不在磁盘上了，就是 null。 */
    fun path(): String? {
        val text = readTextOrNull(file) ?: return null
        val remembered = try {
            SaveFile.json.decodeFromString(LastSaveFile.serializer(), text).path
        } catch (cause: Throwable) {
            null
        } ?: return null
        // **还要问一句"它还在不在"**：文件被移走/删掉之后，那个"继续"按钮不该还亮着，点下去才发现
        // 读不到（那一下报错是对的，但不该让玩家先点一下才知道）。
        return remembered.takeIf { readTextOrNull(it) != null }
    }

    /** 记下这一次用的那一份。 */
    suspend fun remember(path: String) {
        writeTextFile(file, SaveFile.json.encodeToString(LastSaveFile.serializer(), LastSaveFile(path)))
    }
}

/** 那一个小文件的形状。单独一个类，是为了将来加字段时（例如"上次玩到什么时候"）老文件照样读得进来。 */
@Serializable
data class LastSaveFile(val path: String)
