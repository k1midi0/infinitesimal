package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.AssistantTurn
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.FunctionCall
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.LlmEvent
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.ToolCall
import io.github.k1midi0.infinitesimal.llm.ToolCallRequest
import io.github.k1midi0.infinitesimal.llm.Usage
import io.github.k1midi0.infinitesimal.llm.decodeCall
import io.github.k1midi0.infinitesimal.spatial.Direction
import io.github.k1midi0.infinitesimal.spatial.distanceBetween
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.VOXEL_CM
import io.github.k1midi0.infinitesimal.world.TerrainBox
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.rendered
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.time.TimeSource

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
    /**
     * **这一轮说了什么话**（原话，没有就是空串）。§14.37。
     *
     * 它进的是第一步，因为要判的是「**这句话指向谁**」——那是一个语义判断，代码做不了。而它一旦进来，
     * 说话与动作就**共用同一次第一步**：早先说话要单独再跑一次对齐去找听者，于是"说+做"的一轮要花
     * 两次调用，还多出一处两条路会漂开的地方。
     *
     * 它**不进**第二步：第二步算的是动作的后果，而说话不产生后果。
     */
    val said: String = "",
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
    data class Aligned(
        val targets: List<String>,
        /**
         * **这句话是说给谁听的**（§14.37）。这次没有话要说时是空的。
         *
         * 它与 [targets] 分开，是两件事：`targets` 决定**这次动作作用在什么上**（也是纠正预览要比对的
         * 那一份），这个决定**谁被唤醒**。混成一栏的话，"跟同伴闲聊一句"与"大吼一嗓子"就分不开了
         * ——而它们在世界里完全不同。
         */
        val addressed: List<String> = emptyList(),
    ) : TargetAlignment

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
     * 第二步的思考强度，默认 [ReasoningEffort.HIGH]——**也就是端点的默认，不为了快往下降**。
     *
     * 这条是用户定的（2026-09-24）：**不限制思考，防止逻辑出错；导演系统同理。** 判定是全系统里
     * 唯一被托付"物理推理"的地方（附录 A.4——现实无法用代码模拟），在这里省它的思考预算，等于用
     * 判错换几秒钟。
     *
     * 之所以它还是个参数：§14.19 拿它跑过四整轮采样，四个读数之间的差别**全在噪声里**（总耗时
     * 109–276 秒、单次最慢 36–81 秒、符合预期 12/13/12/12），n=4 分不开。参数留着是为了**能再量**，
     * 不是因为准备降档。
     */
    val effort: ReasoningEffort = ReasoningEffort.HIGH,
    /**
     * 建模那一段（附录 A.9）。`model_shape` 被调用时整个转交给它。
     *
     * **默认复用判定的这个 client**，于是两者会共用一个缓存池。config 那条构造器给的是独立的一份
     * （`SystemId.MODELER`）——共用只是"拿不到配置时也还能跑"的兜底，不是想要的形态。
     */
    private val modeler: ShapeModeler = ShapeModeler(client),
) {
    /** 给配置确实是固定值的地方用。**强制落到 [SystemId.JUDGE] 那个缓存池**（§14.8）。 */
    constructor(
        config: EndpointConfig,
        maxAttempts: Int = 2,
        effort: ReasoningEffort = ReasoningEffort.HIGH,
        /**
         * 预设库（A.14）。**它由调用方拿进来，不在这里新建**：建模的产物是一条预设，而那条预设
         * 该进的是**会话那一份**库——判定、生成器、导演看的是同一个世界，也就该看同一批形状。
         */
        presets: PresetLibrary = PresetLibrary(),
    ) : this(
        LlmClient(config.copy(userId = SystemId.JUDGE)),
        maxAttempts,
        effort,
        // 建模独占一池（SystemId.MODELER 的注释）：它的前缀是几百 token 的网格语法、每次都逐字相同，
        // 是这套东西里最该命中缓存的一段。
        ShapeModeler(LlmClient(config.copy(userId = SystemId.MODELER)), presets = presets),
    )

    /** 两步加起来的模型调用次数。 */
    var requestCount = 0
        private set

    /**
     * 这一局的预设库（附录 A.14）。
     *
     * 露出来是因为**它的寿命该与一局相同**，不是与一次判定相同：生成包是存档要带走的那一份。现在
     * 它挂在 [modeler] 上，而判定器是每局造一个，两者寿命恰好对上；等 `session/` 有了真正的存档文件，
     * 该由那边持有它、再传进来。
     */
    val presets: PresetLibrary get() = modeler.presets

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
     * 最近一次判定里模型用 [JudgeTools.DISTANCE] 问过哪些对，按调用顺序。空列表 = 一次都没问。
     *
     * **这一栏本身就是一条诊断**：判"够不够得着"却不问距离，它就会自己拿坐标推式子——实测里那次
     * 花了四千多个思维 token、22 秒（§14.24）。有对还是零对，一眼就能分开。
     */
    var distancePairs: List<String> = emptyList()
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

    /**
     * 这一次判定的时间账（见 [TimingLog]）。
     *
     * **记的位置只有一处：`call()` 循环里的每一次请求。** 于是"这一轮发了几次请求"（[requestCount]
     * 的增量）与"这一轮记了几条"永远是同一个数——两边不会各说各话，而这一点在量延迟时是全部意义所
     * 在：之前只有"这一轮一共几秒"，钱花在第一步还是第二步、是排队还是思考，全靠猜。
     */
    val timing = TimingLog()

    // ── 第一步：对齐指向对象 ────────────────────────────────────────────────────────

    suspend fun alignTargets(
        world: World,
        request: JudgeRequest,
        retryNotes: List<String> = emptyList(),
        view: ViewBudget = ViewBudget(world, request.actorId, request.centers),
        calc: CalcLog = CalcLog(),
        /** 问过哪些对之间的距离。与 [calc] 同一条理由：这次动作的属性，两步共用一份。 */
        distances: DistanceLog = DistanceLog(),
        /** 这是第几次尝试（1 起）。**只用来记账**——重试与首次在结果表上长得一样，只有这里分得开。 */
        attempt: Int = 1,
    ): TargetAlignment {
        val variables = Variables.of(world, listOf(request.actorId) + request.selected)
        val answered = call(
            world = world,
            tools = JudgeTools.TARGETING,
            user = targetingPrompt(world, request, retryNotes, view, variables),
            // 快速档：对齐不需要推理。**只有这一档 temperature 才生效**，而这里也不需要温度。
            thinking = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.LOW),
            view = view,
            variables = variables,
            calc = calc,
            // 第一步不建模（`model_shape` 只挂在 EFFECTS 那组里），给一份空的只是为了让这一步与第二步
            // 走同一条 `call`。
            shaped = ShapeLog(),
            distances = distances,
            stage = Stage.ALIGN,
            attempt = attempt,
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
        val addressed = mutableListOf<String>()
        var rejection: String? = null
        for (call in calls) {
            when (call.name) {
                JudgeTools.TARGET -> {
                    val args = decode(TargetArgs.serializer(), call) ?: return TargetAlignment.Malformed(
                        listOf("target 的参数不是合法 JSON：${call.arguments.take(160)}"),
                    )
                    targets += args.id
                }

                JudgeTools.ADDRESS -> {
                    val args = decode(TargetArgs.serializer(), call) ?: return TargetAlignment.Malformed(
                        listOf("address 的参数不是合法 JSON：${call.arguments.take(160)}"),
                    )
                    addressed += args.id
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

        return TargetAlignment.Aligned(targets.distinct(), addressed.distinct())
    }

    // ── 第二步：列出后果 ────────────────────────────────────────────────────────────

    suspend fun proposeEffects(
        world: World,
        request: JudgeRequest,
        targets: List<String>,
        retryNotes: List<String> = emptyList(),
        view: ViewBudget = ViewBudget(world, request.actorId, request.centers),
        calc: CalcLog = CalcLog(),
        shapeLog: ShapeLog = ShapeLog(),
        /** 问过哪些对之间的距离。与 [calc] 同一条理由：这次动作的属性，两步共用一份。 */
        distances: DistanceLog = DistanceLog(),
        /** 这是第几次尝试（1 起）。**只用来记账**。 */
        attempt: Int = 1,
    ): Verdict {
        val variables = Variables.of(world, listOf(request.actorId) + targets)
        val answered = call(
            world = world,
            tools = JudgeTools.EFFECTS,
            user = effectPrompt(world, request, targets, retryNotes, view, variables, distances),
            // 思考档：整套设计把"物理推理"整个交给了模型（附录 A.4——现实无法用代码模拟），
            // 这里就是那件事发生的地方，也是全系统里最该让它想清楚的一处。
            thinking = ThinkingRequest.of(ThinkingMode.Always, effort),
            view = view,
            variables = variables,
            calc = calc,
            shaped = shapeLog,
            distances = distances,
            stage = Stage.EFFECT,
            attempt = attempt,
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
        // 这条路**自己造 [Turn]**，不走 [beginTurn]——所以清账要在这里再来一次。
        // （测试逮到过：漏了这一处，第二次判定的账会接着上一次数下去。）
        timing.clear()
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
            distancePairs = turn.distances.pairs
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
    fun beginTurn(world: World, request: JudgeRequest): Turn {
        // 账是**这一次判定**的，不是这一局的——不清就会把上一轮的时间混进来。
        timing.clear()
        return Turn(world, request, ViewBudget(world, request.actorId, request.centers))
    }

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
         * 这次判定里建模造出来的形状。与 [calc] 同一个位置、同一条理由：这次动作的属性，两步共用。
         *
         * 实际上只有第二步会用到它——`model_shape` 只挂在 `EFFECTS` 那组工具里。
         */
        internal val shaped = ShapeLog()

        /**
         * 这次判定里问过哪些对之间的距离。与 [calc] 同一个位置、同一条理由：这次动作的属性，两步共用。
         */
        internal val distances = DistanceLog()

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
                when (val alignment = alignTargets(world, request, problems, view, calc, distances, attempt)) {
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
                when (
                    val verdict = proposeEffects(
                        world, request, targets, problems.toList(), view, calc, shaped, distances, attempt,
                    )
                ) {
                    is Verdict.Rejected ->
                        return Judgment.RejectedWorld(verdict.reason, Stage.EFFECT, attempt)

                    // **做成了，但世界没有改变。** 不再重试，也不写库——它不是失败。
                    is Verdict.Unchanged -> return Judgment.Unchanged(targets, verdict.note, attempt)

                    is Verdict.Empty -> problems += "模型没有提议任何后果，也没有驳回（${verdict.note}）"

                    is Verdict.Malformed -> problems += verdict.problems

                    is Verdict.Proposed -> when (
                        val result = Committer.apply(
                            world,
                            // **建模造的形状接在模型给的那些后面。** 它作用的通常正是这一批里刚 spawn
                            // 或 replace 出来的新东西，而影子状态是按顺序走的（见 ShapeLog.consequences）。
                            verdict.consequences + shaped.consequences(),
                            verdict.affected,
                        )
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

    private suspend fun runJudgment(world: World, request: JudgeRequest, view: ViewBudget): Judgment {
        // 与 [beginTurn] 同一条理由：`judgeAndCommit` 不走那条路，它自己造 [Turn]。
        timing.clear()
        return runJudgmentOn(Turn(world, request, view))
    }

    // ── 请求构造 ───────────────────────────────────────────────────────────────────

    private suspend fun call(
        world: World,
        tools: List<io.github.k1midi0.infinitesimal.llm.ToolDefinition>,
        user: String,
        thinking: ThinkingRequest,
        view: ViewBudget,
        variables: Map<String, Double>,
        calc: CalcLog,
        shaped: ShapeLog,
        /** 这一次判定里问过哪些对之间的距离。与 [calc] 同一个位置、同一条理由：两步共用一份。 */
        distances: DistanceLog,
        /** 这一次调用属于哪一步。**只用来记账**——两步的账必须分得开，否则"钱花在哪"还是猜。 */
        stage: Stage,
        /** 这是第几次尝试（1 起）。**只用来记账**。 */
        attempt: Int,
    ): Answered {
        val messages = mutableListOf(ChatMessage.system(SYSTEM), ChatMessage.user(user))
        val proposals = mutableListOf<ToolCallRequest>()

        /**
         * **查询类的手**：调用它们之后这一轮接着往下走，结果灌回去再问一次。
         *
         * 判据是"它有没有提议改世界"，不是"它是不是 `widen_view`"——把 `calc` 加进来的那天，
         * 原先那个写死 `WIDEN_VIEW` 的判断会让它被当成一条后果，然后整轮判定报协议内失败。
         */
        fun isQuery(name: String): Boolean =
            name == JudgeTools.WIDEN_VIEW || name == JudgeTools.CALC ||
                name == JudgeTools.MODEL_SHAPE || name == JudgeTools.DISTANCE

        var round = 0
        while (true) {
            requestCount++
            round++
            // **每一次请求恰好记一条账**（见 [TimingLog]）。两个时刻：
            // `first` 是收到的**第一个事件**——在那之前模型一个字都没吐，全是排队与预填充；
            // 收完与它之差就是生成与思考。思考模式下这一截里绝大部分是思维链。
            // 分开量是因为治法完全不同：等的那一截靠并发/端点，想的那一截靠提示词与档位。
            val mark = TimeSource.Monotonic.markNow()
            var first: Long? = null
            val turn = AssistantTurn.of(
                client.streamChat(
                    messages = messages,
                    tools = tools,
                    thinking = thinking,
                    // **不设 `max_tokens`。** 思考模式下思维链也算在它里面，任何一个小数字都会把
                    // 想得久的那一次**截断**——症状是"第二步一个工具都没调"，与"模型拒绝回答"长得
                    // 一模一样（§14.19 量到过 2000 时的 4/16、8192 时的 1/16）。用户定的是
                    // **不限制思考，防止逻辑出错**；发 null 就是不发这一项，端点用思考档默认的 64K。
                    maxTokens = null,
                )
                    .onEach { if (first == null) first = mark.elapsedNow().inWholeMilliseconds }
                    .toList(),
            )
            val elapsed = mark.elapsedNow().inWholeMilliseconds
            timing.record(
                stage = stage,
                attempt = attempt,
                round = round,
                elapsedMs = elapsed,
                // 一个事件都没收到时，把整段时间都算作"等"——那正是这种情况的真相。
                firstEventMs = first ?: elapsed,
                tools = turn.calls.map { it.name },
                // 用量必须一路记到账本上：**思维链 token 是唯一能把那一大截"等"拆开的量**（§14.22）。
                usage = turn.usage,
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

                    JudgeTools.MODEL_SHAPE -> modelShape(c, shaped)

                    JudgeTools.DISTANCE -> measure(c, world, distances)

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
    private fun calculate(call: ToolCallRequest, variables: Map<String, Double>, log: CalcLog): String {
        val args = decode(CalcArgs.serializer(), call)
            ?: return "${JudgeTools.CALC} 的参数不是合法 JSON：${call.arguments.take(160)}"
        log.record(args.expression)
        return when (val result = Calc.evaluate(args.expression, variables)) {
            is Calc.Result.Value -> "${args.expression} = ${Calc.format(result.value)}"
            is Calc.Result.Failed -> "算不出来：${result.problem}"
        }
    }

    /**
     * 把一次 `model_shape` 整个转交给建模那一段（[ShapeModeler]），并把造出来的形状记进 [ShapeLog]。
     *
     * **造不出来不是协议内失败**，与 [calculate] 同一条理由：那是模型可以自己绕开的一步——换一句更
     * 具体的要求再试，或者干脆不用形状、接着列后果。把它当成 `Malformed` 会让整轮判定重来，而重来时
     * 它手里并没有"是那一句要求画不出来"这条信息。
     *
     * 回给它的**只有尺寸与格数，不回那张网格**：判定不需要看见格子，它需要的是"这样东西多大"；而一
     * 整张网格会吃掉这一轮里本该留给后果列表的注意力。
     */
    /**
     * 把一次 `distance` 的结果写成一句回给模型的话。
     *
     * **问不出来不是协议内失败**，与 [calculate] 同一条理由：id 抄错了是模型下一轮自己就能修的一步。
     * 把它当成 `Malformed` 会让整轮判定重来，而重来的时候它手里并没有"是哪个 id 错了"这条信息。
     *
     * 回的是**三个数**，不是一个"距离"——理由写在 [io.github.k1midi0.infinitesimal.spatial.Distance]
     * 上：规矩书说的"一格以内"指的是格距，高度差是另一回事。合成一个标量等于替规矩做了决定，而规矩
     * 是内容、会改。
     */
    private fun measure(call: ToolCallRequest, world: World, log: DistanceLog): String {
        val args = decode(DistanceArgs.serializer(), call)
            ?: return "${JudgeTools.DISTANCE} 的参数不是合法 JSON：${call.arguments.take(160)}"
        val a = world.byId(args.a) ?: return "没有 id 是「${args.a}」的东西——id 要从清单里原样抄。"
        val b = world.byId(args.b) ?: return "没有 id 是「${args.b}」的东西——id 要从清单里原样抄。"
        val d = distanceBetween(a, b)
        val answer = buildString {
            append("${a.label}（${a.id}） 到 ${b.label}（${b.id}）：")
            append("**格距 ${d.cells} 格**（地图格子，一格一米，取横向与纵向里大的那个）")
            append("；横向差 ${abs(d.dxCm)} 厘米、纵向差 ${abs(d.dyCm)} 厘米")
            append("；高度差 ${abs(d.dzCm)} 厘米（")
            append(
                when {
                    d.dzCm > 0 -> "${b.label} 比 ${a.label} 高"
                    d.dzCm < 0 -> "${b.label} 比 ${a.label} 低"
                    else -> "两者同高"
                },
            )
            append("）；直线距离 ${d.straightCm} 厘米")
        }
        // **记的是答案原文，不只是"问过"**：第二步要靠它把第一步问过的答案带上，免得再问一遍。
        log.record(a.id, b.id, answer)
        return answer
    }

    private suspend fun modelShape(call: ToolCallRequest, log: ShapeLog): String {
        val args = decode(ModelArgs.serializer(), call)
            ?: return "${JudgeTools.MODEL_SHAPE} 的参数不是合法 JSON：${call.arguments.take(160)}"
        if (args.id.isBlank()) return "${JudgeTools.MODEL_SHAPE} 的 id 不能空——形状是给哪一样东西造的。"
        if (args.brief.isBlank()) {
            return "${JudgeTools.MODEL_SHAPE} 的 brief 不能空——要给一句能独立读懂的要求。"
        }

        return when (val result = modeler.model(args.brief)) {
            is ShapeModeler.Result.Failed -> buildString {
                append("没有造出形状：")
                append(result.problems.joinToString("；").take(400))
                append("。你可以换一句更具体的要求再试一次，或者不用形状、直接接着列后果。")
            }

            is ShapeModeler.Result.Modeled -> {
                log.record(args.id, result.shape)
                val size = result.shape.extent
                "造好了：已经记在 ${args.id} 名下，长 ${size.x + VOXEL_CM} 宽 ${size.y + VOXEL_CM} " +
                    "高 ${size.z + VOXEL_CM} 厘米，占 ${result.shape.cellCount} 个格。你接着干。"
            }
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
        // **这一句同时管两件事，而它们曾经互相打架（§14.23）。**
        //
        // 一件事是「驳回理由不撒谎」：不说，模型会把「这一片里没有」说成「没有」，而它手里真的
        // 只有这一片（§14.12）。框外有什么仍然不给：那会诱它拿手边同类顶替（§14.13）。
        //
        // 另一件事是**驳回本身**：早先这里只写了「不等于世上没有」，模型于是把它读成「所以别驳回」，
        // 报一个发起者交差、把判断推给下一步——而下一步要重新论证一遍才驳得掉（实测同一件事
        // 1577 ms 与 23805 ms，§14.22）。所以这一句必须把两头都说到：**措辞限定在这一片，
        // 但该驳就驳、不要往后推。**
        appendLine(
            "下面这一段**只是发起者身边的一小片，不是整个世界**。理由里只能说「这一片里没有」，" +
                "不能说「世上没有」；但**动作点的东西不在这片里，就该在这一步驳回，不要把它推给下一步**。",
        )
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
        // 材质表（A.15）。放在这里是因为它与规则书同级：**世界级的事实、一局里不变**，所以它进
        // 共用前缀是安全的（会变的东西一律不许进来，§7.5）。三只改地形的手要从这里取材质 id。
        MaterialView.forPrefix(world).takeIf { it.isNotEmpty() }?.let {
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
            // 与第二步同一条：**距离代码算好写在这一行里**，不让模型拿坐标自己推。
            request.selected.forEach { appendLine(thingLine(world, request.actorId, it)) }
            appendLine()
            appendLine(DISTANCE_IS_GIVEN)
        }
        appendLine()
        appendLine("判断这次动作**实际作用于哪些东西**，然后用 ${JudgeTools.TARGET} 逐个报出来：")
        appendLine("- **选少了就补**：动作说的是「桌上的东西」，桌上每一件都得报。")
        appendLine("- **选多了就剔除**：跟这次动作无关的不要报。")
        appendLine("- **选错了就换成对的**。")
        appendLine("- **发起者本人也是对象。**「我理了理衣襟」「我环顾四周」「我打了个哈欠」——这类动作只有它一个人，那就报它自己，照常走完两步。")
        appendLine("- 报的东西**也包括会被这次动作牵涉到的角色**（「我把酒泼在他身上」——那个人也算）。")
        appendLine()
        // **说话单独一栏，而且只有它一栏**（§14.37）。早先这里写的是"说话也是一次动作，要报出这一片
        // 里听得见这句话的角色"——于是"指向"与"听见"混进了同一栏，而那是两件完全不同的事：
        //
        //   「大吼一嗓子」 —— 是对周围人的**指向**，那些人该被唤醒
        //   「跟同伴闲聊」 —— 指向的只有同伴，隔壁桌**听得见，但没被指向**
        //
        // 混着写的直接代价是每说一句话要**单独再跑一次第一步**去找听者（一次真金白银的调用）。分开
        // 之后一次第一步同时给出两栏，而"谁收到了这件事"由代码按空间框投递，不占模型。
        if (request.said.isNotEmpty()) {
            appendLine("这一轮发起者还开口说了话，原话是：")
            appendLine("「${request.said}」")
            appendLine("用 ${JudgeTools.ADDRESS} 报出**这句话是说给谁听的**：")
            appendLine("- 喊了一嗓子、当众宣布、话里点了名的——**在场听得见的角色都要报**。")
            appendLine("- 只跟某一个人说话——**只报那一个人**。")
            appendLine(
                "- 旁边还有人听得到，但那不是对他们说的——**不要报他们**。" +
                    "这一片里的人都收得到这一轮发生了什么，用不着你替他们记。",
            )
        } else {
            appendLine("这一轮发起者没有说话，${JudgeTools.ADDRESS} 一次都不要调。")
        }
        appendLine()
        // **优先级必须写出来，不能靠两句话的先后。** 早先这里只有一句"永远不要一个工具都不调"，
        // 外加上面那条"发起者本人也是对象"——两句都指向"报个什么"，于是模型遇到"三条街外那家店
        // 里的杯子"就报发起者，把驳回推给下一步。而下一步手里有 `calc`，会认真地算一遍距离
        // （实测 4309 个思维 token、22 秒）才驳回（§14.22）。同一次动作，第一步驳掉是 1577 ms。
        appendLine("**先看动作的名字有没有点到某样东西，这件事优先：**")
        appendLine(
            "- 点了，而这一片里没有它——调 ${JudgeTools.REJECT_TARGETS}，理由说清「这一片里没有……」。" +
                "**这一条优先于下面那条**：不要用「报发起者本人」来代替它，那只是把这个判断推给下一步，" +
                "而下一步要重新论证一遍才驳得掉。",
        )
        appendLine(
            "- 没点到任何东西（「我理了理衣襟」），或者点的东西都在这片里——按上面的规则报出来。" +
                "**任何动作至少落在发起者身上**，永远不要一个工具都不调。",
        )
        appendLine()
        appendLine("**够不够得着、说不说得出口，同样看规则书**：里面有冲突时采信数字大的那条，并在理由里点名是哪一条。")
        appendLine()
        appendLine("**不要在这一步推演后果**，那是下一步的事。")
        appendVariables(this, variables)
        appendRetryNotes(retryNotes)
    }

    /**
     * 清单里的那一行：**发起者到它的距离直接算好写上去**。
     *
     * 用户定的：*测量工具保留，但先自动算好动作角色和被指向物体/角色的距离*。理由是实测出来的
     * （§14.26）——把这件事留给模型，它会为此烧掉几百到四千多个思维 token；而代码算这个数是一瞬间
     * 的事，**而且答案不会有错**。清单里本来就有厘米坐标，让模型自己去减、去开方，是白给的出错机会。
     *
     * **为什么仍然留着 [JudgeTools.DISTANCE]。** 代码只算得出"发起者到被点名的东西"这一种；两样
     * **都不是发起者**的东西之间有多远、或者发起者到一样**没被写进清单**的东西有多远，只有模型问了
     * 才知道它想问哪一对。
     *
     * 三个数都给，理由与 [io.github.k1midi0.infinitesimal.spatial.Distance] 上那段相同：规矩书说的
     * "一格以内"指的是格距，高度差是另一回事，合成一个标量等于替规矩做了决定。
     */
    private fun thingLine(world: World, actorId: String, id: String): String {
        val thing = world.byId(id) ?: return "- $id"
        val head = "- ${thing.id}（${thing.label}）"
        if (thing.id == actorId) return "$head　就是发起者本人"
        val actor = world.byId(actorId) ?: return head
        val d = distanceBetween(actor, thing)
        return buildString {
            append(head)
            append("　格距 ${d.cells} 格")
            append("；高度差 ${abs(d.dzCm)} 厘米")
            append(
                when {
                    d.dzCm > 0 -> "（它比发起者高）"
                    d.dzCm < 0 -> "（它比发起者低）"
                    else -> "（与发起者同高）"
                },
            )
            append("；直线 ${d.straightCm} 厘米")
        }
    }

    /**
     * 那几行清单**每次都要带的一句话**。不写它，模型会照着坐标自己再算一遍——而实测里它正是这么干的。
     */
    private val DISTANCE_IS_GIVEN =
        "**每一样后面都写好了它与发起者的距离，要判够不够得着就看那里**——不要自己拿坐标算，那是白给的出错机会。"

    private fun effectPrompt(
        world: World,
        request: JudgeRequest,
        targets: List<String>,
        retryNotes: List<String>,
        view: ViewBudget,
        variables: Map<String, Double>,
        distances: DistanceLog,
    ): String =
        buildString {
            append(sharedPrefix(world, request, view))
            appendLine()
            appendLine("【这次动作作用在这些东西上】")
            if (targets.isEmpty()) {
                appendLine("（不作用于任何具体的东西）")
            } else {
                // 距离**代码算好写在这一行里**（用户定的：测量工具保留，但先自动算好发起者与被指向
                // 东西的距离）。清单里本来就印着厘米坐标，让模型自己减、自己开方，是白给的出错机会。
                targets.forEach { appendLine(thingLine(world, request.actorId, it)) }
                appendLine()
                appendLine(DISTANCE_IS_GIVEN)
            }
            appendTargetCards(world, request.actorId, targets)
            // **第一步问过的距离带过来。** 同一次动作的两步看的是同一个世界，答案不会变；不带上，
            // 模型就会把同一个问题再问一遍——实测里那多烧一整次往返（§14.25）。
            if (distances.answers.isNotEmpty()) {
                appendLine()
                appendLine("【这一步之前已经问过的距离】（不必再问一遍，同一次动作里答案不会变）")
                distances.answers.forEach { appendLine("· $it") }
            }
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

    /**
     * 一次"问到结束"的结果。
     *
     * 除了攒下的提议，它还留着模型**最后那一轮说的话**和端点的 `finish_reason`。两样都是诊断用的，
     * 而它们是这一层唯一能拿到的信号：第二步一个工具都没调时，光有"没调"这三个字，日志里那条失败
     * **与被截断、被拒绝、模型跑偏三种情况长得一模一样**（§14.19 就是这么撞上的）。
     */
    private class Answered(
        val proposals: List<ToolCallRequest>,
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

    /** `widen_view` 的参数。**`direction` 是可选的**——这是这个项目里第一只真正带可选参数的工具。 */
    @Serializable
    private data class WidenArgs(val direction: String? = null)

/** `model_shape` 的两个参数。 */
@Serializable private data class ModelArgs(val id: String = "", val brief: String = "")

    private fun <T> decode(serializer: KSerializer<T>, call: ToolCallRequest): T? =
        decodeCall(serializer, call)

    private sealed interface Parsed {
        data class Effect(val consequence: Consequence) : Parsed
        data class Affects(val actor: String) : Parsed
        data class Rejects(val reason: String) : Parsed
        data class Unchanged(val note: String) : Parsed
        data class Bad(val problem: String) : Parsed
    }

    private fun parseEffect(call: ToolCallRequest): Parsed {
        fun <T> decodeInto(serializer: KSerializer<T>, build: (T) -> Parsed): Parsed {
            val value = decode(serializer, call)
                ?: return Parsed.Bad("${call.name} 的参数读不出来（缺字段，或者类型不对）：${call.arguments.take(160)}")
            return build(value)
        }

        return when (call.name) {
            JudgeTools.MOVE -> decodeInto(MoveArgs.serializer()) {
                Parsed.Effect(Consequence.Move(it.id, it.x, it.y, it.z))
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

            JudgeTools.FILL_TERRAIN -> decodeInto(BoxArgs.serializer()) {
                Parsed.Effect(Consequence.FillTerrain(it.toBox(), it.material))
            }

            JudgeTools.DIG_TERRAIN -> decodeInto(BoxArgs.serializer()) {
                Parsed.Effect(Consequence.DigTerrain(it.toBox()))
            }

            JudgeTools.REPLACE_TERRAIN -> decodeInto(BoxArgs.serializer()) {
                Parsed.Effect(Consequence.ReplaceTerrain(it.toBox(), it.from, it.to))
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

@Serializable private data class SetAttributeArgs(val id: String = "", val key: String = "", val value: String = "")

@Serializable private data class IdArgs(val id: String = "")

@Serializable private data class SpawnArgs(
    val id: String = "",
    val kind: String = "",
    val label: String = "",
    val x: Int = 0, val y: Int = 0, val z: Int = 0,
) {
    fun toEntity(): Entity = Entity(
        id = id, kind = kind, label = label, x = x, y = y, z = z,
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
) {
    fun toEntity(): Entity = Entity(
        id = newId, kind = kind, label = label, x = x, y = y, z = z,
        sentences = emptyList(),
    )
}

@Serializable private data class AffectArgs(val actor: String = "")

/** `no_change` 的参数。 */
@Serializable private data class NoteArgs(val note: String = "")

/** `calc` 的参数。**只有一个字符串**——schema 只支持扁平类型，而表达式本来就是一个字符串。 */
@Serializable private data class CalcArgs(val expression: String = "")

/** `distance` 的两个 id。**两个都必填**——"离某样东西多远"少一半是问不出来的。 */
@Serializable private data class DistanceArgs(val a: String = "", val b: String = "")

/**
 * 三只框选地形的手收的同一个长方体（附录 A.15）。
 *
 * **六个坐标刻意不给默认值。** 其余每只手的参数都有默认值，漏填的落点是"id 是空的"→ `Committer`
 * 驳回并说清哪一条坏了。这里不行：六个坐标全部默认成 0 的话，一个漏填 `z2` 的框会**静默地**变成
 * "从 z1 到 0"的一根竖井，那是个合法的框，于是它照样被提交，而模型从来不知道自己的参数掉了一半。
 *
 * 不给默认值，缺字段就在**解码**这一步失败：`Parsed.Bad` → 退回重试。这是唯一一处"宁可读不出来，
 * 也不要一个看起来合理的默认值"。
 */
@Serializable private data class BoxArgs(
    val x1: Int, val y1: Int, val z1: Int,
    val x2: Int, val y2: Int, val z2: Int,
    val material: String = "",
    val from: String = "",
    val to: String = "",
) {
    fun toBox(): TerrainBox = TerrainBox(x1, y1, z1, x2, y2, z2)
}

@Serializable private data class RejectArgs(val reason: String = "")
