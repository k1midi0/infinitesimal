package io.github.k1midi0.infinitesimal.render

import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.FimClient
import io.github.k1midi0.infinitesimal.llm.FunctionSpec
import io.github.k1midi0.infinitesimal.llm.JsonSchema
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.LlmEvent
import io.github.k1midi0.infinitesimal.llm.PropertySchema
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.ToolDefinition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * 一个写入器对「该在哪里补一句、补什么」的提议。
 *
 * **提议还不是事实。** 守卫与落库都在 [DetailInserter] 那一侧——写入器不碰世界状态，这样"模型说
 * 什么"和"世界里多了什么"之间永远隔着一道能检查的关口（§1.2）。
 */
data class Proposal(
    val afterIndex: Int,
    val sentence: String,
    val writer: String,
    val elapsedMs: Long,
    val note: String? = null,
)

/**
 * 「往已有描述里补一句话」这件事的两条实现路径。
 *
 * **接口之所以存在，是因为工具那条路必须一直在。** FIM 是 Beta、有 4K 上限、且不是每个模型都有；
 * 工具调用是通用的。实测的结论是**工具 2/2 成功、FIM 5 次里只有 2 次可用**（§14.3）——所以工具是
 * 主路径，FIM 是可选加速。
 *
 * [hint] 是希望插入的位置（第几句之后，0 = 最前，n = 最后）。工具实现可以自己另选位置，FIM 只能用
 * hint——**这个不对称是两种机制的本质差别**：FIM 的插入点是我们切出来的，不是模型选的。
 */
interface SentenceWriter {

    val name: String

    suspend fun propose(sentences: List<String>, context: String, hint: Int): Proposal
}

/**
 * 先走主路，不行就回退。
 *
 * §14.4 定下来的：模型输出**落库前必须过守卫**，而 FIM 这条路必须**带重试与回退**，回退目标是工具。
 * FIM 的失败方式实测有三种——空返回、假标记 `<cont=12>`、以及把端点的安全审核标记当作补全结果返回
 * ——**它们都落在 `finish_reason=stop` 上，看起来完全正常**。
 *
 * 两条路都过不了时返回 `null`，**世界状态一点不动**。玩家看到的是"这次没补上"，而不是一段坏文字。
 */
class DetailInserter(
    /** 可选的加速路径（FIM）。没有 FIM 能力的模型上它就是 null，于是只剩工具——那不是降级，是常态。 */
    private val primary: SentenceWriter?,
    /** 必备路径（工具）。它必须在。 */
    private val fallback: SentenceWriter,
) {

    data class Outcome(
        /** 通过守卫、已经可以落库的那份句子列表。null = 两条路都没产出可用的东西。 */
        val sentences: List<String>?,
        val writer: String?,
        /** 每条路各自的结果，供日志与调试。 */
        val attempts: List<String>,
    )

    /**
     * 这次装配挂了哪几条路，按尝试顺序。
     *
     * 日志与界面都该看得见它——**"现在到底能不能用 FIM"必须是能一眼看出来的**，否则一次装配失误
     * （比如开关没接上）会表现为"细化偶尔不好使"，而那种症状没人查得出来。
     */
    val paths: List<String> get() = listOfNotNull(primary, fallback).map { it.name }

    suspend fun insert(sentences: List<String>, context: String, hint: Int): Outcome {
        val attempts = mutableListOf<String>()

        for (writer in listOfNotNull(primary, fallback)) {
            val proposal = try {
                writer.propose(sentences, context, hint)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Throwable) {
                // 一条路坏掉不该带走整次细化：记下来，交给下一条。
                attempts += "${writer.name}：抛错 ${cause::class.simpleName}"
                continue
            }

            val violations = InsertGuards.check(sentences, proposal.sentence)
            if (violations.isEmpty()) {
                val at = proposal.afterIndex.coerceIn(0, sentences.size)
                val next = sentences.toMutableList().apply { add(at, proposal.sentence.trim()) }
                attempts += "${writer.name}：通过（第 $at 句之后）"
                return Outcome(next, writer.name, attempts)
            }
            attempts += "${writer.name}：${violations.joinToString("；")}"
        }

        return Outcome(null, null, attempts)
    }
}

/** 两条路共用的那几段提示词。指令只写一处，否则两条路的规则迟早不一致。 */
internal object DetailPrompt {

    val INSTRUCTION: String = """
        请**只补一句**新的细节，插在上面这段话里。

        规则：
        1. 只写这一句。不要复述已有的句子，不要把整段重写一遍。
        2. 不得与已有描述矛盾。已经说过、或者已经被排除的（例如已经写了"不见划痕"），不要反过来讲。
        3. 这一句要和它前后的句子接得上，也不要抢后面那句要说的事。
        4. 只讲这个物体**自身**的外表，不写它周围有什么、也不写它和别的东西的位置关系。
        5. 一句话，句末带标点。不要编号，不要引号，不要解释。
    """.trimIndent()

    fun numbered(sentences: List<String>): String =
        sentences.mapIndexed { index, sentence -> "${index + 1}. $sentence" }.joinToString("\n")

    fun contextBlock(context: String, sentences: List<String>): String = buildString {
        appendLine(context)
        appendLine()
        appendLine("这个物体已经固化下来的描述（按句编号）：")
        appendLine(numbered(sentences))
    }
}

@Serializable
private data class AddSentenceArgs(val after: Int = 0, val sentence: String = "")

private const val ADD_SENTENCE = "add_sentence"

private val ADD_SENTENCE_TOOL = ToolDefinition(
    function = FunctionSpec(
        name = ADD_SENTENCE,
        description = "在已有描述的第 after 句之后插入一句新的细节。只能插入，不能修改也不能删除已有句子。",
        parameters = JsonSchema(
            properties = mapOf(
                "after" to PropertySchema("integer", "插在第几句之后。0 表示最前面，句子条数表示最后面。"),
                "sentence" to PropertySchema("string", "要插入的那一句话，带句末标点。一次只写一句。"),
            ),
            required = listOf("after", "sentence"),
        ),
    ),
)

/**
 * 工具插入：走 `chat/completions` 的 tool call。**必备路径。**
 *
 * 通用、不依赖任何 Beta 能力，在实测里 2/2 成功。代价是位置要靠模型自己数——而我们给它的编号是
 * 抄的、不是数的，所以这一路的位置比 FIM 更容易偏。
 */
class ToolSentenceWriter(
    private val client: LlmClient,
) : SentenceWriter {

    override val name: String = "工具插入"

    override suspend fun propose(sentences: List<String>, context: String, hint: Int): Proposal {
        val user = buildString {
            appendLine(DetailPrompt.contextBlock(context, sentences))
            appendLine()
            appendLine("建议插入位置：第 $hint 句之后（0 表示最前，${sentences.size} 表示最后）。你也可以判断更合适的位置。")
            appendLine()
            appendLine(DetailPrompt.INSTRUCTION)
        }

        val mark = TimeSource.Monotonic.markNow()
        val events = client.streamChat(
            messages = listOf(ChatMessage.system(SYSTEM), ChatMessage.user(user)),
            tools = listOf(ADD_SENTENCE_TOOL),
            thinking = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.LOW),
        ).toList()
        val elapsed = mark.elapsedNow().inWholeMilliseconds

        val raw = collectArguments(events)
            ?: return Proposal(hint, "", name, elapsed, "模型没有调用 $ADD_SENTENCE")

        val parsed = try {
            Json.decodeFromString<AddSentenceArgs>(raw)
        } catch (cause: Throwable) {
            return Proposal(hint, "", name, elapsed, "arguments 不是合法 JSON：${raw.take(120)}")
        }

        return Proposal(
            afterIndex = parsed.after.coerceIn(0, sentences.size),
            sentence = parsed.sentence.trim(),
            writer = name,
            elapsedMs = elapsed,
        )
    }

    /** 按 index 把流式的工具调用参数片段拼起来，只取我们注册的那个函数。 */
    private fun collectArguments(events: List<LlmEvent>): String? {
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
        val index = names.entries.firstOrNull { it.value == ADD_SENTENCE }?.key ?: return null
        return arguments[index]?.toString()
    }

    private companion object {
        val SYSTEM = """
            你是一个观察器，正在给一个已经描述过的物体补充细节。

            你只能通过 add_sentence 工具补一句话。不能修改、删除、或重写已有的任何一句话。
        """.trimIndent()
    }
}

/**
 * FIM 插入：走 `/beta/completions` 的 `prompt` + `suffix`。**可选加速路径。**
 *
 * **插入点由我们切，不由模型选。** 端点的契约是"补全 prompt 与 suffix 之间的内容"，所以两侧的文字
 * 由协议原样保留——模型不是"不会改"，是**没有机会碰**。这是它唯一无法被工具替代的价值。
 *
 * 但它的输出**必须过守卫**：实测它会返回空、返回假标记 `<cont=12>`、甚至把端点的安全审核标记当成
 * 补全结果交出来（§14.4）。
 *
 * 提示词要短。第一版把四条"元规则"塞进 prompt，模型切到了元语言 register、吐出一个假标记——**FIM
 * 不是对话接口，它旁边不该站着说明书**（§14.3）。
 */
class FimSentenceWriter(
    private val client: FimClient,
) : SentenceWriter {

    override val name: String = "FIM 插入"

    override suspend fun propose(sentences: List<String>, context: String, hint: Int): Proposal {
        val at = hint.coerceIn(0, sentences.size)

        val prompt = buildString {
            appendLine("给这个物体补一句新的细节，插在下面这段话中间。不得与已有描述矛盾，不得复述已有句子。只讲它自身的外表，不写周围有什么。")
            appendLine(context)
            append(sentences.take(at).joinToString(""))
        }

        val mark = TimeSource.Monotonic.markNow()
        val result = client.complete(prompt = prompt, suffix = sentences.drop(at).joinToString(""), maxTokens = 200)
        val elapsed = mark.elapsedNow().inWholeMilliseconds

        // 补全可能不带句末标点，也可能带前后空白或引号。收拾它是"把它拼回描述"这件事的一部分。
        val trimmed = result.text.trim().trim('"', '“', '”')
        val sentence = if (trimmed.isEmpty() || trimmed.last() in "。！？!?…") trimmed else "$trimmed。"

        return Proposal(
            afterIndex = at,
            sentence = sentence,
            writer = name,
            elapsedMs = elapsed,
            note = "finish_reason=${result.finishReason}",
        )
    }
}
