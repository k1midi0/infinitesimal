package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.RegionBuilder
import io.github.k1midi0.infinitesimal.spatial.RegionView
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test

/**
 * 把空间框**真跑出来看**。
 *
 * 这个探针不碰模型，所以它不需要密钥、随时能跑——但它的价值恰恰在于此：这个算法的对错全在"输出
 * 长什么样"上，而那是盯代码看不出来的。
 */
class RegionProbe {

    private fun tavern() = World.from(WorldSnapshot(StartingWorld.tavern()))

    private fun show(title: String, world: World, centers: List<String>, widenings: Int = 0) {
        val region = RegionBuilder.of(world, centers, widenings)
        println()
        println("═".repeat(96))
        println("▸ $title     （中心：${centers.joinToString("、")}）")
        println("═".repeat(96))
        println(RegionView.render(world, region, actorId = "player"))
        println("─".repeat(96))
        println(
            "$region｜参考点 ${region.points.size} 个（打空 ${region.points.count { !it.hit }} 个）" +
                "｜被射线打中的 ${region.hitIds.size} 样：${region.hitIds.sorted().take(8).joinToString("、")}" +
                if (region.hitIds.size > 8) "…" else "",
        )
    }

    @Test
    fun whatTheJudgeWouldSee() {
        val world = tavern()

        // ① 「我环顾四周」——不指向任何东西，中心只有发起者自己。
        show("① 环顾四周（中心只有玩家）", world, listOf("player"))

        // ② 「我拿起桌上那杯麦酒」——中心是玩家 + 酒杯。**这才是坐标参照系该有的样子。**
        show("② 玩家 + 桌上的酒杯", world, listOf("player", "wine_cup"))

        // ③ 判定说"不够"。
        show("③ ②扩大一次", world, listOf("player", "wine_cup"), widenings = 1)
        show("④ ②扩大两次", world, listOf("player", "wine_cup"), widenings = 2)
    }

    @Test
    fun aTargetThreeStreetsAway() {
        // 「三条街外那家店里的杯子」：框必须把这一段距离**摆出来**，判定才能看着距离说够不着，
        // 而不是因为看不见那个东西而报"没这东西"。
        val world = World.from(
            WorldSnapshot(
                StartingWorld.tavern() + listOf(
                    Entity(id = "far_cup", kind = "器具", label = "远处的酒杯", x = 40, y = 30, z = 0),
                ),
            ),
        )
        show("⑤ 目标在 (40, 30)", world, listOf("player", "far_cup"))
        show("⑥ ⑤扩大一次", world, listOf("player", "far_cup"), widenings = 1)
    }

    @Test
    fun obstaclesActuallyBite() {
        // 一条**跨层的墙**摆在东边。用来对照 [io.github.k1midi0.infinitesimal.spatial.RegionTest]
        // 里那两条：跨层的墙收得住框，单层的家具收不住。
        val wall = (0..8).flatMap { y -> (-3..3).map { z -> Entity("w_${y}_$z", "墙", "墙", 13, y, z) } }
        val world = World.from(WorldSnapshot(StartingWorld.tavern() + wall))
        show("⑦ 东边一堵跨层的墙", world, listOf("player", "wine_cup"))
    }
}
