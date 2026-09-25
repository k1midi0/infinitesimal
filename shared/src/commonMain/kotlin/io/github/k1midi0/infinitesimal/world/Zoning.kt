package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * 区划格的大小：**10 米**（附录 A.16）。
 *
 * 它比地形格（[TERRAIN_CM] = 50 厘米）粗二十倍。两个粒度差这么远不是随便定的：**区划是意图，地形
 * 是事实**，意图只需要粗到"这一片是森林还是街道"，而事实要细到玩家踩得到。
 */
const val ZONE_CM = 1000

/** 一个区划格里有多少个地形格。10 米 ÷ 50 厘米 = 20。 */
const val ZONE_TERRAIN_CELLS = ZONE_CM / TERRAIN_CM

/**
 * 一片地方是什么样子。**闭合枚举**——它只有这几种，写列表外的词是协议内失败，不是新地貌。
 *
 * ## 为什么有一个 [glyph]
 *
 * [label] 是给人读的（「镇」「林」），而**它没法进网格**：中文在等宽字体里占两列，一张 9×9 的图会
 * 散架，而对齐正是网格的全部价值。所以网格里用 [glyph] 这个 ASCII 字符，真正的名字写在下边的图例里
 * ——与 `spatial/RegionView.kt` 里材质那套做法逐字相同。
 */
enum class Landform(val label: String, val glyph: Char) {
    CITY("城", 'C'),
    TOWN("镇", 'T'),
    ROAD("路", 'R'),
    FOREST("林", 'F'),
    WILD("野", 'W'),
    WATER("水", '~'),
    MOUNTAIN("山", '^'),
}

/**
 * 区划格里的一格（附录 A.16）。
 *
 * 只有两样：**[landform] 与 [note]**。
 *
 * **没有"材质"这一栏，这是刻意的。** 你定的是"区划是意图、地形是事实，两者允许不一致"（A.16）；
 * 一旦区划格里也能写材质，它就变成了半份事实，而"允许不一致"那句话会变得说不清到底是哪一份说了算。
 * 材质留给地形层，生成器自己从 [landform] 推。
 */
@Serializable
data class ZoneTile(
    val landform: Landform,
    /** 导演的自由文本。**代码不解析它**——它只被拼进提示词。 */
    val note: String = "",
)

/** 区划格上的一个位置。**没有 z**：区划是俯视的，不分层。 */
@Serializable
data class ZoneCell(val x: Int, val y: Int)

/** 厘米 → 区划格，向下取整。 */
fun zoneCellOf(cm: Int): Int = floorDiv(cm, ZONE_CM)

@Serializable
data class ZoneBlock(val cell: ZoneCell, val tile: ZoneTile)

@Serializable
data class ZoningSnapshot(val tiles: List<ZoneBlock> = emptyList())

/**
 * 区划层：**导演的涂鸦，但它是世界事实**（附录 A.16）。
 *
 * ## 它不是一份独立的笔记
 *
 * §3.2.4 立过一条：**世界没有独立的事实记录，只有地图本身**，理由是"根本没有第二份可以走偏的数据"。
 * 区划层因此**落在地图上**，玩家也看得见——它是地图的一层，不是导演手里另存的一份草稿。
 *
 * ## 留白是合法的
 *
 * 不在表里的格就是**没标注**。导演可以只画它想清楚的那几块，剩下的留白，生成器遇到留白就用默认
 * 剖面。
 *
 * ## 已探索的不能改
 *
 * 这条**不在这里执行**：判据是"这一片的 chunk 生成过没有"（[Terrain.isGenerated]），而那是 [Terrain]
 * 的事。这里只提供 [isExplored] 这个查询，写入方（导演那只涂鸦工具）负责先问一句。把判据写进
 * `put` 会让同一个规则散在两个地方，而其中一处迟早会被漏掉。
 */
class Zoning private constructor(
    private val tiles: MutableMap<ZoneCell, ZoneTile>,
) {

    val cellCount: Int get() = tiles.size

    fun tileAt(cell: ZoneCell): ZoneTile? = tiles[cell]

    fun all(): List<ZoneBlock> = tiles.entries.map { ZoneBlock(it.key, it.value) }

    /** 落一格。`null` = 抹掉（回到留白）。 */
    fun set(cell: ZoneCell, tile: ZoneTile?) {
        if (tile == null) tiles.remove(cell) else tiles[cell] = tile
    }

    /**
     * **这一格有没有被探索过。**
     *
     * 判据是"它覆盖到的每一个 chunk 都生成过了"——只要还有一块没生成过，这一片就还能改：往没探索过
     * 的地方涂鸦，正是"生成时定制"那条路（A.16）。
     */
    fun isExplored(cell: ZoneCell, terrain: Terrain): Boolean {
        val x0 = cell.x * ZONE_TERRAIN_CELLS
        val y0 = cell.y * ZONE_TERRAIN_CELLS
        val cx = floorDiv(x0, CHUNK_CELLS)..floorDiv(x0 + ZONE_TERRAIN_CELLS - 1, CHUNK_CELLS)
        val cy = floorDiv(y0, CHUNK_CELLS)..floorDiv(y0 + ZONE_TERRAIN_CELLS - 1, CHUNK_CELLS)
        for (x in cx) for (y in cy) if (!terrain.isGenerated(ChunkKey(x, y))) return false
        return true
    }

    fun snapshot(): ZoningSnapshot =
        ZoningSnapshot(all().sortedWith(compareBy({ it.cell.x }, { it.cell.y })))

    companion object {

        fun empty(): Zoning = Zoning(mutableMapOf())

        fun from(snapshot: ZoningSnapshot): Zoning =
            Zoning(snapshot.tiles.associateTo(mutableMapOf()) { it.cell to it.tile })
    }
}
