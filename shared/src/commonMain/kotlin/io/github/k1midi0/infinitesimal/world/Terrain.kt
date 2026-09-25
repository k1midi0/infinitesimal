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
 * **硬性圈**按块算的半径：4 个 chunk = **32 米**（附录 A.16）。
 *
 * "以 chunk 为单位"不是修辞：**圈是拿块挑出来的**，一块要么整个在圈里、要么整个不在。按格画出来的
 * 圆会切出半块的 chunk，而那些块会被标成「已经有地形」，于是界外那几列空着、谁也不会再去补——
 * 那是「地形以 chunk 为最小生成单位、不会有厘米级缺口」的一个反例（§14.30）。
 */
const val HARD_RADIUS_CHUNKS = 4

/**
 * **硬性圈**的半径，单位是地形格：4 个 chunk = 64 格 = **32 米**（附录 A.16）。
 *
 * 它是**准备冒险时要把话说满的范围**：跨出去之前，那边的地形还不存在。
 */
const val HARD_RADIUS_CELLS = HARD_RADIUS_CHUNKS * CHUNK_CELLS

/**
 * **缓冲圈**按块算的半径：32 个 chunk = **256 米**。硬性圈之外到这一圈之内，地形是**按需生成**的。
 */
const val BUFFER_RADIUS_CHUNKS = 32

/**
 * **缓冲圈**的半径，单位是地形格：32 个 chunk = 512 格 = **256 米**（附录 A.16）。
 *
 * 圈外就是「世界还没有长出来」。
 */
const val BUFFER_RADIUS_CELLS = BUFFER_RADIUS_CHUNKS * CHUNK_CELLS

/**
 * 向下整除。**负数要单独处理**：Kotlin 的 `/` 向零截断（`-1 / 50 == 0`），而物理上 -1 厘米在地面
 * **以下**那一格里。
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
 * | [surface] | 256 | 默认地面。八成的实体都站在这一层上下。 |
 * | [top] | 1024 | 世界顶。surface 以上到 top 是空中。 |
 *
 * 整套在**准备冒险时可以配置**，所以它是一份数据，不是常量。
 */
@Serializable
data class WorldDepth(
    val bottom: Int = 0,
    val surface: Int = 256,
    val top: Int = 1024,
) {

    val height: Int get() = top - bottom + 1

    /**
     * 地面在世界坐标里的**厘米**高度。
     *
     * 实体坐标是厘米，而地面是一个**地形格编号**——这里是两者唯一的接口。默认地面在第 256 格，
     * 也就是 **12800 厘米**。
     *
     * 它存在的理由是"开局那张酒馆站在地上"：酒馆里的东西写 `z = 1200` 会在 12 米高处，而写
     * `z = 0` 会在地下一百二十八米。所有内容都该写成 [surfaceCm] 加一个偏移，而不是写死一个数。
     */
    val surfaceCm: Int get() = surface * TERRAIN_CM

    fun contains(z: Int): Boolean = z in bottom..top

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
 * 坐标要学：`x1/y1/z1/x2/y2/z2` 六个整数，写反了也没关系。
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

    /** 框里有几个格。**它只是最后那张安全网**（见 [problems]）。 */
    val cellCount: Int
        get() = (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1)

    /**
     * 框横跨多少列。
     *
     * **它是段数的保守下界**：地形按列分段存（A.15），每一列至少留下一段，所以"这一次要写下多少段"
     * 永远不会少于列数。规模的上限因此判在它身上，而不是判在格数上——按列分段之后，格数早就是存储
     * 的实现细节了（§14.30）。
     */
    val columns: Int
        get() = (maxX - minX + 1) * (maxY - minY + 1)

    /** 一次返回**全部**问题，空列表 = 合格。 */
    fun problems(limits: TerrainLimits = TerrainLimits()): List<String> = buildList {
        if (columns > limits.maxRuns) {
            add(
                "这个框横跨 $columns 列，超过上限 ${limits.maxRuns}——地形按列存，" +
                    "每一列至少要留一段，所以这一次至少要写下 $columns 段。框小一点，或者分几次",
            )
        }
        if (maxZ - minZ + 1 > limits.maxHeight) {
            add("这个框有 ${maxZ - minZ + 1} 层高，超过上限 ${limits.maxHeight}")
        }
        if (cellCount > limits.maxCells) {
            add("这个框有 $cellCount 个格，超过安全上限 ${limits.maxCells}——框大到这个程度已经不像话了")
        }
    }
}

/**
 * 一次地形改动的规模上限。**它是参数而不是常量**，理由与 [ShapeLimits] 逐字相同。
 *
 * ## 为什么判的是列数而不是格数（§14.30）
 *
 * 早先只有 [maxCells]，默认 64×64 = 4096。而**一块 chunk 就是 16×16×256 = 65536 格**——生成器
 * "一次把一块铺满"这个最基本的需求，在旧上限下超限十六倍。
 *
 * 那不是把数字调大就完事的：格数是**按格存**时代的量，而地形早就按列分段存了。铺满一整块存下来
 * 只是每列几段。逼模型把小框拼对，是又一次「油灯坐标」式的错误来源——它在坐标上没有参照系。
 */
data class TerrainLimits(
    /**
     * 一次改动最多碰多少**列**。段数的保守下界（见 [TerrainBox.columns]）。
     *
     * 默认 16,384 = 128×128 列，正好是**硬性圈那一整片**（半径 32 米 = 64 个地形格 = 128 列见方）。
     * 取这个数，是因为生成器要能一次把硬性圈里缺的那一片铺满：一块 chunk 是 16×16 列。
     */
    val maxRuns: Int = 128 * 128,

    /**
     * 一次改动最多写多少**格**。**它只是一张安全网**，不是真正的判据（§14.30）。
     *
     * 默认 16,777,216 = 128×128 列 × 1024 层，也就是"把硬性圈那一整片从顶铺到底"。真到它上面，
     * 说明框已经大到不像话了。留这一条是因为格数是唯一一个**不需要看世界**就能算出来的量。
     */
    val maxCells: Int = 16 * 1_024 * 1_024,

    val maxHeight: Int = 1_024,
)

/**
 * 一列里的一段：**[from]..[to] 这些格都是 [material]**（闭区间）。
 *
 * ## 为什么按段存，而不是按格存
 *
 * 你定的是**硬性圈里要有地形**。半径 32 米的硬性圈是 12,868 个地形格，乘上 257 层
 * 是 **330 万个格**；按格存就是一张 330 万条的 map，光 entry 开销几百 MB，一开局就爆掉。
 *
 * 而地下**是分层的，不是噪点**：一列「岩层 0、石 1–180、土 181–256」是**三段**。语义一字不变
 * （每一格都有材质），存储降两个数量级。只有玩家真的挖过、堆过的那几列段数才会涨。
 *
 * ## 段与段不重叠、不相邻
 *
 * 相邻的同材质段一律合并，所以"这一列有几段"是一个确定的数，`dig` 留下的碎段不会无限累积。
 */
@Serializable
data class TerrainRun(
    val material: String,
    val from: Int,
    val to: Int,
) {
    val length: Int get() = to - from + 1

    fun covers(z: Int): Boolean = z in from..to
}

/** 一列。快照里用列表而不是 map：JSON 的键只能是字符串，而这个键是两个整数。 */
@Serializable
data class TerrainColumn(
    val x: Int,
    val y: Int,
    val runs: List<TerrainRun> = emptyList(),
)

@Serializable
data class TerrainSnapshot(
    val columns: List<TerrainColumn> = emptyList(),
    /** **已经生成过的 chunk。**"探索过"就是它（附录 A.16）。 */
    val generated: List<ChunkKey> = emptyList(),
    val depth: WorldDepth = WorldDepth(),
)

/**
 * 地形层：**一格一个材质 id，按列分段存**（附录 A.15）。
 *
 * ## "空气"不是一种材质
 *
 * 挖去就是把那一格**从列里去掉**。不在段里的格就是"没有地形"，不需要往表里放一个什么都不代表的
 * id——放了就得在所有读地形的地方多一个"这个 id 要跳过"的判断。
 *
 * ## 三只工具
 *
 * - [fill]：**只填空的格**。已经有的材质不动——这是"铺一条路"。
 * - [dig]：清空。**不可破坏的格跳过去**（世界底那层岩层靠它拦住）。
 * - [replace]：把框里的 `from` 换成 `to`。这是"烧焦一片草地"。
 *
 * 三只都**只动落在 [WorldDepth] 里的格**，都返回**真改了几个格**——返回 0 与"一个格都没动"不是
 * 一回事。三只都是**按段算的**：一个 16×16×257 的框是六万五千个格，而真正被碰到的段只有几十条。
 */
class Terrain private constructor(
    /** 每一列按 (x, y) 索引。**空列当场删掉**，所以不在表里就是"这一列什么都没有"。 */
    private val store: MutableMap<Long, TerrainColumn>,
    private val generated: MutableSet<ChunkKey>,
    val depth: WorldDepth,
) {

    /** 一共有几个格有材质。**它是个大数**（铺满的硬性圈是几百万），别拿它当内存占用的代理。 */
    val cellCount: Int
        get() = store.values.sumOf { column -> column.runs.sumOf { it.length } }

    /** **真正占用的段数。**它才是存储规模；`cellCount ÷ runCount` 大致就是分层的压缩比。 */
    val runCount: Int
        get() = store.values.sumOf { it.runs.size }

    fun materialIdAt(cell: TerrainCell): String? =
        materialAt(runsAt(cell.x, cell.y), cell.z)

    /** 这个格有没有被写过。**与"是不是空的"是同一件事**——写过的格一定有材质。 */
    fun isFilled(cell: TerrainCell): Boolean = materialIdAt(cell) != null

    /** 这一列的段，**按 z 升序**。空列返回空表。 */
    fun runsAt(x: Int, y: Int): List<TerrainRun> = store[columnKey(x, y)]?.runs ?: emptyList()

    /** 世界里所有非空的列。 */
    fun columns(): List<TerrainColumn> = store.values.toList()

    /**
     * 直接落一格。`null` = 清掉。
     *
     * **它不看 `breakable`**——这里是数据的写入口，不是规则。规则在 [dig] 与 [replace] 上，而那些
     * 才是一次动作会走到的地方。
     */
    fun set(cell: TerrainCell, materialId: String?) {
        if (!depth.contains(cell.z)) return
        val k = columnKey(cell.x, cell.y)
        val after = setInColumn(store[k]?.runs ?: emptyList(), cell.z, materialId)
        if (after.isEmpty()) store.remove(k) else store[k] = TerrainColumn(cell.x, cell.y, after)
    }

    /** **只填空的格。** 返回真改了几个。 */
    fun fill(box: TerrainBox, materialId: String, materials: MaterialTable): Int {
        if (materials.byId(materialId) == null) return 0
        return edit(box) { runs, from, to -> fillInColumn(runs, from, to, materialId) }
    }

    /** 清空。**不可破坏的格跳过去。** 返回真改了几个。 */
    fun dig(box: TerrainBox, materials: MaterialTable): Int =
        edit(box) { runs, from, to -> digInColumn(runs, from, to) { materials.isBreakable(it) } }

    /** 把 `from` 换成 `to`。**不可破坏的格跳过去**（岩层不会被换成土）。返回真改了几个。 */
    fun replace(box: TerrainBox, from: String, to: String, materials: MaterialTable): Int {
        if (materials.byId(to) == null) return 0
        return edit(box) { runs, a, b -> replaceInColumn(runs, a, b, from, to) { materials.isBreakable(it) } }
    }

    /**
     * 逐列走一遍框，只把**真的变了**的列写回去。
     *
     * 一片空地被 `dig` 一遍不该留下一堆空列——那等于把稀疏存的收益又还回去。
     */
    private fun edit(
        box: TerrainBox,
        change: (runs: List<TerrainRun>, from: Int, to: Int) -> List<TerrainRun>,
    ): Int {
        val from = maxOf(box.minZ, depth.bottom)
        val to = minOf(box.maxZ, depth.top)
        if (from > to) return 0

        var changed = 0
        for (x in box.minX..box.maxX) {
            for (y in box.minY..box.maxY) {
                val k = columnKey(x, y)
                val before = store[k]?.runs ?: emptyList()
                val after = change(before, from, to)
                if (after == before) continue
                for (z in from..to) {
                    if (materialAt(before, z) != materialAt(after, z)) changed++
                }
                if (after.isEmpty()) store.remove(k) else store[k] = TerrainColumn(x, y, after)
            }
        }
        return changed
    }

    // ── chunk ─────────────────────────────────────────────────────────────────────

    /**
     * **已经生成过的 chunk。**"探索过"就是它（附录 A.16）——而"探索过 = 既定事实"是这个世界里那条
     * 硬边界：生成时随便定制，生成过之后往那里再放东西就是凭空捏造。
     */
    fun generatedChunks(): Set<ChunkKey> = generated.toSet()

    fun isGenerated(chunk: ChunkKey): Boolean = chunk in generated

    fun markGenerated(chunk: ChunkKey) {
        generated += chunk
    }

    fun snapshot(): TerrainSnapshot = TerrainSnapshot(
        columns = store.values
            .filter { it.runs.isNotEmpty() }
            .sortedWith(compareBy({ it.x }, { it.y })),
        generated = generated.sortedWith(compareBy({ it.x }, { it.y })),
        depth = depth,
    )

    companion object {

        fun empty(depth: WorldDepth = WorldDepth()): Terrain =
            Terrain(mutableMapOf(), mutableSetOf(), depth)

        fun from(snapshot: TerrainSnapshot): Terrain = Terrain(
            snapshot.columns
                .filter { it.runs.isNotEmpty() }
                .associateTo(mutableMapOf()) { columnKey(it.x, it.y) to it },
            snapshot.generated.toMutableSet(),
            snapshot.depth,
        )

        /** 一列的主键：两个 Int 拼成一个 Long，比 `Pair` 少一次装箱。 */
        fun columnKey(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
    }
}

// ── 段运算 ────────────────────────────────────────────────────────────────────────
//
// 四个纯函数，与 [Terrain] 的字段无关。它们全都保证"不重叠、不相邻"这条不变式（末尾一律
// [mergeRuns]），所以 Terrain 那边不必再查一次。

/** [runs] 在 [z] 处是什么材质。段很少，线性扫比二分好读。 */
internal fun materialAt(runs: List<TerrainRun>, z: Int): String? =
    runs.firstOrNull { it.covers(z) }?.material

/**
 * 整理成一列：按 z 排序、合并相邻同材质、砍掉重叠部分。
 *
 * 它是这一层唯一的不变式守卫——四个运算都以它收尾，所以"一格里有两段"在数据上做不到。
 */
internal fun mergeRuns(runs: List<TerrainRun>): List<TerrainRun> {
    val live = runs.filter { it.from <= it.to }
    if (live.isEmpty()) return emptyList()
    val out = mutableListOf<TerrainRun>()
    for (r in live.sortedWith(compareBy({ it.from }, { it.to }))) {
        val last = out.lastOrNull()
        when {
            last == null -> out += r
            // 完全接不上：新起一段。
            r.from > last.to + 1 -> out += r
            // 接着、或者重叠，材质又一样：并进去。
            r.material == last.material -> if (r.to > last.to) out[out.size - 1] = last.copy(to = r.to)
            // 材质不同又重叠：只保留露在外面的尾巴。
            r.to > last.to -> out += r.copy(from = last.to + 1)
            else -> Unit
        }
    }
    return out
}

/** `[from]..[to]` 里**还没有材质**的那些小段。 */
internal fun gapsIn(runs: List<TerrainRun>, from: Int, to: Int): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var cursor = from
    for (r in runs.sortedBy { it.from }) {
        if (r.to < from) continue
        if (r.from > to) break
        val head = maxOf(r.from, from)
        if (head > cursor) out += cursor..(head - 1)
        cursor = maxOf(cursor, r.to + 1)
        if (cursor > to) break
    }
    if (cursor <= to) out += cursor..to
    return out
}

/** 落一格。`null` = 清掉。 */
internal fun setInColumn(runs: List<TerrainRun>, z: Int, material: String?): List<TerrainRun> {
    val without = runs.flatMap { r ->
        if (!r.covers(z)) {
            listOf(r)
        } else {
            buildList {
                if (r.from < z) add(TerrainRun(r.material, r.from, z - 1))
                if (r.to > z) add(TerrainRun(r.material, z + 1, r.to))
            }
        }
    }
    return mergeRuns(if (material == null) without else without + TerrainRun(material, z, z))
}

/** 只填空的格。 */
internal fun fillInColumn(runs: List<TerrainRun>, from: Int, to: Int, material: String): List<TerrainRun> {
    val gaps = gapsIn(runs, from, to)
    if (gaps.isEmpty()) return runs
    return mergeRuns(runs + gaps.map { TerrainRun(material, it.first, it.last) })
}

/** 清空 `[from]..[to]`，跳过不可破坏的材质。 */
internal fun digInColumn(
    runs: List<TerrainRun>,
    from: Int,
    to: Int,
    breakable: (String) -> Boolean,
): List<TerrainRun> = mergeRuns(
    runs.flatMap { r ->
        val a = maxOf(r.from, from)
        val b = minOf(r.to, to)
        if (a > b || !breakable(r.material)) {
            listOf(r)
        } else {
            buildList {
                if (r.from < a) add(TerrainRun(r.material, r.from, a - 1))
                if (r.to > b) add(TerrainRun(r.material, b + 1, r.to))
            }
        }
    },
)

/** 把 `target` 换成 `replacement`，跳过不可破坏的材质。 */
internal fun replaceInColumn(
    runs: List<TerrainRun>,
    from: Int,
    to: Int,
    target: String,
    replacement: String,
    breakable: (String) -> Boolean,
): List<TerrainRun> = mergeRuns(
    runs.flatMap { r ->
        val a = maxOf(r.from, from)
        val b = minOf(r.to, to)
        if (a > b || r.material != target || !breakable(r.material)) {
            listOf(r)
        } else {
            buildList {
                if (r.from < a) add(TerrainRun(r.material, r.from, a - 1))
                add(TerrainRun(replacement, a, b))
                if (r.to > b) add(TerrainRun(r.material, b + 1, r.to))
            }
        }
    },
)
