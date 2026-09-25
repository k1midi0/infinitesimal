package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * 形状的最小单位：**5 厘米**。
 *
 * 比它更小的东西不用体素表示，用**质点**——一颗蓝莓画不成一个 5 厘米的方块，但它可以算作那个格
 * 里的一个质点。所以形状只有两种占用方式：**实心**与**质点**。
 */
const val VOXEL_CM = 5

/** 一个格最多装几个质点。**超过它就该升一档去用体素了**，而不是让一个格无限装。 */
const val MAX_POINTS_PER_VOXEL = 10

/**
 * 形状里的一个格：**局部坐标，单位是厘米，必须是 [VOXEL_CM] 的整数倍**。
 *
 * 坐标一律用厘米，不另立一套"第几个格子"的下标——一套单位比两套省事得多，而厘米是世界里本来就有的
 * 那一套（附录 A.2：坐标改成厘米级）。
 */
@Serializable
data class Voxel(val x: Int, val y: Int, val z: Int)

/** 一个格里装着几个质点。`count` 只能是 1..[MAX_POINTS_PER_VOXEL]。 */
@Serializable
data class PointCluster(val voxel: Voxel, val count: Int)

/**
 * 一样东西的形状：**它占哪些 5 厘米格**（附录 A.9–A.11）。
 *
 * ## 三条定下来的
 *
 * 1. **实体表是真相，形状是它的语言**（A.9）。操作的单位始终是**物体**，不是体素——判定移动的是
 *    「那张桌子」，不是三千个格子。
 * 2. **不能互相引用。** 形状是**拷贝**，不是共享对象：打破一个杯子不能让所有引用同一形状的杯子
 *    一起碎（A.9）。预设库因此是工厂，不是引用。
 * 3. **实心与质点互斥**：一个 5 厘米格要么是实心（满了），要么装着 1–10 个质点（空腔里的东西）。
 *    两者同时成立在物理上说不通，代码里也就没有它的位置。
 *
 * ## 为什么包围盒的最小角必须是 (0,0,0)
 *
 * 因为形状进出的那一种表示法是**一张从原点起画的网格**（[ShapeGrid]）：第 r 行就是 y = r×5，第 c 个
 * 字符就是 x = c×5。允许最小角跑偏，就得在网格里补一串前导空行、或者给网格再加一个原点声明——两条
 * 都比直接要求"从原点起画"更容易出错。
 *
 * 于是 `Entity.x/y/z` 的含义是确定的：**那样东西最小角在世界里的厘米坐标**。
 *
 * ## 校验不是构造的一部分
 *
 * [problems] 是**一次返回全部问题**的查询，而不是 `init` 里的 `require`。理由是这套东西来自模型：
 * 它画错是常态，代码要做的是**把错在哪原样告诉它**（§1.3），而不是在校验处抛一个异常、把它变成一次
 * 看不见的崩溃。空列表就是合格。
 */
@Serializable
data class VoxelShape(
    /** 实心的格。**稀疏**：没填的格不出现。 */
    val solid: List<Voxel> = emptyList(),
    /** 装着质点的格。 */
    val pointMass: List<PointCluster> = emptyList(),
) {

    val isEmpty: Boolean get() = solid.isEmpty() && pointMass.isEmpty()

    /** 实心格 + 质点格的总数。**它才是"这样东西有多大"的度量**，不是包围盒体积。 */
    val cellCount: Int get() = solid.size + pointMass.size

    /** 包围盒的最大角；空形状是 (0,0,0)。最小角按约定恒为 (0,0,0)。 */
    val extent: Voxel
        get() = Voxel(
            maxOf((solid.map { it.x } + pointMass.map { it.voxel.x }).maxOrNull() ?: 0, 0),
            maxOf((solid.map { it.y } + pointMass.map { it.voxel.y }).maxOrNull() ?: 0, 0),
            maxOf((solid.map { it.z } + pointMass.map { it.voxel.z }).maxOrNull() ?: 0, 0),
        )

    /**
     * 一次返回**全部**问题。空列表 = 合格。
     *
     * 一次给全，是因为模型修错的能力与它拿到的信息成正比：只报第一条，它就得来回好几轮才把一张图改
     * 对，而每一轮都是一次完整的调用。
     */
    fun problems(limits: ShapeLimits = ShapeLimits()): List<String> = buildList {
        if (isEmpty) {
            add("形状是空的：一个实心格、一个质点都没有")
            return@buildList
        }

        val owned = mutableMapOf<Voxel, String>()

        for (v in solid) {
            val where = "(${v.x},${v.y},${v.z})"
            addAll(placementProblems(v, where))
            owned.put(v, "实心")?.let { add("格 $where 出现了两次") }
        }

        for (p in pointMass) {
            val v = p.voxel
            val where = "(${v.x},${v.y},${v.z})"
            addAll(placementProblems(v, where))
            if (p.count !in 1..MAX_POINTS_PER_VOXEL) {
                add("格 $where 里的质点数是 ${p.count}，只能是 1..$MAX_POINTS_PER_VOXEL")
            }
            when (owned.put(v, "装着 ${p.count} 个质点")) {
                null -> Unit
                "实心" -> add("格 $where 既标成实心、又装着质点；这两件事互斥")
                else -> add("格 $where 出现了两次")
            }
        }

        if (cellCount > limits.maxCells) {
            add("一共占了 $cellCount 个格，超过上限 ${limits.maxCells}；形状要更省一点")
        }

        // **最小角必须是原点。** 它由网格那种表示法决定（第 r 行就是 y = r×5），所以它是一条硬规则，
        // 而不是一个可以商量的偏好。
        val origin = Voxel(
            (solid.map { it.x } + pointMass.map { it.voxel.x }).minOrNull() ?: 0,
            (solid.map { it.y } + pointMass.map { it.voxel.y }).minOrNull() ?: 0,
            (solid.map { it.z } + pointMass.map { it.voxel.z }).minOrNull() ?: 0,
        )
        if (origin != Voxel(0, 0, 0)) {
            add("形状的最小角必须在 (0,0,0)，现在是 (${origin.x},${origin.y},${origin.z})；整张图往原点挪一下")
        }
    }

    /**
     * 单个格自身的问题：负数、不是 5 的整数倍。
     *
     * **这里没有"太大"那一条**（用户定的：不做大小限制）。早先有一条单边两百厘米的上限，而它的后果
     * 是"橡树"一律画成树苗、门和墙根本画不出来——那个数是当初按"一件家具"定的，而一片林子也在用
     * 同一份形状服务。见 [ShapeLimits]。
     */
    private fun placementProblems(v: Voxel, where: String): List<String> = buildList {
        if (v.x < 0 || v.y < 0 || v.z < 0) {
            add("格 $where 的坐标不能是负的")
            return@buildList
        }
        if (v.x % VOXEL_CM != 0 || v.y % VOXEL_CM != 0 || v.z % VOXEL_CM != 0) {
            add("格 $where 不是 $VOXEL_CM 厘米的整数倍")
        }
    }
}

/**
 * 一次建模的规模上限。
 *
 * ## 没有"单边多长"这一条（用户定的：不做大小限制）
 *
 * 早先它是 `maxSideCm = 200`，理由是"一件家具两米封顶"——听起来合理，实际上是一条**世界规则偷偷
 * 藏在代码里**：它让橡树只能画成树苗、门和墙根本画不出来，而症状是"模型画不好树"，不像一条限制。
 * 世界里的东西该多大，是内容的事，不是校验的事。
 *
 * ## 留下的这一条是保险丝，不是规则
 *
 * [maxCells] 与建模那四十个来回的上限同类（那时管的是时间，这里管的是体积）：它拦的不是"这东西太
 * 大了"，而是"模型一次吐出一张病态巨大的图"。抬到十万格——现实里碰不到，而碰到了能报一句人话。
 *
 * 它是**参数而不是常量**，因为附录 A.10 说这个建模服务是通用的：每个调用方可以带自己的一份。
 */
data class ShapeLimits(
    /**
     * 一共最多占几个格。默认十万。
     *
     * 稀疏存储下这是一份几 MB 的网格文本，而它要过一次解析、进一次世界——所以这条线划在这里，量的是
     * "一次响应值不值得处理完"，不是"允不允许大东西"。
     */
    val maxCells: Int = 100_000,
)
