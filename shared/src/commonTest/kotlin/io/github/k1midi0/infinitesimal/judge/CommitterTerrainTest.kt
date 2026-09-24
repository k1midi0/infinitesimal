package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.TerrainBox
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 第八类后果——**改地形**（附录 A.15）——在提交层上的行为。
 *
 * 这一层值得单独测，因为它与那七种的处境不同：那七种作用于"一样东西"，校验的是 id 在不在；地形
 * 作用于一片格子，而**它的坑全是静默的**——挖到挖不动的材质会被跳过、框落在世界之外会一格不动、
 * 材质名拼错会什么都不发生。三种都是"代码没报错、世界没变"，而模型下一轮只会原样再提一次。
 *
 * 所以这一份测的不是"功能对不对"，是**那三种静默失败有没有变成一句说得出口的话**。
 */
class CommitterTerrainTest {

    /**
     * 一列测试地形：`z=0` 是那层挖不动的岩层，`z=1..10` 是土。旁边一样东西用来验原子性。
     *
     * 用 [World.from] 而不是 `World.empty()`，是因为世界级的规则书那些字段也要一起成立——测试里
     * 少一个字段的症状是别处的断言失败，找起来很远。
     */
    private fun world(): World {
        val w = World.from(WorldSnapshot(listOf(Entity("cup", "器具", "酒杯", 0, 0, 0))))
        w.terrain.fill(TerrainBox(0, 0, 0, 0, 0, 0), "bedrock", w.materials)
        w.terrain.fill(TerrainBox(0, 0, 1, 0, 0, 10), "soil", w.materials)
        return w
    }

    private fun World.at(x: Int, y: Int, z: Int): String? =
        terrain.materialIdAt(io.github.k1midi0.infinitesimal.world.TerrainCell(x, y, z))

    @Test
    fun diggingClearsTheCells() {
        val w = world()
        val result = Committer.apply(w, listOf(Consequence.DigTerrain(TerrainBox(0, 0, 1, 0, 0, 10))))
        assertIs<Committer.Applied>(result)
        assertNull(w.at(0, 0, 5), "5 那一格应该被挖空了")
        assertEquals("bedrock", w.at(0, 0, 0), "岩层不在框里，不该被动")
    }

    @Test
    fun fillingOnlyTouchesEmptyCells() {
        val w = world()
        val result = Committer.apply(w, listOf(Consequence.FillTerrain(TerrainBox(0, 0, 1, 0, 0, 12), "stone")))
        assertIs<Committer.Applied>(result)
        assertEquals("soil", w.at(0, 0, 5), "已经有土的地方不该被覆盖——fill 是「铺路」不是「覆盖」")
        assertEquals("stone", w.at(0, 0, 12), "空的那几格应该被铺上")
    }

    @Test
    fun replacingSwapsOnlyTheNamedMaterial() {
        val w = world()
        val result = Committer.apply(
            w,
            listOf(Consequence.ReplaceTerrain(TerrainBox(0, 0, 1, 0, 0, 10), from = "soil", to = "stone")),
        )
        assertIs<Committer.Applied>(result)
        assertEquals("stone", w.at(0, 0, 5))
        assertEquals("bedrock", w.at(0, 0, 0), "换的是 soil，岩层不是 soil，一格不该动")
    }

    @Test
    fun diggingIntoTheUnbreakableLayerIsRejectedWithTheReason() {
        val w = world()
        // 框从 0 起 —— 正好把那层岩层圈进来。这正是"挖一个坑"最容易写成的样子。
        val result = Committer.apply(w, listOf(Consequence.DigTerrain(TerrainBox(0, 0, 0, 0, 0, 10))))
        val rejected = assertIs<Committer.Rejected>(result)
        assertTrue(
            rejected.problems.any { it.contains("岩层") },
            "理由要点名是哪一层挖不动，模型才改得动框；实得 ${rejected.problems}",
        )
        assertEquals("soil", w.at(0, 0, 5), "整批被驳回了，一格都不该动")
    }

    @Test
    fun replacingRejectsRatherThanSilentlySkippingTheUnbreakableLayer() {
        val w = world()
        // 把岩层换成土。Terrain 会静默跳过它，于是"什么都没发生"——所以要在这里拦下。
        val result = Committer.apply(
            w,
            listOf(Consequence.ReplaceTerrain(TerrainBox(0, 0, 0, 0, 0, 0), from = "bedrock", to = "soil")),
        )
        assertTrue(assertIs<Committer.Rejected>(result).problems.any { it.contains("岩层") })
    }

    @Test
    fun anUnknownMaterialIsRejectedAndTheTableIsListed() {
        val w = world()
        val result = Committer.apply(w, listOf(Consequence.FillTerrain(TerrainBox(0, 0, 20, 0, 0, 20), "lava")))
        val problems = assertIs<Committer.Rejected>(result).problems
        assertTrue(problems.any { it.contains("lava") })
        // **把表里有什么一起说出来**：模型猜不出闭合词表里有哪些名字，报"没有 lava"它就只会再猜一个。
        assertTrue(problems.any { it.contains("soil") }, "理由里要带上材质表，实得 $problems")
    }

    @Test
    fun anUnknownSourceMaterialIsAlsoRejected() {
        val w = world()
        val result = Committer.apply(
            w,
            listOf(Consequence.ReplaceTerrain(TerrainBox(0, 0, 1, 0, 0, 10), from = "sand", to = "stone")),
        )
        assertTrue(assertIs<Committer.Rejected>(result).problems.any { it.contains("sand") })
    }

    @Test
    fun aBoxOutsideTheWorldIsRejectedInsteadOfDoingNothing() {
        val w = world()
        // 世界是 z 0..1024。这个框整个在下面——它会过规模校验、过材质校验，然后一格不动。
        val result = Committer.apply(w, listOf(Consequence.DigTerrain(TerrainBox(0, 0, -900, 2, 2, -800))))
        val problems = assertIs<Committer.Rejected>(result).problems
        assertTrue(problems.any { it.contains("世界范围") }, "实得 $problems")
    }

    @Test
    fun anOversizedBoxIsRejectedBeforeAnythingIsScanned() {
        val w = world()
        val result = Committer.apply(w, listOf(Consequence.DigTerrain(TerrainBox(0, 0, 1, 300, 300, 10))))
        assertTrue(assertIs<Committer.Rejected>(result).problems.any { it.contains("超过上限") })
    }

    @Test
    fun terrainAndEntitiesCommitTogetherOrNotAtAll() {
        val w = world()
        // 一条合法的实体后果 + 一条坏掉的地形后果：**两条都不该落地**。这正是"同批原子提交"那句话
        // （A.15）唯一能被证伪的地方。
        val result = Committer.apply(
            w,
            listOf(
                Consequence.Move("cup", 5, 5, 5),
                Consequence.FillTerrain(TerrainBox(0, 0, 20, 0, 0, 20), "lava"),
            ),
        )
        assertIs<Committer.Rejected>(result)
        assertEquals(0, w.byId("cup")?.x, "地形那条坏了，实体那条也不该生效")
    }

    @Test
    fun aValidBatchAppliesBothKinds() {
        val w = world()
        val result = Committer.apply(
            w,
            listOf(
                Consequence.Move("cup", 5, 5, 5),
                Consequence.DigTerrain(TerrainBox(0, 0, 1, 0, 0, 10)),
            ),
        )
        assertIs<Committer.Applied>(result)
        assertEquals(5, w.byId("cup")?.x)
        assertNull(w.at(0, 0, 5))
    }

    @Test
    fun terrainConsequencesAreNotEntityConsequences() {
        // 这是一条**类型上**的断言，测试它是因为它替代了一段本该写在这里的注释：地形没有 `targetId`，
        // 所以 `GameSession` 那边那句"描述作废"的集合只能收实体那一族。要是哪天有人给地形补上一个
        // `targetId` 来"让它也走同一条路"，这条会红。
        val terrain: Consequence = Consequence.DigTerrain(TerrainBox(0, 0, 1, 0, 0, 1))
        assertFalse(terrain is Consequence.EntityConsequence)
        assertTrue(terrain is Consequence.TerrainConsequence)
    }
}
