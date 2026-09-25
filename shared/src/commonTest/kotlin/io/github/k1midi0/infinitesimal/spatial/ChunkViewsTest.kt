package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.chunkOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 角色那两份视野（§14.36）。
 *
 * 这一对视图存在的理由是一处实测：**粗的那份原来画的是"有没有地形"，而在开局世界里那是一张全是
 * 同一个字符的图**——矮人 9×9 的视野里 81 格全是 `#`，图上没有一个字是它还不知道的。
 *
 * 所以这里钉的正是"信息量"这件事，不是排版：
 *
 * - 远的那张要读得出**这一带是什么地方**（地貌），而不是"这里存在"；
 * - 近的那张要读得出**手边有什么**（具体哪样东西、带什么属性），而不是一个点。
 */
class ChunkViewsTest {

    private fun world(): World = World.from(StartingWorld.snapshot())

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun dwarfChunk(world: World) = world.chunkOfEntity("dwarf")!!

    @Test
    fun theFarMapTellsYouWhatKindOfPlaceThisIs() {
        val world = world()
        val text = ChunkView.renderLandform(world, origin(world), dwarfChunk(world), radius = 4)

        // 开局那份区划（`StartingWorld.zoning()`）里写着镇中心、往东是石板路、再东是林子。
        assertTrue('T' in text, "镇中心那一块该画成 T：\n$text")
        assertTrue('R' in text, "往东的石板路该画成 R：\n$text")
        assertTrue('F' in text, "再往东的林子该画成 F：\n$text")
        assertTrue("这一片里出现的" in text, "网格里是 ASCII，名字得在图例里说清楚")
        assertTrue("镇" in text && "路" in text && "林" in text)
    }

    @Test
    fun theFarMapStillSaysWhenTheGroundIsMissing() {
        val world = world()
        // **缺地那两格没有被地貌盖掉。** 它们是"这里还不存在"，比"这里是什么地方"更硬的信息
        // ——生成器那一路靠的正是它。
        val bare = World.from(
            StartingWorld.snapshot().let { it.copy(terrain = it.terrain.copy(generated = emptyList())) },
        )
        val text = ChunkView.renderLandform(bare, origin(bare), dwarfChunk(bare), radius = 4)

        assertTrue('!' in text, "硬圈里那些还没长出来的块要还是 `!`：\n$text")
        // **只看网格那几行。** 图例那一行永远把七个字形都列一遍（C 城、T 镇、R 路……），拿整段文字
        // 去找 'T' 必然命中——那是量错了对象。
        val grid = text.lines().filter { it.startsWith("y=") }.joinToString("\n")
        for (glyph in listOf('T', 'R', 'F', 'W')) {
            assertTrue(glyph !in grid, "一格地都没有的时候，网格里不该画着地貌 `$glyph`：\n$grid")
        }
    }

    @Test
    fun theNearMapShowsWhatIsWithinArmReach() {
        val world = world()
        val text = ChunkDetail.render(world, dwarfChunk(world), "dwarf")

        assertTrue("@ " in text || text.contains("@  "), "发起者永远是 @：\n$text")
        // 一块是八米见方：八行、每行八个格子。
        val grid = text.lines().filter { it.trimStart().startsWith("y=") }
        assertEquals(8, grid.size, "一块是八个米格高：\n$text")
        // 桌子和酒杯在同一个 (x,y) 上——**画的是最靠下的那一个**（桌子），酒杯在清单里另列。
        assertTrue("木桌" in text && "酒杯" in text)
        assertTrue("内容物=半杯麦酒" in text, "属性必须印出来，否则读的人只剩一个光秃秃的名字（§14.24）")
        // 落在别的层上的那几样（酒杯、斧子、抹布）还要列出来，只是不进网格。
        assertTrue("短柄斧" in text)
    }

    @Test
    fun theNearMapIsExactlyOneChunkWide() {
        val world = world()
        val text = ChunkDetail.render(world, dwarfChunk(world), "dwarf")
        val header = text.lines().first { it.trimStart().startsWith("x=") }

        // 八米见方 = 八个米格列头，一格一个数字。
        val columns = header.removePrefix("      x=").trim().split(Regex("\\s+"))
        assertEquals(8, columns.size, "列头该是八列：$header")
    }
}
