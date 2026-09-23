package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
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
    fun aSingleLayerWallDoesNotTightenTheFootprint() {
        // 这一条是**记录一个真实的后果**，不是记录我们想要的行为：墙只在 z=0 时，斜向射线
        // （±1,0,±1）从它上面/下面飞过去了，于是俯视图上的跨度一点没变。
        //
        // 也就是说：**要让框真的贴合，能挡射线的得是跨层的东西**（墙、地形），单摆一件家具挡不住。
        // 这条在探针里会再露一次面。
        val wall = (0..6).map { thing("w$it", 13, it) }
        val w = world(thing("a", 12, 3), *wall.toTypedArray())
        val r = RegionBuilder.of(w, listOf("a"))
        assertEquals(15, r.maxX, "单层的墙不该收紧俯视图跨度——如果它收紧了，说明算法被改过了")
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
        assertEquals(12, r2.rayLen)
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
