package io.github.k1midi0.infinitesimal.llm

import kotlin.math.ceil

/**
 * 估算一段文字的 token 数（§6.1、§14.39）。
 *
 * ## 为什么不照抄 dsh 那条
 *
 * DSH 的 token 统计是一个固定启发式：**四个字符一个 token**。它自己的 README 写着这条规则
 * 「**低估 CJK 文本与 JSON schema**」——而我们的提示词**通篇是中文**，照抄等于系统性少算。
 *
 * 算一笔账就看得清：用户定的压缩阈值是 **400K token**。按四字符一 token，那是 **160 万字符**；
 * 而中文实测约 0.6 token/字，换算回去是**约 96 万真实 token**——已经贴着 1M 的上下文窗口了。
 * 也就是说照抄那条规则，压缩会在窗口快要满的时候才触发一次。
 *
 * ## 这条规则本身
 *
 * - **CJK 字符**：一个约 0.6 个 token（`D:\fatfish\DeepSeek开发参考.md` 里的接口实测；§6.1 早先
 *   写的是"留余量按 1 字 1 token"，那条过于保守，会让阈值提前一半触发）。
 * - **其余字符**：四个一个 token——拉丁字母、数字、标点、以及 JSON 那些花括号，dsh 那条在这里是对的。
 *
 * ## 它是什么、不是什么
 *
 * 它是一个**估算**，不是计费口径。真正准的那个数每次响应都会回来（`usage.prompt_tokens`），所以
 * 压缩的判据用的是**那个真数当锚**、这条估算只负责"自锚点以来改了多少"（见 `npc/Conversations.kt`）。
 * 凭空拿它当绝对量是错的，差个一两成很正常。
 */
fun estimateTokens(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    for (ch in text) {
        if (ch.isCjk()) cjk++ else other++
    }
    // 0.6 = 3/5。用整数算，免得浮点的舍入在长文本上攒出偏。
    return (cjk * 3 + 4) / 5 + (other + 3) / 4
}

/**
 * 估算一整段消息的 token 数——**含每条消息的固定开销**。
 *
 * 那个开销不是拍的：dsh 的估算器也单独给它一项（"four characters per token **plus block and role
 * overhead**"）。理由与它一样——一次请求里每条消息都带角色标记与分隔符，几十条消息加起来不是小数目，
 * 而它**不随正文长短变化**，所以正文的估算里看不见它。
 */
fun estimateTokens(messages: List<ChatMessage>): Int =
    messages.sumOf { estimateTokens(it) }

/** 一条消息的估算：正文 + 角色开销 + 工具调用那些 JSON 参数。 */
fun estimateTokens(message: ChatMessage): Int {
    var total = PER_MESSAGE_OVERHEAD
    total += estimateTokens(message.content.orEmpty())
    total += estimateTokens(message.reasoningContent.orEmpty())
    total += estimateTokens(message.toolCallId.orEmpty())
    message.toolCalls?.forEach { call ->
        total += estimateTokens(call.function.name)
        total += estimateTokens(call.function.arguments)
        total += PER_MESSAGE_OVERHEAD
    }
    return total
}

/**
 * 一条消息的固定开销。
 *
 * 12 这个数来自 dsh 同一处的量级（它的估算器给每个 block 加一项固定结构价）。**它宁愿略高**：算少了
 * 会让压缩晚触发，而晚触发的后果是那一次请求真的撞上窗口上限——那时错误发生在模型那一侧，比我们早
 * 一点主动压要难查得多。
 */
private const val PER_MESSAGE_OVERHEAD = 12

/**
 * 这个字符算不算 CJK。
 *
 * 覆盖的是真正会出现的那几段：汉字主区、扩展 A、兼容区，以及中文标点与全角字符。**不做完整的
 * Unicode 分词**——那要么引一个依赖，要么写几百行，而收益只是估算准一点点；准的那一头有真数兜着。
 */
private fun Char.isCjk(): Boolean = when (code) {
    in 0x4E00..0x9FFF -> true   // 汉字主区
    in 0x3400..0x4DBF -> true   // 扩展 A
    in 0xF900..0xFAFF -> true   // 兼容汉字
    in 0x3000..0x303F -> true   // 中文标点（、。「」…）
    in 0xFF00..0xFFEF -> true   // 全角字符
    in 0x20000..0x2FA1F -> true // 扩展 B 及以后（稀少，但列上不花钱）
    else -> false
}
