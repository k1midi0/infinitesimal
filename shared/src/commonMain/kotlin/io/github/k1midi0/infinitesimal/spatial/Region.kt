package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 俯视图上的一个格子。 */
data class Cell(val x: Int, val y: Int)

/**
 * 一次射线追出来的落点。
 *
 * `hit` 为 false 表示那条射线一路没碰到东西，走满长度停下的。[blockerId] 是把它挡下来的那样东西
 * ——**它可能落在凸包的边界上，而不是内部**。
 *
 * **挡住射线的有两类：东西，与地形**（附录 A.15）。两类分别记在两个字段上，因为后面要做的事完全
 * 不同：东西要进 [Region.hitIds] 从而进清单，而地形不进清单——同一个材质在网格里已经画过一次，
 * 在清单里再列一遍就是两份可以不一致的数据。
 */
data class RayPoint(
    val x: Int,
    val y: Int,
    val z: Int,
    val hit: Boolean,
    /** 把它挡下来的**东西**的 id。地形挡下时是 null。 */
    val blockerId: String? = null,
    /** 把它挡下来的**地形**的材质 id。东西挡下时是 null。 */
    val blockerMaterial: String? = null,
)

/**
 * 判定看得见的那一片空间。
 *
 * 它由**射线追出来的参考点的凸包**定义，而不是一个各边等长的方框——理由见 [RegionBuilder]。
 */
class Region internal constructor(
    val points: List<RayPoint>,
    /**
     * **被射线打中的那些东西。**
     *
     * 它们必须算作"在框里"，哪怕它们的格子落在凸包边界上——而它们几乎总在边界上：把射线挡下来的
     * 那样东西，定义的就是这片空间的边。少了它们，判定会看见一个在 x=13 处齐刷刷断掉的框，
     * 却不知道 13 那里是什么。
     */
    val hitIds: Set<String>,
    private val hull: List<Cell>,
    private val degenerate: Boolean,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val minZ: Int,
    val maxZ: Int,
    /**
     * 每个扇区各自的档位，顺序同 [Direction.ALL]。
     *
     * **它取代了原来那个单一的 k。** 定向扩大之后东南可以比西边远一档，而框的凸包自然就是偏的
     * ——那正是"往那边看"的意思。
     */
    val levels: IntArray,
) {
    private val maxLevel: Int get() = levels.maxOrNull() ?: 1

    /** 最远的那个扇区用的射线长。各方向不同时它只是个上界。 */
    val rayLen: Int get() = RegionBuilder.DEFAULT_RAY * maxLevel

    /** 比第一档高出的最大档数。0 表示这是第一眼。 */
    val widenings: Int get() = maxLevel - 1
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
    val cellCount: Int get() = cells().size

    /**
     * 这个格子在不在框里。
     *
     * **边界算在框里**（[onBoundary]）。穿越法是半开的，只判内部——照那样算，凸包的整条边上每一格
     * 都会被判成框外，而射线打中的东西恰恰都站在那条边上。
     */
    fun contains(x: Int, y: Int): Boolean {
        if (x < minX || x > maxX || y < minY || y > maxY) return false
        // 退化的凸包（一个点、或一条线）没有内部可言，那时用外接矩形兜底——**这一片仍必须能装下
        // 发起者自己**，否则判定拿到的坐标范围里不含它，反而更容易瞎填。
        if (degenerate) return true
        return insidePolygon(x, y) || onBoundary(x, y)
    }

    /**
     * 那样东西算不算在这片里：**它占的格子有任何一个落在框内，或者它是被射线打中的那一个**。
     *
     * 用 [occupiedCells] 而不是它坐标上那一个点：一张两米长的桌子，只要有一头伸进框里，它就该在
     * 清单上——报一个"半张桌子在这儿"比报"这儿没有桌子"坏得多。
     */
    fun covers(entity: Entity): Boolean =
        entity.id in hitIds || entity.occupiedCells().any { contains(it.x, it.y) }

    fun cells(): List<Cell> {
        if (degenerate) {
            return buildList {
                for (y in minY..maxY) for (x in minX..maxX) add(Cell(x, y))
            }
        }
        // 凸包不大（一次射线最多也就几十格跨度），逐格判定即可，不值得做扫描线优化。
        val out = mutableListOf<Cell>()
        for (y in minY..maxY) for (x in minX..maxX) if (contains(x, y)) out += Cell(x, y)
        return out
    }

    /** 整数点是否正落在凸包的某条边上。 */
    private fun onBoundary(x: Int, y: Int): Boolean {
        var j = hull.size - 1
        for (i in hull.indices) {
            val a = hull[i]
            val b = hull[j]
            val cross = (b.x - a.x).toLong() * (y - a.y) - (b.y - a.y).toLong() * (x - a.x)
            if (cross == 0L &&
                x >= minOf(a.x, b.x) && x <= maxOf(a.x, b.x) &&
                y >= minOf(a.y, b.y) && y <= maxOf(a.y, b.y)
            ) {
                return true
            }
            j = i
        }
        return false
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
 * - 从每个中心（发起者 + 动作指向的东西）朝 **球面上均匀铺开的若干个方向**各发一条射线；
 * - 射线**碰到东西就停**，那个交点作为一个参考点；
 * - **碰不到就走满 [RegionBuilder.DEFAULT_RAY] 格**，端点同样作为参考点；
 * - 所有参考点的**凸包**就是这一片。
 *
 * 第二条是关键：**每条射线都一定留下一个点**。于是参考点天然围着中心一圈，凸包**必然包含发起者
 * 和指向的东西**——这不是碰巧，是构造出来的。若把打空的射线丢掉（比如朝上的那几条），凸包就可能
 * 缩到中心一侧，反而把自己关在外面。
 *
 * ## 方向是三维的
 *
 * 层是真实的一维，所以确实有「指向天空」这类射线：它们在 z 上走得最远，落点在 (x,y) 上不挪窝，
 * 于是**不影响俯视图的形状**，但会撑高层范围。球面采样天然覆盖这些方向，不需要单独挑出来。
 *
 * ## 扩大
 *
 * 判定说"不够"时调 `widen_view()`，算一遍新的：**长度加一档、条数按 k² 长**，而且**上一眼够得着的那段距离之内的
 * 东西不再算阻挡**——不然射线还是被同一张桌子在第一步就拦下，扩大等于没扩。这条也让扩大一定收敛：
 * 每扩一次，起算距离就挪远一档，而世界里的东西是有限的。
 *
 * **长度每档只加 3，不翻倍。** 要维持空间密度，条数就得按 k² 长；长度再翻倍的话，k 得跟着翻，
 * 条数就是每扩一次乘四——k=8 时 1530 条。加一档的代价是每扩一次要多走一轮往返，但从实测看
 * 一次判定最多也就扩两次。
 */
object RegionBuilder {

    /**
     * 十个扇区都在第 k 档时，射线一共几条。
     *
     * **定向扩大之后各扇区档位不同，总数只能逐个问 [Direction.rayCount]。** 这一条只是"整体扩大
     * 一次要多少条"的那个数。
     */
    internal fun rayCount(k: Int): Int = Direction.ALL.sumOf { it.rayCount(k) }

    /**
     * 打不到东西时，射线走多远。
     *
     * **3 这个数不是随手取的**：它是第一档的射线长度（k=1，18 条球面方向）。周围一片空时，
     * 最靠东西南北的那几条端点落在 ±3 上，围出来正好是 7×7 格——也就是
     * 那个"最小方框"。于是"退回最小方框"不需要单独一条分支，它就是这套算法在真空里的自然结果。
     */
    const val DEFAULT_RAY: Int = 3

    /** 一次最多画多大的网格。再大就只列格子，不然一段提示词里全是点。 */
    const val GRID_LIMIT: Int = 16

    fun of(
        world: World,
        centers: List<String>,
        widenings: Int = 0,
    ): Region = of(world, centers, IntArray(Direction.ALL.size) { widenings + 1 })

    /**
     * 各扇区档位不同时的入口。数组顺序就是 [Direction.ALL]。
     *
     * 定向扩大（`widen_view("东南")`）走的就是这一条：只把那一个扇区抬高，射线数只多那一个扇区的
     * 那几条（[Direction.rayCount]）——整体抬一档是 24 → 92 条，只抬东南一档是 24 → 30 条。
     */
    fun of(world: World, centers: List<String>, levels: IntArray): Region {
        require(levels.size == Direction.ALL.size) {
            "档位数组该有 ${Direction.ALL.size} 项（一个扇区一项），实际 ${levels.size}"
        }
        val entities = world.all()
        val centerEntities = centers.mapNotNull { world.byId(it) }
        require(centerEntities.isNotEmpty()) { "一个中心都没有，框无处可谈" }
        return trace(world, entities, centerEntities, levels)
    }

    private fun trace(
        world: World,
        entities: List<Entity>,
        centers: List<Entity>,
        /** 一个扇区一项的档位。同一档的射线长 `3k`。 */
        levels: IntArray,
    ): Region {
        // **单位在这里换一次，之后整条射线链上全是米格。** 世界把坐标存成厘米（一张桌子高 80 厘米
        // 是它的事实），而射线、凸包、包围框按 1 米格走（附录 A.12）。两者不是两套数据，是同一份
        // 坐标的两种分辨率——所以换算是**一处**，不是散在每一条 `abs()` 里。
        //
        // **阻挡面按"每样东西占哪几格"建一次**，不是每走一步去问每样东西一遍。差别不只是快：
        // 建一次之后，"一格被两样东西压着"这件事只在这里决一次（先遇到的那样赢），而不是每一步
        // 各决一次、结果还可能不一样。
        val blockers = mutableMapOf<Cell3, String>()
        for (e in entities) {
            for (c in e.occupiedCells()) blockers.putIfAbsent(c, e.id)
        }
        val points = mutableListOf<RayPoint>()
        for (c in centers) {
            val origin = c.cell()
            for (sector in Direction.ALL.indices) {
                val k = levels[sector]
                val rayLen = DEFAULT_RAY * k
                // **上一档够得着的距离之内的东西，这一档不再算阻挡。**
                //
                // 这条一开始写的是"上一个框的格子集合"，跑出来是错的：参差凸包的边界格子有一半落在框
                // 外，而卡在边界上的那堵墙正好就在那些格子上，于是射线还是被同一堵墙拦下——**扩大一次
                // maxX 纹丝不动**。改成按"离中心的距离"之后就对了，而且它才是本来想说的那句话。
                val reach = DEFAULT_RAY * (k - 1)
                for ((ux, uy, uz) in Direction.ALL[sector].directions(k)) {
                    var landed: RayPoint? = null
                    for (t in 1..rayLen) {
                        val x = origin.x + (ux * t).roundToInt()
                        val y = origin.y + (uy * t).roundToInt()
                        val z = origin.z + (uz * t).roundToInt()
                        if (maxOf(abs(x - origin.x), abs(y - origin.y), abs(z - origin.z)) <= reach) {
                            // **上一档够得着的那段距离之内的东西，这一档不再算阻挡**——不然射线还是
                            // 被同一张桌子在第一步就拦下，扩大等于没扩。地形同样适用：脚下那一层永远
                            // 在最近的一段里，不豁免它的话每次扩大的第一步都是撞地。
                            continue
                        }
                        val cell = Cell3(x, y, z)
                        val blockerId = blockers[cell]
                        if (blockerId != null && blockerId != c.id) {
                            landed = RayPoint(x, y, z, hit = true, blockerId = blockerId)
                            break
                        }
                        // 地形与东西**同级**（附录 A.15）。它排在东西后面：一样东西站在墙上时，模型
                        // 要读到的是那样东西，而不是"这里有一堵墙"。
                        val material = world.solidTerrainAt(cell)
                        if (material != null) {
                            landed = RayPoint(x, y, z, hit = true, blockerMaterial = material)
                            break
                        }
                    }
                    points += landed ?: RayPoint(
                        origin.x + (ux * rayLen).roundToInt(),
                        origin.y + (uy * rayLen).roundToInt(),
                        origin.z + (uz * rayLen).roundToInt(),
                        hit = false,
                    )
                }
            }
        }

        val projected = points.map { Cell(it.x, it.y) }.distinct()
        val hull = convexHull(projected)
        // 三个不共线的点才围得出一个有内部的凸包；一个点、或一条直线上的点，用外接矩形兜底。
        val degenerate = hull.size < 3
        return Region(
            points = points,
            hitIds = points.mapNotNull { it.blockerId }.toSet(),
            hull = hull,
            degenerate = degenerate,
            minX = projected.minOf { it.x },
            maxX = projected.maxOf { it.x },
            minY = projected.minOf { it.y },
            maxY = projected.maxOf { it.y },
            minZ = points.minOf { it.z },
            maxZ = points.maxOf { it.z },
            levels = levels,
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
