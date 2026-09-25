package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.BUFFER_RADIUS_CHUNKS
import io.github.k1midi0.infinitesimal.world.CHUNK_CELLS
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.HARD_RADIUS_CHUNKS
import io.github.k1midi0.infinitesimal.world.TerrainBox
import io.github.k1midi0.infinitesimal.world.TerrainCell
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.chunkOf
import io.github.k1midi0.infinitesimal.world.floorDiv
import io.github.k1midi0.infinitesimal.world.terrainCellOf
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * **区块坐标的显示原点。**
 *
 * 原点落在**玩家开局那一块的左下**——于是玩家开局在 (1,1)，世界往东北两个方向长成正数。这条是用户
 * 定的（§14.33）。它不跟着玩家走：玩家走到哪里，地图上那些数字都还是同一个意思。
 *
 * 为什么要一个原点：区块的绝对坐标是从世界 (0,0) 数起的，而那个 (0,0) 是个谁都没见过的位置——
 * 把 `(84,-13)` 交给模型，与把 `(2,-1)` 交给它，是两件事。给模型看的永远是**相对原点**的数字，
 * 换算只在 [ChunkView.display] 与 [ChunkView.absolute] 这两处发生。
 */
data class ChunkOrigin(val chunk: ChunkKey) {
    fun display(of: ChunkKey): ChunkKey = ChunkKey(of.x - chunk.x, of.y - chunk.y)
    fun absolute(of: ChunkKey): ChunkKey = ChunkKey(of.x + chunk.x, of.y + chunk.y)

    companion object {
        /**
         * 开局的显示原点：**玩家所在那一块的左下那一块**。
         *
         * 用户的原话是「玩家在 (1,1)，即原点右上方第一格」，所以原点 = 玩家那一块减一。写下这一条而不是
         * 直接写 `ChunkKey(0, 0)`，是因为它表达的是「原点与玩家的关系」这条规则本身。
         */
        fun atPlayerStart(playerChunk: ChunkKey): ChunkOrigin =
            ChunkOrigin(ChunkKey(playerChunk.x - 1, playerChunk.y - 1))
    }
}

/** 一样东西落在哪个区块上。 */
fun World.chunkOfEntity(id: String): ChunkKey? = byId(id)?.let {
    chunkOf(TerrainCell(terrainCellOf(it.x), terrainCellOf(it.y), terrain.depth.bottom))
}

/**
 * 以 [center] 为心、半径 [radius] 块的**区块圆盘**。
 *
 * 判据是 `dx² + dy² ≤ r²`，与开局铺地那一条**同一个规则**（`StartingWorld.layGround`）——所以半径 4
 * 恰好是 49 块，开局那片地正好填满它。两处各写一遍半径的后果是「开局圆盘」与「硬圈」错开一格，而
 * 症状是一只永远补不上的月牙。
 */
fun chunkDisc(center: ChunkKey, radius: Int): List<ChunkKey> = buildList {
    val r2 = radius * radius
    for (dx in -radius..radius) {
        for (dy in -radius..radius) {
            if (dx * dx + dy * dy > r2) continue
            add(ChunkKey(center.x + dx, center.y + dy))
        }
    }
}

/**
 * **硬圈里还没有地形的那些块。** 这是「必须生成」那一份清单，由代码算，与模型说了什么无关。
 *
 * 「已经有地形了」看的是 [io.github.k1midi0.infinitesimal.world.Terrain.isGenerated]，不是「有没有
 * 列」——§14.30 把那个集合的语义从「生成器长的」改成了「这块地已经有地形了」，开局那片地也标在里面。
 */
fun requiredChunks(world: World, player: ChunkKey, radius: Int = HARD_RADIUS_CHUNKS): List<ChunkKey> =
    chunkDisc(player, radius).filterNot { world.terrain.isGenerated(it) }

/** 最近的一块还没地形的区块，以及它离硬圈有多远。 */
data class NearestMissing(val chunk: ChunkKey, val chunksAway: Int, val beyondHard: Int)

/**
 * 缓冲圈里**最近的一块还没地形的区块**。
 *
 * 这是快速档每回合要看的那个数（§14.33）：`beyondHard` 是它离硬圈边缘还有几块。0 就是「贴着硬圈
 * 边上还缺着」，而那个数一大，快速档就没有理由叫醒思考档。
 *
 * 距离用**欧氏距离向上取整**，与圆盘那一条判据同源；用切比雪夫距离的话，斜着那一列会比看上去更远。
 */
fun nearestMissing(
    world: World,
    player: ChunkKey,
    radius: Int = BUFFER_RADIUS_CHUNKS,
): NearestMissing? {
    var best: NearestMissing? = null
    for (c in chunkDisc(player, radius)) {
        if (world.terrain.isGenerated(c)) continue
        val dx = c.x - player.x
        val dy = c.y - player.y
        val away = ceil(sqrt((dx * dx + dy * dy).toDouble())).toInt()
        if (best == null || away < best.chunksAway) {
            best = NearestMissing(c, away, (away - HARD_RADIUS_CHUNKS).coerceAtLeast(0))
        }
    }
    return best
}

/**
 * 这个地形框横跨哪几个区块。
 *
 * 它是「**这一次生成到底写进了哪些块**」那条判据的算法（§14.33）：必需区块是代码算的那一份，而
 * 「写没写进去」只能从后果自己身上读——模型说了什么不算数。
 */
fun TerrainBox.chunks(): List<ChunkKey> = buildList {
    for (cx in floorDiv(minX, CHUNK_CELLS)..floorDiv(maxX, CHUNK_CELLS)) {
        for (cy in floorDiv(minY, CHUNK_CELLS)..floorDiv(maxY, CHUNK_CELLS)) {
            add(ChunkKey(cx, cy))
        }
    }
}

/**
 * **给导演看的那张区块地图**（§14.33）。
 *
 * 默认画的是**缓冲圈**（以玩家为心、半径 32 块），因为「在缓冲圈里铺多少」正是导演要决定的那件事，
 * 而它得先看见整个圈。要看更远或者看更细用 `look_map`。
 *
 * 坐标是**显示坐标**（相对 [ChunkOrigin]），行头列头就是数轴。列头两行：上一行每十列写一次 x 的
 * 值，下一行是每一列 x 的个位——两三位的数字塞不进一格，这是把数轴压进一行里最省字的写法。
 *
 * 四种字符：`#` 已经有地形、`!` 硬圈里还缺（必需）、`.` 缓冲圈里还缺（可选）、`@` 玩家所在那一块。
 * 圆盘之外是空格，所以这张图的**形状本身就是信息**。
 */
object ChunkView {
    const val GENERATED: Char = '#'
    const val REQUIRED: Char = '!'
    const val OPTIONAL: Char = '.'
    const val PLAYER: Char = '@'

    /** 行头与两行列头的宽度，供 `look_map` 复用同一套排版。 */
    const val LABEL: Int = 6

    fun render(
        world: World,
        origin: ChunkOrigin,
        player: ChunkKey,
        radius: Int = BUFFER_RADIUS_CHUNKS,
    ): String {
        val width = 2 * radius + 1
        val r2 = radius * radius
        val hard2 = HARD_RADIUS_CHUNKS * HARD_RADIUS_CHUNKS

        // 一行里第 i 格对应的世界区块。
        fun absX(i: Int) = player.x - radius + i
        fun absY(j: Int) = player.y - radius + j

        val xs = (0 until width).map { absX(it) }
        val sb = StringBuilder().append(axes(origin, xs))

        for (j in 0 until width) {
            sb.append("y=").append(signed(origin.display(ChunkKey(0, absY(j))).y)).append(' ')
            for (i in 0 until width) {
                val ax = absX(i)
                val ay = absY(j)
                val dx = ax - player.x
                val dy = ay - player.y
                val d2 = dx * dx + dy * dy
                val ch = when {
                    d2 > r2 -> ' '
                    else -> cell(world, player, ax, ay, d2 <= hard2)
                }
                sb.append(ch)
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /**
     * 看地图上的**一框**，坐标是绝对区块坐标（`look_map` 先把数轴上的数字过了 [ChunkOrigin.absolute]）。
     *
     * 与 [render] 的区别只有两处：不画圆盘（框本身就是范围），以及**不把框外的格子涂成空格**。
     * 前者是因为它不是"以玩家为中心看一眼"，后者是因为那样会把请求的框缩回圆盘里去——而
     * "看得远不等于长得到"这条边界由 `grow_terrain` 那边驳回，不该由这一只手悄悄掩掉。
     */
    fun renderBox(
        world: World,
        origin: ChunkOrigin,
        player: ChunkKey,
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ): String {
        val hard2 = HARD_RADIUS_CHUNKS * HARD_RADIUS_CHUNKS
        val xs = (x1..x2).toList()
        val sb = StringBuilder().append(axes(origin, xs))

        for (ay in y1..y2) {
            sb.append("y=").append(signed(origin.display(ChunkKey(0, ay)).y)).append(' ')
            for (ax in xs) {
                val dx = ax - player.x
                val dy = ay - player.y
                sb.append(cell(world, player, ax, ay, dx * dx + dy * dy <= hard2))
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 一格画什么。四种字符的意思见这个对象的注释。 */
    private fun cell(world: World, player: ChunkKey, ax: Int, ay: Int, withinHard: Boolean): Char = when {
        ax == player.x && ay == player.y -> PLAYER
        world.terrain.isGenerated(ChunkKey(ax, ay)) -> GENERATED
        withinHard -> REQUIRED
        else -> OPTIONAL
    }

    /**
     * 两行刻度。十位尺每十列写一次完整的 x（写出来的字符可能盖到右边几格，而刻度之间隔着十格，不会
     * 撞）；个位尺用**绝对值**的个位，不用取模——取模下 -11 的个位是 9，而人读「-11」时看的是那个 1。
     * 符号由十位尺给（它写着 -10、0、+10），两行合起来才是一个完整的数轴刻度。
     */
    private fun axes(origin: ChunkOrigin, xs: List<Int>): String {
        val width = xs.size
        val ruler = CharArray(width) { ' ' }
        for (i in 0 until width) {
            val x = origin.display(ChunkKey(xs[i], 0)).x
            if (x % 10 != 0) continue
            for ((k, ch) in x.toString().withIndex()) {
                val p = i + k
                if (p in 0 until width) ruler[p] = ch
            }
        }
        val units = CharArray(width) { i ->
            '0' + (abs(origin.display(ChunkKey(xs[i], 0)).x) % 10)
        }
        return " ".repeat(LABEL) + ruler.concatToString() + "\n" +
            " ".repeat(LABEL) + units.concatToString() + "\n"
    }

    /** 数轴上那种三字符的写法：`+32`、`-03`、`+00`。定宽是有意义的——行头对齐靠它。 */
    fun signed(v: Int): String = (if (v < 0) "-" else "+") + abs(v).toString().padStart(2, '0')

    /**
     * 把导演写的一个区块坐标读回来。**读进来的永远是显示坐标**，要落进世界得再过 [ChunkOrigin.absolute]。
     *
     * 容忍 `(6,2)`、`6,2`、`( 6 , 2 )` 这几种写法：模型抄数轴上的数字时，括号与空格是随手加的。
     */
    fun parseChunk(text: String, origin: ChunkOrigin): ChunkKey? {
        val m = PATTERN.find(text) ?: return null
        val x = m.groupValues[1].toIntOrNull() ?: return null
        val y = m.groupValues[2].toIntOrNull() ?: return null
        return origin.absolute(ChunkKey(x, y))
    }

    private val PATTERN = Regex("""\(?\s*(-?\d+)\s*,\s*(-?\d+)\s*\)?""")

    /** 把一段文本里所有区块坐标读出来。导演填的是一串，逗号或空白分开都认。 */
    fun parseChunks(text: String, origin: ChunkOrigin): List<ChunkKey> =
        PATTERN.findAll(text).mapNotNull { m ->
            val x = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val y = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            origin.absolute(ChunkKey(x, y))
        }.toList()
}
