package io.github.k1midi0.infinitesimal.world

import io.github.k1midi0.infinitesimal.session.StartingWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **到点就位的那半个地形判据**（§14.45）。
 *
 * 这一支钉三件事，而三件都是"免费的那一半"（不碰模型）：
 *
 *  1. **站得住**：脚那一格是空的、底下有东西或者就是世界地面。
 *  2. **站不住**：那一格被填实了（或被水淹了）——它要进"卡住"那条路。
 *  3. **不知道就不拦**：还没长出来的地方不该让谁都不动。
 *
 * ## 这一支逮到过的那条错
 *
 * 第一版把判据写成"脚那一格没有材质、脚下一格有材质"，而**世界地面顶上那一格本来就有土**
 * （开局那片地一直铺到 `depth.surface`）——于是**每一个站在地上的人都被判成站在土里**，症状是所有
 * 坐标恒在原地、而没有任何一处报错。[itCanStandOnTheWorldSurface] 就是拦住它的那一条。
 */
class ScheduleTest {

    private val ground = WorldDepth().surfaceCm // 12800，脚踩在地面顶上的那个 z

    private fun world() = World.from(StartingWorld.snapshot())

    private fun beat(x: Int = 1000, y: Int = 200, z: Int = ground) = Beat(600, "站在柜台后面招呼客人。", x, y, z)

    private fun character(x: Int = 0, y: Int = 0, z: Int = 0, cycle: Cycle = Cycle()) =
        Entity(id = "dwarf", kind = CHARACTER_KIND, label = "酒馆里的矮人", x = x, y = y, z = z, cycle = cycle)

    private fun routine(vararg beats: Beat) = Cycle(beats.toList(), bornAt = WorldTime(0))

    // ── 脱轨太久（§14.50） ──────────────────────────────────────────────────────────

    /**
     * **这一条判据要回答的只有"过了多久"**（§14.50）。
     *
     * 到点那条路（[refreshCyclesToClock]）看不见一个已经活跃的人——它第一行就把 `active != null` 的
     * 跳过了。所以"被锁起来、被带走"这种情形在那一层**永远报不出来**，而这一条就是补那个洞的。
     */
    @Test
    fun someoneOffTheirCycleTooLongIsReported() {
        val w = world()
        val dwarf = w.byId("dwarf")!!
        val beat = dwarf.cycle.beatAt(WorldTime(700))!!
        w.clock = WorldTime(700)
        w.put(dwarf.copy(x = 5000, y = 5000, active = Active(since = WorldTime(340))))

        val found = w.longAbsent(minutes = 360)

        assertEquals(1, found.size)
        assertEquals("dwarf", found.single().id)
        assertEquals(beat.atMinute, found.single().beat.atMinute, "它想回去的那一格要跟出来")
        assertFalse(found.single().moved, "它不进'该挪过去'那一批：它根本过不去")
        assertTrue("360" in found.single().reason.orEmpty(), "理由里要写出过了多久：${found.single().reason}")
    }

    @Test
    fun beforeTheThresholdNothingIsReported() {
        val w = world()
        val dwarf = w.byId("dwarf")!!
        w.clock = WorldTime(700)
        w.put(dwarf.copy(x = 5000, y = 5000, active = Active(since = WorldTime(400))))

        assertTrue(w.longAbsent(minutes = 360).isEmpty(), "才过了 300 分钟，还不够久")
        assertEquals(1, w.longAbsent(minutes = 300).size, "而阈值收到 300 就够久了")
    }

    @Test
    fun someoneStillOnTheirCycleIsNotLongAbsent() {
        val w = world()
        w.clock = WorldTime(700)
        // 没脱轨的人：到点那一条路管着它，而它不在这一条的路上。
        assertTrue(w.longAbsent(minutes = 1).isEmpty())
    }

    /**
     * **时钟比它变活跃那一刻还早**（读档、或者有人手动拨过钟）时也报出去。
     *
     * 那一种情形下差值永远是负的，于是"等够久"这件事**永远不会发生**——与其让它一直等不到，不如交给
     * 导演看一眼。
     */
    @Test
    fun aClockThatWentBackwardsCountsAsTooLong() {
        val w = world()
        val dwarf = w.byId("dwarf")!!
        w.clock = WorldTime(100)
        w.put(dwarf.copy(x = 5000, y = 5000, active = Active(since = WorldTime(900))))

        val found = w.longAbsent(minutes = 360)
        assertEquals(1, found.size, "负差值也算'等不到'")
        assertTrue("比那一刻还早" in found.single().reason.orEmpty(), "理由要说清是哪一种：${found.single().reason}")
    }

    @Test
    fun aNonCharacterIsIgnoredHere() {
        val w = world()
        w.clock = WorldTime(700)
        w.put(
            Entity(
                id = "cup", kind = "物品", label = "杯子",
                x = 0, y = 0, z = 0, active = Active(since = WorldTime(1)),
                cycle = routine(Beat(0, "待在桌上。", 1, 1, 1), Beat(600, "换个地方。", 2, 2, 2)),
            ),
        )

        assertTrue(w.longAbsent(minutes = 1).none { it.id == "cup" })
    }

    @Test
    fun itCanStandOnTheWorldSurface() {
        val w = world()

        // 开局那片地铺到第 256 格（= 12800 厘米），而角色的 `z` 就是脚底：**它就站在那一格上**。
        assertTrue(w.canStandAt(beat()), "地面顶上那一个 z 是站得住的")
        assertTrue(w.canStandAt(beat(x = 900, y = 320)), "换个地面上的点也一样")
    }

    @Test
    fun aFilledCellCannotBeStoodIn() {
        val w = world()

        // 把那一格填上（灌水、堆塌下来的土、砌一堵墙都长这样）。它本来就填着地基的土，所以
        // `fill` 一格不动——**要钉的是判据，不是 fill**：站在世界地面上不算"站进东西里"。
        fillStandingCell(w, 1000, 200, "water")
        assertTrue(w.canStandAt(beat()), "地基那一格填着土，而它就站在地面上——站得住")

        // 而**脚下空着**（整根柱子被挖掉、或者被传送到半空）时，那一格填着东西就是站不进去。
        digitOut(w, 1000, 200)
        fillStandingCell(w, 1000, 200, "water")
        assertFalse(w.canStandAt(beat()), "那一格被水漫过、而脚底下是空的")
    }

    private fun fillStandingCell(w: World, x: Int, y: Int, material: String) {
        w.terrain.fill(
            TerrainBox(
                x1 = terrainCellOf(x), y1 = terrainCellOf(y), z1 = terrainCellOf(ground),
                x2 = terrainCellOf(x), y2 = terrainCellOf(y), z2 = terrainCellOf(ground),
            ),
            materialId = material,
            materials = w.materials,
        )
    }

    /** 把它脚下那一格挖掉（整根柱子空心）。 */
    private fun digitOut(w: World, x: Int, y: Int) {
        w.terrain.dig(
            TerrainBox(
                x1 = terrainCellOf(x), y1 = terrainCellOf(y), z1 = 0,
                x2 = terrainCellOf(x), y2 = terrainCellOf(y), z2 = terrainCellOf(ground),
            ),
            materials = w.materials,
        )
    }

    @Test
    fun groundThatWasNeverGrownDoesNotBlockAnybody() {
        val w = world()
        // 一个远在世界地面那一圈以外的地方（那一列根本不存在）。
        assertTrue(
            w.canStandAt(beat(x = 900_000, y = 900_000)),
            "还没长出来的地方是「我们还不知道」，不是「那里被填了」",
        )

        // 整块还没生成的也同理：列在、但那一块没被标成生成过。
        val empty = World.empty()
        assertTrue(empty.canStandAt(beat()), "空世界里谁都不该被拦——测试里那些只放几个实体的世界就是它")
    }

    @Test
    fun itReportsWhoHasToMoveWithoutMovingThem() {
        val w = world()
        w.put(character(cycle = routine(Beat(600, "在柜台后面招呼客人。", 1000, 200, ground))))
        w.clock = WorldTime(700)

        val placements = w.refreshCyclesToClock()

        // **刷新自己只报不写**（§14.45）：一次到点可能同时有几个人，而"谁放行、谁卡住"要整批一起
        // 生效——写一半再判一半的话，一个刚被判"进不去"的人已经被挪到了那一格上。
        assertEquals(0, w.byId("dwarf")!!.x, "报完了，位置还没动")
        assertEquals(1, placements.size)
        assertEquals(null, placements.first().reason, "地形这一半说它进得去")
        assertTrue(placements.first().moved, "它不在那儿，所以要挪")
        assertEquals(1000, placements.first().beat.x, "去那一格")
    }

    @Test
    fun someoneAlreadyStandingOnTheBeatIsNotTouchedAtAll() {
        val w = world()
        // 它本来就在那一格上：**这一条是成本闸**——没有它，每一轮都会为每个人问一次哨兵。
        w.put(character(x = 1000, y = 200, z = ground, cycle = routine(Beat(600, "招呼客人。", 1000, 200, ground))))
        w.clock = WorldTime(700)

        val placements = w.refreshCyclesToClock()

        assertTrue(placements.isEmpty(), "它就在那儿，连一条记录都不该有——更不该被问一次")
        assertEquals(1000, w.byId("dwarf")!!.x)
    }

    @Test
    fun aBlockedCharacterIsReportedWithoutMovingThem() {
        val w = world()
        w.put(character(x = 5000, y = 5000, z = ground, cycle = routine(Beat(600, "在柜台后面。", 1000, 200, ground))))
        // 那一格现在进不去了：脚下被挖空，而那一格被水漫过。
        digitOut(w, 1000, 200)
        fillStandingCell(w, 1000, 200, "water")
        w.clock = WorldTime(700)

        val placements = w.refreshCyclesToClock()

        val dwarf = w.byId("dwarf")!!
        assertEquals(5000, dwarf.x, "**没去成就不挪**——把它放到那一格上是会话那一层的事")
        assertTrue(placements.first().reason?.contains("站不住") == true, "理由要说出来：${placements.first().reason}")
    }

    @Test
    fun whoeverIsOffTheirCycleIsLeftAlone() {
        val w = world()
        w.put(
            character(x = 5000, y = 5000, z = ground, cycle = routine(Beat(600, "招呼客人。", 1000, 200, ground)))
                .copy(active = Active(since = WorldTime(660))),
        )
        w.clock = WorldTime(700)

        val placements = w.refreshCyclesToClock()

        assertEquals(5000, w.byId("dwarf")!!.x, "活跃期的人不许被作息拉回去")
        assertTrue(placements.isEmpty(), "它不在这一趟里，连问都不用问")
    }

    @Test
    fun onlyCharactersWithACycleAreMoved() {
        val w = world()
        w.put(character(x = 77, y = 88)) // 空循环
        w.put(Entity(id = "cup", kind = "器具", label = "杯子", x = 11, y = 22, z = 33, cycle = routine(Beat(600, "x", 1000, 200, ground))))
        w.clock = WorldTime(700)

        w.refreshCyclesToClock()

        assertEquals(77, w.byId("dwarf")!!.x, "没有循环 = 没有答案，不是 (0,0,0)")
        assertEquals(11, w.byId("cup")!!.x, "作息只管角色")
    }

    @Test
    fun repeatedRunsSayTheSameThing() {
        val w = world()
        w.put(character(x = 1000, y = 200, z = ground, cycle = routine(Beat(600, "招呼客人。", 1000, 200, ground))))
        w.clock = WorldTime(700)

        val first = w.refreshCyclesToClock()
        val second = w.refreshCyclesToClock()

        assertTrue(first.isEmpty(), "它本来就站在那一格上——第一遍就没什么可报的")
        assertEquals(first, second, "对一遍是幂等的——它每回合开头都会被调到")
    }
}
