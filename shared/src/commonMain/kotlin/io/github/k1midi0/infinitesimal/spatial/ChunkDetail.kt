package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.CHUNK_CELLS
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.TERRAIN_CM
import io.github.k1midi0.infinitesimal.world.World

/**
 * **一个角色自己站的那一块，细看**（§14.36）。
 *
 * ## 它为什么与区块地图是两份，而不是一份画细一点
 *
 * 角色看世界有两个尺度，而它们回答的是两个不同的问题：
 *
 * | | 问的 | 一格有多大 | 谁在画 |
 * |---|---|---|---|
 * | [ChunkView.renderLandform] | 这一带是什么地方 | 八米 | 城/镇/路/林/野 |
 * | 这里 | **我手边有什么** | **一米** | 具体哪样东西、脚下什么材质 |
 *
 * 合成一份是做不到的：八米一格画不下"桌上那只杯子"，而一米一格铺 32 米是 64×64 ——一张 4096 个
 * 字符的图，每次唤醒都付一次。**尺度不同就该分开画**，这与 §7 里"渲染只做一件事"是同一条。
 *
 * ## 与空间框（[RegionView]）的区别
 *
 * 空间框是**判定**看的那一份：它由一个动作的发起者与指向物算出来，边缘参差，还可能被 `widen_view`
 * 扩大。而这一份是**角色**看的那一份：范围永远是它自己那一块，不参差、不能扩——因为它不是为某一次
 * 动作算的，它是"睁开眼看见的"。
 *
 * 两者在同一个角色身上会同时出现（这一份在提示词里，空间框在它调 `act` 时由判定那边再算一次），
 * 而它们**不会打架**：一个是八米的固定切面，一个是三米的动作视窗。
 *
 * ## 为什么网格还是 ASCII
 *
 * 与 [RegionView] 逐字相同的理由：中文在等宽字体里占两列，而对齐是网格的全部价值。名字在下面的
 * 清单里，那里写得下中文。
 */
object ChunkDetail {

    /** 一块 = 16 个地形格 = 8 个米格。 */
    fun render(world: World, chunk: ChunkKey, actorId: String): String {
        val actor = world.byId(actorId)
        val xs = metreRange(chunk.x)
        val ys = metreRange(chunk.y)

        val here = world.all().filter { it.cell().x in xs && it.cell().y in ys }
        val layer = actor?.cell()?.z ?: 0
        val onLayer = here
            .filter { it.cell().z == layer }
            .sortedWith(compareBy({ it.cell().y }, { it.cell().x }))

        // 发起者永远 `@`，其余按出现顺序发字母——与 [RegionView] 同一套约定（字母是这一份输出的
        // 局部约定，不是世界里的 id）。
        val symbols = mutableMapOf<String, Char>()
        actor?.let { if (it.id in onLayer.map { e -> e.id }) symbols[it.id] = '@' }
        var next = 'a'
        for (e in onLayer) {
            if (e.id in symbols) continue
            symbols[e.id] = next
            next = if (next == 'z') 'a' else next + 1
        }

        // 一格可能站着好几样（桌子和它上面的酒杯是同一个 (x,y)）：**画最靠下的那一个**，它是一张
        // "地上有什么"的图。发起者例外，否则挂在它身上的东西会把它顶掉。
        val byCell = onLayer.groupBy { it.cell() }
        val drawn = byCell.mapValues { (_, list) ->
            list.firstOrNull { it.id == actor?.id } ?: list.minByOrNull { it.z } ?: list.first()
        }
        val drawnIds = drawn.values.map { it.id }.toSet()

        val actorCm = actor?.z ?: 0

        return buildString {
            appendLine("【你在的这一块】八米见方，一格一米")
            appendLine()
            append("      x=")
            for (x in xs) append(" ").append(x.toString().padEnd(2))
            appendLine()
            for (y in ys) {
                append(("  y=$y").padEnd(8))
                for (x in xs) {
                    val thing = drawn[Cell3(x, y, layer)]
                    // 地形垫在下面，东西叠在上面——**只有确实没有东西的格子才轮到地形**。
                    val ground = world.groundMaterialAt(Cell(x, y), actorCm)
                    append(
                        when {
                            thing != null -> symbols[thing.id].toString()
                            ground != null -> world.materials.display(ground).toString()
                            else -> "."
                        }.padEnd(3).let { " $it" },
                    )
                }
                appendLine()
            }
            appendLine()
            appendLine("@ 是你自己，字母是下面列出的东西；其余符号是**脚下踩的材质**。")
            appendLine()

            appendLine("【这一块里的东西】")
            if (here.isEmpty()) {
                appendLine("（除了你，这一块里什么都没有）")
            } else {
                for (e in here) {
                    val mark = if (e.id in drawnIds) symbols[e.id].toString() else "·"
                    appendLine("  $mark ${describeThing(world, e)}")
                }
            }
        }.trimEnd()
    }

    /**
     * 一块覆盖的米格范围：八米见方。
     *
     * **从世界常数量出来，不写死 8**：一块是 [CHUNK_CELLS] 个地形格，每个 [TERRAIN_CM] 厘米。
     * 哪天 chunk 改成 32 格，这里跟着变，而"图上少画了一半"那种错不会发生。
     */
    private fun metreRange(block: Int): IntRange {
        val first = block * CHUNK_CELLS * TERRAIN_CM
        return metreCell(first)..metreCell(first + CHUNK_CELLS * TERRAIN_CM - 1)
    }
}
