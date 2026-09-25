package io.github.k1midi0.infinitesimal.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * 出厂预设包（§12 第 36 项）。
 *
 * 这一条钉的是**打包**，不是内容："这批形状好不好看"是审的事，而"随游戏带来的那份东西能不能装进
 * 库里"是加载的事——它坏了的表现是每一次生成都退回去现造，而那要几十秒一次、还不报错。
 *
 * 空包在这里**是合法的**（扫之前就是空的），所以断言写成"逐条都立得住"，而不是"至少有几条"。
 */
class BuiltinPresetsTest {

    @Test
    fun theShippedPackLoadsAndEveryPresetInItStandsUp() = runTest {
        val loaded = BuiltinPresets.load()

        loaded.forEach { preset ->
            assertTrue(preset.name.isNotBlank(), "每条都要有名字：$preset")
            assertTrue(preset.brief.isNotBlank(), "每条都要有当初那句要求（它是第一道关的键）：$preset")
            assertTrue(
                preset.shape.problems(ShapeLimits()).isEmpty(),
                "${preset.name} 的形状不合规：${preset.shape.problems(ShapeLimits())}",
            )
            // 写进这个文件的时候要改回 **BUILTIN**：出厂与生成分开存是用户定的，而"随游戏带来的"
            // 正是这个文件里那些。挂错标签的表现是它在存档里跟着一局走。
            assertEquals(Preset.Origin.BUILTIN, preset.origin, "${preset.name} 该是出厂件")
        }

        // 名字是把手：重名的两条会让 find 的答案取决于顺序。
        val names = loaded.map { it.name }
        assertEquals(names.size, names.distinct().size, "名字不该重复：$names")
    }

    @Test
    fun theWholePackIsReachableByKeywordSearch() = runTest {
        val library = PresetLibrary(BuiltinPresets.load())
        val loaded = library.builtinPresets()

        // 出厂件**每一条都该能被搜到**——否则它占着包却帮不上忙，而症状是"库里明明有却还在现造"。
        // 拿它自己的类别或第一个标签去搜，是这套东西真实会发生的问法。
        val unreachable = loaded.filter { preset ->
            val words = buildList {
                if (preset.category.isNotBlank()) add(preset.category)
                addAll(preset.tags)
            }
            words.isEmpty() || words.none { library.search(it).contains(preset) }
        }

        assertTrue(unreachable.isEmpty(), "这些出厂件搜不到：${unreachable.map { it.name }}")
    }
}
