package io.github.k1midi0.infinitesimal.world

import infinitesimal.shared.generated.resources.Res
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * **出厂预设包**：随游戏带来的那批形状（§12 第 36 项、附录 A.14）。
 *
 * ## 为什么是资源文件，不是源码
 *
 * 这是用户定的。一批形状有几千行网格文本，写进 `.kt` 会把源码淹掉；而它同时又是**可以重扫的数据**
 * ——`spike/PresetSweep` 跑一遍就能重新生成，产物不该与别的代码混在一处。
 *
 * 代价是一条异步加载路径：Compose 的资源 API 是挂起的。所以 [load] 是 `suspend`，**在进游戏之前读
 * 一次**，读到的表交给会话（预设库该挂在会话上，判定与生成共用同一份——A.14 记的那条）。
 *
 * ## 读不出来时怎么办
 *
 * **不静默退回空表。** 出厂包是"随游戏带来的"那一份，它空着与它读不出来是两件完全不同的事：前者是
 * 我们没扫，后者是打包或路径坏了。所以这里把两类错误都抛出去，让调用方去处理——一条安静的
 * `catch` 会让"预设库怎么全在现造"这个问题查一整天。
 */
object BuiltinPresets {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 从资源里读出厂包。
     *
     * 空表是**合法**的（扫之前就是空的），而读不出来不是——见类注释。文件里存的是网格文本
     * （[PresetPack] 写了为什么），所以这里要过一遍 [ShapeGrid.parse]；哪一条读不成，就在启动时
     * 炸掉，而不是让它变成"这东西怎么一直在现造"。
     */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(): List<Preset> {
        val text = Res.readBytes(PATH).decodeToString()
        return json.decodeFromString<PresetPack>(text).presets.map { it.toPreset() }
    }

    /** 资源路径。它与 `spike/PresetSweep` 写的那个文件是同一个位置。 */
    const val PATH = "files/builtin-presets.json"
}
