package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **活跃与回归的那条线**（§3.2.1、§12 第 46 项、§14.44、§14.45）。
 *
 * [Active.canGo] 比**三样东西**，而三样都不能被顺手改掉：
 *
 *  - **此刻那一格**（这一条取代了原来比 `Active.target` 的那一条）。原来那条在真游戏里是死的：生产
 *    代码里 `Active` 只在"到点卡住"时被创建（§14.45），在那之前没有任何一处设过那个 `target`，于是
 *    `canArrive` 恒为 false。现在"回哪一格"由**此刻的循环**说了算：它此刻该在的那一格，就是它要回的
 *    那一格。
 *  - **地点**（§14.44 加的）。循环里每一格带坐标之后代码才查得了这一条：没有它，一个还在三条街以外的
 *    矮人只要在门口抄一遍原文就能宣称"我回去了"。[itMustBeStandingThere] 钉住它。
 *  - **它报上来的那句话就是那一格的原文**。这一条是**校验**，不是"用文字认格子"：模型手里有原文
 *    （提示词里印着），抄错就是它没看。[itMustQuoteTheBeatVerbatim] 钉住它。
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

    /** 此刻该在的那一格说的是什么——测试里一律照抄它，等于"模型抄对了"。 */
    private fun said(c: Cycle, now: WorldTime) = c.beatAt(now)?.state.orEmpty()

    @Test
    fun beatAtGivesTheWholeBeatNotJustItsSentence() {
        val c = cycle()

        assertEquals("招呼客人", c.beatAt(at(10))?.state)
        assertEquals(9 * 60, c.beatAt(at(10))?.atMinute, "整格都要给得出来——回归判据读的是它")
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
        val verdict = Active(since = at(18)).canGo(c, at(10), standing(), "招呼客人")
        assertFalse(verdict.can)
        assertNull(verdict.beat, "没有循环就是没有那一格")
    }

    @Test
    fun aFreshlyActiveOneWithoutATargetCanStillGoHome() {
        // **这一条是 §14.45 修掉的那个死线。** 一个人卡住时会带着 target 变活跃，但一段
        // `Active(since = 现在)`（还没有 target，或者导演换过作息之后被清成了 null）**照样能回去**：
        // 判据读的是此刻的循环，不读那个字段。
        val c = cycle()
        val fresh = Active(since = at(18), target = null)

        assertTrue(fresh.canGo(c, at(10), standing(), said(c, at(10))).can, "此刻那一格就是它要回的那一格")
        assertFalse(
            fresh.canGo(c, at(10), standing(y = 5000), said(c, at(10))).can,
            "但人不在那儿还是回不去",
        )
    }

    @Test
    fun theBeatIsWhateverTheCycleSaysRightNow() {
        // **不再有"它指名的那一格"这回事**：它此刻该在哪一格，判据就是哪一格。这不是放松，而是把
        // 那个字段（`target`）上原来压着的两件事分开——它只作归因，判断由循环说。
        //
        // 这里用三格**各不相同**的作息，好把"哪一格"这一栏单独问出来。
        val spread = Cycle(
            listOf(
                Beat(8 * 60, "生火", x = 900, y = 320, z = 0),
                Beat(12 * 60, "招呼客人", x = 1000, y = 200, z = 0),
                Beat(20 * 60, "打烊", x = 1300, y = 100, z = 0),
            ),
        ).sorted()
        fun saidAt(now: WorldTime) = spread.beatAt(now)?.state.orEmpty()

        assertTrue(
            Active(since = at(18)).canGo(spread, at(15), standing(x = 1000), saidAt(at(15))).can,
            "15 点落在中午那一格上，人也站在那一格",
        )
        assertFalse(
            Active(since = at(18)).canGo(spread, at(15), standing(x = 1200), saidAt(at(15))).can,
            "站错地方了，哪怕报的原文是对的",
        )
        val probe = Active(since = at(18)).canGo(spread, at(9), standing(x = 900, y = 320), saidAt(at(9)))
        assertTrue(probe.can, "9 点那一格在 (900, 320)")
    }

    @Test
    fun theSameSentenceInTwoBeatsIsStillTwoBeats() {
        // 9 点与 14 点那两句状态**一模一样**，而地点不同（1200,200 与 1000,200）。每一格管到下一格开始
        // 之前，所以 14 点落在**第二格**上（它是从 840 分钟那一刻开始的），而那时该在的是 (1000,200)。
        // 判据读的是此刻那一格与坐标，于是站在第一格那个点上的回不去——同一句话不能替它冒领。
        val c = cycle()

        assertTrue(
            Active(since = at(18)).canGo(c, at(9), standing(x = 1200), said(c, at(9))).can,
            "9 点那一格，人在 1200",
        )
        // 14 点那一格从 840 分钟那一刻开始（每一格管到下一格开始之前），而它的地点是 1000。
        assertFalse(
            Active(since = at(18)).canGo(c, at(14), standing(x = 1200), said(c, at(14))).can,
            "14 点该在 1000，而它还在 1200",
        )
        assertTrue(
            Active(since = at(18)).canGo(c, at(14), standing(x = 1000), said(c, at(14))).can,
            "走到 1000 就算到了那一格",
        )
    }

    @Test
    fun itMustBeStandingThere() {
        // §14.44：时刻对了、人还在三条街以外——那不是一次回归。容差是两米，所以"差一点点"仍然算到
        // 了（它自己看不到自己的坐标，要求一个点等于永远不满足）。
        val c = cycle()
        fun verdict(x: Int = 1200, y: Int = 200, z: Int = 0) =
            Active(since = at(18)).canGo(c, at(10), standing(x, y, z), said(c, at(10)))

        assertTrue(verdict(x = 1200 + ARRIVE_TOLERANCE_CM).can, "容差之内算到了")
        assertTrue(verdict(x = 1200 - ARRIVE_TOLERANCE_CM).can, "两边都算")
        assertFalse(verdict(x = 1200 + ARRIVE_TOLERANCE_CM + 1).can, "超出一厘米就不算")
        assertFalse(verdict(y = 2000).can, "另一个方向也一样")
        assertFalse(verdict(z = 900).can, "高度也算数——它悬在半空不是站在那里")
    }

    @Test
    fun itMustQuoteTheBeatVerbatim() {
        // 报上来的那句话**必须是那一格的原文**（首尾空白不算）。这一条拦的是"背了个大意就宣称回到了
        // 循环上"——而它回来之后笔记就重新装进它眼前了，所以这句话不能白说。
        val c = cycle()
        val now = at(10)

        assertTrue(Active(since = at(18)).canGo(c, now, standing(), "招呼客人").can, "原文照抄")
        assertTrue(Active(since = at(18)).canGo(c, now, standing(), "  招呼客人  ").can, "首尾空白不算错")
        assertFalse(Active(since = at(18)).canGo(c, now, standing(), "在柜台后面忙").can, "换个说法不行")
        assertFalse(Active(since = at(18)).canGo(c, now, standing(), "").can, "空着更不行")

        // 三栏各自说得出是哪一条不对（拒绝话术就是照着这三栏写的）。
        val wrong = Active(since = at(18)).canGo(c, now, standing(y = 5000), "在柜台后面忙")
        assertEquals("招呼客人", wrong.beat?.state)
        assertFalse(wrong.atThePlace)
        assertFalse(wrong.saidTheSame)
    }
}
