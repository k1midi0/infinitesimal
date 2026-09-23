package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.LlmEvent
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一次动作的完整输入。
 *
 * [intention] 是**玩家原话**，不做任何解析——因为动作不能穷尽（§4.2），任何"先解析成结构化动作、
 * 再判定"的设计都会在那一步丢掉它理解不了的东西。解析与判定是**同一次调用**：它们依赖同一份情境。
 */
data class JudgeRequest(
    val actorId: String,
    val actorLabel: String,
    val intention: String,
    /** 玩家多选的对象。一次提交 = 一个意图，可以作用于多个对象（§4.1）。 */
    val selected: List<String>,
    /** 地点背景 + 同一地点里有什么。由 [Scene] 拼好——判定不自己去猜世界长什么样。 */
    val scene: String,
)

sealed interface Verdict {
    /** **世界内驳回**：模型判定它在情境下不成立。理由原样给玩家看。 */
    data class Rejected(val reason: String) : Verdict

    /** 通过：这些后果等着被校验与提交。 */
    data class Proposed(val consequences: List<Consequence>, val affected: List<String>) : Verdict

    /**
     * 模型既没提议后果、也没驳回。
     *
     * **这不是"世界内驳回"。** 它是协议上的空转，该退回重试；重试还不行就当一次失败，
     * 但**绝不能告诉玩家"你的操作不合理"**（§4.3）——那是我们这边坏了，不是他的错。
     */
    data class Empty(val note: String) : Verdict

    /** 协议内失败：模型吐出来的东西根本没法当成后果。同样**不示给玩家**。 */
    data class Malformed(val problems: List<String>) : Verdict
}

/** 一次判定的最终结果。 */
sealed interface Judgment {
    data class Applied(
        val consequences: List<Consequence>,
        val affected: Set<String>,
        val attempts: Int,
    ) : Judgment

    /** 世界内驳回。**理由给玩家看。** */
    data class RejectedWorld(val reason: String, val attempts: Int) : Judgment

    /** 我们这边没救回来。**不示给玩家**，记日志、当这次动作没发生。 */
    data class FailedProtocol(val problems: List<String>, val attempts: Int) : Judgment
}

/**
 * 判定系统：**模型段**。
 *
 * 一次调用产出三样东西——合不合理、后果清单、驳回理由（§4.2）。**它们不能是三次调用**："想干什么"
 * 与"能不能成立"依赖同一份情境，拆开就是两次延迟、两次成本，中间还多出一个没人消费的半成品状态。
 *
 * 用**思考档**：整套设计把"物理推理"整个交给了模型（附录 A.4——现实无法用代码模拟），这里就是那件
 * 事发生的地方，也是全系统里最该让它想清楚的一处。
 *
 * 但它的**写权限是零**：模型只能调工具**提议**后果，落库一律经 [Committer]（§1.2、§1.3）。
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

    var requestCount = 0
        private set

    /**
     * 问一次、校验、提交；**协议内失败就带着问题重试**（§4.3）。
     *
     * 重试的边界是 [maxAttempts] 次模型调用。宁可这次动作什么都不发生，也不要写进去一半。
     */
    suspend fun judgeAndCommit(world: World, request: JudgeRequest): Judgment {
        val problems = mutableListOf<String>()

        repeat(maxAttempts) { attempt ->
            val verdict = ask(world, request, problems.toList())
            when (verdict) {
                is Verdict.Rejected -> return Judgment.RejectedWorld(verdict.reason, attempt + 1)

                is Verdict.Empty -> problems += "模型没有提议任何后果，也没有驳回（${verdict.note}）"

                is Verdict.Malformed -> problems += verdict.problems

                is Verdict.Proposed -> when (
                    val result = Committer.apply(world, verdict.consequences, verdict.affected)
                ) {
                    is Committer.Applied ->
                        return Judgment.Applied(result.consequences, result.affected, attempt + 1)

                    is Committer.Rejected -> problems += result.problems
                }
            }
        }

        return Judgment.FailedProtocol(problems, maxAttempts)
    }

    /**
     * 只问模型，不落库。
     *
     * [retryNotes] 是上一次尝试被代码段拒绝的原因。把它交回去，比让模型自己猜要好——**这些是结构性
     * 问题**（id 不存在、新 id 撞了），它能改；而放它不管只会让它反复犯同一个错。
     */
    suspend fun ask(world: World, request: JudgeRequest, retryNotes: List<String> = emptyList()): Verdict {
        requestCount++
        val events = client.streamChat(
            messages = listOf(
                ChatMessage.system(SYSTEM),
                ChatMessage.user(userPrompt(request, retryNotes)),
            ),
            tools = JudgeTools.ALL,
            thinking = ThinkingRequest.of(ThinkingMode.Always, ReasoningEffort.HIGH),
            // 一次判定只该吐出几条工具调用。不给上限的话，思考档的默认输出预算是 64K——
            // 偶发的一轮长篇大论会把玩家的这一回合拖住（§8.3）。
            maxTokens = 2_000,
        ).toList()

        val calls = collectCalls(events)
        if (calls.isEmpty()) {
            val finish = events.filterIsInstance<LlmEvent.Finished>().lastOrNull()?.reason
            return Verdict.Empty("finish_reason=$finish")
        }

        val consequences = mutableListOf<Consequence>()
        val affected = mutableListOf<String>()
        var rejection: String? = null

        for (call in calls) {
            when (val parsed = parse(call)) {
                is Parsed.Effect -> consequences += parsed.consequence
                is Parsed.Affects -> affected += parsed.actor
                is Parsed.Rejects -> rejection = parsed.reason
                is Parsed.Bad -> return Verdict.Malformed(listOf(parsed.problem))
            }
        }

        // **驳回与后果不能并存。** 它是一条结构性的矛盾，代码验得了——而"默默地选一边"正是那种
        // 会把 bug 藏起来的事：到底该按驳回算还是按后果算，两边都自洽，谁都不会报错。
        if (rejection != null && consequences.isNotEmpty()) {
            return Verdict.Malformed(
                listOf("同时给了驳回理由又提议了 ${consequences.size} 条后果；这两个是互斥的，要哪一个"),
            )
        }
        rejection?.let { return Verdict.Rejected(it) }

        if (consequences.isEmpty()) {
            return Verdict.Malformed(listOf("只说了影响谁，没有说要发生什么"))
        }
        return Verdict.Proposed(consequences, affected)
    }

    private fun userPrompt(request: JudgeRequest, retryNotes: List<String>): String = buildString {
        appendLine("【情境】")
        appendLine(request.scene)
        appendLine()
        appendLine("【动作发起者】")
        appendLine("id=${request.actorId}，名字=${request.actorLabel}")
        appendLine()
        appendLine("【动作】")
        appendLine(request.intention)
        if (request.selected.isNotEmpty()) {
            appendLine()
            appendLine("【选中的对象】")
            request.selected.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("【怎么做】")
        appendLine("1. 先判断这次动作在这个情境下成不成立。不成立就调 ${JudgeTools.REJECT}，理由写成这个世界会说的话。")
        appendLine("2. 成立就用工具列出它造成的**全部**后果——包括不显眼但确实发生的（桌上的东西跟着滑动、火把脱手落地）。")
        appendLine("3. 被这次动作影响到的角色，每个调一次 ${JudgeTools.AFFECT}。发起者本人不用记。")
        appendLine("4. 只列这次动作确实造成的后果。不要顺手整理世界。")
        appendLine("5. 一次动作可能同时作用于多个被选中的对象，每个对象各自的后果都要给。")
        if (retryNotes.isNotEmpty()) {
            appendLine()
            appendLine("【上一次尝试被拒绝了，请修正】")
            retryNotes.forEach { appendLine("- $it") }
        }
    }

    private data class RawCall(val name: String, val arguments: String)

    /** 按 index 把流式的工具调用片段拼起来。 */
    private fun collectCalls(events: List<LlmEvent>): List<RawCall> {
        val names = mutableMapOf<Int, String>()
        val arguments = mutableMapOf<Int, StringBuilder>()
        for (event in events) {
            when (event) {
                is LlmEvent.ToolCallStarted -> names[event.index] = event.name
                is LlmEvent.ToolCallArguments ->
                    arguments.getOrPut(event.index) { StringBuilder() }.append(event.fragment)
                else -> Unit
            }
        }
        return names.keys.sorted().map { index ->
            RawCall(names.getValue(index), arguments[index]?.toString().orEmpty())
        }
    }

    private sealed interface Parsed {
        data class Effect(val consequence: Consequence) : Parsed
        data class Affects(val actor: String) : Parsed
        data class Rejects(val reason: String) : Parsed
        data class Bad(val problem: String) : Parsed
    }

    private fun parse(call: RawCall): Parsed {
        fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, build: (T) -> Parsed): Parsed = try {
            build(JSON.decodeFromString(serializer, call.arguments))
        } catch (cause: Throwable) {
            Parsed.Bad("${call.name} 的参数不是合法 JSON：${call.arguments.take(160)}")
        }

        return when (call.name) {
            JudgeTools.MOVE -> decode(MoveArgs.serializer()) {
                Parsed.Effect(Consequence.Move(it.id, it.x, it.y, it.z))
            }

            JudgeTools.REPARENT -> decode(ReparentArgs.serializer()) {
                Parsed.Effect(Consequence.Reparent(it.id, it.parent.ifBlank { null }))
            }

            JudgeTools.SET_ATTRIBUTE -> decode(SetAttributeArgs.serializer()) {
                Parsed.Effect(Consequence.SetAttribute(it.id, it.key, it.value))
            }

            JudgeTools.CHANGE_APPEARANCE -> decode(IdArgs.serializer()) {
                Parsed.Effect(Consequence.ChangeAppearance(it.id))
            }

            JudgeTools.DESTROY -> decode(IdArgs.serializer()) {
                Parsed.Effect(Consequence.Destroy(it.id))
            }

            JudgeTools.SPAWN -> decode(SpawnArgs.serializer()) {
                Parsed.Effect(Consequence.Spawn(it.toEntity()))
            }

            JudgeTools.REPLACE -> decode(ReplaceArgs.serializer()) {
                Parsed.Effect(Consequence.Replace(it.oldId, it.toEntity()))
            }

            JudgeTools.AFFECT -> decode(AffectArgs.serializer()) { Parsed.Affects(it.actor) }

            JudgeTools.REJECT -> decode(RejectArgs.serializer()) { Parsed.Rejects(it.reason) }

            else -> Parsed.Bad("不认识工具 ${call.name}")
        }
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        val SYSTEM = """
            你是一个世界的裁定者。

            你只做两件事：判断一次动作在这个世界里成不成立，以及列出它造成的后果。你不描写、不叙事、不对话——那些是别人的事。

            后果只能通过工具给出。**你没有直接修改世界的能力。**
        """.trimIndent()
    }
}

// ── 工具参数。字段刻意与线上名字一一对应，改名要同时改 JudgeTools 的 schema ────────────────────

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

/**
 * 给模型的地图视图（附录 A.2 的"投影 B"）。
 *
 * 它只给**同一个地点里的东西**与**地点背景**——不是整张地图。而且它**不给坐标**：实测里把坐标发给
 * 渲染器，它就写位置关系（§7.6）。判定确实需要知道空间关系才能判断"够不够得着"，但那是**玩家自己在
 * 动作里说的**，不是我们塞给它的。
 */
object Scene {

    fun of(world: World, actorId: String, extraNote: String? = null): String = buildString {
        val background = world.backgroundChainOf(actorId)
        if (background.isNotEmpty()) {
            appendLine("【所在】")
            background.forEach { appendLine(it) }
        }

        val root = rootOf(world, actorId)
        val here = world.all().filter { rootOf(world, it.id) == root }
        appendLine()
        appendLine("【这里有什么】")
        here.forEach { appendLine(describe(world, it)) }

        if (extraNote != null) {
            appendLine()
            appendLine(extraNote)
        }
    }.trimEnd()

    private fun describe(world: World, entity: Entity): String = buildString {
        append("- id=${entity.id}｜${entity.label}（${entity.kind}）")
        // **必须标出"谁挂在谁下面"**：不标的话，判定看不出"桌上的东西"是哪几件——实测里它因此
        // 漏掉了桌上的第二样东西（只有酒杯被扫下去，油灯还在桌上）。
        //
        // 这与 §7.6 不冲突：那条禁的是**渲染器的输出**里出现位置关系，不是判定看不到归属。
        // 判定要靠归属才能穷尽"这个动作波及了哪些东西"，而穷尽是它的职责（§4.2）。
        entity.parent?.let { parentId ->
            world.byId(parentId)?.let { parent -> append("｜在「${parent.label}」上") }
        }
        if (entity.attributes.isNotEmpty()) {
            append("｜")
            append(entity.attributes.entries.joinToString("，") { "${it.key}=${it.value}" })
        }
        if (entity.sentences.isNotEmpty()) {
            appendLine()
            append("  它看上去是：${entity.text()}")
        }
    }

    /** 沿 parent 链走到根。同一棵树下就算"在同一个地点"。 */
    private fun rootOf(world: World, id: String): String {
        var current = world.byId(id)
        var last = id
        while (current?.parent != null) {
            last = current.parent!!
            current = world.byId(last)
        }
        return last
    }
}
