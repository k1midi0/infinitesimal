package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.spatial.Direction
import io.github.k1midi0.infinitesimal.spatial.RegionBuilder
import io.github.k1midi0.infinitesimal.spatial.RegionView
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.math.abs
import kotlin.test.Test

/**
 * 判定**实际拿到的那段文字**长什么样。离线、确定性、不需要密钥。
 *
 * ## 它要回答的两个问题
 *
 * 1. 「差一格在框外」那一格，扫帚到底在不在框外那句话里。`JudgeWidenProbe` 量出 2/5 次的驳回理由
 *    说「没有扫帚」——如果那句话根本没说扫帚，那是数据没给到；说了而模型不用，那是别的问题。
 *    这两种原因的修法完全不同，所以先把这段话打出来。
 * 2. 「框外只说一句」改成别的形状之后会长成什么样。给 `farLine` 挑形状时得有真字符串看，不能凭
 *    想象。
 *
 * 跑法：`.\gradlew.bat :shared:jvmTest --tests "*FrameProbe*"`
 */
class FrameProbe {

    @Test
    fun frameText() {
        for ((name, extra) in windows()) {
            val world = World.from(WorldSnapshot(ProbeSupport.tavernSeed() + extra))
            val actor = world.byId("player")!!
            val region = RegionBuilder.of(world, listOf("player"))

            println()
            println("═".repeat(80))
            println(name)
            println("═".repeat(80))
            println("框：x ${region.minX}..${region.maxX}｜y ${region.minY}..${region.maxY}｜层 ${region.minZ}..${region.maxZ}")
            println()
            println(RegionView.render(world, region, "player"))
            println()
            println("──── 框外的东西（现在只说一句，下面是原料） ────")
            val outside = world.all().filter { !region.covers(it) && it.id != actor.id }
            if (outside.isEmpty()) {
                println("（框外什么都没有）")
            } else {
                for ((d, mine) in outside.groupBy { directionOf(actor, it = it) }) {
                    println("  ${d.label}：" + mine.joinToString("、") { "${it.label} ${cheb(actor, it)} 格" })
                }
                println()
                println("  【形状甲】按方向聚成几行，列全部名字：")
                for (d in Direction.ALL) {
                    val mine = outside.filter { directionOf(actor, it = it) == d }
                    if (mine.isEmpty()) continue
                    println("    ${d.label}：" + mine.sortedBy { cheb(actor, it) }
                        .joinToString("、") { it.label })
                }
                println()
                println("  【形状乙】先按方向分组，每个方向只取最近的：")
                for (d in Direction.ALL) {
                    val nearest = outside.filter { directionOf(actor, it = it) == d }
                        .minByOrNull { cheb(actor, it) } ?: continue
                    println("    ${d.label} ${cheb(actor, nearest)} 格：「${nearest.label}」")
                }
            }
        }
    }

    private fun directionOf(actor: Entity, it: Entity): Direction =
        Direction.of(it.x - actor.x, it.y - actor.y, it.z - actor.z)

    private fun cheb(a: Entity, b: Entity): Int =
        maxOf(abs(b.x - a.x), abs(b.y - a.y), abs(b.z - a.z))

    private fun windows(): List<Pair<String, List<Entity>>> = listOf(
        "乙 差一格在框外：扫帚在 (16,3)，玩家在 (12,3)" to
            listOf(Entity("broom", "器具", "靠在墙上的扫帚", 16, 3, 0)),
        "丁 八格开外：井与杯子都在 (20,8)，玩家在 (12,3)" to listOf(
            Entity("town_well", "地点", "街心的水井", 20, 8, 0, attributes = mapOf("井水" to "冰凉")),
            Entity("well_cup", "器具", "粗陶杯", 20, 8, 1, parent = "town_well"),
        ),
        "丙 对照：手边的酒杯" to emptyList(),
    )
}
