package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 时间轴：**未来的事，按到期时刻排好**（§8.1）。
 *
 * 这里最要紧的一条不是排序，而是 **[Timeline.takeDueBy] 会把到期的取走**——时间轴是**计划**，不是
 * **记录**。这一条要是错了，"快进到天亮"会在每一次调用里把同一批事重新算一遍。
 */
class TimelineTest {

    private fun entry(id: String, at: WorldTime, what: String = "到点了") =
        TimelineEntry(id = id, dueAt = at, what = what)

    private val evening = WorldTime.of(1, 18)

    @Test
    fun entriesComeBackInTimeOrderHoweverTheyWerePutIn() {
        val timeline = Timeline.empty()
        timeline.put(entry("late", WorldTime.of(2, 6)))
        timeline.put(entry("early", WorldTime.of(1, 20)))
        timeline.put(entry("middle", WorldTime.of(2, 1)))
        assertEquals(listOf("early", "middle", "late"), timeline.all().map { it.id })
    }

    @Test
    fun puttingTheSameIdTwiceIsTheDirectorChangingItsMind() {
        val timeline = Timeline.empty()
        timeline.put(entry("fire", WorldTime.of(3, 2), what = "第三天夜里放火"))
        timeline.put(entry("fire", WorldTime.of(4, 9), what = "改成第四天早上放火"))

        assertEquals(1, timeline.all().size, "同一件事被重新安排，不是两件事")
        assertEquals(WorldTime.of(4, 9), timeline.byId("fire")?.dueAt)
        assertEquals("改成第四天早上放火", timeline.byId("fire")?.what)
    }

    /** 边界含在内：正好到期的那一刻算"到期了"。半开区间会让快进在到期那一刻看不见它。 */
    @Test
    fun dueByIncludesTheMomentItself() {
        val timeline = Timeline.of(entry("a", evening), entry("b", evening + 60))
        assertEquals(listOf("a"), timeline.dueBy(evening).map { it.id })
        assertEquals(listOf("a", "b"), timeline.dueBy(evening + 60).map { it.id })
        assertEquals(emptyList(), timeline.dueBy(evening + -1).map { it.id })
    }

    /**
     * `nextDueFrom` **含"此刻"**：导演刚放进来一件"立刻就发生"的事时，快进到任何时刻都该先在这一刻
     * 停一下，而不是从这一刻之后才开始找。
     */
    @Test
    fun nextDueFromCountsTheMomentItselfAsStillAhead() {
        val timeline = Timeline.of(entry("now", evening), entry("later", evening + 600))
        assertEquals(evening, timeline.nextDueFrom(evening))
        assertEquals(evening + 600, timeline.nextDueFrom(evening + 1))
        assertNull(timeline.nextDueFrom(evening + 601), "过了最后一件事就没有下一刻了")
    }

    @Test
    fun takingDueEntriesLeavesTheRestAndIsNotRepeatable() {
        val timeline = Timeline.of(
            entry("a", evening),
            entry("b", evening + 30),
            entry("c", evening + 600),
        )
        val taken = timeline.takeDueBy(evening + 30)
        assertEquals(listOf("a", "b"), taken.map { it.id })
        assertEquals(listOf("c"), timeline.all().map { it.id }, "取走的必须真的不在时间轴上了")
        assertEquals(emptyList(), timeline.takeDueBy(evening + 30), "同一批不能被取第二次")
    }

    @Test
    fun removingByIdReportsWhetherAnythingWasThere() {
        val timeline = Timeline.of(entry("a", evening))
        assertTrue(timeline.remove("a"))
        assertTrue(!timeline.remove("a"))
        assertEquals(emptyList(), timeline.all())
    }

    /** 快照必须把时间轴一起带上——否则存档读回来，将来的事全没了，而且没有任何东西会报。 */
    @Test
    fun aSnapshotRoundTripKeepsTheTimelineAndTheClock() {
        val world = World.empty()
        world.clock = WorldTime.of(2, 3)
        world.timeline.put(entry("later", WorldTime.of(5, 9), what = "北门有一支队伍经过"))

        val back = World.from(world.snapshot())
        assertEquals(WorldTime.of(2, 3), back.clock)
        assertEquals(1, back.timeline.all().size)
        assertEquals("北门有一支队伍经过", back.timeline.byId("later")?.what)
        assertEquals(WorldTime.of(5, 9), back.timeline.byId("later")?.dueAt)
    }
}
