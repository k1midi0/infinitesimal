package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.spatial.RegionBuilder
import io.github.k1midi0.infinitesimal.spatial.RegionView
import io.github.k1midi0.infinitesimal.spatial.cell
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.math.abs
import kotlin.test.Test

/**
 * 判定**实际拿到的那段文字**长什么样。离线、确定性、不需要密钥。
 *
 * 它存在过一次是为了回答一个问题：「差一格在框外」那一格，扫帚到底在不在提示词里。答案是**不在**
 * ——当时那行「框外还有 N 样东西；最近的是「X」」只报最近的一样，而最近的那样是发起者自己所在的
 * 房间。那条结论让那句话被整行删掉了（`spatial/RegionView.kt`）。
 *
 * 现在它是一件日常工具：改完空间框就把三个窗口打出来看一眼，**读到的就是模型读到的**。上一轮采样
 * 里甲乙丁三个场景全是「没勾任何东西」的形状，所以这里也用那个形状。
 *
 * 跑法：`.\gradlew.bat :shared:jvmTest --tests "*FrameProbe*"`
 */
class FrameProbe {

    @Test
    fun frameText() {
        for ((name, extra) in windows()) {
            // **带上地形。** 不带的话网格永远只画得出 `.`，而地形正是这一段里最该被看一眼的东西
            // ——它的判据错一格（"半格土算不算实心"）就会让整个框塌掉，而输出里看不出任何异常。
            val world = World.from(
                WorldSnapshot(ProbeSupport.tavernSeed() + extra, terrain = StartingWorld.ground),
            )
            val actor = world.byId("player")!!
            val region = RegionBuilder.of(world, listOf("player"))

            println()
            println("═".repeat(80))
            println(name)
            println("═".repeat(80))
            println(RegionView.render(world, region, "player"))
            println()
            val outside = world.all().filter { !region.covers(it) && it.id != actor.id }
            println(
                "（这一片之外还有 ${outside.size} 样东西：" +
                    outside.joinToString("、") { "${it.label} ${cheb(actor, it)} 格" } +
                    "——判定看不到它们，要看就得调 widen_view）",
            )
        }
    }

    /** 与 `JudgeWidenProbe` 的乙、丁、丙三个场景同形，读数才能横向比。 */
    private fun windows(): List<Pair<String, List<Entity>>> = listOf(
        "乙 差一格在框外：扫帚在 (1600,300)，玩家在 (1200,300)" to
            listOf(Entity("broom", "器具", "靠在墙上的扫帚", 1600, 300, ProbeSupport.GROUND)),
        "丁 八格开外：井与杯子都在 (2000,800)，玩家在 (1200,300)" to listOf(
            Entity("town_well", "地点", "街心的水井", 2000, 800, ProbeSupport.GROUND, attributes = mapOf("井水" to "冰凉")),
            Entity("well_cup", "器具", "粗陶杯", 2000, 800, ProbeSupport.GROUND + 100),
        ),
        "丙 对照：手边的酒杯" to emptyList(),
    )

    /**
     * 切比雪夫距离，**按米格算**。
     *
     * 厘米坐标下直接减出来的数字是厘米，而这个探针要的是"几格"——那正是 [Cell3] 存在的理由：
     * 米格是厘米的一种视图，不是另一份数据。
     */
    private fun cheb(a: Entity, b: Entity): Int =
        maxOf(abs(a.cell().x - b.cell().x), abs(a.cell().y - b.cell().y), abs(a.cell().z - b.cell().z))
}
