package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * 规则书里的一条。
 *
 * **一条就是一句话**（§12 第 12 项）。需要精确数值时才附 [values]——数值是给代码算的，句子是给
 * 模型读的，两者都要，但**默认只有句子**：绝大多数规则不需要数字，而每条都配一张键值表会把规则书
 * 变成一份要先解析才能读的清单。
 *
 * [priority] 是**这一条自己的**优先级，`null` 表示跟随所在规则书的（也是绝大多数情况的答案）。
 * 单独抬一条的口子是为这种情况留的：世界规则整体低于物品，但"这个世界里火会烧起来"那一条比某张
 * 桌子上的"这桌子是防火石做的"更该赢。
 */
@Serializable
data class RuleEntry(
    /** 一句话。**代码不解析它**——它只被排序、被拼进提示词。 */
    val text: String,
    /** 这一条自己的优先级；`null` = 跟随所在规则书。**数字大的先算。** */
    val priority: Int? = null,
    /** 需要精确数值时才附的键值对。它同时是 `calc` 的变量来源之一。 */
    val values: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = text.isBlank() && values.isEmpty()
}

/**
 * 一份规则书：**角色、物品、世界各可以带一份**（§12 第 12 项）。
 *
 * 它是**只读数据**，判定拿它当依据、不改它——与 [Entity.attributes]（判定能改的世界状态）是两回事。
 *
 * [priority] 是**这一份整体的**优先级，`null` 表示按来源取默认值：世界 10、角色 20、物品 30。
 * 默认值是"越具体越优先"这条判断的落地——世界规则最泛，具体到某一样东西上的规则最该赢。
 * 三个数字都在 [RulebookDefaults] 里，改它们要连同 §12 那一条一起想。
 *
 * **代码不判冲突。** 它只按优先级排序、分片、把来源标清楚，剩下的是模型的事：冲突时它必须点名
 * 自己采信了哪一条（§12 第 12 项）。让代码判冲突就得让代码读懂句子，而这里一条都不该被解析。
 */
@Serializable
data class Rulebook(
    val entries: List<RuleEntry> = emptyList(),
    /** 这一份整体的优先级；`null` = 按来源取默认值（见 [RulebookDefaults]）。 */
    val priority: Int? = null,
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

/** 四个默认优先级，以及"一个实体算角色还是算物品"的判据。 */
object RulebookDefaults {

    const val WORLD = 10

    /**
     * **材质排在中间**（附录 A.15，2026-09-26 定）。
     *
     * 世界规则最泛（这个世界的火会烧起来），材质比某个具体角色更基本（石头不会被火点着），而具体到
     * 某个人身上的规则最该赢。四个数字的相对顺序是这一整个机制的全部内容，所以它们写在一起。
     */
    const val MATERIAL = 15

    const val CHARACTER = 20
    const val OBJECT = 30

    /**
     * 一个实体带的那份规则书该按哪个默认优先级算。
     *
     * **按「有没有卡」分，不按 `kind` 分**——与判定那边取角色卡是同一条理由：角色就是物体的一种，
     * 多一个 `kind == "角色"` 的判断只会多一处会写错的地方（§3.5、[Entity.cards]）。
     */
    fun defaultPriorityOf(entity: Entity): Int =
        if (entity.cards.isNotEmpty()) CHARACTER else OBJECT
}
