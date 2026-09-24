package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.RuleEntry
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldDepth
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `calc` 的求值器（§12 第 12 项）。
 *
 * 为什么值得写一整个测试类：**它是这套设计里唯一一处"数字必须对"的地方**。别处的错都会以某种方式
 * 露出来（协议失败、驳回得离谱、文字和地图打架），只有这里算错了是一点痕迹都没有的——下游那句话
 * 照样通顺。所以边界要穷尽，尤其是**"不认识的名字必须是错误"**那一条。
 */
class CalcTest {

    private val vars = mapOf(
        "player.力量" to 3.0,
        "oak_table.硬度" to 6.0,
        "世界.每息" to 0.5,
    )

    private fun value(expression: String, variables: Map<String, Double> = vars): Double =
        when (val result = Calc.evaluate(expression, variables)) {
            is Calc.Result.Value -> result.value
            is Calc.Result.Failed -> throw AssertionError("本该算出来，却报了：${result.problem}")
        }

    private fun problem(expression: String, variables: Map<String, Double> = vars): String =
        when (val result = Calc.evaluate(expression, variables)) {
            is Calc.Result.Value -> throw AssertionError("本该算不出来，却得到 ${result.value}")
            is Calc.Result.Failed -> result.problem
        }

    @Test
    fun theFourOperationsAndPrecedenceHold() {
        assertEquals(7.0, value("3 + 4"))
        assertEquals(-1.0, value("3 - 4"))
        assertEquals(12.0, value("3 * 4"))
        assertEquals(2.0, value("8 / 4"))
        assertEquals(14.0, value("2 + 3 * 4"), "乘法该先算")
        assertEquals(20.0, value("(2 + 3) * 4"))
        assertEquals(1.0, value("((1))"))
        assertEquals(-6.0, value("-2 * 3"))
        assertEquals(2.0, value("5 + -3"))
    }

    @Test
    fun chineseVariableNamesWorkAsWrittenInThePrompt() {
        assertEquals(3.0, value("player.力量"))
        assertEquals(0.0, value("(player.力量 * 2 - oak_table.硬度) / 3"))
        assertEquals(6.5, value("player.力量 + 3.5"))
    }

    @Test
    fun thePowerOperatorWorksBecauseTheModelWritesIt() {
        // **这一段是实测逼出来的**（§14.19）：模型要算距离，自己写了
        // `((broom.x - player.x)^2 + (broom.y - player.y)^2)^0.5`，而当时求值器不认 `^`，
        // 那几次调用全废——它退回去写成 `(dx)*(dx) + ...` 才算出结果。
        assertEquals(8.0, value("2 ^ 3"))
        assertEquals(3.0, value("9 ^ 0.5"))
        assertEquals(4.0, value("9 ^ 0.5 + broom_x", mapOf("broom_x" to 1.0)))
        // 右结合：2^(3^2) = 2^9，不是 (2^3)^2 = 64。
        assertEquals(512.0, value("2 ^ 3 ^ 2"))

        val distance = mapOf("broom.x" to 14.0, "broom.y" to 3.0, "player.x" to 12.0, "player.y" to 3.0)
        assertEquals(
            2.0,
            value("((broom.x - player.x)^2 + (broom.y - player.y)^2)^0.5", distance),
            "走这条路才是模型真的想写的那一行",
        )
    }

    @Test
    fun aMisspelledNameIsAnErrorNotZero() {
        // **这是整套东西里最要紧的一条。** 把 `oak_table` 打错一个字母当成 0，算出来的是一个
        // 看着完全合理的错答案。
        val said = problem("oak_tabel.硬度 + 1")
        assertTrue(said.contains("oak_tabel.硬度"), said)
        assertTrue(said.contains("oak_table.硬度"), "要把它能用的名字列出来：$said")
    }

    @Test
    fun nonsenseIsReportedWithTheSpotItStopped() {
        assertTrue(problem("3 +").isNotEmpty())
        assertTrue(problem("(3 + 4").contains("括号"))
        assertTrue(problem("3 / 0").contains("除以零"))
        assertTrue(problem("3 $ 4").contains("$"))
        assertTrue(problem("").contains("空"))
        assertTrue(problem("3 + 4)").contains("读不下去"))
        assertTrue(problem("1.2.3 + 1").isNotEmpty())
    }

    @Test
    fun integersPrintWithoutADecimalPoint() {
        assertEquals("3", Calc.format(3.0))
        assertEquals("-2", Calc.format(-2.0))
        assertEquals("1.5", Calc.format(1.5))
        assertEquals("1.25", Calc.format(1.25))
    }

    @Test
    fun onlyNumbersBecomeVariables() {
        val entities = StartingWorld.tavern().map {
            if (it.id == "oak_table") {
                it.copy(rulebook = Rulebook(listOf(RuleEntry("这是一张橡木桌。", values = mapOf("硬度" to "6", "材质" to "橡木")))))
            } else {
                it
            }
        }
        val world = World.from(WorldSnapshot(entities, Rulebook(listOf(RuleEntry("火会烧起来。", values = mapOf("每息" to "0.5"))))))

        val variables = Variables.of(world, listOf("player", "oak_table"))

        assertEquals(6.0, variables["oak_table.硬度"])
        assertEquals(0.5, variables["世界.每息"])
        // 「材质=橡木」不是数，所以它根本不出现——列出来只会让人以为它能算。
        assertTrue(variables.keys.none { it.contains("材质") }, variables.keys.toString())
        // 「两手=空着」是属性，但不是数，同样不出现。
        assertTrue(variables.keys.none { it == "player.两手" }, variables.keys.toString())
        // **坐标也在。** 实测（§14.19）里模型第一件想算的事就是"隔多远"——它写的是
        // `broom.x - player.x`；当时变量表里没有坐标，那一次白调了。**单位是厘米**（附录 A.2），
        // 与它要算的那个差值同单位，所以减出来直接就是厘米，不必再换算。
        assertEquals(1200.0, variables["oak_table.x"])
        assertEquals(400.0, variables["oak_table.y"])
        // **z 是地面高度，不是 0**（2026-09-26 的迁移）：世界的地面在第 256 个地形格 = 12800 厘米，
        // 开局的酒馆站在地上。这里写 `WorldDepth().surfaceCm` 而不是 12800，是为了让这条断言跟着那条
        // 配置走——否则改一次地面高度就要回来再找一遍。
        assertEquals(WorldDepth().surfaceCm.toDouble(), variables["oak_table.z"])
        assertEquals(1200.0, variables["player.x"])
        assertEquals(300.0, variables["player.y"])
    }

    @Test
    fun aThingThatIsNotInTheWorldContributesNothing() {
        val world = World.from(WorldSnapshot(listOf(Entity("rock", "杂物", "石头", 0, 0, 0))))
        assertTrue(Variables.of(world, listOf("nowhere")).isEmpty())
        assertIs<Calc.Result.Failed>(Calc.evaluate("nowhere.x", Variables.of(world, listOf("nowhere"))))
    }
}
