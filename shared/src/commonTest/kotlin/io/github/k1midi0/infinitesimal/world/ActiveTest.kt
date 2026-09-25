package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **活跃与回归的那条线**（§3.2.1、§12 第 46 项）。
 *
 * 这一刀唯一的"判据"就是 [Active.canArrive]，而它只比**时刻**、不比那句状态。那个选择值得被钉住：
 * 一个写测试的人很容易顺手改成比 `state`，而改完之后**两边都会错**——该放行的驳回（两句不同的话指向
 * 同一件事），该驳回的放行（同一句话出现在两格里）。所以这里三条断言缺一不可。
 */
class ActiveTest {

    private fun cycle() = Cycle(
        listOf(
            Beat(atMinute = 9 * 60, state = "招呼客人"),
            Beat(atMinute = 14 * 60, state = "招呼客人"), // **同一句话，两格**——这是有意的
            Beat(atMinute = 23 * 60, state = "打烊"),
        ),
    ).sorted()

    private fun at(hour: Int, minute: Int = 0) = WorldTime.of(1, hour, minute)

    @Test
    fun beatAtGivesTheWholeBeatNotJustItsSentence() {
        val c = cycle()

        assertEquals("招呼客人", c.beatAt(at(10))?.state)
        assertEquals(9 * 60, c.beatAt(at(10))?.atMinute, "整格都要给得出来——回归判据读的是时刻")
        // 跨夜那一格：此刻早于当天第一格时取当天最后一格（§3.2.5 早就写下那条读法）。
        assertEquals("打烊", c.beatAt(at(3))?.state)
        assertEquals("打烊", c.stateAt(at(3)), "stateAt 还是那句话，只是它经过 beatAt 拿到")
    }

    @Test
    fun noCycleMeansNoBeatToReturnTo() {
        val c = Cycle()
        assertNull(c.beatAt(at(10)))
        assertNull(c.stateAt(at(10)))

        // **没有循环时永远回不去。** 「回一个不存在的地方」不该被接受——那不是严格，那是它在说一件
        // 世界不支持的事。
        assertFalse(Active(since = at(18), target = Beat(9 * 60, "招呼客人")).canArrive(c, at(10)))
    }

    @Test
    fun noTargetMeansNothingToConfirm() {
        // 还没定回哪一格时，谁都不能替它确认"到了"。这一条堵的是"活跃了但从来没设过目标"那个中间态
        // ——它会表现为它永远发 arrive、永远被驳回。
        assertFalse(Active(since = at(18), target = null).canArrive(cycle(), at(10)))
    }

    @Test
    fun itCanArriveOnlyOnTheBeatItNamed() {
        val c = cycle()
        val active = Active(since = at(18), target = Beat(9 * 60, "招呼客人"))

        assertTrue(active.canArrive(c, at(10)), "10 点落在它要回的那一格上")
        assertFalse(active.canArrive(c, at(15)), "15 点落在另一格上")
        assertFalse(active.canArrive(c, at(3)), "凌晨落在打烊那一格上")
    }

    @Test
    fun thesameSentenceInTwoBeatsIsStillTwoBeats() {
        // **这一条是那个选择的全部理由。** 9 点与 14 点那两句状态**一模一样**，而它们不是同一格：
        // 一个要回 9 点那一格的矮人，14 点说"我回去了"是**还没到**——它要等的是明天早上 9 点。
        // 谁要是把判据改成比 `state`，这一条就是唯一会拦住他的东西。
        val c = cycle()
        val wantsMorning = Active(since = at(18), target = Beat(9 * 60, "招呼客人"))

        assertFalse(
            wantsMorning.canArrive(c, at(14)),
            "同一句话出现在两格里，而它要回的是 9 点那一格",
        )
        assertTrue(wantsMorning.canArrive(c, at(9)))
    }
}
