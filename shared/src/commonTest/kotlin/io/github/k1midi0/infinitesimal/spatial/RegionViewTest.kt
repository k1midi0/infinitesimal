package io.github.k1midi0.infinitesimal.spatial

import io.github.k1midi0.infinitesimal.world.CM_PER_METRE
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 判定拿到的那一段文字里，**世界对一样东西的真相有没有送到**。
 *
 * 这一支是被一次假驳回逼出来的（§14.24）：玩家说「我把桌上那杯麦酒端起来」，开局世界的酒杯
 * `attributes` 里明明白白写着「内容物=半杯麦酒」，可判定驳回了——理由是「这一片里没有麦酒」。
 * **驳回没有问题，是它手里只有一个光秃秃的名字**：`describe` 原先只印 label、id 和坐标。
 */
class RegionViewTest {

    private fun thing(
        id: String,
        label: String,
        x: Int,
        y: Int,
        z: Int = 0,
        attributes: Map<String, String> = emptyMap(),
    ) = Entity(
        id = id, kind = "东西", label = label,
        x = x * CM_PER_METRE, y = y * CM_PER_METRE, z = z * CM_PER_METRE,
        attributes = attributes,
    )

    private fun world(vararg e: Entity) = World.from(WorldSnapshot(e.toList()))

    private fun view(world: World, actor: String, vararg centers: String): String =
        RegionView.render(world, RegionBuilder.of(world, listOf(actor) + centers), actor)

    /**
     * **这一条就是那个假驳回的反面。**
     *
     * 「内容物=半杯麦酒」在文字里，判定才可能把玩家说的「麦酒」对上这只酒杯。
     */
    @Test
    fun theThingsAttributesReachTheJudge() {
        val w = world(
            thing("player", "你", 12, 3),
            thing("wine_cup", "酒杯", 12, 4, 1, attributes = mapOf("材质" to "深色木制", "内容物" to "半杯麦酒")),
        )

        val text = view(w, "player", "wine_cup")

        assertTrue(text.contains("内容物=半杯麦酒"), "属性没送到，判定就只剩一个名字：\n$text")
        assertTrue(text.contains("材质=深色木制"), text)
    }

    @Test
    fun anEntityWithoutAttributesIsStillListedPlainly() {
        val w = world(
            thing("player", "你", 12, 3),
            thing("stone", "石头", 12, 4),
        )

        val text = view(w, "player", "stone")

        // 那一行必须**正好在坐标之后收住**——不能多一个空的分隔符，也不能多印别的。
        // （`RegionView` 最后会 `trimEnd`，所以这里不要求行尾那个换行。）
        assertEquals(
            "  a 石头   id=stone   (1200, 400, 0) 厘米",
            text.lines().last { it.contains("石头") },
            "没有属性时那一行不该再拖别的东西：\n$text",
        )
    }

    /**
     * **坐标还在**，加属性没有把它挤掉——"够不够得着"要靠坐标（虽然现在由 `distance` 那只手算）。
     */
    @Test
    fun theCoordinatesAreStillThere() {
        val w = world(
            thing("player", "你", 12, 3),
            thing("wine_cup", "酒杯", 12, 4, 1, attributes = mapOf("内容物" to "半杯麦酒")),
        )

        val text = view(w, "player", "wine_cup")

        assertTrue(text.contains("(1200, 400, 100) 厘米"), text)
    }
}
