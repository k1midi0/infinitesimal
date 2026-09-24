package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.TerrainCell
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.terrainCellOf

/**
 * 地形与米格之间的两条换算（附录 A.15）。
 *
 * **它们问的不是同一个问题，所以是两个函数。** 把两者合成一个"这个米格的地形是什么"，两边都会
 * 得到错的答案，而错法很隐蔽——下面两条注释说的就是各自的错法。
 *
 * 一个地形格是 50 厘米、一个米格是 100 厘米，所以**一格米格覆盖 2×2×2 个地形格**，换算只有乘二。
 */

/** 一格米格覆盖的那 8 个地形格。顺序固定（x、y、z 各先 0 后 1），所以"最上面那个"是确定的。 */
private fun subCells(cell: Cell3): List<TerrainCell> = buildList {
    for (dx in 0..1) {
        for (dy in 0..1) {
            for (dz in 0..1) {
                add(TerrainCell(cell.x * 2 + dx, cell.y * 2 + dy, cell.z * 2 + dz))
            }
        }
    }
}

/**
 * 这一格米格**是不是整格都被地形填满**。填满就挡射线（[RegionBuilder]），返回最上面那个子格的材质；
 * 不实心返回 null。
 *
 * 判据是「**全**」，不是「有任何一个」——而差别正好落在脚下那一层。世界的地面铺到地形第 256 格
 * （A.16），米格 128 覆盖地形 256..257：**下半格是土、上半格是空的**。按"有就算"的话，发起者自己
 * 站的那一格就是实心的，于是**每一条水平射线在 t=1 就撞上地面**，框塌成 ±1 格——症状是"这个世界
 * 明明很开阔，框却只有三格"，而那种错看起来像是算法坏了，其实是判据选错了。
 */
internal fun World.solidTerrainAt(cell: Cell3): String? {
    var material: String? = null
    var topZ = Int.MIN_VALUE
    for (sub in subCells(cell)) {
        val here = terrain.materialIdAt(sub) ?: return null
        if (sub.z > topZ) {
            topZ = sub.z
            material = here
        }
    }
    return material
}

/**
 * 网格那一格里该画的地形：这一列在 [cm] 及以下**最高的那一层**的材质。
 *
 * 它与 [solidTerrainAt] 问的不是一回事：那个问"整格实心吗"（挡不挡射线），这个问"脚下踩的是什么"
 * （画什么字）。用 [solidTerrainAt] 来画网格的话，**发起者站的那一层永远是空白**——它是半格土半格
 * 空气，不实心——于是整张地面图一个字都没有。
 *
 * 一格米格覆盖两列地形（`2x`、`2y`），这里只取**靠前的**那一列。一个米格里两列材质不同是可能的
 * （一格土一格石），而网格只有两个字符宽，说不清这件事；取靠前那一列是**确定的**，不含糊。
 */
internal fun World.groundMaterialAt(cell: Cell, cm: Int): String? {
    val z = terrainCellOf(cm)
    // `runsAt` 按 z 升序，所以"最后一个 from ≤ z 的段"就是 z 及以下最高的那一段。落在空档里时
    // 它给出下面那一层——那是对的：空档之上什么都没有。
    return terrain.runsAt(cell.x * 2, cell.y * 2).lastOrNull { it.from <= z }?.material
}
