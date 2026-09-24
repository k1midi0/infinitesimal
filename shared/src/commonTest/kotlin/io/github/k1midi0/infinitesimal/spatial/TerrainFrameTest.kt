package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.TerrainBox
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldDepth
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 地形与空间框的关系（附录 A.15）。
 *
 * 这一份里最重要的一条是 [standingOnTheGroundDoesNotCollapseTheFrame]：它钉住的是一个**看起来
 * 完全正常、实际全错**的判据——"米格里只要有地形就算实心"。按那个判据，发起者自己站的那一格
 * （下半格土、上半格空气）就是实心的，于是每条水平射线在 t=1 撞地，框塌成 3×3，而症状像是"这片
 * 世界很挤"，不像一个 bug。
 */
class TerrainFrameTest {

    private val ground = WorldDepth().surfaceCm

    /** 一个站在大地上的角色。地形由调用方铺——**这一份测试里地形就是主角**。 */
    private fun world(): World =
        World.from(WorldSnapshot(listOf(Entity("player", "角色", "你", 1000, 1000, ground))))

    /** 从地形格 0 到地面铺满，也就是 A.16 那条"硬性圈里每一格都有材质"。 */
    private fun World.pave() {
        terrain.fill(TerrainBox(0, 0, 0, 63, 63, WorldDepth().surface), "soil", materials)
    }

    @Test
    fun standingOnTheGroundDoesNotCollapseTheFrame() {
        val w = world()
        w.pave()
        val bare = RegionBuilder.of(world(), listOf("player"))
        val region = RegionBuilder.of(w, listOf("player"))

        // 铺了地形之后框**不该变小**：脚下那半格土与发起者同处一格，而它不挡水平射线。
        assertEquals(bare.maxX, region.maxX, "铺了地面之后东边反而变近了——多半是拿半格的土当了实心")
        assertEquals(bare.minX, region.minX)
        assertEquals(bare.width, region.width)
    }

    @Test
    fun aFullyFilledMetreCellIsSolidAndAHalfFilledOneIsNot() {
        val w = world()
        w.pave()
        // 发起者那一格：地形 256 有、257 没有 —— **半格**，不算实心。
        assertNull(w.solidTerrainAt(Cell3(10, 10, 128)), "半格土不算实心")
        // 它下面那一格：地形 254、255 都有 —— 整格实心。
        assertEquals("soil", w.solidTerrainAt(Cell3(10, 10, 127)), "整格土算实心")
        // 再往下也是。
        assertEquals("soil", w.solidTerrainAt(Cell3(10, 10, 100)))
    }

    @Test
    fun terrainTightensTheFrameLikeAThingWould() {
        val bare = world()
        bare.pave()

        val walled = world()
        walled.pave()
        // 东边两米处砌一堵实心墙：米格 x=12..13，从地底堆到米格 200。
        walled.terrain.fill(TerrainBox(24, 0, 0, 27, 63, 400), "stone", walled.materials)

        val open = RegionBuilder.of(bare, listOf("player"))
        val closed = RegionBuilder.of(walled, listOf("player"))
        assertTrue(
            closed.maxX < open.maxX,
            "实心墙该让东边收进来：开阔时 maxX=${open.maxX}，堵上之后 ${closed.maxX}",
        )
        assertTrue(
            closed.points.any { it.blockerMaterial == "stone" },
            "该有射线是被**地形**挡下的，而不是被别的什么",
        )
    }

    @Test
    fun terrainBlockerIsRecordedSeparatelyFromAThingBlocker() {
        val w = world()
        w.pave()
        w.terrain.fill(TerrainBox(24, 0, 0, 27, 63, 400), "stone", w.materials)
        val region = RegionBuilder.of(w, listOf("player"))
        val byGround = region.points.filter { it.blockerMaterial != null }
        assertTrue(byGround.isNotEmpty())
        // **两类分开记**：地形不进 `hitIds`，否则它会以"一样东西"的身份进清单——而它在网格里已经
        // 画过一次了（A.15：一样东西写两遍就是两份可以不一致的数据）。
        assertTrue(byGround.all { it.blockerId == null })
        assertTrue(region.hitIds.none { it == "stone" })
    }

    @Test
    fun theGridAsksWhatIsUnderfootNotWhetherTheCellIsSolid() {
        val w = world()
        w.pave()
        // 发起者站的那一格是**半格土**（不实心），但脚下踩的确实是土——网格要画的就是它。
        assertEquals("soil", w.groundMaterialAt(Cell(10, 10), ground))
        assertEquals("soil", w.groundMaterialAt(Cell(13, 13), ground))
    }

    @Test
    fun theGridViewDrawsTheMaterialAndExplainsItOnce() {
        val w = world()
        w.pave()
        val region = RegionBuilder.of(w, listOf("player"))
        val text = RegionView.render(w, region, "player")

        assertTrue(text.contains(","), "网格里该画出材质的字符：\n$text")
        assertTrue(text.contains("【这一片里的材质】"), "该有一份图例：\n$text")
        // **按材质去重**：一片全是土的地面是**一行**图例，不是几十行。
        assertEquals(1, text.split("土").size - 1, "「土」该只在图例里出现一次：\n$text")
        // 东西仍然叠在地形上面：发起者还是 `@`，没有被脚下那格土顶掉。
        assertTrue(text.contains("@"), "发起者该画在地形上面：\n$text")
    }

    @Test
    fun anUnknownMaterialDoesNotPretendToBeFine() {
        val w = world()
        // 直接往地形里塞一个表里没有的 id（模拟坏数据）。
        w.terrain.set(io.github.k1midi0.infinitesimal.world.TerrainCell(20, 20, 254), "lava")
        val text = RegionView.render(w, RegionBuilder.of(w, listOf("player")), "player")
        assertNotNull(text)
        // 那一格不该被当成"没有地形"（那会与"这里是空的"长得一样）。
        assertEquals("lava", w.groundMaterialAt(Cell(10, 10), ground))
        assertTrue(text.contains("不在材质表里"), "不认识的材质要说清是什么坏了：\n$text")
    }
}
