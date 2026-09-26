package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **活跃与回归的那条线**（§3.2.1、§12 第 46 项、§14.44）。
 *
 * 这一刀的判据 [Active.canArrive] 比**两样东西**，而两样都不能被顺手改掉：
 *
 *  - **时刻**，不比那句状态。一个写测试的人很容易改成比 `state`，而改完之后**两边都会错**——该放行的
 *    驳回（两句不同的话指向同一件事），该驳回的放行（同一句话出现在两格里）。下面
 *    [theSameSentenceInTwoBeatsIsStillTwoBeats] 就是拦住他的那一条。
 *  - **地点**（§14.44 加的）。循环里每一格带坐标之后代码才查得了这一条：没有它，一个还在三条街以外的
 *    矮人只要等到对的时刻就能宣称"我回去了"。[itMustBeStandingThere] 钉住它。
 */
class ActiveTest {

    private fun cycle() = Cycle(
        listOf(
            Beat(atMinute = 9 * 60, state = "招呼客人", x = 1200, y = 200, z = 0),
            Beat(atMinute = 14 * 60, state = "招呼客人", x = 1000, y = 200, z = 0), // 同一句话，两格
            Beat(atMinute = 23 * 60, state = "打烊", x = 1300, y = 100, z = 0),
        ),
    ).sorted()

    private fun at(hour: Int, minute: Int = 0) = WorldTime.of(1, hour, minute)

    /** 站在哪。**默认站在第一格的地点上**——于是"时刻对不对"那些断言不会被地点那一栏搅进来。 */
    private fun standing(x: Int = 1200, y: Int = 200, z: Int = 0) =
        Entity(id = "dwarf", kind = "角色", label = "酒馆里的矮人", x = x, y = y, z = z)

    @Test
    fun beatAtGivesTheWholeBeatNotJustItsSentence() {
        val c = cycle()

        assertEquals("招呼客人", c.beatAt(at(10))?.state)
        assertEquals(9 * 60, c.beatAt(at(10))?.atMinute, "整格都要给得出来——回归判据读的是时刻")
        assertEquals(1200, c.beatAt(at(10))?.x, "坐标也要给得出来——回归判据还读地点")
        // 转圈那一格：此刻早于当天第一格时取最后一格（那条读法在周期语义下仍然是它）。
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
        assertFalse(Active(since = at(18), target = Beat(9 * 60, "招呼客人")).canArrive(c, at(10), standing()))
    }

    @Test
    fun noTargetMeansNothingToConfirm() {
        // 还没定回哪一格时，谁都不能替它确认"到了"。这一条堵的是"活跃了但从来没设过目标"那个中间态
        // ——它会表现为它永远发 arrive、永远被驳回。
        assertFalse(Active(since = at(18), target = null).canArrive(cycle(), at(10), standing()))
    }

    @Test
    fun itCanArriveOnlyOnTheBeatItNamed() {
        val c = cycle()
        val active = Active(since = at(18), target = Beat(9 * 60, "招呼客人", 1200, 200, 0))

        assertTrue(active.canArrive(c, at(10), standing()), "10 点落在它要回的那一格上，人也站在那儿")
        assertFalse(active.canArrive(c, at(15), standing()), "15 点落在另一格上")
        assertFalse(active.canArrive(c, at(3), standing()), "凌晨落在打烊那一格上")
    }

    @Test
    fun theSameSentenceInTwoBeatsIsStillTwoBeats() {
        // **这一条是那个选择的全部理由。** 9 点与 14 点那两句状态**一模一样**，而它们不是同一格：
        // 一个要回 9 点那一格的矮人，14 点说"我回去了"是**还没到**——它要等的是下一圈 9 点。
        // 谁要是把判据改成比 `state`，这一条就是唯一会拦住他的东西。
        val c = cycle()
        val wantsMorning = Active(since = at(18), target = Beat(9 * 60, "招呼客人", 1200, 200, 0))

        assertFalse(
            wantsMorning.canArrive(c, at(14), standing(x = 1000)),
            "同一句话出现在两格里，而它要回的是 9 点那一格",
        )
        assertTrue(wantsMorning.canArrive(c, at(9), standing()))
    }

    @Test
    fun itMustBeStandingThere() {
        // §14.44：时刻对了、人还在三条街以外——那不是一次回归。容差是两米，所以"差一点点"仍然算到
        // 了（它自己看不到自己的坐标，要求一个点等于永远不满足）。
        val c = cycle()
        val active = Active(since = at(18), target = Beat(9 * 60, "招呼客人", 1200, 200, 0))

        assertTrue(active.canArrive(c, at(10), standing(x = 1200 + ARRIVE_TOLERANCE_CM, y = 200)), "容差之内算到了")
        assertTrue(active.canArrive(c, at(10), standing(x = 1200 - ARRIVE_TOLERANCE_CM, y = 200)), "两边都算")
        assertFalse(active.canArrive(c, at(10), standing(x = 1200 + ARRIVE_TOLERANCE_CM + 1)), "超出一厘米就不算")
        assertFalse(active.canArrive(c, at(10), standing(y = 2000)), "另一个方向也一样")
        assertFalse(active.canArrive(c, at(10), standing(z = 900)), "高度也算数——它悬在半空不是站在那里")
    }
}
