package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.EventKind
import io.github.k1midi0.infinitesimal.world.Spot
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「投递」那一侧的粗筛（§14.37）。
 *
 * 它要钉住的分界是**投递与唤醒是两件事**：同处一片的角色都会收到这一轮发生了什么，而**要唤醒谁**
 * 是判定那一栏说了算。这里只管前半句。
 *
 * 另外两条同样是"错了不报"的：
 *
 * - **发起者自己不在"目击者"里**（它当然知道，但那是 `audience` 最后补上的，不是"框里还有它自己"）。
 * - **没有卡的东西不是角色**（判据是"有没有人设卡"，不是 `kind == "角色"`，§12 第 12 项）——少了
 *   这一条，一只酒杯会被当成"收到了这件事"的对象。
 */
class WitnessesTest {

    private fun character(id: String, x: Int, y: Int) = Entity(
        id = id,
        kind = "角色",
        label = id,
        x = x, y = y, z = 0,
        cards = listOf(CharacterCard("身份", "你是 $id。")),
    )

    /** 玩家：**没有卡**——它照样是人，但不是"角色"那一类（判据见文件头）。 */
    private val player = Entity(id = "player", kind = "角色", label = "你", x = 1200, y = 300, z = 0)

    private val dwarf = character("dwarf", 1000, 200)
    private val bard = character("bard", 3000, 300)
    private val cup = Entity(id = "cup", kind = "器物", label = "酒杯", x = 1200, y = 400, z = 0)

    private fun world() = World.from(WorldSnapshot(listOf(player, dwarf, bard, cup)))

    @Test
    fun onlyTheCharactersInsideTheFrameAreWitnesses() {
        // 矮人离玩家两米出头，在框里；吟游诗人十八米开外，不在；酒杯在框里但没有卡，不是角色。
        assertEquals(listOf("dwarf"), world().witnesses("player"))
    }

    @Test
    fun theActorItselfIsNotAWitness() {
        assertEquals(false, "player" in world().witnesses("player"))
    }

    @Test
    fun theAudienceIsTheFramePlusWhoWasPointedAtPlusWhoWasAffectedPlusTheActor() {
        assertEquals(
            listOf("dwarf", "bard", "cup", "player"),
            world().audience("player", addressed = listOf("bard"), affected = listOf("cup")),
        )
    }

    @Test
    fun theAudienceNeverRepeatsAnybody() {
        // 被点名的人同时也在框里时**只出现一次**——编号列表里重复一个 seq 是幂等的，但排一次让
        // 这一层读起来没有歧义。
        assertEquals(listOf("dwarf", "player"), world().audience("player", addressed = listOf("dwarf")))
    }

    @Test
    fun anActorThatIsNotInTheWorldHasNoAudience() {
        assertEquals(emptyList(), world().witnesses("nobody"))
    }

    // ── 事件总线（§6.1、§14.39）──────────────────────────────────────────────────────

    @Test
    fun theBusOnlyShowsThisTurnsEvents() {
        val world = world()
        val older = world.log.append(
            World.StartingClock, EventKind.SAY, "player", "你", "上一回合的话", spot = Spot(1200, 300, 0),
        )
        world.log.append(
            World.StartingClock, EventKind.SAY, "player", "你", "这一回合的话", spot = Spot(1200, 300, 0),
        )

        // **它是视口，不是日志**：`since` 之前的一律看不见，哪怕它就在旁边。
        assertEquals(
            listOf("这一回合的话"),
            world.bus("dwarf", since = older.seq + 1).map { it.event.text },
        )
    }

    @Test
    fun theBusLeavesOutWhatHappenedOutOfRange() {
        val world = world()
        world.log.append(
            World.StartingClock, EventKind.SAY, "player", "你", "在这边", spot = Spot(1200, 300, 0),
        )
        world.log.append(
            World.StartingClock, EventKind.SAY, "bard", "吟游诗人", "在很远那边", spot = Spot(90_000, 90_000, 0),
        )

        assertEquals(listOf("在这边"), world.bus("dwarf", since = 0).map { it.event.text })
    }

    @Test
    fun beingAddressedGetsYouTheDetailAndMerelyBeingNearGetsYouATitle() {
        val world = world()
        world.log.append(
            World.StartingClock, EventKind.ACT, "player", "你", "我把琴挪了挪",
            effects = listOf("琴 挪到了 (1100, 400, 0) 厘米"),
            addressedTo = listOf("dwarf"), spot = Spot(1200, 300, 0),
        )
        world.log.append(
            World.StartingClock, EventKind.ACT, "bard", "吟游诗人", "他清了清嗓子",
            effects = listOf("某个只有查了才看得到的细节"),
            spot = Spot(1100, 400, 0),
        )

        val bus = world.bus("dwarf", since = 0)
        assertEquals(listOf(true, false), bus.map { it.full })
        // **分野在后果那几条**：被指向的连细节一起给，其余的只给一行 gist。
        assertTrue("琴 挪到了" in bus[0].render())
        assertTrue("某个只有查了才看得到的细节" !in bus[1].render())
        assertTrue("e" in bus[1].render(), "标题那一行要带编号，否则它没法 look")
    }

    @Test
    fun yourOwnDoingIsAlwaysShownInFull() {
        val world = world()
        world.log.append(
            World.StartingClock, EventKind.SAY, "dwarf", "酒馆里的矮人", "我自己说的",
            spot = Spot(1000, 200, 0),
        )

        val bus = world.bus("dwarf", since = 0)
        assertTrue(bus.single().full, "一个人不必去查自己刚说过什么")
        assertTrue("我自己说的" in bus.single().render())
    }

    @Test
    fun anEventWithNoPlaceIsShownToEverybody() {
        // 时间轴到点那种（没有主体、没有位置）。**宁可多推一条，也不要悄悄丢掉一条真发生过的事**——
        // 这条局限记在 §12 第 20 项。
        val world = world()
        world.log.append(World.StartingClock, EventKind.CLOCK, null, "", "邻居家着了火", spot = null)

        assertEquals(1, world.bus("dwarf", since = 0).size)
    }
}
