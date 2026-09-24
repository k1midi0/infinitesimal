package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * 世界里的一个东西。物体、角色、地点都是它——**只有一种类型**（§3.1 统一 Actor 的起点）。
 *
 * 几个字段各自背着一条已经定下来的规则，写在这里免得日后有人"顺手"改掉：
 *
 *  - [x]/[y]/[z]：体素坐标，离散三维整数（附录 A.2）。**不发给渲染器**——给了它，它就会写位置
 *    关系（§7.6 实测过）。
 *  - [parent]：**只用来取背景和做归属，同样不发给渲染器**（§7.6）。
 *  - [background]：地点背景，**由导演写，写完不变**（§7.5）。
 *  - [sentences]：固化的描述，**按句存**。按句存是"只增不改"能成立的原因（§14.2）——唯一的写入口
 *    是插入，已有的句子没有被改的机会。外表变化时整个作废（§7.1）。
 */
@Serializable
data class Entity(
    val id: String,
    val kind: String,
    /** 给人看的名字，例如「木桌」。 */
    val label: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val parent: String? = null,
    /** 结构化属性：可查询、可判定。渲染器会看到它，所以它只该放**物体自身**的事。 */
    val attributes: Map<String, String> = emptyMap(),
    /** 地点背景。通常写在地点实体上，物体沿 [parent] 链继承。 */
    val background: String? = null,
    val sentences: List<String> = emptyList(),
    /**
     * 人设卡（§3.5）。
     *
     * **只有角色会写它，但字段对所有实体开放**——角色就是物体的一种，多一个 `kind == "角色"` 的
     * 判断只会多一处会写错的地方（判定那边按"有没有卡"取，不按 `kind` 取）。
     *
     * 它和 [attributes] 是两回事：[attributes] 是判定能改的世界状态，这一项判定只读。
     */
    val cards: List<CharacterCard> = emptyList(),
    /**
     * 这个实体自己的规则书（§12 第 12 项）。
     *
     * 与 [attributes] 的分工是硬的：[attributes] 是**判定能改的世界状态**（杯子里还有没有酒），
     * 这里的是**判定只读的依据**（木制品怕火）。所以它不随动作变化，删掉它等于删掉一样东西的性质。
     */
    val rulebook: Rulebook = Rulebook(),
) {
    /** 展示用的整段文字。句子里带着各自的句末标点，直接相连即可。 */
    fun text(): String = sentences.joinToString("")
}

/**
 * 判定系统对「这个物体变了没有」的判定（§7.1）。
 *
 * **它不是布尔值，这是刻意的。** 三档的性质完全不同，混成"变了/没变"会让代码在两件根本不同的
 * 事之间二选一，而选错的那一边各有各的坏法：
 *
 *  - 全都当成 [REPLACED]：id 爆炸，玩家认知里"那张桌子"在不断换身份
 *  - 全都当成 [APPEARANCE]：形状变了却只改文字，**俯视图与文字打架**（地图是数据的直接投影，
 *    不经过模型，它编不了）
 *
 * 分界线是**形状**：几何没变就还是同一个物体，只是外表不同；几何变了，它就不是原来那个东西了。
 */
enum class ChangeLevel {
    /** 没变。 */
    UNCHANGED,

    /** 只是外表变了（桌子被擦干净）。**形状不变**，id 不变，描述作废、等着重渲染。 */
    APPEARANCE,

    /** 彻底变了（桌子被劈开）。**这是另一个物品**：新 id、新建模、新描述。 */
    REPLACED,
}

/**
 * 世界的一份快照。
 *
 * `world/` **不做持久化**——存档是 `session/` 的事。这里只提供"取一份快照 / 从快照装回来"，
 * 于是这一层没有平台依赖，也不必知道文件是什么。
 */
@Serializable
data class WorldSnapshot(
    val entities: List<Entity>,
    /** 世界自己的那份规则书（§12 第 12 项）。**默认优先级 10，最低**——它最泛。 */
    val rulebook: Rulebook = Rulebook(),
)
