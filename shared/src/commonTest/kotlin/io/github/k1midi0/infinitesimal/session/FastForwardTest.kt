package io.github.k1midi0.infinitesimal.session

import io.github.k1midi0.infinitesimal.world.TimelineEntry
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 快进：**在最早到期的那一刻停下**（§8.1）。
 *
 * 这一支钉的是那条语义——"只停一次""没有可等的就不推进""到期的事被取走"。三条都错得很安静：少停一次
 * 玩家会错过一夜之间发生的事，多推一小时会凭空造出一段没人看见的时间。
 */
class FastForwardTest {

    private fun entry(id: String, at: WorldTime, what: String = "到点了") =
        TimelineEntry(id = id, dueAt = at, what = what)

    private fun worldWith(vararg entries: TimelineEntry, now: WorldTime = WorldTime.of(1, 18)): World {
        val world = World.empty()
        world.clock = now
        entries.forEach { world.timeline.put(it) }
        return world
    }

    @Test
    fun anEmptyTimelineHasNothingToWaitForAndDoesNotMove() {
        val world = worldWith()
        assertNull(FastForward.nextStop(world))
        assertNull(FastForward.runToNext(world), "没有下一刻可等时，等一下这个动作不成立")
        assertEquals(WorldTime.of(1, 18), world.clock, "时间一格都不该动")
    }

    @Test
    fun sleepingThroughTheNightStopsAtTheFireInsteadOfDawn() {
        val world = worldWith(
            entry("fire", WorldTime.of(2, 2), what = "邻居家着火"),
            entry("dawn", WorldTime.of(2, 6), what = "天亮"),
        )
        val stop = FastForward.run(world, WorldTime.of(2, 9))

        assertEquals(WorldTime.of(2, 2), stop.at, "该停在最早的那件事上，不是停在目标时刻")
        assertTrue(stop.interrupted)
        assertEquals(listOf("fire"), stop.happened.map { it.id })
        assertEquals(WorldTime.of(2, 2), world.clock)
        assertEquals(listOf("dawn"), world.timeline.all().map { it.id }, "没到期的那些留在轴上")
    }

    /**
     * **只停一次。** 快进到天亮这一路上有两件事，它停在第一件上就结束——看过之后时间不会自己继续走。
     * 一次调用里连停两次，等于替调用方决定"没人看也接着走"。
     */
    @Test
    fun itStopsOnlyOnceEvenWhenSeveralThingsAreDueAlongTheWay() {
        val world = worldWith(
            entry("fire", WorldTime.of(2, 2)),
            entry("dawn", WorldTime.of(2, 6)),
        )
        FastForward.run(world, WorldTime.of(2, 9))
        assertEquals(listOf("dawn"), world.timeline.all().map { it.id }, "第二件事必须还在轴上等着")
        assertEquals(WorldTime.of(2, 2), world.clock)
    }

    @Test
    fun aQuietNightWalksAllTheWayToTheTarget() {
        val world = worldWith(entry("muchLater", WorldTime.of(4, 9)))
        val stop = FastForward.run(world, WorldTime.of(2, 6))

        assertEquals(WorldTime.of(2, 6), stop.at)
        assertTrue(!stop.interrupted, "一路无事就不算被打断")
        assertEquals(emptyList(), stop.happened.map { it.id })
        assertEquals(WorldTime.of(2, 6), world.clock)
    }

    /** 目标正好就是到期时刻：走到了，也算不上"中途被打断"。 */
    @Test
    fun landingExactlyOnTheDueMomentIsNotAnInterruption() {
        val world = worldWith(entry("fire", WorldTime.of(2, 2)))
        val stop = FastForward.run(world, WorldTime.of(2, 2))
        assertEquals(WorldTime.of(2, 2), stop.at)
        assertTrue(!stop.interrupted)
        assertEquals(listOf("fire"), stop.happened.map { it.id })
    }

    /** 时间只往前。往过去"快进"不会倒着走，但此刻正好到期的事仍然会被取走。 */
    @Test
    fun runningTowardsThePastDoesNotRewindButStillPicksUpWhatIsDueNow() {
        val world = worldWith(entry("now", WorldTime.of(1, 18)))
        val stop = FastForward.run(world, WorldTime.of(1, 9))

        assertEquals(WorldTime.of(1, 18), world.clock, "不能倒回 9 点")
        assertTrue(!stop.interrupted)
        assertEquals(listOf("now"), stop.happened.map { it.id })
    }

    /**
     * 到期是"此刻"的事：导演刚放进来一件立刻就发生的事，快进到任何时刻都该先在这一刻停一下，
     * 而**时间不往前**——因为该动的是别的东西，不是钟。
     */
    @Test
    fun somethingDueRightNowStopsTheClockWhereItIs() {
        val world = worldWith(entry("now", WorldTime.of(1, 18)))
        assertEquals(WorldTime.of(1, 18), FastForward.nextStop(world))

        val stop = FastForward.runToNext(world)
        assertEquals(WorldTime.of(1, 18), stop?.at)
        assertEquals(WorldTime.of(1, 18), world.clock)
        assertEquals(listOf("now"), stop?.happened?.map { it.id })
        assertEquals(emptyList(), world.timeline.all().map { it.id }, "取走之后就没了")
    }

    @Test
    fun runToNextWalksToTheFirstThingAndLeavesTheRest() {
        val world = worldWith(
            entry("b", WorldTime.of(1, 22)),
            entry("a", WorldTime.of(1, 20)),
        )
        val stop = FastForward.runToNext(world)
        assertEquals(WorldTime.of(1, 20), stop?.at)
        assertEquals(listOf("a"), stop?.happened?.map { it.id })
        assertEquals(listOf("b"), world.timeline.all().map { it.id })

        val second = FastForward.runToNext(world)
        assertEquals(WorldTime.of(1, 22), second?.at, "再等一次就轮到第二件")
        assertNull(FastForward.runToNext(world))
    }

    /** 同一刻有好几件事时，一次全取走——停在哪一刻是一件事，那一刻有几件事是另一件事。 */
    @Test
    fun everythingDueAtTheSameMomentComesOutTogether() {
        val world = worldWith(
            entry("a", WorldTime.of(2, 2)),
            entry("b", WorldTime.of(2, 2)),
            entry("c", WorldTime.of(2, 3)),
        )
        val stop = FastForward.runToNext(world)
        assertEquals(WorldTime.of(2, 2), stop?.at)
        assertEquals(listOf("a", "b"), stop?.happened?.map { it.id })
        assertEquals(listOf("c"), world.timeline.all().map { it.id })
    }
}
