package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 区划层（附录 A.16）。
 *
 * 两条要钉的：**留白是合法的**（不在表里就是没标注），以及**"已探索"的判据是 chunk 生成过没有**
 * ——而一个 10 米的区划格横跨 20 个地形格，通常是四个 chunk，所以它比看上去更容易被判成"已探索"。
 */
class ZoningTest {

    private fun terrain(): Terrain = Terrain.empty()

    @Test
    fun aTileCarriesALandformAndANote() {
        val z = Zoning.empty()
        z.set(ZoneCell(2, 3), ZoneTile(Landform.FOREST, "老林子，树很密"))

        val tile = z.tileAt(ZoneCell(2, 3))!!
        assertEquals(Landform.FOREST, tile.landform)
        assertEquals("老林子，树很密", tile.note)
        assertEquals("林", tile.landform.label)
        assertEquals(1, z.cellCount)
    }

    /** **留白**：没标注的格不在表里，而抹掉一格就是把它从表里去掉。 */
    @Test
    fun aCellThatWasNeverPaintedIsBlank() {
        val z = Zoning.empty()
        assertNull(z.tileAt(ZoneCell(0, 0)))

        z.set(ZoneCell(0, 0), ZoneTile(Landform.ROAD))
        z.set(ZoneCell(0, 0), null)

        assertNull(z.tileAt(ZoneCell(0, 0)))
        assertEquals(0, z.cellCount)
    }

    /** 一个 10 米的区划格 = 20×20 个地形格；从 0 起的那一格横跨 chunk (0,0) 到 (1,1)。 */
    @Test
    fun oneZoneCellSpansFourChunks() {
        val t = terrain()
        val z = Zoning.empty()

        assertFalse(z.isExplored(ZoneCell(0, 0), t))

        t.markGenerated(ChunkKey(0, 0))
        t.markGenerated(ChunkKey(1, 0))
        t.markGenerated(ChunkKey(0, 1))
        assertFalse(z.isExplored(ZoneCell(0, 0), t), "还差一块就不算探索过")

        t.markGenerated(ChunkKey(1, 1))
        assertTrue(z.isExplored(ZoneCell(0, 0), t))
    }

    @Test
    fun exploringOneZoneDoesNotExploreTheNext() {
        val t = terrain()
        val z = Zoning.empty()
        for (x in 0..1) for (y in 0..1) t.markGenerated(ChunkKey(x, y))

        assertTrue(z.isExplored(ZoneCell(0, 0), t))
        assertFalse(z.isExplored(ZoneCell(1, 0), t))
    }

    /** 负坐标同样要往下取整——否则 (0,0) 与 (-1,-1) 会共用同一批 chunk。 */
    @Test
    fun zonesLeftAndBelowTheOriginUseTheirOwnChunks() {
        val t = terrain()
        val z = Zoning.empty()
        for (x in 0..1) for (y in 0..1) t.markGenerated(ChunkKey(x, y))

        assertFalse(z.isExplored(ZoneCell(-1, -1), t))

        // zone(-1,-1) 的地形格是 -20..-1，chunk 是 -2..-1。
        for (x in -2..-1) for (y in -2..-1) t.markGenerated(ChunkKey(x, y))
        assertTrue(z.isExplored(ZoneCell(-1, -1), t))
    }

    @Test
    fun aSnapshotCarriesEveryTileAndComesBackSorted() {
        val z = Zoning.empty()
        z.set(ZoneCell(5, 1), ZoneTile(Landform.WATER))
        z.set(ZoneCell(-2, 7), ZoneTile(Landform.MOUNTAIN, "北边的山"))
        z.set(ZoneCell(0, 0), ZoneTile(Landform.CITY, "镇上"))

        val back = Zoning.from(z.snapshot())

        assertEquals(3, back.cellCount)
        assertEquals(Landform.CITY, back.tileAt(ZoneCell(0, 0))!!.landform)
        assertEquals("北边的山", back.tileAt(ZoneCell(-2, 7))!!.note)
        assertEquals(
            listOf(ZoneCell(-2, 7), ZoneCell(0, 0), ZoneCell(5, 1)),
            back.all().map { it.cell },
        )
    }

    @Test
    fun aWorldCarriesItsZoning() {
        val world = World.empty()
        world.zoning.set(ZoneCell(0, 0), ZoneTile(Landform.TOWN, "酒馆就在这儿"))

        val back = World.from(world.snapshot())

        assertEquals(Landform.TOWN, back.zoning.tileAt(ZoneCell(0, 0))!!.landform)
    }

    /** 区划格与地形格的换算：一格区划 = 20 格地形。 */
    @Test
    fun aZoneCellIsTwentyTerrainCellsAcross() {
        assertEquals(20, ZONE_TERRAIN_CELLS)
        assertEquals(0, zoneCellOf(0))
        assertEquals(0, zoneCellOf(999))
        assertEquals(1, zoneCellOf(1000))
        assertEquals(-1, zoneCellOf(-1))
    }
}
