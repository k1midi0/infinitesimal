package io.github.k1midi0.infinitesimal.world

import infinitesimal.shared.generated.resources.Res
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * **出厂参考剧本库**（§14.55）。
 *
 * ## 它与出厂预设包同一条路
 *
 * 一条随游戏带来的数据、放在资源文件里、由 `spike/ScriptDigest` 那个**离线工具**重新生成。区别只在
 * **谁来生成**：预设是扫一批要求画出来的，参考剧本是拆一部小说/剧本拆出来的。
 *
 * ## 为什么出厂这一份是**手写**的种子
 *
 * 因为"一手素材"在仓库里不存在（那是玩家自己的小说），而一个空库会让整条第一层在真游戏里**一次都不被
 * 走到**——这正是 §14.51 那个"发言名单从来没被带进世界"的同一种病。所以随游戏带四条**概括性的**
 * 骨架（没有人名、没有地名，只有处境与角色位），让开局那一次填充有东西可填。
 *
 * 用户跑一次 `spike/ScriptDigest`（把 `-PinfinitesimalNovelFile` 指到自己的小说）就会往这个文件里
 * **追加**拆出来的那些——工具是增量的，手写这几条因此不会被冲掉，`from` 那一格分得清谁是谁。
 *
 * ## 读不出来时报出去
 *
 * 与 [BuiltinPresets] 同一条纪律：库空着是"我们没拆过"，读不出来是"打包或者路径坏了"，两件事在调用
 * 点要分得开。这一份的失败**不挡进游戏**（没有参考剧本也能玩，只是导演不会自己排线），所以由调用点
 * 把它变成主界面上的一句通知，而不是一个等待界面。
 */
object BuiltinScripts {

    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): List<ReferenceScript> {
        val text = Res.readBytes(PATH).decodeToString()
        return json.decodeFromString<ReferencePack>(text).scripts
    }

    /** 资源路径。它与 `spike/ScriptDigest` 写的那个文件是同一个位置。 */
    const val PATH = "files/builtin-scripts.json"
}
