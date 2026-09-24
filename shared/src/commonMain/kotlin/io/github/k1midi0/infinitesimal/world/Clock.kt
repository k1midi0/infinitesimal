package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * 世界时间：**从第 1 天 00:00 起的分钟数**。
 *
 * ## 为什么是"一个整数"而不是日期时间
 *
 * 时间轴要能算"最早到期的时刻"（§8.1），而那种计算只用到两件事：**比较**与**加减**。日期时间那一套
 * （时区、夏令时、闰年、月长不等）在这里一点用都没有，却会带来一整类只在边界上发作的错。开局那个
 * 世界的寿命是一个故事的长度，不是几十年。
 *
 * 显示与换算（第几天、什么时辰）都放在这里，因为它们纯粹是**同一个数的两种写法**；把"第 3 天夜里"
 * 存下来再解析回去，等于把一处换算变成两处。
 *
 * ## 为什么下限是分钟而不是秒
 *
 * 设计里出现的全部粒度是"多久之后""哪一天夜里"，最小有意义的一格是分钟（一次动作、一段对话）。
 * 秒级在这里只会让日志变长。
 */
@Serializable
data class WorldTime(val minutes: Long) : Comparable<WorldTime> {

    /**
     * 第几天（1 起）。开局是第 1 天。
     *
     * 时间**只往前**——快进、等、事件到期都是加。负的世界时间没有意义，也不去为它编一个说法。
     */
    val day: Int get() = (minutes / MINUTES_PER_DAY).toInt() + 1

    /** 当天的第几分钟（0..1439）。 */
    val minuteOfDay: Int get() = (((minutes % MINUTES_PER_DAY) + MINUTES_PER_DAY) % MINUTES_PER_DAY).toInt()

    val hour: Int get() = minuteOfDay / 60

    val minute: Int get() = minuteOfDay % 60

    val partOfDay: PartOfDay get() = PartOfDay.of(hour)

    operator fun plus(deltaMinutes: Long): WorldTime = WorldTime(minutes + deltaMinutes)

    operator fun minus(other: WorldTime): Long = minutes - other.minutes

    override fun compareTo(other: WorldTime): Int = minutes.compareTo(other.minutes)

    /** 给模型与界面看的那一句话，例如 `第 1 天 傍晚（18:00）`。 */
    override fun toString(): String =
        "第 $day 天 ${partOfDay.label}（${hour.toString().padStart(2, '0')}:" +
            "${minute.toString().padStart(2, '0')}）"

    /** 短的那种，例如 `1 天 傍晚`。按钮上写得下的那种写法。 */
    val short: String get() = "$day 天 ${partOfDay.label}"

    companion object {

        const val MINUTES_PER_DAY = 1440L

        /** 第 [day] 天的 [hour] 点 [minute] 分。`of(1, 18)` 就是"第 1 天傍晚六点"。 */
        fun of(day: Int, hour: Int, minute: Int = 0): WorldTime {
            require(day >= 1) { "第几天从 1 起，实得 $day" }
            require(hour in 0..23) { "小时要在 0..23，实得 $hour" }
            require(minute in 0..59) { "分钟要在 0..59，实得 $minute" }
            return WorldTime((day - 1) * MINUTES_PER_DAY + hour * 60L + minute)
        }
    }
}

/**
 * 一天里的时辰。
 *
 * **它只用来写字，不用来判断。** 上界与下界在这里是为了让"傍晚"这个词有一个确定的意思——规则书、
 * 时间轴、日志里都会用到它；一旦它有了确定的意思，模型的判断与代码的显示就不会各说各话。
 */
enum class PartOfDay(val label: String, val fromHour: Int) {
    LATE_NIGHT("深夜", 0),
    MORNING("早上", 5),
    FORENOON("上午", 8),
    NOON("中午", 11),
    AFTERNOON("下午", 13),
    EVENING("傍晚", 17),
    NIGHT("夜里", 20),
    ;

    /** 这个时辰覆盖到几点（不含）。最后一段收在 24 点。 */
    val untilHour: Int get() = if (this == entries.last()) 24 else entries[ordinal + 1].fromHour

    companion object {

        /** 某个钟点属于哪个时辰。**它是唯一一处"小时 → 时辰"的换算。** */
        fun of(hour: Int): PartOfDay = entries.last { it.fromHour <= hour }
    }
}
