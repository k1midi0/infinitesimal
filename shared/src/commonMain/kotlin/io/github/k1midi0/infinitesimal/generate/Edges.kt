package io.github.k1midi0.infinitesimal.generate

import io.github.k1midi0.infinitesimal.world.CHUNK_CELLS
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.TERRAIN_CM
import io.github.k1midi0.infinitesimal.world.World

/**
 * **接壤处**：这一片地和已经长好的地交界的地方长什么样（§14.30）。
 *
 * ## 为什么要把这件事算好递给它
 *
 * 模型看不到自己旁边那片地。不知道旁边是平地还是深谷，它铺出来的地面高度就只能瞎猜，而后果是
 * **地图上一道刀切出来的台阶**——那种东西一眼就能看出是生成出来的，而它没法自己发现：等它下一次
 * 被叫起来的时候，那道缝已经在世界里了。
 *
 * ## 为什么只报摘要，不报逐列的高度
 *
 * 一条边是 16 列，四条边 64 个数，几块 chunk 就是几百个数——那是给模型一堆它要自己求最小值的东西。
 * 它真正需要的只有两样：**地面大概在多高**（所以要 `min..max`）、**最上面铺的是什么**（所以要材质）。
 * 一条平的边就压成一句话。
 *
 * 挨着「还没有地形」的方向不报：那边是它接下来要自己铺的，不是既成事实。
 */
internal object Edges {

    /** 空串 = 四面都不挨着已经长好的地（一般是开局第一片）。 */
    fun of(world: World, chunks: List<ChunkKey>): String {
        val lines = mutableListOf<String>()
        for (chunk in chunks.distinct()) {
            val x0 = chunk.x * CHUNK_CELLS
            val y0 = chunk.y * CHUNK_CELLS
            val x1 = x0 + CHUNK_CELLS - 1
            val y1 = y0 + CHUNK_CELLS - 1

            val sides = listOf(
                side(world, chunk, "北", ChunkKey(chunk.x, chunk.y - 1)) { (x0..x1).map { it to (y0 - 1) } },
                side(world, chunk, "南", ChunkKey(chunk.x, chunk.y + 1)) { (x0..x1).map { it to (y1 + 1) } },
                side(world, chunk, "西", ChunkKey(chunk.x - 1, chunk.y)) { (y0..y1).map { (x0 - 1) to it } },
                side(world, chunk, "东", ChunkKey(chunk.x + 1, chunk.y)) { (y0..y1).map { (x1 + 1) to it } },
            )
            lines += sides.filterNotNull()
        }
        return lines.joinToString("\n")
    }

    /**
     * 一条边。[border] 是**懒算**的：挨着没有地形的方向，那 16 个坐标一个都不用求。
     */
    private fun side(
        world: World,
        chunk: ChunkKey,
        label: String,
        neighbour: ChunkKey,
        border: () -> List<Pair<Int, Int>>,
    ): String? {
        if (!world.terrain.isGenerated(neighbour)) return null

        val tops = border().mapNotNull { (x, y) ->
            // 世界顶以上那几段不算：那是天上，不是地面。
            world.terrain.runsAt(x, y).lastOrNull { it.from <= world.terrain.depth.top }
        }
        if (tops.isEmpty()) return null

        val heights = tops.map { it.to }
        val low = heights.min()
        val high = heights.max()
        val materials = tops.map { world.materials.name(it.material) }.distinct()

        val shape = if (low == high) {
            "地面就在第 $low 格，一条平"
        } else {
            "地面在第 $low..$high 格之间"
        }
        return "  chunk (${chunk.x},${chunk.y}) 的${label}边接着已经长好的地：$shape；" +
            "那边最上面是 ${materials.joinToString("、")}"
    }

    /** 给提示词用的厘米范围：模型填 `make_object` 的坐标时要用。 */
    fun centimetresOf(chunk: ChunkKey): String {
        val x0 = chunk.x * CHUNK_CELLS * TERRAIN_CM
        val y0 = chunk.y * CHUNK_CELLS * TERRAIN_CM
        val x1 = x0 + CHUNK_CELLS * TERRAIN_CM - 1
        val y1 = y0 + CHUNK_CELLS * TERRAIN_CM - 1
        return "x $x0..$x1、y $y0..$y1"
    }

    /** 给提示词用的地形格范围。 */
    fun cellsOf(chunk: ChunkKey): String {
        val x0 = chunk.x * CHUNK_CELLS
        val y0 = chunk.y * CHUNK_CELLS
        return "x $x0..${x0 + CHUNK_CELLS - 1}、y $y0..${y0 + CHUNK_CELLS - 1}"
    }
}
