package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.session.GameSession
import io.github.k1midi0.infinitesimal.world.ReferencePack
import io.github.k1midi0.infinitesimal.world.ReferenceScript
import io.github.k1midi0.infinitesimal.world.ScriptLibrary
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/**
 * **开局填充的联机读数**（§14.55）。
 *
 * 它读的是那一个**假端点看不到**的问题：导演照参考剧本填出来的线，是不是**这个世界的**线？
 * 骨架里的人与地方**本来就没有名字**，所以正确的行为是**用这一带真的有的人、真的有的地方去填**——
 * 矮人、酒馆、地窖门、木桌、油灯，而不是把骨架里那些"欠债的人""债主"原样写成两个没有名字的影子。
 *
 * 它不断言，只印读数：每一条填出来的线、它的来源、以及**正文里出现了哪些这一带真有的东西**（那一行是
 * 给"自动读"用的，正文整段也印出来给人读）。
 *
 * 跑法：
 *
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*OpeningScriptsProbe*" --rerun `
 *   -PinfinitesimalLiveKeyFile="<密钥文件>"
 * ```
 *
 * `-PinfinitesimalOpeningRuns=N` 控遍数（默认 3）。
 */
class OpeningScriptsProbe {

    @Test
    fun whatTheDirectorFillsFromTheReferenceLibrary() {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[开局填充探针未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }

        val runs = (System.getProperty("infinitesimal.openingRuns")?.toIntOrNull() ?: 3).coerceAtLeast(1)
        val library = ScriptLibrary(readLibrary())

        println("===== 开局填充 =====")
        println("参考剧本库：${library.size} 条")
        library.all().forEach { println("  · ${it.id}｜${it.title}｜出自 ${it.from}") }
        println("跑 $runs 遍\n")

        val counts = mutableListOf<Int>()
        val withWorldThings = mutableListOf<Int>()

        repeat(runs) { index ->
            println("───── 第 ${index + 1}/$runs 遍 ─────")
            // **每一遍一个新会话**：一个新的世界，与真实"开一局新的"那一条路一模一样。
            val session = GameSession(config = ProbeSupport.configOf(apiKey), references = library)

            val started = System.currentTimeMillis()
            runBlocking { session.fillOpeningScripts() }
            val ms = System.currentTimeMillis() - started

            val filled = session.world.scripts.filter { it.source != null }
            val names = session.world.all()
                .mapNotNull { it.label.takeIf { label -> label.length >= 2 } }
                .distinct()

            println("${ms} ms｜填出 ${filled.size} 条（请求见会话日志）")
            session.log.forEach { println("  日志 · ${it.text}") }

            if (filled.isEmpty()) {
                println("  （一条都没填出来）")
                return@repeat
            }

            var landed = 0
            filled.forEach { script ->
                val hits = names.filter { it in script.body || it in script.summary }
                if (hits.isNotEmpty()) landed++
                println("· ${script.id}｜${script.title}｜来源=${script.source}｜这一带的东西出现了 ${hits.size} 个")
                println("   摘要：${script.summary}")
                println("   世界里出现过：${hits.joinToString("、").ifEmpty { "（一个都没有——那它可能只是把骨架抄了一遍）" }}")
                println("   正文：${script.body.replace("\n", " / ").take(700)}")
                println()
            }

            counts += filled.size
            withWorldThings += landed
            println("  这一遍：${filled.size} 条，其中 ${landed} 条提到了这一带真有的东西")
            println()
        }

        println("───── 汇总 ─────")
        println("库 ${library.size} 条｜每遍填出：${counts.joinToString(" / ")}")
        println("提到这一带真有的东西的条数：${withWorldThings.joinToString(" / ")}")
        println(
            "读法：**「提到这一带的东西」是这一层的要害**——骨架里的人与地方没有名字，正确的填法是拿这一带" +
                "真的有的人、真的有的地方去填；一条都没提到，说明它多半只是把骨架换了个说法抄了一遍。",
        )
    }

    /**
     * 直接读出厂库那个文件（**不走资源加载**：探针的工作目录是 `shared/`，而那份文件就在源码树里）。
     *
     * 读不出来就报错，不当成空库继续跑——一个空库会让这一遍"跑通了但什么都没发生"，而那看起来与
     * "模型什么都没填"一模一样。
     */
    private fun readLibrary(): List<ReferenceScript> {
        val file = File("src/commonMain/composeResources/files/builtin-scripts.json")
        if (!file.isFile) error("读不到出厂参考剧本库：${file.absolutePath}")
        val pack = Json { ignoreUnknownKeys = true }
            .decodeFromString(ReferencePack.serializer(), file.readText())
        return pack.scripts
    }
}
