package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 状态循环（§3.2.5、§14.37，**§14.44 把它从"一天的钟点表"改成"一只从诞生起跑的定时器"**）。
 *
 * ## 四条读法，缺一不可
 *
 *  - **时间是相对循环起点的**（[Cycle.bornAt]），不是"一天里的第几分钟"——所以一个循环可以长过一天。
 *  - **每一格管到下一格开始之前**：`[它的时刻, 下一格的时刻)`。
 *  - **最后一格是这一圈的边界**：它自己那一段 `[它的时刻, 周期)` 宽度为零（相位恒 `< 周期`），它真正
 *    管的是**绕回来的那一截** `[0, 第一格的时刻)`——"上一圈还没走完"。相位落在第一格之前时走的正是
 *    这条路。
 *  - **一圈多长由内容说定**（[Cycle.periodOverride]），不给时按"最后一格的时刻"算。一天的作息必须
 *    显式给 1440：07:00 起、23:00 收时，"23:00 到次日 07:00"那 8 个钟头得归最后一格。
 *
 * 第三条与第四条前后踩过两次坑，都由这一支钉住：周期取"首尾之差"时最后一格永远轮不到（某一格静默
 * 地死了）；而一天里上班前那段时间会被算错。
 */
class CycleTest {

    private fun at(hour: Int, minute: Int = 0) = WorldTime.of(day = 1, hour = hour, minute = minute)

    /** 世界起点之后的第几分钟。**跨夜那些相位全靠它**（`at(27)` 造不出第 27 个钟头）。 */
    private fun since(n: Int) = WorldTime(n.toLong())

    /** 诊断用：某一刻的相位与它落在哪一格。查"时间是相对的"这类错时先看它。 */
    private fun why(c: Cycle, t: WorldTime): String =
        "phase=${c.phaseAt(t)} period=${c.periodMinutes} beats=${c.beats.map { it.atMinute }} " +
            "beat=${c.beatAt(t)?.atMinute}"

    /** 一个矮人的一天：**起点是世界起点**（`bornAt` 默认零），一圈**显式给成一天**（1440）。 */
    private val tavern = Cycle(
        beats = listOf(
            Beat(atMinute = 7 * 60, state = "开门"),
            Beat(atMinute = 12 * 60, state = "吃饭"),
            Beat(atMinute = 18 * 60, state = "招呼客人"),
            Beat(atMinute = 23 * 60, state = "打烊"),
        ),
        periodOverride = 24 * 60,
    )

    /** 同一张表、**不给周期**：一圈就是最后一格的时刻（23:00 = 1380）。 */
    private val byLastBeat = tavern.copy(periodOverride = null)

    @Test
    fun theStateIsTheLastBeatThatHasAlreadyStarted() {
        assertEquals("开门", tavern.stateAt(at(8)), why(tavern, at(8)))
        assertEquals("招呼客人", tavern.stateAt(at(18)), why(tavern, at(18)))
        assertEquals("招呼客人", tavern.stateAt(at(22, 59)), why(tavern, at(22, 59)))
        assertEquals("打烊", tavern.stateAt(at(23, 30)), why(tavern, at(23, 30)))
    }

    @Test
    fun beforeTheFirstBeatYouAreStillInTheLastOneOfThePreviousLap() {
        // 03:00：这一圈还没有第一格（420），所以人还在"打烊"里——**不是**"这一圈还没开始"。
        assertEquals("打烊", tavern.stateAt(at(3)), why(tavern, at(3)))
        assertEquals("打烊", tavern.stateAt(at(0, 1)), why(tavern, at(0, 1)))
    }

    @Test
    fun aBeatIsPickedUpOnItsExactMinute() {
        assertEquals("吃饭", tavern.stateAt(at(12)), why(tavern, at(12)))
        assertEquals("打烊", tavern.stateAt(at(23)), why(tavern, at(23)))
    }

    @Test
    fun aDayLongCycleCoversTheNightBeforeTheFirstBeat() {
        // 一天的作息：07:00 起、23:00 收，**而这一圈是 24 小时**——23:00 到次日 07:00 那 8 个钟头是
        // "打烊"那一格。这一条只有显式给了周期才对。
        assertEquals(24 * 60, tavern.periodMinutes)
        assertEquals("打烊", tavern.stateAt(at(1)), why(tavern, at(1)))
        assertEquals("打烊", tavern.stateAt(at(6, 59)), why(tavern, at(6, 59)))
        assertEquals("开门", tavern.stateAt(at(7)), why(tavern, at(7)))
        // 第二天同一时刻读的是同一格——循环自己转圈。
        assertEquals("开门", tavern.stateAt(since(7 * 60 + 1440)), why(tavern, since(7 * 60 + 1440)))
    }

    @Test
    fun withoutAnExplicitPeriodTheLapEndsJustAfterTheLastBeat() {
        // 不给周期时一圈 = 最后一格的时刻 + 1 = 23:01（1381）。那 `+1` 把最后一刻收进这一圈，于是
        // 最后一格至少有一分钟是它自己的。
        assertEquals(1381, byLastBeat.periodMinutes)
        assertEquals("开门", byLastBeat.stateAt(at(8)), why(byLastBeat, at(8)))
        assertEquals("打烊", byLastBeat.stateAt(at(23, 30)), why(byLastBeat, at(23, 30)))
        assertEquals("打烊", byLastBeat.stateAt(at(3)), why(byLastBeat, at(3)))
    }

    @Test
    fun aCycleLongerThanADayIsLegal() {
        // 一天半的循环：00:30 起、最后一格 30:00，再一格 31:40 收。
        val watch = Cycle(
            listOf(
                Beat(30, "守上半夜", x = 900, y = 100),
                Beat(750, "睡下半夜", x = 950, y = 120),
                Beat(1800, "交班，绕着镇子走一圈", x = 1100, y = 400),
                Beat(1900, "回屋睡下", x = 950, y = 120),
            ),
        )
        assertEquals(1901, watch.periodMinutes, "一圈近 32 个钟头")

        // 相位 = 绝对分钟 % 1901。
        assertEquals("守上半夜", watch.stateAt(since(30)), why(watch, since(30)))
        assertEquals("守上半夜", watch.stateAt(since(700)), why(watch, since(700)))
        assertEquals("睡下半夜", watch.stateAt(since(800)), why(watch, since(800)))
        // 1800 那一格（交班）要有它自己那一段：相位落在 [1800, 1901) 上。
        assertEquals("交班，绕着镇子走一圈", watch.stateAt(since(1800)), why(watch, since(1800)))
        assertEquals("回屋睡下", watch.stateAt(since(1900)), why(watch, since(1900)))
        // 而第二圈的相位 30 又回到第一格。
        assertEquals("守上半夜", watch.stateAt(since(30 + 1901)), why(watch, since(30 + 1901)))
    }

    @Test
    fun aShortCycleTurnsOverSeveralTimesADay() {
        // 一圈 40 分钟：一天转三十六圈。**旧语义读不出这个**（那时 atMinute 被钉在 0..1439）。
        val short = Cycle(listOf(Beat(10, "打更"), Beat(39, "歇一会儿")))
        assertEquals(40, short.periodMinutes, "最后一格 39，而一圈是 40——那 +1 把最后一刻收进来了")

        // 每一格管到下一格开始之前，最后一格绕回来管 `[0, 10)`。
        assertEquals("歇一会儿", short.stateAt(since(5)), why(short, since(5)))
        assertEquals("打更", short.stateAt(since(20)), why(short, since(20)))
        // 相位 = 绝对分钟 % 40：45 % 40 = 5 → 绕回最后一格；50 % 40 = 10 → 第一格。
        assertEquals("歇一会儿", short.stateAt(since(45)), why(short, since(45)))
        assertEquals("打更", short.stateAt(since(50)), why(short, since(50)))
    }

    @Test
    fun bornAtIsTheAnchorOfThePhase() {
        // 同一张表、不同的起点，落在不同的格上——这就是 [Cycle.bornAt] 的全部作用。
        val bornLater = tavern.copy(bornAt = at(12))
        // 起点那一刻相位 0，早于第一格（420）→ 绕给最后一格。
        assertEquals(0, bornLater.phaseAt(at(12)))
        assertEquals("打烊", bornLater.stateAt(at(12)), why(bornLater, at(12)))
        // 走到相位 420（12:00 + 7 个钟头 = 19:00）才进第一格。
        assertEquals(420, bornLater.phaseAt(at(19)))
        assertEquals("开门", bornLater.stateAt(at(19)), why(bornLater, at(19)))
        assertEquals(900, bornLater.phaseAt(since(12 * 60 + 900)))
        // 相位 900 落在 [720, 1080) 那一格上——"吃饭"。
        assertEquals("吃饭", bornLater.stateAt(since(12 * 60 + 900)), why(bornLater, since(12 * 60 + 900)))
        // 要读到 18:00 那一格（相位 1080），起点之后要走 1080 分钟。
        assertEquals("招呼客人", bornLater.stateAt(since(12 * 60 + 1080)), why(bornLater, since(12 * 60 + 1080)))
    }

    @Test
    fun noCycleMeansNoAnswerRatherThanIdle() {
        assertNull(Cycle().stateAt(at(12)))
        assertTrue(Cycle().isEmpty)
    }

    @Test
    fun theOrderOfTheBeatsDoesNotMatterBecauseItGetsSorted() {
        // 乱序的一份：排序之后它读起来与给的模样一致。
        val shuffled = Cycle(
            listOf(
                Beat(atMinute = 23 * 60, state = "打烊"),
                Beat(atMinute = 7 * 60, state = "开门"),
                Beat(atMinute = 18 * 60, state = "招呼客人"),
            ),
            periodOverride = 24 * 60,
        ).sorted()

        assertEquals(24 * 60, shuffled.periodMinutes, "显式给的周期不受排序影响")
        assertEquals(listOf(420, 1080, 1380), shuffled.beats.map { it.atMinute })
        assertEquals("招呼客人", shuffled.stateAt(at(18)), why(shuffled, at(18)))
        assertEquals("开门", shuffled.stateAt(at(8)), why(shuffled, at(8)))
        // 23:00 起（相位 1380）绕回第一格之前的最后一段，读作"打烊"。
        assertEquals("打烊", shuffled.stateAt(at(23, 30)), why(shuffled, at(23, 30)))
    }

    @Test
    fun eachBeatCarriesWhereItShouldBe() {
        // 坐标是**基线**：它平时这个点该在哪儿。代码拿它到点把角色挪过去（`World.refreshCyclesToClock`）、
        // 回归时也拿它判"到没到"。
        val withPlace = Cycle(
            listOf(
                Beat(420, "在后厨", x = 900, y = 320, z = 12800),
                Beat(720, "在柜台后面", x = 1150, y = 120, z = 12800),
                Beat(1140, "去地窖", x = 1300, y = 100, z = 12800),
            ),
            periodOverride = 1440,
        )
        // 相位 140 早于第一格（420）→ 绕给最后一格（地窖）。
        assertEquals(1300, withPlace.beatAt(since(140))?.x, why(withPlace, since(140)))
        // 相位 560 落在 420 那一格（它在 720 之前）。
        assertEquals(900, withPlace.beatAt(since(560))?.x, why(withPlace, since(560)))
        assertEquals(1150, withPlace.beatAt(since(800))?.x, why(withPlace, since(800)))
        assertEquals(1300, withPlace.beatAt(since(1200))?.x, why(withPlace, since(1200)))
    }

    @Test
    fun problemsCatchTheShapesThatWouldSilentlyGiveAWrongAnswer() {
        // 好的一份：至少两格、第一格晚于 0、最后一格在它之后。
        assertTrue(Cycle(listOf(Beat(60, "开门"), Beat(12 * 60, "吃饭"))).problems().isEmpty())
        // **长过一天是合法的**（§14.44）——1500 不再是坏值，它是"一天零一个钟头"。
        assertTrue(Cycle(listOf(Beat(30, "守夜"), Beat(1500, "睡下半夜"))).problems().isEmpty())
        // 显式给一圈长度：要比最后一格更晚。
        assertTrue(
            Cycle(listOf(Beat(60, "开门"), Beat(120, "吃饭")), periodOverride = 200).problems().isEmpty(),
        )
        assertTrue(
            Cycle(listOf(Beat(60, "开门"), Beat(120, "吃饭")), periodOverride = 100)
                .problems().any { "一圈长度" in it },
            "实得 ${Cycle(listOf(Beat(60, "开门"), Beat(120, "吃饭")), periodOverride = 100).problems()}",
        )

        // 一格：等于它一辈子只做一件事，不是作息。
        assertTrue(Cycle(listOf(Beat(7 * 60, "开门"))).problems().any { "至少两格" in it })
        // 负的相对时间不是合法值（它是从循环开始那一刻数起的分钟数）。
        assertTrue(Cycle(listOf(Beat(-1, "守夜"), Beat(600, "睡下半夜"))).problems().any { "负" in it })
        assertTrue(Cycle(listOf(Beat(60, " "), Beat(120, "开门"))).problems().any { "空的" in it })
        // 倒着写：最后一格必须大于第一格。
        assertTrue(
            Cycle(listOf(Beat(600, "开门"), Beat(60, "打烊"))).problems().any { "一圈的长度" in it },
            "实得 ${Cycle(listOf(Beat(600, "开门"), Beat(60, "打烊"))).problems()}",
        )
        // **同一分钟两格 = 那一刻它到底在做什么没有答案**，而这不是"随便取一个"能糊过去的。
        assertTrue(
            Cycle(listOf(Beat(60, "开门"), Beat(60, "睡觉"))).problems().any { "同一分钟" in it },
        )
    }
}
