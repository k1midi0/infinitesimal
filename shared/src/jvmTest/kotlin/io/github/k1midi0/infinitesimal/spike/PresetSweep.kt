package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.judge.ShapeModeler
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.world.Preset
import io.github.k1midi0.infinitesimal.world.PresetPack
import io.github.k1midi0.infinitesimal.world.toEntry
import io.github.k1midi0.infinitesimal.world.toPreset
import kotlin.test.Test
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/**
 * **扫出厂预设包**（§12 第 36 项、附录 A.14）。
 *
 * 这个探针不是一次实验，是一件**工具**：它把一批要求逐条建模，把结果写成出厂预设包那个 JSON。
 *
 * ## 为什么要有它
 *
 * §14.31 量到的账：一次生成 34 分钟，其中 21 样东西全是现场建模，而 `PresetLibrary()` 的出厂包是
 * **一张空表**——模型照着清单逐字抄就能命中，清单空着就无从抄起。
 *
 * ## 它为什么可以反复跑
 *
 * **增量**：已经在这个文件里的要求会跳过。所以中断之后重跑不会重做已经花掉的钱，而补一批新东西只要
 * 往 [BRIEFS] 里加几行再跑一次。
 *
 * ## 它写出来的东西是出厂件
 *
 * 建模那一层产出的预设一律是 `GENERATED`（那是生成包的定义）。写进这个文件的时候**改回 `BUILTIN`**
 * ——出厂与生成分开存是用户定的，而"随游戏带来的"正是这个文件里那些。
 *
 * 跑法：
 *
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*PresetSweep*" --rerun `
 *   -PinfinitesimalLiveKeyFile="<密钥文件>"
 * ```
 *
 * 换个输出路径：再加 `-PinfinitesimalPresetFile=<路径>`（默认是出厂包那个位置）。
 */
class PresetSweep {

    @Test
    fun sweep() {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[出厂预设扫描未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return
        }

        val file = File(
            System.getProperty("infinitesimal.presetFile")?.takeIf { it.isNotBlank() } ?: DEFAULT_PATH,
        )
        // 工作目录是 `shared/`，所以这个相对路径落在源码树里——**产物是源码的一部分，不是构建产物**。
        println("===== 出厂预设扫描 =====")
        println("输出：${file.absolutePath}")

        val json = Json { prettyPrint = true; encodeDefaults = true }
        val existing: List<Preset> = readPack(file, json)
        val done = existing.associateBy { it.brief }

        println("已有 ${existing.size} 条，计划 ${BRIEFS.size} 条\n")

        val modeler = ShapeModeler(
            LlmClient(ProbeSupport.configOf(apiKey).copy(userId = SystemId.MODELER)),
        )
        // 已经扫出来的先灌回库里：这样同一个要求不会因为上次跑过而重画，而且**同名会替换**——
        // 补扫一条与老的一条重名时，留下的是新的那一份。
        existing.forEach { modeler.presets.learn(it) }

        val collected = existing.toMutableList()
        var failures = 0
        val started = System.currentTimeMillis()

        for ((index, brief) in BRIEFS.withIndex()) {
            val already = done[brief]
            if (already != null) {
                println("[${index + 1}/${BRIEFS.size}] 已有 · ${already.name} · ${brief.take(24)}")
                continue
            }

            print("[${index + 1}/${BRIEFS.size}] $brief … ")
            System.out.flush()

            val before = modeler.requestCount
            var result: ShapeModeler.Result? = null
            val ms = measureTimeMillis { result = runBlocking { modeler.model(brief) } }

            when (val r = result!!) {
                is ShapeModeler.Result.Modeled -> {
                    val size = r.shape.extent
                    println(
                        "✓ ${r.name}｜${r.category}/${r.tags.joinToString("、")}｜" +
                            "${r.shape.cellCount} 格｜" +
                            "${size.x + 5}×${size.y + 5}×${size.z + 5} 厘米｜" +
                            "${modeler.requestCount - before} 请求｜$ms ms",
                    )
                    val stored = modeler.presets.find(r.name) ?: continue
                    collected.removeAll { it.name == stored.name }
                    collected += stored.copy(origin = Preset.Origin.BUILTIN)
                }

                is ShapeModeler.Result.Failed -> {
                    failures++
                    println("✗ 没画成：${r.problems.joinToString("；").take(200)}")
                }
            }

            // **每画成一条就落一次盘**：这一趟可能要跑半小时，中途断掉不该把已经付过的钱丢掉。
            write(file, json, collected)
        }

        write(file, json, collected)

        println("\n───── 汇总 ─────")
        println("总共 ${collected.size} 条，这次新画 ${collected.size - existing.size} 条，失败 $failures 条")
        println("用掉 ${(System.currentTimeMillis() - started) / 1000} 秒，请求 ${modeler.requestCount} 次")
        println("按类别：")
        collected.groupBy { it.category.ifEmpty { "（没写类别）" } }
            .forEach { (category, list) -> println("  $category  ${list.size} 条：${list.joinToString("、") { it.name }}") }
        println("按尺寸（前五大的）：")
        collected.sortedByDescending { it.shape.cellCount }.take(5).forEach {
            println("  ${it.name}  ${it.shape.cellCount} 格")
        }
    }

    /**
     * 读现有的包。**两种格式都认**。
     *
     * 第一版写的是直接序列化出来的 `List<Preset>`（一堆坐标三元组，读不出来、还大一倍），后来改成
     * 网格文本（[PresetPack]）。留着这一条兼容路径，是为了**转格式不必重画**——那 19 条形状是实打实
     * 花掉二十几分钟画出来的，再付一次钱只为了换个编码是荒唐的。
     */
    private fun readPack(file: File, json: Json): List<Preset> {
        if (!file.isFile) return emptyList()
        val text = file.readText()
        if (text.isBlank()) return emptyList()

        runCatching { json.decodeFromString<PresetPack>(text) }
            .onSuccess { return it.presets.map(PresetPack.Entry::toPreset) }

        @Suppress("DEPRECATION")
        runCatching { json.decodeFromString<List<Preset>>(text) }
            .onSuccess { return it }

        error("$file 既不是 PresetPack 也不是老格式的预设表——它坏了，别当作空包继续跑。")
    }

    private fun write(file: File, json: Json, presets: List<Preset>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(PresetPack(presets.map(Preset::toEntry))))
    }

    private companion object {

        const val DEFAULT_PATH = "src/commonMain/composeResources/files/builtin-presets.json"

        /**
         * 第一批：**开局真正会用到的那些**（用户定的"小而准"）。
         *
         * 挑的时候对着两处现成的内容：`StartingWorld.tavern()` 里那九样东西，以及 §14.31 那次生成真
         * 正放到地上的那些（橡树、垂柳）。
         *
         * 每一条都得是**一句能独立读懂的要求**，因为造形状的那个模型看不到世界（A.14）；而且都落在
         * [io.github.k1midi0.infinitesimal.world.ShapeLimits] 的单边两百厘米以内——超了会被直接退回。
         */
        val BRIEFS = listOf(
            // 自然：镇外那片林子要的
            "一棵高约两米的橡树，树干笔直，树冠朝四面张开、枝叶分层，树皮皲裂",
            "一棵高约两米的垂柳，枝条从顶上垂下来，细而密",
            "一丛半人高的灌木，高约八十厘米，枝叶蓬松、朝四面散开",
            "一小丛野草，高约三十厘米，几片细长的叶子朝上散开",
            "一块半埋在土里的石头，约六十厘米见方，表面有棱有角",
            "一根横躺在地上的原木，长约一米五、直径约三十厘米，两端是断面",
            "一个树桩，高约四十厘米、直径约五十厘米，断面粗糙",

            // 家具：酒馆大堂要的
            "一张橡木方桌，桌面一百二十厘米见方、八十厘米高，四条方腿，桌面厚五厘米",
            "一把木椅，坐面约四十五厘米见方、离地四十五厘米，背后有靠背",
            "一张木凳，坐面约三十五厘米见方、离地四十五厘米，三条腿",
            "一个木架，高约一米八、宽约一米的四层架子，背板是木条拼的",

            // 器物：酒馆里的东西
            "一只深色木制小酒杯，口径八厘米、高十厘米，杯壁薄、里面是空的",
            "一只木盘，直径二十五厘米、边沿高两厘米，盘面微微下凹",
            "一盏陶制油灯，底座直径十二厘米、高二十厘米，上面扣着一个玻璃罩",
            "一只木桶，口径五十厘米、高七十厘米，桶身外面箍着两道铁箍",
            "一只木箱，六十厘米见方、五十厘米高，上面有盖、盖上有铜搭扣",

            // 工具：玩家的手上那些
            "一把扫帚，柄长一米五，下面是一束绑紧的枝条",
            "一把短柄斧，柄长六十厘米，顶端是斧头、一侧开刃",
            "一根粗麻绳，盘成一卷，直径约二十厘米",
        )
    }
}
