package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 三只改地形的手**交给模型的那一面**：schema 与那一节材质表。
 *
 * 这一份测的是提示词工程里最容易无声坏掉的两处：
 *
 *  1. **必填名单。** `tool(...)` 把所有参数默认塞进 `required`，只有显式列进 `optional` 的才减掉。
 *     漏掉一个坐标的后果不是报错，而是模型可以合法地不填它——于是框会塌掉一条边。
 *  2. **材质表要真的出现在前缀里。** 三只手的说明都写着"id 从【世界材质表】里取"，而那一节是
 *     [MaterialView] 拼的。它要是没接上，提示词里就是一句指向空气的引用，模型只能猜材质名。
 */
class TerrainToolsTest {

    private fun tool(name: String) = JudgeTools.EFFECTS.first { it.function.name == name }

    private fun required(name: String): List<String> = tool(name).function.parameters.required

    private val boxNames = listOf("x1", "y1", "z1", "x2", "y2", "z2")

    @Test
    fun allThreeTerrainToolsAreInTheEffectsGroup() {
        val names = JudgeTools.EFFECTS.map { it.function.name }
        assertTrue(JudgeTools.FILL_TERRAIN in names)
        assertTrue(JudgeTools.DIG_TERRAIN in names)
        assertTrue(JudgeTools.REPLACE_TERRAIN in names)
    }

    @Test
    fun everyCoordinateIsRequired() {
        // 六个坐标**一个都不能是可选的**：漏填一个的框仍然合法，于是它会被提交，而模型永远不知道
        // 自己的参数掉了一半（见 `BoxArgs` 的注释）。
        for (name in listOf(JudgeTools.FILL_TERRAIN, JudgeTools.DIG_TERRAIN, JudgeTools.REPLACE_TERRAIN)) {
            val missing = boxNames - required(name).toSet()
            assertTrue(missing.isEmpty(), "$name 少了必填坐标 $missing")
        }
    }

    @Test
    fun theMaterialParametersAreWhereTheyBelong() {
        assertEquals(boxNames + "material", required(JudgeTools.FILL_TERRAIN))
        // 挖去**没有材质参数**——"空气"不是一种材质（A.15）。
        assertEquals(boxNames, required(JudgeTools.DIG_TERRAIN))
        assertEquals(boxNames + listOf("from", "to"), required(JudgeTools.REPLACE_TERRAIN))
    }

    @Test
    fun theMaterialTableIsSpelledOutForTheModel() {
        val world = World.from(WorldSnapshot(listOf(Entity("cup", "器具", "酒杯", 0, 0, 0))))
        val section = MaterialView.forPrefix(world)
        for (material in world.materials.all) {
            assertTrue(
                section.contains(material.id),
                "材质表那一节里少了 ${material.id}——而三只手的说明都让模型从那里取 id",
            )
        }
        // 「挖不动」那一栏必须给：它是 dig / replace 会被驳回的唯一原因，而模型看不到别的线索。
        assertTrue(section.contains("挖不动"), "岩层那一行要标出来，否则模型只会反复撞上驳回")
    }
}
