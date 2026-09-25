package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 地形那张表的读与写，以及**两条不同的规模判据**（§14.30）。
 *
 * 这里验的全是**离线也确定**的东西：一张表画出来是什么样、读回去能不能复原、与框对不上时报什么。
 * 「模型画得对不对」不在这里——那是探针的事。
 */
class TerrainGridTest {

    private val depth = WorldDepth()

    /** 材质字符：土是 `,`、石是 `#`、草是 `"`（[MaterialTable.Defaults]）。 */
    private fun worldWithSoil(): World {
        val world = World.empty()
        world.terrain.fill(TerrainBox(0, 0, 0, 3, 3, 3), "soil", world.materials)
        return world
    }

    private fun box(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) =
        TerrainBox(x1, y1, z1, x2, y2, z2)

    @Test
    fun whatItWritesIsWhatItReads() {
        val world = worldWithSoil()
        val box = box(0, 0, 0, 3, 3, 3)

        val text = TerrainGrid.render(world, box)
        assertEquals(4, text.lines().count { it.startsWith("z=") }, text)
        assertEquals(16, text.lines().count { it == ",,,," }, text)

        val parsed = TerrainGrid.parse(world, box, text)
        assertTrue(parsed is TerrainGrid.Parsed.Ok, parsed.toString())
        val cells = (parsed as TerrainGrid.Parsed.Ok).cells
        assertEquals(64, cells.size)
        assertTrue(cells.values.all { it == "soil" })
    }

    @Test
    fun theDotMeansDigItOut() {
        val world = worldWithSoil()
        val parsed = TerrainGrid.parse(world, box(0, 0, 0, 1, 1, 0), "z=0\n,.\n,,")

        assertTrue(parsed is TerrainGrid.Parsed.Ok, parsed.toString())
        val cells = (parsed as TerrainGrid.Parsed.Ok).cells
        assertEquals(4, cells.size)
        assertEquals("soil", cells[TerrainCell(0, 0, 0)])
        assertEquals(null, cells[TerrainCell(1, 0, 0)])
        assertEquals("soil", cells[TerrainCell(0, 1, 0)])
        assertEquals("soil", cells[TerrainCell(1, 1, 0)])
    }

    @Test
    fun aMissingLayerIsRejectedBecauseTheTableMustMatchTheBoxExactly() {
        val world = worldWithSoil()
        // 框是三层，表里只有两层。
        val parsed = TerrainGrid.parse(world, box(0, 0, 0, 1, 1, 2), "z=0\n,,\n,,\nz=1\n,,\n,,")

        assertTrue(parsed is TerrainGrid.Parsed.Bad, parsed.toString())
        val problem = (parsed as TerrainGrid.Parsed.Bad).problems.single()
        assertTrue(problem.contains("严格同尺寸"), problem)
        assertTrue(problem.contains("一层都不能省"), problem)
    }

    @Test
    fun aShortRowIsRejected() {
        val world = worldWithSoil()
        val parsed = TerrainGrid.parse(world, box(0, 0, 0, 2, 0, 0), "z=0\n,,")

        assertTrue(parsed is TerrainGrid.Parsed.Bad, parsed.toString())
        assertTrue(
            (parsed as TerrainGrid.Parsed.Bad).problems.single().contains("应该是 3 个"),
            parsed.toString(),
        )
    }

    @Test
    fun anUnknownCharacterComesBackWithTheWholeVocabulary() {
        val world = worldWithSoil()
        val parsed = TerrainGrid.parse(world, box(0, 0, 0, 1, 0, 0), "z=0\nZ,")

        assertTrue(parsed is TerrainGrid.Parsed.Bad, parsed.toString())
        val problem = (parsed as TerrainGrid.Parsed.Bad).problems.single()
        assertTrue(problem.contains("「Z」"), problem)
        // 词表要整份附上：模型下一轮就是照它改的。
        assertTrue(problem.contains("土"), problem)
        assertTrue(problem.contains("挖空"), problem)
    }

    // ── 两条规模判据 ───────────────────────────────────────────────────────────────

    @Test
    fun theTableBudgetIsCharactersNotCells() {
        val world = worldWithSoil()

        // 21×21×21 = 9261 格 > 8000 的字符预算
        assertTrue(TerrainGrid.tableProblems(world, box(0, 0, 0, 20, 20, 20)).any { it.contains("超过 8000 的预算") })
        // 20×20×20 = 8000 格，正好在预算上
        assertTrue(TerrainGrid.tableProblems(world, box(0, 0, 0, 19, 19, 19)).isEmpty())
    }

    /**
     * **这一条是回归。**第一版把字符预算套在了所有地形手上，于是「一次铺满一整块 chunk」——
     * 生成器最基本的一个动作——被拦了下来，报的是「这一张表太大了」。而它根本不画表。
     */
    @Test
    fun aWholeChunkIsFineForTheBoxHandsBecauseTheyDoNotDrawAnyTable() {
        val world = worldWithSoil()
        val oneChunk = box(96, 0, 0, 111, 15, 256)

        assertEquals(65_792, oneChunk.cellCount)
        assertEquals(256, oneChunk.columns)

        // 框选那三只：过。
        assertTrue(TerrainGrid.boxProblems(world, oneChunk).isEmpty())
        assertTrue(oneChunk.problems().isEmpty())

        // 同一块地要画表：不过，而且理由说的是字符。
        assertTrue(TerrainGrid.tableProblems(world, oneChunk).single().contains("超过 8000 的预算"))
    }

    @Test
    fun aBoxEntirelyOutsideTheWorldIsReportedBeforeItIsApplied() {
        val world = worldWithSoil()
        val problem = TerrainGrid.boxProblems(world, box(0, 0, -900, 0, 0, -900)).single()

        assertTrue(problem.contains("世界范围之外"), problem)
        assertTrue(problem.contains("z ${depth.bottom}..${depth.top}"), problem)
    }
}
