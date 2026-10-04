package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.director.ScriptDigester
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.world.ReferencePack
import kotlin.test.Test
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/**
 * **拆一部小说**（§14.55、§8.4）——第一层的那个离线工具。
 *
 * ## 它是什么
 *
 * 把用户给的一部小说/剧本读进来，按段送进 [ScriptDigester]，把拆出来的**概括性参考剧本**写进出厂参考
 * 剧本库那个文件。跑完之后，游戏里的导演就有了"世界之外的素材"可填（`write_script` 的 `source`）。
 *
 * ## 它为什么可以反复跑
 *
 * **增量**：那个文件里记着 `chunksDone`（拆到第几段了），重跑从那里接着走。所以中断、断电、或者只想
 * 先花一点钱看看效果，都是安全的——已经付过的那几段不会重拆。分段是**确定性的**（同一份原文 → 同一批
 * 段），`chunksDone` 因此有意义。
 *
 * ## 它也**不覆盖**手写那几条出厂种子
 *
 * 库是"素材"，写进来的一律按 id 合并：新拆出来的追加在后面，同名的替换。手写那四条因此在里面留着
 * （它们的 `from` 写着"出厂种子"），而拆出来的每一条的 `from` 是这本小说的文件名。
 *
 * 跑法：
 *
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*ScriptDigest*" --rerun `
 *   -PinfinitesimalLiveKeyFile="<密钥文件>" `
 *   -PinfinitesimalNovelFile="<小说或剧本的文本文件>"
 * ```
 *
 * 另外两个旋钮：
 *  - `-PinfinitesimalScriptsFile=<路径>`：改输出（默认就是出厂库那个文件）。
 *  - `-PinfinitesimalDigestChunks=N`：这一次最多拆几段（默认 0 = 拆到完）。长篇先跑 `10` 试试手感。
 */
class ScriptDigest {

    @Test
    fun digest() {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[拆分未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }

        val novelPath = System.getProperty("infinitesimal.novelFile")?.takeIf { it.isNotBlank() }
        if (novelPath == null) {
            println("[拆分未执行] 未提供 -PinfinitesimalNovelFile=<小说或剧本的文本文件>")
            println(PURPOSE)
            return
        }

        val source = File(novelPath)
        if (!source.isFile) {
            println("[拆分未执行] 读不到这个文件：${source.absolutePath}")
            return
        }

        val file = File(
            System.getProperty("infinitesimal.scriptsFile")?.takeIf { it.isNotBlank() } ?: DEFAULT_PATH,
        )
        val cap = System.getProperty("infinitesimal.digestChunks")?.toIntOrNull() ?: 0

        val json = Json { prettyPrint = true; encodeDefaults = true }
        val existing = readPack(file, json)
        val chunks = chunksOf(source.readText())

        println("===== 参考剧本拆分 =====")
        println("原文：${source.absolutePath}（${source.readText().length} 字，${chunks.size} 段）")
        println("输出：${file.absolutePath}")
        println("已有 ${existing.scripts.size} 条，已拆到第 ${existing.chunksDone} 段\n")

        val model = ScriptDigester(
            LlmClient(ProbeSupport.configOf(apiKey).copy(userId = SystemId.DIGEST)),
        )

        val collected = existing.scripts.toMutableList()
        var done = existing.chunksDone
        var from = existing.from.ifBlank { source.name }
        var taken = collected.map { it.id }.toSet()
        var failures = 0
        var processed = 0
        val started = System.currentTimeMillis()

        for (index in done until chunks.size) {
            if (cap > 0 && processed >= cap) {
                println("\n（到达这一次的上限 $cap 段，停下。下次接着跑。）")
                break
            }
            val chunk = chunks[index]
            print("[${index + 1}/${chunks.size}] ${chunk.length} 字 … ")
            System.out.flush()

            val before = model.requestCount
            var result: ScriptDigester.Result? = null
            val ms = measureTimeMillis {
                result = runBlocking {
                    model.digest(
                        text = chunk,
                        from = source.name,
                        label = "（${source.name} 的第 ${index + 1} 段，一共 ${chunks.size} 段）",
                        taken = taken,
                    )
                }
            }

            when (val r = result!!) {
                is ScriptDigester.Result.Digested -> {
                    r.scripts.forEach { script ->
                        collected.removeAll { it.id == script.id }
                        collected += script
                    }
                    taken = collected.map { it.id }.toSet()
                    println(
                        "✓ 拆出 ${r.scripts.size} 条｜${model.requestCount - before} 请求｜$ms ms" +
                            if (r.scripts.isEmpty()) "" else "｜" + r.scripts.joinToString("、") { it.id },
                    )
                }

                is ScriptDigester.Result.Failed -> {
                    failures++
                    println("✗ 这一段没拆出来：${r.problems.joinToString("；").take(200)}")
                }
            }

            // **拆完一段就落一次盘**：一部长篇要跑很久，中途断掉不该把已经付过的钱丢掉。
            done = index + 1
            write(file, json, ReferencePack(from = from, chunksDone = done, scripts = collected))
            processed++
        }

        write(file, json, ReferencePack(from = from, chunksDone = done, scripts = collected))

        println("\n───── 汇总 ─────")
        println("这一次拆了 $processed 段，库从 ${existing.scripts.size} 条变成 ${collected.size} 条（失败 $failures 段）")
        println("进度：第 $done/${chunks.size} 段")
        println("用掉 ${(System.currentTimeMillis() - started) / 1000} 秒，请求 ${model.requestCount} 次")
        collected.groupBy { it.from }
            .forEach { (origin, list) ->
                println("  出自 ${origin.ifEmpty { "（没说来源）" }}：${list.size} 条")
            }
    }

    /**
     * 读现有的包。**读不出来就报错，不当成空包继续跑**——一个坏掉的文件被当成空的，会把已经拆好的
     * 几十条静默地冲掉（与 `PresetSweep.readPack` 同一条纪律）。
     */
    private fun readPack(file: File, json: Json): ReferencePack {
        if (!file.isFile) return ReferencePack()
        val text = file.readText()
        if (text.isBlank()) return ReferencePack()
        return runCatching { json.decodeFromString<ReferencePack>(text) }
            .getOrElse { error("$file 读不成一个参考剧本库——它坏了，别当作空包继续跑。") }
    }

    private fun write(file: File, json: Json, pack: ReferencePack) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ReferencePack.serializer(), pack))
    }

    private companion object {

        const val DEFAULT_PATH = "src/commonMain/composeResources/files/builtin-scripts.json"

        /**
         * 一段多大。
         *
         * 四千字：够放下两三场戏（拆得出几条），又小到模型不会在"这一段里哪件事才算一条线"上走神。
         * **按空行切段、不切在句子中间**——一句话被切成两半，拆出来的东西会同时缺前因和后文。
         */
        const val CHUNK_CHARS = 4_000

        val PURPOSE = """
            这个工具**把一部小说拆成一个参考剧本库**（§14.55 的第一层）：模型总结、拆分，抛弃角色背景、
            人名、地名，只留"处境与角色位"，产出的是能被填进任何一个世界的骨架。导演照着它，用这个世界
            真的有的人和真的有的地方，把它们填成这个世界的剧本（`write_script`）。

            用法：把小说存成一个 UTF-8 文本文件，然后
              .\gradlew.bat :shared:jvmTest --tests "*ScriptDigest*" --rerun `
                -PinfinitesimalLiveKeyFile="<密钥文件>" -PinfinitesimalNovelFile="<小说文本>"
        """.trimIndent()

        /**
         * 把原文切成段：**按空行切，再按 [CHUNK_CHARS] 攒**。
         *
         * 确定性是这条路的全部前提——那个文件里的 `chunksDone` 靠"同一份原文切出同一批段"才有意义。
         * 一个比 [CHUNK_CHARS] 还长的自然段**不切开**（宁可让这一段大一点）：切在句子中间会让那一句话
         * 的两半落在两段里，而两边都读不懂它。
         */
        fun chunksOf(text: String, size: Int = CHUNK_CHARS): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            for (raw in text.split(Regex("\n\\s*\n"))) {
                val paragraph = raw.trim()
                if (paragraph.isEmpty()) continue
                if (current.isNotEmpty() && current.length + paragraph.length + 2 > size) {
                    out += current.toString().trim()
                    current.clear()
                }
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(paragraph)
            }
            if (current.isNotEmpty()) out += current.toString().trim()
            return out
        }
    }
}
