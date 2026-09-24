package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.RuleEntry
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 规则书给判定看的那个形状（§12 第 12 项）。
 *
 * 这里钉的是**代码那一半**：排序、分片、覆盖、空片不出现。至于"模型有没有照它裁"是联机的事，
 * 不在这里断言——离线测不出模型的判断，硬造一个只会得到一条永远为真的断言。
 */
class RulebookViewTest {

    private fun world(
        worldRules: List<RuleEntry> = emptyList(),
        worldPriority: Int? = null,
        entities: List<Entity> = StartingWorld.tavern(),
    ): World = World.from(WorldSnapshot(entities, Rulebook(worldRules, worldPriority)))

    private val fire = RuleEntry("火会烧起来。")

    @Test
    fun aThingWithNeitherRulesNorAttributesSaysNothing() {
        val w = World.from(WorldSnapshot(listOf(Entity("rock", "杂物", "石头", 0, 0, 0))))
        assertEquals("", RulebookView.forPrefix(w, "rock"))
        assertEquals("", RulebookView.forTargets(w, "rock", listOf("rock")))
    }

    @Test
    fun attributesRideAlongAndAnEmptyWorldRulebookDisappears() {
        // 开局世界里没有规则书，但有属性词条。**属性永远在**——它是状态，判定要靠它。
        val w = world()
        val prefix = RulebookView.forPrefix(w, "player")
        assertTrue(prefix.contains("属性："), prefix)
        assertTrue(prefix.contains("腰间=挂着一把短柄斧"), prefix)
        assertFalse(prefix.contains("世界规则"), "空的规则书不该占一行：\n$prefix")
    }

    @Test
    fun theMoreSpecificRulebookComesFirst() {
        // 世界 10、角色 20、物品 30：**数字大的先算**，所以顺序是反过来的。
        val entities = StartingWorld.tavern().map {
            when (it.id) {
                "player" -> it.copy(rulebook = Rulebook(listOf(RuleEntry("你惯用一把短柄斧。"))))
                "oak_table" -> it.copy(rulebook = Rulebook(listOf(RuleEntry("这是一张橡木桌。"))))
                else -> it
            }
        }
        val w = world(worldRules = listOf(fire), entities = entities)

        val prefix = RulebookView.forPrefix(w, "player")
        assertTrue(
            prefix.indexOf("「你」") < prefix.indexOf("世界规则"),
            "世界那一片（10）该排在角色（20）之后：\n$prefix",
        )
        assertTrue(prefix.contains("优先级 10"), prefix)
        assertTrue(prefix.contains("优先级 20"), prefix)
        assertFalse(prefix.contains("橡木桌"), "物品的规则书不该进共用前缀：\n$prefix")

        val targets = RulebookView.forTargets(w, "player", listOf("oak_table"))
        assertTrue(targets.contains("「木桌」（id=oak_table）（优先级 30）"), targets)
    }

    @Test
    fun aRulebookCanOverrideItsOwnPriority() {
        val entities = StartingWorld.tavern().map {
            if (it.id == "oak_table") {
                it.copy(rulebook = Rulebook(listOf(RuleEntry("这是这个世界里唯一不怕火的东西。")), priority = 99))
            } else {
                it
            }
        }
        val w = world(worldRules = listOf(fire), entities = entities)

        val prefix = RulebookView.forPrefix(w, "player")
        val targets = RulebookView.forTargets(w, "player", listOf("oak_table"))
        // 分开渲染，但两边都得带上那个被改过的数字——模型要靠它决定信谁。
        assertTrue(targets.contains("优先级 99"), targets)
        assertTrue(prefix.contains("火会烧起来。"), prefix)
    }

    @Test
    fun aSingleEntryCanOutrankItsOwnRulebook() {
        val book = Rulebook(
            listOf(
                RuleEntry("这是一张普通的木桌。"),
                RuleEntry("这张桌子是防火石做的。", priority = 99),
            ),
        )
        val entities = StartingWorld.tavern().map { if (it.id == "oak_table") it.copy(rulebook = book) else it }
        val w = world(worldRules = listOf(fire), entities = entities)

        val text = RulebookView.forTargets(w, "player", listOf("oak_table"))
        assertTrue(
            text.indexOf("防火石") < text.indexOf("普通的木桌"),
            "单独抬过的那条该排在前面：\n$text",
        )
    }

    @Test
    fun valuesRideAlongWithTheSentence() {
        val entities = StartingWorld.tavern().map {
            if (it.id == "oak_table") {
                it.copy(rulebook = Rulebook(listOf(RuleEntry("这是一张橡木桌。", values = mapOf("硬度" to "6")))))
            } else {
                it
            }
        }
        val w = world(entities = entities)

        assertTrue(RulebookView.forTargets(w, "player", listOf("oak_table")).contains("硬度=6"))
    }
}
