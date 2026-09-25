package io.github.k1midi0.infinitesimal.generate

import io.github.k1midi0.infinitesimal.world.ChunkKey

/**
 * **导演点名要放的一样东西**（§14.33）。
 *
 * 三样：放什么（一句话，走预设库→现造那条路）、放在哪一块、以及它自己的那份渲染背景。
 *
 * **没有厘米坐标。** 具体放在这一块里的哪儿，是生成器在铺这块地的时候定的——那正是「油灯坐标是编的」
 * 那一课：导演手里没有地形剖面，给它一个厘米数字只会得到一个编出来的数字。它给的是**哪一块地**，
 * 而那一块地是它刚在区块地图上看见的。
 */
data class Placement(
    val chunk: ChunkKey,
    /** 放什么，一句话。走 `find_preset` / `byBrief` / 现造那条路。 */
    val brief: String,
    /**
     * 这样东西自己的**渲染背景**（A.13）：它长成什么样、什么风格。
     *
     * 导演写这一句是它的本职——「酒馆门前那两棵」与「皇宫里那两棵」的差别全在这里，而生成器看不见
     * 这个差别。
     */
    val background: String = "",
)
