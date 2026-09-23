package io.github.k1midi0.infinitesimal.render

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.SystemId
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.LlmEvent
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.Usage
import io.github.k1midi0.infinitesimal.text.SentenceSegmenter
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import kotlinx.coroutines.flow.toList
import kotlin.time.TimeSource

/**
 * 观察器：`观察(X) → 描述(X)`，读或创建并写回（§7）。
 *
 * **它不是旁白者。** 没有剧情、没有语气、没有"你走进房间，一股霉味扑面而来"。旁白性的文字来自导演，
 * 而且低频。
 *
 * 固化以**句子列表**为单位存，所以"只增不改"由数据模型保证：唯一能改描述的入口是 [addDetail]，
 * 而它只能往列表里插一项（§14.2）。
 */
class Renderer(
    private val client: LlmClient,
    private val world: World,
) {

    /**
     * 给配置确实是固定值的地方用，主要是测试与装配代码。
     *
     * **它强制把缓存池设成 [SystemId.RENDERER]**（§14.8）。让调用方自己填 `userId`，迟早有人把渲染器
     * 的请求放进 NPC 那个池——而那样做的症状只是命中率悄悄变了，没有任何地方报错。分层因此落在这里，
     * 不落在调用约定上。
     */
    constructor(config: EndpointConfig, world: World) : this(
        LlmClient(config.copy(userId = SystemId.RENDERER)),
        world,
    )

    /** 真正发出去的整段渲染请求数。缓存命中不该让它增加——这是"固化"唯一的客观证据。 */
    var requestCount = 0
        private set

    data class Observation(
        val text: String,
        val sentences: List<String>,
        val fromCache: Boolean,
        val elapsedMs: Long,
        val usage: Usage?,
        val finishReason: String?,
        /** 抽查结果，见 [RenderGuards]。**不阻止落库**，它只记录。 */
        val violations: List<String>,
    )

    suspend fun observe(id: String): Observation {
        val entity = requireNotNull(world.byId(id)) { "世界里没有这个物体：$id" }

        entity.sentences.takeIf { it.isNotEmpty() }?.let { frozen ->
            return Observation(
                text = entity.text(),
                sentences = frozen,
                fromCache = true,
                elapsedMs = 0,
                usage = null,
                finishReason = "cache",
                violations = RenderGuards.check(entity.text()),
            )
        }

        val mark = TimeSource.Monotonic.markNow()
        requestCount++
        val events = client.streamChat(
            messages = listOf(
                // **三段动静分层**：规则（全局静态、逐字不变）→ 导演给的背景（每地点稳定）→ 对象
                // （每次都变）。前两段拼成同一段前缀，缓存才有东西可以完整匹配。
                ChatMessage.system(staticPrefixOf(entity)),
                ChatMessage.user(contextOf(entity)),
            ),
            thinking = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.LOW),
        ).toList()
        val elapsed = mark.elapsedNow().inWholeMilliseconds

        val text = events.filterIsInstance<LlmEvent.Content>().joinToString("") { it.text }.trim()
        val finished = events.filterIsInstance<LlmEvent.Finished>().lastOrNull()
        check(text.isNotEmpty()) { "端点返回了空的 content，finish_reason=${finished?.reason}" }

        // 入口就切句：之后所有写操作都以"句"为单位，展示时才拼回整段。
        // 切句器因此是描述的**入口和出口的同一个函数**。
        val sentences = SentenceSegmenter.split(text)
        world.put(entity.copy(sentences = sentences))

        return Observation(
            text = text,
            sentences = sentences,
            fromCache = false,
            elapsedMs = elapsed,
            usage = finished?.usage,
            finishReason = finished?.reason,
            violations = RenderGuards.check(text),
        )
    }

    /**
     * 要求更精细：在既有句子之间**插入**一句（§7.2）。
     *
     * 细化只增不改，所以它永远不重写、也不删除。落库由这里做——写入器只**提议**（见 [Proposal]）。
     *
     * 两条路都过不了守卫时**世界状态一点不动**，返回的 `sentences` 为 null。
     */
    suspend fun addDetail(id: String, inserter: DetailInserter, afterIndex: Int = -1): DetailInserter.Outcome {
        val entity = requireNotNull(world.byId(id)) { "世界里没有这个物体：$id" }
        // 默认追加在末尾——这也是 FIM 唯一能"只用 hint"的情形。
        val hint = if (afterIndex < 0) entity.sentences.size else afterIndex

        val outcome = inserter.insert(entity.sentences, contextOf(entity), hint)
        outcome.sentences?.let { world.put(entity.copy(sentences = it)) }
        return outcome
    }

    /**
     * 发给模型的那一段：**只有这个物体自身的属性**。
     *
     * **不含坐标、不含"所在"。** 这两样原来在这里，而模型忠实地把它们用上了：
     *
     * > 它靠大厅中央立柱横向摆着，一侧对着吧台，另一侧留出较宽的通行走道。
     *
     * 那不是桌子的外表，那是**关系**——而关系会变、物体自身没变，于是那句话会**静默地过时**，永远
     * 没人发现（§7.6）。
     *
     * 所以处置不是"在提示词里叫它别写"，而是**输入里压根不给**：没有素材，就写不出来。
     * `parent` 链仍然要用——用来取地点背景（见 [staticPrefixOf]），但**不告诉它"你在哪"**。
     */
    fun contextOf(entity: Entity): String = buildString {
        appendLine("对象：${entity.label}（种类：${entity.kind}）")
        if (entity.attributes.isNotEmpty()) {
            appendLine("已知属性：")
            entity.attributes.forEach { (key, value) -> appendLine("  $key = $value") }
        }
    }.trimEnd()

    /**
     * 静态前缀 = 规则 + 背景。
     *
     * 拼成**一段**而不是两条 system 消息：缓存按 token 前缀匹配，分成两条只会在中间多出 role 标记，
     * 对命中没有任何好处，却让"前两层"变成两个要各自维护的东西（§7.5）。
     */
    fun staticPrefixOf(entity: Entity): String {
        val background = world.backgroundChainOf(entity.id)
        if (background.isEmpty()) return SYSTEM_PROMPT
        return buildString {
            appendLine(SYSTEM_PROMPT)
            appendLine()
            appendLine("【背景】")
            background.forEach { appendLine(it) }
        }.trimEnd()
    }

    companion object {

        /**
         * 永远不变的那一段。
         *
         * 它必须是**逐字固定**的：缓存按 token 前缀完整匹配，任何插值都会让前缀变样、缓存全灭。
         *
         * 第 2 条是 §7.6 那条规则的落点。它**不能**替代输入裁剪——实测里正是"把坐标发给它，它就写
         * 位置关系"，所以这里只是第二道，第一道是不给素材。
         */
        val SYSTEM_PROMPT: String = """
            你是一个观察器。你只描述被观察的对象**自身**。

            规则：
            1. 只写这个对象看上去是什么样：材质、形态、磨损、颜色、纹理、气味、触感。
            2. **不写它周围有什么，也不写它和其他东西的位置关系。** 不出现"旁边""上面""对面""一侧""搁在""放在""摆在"这类说法。它在哪里、和谁挨着，属于别的层，不属于它的外表。
            3. 不写动作、不写情节、不写心情，不写"你走进房间"这类叙述。你不是旁白者。
            4. 不推测没有给出的事实。看不到的东西不要编。
            5. 用中文，三到四句，每句以句末标点结束，直接给出描述本身。
        """.trimIndent()
    }
}
