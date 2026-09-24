package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.RulebookView
import io.github.k1midi0.infinitesimal.llm.AssistantTurn
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.ToolCallRequest
import io.github.k1midi0.infinitesimal.llm.Usage
import io.github.k1midi0.infinitesimal.llm.decodeCall
import io.github.k1midi0.infinitesimal.spatial.RegionBuilder
import io.github.k1midi0.infinitesimal.spatial.RegionView
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.TimelineEntry
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlinx.coroutines.flow.toList

/** 排一次时间轴的结果。 */
sealed interface Arrangement {

    /** 排上了这些，**它们已经在时间轴上了**。 */
    data class Done(val entries: List<TimelineEntry>, val attempts: Int) : Arrangement

    /**
     * 试了 [attempts] 次，一条都没排成。
     *
     * [problems] 是最后一轮的理由，**原样留着**——它是日志里唯一能分辨"模型没说话""参数一直写错"
     * "它觉得无事可排"的东西（§1.5 第三条：驳回理由不撒谎）。
     */
    data class FailedProtocol(val problems: List<String>, val attempts: Int) : Arrangement
}

/**
 * 导演的模型段：**安排将来会发生什么**（§8）。
 *
 * ## 它为什么是思考档，而且是全程思考
 *
 * §8.2 把两个触发器分得很清楚：**时间轴到期由代码检测**（确定性、可复现，见
 * [io.github.k1midi0.infinitesimal.session.FastForward]）；**剧情跑偏由快速模型发现，而它不能改
 * 时间轴**——改时间轴只有思考档能做。这一层做的正是"改时间轴"，所以它是思考档，没有降档的余地。
 *
 * 用户对这件事的裁定是直白的：**不要限制思考，防止逻辑出错，包括导演系统同理**（§12 第 16 项）。
 * 所以这里**不设 `max_tokens`**——发 null 就是不发这一项，端点用思考档默认的 64K。任何一个小数字
 * 都会把想得久的那一次截断，而症状是"它什么都没排出来"，与"它觉得无事可排"长得一模一样（§14.19）。
 *
 * ## 它的权限是「只能追加」
 *
 * §1.4：**快速档只能追加，只有思考档能改写。** 导演是思考档，所以它**应该**能改写时间轴——但这一版
 * 只给了它追加（`schedule`）。理由是当下没有可改写的东西：开局时间轴是空的（§12 第 19 项）。
 * 等"偏离检测 → 重规划"那条路进来，`unschedule` 与"改某一条的到期时刻"才有真实需要。
 *
 * ## 它读的东西
 *
 * 世界规则与发起者的规则/属性、**发起者身边的那一片**（复用判定那套空间框）、时间轴上已经排了什么。
 * 最后一样是它唯一需要"看见自己上次干了什么"的地方——没有它，每一次调用都会从零开始重排一遍，
 * 而重排出来的会与上一次对不上。
 */
class Director(
    private val client: LlmClient,
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
        maxAttempts: Int = 2,
        effort: ReasoningEffort = ReasoningEffort.HIGH,
    ) : this(LlmClient(config.copy(userId = SystemId.DIRECTOR)), maxAttempts, effort)

    /** 整场跑掉的模型调用。与判定那个分开数，是为了让"哪一层烧了钱"看得出来。 */
    var requestCount = 0
        private set

    var lastUsage: Usage? = null
        private set

    var lastFinishReason: String? = null
        private set

    /**
     * 让导演排出**接下来该发生的事**。
     *
     * [focusId] 是它看世界时站在谁那儿——现在只有玩家一个人，所以调用方传的是玩家。等 NPC 进来，
     * 同一个函数还会被用来给某个 NPC 排日程，那时它站在那个 NPC 那儿。
     */
    suspend fun arrange(world: World, focusId: String): Arrangement {
        val focus = world.byId(focusId)
            ?: return Arrangement.FailedProtocol(listOf("世界里没有 id 是「$focusId」的东西。"), 0)

        var notes = emptyList<String>()
        var lastProblems = listOf("一次都没试。")
        var attempts = 0

        while (attempts < maxAttempts) {
            attempts++
            val taken = arrangeOnce(world, focus, focusId, notes)

            if (taken.entries.isEmpty()) {
                // **一件都没排 = 什么都没安排**，不是一次成功的安排。它多半是"想说的没说"或"被截断"，
                // 两种情况都该重来一次，而不是把一次空转当成结果收下（§8.1）。
                lastProblems = listOf(
                    "一件都没排。至少排一件——一件都没排等于什么都没安排。",
                )
                notes = lastProblems
                continue
            }

            when (val planned = TimelinePlanner.apply(world, taken.entries)) {
                is Planned.Done -> return Arrangement.Done(planned.entries, attempts)
                is Planned.Rejected -> {
                    lastProblems = planned.problems
                    notes = planned.problems
                }
            }
        }

        return Arrangement.FailedProtocol(lastProblems, attempts)
    }

    // ── 一次安排 ───────────────────────────────────────────────────────────────────

    /** 一次安排跑出来的东西。**诊断信息一并带着**，否则失败了只剩下一句"没排成"。 */
    private class Taken(
        val entries: List<TimelineEntry>,
        val reply: String,
        val finishReason: String?,
        val usage: Usage?,
    )

    private suspend fun arrangeOnce(
        world: World,
        focus: Entity,
        focusId: String,
        notes: List<String>,
    ): Taken {
        val messages = mutableListOf(
            ChatMessage.system(SYSTEM),
            ChatMessage.user(userPrompt(world, focus, focusId, notes)),
        )
        val entries = mutableListOf<TimelineEntry>()

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
                    tools = DirectorTools.ALL,
                    thinking = ThinkingRequest.of(ThinkingMode.Always, effort),
                    // **不设 `max_tokens`。** 见类注释：思维链也算在它里面。
                    maxTokens = null,
                ).toList(),
            )

            reply = turn.content
            finish = turn.finishReason
            usage = turn.usage
            if (round == 1) lastFinishReason = finish
            lastUsage = usage

            // **一个工具都没调 = 它说排完了。** 这是这一层与判定那一层唯一一处不同的循环规则：
            // 判定是"这一轮没有查询手就收工"，导演是"这一轮没有手就收工"——因为它排的事是要一件一件
            // 排下去的（§8.1 那张表本来就是多行）。
            if (turn.calls.isEmpty()) break

            messages += turn.asAssistantMessage()
            for (call in turn.calls) {
                messages += ChatMessage.toolResult(call.id, answerFor(call, world.clock, entries))
            }
        }

        return Taken(entries, reply, finish, usage)
    }

    /** 一只手的答复。**解不开的参数与不合法的值都变成文字退回去**，不抛异常（§1.3）。 */
    private fun answerFor(
        call: ToolCallRequest,
        now: WorldTime,
        into: MutableList<TimelineEntry>,
    ): String = when (call.name) {
        DirectorTools.SCHEDULE -> schedule(call, now, into)
        else -> "没有叫「${call.name}」的工具。你能用的只有：" +
            DirectorTools.ALL.joinToString("、") { it.function.name } + "。"
    }

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

    // ── 提示词 ─────────────────────────────────────────────────────────────────────

    private fun userPrompt(
        world: World,
        focus: Entity,
        focusId: String,
        notes: List<String>,
    ): String = buildString {
        appendLine("【现在】")
        appendLine(world.clock.toString())
        appendLine()

        // 世界规则 + 发起者那份。与判定共用同一段渲染——同一份依据在两个系统里读起来不一样，
        // 迟早会让其中一个按错误的优先级行事。
        val rules = RulebookView.forPrefix(world, focusId)
        if (rules.isNotEmpty()) {
            appendLine(rules)
        }

        appendLine("【${focus.label}身边的那一片】")
        append(RegionView.render(world, RegionBuilder.of(world, listOf(focusId)), focusId))
        appendLine()

        appendLine("【时间轴上已经排了的】")
        appendLine(timelineListing(world))
        appendLine()

        appendLine("【你要做的】")
        appendLine("把接下来一两天里会发生的事排出来。")

        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("【上一次没排成，请修正】")
            notes.forEach { appendLine("- $it") }
        }
    }

    private fun timelineListing(world: World): String {
        val all = world.timeline.all()
        if (all.isEmpty()) return "（还没有排过任何事）"
        // **按时刻排序**（[io.github.k1midi0.infinitesimal.world.Timeline.all] 已经排好），
        // 因为导演要判的是"这几件事之间的先后合不合理"。
        return all.joinToString("\n") { "- ${it.dueAt}　${it.what}（id=${it.id}）" }
    }

    private companion object {

        /**
         * 一个来回 = 一次请求。**它不是失败计数**——排十件事就要十来个来回，那是正常进度。
         *
         * 与建模那边同一条理由（附录 A.14）：把"错了几次"与"问了多少来回"混成一个数字，长东西会被
         * 判成"排不出来"，而它其实一次都没排错。
         */
        const val MAX_ROUNDS = 12

        /**
         * 导演的 system，**逐字不变**。
         *
         * 它是这一层唯一该命中缓存的那一段（[SystemId.DIRECTOR] 的注释）。所以"现在是第几天""身边有
         * 什么"这类每回合都在变的东西一律放在 user 消息里，这里只写"你是谁、你的权限边界、规矩"。
         */
        val SYSTEM = """
            你是一个世界的导演。

            你安排**将来会发生什么**：什么时候、谁、做什么。你排下的东西是**意图**——到点了才会有人来
            处理它，那时候才决定它具体怎么发生。

            **你只能做一件事：往时间轴里放意图。**
            · 你不能改世界：挪动一个东西、改一个属性、弄坏什么，都不是你的事。
            · 你不能写字数，也不能改任何数值。
            · 你排的是「将来要发生」，不是「现在就发生」。

            每一件排下的事都**必须有一个到期时刻**。这条是硬的：没有到期时刻的事，快进的时候就无从判断
            它该不该发生，于是它只能被漏掉——而且是静默地漏掉。

            **规矩**
            1. 一件事调一次工具，排在时间轴上。
            2. 一句话说清「什么时候、谁、做什么」，例如「酒馆的矮人把最后一桶酒搬进地窖」。
            3. id 自己起，看得出是什么就行。**同一个 id 再排一次等于改主意**，所以一批里不要重复用。
            4. **已经过去的时刻排不出事。** 现在是第几天几点，看【现在】那一行。
            5. 你排的是将来，所以里面出现的人或东西**现在可以还不存在**——那是等它到点的时候再补的。
            6. 就近安排：先把接下来一两天里的事排出来。太远的事排了也会被改。
            7. 排完了就停手：**不要再调用任何工具**。你的沉默就是你「排完了」。
            8. **至少排一件。** 一件都没排等于什么都没安排，那不是一次成功的安排。
        """.trimIndent()
    }
}
