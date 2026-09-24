package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 两样东西之间有多远。**全部离线、全部确定性**——这里没有模型，只有算术，所以它该被钉死。
 *
 * 这一支的存在理由是一次实测（§14.24）：判定要判"够不够得着"，而清单里只有厘米坐标，它于是自己
 * 写式子去算，为此烧掉四千多个思维 token、22 秒。**算得对不该这么贵**，所以这件事搬进代码。
 */
class DistanceTest {

    /** 一个东西，参数直接是厘米（这个世界存的就是厘米）。 */
    private fun at(id: String, x: Int, y: Int, z: Int) = Entity(
        id = id, kind = "东西", label = id, x = x, y = y, z = z,
    )

    /**
     * **开局酒馆里那次真实的争议。**
     *
     * 酒杯在 (1200, 400, 80)、玩家在 (1200, 300, 0)。三维直线 128 厘米，而交互范围卡说"一格以内"——
     * 按同一个世界、同一句话，两轮判成"成立"、两轮判成"够不着"。三个分量各是什么、哪一个才是规矩
     * 里那个数，正是这一支要替它算清的。
     */
    @Test
    fun theTavernsCupIsOneCellAwayAndAboutOneAndAQuarterMetresInAStraightLine() {
        val player = at("player", 1200, 300, 0)
        val cup = at("wine_cup", 1200, 400, 80)

        val d = distanceBetween(player, cup)

        assertEquals(1, d.cells, "格距取横向与纵向里大的那个：纵向差正好一格")
        assertEquals(0, d.dxCm)
        assertEquals(-100, d.dyCm)
        assertEquals(-80, d.dzCm, "按 a - b 算，杯子高所以是负的")
        assertEquals(128, d.straightCm, "100 与 80 是 3-4-5 的变体：斜边 128")
        assertEquals(1.28, d.straightMetres)
    }

    /**
     * **"为什么不能只给一个标量"就是这一条。**
     *
     * 同一个格子上、上下相差 80 厘米的两样东西，格距是 **0**。把三个数合成一个"距离"就等于替规矩
     * 决定了"一格以内"到底按格算还是按直线算——而规矩是内容，是会改的。
     */
    @Test
    fun heightDoesNotCountTowardsTheGridDistance() {
        val onFloor = at("rag_on_floor", 1200, 300, 0)
        val onTable = at("cup", 1200, 300, 80)

        val d = distanceBetween(onFloor, onTable)

        assertEquals(0, d.cells)
        assertEquals(80, d.straightCm)
        assertEquals(-80, d.dzCm)
        assertTrue(!d.level, "高度差不是 0")
    }

    @Test
    fun theGridDistanceIsChebyshevSoADiagonalNeighbourIsStillOneCell() {
        val here = at("a", 0, 0, 0)
        val diagonal = at("b", 199, 199, 0)

        val d = distanceBetween(here, diagonal)

        assertEquals(1, d.cells, "斜着隔一格就是一格，不是 1.41 格——地图格子是方的")
    }

    @Test
    fun twoThingsInTheSameCellAreZeroCellsApartButNotZeroCentimetresApart() {
        val a = at("a", 1200, 300, 0)
        val b = at("b", 1299, 399, 0)

        val d = distanceBetween(a, b)

        assertEquals(0, d.cells)
        assertTrue(d.straightCm > 0, "同一格里也可能离着一米：格是粗的，直线是细的")
        assertTrue(d.level)
    }

    @Test
    fun negativeCentimetresFallIntoTheCellBelowSoDistancesStayHonestUnderground() {
        // 地面以下那一格（-50 厘米）与地面那一格（0 厘米）之间，格距必须是 1，不是 0。
        val underground = at("cellar", -50, 0, 0)
        val ground = at("hall", 0, 0, 0)

        assertEquals(1, distanceBetween(underground, ground).cells)
    }

    /**
     * 方向是有意义的：`distanceBetween(a, b)` 的 `dzCm` 为正表示 **b 在 a 上面**。
     *
     * 钉住它是因为那句话要写给模型看（"酒杯比你高"），而反了就是一句错话——错话在这里比沉默更糟。
     */
    @Test
    fun theSignSaysWhetherTheSecondOneIsHigher() {
        val low = at("floor", 0, 0, 0)
        val high = at("shelf", 0, 0, 150)

        assertEquals(-150, distanceBetween(low, high).dzCm, "b 更高，a-b 是负的")
        assertEquals(150, distanceBetween(high, low).dzCm, "换个方向就差一个负号")
    }

    /** 世界里的东西照样能算——这一支不是只为几个裸实体服务的。 */
    @Test
    fun itWorksOnAWholeWorldsEntities() {
        val world = World.from(
            WorldSnapshot(
                listOf(
                    at("player", 1200, 300, 0),
                    at("wine_cup", 1200, 400, 80),
                ),
            ),
        )

        val d = distanceBetween(world.byId("player")!!, world.byId("wine_cup")!!)

        assertEquals(1, d.cells)
        assertEquals(128, d.straightCm)
    }
}
