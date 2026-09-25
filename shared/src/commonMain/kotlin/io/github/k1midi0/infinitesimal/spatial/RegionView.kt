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
        val layer = actor?.cell()?.z ?: 0
        // **地形按发起者的高度问**：网格画的是"脚下踩的是什么"，而发起者的脚就是这个世界里唯一
        // 有意义的参照高度（附录 A.15）。用米格层去问会永远问到那一层半土半空的格子，见
        // `groundMaterialAt` 的注释。
        val actorCm = actor?.z ?: 0

        // **covers 而不是 contains**：被射线打中的东西一定算在片里，哪怕它的格子落在凸包边界上
        // （它几乎总在边界上——挡住射线的那样东西，定义的就是这片空间的边）。
        val inside = world.all().filter { region.covers(it) }

        // **网格画发起者所在那一米层上的东西。**
        //
        // 试过按附录 A.5 那句「俯视图只画 `parent` 为空的顶层物体」来过滤，结果是**网格整个空了**：
        // 这份内容里木桌挂着 `tavern_hall`、酒杯挂着木桌，一个 `parent` 为空的都没有（除非把房间
        // 本身框进来）。A.5 那句是照"`parent` 只表示装着关系"写的，而这份内容里 `parent` 同时承担
        // 了"在哪个地点"——**两种关系挤在同一个字段上**，这一条记在 §12 里等定。
        //
        // 所以暂时按层过滤，再把"一个格子里站着好几样"的挑一个出来（下面 [byCell]）。
        val onLayer = inside
            .filter { it.cell().z == layer }
            .sortedWith(compareBy({ it.cell().y }, { it.cell().x }))
        val onLayerIds = onLayer.map { it.id }.toSet()

        // 发起者永远用 @，其余按出现顺序发字母。字母是这一份输出的局部约定，不是世界里的 id。
        val symbols = mutableMapOf<String, Char>()
        actor?.let { if (it.id in onLayerIds) symbols[it.id] = '@' }
        var next = 'a'
        for (e in onLayer) {
            if (e.id in symbols) continue
            symbols[e.id] = next
            next = if (next == 'z') 'a' else next + 1
        }

        // 一个米格里可能站着好几样东西（木桌和它上面的酒杯是同一个 (x,y)）。**网格画最靠下的那
        // 一个**——它是一张"地上有什么"的图，桌子占着那一格；酒杯的高度在下面的清单里，那里写得下
        // 一个精确的厘米数，而格子里只有两个字符。
        //
        // 发起者例外：它永远是 @，否则挂在它身上的斧子与抹布会把它自己顶掉。
        val byCell = onLayer.groupBy { it.cell() }
        val drawn = byCell.mapValues { (_, here) ->
            here.firstOrNull { it.id == actor?.id } ?: here.minByOrNull { it.z } ?: here.first()
        }
        val drawnIds = drawn.values.map { it.id }.toSet()
        // 第二份清单是**这一层之外**的东西。落在同一格、却没被画出来的那几个（酒杯、斧子、抹布）
        // 已经在上面那份清单里了，再列一遍是同一件事说两遍。
        val listed = inside.filterNot { it.id in onLayerIds }

        return buildString {
            appendLine("【空间框】发起者与这次动作指向的东西所在的那一片")
            appendLine("一格约合现实中的一米。**下面清单里的坐标是厘米**，一格就是 100 厘米。")
            appendLine(
                "范围 x ${region.minX}..${region.maxX}（${region.width} 格），" +
                    "y ${region.minY}..${region.maxY}（${region.height} 格）；" +
                    "层 ${region.minZ}..${region.maxZ}；${reachLine(region)}",
            )
            appendLine()

            // **这一片里出现过哪些材质。** 图例只列它们，不把整张材质表抄一遍——那张表已经在前缀里
            // 了（`judge/MaterialView.kt`），而这一份输出是每一步都要重付的。
            val materials = linkedSetOf<String>()

            if (region.width <= GRID_LIMIT && region.height <= GRID_LIMIT) {
                append("      x=")
                for (x in region.minX..region.maxX) append(pad(x.toString()))
                appendLine()
                for (y in region.minY..region.maxY) {
                    append(("  y=$y").padEnd(8))
                    for (x in region.minX..region.maxX) {
                        val here = drawn[Cell3(x, y, layer)]
                        // **地形画在网格里，东西叠在上面**（A.15）。只有确实没有东西的格子才轮到
                        // 地形——一样东西站在地上时，它的符号比脚下的材质重要。而**框外那一格不查
                        // 地形**：查了就会把框外的东西漏进图例，而"框外一概不提"是这份输出的老规矩。
                        val ground = if (region.contains(x, y)) {
                            world.groundMaterialAt(Cell(x, y), actorCm)?.also { materials += it }
                        } else {
                            null
                        }
                        append(
                            when {
                                here != null -> pad(symbols[here.id].toString())
                                // **框外留空**。这一句 if 就是"参差"的全部实现。
                                !region.contains(x, y) -> pad(" ")
                                // 地形与东西用**两套不重叠的符号**：东西是 `@` 与 `a`..`z`，材质是
                                // 各自那个 ASCII 字符，所以叠在一张表上也不会有歧义。
                                ground != null -> pad(world.materials.display(ground).toString())
                                // 在框里、没东西、脚下也没地形。**这与框外不是一回事**，所以符号
                                // 也不同：`.` 是"这一格空着"，空白是"这一格不归这一片管"。
                                else -> pad(".")
                            },
                        )
                    }
                    appendLine()
                }
                appendLine()
                appendLine(
                    "@ 是发起者自己，字母是下面列出的东西；**其余符号是地形材质**（见下面的图例）。" +
                        ". 是这一片里既没有东西、脚下也没有地形的格子，**空白是框外**。",
                )
            } else {
                appendLine("这一片跨度太大（${region.width}×${region.height}），不画网格，只列格子范围和里面的东西。")
            }
            appendLine()

            if (materials.isNotEmpty()) {
                appendLine("【这一片里的材质】（网格里画的就是这些字符；更全的材质表在【世界材质表】里）")
                // 按材质表的顺序列，不按扫描顺序——同一片地形换个扫描方向就换一个次序，读数就没法
                // 横向比了。表里没有的 id 排在最后（它是坏的，不该混在正常材质中间）。
                val ordered = world.materials.all.map { it.id }.filter { it in materials } +
                    materials.filter { world.materials.byId(it) == null }
                for (id in ordered) appendLine(materialLegend(world, id))
                appendLine()
            }

            appendLine("【层 $layer 上的东西】（一层 = 1 米）")
            if (onLayer.isEmpty()) {
                appendLine("（没有）")
            } else {
                for (e in onLayer) {
                    val mark = if (e.id in drawnIds) symbols[e.id].toString() else "·"
                    appendLine("  $mark ${describe(world, e)}")
                }
            }
            appendLine()

            if (listed.isNotEmpty()) {
                appendLine("【这一片里、但不在这一层上的】")
                for (e in listed) appendLine("  · ${describe(world, e)}")
                appendLine()
            }

        }.trimEnd()
    }

    private fun describe(world: World, e: Entity): String = describeThing(world, e)

    /**
     * 图例里的一行：**一行一种材质**。
     *
     * 这是 A.15 那条"按材质去重的图例"的落点。一片 16×16 的草地是**一行**，不是二百五十六行——
     * 布局（哪一格是什么）在网格里，性质（这一种材质是什么）在这里，两处都不重复。
     *
     * **属性与规则书要印出来**，理由与 [describe] 里那段逐字相同：世界对一种材质的真相就装在
     * `attributes` 与 `rulebook` 里，不印的话模型只剩一个名字，于是它要么心算、要么编。而"她踩过的
     * 那片草会不会烧起来"这类判断，全靠这两样。
     */
    private fun materialLegend(world: World, id: String): String = buildString {
        append("  ")
        append(world.materials.display(id))
        append(' ')
        append(world.materials.name(id))
        val material = world.materials.byId(id)
        if (material == null) {
            // 网格里那个符号是 `?`（`MaterialTable.display` 对不认识的 id 退成 `?`）。**说清是什么
            // 坏了**，不要装作没事：这一格写的 id 不在表里，那是个数据错误。
            append("（这一格写的材质 id 不在材质表里）")
            return@buildString
        }
        if (!material.breakable) append("（挖不动）")
        if (material.attributes.isNotEmpty()) {
            append("   ")
            append(material.attributes.entries.joinToString("、") { "${it.key}=${it.value}" })
        }
        val rules = material.rulebook.entries.joinToString("；") { it.text }
        if (rules.isNotEmpty()) {
            append("   规则：")
            append(rules)
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

/**
 * 清单里的一行：**一样东西叫什么、id 是什么、在哪、身上带着什么属性。**
 *
 * 从 [RegionView] 里抽出来，因为现在**有两份视图要列东西**：判定那份空间框，与角色那份"我在的这一
 * 块"（`spatial/ChunkDetail.kt`）。两处各写一遍之后，迟早有一处先漂开——而这里漂开的后果是具体的：
 * **属性不印，读的人手里就只剩一个光秃秃的名字**，于是"那杯麦酒"与实体名"酒杯"对不上（§14.24）。
 *
 * **只印属性，不印 `sentences`。** 那些是按句存的散文描述，是判定的**产物**而不是它的输入
 * （渲染在读它、判定在写它），而且长得多——一个桶的描述几百字，列十几样东西就装不下了。
 *
 * 坐标**印成厘米**：世界存的就是厘米，而"够不够得着"要算的正是它。格子那边的粗粒度留给网格。
 */
internal fun describeThing(world: World, e: Entity): String = buildString {
    append(e.label)
    append("   id=").append(e.id)
    append("   (").append(e.x).append(", ").append(e.y).append(", ").append(e.z).append(") 厘米")
    if (e.attributes.isNotEmpty()) {
        append("   ")
        append(e.attributes.entries.joinToString("、") { "${it.key}=${it.value}" })
    }
}
