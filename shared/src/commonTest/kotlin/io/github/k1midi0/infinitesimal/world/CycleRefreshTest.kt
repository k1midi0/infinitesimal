package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **到点了：谁被挪过去、谁不动**（§14.44）。
 *
 * 这一条把两句话同时按住的正是刷新那两条"不动"：[Entity.active] 的人留在它自己那趟路上（§3.2.1），
 * 而没有循环的人 `stateAt` 是 `null`——那是"没有答案"，不是"该在 (0,0,0)"。
 */
class CycleRefreshTest {

    private fun world() = World.empty()

    private fun cycle(vararg beats: Beat) = Cycle(beats.toList())

    /**
     * 三个钟点、三个地方：一格就换一个位置。一圈 = 尾 − 首 = 900 分钟。
     *
     * 第一格刻意不落在 0（`Cycle.problems` 会驳回那种）：相位 `[0, 240)` 那一段绕给最后一格。
     */
    private fun routine() = cycle(
        Beat(240, "在后厨", x = 900, y = 320, z = 12800),
        Beat(600, "在柜台后面", x = 1150, y = 120, z = 12800),
        Beat(1140, "去地窖", x = 1300, y = 100, z = 12800),
    )

    private fun character(id: String, x: Int = 0, y: Int = 0, z: Int = 0, cycle: Cycle = Cycle()) =
        Entity(id = id, kind = "角色", label = id, x = x, y = y, z = z, cycle = cycle)

    @Test
    fun itMovesWhoeverIsWalkingTheirCycle() {
        val w = world()
        w.put(character("dwarf", cycle = routine()))
        w.clock = WorldTime(700) // 相位 700 → 「在柜台后面」那一格

        w.refreshCyclesToClock()

        val dwarf = w.byId("dwarf")!!
        assertEquals(1150, dwarf.x)
        assertEquals(120, dwarf.y)
        assertEquals(12800, dwarf.z)
    }

    @Test
    fun itTurnsOverWithTheCycleSoALaterMinuteLandsSomewhereElse() {
        val w = world()
        w.put(character("dwarf", cycle = routine()))

        // 相位 100 早于第一格（240）→ 绕给最后一格（地窖）。
        w.clock = WorldTime(100)
        w.refreshCyclesToClock()
        assertEquals(1300, w.byId("dwarf")!!.x, "相位 0..239 是绕回来的那一截，归最后一格")

        w.clock = WorldTime(400)
        w.refreshCyclesToClock()
        assertEquals(900, w.byId("dwarf")!!.x, "过了 240 就进第一格")

        // 一圈 1140（最后一格的时刻），所以相位每 1140 分钟转一圈；+1140 之后又是同一个相位。
        w.clock = WorldTime(400 + 1140)
        w.refreshCyclesToClock()
        assertEquals(900, w.byId("dwarf")!!.x, "转到第二圈的同一格上")
    }

    @Test
    fun itDoesNotDragBackSomebodyWhoIsOffTheirCycle() {
        val w = world()
        // 被揍了、离开酒馆的矮人：`active != null` 就是"此刻没在走循环"。
        w.put(
            character("dwarf", x = 5000, y = 5000, cycle = routine())
                .copy(active = Active(since = WorldTime(660))),
        )
        w.clock = WorldTime(700)

        w.refreshCyclesToClock()

        assertEquals(5000, w.byId("dwarf")!!.x, "活跃期的人不许被作息拉回去")
    }

    @Test
    fun itLeavesWhoeverHasNoCycleAlone() {
        val w = world()
        w.put(character("bard", x = 77, y = 88)) // 空循环
        w.put(Entity(id = "cup", kind = "器具", label = "杯子", x = 11, y = 22, z = 33, cycle = routine()))
        w.clock = WorldTime(700)

        w.refreshCyclesToClock()

        assertEquals(77, w.byId("bard")!!.x, "没有循环 = 没有答案，不是 (0,0,0)")
        assertEquals(11, w.byId("cup")!!.x, "作息只管角色")
    }

    @Test
    fun runningItTwiceChangesNothingTheSecondTime() {
        val w = world()
        w.put(character("dwarf", cycle = routine()))
        w.clock = WorldTime(700)

        w.refreshCyclesToClock()
        val once = w.byId("dwarf")!!
        w.refreshCyclesToClock()

        assertEquals(once, w.byId("dwarf")!!, "对一遍是幂等的——它每次回合开头都会被调到")
    }
}
