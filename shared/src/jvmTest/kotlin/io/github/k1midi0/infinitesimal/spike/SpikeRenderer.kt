package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.llm.LlmEvent
import io.github.k1midi0.infinitesimal.llm.ThinkingRequest
import io.github.k1midi0.infinitesimal.llm.Usage
import io.github.k1midi0.infinitesimal.text.SentenceSegmenter
import kotlinx.coroutines.flow.toList
import kotlin.system.measureTimeMillis

/**
 * 渲染输出的抽查：有没有写到周边或位置关系。
 *
 * **它不是可靠的判定办法**——词表永远不全，而"桌子对着吧台"完全可以用一个词表里没有的说法讲出来。
 * 真正的处置是**输入里不给周边信息**（见 [SpikeRenderer.contextOf]）：没有素材就写不出来。
 *
 * 这里只做抽查，用来验证"去掉输入里的坐标与所在之后，输出真的干净了"。误报可以接受（"立在""中央"
 * 这类词既可能指物体自身，也可能指关系，所以干脆不收进表里）。
 */
object RenderGuards {

    private val RELATION_WORDS = listOf(
        "旁边", "边上", "对面", "挨着", "靠着", "朝着", "对着", "紧挨",
        "周围", "四周", "附近", "贴着", "搁在", "放在", "摆在",
        "一侧", "另一侧", "两旁",
    )

    fun check(text: String): List<String> =
        RELATION_WORDS.filter { text.contains(it) }.map { "疑似写了周边或位置关系：「$it」" }
}

/**
 * 观察器的最小原型：`观察(X) → 描述(X)`，读或创建并写回。
 *
 * 固化以**句子列表**为单位存，所以"只增不改"由数据模型保证：唯一能改描述的入口是
 * [applyInsertion]，而它只能往列表里插一项。
 */
class SpikeRenderer(
    private val client: LlmClient,
    private val world: SpikeWorld,
) {

    /** 真正发出去的整段渲染请求数。缓存命中不该让它增加——这是"固化"唯一的客观证据。 */
    var requestCount = 0
        private set

    data class Outcome(
        val text: String,
        val sentences: List<String>,
        val fromCache: Boolean,
        val elapsedMs: Long,
        val usage: Usage?,
        val finishReason: String?,
    )

    suspend fun observe(id: String): Outcome {
        val entity = requireNotNull(world.byId(id)) { "世界里没有这个物体：$id" }

        if (entity.sentences.isNotEmpty()) {
            return Outcome(
                text = entity.text(),
                sentences = entity.sentences,
                fromCache = true,
                elapsedMs = 0,
                usage = null,
                finishReason = "cache",
            )
        }

        var events: List<LlmEvent> = emptyList()
        val elapsed = measureTimeMillis {
            requestCount++
            events = client
                .streamChat(
                    messages = listOf(
                        // **三层动静分层**：观察器规则（全局静态、逐字不变）→ 导演给的背景（每地点
                        // 稳定）→ 对象本身（每次都变）。前两层拼成同一段前缀，缓存才有东西可以完整
                        // 匹配；背景也因此不只是"让描述不编造"，它同时把渲染器那点可怜的前缀撑长了。
                        ChatMessage.system(staticPrefixOf(entity)),
                        ChatMessage.user(contextOf(entity)),
                    ),
                    thinking = ThinkingRequest.of(ThinkingMode.Never, ReasoningEffort.LOW),
                )
                .toList()
        }

        val text = events.filterIsInstance<LlmEvent.Content>().joinToString("") { it.text }.trim()
        val finished = events.filterIsInstance<LlmEvent.Finished>().lastOrNull()
        check(text.isNotEmpty()) { "端点返回了空的 content，finish_reason=${finished?.reason}" }

        // 入口就切句：之后所有写操作都以"句"为单位，展示时才拼回整段。
        // 切句器因此是描述的入口和出口的同一个函数。
        val sentences = SentenceSegmenter.split(text)
        world.put(entity.copy(sentences = sentences))

        return Outcome(text, sentences, fromCache = false, elapsedMs = elapsed, usage = finished?.usage, finishReason = finished?.reason)
    }

    /**
     * 把一次插入落到实体上。
     *
     * 这是**唯一**能改动描述的入口，而它只能插入。守卫记下的问题照样落库、照样返回——spike 阶段
     * 要先看清模型会犯哪些错，拦截留到有了定论之后。
     */
    fun applyInsertion(id: String, insertion: Insertion): List<String> {
        val entity = requireNotNull(world.byId(id)) { "世界里没有这个物体：$id" }
        if (insertion.sentence.isBlank()) return insertion.violations
        val at = insertion.afterIndex.coerceIn(0, entity.sentences.size)
        val next = entity.sentences.toMutableList().apply { add(at, insertion.sentence) }
        world.put(entity.copy(sentences = next))
        return insertion.violations
    }

    /**
     * 地点背景：沿 `parent` 链往上收集，由**导演**写在地点实体上。
     *
     * 渲染器只读它、不改它——"导演是世界的补写者"（§8.3）在这里的落点。
     */
    fun backgroundOf(entity: SpikeEntity): List<String> =
        generateSequence(entity) { current -> current.parent?.let { world.byId(it) } }
            .mapNotNull { it.background }
            .toList()
            .reversed() // 从地点（根）到最近，读起来是"先大势、后近况"

    /**
     * 静态前缀 = 规则 + 背景。
     *
     * 拼成**一段**而不是两条 system 消息：缓存按 token 前缀匹配，分成两条只会在中间多出 role 标记，
     * 对命中没有任何好处，却让"前两层"变成两个要各自维护的东西。
     */
    fun staticPrefixOf(entity: SpikeEntity): String {
        val background = backgroundOf(entity)
        if (background.isEmpty()) return SYSTEM_PROMPT
        return buildString {
            appendLine(SYSTEM_PROMPT)
            appendLine()
            appendLine("【背景】")
            background.forEach { appendLine(it) }
        }.trimEnd()
    }

    /**
     * 物体的上下文块。**观察与插入共用这一份**——两条路看到的"世界"必须是同一个，否则对比就不成立。
     *
     * **不含坐标、不含"所在"。** 这两样原来在这里，而模型忠实地把它们用上了：
     *
     * > 它靠大厅中央立柱横向摆着，一侧对着吧台，另一侧留出较宽的通行走道。
     *
     * 那不是桌子的外表，那是**关系**——而关系属于地图，不属于任何一个物体的描述。更要命的是
     * 关系会变而物体没变：旁边的酒杯被拿走后，这句描述就过时了，可"邻物改变"根本不是失效条件
     * （失效只有三条），于是它会**静默地过时**，永远没人发现。
     *
     * 所以处置不是"在提示词里叫它别写"，而是**输入里压根不给**：没有素材，就写不出来。
     * 这与"写操作的限制要落在数据模型上"是同一条原则，只是这里限制的是输入。
     *
     * `parent` 链仍然要用——用来取地点背景（见 [staticPrefixOf]），但**不告诉它"你在哪"**。
     */
    fun contextOf(entity: SpikeEntity): String = buildString {
        appendLine("对象：${entity.label}（种类：${entity.kind}）")
        if (entity.attributes.isNotEmpty()) {
            appendLine("已知属性：")
            entity.attributes.forEach { (key, value) -> appendLine("  $key = $value") }
        }
    }.trimEnd()

    companion object {

        /**
         * 永远不变的那一段。
         *
         * 它必须是**逐字固定**的：缓存按 token 前缀完整匹配，任何插值都会让前缀变样、缓存全灭。
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
