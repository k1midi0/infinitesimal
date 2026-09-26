package io.github.k1midi0.infinitesimal.save

import io.github.k1midi0.infinitesimal.npc.ConversationSnapshot
import io.github.k1midi0.infinitesimal.world.Preset
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlinx.serialization.Serializable

/**
 * **一份存档**（§14.49）。
 *
 * 装三样，缺一样都会让读回来的人觉得"这不是刚才那一局"：
 *
 *  - [world] —— 世界本身（实体、规则书、时钟、时间轴、材质表、地形、区划、事件记录、发言名单）。
 *    这一份的字段本来就在 `WorldSnapshot` 里齐了（`world/Entity.kt:147`）。
 *  - [conversations] —— 每个角色的对话与记忆，**含压缩摘要**。摘要是当初模型给的一句话，**重算不出来**。
 *  - [presets] —— 运行时攒下的生成包。不带它，读回来后新长一块地要重新建模（一次几十秒）。
 *
 * ## 版本那两格：一个说文件、一个说游戏
 *
 * [format] 是**文件形状**的版本：字段怎么排、哪些必有。读到不认识的 [format] 就该**拒绝**——照着猜读
 * 一份旧档，最坏的结果是"读进来了，但某个字段悄悄是错的"（§1.5：驳回理由不撒谎）。
 *
 * [game] 是**游戏版本**（`gradle.properties` 里的 `infinitesimal.version`）。它现在只被记下来、显示给
 * 人看，不参与判断：世界快照那条路是**向前兼容**的（新字段一律给默认值，老档照读），所以版本不同不等于
 * 读不了。
 *
 * ## 为什么不把 `World` 本身标成可序列化
 *
 * 因为 `World` 里有 `Terrain` / `EventLog` / `Timeline` 这些**带内部映射与不变式的活对象**。序列化它们
 * 等于把这些不变式搬到 JSON 上守一遍，而 `WorldSnapshot` 这条"数据出去、不变式留在类里"的路已经在了
 * （`world/Entity.kt:144` 那段注释：`world/` 不做持久化）。存档这一层只搬数据。
 */
@Serializable
data class SaveGame(
    val format: Int = FORMAT,
    val game: String = "",
    val savedAt: Long = 0L,
    /**
     * 世界本身。**默认是一份空世界**而不是 `WorldSnapshot()`——后者没有默认构造（`entities` 是必填），
     * 而"空"在这里要表达的意思是"还没有世界"，不是"一个 0 实体 0 地形的合法世界"。
     */
    val world: WorldSnapshot = WorldSnapshot(emptyList()),
    val conversations: List<ConversationSnapshot> = emptyList(),
    val presets: List<Preset> = emptyList(),
) {
    companion object {
        /**
         * 文件形状的版本。**改动字段的语义与必有时加一**，加字段（带默认值）不用。
         */
        const val FORMAT: Int = 1
    }
}

/**
 * 一份存档的**头部信息**：不解整份文件就能读出来的那几格（§14.49）。
 *
 * 主界面的「继续」要显示"那一局是什么时候存的"，而读取一个几 MB 的 JSON 只为拿一个时间戳是不划算的。
 */
@Serializable
data class SaveHeader(
    val format: Int = SaveGame.FORMAT,
    val game: String = "",
    val savedAt: Long = 0L,
)
