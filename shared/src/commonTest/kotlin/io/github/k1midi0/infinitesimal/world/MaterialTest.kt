package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 材质表（附录 A.15）。
 *
 * 这里钉的是**表本身**的几条硬性质：七种开局材质、id 不重复、字符是 ASCII、未知 id 不静默变成
 * 空白。至于"模型会不会用对材质"，那是联机的事。
 */
class MaterialTest {

    private val table = MaterialTable()

    @Test
    fun theSevenStartingMaterialsAreThere() {
        assertEquals(
            listOf("soil", "stone", "bedrock", "wood", "grass", "water", "road"),
            table.all.map { it.id },
        )
        assertEquals(emptyList(), table.problems())
    }

    /**
     * **"空气"不是一种材质**（2026-09-26 定的）。
     *
     * 挖去就是把那一格从地形里去掉，而稀疏存天然表达这件事。一旦表里有一个什么都不代表的 id，
     * 所有读地形的地方就得各加一个"这个 id 要跳过"的判断——那种判断漏一处，地上就会多出一片
     * 谁也说不清的东西。
     */
    @Test
    fun airIsNotAMaterial() {
        assertNull(table.byId("air"))
        assertNull(table.byId("空气"))
        assertNull(table.byId("empty"))
    }

    @Test
    fun bedrockIsTheOnlyUnbreakableOne() {
        assertFalse(table.isBreakable("bedrock"))
        for (id in listOf("soil", "stone", "wood", "grass", "water", "road")) {
            assertTrue(table.isBreakable(id), "$id 应该能挖")
        }
    }

    /** **不认识的 id 一律按不可破坏算。** 反过来（默认能挖）会让一次笔误挖穿世界底。 */
    @Test
    fun anUnknownMaterialIsNotBreakable() {
        assertFalse(table.isBreakable("unobtainium"))
    }

    /**
     * 网格里对齐全靠等宽，而中文在等宽字体里占两列。
     *
     * 这一条是**判据而不是口号**：字符一旦越过 ASCII，`RegionView` 那张 `%3s` 的网格整张推歪，
     * 而歪掉的网格等于没画。
     */
    @Test
    fun everyDisplayCharIsAscii() {
        for (m in table.all) {
            assertTrue(m.display.code < 128, "材质「${m.id}」的字符是 '${m.display}'，不是 ASCII")
        }
    }

    /** 未知材质的字符是 `?`——**不能是空格**，那会让"不认识"与"没有地形"长得一模一样。 */
    @Test
    fun anUnknownMaterialDrawsAQuestionMarkNotASpace() {
        assertEquals('?', table.display("unobtainium"))
        assertTrue(table.display("unobtainium") != ' ')
    }

    @Test
    fun aRepeatedIdIsReported() {
        val broken = MaterialTable(listOf(Material("soil", "土", ',', 0), Material("soil", "又一份土", ',', 0)))
        assertEquals(listOf("材质 id「soil」出现了两次"), broken.problems())
    }

    @Test
    fun theNameOfAnUnknownIdIsTheIdItself() {
        assertEquals("unobtainium", table.name("unobtainium"))
        assertEquals("木地板", table.name("wood"))
    }

    /** 材质那份规则书的默认优先级**排在世界 10 与角色 20 之间**（附录 A.15）。 */
    @Test
    fun materialSitsBetweenTheWorldAndACharacter() {
        assertTrue(RulebookDefaults.WORLD < RulebookDefaults.MATERIAL)
        assertTrue(RulebookDefaults.MATERIAL < RulebookDefaults.CHARACTER)
        assertTrue(RulebookDefaults.CHARACTER < RulebookDefaults.OBJECT)
        assertEquals(15, RulebookDefaults.MATERIAL)
    }

    @Test
    fun aMaterialCarriesAttributesAndItsOwnRulebook() {
        val ice = Material(
            id = "ice",
            name = "冰面",
            display = 'i',
            color = 0xFFAAE0FF.toInt(),
            attributes = mapOf("摩擦" to "很低"),
            rulebook = Rulebook(listOf(RuleEntry("冰面站不住脚。"))),
        )
        val withIce = MaterialTable(table.all + ice)
        val back = assertNotNull(withIce.byId("ice"))
        assertEquals("很低", back.attributes["摩擦"])
        assertEquals(1, back.rulebook.entries.size)
    }
}
