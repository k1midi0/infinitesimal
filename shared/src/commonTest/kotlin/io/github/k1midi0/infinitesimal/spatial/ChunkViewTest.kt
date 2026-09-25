package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.CHUNK_CELLS
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.HARD_RADIUS_CHUNKS
import io.github.k1midi0.infinitesimal.world.TERRAIN_CM
import io.github.k1midi0.infinitesimal.world.TerrainSnapshot
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldDepth
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 区块坐标这一层（§14.33）的离线测试。
 *
 * 全是确定性的：没有端点、没有密钥、没有随机。这一层是「导演自己填坐标」那条决定的参照系，算错一格
 * 的后果不是报错，是一块永远补不上的月牙。
 */
class ChunkViewTest {

    private val player = ChunkKey(10, 10)
    private val origin = ChunkOrigin.atPlayerStart(player)

    /** 玩家站在 [chunk] 那一块上，地形层里只有 [generated] 这些块「已经有地形了」。 */
    private fun worldWith(generated: Set<ChunkKey>): World = World.from(
        WorldSnapshot(
            entities = listOf(
                Entity(
                    id = "player",
                    kind = "角色",
                    label = "你",
                    x = player.x * CHUNK_CELLS * TERRAIN_CM + 1,
                    y = player.y * CHUNK_CELLS * TERRAIN_CM + 1,
                    z = WorldDepth().surfaceCm,
                ),
            ),
            terrain = TerrainSnapshot(generated = generated.toList()),
        ),
    )

    private fun World.playerChunk(): ChunkKey = requireNotNull(chunkOfEntity("player"))

    @Test
    fun `theDiscIsTheSameFortyNineChunksTheStartingGroundLays`() {
        assertEquals(49, chunkDisc(player, HARD_RADIUS_CHUNKS).size)
        // 半径 32 的圆盘（缓冲圈）。数一遍是为了钉住「圆盘不是方块」——方块会是 65×65 = 4225。
        val buffer = chunkDisc(player, 32)
        assertTrue(buffer.size in 3000..3400, "半径 32 的圆盘应该有三千出头块，实得 ${buffer.size}")
        assertTrue(ChunkKey(player.x + 32, player.y + 32) !in buffer, "正方形的角不该落在圆盘里")
    }

    @Test
    fun `theOriginPutsThePlayerAtOneOne`() {
        assertEquals(ChunkKey(1, 1), origin.display(player))
        assertEquals(ChunkKey(-3, -3), origin.display(ChunkKey(player.x - 4, player.y - 4)))
    }

    @Test
    fun `displayAndAbsoluteAreInverses`() {
        for (c in chunkDisc(player, 6)) {
            assertEquals(c, origin.absolute(origin.display(c)))
        }
    }

    @Test
    fun `thePlayerChunkIsReadOffWhereTheEntityStands`() {
        assertEquals(player, worldWith(emptySet()).playerChunk())
    }

    @Test
    fun `requiredChunksAreTheHardDiscMinusWhatAlreadyHasTerrain`() {
        val empty = worldWith(emptySet())
        assertEquals(49, requiredChunks(empty, player).size)

        val full = worldWith(chunkDisc(player, HARD_RADIUS_CHUNKS).toSet())
        assertEquals(emptyList(), requiredChunks(full, player))

        // 缓冲圈里的块不算「必需」——那是可选的那一半。
        val oneHole = worldWith((chunkDisc(player, HARD_RADIUS_CHUNKS) - ChunkKey(player.x + 2, player.y)).toSet())
        assertEquals(listOf(ChunkKey(player.x + 2, player.y)), requiredChunks(oneHole, player))
    }

    @Test
    fun `theNearestMissingChunkKnowsHowFarBeyondTheHardCircleItIs`() {
        // 什么都没有：最近的就是脚下这一块，贴着硬圈（距离 0）。
        val empty = nearestMissing(worldWith(emptySet()), player)
        assertEquals(NearestMissing(player, 0, 0), empty)

        // 硬圈铺满：最近的一块在距离 5 的那一圈上，也就是硬圈外第一圈。
        val filled = nearestMissing(worldWith(chunkDisc(player, HARD_RADIUS_CHUNKS).toSet()), player)
        assertEquals(5, filled?.chunksAway)
        assertEquals(1, filled?.beyondHard)

        // 硬圈里缺一块：距离回到 2，`beyondHard` 是 0——那是同步那条路要管的。
        val hole = ChunkKey(player.x + 2, player.y)
        val holed = nearestMissing(worldWith(chunkDisc(player, HARD_RADIUS_CHUNKS).toSet() - hole), player)
        assertEquals(NearestMissing(hole, 2, 0), holed)

        // 铺到距离 20 以内：最近的一块落在 21，于是「离硬圈还有 17 块」——快速档据此判断还不用动。
        val wide = chunkDisc(player, 20).toSet()
        val far = nearestMissing(worldWith(wide), player)
        assertEquals(21, far?.chunksAway)
        assertEquals(17, far?.beyondHard)
    }

    @Test
    fun `theMapMarksThePlayerTheGeneratedAndTheTwoKindsOfMissing`() {
        val hole = ChunkKey(player.x + 2, player.y)
        val outside = ChunkKey(player.x + 3, player.y)
        val world = worldWith(
            (chunkDisc(player, HARD_RADIUS_CHUNKS).toSet() - hole) + outside,
        )
        val radius = 6
        val text = ChunkView.render(world, origin, player, radius)
        val lines = text.trimEnd('\n').lines()
        val body = lines.drop(2)
        assertEquals(2 * radius + 1, body.size)

        fun charAt(c: ChunkKey): Char {
            val i = c.x - (player.x - radius)
            val j = c.y - (player.y - radius)
            return body[j][ChunkView.LABEL + i]
        }

        assertEquals(ChunkView.PLAYER, charAt(player))
        assertEquals(ChunkView.GENERATED, charAt(ChunkKey(player.x - 3, player.y)))
        assertEquals(ChunkView.REQUIRED, charAt(hole), "硬圈里缺的那一块是必需，不是可选")
        assertEquals(ChunkView.GENERATED, charAt(outside))
        val far = ChunkKey(player.x, player.y + radius)
        assertEquals(ChunkView.OPTIONAL, charAt(far), "缓冲圈里没长过的是可选")
        assertEquals(' ', lines[0].last(), "圆盘之外留空——图的形状本身就是信息")

        // 行头是 y 的显示坐标，第一行是最北（y 最小）那一行。原点在玩家左下，所以显示坐标从 (1,1) 数。
        assertTrue(body[0].startsWith("y=-05 "), "行头实得 ${body[0].take(6)}")
        assertTrue(body.last().startsWith("y=+07 "), "行头实得 ${body.last().take(6)}")
    }

    @Test
    fun `theAxisHeaderCarriesEveryTenthValueAndTheOnesDigitOfEveryColumn`() {
        val text = ChunkView.render(worldWith(emptySet()), origin, player, 12)
        val lines = text.lines()
        val ruler = lines[0]
        val units = lines[1]

        // 显示坐标从 -11 到 +13，十的倍数只有 -10、0、10。
        assertTrue(ruler.contains("-10"), "十位尺里应该有 -10：$ruler")
        assertTrue(ruler.contains("+10") || ruler.contains("10"), "十位尺里应该有 10：$ruler")
        assertEquals(ChunkView.LABEL + 25, ruler.length)
        assertEquals(ChunkView.LABEL + 25, units.length)
        // 个位尺每一列都有一个数字：显示 x = -11 那一列的个位是 1。
        assertEquals('1', units[ChunkView.LABEL])
        assertEquals('0', units[ChunkView.LABEL + 11], "显示 x = 0 那一列")
    }

    @Test
    fun `aDisplayCoordinateIsReadBackAsAnAbsoluteChunk`() {
        // 原点在玩家左下，所以显示 (1,1) 就是玩家脚下那一块。
        assertEquals(player, ChunkView.parseChunk("(1,1)", origin))
        assertEquals(origin.absolute(ChunkKey(1, 2)), ChunkView.parseChunk("(1,2)", origin))
        assertEquals(
            origin.absolute(ChunkKey(-3, 4)),
            ChunkView.parseChunk("  ( -3 , 4 )  ", origin),
        )
        assertEquals(origin.absolute(ChunkKey(0, -5)), ChunkView.parseChunk("-0,-5", origin))
        assertNull(ChunkView.parseChunk("北边四块", origin))

        assertEquals(
            listOf(origin.absolute(ChunkKey(1, 2)), origin.absolute(ChunkKey(-1, 0))),
            ChunkView.parseChunks("(1,2) (-1,0)", origin),
        )
    }
}
