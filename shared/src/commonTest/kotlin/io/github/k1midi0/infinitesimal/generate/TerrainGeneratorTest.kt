package io.github.k1midi0.infinitesimal.generate

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Consequence
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.CHUNK_CELLS
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Preset
import io.github.k1midi0.infinitesimal.world.TerrainBox
import io.github.k1midi0.infinitesimal.world.TerrainCell
import io.github.k1midi0.infinitesimal.world.Voxel
import io.github.k1midi0.infinitesimal.world.VoxelShape
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 生成器（A.16、§14.30）。
 *
 * 假端点验的是**协议与状态**：手调下去世界有没有变、地块有没有被标上、解不开的参数退回到哪里。
 * 「模型长得好不好看」不在这里——那是探针的事。
 */
class TerrainGeneratorTest {

    /** 硬性圈东边紧挨着的那一块：开局那片地覆盖到 chunk 5，所以这一块是空的。 */
    private val east = ChunkKey(6, 0)

    private fun world(): World = World.from(
        WorldSnapshot(
            StartingWorld.tavern(),
            StartingWorld.rules(),
            terrain = StartingWorld.ground,
            zoning = StartingWorld.zoning(),
        ),
    )

    private fun config() = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    private fun generator(script: Scripted) = TerrainGenerator(LlmClient(config(), script.client))

    private fun request() = TerrainGenerator.Request("镇东那片林子往外延一段，地面是草地。", listOf(east))

    private fun fillAt(chunk: ChunkKey, material: String): String {
        val x0 = chunk.x * CHUNK_CELLS
        val y0 = chunk.y * CHUNK_CELLS
        return """{"x1":$x0,"y1":$y0,"z1":0,"x2":${x0 + 15},"y2":${y0 + 15},"z2":256,"material":"$material"}"""
    }

    private fun fill(material: String): String = fillAt(east, material)

    /** 一样东西落在哪一块由它的厘米位置算出来，所以这里直接写位置。 */
    private fun treeJson(id: String, x: Int, y: Int, preset: String = "", background: String = ""): String {
        val sb = StringBuilder()
        sb.append("{\"id\":\"$id\",\"kind\":\"tree\",\"label\":\"橡树\",\"brief\":\"\",")
        sb.append("\"x\":$x,\"y\":$y,\"z\":12800")
        if (preset.isNotEmpty()) sb.append(",\"preset\":\"$preset\"")
        if (background.isNotEmpty()) sb.append(",\"background\":\"$background\"")
        sb.append("}")
        return sb.toString()
    }

    private fun grown(result: TerrainGenerator.Growth): TerrainGenerator.Growth.Grown {
        assertTrue(result is TerrainGenerator.Growth.Grown, result.toString())
        return result as TerrainGenerator.Growth.Grown
    }

    private fun failed(result: TerrainGenerator.Growth): TerrainGenerator.Growth.FailedProtocol {
        assertTrue(result is TerrainGenerator.Growth.FailedProtocol, result.toString())
        return result as TerrainGenerator.Growth.FailedProtocol
    }

    // ── 长出来 ─────────────────────────────────────────────────────────────────────

    @Test
    fun itFillsTheChunkAndThenMarksThatChunkAsHavingTerrain() = runTest {
        val world = world()
        assertFalse(world.terrain.isGenerated(east), "开局那片地不该盖到 chunk (6,0)")

        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.FILL_TERRAIN, fill("grass")),
                stop("这一片长好了。"),
            ),
        )

        val result = grown(generator(script).grow(world, request()))

        assertEquals(1, result.attempts, "铺一次、说一声收工 = 一次尝试")
        assertEquals("grass", world.terrain.materialIdAt(TerrainCell(100, 5, 200)))
        assertTrue(world.terrain.isGenerated(east), "长完之后这块地就该有地形了")
        assertEquals(listOf(east), result.chunks)
    }

    @Test
    fun aRoundWithNoHandsIsNotAGeneration() = runTest {
        val world = world()
        val script = Scripted(listOf(stop("我什么也没干。"), stop("我还是什么也没干。")))

        val result = failed(generator(script).grow(world, request()))

        assertEquals(2, result.attempts)
        assertTrue(result.problems.single().contains("一件都没长出来"), result.problems.toString())
        // 一件都没长 = 世界一动没动，而「这块地已经有地形了」这句也不能落下。
        assertFalse(world.terrain.isGenerated(east))
    }

    // ── 报错回到模型手里 ───────────────────────────────────────────────────────────

    @Test
    fun anUnknownMaterialIsToldBackInsteadOfBeingThrown() = runTest {
        val world = world()
        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.FILL_TERRAIN, fill("ston")),
                turn("c2", GeneratorTools.FILL_TERRAIN, fill("stone")),
                stop(),
            ),
        )

        grown(generator(script).grow(world, request()))

        // 第二次请求的 messages 里带着第一次的退回理由——**这就是「回到模型手里」**。
        assertTrue(script.bodies[1].contains("材质表里没有"), script.bodies[1].takeLast(600))
        assertEquals("stone", world.terrain.materialIdAt(TerrainCell(100, 5, 200)))
    }

    @Test
    fun aBoxBeyondTheWorldIsToldBackToo() = runTest {
        val world = world()
        val script = Scripted(
            listOf(
                turn(
                    "c1",
                    GeneratorTools.FILL_TERRAIN,
                    """{"x1":96,"y1":0,"z1":-900,"x2":111,"y2":15,"z2":-900,"material":"grass"}""",
                ),
                turn("c2", GeneratorTools.FILL_TERRAIN, fill("grass")),
                stop(),
            ),
        )

        grown(generator(script).grow(world, request()))

        assertTrue(script.bodies[1].contains("世界范围之外"), script.bodies[1].takeLast(600))
    }

    /** `look_terrain` **不落地**：看完这一轮还要接着往下走（与导演那条「一件一件排下去」同形）。 */
    @Test
    fun lookingDoesNotEndTheRound() = runTest {
        val world = world()
        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.LOOK_TERRAIN, """{"x1":96,"y1":0,"z1":250,"x2":99,"y2":3,"z2":256}"""),
                turn("c2", GeneratorTools.FILL_TERRAIN, fill("grass")),
                stop(),
            ),
        )
        val generator = generator(script)

        val result = grown(generator.grow(world, request()))

        assertEquals(3, generator.requestCount, "看一眼、铺一次、收工 = 三次请求")
        assertEquals(1, result.consequences.size, "看一眼不该变成一条后果")
    }

    // ── 提示词 ─────────────────────────────────────────────────────────────────────

    @Test
    fun thePromptCarriesTheRequirementTheChunksTheZoningAndTheSeam() {
        val world = world()
        val text = generator(Scripted(listOf(stop()))).preview(world, request())

        assertTrue(text.contains("镇东那片林子往外延一段"), "导演的要求要原样进去")
        assertTrue(text.contains("chunk (6,0)"), "要长哪几块要写清楚")
        assertTrue(text.contains("地形格 x 96..111"), text.take(4000))
        assertTrue(text.contains("的西边接着"), text.substringAfter("接壤处").take(600))
        assertTrue(text.contains("改地皮那五只用地形格，放东西用厘米"), "单位那句话要在末尾再说一遍")
    }

    /**
     * 开局那片地是**拿整块挑出来的圆**（[io.github.k1midi0.infinitesimal.world.HARD_RADIUS_CHUNKS]），
     * 所以紧挨着它的那两块每一面都真的挨着地形。
     *
     * 离得远的地方四面都是空的，那时不该报一句「地面就在第几格」——那是一句假话。
     */
    @Test
    fun aBorderWithNoTerrainAtAllIsNotASeam() {
        val world = world()
        val far = TerrainGenerator.Request("很远的地方。", listOf(ChunkKey(20, 20)))
        val text = generator(Scripted(listOf(stop()))).preview(world, far)

        assertTrue(
            text.contains("接壤处：这一片四面都不挨着已经长好的地"),
            text.substringAfter("接壤处").take(200),
        )
    }

    // ── 找东西：第二道关（A.14、§14.31）─────────────────────────────────────────────
    //
    // 这一批钉的是**这次改动要治的那个病**：库里明明有橡树，模型却每次另写一句要求，于是同一件东西被
    // 造了 11 遍。两只手各管一半——`find_preset` 让它**看得见**库里有什么，`make_object` 的 `preset`
    // 让它**说得中**那一条。

    private fun oak() = Preset(
        name = "oak_tree",
        brief = "一棵三人合抱的老橡树，树皮皲裂，树冠秃了一半",
        shape = VoxelShape(solid = listOf(Voxel(0, 0, 0), Voxel(5, 0, 0))),
        category = "树木",
        tags = listOf("橡树", "乔木", "落叶"),
    )

    private fun tree(id: String, preset: String = "", brief: String = "") =
        """{"id":"$id","kind":"tree","label":"橡树","brief":"$brief","x":4900,"y":130,"z":12800""" +
            (if (preset.isEmpty()) "}" else ""","preset":"$preset"}""")

    @Test
    fun searchingGivesBackWhatToPutInTheNameField() = runTest {
        val world = world()
        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.FIND_PRESET, """{"keywords":"橡树","category":""}"""),
                // 搜完还得真干点什么：**只调查询手不算长出东西**，那一轮会被当成空转（见 grow）。
                turn("c2", GeneratorTools.FILL_TERRAIN, fill("grass")),
                stop("先看看库里有什么，然后铺上了草。"),
            ),
        )
        val generator = generator(script)
        generator.presets.learn(oak())

        grown(generator.grow(world, request()))

        // 三样都要给：名字（填进 preset 的就是它）、属性（它为什么被搜出来）、那句要求（逐字抄的备份）。
        val answer = script.bodies[1]
        assertTrue(answer.contains("oak_tree"), answer.takeLast(1200))
        assertTrue(answer.contains("树木"), answer.takeLast(1200))
        assertTrue(answer.contains("乔木"), answer.takeLast(1200))
    }

    @Test
    fun findingNothingSaysWhatToDoInsteadOfReturningAnEmptyString() = runTest {
        val world = world()
        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.FIND_PRESET, """{"keywords":"潜水艇"}"""),
                turn("c2", GeneratorTools.FILL_TERRAIN, fill("grass")),
                stop(),
            ),
        )

        grown(generator(script).grow(world, request()))

        // 空字符串与"搜到了但清单是空的"长得一样，而这里真正的含义是"库里没有，你自己写一句"。
        assertTrue(script.bodies[1].contains("要现造"), script.bodies[1].takeLast(600))
    }

    @Test
    fun namingAPresetUsesItWithoutSpendingAModelCall() = runTest {
        val world = world()
        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.MAKE_OBJECT, tree("oak_a", preset = "oak_tree")),
                stop(),
            ),
        )
        val generator = generator(script)
        generator.presets.learn(oak())

        val result = grown(generator.grow(world, request()))

        // **两次请求**：放东西、收工。走一次建模的话这里会多出好几个来回，而那正是要省的东西。
        assertEquals(2, generator.requestCount)
        val spawned = result.consequences.filterIsInstance<Consequence.Spawn>().single()
        assertEquals("oak_a", spawned.entity.id)
        assertEquals(2, spawned.entity.shape?.cellCount)
        assertEquals(1, generator.presets.generatedPresets().size, "点名用现成的，不该再往库里长一条")
    }

    @Test
    fun namingSomethingTheLibraryDoesNotHaveIsToldBack() = runTest {
        val world = world()
        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.MAKE_OBJECT, tree("oak_a", preset = "oak_tre")),
                turn("c2", GeneratorTools.FILL_TERRAIN, fill("grass")),
                stop(),
            ),
        )
        val generator = generator(script)
        generator.presets.learn(oak())

        grown(generator.grow(world, request()))

        // 名字抄错了要说出来，并且**告诉它下一步做什么**——只说"没有"会让它再猜一次。
        assertTrue(script.bodies[1].contains("没有叫「oak_tre」"), script.bodies[1].takeLast(600))
    }

    // ── 必需的那几块（§14.33）────────────────────────────────────────────────────────
    //
    // 「最小生成距离」触发的那一次，硬圈里缺的那几块**必须真的长出地形**。判据不是模型说了什么，而是
    // 后果里那些地形框**盖住了哪几块**——账从后果上读，不从回答里读。

    @Test
    fun aRequiredChunkThatWasNotWrittenIsRetriedBeforeAnythingIsCommitted() = runTest {
        val world = world()
        val elsewhere = ChunkKey(7, 0)
        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.FILL_TERRAIN, fillAt(elsewhere, "grass")),
                turn("c2", GeneratorTools.FILL_TERRAIN, fillAt(elsewhere, "grass")),
            ),
        )

        val result = failed(generator(script).grow(world, request().copy(required = listOf(east))))

        assertEquals(2, result.attempts)
        assertTrue(
            result.problems.single().contains("必需的那几块还没写进地形"),
            result.problems.toString(),
        )
        assertTrue(result.problems.single().contains("(6,0)"), result.problems.single())
        // **检查在提交之前**：一次「地铺到了别处」不该把世界动掉一半。
        assertFalse(world.terrain.isGenerated(east))
        assertFalse(world.terrain.isGenerated(elsewhere))
        assertNull(world.terrain.materialIdAt(TerrainCell(120, 5, 200)), "写歪的那一片也不该落库")
    }

    @Test
    fun onlyTheChunksThatWereActuallyWrittenGetMarked() = runTest {
        val world = world()
        val extra = ChunkKey(7, 0)
        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.FILL_TERRAIN, fillAt(east, "grass")),
                stop("只长了必需的那一块。"),
            ),
        )

        val result = grown(
            generator(script).grow(
                world,
                TerrainGenerator.Request("一段要求。", listOf(extra), required = listOf(east)),
            ),
        )

        assertTrue(world.terrain.isGenerated(east), "真写进去的那一块要有地形")
        assertFalse(
            world.terrain.isGenerated(extra),
            "请求过、但一块土都没写的那一块**不能**被标上——拿账本替现实签字之后，没人会再来补它",
        )
        assertEquals(listOf(extra, east), result.chunks, "必需的那几块会被并进这一次的范围里")
    }

    // ── 导演点名要放的东西（§14.33）──────────────────────────────────────────────────

    @Test
    fun aNamedThingThatLandedOutsideItsChunkIsToldBack() = runTest {
        val world = world()
        val request = TerrainGenerator.Request(
            requirement = "村口那片空地。",
            chunks = listOf(east),
            placements = listOf(Placement(east, "一棵老槐树", "树皮上刻着旧痕")),
        )
        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.FILL_TERRAIN, fillAt(east, "grass")),
                // 导演说的是 (6,0)，而这一件落在 chunk (4,0)。
                turn("c2", GeneratorTools.MAKE_OBJECT, treeJson("oak_a", 3300, 130, preset = "oak_tree")),
                turn("c3", GeneratorTools.FILL_TERRAIN, fillAt(east, "grass")),
                turn("c4", GeneratorTools.MAKE_OBJECT, treeJson("oak_a", 3300, 130, preset = "oak_tree")),
            ),
        )
        val generator = generator(script)
        generator.presets.learn(oak())

        val result = failed(generator.grow(world, request))

        assertEquals(2, result.attempts)
        assertTrue(result.problems.single().contains("老槐树"), result.problems.toString())
        assertTrue(result.problems.single().contains("chunk (6,0)"), result.problems.single())
        assertFalse(world.terrain.isGenerated(east), "退回的那两次都不该落库")
    }

    @Test
    fun aNamedThingThatLandedInsideItsChunkIsAcceptedAndCarriesItsBackground() = runTest {
        val world = world()
        val request = TerrainGenerator.Request(
            requirement = "村口那片空地。",
            chunks = listOf(east),
            placements = listOf(Placement(east, "一棵老槐树", "树皮上刻着旧痕")),
        )
        val script = Scripted(
            listOf(
                turn("c1", GeneratorTools.FILL_TERRAIN, fillAt(east, "grass")),
                turn(
                    "c2",
                    GeneratorTools.MAKE_OBJECT,
                    treeJson("oak_a", 4900, 130, preset = "oak_tree", background = "树皮上刻着旧痕"),
                ),
                stop(),
            ),
        )
        val generator = generator(script)
        generator.presets.learn(oak())

        val result = grown(generator.grow(world, request))

        val spawned = result.consequences.filterIsInstance<Consequence.Spawn>()
            .first { it.entity.id == "oak_a" }
        assertEquals("树皮上刻着旧痕", spawned.entity.background, "导演写的那句背景要落到实体上")
    }

    @Test
    fun thePromptMarksTheRequiredChunksAndCarriesTheNamedThings() {
        val world = world()
        val request = TerrainGenerator.Request(
            requirement = "把镇东那片空地补上。",
            chunks = listOf(ChunkKey(7, 0)),
            required = listOf(east),
            placements = listOf(Placement(east, "一棵老槐树", "树皮上刻着旧痕")),
        )

        val text = generator(Scripted(listOf(stop()))).preview(world, request)

        assertTrue(
            text.contains("chunk (6,0)（**必须有地**）"),
            text.substringAfter("你这一次要长的是").take(500),
        )
        assertTrue(text.contains("一块都不能落下"))
        assertTrue(text.contains("一棵老槐树 —— 放进 chunk (6,0)"), text.substringAfter("导演还点名").take(500))
        assertTrue(text.contains("树皮上刻着旧痕"), "导演写的那句背景要跟着过去")
    }
}
