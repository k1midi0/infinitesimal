package io.github.k1midi0.infinitesimal.npc

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
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.spatial.audience
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.EventKind
import io.github.k1midi0.infinitesimal.world.HARD_RADIUS_CHUNKS
import io.github.k1midi0.infinitesimal.world.PresetLibrary
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
 * 记忆窗口里装几条（§6.1）。
 *
 * **第一版是拍的一个保守值，它该由实测改掉。** §6.1 对它的原话是「N 由 spike 实测定，不由拍脑袋：
 * 观察 NPC 在多少轮之后开始重复或自相矛盾」。12 条大致是"最近几轮"，而一场对话里一轮往往产生两三条
 * （玩家说一句、做一个动作、矮人应一句）。
 *
 * 按**条**裁而不是按轮：§6.1 那三条裁剪规则里，「一次提交产生的全部后果不可切开」是在**一条事件
 * 内部**保证的（`Event.effects` 是一个整体），跨事件不需要绑定。
 *
 * 放在类外而不是私有伴生对象里，是为了让探针读得到它——`ConversationProbe` 要打印"这一轮它看得见
 * 几条"，而抄一个 12 到探针里就是那种迟早会漂开的东西。
 */
internal const val NPC_MEMORY_WINDOW = 12

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
 * ## 它没有的东西
 *
 * **messages 仍然不累积。** 每次唤醒新建一段（§14.36，与导演 §12 第 38 项同一个口径）——它跨回合记得
 * 的东西全部来自**记录层的窗口**（`NPC_MEMORY_WINDOW` 条，§14.37），而不是来自上一次的对话原文。
 *
 * 这两条路的区别是实在的：窗口是**世界对它的广播记录**（谁说了什么、世界因此变了哪几条），不是它自己
 * 那场对话的逐字回放。§6.1 选的就是前者——它可审计，而且不会因为模型上一次的措辞而漂。
 *
 * **没有笔记、没有状态循环。** 前者是 §6.5，后者是 §12 第 24 项。现在它每次醒来只知道"我是谁、
 * 我在哪、我刚才听见了什么"。
 */
class Npc(
    private val client: LlmClient,
    private val judge: Judge,
    val effort: ReasoningEffort = ReasoningEffort.HIGH,
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
    ) : this(
        LlmClient(config.copy(userId = SystemId.NPC)),
        Judge(config, effort = effort, presets = presets),
        effort,
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

        val messages = mutableListOf(
            ChatMessage.system(SYSTEM),
            ChatMessage.user(userPrompt(world, waking, listener)),
        )

        var round = 0
        while (round < MAX_ROUNDS) {
            round++
            requestCount++

            val turn = AssistantTurn.of(
                client.streamChat(
                    messages = messages,
                    tools = NpcTools.ALL,
                    thinking = ThinkingRequest.of(ThinkingMode.Always, effort),
                    // **不设 `max_tokens`。** 与判定、导演同一条：思维链也算在它里面，限了就会让第二步
                    // 空转（§14.19 第四节实测过）。
                    maxTokens = null,
                ).toList(),
            )

            lastUsage = turn.usage
            if (round == 1) lastFinishReason = turn.finishReason

            // **一个工具都不调 = 它说完了。** 与导演同一条循环规则（§14.33）：它要说几句、做几件事
            // 本来就是多行的，所以判据是"这一轮没有手"，不是"这一轮没有查询手"（判定那边才是后者）。
            if (turn.calls.isEmpty()) break

            messages += turn.asAssistantMessage()

            for (call in turn.calls) {
                val answer = answerFor(call, world, waking, listener, said, acted)
                if (answer.problem != null) problems += answer.problem
                messages += ChatMessage.toolResult(call.id, answer.text)
            }
        }

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
        else -> Answered(
            "没有叫「${call.name}」的工具。你现在能用的只有：" +
                NpcTools.ALL.joinToString("、") { it.function.name } + "。",
            // **记成一条协议问题，不静默咽下去**（§1.3）。它是"模型调了一只不存在的工具"，而不是
            // "这个动作不成立"——两者要分开，后者是世界的裁定，前者是它自己出了问题。
            "模型调了一只不存在的工具：${call.name}",
        )
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
                to = world.audience(listener.id, affected = judgment.affected),
            )

            is Judgment.Unchanged -> world.log.append(
                at = world.clock,
                kind = EventKind.ACT,
                actorId = listener.id,
                actorLabel = listener.label,
                text = intention,
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

        appendLine("【你记得的最近这些事】")
        val window = world.log.windowOf(listener.id, NPC_MEMORY_WINDOW)
        if (window.isEmpty()) {
            appendLine("（你刚到这里，还什么都没赶上。）")
        } else {
            // 最早的在前，与发生顺序一致。**最后那几条就是此刻的上下文**——包括刚刚叫醒你的那一句。
            window.forEach { appendLine("- ${it.line()}") }
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
