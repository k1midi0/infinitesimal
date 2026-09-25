package io.github.k1midi0.infinitesimal.npc

import io.github.k1midi0.infinitesimal.config.ContextSettings
import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.judge.RulebookView
import io.github.k1midi0.infinitesimal.judge.describeConsequence
import io.github.k1midi0.infinitesimal.llm.AssistantTurn
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.ToolCallRequest
import io.github.k1midi0.infinitesimal.llm.Usage
import io.github.k1midi0.infinitesimal.llm.decodeCall
import io.github.k1midi0.infinitesimal.spatial.ChunkDetail
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.ChunkView
import io.github.k1midi0.infinitesimal.spatial.bus
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.spatial.audience
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.EventKind
import io.github.k1midi0.infinitesimal.world.HARD_RADIUS_CHUNKS
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.Spot
import io.github.k1midi0.infinitesimal.world.Todo
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import io.github.k1midi0.infinitesimal.world.due
import io.github.k1midi0.infinitesimal.world.rendered
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable

/**
 * **一个 AI 角色为什么醒过来。**
 *
 * 与导演的 `Wake` 是同一个形状、同一个理由：它决定"什么都不做"合不合法（§14.34）。现在只有一种，
 * 但**形状先立住**——将来的「被一个动作波及到了」「日程到点了」各是一种，而它们在"醒过来之后该
 * 干什么"上是不同的。
 *
 * ## 现在这一种的特殊之处
 *
 * 被叫到名字（或者被搭话）时**什么都不做是合法的**：一个不想理你的矮人是一个真实的反应，逼它必须
 * 答话，会让每个 NPC 都变成同一个话多的家伙。所以这里没有"必须说点什么"的契约——**这一条与导演的
 * `Arrange`（至少排一件）正好相反，而它们都是对的，因为要的东西不一样**（§14.34 踩过同一个坑）。
 */
sealed interface Heard {

    /**
     * 听见某个人说了一句话。
     *
     * §5.1：**话不做任何处理——就像现实一样。** 这里也不处理：说的是什么就原样交给它，不翻译、
     * 不摘要、不判断"它该不该听懂"。
     */
    data class Spoken(
        val fromId: String,
        val fromLabel: String,
        val text: String,
    ) : Heard
}

/**
 * 一次唤醒：**谁醒了、为什么**。
 *
 * [origin] 与导演那份是同一个坐标原点（以玩家开局那一块为原点，§12 第 39 项）——视野按区块坐标算，
 * 换个原点就会画出一张对不上的图。
 */
data class Waking(
    val listenerId: String,
    val heard: Heard,
    val origin: ChunkOrigin,
    /**
     * **事件总线的起点**：这一回合的第一条事件的编号（§6.1、§14.39）。
     *
     * 会话在玩家一次提交之前读一下 `EventLog.latestSeq` 拿到它，然后一路带到这里。总线**只装上回合**，
     * 而"上回合从哪儿开始"是一个不该由角色自己猜的刻度。
     *
     * 默认 `0` = 从最开始——只该用在测试与探针里（那会给出一个"什么都算在附近"的总线）。
     */
    val sinceSeq: Long = 0,
)

/**
 * 一个角色说出口的一句话。
 *
 * §5.3：**气泡只存在当前回合**——所以它不是世界状态，不进 `World`，由会话拿着，每一轮开头清掉。
 * 它也不是"记录"：§1.5 的记录永不改写，而这一条按定义就是要消失的。
 */
data class Spoken(val whoId: String, val text: String)

/**
 * 一个角色醒过来之后干了什么。
 *
 * **它不是一个 `sealed interface`。** 与导演的 `Awakening` 不同，这里没有"失败"这个结局：「一句话
 * 没说、一个动作没做」是正常的（见 [silent]），而协议内失败已经在回合循环里被退回去重试过了
 * ——走出这个函数的，必然是一次跑完的唤醒。
 */
data class Reaction(
    /** 它说了哪几句，按说出口的顺序。 */
    val said: List<Spoken>,
    /** 它做成了哪几个动作。**每一个都已经落库**（判定那边提交的）。 */
    val acted: List<Judgment>,
    /** 这一次唤醒花了几个来回。诊断用。 */
    val rounds: Int,
    /** 工具参数解不开、判定的契约被违反之类。**不抛异常，收集起来给日志**（§1.3）。 */
    val problems: List<String>,
) {
    /**
     * 它什么也没做。
     *
     * **这是合法的**——见 [Heard] 上那段。会话拿它来决定"要不要说一句'它没什么反应'"，不是拿它
     * 来判断成败。
     */
    val silent: Boolean get() = said.isEmpty() && acted.isEmpty()
}

/**
 * 扮演一个 AI 角色（§3.2、§3.4、§5）。
 *
 * ## 它手里有什么
 *
 * 一份**格式化的地图视图**，加上两只手（[NpcTools.SAY] 与 [NpcTools.ACT]）——§3.4 的原话是
 * "NPC 拿到的是同一套东西的 AI 版本"。地图的**半径与玩家硬圈一样大**（用户裁定，§14.36）：视野
 * 是 32 米，粗到能看出"这一带是什么地方"，而**细到能看清桌上那只杯子要靠判定**——它真动手时，
 * 判定会把 [io.github.k1midi0.infinitesimal.spatial.RegionView] 那份 1 米格的空间框给它。
 *
 * ## 「做」为什么走判定
 *
 * 用户裁定（§14.36）：**NPC 的「做」走同一条判定链路**，只是发起者换了个人。理由是硬的——判定是
 * 唯一能改世界的地方（§1），给它另开一条通路就等于让世界有了第二个规则来源。
 *
 * 代价是它一次「做」要花两到三次调用（对齐一步、后果一步、可能还有查询手）。那是这一层的真实
 * 账单，不是可以省掉的：**角色的意愿与世界的裁决必须分开**，合起来就等于模型自己给自己发许可。
 *
 * ## 它记得住
 *
 * **它有一场真正的对话。** 每次唤醒产生的消息（它收到的 prompt、它说的、它做的、工具回的）都接进一份
 * **按角色累积的历史**（[Conversations]），下一次唤醒从那里往下接（§6.1、§14.39）。这一条是 2026-09-26
 * 改的——在那之前每次唤醒都新建一段，于是它每一句都从零开始，而"记得住"只是有人把记录铺成了提示词。
 *
 * **累积不等于什么都记得。** 事件总线只装上回合（`Waking.sinceSeq` 之后），更早的那些只在记录层里、
 * **不进提示词**——「自然遗忘」是设计要的（§1.5 那条根决策由底下的记录层保住，模型这一侧看不见）。
 * 重要的东西由待办（§3.2.5）与笔记（§6.3）兜住。
 *
 * **没有笔记、没有状态循环的那一半。** 笔记是 §6.3（还没做），循环已经在 `Entity.cycle` 上（§14.37）。
 *
 * 压缩——超阈之后把一段换成一条摘要、摘要再超阈就再压——是同一条路的下半段，还没做（§6.1）。
 */
class Npc(
    private val client: LlmClient,
    private val judge: Judge,
    val effort: ReasoningEffort = ReasoningEffort.HIGH,
    /**
     * 每个角色那一份**累积的对话历史**（§6.1、§14.39）。
     *
     * 默认现开一份——于是只有一个 `Npc` 的进程里，`Conversations` 的生命周期与它相同。**它现在不落盘**
     * （存档还没做，§12 第 33 项），所以重启就没了；这一点写在 [Conversation] 的 KDoc 上。
     */
    val conversations: Conversations = Conversations(),
    /** 上下文预算（§6.1）。阈值离现实很远，探针里要调小才验得到。 */
    val settings: ContextSettings = ContextSettings(),
) {

    /**
     * 从配置造一个。
     *
     * 它给自己开一份 [Judge]，而那份判定**用的是 [SystemId.JUDGE] 那个池**——这是对的：判定是同一个
     * 系统，不管它是为玩家跑的还是为 NPC 跑的，它的 system 前缀逐字相同，合在一池才命中缓存。
     * 角色自己那份提示词才进 [SystemId.NPC]。
     */
    constructor(
        config: EndpointConfig,
        presets: PresetLibrary = PresetLibrary(),
        effort: ReasoningEffort = ReasoningEffort.HIGH,
        settings: ContextSettings = ContextSettings(),
    ) : this(
        LlmClient(config.copy(userId = SystemId.NPC)),
        Judge(config, effort = effort, presets = presets),
        effort,
        Conversations(),
        settings,
    )

    /** 这个角色**自己**发出去的请求数。判定那边的另算（见 [judgeRequestCount]）。 */
    var requestCount = 0
        private set

    /**
     * 它调判定时，判定发出去的请求数。
     *
     * **分开记，不合并。**两者是不同的池子（[SystemId.NPC] 与 [SystemId.JUDGE]），混在一起之后
     * "NPC 说话贵不贵"和"NPC 动手贵不贵"就永远算不开了——而这两件事的差额正是这一层最该量的数。
     */
    val judgeRequestCount: Int get() = judge.requestCount

    var lastUsage: Usage? = null
        private set

    var lastFinishReason: String? = null
        private set

    /**
     * 叫醒一个角色，让它对听见的东西做出反应。
     *
     * **它不抛异常**：世界里没有那个人、参数解不开、判定驳回，都是这个世界的正常结果，各自变成一句
     * 退回去的话（§1.3）。
     */
    suspend fun wake(world: World, waking: Waking): Reaction {
        val listener = world.byId(waking.listenerId)
            ?: return Reaction(
                emptyList(),
                emptyList(),
                0,
                listOf("世界里没有 id 是「${waking.listenerId}」的东西，没人可唤醒。"),
            )

        return once(world, waking, listener)
    }

    // ── 一次唤醒 ───────────────────────────────────────────────────────────────────

    private suspend fun once(world: World, waking: Waking, listener: Entity): Reaction {
        val said = mutableListOf<Spoken>()
        val acted = mutableListOf<Judgment>()
        val problems = mutableListOf<String>()

        val system = ChatMessage.system(SYSTEM)
        val fresh = listOf(ChatMessage.user(userPrompt(world, waking, listener)))
        val conversation = conversations.of(listener.id)

        // **这一次唤醒产生的全部消息**：请求过的、模型回的、工具答的。它们要一起接进历史，而且
        // **工具调用与结果必须成对**——只留台词会让消息数组悬空，端点直接 400（§14.31 踩过一次空
        // `tool_calls`）。
        val produced = mutableListOf<ChatMessage>()
        produced += fresh

        var round = 0
        while (round < MAX_ROUNDS) {
            round++
            requestCount++

            // 这一次请求里"历史之外"发了多少条——锚要覆盖它们，见下面的 `anchor`。
            val sent = produced.size

            val turn = AssistantTurn.of(
                client.streamChat(
                    // **每轮现拼**：历史 + 这一轮为止产生的。历史那一段永不改变，所以前缀缓存能一路
                    // 命中到这一次请求新添的那几条（§6.1）。
                    messages = conversation.request(system, produced),
                    tools = NpcTools.ALL,
                    thinking = ThinkingRequest.of(ThinkingMode.Always, effort),
                    // **不设 `max_tokens`。** 与判定、导演同一条：思维链也算在它里面，限了就会让第二步
                    // 空转（§14.19 第四节实测过）。
                    maxTokens = null,
                ).toList(),
            )

            lastUsage = turn.usage
            if (round == 1) lastFinishReason = turn.finishReason

            // **锚钉在这一次请求上。** 提供方说这个 prompt 多大，那就是真数；`covered = sent` 防的是把
            // 已经算进这个数的内容再算一遍——算重了会让阈值提前触发，而症状是"它怎么这么早就开始压了"。
            turn.usage?.let { conversation.anchor(it.promptTokens, sent) }

            val assistant = turn.asAssistantMessage()
            // **没有工具调用也要留下这一条。** 模型自己说的那段（`content`）就在里面，而 §6.1 的清单里
            // 有它（「模型自己说的，不是调用工具」）。只有真正空的那条才丢。
            if (!assistant.content.isNullOrBlank() || turn.calls.isNotEmpty()) {
                produced += assistant
            }

            // **一个工具都不调 = 它说完了。** 与导演同一条循环规则（§14.33）：它要说几句、做几件事本来
            // 就是多行的，所以判据是"这一轮没有手"，不是"这一轮没有查询手"（判定那边才是后者）。
            if (turn.calls.isEmpty()) break

            for (call in turn.calls) {
                val answer = answerFor(call, world, waking, listener, said, acted)
                if (answer.problem != null) problems += answer.problem
                produced += ChatMessage.toolResult(call.id, answer.text)
            }
        }

        // **接进历史。** 这一句就是「正常拼接的多轮上下文」的全部：下一次唤醒时，它从这里往下接
        // （§6.1、§14.39）。压到阈值之后该发生的事（压成一条摘要、摘要再超阈就再压）是下一刀。
        conversation.commit(produced)

        return Reaction(said, acted, round, problems)
    }

    /** 一只手的答复。解开的东西与解不开的东西走同一条路回去。 */
    private class Answered(val text: String, val problem: String? = null)

    /**
     * 一只手的答复。**解不开的参数与不合法的值都变成文字退回去**，不抛异常（§1.3）。
     *
     * 与判定、导演不同的是：这里的两只手**都是"提议"**——`say` 提议说一句话，`act` 提议做一个动作，
     * 而动作那条要过判定才知道成不成立。所以每次调用都可能返回一段"不成，因为……"，而那段话会原样
     * 回到模型的上下文里，它据此换个做法再试。
     */
    private suspend fun answerFor(
        call: ToolCallRequest,
        world: World,
        waking: Waking,
        listener: Entity,
        said: MutableList<Spoken>,
        acted: MutableList<Judgment>,
    ): Answered = when (call.name) {
        NpcTools.SAY -> say(call, world, listener, said)
        NpcTools.ACT -> act(call, world, listener, acted)
        NpcTools.PLAN -> plan(call, world, listener)
        NpcTools.LOOK -> look(call, world, waking, listener)
        else -> Answered(
            "没有叫「${call.name}」的工具。你现在能用的只有：" +
                NpcTools.ALL.joinToString("、") { it.function.name } + "。",
            // **记成一条协议问题，不静默咽下去**（§1.3）。它是"模型调了一只不存在的工具"，而不是
            // "这个动作不成立"——两者要分开，后者是世界的裁定，前者是它自己出了问题。
            "模型调了一只不存在的工具：${call.name}",
        )
    }

    /**
     * **查看总线上某一条的细节**（§6.1、§14.39）。
     *
     * 它**不是**记录层的入口，而是**总线的入口**——所以判据一并落在"在不在这一回合的总线上"，而那
     * 同时管住了两件事：**不带历史**（更早的那些查不到，那正是"自然遗忘"）与**在不在附近**（远处的
     * 事它本来就不该看得见）。
     *
     * 查不到不是失败，是这个世界的一个正常结果——那句话得说得像世界在说话（§4.3）。
     */
    private fun look(call: ToolCallRequest, world: World, waking: Waking, listener: Entity): Answered {
        val ref = decodeCall(LookArgs.serializer(), call)?.id?.trim().orEmpty()
        if (ref.isEmpty()) {
            return Answered("「id」是空的——把总线上那个编号写进去，形如 e12。", "look 的 id 是空的")
        }

        val wanted = world.log.byRef(ref)?.seq
        val line = wanted?.let { seq ->
            world.bus(listener.id, waking.sinceSeq).firstOrNull { it.event.seq == seq }
        }

        return if (line == null) {
            Answered(
                "这一回合的总线上没有「$ref」这件事——要么它已经过去了，要么它没发生在你附近。",
            )
        } else {
            Answered(line.event.line())
        }
    }

    private fun say(
        call: ToolCallRequest,
        world: World,
        listener: Entity,
        into: MutableList<Spoken>,
    ): Answered {
        val text = decodeCall(SayArgs.serializer(), call)?.text?.trim()
        if (text.isNullOrEmpty()) {
            return Answered("「text」是空的——你要说的那句话得写进去。", "say 的 text 是空的")
        }
        into += Spoken(listener.id, text)

        // **进记录层**（§6.1）。角色说的话与玩家说的话是同一类事实，所以走同一个形状：原话原样、
        // 投递给这一片里的人。
        //
        // **它不知道自己在"对谁说"**——`say` 没有指向那一栏，因为一只不点名的手说得越多，它就越
        // 容易与判定打架。所以这里的投递就是"这一片里的人都收到了"，而唤醒别人这件事由**玩家的**
        // 那一轮负责（§14.37）——NPC 的话不再唤醒别人，深度钉在 1。
        world.log.append(
            at = world.clock,
            kind = EventKind.SAY,
            actorId = listener.id,
            actorLabel = listener.label,
            text = text,
            // **它不知道自己"对谁说"**——`say` 没有指向那一栏，因为一只不点名的手说得越多，它就越容易与
            // 判定打架。所以这一栏留空：对旁人来说，它说的那句是**标题**，想看细节要自己 `look`（§6.1）。
            spot = Spot(listener.x, listener.y, listener.z),
            to = world.audience(listener.id),
        )
        return Answered("说出去了。")
    }

    /**
     * **给自己安排一件将来做的事**（§3.2.5）。它**不产生任何后果**——世界一个字都不动，只是那个角色
     * 从此多了一条待办。
     *
     * 到点之后它出现在提示词里，然后照常要动手、照常要过判定。这条线不能松：如果到点就自动发生，
     * 世界上会出现一批没人做过、也没有痕迹的改变。
     */
    private fun plan(call: ToolCallRequest, world: World, listener: Entity): Answered {
        val args = decodeCall(PlanArgs.serializer(), call)
        val what = args?.what?.trim()
        if (what.isNullOrEmpty()) {
            return Answered("「what」是空的——那件事用一句话写清楚。", "plan 的 what 是空的")
        }
        if (args.hour !in 0..23) return Answered("「hour」得是 0 到 23。", "plan 的 hour 越界：${args.hour}")
        if (args.minute !in 0..59) return Answered("「minute」得是 0 到 59。", "plan 的 minute 越界：${args.minute}")

        val at = WorldTime.of(day = args.day, hour = args.hour, minute = args.minute)
        // **从世界里重新拿一份，不用传进来的那份。** 一次唤醒里连调两次 `plan` 时，第二份要是拿的还是
        // 进函数时那一份，第一条待办就被覆盖掉了——而"写了一条、另一条没了"没有任何东西会报。
        val now = world.byId(listener.id) ?: listener
        world.put(now.copy(todos = now.todos + Todo(at, what)))
        return Answered("记下了：${at}，$what。到点它会提醒你，**但它不会自己发生**。")
    }

    /**
     * 做一个动作：**交给判定**（§14.36）。
     *
     * `selected` 是它自己点名的目标。**点名是可选的**——不点名时判定按空间框自己对齐，那条路与玩家
     * 什么都不勾时走的是同一条（§4.6 第一步）。点名点错了也由判定纠正，理由会一起退回来。
     */
    private suspend fun act(
        call: ToolCallRequest,
        world: World,
        listener: Entity,
        into: MutableList<Judgment>,
    ): Answered {
        val args = decodeCall(ActArgs.serializer(), call)
        val intention = args?.intention?.trim()
        if (intention.isNullOrEmpty()) {
            return Answered("「intention」是空的——你想做什么，用一句话写清楚。", "act 的 intention 是空的")
        }

        // 点名里认不出来的 id **不当作失败**：判定那边本来就会拿到一份清单，它自己会对齐。这里是
        // 提醒一句，让模型下一轮别再把错的 id 抄一遍。
        val named = args.targets.split(' ', ',', '，', '、')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val known = named.filter { world.byId(it) != null }
        val unknown = named - known.toSet()

        // **标签要先抓下来。** 判定落库之后被 Destroy 的东西就不在世界里了，那时候再查名字只能拿到
        // id。与玩家那一轮同一条理由。
        val labels = world.all().associate { it.id to it.label }

        val judgment = judge.judgeAndCommit(
            world,
            JudgeRequest(
                actorId = listener.id,
                actorLabel = listener.label,
                intention = intention,
                selected = known,
                centers = known,
            ),
        )
        into += judgment

        // **落地了才写记录**——与玩家那一轮同一条判据：驳回的、协议失败的不是世界里发生过的事。
        fun name(id: String) = labels[id] ?: world.byId(id)?.label ?: id
        when (judgment) {
            is Judgment.Applied -> world.log.append(
                at = world.clock,
                kind = EventKind.ACT,
                actorId = listener.id,
                actorLabel = listener.label,
                text = intention,
                effects = judgment.consequences.map { describeConsequence(it, ::name, world.materials) },
                addressedTo = judgment.affected.toList(),
                spot = Spot(listener.x, listener.y, listener.z),
                to = world.audience(listener.id, affected = judgment.affected),
            )

            is Judgment.Unchanged -> world.log.append(
                at = world.clock,
                kind = EventKind.ACT,
                actorId = listener.id,
                actorLabel = listener.label,
                text = intention,
                spot = Spot(listener.x, listener.y, listener.z),
                to = world.audience(listener.id),
            )

            else -> Unit
        }

        val told = describe(judgment)
        return if (unknown.isEmpty()) {
            Answered(told)
        } else {
            Answered("$told\n（另外：${unknown.joinToString("、")} 这几个 id 在这一片里找不到，没算进去。）")
        }
    }

    /** 把判定结果说成一句模型读得懂的话。**驳回的理由原样带着**——它就是模型下一轮的依据。 */
    private fun describe(judgment: Judgment): String = when (judgment) {
        is Judgment.Applied ->
            "做成了。世界变了 ${judgment.consequences.size} 处；受影响的东西：${
                judgment.affected.joinToString("、").ifEmpty { "没有别的" }
            }。"

        is Judgment.RejectedWorld -> "没做成。「${judgment.reason}」"
        is Judgment.Unchanged -> "做成了，但世界一点都没变：${judgment.note}"
        is Judgment.Nothing -> "这个动作没有落在任何东西上——它既没指向谁，也没牵涉到谁。换个具体点的说法。"
        is Judgment.FailedProtocol -> "判定没能读懂这个动作（这是我这边的毛病，不是你的）：${
            judgment.problems.joinToString("；")
        }"
    }

    // ── 提示词 ─────────────────────────────────────────────────────────────────────

    /**
     * 角色那一段。**它里面有地图，这是与导演快速档最大的不同**——导演快速档那一回合大多数时候什么
     * 都不做，所以地图是"真要看时才补给它"；而一个被叫醒的角色**每一次都要决定"我看见的是什么"**，
     * 所以它一上来就带着。
     */
    private fun userPrompt(world: World, waking: Waking, listener: Entity): String = buildString {
        appendLine("【你】")
        appendLine("- id：${listener.id}")
        appendLine("- ${listener.label}")
        appendLine("- 你站在 (${listener.x}, ${listener.y}, ${listener.z}) 厘米")
        appendLine()

        appendLine("【现在】")
        appendLine(world.clock.toString())
        appendLine()

        val persona = listener.cards.rendered()
        if (persona.isNotEmpty()) {
            appendLine("【你的人设】")
            appendLine(persona)
            appendLine()
        }

        if (listener.attributes.isNotEmpty()) {
            appendLine("【你的状态】")
            listener.attributes.forEach { (k, v) -> appendLine("- $k：$v") }
            appendLine()
        }

        // **世界规则也给它。** 它活在这个世界里，火会烧起来、水往低处流，这些它本来就知道；而它
        // 自己那份规则书（角色优先级 20）排在后面、压过世界那份——排序在 `RulebookView` 里。
        val rules = RulebookView.forPrefix(world, listener.id)
        if (rules.isNotEmpty()) {
            appendLine("【规矩】")
            appendLine(rules)
            appendLine()
        }

        appendLine("【你这双眼睛看得见的一片】")
        // **两个尺度，各答一个问题**（§14.36），理由写在 `spatial/ChunkDetail.kt` 上：
        //
        //   粗的（八米一格）：这一带是什么地方   —— 城/镇/路/林/野
        //   细的（一米一格）：我手边有什么       —— 具体哪样东西、脚下什么材质
        //
        // 早先这里只有粗的那一份，而它画的是"有没有地形"——实测出来是一张**全是同一个字符**的图
        // （矮人那 9×9 里 81 格全是 `#`）。那不是错，那是空的：图上没有一个字是它还不知道的。
        val here = world.chunkOfEntity(listener.id)
        if (here == null) {
            appendLine("（你不在任何一块地上）")
        } else {
            appendLine("**远的那一张（八米一格）：这一带是什么地方。**")
            appendLine(ChunkView.renderLandform(world, waking.origin, here, radius = HARD_RADIUS_CHUNKS))
            appendLine()
            appendLine("**近的那一张（一米一格）：你手边有什么。**")
            appendLine(ChunkDetail.render(world, here, listener.id))
            appendLine()
            appendLine("**两张图之外有什么，你不知道**——也不要猜。")
        }
        appendLine()

        appendLine("【你为什么现在醒着】")
        when (val heard = waking.heard) {
            is Heard.Spoken -> {
                // **id 与名字都给。** 玩家那个实体的 label 是「你」（那是从玩家自己的视角起的名字），
                // 而在这段提示词里「你」指的是这个角色自己——只印 label 会让模型把说话的人当成自己。
                //
                // **它和下面那段记忆里最后一条是同一句话，这份重复是刻意的。** 两段回答的是两个问题：
                // 这一段是"**现在**为什么醒了"（一个关于此刻的事实），下面那段是"**我记得**什么"（一段
                // 历史）。让这一段去指下面那一条，就等于把"叫醒它的那句话到没到"绑在"记录先写了没有"
                // 上——一个纯粹的调用顺序依赖，而这类错这个项目已经踩过两次（规则书、地形）。
                appendLine("${heard.fromLabel}（${heard.fromId}）刚刚对你开口说：「${heard.text}」")
            }
        }
        appendLine()

        // **状态循环**（§3.2.5）：基线。它由代码查表得出，**不花任何模型调用**——氛围 NPC 能有成百个
        // 而成本零增量，全部来源就是这一行是纯代码。而模型接管之后必须知道"它本来在干什么"，否则
        // 一个正在打烊的酒保会表现得像一个一直在等你来的人。
        listener.cycle.stateAt(world.clock)?.let {
            appendLine("【没人打扰的时候，你此刻本来在做什么】")
            appendLine(it)
            appendLine()
        }

        // **待办**（§3.2.5）：到点了**不是自动发生，而是提醒它**。它照常要动手、照常要过判定。
        val due = listener.todos.due(world.clock)
        if (due.isNotEmpty()) {
            appendLine("【你打算做的事（已经到点了）】")
            due.forEach { appendLine("- ${it.at}：${it.what}") }
            appendLine("（它们不会自己发生。要做就得动手，做不做由你。）")
            appendLine()
        }

        appendLine("【这一片里刚刚发生的事】")
        val bus = world.bus(listener.id, waking.sinceSeq)
        if (bus.isEmpty()) {
            appendLine("（这一回合，你附近什么都没发生。）")
        } else {
            // **全文还是标题，就是这一节的全部意思**（§6.1、§14.39）：被指向的、以及你自己做的给全文；
            // 其余的只给编号与一行标题——想看就用 `look`。这不是省 token 的手段，而是「被指向」与
            // 「恰好在场」本来就是两回事，而模型该看得出这个差别，并自己决定要不要花一次调用去查。
            bus.forEach { appendLine(it.render()) }
        }
        appendLine()
        appendLine("（这段提示词里，「你」永远指你自己；别人一律带着 id 出现。`player` 就是你对面那个人。）")
    }

    private companion object {

        /**
         * 一次唤醒最多几个来回。
         *
         * 比判定（无上限，靠"没有查询手就收工"）与导演（24）都小得多：一个角色要说的话、要做的事
         * 就那么几件，而每一次来回都是一次真金白银的调用。**它是一条保险丝，不是一条规则**——正常
         * 情况下两次就收工了（说一句、然后不再调手）。
         */
        const val MAX_ROUNDS = 8

        val SYSTEM = """
            你扮演这个世界里的一个人。你不是助手，不讲故事，不替别人做决定——你只做你自己那个人。

            你能做三件事，都靠调工具：
            - `say`：开口说一句话。
            - `act`：做一个动作。**它成不成立由另一个独立的判定系统裁决**——你说了要做什么，不等于
              你做成了。被驳回是常事，驳回理由会说清为什么，你可以换个做法再试一次。
            - `plan`：给自己安排一件将来要做的事。它不影响眼前这一刻。

            规矩：

            1. **你就是人设里那个人。** 你的身份、脾气、知道些什么，一律以人设为准；人设里没写的，
               不要自己编一个出来。
            2. **你只看得见你身边这一片。** 下面那两张图就是你的全部视野。图外面有什么，你不知道——
               也不要猜。
            3. **你不掌控世界。** 不要描述自己做成了什么，那是判定的活；你只说自己要做什么、
               说什么。
            4. **什么都不做是允许的。** 不想理就是不想理，那也是一个反应——不要为了"有反应"而硬说
               一句。
            5. 说一句就调一次 `say`，做一件就调一次 `act`。**做完了就停下来**，不要一直调下去。
            6. **一次醒来通常只说一句，最多两句。** 对话是一来一回的——一口气说上四句，读起来像自
               言自语，不像有人在你对面。要做的事也最多一件；被驳回了就换个做法或者干脆不做，
               不要对着同一件事反复试。
            7. **不要为了做而做，不要为了说而说。** 别人只是跟你打个招呼，你回一句话就够了，不必
               顺手再做个动作；没什么想说的就什么都不说。**一次醒来只说话、或者只回一句就结束，
               都是完整的反应**——手里那两只手不是任务清单，不用每轮都过一遍。
            8. 你说话就是说话，不要加旁白、不要写"（他笑了笑）"这种舞台提示——那是别人看见的，
               不是你说出口的。
        """.trimIndent()
    }
}

/** `say` 的参数。**全带默认值**：解不开时退回空串，由上面那些判断统一报出来。 */
@Serializable
private data class SayArgs(val text: String = "")

/** `act` 的参数。同上。 */
@Serializable
private data class ActArgs(val intention: String = "", val targets: String = "")

/** `plan` 的参数。同上——**全带默认值**，解不开时退回一个明天中午，由上面那些判断统一报出来。 */
@Serializable
private data class PlanArgs(
    val what: String = "",
    val day: Int = 1,
    val hour: Int = 12,
    val minute: Int = 0,
)

/** `look` 的参数。同上。 */
@Serializable
private data class LookArgs(val id: String = "")
