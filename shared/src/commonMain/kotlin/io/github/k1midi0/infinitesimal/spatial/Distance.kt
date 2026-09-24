package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.Entity
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 两样东西之间有多远。**这是"够不够得着"唯一的算法**，写在代码里，不在提示词里。
 *
 * ## 为什么必须有它
 *
 * 在这之前，判定想知道"够不够得着"，只能自己拿清单里的厘米坐标做算术。实测（§14.24）里它写出过
 * `((broom.x - player.x)^2 + ...)^0.5` 这种式子——**式子本身没错**，但它得为此烧掉四千多个思维
 * token（22 秒），而算出来的 128 厘米又正好卡在"一格以内"那条规矩的边界上：三个分量各自是什么、
 * 哪一个才是规矩里说的那个"格"，让模型去猜是不必要的风险。
 *
 * ## 为什么是三个数，不合并成一个"距离"
 *
 * 规矩书说的是"一格以内"——**那是地图格子上的说法**（[Cell3]，一格 = 1 米）。高度差是另一回事：
 * 一样东西在桌上、另一样在手里，它们的格距可能是 0，可你仍然够不着。合成一个标量就等于替规矩做了
 * 决定，而规矩是内容、是会改的。三个数照给，怎么用交给模型。
 */
data class Distance(
    /** 格子上的距离：横向与纵向里大的那个（切比雪夫）。**"一格以内"说的就是这个。** */
    val cells: Int,
    /** 横向差（x），厘米。 */
    val dxCm: Int,
    /** 纵向差（y），厘米。 */
    val dyCm: Int,
    /** 高度差（z），厘米。正数表示 [b] 比 [a] 高。 */
    val dzCm: Int,
) {

    /** 两点直线距离，厘米。**它是几何事实，不是规矩。** */
    val straightCm: Int
        get() = sqrt((dxCm.toDouble() * dxCm + dyCm.toDouble() * dyCm + dzCm.toDouble() * dzCm))
            .roundToInt()

    /** 直线距离折成米（"几个一米"）。规矩以格为单位时，这个数常用来和它对照。 */
    val straightMetres: Double get() = straightCm / 100.0

    /** 高度差是不是 0——"同高"是一种常见情形，值得单独说出来。 */
    val level: Boolean get() = dzCm == 0
}

/**
 * 算 [a] 到 [b] 的距离。
 *
 * 方向是有意义的：`dxCm` 之类都按 `a - b` 算，`dzCm` 为正表示 b 在 a 上面。
 */
fun distanceBetween(a: Entity, b: Entity): Distance {
    val ca = a.cell()
    val cb = b.cell()
    return Distance(
        cells = maxOf(abs(ca.x - cb.x), abs(ca.y - cb.y)),
        dxCm = a.x - b.x,
        dyCm = a.y - b.y,
        dzCm = a.z - b.z,
    )
}
