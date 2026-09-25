package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 记录层（§6.1、§14.37）。
 *
 * 这一层要钉住的四件事，每一条都对应一个"错了不会报、只会悄悄给出错答案"的地方：
 *
 * 1. **投递是每人一份编号列表**，不是全局共享——信息隔离靠它，错了就是"隔壁镇的人也收到了"。
 * 2. **一次提交的全部后果挂在同一条记录上**（`Event.effects`），裁窗口不能把它们切开。
 * 3. **快照要连 `next` 一起存回来**，否则裁过事件之后会撞号，而撞号的症状是两个不同的事件共用一个
 *    编号。
 * 4. **措辞由代码拼**，所以同一件事在任何人的窗口里都是同一句话。
 */
class EventLogTest {

    private val at = World.StartingClock

    @Test
    fun anEventGoesToTheStreamAndToEveryoneItWasDeliveredTo() {
        val log = EventLog.empty()
        val event = log.append(at, EventKind.SAY, "player", "你", "你好啊。", to = listOf("dwarf", "bard"))

        assertEquals(1, log.size)
        assertEquals(listOf(event.seq), log.deliveredTo("dwarf"))
        assertEquals(listOf(event.seq), log.deliveredTo("bard"))
        assertEquals(listOf(event), log.windowOf("dwarf", 10))
        // **没被投递的人看不到它。** 这一条就是「信息隔离」的全部——没有它，事件流会变成一个
        // "所有人都知道一切"的全局广播。
        assertEquals(emptyList(), log.windowOf("someone_far_away", 10))
    }

    @Test
    fun deliveringTheSameNumberTwiceDoesNotDuplicateIt() {
        val log = EventLog.empty()
        val event = log.append(at, EventKind.SAY, "player", "你", "你好啊。", to = listOf("dwarf"))
        log.deliver(listOf("dwarf", "dwarf"), event.seq)

        assertEquals(listOf(event.seq), log.deliveredTo("dwarf"))
    }

    @Test
    fun theWindowKeepsTheLatestAndInOrder() {
        val log = EventLog.empty()
        repeat(5) { i -> log.append(at, EventKind.CLOCK, null, "", "第 $i 件事", to = listOf("dwarf")) }

        assertEquals(listOf("第 2 件事", "第 3 件事", "第 4 件事"), log.windowOf("dwarf", 3).map { it.text })
        assertEquals(emptyList(), log.windowOf("dwarf", 0))
    }

    @Test
    fun allTheEffectsOfOneSubmitRideOnOneEvent() {
        val log = EventLog.empty()
        val event = log.append(
            at = at,
            kind = EventKind.ACT,
            actorId = "player",
            actorLabel = "你",
            text = "我把桌子劈了",
            effects = listOf("木桌 变成了「裂成两半的木桌」", "油灯 挪到了 (1200, 500, 0) 厘米"),
            to = listOf("dwarf"),
        )

        assertEquals(2, event.effects.size)
        // 窗口按**条**裁，而"一次提交产生的全部后果不可切开"落在 effects 是一个整体上：裁到这条，
        // 那两条后果一起进出，不会出现"看到油灯掉了却没看到桌子被劈"。
        assertEquals(listOf(event), log.windowOf("dwarf", 1))
    }

    @Test
    fun aSnapshotComesBackWholeAndTheNextNumberDoesNotCollide() {
        val log = EventLog.empty()
        log.append(at, EventKind.SAY, "player", "你", "第一句", to = listOf("dwarf"))
        log.append(at, EventKind.SAY, "dwarf", "酒馆里的矮人", "第二句", to = listOf("player"))

        val back = EventLog.from(log.snapshot())
        assertEquals(2, back.size)
        assertEquals(listOf("第一句"), back.windowOf("dwarf", 10).map { it.text })

        // **编号从快照里读回来，不是从条数推。** 推出来的话，一个被裁过事件的世界会在下一次追加时
        // 撞号——而撞号的症状是两个不同的事件共用一个 seq，谁的窗口里都会出现重影。
        assertEquals(3L, back.append(at, EventKind.CLOCK, null, "", "第三件").seq)
    }

    @Test
    fun theLineCarriesBothTheIdAndTheName() {
        val log = EventLog.empty()
        val event = log.append(at, EventKind.SAY, "dwarf", "酒馆里的矮人", "……嗯。", to = listOf("player"))

        val line = event.line()
        // **id 与名字都给**：只印 label 会让模型把说话的人当成自己（`Npc` 那段提示词里踩过）。
        assertEquals(true, "酒馆里的矮人" in line && "dwarf" in line)
        assertEquals(true, "……嗯。" in line)
    }
}
