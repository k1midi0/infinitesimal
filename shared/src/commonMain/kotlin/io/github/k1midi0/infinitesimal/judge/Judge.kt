package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.FunctionCall
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.LlmEvent
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.ToolCall
import io.github.k1midi0.infinitesimal.spatial.Direction
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一次动作的完整输入。
 *
 * [intention] 是**玩家原话**，不做任何解析——因为动作不能穷尽（§4.2），任何"先解析成结构化动作、
 * 再判定"的设计都会在那一步丢掉它理解不了的东西。
 *
 * [selected] 是**玩家在界面上选的**，它是一个猜测。真正作用在哪些东西上是第一步的输出（见 [Judge]）。
 */
data class JudgeRequest(
    val actorId: String,
    val actorLabel: String,
    val intention: String,
    val selected: List<String>,
    /**
     * 这次动作涉及的东西。**空间框就是以「发起者 + 这些」为中心算出来的**（见 [ViewBudget]）。
     *
     * 这里要的是 id，不是拼好的一段文字：这一片空间有**两个投影**——给模型看的文字，和给代码用的
     * 几何。让调用方拼文字，代码手里就只剩文字了，而"模型的坐标是编的"这件事正是那样发生的。
     *
     * 两步用的是**同一批 centers**（玩家勾的那些），不是第一步对齐出来的结果。理由是缓存：
     * [sharedPrefix] 必须两步逐字相同（§7.5），把随步骤变的东西放进去，第二步的缓存就全灭。
     */
    val centers: List<String>,
)

/** 判定发生在哪一步。驳回是"选错了"还是"做不成"，玩家能做的事完全不同。 */
enum class Stage {
    /** 第一步：指向对象对不上。**玩家改一下选择就能重来。** */
    ALIGN,

    /** 第二步：动作本身不成立。 */
    EFFECT,
}

sealed interface TargetAlignment {
    /** 对齐好了：这些才是这次动作作用的东西。**可能为空**——"我环顾四周"确实不作用在任何东西上。 */
    data class Aligned(val targets: List<String>) : TargetAlignment

    /** 看不出这次动作指着什么。理由给玩家看。 */
    data class Rejected(val reason: String) : TargetAlignment

    /** 协议内失败：模型吐出来的东西没法当目标。**不示给玩家**。 */
    data class Malformed(val problems: List<String>) : TargetAlignment
}

sealed interface Verdict {
    /** **世界内驳回**：动作在情境下不成立。理由原样给玩家看。 */
    data class Rejected(val reason: String) : Verdict

    data class Proposed(val consequences: List<Consequence>, val affected: List<String>) : Verdict

    /**
     * 模型既没提议后果、也没驳回。
     *
     * **这不是"世界内驳回"。** 它是协议上的空转，该退回重试；重试还不行就当一次失败，
     * 但**绝不能告诉玩家"你的操作不合理"**（§4.3）。
     */
    data class Empty(val note: String) : Verdict

    data class Malformed(val problems: List<String>) : Verdict
}

/** 一次判定的最终结果。 */
sealed interface Judgment {
    data class Applied(
        /** 第一步对齐出来的目标。**与玩家选的可能不同**——界面该把这件事说出来。 */
        val targets: List<String>,
        val consequences: List<Consequence>,
        val affected: Set<String>,
        val attempts: Int,
    ) : Judgment

    /** 世界内驳回。**理由给玩家看**；[stage] 说明是哪一步拦下的。 */
    data class RejectedWorld(val reason: String, val stage: Stage, val attempts: Int) : Judgment

    /** 我们这边没救回来。**不示给玩家**，记日志、当这次动作没发生。 */
    data class FailedProtocol(val problems: List<String>, val attempts: Int) : Judgment
}

/**
 * 判定系统：**分两步**。
 *
 * | | 输入 | 输出 | 档位 |
 * |---|---|---|---|
 * | 第一步 对齐指向对象 | 动作 + 场景 + **玩家选的** | 修正后的目标集 / 驳回 | **快速档** |
 * | 第二步 列出后果 | 动作 + 场景 + **对齐后的目标** | 后果清单 / 驳回 | 思考档 |
 *
 * **为什么可以拆（而 §4.2 又为什么说别乱拆）：** 判据不是"调了几次"，而是**中间产物有没有人消费**。
 * 第一步的输出——修正后的目标集——是第二步的**必需输入**，所以它不是一个没人要的半成品。反过来，
 * "解析玩家想做什么"与"判定它成不成立"依赖同一份判断，拆开只会多一次延迟。
 *
 * **第一步用快速档**（D1a）：对齐是**比对**（动作说的事 vs 选中的东西），不是物理推理。而且延迟是
 * 两段之和——第二步实测 4–6 秒，第一步再用思考档就没法玩了。
 *
 * 两步的**系统提示词与"情境 + 发起者 + 动作"逐字相同**，只有工具集与末尾那一段指令不同。这样第二步
 * 有可能命中第一步写下的缓存（门槛见 §14.5）。
 *
 * 但它的**写权限是零**：模型只能调工具**提议**，落库一律经 [Committer]。
 */
class Judge(
    private val client: LlmClient,
    private val maxAttempts: Int = 2,
) {
    /** 给配置确实是固定值的地方用。**强制落到 [SystemId.JUDGE] 那个缓存池**（§14.8）。 */
    constructor(config: EndpointConfig, maxAttempts: Int = 2) : this(
        LlmClient(config.copy(userId = SystemId.JUDGE)),
        maxAttempts,
    )

    /** 两步加起来的模型调用次数。 */
    var requestCount = 0
        private set

    /**
     * 最近一次判定里，模型一共要求"看大一点"几次。
     *
     * **没有上限的东西必须能被观测**，否则"它会不会一直扩下去"永远是个猜。这也是为什么它是**两步
     * 共享的那一个数字**，而不是每步各记一个。
     */
    var viewWidenings = 0
        private set

    /**
     * 最近一次判定里，模型每次扩大填的方向，按调用顺序。
     *
     * **`null` 表示整体扩大**，空列表表示一次都没扩。这是"模型会不会填方向"唯一的答案来源——
     * 它是新加的一只可选参数值不值，全看这里。
     */
    var viewDirections: List<Direction?> = emptyList()
        private set

    // ── 第一步：对齐指向对象 ────────────────────────────────────────────────────────

    suspend fun alignTargets(
        world: World,
        request: JudgeRequest,
        retryNotes: List<String> = emptyList(),
        view: ViewBudget = ViewBudget(world, request.actorId, request.centers),
    ): TargetAlignment {
        val calls = call(
            tools = JudgeTools.TARGETING,
            user = targetingPrompt(request, retryNotes, view),
            // 快速档：对齐不需要推理。**只有这一档 temperature 才生效**，而这里也不需要温度。
            thinking = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.LOW),
            maxTokens = 512,
            view = view,
        )
        if (calls.isEmpty()) return TargetAlignment.Malformed(listOf("第一步没有调用任何工具"))

        val targets = mutableListOf<String>()
        var rejection: String? = null
        for (call in calls) {
            when (call.name) {
                JudgeTools.TARGET -> {
                    val args = decode(TargetArgs.serializer(), call) ?: return TargetAlignment.Malformed(
                        listOf("target 的参数不是合法 JSON：${call.arguments.take(160)}"),
                    )
                    targets += args.id
                }

                JudgeTools.REJECT_TARGETS -> {
                    val args = decode(RejectArgs.serializer(), call) ?: return TargetAlignment.Malformed(
                        listOf("reject_targets 的参数不是合法 JSON：${call.arguments.take(160)}"),
                    )
                    rejection = args.reason
                }

                else -> return TargetAlignment.Malformed(listOf("第一步不该调用 ${call.name}"))
            }
        }

        // 与第二步同一条纪律：驳回与产物互斥，代码不选边，退回重试。
        if (rejection != null && targets.isNotEmpty()) {
            return TargetAlignment.Malformed(
                listOf("同时给了驳回理由又报了 ${targets.size} 个目标；这两个是互斥的，要哪一个"),
            )
        }
        rejection?.let { return TargetAlignment.Rejected(it) }

        return TargetAlignment.Aligned(targets.distinct())
    }

    // ── 第二步：列出后果 ────────────────────────────────────────────────────────────

    suspend fun proposeEffects(
        world: World,
        request: JudgeRequest,
        targets: List<String>,
        retryNotes: List<String> = emptyList(),
        view: ViewBudget = ViewBudget(world, request.actorId, request.centers),
    ): Verdict {
        val calls = call(
            tools = JudgeTools.EFFECTS,
            user = effectPrompt(request, targets, retryNotes, view),
            // 思考档：整套设计把"物理推理"整个交给了模型（附录 A.4——现实无法用代码模拟），
            // 这里就是那件事发生的地方，也是全系统里最该让它想清楚的一处。
            thinking = ThinkingRequest.of(ThinkingMode.Always, ReasoningEffort.HIGH),
            maxTokens = 2_000,
            view = view,
        )
        if (calls.isEmpty()) return Verdict.Empty("第二步没有调用任何工具")

        val consequences = mutableListOf<Consequence>()
        val affected = mutableListOf<String>()
        var rejection: String? = null

        for (call in calls) {
            when (val parsed = parseEffect(call)) {
                is Parsed.Effect -> consequences += parsed.consequence
                is Parsed.Affects -> affected += parsed.actor
                is Parsed.Rejects -> rejection = parsed.reason
                is Parsed.Bad -> return Verdict.Malformed(listOf(parsed.problem))
            }
        }

        // **驳回与后果不能并存。** 默默选一边正是那种会把 bug 藏起来的事：两边都自洽，谁都不会报错。
        if (rejection != null && consequences.isNotEmpty()) {
            return Verdict.Malformed(
                listOf("同时给了驳回理由又提议了 ${consequences.size} 条后果；这两个是互斥的，要哪一个"),
            )
        }
        rejection?.let { return Verdict.Rejected(it) }

        if (consequences.isEmpty()) return Verdict.Malformed(listOf("只说了影响谁，没有说要发生什么"))
        return Verdict.Proposed(consequences, affected)
    }

    // ── 串起来 ─────────────────────────────────────────────────────────────────────

    /**
     * 对齐 → 列后果 → 校验 → 提交。**协议内失败就带着问题重试**（§4.3）。
     *
     * 宁可这次动作什么都不发生，也不要写进去一半。两步各自最多 [maxAttempts] 次模型调用。
     */
    suspend fun judgeAndCommit(world: World, request: JudgeRequest): Judgment {
        // **一次判定一个预算，两步共用。** 第一步扩过之后，第二步拿到的是已经大了的那个框。
        val view = ViewBudget(world, request.actorId, request.centers)
        return try {
            runJudgment(world, request, view)
        } finally {
            // 提前返回的几条路也要记上——驳回的那一次同样扩过，那也是成本。
            viewWidenings = view.widenings
            viewDirections = view.asked
        }
    }

    private suspend fun runJudgment(world: World, request: JudgeRequest, view: ViewBudget): Judgment {
        // 第一步
        var alignProblems = listOf<String>()
        var aligned: List<String>? = null
        for (attempt in 1..maxAttempts) {
            when (val alignment = alignTargets(world, request, alignProblems, view)) {
                is TargetAlignment.Rejected ->
                    return Judgment.RejectedWorld(alignment.reason, Stage.ALIGN, attempt)

                is TargetAlignment.Malformed -> alignProblems = alignment.problems

                is TargetAlignment.Aligned -> {
                    aligned = alignment.targets
                    break
                }
            }
        }
        val targets = aligned
            ?: return Judgment.FailedProtocol(listOf("对齐指向对象失败") + alignProblems, maxAttempts)

        // 第二步
        val effectProblems = mutableListOf<String>()
        for (attempt in 1..maxAttempts) {
            when (val verdict = proposeEffects(world, request, targets, effectProblems.toList(), view)) {
                is Verdict.Rejected ->
                    return Judgment.RejectedWorld(verdict.reason, Stage.EFFECT, attempt)

                is Verdict.Empty -> effectProblems += "模型没有提议任何后果，也没有驳回（${verdict.note}）"

                is Verdict.Malformed -> effectProblems += verdict.problems

                is Verdict.Proposed -> when (
                    val result = Committer.apply(world, verdict.consequences, verdict.affected)
                ) {
                    is Committer.Applied ->
                        return Judgment.Applied(targets, result.consequences, result.affected, attempt)

                    is Committer.Rejected -> effectProblems += result.problems
                }
            }
        }

        return Judgment.FailedProtocol(effectProblems.toList(), maxAttempts)
    }

    // ── 请求构造 ───────────────────────────────────────────────────────────────────

    private suspend fun call(
        tools: List<io.github.k1midi0.infinitesimal.llm.ToolDefinition>,
        user: String,
        thinking: ThinkingRequest,
        maxTokens: Int,
        view: ViewBudget,
    ): List<RawCall> {
        val messages = mutableListOf(ChatMessage.system(SYSTEM), ChatMessage.user(user))
        val proposals = mutableListOf<RawCall>()
        while (true) {
            requestCount++
            val turn = Turn.of(
                client.streamChat(
                    messages = messages,
                    tools = tools,
                    thinking = thinking,
                    maxTokens = maxTokens,
                ).toList(),
            )
            proposals += turn.calls.filter { it.name != JudgeTools.WIDEN_VIEW }
            if (turn.calls.none { it.name == JudgeTools.WIDEN_VIEW }) return proposals

            // 这一轮模型只是想看得更远。把它**如实**记回历史再接着问——有两样不能省：
            // assistant 那条要带 `reasoning_content`（带 tools 的请求里它必须在，§1.2.1），
            // 而每一个 tool_call_id 都要有一条 tool 结果，端点是这么要求配对的。
            messages += turn.asAssistantMessage()
            for (c in turn.calls) {
                messages += ChatMessage.toolResult(
                    c.id,
                    if (c.name == JudgeTools.WIDEN_VIEW) {
                        // 方向是**可选**的。认不出来的词就整体扩——不退回让模型重说：它"想看得更远"
                        // 这个愿望是明确的，只有"哪边"没表达清楚，为这个多烧一整轮不值得。
                        val wanted = decode(WidenArgs.serializer(), c)
                            ?.direction
                            ?.let(Direction::byLabel)
                        if (wanted != null) view.widen(wanted) else view.widen()
                    } else {
                        // 同一轮里既报了东西又要看更大范围。**不能把这个报的东西丢掉**——它是模型
                        // 已经做出的判断，而这一轮之后它没有理由再报一次。
                        "（记下了。你这一轮还要看更大的范围，所以它先搁着；看完新的范围再一起给。）"
                    },
                )
            }
        }
    }

    /**
     * 两步**逐字相同**的那一段。
     *
     * 它是缓存唯一可能命中的地方：两步的 system 相同、情境相同、发起者与动作相同，之后才分岔。
     * 所以**任何会变的东西都不许放进这里**——放进来一次，第二步的缓存就全灭（§7.5 是同一条理由）。
     */
    private fun sharedPrefix(request: JudgeRequest, view: ViewBudget): String = buildString {
        appendLine("【你周围这一片】")
        appendLine(view.text())
        appendLine()
        appendLine("【动作发起者】")
        appendLine("id=${request.actorId}，名字=${request.actorLabel}")
        appendLine()
        appendLine("【动作】")
        appendLine(request.intention)
    }

    private fun targetingPrompt(request: JudgeRequest, retryNotes: List<String>, view: ViewBudget): String = buildString {
        append(sharedPrefix(request, view))
        appendLine()
        appendLine("【你这一步只做一件事：对齐指向对象】")
        appendLine("上面是发起者自己在界面上选的东西：")
        if (request.selected.isEmpty()) {
            appendLine("（什么都没选）")
        } else {
            request.selected.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("判断这次动作**实际作用于哪些东西**，然后用 ${JudgeTools.TARGET} 逐个报出来：")
        appendLine("- **选少了就补**：动作说的是「桌上的东西」，桌上每一件都得报。")
        appendLine("- **选多了就剔除**：跟这次动作无关的不要报。")
        appendLine("- **选错了就换成对的**。")
        appendLine("- 这次动作确实不作用在任何东西上（例如「我环顾四周」），那就一个都不报——**那是合法的，不是驳回**。")
        appendLine("- 只有当你完全看不出它要对什么做时才调 ${JudgeTools.REJECT_TARGETS}。")
        appendLine()
        appendLine("**不要在这一步推演后果**，那是下一步的事。")
        appendRetryNotes(retryNotes)
    }

    private fun effectPrompt(
        request: JudgeRequest,
        targets: List<String>,
        retryNotes: List<String>,
        view: ViewBudget,
    ): String =
        buildString {
            append(sharedPrefix(request, view))
            appendLine()
            appendLine("【这次动作作用在这些东西上】")
            if (targets.isEmpty()) {
                appendLine("（不作用于任何具体的东西）")
            } else {
                targets.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("【你这一步要做的事：列出后果】")
            appendLine("1. 先判断这次动作在这个情境下成不成立。不成立就调 ${JudgeTools.REJECT}，理由写成这个世界会说的话。")
            appendLine("2. 成立就用工具列出它造成的**全部**后果——包括不显眼但确实发生的（桌上的东西跟着滑动、火把脱手落地）。")
            appendLine("3. 被这次动作影响到的角色，每个调一次 ${JudgeTools.AFFECT}。发起者本人不用记。")
            appendLine("4. 只列这次动作确实造成的后果。不要顺手整理世界。")
            appendRetryNotes(retryNotes)
        }

    private fun StringBuilder.appendRetryNotes(notes: List<String>) {
        if (notes.isEmpty()) return
        appendLine()
        appendLine("【上一次尝试被拒绝了，请修正】")
        notes.forEach { appendLine("- $it") }
    }

    private class RawCall(val id: String, val name: String, val arguments: String)

    /**
     * 一轮流式响应拼起来的样子。
     *
     * 与原先那个只收工具调用的版本相比，多留了两样，而这两样都是硬的：**这条 assistant 消息的思考
     * 内容**，和**每个工具调用的 id**。要让这一轮接着往下走（`widen_view` 是唯一那种情况），端点
     * 要求每条 assistant 都带着 `reasoning_content`，而每条 tool 结果都要对上 `tool_call_id`——
     * 只收 name 与 arguments 是做不了这件事的。
     */
    private class Turn(val content: String, val reasoning: String, val calls: List<RawCall>) {

        fun asAssistantMessage() = ChatMessage.assistant(
            content = content.ifEmpty { null },
            // 没有思考时给空串、不给 null：带 tools 的请求里这个字段必须在（§1.2.1）。
            reasoningContent = reasoning,
            toolCalls = calls.map { c ->
                ToolCall(id = c.id, function = FunctionCall(c.name, c.arguments.ifEmpty { "{}" }))
            },
        )

        companion object {
            /** 按 index 把流式的工具调用片段拼起来。 */
            fun of(events: List<LlmEvent>): Turn {
                val content = StringBuilder()
                val reasoning = StringBuilder()
                val ids = mutableMapOf<Int, String>()
                val names = mutableMapOf<Int, String>()
                val arguments = mutableMapOf<Int, StringBuilder>()
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

                        is LlmEvent.Finished -> Unit
                    }
                }
                val calls = names.keys.sorted().map { index ->
                    RawCall(
                        ids[index].orEmpty(),
                        names.getValue(index),
                        arguments[index]?.toString().orEmpty(),
                    )
                }
                return Turn(content.toString(), reasoning.toString(), calls)
            }
        }
    }

    /** `widen_view` 的参数。**`direction` 是可选的**——这是这个项目里第一只真正带可选参数的工具。 */
    @Serializable
    private data class WidenArgs(val direction: String? = null)

    private fun <T> decode(serializer: KSerializer<T>, call: RawCall): T? = try {
        JSON.decodeFromString(serializer, call.arguments)
    } catch (cause: Throwable) {
        null
    }

    private sealed interface Parsed {
        data class Effect(val consequence: Consequence) : Parsed
        data class Affects(val actor: String) : Parsed
        data class Rejects(val reason: String) : Parsed
        data class Bad(val problem: String) : Parsed
    }

    private fun parseEffect(call: RawCall): Parsed {
        fun <T> decodeInto(serializer: KSerializer<T>, build: (T) -> Parsed): Parsed {
            val value = decode(serializer, call)
                ?: return Parsed.Bad("${call.name} 的参数不是合法 JSON：${call.arguments.take(160)}")
            return build(value)
        }

        return when (call.name) {
            JudgeTools.MOVE -> decodeInto(MoveArgs.serializer()) {
                Parsed.Effect(Consequence.Move(it.id, it.x, it.y, it.z))
            }

            JudgeTools.REPARENT -> decodeInto(ReparentArgs.serializer()) {
                Parsed.Effect(Consequence.Reparent(it.id, it.parent.ifBlank { null }))
            }

            JudgeTools.SET_ATTRIBUTE -> decodeInto(SetAttributeArgs.serializer()) {
                Parsed.Effect(Consequence.SetAttribute(it.id, it.key, it.value))
            }

            JudgeTools.CHANGE_APPEARANCE -> decodeInto(IdArgs.serializer()) {
                Parsed.Effect(Consequence.ChangeAppearance(it.id))
            }

            JudgeTools.DESTROY -> decodeInto(IdArgs.serializer()) {
                Parsed.Effect(Consequence.Destroy(it.id))
            }

            JudgeTools.SPAWN -> decodeInto(SpawnArgs.serializer()) {
                Parsed.Effect(Consequence.Spawn(it.toEntity()))
            }

            JudgeTools.REPLACE -> decodeInto(ReplaceArgs.serializer()) {
                Parsed.Effect(Consequence.Replace(it.oldId, it.toEntity()))
            }

            JudgeTools.AFFECT -> decodeInto(AffectArgs.serializer()) { Parsed.Affects(it.actor) }

            JudgeTools.REJECT -> decodeInto(RejectArgs.serializer()) { Parsed.Rejects(it.reason) }

            else -> Parsed.Bad("第二步不该调用 ${call.name}")
        }
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * 两步共用同一个 system，**逐字不变**。
         *
         * 换掉它，后面的一切都不再共享前缀——缓存就没了。所以步骤特有的指令全部放在 user 消息的末尾，
         * 而这里只写"你是谁、你分两步、你的权限边界"。
         */
        val SYSTEM = """
            你是一个世界的裁定者。你分两步工作，每次只做被要求的那一步：

            1. **对齐**：判断一次动作实际作用于哪些东西。
            2. **后果**：列出它造成的全部后果。

            你不描写、不叙事、不对话——那些是别人的事。后果只能通过工具给出，**你没有直接修改世界的能力**。
        """.trimIndent()
    }
}

// ── 工具参数。字段刻意与线上名字一一对应，改名要同时改 JudgeTools 的 schema ────────────────────

@Serializable private data class TargetArgs(val id: String = "")

@Serializable private data class MoveArgs(val id: String = "", val x: Int = 0, val y: Int = 0, val z: Int = 0)

@Serializable private data class ReparentArgs(val id: String = "", val parent: String = "")

@Serializable private data class SetAttributeArgs(val id: String = "", val key: String = "", val value: String = "")

@Serializable private data class IdArgs(val id: String = "")

@Serializable private data class SpawnArgs(
    val id: String = "",
    val kind: String = "",
    val label: String = "",
    val x: Int = 0, val y: Int = 0, val z: Int = 0,
    val parent: String = "",
) {
    fun toEntity(): Entity = Entity(
        id = id, kind = kind, label = label, x = x, y = y, z = z,
        parent = parent.ifBlank { null },
        // 新东西一开始没有描述，等着被渲染。属性可以随后用 set_attribute 补。
        sentences = emptyList(),
    )
}

@Serializable private data class ReplaceArgs(
    @SerialName("old_id") val oldId: String = "",
    @SerialName("new_id") val newId: String = "",
    val kind: String = "",
    val label: String = "",
    val x: Int = 0, val y: Int = 0, val z: Int = 0,
    val parent: String = "",
) {
    fun toEntity(): Entity = Entity(
        id = newId, kind = kind, label = label, x = x, y = y, z = z,
        parent = parent.ifBlank { null },
        sentences = emptyList(),
    )
}

@Serializable private data class AffectArgs(val actor: String = "")

@Serializable private data class RejectArgs(val reason: String = "")
