package io.github.k1midi0.infinitesimal.world

/**
 * 手写形状的几个常用骨架。
 *
 * 它们**不是产品的一部分**——形状的正经来源是建模（附录 A.10：模型画一张分层网格，代码解析）。
 * 这里存在的理由只有一个：**在建模还没有的时候，让"形状接进几何"这条路先能跑通**。开局那几个东西
 * 需要形状，而手画一张 40×20×16 的 ASCII 网格不现实。
 *
 * 所以它们的用户是**内容与探针**（`StartingWorld`、spike）。等建模可用了，这些调用点会一个一个换成
 * 真的建模结果，而这个文件会缩到只剩测试在用。
 */
object Shapes {

    /**
     * 一个实心长方体：**从 (0,0,0) 起，给定厘米尺寸**。
     *
     * 尺寸必须是 [VOXEL_CM] 的整数倍。`box(200, 100, 80)` 得到的形状**占 200×100×80 厘米**，最后
     * 一个格在 (195, 95, 75)——因为一格 5 厘米，`0..195` 正好是 40 格。
     */
    fun box(widthCm: Int, depthCm: Int, heightCm: Int): VoxelShape =
        VoxelShape(solid = fill(0, 0, 0, widthCm, depthCm, heightCm))

    /**
     * 一张桌面加四条腿。
     *
     * [legCm] 是腿的截面边长。腿摆在四个角上，桌面 [heightCm] - [topCm] 那么厚——这形状一眼看得出
     * 是桌子，正是"体素是它的形状语言"那句的意思。
     */
    fun table(widthCm: Int, depthCm: Int, heightCm: Int, topCm: Int = 5, legCm: Int = 10): VoxelShape {
        val legs = buildList {
            for (x in listOf(0, widthCm - legCm)) {
                for (y in listOf(0, depthCm - legCm)) {
                    addAll(fill(x, y, 0, legCm, legCm, heightCm - topCm))
                }
            }
        }
        return VoxelShape(solid = (legs + fill(0, 0, heightCm - topCm, widthCm, depthCm, topCm)).distinct())
    }

    /** 只有外壳的盒子（箱、桶、房间）：里面是空的，能装东西。 */
    fun shell(widthCm: Int, depthCm: Int, heightCm: Int, wallCm: Int = VOXEL_CM): VoxelShape {
        val inside = fill(0, 0, 0, widthCm, depthCm, heightCm)
        val cavity = fill(wallCm, wallCm, wallCm, widthCm - wallCm * 2, depthCm - wallCm * 2, heightCm - wallCm)
        val hollow = inside.toHashSet().apply { removeAll(cavity.toSet()) }
        return VoxelShape(solid = hollow.toList())
    }

    /** 一根竖着的柱子（门框、柱子、树干）。 */
    fun post(sizeCm: Int, heightCm: Int): VoxelShape = box(sizeCm, sizeCm, heightCm)

    private fun fill(x0: Int, y0: Int, z0: Int, w: Int, d: Int, h: Int): List<Voxel> {
        require(w > 0 && d > 0 && h > 0) { "形状的尺寸必须是正数，实际 $w×$d×$h" }
        require(w % VOXEL_CM == 0 && d % VOXEL_CM == 0 && h % VOXEL_CM == 0) {
            "形状的尺寸必须是 $VOXEL_CM 厘米的整数倍，实际 $w×$d×$h"
        }
        val out = mutableListOf<Voxel>()
        var z = z0
        while (z < z0 + h) {
            var y = y0
            while (y < y0 + d) {
                var x = x0
                while (x < x0 + w) {
                    out += Voxel(x, y, z)
                    x += VOXEL_CM
                }
                y += VOXEL_CM
            }
            z += VOXEL_CM
        }
        return out
    }
}
