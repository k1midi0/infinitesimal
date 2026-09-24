package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * 时间轴上的一件事：**到了 [dueAt] 那一刻，就要发生 [what]**。
 *
 * ## 为什么每一件事都必须有到期时刻
 *
 * 这是 §8.1 那条规矩，理由是它决定了一件事能不能被算出来：
 *
 * > **没有时间轴就必然漏事**——你睡觉时邻居家着火，是没有任何定时器能预告的。
 *
 * 玩家说"我睡到天亮"，代码得知道**这一夜之间该发生什么**。那只能来自一张按时刻排好的表：没有到期
 * 时刻的事，快进时就无从判断它该不该发生，于是它只能被漏掉——而且是静默地漏掉。
 *
 * ## 时间轴是**计划**，不是**记录**
 *
 * 这一条不显然，但必须分清：一条到期的事发生之后，它**从时间轴上离开**（见 [Timeline.takeDueBy]）。
 * "发生过什么"是记录层的事（§1.5 记录永不改写），而时间轴是可以被导演改写的计划——§8.2 说得很明确，
 * **只有思考档改得动它**。把两者混在一张表里，就会出现"这条到底改了还是发生过"这种读不出来的状态。
 */
@Serializable
data class TimelineEntry(
    val id: String,
    /** 到期时刻（[WorldTime]）。 */
    val dueAt: WorldTime,
    /** 一句话：到点了要发生什么。写给导演自己与日志看，不必写给玩家。 */
    val what: String,
    /** 牵涉到谁（实体 id）。可以空——例如"北门有一支队伍经过"不牵涉世界里已知的人。 */
    val actors: List<String> = emptyList(),
)

/**
 * 时间轴：**未来的事，按到期时刻排好**。
 *
 * 它只做三件事——存、按时刻取、排出最早的一件。**它不认识模型，也不认识剧本**：往里面放什么是
 * `director/` 的事（§8.2、§11.2），这里只保证"按时刻查得出来"。
 *
 * 时间轴上的事**允许互相矛盾**（导演改剧本时可能留下两条对不上的安排）。这里不校验，因为那种矛盾
 * 的判据是语义的，代码判不了；**代码近似的判据会静默地错**（§6.4 同一条理由）。
 */
class Timeline private constructor(
    private val entries: MutableList<TimelineEntry>,
) {

    /** 按到期时刻排序。同一时刻的，按放进去的先后。 */
    fun all(): List<TimelineEntry> = entries.sortedBy { it.dueAt.minutes }

    fun byId(id: String): TimelineEntry? = entries.firstOrNull { it.id == id }

    /**
     * 放一件事（同 id 覆盖）。
     *
     * **同 id 覆盖**是"导演改主意了"那条路：原来打算第三天夜里放火，现在改成第四天早上——那是同
     * 一件事被重新安排，不是两件事。
     */
    fun put(entry: TimelineEntry) {
        entries.removeAll { it.id == entry.id }
        entries += entry
    }

    fun remove(id: String): Boolean = entries.removeAll { it.id == id }

    /** 到 [time] 为止（含）该发生的那些，按时刻排序。 */
    fun dueBy(time: WorldTime): List<TimelineEntry> =
        entries.filter { it.dueAt <= time }.sortedBy { it.dueAt.minutes }

    /**
     * **[time] 起（含）最早到期的那一刻。** 没有就是 `null`（时间轴上没有将来，也没有"此刻正到期"
     * 的东西）。
     *
     * 它是快进的核心（§8.1）：快进要"算出最早该停下的时刻"，算的就是这个。**含"此刻"**是有意的——
     * 导演刚放进来一件"立刻就发生"的事时，快进到任何时刻都该先在这一刻停一下。
     *
     * **同一时刻有多件事时只报一个时刻**——停在哪一刻是时间轴的事，那一刻有几件事是另一件事。
     */
    fun nextDueFrom(time: WorldTime): WorldTime? =
        entries.filter { it.dueAt >= time }.minOfOrNull { it.dueAt }

    /**
     * **把到期的那些取走。**
     *
     * 取走而不是留下，是"时间轴是计划、不是记录"那条的直接后果（见 [TimelineEntry]）。调用方拿到
     * 之后负责把它们写进记录层——**记录层现在还没有**，所以这一步目前只是把它们交给调用方。
     */
    fun takeDueBy(time: WorldTime): List<TimelineEntry> {
        val due = dueBy(time)
        entries.removeAll(due.toSet())
        return due
    }

    fun snapshot(): TimelineSnapshot = TimelineSnapshot(all())

    companion object {

        fun empty(): Timeline = Timeline(mutableListOf())

        fun of(vararg entries: TimelineEntry): Timeline = Timeline(entries.toMutableList())

        fun from(snapshot: TimelineSnapshot): Timeline = Timeline(snapshot.entries.toMutableList())
    }
}

@Serializable
data class TimelineSnapshot(val entries: List<TimelineEntry> = emptyList())
