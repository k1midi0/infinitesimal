package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 笔记的形状（§6.3、§6.4、§6.6）。
 *
 * 这里钉的是三件在提示词里看不到、但错了会静默出事的事：
 *
 *  1. **被替代的不算数**——它与"删掉"的区别只有这一条函数在守（[live]）。
 *  2. **编号不复用**——`nextId` 数的是已有那些，所以一个删不掉、只会被替代的表里不会撞号。
 *  3. **替代关系是单向可查的**——[replaced] 只给测试与探针看，注入永远走 [live]。
 */
class NoteTest {

    private fun note(id: String, text: String, supersedes: String? = null) =
        Note(id = id, text = text, at = WorldTime.of(1, 18), supersedes = supersedes)

    @Test
    fun asupersededNoteStaysInTheListButLeavesTheLiveSet() {
        val notes = listOf(
            note("n1", "玩家欠我一杯酒。"),
            note("n2", "那杯酒他还了。", supersedes = "n1"),
        )

        // **它没有被删掉**（§6.4）：事后要能回答"它当时为什么这么想"。
        assertEquals(2, notes.size)
        // 但它不再出现在眼前——否则模型会同时相信"欠我一杯酒"与"他还了"。
        assertEquals(listOf("n2"), notes.live().map { it.id })
        assertEquals(listOf("n1"), notes.replaced().map { it.id })
    }

    @Test
    fun aReplacementChainOnlyLeavesTheNewestStanding() {
        val notes = listOf(
            note("n1", "他欠我一杯酒。"),
            note("n2", "他说下次给钱。", supersedes = "n1"),
            note("n3", "钱已经给了。", supersedes = "n2"),
        )

        assertEquals(listOf("n3"), notes.live().map { it.id })
    }

    @Test
    fun theNextIdCountsWhatIsAlreadyThere() {
        assertEquals("n1", emptyList<Note>().nextId())
        assertEquals("n2", listOf(note("n1", "一")).nextId())
        // **不复用被替代掉的编号吗？——复用了也没关系，因为那个编号已经不在注入里。** 这里钉的是
        // "从已有那些里数出来"这条实现：跳号（n1 → n3）说明它在另存一个计数器，而那正是会与这张表
        // 对不上的东西。
        assertEquals(
            "n3",
            listOf(note("n1", "一"), note("n2", "二", supersedes = "n1")).nextId(),
        )
    }

    @Test
    fun nothingIsReplacedWhenNothingPointsAtIt() {
        val notes = listOf(note("n1", "一"), note("n2", "二"))
        assertEquals(listOf("n1", "n2"), notes.live().map { it.id })
        assertTrue(notes.replaced().isEmpty())
    }

    @Test
    fun tagsAndTimesRideAlong() {
        val n = Note(id = "n4", text = "东街木匠说下周有货。", tag = "事", at = WorldTime.of(2, 9, 30))
        assertEquals("事", n.tag)
        assertEquals(WorldTime.of(2, 9, 30), n.at)
        assertEquals(null, n.supersedes)
    }
}
