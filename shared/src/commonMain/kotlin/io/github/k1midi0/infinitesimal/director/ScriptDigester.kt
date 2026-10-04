package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.AssistantTurn
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.ToolDefinition
import io.github.k1midi0.infinitesimal.llm.Usage
import io.github.k1midi0.infinitesimal.llm.decodeCall
import io.github.k1midi0.infinitesimal.llm.text
import io.github.k1midi0.infinitesimal.llm.tool
import io.github.k1midi0.infinitesimal.world.ReferenceScript
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable

/**
 * **拆分**：把一段故事文本拆成若干条概括性的**参考剧本**（§14.55、§8.4）。
 *
 * ## 它是离线的那一层，不是游戏里的一层
 *
 * 它由 `spike/ScriptDigest` 那个工具驱动：读一部小说，按段送进来，把拆出来的东西写进出厂参考剧本库。
 * 它**不碰世界、不碰存档**——产物是一张表，谁拿去用是调用方的事（与 [io.github.k1midi0.infinitesimal.judge.ShapeModeler]
 * 同一个形状：一次独立的工作、自己的 system、自己的缓存池）。
 *
 * ## 一条规矩压倒其余：抛弃角色背景
 *
 * 用户定的原文是「模型总结拆分出**抛弃角色背景等**概括性的参考剧本」。所以这一段的 system 里有一整节
 * 在讲**不许出现具体的人名、地名、家族、门派、头衔**——只留处境与角色位。这不是洁癖：参考剧本要被填进
 * **任何一个**世界，而"卡尔·莫恩是个被逐出师门的铁匠"这种句子只有那一个世界能用。写"一个被逐出师门的
 * 手艺人，他的旧师父还在这一带"，才能填成本世界的矮人、或者玩家明天才遇见的那个人。
 *
 * ## 它判的只有"读得懂吗、写得下吗"
 *
 * 与 [ScriptPlanner] 同一条纪律：**没有一条是"这条剧情好不好"**。代码判三条——三段都写全了没有、
 * 摘要与正文超没超那两档上限、代号撞了没有。撞了不驳回，而是**加一个后缀并且告诉它**（一部小说里
 * 同一件事换个角度讲两遍是常事，为这个退回去重写不值得），所以这里没有"静默改名"那一路。
 */
class ScriptDigester(
    private val client: LlmClient,
    /** 最多**错**几次（与建模那层同名同义：它数的是"这一轮交上来的东西有没有被退回去"）。 */
    private val maxAttempts: Int = 2,
    /** 拆分的思考强度。**也是 HIGH**：这一层的判断力直接决定库里那批素材值不值钱。 */
    private val effort: ReasoningEffort = ReasoningEffort.HIGH,
) {

    var requestCount = 0
        private set

    var lastUsage: Usage? = null
        private set

    /** 最近一次被退回去的那些理由。诊断用：它多半就是"这段提示词教得不够"。 */
    var lastProblems: List<String> = emptyList()
        private set

    sealed interface Result {

        /** 拆出来的这些。**可能是空表**——一整段都在写风景时，一条都没有才是对的答案。 */
        data class Digested(val scripts: List<ReferenceScript>, val rounds: Int) : Result

        /** 一个来回都没交出东西（或者说不出话来）。理由原样留着。 */
        data class Failed(val problems: List<String>) : Result
    }

    /**
     * 拆一段。
     *
     * [taken] 是**库里已经有的代号**：撞上时这一条会拿到一个带后缀的新代号，并且在回给模型的那句话里
     * 说清（它下一次就不会再写同一个标题了）。[from] 是这一段出自哪一部作品，会原样记进每条
     * [ReferenceScript.from]。
     *
     * [label] 只用于提示词里那句"这是第几段"——它让人事后读日志时对得上原文。
     */
    suspend fun digest(text: String, from: String, label: String, taken: Set<String> = emptySet()): Result {
        val produced = LinkedHashMap<String, ReferenceScript>()
        val messages = mutableListOf(
            ChatMessage.system(SYSTEM),
            ChatMessage.user(user(text, from, label, taken + produced.keys)),
        )

        var failures = 0
        var rounds = 0
        var spoke = false

        while (failures < maxAttempts && rounds < MAX_ROUNDS) {
            rounds++
            requestCount++
            val turn = AssistantTurn.of(
                client.streamChat(
                    messages = messages,
                    tools = TOOLS,
                    thinking = ThinkingRequest.of(ThinkingMode.Always, effort),
                    // 与判定、建模同一条：**不设 max_tokens。**思维链也算在它里面，截断的表现是"少拆了
                    // 一条"，而那看起来与"这一段本来就只有这些"一模一样。
                    maxTokens = null,
                ).toList(),
            )
            lastUsage = turn.usage
            spoke = true

            // **一个工具都没调 = 它拆完了。**这是它与建模那层的差别：建模那边"没调工具"是一次失败
            // （它总要交一份形状），而这里**空手是合法的答案**——一段写景的文字里没有任何一条线。
            if (turn.calls.isEmpty()) break

            messages += turn.asAssistantMessage()

            val complaints = mutableListOf<String>()
            for (call in turn.calls) {
                if (call.name != ADD_REFERENCE) {
                    val complaint = "没有 ${call.name} 这只工具。你能用的只有 $ADD_REFERENCE。"
                    complaints += complaint
                    messages += ChatMessage.toolResult(call.id, complaint)
                    continue
                }

                val args = decodeCall<AddArgs>(AddArgs.serializer(), call)
                if (args == null) {
                    complaints += "参数不是合法 JSON：${call.arguments.take(160)}"
                    messages += ChatMessage.toolResult(call.id, complaints.last())
                    continue
                }

                val title = args.title.trim()
                val summary = args.summary.trim()
                val body = args.body.trim()
                val problem = when {
                    title.isEmpty() -> "这一条没有标题。标题是它在清单里被认出来的那一样东西。"
                    summary.isEmpty() ->
                        "「$title」没有概括。**概括才是常驻提示词的那一段**——它决定导演在那个世界里" +
                            "看不看得见这一条。"

                    body.isEmpty() -> "「$title」没有骨架。骨架是这条线的全部内容。"
                    summary.length > MAX_SUMMARY_CHARS ->
                        "「$title」的概括太长了（${summary.length} 字，最多 $MAX_SUMMARY_CHARS）——它是一句话，" +
                            "不是骨架的缩写。"

                    body.length > MAX_BODY_CHARS ->
                        "「$title」的骨架太长了（${body.length} 字，最多 $MAX_BODY_CHARS）。太长的那一条通常" +
                            "是两三件事写在一起了：把它们拆成两条。"

                    else -> null
                }
                if (problem != null) {
                    complaints += problem
                    messages += ChatMessage.toolResult(call.id, problem)
                    continue
                }

                val id = freshId(title, taken + produced.keys)
                produced[id] = ReferenceScript(id = id, title = title, summary = summary, body = body, from = from)
                messages += ChatMessage.toolResult(
                    call.id,
                    if (id == title) {
                        "记下了：「$id」。这一段现在一共拆出 ${produced.size} 条。"
                    } else {
                        // **撞了名就说出来**：它还走在"这一段"里，下一句它就该换个更具体的标题了。
                        "记下了，代号是「$id」——因为库里或者这一段里已经有一条叫「$title」的了。" +
                            "同一件事不要拆两遍；如果这确实是另一件事，下一次给它一个更具体的标题。"
                    },
                )
            }

            if (complaints.isNotEmpty()) {
                failures++
                lastProblems = complaints
                messages += ChatMessage.user(feedback(complaints))
            }
        }

        if (!spoke) return Result.Failed(listOf("一次都没问成（$rounds 个来回都没有答复）。"))
        if (produced.isEmpty() && lastProblems.isNotEmpty()) return Result.Failed(lastProblems)
        return Result.Digested(produced.values.toList(), rounds)
    }

    /**
     * 一个还没被用过的代号。**标题就是代号**，撞了才加 `-2`、`-3`。
     *
     * 标题优先（而不是另编一个编号）是因为导演要在提示词里读这些 id，而"一笔到期的账"比"ref-017"
     * 让它一眼知道那是什么。
     */
    private fun freshId(title: String, taken: Set<String>): String {
        if (title !in taken) return title
        var n = 2
        while ("$title-$n" in taken) n++
        return "$title-$n"
    }

    private fun feedback(problems: List<String>): String = buildString {
        appendLine("刚才那几条没记上，理由在下面。**改好之后再交一次**（已经记下的不用重交）：")
        problems.forEach { appendLine("· $it") }
    }

    private fun user(text: String, from: String, label: String, taken: Set<String>): String = buildString {
        appendLine("【这一段是哪儿来的】")
        appendLine(if (from.isBlank()) "（没说来源）" else from)
        appendLine(label)
        appendLine()
        appendLine("【原文】")
        appendLine(text.trim())
        if (taken.isNotEmpty()) {
            appendLine()
            appendLine("【库里已经有这些代号了，别再拆一遍】")
            appendLine(taken.joinToString("、"))
        }
    }

    private companion object {

        const val ADD_REFERENCE = "add_reference"

        /** 摘要上限。与 [ScriptPlanner.MAX_SUMMARY_CHARS] **同一个数**：两份东西的"每次唤醒付多少"是同一笔账。 */
        const val MAX_SUMMARY_CHARS = ScriptPlanner.MAX_SUMMARY_CHARS

        /** 正文（骨架）上限。与 [ScriptPlanner.MAX_BODY_CHARS] 同一个数，同一个理由。 */
        const val MAX_BODY_CHARS = ScriptPlanner.MAX_BODY_CHARS

        /**
         * 一段原文最多几个来回。
         *
         * 它不是重试次数（那是 `maxAttempts`）。一段文字里能拆出好几条，一条调一只手，所以来回数跟着
         * "这一段里有几件事"走——20 够一段里拆出十条并且改两轮。
         */
        const val MAX_ROUNDS = 20

        val TOOLS: List<ToolDefinition> = listOf(
            tool(
                ADD_REFERENCE,
                "交一条参考剧本。**拆出一条就交一条**，一次也可以交好几条。" +
                    "全交完就停手（不要再调用任何工具）——**一条都拆不出来也是合法的**：那就什么都别调。",
                text("title", "一句话的标题。它就是这一条的代号，所以别与库里已有的重复。"),
                text(
                    "summary",
                    "概括（$MAX_SUMMARY_CHARS 字以内）：谁在什么处境里、将要发生什么、玩家可能被卷进哪一边。" +
                        "**不许出现人名、地名、家族、门派、头衔**——那些是填进世界时才有的东西。",
                ),
                text(
                    "body",
                    "骨架（$MAX_BODY_CHARS 字以内）：处境、角色位、玩家可能怎么撞上、有哪几种走法，" +
                        "以及**玩家不进场会怎样**。同样不许出现具体的人名与地名。",
                ),
            ),
        )

        val SYSTEM = """
            你是「拆分」这一段。有人给你一段小说、剧本或者别的故事文本，你把它拆成若干条**参考剧本**。

            【参考剧本是什么】
            一条 = **一件能发生的事的骨架**：为什么会发生、谁卷进来、玩家可能怎么撞上、有哪几种走法。
            它不是剧情简介，也不是文摘——它要能被**填进任意一个世界**。所以它写的是**事情**，不是原文。

            【最重要的一条：抛弃角色背景】
            · **不许出现具体的人名、地名、家族、门派、头衔、称号。** 一个都不要。
            · 要写的是**处境与角色位**：「一个欠了债的人」「债主」「一个知道那件东西去了哪里的旁观者」。
            · 原文里那个人的身世、他属于哪个门派、那座城叫什么——**全部丢掉**。它们是"填的时候"才有的
              东西，写进来这一条就只对那一个世界成立了。
            · 要保留的是**这件事的形状**：谁与谁处在什么关系里、什么东西快要浮出来、哪一步是转折、
              哪一种走法会把事情变坏。

            【一条写全三段】
            · title：一句话的标题，看得出这件事是什么（不必是原文的章节名）。
            · summary：$MAX_SUMMARY_CHARS 字以内。谁在什么处境里、将要发生什么、玩家可能被卷进哪一边。
            · body：$MAX_BODY_CHARS 字以内。处境、角色位、玩家可能怎么撞上、几种走法，
              结尾写清**玩家不进场会怎样**（什么都不做也是要走下去的一种）。

            【宁少勿多】
            一段文本里能成为一条线的东西通常只有几条。**不要逐句复述、不要按段落切。**同一件事只说一遍。
            一段里讲了两件事就拆两条；一整段都在写风景、写心情、写回忆，就**一条都不要拆**。
            宁可少拆，也不要为了凑数把一句话拆成一条。

            【怎么交】
            一条调一次 $ADD_REFERENCE。交完就停手——**别再调用任何工具**，你的沉默就是"这一段拆完了"。
        """.trimIndent()
    }
}

/** [ScriptDigester] 那一只手的参数。 */
@Serializable
internal data class AddArgs(
    val title: String = "",
    val summary: String = "",
    val body: String = "",
)
