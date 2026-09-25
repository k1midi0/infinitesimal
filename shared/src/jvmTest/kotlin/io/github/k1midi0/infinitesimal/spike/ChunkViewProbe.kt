package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.ChunkView
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.spatial.nearestMissing
import io.github.k1midi0.infinitesimal.spatial.requiredChunks
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.HARD_RADIUS_CHUNKS
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test

/**
 * **离线**看开局那张区块地图（§14.33）。不需要密钥，也不发请求——这是一张排版，眼睛看得比断言清楚。
 *
 * 三张图：开局（硬圈正好铺满）、往外走八块之后（露出一个月牙）、以及缩到硬圈附近看那个月牙。
 */
class ChunkViewProbe {

    private fun opening(): World = World.from(
        WorldSnapshot(
            entities = StartingWorld.tavern(),
            rulebook = StartingWorld.rules(),
            terrain = StartingWorld.ground,
        ),
    )

    @Test
    fun `print the chunk map`() {
        val world = opening()
        val start = requireNotNull(world.chunkOfEntity("player"))
        val origin = ChunkOrigin.atPlayerStart(start)

        println("═".repeat(72))
        println("开局：玩家在绝对 ${start.x},${start.y}；显示坐标 (1,1)。硬圈半径 $HARD_RADIUS_CHUNKS 块")
        println("硬圈里还缺 ${requiredChunks(world, start).size} 块")
        println("═".repeat(72))
        println(ChunkView.render(world, origin, start, radius = 6))

        // 往外走八块：圆盘跟着人移，身后那一圈不再算数，前头露出一个月牙。
        val moved = ChunkKey(start.x + 8, start.y)
        println("═".repeat(72))
        println("往东走八块之后：玩家在绝对 ${moved.x},${moved.y}")
        println("硬圈里还缺 ${requiredChunks(world, moved).size} 块")
        nearestMissing(world, moved)?.let {
            println("最近一块没地形的在绝对 ${it.chunk.x},${it.chunk.y}，隔 ${it.chunksAway} 块，硬圈外 ${it.beyondHard} 块")
        }
        println("═".repeat(72))
        println(ChunkView.render(world, origin, moved, radius = 10))
    }
}
