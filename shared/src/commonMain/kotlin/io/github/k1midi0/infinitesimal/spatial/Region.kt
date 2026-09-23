package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import kotlin.math.abs

/** 俯视图上的一个格子。 */
data class Cell(val x: Int, val y: Int)

/** 一次射线追出来的落点。`hit` 为 false 表示那条射线一路没碰到东西，走满长度停下的。 */
data class RayPoint(val x: Int, val y: Int, val z: Int, val hit: Boolean)

/**
 * 判定看得见的那一片空间。
 *
 * 它由**射线追出来的参考点的凸包**定义，而不是一个各边等长的方框——理由见 [RegionBuilder]。
 */
class Region internal constructor(
    val points: List<RayPoint>,
    private val hull: List<Cell>,
    private val degenerate: Boolean,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val minZ: Int,
    val maxZ: Int,
    /** 这一次用的射线长度。扩大一次就翻一倍。 */
    val rayLen: Int,
    /** 已经被要求扩大过几次。0 表示这是第一眼。 */
    val widenings: Int,
) {
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
    val cellCount: Int get() = if (degenerate) width * height else hullCells().size

    /** 这个格子在不在框里。 */
    fun contains(x: Int, y: Int): Boolean {
        if (x < minX || x > maxX || y < minY || y > maxY) return false
        // 退化的凸包（一个点、或一条线）没有内部可言，那时用外接矩形兜底——**这一片仍必须能装下
        // 发起者自己**，否则判定拿到的坐标范围里不含它，反而更容易瞎填。
        if (degenerate) return true
        return insidePolygon(x, y)
    }

    fun cells(): List<Cell> {
        if (degenerate) {
            return buildList {
                for (y in minY..maxY) for (x in minX..maxX) add(Cell(x, y))
            }
        }
        return hullCells()
    }

    private fun hullCells(): List<Cell> {
        // 凸包不大（一次射线最多也就几十格跨度），逐行求交即可，不值得做扫描线优化。
        val out = mutableListOf<Cell>()
        for (y in minY..maxY) for (x in minX..maxX) if (insidePolygon(x, y)) out += Cell(x, y)
        return out
    }

    private fun insidePolygon(x: Int, y: Int): Boolean {
        // 标准的奇偶穿越法（甚至-奇数规则）。用 double 算交点而不是拿整数叉积判侧，是因为格子中心
        // 取 (x, y)，整数叉积在点正好落在边上时符号会翻——那种边界情况不值得为它省一次除法。
        var inside = false
        var j = hull.size - 1
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[j]
            if ((a.y > y) != (b.y > y)) {
                val crossing = a.x + (y - a.y).toDouble() * (b.x - a.x) / (b.y - a.y)
                if (x < crossing) inside = !inside
            }
            j = i
        }
        return inside
    }

    override fun toString(): String =
        "Region(x $minX..$maxX, y $minY..$maxY, 层 $minZ..$maxZ, ${cellCount} 格, 射线长 $rayLen, 扩大 $widenings 次)"
}

/**
 * 空间框的算法。
 *
 * ## 为什么不是方框
 *
 * 各边等长的方框有个说不清的地方：**它的大小该是多少？** 定小了装不下动作涉及的东西，定大了把
 * 半张地图都塞给判定。而这个数字无论取多少都是拍脑袋——世界上没有任何东西支持它。
 *
 * 改成**射线**之后，框的大小由"周围实际有什么"决定：
 *
 * - 从每个中心（发起者 + 动作指向的东西）朝 **18 个方向**各发一条射线；
 * - 射线**碰到东西就停**，那个交点作为一个参考点；
 * - **碰不到就走满 [RegionBuilder.DEFAULT_RAY] 格**，端点同样作为参考点；
 * - 所有参考点的**凸包**就是这一片。
 *
 * 第二条是关键：**每条射线都一定留下一个点**。于是参考点天然围着中心一圈，凸包**必然包含发起者
 * 和指向的东西**——这不是碰巧，是构造出来的。若把打空的射线丢掉（比如朝上的那几条），凸包就可能
 * 缩到中心一侧，反而把自己关在外面。
 *
 * ## 18 向是三维的
 *
 * 6 个轴向 + 12 个棱对角，**没有面对角线**（那会变成 26 向）。层是真实的一维，所以「指向天空」
 * 是一条真的射线：它在 z 上走满长度，落点在 (x,y) 上不挪窝，于是**不影响俯视图的形状**，但会撑高
 * 层范围。
 *
 * ## 扩大
 *
 * 判定说"不够"时调 `widen_view()`，算一遍新的：**射线长度翻倍**，而且**上一眼够得着的那段距离之内的
 * 东西不再算阻挡**——不然射线还是被同一张桌子在第一步就拦下，扩大等于没扩。这条也让扩大一定收敛：
 * 每扩一次，起算距离就挪远一倍，而世界里的东西是有限的。
 */
object RegionBuilder {

    /** 6 个轴向 + 12 个棱对角。这个数字是数出来的，不是挑的。 */
    private val DIRECTIONS: List<Triple<Int, Int, Int>> = buildList {
        fun add3(x: Int, y: Int, z: Int) { add(Triple(x, y, z)) }
        for (axis in 0..2) {
            for (sign in listOf(-1, 1)) {
                add3(
                    if (axis == 0) sign else 0,
                    if (axis == 1) sign else 0,
                    if (axis == 2) sign else 0,
                )
            }
        }
        for (a in 0..2) for (b in a + 1..2) for (sa in listOf(-1, 1)) for (sb in listOf(-1, 1)) {
            val v = IntArray(3)
            v[a] = sa
            v[b] = sb
            add3(v[0], v[1], v[2])
        }
    }

    init {
        check(DIRECTIONS.size == 18) { "18 向算错了：${DIRECTIONS.size}" }
        check(DIRECTIONS.toSet().size == 18) { "18 向里有重复" }
    }

    /**
     * 打不到东西时，射线走多远。
     *
     * **3 这个数不是随手取的**：周围一片空的时候，6 个轴向的端点围出来恰好是 7×7 格——也就是
     * 那个"最小方框"。于是"退回最小方框"不需要单独一条分支，它就是这套算法在真空里的自然结果。
     */
    const val DEFAULT_RAY: Int = 3

    /** 一次最多画多大的网格。再大就只列格子，不然一段提示词里全是点。 */
    const val GRID_LIMIT: Int = 16

    fun of(
        world: World,
        centers: List<String>,
        widenings: Int = 0,
    ): Region {
        val entities = world.all()
        val centerEntities = centers.mapNotNull { world.byId(it) }
        require(centerEntities.isNotEmpty()) { "一个中心都没有，框无处可谈" }

        var region = trace(entities, centerEntities, DEFAULT_RAY, widenings = 0, reach = 0)
        repeat(widenings) {
            // **上一眼够得着的距离之内的东西，这一眼不再算阻挡。**
            //
            // 这条一开始写的是"上一个框的格子集合"，跑出来是错的：参差凸包的边界格子有一半落在框
            // 外，而卡在边界上的那堵墙正好就在那些格子上，于是射线还是被同一堵墙拦下——**扩大一次
            // maxX 纹丝不动**。改成按"离中心的距离"之后就对了，而且它才是本来想说的那句话。
            region = trace(
                entities,
                centerEntities,
                region.rayLen * 2,
                widenings = region.widenings + 1,
                reach = region.rayLen,
            )
        }
        return region
    }

    private fun trace(
        entities: List<Entity>,
        centers: List<Entity>,
        rayLen: Int,
        widenings: Int,
        /** 离中心没超过这个距离的东西不算阻挡。第一眼是 0，也就是什么都算阻挡。 */
        reach: Int,
    ): Region {
        val points = mutableListOf<RayPoint>()
        for (c in centers) {
            for ((dx, dy, dz) in DIRECTIONS) {
                var landed: RayPoint? = null
                for (t in 1..rayLen) {
                    val x = c.x + dx * t
                    val y = c.y + dy * t
                    val z = c.z + dz * t
                    val blocked = entities.any { e ->
                        e.id != c.id && e.x == x && e.y == y && e.z == z &&
                            maxOf(abs(e.x - c.x), abs(e.y - c.y), abs(e.z - c.z)) > reach
                    }
                    if (blocked) {
                        landed = RayPoint(x, y, z, hit = true)
                        break
                    }
                }
                points += landed ?: RayPoint(c.x + dx * rayLen, c.y + dy * rayLen, c.z + dz * rayLen, hit = false)
            }
        }

        val projected = points.map { Cell(it.x, it.y) }.distinct()
        val hull = convexHull(projected)
        // 三个不共线的点才围得出一个有内部的凸包；一个点、或一条直线上的点，用外接矩形兜底。
        val degenerate = hull.size < 3
        return Region(
            points = points,
            hull = hull,
            degenerate = degenerate,
            minX = projected.minOf { it.x },
            maxX = projected.maxOf { it.x },
            minY = projected.minOf { it.y },
            maxY = projected.maxOf { it.y },
            minZ = points.minOf { it.z },
            maxZ = points.maxOf { it.z },
            rayLen = rayLen,
            widenings = widenings,
        )
    }

    /** Andrew 单调链。返回逆时针的顶点（含共线的端点会被去掉）。 */
    internal fun convexHull(points: List<Cell>): List<Cell> {
        val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted

        fun cross(o: Cell, a: Cell, b: Cell): Long =
            (a.x - o.x).toLong() * (b.y - o.y) - (a.y - o.y).toLong() * (b.x - o.x)

        val lower = mutableListOf<Cell>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = mutableListOf<Cell>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper += p
        }
        val hull = (lower.dropLast(1) + upper.dropLast(1))
        // 全共线时单调链会退化成两个端点，这时交回给调用方去走"退化"那条路。
        return hull
    }

    /** 供测试用：两个格子之间的切比雪夫距离（层无关）。 */
    internal fun chebyshev(a: Entity, b: Entity): Int = maxOf(abs(a.x - b.x), abs(a.y - b.y))
}
