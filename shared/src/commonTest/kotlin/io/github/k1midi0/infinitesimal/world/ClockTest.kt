package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 世界时间：**一个整数 + 两处换算**（第几天、什么时辰）。
 *
 * 这一支钉的是那两处换算的边界。它们不显眼，但"第 3 天夜里"写错一天，时间轴上的事就会在错的那天
 * 发生——而那种错没有任何东西会报。
 */
class ClockTest {

    @Test
    fun midnightStartsANewDay() {
        assertEquals(1, WorldTime(0).day, "开局那一刻是第 1 天的开始")
        assertEquals(1, WorldTime(1439).day, "第 1 天的最后一分钟仍在第 1 天")
        assertEquals(2, WorldTime(1440).day, "满一天就是第 2 天")
        assertEquals(3, WorldTime(2880).day)
    }

    @Test
    fun theHourAndMinuteComeOutOfTheMinuteOfDay() {
        val t = WorldTime(1440 + 17 * 60 + 42)
        assertEquals(2, t.day)
        assertEquals(17, t.hour)
        assertEquals(42, t.minute)
        assertEquals(17 * 60 + 42, t.minuteOfDay)
    }

    /**
     * 七个时辰的**边界**逐个钉住。
     *
     * 一次判断错一格，日志里就会写"深夜"而实际是"早上"——而写日志这件事没有第二个人校对。
     */
    @Test
    fun everyPartOfDayStartsAtItsOwnHourAndTheLastOneRunsToMidnight() {
        val expected = mapOf(
            0 to PartOfDay.LATE_NIGHT, 4 to PartOfDay.LATE_NIGHT,
            5 to PartOfDay.MORNING, 7 to PartOfDay.MORNING,
            8 to PartOfDay.FORENOON, 10 to PartOfDay.FORENOON,
            11 to PartOfDay.NOON, 12 to PartOfDay.NOON,
            13 to PartOfDay.AFTERNOON, 16 to PartOfDay.AFTERNOON,
            17 to PartOfDay.EVENING, 19 to PartOfDay.EVENING,
            20 to PartOfDay.NIGHT, 23 to PartOfDay.NIGHT,
        )
        for ((hour, part) in expected) {
            assertEquals(part, PartOfDay.of(hour), "$hour 点应当是${part.label}")
        }
        assertEquals(24, PartOfDay.NIGHT.untilHour, "最后一段必须收在午夜，不能漏掉 23 点")
    }

    /** 开局的时辰与开局那张酒馆写的一致——两处对不上，模型第一句话就会露馅。 */
    @Test
    fun theStartingClockIsTheEveningTheTavernWasWrittenIn() {
        assertEquals(WorldTime.of(1, 18), World.StartingClock)
        assertEquals(PartOfDay.EVENING, World.StartingClock.partOfDay)
        assertEquals("第 1 天 傍晚（18:00）", World.StartingClock.toString())
    }

    @Test
    fun addingAndComparingArePlainArithmetic() {
        val evening = WorldTime.of(1, 18)
        assertEquals(WorldTime.of(2, 2), evening + 8 * 60, "傍晚六点过八个钟头是第二天凌晨两点")
        assertEquals(8 * 60L, (evening + 480) - evening)
        assertTrue(WorldTime.of(1, 18) < WorldTime.of(1, 19))
        assertTrue(WorldTime.of(2, 1) > WorldTime.of(1, 23))
    }

    @Test
    fun ofRejectsTimesThatDoNotExist() {
        assertFailsWith<IllegalArgumentException> { WorldTime.of(0, 12) }
        assertFailsWith<IllegalArgumentException> { WorldTime.of(1, 24) }
        assertFailsWith<IllegalArgumentException> { WorldTime.of(1, 12, 60) }
    }
}
