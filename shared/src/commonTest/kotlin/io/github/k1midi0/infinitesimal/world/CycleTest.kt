package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 状态循环与待办（§3.2.5、§14.37）。
 *
 * 循环那一边最该钉住的是**跨夜那一格**：23:00 打烊之后到次日 07:00 开门之前，人还在"打烊"那一格里。
 * 写成"今天还没开始"是一种看起来很像、其实把整个作息读反的错——而它不会报任何东西。
 */
class CycleTest {

    private fun at(hour: Int, minute: Int = 0) = WorldTime.of(day = 1, hour = hour, minute = minute)

    private val tavern = Cycle(
        listOf(
            Beat(atMinute = 7 * 60, state = "开门"),
            Beat(atMinute = 12 * 60, state = "吃饭"),
            Beat(atMinute = 18 * 60, state = "招呼客人"),
            Beat(atMinute = 23 * 60, state = "打烊"),
        ),
    )

    @Test
    fun theStateIsTheLastBeatThatHasAlreadyStarted() {
        assertEquals("开门", tavern.stateAt(at(8)))
        assertEquals("招呼客人", tavern.stateAt(at(18)))
        assertEquals("招呼客人", tavern.stateAt(at(22, 59)))
        assertEquals("打烊", tavern.stateAt(at(23, 30)))
    }

    @Test
    fun beforeTheFirstBeatOfTheDayYouAreStillInYesterdaysLastOne() {
        // 03:00：今天还没有第一格，所以人还在"打烊"里——**不是**"今天还没开始"。
        assertEquals("打烊", tavern.stateAt(at(3)))
        assertEquals("打烊", tavern.stateAt(at(0, 1)))
    }

    @Test
    fun aBeatIsPickedUpOnItsExactMinute() {
        assertEquals("吃饭", tavern.stateAt(at(12)))
    }

    @Test
    fun noCycleMeansNoAnswerRatherThanIdle() {
        assertNull(Cycle().stateAt(at(12)))
        assertTrue(Cycle().isEmpty)
    }

    @Test
    fun theOrderOfTheBeatsDoesNotMatterBecauseItGetsSorted() {
        val shuffled = Cycle(
            listOf(
                Beat(atMinute = 23 * 60, state = "打烊"),
                Beat(atMinute = 7 * 60, state = "开门"),
                Beat(atMinute = 18 * 60, state = "招呼客人"),
            ),
        ).sorted()

        assertEquals("招呼客人", shuffled.stateAt(at(18)))
    }

    @Test
    fun problemsCatchTheShapesThatWouldSilentlyGiveAWrongAnswer() {
        assertTrue(Cycle(listOf(Beat(7 * 60, "开门"), Beat(12 * 60, "吃饭"))).problems().isEmpty())
        assertTrue(Cycle(listOf(Beat(7 * 60, "开门"))).problems().isEmpty())

        assertTrue(Cycle(listOf(Beat(1500, "什么时候"))).problems().any { "不在一天之内" in it })
        assertTrue(Cycle(listOf(Beat(60, " "))).problems().any { "空的" in it })
        // **同一分钟两格 = 那一刻它到底在做什么没有答案**，而这不是"随便取一个"能糊过去的。
        assertTrue(
            Cycle(listOf(Beat(60, "开门"), Beat(60, "睡觉"))).problems().any { "同一分钟" in it },
        )
    }
}
