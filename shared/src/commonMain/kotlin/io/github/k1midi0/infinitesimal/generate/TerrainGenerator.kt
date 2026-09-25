package io.github.k1midi0.infinitesimal.generate

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Committer
import io.github.k1midi0.infinitesimal.judge.Consequence
import io.github.k1midi0.infinitesimal.judge.MaterialView
import io.github.k1midi0.infinitesimal.judge.ShapeModeler
import io.github.k1midi0.infinitesimal.llm.AssistantTurn
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.ToolCallRequest
import io.github.k1midi0.infinitesimal.llm.Usage
import io.github.k1midi0.infinitesimal.llm.decodeCall
import io.github.k1midi0.infinitesimal.spatial.chunks
import io.github.k1midi0.infinitesimal.world.CHUNK_CELLS
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Preset
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.TERRAIN_CM
import io.github.k1midi0.infinitesimal.world.TerrainBox
import io.github.k1midi0.infinitesimal.world.TerrainCell
import io.github.k1midi0.infinitesimal.world.TerrainGrid
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.ZoneCell
import io.github.k1midi0.infinitesimal.world.floorDiv
import io.github.k1midi0.infinitesimal.world.terrainCellOf
import io.github.k1midi0.infinitesimal.world.zoneCellOf
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable

/**
 * 地形生成器：把一片**还没有地形**的 chunk 长出来（A.16、§14.30）。
 *
 * ## 它听谁的
 *
 * **地块是上游给的**，它不在世界里自己找缺口。这一点是被推翻过一次的：早先那一版让生成器自己看
 * 「哪儿还没有地形」，然后自己决定这次铺多大——用户把它改成了「地形处理哪些地块是导演/判断给的」。
 * 于是这个类的形状变得很简单：**给它一块地，给它一段要求，它长出地形来**。
 *
 * 接它的是导演（叫醒思考档 → 决定生成要求 → 调地形系统）。现在还没有那一段，所以它先由一个临时的
 * 入口驱动（探针直接给要求），但那不影响它的形状：要求是一段自由文本，谁写的都一样。
 *
 * ## 一次调用能跨好几块 chunk
 *
 * chunk 是**生成的最小单位，不是最大的**。它有框选三只手，一次可以盖住好几块——所以一次探索通常
 * 只有一次调用，而不是每块一次。
 *
 * ## 地形与东西一起产出
 *
 * 一片林子是「地面铺草」加上「树、灌木、也许一只野兽」，不是先铺完草再想起来该种树。所以六只手
 * 一次性全给它，不分两步。
 *
 * ## 落库是整批原子的
 *
 * 一次调用产出的全部后果一起过 [Committer]。半片地比没有地更难查：地形是**世界事实**，而一个
 * 「铺到一半」的世界没有任何东西会报错。
 *
 * ## 一条纪律
 *
 * **不设 `max_tokens`。** 思考模式下思维链也算在它里面，任何一个小数字都会把想得久的那一次截断，
 * 而症状是「这一轮一个工具都没调」——与「它说长完了」长得一模一样（§14.19 量到过）。
 */
class TerrainGenerator(
    private val client: LlmClient,
    private val maxAttempts: Int = 2,
    /** 思考档的档位。**留着这个参数只为能再量，不是为了降档**（§12 第 17 项同一条理由）。 */
    val effort: ReasoningEffort = ReasoningEffort.HIGH,
    /**
     * 预设库（A.14）。**由调用方拿进来**：出厂包挂在会话上，而生成器与判定看的是同一个世界，
     * 也就该看同一批形状。
     *
     * 名字刻意不叫 `presets`——类里已经有一个同名的只读属性（它转发给 [modeler]），同名会让那个
     * 属性初始化时读到自己。
     */
    presetLibrary: PresetLibrary = PresetLibrary(),
) {

    /**
     * 给配置确实是固定值的地方用。**强制落到 [SystemId.GENERATOR] 那个缓存池**（见 [SystemId]）。
     */
    constructor(
        config: EndpointConfig,
        maxAttempts: Int = 2,
        effort: ReasoningEffort = ReasoningEffort.HIGH,
        presetLibrary: PresetLibrary = PresetLibrary(),
    ) : this(LlmClient(config.copy(userId = SystemId.GENERATOR)), maxAttempts, effort, presetLibrary)

    /**
     * 造形状的那一层。它拿的是**调用方给的那份库**——建模的产物是一条预设，而那条预设该进的是会话
     * 那一份（A.14）。
     */
    private val modeler: ShapeModeler = ShapeModeler(client, presets = presetLibrary)

    /** 库里现在有什么。判定、生成器、导演共用同一份，所以它是**活的**。 */
    val presets: PresetLibrary get() = modeler.presets

    var requestCount = 0
        private set

    var lastUsage: Usage? = null
        private set

    var lastFinishReason: String? = null
        private set

    /**
     * 这一次要长什么、长在哪儿。
     *
     * [chunks] 是**整块**：地形以 chunk 为最小生成单位，所以不存在「半块地缺地形」这件事
     * （§14.30）。空列表 = 没得长，直接退回。
     *
     * [required] 是**必需的那几块**（§14.33）：硬圈里还没有地形的那些，由代码算。它们与 [chunks]
     * 的区别只在「写没写进去才算数」——必需的那几块**没有真的写进地形就是没完成**，要重试；其余的
     * 是导演额外要的，长出来就好。
     *
     * [placements] 是**导演点名要放的东西**。一片森林里的每一棵树不该由它逐棵列出来，但「村口那棵
     * 老槐树」该由它点——那是地标，不是清单。
     */
    data class Request(
        val requirement: String,
        val chunks: List<ChunkKey>,
        val required: List<ChunkKey> = emptyList(),
        val placements: List<Placement> = emptyList(),
    )

    sealed interface Growth {

        /** 长好了，并且**已经落库**。[consequences] 是这一次写进世界的那一批。 */
        data class Grown(
            val consequences: List<Consequence>,
            val chunks: List<ChunkKey>,
            val attempts: Int,
        ) : Growth

        /** 协议内失败。**退回重试，不示给玩家。** */
        data class FailedProtocol(val problems: List<String>, val attempts: Int) : Growth
    }

    /**
     * 把上游给的范围补全：**必需的那几块永远在这块地里**。
     *
     * 上游给的范围可能没带上它们——导演自己填坐标，它可能只报了额外想长的那几块。少了这一步，一次
     * 「必需区块没写进去」会以「模型没照要求做」的样子出现，而真相是那一块根本没进这一次的请求。
     *
     * `grow` 与 `preview` 都走这一条，于是探针打印出来的那段提示与真正发出去的**逐字相同**。
     */
    fun normalised(request: Request): Request =
        request.copy(chunks = (request.chunks + request.required).distinct())

    suspend fun grow(world: World, raw: Request): Growth {
        val request = normalised(raw)
        val required = request.required.distinct()
        val chunks = request.chunks
        if (chunks.isEmpty()) {
            return Growth.FailedProtocol(listOf("没有给它任何地块，没得长。"), 0)
        }

        var notes = emptyList<String>()
        var lastProblems = emptyList<String>()
        var attempts = 0

        while (attempts < maxAttempts) {
            attempts++
            val taken = growOnce(world, request, notes)

            if (taken.consequences.isEmpty()) {
                // **一件都没长 = 这片地还是空的。**它不是一次成功的生成，多半是「想说的没说」或者
                // 被截断——两种都该重来一次，而不是把一次空转收下。（与导演那条「一件都没排」同一条
                // 理由。）
                //
                // 但这句话**只在还没有更具体的理由时**才算失败理由：一次被驳回、紧接着一次空转，
                // 把理由换成这句空话，看到的人就永远不知道第一次为什么被驳。
                if (lastProblems.isEmpty()) lastProblems = listOf(NOTHING_GREW)
                notes = listOf(NOTHING_GREW)
                continue
            }

            // 「哪些块真的被写进去了」只能从后果自己身上读——模型说了什么不算数。
            val touched = taken.consequences
                .filterIsInstance<Consequence.TerrainConsequence>()
                .flatMap { it.box.chunks() }
                .toSet()

            // 这两条检查**都在提交之前**。放到提交之后的话，一次「地被铺了、但指名要放的东西没放」
            // 就已经把半件事落库了，而重试会在这半件事之上再铺一遍。
            val missing = required.filterNot { it in touched }
            if (missing.isNotEmpty()) {
                val note = "必需的那几块还没写进地形：" +
                    missing.joinToString("、") { "(${it.x},${it.y})" } +
                    "。这几块在玩家脚底下，必须有地；只在别处长不算数。"
                lastProblems = listOf(note)
                notes = listOf(note)
                continue
            }

            val unplaced = placementProblems(taken.consequences, request.placements)
            if (unplaced.isNotEmpty()) {
                lastProblems = unplaced
                notes = unplaced
                continue
            }

            when (val applied = Committer.apply(world, taken.consequences)) {
                is Committer.Applied -> {
                    // 「这块地已经有地形了」这句话落在这儿。它不进后果那一批：后果是**世界的改动**，
                    // 这一句是账本（§14.30：来源不区分，开局那片地也会被标上）。
                    //
                    // **只标真的写进去的那些块。** 把请求过的一律标上，是拿账本替现实签字——那块空地
                    // 从此再没有人会来补，而症状是一片永远空着的马赛克：看起来像「模型没画好」，实际
                    // 是代码撒了谎。
                    touched.forEach(world.terrain::markGenerated)
                    return Growth.Grown(applied.consequences, chunks, attempts)
                }

                is Committer.Rejected -> {
                    lastProblems = applied.problems
                    notes = applied.problems
                }
            }
        }

        return Growth.FailedProtocol(lastProblems.ifEmpty { listOf(NOTHING_GREW) }, attempts)
    }

    /**
     * 导演点名要放的东西，**放进去了没有**。
     *
     * 判据是后果里有没有一条落在**那一块 chunk 里**的 `Spawn`——不是「模型说它放了」。这与「必需区块
     * 写没写进去」是同一条纪律：账从后果上读，不从回答里读。
     */
    private fun placementProblems(
        consequences: List<Consequence>,
        placements: List<Placement>,
    ): List<String> {
        if (placements.isEmpty()) return emptyList()
        val spawned = consequences.filterIsInstance<Consequence.Spawn>().map { it.entity }
        return placements.mapNotNull { p ->
            val inside = spawned.any { chunkOfPoint(it.x, it.y) == p.chunk }
            if (inside) {
                null
            } else {
                "导演点名要放的「${p.brief.trim()}」没有落进 chunk (${p.chunk.x},${p.chunk.y}) 里。" +
                    "它要求放在那一块，放到别处不算。"
            }
        }
    }

    private fun chunkOfPoint(x: Int, y: Int): ChunkKey =
        ChunkKey(floorDiv(terrainCellOf(x), CHUNK_CELLS), floorDiv(terrainCellOf(y), CHUNK_CELLS))

    // ── 一次生成 ───────────────────────────────────────────────────────────────────

    private class Taken(
        val consequences: List<Consequence>,
        val reply: String,
        val finishReason: String?,
        val usage: Usage?,
    )

    private suspend fun growOnce(world: World, request: Request, notes: List<String>): Taken {
        val messages = mutableListOf(
            ChatMessage.system(systemFor(world)),
            ChatMessage.user(userPrompt(world, request, notes)),
        )
        val consequences = mutableListOf<Consequence>()

        var reply = ""
        var finish: String? = null
        var usage: Usage? = null

        var round = 0
        while (round < MAX_ROUNDS) {
            round++
            requestCount++

            val turn = AssistantTurn.of(
                client.streamChat(
                    messages = messages,
                    tools = GeneratorTools.ALL,
                    thinking = ThinkingRequest.of(ThinkingMode.Always, effort),
                    // **不设 `max_tokens`。** 见类注释：思维链也算在它里面。
                    maxTokens = null,
                ).toList(),
            )

            reply = turn.content
            finish = turn.finishReason
            usage = turn.usage
            lastFinishReason = finish
            lastUsage = usage

            // **这一轮没有手就收工**——与导演同一条循环规则，与判定那条不同（判定是「这一轮没有
            // 查询手就收工」）：它要铺的事是一件一件排下去的。
            if (turn.calls.isEmpty()) break

            messages += turn.asAssistantMessage()
            for (call in turn.calls) {
                messages += ChatMessage.toolResult(call.id, answerFor(call, world, consequences))
            }
        }

        return Taken(consequences, reply, finish, usage)
    }

    /** 一只手的答复。**解不开的参数与不合法的值都变成文字退回去**，不抛异常（§1.3）。 */
    private suspend fun answerFor(
        call: ToolCallRequest,
        world: World,
        consequences: MutableList<Consequence>,
    ): String {
        if (call.name == GeneratorTools.MAKE_OBJECT) return makeObject(call, world, consequences)
        if (call.name == GeneratorTools.FIND_PRESET) return findPresets(call)

        val args = decodeCall(BoxArgs.serializer(), call) ?: return unreadable(call)
        val box = args.toBox()

        if (call.name == GeneratorTools.LOOK_TERRAIN) {
            val problems = TerrainGrid.tableProblems(world, box)
            return if (problems.isNotEmpty()) problems.joinToString("\n") else TerrainGrid.render(world, box)
        }

        // **两条不同的规模判据，量的是两种代价**（§14.30）：框选那三只看段数，表格那两只还要看字符。
        // 混用它们会把「一次铺满一整块 chunk」这个最基本的动作拦下来——那正是这一层刚开工时踩的坑。
        val table = call.name == GeneratorTools.EDIT_TERRAIN
        val problems = (if (table) TerrainGrid.tableProblems(world, box) else TerrainGrid.boxProblems(world, box))
            .toMutableList()
        when (call.name) {
            GeneratorTools.FILL_TERRAIN -> problems += materialProblems(world, args.material)
            GeneratorTools.REPLACE_TERRAIN -> {
                problems += materialProblems(world, args.from)
                problems += materialProblems(world, args.to)
            }
        }
        if (problems.isNotEmpty()) return problems.joinToString("\n")

        return when (call.name) {
            GeneratorTools.FILL_TERRAIN -> {
                consequences += Consequence.FillTerrain(box, args.material)
                "铺下了：${where(box)}，材质是「${world.materials.name(args.material)}」"
            }

            GeneratorTools.DIG_TERRAIN -> {
                consequences += Consequence.DigTerrain(box)
                "挖掉了：${where(box)}（框里挖不动的材质会被拦下，到时候我会告诉你）"
            }

            GeneratorTools.REPLACE_TERRAIN -> {
                consequences += Consequence.ReplaceTerrain(box, args.from, args.to)
                "换了：${where(box)}，${world.materials.name(args.from)} → ${world.materials.name(args.to)}"
            }

            GeneratorTools.EDIT_TERRAIN -> {
                when (val parsed = TerrainGrid.parse(world, box, args.table)) {
                    is TerrainGrid.Parsed.Bad -> parsed.problems.joinToString("\n")
                    is TerrainGrid.Parsed.Ok -> {
                        // 一张表拆成**一条一条按材质合并的框**：后果那一层只有三种地形后果，
                        // 没有「按格写」这一种。表里每种材质各成一批（同一批里后面的框覆盖前面的，
                        // 所以顺序是从挖空到铺上）。
                        val written = tableInto(parsed.cells, world)
                        if (written.isEmpty()) {
                            "这张表和现在一模一样，没什么要改的。"
                        } else {
                            consequences += written
                            "改好了：${where(box)}，按这张表写下了 ${parsed.cells.size} 格"
                        }
                    }
                }
            }

            else -> "没有 ${call.name} 这只手。能用的是 ${GeneratorTools.ALL.joinToString("、") { it.function.name }}。"
        }
    }

    /**
     * 把一张表变成后果。
     *
     * 表是**按格**的，而后果那一层只有 `fill` / `dig` / `replace` 三种**按框**的地形改动（A.15）。
     * 这里因此按「材质 → 格集合」分组，每组取它自己的包围盒写成一条框。代价是**属于别的材质的格会被
     * 顺带覆盖**——所以顺序很重要：先写挖空，再一种一种往上铺，后写的盖住先写的。而 `replace` 只动
     * 它自己那一种，不参与这个顺序。
     *
     * 这一层是刻意做薄的：`edit_terrain` 是给**精修**用的（雕一个台阶、挖一条窄沟），大改动本来就
     * 该走框选那三只。
     */
    private fun tableInto(cells: Map<TerrainCell, String?>, world: World): List<Consequence> {
        val order = world.materials.all.map { it.id }
        val grouped = cells.entries.groupBy({ it.value }, { it.key })

        val dig = grouped[null].orEmpty()
        val out = mutableListOf<Consequence>()
        if (dig.isNotEmpty()) out += Consequence.DigTerrain(boundingBox(dig))

        for (id in order) {
            val group = grouped[id].orEmpty()
            if (group.isNotEmpty()) out += Consequence.FillTerrain(boundingBox(group), id)
        }
        return out
    }

    /** 一组格的包围盒。空格子已经在上一步分流出去了，所以这里至少有一个格。 */
    private fun boundingBox(cells: Collection<TerrainCell>): TerrainBox =
        TerrainBox(
            x1 = cells.minOf { it.x },
            y1 = cells.minOf { it.y },
            z1 = cells.minOf { it.z },
            x2 = cells.maxOf { it.x },
            y2 = cells.maxOf { it.y },
            z2 = cells.maxOf { it.z },
        )

    /**
     * 按关键词找现成的东西。**这是"先看看库里有什么"那只手。**
     *
     * 它只读不写，所以没有后果、没有校验——唯一要做的是把结果**排成人能一眼扫过的样子**：名字在前
     * （那是 `make_object` 要填的东西），类别与标签居中（那是"为什么它被搜出来"），当初那句要求在后
     * （那是逐字抄的备份）。三样都给，是因为模型接下来有两条路可走，而两条路要的信息不一样。
     *
     * 搜不到时**不返回空字符串**：空字符串与"搜到了但清单是空的"长得一样，而这里真正的含义是"库里
     * 没有，你得自己写一句要求"。这句话必须说出来，否则模型会以为手坏了。
     */
    private fun findPresets(call: ToolCallRequest): String {
        val args = decodeCall(FindArgs.serializer(), call) ?: return unreadable(call)

        val keywords = args.keywords.trim()
        val wanted = (keywords + " " + args.category.trim()).trim()
        if (wanted.isEmpty()) {
            return "没给关键词。写几个你想到它的词，例如「橡树 乔木」，或者只给 category 例如「树木」。"
        }

        val found = presets.search(wanted)
        if (found.isEmpty()) {
            return "库里没有像「${keywords}」的东西。**这一类要现造**——" +
                "照你要的样子写一句 brief 交给 make_object，把它写清楚：是什么、多大、什么样子。"
        }

        return buildString {
            appendLine("找到 ${found.size} 条像「$keywords」的：")
            found.forEach { preset ->
                appendLine("  · ${preset.name}｜${attributeOf(preset)}｜${preset.brief.take(MAX_BRIEF)}")
            }
            append("放东西的时候，把选中那条的名字填进 make_object 的 preset。")
        }
    }

    /** 一条预设的类别与标签，印成一行。**没有属性时印一句实话**，不印一片空白。 */
    private fun attributeOf(preset: Preset): String {
        val parts = buildList {
            if (preset.category.isNotBlank()) add(preset.category)
            addAll(preset.tags)
        }
        return if (parts.isEmpty()) "（没有属性）" else parts.joinToString("、")
    }

    /**
     * 放一样东西。**一只走到底**：先按名字、再按 brief 查预设库，两关都没命中就照着这句 brief 现造一份，
     * 再放上去（§9.1 那条「调用预设、简单修改后放进地图」）。
     *
     * 两道关的顺序是**用户定的**（A.14）：`preset` 是模型搜过之后点名的，比一句自己写的要求可信；
     * `brief` 的逐字匹配是免费的快路径，留着不影响任何东西。
     */
    private suspend fun makeObject(
        call: ToolCallRequest,
        world: World,
        consequences: MutableList<Consequence>,
    ): String {
        val args = decodeCall(MakeArgs.serializer(), call) ?: return unreadable(call)

        val named = args.preset.trim().let { if (it.isEmpty()) null else presets.find(it) }
        if (args.preset.isNotBlank() && named == null) {
            return "库里没有叫「${args.preset.trim()}」的预设。先 `find_preset` 搜一下，" +
                "把它给你的那个名字原样填进来；或者留空 `preset`，只写 `brief` 让它现造。"
        }

        val brief = args.brief.trim()
        if (named == null && brief.isEmpty()) {
            return "`brief` 是空的。它是拿来形状的一句要求——空着的话那边造不出来，" +
                "写成「三人合抱的老橡树，树皮皲裂」这样。"
        }

        val preset = named ?: presets.byBrief(brief) ?: when (val modeled = modeler.model(brief)) {
            is ShapeModeler.Result.Modeled -> presets.find(modeled.name)
            is ShapeModeler.Result.Failed ->
                return "这一件造不出来：「$brief」——${modeled.problems.joinToString("；")}"
        }
        if (preset == null) {
            return "形状造出来了，但库里的名字对不上，这一件先跳过。"
        }

        val entity = presets.spawn(
            preset = preset,
            id = args.id.trim(),
            kind = args.kind.trim(),
            label = args.label.trim(),
            x = args.x,
            y = args.y,
            z = args.z,
            background = args.background.trim().ifEmpty { null },
        )
        consequences += Consequence.Spawn(entity)
        return "放好了：${entity.label}（用「${preset.name}」这份形状，${entity.shape?.cellCount ?: 0} 格）"
    }

    // ── 提示词 ─────────────────────────────────────────────────────────────────────

    /**
     * 这一次调用会发出去的那两段文字。**给探针与测试用**。
     *
     * 提示词是这个系统里最该被人读一遍的东西，而它在一次真实调用里是看不见的——探针里那一次会把
     * 几十秒花在它上面，但没人知道它到底写了什么。
     */
    fun preview(world: World, request: Request): String =
        systemFor(world) + "\n\n──────── 上面是 system，下面是 user ────────\n\n" +
            userPrompt(world, normalised(request), emptyList())

    /**
     * 静态那一段 + 材质表。
     *
     * **材质表进 system**，因为它在一次接一次的生成之间逐字相同——而这一段是缓存最该命中的地方
     * （§14.8）。**预设清单反而不进**：它是会长的（这一轮现造一件，下一轮库里就多一条），把它放进
     * 静态前缀会让整个前缀在生成中间失效。
     */
    private fun systemFor(world: World): String = buildString {
        append(SYSTEM)
        appendLine()
        append(MaterialView.forPrefix(world))
    }

    private fun userPrompt(world: World, request: Request, notes: List<String>): String = buildString {
        appendLine("你这一次要长的是这些地块。一块 chunk 是 16×16 个地形格，也就是 8×8 米：")
        appendLine()
        val required = request.required.toSet()
        request.chunks.forEach { chunk ->
            val mark = if (chunk in required) "**必须有地**" else "有就行"
            appendLine(
                "  chunk (${chunk.x},${chunk.y})（$mark）：地形格 ${Edges.cellsOf(chunk)}；" +
                    "厘米 ${Edges.centimetresOf(chunk)}",
            )
        }
        if (required.isNotEmpty()) {
            appendLine()
            appendLine("标着「必须有地」的那几块在玩家脚底下，**一块都不能落下**——只在别处长不算数。")
        }
        appendLine()
        appendLine("导演对这一次的要求：")
        appendLine()
        appendLine("  ${request.requirement.trim()}")
        appendLine()
        append(placements(request.placements))
        append(zoning(world, request.chunks))
        append(edges(world, request.chunks))
        append(presetListing())
        if (notes.isNotEmpty()) {
            appendLine("上一次没成。问题在下面这些地方，改掉它们再试一遍：")
            appendLine()
            notes.forEach { appendLine("  - $it") }
            appendLine()
        }
        appendLine("动手吧。记住：**改地皮那五只用地形格，放东西用厘米**。")
    }

    /**
     * 导演点名要放的那几样。
     *
     * 写的是**在哪一块**，不写厘米——具体落在这一块里的哪儿由它自己定，而它刚铺完这块地、看得见
     * 剖面。给它一个厘米数字只会得到一个编出来的数字（§14.33）。
     */
    private fun placements(list: List<Placement>): String {
        if (list.isEmpty()) return ""
        return buildString {
            appendLine("导演还点名要在下面这些地方放东西（**放进它说的那一块里**，具体落在哪儿你自己定）：")
            appendLine()
            list.forEach { p ->
                appendLine("  - ${p.brief.trim()} —— 放进 chunk (${p.chunk.x},${p.chunk.y})")
                if (p.background.isNotBlank()) {
                    appendLine("      它自己的背景（照抄进 `make_object` 的 background）：${p.background.trim()}")
                }
            }
            appendLine()
        }
    }

    private fun zoning(world: World, chunks: List<ChunkKey>): String {
        // 一块 chunk 是 8×8 米，而区划一格是 10 米，所以一块 chunk 最多碰到四个区划格。
        val touched = linkedSetOf<ZoneCell>()
        for (chunk in chunks) {
            val x0 = chunk.x * CHUNK_CELLS * TERRAIN_CM
            val y0 = chunk.y * CHUNK_CELLS * TERRAIN_CM
            val x1 = x0 + CHUNK_CELLS * TERRAIN_CM - 1
            val y1 = y0 + CHUNK_CELLS * TERRAIN_CM - 1
            for (zx in listOf(zoneCellOf(x0), zoneCellOf(x1))) {
                for (zy in listOf(zoneCellOf(y0), zoneCellOf(y1))) {
                    touched += ZoneCell(zx, zy)
                }
            }
        }

        val lines = touched.mapNotNull { cell ->
            val tile = world.zoning.tileAt(cell) ?: return@mapNotNull null
            val note = tile.note.takeIf { it.isNotBlank() }?.let { "（导演写的：「$it」）" }.orEmpty()
            "  区划格 (${cell.x},${cell.y})：${tile.landform.label}$note"
        }

        return if (lines.isEmpty()) {
            "这些地块还没有区划——你自己看着办，长成什么都行。\n\n"
        } else {
            "区划（导演涂在地图上的**意图，不是事实**——地形和它对不上没关系，别为了对上它硬凑）：\n\n" +
                lines.joinToString("\n") + "\n\n"
        }
    }

    private fun edges(world: World, chunks: List<ChunkKey>): String {
        val text = Edges.of(world, chunks)
        return if (text.isEmpty()) {
            "接壤处：这一片四面都不挨着已经长好的地——地盘你自己定，怎么长都行。\n\n"
        } else {
            "接壤处（下面这几条边外面是**已经长好的地**，地面高度至少要大致接得上，" +
                "否则地图上会出现一道刀切的台阶）：\n\n$text\n\n"
        }
    }

    private fun presetListing(): String {
        val all = presets.all()
        if (all.isEmpty()) {
            return "预设库现在是空的——`find_preset` 会一无所获，`make_object` 只能照你写的 brief " +
                "现造。所以 brief 要写得特别清楚。\n\n"
        }
        val shown = all.take(MAX_PRESETS)
        return buildString {
            appendLine(
                "预设库里现在有这些东西（名字｜类别、标签｜当初那句要求）。" +
                    "要放东西之前先 `find_preset` 搜一下，**别自己另写一句要求**——" +
                    "库里有的就直接用它的名字，一分钱不花；库里真的没有，`make_object` 才照你的 " +
                    "brief 现造一份（几十秒）：",
            )
            appendLine()
            shown.forEach { appendLine("  ${it.name}｜${attributeOf(it)}｜${it.brief.take(MAX_BRIEF)}") }
            if (all.size > shown.size) appendLine("  ……还有 ${all.size - shown.size} 条没列出来")
            appendLine()
        }
    }

    // ── 小工具 ─────────────────────────────────────────────────────────────────────

    private fun unreadable(call: ToolCallRequest): String =
        "`${call.name}` 的参数读不出来（缺字段，或者类型不对）。照工具上的说明再写一遍。"

    private fun where(box: TerrainBox): String =
        "地形格 x ${box.minX}..${box.maxX}、y ${box.minY}..${box.maxY}、z ${box.minZ}..${box.maxZ}"

    /** 材质 id 不在表里。**拼错的材质名不报错，只会什么都不发生**，所以这条在提交前就挡住。 */
    private fun materialProblems(world: World, material: String): List<String> {
        if (world.materials.byId(material) != null) return emptyList()
        val known = world.materials.all.joinToString("、") { it.id }
        return listOf("材质表里没有「$material」。表里现在是：$known")
    }

    @Serializable
    private data class BoxArgs(
        val x1: Int,
        val y1: Int,
        val z1: Int,
        val x2: Int,
        val y2: Int,
        val z2: Int,
        val material: String = "",
        val from: String = "",
        val to: String = "",
        val table: String = "",
    ) {
        fun toBox(): TerrainBox = TerrainBox(x1, y1, z1, x2, y2, z2)
    }

    @Serializable
    private data class MakeArgs(
        val id: String,
        val kind: String,
        val label: String,
        val brief: String,
        val x: Int,
        val y: Int,
        val z: Int,
        /** 可选：`find_preset` 找到的那条的名字。留空才看 [brief]。 */
        val preset: String = "",
        /** 可选：**这样东西自己那份渲染背景**，导演点名时带过来的那一句。 */
        val background: String = "",
    )

    @Serializable
    private data class FindArgs(
        val keywords: String = "",
        val category: String = "",
    )

    private companion object {
        /** 一轮最多几个来回。40 与建模那边同一个数：**它不是给模型的上限，是给失控的保险丝**。 */
        const val MAX_ROUNDS = 40

        /** 预设清单最多列几条、每条截多长。清单是拿来照着写 brief 的，不是拿来读完的。 */
        const val MAX_PRESETS = 60
        const val MAX_BRIEF = 60

        /** 一次空转的失败理由。它是一条**兜底**：只要有过更具体的理由，就不用它（见 [grow]）。 */
        const val NOTHING_GREW = "一件都没长出来。这一片现在还是空的——至少先铺一层地面，再说别的事。"

        val SYSTEM = """
            你在给一片**还没有地形**的空地铺地形。这一片现在什么都没有——没有土、没有石头、没有树，
            你要把它变成一处说得通的地方。

            ## 你的手

            - `fill_terrain` / `dig_terrain` / `replace_terrain`：**框选一大片**，一次铺、挖、或者换掉
              一种材质。参数里的坐标是**地形格**，50 厘米一格。铺大片用这三只。
            - `look_terrain`：框选一块，**读回它现在长什么样**（一张分层的字符表）。想改一块地方之前
              先看它。这是不落地的手之一。
            - `edit_terrain`：把 `look_terrain` 读回来的那张表改几个字符再发回去，以同一个框为最大
              边界。表必须与框严格同尺寸、每格都写，`.` 是挖空。**精修用这只**（雕一个台阶、挖一条
              窄沟），铺大片别用它。
            - `find_preset`：**按关键词在预设库里找现成的东西。**写几个你想到它的词，拿回一张候选
              清单。放东西之前先搜一次。这也是不落地的手。
            - `make_object`：在地面上**放一样东西**。参数里的坐标是**厘米**——这是唯一一只用厘米的手。

            ## 规矩

            - **地形与东西一起产出。**一片林子是「地面铺草」加上「树、灌木、也许一只野兽」，不是先铺完
              草再想起来该种树。
            - **别让脚下是空的。**这一片长完之后，站在任何一处都该踩得到东西。悬崖、沟壑、洞穴都可以
              造——那是地形的一部分——但别在平地上留一个悬空的洞。
            - **和旁边接上。**下面会告诉你这一片挨着的、已经长好的地是什么样。地面高度别和它们差出
              一大截，除非你有理由（一道崖、一条河）。
            - **一次能铺多大就铺多大。**框选那三只一次可以盖住好几块 chunk，别一块一块地铺。
            - **放东西之前先搜。**`find_preset` 搜到合适的就用它的名字，别自己另写一句要求——
              现造一份要几十秒，而库里多半已经有了。
            - **库里真的没有才自己写。**那时 brief 要写成**一句能独立读懂的要求**——造形状的那个模型
              看不到你看到的这一切。
            - **没手可调了，就停下来。**停下来之前确认这一片真的长完了。
        """.trimIndent()
    }
}
