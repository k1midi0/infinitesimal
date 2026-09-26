package io.github.k1midi0.infinitesimal.npc

import io.github.k1midi0.infinitesimal.config.ContextSettings
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.estimateTokens
import kotlinx.serialization.Serializable

/**
 * 历史里的一段。
 *
 * 历史不是一串裸消息，而是一串**段**：[RawPiece] 是原文，[SummaryPiece] 是一条摘要——它**遮住**了更早
 * 的那一串。这正是 §6.1 与 §14.39 要的形状：累积、超阈压缩、摘要自己再超阈就再压。
 *
 * 每一段有一个**永不变的位置号** [pos]（`h1`、`h2`……）。**位置号不复用、不重排**：压缩之后原来那些
 * 位置就没了，而模型看到的编号与它上一轮看到的对得上——换了编号，它就会指着一段不存在的东西说话。
 *
 * ## 它自己也要 `@Serializable`（§14.49）
 *
 * 只标在 [RawPiece] / [SummaryPiece] 上是不够的：存档里那个字段的类型是 **`Piece`**，而
 * `kotlinx.serialization` 只有在**父类型自己**标了 `@Serializable` 时才知道去它的子类里找序列化器。
 * 症状是一句 `Serializer for subclass 'RawPiece' is not found in the polymorphic scope of 'Piece'`
 * ——它只在真的存/读一次的时候才会出现。
 */
@Serializable
sealed interface Piece {
    val pos: Long
}

/** 原文：一条真的发出去过的消息。 */
@Serializable
data class RawPiece(override val pos: Long, val message: ChatMessage) : Piece

/**
 * **一条摘要节点**（§6.1、§14.40）。它遮住了从 [pos] 到 [coveredThrough] 之间那些段。
 *
 * @property blockId 给人看的块号（`b1`、`b2`……）。**它是压缩的入口**：一块的摘要自己再超阈时，
 *   压的就是它——而那一压出来的是 [tier] + 1 的摘要。
 * @property tier 1 = 压原文压出来的，2 = 压摘要压出来的，3 = 再往上。**这就是"自然遗忘"的层次**：
 *   越早的事被压得越狠，而它不会消失，只会变短。
 * @property coveredThrough 它遮到哪个位置号（含）。与 [pos] 合起来就是它覆盖的范围——tier 2 压的时
 *   候，这个范围里装的是别的一些摘要，而不是原文。
 */
@Serializable
data class SummaryPiece(
    override val pos: Long,
    val coveredThrough: Long,
    val blockId: String,
    val tier: Int,
    val topic: String,
    val text: String,
    /**
     * **它一共盖住了多少条原文**（递归累计：盖住一条摘要时，算的是那条摘要盖住的那些）。
     *
     * 这个数不是装饰。压缩有个下限（压太短的一段，摘要反而比原文长），而**下限不能按"段数"算**——
     * 一条摘要只占一段，可它背后是八条。按段数算的话，二次压缩几乎永远凑不够下限，于是"摘要再超阈就
     * 再压"那条设计根本走不到。这个字段就是让第二级数得出来的东西。
     */
    val covered: Int,
) : Piece

/**
 * **一个角色的对话历史**（§6.1、§14.39、§14.40）。
 *
 * 在这之前，角色每次被唤醒都新建一段 messages——所以它每一句都从零开始。用户对这一条的裁定是重做：
 *
 * > 应该是正常拼接的多轮对话上下文，超过阈值后压缩，压缩后的部分超过阈值就二次压缩
 *
 * 这个类管的就是这三件事：**拼接**（[commit]）、**压缩**（[compact]）、**超阈的判断**（[overThreshold]）。
 *
 * ## 大小怎么算：一个真数当锚，一条估算管增量
 *
 * 「上下文现在多大」有两半，来源不同：
 *
 *  - **上次请求到底多大** —— **提供方说了算**。每次响应都带 `usage.prompt_tokens`，那是真数，零成本。
 *  - **自那以后又添了多少** —— 只能估。这一半只有新追加的那几条，误差不随对话变长而累积。
 *
 * 所以不拿估算去猜绝对量（那会差一两成，而在 400K 上就是几万）。这也是 dsh 自己的做法——它的原话是
 * 「**Provider usage anchors a measurement**」。
 *
 * ## 它现在会跟着存档走（§14.49）
 *
 * 从前它只跟着进程活着。现在 [snapshot] 与 [restore] 把它整份交出去、整份收回来——**包括那三个内部
 * 计数**（下一段的位置号、下一个块号、锚的两格）。三个都要带：只带 [pieces] 的话，读回来的历史会
 * 从 `h1` 重新编号，而模型上一轮看到的是 `h37`——它就会指着一个不存在的位置号说话。
 *
 * **摘要不能重算**，所以它必须原样带走：压掉的那些原话只剩[底部记录][io.github.k1midi0.infinitesimal.world.EventLog]
 * 里那一层，而摘要是当初模型给的一句话。丢掉它，读回来的角色就失忆了。
 */
class Conversation(val ownerId: String) {

    private val pieces = mutableListOf<Piece>()

    /** 下一个位置号。**只增不减**，压缩也不回退。 */
    private var nextPos = 1L

    /** 下一个块号。 */
    private var nextBlock = 1L

    /** 提供方回报的真实 prompt token 数。`-1` = 还没有过锚。 */
    private var anchorTokens = -1

    /** 锚覆盖到历史里的第几条**消息**（不含）。它之后的那些才算"自锚点以来的增量"。 */
    private var anchoredThrough = 0

    /**
     * 照着快照重建一份。
     *
     * **它不能是私有的**：`@Serializable` 生成的那个序列化器要与它共存，而私有次构造会让生成的那份代码
     * 也够不着它（编译器报的是 `Cannot access 'constructor(...)': it is private`，位置指在生成代码里）。
     * 所以走 `internal` + 一个公开的工厂函数：模块外拿不到这个构造器，而读档那一侧拿得到。
     */
    internal constructor(snapshot: ConversationSnapshot) : this(snapshot.ownerId) {
        pieces += snapshot.pieces
        nextPos = snapshot.nextPos
        nextBlock = snapshot.nextBlock
        anchorTokens = snapshot.anchorTokens
        anchoredThrough = snapshot.anchoredThrough
    }

    /**
     * 整份交出去，给存档用。
     *
     * 它**故意是公开的**，而不是让存档那一层反射进来掏私有字段：那三个计数与 [pieces] 的关系（谁该与谁
     * 一致、哪些不能重排）是这个类自己的事，掏出去之后每个调用点都要自己守一遍。
     */
    fun snapshot(): ConversationSnapshot = ConversationSnapshot(
        ownerId = ownerId,
        pieces = pieces.toList(),
        nextPos = nextPos,
        nextBlock = nextBlock,
        anchorTokens = anchorTokens,
        anchoredThrough = anchoredThrough,
    )

    val size: Int get() = pieces.size

    /** 现在有几条原文、几条摘要——探针与测试要看这个。 */
    val rawCount: Int get() = pieces.count { it is RawPiece }
    val summaryCount: Int get() = pieces.count { it is SummaryPiece }

    /** 摊平之后的 messages。**它在压缩之后会变短**——那正是压缩的意思。 */
    fun history(): List<ChatMessage> = pieces.mapNotNull { it.message() }

    /**
     * 现在身上那几条摘要，**按位置**。探针用它看"到第几级了"——第 2 级出现过没有，是这个设计里最该被
     * 看见的一格读数（「摘要自己再超阈就再压」是用户点名要的那一半，§6.1）。
     */
    fun summaries(): List<SummaryPiece> = pieces.filterIsInstance<SummaryPiece>()

    /**
     * 组装这一次请求要发的 messages：**system 在最前，然后是全部历史，最后是这一次的新内容**。
     *
     * 顺序不能动。历史的尾部与这一次的开头紧挨着，而**前缀缓存认的就是这个**——旧的块一旦写进去就永不
     * 改变，所以缓存能一路命中到最新那一轮，只在最后 [fresh] 那里断开（§6.1）。
     */
    fun request(system: ChatMessage, fresh: List<ChatMessage>): List<ChatMessage> =
        buildList {
            add(system)
            addAll(history())
            addAll(fresh)
        }

    /**
     * 把锚钉上。[promptTokens] 是提供方回报的真数，[covered] 是**这次请求里还没进历史的那几条**。
     *
     * `covered` 是防重复计数的：这一次的 `fresh` 已经算在 [promptTokens] 里了，如果 `anchoredThrough`
     * 只推到消息总数减去 `covered`，那么下一轮估算会把它**再加一遍**。一个 1.5K 的 prompt 被算两次，
     * 在 3K 的请求上就是五成的虚高——阈值会因此提前一半触发，而症状是"它怎么这么早就开始压了"。
     */
    fun anchor(promptTokens: Int, covered: Int) {
        anchorTokens = promptTokens
        anchoredThrough = totalMessages() + covered
    }

    /**
     * 一轮结束，把这一轮产生的消息接上去。
     *
     * **工具调用与结果必须一起接**——只留 `say` 里那句话会让消息数组变成 tool-call 悬空，而 DeepSeek
     * 直接 400（§14.31 踩过一次空 `tool_calls`）。
     */
    fun commit(messages: List<ChatMessage>) {
        messages.forEach { pieces += RawPiece(nextPos++, it) }
    }

    /**
     * 估算**下一次**请求的 prompt 会多大。
     *
     * 有锚就用锚 + 增量；没锚（第一次唤醒）才整体估一遍。
     */
    fun estimate(system: ChatMessage, fresh: List<ChatMessage>): Int {
        val flattened = history()
        return if (anchorTokens < 0) {
            estimateTokens(listOf(system)) + estimateTokens(flattened) + estimateTokens(fresh)
        } else {
            // **锚是"上一次请求的 prompt"**，而 prompt = system + 历史 + 当时的 fresh。所以增量是
            // 从那之后新进历史的那些消息。
            anchorTokens + estimateTokens(flattened.drop(anchoredThrough)) + estimateTokens(fresh)
        }
    }

    /** 现在这个大小，够不够触发一次压缩（§6.1）。 */
    fun overThreshold(settings: ContextSettings, system: ChatMessage, fresh: List<ChatMessage>): Boolean =
        estimate(system, fresh) >= settings.compressAfterTokens

    // ── 压缩 ───────────────────────────────────────────────────────────────────────

    /**
     * **可以压的那一段**：一段连续的位置区间，以及它里面有几条。
     *
     * 「可以压」的完整判据见 [compact]。这里挑的是**最老的那一段**——最老的事最该被压扁，这与"自然遗忘"
     * 是同一件事。
     *
     * @property from 位置号（含）。
     * @property to 位置号（含）。
     * @property messages 那一段里有多少条消息。
     * @property oldest 那一段里最早那条消息的**摘要级别**：全是原文就是 0。
     */
    data class Range(val from: Long, val to: Long, val messages: Int, val oldest: Int) {
        /** 压出来的是第几级摘要：压原文出 1 级，压 1 级摘要出 2 级，依此类推。 */
        val tier: Int get() = oldest + 1
    }

    /**
     * 挑一段**建议压**的范围给模型看。
     *
     * 规则三条，每一条都对应一种"不说清就会出事"：
     *
     *  1. **最老的那些优先**——最老的事最该被压扁。
     *  2. **最近 [PRESERVE] 条一律不动**。丢掉最新的等于丢掉正在被回应的东西，那不是更小的请求，是
     *     另一个请求（§6.1 的裁剪规则之二）。
     *  3. **不能切在工具调用的中间**——收尾那几个位置要能安全地当成一个切点，否则消息数组会悬空。
     *
     * 找不到就返回 `null`（历史还太短），那时提示词里一个字都不提。
     */
    fun suggestRange(): Range? {
        if (pieces.size <= PRESERVE) return null
        val cuttable = pieces.size - PRESERVE
        var end = cuttable
        while (end < pieces.size && pieces[end].isToolResult()) end++
        if (end == 0) return null

        val taken = pieces.take(end)
        val messages = taken.sumOf { it.weight() }
        if (messages < MIN_RANGE_MESSAGES) return null
        return Range(
            from = taken.first().pos,
            to = taken.last().pos,
            messages = messages,
            oldest = taken.filterIsInstance<SummaryPiece>().maxOfOrNull { it.tier } ?: 0,
        )
    }

    /** 压缩失败的理由。**它能原样退给模型**——所以每一条都要说清"该怎么改"（§1.3）。 */
    sealed interface Refused {
        data class Bad(val why: String) : Refused
    }

    /**
     * **把一段换成一条摘要**（§6.1、§14.39）。这是"自然遗忘"唯一真正发生的地方。
     *
     * ## 为什么不许切在工具调用中间
     *
     * 一条带 `tool_calls` 的助手消息后面必须跟着它的每一条工具结果。切在中间，消息数组就悬空了，而
     * DeepSeek 对它的反应是 **400**（§14.31 踩过一次空 `tool_calls`）。dsh 的内核把这条叫做
     * `toolPairingBalancedBefore/After`，而它在本会话里真的拒过我两次——原话是
     * `no tool-pairing-balanced range around seq 44..65`。
     *
     * @return `null` 表示压成了；否则是拒绝的理由。
     */
    fun compact(from: Long, to: Long, topic: String, text: String): Refused? {
        if (text.isBlank()) return Refused.Bad("摘要正文是空的，那样压完这一段就等于没了。")
        if (from > to) return Refused.Bad("起点必须不晚于终点（收到 $from..$to）。")

        val first = pieces.indexOfFirst { it.pos == from }
        val last = pieces.indexOfLast { it.pos == to }
        if (first < 0) return Refused.Bad("没有位置 h$from 这么一条。")
        if (last < 0) return Refused.Bad("没有位置 h$to 这么一条。")
        if (last < first) return Refused.Bad("h$from 在 h$to 后面——范围反了。")

        val taken = pieces.subList(first, last + 1).toList()
        if (taken.sumOf { it.weight() } < MIN_RANGE_MESSAGES) {
            return Refused.Bad("这一段太短（不足 $MIN_RANGE_MESSAGES 条），压了反而更长。往更早、更长的一段压。")
        }

        // **边界平衡。** 切点落在一条工具结果的中间，就等于把那个函数的调用与结果拆开了。
        if (taken.last().isToolCall()) {
            return Refused.Bad("这一段以一次工具调用结尾，而它的结果还在后面——切在这里会让消息悬空。把范围往后挪一条。")
        }
        if (taken.first().isToolResult()) {
            return Refused.Bad("这一段以一条工具结果开头，而调用它的那条还在前面——把范围往前挪一条。")
        }

        val tier = (taken.filterIsInstance<SummaryPiece>().maxOfOrNull { it.tier } ?: 0) + 1
        val block = SummaryPiece(
            pos = taken.first().pos,
            coveredThrough = taken.last().pos,
            blockId = "b${nextBlock++}",
            tier = tier,
            topic = topic.trim(),
            text = text.trim(),
            covered = taken.sumOf { it.weight() },
        )
        pieces.removeAll(taken)
        pieces.add(first, block)
        return null
    }

    companion object {
        /**
         * 最近的这几段一律不进压缩范围。
         *
         * §6.1 的裁剪规则之二：**最新一轮无论多大都保留**——丢掉它等于丢掉正在被回应的东西，那不是更小
         * 的请求，是另一个请求。
         */
        const val PRESERVE = 4

        /**
         * 一段至少要覆盖几条消息才值得压。
         *
         * 与 dsh 内核那条 `minCompressRange` 同一条道理（它按 token 算，我们按条算——这里是条数的一个粗
         * 下限）。低于它，一条摘要反而比原文更长，压了是净亏。
         */
        const val MIN_RANGE_MESSAGES = 6

        /** 照着一份快照重建（读档那条路，§14.49）。**它的位置号、块号、锚都跟着走。** */
        fun from(snapshot: ConversationSnapshot): Conversation = Conversation(snapshot)
    }

    /**
     * 摊平之后的条数——**锚要用它**，因为锚比的是"上一次请求里那些 message"，而一段渲染成一条消息。
     *
     * 它与 [weight] 是**两个不同的度量**，别混：这一条数的是"发出去几条"，而那一条数的是"背后原来是
     * 几条原文"。压缩的下限用后者，锚用前者。
     */
    private fun totalMessages(): Int = pieces.size
}

/** 一段折算成几条消息：原文一条，摘要是**它背后盖住的那些条数**（见 [SummaryPiece.covered]）。 */
private fun Piece.weight(): Int = when (this) {
    is RawPiece -> 1
    is SummaryPiece -> covered
}

/**
 * 这一段渲染成的那条消息。摘要渲染成一条 **user** 消息，而且**前面带一行标源**。
 *
 * ## 那行标源不是客套
 *
 * 摘要是模型自己写的，而它可能在里面写下"玩家答应给矮人一坛酒"这种**义务句**。那行标源的作用是**阻止
 * 模型把摘要里的义务句当成玩家的原话直接执行**——这是 dsh 那套里一条便宜的防御，原话就是下面那句英文。
 * 中文那一句跟着它，是给模型解释"那行字是什么意思"。
 */
private fun Piece.message(): ChatMessage? = when (this) {
    is RawPiece -> message
    is SummaryPiece -> ChatMessage.user(
        buildString {
            appendLine(SUMMARY_GUARD)
            appendLine("【这是一条摘要，覆盖了 h$pos–h$coveredThrough，第 $tier 级${if (topic.isEmpty()) "" else "，主题：$topic"}】")
            appendLine(text)
        }.trimEnd(),
    )
}

/**
 * 摘要的标源行。**照抄 dsh 那一条**（§12 第 47 项）——它不是文案，是一条协议。
 */
const val SUMMARY_GUARD: String =
    "[Model-written summary — not user words; re-verify any obligations before relying on them]"

private fun Piece.isToolCall(): Boolean =
    this is RawPiece && !message.toolCalls.isNullOrEmpty()

private fun Piece.isToolResult(): Boolean =
    this is RawPiece && message.role == "tool"

/**
 * 一个角色的对话历史，摊平成一份可序列化的东西（§14.49）。
 *
 * 三个计数必须一起走：[nextPos] 是下一段的位置号（读回来不复用旧号），[nextBlock] 是下一个块号
 * （`b1`、`b2`……），[anchorTokens] 与 [anchoredThrough] 是"上次请求到底多大"那个锚——丢了它，
 * 读回来的第一轮会把上下文估小，200 轮之内就该压的一次都不压。
 */
@Serializable
data class ConversationSnapshot(
    val ownerId: String,
    val pieces: List<Piece> = emptyList(),
    val nextPos: Long = 1L,
    val nextBlock: Long = 1L,
    val anchorTokens: Int = -1,
    val anchoredThrough: Int = 0,
)

/**
 * 每个角色一份对话历史（§6.1）。
 *
 * 按 `ownerId` 取，取不到就现开一份——于是"这个角色有没有历史"不是一件要维护的状态，而是**第一次唤醒
 * 时自然长出来的**。这与 §3.2 那条「知识不是分发的，是当场授予的」是同一种做法。
 */
class Conversations {

    private val byOwner = mutableMapOf<String, Conversation>()

    fun of(ownerId: String): Conversation = byOwner.getOrPut(ownerId) { Conversation(ownerId) }

    /** 现在有几位在册（探针与测试用；它是**曾经醒过**的角色数，不是世界里的角色数）。 */
    val size: Int get() = byOwner.size

    /**
     * **在册的那些角色 id，按 id 排。**
     *
     * 存档要用它——而 `byOwner` 是私有的，在这之前**没有任何办法问出"都有谁"**（§14.49）。排序是为了
     * 让同一份世界每次存出来的文件逐字节一样，于是两份存档能不能对得上变成一件可以比的事。
     */
    fun ownerIds(): List<String> = byOwner.keys.sorted()

    /** 整份交出去，给存档用（按 [ownerIds] 的顺序，于是同一份历史每次存出来一样）。 */
    fun snapshot(): List<ConversationSnapshot> = ownerIds().map { byOwner.getValue(it).snapshot() }

    /**
     * 整份收回来。**先清空**：读档不是"把新历史混进旧的"。
     *
     * 收回来的是**现成的 [Conversation]**，而不是"照着快照重建"——那三个私有计数只有它自己装得对
     * （[Conversation.snapshot] 那边有同一条理由）。
     */
    fun restore(snapshots: List<ConversationSnapshot>) {
        byOwner.clear()
        snapshots.forEach { byOwner[it.ownerId] = Conversation.from(it) }
    }

    fun clear() = byOwner.clear()
}
