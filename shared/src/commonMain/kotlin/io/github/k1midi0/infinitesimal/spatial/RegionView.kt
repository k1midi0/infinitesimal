package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World

/**
 * 把一片 [Region] 写成判定看得懂的文字。
 *
 * ## 网格只画一层
 *
 * 俯视图的定义就是一层的地面图。把层折进网格会变成"一格塞好几样东西"（木桌和它上面的酒杯、油灯
 * 是同一个 (x,y)），那正是现在这个数据形状最不该退化的地方。所以：**网格画发起者所在的那一层，
 * 别层的在清单里带坐标和归属单独列。**
 *
 * ## 网格用 ASCII
 *
 * 中文在等宽字体里是两列宽，`%3s` 对不齐——而网格的价值全在对齐上，对不齐就等于没画。所以格子
 * 里放 `@`（发起者）和 `a`..`z`（每样东西一个字母），真正的名字在下面的清单里。清单是列表，
 * 不需要对齐，中文尽管写。
 *
 * ## 参差
 *
 * **框外的格子留空白**，不填占位符。参差的边缘本身就是信息——"这一片是在东北方向被挡住的"，
 * 而一个填满的方框会把这个信息抹掉。
 *
 * ## 框外一样都不提
 *
 * 早先这里还有一句「框外还有 N 样东西；最近的是「X」，在东南 4 格外」。它被删掉了，因为它给出的
 * 是一行**假目标**：`minByOrNull` 取「最近的那样」，而最近的那样常常是发起者**自己所在的房间**
 * ——房间的坐标落在参差凸包的角上，于是模型读到「酒馆大厅在西北 3 格外」。它不但没用，还骗走了
 * 一次扩大；而真正在框外的第二样东西（扫帚、井边那只杯子）永远排不上号，模型于是拿手边的同类
 * 顶替。
 *
 * 判定**不需要**知道框外有什么：要看的东西在框外，就调 `widen_view` 把框扩过去。理由也是现成的
 * ——没被指向的东西本来就不该被判。
 */
object RegionView {

    /** 网格超过这么大就只列格子。一段提示词里 30 行 × 30 列的点是灾难。 */
    private const val GRID_LIMIT = 16

    fun render(world: World, region: Region, actorId: String): String {
        val actor = world.byId(actorId)
        val layer = actor?.z ?: 0

        // **covers 而不是 contains**：被射线打中的东西一定算在片里，哪怕它的格子落在凸包边界上
        // （它几乎总在边界上——挡住射线的那样东西，定义的就是这片空间的边）。
        val inside = world.all().filter { region.covers(it) }
        val onLayer = inside.filter { it.z == layer }.sortedWith(compareBy({ it.y }, { it.x }))
        val otherLayers = inside.filter { it.z != layer }

        // 发起者永远用 @，其余按出现顺序发字母。字母是这一份输出的局部约定，不是世界里的 id。
        val symbols = mutableMapOf<String, Char>()
        actor?.let { if (it in onLayer) symbols[it.id] = '@' }
        var next = 'a'
        for (e in onLayer) {
            if (e.id in symbols) continue
            symbols[e.id] = next
            next = if (next == 'z') 'a' else next + 1
        }

        return buildString {
            appendLine("【空间框】发起者与这次动作指向的东西所在的那一片")
            appendLine("一格约合现实中的一米。")
            appendLine(
                "范围 x ${region.minX}..${region.maxX}（${region.width} 格），" +
                    "y ${region.minY}..${region.maxY}（${region.height} 格）；" +
                    "层 ${region.minZ}..${region.maxZ}；${reachLine(region)}",
            )
            appendLine()

            if (region.width <= GRID_LIMIT && region.height <= GRID_LIMIT) {
                append("      x=")
                for (x in region.minX..region.maxX) append(pad(x.toString()))
                appendLine()
                for (y in region.minY..region.maxY) {
                    append(("  y=$y").padEnd(8))
                    for (x in region.minX..region.maxX) {
                        val here = onLayer.firstOrNull { it.x == x && it.y == y }
                        append(
                            when {
                                here != null -> pad(symbols[here.id].toString())
                                // **框外留空**。这一句 if 就是"参差"的全部实现。
                                !region.contains(x, y) -> pad(" ")
                                else -> pad(".")
                            },
                        )
                    }
                    appendLine()
                }
                appendLine()
                appendLine("@ 是发起者自己，字母是下面列出的东西，. 是这一片里的空格子，**空白是框外**。")
            } else {
                appendLine("这一片跨度太大（${region.width}×${region.height}），不画网格，只列格子范围和里面的东西。")
            }
            appendLine()

            appendLine("【层 $layer 上的东西】")
            if (onLayer.isEmpty()) {
                appendLine("（没有）")
            } else {
                for (e in onLayer) appendLine("  ${symbols[e.id]} ${describe(world, e)}")
            }
            appendLine()

            if (otherLayers.isNotEmpty()) {
                appendLine("【别的层上的东西】")
                for (e in otherLayers) appendLine("  · ${describe(world, e)}")
                appendLine()
            }

        }.trimEnd()
    }

    private fun describe(world: World, e: Entity): String {
        val parent = e.parent?.let { world.byId(it)?.label }
        return buildString {
            append(e.label)
            append("   id=").append(e.id)
            append("   (").append(e.x).append(", ").append(e.y).append(") 层").append(e.z)
            if (parent != null) append("，在「").append(parent).append("」上")
        }
    }

    /**
     * 各方向看得多远。
     *
     * 定向扩大之后各扇区档位不同，所以这一行可能不是一句话。**它必须说出来**：模型得知道自己上一眼
     * 往哪边看过了，否则它会一遍遍要求看同一个方向，而每一次都是一整趟往返。
     */
    private fun reachLine(region: Region): String {
        val byLevel = Direction.ALL.groupBy { region.levels[it.ordinal] }
        if (byLevel.size == 1) {
            val k = byLevel.keys.first()
            val note = if (k == 1) "未扩大" else "各方向一致，已扩大 ${k - 1} 次"
            return "射线长 ${RegionBuilder.DEFAULT_RAY * k}（$note）"
        }
        val parts = byLevel.entries.sortedByDescending { it.key }.map { (k, dirs) ->
            "${dirs.joinToString("、") { it.label }} ${RegionBuilder.DEFAULT_RAY * k}"
        }
        return "射线长：" + parts.joinToString("；")
    }

    private fun pad(s: String): String = " " + s.padEnd(2)
}
