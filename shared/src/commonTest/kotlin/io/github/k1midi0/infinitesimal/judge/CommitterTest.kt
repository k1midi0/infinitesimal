package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 代码段：校验与原子提交。**不需要网络**——设计里的全部保证都在这一半身上。
 *
 * 模型段只是"把工具调用解析成 [Consequence]"，机械得很；真正决定这套东西可信不可信的，是下面这些：
 * 一批里第 3 条非法时世界有没有被写进去一点、变化等级有没有从工具推出来、描述什么时候该作废。
 */
class CommitterTest {

    private fun entity(
        id: String,
        parent: String? = null,
        sentences: List<String> = listOf("它看上去是这样。"),
        attributes: Map<String, String> = emptyMap(),
    ) = Entity(
        id = id, kind = "家具", label = id, x = 0, y = 0, z = 0, attributes = attributes, sentences = sentences,
    )

    private fun worldOf(vararg entities: Entity): World = World.from(WorldSnapshot(entities.toList()))

    private fun Entity.describe(): String = text()

    // ── 等级是从工具推出来的，不是模型填的 ────────────────────────────────────────────

    @Test
    fun movingDoesNotTouchTheDescription() {
        // 描述只讲物体自身（§7.6），所以把杯子从桌上挪到地上不烧一次渲染。
        val world = worldOf(entity("cup"))
        val result = Committer.apply(world, listOf(Consequence.Move("cup", 5, 3, 0)))

        assertTrue(result is Committer.Applied)
        val moved = assertNotNull(world.byId("cup"))
        assertEquals(5, moved.x)
        assertEquals(listOf("它看上去是这样。"), moved.sentences, "移动不该动描述")
    }

    @Test
    fun changingAnAttributeInvalidatesTheDescription() {
        val world = worldOf(entity("table", attributes = mapOf("状态" to "积灰")))
        Committer.apply(world, listOf(Consequence.SetAttribute("table", "状态", "擦干净")))

        val table = assertNotNull(world.byId("table"))
        assertEquals("擦干净", table.attributes["状态"])
        assertEquals(emptyList(), table.sentences, "属性变了，既有描述就不再可信（保守地重渲染一次）")
    }

    @Test
    fun appearanceChangeKeepsTheIdentity() {
        val world = worldOf(entity("table"))
        Committer.apply(world, listOf(Consequence.ChangeAppearance("table")))

        val table = assertNotNull(world.byId("table"), "外表变化不该改变身份")
        assertEquals(emptyList(), table.sentences)
    }

    @Test
    fun replaceIsANewIdentity() {
        val world = worldOf(entity("table"))
        Committer.apply(
            world,
            listOf(Consequence.Replace("table", entity("splintered", sentences = emptyList()))),
        )

        assertNull(world.byId("table"), "彻底改变之后旧 id 不该还在世界里")
        val fresh = assertNotNull(world.byId("splintered"))
        assertEquals(emptyList(), fresh.sentences, "新物品还没有描述，等着被渲染")
    }

    // ── 原子性：全成或全不成 ──────────────────────────────────────────────────────────

    @Test
    fun oneBadConsequenceUnwritesTheWholeBatch() {
        val world = worldOf(entity("cup"), entity("table"))
        val before = world.snapshot()

        val result = Committer.apply(
            world,
            listOf(
                Consequence.Move("cup", 9, 9, 0),
                Consequence.SetAttribute("table", "状态", "裂了"),
                Consequence.Move("ghost", 1, 1, 0), // 世界里没有这个东西
            ),
        )

        assertTrue(result is Committer.Rejected)
        assertTrue((result as Committer.Rejected).problems.any { it.contains("ghost") })
        assertEquals(before, world.snapshot(), "第 3 条不合法时，前两条也一个字都不许写进去")
    }

    // ── 影子状态：一批里后面的后果不能引用已被收走的东西 ──────────────────────────────

    @Test
    fun destroyedThingsCannotBeReferencedLaterInTheSameBatch() {
        val world = worldOf(entity("cup"))
        val result = Committer.apply(
            world,
            listOf(Consequence.Destroy("cup"), Consequence.Move("cup", 1, 1, 0)),
        )

        assertTrue(result is Committer.Rejected)
        assertTrue((result as Committer.Rejected).problems.any { it.contains("收走") })
        assertNotNull(world.byId("cup"), "全不成，杯子还在")
    }

    @Test
    fun spawnedThingsCanBeReferencedLaterInTheSameBatch() {
        // 同一个动作的后果里，"桌上升出一只杯子、然后把它推到桌边"是合理的一批。
        val world = worldOf(entity("table"))
        val result = Committer.apply(
            world,
            listOf(
                Consequence.Spawn(entity("new_cup", sentences = emptyList())),
                Consequence.Move("new_cup", 7, 2, 1),
            ),
        )

        assertTrue(result is Committer.Applied, "同批里的新增应当能被后面的后果引用")
        val cup = assertNotNull(world.byId("new_cup"))
        assertEquals(7, cup.x)
    }

    @Test
    fun replacingAndThenTouchingTheOldIdIsRejected() {
        val world = worldOf(entity("table"))
        val result = Committer.apply(
            world,
            listOf(
                Consequence.Replace("table", entity("splintered", sentences = emptyList())),
                Consequence.SetAttribute("table", "状态", "又好了"),
            ),
        )

        assertTrue(result is Committer.Rejected, "旧 id 已经被换掉，后面的后果不能再碰它")
    }

    // ── 结构性非法：这些**退回重试**，不示给玩家（§4.3） ─────────────────────────────

    @Test
    fun replaceWithTheSameIdIsRejected() {
        val world = worldOf(entity("table"))
        val result = Committer.apply(world, listOf(Consequence.Replace("table", entity("table"))))

        assertTrue(result is Committer.Rejected)
        assertTrue(
            (result as Committer.Rejected).problems.any { it.contains("新 id 必须与旧 id 不同") },
            "同 id 的改动是外表变化，用错了工具要说清楚，而不是静悄悄地什么都没发生",
        )
    }

    @Test
    fun spawningAnExistingIdIsRejected() {
        val world = worldOf(entity("cup"))
        val result = Committer.apply(world, listOf(Consequence.Spawn(entity("cup"))))

        assertTrue(result is Committer.Rejected)
    }

    @Test
    fun anEmptyAttributeNameIsRejected() {
        val world = worldOf(entity("cup"))
        val result = Committer.apply(world, listOf(Consequence.SetAttribute("cup", "  ", "x")))

        assertTrue(result is Committer.Rejected)
    }

    // ── 投递对象只是被带过去，不在这里判定 ────────────────────────────────────────────

    @Test
    fun affectedActorsAreCarriedThrough() {
        val world = worldOf(entity("cup"))
        val result = Committer.apply(world, listOf(Consequence.Move("cup", 1, 1, 0)), affected = listOf("dwarf"))

        assertTrue(result is Committer.Applied)
        assertEquals(setOf("dwarf"), (result as Committer.Applied).affected)
    }
}
