package io.github.k1midi0.infinitesimal.npc

import io.github.k1midi0.infinitesimal.config.ContextSettings
import io.github.k1midi0.infinitesimal.llm.ChatMessage
import io.github.k1midi0.infinitesimal.llm.estimateTokens

/**
 * **一个角色的对话历史**（§6.1、§14.39）。
 *
 * ## 它为什么存在
 *
 * 在这之前，角色每次被唤醒都新建一段 messages——所以它每一句都从零开始，记得住"酒淡"只是因为有人把
 * 记录铺成了提示词（§14.37），不是因为它有过一场对话。用户对这一条的裁定是重做：
 *
 * > 应该是正常拼接的多轮对话上下文，超过阈值后压缩，压缩后的部分超过阈值就二次压缩
 *
 * 这个类就是「正常拼接」那一段：**只增不减**，直到 [ContextSettings.compressAfterTokens] 那条线被
 * 越过（压缩本身是下一刀的事，§14.39）。
 *
 * ## 大小怎么算：一个真数当锚，一条估算管增量
 *
 * 「上下文现在多大」这个问题有两半，而它们的答案来源不同：
 *
 *  - **上次请求到底多大** —— **提供方说了算**。每次响应都带 `usage.prompt_tokens`，那是真数，零成本。
 *  - **自那以后又添了多少** —— 只能估。而这一半只有新追加的那几条，误差不会随对话变长而累积。
 *
 * 所以不拿估算去猜绝对量（那会差一两成，而差在 400K 上就是几万）。这也正是 dsh 自己的做法——
 * 它的原话是「**Provider usage anchors a measurement**」。
 *
 * ## 它现在住在内存里
 *
 * `world/` 做持久化，而**存档本身还没做**（§12 第 33 项：世界现在完全不落盘）。所以这一层先跟着进程
 * 活着，等存档落地时再决定它进哪一份快照。写在这里是因为它迟早要搬——**别以为它已经存下来了**。
 */
class Conversation(val ownerId: String) {

    private val history = mutableListOf<ChatMessage>()

    /** 提供方回报的真实 prompt token 数。`-1` = 还没有过锚。 */
    private var anchorTokens = -1

    /** 锚覆盖到历史里的第几条（不含）。它之后的那些才算"自锚点以来的增量"。 */
    private var anchoredThrough = 0

    val size: Int get() = history.size

    fun history(): List<ChatMessage> = history.toList()

    /**
     * 组装这一次请求要发的 messages：**system 在最前，然后是全部历史，最后是这一次的新内容**。
     *
     * 顺序不能动。历史的尾部与这一次的开头是紧挨着的，而**前缀缓存认的就是这个**——旧的块一旦写进去
     * 就永不改变，所以缓存能一路命中到最新那一轮，只在最后 [fresh] 那里断开（§6.1）。
     */
    fun request(system: ChatMessage, fresh: List<ChatMessage>): List<ChatMessage> =
        buildList {
            add(system)
            addAll(history)
            addAll(fresh)
        }

    /**
     * 把锚钉上。[promptTokens] 是提供方回报的真数，[covered] 是**这次请求里还没进历史的那几条**。
     *
     * `covered` 这个参数是防重复计数的：这一次的 `fresh` 已经算在 [promptTokens] 里了，如果 `anchoredThrough`
     * 只推到 `history.size`，那么下一轮估算会把它**再加一遍**。一个 1.5K 的 prompt 被算两次，在 3K 的
     * 请求上就是五成的虚高——阈值会因此提前一半触发，而症状是"它怎么这么早就开始压了"。
     */
    fun anchor(promptTokens: Int, covered: Int) {
        anchorTokens = promptTokens
        anchoredThrough = history.size + covered
    }

    /**
     * 一轮结束，把这一轮产生的消息接上去。
     *
     * **只增不减**，而且**工具调用与结果必须一起接**——只留 `say` 里那句话会让消息数组变成 tool-call
     * 悬空，而 DeepSeek 直接 400（§14.31 踩过一次空 `tool_calls`）。
     */
    fun commit(messages: List<ChatMessage>) {
        history += messages
    }

    /**
     * 估算**下一次**请求的 prompt 会多大。
     *
     * 有锚就用锚 + 增量；没锚（第一次唤醒）才整体估一遍。
     */
    fun estimate(system: ChatMessage, fresh: List<ChatMessage>): Int =
        if (anchorTokens < 0) {
            estimateTokens(listOf(system)) + estimateTokens(history) + estimateTokens(fresh)
        } else {
            anchorTokens + estimateTokens(history.drop(anchoredThrough)) + estimateTokens(fresh)
        }

    /** 现在这个大小，够不够触发一次压缩（§6.1）。压缩本身还没做——这个判断先立在这里给下一刀用。 */
    fun overThreshold(settings: ContextSettings, system: ChatMessage, fresh: List<ChatMessage>): Boolean =
        estimate(system, fresh) >= settings.compressAfterTokens
}

/**
 * 每个角色一份对话历史（§6.1）。
 *
 * 按 `ownerId` 取，取不到就现开一份——于是"这个角色有没有历史"不是一件要维护的状态，而是**第一次唤醒
 * 时自然长出来的**。这一点与 §3.2 那条「知识不是分发的，是当场授予的」是同一种做法。
 */
class Conversations {

    private val byOwner = mutableMapOf<String, Conversation>()

    fun of(ownerId: String): Conversation = byOwner.getOrPut(ownerId) { Conversation(ownerId) }

    /** 现在有几位在册（探针与测试用；它是**曾经醒过**的角色数，不是世界里的角色数）。 */
    val size: Int get() = byOwner.size

    fun clear() = byOwner.clear()
}
