package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 空间框的算法测试。**全部离线、全部确定性**——这个算法不碰模型，所以它该像别的纯函数一样被钉死。
 */
class RegionTest {

    private fun thing(id: String, x: Int, y: Int, z: Int = 0) =
        Entity(id = id, kind = "东西", label = id, x = x, y = y, z = z)

    private fun world(vararg e: Entity) = World.from(WorldSnapshot(e.toList()))

    /** 这条不变量是整套算法的存在理由：**框必须装得下发起者自己**，否则它拿到的坐标范围里不含它。 */
    private fun assertCentersInside(world: World, centers: List<String>, widenings: Int = 0) {
        val r = RegionBuilder.of(world, centers, widenings)
        for (id in centers) {
            val e = requireNotNull(world.byId(id))
            assertTrue(
                r.contains(e.x, e.y),
                "框 $r 里没有 $id 自己（在 ${e.x}, ${e.y}）——凸包把它关在外面了",
            )
        }
    }

    @Test
    fun emptySpaceIsExactlySevenBySeven() {
        // 周围一片空时，6 个轴向的端点围出来正好是 ±3。**"退回最小方框"不用单独写一条分支**，
        // 它就是这套算法在真空里的自然结果。
        val r = RegionBuilder.of(world(thing("a", 12, 3)), listOf("a"))
        assertEquals(9, r.minX)
        assertEquals(15, r.maxX)
        assertEquals(0, r.minY)
        assertEquals(6, r.maxY)
        assertEquals(7, r.width)
        assertEquals(7, r.height)
        assertEquals(0, r.widenings)
    }

    @Test
    fun boxAlwaysContainsItsCenters() {
        assertCentersInside(world(thing("a", 12, 3)), listOf("a"))

        // 两个东西分得很开：框必须把它们连起来，而不是只包一个。
        val spread = world(thing("a", 0, 0), thing("b", 20, 8))
        assertCentersInside(spread, listOf("a", "b"))
        val r = RegionBuilder.of(spread, listOf("a", "b"))
        assertTrue(r.minX <= 0 && r.maxX >= 20, "框没把两个东西都框住：$r")
        assertTrue(r.minY <= 0 && r.maxY >= 8, "框没把两个东西都框住：$r")

        // 扩过之后仍然必须装得下。
        assertCentersInside(spread, listOf("a", "b"), widenings = 2)
    }

    @Test
    fun obstaclesAcrossLayersTightenTheBox() {
        // 一条**跨层**的墙（z 从 -3 到 3）。斜向射线只有在墙上真的有个东西时才停得住。
        val wall = (0..6).flatMap { y -> (-3..3).map { z -> thing("w_${y}_$z", 13, y, z) } }
        val w = world(thing("a", 12, 3), *wall.toTypedArray())
        val r = RegionBuilder.of(w, listOf("a"))
        assertEquals(13, r.maxX, "东边那堵墙没把框拦住：$r")
        assertEquals(9, r.minX, "西边没东西，该走满长度")
    }

    @Test
    fun aSingleLayerWallTightensOnlyPartOfTheWay() {
        // 这一条量过三次，每次数字都不一样，所以三个都记下来：
        //
        // | 方向集 | maxX |
        // |---|---|
        // | 整数 18 向（6 轴向 + 12 棱对角） | 15（一点没挡住） |
        // | 全局斐波那契 18 条 | 13（完全挡住） |
        // | 十个扇区 × 各 2 条 | **14**（挡住一半） |
        //
        // 原因很清楚：EAST 扇区在第 1 档只有 **2 条**射线。第一条正好落在 (1,0,0)，撞在墙上；
        // 第二条仰角 32.7°，从单层的墙上方飞了过去。要挡住它得有贴着地面走的射线，那是密度问题，
        // 不是算法问题——**跨层的东西（墙、地形）才挡得住全部方向**，探针 ⑦ 里 maxX 是 13。
        val wall = (0..6).map { thing("w$it", 13, it) }
        val w = world(thing("a", 12, 3), *wall.toTypedArray())
        val r = RegionBuilder.of(w, listOf("a"))
        assertEquals(14, r.maxX, "单层的墙只收紧到一半：$r")
        assertEquals(9, r.minX, "西边没东西，该走满长度")
    }

    @Test
    fun everySectorIsCountedAndSpacedByTheSameRule() {
        // 十个扇区的立体角加起来正好是 4π——不重不漏。这是下面所有条数式子的前提。
        val total = Direction.ALL.sumOf { it.solidAngle }
        assertTrue(abs(total - 4 * PI) < 1e-9, "十个扇区的立体角之和该是 4π，实际 $total")
        assertEquals(10, Direction.ALL.size)

        // 条数按 k² 长。整体抬一档：24 → 92 → 216 条。
        assertEquals(24, RegionBuilder.rayCount(1))
        assertEquals(92, RegionBuilder.rayCount(2))
        assertEquals(216, RegionBuilder.rayCount(3))

        for (d in Direction.ALL) {
            for (k in 1..3) {
                val dirs = d.directions(k)
                assertEquals(d.rayCount(k), dirs.size, "$d 在第 $k 档的条数不对")
                for ((x, y, z) in dirs) {
                    val len = sqrt(x * x + y * y + z * z)
                    assertTrue(abs(len - 1.0) < 1e-9, "$d 第 $k 档里有个不是单位向量：($x, $y, $z) 长度 $len")
                }
            }
        }

        // 每一条射线摊到的球面面积 ≈ 4.71 格²（间距 2.17 格）。**它必须在每一档、每个扇区上都差不多**
        // ——那正是这次改动的全部目的：老的 18 条固定，射线一长缝就跟着大（2.5 → 5.0 → 10.0 → 20.0）。
        val spacing = (1..5).flatMap { k ->
            Direction.ALL.map { d ->
                val r = RegionBuilder.DEFAULT_RAY.toDouble() * k
                sqrt(d.solidAngle * r * r / d.rayCount(k))
            }
        }
        assertTrue(
            spacing.max() / spacing.min() < 1.2,
            "每一档的密度散得太开了：最小 ${spacing.min()}，最大 ${spacing.max()}",
        )
    }

    @Test
    fun sampledDirectionsAgreeWithTheSectorTheyAreNamedAfter() {
        // **这两件事必须一致。** 模型读到框外那句「在东南 8 格外」，拿「东南」要求看远一档，
        // 而东南那一档的射线必须真的铺在东南——否则它花了钱、多等一趟，拿回来的还是原来那一片，
        // 而这件事没有任何地方会报错。
        assertEquals(Direction.EAST, Direction.of(5, 0, 0))
        assertEquals(Direction.SOUTHEAST, Direction.of(5, 5, 0))
        assertEquals(Direction.SOUTH, Direction.of(0, 5, 0))
        assertEquals(Direction.SOUTHWEST, Direction.of(-5, 5, 0))
        assertEquals(Direction.WEST, Direction.of(-5, 0, 0))
        assertEquals(Direction.NORTHWEST, Direction.of(-5, -5, 0))
        assertEquals(Direction.NORTH, Direction.of(0, -5, 0))
        assertEquals(Direction.NORTHEAST, Direction.of(5, -5, 0))
        assertEquals(Direction.UP, Direction.of(0, 0, 5))
        assertEquals(Direction.DOWN, Direction.of(0, 0, -5))

        for (d in Direction.ALL) {
            for (k in 1..3) {
                for ((x, y, z) in d.directions(k)) {
                    val near = Direction.of(
                        (x * 1000).roundToInt(),
                        (y * 1000).roundToInt(),
                        (z * 1000).roundToInt(),
                    )
                    assertEquals(d, near, "$d 第 $k 档采样出一个会被划到 $near 的方向：($x, $y, $z)")
                }
            }
        }
    }

    @Test
    fun wideningOneSectorCostsFarLessThanWideningAll() {
        // 这是"指定方向"存在的理由，所以把它钉住：只抬一个扇区要加的射线，是整体抬一档的零头。
        val all = RegionBuilder.rayCount(2)
        val one = RegionBuilder.rayCount(1) - Direction.SOUTHEAST.rayCount(1) + Direction.SOUTHEAST.rayCount(2)
        assertTrue(one * 3 < all, "只抬东南一档要 $one 条，整体抬一档是 $all 条——便宜得不够明显")
    }

    @Test
    fun wideningAlwaysGrows() {
        val wall = (0..6).flatMap { y -> (-3..3).map { z -> thing("w_${y}_$z", 13, y, z) } }
        val w = world(thing("a", 12, 3), *wall.toTypedArray())
        val r0 = RegionBuilder.of(w, listOf("a"), widenings = 0)
        val r1 = RegionBuilder.of(w, listOf("a"), widenings = 1)
        val r2 = RegionBuilder.of(w, listOf("a"), widenings = 2)

        assertEquals(3, r0.rayLen)
        assertEquals(6, r1.rayLen)
        assertEquals(9, r2.rayLen)
        assertEquals(0, r0.widenings)
        assertEquals(1, r1.widenings)
        assertEquals(2, r2.widenings)

        // **扩大必须真的长大。** 若"已经看过的东西不再算阻挡"这条没生效，射线还是会在同一堵墙上
        // 被拦下，三次的 maxX 会一模一样——那是最容易悄悄坏掉的地方。
        assertTrue(r1.maxX > r0.maxX, "扩大一次没变大：${r0.maxX} → ${r1.maxX}")
        assertTrue(r2.maxX > r1.maxX, "扩大两次没变大：${r1.maxX} → ${r2.maxX}")
    }

    @Test
    fun convexHullDropsCollinearPoints() {
        val line = listOf(Cell(0, 0), Cell(1, 1), Cell(2, 2))
        // 全共线时退化成两个端点——调用方据此走"退化"那条路（用外接矩形兜底）。
        assertEquals(2, RegionBuilder.convexHull(line).size)

        val square = listOf(Cell(0, 0), Cell(4, 0), Cell(4, 4), Cell(0, 4), Cell(2, 2))
        val hull = RegionBuilder.convexHull(square)
        assertEquals(4, hull.size, "内部那个点不该出现在凸包上：$hull")
        assertTrue(Cell(2, 2) !in hull)
    }

    @Test
    fun whateverStopsARayIsAlwaysInTheRegion() {
        // 把射线挡下来的那样东西，**定义的就是这片空间的边**。它必须出现在片里：否则判定会看见
        // 一个在 x=13 处齐刷刷断掉的框，却不知道 13 那里是什么——而那正是最该知道的东西。
        val wall = (0..8).flatMap { y -> (-3..3).map { z -> thing("w_${y}_$z", 13, y, z) } }
        val w = world(thing("a", 12, 3), *wall.toTypedArray())
        val r = RegionBuilder.of(w, listOf("a"))

        assertTrue(r.hitIds.isNotEmpty(), "墙该被射线打中：$r")
        for (id in r.hitIds) {
            val e = requireNotNull(w.byId(id))
            assertTrue(r.covers(e), "$id 把射线挡下来了，却不在这片里（在 ${e.x}, ${e.y}, 层 ${e.z}）")
        }

        // 穿越法是半开的，只判内部——照那样算，凸包整条边上每一格都会被判成框外，而被打中的东西
        // 恰恰都站在那条边上。所以边界必须算框内。
        assertTrue(r.contains(13, 3), "凸包边界上的格子该算在框里：$r")
        assertTrue(!r.contains(15, 3), "框外的格子不该算在框里：$r")
    }
}
