package io.github.k1midi0.infinitesimal.config

/**
 * 上下文的预算（§6.1、§14.39）。
 *
 * 它**不是**端点配置：它管的是"一段对话长到什么时候该被压一次"，与用哪个模型、多少温度无关。分开也是为了
 * 让探针改得动它——[compressAfterTokens] 的默认值离现实很远（见下），不调小的话压缩那条路在联机探针里
 * 永远跑不到。
 */
data class ContextSettings(
    /**
     * 一段对话估算超过这个 token 数，就该压一次。
     *
     * **400K 是用户定的**（2026-09-26）。这个数**故意离得远**：现在一个角色一轮的 prompt 大约 3K token，
     * 也就是说要一百多轮才会碰到它。这是刻意的——设计要的是"**让它真的能累积**"，而不是早早就开始压。
     *
     * 判据用的是**估算**，而锚是**提供方回报的真实 `prompt_tokens`**（每次响应都带）；见
     * `npc/Conversations.kt`。两者差着一层：真数管"上次请求到底多大"，估算管"自那以后又添了多少"。
     */
    val compressAfterTokens: Int = DEFAULT_COMPRESS_AFTER,
) {
    init {
        require(compressAfterTokens > 0) { "压缩阈值得是正数，收到 $compressAfterTokens" }
    }

    companion object {
        const val DEFAULT_COMPRESS_AFTER: Int = 400_000
    }
}
