package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.CM_PER_METRE
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World

/**
 * **米级格子**上的一个位置。
 *
 * 射线的步进、凸包、包围框、俯视图网格全都在这一套格子上算（附录 A.12：一格 = 1 米）。它是
 * [io.github.k1midi0.infinitesimal.world.VoxelShape] 那套 5 厘米体素的**粗视图**——两者不是两套
 * 数据，是同一份世界坐标的两种分辨率。
 *
 * 为什么不干脆全用厘米：7×7 米的框在 5 厘米格下是 140×140，一层网格 19600 个字符，一段提示词里
 * 装不下；而"这一片有多大"本来就不需要厘米级。厘米级留给**清单**——每样东西的精确位置照写厘米
 * （见 [RegionView]）。
 */
data class Cell3(val x: Int, val y: Int, val z: Int)

/**
 * 厘米 → 米格，**向下取整**。
 *
 * 取整方向是有语义的：一样东西站在地面上、位置是 0..99 厘米的任意值，它都属于**地面那一格**，
 * 而不是"第 0.37 格"。Kotlin 的 `/` 对负数向零截断（-50 / 100 == 0），而物理上 -50 厘米在
 * 地面**以下**那一格里，所以负数要单独往下取一格。
 */
fun metreCell(cm: Int): Int {
    val quotient = cm / CM_PER_METRE
    val exact = cm % CM_PER_METRE == 0
    return if (!exact && cm < 0) quotient - 1 else quotient
}

/** 一样东西占在哪个米格上。**它的位置是厘米，格是派生量**。 */
fun Entity.cell(): Cell3 = Cell3(metreCell(x), metreCell(y), metreCell(z))

/**
 * **地面在米格上是第几层。**
 *
 * 它由世界自己那份 [io.github.k1midi0.infinitesimal.world.WorldDepth] 算出来（默认地面在 256 个
 * 地形格 = 12800 厘米 = **米格 128**），**不是一个写死的 0**。
 *
 * 俯视图靠这一条分辨"站在地上的东西"与"摆在它们上面的东西"。写死 0 的话，开局的整座酒馆都会变成
 * "摆件"，而症状是俯视图空空如也——一个看起来像渲染坏了、实际是坐标系换了基准的错。
 */
fun World.groundMetreCell(): Int = metreCell(terrain.depth.surfaceCm)

/**
 * 这样东西在米格上**占哪几格**。
 *
 * 有形状时，是形状里每一个格换算过去（局部的格 + 世界位置）；**没有形状时就是它自己那一格**——
 * `shape == null` 的意思是"还没建模"，不是"没有体积"。少了这条兜底，所有还没建模的东西会一起从
 * 空间框里消失，而那正是最难查的一种回归。
 *
 * **这就是"形状接进几何"的全部。** 射线、包围框、俯视图从此看的是这样东西占哪几格，而不是它坐标
 * 上那一个点。一张 2×1×0.8 米的桌子因此占 2×1 格（它只有一层高，因为 0.8 米不满一米）——斜着往
 * 上走的射线会从它上面过去，那是对的，桌子就那么高。
 */
fun Entity.occupiedCells(): Set<Cell3> {
    val shape = shape ?: return setOf(cell())
    return buildSet {
        for (v in shape.solid) add(Cell3(metreCell(x + v.x), metreCell(y + v.y), metreCell(z + v.z)))
        for (p in shape.pointMass) {
            add(Cell3(metreCell(x + p.voxel.x), metreCell(y + p.voxel.y), metreCell(z + p.voxel.z)))
        }
    }
}
