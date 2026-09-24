package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.CM_PER_METRE
import io.github.k1midi0.infinitesimal.world.Entity

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
