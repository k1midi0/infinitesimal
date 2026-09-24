package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.FunctionCall
import io.github.k1midi0.infinitesimal.llm.FunctionSpec
import io.github.k1midi0.infinitesimal.llm.JsonSchema
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.LlmEvent
import io.github.k1midi0.infinitesimal.llm.PropertySchema
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.ToolCall
import io.github.k1midi0.infinitesimal.llm.ToolDefinition
import io.github.k1midi0.infinitesimal.llm.Usage
import io.github.k1midi0.infinitesimal.world.Preset
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.ShapeGrid
import io.github.k1midi0.infinitesimal.world.ShapeLimits
import io.github.k1midi0.infinitesimal.world.VOXEL_CM
import io.github.k1midi0.infinitesimal.world.VoxelShape
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * **建模**：把一句要求画成一份 5 厘米体素的形状（附录 A.9–A.11）。
 *
 * ## 它是一个独立的模型段，不是判定的一部分
 *
 * A.10 定的是"这个东西是通用的"：物品、角色、地形块都调它，而每个调用方带自己的一份 [ShapeLimits]。
 * 所以它是**一次独立的调用**，有自己的 system、自己的工具、自己的缓存池，与判定那一轮完全分开。
 *
 * 分开买到的是一件事：**判定不必为了画一张桌子而背上整段网格语法**。判定的提示词里已经装了两步的
 * 工具、空间框、规则书、角色卡——再塞进网格语法，那一段会开始挤掉真正要它想的东西。
 *
 * ## 为什么重试，以及"什么才算一次失败"
 *
 * 形状是模型画的，画错是常态。错只有两类，修法不同（[ShapeGrid] 的注释里写过）：**读不成格子**（行
 * 不一样长、字符不认识）与**格子不合理**（同一格既实心又质点、超了上限）。两类都由 [ShapeGrid.parse]
 * 一次报全、原样回给模型——它拿着"第几行第几个字符不对"这条信息，修得比谁都快（§1.3）。
 *
 * 但**交一层不算失败**。一样东西有几层就要交几层，一个十层高的柜子因此要十来个来回，而那是**正常
 * 进度**，不是错误。所以这里数的是两样不同的东西：错了几次（[maxAttempts]）与问了多少来回
 * （[MAX_ROUNDS]）。把两者混成一个计数，长东西就会被判成"画不出来"——而它其实一次都没画错。
 *
 * ## 它不碰世界
 *
 * 它只回答"画成没画成"，**不改任何状态**。谁要形状、拿到之后往哪放，是调用方的事。
 */
class ShapeModeler(
    private val client: LlmClient,
    private val limits: ShapeLimits = ShapeLimits(),
    /**
     * 最多**错**几次。默认 3——比判定多一次，因为建模的失败几乎全是画法问题，而画法问题每问一次都会
     * 收敛（判定那边失败的原因更杂，重试的收益低一些）。
     */
    private val maxAttempts: Int = 3,
    /**
     * 建模的思考强度。**也是 HIGH，与判定同理**（用户定的：不限制思考，防止逻辑出错）。
     */
    private val effort: ReasoningEffort = ReasoningEffort.HIGH,
    /**
     * 预设库（附录 A.14）。**它同时是缓存**——用户定的：缓存键是**那句要求**。
     *
     * 它是一条真的数据、不是一份加速表：造出来的东西会进**生成预设包**，存档要带走它。所以它的寿命
     * 应当与一局相同（`session/` 那边持有），而不是与一次调用相同。默认值只是"拿不到时也还能跑"的
     * 兜底——那样每次都要重画，而画一次是几十秒。
     */
    val presets: PresetLibrary = PresetLibrary(),
) {

    var requestCount = 0
        private set

    /** 最近一次建模失败时，代码报回去的那些问题。诊断用。 */
    var lastProblems: List<String> = emptyList()
        private set

    /**
     * 最后一个来回的用量。**留它主要是为了看缓存**：建模独占一个 `user_id`（[
     * io.github.k1midi0.infinitesimal.config.SystemId.MODELER]），而它的 system 是整段网格语法、每次
     * 逐字相同——这段前缀该稳定命中。没有这个读数，"独占一池有没有用"就永远是个猜。
     */
    var lastUsage: Usage? = null
        private set

    sealed interface Result {

        /** [cached] = 这次没有花模型调用，直接拿回了同一条要求造过的形状。 */
        data class Modeled(val name: String, val shape: VoxelShape, val cached: Boolean = false) : Result

        /** 错满了次数、或者来回太多。**理由原样留着**——它多半就是"这段提示词教得不够"。 */
        data class Failed(val problems: List<String>) : Result
    }

    /**
     * 画一样东西。
     *
     * **第一件事是查缓存**：同一句要求造过就原样拿回来。用户定的这条规则在这里兑现——要求写得越
     * 具体，命中越准；而写得含糊时，本来也不该复用一份形状（[PresetLibrary.byBrief] 的注释）。
     */
    suspend fun model(brief: String): Result {
        presets.byBrief(brief)?.let {
            return Result.Modeled(it.name, it.shape, cached = true)
        }
        return when (val result = draw(brief)) {
            is Result.Modeled -> {
                // **产物是一条预设**（用户定的），不是一份加速表：它进生成预设包，存档要带走。
                val stored = presets.learn(
                    Preset(name = result.name, brief = brief.trim(), shape = result.shape),
                )
                Result.Modeled(stored.name, stored.shape)
            }

            is Result.Failed -> result
        }
    }

    private suspend fun draw(brief: String): Result {
        /** 交上来的层：z -> 这一层的所有行。**同一个 z 再交一次就是改那一层。** */
        val floors = sortedMapOf<Int, String>()
        val messages = mutableListOf(
            ChatMessage.system(system()),
            ChatMessage.user(user(brief, emptyList())),
        )

        var failures = 0
        var rounds = 0

        while (failures < maxAttempts && rounds < MAX_ROUNDS) {
            rounds++
            requestCount++
            val raw = Raw.of(client.streamChat(
                messages = messages,
                tools = TOOLS,
                thinking = ThinkingRequest.of(ThinkingMode.Always, effort),
                // 与判定同一条：**不设 max_tokens**。网格是一大段文字，截断的表现是"少交了一层"，
                // 而那看起来与"模型自己觉得够了"一模一样。
                maxTokens = null,
            ).toList())

            messages += raw.assistant()
            lastUsage = raw.usage

            if (raw.calls.isEmpty()) {
                // 一句话都没说、一个工具都没调。**这是可以指出来的**，不必当成设备故障。
                failures++
                lastProblems = listOf(
                    "你没有调用任何工具。" +
                        (raw.finishReason?.let { "（这次的 finish_reason=$it；如果是 length，说明话说太长了）" } ?: ""),
                )
                messages += ChatMessage.user(feedback(lastProblems))
                continue
            }

            /** 这一轮里报回去的问题。**它是"错了一次"的判据**，不是"这一轮没交东西"。 */
            val complaints = mutableListOf<String>()
            var modeled: Result.Modeled? = null

            for (call in raw.calls) {
                when (call.name) {
                    VOXEL_FLOOR -> {
                        val args = decode<FloorArgs>(call.arguments)
                        val complaint = when {
                            args == null -> "参数不是合法 JSON：${call.arguments.take(160)}"
                            args.z % VOXEL_CM != 0 || args.z < 0 ->
                                "z 要是 $VOXEL_CM 的非负整数倍（地面那层是 0），你给的是 ${args.z}。"

                            else -> null
                        }
                        if (complaint != null) {
                            complaints += complaint
                            messages += ChatMessage.toolResult(call.id, complaint)
                        } else {
                            floors[args!!.z] = args.rows
                            // 回一句"收到了、现在有几层"：模型看不到自己的历史里被记下了什么，
                            // 而这正好是它接下来要用的东西。
                            messages += ChatMessage.toolResult(
                                call.id,
                                "第 ${args.z} 层记下了。已经交了 ${floors.size} 层：" +
                                    floors.keys.joinToString("、") + "。",
                            )
                        }
                    }

                    VOXEL_DONE -> {
                        val args = decode<DoneArgs>(call.arguments)
                        when (val parsed = ShapeGrid.parse(assemble(floors))) {
                            is ShapeGrid.Parsed.Ok ->
                                modeled = Result.Modeled(nameOf(args?.name, brief), parsed.shape)

                            is ShapeGrid.Parsed.Bad -> {
                                complaints += parsed.problems
                                messages += ChatMessage.toolResult(call.id, report(parsed.problems))
                            }
                        }
                    }

                    GIVE_UP -> {
                        val args = decode<GiveUpArgs>(call.arguments)
                        val why = args?.reason?.takeIf { it.isNotBlank() } ?: "（没有说明理由）"
                        return Result.Failed(listOf("它说画不出来：$why") + lastProblems)
                    }

                    else -> {
                        val complaint = "没有 $call.name 这只工具。能用的是 $VOXEL_FLOOR、$VOXEL_DONE、$GIVE_UP。"
                        complaints += complaint
                        messages += ChatMessage.toolResult(call.id, complaint)
                    }
                }
            }

            modeled?.let { return it }

            if (complaints.isNotEmpty()) {
                failures++
                lastProblems = complaints
                messages += ChatMessage.user(feedback(complaints))
            }
        }

        return Result.Failed(
            lastProblems.ifEmpty { listOf("来回太多（超过 $MAX_ROUNDS 次），也没有交出能用的形状") },
        )
    }

    /** 把交上来的层拼成 [ShapeGrid] 认得的那种文本。 */
    private fun assemble(floors: Map<Int, String>): String = buildString {
        floors.forEach { (z, rows) ->
            appendLine("z=$z")
            appendLine(rows)
        }
    }

    /** 把问题列成一段话回给模型。 */
    private fun report(problems: List<String>): String = buildString {
        appendLine("这份形状有 ${problems.size} 处问题，一处都还没通过：")
        problems.forEach { appendLine("· $it") }
        append("改好之后重交。")
    }

    private fun feedback(problems: List<String>): String = buildString {
        appendLine("上一版没通过。问题在下面，请**重交一遍**——同一个 z 再交一次就是改那一层：")
        problems.forEach { appendLine("· $it") }
    }

    private fun user(brief: String, problems: List<String>): String = buildString {
        appendLine("【要造的东西】")
        appendLine(brief)
        if (problems.isNotEmpty()) {
            appendLine()
            appendLine("【上一版的问题】")
            problems.forEach { appendLine("· $it") }
        }
    }

    /**
     * 名字：模型给的那个，规范化之后用。
     *
     * **它只是一个把手**，不承担唯一性（唯一性由缓存键 [brief] 承担，见
     * [io.github.k1midi0.infinitesimal.world.Preset]）。所以这里只要把它收拾成"能用"的样子，不必为了
     * 它退回去让模型重说一遍——那会为一件不影响任何东西的事烧掉一整轮。
     */
    private fun nameOf(given: String?, brief: String): String =
        slug(given).ifEmpty { slug(brief) }.ifEmpty { "shape" }

    /** 只留小写字母、数字、下划线，并且不超长。 */
    private fun slug(raw: String?): String = (raw ?: "").trim().lowercase()
        .map { if (it.isLetterOrDigit() && it.code < 128) it else '_' }
        .joinToString("")
        .trim('_')
        .replace(Regex("_+"), "_")
        .take(48)

    private fun system(): String = """
        你是「建模」这一段。有人告诉你需要一样什么东西，你把它画成一份 ${VOXEL_CM} 厘米体素的形状。
        画出来的东西会成为一个**预设**——世界里的物体照着它长出来。

        【尺寸与单位】
        · 长度一律用厘米。一个格子是 $VOXEL_CM 厘米见方的小方块。
        · 比一个格子还小的东西（一根针、一颗浆果）不要画成实心格，画成**质点**：
          那个格子里写上 1 到 9 表示有几个，10 个写成 ${ShapeGrid.TEN_POINTS}。
        · 单边最长 ${limits.maxSideCm} 厘米，整份形状最多 ${limits.maxCells} 个格。

        【怎么画】
        调 $VOXEL_FLOOR 一层一层交。它有两个参数：
        · z：这一层离地多高，厘米，必须是 $VOXEL_CM 的倍数。地面那层是 0。
        · rows：这一层的所有行，行与行之间用换行分开。
          第 1 行是 y=0，第 2 行是 y=$VOXEL_CM，以此类推；每行的第 1 个字符是 x=0，第 2 个是 x=$VOXEL_CM。
          ${ShapeGrid.SOLID} 是实心格，${ShapeGrid.EMPTY} 是空的，1 到 9 是那个格里的质点数，
          ${ShapeGrid.TEN_POINTS} 是 10 个质点。**同一层里每一行必须一样长。**
        一层交一次，交完这一层再交下一层。同一个 z 再交一次就是改那一层。
        全交完调 $VOXEL_DONE，并给它一个短名字：只用小写字母、数字、下划线，例如 split_oak_table。
        确实画不出来就调 $GIVE_UP，并说明是哪一步画不出来。

        【最容易画错的几条】
        · 画的是**这样东西本身**。不要把地面、墙壁、底座一起画进去。
        · 图的四周不要留空行、空列，也不要留空层——包围盒要贴着东西本身，
          整张图的最小角必须落在 (0, 0, 0)。有空行空列就通不过。
        · **里面是空的东西要挖空**：杯子、桶、箱子、屋子——外壁是 ${ShapeGrid.SOLID}，里面是
          ${ShapeGrid.EMPTY}。别画成一坨实心：一个实心的杯子在世界里看起来就是一块木头。
        · 壁厚至少一格（$VOXEL_CM 厘米）；比这更薄的壁没有画法。
        · 高度上也要贴着：一只杯子不该有几十层空气。
        · 该对称的东西就画对称，别在中间漏一格。
        · 一层一层交；一次可以交好几层，也可以一次只交一层。
    """.trimIndent()

    // ── 工具 ────────────────────────────────────────────────────────────────────

    private class Raw(
        val content: String,
        val reasoning: String,
        val calls: List<Call>,
        val finishReason: String?,
        val usage: Usage?,
    ) {
        class Call(val id: String, val name: String, val arguments: String)

        fun assistant() = ChatMessage.assistant(
            content = content.ifEmpty { null },
            // 带 tools 的请求里这个字段必须在，没有思考时给空串（§1.2.1）。
            reasoningContent = reasoning,
            toolCalls = calls.map {
                ToolCall(id = it.id, function = FunctionCall(it.name, it.arguments.ifEmpty { "{}" }))
            },
        )

        companion object {
            fun of(events: List<LlmEvent>): Raw {
                val content = StringBuilder()
                val reasoning = StringBuilder()
                val ids = mutableMapOf<Int, String>()
                val names = mutableMapOf<Int, String>()
                val arguments = mutableMapOf<Int, StringBuilder>()
                var finish: String? = null
                var usage: Usage? = null
                for (event in events) {
                    when (event) {
                        is LlmEvent.Content -> content.append(event.text)
                        is LlmEvent.Reasoning -> reasoning.append(event.text)
                        is LlmEvent.ToolCallStarted -> {
                            ids[event.index] = event.id
                            names[event.index] = event.name
                        }

                        is LlmEvent.ToolCallArguments ->
                            arguments.getOrPut(event.index) { StringBuilder() }.append(event.fragment)

                        is LlmEvent.Finished -> {
                            finish = event.reason
                            usage = event.usage
                        }
                    }
                }
                return Raw(
                    content.toString(),
                    reasoning.toString(),
                    names.keys.sorted().map {
                        Call(ids[it].orEmpty(), names.getValue(it), arguments[it]?.toString().orEmpty())
                    },
                    finish,
                    usage,
                )
            }
        }
    }

    private companion object {

        const val VOXEL_FLOOR = "voxel_floor"
        const val VOXEL_DONE = "voxel_done"
        const val GIVE_UP = "give_up"

        /**
         * 一次建模最多几个来回。**它不是重试次数**（那是 `maxAttempts`）——交一层算一个来回，而一样
         * 东西有几层就该交几层。40 够一个两米高、每 5 厘米一层的东西（40 层）一次画完。
         */
        const val MAX_ROUNDS = 40

        val json = Json { ignoreUnknownKeys = true; isLenient = true }

        inline fun <reified T> decode(arguments: String): T? =
            runCatching { json.decodeFromString<T>(arguments.ifBlank { "{}" }) }.getOrNull()

        fun tool(name: String, description: String, vararg properties: Pair<String, PropertySchema>) =
            ToolDefinition(
                function = FunctionSpec(
                    name = name,
                    description = description,
                    parameters = JsonSchema(
                        properties = properties.toMap(),
                        // 这里全部必填：每一只工具的参数都是它自己的全部内容，没有"可选"这回事。
                        required = properties.map { it.first },
                    ),
                ),
            )

        fun int(name: String, description: String) = name to PropertySchema("integer", description)
        fun text(name: String, description: String) = name to PropertySchema("string", description)

        val TOOLS: List<ToolDefinition> = listOf(
            tool(
                VOXEL_FLOOR,
                "交上一层格子。第 1 行是 y=0，每行第 1 个字符是 x=0；" +
                    "「${ShapeGrid.SOLID}」实心、「${ShapeGrid.EMPTY}」空、" +
                    "1-9 几个质点、「${ShapeGrid.TEN_POINTS}」十个质点。" +
                    "同一层每行必须一样长。同一个 z 再交一次就是改那一层。",
                int("z", "这一层离地多高，厘米，$VOXEL_CM 的倍数，地面是 0"),
                text("rows", "这一层的所有行，行与行用换行分开"),
            ),
            tool(
                VOXEL_DONE,
                "所有层都交完了。代码会把它们拼起来校验，通不过就把问题还给你。",
                text("name", "给这份形状的短名字，只用小写字母、数字、下划线，例如 split_oak_table"),
            ),
            tool(
                GIVE_UP,
                "确实画不出来时用它，并说明卡在哪。**不要用它代替画图。**",
                text("reason", "一句话，说明是哪一步画不出来"),
            ),
        )
    }
}

/**
 * 这次判定里 `model_shape` 造出来的形状。
 *
 * 它是**代码段造的，不是模型提议的**——但落库仍然走 [Committer]（[Consequence.SetShape] 与模型给的
 * 那些后果一起校验、一起提交）。这条区分要紧：判定那一轮里的写权限只有"提议"，建模只是把内容准备
 * 出来，放不放得进世界由 [Committer] 说了算。
 *
 * 与 [CalcLog] 同一个位置：它们是这次动作的属性，不是某一步的属性，所以 [Judge.Turn] 持有一份。
 */
class ShapeLog {

    private val shapes = LinkedHashMap<String, VoxelShape>()

    val count: Int get() = shapes.size

    val names: List<String> get() = shapes.keys.toList()

    fun record(id: String, shape: VoxelShape) {
        shapes[id] = shape
    }

    /**
     * 把它们变成后果。**接在模型给的那些后面**——新造出来的东西通常是这一批里先 `spawn` 或 `replace`
     * 出来的，[Committer] 按顺序维护影子状态，`SetShape` 排在前面会撞上"世界里没有它"。
     */
    fun consequences(): List<Consequence> =
        shapes.map { (id, shape) -> Consequence.SetShape(id, shape) }
}

@Serializable
private data class FloorArgs(val z: Int, val rows: String)

@Serializable
private data class DoneArgs(val name: String = "")

@Serializable
private data class GiveUpArgs(val reason: String = "")
