package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.ChangeLevel
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.TerrainBox
import io.github.k1midi0.infinitesimal.world.VoxelShape

/**
 * 判定系统提出的一条后果。**它是提议，不是事实**——校验与落库在 [Committer]。
 *
 * 这里最要紧的一件事：**变化等级不是模型填的枚举，是从它调用哪个工具推出来的**（[EntityConsequence.level]）。
 * 于是"声明只是外表变化、却交回一个新形状"这种矛盾**无法被表达**——不是"表达了再被校验"，是根本没有
 * 那个字段可填。
 *
 * 这与 §7.2 是同一条原则：写操作的限制要落在数据模型上，不落在提示词上。
 *
 * ## 两族
 *
 * 它下面分两族：作用于**一样东西**的 [EntityConsequence]（七种），与作用于**地形**的
 * [TerrainConsequence]（三种，附录 A.15）。两族的差别不是"填了没有"，而是那两个字段在一边成立、
 * 在另一边不成立——见 [TerrainConsequence] 的注释。
 *
 * 两族的成员都嵌在 `Consequence` 里（`Consequence.Move`、`Consequence.DigTerrain`），因为调用点
 * 关心的是"这是哪一条后果"，而"它属于哪一族"只在少数几处（[Committer]、`GameSession` 的作废集合）
 * 需要区分。
 */
sealed interface Consequence {

    /**
     * 作用于**一样东西**的后果。七种。
     *
     * [targetId] 与 [level] 都长在这一族上：前者说的是"哪一样东西"，后者说的是"它的描述还算不算数"。
     */
    sealed interface EntityConsequence : Consequence {

        /** 这条后果作用于哪个实体。 */
        val targetId: String

        /** 它让被作用的对象落到哪一档变化上（§7.1）。 */
        val level: ChangeLevel
    }

    /**
     * 作用于**地形**的后果。三种，对应三只框选工具（附录 A.15）。
     *
     * ## 它为什么没有 `targetId`，也没有变化等级
     *
     * 那两个字段不是"这里没填"，是**它们在这一族上不成立**：
     *
     * - `targetId` 回答的是"**哪一样东西**的描述作废了"。地形的改动不动任何东西自己的描述——描述只讲
     *   物体自身（§7.6），脚下的土从土变成坑不改变那只杯子长什么样。
     * - **变化等级是渲染决策的输入**（§7.1），地形不进那张表。
     *
     * 强塞一个假 id（`"terrain:12,4,256"` 之类）会让 `GameSession` 那边多一条**永远匹配不上的作废
     * 项**，而那种错看不出来：缓存里少作废一样东西的症状是"描述偶尔过时"，不是一个报错。所以接口在
     * 这里分叉，让"给地形编一个实体 id"这件事**做不到**。
     *
     * ## 单位是地形格
     *
     * [box] 收的是地形格坐标（50 厘米一格），**不是**体素、也不是米。三只工具收同一个形状，模型只有
     * 一套坐标要学。
     */
    sealed interface TerrainConsequence : Consequence {

        /** 作用在哪一片地形格上。 */
        val box: TerrainBox
    }

    /**
     * 移动。**描述不动**——描述只讲物体自身（§7.6），把杯子从桌上挪到地上，杯子本身没变。
     * 这是"描述只讲自身"白拿的好处：移动不烧一次渲染。
     */
    data class Move(val id: String, val x: Int, val y: Int, val z: Int) : EntityConsequence {
        override val targetId: String get() = id
        override val level: ChangeLevel get() = ChangeLevel.UNCHANGED
    }

    /**
     * 改一个结构化属性。
     *
     * **它算外表变化，描述作废。** 因为属性变化通常正是"擦干净桌子"那一路（污渍没了、酒满了）。
     * 保守地重渲染一次，比留着一条已经过时的描述便宜——**过时的描述是更坏的失败**（§7.6）。
     */
    data class SetAttribute(val id: String, val key: String, val value: String) : EntityConsequence {
        override val targetId: String get() = id
        override val level: ChangeLevel get() = ChangeLevel.APPEARANCE
    }

    /**
     * 仅仅外表变了：形状不变，描述作废、等着重渲染（§7.1）。
     *
     * **它没有形状参数。** 这是刻意的——那条"声明外表变化却交回新形状"的自相矛盾，在这里根本没有
     * 地方可以表达出来。
     */
    data class ChangeAppearance(val id: String) : EntityConsequence {
        override val targetId: String get() = id
        override val level: ChangeLevel get() = ChangeLevel.APPEARANCE
    }

    /** 摧毁：物体从世界态里消失。等级无从谈起——它不再参与渲染决策。 */
    data class Destroy(val id: String) : EntityConsequence {
        override val targetId: String get() = id
        override val level: ChangeLevel get() = ChangeLevel.UNCHANGED
    }

    /** 凭空出现一个东西：木匠被"补写"出来（§8.3）、桌上多了一只杯子。 */
    data class Spawn(val entity: Entity) : EntityConsequence {
        override val targetId: String get() = entity.id
        override val level: ChangeLevel get() = ChangeLevel.REPLACED
    }

    /** 彻底改变：旧的没了，**这是另一个物品**（桌子被劈开）。新 id 必须不同。 */
    data class Replace(val oldId: String, val entity: Entity) : EntityConsequence {
        override val targetId: String get() = oldId
        override val level: ChangeLevel get() = ChangeLevel.REPLACED
    }

    /**
     * 给一样东西安上形状——建模的产物（附录 A.9）。
     *
     * **它不是模型能直接调的一只手。** 它是 `model_shape` 造出形状之后由**代码补上**的一条后果，
     * 然后与模型给的那些一起走 [Committer]。绕这一圈而不是当场写进世界，是因为"这个 id 是这一批里
     * 刚 spawn 出来的、还是本来就有"，只有 [Committer] 的影子状态知道；当场写会漏掉那条判断。
     *
     * 算**外表变化**：几何变了，它既有的描述不再是它的样子（§7.1）。新造出来的东西还没有描述，
     * 所以这一条对它们是白拿的。
     */
    data class SetShape(val id: String, val shape: VoxelShape) : EntityConsequence {
        override val targetId: String get() = id
        override val level: ChangeLevel get() = ChangeLevel.APPEARANCE
    }

    /**
     * 铺：**只填空的格**。这是"铺一条路"——已经有的材质不动
     * （[io.github.k1midi0.infinitesimal.world.Terrain.fill]）。
     */
    data class FillTerrain(override val box: TerrainBox, val material: String) : TerrainConsequence

    /**
     * 挖：清空这一片。**不可破坏的材质跳过去**——世界底那层岩层就是这么拦住的。
     *
     * 它没有材质参数，这不是省事：挖去的结果就是"没有材质"，而"空气"不是一种材质（附录 A.15）。
     */
    data class DigTerrain(override val box: TerrainBox) : TerrainConsequence

    /** 换：把框里的 [from] 换成 [to]。这是"烧焦一片草地"。同样跳过不可破坏的材质。 */
    data class ReplaceTerrain(
        override val box: TerrainBox,
        val from: String,
        val to: String,
    ) : TerrainConsequence
}
