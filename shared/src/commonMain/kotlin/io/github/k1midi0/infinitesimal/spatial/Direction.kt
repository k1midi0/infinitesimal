package io.github.k1midi0.infinitesimal.spatial

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 十个方向扇区。
 *
 * ## 词表必须和 [RegionView] 说的一致
 *
 * 八个罗盘方向 + 上/下。这一套词不是新造的：框外那句话（「最近的是「街心的水井」，在**东南** 8 格外」）
 * 用的就是它们。模型必须在提示里读到哪个词，就能用同一个词要求往那边看——两张词表不重合，等于让它
 * 翻译一次，而它翻译错的时候没人会发现。
 *
 * ## 划分是精确的
 *
 * - 八个罗盘扇区：水平方位各 45°，仰角 ±45°
 * - 上/下两个极冠：仰角绝对值大于 45°，半角 45°
 *
 * 十个扇区的立体角加起来正好是 `4π`，不重不漏（有测试钉住）。
 *
 * ## 条数按 k² 长
 *
 * 每个扇区在第 k 档的射线数是 `round(6Ωk²/π)`。这条式子不是挑的：它保证**每一条射线摊到的球面面积
 * 恒定**（约 4.71 格²，相邻间距 2.17 格），也就是"随距离增加射线数量、保持空间密度大体相同"。
 * 定向扩大只动一个扇区的 k，所以只多铺那一个扇区的那几条。
 */
enum class Direction(val label: String, val azimuth: Double?) {
    EAST("东", 0.0),
    SOUTHEAST("东南", PI / 4),
    SOUTH("南", PI / 2),
    SOUTHWEST("西南", 3 * PI / 4),
    WEST("西", PI),
    NORTHWEST("西北", 5 * PI / 4),
    NORTH("北", 3 * PI / 2),
    NORTHEAST("东北", 7 * PI / 4),
    UP("上", null),
    DOWN("下", null),
    ;

    /** 这个扇区一档的立体角。罗盘扇区与极冠不同，所以射线数也不同。 */
    val solidAngle: Double
        get() = if (azimuth == null) CAP_SOLID_ANGLE else WEDGE_SOLID_ANGLE

    /** 第 k 档要几条射线。**条数随 k² 长**，这正是空间密度守恒的来源。 */
    fun rayCount(k: Int): Int = (6.0 * solidAngle * k * k / PI).roundToInt().coerceAtLeast(1)

    /**
     * 第 k 档的方向：在这个扇区里均匀铺开的 [rayCount] 个单位向量。
     *
     * 楔形用两条低差异序列（`1/φ` 与 `1/φ²`）铺方位角与仰角，极冠用斐波那契螺旋。都是确定性的
     * ——同一个 k 每次给出同一批方向，所以这一层可以像别的纯函数一样被测试钉死。
     */
    fun directions(k: Int): List<Triple<Double, Double, Double>> {
        val n = rayCount(k)
        val az = azimuth
        return if (az != null) {
            val halfWedge = WEDGE / 2
            val sinLimit = sin(ELEVATION)
            List(n) { i ->
                val phi = az - halfWedge + WEDGE * frac(0.5 + i * PHI_INV)
                val sinT = -sinLimit + 2 * sinLimit * frac(0.5 + i * PHI_INV_SQ)
                val cosT = sqrt((1.0 - sinT * sinT).coerceAtLeast(0.0))
                Triple(cosT * cos(phi), cosT * sin(phi), sinT)
            }
        } else {
            val sign = if (this == UP) 1.0 else -1.0
            val cosLimit = cos(ELEVATION)
            List(n) { i ->
                val cosT = cosLimit + (1.0 - cosLimit) * (i + 0.5) / n
                val sinT = sqrt((1.0 - cosT * cosT).coerceAtLeast(0.0))
                val phi = GOLDEN_ANGLE * i
                Triple(sinT * cos(phi), sinT * sin(phi), sign * cosT)
            }
        }
    }

    companion object {

        /** 仰角半宽，同时也是极冠的半角：45°。 */
        private const val ELEVATION: Double = PI / 4

        /** 每个罗盘扇区的方位宽度：45°。 */
        private const val WEDGE: Double = PI / 4

        private val WEDGE_SOLID_ANGLE: Double = WEDGE * 2 * sin(ELEVATION)
        private val CAP_SOLID_ANGLE: Double = 2 * PI * (1 - cos(ELEVATION))

        /** `1/φ`、`1/φ²` 与黄金角 `2π/φ²`，低差异序列的三个步长。 */
        private const val PHI_INV: Double = 0.6180339887498949
        private const val PHI_INV_SQ: Double = 0.3819660112501051
        private const val GOLDEN_ANGLE: Double = 2.399963229728653

        /** 按方位角递增排列的八个罗盘扇区——[of] 的取模索引直接对到这张表上。 */
        private val COMPASS: List<Direction> =
            listOf(EAST, SOUTHEAST, SOUTH, SOUTHWEST, WEST, NORTHWEST, NORTH, NORTHEAST)

        /** 十个扇区，顺序固定。档位数组（`IntArray`）就是按这个顺序排的。 */
        val ALL: List<Direction> = entries.toList()

        /** 模型给的那个词。**不做模糊匹配**：认不出来就是参数错，该退回让模型重说。 */
        fun byLabel(label: String): Direction? = ALL.firstOrNull { it.label == label.trim() }

        /**
         * 一个方向向量落在哪个扇区里。
         *
         * 先看仰角：水平分量相对竖直分量太小就归极冠；否则按方位角落到哪个 45° 楔形里。
         * 这条回答必须和 [directions] 的采样边界一致，否则模型会读到「在东南」，要求看东南，
         * 而东南那一档的射线根本不在那个方向上。
         */
        fun of(dx: Int, dy: Int, dz: Int): Direction {
            val horizontal = sqrt((dx.toDouble() * dx) + (dy.toDouble() * dy))
            if (horizontal == 0.0) return if (dz >= 0) UP else DOWN
            if (atan2(abs(dz).toDouble(), horizontal) > ELEVATION) return if (dz > 0) UP else DOWN

            var deg = atan2(dy.toDouble(), dx.toDouble())
            if (deg < 0) deg += 2 * PI
            val index = floor((deg + WEDGE / 2) / WEDGE).toInt() % COMPASS.size
            return COMPASS[index]
        }

        private fun frac(x: Double): Double = x - floor(x)
    }
}
