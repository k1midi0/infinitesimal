package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.ChangeLevel
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.VoxelShape

/**
 * 判定系统提出的一条后果。**它是提议，不是事实**——校验与落库在 [Committer]。
 *
 * 这里最要紧的一件事：**变化等级不是模型填的枚举，是从它调用哪个工具推出来的**（[level]）。于是
 * "声明只是外表变化、却交回一个新形状"这种矛盾**无法被表达**——不是"表达了再被校验"，是根本没有
 * 那个字段可填。
 *
 * 这与 §7.2 是同一条原则：写操作的限制要落在数据模型上，不落在提示词上。
 */
sealed interface Consequence {

    /** 这条后果作用于哪个实体。 */
    val targetId: String

    /** 它让被作用的对象落到哪一档变化上（§7.1）。 */
    val level: ChangeLevel

    /**
     * 移动。**描述不动**——描述只讲物体自身（§7.6），把杯子从桌上挪到地上，杯子本身没变。
     * 这是"描述只讲自身"白拿的好处：移动不烧一次渲染。
     */
    data class Move(val id: String, val x: Int, val y: Int, val z: Int) : Consequence {
        override val targetId: String get() = id
        override val level: ChangeLevel get() = ChangeLevel.UNCHANGED
    }

    /**
     * 改一个结构化属性。
     *
     * **它算外表变化，描述作废。** 因为属性变化通常正是"擦干净桌子"那一路（污渍没了、酒满了）。
     * 保守地重渲染一次，比留着一条已经过时的描述便宜——**过时的描述是更坏的失败**（§7.6）。
     */
    data class SetAttribute(val id: String, val key: String, val value: String) : Consequence {
        override val targetId: String get() = id
        override val level: ChangeLevel get() = ChangeLevel.APPEARANCE
    }

    /**
     * 仅仅外表变了：形状不变，描述作废、等着重渲染（§7.1）。
     *
     * **它没有形状参数。** 这是刻意的——那条"声明外表变化却交回新形状"的自相矛盾，在这里根本没有
     * 地方可以表达出来。
     */
    data class ChangeAppearance(val id: String) : Consequence {
        override val targetId: String get() = id
        override val level: ChangeLevel get() = ChangeLevel.APPEARANCE
    }

    /** 摧毁：物体从世界态里消失。等级无从谈起——它不再参与渲染决策。 */
    data class Destroy(val id: String) : Consequence {
        override val targetId: String get() = id
        override val level: ChangeLevel get() = ChangeLevel.UNCHANGED
    }

    /** 凭空出现一个东西：木匠被"补写"出来（§8.3）、桌上多了一只杯子。 */
    data class Spawn(val entity: Entity) : Consequence {
        override val targetId: String get() = entity.id
        override val level: ChangeLevel get() = ChangeLevel.REPLACED
    }

    /** 彻底改变：旧的没了，**这是另一个物品**（桌子被劈开）。新 id 必须不同。 */
    data class Replace(val oldId: String, val entity: Entity) : Consequence {
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
    data class SetShape(val id: String, val shape: VoxelShape) : Consequence {
        override val targetId: String get() = id
        override val level: ChangeLevel get() = ChangeLevel.APPEARANCE
    }
}
