package io.github.k1midi0.infinitesimal.config

/**
 * 上下文的预算（§6.1、§6.3、§14.39）。
 *
 * 它**不是**端点配置：它管的是"一段对话长到什么时候该被压一次"、以及"一个角色能记下多少事"，与用
 * 哪个模型、多少温度无关。分开也是为了让探针改得动它——[compressAfterTokens] 的默认值离现实很远
 * （见下），不调小的话压缩那条路在联机探针里永远跑不到。
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
    /**
     * **一个角色最多留几条活笔记**（§6.6）。活 = 没有被更新的笔记替代掉的。
     *
     * 上限不是装饰：**没有它，角色会把笔记写爆**——而写爆的症状不是报错，是提示词里那一节越来越长，
     * 长到把真正要紧的东西挤出视野。到顶之后不是静默丢弃，而是**打回，并让它点名替代掉一条**：
     * 只有它自己知道哪条不再算了（§6.4）。
     */
    val noteLimit: Int = DEFAULT_NOTE_LIMIT,
    /**
     * **注进提示词的笔记最多几个字**（§6.5）。
     *
     * 与 [noteLimit] 是两个量：条数是"它能记住几件事"，字数是"这一节最多占多大的提示词"。都设，是因为
     * 超长的两条笔记能把条数上限绕过去。
     *
     * 截断**看得见**：被截掉的部分会留下一行说明，而不是静默消失——静默消失会让模型以为"我没记过这
     * 件事"，然后重新写一条，于是它越记越多、越多越看不见（§6.5 那条「可见的截断标记」）。
     */
    val noteBudgetChars: Int = DEFAULT_NOTE_BUDGET,
) {
    init {
        require(compressAfterTokens > 0) { "压缩阈值得是正数，收到 $compressAfterTokens" }
        require(noteLimit > 0) { "笔记条数上限得是正数，收到 $noteLimit" }
        require(noteBudgetChars > 0) { "笔记注入预算得是正数，收到 $noteBudgetChars" }
    }

    companion object {
        const val DEFAULT_COMPRESS_AFTER: Int = 400_000

        /**
         * 二十条。
         *
         * §6.6 只说「每角色 N 条」，N 没定，这是第一次给出一个数。挑二十的理由是**注入那一侧的账单**：
         * 一条笔记一两句话（约 20–60 字），二十条满打满算一千二百字，相比之下这个角色一轮的 prompt 是
         * 三千 token 量级（§14.40 量过）——也就是说笔记即便写满，也只占它一个很小的零头。
         *
         * 而"写满二十条"本身是件难事：每回合至多一条（见 `Npc` 里那道频率闸），而它得有二十件值得
         * 一辈子记住的事。
         */
        const val DEFAULT_NOTE_LIMIT: Int = 20

        /**
         * 两千字。
         *
         * 比 [DEFAULT_NOTE_LIMIT] 条写满的账略宽一点，所以正常情况下这个预算永远碰不到——它是给"某一条
         * 笔记特别长"准备的，不是给"笔记很多"准备的。**它碰到了就说明有人在写长篇**，而那本身就值得
         * 在提示词里说一句。
         */
        const val DEFAULT_NOTE_BUDGET: Int = 2_000
    }
}
