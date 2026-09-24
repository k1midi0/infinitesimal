package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * 地形格的大小：**50 厘米**（附录 A.15）。
 *
 * 八个正好填满一个米格。地形**只有这一种精度**——不会有 10 厘米的地形。比 50 厘米更细的世界由
 * **物体**表达（5 厘米的体素，[VOXEL_CM]），两者不是一套东西：**地形是地下的体积，物体在地形表面
 * 之上**。
 */
const val TERRAIN_CM = 50

/** 一个 chunk 的水平边长，单位是地形格。**16 格 = 8 米**（附录 A.16）。 */
const val CHUNK_CELLS = 16

/**
 * 向下整除。**负数要单独处理**：Kotlin 的 `/` 向零截断（`-50 / 50 == -1`），而物理上 -50 厘米在
 * 地面**以下**那一格里，答案是 -1——但 `-1 / 50` 会给出 0，那就在地面之上了。
 *
 * 与 `spatial/Cells.kt` 的 `metreCell` 是同一条理由，两处各写一遍是因为两个分辨率各自要用。
 */
fun floorDiv(a: Int, b: Int): Int {
    val q = a / b
    return if (a % b != 0 && (a xor b) < 0) q - 1 else q
}

/** 厘米 → 地形格，向下取整。 */
fun terrainCellOf(cm: Int): Int = floorDiv(cm, TERRAIN_CM)

/** 地形格上的一个位置。单位是 [TERRAIN_CM]。 */
@Serializable
data class TerrainCell(val x: Int, val y: Int, val z: Int)

/** 一个 chunk 的水平坐标，单位是 [CHUNK_CELLS]。**它是竖向一整列**，所以只有 x、y。 */
@Serializable
data class ChunkKey(val x: Int, val y: Int)

fun chunkOf(cell: TerrainCell): ChunkKey =
    ChunkKey(floorDiv(cell.x, CHUNK_CELLS), floorDiv(cell.y, CHUNK_CELLS))

/**
 * 世界的竖向范围（附录 A.15，2026-09-26 定）。
 *
 * | | 默认 | 是什么 |
 * |---|---|---|
 * | [bottom] | 0 | 世界底。**这一层是 [Material] 里 `breakable = false` 的那层岩层。** |
 * | [surface] | 256 | 默认地面。**八成的实体都站在这一层上下。** |
 * | [top] | 1024 | 世界顶。surface 以上到 top 是空中——可以往上建、往上飞，现在什么都没有。 |
 *
 * 整套在**准备冒险时可以配置**：换一张地图就是换这三个数。所以它是一份数据，不是常量。
 */
@Serializable
data class WorldDepth(
    val bottom: Int = 0,
    val surface: Int = 256,
    val top: Int = 1024,
) {

    val height: Int get() = top - bottom + 1

    fun contains(z: Int): Boolean = z in bottom..top

    fun contains(cell: TerrainCell): Boolean = contains(cell.z)

    /** 一次返回**全部**问题，空列表 = 合格。 */
    fun problems(): List<String> = buildList {
        if (top <= bottom) add("世界顶（$top）不比世界底（$bottom）高")
        if (surface !in bottom..top) add("地面在 $surface，落在世界范围 $bottom..$top 之外")
    }
}

/**
 * 三个框选工具收的那个长方体（附录 A.15）。
 *
 * **三只工具收同一个形状**，包括挖去——它的参数里只是没有材质。收同一个形状的理由是模型只有一套
 * 坐标要学：`x1/y1/z1/x2/y2/z2` 六个整数，写反了也没关系（`minOf`/`maxOf` 会摆正）。
 *
 * 单位是**地形格**，不是厘米。
 */
@Serializable
data class TerrainBox(
    val x1: Int,
    val y1: Int,
    val z1: Int,
    val x2: Int,
    val y2: Int,
    val z2: Int,
) {

    val minX: Int get() = minOf(x1, x2)
    val maxX: Int get() = maxOf(x1, x2)
    val minY: Int get() = minOf(y1, y2)
    val maxY: Int get() = maxOf(y1, y2)
    val minZ: Int get() = minOf(z1, z2)
    val maxZ: Int get() = maxOf(z1, z2)

    /** 框里有几个格。**它是这一批要写多少格**，所以上限判在它身上。 */
    val cellCount: Int
        get() = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)

    /** 一次返回**全部**问题，空列表 = 合格。 */
    fun problems(limits: TerrainLimits = TerrainLimits()): List<String> = buildList {
        if (cellCount > limits.maxCells) {
            add("这个框有 $cellCount 个格，超过上限 ${limits.maxCells}；框小一点，或者分几次")
        }
        if (maxZ - minZ + 1 > limits.maxHeight) {
            add("这个框有 ${maxZ - minZ + 1} 层高，超过上限 ${limits.maxHeight}")
        }
    }
}

/**
 * 一次地形改动的规模上限。**它是参数而不是常量**，理由与 [ShapeLimits] 逐字相同：地形块的大小差得
 * 远，而那个调用方会有自己的一份。
 */
data class TerrainLimits(
    val maxCells: Int = 64 * 64,
    val maxHeight: Int = 64,
)

/** 快照里的一格。用列表而不是 `Map`：JSON 的键只能是字符串，而这个键是三个整数。 */
@Serializable
data class TerrainBlock(val cell: TerrainCell, val material: String)

@Serializable
data class TerrainSnapshot(
    val blocks: List<TerrainBlock> = emptyList(),
    /** **已经生成过的 chunk。**"探索过"就是它（附录 A.16）。 */
    val generated: List<ChunkKey> = emptyList(),
    val depth: WorldDepth = WorldDepth(),
)

/**
 * 地形层：**一格一个材质 id，稀疏存**（附录 A.15）。
 *
 * ## 为什么稀疏
 *
 * 世界竖向 0–1024 格，而水平不设上限（A.16）。照 1024³ 铺满是一百多万个格，而**真正被写过的格
 * 不到万分之一**——地下一百多米，玩家一辈子也挖不到。所以这里存的只是**被写过的那几个格**，
 * 不在表里的格就是"没有地形"。
 *
 * ## "空气"不是一种材质
 *
 * 挖去就是把那一格**从表里去掉**。稀疏存天然表达这件事，不需要往表里放一个什么都不代表的 id
 * ——放了就得在所有读地形的地方多一个"这个 id 要跳过"的判断。
 *
 * ## 三只工具
 *
 * - [fill]：**只填空的格**。已经有的材质不动——这是"铺一条路"。
 * - [dig]：清空。**不可破坏的格跳过去**（世界底那层岩层靠它拦住）。
 * - [replace]：把框里的 [Material] `from` 换成 `to`。这是"烧焦一片草地"。
 *
 * 三只都**只动落在 [WorldDepth] 里的格**：范围外的格不存在，不该被写出来。三只都返回**真改了几个
 * 格**——返回 0 与"改成了 0 个"是两回事，而日志里要靠它分辨。
 */
class Terrain private constructor(
    private val blocks: MutableMap<TerrainCell, String>,
    private val generated: MutableSet<ChunkKey>,
    val depth: WorldDepth,
) {

    /** 一共有几个格有材质。**它才是"这个地形有多大"的度量**，不是世界体积。 */
    val cellCount: Int get() = blocks.size

    fun materialIdAt(cell: TerrainCell): String? = blocks[cell]

    /** 这个格有没有被写过。**与"是不是空的"是同一件事**——写过的格一定有材质。 */
    fun isFilled(cell: TerrainCell): Boolean = blocks.containsKey(cell)

    fun all(): List<TerrainBlock> = blocks.entries.map { TerrainBlock(it.key, it.value) }

    /**
     * 直接落一格。`null` = 清掉。
     *
     * **它不看 `breakable`**——这里是数据的写入口，不是规则。规则在 [dig] 与 [replace] 上，而那些
     * 才是一次动作会走到的地方。
     */
    fun set(cell: TerrainCell, materialId: String?) {
        if (materialId == null) blocks.remove(cell) else blocks[cell] = materialId
    }

    /** **只填空的格。** 返回真改了几个。 */
    fun fill(box: TerrainBox, materialId: String, materials: MaterialTable): Int {
        if (materials.byId(materialId) == null) return 0
        var n = 0
        each(box) { cell ->
            if (!blocks.containsKey(cell)) {
                blocks[cell] = materialId
                n++
            }
        }
        return n
    }

    /** 清空。**不可破坏的格跳过去。** 返回真改了几个。 */
    fun dig(box: TerrainBox, materials: MaterialTable): Int {
        var n = 0
        each(box) { cell ->
            val here = blocks[cell]
            if (here != null && materials.isBreakable(here)) {
                blocks.remove(cell)
                n++
            }
        }
        return n
    }

    /** 把 `from` 换成 `to`。**不可破坏的格跳过去**（岩层不会被换成土）。返回真改了几个。 */
    fun replace(box: TerrainBox, from: String, to: String, materials: MaterialTable): Int {
        if (materials.byId(to) == null) return 0
        var n = 0
        each(box) { cell ->
            if (blocks[cell] == from && materials.isBreakable(from)) {
                blocks[cell] = to
                n++
            }
        }
        return n
    }

    /** 只走竖向范围里的格。范围外的格**不存在**，不该被写出来。 */
    private inline fun each(box: TerrainBox, body: (TerrainCell) -> Unit) {
        for (x in box.minX..box.maxX) {
            for (y in box.minY..box.maxY) {
                for (z in box.minZ..box.maxZ) {
                    if (!depth.contains(z)) continue
                    body(TerrainCell(x, y, z))
                }
            }
        }
    }

    // ── chunk ─────────────────────────────────────────────────────────────────────

    /**
     * **已经生成过的 chunk。**"探索过"就是它（附录 A.16）——而"探索过 = 既定事实"是这个世界里
     * 那条硬边界：生成时随便定制，生成过之后往那里再放东西就是凭空捏造。
     */
    fun generatedChunks(): Set<ChunkKey> = generated.toSet()

    fun isGenerated(chunk: ChunkKey): Boolean = chunk in generated

    fun markGenerated(chunk: ChunkKey) {
        generated += chunk
    }

    fun snapshot(): TerrainSnapshot =
        TerrainSnapshot(all(), generated.sortedWith(compareBy({ it.x }, { it.y })), depth)

    companion object {

        fun empty(depth: WorldDepth = WorldDepth()): Terrain =
            Terrain(mutableMapOf(), mutableSetOf(), depth)

        fun from(snapshot: TerrainSnapshot): Terrain = Terrain(
            snapshot.blocks.associateTo(mutableMapOf()) { it.cell to it.material },
            snapshot.generated.toMutableSet(),
            snapshot.depth,
        )
    }
}
