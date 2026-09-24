package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 地形层（附录 A.15）。
 *
 * 全部离线、全部确定性。这里钉的是三只工具的语义（填充只填空的、挖去跳过不可破坏的、替换认那一个
 * 材质）、竖向范围的裁剪、以及 chunk 的记法。
 */
class TerrainTest {

    private val materials = MaterialTable()

    private fun terrain(): Terrain = Terrain.empty()

    private fun box(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) =
        TerrainBox(x1, y1, z1, x2, y2, z2)

    // ── 换算 ──────────────────────────────────────────────────────────────────────

    @Test
    fun centimetresTurnIntoFiftyCentimetreCellsByFlooring() {
        assertEquals(0, terrainCellOf(0))
        assertEquals(0, terrainCellOf(49))
        assertEquals(1, terrainCellOf(50))
        assertEquals(1, terrainCellOf(99))
        assertEquals(2, terrainCellOf(100))
        // **负数往下走一格**：-1 厘米在地面以下那一格里，不是"第 0 格"。
        assertEquals(-1, terrainCellOf(-1))
        assertEquals(-1, terrainCellOf(-50))
        assertEquals(-2, terrainCellOf(-51))
    }

    @Test
    fun floorDivisionKeepsTheSignRight() {
        assertEquals(0, floorDiv(15, 16))
        assertEquals(1, floorDiv(16, 16))
        assertEquals(-1, floorDiv(-1, 16))
        assertEquals(-2, floorDiv(-17, 16))
        assertEquals(-16, floorDiv(-256, 16))
    }

    @Test
    fun theDefaultWorldIsFiveHundredAndTwelveMetresTall() {
        val depth = WorldDepth()
        assertEquals(0, depth.bottom)
        assertEquals(256, depth.surface)
        assertEquals(1024, depth.top)
        // 1024 个 50 厘米的格 = 512 米；地面在 256 格 = 128 米。
        assertEquals(512, depth.top * TERRAIN_CM / 100)
        assertEquals(128, depth.surface * TERRAIN_CM / 100)
        assertEquals(emptyList(), depth.problems())
    }

    // ── 三只工具 ───────────────────────────────────────────────────────────────────

    @Test
    fun fillOnlyWritesIntoEmptyCells() {
        val t = terrain()
        t.set(TerrainCell(5, 5, 256), "wood")

        val n = t.fill(box(5, 5, 256, 7, 5, 256), "road", materials)

        assertEquals(2, n) // (6,5) 与 (7,5)
        assertEquals("wood", t.materialIdAt(TerrainCell(5, 5, 256)))
        assertEquals("road", t.materialIdAt(TerrainCell(6, 5, 256)))
        assertEquals("road", t.materialIdAt(TerrainCell(7, 5, 256)))
    }

    @Test
    fun fillWithAnUnknownMaterialChangesNothing() {
        val t = terrain()
        assertEquals(0, t.fill(box(0, 0, 256, 3, 3, 256), "unobtainium", materials))
        assertEquals(0, t.cellCount)
    }

    @Test
    fun digClearsTheBox() {
        val t = terrain()
        t.fill(box(0, 0, 256, 3, 3, 256), "soil", materials)
        assertEquals(16, t.cellCount)

        val n = t.dig(box(1, 1, 256, 2, 2, 256), materials)

        assertEquals(4, n)
        assertEquals(12, t.cellCount)
        assertNull(t.materialIdAt(TerrainCell(1, 1, 256)))
        assertEquals("soil", t.materialIdAt(TerrainCell(0, 0, 256)))
    }

    /** **世界底那层岩层靠这条拦住**（附录 A.15）：一次挖掘挖不穿它。 */
    @Test
    fun digSkipsUnbreakableCells() {
        val t = terrain()
        t.fill(box(0, 0, 0, 3, 3, 0), "bedrock", materials)
        t.set(TerrainCell(0, 0, 1), "soil")

        val n = t.dig(box(0, 0, 0, 3, 3, 1), materials)

        assertEquals(1, n) // 只有上面那格土
        assertEquals(16, t.cellCount)
        assertEquals("bedrock", t.materialIdAt(TerrainCell(0, 0, 0)))
        assertNull(t.materialIdAt(TerrainCell(0, 0, 1)))
    }

    @Test
    fun digWithAnUnbreakableSourceIsSkippedToo() {
        val t = terrain()
        t.fill(box(0, 0, 0, 2, 2, 0), "bedrock", materials)

        assertEquals(0, t.replace(box(0, 0, 0, 2, 2, 0), "bedrock", "soil", materials))
        assertEquals("bedrock", t.materialIdAt(TerrainCell(0, 0, 0)))
    }

    @Test
    fun replaceOnlyTouchesTheNamedMaterial() {
        val t = terrain()
        t.fill(box(0, 0, 256, 3, 0, 256), "grass", materials)
        t.set(TerrainCell(2, 0, 256), "road")

        val n = t.replace(box(0, 0, 256, 3, 0, 256), "grass", "soil", materials)

        assertEquals(3, n)
        assertEquals("soil", t.materialIdAt(TerrainCell(0, 0, 256)))
        assertEquals("road", t.materialIdAt(TerrainCell(2, 0, 256)))
    }

    /** 框写反了不算错——`minOf`/`maxOf` 会把它摆正，因为模型只有一套坐标要学。 */
    @Test
    fun aBoxWrittenBackwardsIsStillTheSameBox() {
        val t = terrain()
        val n = t.fill(box(7, 7, 258, 5, 5, 256), "stone", materials)
        assertEquals(27, n)
        assertEquals("stone", t.materialIdAt(TerrainCell(5, 5, 256)))
        assertEquals("stone", t.materialIdAt(TerrainCell(7, 7, 258)))
    }

    // ── 竖向范围 ───────────────────────────────────────────────────────────────────

    /** 范围外的格**不存在**，不该被写出来——否则地形会有一块谁也没打算要的东西。 */
    @Test
    fun nothingOutsideTheVerticalRangeIsWritten() {
        val t = Terrain.empty(WorldDepth(bottom = 0, surface = 2, top = 4))

        // 框是 2×2×3 = 12 个格，全部落在 1..3 里，所以一个都不该被裁掉。
        assertEquals(12, t.fill(box(0, 0, 1, 1, 1, 3), "soil", materials))
        assertEquals(12, t.cellCount)

        // 同一个框往下探两层：只有 z=0 那一层在范围里，下面两层**一格都不写**。
        assertEquals(4, t.fill(box(0, 0, -2, 1, 1, 0), "stone", materials))
        assertEquals("stone", t.materialIdAt(TerrainCell(0, 0, 0)))
        assertNull(t.materialIdAt(TerrainCell(0, 0, -1)))
        assertNull(t.materialIdAt(TerrainCell(0, 0, -2)))

        // 往上探：z=3 已经有土（填充不覆盖），只有 z=4 是空的。
        assertEquals(4, t.fill(box(0, 0, 3, 1, 1, 6), "stone", materials))
        assertEquals("soil", t.materialIdAt(TerrainCell(0, 0, 3)))
        assertEquals("stone", t.materialIdAt(TerrainCell(0, 0, 4)))
        assertNull(t.materialIdAt(TerrainCell(0, 0, 5)))
        // 12（z 1..3）+ 4（z=0）+ 4（z=4）
        assertEquals(20, t.cellCount)
    }

    @Test
    fun aBoxKnowsHowManyCellsItHolds() {
        assertEquals(27, box(0, 0, 0, 2, 2, 2).cellCount)
        assertEquals(1, box(5, 5, 5, 5, 5, 5).cellCount)
    }

    @Test
    fun anOversizedBoxIsReportedBeforeItIsApplied() {
        val problems = box(0, 0, 256, 100, 100, 256).problems(TerrainLimits(maxCells = 100))
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("超过上限 100"))
    }

    // ── chunk ─────────────────────────────────────────────────────────────────────

    @Test
    fun chunksAreSixteenCellsAcrossAndRunDownTheWholeColumn() {
        assertEquals(ChunkKey(0, 0), chunkOf(TerrainCell(0, 0, 0)))
        assertEquals(ChunkKey(0, 0), chunkOf(TerrainCell(15, 15, 900)))
        assertEquals(ChunkKey(1, 1), chunkOf(TerrainCell(16, 16, 900)))
        assertEquals(ChunkKey(-1, -1), chunkOf(TerrainCell(-1, -1, 0)))
        // 竖向不分 chunk：同一列的 0 层与 1000 层是同一个 chunk。
        assertEquals(chunkOf(TerrainCell(3, 4, 0)), chunkOf(TerrainCell(3, 4, 1000)))
    }

    /** "探索过"就是"这一块生成过"（附录 A.16），而它是这个世界里那条硬边界。 */
    @Test
    fun generatedChunksAreRemembered() {
        val t = terrain()
        assertFalse(t.isGenerated(ChunkKey(0, 0)))

        t.markGenerated(ChunkKey(0, 0))
        t.markGenerated(ChunkKey(0, 0))

        assertTrue(t.isGenerated(ChunkKey(0, 0)))
        assertFalse(t.isGenerated(ChunkKey(1, 0)))
        assertEquals(setOf(ChunkKey(0, 0)), t.generatedChunks())
    }

    // ── 快照 ─────────────────────────────────────────────────────────────────────

    @Test
    fun aSnapshotCarriesTheBlocksTheChunksAndTheDepth() {
        val t = Terrain.empty(WorldDepth(bottom = -4, surface = 0, top = 8))
        t.fill(box(1, 2, 0, 1, 2, 0), "water", materials)
        t.markGenerated(ChunkKey(-1, 3))

        val back = Terrain.from(t.snapshot())

        assertEquals("water", back.materialIdAt(TerrainCell(1, 2, 0)))
        assertEquals(1, back.cellCount)
        assertTrue(back.isGenerated(ChunkKey(-1, 3)))
        assertEquals(-4, back.depth.bottom)
        assertEquals(8, back.depth.top)
    }

    @Test
    fun aWorldCarriesItsTerrainAndItsMaterials() {
        val world = World.empty()
        world.terrain.fill(box(0, 0, 256, 1, 1, 256), "grass", world.materials)
        world.terrain.markGenerated(ChunkKey(0, 0))

        val back = World.from(world.snapshot())

        assertEquals(4, back.terrain.cellCount)
        assertEquals("草", back.materials.name(back.terrain.materialIdAt(TerrainCell(0, 0, 256))!!))
        assertTrue(back.terrain.isGenerated(ChunkKey(0, 0)))
    }

    // ── 按段存 ─────────────────────────────────────────────────────────────────────

    /**
     * **这一条是整次重构的理由。**
     *
     * 你定的是硬性圈里从 0 格到地面都要铺满。半径 32 米的硬性圈约 12,868 个地形格，乘 257 层是
     * **330 万个格**——按格存是一张开局就爆掉的 map。而地下是分层的：一整列同一种材质就是**一段**。
     *
     * 这个 32×32 的方块是那笔算术的缩影：**26 万个格、1024 段**。
     */
    @Test
    fun aFullyFilledNeighbourhoodCostsRunsNotHundredsOfThousandsOfCells() {
        val t = Terrain.empty()

        assertEquals(32 * 32 * 257, t.fill(box(0, 0, 0, 31, 31, 256), "soil", materials))

        assertEquals(263_168, t.cellCount)
        assertEquals(1_024, t.runCount)
    }

    @Test
    fun aSolidColumnIsOneRunNotTwoHundredAndFiftySeven() {
        val t = Terrain.empty()
        t.fill(box(0, 0, 0, 0, 0, 256), "soil", materials)

        assertEquals(257, t.cellCount)
        assertEquals(1, t.runCount)
        assertEquals(listOf(TerrainRun("soil", 0, 256)), t.runsAt(0, 0))
    }

    @Test
    fun diggingTheMiddleSplitsTheColumnInTwo() {
        val t = Terrain.empty()
        t.fill(box(3, 3, 0, 3, 3, 10), "stone", materials)

        assertEquals(3, t.dig(box(3, 3, 4, 3, 3, 6), materials))

        assertEquals(listOf(TerrainRun("stone", 0, 3), TerrainRun("stone", 7, 10)), t.runsAt(3, 3))
        assertEquals(8, t.cellCount)
        assertEquals(2, t.runCount)
    }

    /** 挨着的同材质段**必须合并**，否则挖几下就能把一列堆成几百段。 */
    @Test
    fun adjacentRunsOfTheSameMaterialAreMerged() {
        val t = Terrain.empty()
        t.fill(box(1, 1, 0, 1, 1, 4), "soil", materials)
        t.fill(box(1, 1, 5, 1, 1, 9), "soil", materials)

        assertEquals(listOf(TerrainRun("soil", 0, 9)), t.runsAt(1, 1))
        assertEquals(1, t.runCount)
    }

    @Test
    fun runsOfDifferentMaterialsStayApart() {
        val t = Terrain.empty()
        t.fill(box(1, 1, 0, 1, 1, 4), "soil", materials)
        t.fill(box(1, 1, 5, 1, 1, 9), "stone", materials)

        assertEquals(listOf(TerrainRun("soil", 0, 4), TerrainRun("stone", 5, 9)), t.runsAt(1, 1))
        assertEquals(2, t.runCount)
    }

    /** 替换是在一段**中间**切一刀，两头还得留着。 */
    @Test
    fun replacingCutsAHoleInsideARun() {
        val t = Terrain.empty()
        t.fill(box(0, 0, 0, 0, 0, 9), "soil", materials)

        assertEquals(3, t.replace(box(0, 0, 3, 0, 0, 5), "soil", "water", materials))

        assertEquals(
            listOf(TerrainRun("soil", 0, 2), TerrainRun("water", 3, 5), TerrainRun("soil", 6, 9)),
            t.runsAt(0, 0),
        )
        assertEquals(3, t.runCount)
    }

    /** **挖空的列当场从表里删掉。** 留着它是把稀疏存的收益又还回去。 */
    @Test
    fun aColumnThatWasDugOutCompletelyDisappears() {
        val t = Terrain.empty()
        t.fill(box(0, 0, 0, 3, 3, 5), "soil", materials)
        assertEquals(16, t.columns().size)

        t.dig(box(0, 0, 0, 3, 3, 5), materials)

        assertEquals(0, t.columns().size)
        assertEquals(0, t.runCount)
        assertEquals(0, t.cellCount)
    }

    /** 一片空地 `dig` 一遍**不该留下一列空段**——那正是稀疏存要避免的东西。 */
    @Test
    fun diggingEmptyGroundLeavesNothingBehind() {
        val t = Terrain.empty()

        assertEquals(0, t.dig(box(0, 0, 0, 9, 9, 256), materials))

        assertEquals(0, t.columns().size)
        assertEquals(0, t.cellCount)
        assertNull(t.materialIdAt(TerrainCell(0, 0, 256)))
    }

    @Test
    fun aSnapshotCarriesTheRunsNotTheCells() {
        val t = Terrain.empty()
        t.fill(box(2, 3, 0, 2, 3, 255), "soil", materials)
        t.set(TerrainCell(2, 3, 0), "bedrock")

        val snap = t.snapshot()
        assertEquals(1, snap.columns.size)
        assertEquals(2, snap.columns.single().runs.size)

        val back = Terrain.from(snap)
        assertEquals(256, back.cellCount)
        assertEquals(2, back.runCount)
        assertEquals("bedrock", back.materialIdAt(TerrainCell(2, 3, 0)))
        assertEquals("soil", back.materialIdAt(TerrainCell(2, 3, 255)))
    }
}
