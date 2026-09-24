package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.world.TimelineEntry
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 时间轴的提交层：**一次性校验、一次性提交**（§10.2）。
 *
 * 这一支钉的主要是"整批退"那条：落一半进去，剩下那一半还得重排一次，而它重排时会看到一张已经变了
 * 一半的表，于是两批对不上。这条坏了不会报错，只会让世界慢慢长出一副前后矛盾的剧本。
 */
class TimelinePlannerTest {

    private val now = WorldTime.of(1, 18)

    private fun entry(id: String, day: Int, hour: Int, minute: Int = 0, what: String = "到点了") =
        TimelineEntry(id = id, dueAt = WorldTime.of(day, hour, minute), what = what)

    private fun world() = World.empty()

    @Test
    fun aCleanBatchLandsOnTheTimeline() {
        val world = world()
        val batch = listOf(
            entry("dwarf_stows_the_barrel", 1, 22, 30, "酒馆的矮人把最后一桶酒搬进地窖"),
            entry("lamps_burn_low", 2, 3, 0, "四盏油灯里的油烧得差不多了"),
        )

        val result = TimelinePlanner.apply(world, batch)

        assertEquals(Planned.Done(batch), result)
        assertEquals(listOf("dwarf_stows_the_barrel", "lamps_burn_low"), world.timeline.all().map { it.id })
    }

    /**
     * **一条不合法，整批都不落。**
     *
     * 这一条最容易被写成一个"跳过坏的那条"的循环，而它的后果是很晚才看得出来的：导演以为两件都排上
     * 了，实际只排上了一件，而它下一次看到的时间轴里少了一件——它会当成"我上次没排"再排一遍。
     */
    @Test
    fun oneBadEntrySendsTheWholeBatchBackAndLandsNothing() {
        val world = world()
        val batch = listOf(
            entry("good", 2, 6, what = "天亮了"),
            entry("stale", 1, 9, what = "这件事排在今天早上，而现在已经傍晚了"),
        )

        val result = TimelinePlanner.apply(world, batch)

        assertTrue(result is Planned.Rejected, "整批必须退回去，实得 $result")
        assertEquals(emptyList(), world.timeline.all().map { it.id }, "一条都不该落")
        assertTrue((result as Planned.Rejected).problems.any { it.contains("stale") })
    }

    /**
     * 一批里同一个 id 出现两次：**几乎一定是笔误**。
     *
     * 它坏的方式最安静——`Timeline.put` 是同 id 覆盖，于是其中一件事**凭空消失**，而时间轴上看起来
     * 完全正常。§8.1 那句"必然漏事"最典型的一种就是从这种地方漏的。
     */
    @Test
    fun theSameIdTwiceInOneBatchIsRefusedRatherThanSilentlyMerging() {
        val world = world()
        val batch = listOf(
            entry("fire", 1, 20, what = "邻居家着了火"),
            entry("fire", 1, 21, what = "邻居家着了火（其实想说的是另一件事）"),
        )

        val result = TimelinePlanner.apply(world, batch)

        assertTrue(result is Planned.Rejected)
        assertTrue((result as Planned.Rejected).problems.any { it.contains("fire") && it.contains("不止一次") })
        assertEquals(emptyList(), world.timeline.all().map { it.id })
    }

    /** 空 id 与空 what 都要拦：一条没有内容的安排落进时间轴，快进时只会停一下、什么也说不出来。 */
    @Test
    fun anEntryWithNothingToSayIsRefused() {
        val world = world()
        val result = TimelinePlanner.apply(
            world,
            listOf(
                TimelineEntry(id = "", dueAt = WorldTime.of(2, 6), what = "天亮了"),
                TimelineEntry(id = "unnamed", dueAt = WorldTime.of(2, 7), what = "   "),
            ),
        )

        assertTrue(result is Planned.Rejected)
        val problems = (result as Planned.Rejected).problems
        assertTrue(problems.any { it.contains("没给 id") })
        assertTrue(problems.any { it.contains("unnamed") && it.contains("没说要发生什么") })
    }

    /** **一次报全**，不是报第一条就停：模型拿着一整张清单修一轮就够了，报一条要来回好几趟。 */
    @Test
    fun everyProblemIsReportedAtOnce() {
        val problems = TimelinePlanner.problems(
            listOf(
                TimelineEntry(id = "", dueAt = WorldTime.of(2, 6), what = "天亮了"),
                TimelineEntry(id = "a", dueAt = WorldTime.of(1, 9), what = "过去的事"),
                TimelineEntry(id = "b", dueAt = WorldTime.of(1, 10), what = "也是过去的事"),
            ),
            now,
        )
        assertEquals(3, problems.size, "三条毛病该一次报全，实得：$problems")
    }

    /**
     * 对着**已经排在轴上的**那一条用同一个 id：那是"改主意"，合法。
     *
     * 与"一批里重复"是两回事——前者是导演看见了自己上次排的东西之后要改，后者是它一笔写岔了。
     */
    @Test
    fun aSameIdAgainstWhatIsAlreadyOnTheTimelineIsTheDirectorChangingItsMind() {
        val world = world()
        world.timeline.put(entry("fire", 3, 2, what = "第三天夜里放火"))

        val result = TimelinePlanner.apply(world, listOf(entry("fire", 4, 9, what = "改成第四天早上放火")))

        assertTrue(result is Planned.Done, "实得 $result")
        assertEquals(1, world.timeline.all().size)
        assertEquals(WorldTime.of(4, 9), world.timeline.byId("fire")?.dueAt)
        assertEquals("改成第四天早上放火", world.timeline.byId("fire")?.what)
    }

    /** 正好排在"现在"是允许的：那是一条"立刻就发生"的意图，快进会先在这一刻停一下。 */
    @Test
    fun somethingDueRightNowIsAllowed() {
        val world = world()
        val result = TimelinePlanner.apply(world, listOf(entry("now", 1, 18, what = "门被推开了")))
        assertTrue(result is Planned.Done, "实得 $result")
        assertEquals(now, world.timeline.nextDueFrom(now))
    }
}
