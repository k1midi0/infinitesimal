package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * **发言的顺序**（用户定，2026-09-26）。
 *
 * 这一套的形状是一个**队列**，而不是"每人身上一个优先值"。那条选择值得被钉住：用户要的是
 * 「创建和修改的时候直接选择插入的位置，剩下的顺延往后，就像排队插队一样，**不会出现重合的**」。
 * 于是"两个 NPC 优先值相同"这件事没有地方可以表达——下面 `insertingSomeoneAlreadyInTheQueue`
 * 与 `theQueueNeverHoldsSomeoneTwice` 两条就是这件事的证据。
 */
class TalkTest {

    private fun worldOf(vararg ids: String): World {
        val w = World.empty()
        ids.forEach { w.put(Entity(id = it, kind = "角色", label = it, x = 0, y = 0, z = 0)) }
        return w
    }

    @Test
    fun theQueueOrdersWhoSpeaksFirst() {
        val w = worldOf("dwarf", "bard", "guard")
        w.joinTheQueue("dwarf")
        w.joinTheQueue("bard")
        w.joinTheQueue("guard")

        assertEquals(listOf("dwarf", "bard", "guard"), w.speakQueue)
        assertEquals(listOf("dwarf", "bard", "guard"), w.speakersAmong(listOf("guard", "bard", "dwarf")))
    }

    @Test
    fun insertingSomeoneAlreadyInTheQueueMovesThemInsteadOfDuplicatingThem() {
        val w = worldOf("dwarf", "bard", "guard")
        listOf("dwarf", "bard", "guard").forEach { w.joinTheQueue(it) }

        // **bard 插队到最前面。** 它已经在名单上，所以先摘掉、再插进去——名单里始终只有它一个。
        w.insertIntoQueue("bard", 0)

        assertEquals(listOf("bard", "dwarf", "guard"), w.speakQueue, "后面那两位顺延，而 bard 没有出现两次")
        assertEquals(0, w.speakPositionOf("bard"))
        assertEquals(1, w.speakPositionOf("dwarf"))
        assertEquals(2, w.speakPositionOf("guard"))
    }

    @Test
    fun theQueueNeverHoldsSomeoneTwice() {
        val w = worldOf("dwarf", "bard")
        w.joinTheQueue("dwarf")
        w.joinTheQueue("bard")
        w.insertIntoQueue("dwarf", 1)
        w.joinTheQueue("dwarf")

        assertEquals(2, w.speakQueue.size, "名单里只有两个人：${w.speakQueue}")
        assertTrue(w.speakQueueProblems().isEmpty(), "名单自己是干净的：${w.speakQueueProblems()}")
    }

    @Test
    fun insertingAtTheSamePositionTwiceKeepsThemAdjacentNotEqual() {
        val w = worldOf("a", "b", "c")
        listOf("a", "b", "c").forEach { w.joinTheQueue(it) }

        // **"插到第 1 位"总是把第 1 位让给新来的人**，前面那位留在 0、后面那位顺延到 2。这就是
        // "排队插队"的字面意思：位置是**位次**，不是每人的一个数——所以两次插同一个位次不会撞。
        w.insertIntoQueue("b", 1)
        assertEquals(listOf("a", "b", "c"), w.speakQueue)

        w.insertIntoQueue("c", 1)
        assertEquals(listOf("a", "c", "b"), w.speakQueue, "c 占了第 1 位，b 顺延到第 2 位")
    }

    @Test
    fun joiningTheQueueAgainDoesNotSendYouToTheBack() {
        val w = worldOf("dwarf", "bard")
        w.joinTheQueue("dwarf")
        w.joinTheQueue("bard")
        // 一个早就在排队的角色，不该因为再被提起一次就失去它的位置。少了这一条，每次"谁进来了"
        // 都会把它甩到队尾，而那个症状（"先说话的人怎么老在换"）看不出来是为什么。
        w.joinTheQueue("dwarf")

        assertEquals(listOf("dwarf", "bard"), w.speakQueue)
    }

    @Test
    fun somebodyNotInTheQueueSpeaksLast() {
        val w = worldOf("dwarf", "bard")
        w.joinTheQueue("bard")

        // **没排过队的人照样能说话**（世界不必先给人排座次才能跑），只是排在最后。而两个都没排队的
        // 之间按 id 排——不为别的，只为同一份世界每次跑出同一个顺序。
        assertEquals(listOf("bard", "dwarf"), w.speakersAmong(listOf("dwarf", "bard")))
        assertNull(w.speakPositionOf("dwarf"))
    }

    @Test
    fun anIdThatIsNotInTheWorldIsSkippedRatherThanCrashing() {
        val w = worldOf("dwarf", "bard")
        listOf("dwarf", "bard").forEach { w.joinTheQueue(it) }

        // **先排队、之后才不在世界上**（走了、被删了）。`joinTheQueue` 不让不存在的人进队，所以
        // 只有直接改名单造得出这一格——而它正是那条"名单里留着暂时不在场的人"的设计要处理的形状：
        // 跳过而不是清掉，因为位置是存下来的值，它可能只是走到别处去了。
        w.speakQueue = listOf("dwarf", "ghost", "bard")

        assertEquals(listOf("dwarf", "bard"), w.speakersAmong(listOf("dwarf", "bard", "ghost")))
        assertTrue(w.speakQueueProblems().any { "ghost" in it }, "诊断里要看得出它不在世界上")
    }

    @Test
    fun leavingTheQueueMovesEverybodyBehindForward() {
        val w = worldOf("dwarf", "bard", "guard")
        listOf("dwarf", "bard", "guard").forEach { w.joinTheQueue(it) }

        w.leaveTheQueue("bard")

        assertEquals(listOf("dwarf", "guard"), w.speakQueue)
        assertEquals(1, w.speakPositionOf("guard"), "后面那位往前挪了一位")
    }

    @Test
    fun insertingOutOfRangeClampsInsteadOfFailingAt() {
        val w = worldOf("dwarf", "bard")
        w.joinTheQueue("dwarf")

        // 调用方给的是**意图**（"让它先手"、"往后挪很多位"），所以夹一下比抛出去更接近它要的意思。
        w.insertIntoQueue("bard", -5)
        assertEquals(listOf("bard", "dwarf"), w.speakQueue)

        w.insertIntoQueue("bard", 99)
        assertEquals(listOf("dwarf", "bard"), w.speakQueue)
    }

    @Test
    fun theQueueRidesAlongWithTheSnapshot() {
        val w = worldOf("dwarf", "bard")
        listOf("dwarf", "bard").forEach { w.joinTheQueue(it) }
        w.insertIntoQueue("bard", 0)

        val back = World.from(w.snapshot())

        // 它是世界的事实（"谁先说话"不该随重启变），所以它跟快照走。
        assertEquals(listOf("bard", "dwarf"), back.speakQueue)
    }

    @Test
    fun joiningTheQueueDoesNotPutSomebodyWhoIsNotInTheWorldOnIt() {
        val w = worldOf("dwarf")
        w.joinTheQueue("nobody")

        assertTrue(w.speakQueue.isEmpty(), "世界上没有这个人，排他进队毫无意义")
    }
}
