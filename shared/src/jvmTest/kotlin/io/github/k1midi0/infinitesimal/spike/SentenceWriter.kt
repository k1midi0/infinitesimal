package io.github.k1midi0.infinitesimal.spike

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
import io.github.k1midi0.infinitesimal.text.SentenceSegmenter
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一次插入：在第 [afterIndex] 句之后插入 [sentence]。
 *
 * [violations] 是守卫拦下的问题。**它不阻止插入**——spike 阶段要先看清模型到底会犯哪些错，
 * 所以记录而不拦截。
 */
data class Insertion(
    val afterIndex: Int,
    val sentence: String,
    val violations: List<String> = emptyList(),
    val elapsedMs: Long = 0,
    val note: String? = null,
)

/**
 * "往已有描述里补一句话"这件事的两种实现。
 *
 * **接口之所以存在，是因为工具那条路必须一直在**：FIM 是 Beta、有 4K 上限、且不是每个模型都有；
 * 工具调用是通用的。所以 FIM 是可选加速，工具是必备能力——两者共用同一份数据模型（句子列表），
 * 差别只在"这句话由谁来写"。
 */
interface SentenceWriter {
    val name: String

    /**
     * [hint] 是希望插入的位置（第几句之后，0 = 最前，n = 最后）。
     *
     * 工具实现可以自己另选位置（返回真实的 afterIndex），FIM 实现只能用 hint——因为 FIM 的插入点
     * 是我们切出来的，不是模型选的。**这个不对称是两种机制的本质差别之一。**
     */
    suspend fun insert(sentences: List<String>, context: String, hint: Int): Insertion
}

/**
 * 插入的守卫。
 *
 * 它是"句子列表"这个数据模型的配套：既然写操作被限制成"插一句"，那就要能判出"它插的到底是不是
 * 一句"。没有这一层，模型完全可以声明"我要插一句"然后把整段重写塞进那个字段。
 */
object InsertGuards {

    private const val MAX_CHARS = 120

    fun check(existing: List<String>, sentence: String): List<String> = buildList {
        val text = sentence.trim()
        if (text.isEmpty()) {
            add("空句子")
            return@buildList
        }
        if (text.length > MAX_CHARS) {
            add("长度 ${text.length} 字，超过 $MAX_CHARS（像是把一整段塞进来了）")
        }
        val pieces = SentenceSegmenter.split(text)
        if (pieces.size > 1) {
            add("含 ${pieces.size} 个句子，要求一次只插一句")
        }
        if (existing.any { it.trim() == text }) {
            add("与已有句子完全重复")
        }
    }
}

/** 两种实现共用的那几段提示词。指令只写一处，否则两条路的规则迟早不一致。 */
internal object DetailPrompt {

    val INSTRUCTION: String = """
        请**只补一句**新的细节，插在上面这段话里。

        规则：
        1. 只写这一句。不要复述已有的句子，不要把整段重写一遍。
        2. 不得与已有描述矛盾。已经说过、或者已经被排除的（例如已经写了"不见划痕"），不要反过来讲。
        3. 这一句要和它前后的句子接得上，也不要抢后面那句要说的事。
        4. 一句话，句末带标点。不要编号，不要引号，不要解释。
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
 * 工具插入：走 `chat/completions` 的 tool call。
 *
 * 通用、已测通、不依赖任何 Beta 能力。代价是**位置要靠模型自己数**——而我们给它的编号是抄的，
 * 不是数的，所以这一路的位置由模型决定时容易偏。
 */
class ToolSentenceWriter(private val client: LlmClient) : SentenceWriter {

    override val name: String = "工具插入"

    override suspend fun insert(sentences: List<String>, context: String, hint: Int): Insertion {
        val user = buildString {
            appendLine(DetailPrompt.contextBlock(context, sentences))
            appendLine()
            appendLine("建议插入位置：第 $hint 句之后（0 表示最前，${sentences.size} 表示最后）。你也可以判断更合适的位置。")
            appendLine()
            appendLine(DetailPrompt.INSTRUCTION)
        }

        val started = System.currentTimeMillis()
        val events = client.streamChat(
            messages = listOf(ChatMessage.system(SYSTEM), ChatMessage.user(user)),
            tools = listOf(ADD_SENTENCE_TOOL),
            thinking = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.LOW),
        ).toList()
        val elapsed = System.currentTimeMillis() - started

        val raw = collectArguments(events)
            ?: return Insertion(hint, "", listOf("模型没有调用 $ADD_SENTENCE"), elapsed, finishOf(events))

        val parsed = try {
            Json.decodeFromString<AddSentenceArgs>(raw)
        } catch (cause: Throwable) {
            return Insertion(hint, "", listOf("arguments 不是合法 JSON：${raw.take(120)}"), elapsed)
        }

        val sentence = parsed.sentence.trim()
        val after = parsed.after.coerceIn(0, sentences.size)
        return Insertion(
            afterIndex = after,
            sentence = sentence,
            violations = InsertGuards.check(sentences, sentence).let { guards ->
                if (after != hint) guards + "位置与建议不符（建议 $hint，实际 $after）" else guards
            },
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

    private fun finishOf(events: List<LlmEvent>): String? =
        events.filterIsInstance<LlmEvent.Finished>().lastOrNull()?.reason?.let { "finish_reason=$it" }

    private companion object {
        val SYSTEM = """
            你是一个观察器，正在给一个已经描述过的物体补充细节。

            你只能通过 add_sentence 工具补一句话。不能修改、删除、或重写已有的任何一句话。
        """.trimIndent()
    }
}

/**
 * FIM 插入：走 `/beta/completions` 的 `prompt` + `suffix`。
 *
 * **插入点由我们切，不由模型选。** 端点的契约是"补全 prompt 与 suffix 之间的内容"，所以两侧的文字
 * 由协议原样保留——模型不是"不会改"，是**没有机会碰**。
 */
class FimSentenceWriter(private val client: FimClient) : SentenceWriter {

    override val name: String = "FIM 插入"

    override suspend fun insert(sentences: List<String>, context: String, hint: Int): Insertion {
        val at = hint.coerceIn(0, sentences.size)

        val prompt = buildString {
            // 一句短指令，不是一段规则清单。
            //
            // 第一轮对比里这里放的是四条「元规则」（只写一句、不要编号、不要引号…），结果模型
            // 吐出了一个假标记 `<cont=12>`。探针证明问题出在这个形状上：**FIM 不是对话接口，
            // 它旁边不该站着说明书**。补全模型要的是"接着写"的语境，不是任务说明。
            appendLine("给这个物体补一句新的细节，插在下面这段话中间。不得与已有描述矛盾，不得复述已有句子。")
            appendLine(context)
            append(sentences.take(at).joinToString(""))
        }
        val suffix = sentences.drop(at).joinToString("")

        val started = System.currentTimeMillis()
        val result = client.complete(prompt = prompt, suffix = suffix, maxTokens = 200)
        val elapsed = System.currentTimeMillis() - started

        // 补全可能不带句末标点，也可能带前后空白。补标点在这里做，因为它属于"把它拼回描述"这件事。
        val trimmed = result.text.trim().trim('"', '“', '”')
        val sentence = if (trimmed.isEmpty() || trimmed.last() in "。！？!?…") trimmed else "$trimmed。"

        return Insertion(
            afterIndex = at,
            sentence = sentence,
            violations = InsertGuards.check(sentences, sentence),
            elapsedMs = elapsed,
            note = "finish_reason=${result.finishReason}",
        )
    }
}
