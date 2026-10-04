package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.director.ScriptPlanner
import io.github.k1midi0.infinitesimal.world.BuiltinScripts
import io.github.k1midi0.infinitesimal.world.ReferencePack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import java.io.File

/**
 * **出厂参考剧本库那份文件本身**（§14.55）。
 *
 * 它一半是**手写的**（仓库里没有"一手素材"——那是玩家自己的小说），一半是**从一份真实素材里拆出来的**
 * （用户提供的那一段《赤伶》背后的故事，原文不进仓库）。所以它最容易悄悄写坏：字段名打错、字数超上限、
 * 两条同 id、拆出来的那两条里留了来源的专名。这里把它当数据钉住，而不是等某天导演填出来一条怪东西再去查。
 *
 * 与 [BuiltinScripts.PATH] 对上，是这一支的另一半价值：资源路径与源码树里那个位置一旦漂开，游戏读到的
 * 就是另一份（或者读不到），而症状只是"参考剧本库读不出来"。
 */
class BuiltinScriptsFileTest {

    private val file = File("src/commonMain/composeResources/${BuiltinScripts.PATH}")

    private fun pack(): ReferencePack {
        assertTrue(file.isFile, "出厂参考剧本库不在这个位置：${file.absolutePath}（BuiltinScripts.PATH=${BuiltinScripts.PATH}）")
        return Json { ignoreUnknownKeys = true }.decodeFromString(ReferencePack.serializer(), file.readText())
    }

    @Test
    fun theShippedLibraryDecodesAndHasNoDuplicateCodes() {
        val pack = pack()

        assertTrue(pack.scripts.isNotEmpty(), "出厂库空着的话，第一层在真游戏里一次都不会被走到")
        assertEquals(
            pack.scripts.size,
            pack.scripts.map { it.id }.distinct().size,
            "同一个代号出现了两次：${pack.scripts.map { it.id }}",
        )
        assertEquals(ReferencePack.FORMAT, pack.version)
    }

    /** 三段都要写全，而且**摘要与骨架都在那两档上限之内**（它们与剧本共用同一个数）。 */
    @Test
    fun everyEntryIsCompleteAndWithinTheLimits() {
        pack().scripts.forEach { script ->
            assertTrue(script.id.isNotBlank(), "有一条没有代号")
            assertTrue(script.title.isNotBlank(), "「${script.id}」没有标题")
            assertTrue(script.summary.isNotBlank(), "「${script.id}」没有概括")
            assertTrue(script.body.isNotBlank(), "「${script.id}」没有骨架")
            assertTrue(
                script.summary.length <= ScriptPlanner.MAX_SUMMARY_CHARS,
                "「${script.id}」的概括 ${script.summary.length} 字，超过 ${ScriptPlanner.MAX_SUMMARY_CHARS}",
            )
            assertTrue(
                script.body.length <= ScriptPlanner.MAX_BODY_CHARS,
                "「${script.id}」的骨架 ${script.body.length} 字，超过 ${ScriptPlanner.MAX_BODY_CHARS}",
            )
            assertTrue(script.from.isNotBlank(), "「${script.id}」没有写出处（库由好几部拼起来时要认得出）")
        }
    }

    /**
     * **出厂那几条必须真的是"概括性"的**。
     *
     * 这一支只钉得住最容易机械判的那一半：**开局那张地图上独有的东西一个都不许出现**。参考剧本是要被
     * 填进任何一个世界的，而写着"酒馆"或者"矮人"的那一条，只有这一张地图能用。
     *
     * **"玩家"不算**：它不是这张地图上的名字，而是参考剧本里正当的**角色位**之一（"玩家可能怎么撞上"
     * 是每条骨架都该写的那一段）。
     */
    @Test
    fun theShippedEntriesDoNotLeanOnThisMap() {
        val forbidden = listOf("酒馆", "矮人", "醉猫", "地窖")
        pack().scripts.forEach { script ->
            val text = script.summary + script.body
            forbidden.forEach { word ->
                assertTrue(word !in text, "「${script.id}」里出现了本世界的东西「$word」——参考剧本是世界之外的")
            }
        }
    }

    /**
     * **从一手素材里拆出来的那两条，来源里的专名一个都没留**（§14.55 的那一次读数）。
     *
     * "抛弃角色背景"那条规矩能机械判的只有这一半，而这一半恰好最容易破：真实文本里全是专名（那一段 484
     * 字里有人名、县名、省名、年代、戏名、曲名）。所以把我们已经量到的那份来源钉住——它挡的是一次
     * **重新跑拆分、把带名字的版本提交进来**。
     */
    @Test
    fun theEntriesFromARealSourceKeptNoneOfItsNames() {
        val names = listOf("裴宴之", "安远县", "江西", "李香君", "桃花扇", "赤伶", "1937")
        val fromThatSource = pack().scripts.filter { it.from.contains("赤伶") }
        assertTrue(fromThatSource.isNotEmpty(), "从那一篇里拆出来的两条不见了")
        fromThatSource.forEach { script ->
            val text = script.title + script.summary + script.body
            names.forEach { name ->
                assertTrue(name !in text, "「${script.id}」里留下了来源里的专名「$name」")
            }
        }
    }
}
