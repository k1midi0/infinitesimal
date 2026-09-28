package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.generate.Placement
import io.github.k1midi0.infinitesimal.generate.TerrainGenerator
import io.github.k1midi0.infinitesimal.judge.RulebookView
import io.github.k1midi0.infinitesimal.llm.AssistantTurn
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.ToolCallRequest
import io.github.k1midi0.infinitesimal.llm.Usage
import io.github.k1midi0.infinitesimal.llm.decodeCall
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.ChunkView
import io.github.k1midi0.infinitesimal.spatial.RegionBuilder
import io.github.k1midi0.infinitesimal.spatial.RegionView
import io.github.k1midi0.infinitesimal.spatial.chunkDisc
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.spatial.nearestMissing
import io.github.k1midi0.infinitesimal.spatial.requiredChunks
import io.github.k1midi0.infinitesimal.world.BUFFER_RADIUS_CHUNKS
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.TimelineEntry
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable

/**
 * **导演被叫醒的原因。**
 *
 * 它决定三件事，而这三件事恰好是同一条因果链上的（§8.5、§14.33）：用哪一档、手里有哪些手、以及
 * **「什么都不做」合不合法**。
 *
 * 最后那一条是这一层最容易做错的地方。「什么都没干」在一种情况下是失败（代码发现硬圈缺了地形，
 * 那一块必须长出来），在另一种情况下是**正确的判断**（快速档看着，觉得这一带还不用长）。把它推成
 * 一个统一的"空手 = 重试"，就会让快速档每回合都被逼着做点什么，而那正是它最该少做的事。
 */
sealed interface Wake {

    /**
     * 快速档每回合的那一次：玩家刚做完一个动作，看着。
     *
     * **它不挡玩家**——回合提交之后在后台跑（§14.33）。它看不到地图，只拿到一个数（最近的空地还有
     * 几块远）；这正是它便宜的原因。
     */
    data class Watching(val turn: String) : Wake

    /** 明确要求排一排时间轴（界面上那个按钮）。**至少排一件**——空手等于什么都没安排（§14.28）。 */
    data object Arrange : Wake

    /**
     * 代码发现硬圈缺口，**同步**叫思考档，玩家等（§14.33）。
     *
     * [required] 是代码算的那一份，必须真的写进地形。它是本文件里唯一一条"模型说了不算"的判据。
     */
    data class HardGap(val required: List<ChunkKey>) : Wake

    /**
     * 有人把思考档叫起来了，而且**理由写在 [why] 里**。这一带还不用长的话，**什么都不做是合法的结果**
     * （[unmet] 对它是空的）。
     *
     * [aboutId] 是**这一次要看的那一个人**（§14.52）："它刚从氛围角色变成活跃角色，你看要不要给
     * 它补背景"。它是可空的，因为 `Called` 从前还有别的用法（快速档叫思考档），而那些用法只给一句
     * 理由、不点人。
     */
    data class Called(val why: String, val aboutId: String? = null) : Wake

    /**
     * **有人到了点却没进那一格**（§14.45）：代码判过一次（地形站不住、或者哨兵说这一格现在做不了），
     * 于是那个人被从它的作息上拿了下来，而**它的作息要由导演重新写**。
     *
     * ## 为什么这是一条自己的唤醒，而不是并进 [Called]
     *
     * 因为它的契约不一样：这一次唤醒**有非做不可的事**——[required] 里那几个人必须各拿到一份新作息，
     * 而"什么都没做"在这里不是"我看过了不用动"（那是 [Called] 的合法结果）。空缺的那一格把它送进了
     * 活跃期，而活跃的人只会等着一件永远不会来的事。
     *
     * ## 为什么只有那几个人
     *
     * 它此时处理的**就是玩家身边那一片**（到点就位只处理看得见的那些节点，§14.45），所以那一带别的人
     * 通常也看得见；但这一次要改的是**被卡住的那些人**，别的角色一个字都不该动。
     *
     * [was] 是判"它改没改"的那把尺子：那几个人卡住时，循环的 `bornAt` 就是这里的值；导演写一份新的，
     * `bornAt` 会变成**写下去那一刻**（[CyclePlanner.apply]）。所以 `bornAt` 还等于 [was] 里的值，就是
     * "这一次它什么都没做"。
     *
     * **它是写下去那一刻的时钟，而不是"卡住那一刻"的时钟**——两者通常相等（卡住与唤醒同在一轮），但
     * 判据必须是前者：万一那一份作息本来就是这一刻写下的，拿后者比就会把一次**真写成了**的改写判成
     * 没写（这个坑第一版就踩了，`GameSessionStuckTest` 逮住）。
     */
    data class Stuck(
        val required: List<String>,
        val why: List<String>,
        val was: Map<String, WorldTime>,
    ) : Wake
}

/** 导演的两档。**同一个模型的两套参数**，不是两个模型（§8.5）。 */
enum class Tier { FAST, THINKING }

/** [Wake] 落在哪一档。只有「看着」是快速档。 */
val Wake.tier: Tier
    get() = if (this is Wake.Watching) Tier.FAST else Tier.THINKING

/**
 * 一次唤醒的全部输入。
 *
 * [focusId] 是它站在谁那儿看世界——现在只有玩家一个人，等 NPC 进来，同一个入口还会被用来站在某个
 * NPC 那儿。
 *
 * [origin] 是**区块坐标的显示原点**，它在整局里是固定的（玩家开局那一块的左下，§14.33）。它必须由
 * 调用方拿着，不能在这里从玩家当前位置推——玩家会走，而数轴上的数字一旦跟着走，之前给它看过的
 * 坐标就全部换了意思。
 */
data class Waking(
    val why: Wake,
    val focusId: String,
    val origin: ChunkOrigin,
)

/** 一次唤醒的结果。 */
sealed interface Awakening {

    /**
     * 干完了。
     *
     * [tier] 是**最后停在哪个档位**——快速档什么都没做时它是 [Tier.FAST]，它把思考档叫起来之后就
     * 变成 [Tier.THINKING]，而后者不可能自己降回去（这一刀没有那只手，见 [DirectorTools]）。
     */
    data class Done(
        val tier: Tier,
        val scheduled: List<TimelineEntry>,
        val chunks: List<ChunkKey>,
        val attempts: Int,
    ) : Awakening

    /**
     * 试了 [attempts] 次都没满足这一次唤醒的契约。
     *
     * [problems] 是最后一轮的理由，**原样留着**——它是日志里唯一能分辨"模型没说话""参数一直写错"
     * "它觉得无事可排"的东西（§1.5 第三条：驳回理由不撒谎）。
     */
    data class FailedProtocol(val problems: List<String>, val attempts: Int) : Awakening
}

/**
 * 导演的模型段：**安排将来会发生什么，并且把世界补写出来**（§8）。
 *
 * ## 两级，共用一段历史
 *
 * §8.5 定的形状在这里落成：快速档每回合醒着（`thinking: disabled`），发现要动手就把思考档叫起来；
 * 切档**只换参数**，messages 一段不重建——DeepSeek 的缓存按 token 前缀完整匹配，共用前缀才命中
 * （§14.5、§14.8）。
 *
 * 代价是一条要更小心守的纪律：带 `tools` 的请求，后续所有 assistant 消息都必须回传
 * `reasoning_content`（空思考给 `""`，不能给 null，§1.2.1）。两档共用历史而工具表不同，这一条比单档
 * 时更容易破——破的方式是整次请求 400。
 *
 * ## 它为什么是思考档，而且全程思考
 *
 * §8.2 把两个触发器分得很清楚：**时间轴到期由代码检测**（确定性、可复现，见
 * [io.github.k1midi0.infinitesimal.session.FastForward]）；**剧情跑偏由快速模型发现，而它不能改
 * 时间轴**——改时间轴只有思考档能做。用户对这件事的裁定是直白的：**不要限制思考，防止逻辑出错，
 * 包括导演系统同理**（§12 第 16 项）。所以这里**不设 `max_tokens`**——发 null 就是不发这一项，
 * 端点用思考档默认的 64K。任何一个小数字都会把想得久的那一次截断，而症状是"它什么都没排出来"，
 * 与"它觉得无事可排"长得一模一样（§14.19）。
 *
 * ## 它读的东西
 *
 * 世界规则与发起者的规则/属性、发起者身边的那一片（复用判定那套空间框）、**那张区块地图**、时间轴上
 * 已经排了什么。最后一样是它唯一需要"看见自己上次干了什么"的地方——没有它，每一次调用都会从零开始
 * 重排一遍，而重排出来的会与上一次对不上。
 */
class Director(
    private val client: LlmClient,
    /**
     * 生成地形与物件的那一层（§9）。**导演手上那只 `grow_terrain` 直接调到它**。
     *
     * 它自己带一个客户端与一个缓存池（[SystemId.GENERATOR]），与导演这个分开：一次生成是一次独立、
     * 可长可短的工作，而它烧的 token 与导演排时间轴烧的完全是两笔账。
     */
    private val generator: TerrainGenerator,
    private val maxAttempts: Int = 2,
    /** 思考档的档位。**留这个参数只为能再量，不是为了降档**（§12 第 17 项同一条理由）。 */
    val effort: ReasoningEffort = ReasoningEffort.HIGH,
) {

    /**
     * 给配置确实是固定值的地方用。**强制落到 [SystemId.DIRECTOR] 那个缓存池**。
     *
     * 缓存隔离的代价在这里几乎为零，因为各个系统的 system 提示词本来就完全不同（见 [SystemId]）。
     */
    constructor(
        config: EndpointConfig,
        presetLibrary: io.github.k1midi0.infinitesimal.world.PresetLibrary =
            io.github.k1midi0.infinitesimal.world.PresetLibrary(),
        maxAttempts: Int = 2,
        effort: ReasoningEffort = ReasoningEffort.HIGH,
    ) : this(
        LlmClient(config.copy(userId = SystemId.DIRECTOR)),
        TerrainGenerator(config, presetLibrary = presetLibrary),
        maxAttempts,
        effort,
    )

    /** 整场跑掉的模型调用。与判定那个分开数，是为了让"哪一层烧了钱"看得出来。 */
    var requestCount = 0
        private set

    var lastUsage: Usage? = null
        private set

    var lastFinishReason: String? = null
        private set

    /** 最后一次唤醒用了几档——探针与日志要看得见"它到底想没想"。 */
    var lastTier: Tier = Tier.THINKING
        private set

    /**
     * 把它叫醒一次。
     *
     * 重试的判据是 [unmet]——**从世界与它交出来的东西上读，不从它说的话里读**（§14.33）。必需的那几
     * 块写没写进地形，看的是 [io.github.k1midi0.infinitesimal.world.Terrain.isGenerated]，不是它说
     * "长好了"。
     */
    suspend fun wake(world: World, waking: Waking): Awakening {
        val focus = world.byId(waking.focusId)
            ?: return Awakening.FailedProtocol(listOf("世界里没有 id 是「${waking.focusId}」的东西。"), 0)

        var notes = emptyList<String>()
        var lastProblems = listOf("一次都没试。")
        var attempts = 0

        while (attempts < maxAttempts) {
            attempts++
            val taken = once(world, waking, focus, notes)
            lastTier = taken.tier

            // 排下的那批**整批校验、整批提交**（[TimelinePlanner]）。落一半进去，剩下那一半还得让它
            // 重排一次——而它重排时会看到一张已经变了一半的表，于是第二批会和第一批对不上。
            if (taken.scheduled.isNotEmpty()) {
                when (val planned = TimelinePlanner.apply(world, taken.scheduled)) {
                    is Planned.Done -> Unit
                    is Planned.Rejected -> {
                        lastProblems = planned.problems
                        notes = planned.problems
                        continue
                    }
                }
            }

            val problems = unmet(world, waking, taken)
            if (problems.isEmpty()) {
                return Awakening.Done(taken.tier, taken.scheduled, taken.chunks, attempts)
            }
            lastProblems = problems
            notes = problems
        }

        return Awakening.FailedProtocol(lastProblems, attempts)
    }

    // ── 一次唤醒 ───────────────────────────────────────────────────────────────────

    /** 一次唤醒跑出来的东西。**诊断信息一并带着**，否则失败了只剩下一句"没干成"。 */
    private class Taken(
        val tier: Tier,
        val scheduled: List<TimelineEntry>,
        val chunks: List<ChunkKey>,
        val reply: String,
        val finishReason: String?,
        val usage: Usage?,
    )

    private suspend fun once(
        world: World,
        waking: Waking,
        focus: Entity,
        notes: List<String>,
    ): Taken {
        var tier = waking.why.tier
        val scheduled = mutableListOf<TimelineEntry>()
        val chunks = mutableListOf<ChunkKey>()

        val messages = mutableListOf(
            ChatMessage.system(SYSTEM),
            ChatMessage.user(userPrompt(world, waking, focus, notes)),
        )

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
                    tools = DirectorTools.of(tier),
                    thinking = thinkingFor(tier),
                    // **不设 `max_tokens`。** 见类注释：思维链也算在它里面。
                    maxTokens = null,
                ).toList(),
            )

            reply = turn.content
            finish = turn.finishReason
            usage = turn.usage
            if (round == 1) lastFinishReason = finish
            lastUsage = usage

            // **一个工具都没调 = 它说干完了。** 这是这一层与判定那一层唯一一处不同的循环规则：
            // 判定是"这一轮没有查询手就收工"，导演是"这一轮没有手就收工"——因为它要安排的事本来就是
            // 多行的，而且生成要走到下游去（§8.1）。
            if (turn.calls.isEmpty()) break

            messages += turn.asAssistantMessage()

            var switched = false
            for (call in turn.calls) {
                if (call.name == DirectorTools.WAKE_THINKING) {
                    switched = true
                    messages += ChatMessage.toolResult(call.id, switchedTo(world, waking, call))
                } else {
                    messages += ChatMessage.toolResult(
                        call.id,
                        answerFor(call, world, waking, scheduled, chunks),
                    )
                }
            }

            if (switched) {
                // 换档之后**把思考档该看的东西补给它**。快速档那段提示里没有地图、没有空间框、也没有
                // 时间轴——那些是它每一回合都要付的钱，而它大多数时候什么都不做（§14.33）。
                //
                // 补在末尾而不是重建历史：前缀原样不动，缓存照旧命中。这仍然是「两档共用一段 messages」。
                tier = Tier.THINKING
                messages += ChatMessage.user(thinkingBriefing(world, waking, focus, scheduled))
            }
        }

        return Taken(tier, scheduled, chunks, reply, finish, usage)
    }

    /** 一只手的答复。**解不开的参数与不合法的值都变成文字退回去**，不抛异常（§1.3）。 */
    private suspend fun answerFor(
        call: ToolCallRequest,
        world: World,
        waking: Waking,
        into: MutableList<TimelineEntry>,
        grew: MutableList<ChunkKey>,
    ): String = when (call.name) {
        DirectorTools.SCHEDULE -> schedule(call, world.clock, into)
        DirectorTools.GROW_TERRAIN -> growTerrain(call, world, waking, grew)
        DirectorTools.LOOK_MAP -> lookMap(call, world, waking)
        DirectorTools.WRITE_CYCLE -> writeCycle(call, world, waking)
        DirectorTools.SPAWN_CHARACTER -> spawnCharacter(call, world, waking)
        DirectorTools.WRITE_CARD -> writeCard(call, world, waking)
        else -> "没有叫「${call.name}」的工具。你现在能用的只有：" +
            DirectorTools.of(waking.why.tier).joinToString("、") { it.function.name } + "。"
    }

    // ── 写作息（§14.44） ────────────────────────────────────────────────────────────

    /**
     * 导演手上那只写作息的手。
     *
     * ## 为什么它当场就写，而排时间轴要等整批
     *
     * 时间轴那一批是**一次计划**（"先起火、后被人发现"），所以整批校验、整批提交（[TimelinePlanner]）。
     * 作息不一样：**一份作息只属于一个人，而一次调用正好写一个人**——人与人之间没有那种先后关系，所以
     * 各写各的、写错了当场退回，正是它该有的形状。同一批里给同一个人写两份仍然被拦（那几乎一定是笔误）。
     *
     * ## 驳回的理由都不撒谎（§1.5 第三条）
     *
     * 五条：没见过的人、世界没有的 id、不是角色、格式读不懂、作息立不住。每一条都把可执行的部分写出来
     * ——读不懂给原文，立不住给那一条，看不见的人告诉它"先让人进到你眼前"。
     */
    private fun writeCycle(call: ToolCallRequest, world: World, waking: Waking): String {
        val args = decodeCall(WriteCycleArgs.serializer(), call)
            ?: return "${call.name} 的参数不是合法 JSON：${call.arguments.take(160)}"

        val id = args.id.trim()
        if (id.isEmpty()) return "没说要写给谁。id 填你眼前看得见的那个角色。"

        val target = world.byId(id)
            ?: return "世界里没有 id 是「$id」的东西。你眼前那段清单里的 id 才是能写的那些。"

        if (target.kind != CHARACTER_KIND) {
            return "「${target.label}」不是角色（它是个${target.kind}），没有作息可写。"
        }

        val visible = visibleTo(world, waking.focusId)
        if (id !in visible) {
            return "「${target.label}」不在你这一轮看得见的范围里——你眼前那段清单与那张地图上都没有它。" +
                "你看不见它此刻在哪儿，写出来的坐标只能靠猜。先让它进到你能看见的地方，再排它。"
        }

        val parsed = when (val p = CycleText.parse(args.beats)) {
            is CycleParse.Parsed -> p.beats
            is CycleParse.Failed -> return p.why
        }

        val problems = CyclePlanner.problems(world, listOf(CycleWrite(id, parsed)), visible)
        if (problems.isNotEmpty()) return "这份作息没写进去：\n" + problems.joinToString("\n")

        // **没写进去的绝不报告成写进去了**（§1.5 第三条：理由不撒谎）。上面每一条驳回都在这个世界
        // 一个字没动的时候返回，所以答复里"写好了"这句话只在真的落库之后出现。
        CyclePlanner.apply(world, listOf(CycleWrite(id, parsed)), world.clock)
        return buildString {
            append("写好了：「${target.label}」从现在（${world.clock}）起跑这一圈，")
            append("${parsed.size} 格，一圈 ${parsed.maxOf { it.atMinute }} 分钟。")
            append("它现在按这一圈站在 (${world.byId(id)?.x}, ${world.byId(id)?.y}, ${world.byId(id)?.z})。")
            append("\n你写进去的是：")
            parsed.sortedBy { it.atMinute }.forEach { append("\n- ").append(CycleText.render(it)) }
        }
    }

    // ── 放一个人出来（§14.46） ──────────────────────────────────────────────────────

    /**
     * 导演手上那只"叫一个人来"的手。
     *
     * 与 [writeCycle] 同一个形状：当场校验、当场落库，驳回的理由不撒谎（§1.5 第三条）。六条门写在
     * [CharacterPlanner.problems] 里，这里只负责把它们**在落库之前**读出来，于是答复里那句"放好了"
     * 只在世界真的多了一个人之后才出现。
     *
     * ## 为什么是一次一个人
     *
     * 与作息同一条理由（§14.44）：一个人只属于它自己，人与人之间没有那种先后关系，所以各放各的、
     * 放错了当场退回，正是它该有的形状。而"一条街上该有三五个人"是长地形顺手放的，不是这里数的。
     */
    private fun spawnCharacter(call: ToolCallRequest, world: World, waking: Waking): String {
        val args = decodeCall(SpawnCharacterArgs.serializer(), call)
            ?: return "${call.name} 的参数不是合法 JSON：${call.arguments.take(160)}"

        val id = args.id.trim()
        if (id.isEmpty()) return "没说要放谁。id 是你以后叫得出它的那个名字。"

        val at = CharacterText.parsePoint(args.at)
            ?: return "站的地方读不出来：「${args.at.trim()}」。写成 x,y,z 三个整数（厘米），" +
                "照抄你眼前那段清单里的坐标。"

        val cards = CharacterText.parseCards(args.cards)
        val beats = when (val p = CycleText.parse(args.beats)) {
            is CycleParse.Parsed -> p.beats
            // 空着是允许的（＝剧情角色）。只有它写了、却读不懂时才退回去。
            is CycleParse.Failed -> if (args.beats.isBlank()) {
                emptyList()
            } else {
                return "它的作息读不懂：${p.why}"
            }
        }

        val write = CharacterWrite(
            id = id,
            label = args.label.trim(),
            cards = cards,
            at = CharacterPoint(x = at.first, y = at.second, z = at.third),
            beats = beats,
        )

        val visible = visibleTo(world, waking.focusId)
        val problems = CharacterPlanner.problems(world, listOf(write), visible)
        if (problems.isNotEmpty()) return "这个人没放下去：\n" + problems.joinToString("\n")

        CharacterPlanner.apply(world, listOf(write))
        val placed = world.byId(id)!!
        return buildString {
            append("放好了：「${placed.label}」（id 是「$id」），站在 ")
            append("(${placed.x}, ${placed.y}, ${placed.z})。")
            if (beats.isEmpty()) {
                append("它**没有作息**——平时它就在那儿待着，只有被点到名的时候才动。")
            } else {
                append("它有自己的作息（${beats.size} 格，一圈 ${beats.maxOf { it.atMinute }} 分钟），")
                append("到点代码会把它挪过去，平时不用你管。")
            }
            append("\n你写进去的卡是：")
            cards.forEach { append("\n- ").append(it.title).append("：").append(it.content) }
        }
    }

    // ── 人设追加（§14.52） ──────────────────────────────────────────────────────────

    /**
     * 导演手上那只"把这一趟写进它的人设"的手。
     *
     * 与 [writeCycle] / [spawnCharacter] 同一个形状：解码 → 世界里有没有这个人 → **看得见吗** →
     * 解析 → [CardPlanner.problems] → 通过之后才 [CardPlanner.apply]。答复里那句"写好了"只在真的
     * 落库之后出现（§1.5 第三条：理由不撒谎）。
     */
    private fun writeCard(call: ToolCallRequest, world: World, waking: Waking): String {
        val args = decodeCall(WriteCardArgs.serializer(), call)
            ?: return "${call.name} 的参数不是合法 JSON：${call.arguments.take(160)}"

        val id = args.id.trim()
        if (id.isEmpty()) return "没说要写给谁。id 填你眼前那段清单里的那个角色。"

        val target = world.byId(id)
            ?: return "世界里没有 id 是「$id」的东西。你眼前那段清单里的 id 才是能写的那些。"

        val visible = visibleTo(world, waking.focusId)
        val parsed = when (val p = CardText.parse(args.cards)) {
            is CardParse.Parsed -> p.cards
            is CardParse.Failed -> return p.why
        }

        val write = CardWrite(id, parsed)
        val problems = CardPlanner.problems(world, listOf(write), visible)
        if (problems.isNotEmpty()) return "这份人设没写进去：\n" + problems.joinToString("\n")

        CardPlanner.apply(world, listOf(write))
        val after = world.byId(id)!!
        return buildString {
            append("写好了：「${after.label}」的人设现在是这 ")
            append("${after.cards.size} 张：${after.cards.titlesWithSize()}。")
            append("\n你这一次写进去的是：")
            parsed.forEach { append("\n- ").append(CardText.render(it)) }
        }
    }

    // ── 排时间轴 ───────────────────────────────────────────────────────────────────

    private fun schedule(
        call: ToolCallRequest,
        now: WorldTime,
        into: MutableList<TimelineEntry>,
    ): String {
        val args = decodeCall(ScheduleArgs.serializer(), call)
            ?: return "${call.name} 的参数不是合法 JSON：${call.arguments.take(160)}"

        val id = args.id.trim()
        val what = args.what.trim()
        if (id.isEmpty()) return "这件事没给 id。给一个看得出是什么的代号。"
        if (what.isEmpty()) return "「$id」没说要发生什么。what 里写一句「什么时候、谁、做什么」。"
        if (args.day < 1) return "「$id」的 day 是 ${args.day}；第几天从 1 起（现在是 $now）。"
        if (args.hour !in 0..23) return "「$id」的 hour 是 ${args.hour}；要在 0 到 23 之间。"
        if (args.minute !in 0..59) return "「$id」的 minute 是 ${args.minute}；要在 0 到 59 之间。"

        val due = WorldTime.of(args.day, args.hour, args.minute)
        if (due < now) {
            return "「$id」排在 $due，那个时刻已经过去了（现在是 $now）。已经过去的时刻排不出事。"
        }

        into += TimelineEntry(id = id, dueAt = due, what = what)
        return "记下了：$due，$what"
    }

    // ── 长地形 ─────────────────────────────────────────────────────────────────────

    /**
     * 导演手上那只生成长地形的手。
     *
     * ## 为什么具体落在哪儿不归它管
     *
     * 它给的是「放什么 / 在哪一块 chunk / 那份默认描述」，**具体坐标由生成器在铺那块地的时候定**——
     * 生成器刚铺完，看得见剖面；导演手里没有剖面，给它一个厘米数字只会得到一个编出来的数字
     * （「油灯坐标是编的」那一课，§14.18）。
     *
     * ## 缓冲圈是硬边界
     *
     * 填到圈外的坐标**当场驳回**，理由要可执行：把圈外的数字退回去是不够的，模型需要知道"远"本身就
     * 是它不该做的事（§14.33）。
     */
    private suspend fun growTerrain(
        call: ToolCallRequest,
        world: World,
        waking: Waking,
        grew: MutableList<ChunkKey>,
    ): String {
        val args = decodeCall(GrowArgs.serializer(), call)
            ?: return "${call.name} 的参数不是合法 JSON：${call.arguments.take(160)}"

        val requirement = args.requirement.trim()
        if (requirement.isEmpty()) {
            return "没说要长成什么样。requirement 里写一句这一带该是什么样（「镇子北边的一片荒地」）。"
        }

        val asked = ChunkView.parseChunks(args.chunks, waking.origin)
        if (asked.isEmpty()) {
            return "没读懂要长哪几块。填数轴上的坐标，例如 (6,2) (7,2) —— 就在那张地图上抄。"
        }

        val player = world.chunkOfEntity(waking.focusId)
            ?: return "读不出「${waking.focusId}」在哪一块上，所以算不出缓冲圈。"

        val allowed = chunkDisc(player, BUFFER_RADIUS_CHUNKS).toSet()
        val outside = asked.filterNot { it in allowed }
        if (outside.isNotEmpty()) {
            return "这几块在缓冲圈以外，不归这一次长：" +
                outside.joinToString("、") { shown(it, waking) } +
                "。能长的是以玩家为中心、半径 $BUFFER_RADIUS_CHUNKS 块以内的地方" +
                "——地图上画出来的那些。"
        }

        val (placements, badLines) = placementsOf(args.placements, waking)
        if (badLines.isNotEmpty()) {
            return "点名要放的东西没读懂：\n" + badLines.joinToString("\n") +
                "\n一行一件，写成「放什么｜在哪一块｜默认描述」，后两段可以省。"
        }

        val required = (waking.why as? Wake.HardGap)?.required.orEmpty()
        val growth = generator.grow(
            world,
            TerrainGenerator.Request(
                requirement = requirement,
                chunks = asked,
                required = required,
                placements = placements,
            ),
        )

        return when (growth) {
            is TerrainGenerator.Growth.Grown -> {
                grew += growth.chunks
                "长好了：" + growth.chunks.joinToString("、") { shown(it, waking) } +
                    "（写进去 ${growth.consequences.size} 条后果）"
            }
            is TerrainGenerator.Growth.FailedProtocol ->
                "这一片没长成，重来一次：\n" + growth.problems.joinToString("\n")
        }
    }

    /** `放什么｜哪一块｜默认描述`，一行一件。读不懂的行**一条都不丢**，原样报回去。 */
    private fun placementsOf(text: String, waking: Waking): Pair<List<Placement>, List<String>> {
        val out = mutableListOf<Placement>()
        val bad = mutableListOf<String>()
        for ((i, line) in text.lines().withIndex()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val parts = t.split('｜', '|').map { it.trim() }
            val brief = parts.getOrNull(0).orEmpty()
            if (brief.isEmpty()) {
                bad += "第 ${i + 1} 行没说要放什么：「$t」"
                continue
            }
            val chunk = parts.getOrNull(1)?.let { ChunkView.parseChunk(it, waking.origin) }
            if (chunk == null) {
                bad += "第 ${i + 1} 行没读懂放在哪一块：「$t」"
                continue
            }
            out += Placement(chunk = chunk, brief = brief, background = parts.getOrNull(2).orEmpty())
        }
        return out to bad
    }

    // ── 看地图 ─────────────────────────────────────────────────────────────────────

    /**
     * 看更大范围的地图。
     *
     * 默认那段地图只画缓冲圈（半径 [BUFFER_RADIUS_CHUNKS] 块），而它要决定"是不是该往那一头长"时，
     * 看的是圈里那一片**还没长**的地方——圈外的已经不在它的处置范围里了，所以这一只手其实只用来
     * 看细一点。**看得远不等于长得到**：能长哪儿由 `grow_terrain` 那边驳回。
     */
    private fun lookMap(call: ToolCallRequest, world: World, waking: Waking): String {
        val args = decodeCall(LookMapArgs.serializer(), call)
            ?: return "${call.name} 的参数不是合法 JSON：${call.arguments.take(160)}"

        val player = world.chunkOfEntity(waking.focusId) ?: return "读不出玩家在哪一块上。"
        val a = ChunkView.parseChunk("${args.x1},${args.y1}", waking.origin)
        val b = ChunkView.parseChunk("${args.x2},${args.y2}", waking.origin)
            ?: return "没读懂框。填角上的两个区块坐标，例如 x1=6 y1=2 x2=10 y2=6。"

        val x1 = minOf(a!!.x, b.x)
        val x2 = maxOf(a.x, b.x)
        val y1 = minOf(a.y, b.y)
        val y2 = maxOf(a.y, b.y)
        if ((x2 - x1 + 1) * (y2 - y1 + 1) > LOOK_MAP_LIMIT) {
            return "这一框有 ${(x2 - x1 + 1) * (y2 - y1 + 1)} 块，太多了。" +
                "一次最多看 $LOOK_MAP_LIMIT 块，分几次看。"
        }

        return ChunkView.renderBox(world, waking.origin, player, x1, y1, x2, y2)
    }

    // ── 契约 ───────────────────────────────────────────────────────────────────────

    /**
     * 这一次唤醒的要求满足了没有。
     *
     * 「什么都没做」在这四只上分成两类（[Wake] 的注释）：材料不足是失败，**判断为不必做是成功**。所以
     * 空手本身从来不是判据——判据是这一次唤醒当初要的是什么。
     */
    private fun unmet(world: World, waking: Waking, taken: Taken): List<String> = when (val why = waking.why) {
        is Wake.Watching -> emptyList()

        is Wake.Called -> emptyList()

        is Wake.Stuck -> {
            // **判据是"出生时刻变了没有"，不是它说了什么。** 一个人卡住时它的循环是那一份；导演给它
            // 写一份新的，`bornAt` 会变成写下去那一刻（`CyclePlanner.apply`，取的正是"当下"）——所以
            // "还是原来那一份"就等于"这一次唤醒它什么都没做"（§1.5 第三条：理由不撒谎）。
            val still = why.required.filter { id -> world.byId(id)?.cycle?.bornAt == why.was[id] }
            if (still.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    "这几个人还是原来那一份作息，所以到了点照样进不去：" +
                        still.joinToString("、") { world.byId(it)?.label ?: it } +
                        "。用 `write_cycle` 给他们各写一份新的——旧的那一份整张换掉。",
                )
            }
        }

        is Wake.Arrange ->
            if (taken.scheduled.isEmpty()) {
                listOf("一件都没排。至少排一件——一件都没排等于什么都没安排。")
            } else {
                emptyList()
            }

        is Wake.HardGap -> {
            val missing = why.required.filterNot { world.terrain.isGenerated(it) }
            if (missing.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    "必需的那几块还没写进地形：" + missing.joinToString("、") { shown(it, waking) } +
                        "。玩家正走进这一片，它们必须先长出来。",
                )
            }
        }
    }

    /** 一片区块在数轴上的写法。**给模型看的永远是显示坐标**（[ChunkOrigin]）。 */
    private fun shown(chunk: ChunkKey, waking: Waking): String =
        waking.origin.display(chunk).let { "(${it.x},${it.y})" }

    // ── 提示词 ─────────────────────────────────────────────────────────────────────

    private fun thinkingFor(tier: Tier): ThinkingRequest = when (tier) {
        Tier.FAST -> ThinkingRequest.of(ThinkingMode.Never, effort)
        Tier.THINKING -> ThinkingRequest.of(ThinkingMode.Always, effort)
    }

    /**
     * 快速档那一段。**它很短，而且是有意的**：这一段每一回合都要付一次钱，而它大多数时候什么都不做。
     * 地图、空间框、时间轴都在 [thinkingBriefing] 那一段里，只有真的要动手时才补给它。
     */
    private fun userPrompt(
        world: World,
        waking: Waking,
        focus: Entity,
        notes: List<String>,
    ): String = buildString {
        appendLine("【现在】")
        appendLine(world.clock.toString())
        appendLine()

        val rules = RulebookView.forPrefix(world, waking.focusId)
        if (rules.isNotEmpty()) {
            appendLine(rules)
        }

        when (val why = waking.why) {
            is Wake.Watching -> {
                appendLine("【${focus.label}刚做了什么】")
                appendLine(why.turn.ifBlank { "（没有记下这一回合的动作）" })
                appendLine()

                appendLine("【离内圈最近的空地】")
                appendLine(nearestLine(world, waking))
                appendLine()

                appendLine("【你在哪一档】")
                appendLine("快速档。你只是看着。")
                appendLine()
            }

            is Wake.Called -> {
                appendLine("【为什么把你叫起来】")
                appendLine(why.why.ifBlank { "（没有说明理由）" })
                appendLine()
                append(thinkingBriefing(world, waking, focus, emptyList()))
            }

            is Wake.HardGap -> {
                appendLine("【为什么把你叫起来】")
                appendLine(
                    "玩家正走进这一片，而它还没有地形。必需的那几块：" +
                        why.required.joinToString("、") { shown(it, waking) },
                )
                appendLine("**那几块必须写进地形**，否则这一次唤醒不算完成。")
                appendLine()
                append(thinkingBriefing(world, waking, focus, emptyList()))
            }

            is Wake.Arrange -> {
                appendLine("【你在哪一档】")
                appendLine("思考档。")
                appendLine()
                append(thinkingBriefing(world, waking, focus, emptyList()))
            }

            is Wake.Stuck -> {
                appendLine("【为什么把你叫起来】")
                appendLine("**两种情形都会用这一段**，理由里写得清是哪一种：")
                appendLine()
                appendLine("- **有人到了它作息上的那一格，却没进得去**：它被从自己的日子上拿了下来，此刻正")
                appendLine("  停在那一格的门口。")
                appendLine("- **有人离开自己的日子太久了**（被锁起来、被带走、或者别的原因回不去）：它没有停在")
                appendLine("  哪一格的门口，而是**停在别的地方**。这种情形下它会一直等下去——等不到任何一格。")
                appendLine()
                appendLine("两种情形你要做的事**是同一件**：给名单上这些人各写一份新的作息，让那一格换个")
                appendLine("做得了的地方、或者换个做得了的时候。旧的那一份会被整张换掉。")
                appendLine()
                appendLine("**他们此刻都是活跃的**（不再走循环），所以会一直等在那里，直到有人把它们放回去。")
                appendLine("理由里有它此刻在哪儿、以及它想回去的那一格在哪儿——**这两处不必是同一个地方**。")
                appendLine()
                appendLine("而如果是**它回不去了**（被锁在别处、被带走了、那一片已经不是它能去的地方），")
                appendLine("那也有一条正当的写法：**把它的作息写成就地过日子**——每一格的坐标就用它此刻在的")
                appendLine("那个地方（理由里给了），作息照常按时辰轮转。那样它就回到了自己的日子上，只是那")
                appendLine("一日子发生在它现在所在的地方。**别写它到不了的坐标**：写下去只会让它再卡一次。")
                appendLine()
                why.why.forEach { appendLine("- $it") }
                appendLine()
                append(thinkingBriefing(world, waking, focus, emptyList()))
            }
        }

        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("【上一次没干成，请修正】")
            notes.forEach { appendLine("- $it") }
        }
    }

    /**
     * 思考档该看的东西。**换档之后也补这一段**——快速档那一段里没有它们，而换档不该意味着"少看了一半
     * 世界就开始改"。
     */
    private fun thinkingBriefing(
        world: World,
        waking: Waking,
        focus: Entity,
        scheduled: List<TimelineEntry>,
    ): String = buildString {
        appendLine("【你在哪一档】")
        appendLine("思考档。你要动手了。")
        appendLine()

        appendLine("【${focus.label}身边的那一片】")
        append(RegionView.render(world, RegionBuilder.of(world, listOf(waking.focusId)), waking.focusId))
        appendLine()

        val player = world.chunkOfEntity(waking.focusId)
        if (player != null) {
            appendLine("【地图】")
            appendLine(ChunkView.render(world, waking.origin, player))
            appendLine()
        }

        appendLine("【时间轴上已经排了的】")
        appendLine(timelineListing(world, scheduled))
        appendLine()

        val people = visibleTo(world, waking.focusId).mapNotNull { world.byId(it) }
            .filter { it.kind == CHARACTER_KIND }
            .sortedBy { it.id }
        appendLine("【你这一轮看得见的角色（能写作息、也能写人设的，也就是这些人）】")
        appendLine(
            if (people.isEmpty()) {
                "（一个都看不见）"
            } else {
                people.joinToString("\n") {
                    val cycling = if (it.cycle.isEmpty) "没有作息" else "有作息（${it.cycle.beats.size} 格）"
                    // **卡要连标题一起印出来**（§14.52）：`write_card` 是**按标题改**的，不列标题它就
                    // 只能瞎猜一个——而猜错的后果是"在原来那张旁边又添一张互相矛盾的卡"。
                    val persona = if (it.cards.isEmpty()) "不是角色（没有卡）" else "人设：${it.cards.titlesWithSize()}"
                    "- ${it.id}｜${it.label}　$cycling｜$persona"
                }
            },
        )
        appendLine()

        appendLine("【你要做的】")
        appendLine(
            "看这一片该不该长、将来该发生什么、谁的日子还没有形状、这一带是不是还缺一个**该有名字的人**。" +
                "**没有非做不可的事时，什么都不做是允许的。**",
        )

        // **这一次非做不可的那件事**（§14.45）。放在最后，因为它压过上面那句——那个人正卡在门口，
        // 而它此刻的一切都要等这一份新作息。
        val stuck = (waking.why as? Wake.Stuck)?.required.orEmpty()
        if (stuck.isNotEmpty()) {
            appendLine()
            appendLine("【这一次必须处理的人】")
            stuck.forEach { id ->
                val who = world.byId(id)
                appendLine("- ${who?.label ?: id}（id 是「$id」）此刻在 (${who?.x}, ${who?.y}, ${who?.z})")
            }
            appendLine("**他们各要一份新的 `write_cycle`。** 别的地方照旧：这一带该怎么长、将来该发生")
            appendLine("什么，仍然由你看。")
        }

        // **人设追加**（§14.52）。它是这一档被叫起来时的另一件事：有个人刚从**氛围角色变成活跃角色**
        // ——世界接下来会对它多要求一些（问它这一带有什么、托付它一条支线、把它升成剧情角色），而开局
        // 那张简单的卡可能不够用。**它不点名就没人管**，所以理由里点了名的那个要再出现一次。
        val about = (waking.why as? Wake.Called)?.aboutId
        if (about != null) {
            val who = world.byId(about)
            appendLine()
            appendLine("【这一次要看的人】")
            appendLine("- ${who?.label ?: about}（id 是「$about」）")
            appendLine("它现在的人设卡：${(who?.cards ?: emptyList()).titlesWithSize()}")
            appendLine("**上面这些标题里已经有的，就是那张卡**——想改哪一面，标题照着写；")
            appendLine("写一个新标题就是给它添一张。**要补的是背景（它是谁、它知道什么），不是它这一趟")
            appendLine("经历了什么**——那些事本来就在它自己的上下文里。")
        }
    }

    /**
     * 离内圈最近的空地还有几块远。
     *
     * 这是快速档唯一拿到的那一个数（§14.33）。它刻意**不是坐标**：快速档这一刀没有生成能力，知道
     * 坐标只会诱惑它去试试。它要判的只是"近不近"。
     */
    private fun nearestLine(world: World, waking: Waking): String {
        val player = world.chunkOfEntity(waking.focusId) ?: return "（读不出玩家在哪一块上）"
        val near = nearestMissing(world, player) ?: return "缓冲圈里没有空地了。"
        return "还有 ${near.chunksAway} 块远，离内圈边缘 ${near.beyondHard} 块。" +
            "（内圈就是玩家非有地形不可的那一圈。）"
    }

    private fun timelineListing(world: World, scheduled: List<TimelineEntry>): String {
        val all = world.timeline.all()
        if (all.isEmpty() && scheduled.isEmpty()) return "（还没有排过任何事）"
        // **按时刻排序**（[io.github.k1midi0.infinitesimal.world.Timeline.all] 已经排好），
        // 因为导演要判的是"这几件事之间的先后合不合理"。
        val done = scheduled.joinToString("\n") { "- ${it.dueAt}　${it.what}（id=${it.id}）" }
        val already = all.joinToString("\n") { "- ${it.dueAt}　${it.what}（id=${it.id}）" }
        return when {
            scheduled.isEmpty() -> already
            all.isEmpty() -> "这一次刚排下的：\n$done"
            else -> "$already\n这一次刚排下的：\n$done"
        }
    }

    /** 换档那一下的答复。**它是模型唯一能知道自己换了档的地方**（终点那边只换参数，不说话）。 */
    private fun switchedTo(world: World, waking: Waking, call: ToolCallRequest): String {
        val why = decodeCall(WakeArgs.serializer(), call)?.why?.trim().orEmpty()
        lastTier = Tier.THINKING
        return buildString {
            append("好，换成思考档了。")
            if (why.isNotEmpty()) append("你给的理由是：「$why」。")
            appendLine()
            append("现在你手里的是：")
            append(DirectorTools.of(Tier.THINKING).joinToString("、") { it.function.name })
            append("。下面补上你这一档该看的东西。")
            if (waking.why is Wake.HardGap) {
                append("**注意**：这一次是代码发现玩家正走进一片还没有地形的地方，" +
                    "必需的那几块必须写进地形。")
            }
        }
    }

    private companion object {

        /**
         * 一个来回 = 一次请求。**它不是失败计数**——排十件事、长两三块地就要十来个来回，那是正常进度。
         *
         * 与建模那边同一条理由（附录 A.14）：把"错了几次"与"问了多少来回"混成一个数字，长东西会被
         * 判成"排不出来"，而它其实一次都没排错。
         *
         * 比排时间轴那一版（12）宽，因为这一版还要走生成——而生成自己另有一层回合循环，导演这一层
         * 通常只看到它的结果。
         */
        const val MAX_ROUNDS = 24

        /** 一次 `look_map` 最多看几块。**排版进得去、token 出得来**比看得全要紧。 */
        const val LOOK_MAP_LIMIT = 400

        /**
         * 导演的 system，**逐字不变**。
         *
         * 它是这一层唯一该命中缓存的那一段（[SystemId.DIRECTOR] 的注释）。所以"现在是第几天""身边有
         * 什么"这类每回合都在变的东西一律放在 user 消息里，这里只写"你是谁、你的权限边界、规矩"。
         */
        val SYSTEM = """
            你是一个世界的导演。

            你有两档，同一次工作里可能会换档：
            · **快速档**看着。玩家每做完一个动作，你被叫起来一次，判断该不该动手。你**只有一只手**：
              唤醒思考档。你自己不能改任何东西。
            · **思考档**干活。你只有五只手：排时间轴、长地形与放东西、看地图、给一个角色写它的作息、
              放一个有名字的人出来。

            **换档不是换人。** 同一次工作里你从头到尾是同一个你，只是快的那一档不该做慢的那一档的判断。

            **规矩**
            1. 先看，再决定。**大多数时候什么都不用做**——玩家只是在走路、在说话，那不是要你动手的
               信号。安静下来（不调用任何工具）就是"我看过了，不用动"。
            2. 什么时候值得把思考档叫起来：玩家正走进一片**还没有地形**的地方；这一带**该有点东西**了
               （村口那棵树、路边的石头）；或者你要排的事需要先有舞台。叫的时候用一句话说清理由。
            3. 排时间轴：一件事调一次 `schedule`。每件排下的事都必须有一个到期时刻，否则快进时它只能
               被静默地漏掉。
            4. 一件件事都讲「什么时候、谁、做什么」，例如「酒馆的矮人把最后一桶酒搬进地窖」。
            5. id 自己起，看得出是什么就行。**同一个 id 再排一次等于改主意**，所以一批里不要重复用。
            6. **已经过去的时刻排不出事。** 现在是第几天几点，看【现在】那一行。
            7. 长地形要给一整块地：`grow_terrain` 的坐标抄地图上的数轴。**缓冲圈以外不归你长**，
               填了会被驳回。
            8. 点名要放的东西是**地标，不是清单**：一片森林里的每一棵树由生成器顺手放，你只需要点出
               村口那棵老槐树。
            9. 你排的是将来，里面出现的人或东西**现在可以还不存在**——那是等它到点的时候再补的。
            10. 写作息（`write_cycle`）写的是**没人理那个角色的时候，它在干什么、在哪儿**。时间填**从循环
                开始那一刻数起的分钟**（0 就是第一瞬间），坐标填**厘米**、拄着你眼前那段清单里的数，第三段
                一句话写它在做什么。**至少两格、时刻一格比一格晚、两份不能落在同一分钟**——一圈的长度由
                **最后一格**定（它那一刻起再过一分钟就是下一圈），所以要把一整圈写满，最后一格落在它收工
                那一刻。**一次写完整圈**：旧的那一圈会被整张换掉，只写一半等于给它一个错的答案。只能写给你
                **这一轮看得见的人**。
            11. 干完了就停手：**不要再调用任何工具**。你的沉默就是你「干完了」。
            12. 有一种时候你被叫起来，是因为**有人脱离了它自己的日子**。**两种情形共用这一段**，而
                【为什么把你叫起来】会写明是哪一种：① **它到了作息上那一格却没进得去**（那一格现在
                站不住、或者那个地方已经不成样子了）——它会被从日子里拿下来，停在那一格的门口等着；
                ② **它离开自己的日子太久了**（被锁起来、被带走、回不去）——它停在**别的地方**，而且
                会一直等下去。这种时候**你有非做不可的事**：用 `write_cycle` 给名单上那几个人**各写
                一份新的作息**，把那一格换成做得了的地方或者做得了的时候——为什么不行，写在【为什么
                把你叫起来】的理由里。**别去碰名单以外的人**。
            13. 这一带缺一个**该有名字的人**时，用 `spawn_character` 把它叫出来：id、名字、它的卡
                （一行一张「标题｜内容」，第一张通常是身份）、它站在哪儿（厘米，抄你眼前那段清单里的
                坐标）。**要不要给它作息由你定**：想让它在没人理的时候也自己过日子，就写 `beats`
                （至少两格，第一格必须就是它站的那个位置）；不写，它就待在原地，只有被叫到的时候才动。
                **它不是摆道具的**：一片地上的灌木、碎石、枯树该由 `grow_terrain` 铺地时长出来，
                **不要在这里一件一件地放**。**也别拿它凑人数**——一条街上该有三五个人是长地形时顺手
                放的，按名字叫出来的该是这一个故事用得着的那一个。**已经有的 id 会被驳回**：改它现在
                的日子是 `write_cycle` 的事。
            14. 有一个人**刚从氛围角色变成活跃角色**时（【为什么把你叫起来】会点名是哪一个），你要判的
                是**它现在这一份人设够不够**：世界接下来会对它多要求一些——有人问它这一带有什么、
                要托付它一条支线、把它升成剧情角色，而开局那张简单的卡可能不够用（它其实是传奇铁匠的
                传人、它知道哪条路、它跟镇上谁有旧账）。不够就用 `write_card` **补背景**。**按标题
                改**：一行一张「标题｜内容」，标题写它现在已有的那个就是**替换那一张**（它现在有哪些
                标题，写在你能看见的那份清单里），写一个新标题就是**添一张**。**它这一趟经历了什么
                不用你写**——那些事本来就在它自己的上下文里；人设要补的是它本来该有、而开局那张卡没
                写的那一层。**够用就什么都不写**（那是一个合法的结果）。两条上限（一张 400 字、合计
                2000 字）到顶时，**改写或合并现有的那几张**，别只往上添——卡是它每次醒来都要全文读
                一遍的东西。
        """.trimIndent()
    }
}

/** [DirectorTools.WAKE_THINKING] 的参数。 */
@Serializable
internal data class WakeArgs(val why: String = "")

/** [DirectorTools.GROW_TERRAIN] 的参数。 */
@Serializable
internal data class GrowArgs(
    val requirement: String = "",
    val chunks: String = "",
    val placements: String = "",
)

/** [DirectorTools.LOOK_MAP] 的参数。**显示坐标**，四个都要填。 */
@Serializable
internal data class LookMapArgs(
    val x1: Int = 0,
    val y1: Int = 0,
    val x2: Int = 0,
    val y2: Int = 0,
)

/** [DirectorTools.SCHEDULE] 的参数。**全部有默认值**，所以缺字段只会退化成一次看得懂的失败。 */
@Serializable
internal data class ScheduleArgs(
    val id: String = "",
    val day: Int = 0,
    val hour: Int = 0,
    val minute: Int = 0,
    val what: String = "",
)

/** [DirectorTools.WRITE_CYCLE] 的参数。 */
@Serializable
internal data class WriteCycleArgs(
    val id: String = "",
    val beats: String = "",
)

/**
 * [DirectorTools.SPAWN_CHARACTER] 的参数。
 *
 * `beats` 空着是**合法**的（剧情角色），所以它没有 "必填" 这一说；其余四个字段缺了都会退化成一次
 * 看得懂的失败（一律有默认值，与 [ScheduleArgs] 同一条纪律）。
 */
@Serializable
internal data class SpawnCharacterArgs(
    val id: String = "",
    val label: String = "",
    val cards: String = "",
    val at: String = "",
    val beats: String = "",
)

/** [DirectorTools.WRITE_CARD] 的参数。 */
@Serializable
internal data class WriteCardArgs(
    val id: String = "",
    val cards: String = "",
)
