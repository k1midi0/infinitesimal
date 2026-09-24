package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.World

/**
 * **世界材质表**那一节（附录 A.15）。
 *
 * ## 它为什么在提示词里
 *
 * 三只框选地形的手（[JudgeTools.FILL_TERRAIN] 等）都要模型填一个**材质 id**，而 id 是一张闭合表里的
 * 名字：它猜不出来，猜错了的后果是 `fill_terrain` 被 `Committer` 驳回（拼错的材质名不会报错、
 * 只会什么都不发生——所以那条校验是必须的）。
 *
 * 材质表是**世界级**的事实，和规则书同级，所以它进**两步逐字相同的**那一段前缀（§7.5：会变的东西
 * 一律不许放进去，放进去一次第二步的缓存就全灭）。而它是世界的一部分、一局里不变，放进去是安全的。
 *
 * ## 它只列 id 与名字，不列属性与规则书
 *
 * 属性与规则书在**网格旁边那份图例**里给（A.15：布局在网格里、性质在图例里）。两处都写一遍就成了
 * 两份可以不一致的数据。这一节的职责只有一个：**把闭合的词表摆出来**。
 *
 * 「挖不动」那一栏必须给：它是 `dig` / `replace` 会被驳回的唯一原因，而模型看不到别的线索。
 */
object MaterialView {

    fun forPrefix(world: World): String {
        val materials = world.materials.all
        if (materials.isEmpty()) return ""
        return buildString {
            appendLine("【世界材质表】（地形用的就是这几个 id；地形格 50 厘米一格）")
            for (material in materials) {
                append(material.id)
                append(' ')
                append(material.name)
                append('「')
                append(material.display)
                append('」')
                append(if (material.breakable) "可以挖" else "**挖不动**")
                appendLine()
            }
        }
    }
}
