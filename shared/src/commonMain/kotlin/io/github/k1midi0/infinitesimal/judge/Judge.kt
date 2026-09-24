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
import io.github.k1midi0.infinitesimal.llm.Usage
import io.github.k1midi0.infinitesimal.spatial.Direction
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.rendered
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
     * 模型明确回答：**做成了，但世界没有因此改变**（`no_change`）。
     *
     * 与 [Empty] 是两件完全不同的事：[Empty] 是协议上的空转（该退回重试），这一条是一个**成立的
     * 判定结果**——世界没动，但这次动作没有被驳回，也没有失败。
     */
    data class Unchanged(val note: String) : Verdict

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

    /**
     * 这次动作**不落在任何东西上**：第一步一个目标都没报，第二步按设计根本没有跑。
     *
     * 它既不是驳回（没有东西可以驳回），也不是失败（协议一路正常）。世界没动。
     */
    data class Nothing(val attempts: Int) : Judgment

    /**
     * 动作成立，但**世界没有因此改变**（第二步调了 `no_change`）。世界没动，[note] 是模型给的那句说明。
     *
     * 它不写库、不作废任何描述——与 [Applied] 的共同点是"这次动作没有被驳回"。
     */
    data class Unchanged(val targets: List<String>, val note: String, val attempts: Int) : Judgment
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
    /**
     * 第二步的思考强度，默认 [ReasoningEffort.HIGH]。
     *
     * **这个旋钮还没有被量出来。** §14.19 拿它跑过四整轮采样（每轮 16 次判定），四个读数之间的差别
     * **全在噪声里**：总耗时 109 / 215 / 236 / 276 秒，单次最慢 36 / 76 / 81 / 80 秒，"符合预期"
     * 12 / 13 / 12 / 12 ——`low` 有一轮最快、也有一轮最慢，中位推理量忽高忽低（950 → 5733）。
     * n=4、每轮还开一个新世界，这种方差下**任何"降档更快"的结论都是过度解读**。
     *
     * 所以默认值保持 `high`：拿一次读数去改它是把噪声当结论。它是个参数，是为了下次**采够样本**
     * 之后再回答（§12 第 17 项）。
     */
    val effort: ReasoningEffort = ReasoningEffort.HIGH,
) {
    /** 给配置确实是固定值的地方用。**强制落到 [SystemId.JUDGE] 那个缓存池**（§14.8）。 */
    constructor(
        config: EndpointConfig,
        maxAttempts: Int = 2,
        effort: ReasoningEffort = ReasoningEffort.HIGH,
    ) : this(
        LlmClient(config.copy(userId = SystemId.JUDGE)),
        maxAttempts,
        effort,
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

    /**
     * 最近一次判定里，第一步**报了什么**。
     *
     * **`null` = 驳回，或重试用尽；空列表 = 一个都没报。**「报空」与「驳回」在结果表上长得一样
     * （都让第二步白跑一趟），所以必须分开记——不记的话诊断只能靠反推（§14.16 就是这么推出来的）。
     */
    var alignedTargets: List<String>? = null
        private set

    /** 最近一次判定里模型调过 [JudgeTools.CALC] 的式子，按调用顺序。空列表 = 一次都没算。 */
    var calcExpressions: List<String> = emptyList()
        private set

    /**
     * 最近一次判定里，**第二步最后一轮**的用量。
     *
     * `completion_tokens_details.reasoning_tokens` 是这一层唯一能证明"它想了多久"的东西，而
     * `finish_reason == "length"` 加上这个数字撞到 `max_tokens`，就是**被截断**的直接证据
     * （§14.19 用它区分了"截断"与"模型拒绝回答"——在那之前两者长得一模一样）。
     */
    var lastUsage: Usage? = null
        private set

    /**
     * 最近一次判定里，**第二步最后一轮**的 `finish_reason`。
     *
     * `length` 就是"被 `max_tokens` 截断"——它和"模型没话说"在结果上完全一样，只有这个字段能把
     * 它们分开（§14.19）。
     */
    var lastFinishReason: String? = null
        private set

    // ── 第一步：对齐指向对象 ────────────────────────────────────────────────────────

    suspend fun alignTargets(
        world: World,
        request: JudgeRequest,
        retryNotes: List<String> = emptyList(),
        view: ViewBudget = ViewBudget(world, request.actorId, request.centers),
        calc: CalcLog = CalcLog(),
    ): TargetAlignment {
        val variables = Variables.of(world, listOf(request.actorId) + request.selected)
        val answered = call(
            tools = JudgeTools.TARGETING,
            user = targetingPrompt(world, request, retryNotes, view, variables),
            // 快速档：对齐不需要推理。**只有这一档 temperature 才生效**，而这里也不需要温度。
            thinking = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.LOW),
            maxTokens = 1_024,
            view = view,
            variables = variables,
            calc = calc,
        )
        val calls = answered.proposals
        if (calls.isEmpty()) {
            // 这不是「报空」。**发起者本人永远是对象之一**，所以零工具调用一定是模型没照做——
            // 提示词允许的那条「一个都不报」已经删掉了（§14.18）。重试说明要可执行，不能只说
            // 「你没调工具」；而"它说了什么、被谁停下"这两样缺了，这条日志就只能靠猜。
            return TargetAlignment.Malformed(
                listOf(
                    "一个工具都没调。任何动作至少落在发起者身上——" +
                        "报它（${JudgeTools.TARGET}），或者说明这一片里为什么没有它点的东西（${JudgeTools.REJECT_TARGETS}）" +
                        answered.diagnosis(),
                ),
            )
        }

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
        calc: CalcLog = CalcLog(),
    ): Verdict {
        val variables = Variables.of(world, listOf(request.actorId) + targets)
        val answered = call(
            tools = JudgeTools.EFFECTS,
            user = effectPrompt(world, request, targets, retryNotes, view, variables),
            // 思考档：整套设计把"物理推理"整个交给了模型（附录 A.4——现实无法用代码模拟），
            // 这里就是那件事发生的地方，也是全系统里最该让它想清楚的一处。
            thinking = ThinkingRequest.of(ThinkingMode.Always, effort),
            // **8192，不是 2000。** 思考模式下思维链 token 也算在 `max_tokens` 里，2000 对
            // `reasoning_effort: high` 来说太紧——§14.19 量到过 4 次里有 2 次第二步一个工具都没调，
            // 而那种失败与"模型拒绝回答"在日志里长得一模一样。它是**上限不是预留**，调高只在真的
            // 用掉时才花钱；截断才是净亏（白等 20 秒，什么也没得到）。
            maxTokens = 8_192,
            view = view,
            variables = variables,
            calc = calc,
        )
        val calls = answered.proposals
        if (calls.isEmpty()) return Verdict.Empty("第二步没有调用任何工具" + answered.diagnosis())

        val consequences = mutableListOf<Consequence>()
        val affected = mutableListOf<String>()
        var rejection: String? = null
        var noChange: String? = null

        for (call in calls) {
            when (val parsed = parseEffect(call)) {
                is Parsed.Effect -> consequences += parsed.consequence
                is Parsed.Affects -> affected += parsed.actor
                is Parsed.Rejects -> rejection = parsed.reason
                is Parsed.Unchanged -> noChange = parsed.note
                is Parsed.Bad -> return Verdict.Malformed(listOf(parsed.problem))
            }
        }

        // **`no_change` 必须单独出现。** 它说的是「世界没有改变」，而别的产物每一条都在说"改变了
        // 一样东西"。并存时不是挑一边就完了——两边都自洽，选错的那一边会把一次真的改动静默吞掉。
        val note = noChange
        if (note != null) {
            val clash = buildList {
                if (consequences.isNotEmpty()) add("${consequences.size} 条后果")
                if (rejection != null) add("一条驳回")
                if (affected.isNotEmpty()) add("${affected.size} 个受影响的对象")
            }
            if (clash.isNotEmpty()) {
                return Verdict.Malformed(
                    listOf(
                        "调了 ${JudgeTools.NO_CHANGE}（世界没有因此改变），同时又给了${clash.joinToString("、")}；" +
                            "这两件事互斥，要哪一个",
                    ),
                )
            }
            return Verdict.Unchanged(note)
        }

        // **驳回与后果不能并存。** 默默选一边正是那种会把 bug 藏起来的事：两边都自洽，谁都不会报错。
        if (rejection != null && consequences.isNotEmpty()) {
            return Verdict.Malformed(
                listOf("同时给了驳回理由又提议了 ${consequences.size} 条后果；这两个是互斥的，要哪一个"),
            )
        }
        rejection?.let { return Verdict.Rejected(it) }

        if (consequences.isEmpty()) {
            // 这条重试说明要**可执行**。加 `no_change` 之前，这里只说「只说了影响谁，没有说要发生什么」，
            // 模型于是硬造一条后果出来——那正是「只落在自己身上」慢一倍的原因（§14.18）。
            return Verdict.Malformed(
                listOf(
                    "没有说要发生什么。要么用工具列出这次动作造成的后果；" +
                        "要么，如果它确实没有改变世界（没有东西移动、属性变化、出现或消失），" +
                        "就明确调 ${JudgeTools.NO_CHANGE} 把这件事说出来",
                ),
            )
        }
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
        val turn = Turn(world, request, view)
        return try {
            runJudgmentOn(turn)
        } finally {
            // 提前返回的几条路也要记上——驳回的那一次同样扩过，那也是成本。
            viewWidenings = view.widenings
            viewDirections = view.asked
            alignedTargets = turn.alignedTargets
            calcExpressions = turn.calc.expressions
        }
    }

    /**
     * 开一次判定，**第一步之后可以停下来**（§4.7 纠正预览）。
     *
     * 停下来这一段时间里 [Turn] 一直活着，因为它攥着那个 `ViewBudget`——**扩大的预算是一次动作的
     * 属性，不是某一步的属性**：第一步扩过之后，玩家确认完再跑第二步时，拿到的是已经大了的那个框。
     *
     * 一次性的那条路（[judgeAndCommit]）与它共用同一份实现，所以"停了再走"不会长出一条只在预览
     * 那条路上才有的分叉。
     */
    fun beginTurn(world: World, request: JudgeRequest): Turn =
        Turn(world, request, ViewBudget(world, request.actorId, request.centers))

    /** 一次判定的两段。每一步各自最多 [maxAttempts] 次模型调用。 */
    inner class Turn internal constructor(
        private val world: World,
        private val request: JudgeRequest,
        private val view: ViewBudget,
    ) {
        /** 第一步用了几次调用。`0` 表示还没跑过。 */
        var alignAttempts: Int = 0
            private set

        /** 第一步报了什么。**`null` = 还没跑、或重试用尽；空列表 = 一个都没报。** */
        var alignedTargets: List<String>? = null
            private set

        /**
         * 这次判定里 `calc` 被调用的记录。**两部分共用一份**——与 [ViewBudget] 同一条理由：
         * 它们是这次动作的属性，不是某一步的属性。
         */
        internal val calc = CalcLog()

        /**
         * 第一步：对齐指向对象。
         *
         * 返回 [TargetAlignment.Malformed] 表示**重试用尽**（不是"这一次格式不对"）——模型修不好
         * 的那种失败，再问下去只是白花钱。
         */
        suspend fun align(): TargetAlignment {
            var problems = listOf<String>()
            for (attempt in 1..maxAttempts) {
                alignAttempts = attempt
                when (val alignment = alignTargets(world, request, problems, view, calc)) {
                    is TargetAlignment.Aligned -> {
                        alignedTargets = alignment.targets
                        return alignment
                    }

                    is TargetAlignment.Rejected -> return alignment
                    is TargetAlignment.Malformed -> problems = alignment.problems
                }
            }
            return TargetAlignment.Malformed(listOf("对齐指向对象失败") + problems)
        }

        /**
         * 第二步：列后果、校验、提交。
         *
         * [targets] 由调用方给，而不是从 [align] 记下来——因为**玩家可以在预览那一步改它**
         * （§4.7）。这里要的正是"最后确认过的那个集合"。
         */
        suspend fun finish(targets: List<String>): Judgment {
            // **空目标没有第二步。** 发起者本人永远是对象之一（§14.18），所以这一条现在**够不到**；
            // 留着是因为"够不到"和"不可能"是两回事——真出现了，宁可什么都不发生，也不要拿一句
            // 空目标去白跑一趟第二步。
            if (targets.isEmpty()) return Judgment.Nothing(alignAttempts)

            val problems = mutableListOf<String>()
            for (attempt in 1..maxAttempts) {
                when (val verdict = proposeEffects(world, request, targets, problems.toList(), view, calc)) {
                    is Verdict.Rejected ->
                        return Judgment.RejectedWorld(verdict.reason, Stage.EFFECT, attempt)

                    // **做成了，但世界没有改变。** 不再重试，也不写库——它不是失败。
                    is Verdict.Unchanged -> return Judgment.Unchanged(targets, verdict.note, attempt)

                    is Verdict.Empty -> problems += "模型没有提议任何后果，也没有驳回（${verdict.note}）"

                    is Verdict.Malformed -> problems += verdict.problems

                    is Verdict.Proposed -> when (
                        val result = Committer.apply(world, verdict.consequences, verdict.affected)
                    ) {
                        is Committer.Applied ->
                            return Judgment.Applied(targets, result.consequences, result.affected, attempt)

                        is Committer.Rejected -> problems += result.problems
                    }
                }
            }
            return Judgment.FailedProtocol(problems.toList(), maxAttempts)
        }
    }

    private suspend fun runJudgmentOn(turn: Turn): Judgment = when (val alignment = turn.align()) {
        is TargetAlignment.Rejected ->
            Judgment.RejectedWorld(alignment.reason, Stage.ALIGN, turn.alignAttempts)

        is TargetAlignment.Malformed -> Judgment.FailedProtocol(alignment.problems, maxAttempts)

        is TargetAlignment.Aligned -> turn.finish(alignment.targets)
    }

    private suspend fun runJudgment(world: World, request: JudgeRequest, view: ViewBudget): Judgment =
        runJudgmentOn(Turn(world, request, view))

    // ── 请求构造 ───────────────────────────────────────────────────────────────────

    private suspend fun call(
        tools: List<io.github.k1midi0.infinitesimal.llm.ToolDefinition>,
        user: String,
        thinking: ThinkingRequest,
        maxTokens: Int,
        view: ViewBudget,
        variables: Map<String, Double>,
        calc: CalcLog,
    ): Answered {
        val messages = mutableListOf(ChatMessage.system(SYSTEM), ChatMessage.user(user))
        val proposals = mutableListOf<RawCall>()

        /**
         * **查询类的手**：调用它们之后这一轮接着往下走，结果灌回去再问一次。
         *
         * 判据是"它有没有提议改世界"，不是"它是不是 `widen_view`"——把 `calc` 加进来的那天，
         * 原先那个写死 `WIDEN_VIEW` 的判断会让它被当成一条后果，然后整轮判定报协议内失败。
         */
        fun isQuery(name: String): Boolean = name == JudgeTools.WIDEN_VIEW || name == JudgeTools.CALC

        while (true) {
            requestCount++
            val turn = RawTurn.of(
                client.streamChat(
                    messages = messages,
                    tools = tools,
                    thinking = thinking,
                    maxTokens = maxTokens,
                ).toList(),
            )
            proposals += turn.calls.filterNot { isQuery(it.name) }
            if (turn.calls.none { isQuery(it.name) }) {
                // **最后一轮的用量**：第二步跑过就以它为准，只跑到第一步就以第一步为准。
                lastUsage = turn.usage
                lastFinishReason = turn.finishReason
                return Answered(proposals, turn.content, turn.finishReason, turn.usage)
            }

            // 这一轮模型只是想看得更远、或者想算一个数。把它**如实**记回历史再接着问——有两样不能省：
            // assistant 那条要带 `reasoning_content`（带 tools 的请求里它必须在，§1.2.1），
            // 而每一个 tool_call_id 都要有一条 tool 结果，端点是这么要求配对的。
            messages += turn.asAssistantMessage()
            for (c in turn.calls) {
                val answer = when (c.name) {
                    JudgeTools.WIDEN_VIEW -> {
                        // 方向是**可选**的。认不出来的词就整体扩——不退回让模型重说：它"想看得更远"
                        // 这个愿望是明确的，只有"哪边"没表达清楚，为这个多烧一整轮不值得。
                        val wanted = decode(WidenArgs.serializer(), c)
                            ?.direction
                            ?.let(Direction::byLabel)
                        if (wanted != null) view.widen(wanted) else view.widen()
                    }

                    JudgeTools.CALC -> calculate(c, variables, calc)

                    else ->
                        // 同一轮里既报了东西又要查询。**不能把这个报的东西丢掉**——它是模型
                        // 已经做出的判断，而这一轮之后它没有理由再报一次。
                        "（记下了。你这一轮还调了查询的手，所以它先搁着；看到新东西之后再一起给。）"
                }
                messages += ChatMessage.toolResult(c.id, answer)
            }
        }
    }

    /**
     * 把一次 `calc` 的结果写成一句回给模型的话。
     *
     * **算不出来不是协议内失败**，它是模型可以自己修的一步：式子里的变量名打错了、括号没关、除以
     * 零——把问题原样回给它，它下一轮就改。要是这里当成 `Malformed`，一次打错名字就会让整轮判定
     * 重来，而重试时它手里并没有"是哪个名字错了"这条信息。
     */
    private fun calculate(call: RawCall, variables: Map<String, Double>, log: CalcLog): String {
        val args = decode(CalcArgs.serializer(), call)
            ?: return "${JudgeTools.CALC} 的参数不是合法 JSON：${call.arguments.take(160)}"
        log.record(args.expression)
        return when (val result = Calc.evaluate(args.expression, variables)) {
            is Calc.Result.Value -> "${args.expression} = ${Calc.format(result.value)}"
            is Calc.Result.Failed -> "算不出来：${result.problem}"
        }
    }

    /**
     * 两步**逐字相同**的那一段。
     *
     * 它是缓存唯一可能命中的地方：两步的 system 相同、情境相同、发起者与动作相同，之后才分岔。
     * 所以**任何会变的东西都不许放进这里**——放进来一次，第二步的缓存就全灭（§7.5 是同一条理由）。
     */
    private fun sharedPrefix(world: World, request: JudgeRequest, view: ViewBudget): String = buildString {
        appendLine("【你周围这一片】")
        // **这句是给「驳回理由不撒谎」那条硬规则垫底的。** 不说，模型会把「这一片里没有」说成
        // 「没有」——而它手里真的只有这一片（§14.12 那次量出来的就是这个）。框外有什么仍然不给：
        // 那会诱它拿手边同类顶替（§14.13）。
        appendLine("下面这一段**只是发起者身边的一小片，不是整个世界**——这一片里没有的东西，不等于世上没有。")
        appendLine(view.text())
        appendLine()
        appendLine("【动作发起者】")
        appendLine("id=${request.actorId}，名字=${request.actorLabel}")
        // 发起者的人设卡（§3.5）。**放在共用前缀里**：两步都要它，而前缀两步一字不差，缓存才仍然
        // 命中（§8 的动静分层）。指向的其它角色的卡在第二步自己那一段里。
        world.byId(request.actorId)?.cards?.rendered()?.takeIf { it.isNotEmpty() }?.let { appendLine(it) }
        appendLine()
        appendLine("【动作】")
        appendLine(request.intention)
        // 规则书（§12 第 12 项）。**世界与发起者那两份放在共用前缀里**：两步都要它们，而前缀两步
        // 逐字相同，缓存才仍然命中（§8）。指向的东西那几份在第二步自己那一段里——那时才知道指向谁。
        RulebookView.forPrefix(world, request.actorId).takeIf { it.isNotEmpty() }?.let {
            appendLine()
            append(it)
        }
    }

    private fun targetingPrompt(
        world: World,
        request: JudgeRequest,
        retryNotes: List<String>,
        view: ViewBudget,
        variables: Map<String, Double>,
    ): String = buildString {
        append(sharedPrefix(world, request, view))
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
        appendLine("- **发起者本人也是对象。**「我理了理衣襟」「我环顾四周」「我打了个哈欠」——这类动作只有它一个人，那就报它自己，照常走完两步。")
        appendLine("- 报的东西**也包括会被这次动作牵涉到的角色**（「我喊了一嗓子」——旁边那个人也算，虽然它没被指着）。")
        appendLine()
        appendLine("**永远不要一个工具都不调**：任何动作至少落在发起者身上。")
        appendLine()
        appendLine("**动作点名了一样东西、而这一片里没有它**——这是另一回事：调 ${JudgeTools.REJECT_TARGETS}，理由说清「这一片里没有……」。")
        appendLine()
        appendLine("**够不够得着、说不说得出口，同样看规则书**：里面有冲突时采信数字大的那条，并在理由里点名是哪一条。")
        appendLine()
        appendLine("**不要在这一步推演后果**，那是下一步的事。")
        appendVariables(this, variables)
        appendRetryNotes(retryNotes)
    }

    private fun effectPrompt(
        world: World,
        request: JudgeRequest,
        targets: List<String>,
        retryNotes: List<String>,
        view: ViewBudget,
        variables: Map<String, Double>,
    ): String =
        buildString {
            append(sharedPrefix(world, request, view))
            appendLine()
            appendLine("【这次动作作用在这些东西上】")
            if (targets.isEmpty()) {
                appendLine("（不作用于任何具体的东西）")
            } else {
                targets.forEach { appendLine("- $it") }
            }
            appendTargetCards(world, request.actorId, targets)
            RulebookView.forTargets(world, request.actorId, targets).takeIf { it.isNotEmpty() }?.let {
                append(it)
            }
            appendLine()
            appendLine("【你这一步要做的事：列出后果】")
            appendLine(
                "1. 先判断这次动作在这个情境下成不成立——**规则书是依据**。不成立就调 ${JudgeTools.REJECT}，" +
                    "理由写成这个世界会说的话；**规则书里有冲突时，采信数字大的那条，并在理由里点名是哪一条**。",
            )
            appendLine("2. 成立就用工具列出它造成的**全部**后果——包括不显眼但确实发生的（桌上的东西跟着滑动、火把脱手落地）。")
            appendLine("3. 被这次动作影响到的角色，每个调一次 ${JudgeTools.AFFECT}。发起者本人不用记。")
            appendLine("4. 只列这次动作确实造成的后果。不要顺手整理世界。")
            appendLine(
                "5. **做成了、但世界一点都没变**（没有东西移动、属性变化、出现或消失）——" +
                    "那就调 ${JudgeTools.NO_CHANGE} 把这件事说出来，**不要硬造一条后果**。它必须单独出现。",
            )
            appendVariables(this, variables)
            appendRetryNotes(retryNotes)
        }

    /**
     * 【可以算的数】：这一步里 [JudgeTools.CALC] 能用的变量（[Variables] 那份）。
     *
     * **一个数都没有时整块不出现**：列一个空清单只会占掉真实内容的位置，而模型本来也没打算算。
     * 这份清单同时也是那只工具的变量名来源——工具 schema 是静态的，列不出动态的名字，只能写在这里。
     */
    private fun appendVariables(builder: StringBuilder, variables: Map<String, Double>) {
        if (variables.isEmpty()) return
        builder.appendLine()
        builder.appendLine("【可以算的数】（要算就调 ${JudgeTools.CALC}，别心算）")
        builder.appendLine(
            variables.entries.sortedBy { it.key }.joinToString("，") { "${it.key}=${Calc.format(it.value)}" },
        )
    }

    /**
     * 这次动作指向的**其它角色**的人设卡（§3.5）。
     *
     * 发起者自己的卡已经在共用前缀里，这里跳过它。**按「有没有卡」取，不按 `kind` 取**——角色
     * 就是物体的一种，多一个 `kind == "角色"` 的判断只会多一处会写错的地方。
     *
     * 它只能放在**第二步自己那一段**里：第一步还不知道要指向谁，而共用前缀必须两步一字不差，
     * 否则缓存整段作废（§8）。
     */
    private fun StringBuilder.appendTargetCards(world: World, actorId: String, targets: List<String>) {
        val others = targets.filter { it != actorId }
            .mapNotNull { world.byId(it) }
            .filter { it.cards.isNotEmpty() }
        if (others.isEmpty()) return
        appendLine()
        appendLine("【这次动作指向的角色】")
        for (e in others) {
            appendLine("${e.label}（id=${e.id}）：")
            appendLine(e.cards.rendered())
        }
    }

    private fun StringBuilder.appendRetryNotes(notes: List<String>) {
        if (notes.isEmpty()) return
        appendLine()
        appendLine("【上一次尝试被拒绝了，请修正】")
        notes.forEach { appendLine("- $it") }
    }

    private class RawCall(val id: String, val name: String, val arguments: String)

    /**
     * 一次"问到结束"的结果。
     *
     * 除了攒下的提议，它还留着模型**最后那一轮说的话**和端点的 `finish_reason`。两样都是诊断用的，
     * 而它们是这一层唯一能拿到的信号：第二步一个工具都没调时，光有"没调"这三个字，日志里那条失败
     * **与被截断、被拒绝、模型跑偏三种情况长得一模一样**（§14.19 就是这么撞上的）。
     */
    private class Answered(
        val proposals: List<RawCall>,
        val reply: String,
        val finishReason: String?,
        val usage: Usage?,
    ) {
        fun diagnosis(): String = buildString {
            val said = reply.trim()
            if (said.isNotEmpty()) append("；它说的是：「${said.take(200)}」")
            append("；finish_reason=${finishReason ?: "（这次没有收到）"}")
            usage?.completionTokensDetails?.let { append("；推理想了 ${it.reasoningTokens} token") }
        }
    }

    /**
     * 一轮流式响应拼起来的样子。
     *
     * 与原先那个只收工具调用的版本相比，多留了两样，而这两样都是硬的：**这条 assistant 消息的思考
     * 内容**，和**每个工具调用的 id**。要让这一轮接着往下走（`widen_view` 是唯一那种情况），端点
     * 要求每条 assistant 都带着 `reasoning_content`，而每条 tool 结果都要对上 `tool_call_id`——
     * 只收 name 与 arguments 是做不了这件事的。
     */
    private class RawTurn(
        val content: String,
        val reasoning: String,
        val calls: List<RawCall>,
        val finishReason: String?,
        val usage: Usage?,
    ) {

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
            fun of(events: List<LlmEvent>): RawTurn {
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

                        // **`finish_reason` 要留着。** §14.5 记过 Pulsus 把它丢掉、于是"被截断"与
                        // "正常说完"在日志里长得一样；`length` 正是"第二步一个工具都没调"的常见原因。
                        is LlmEvent.Finished -> {
                            finish = event.reason
                            usage = event.usage
                        }
                    }
                }
                val calls = names.keys.sorted().map { index ->
                    RawCall(
                        ids[index].orEmpty(),
                        names.getValue(index),
                        arguments[index]?.toString().orEmpty(),
                    )
                }
                return RawTurn(content.toString(), reasoning.toString(), calls, finish, usage)
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
        data class Unchanged(val note: String) : Parsed
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

            JudgeTools.NO_CHANGE -> decodeInto(NoteArgs.serializer()) { Parsed.Unchanged(it.note) }

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

/** `no_change` 的参数。 */
@Serializable private data class NoteArgs(val note: String = "")

/** `calc` 的参数。**只有一个字符串**——schema 只支持扁平类型，而表达式本来就是一个字符串。 */
@Serializable private data class CalcArgs(val expression: String = "")

@Serializable private data class RejectArgs(val reason: String = "")
