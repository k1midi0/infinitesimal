package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.llm.Usage

/**
 * 一次模型调用各花了多久，拆成两截。
 *
 * **为什么要有这个文件。** 延迟是这套东西里唯一一直只被量了总数的部分。此前手里只有"这一轮
 * 一共几秒"，于是"钱花在第一步还是第二步""尾部那二十秒是模型在想、还是在等端点"这两件事
 * **全靠猜**。每一次调用拆成：
 *
 * - [firstEventMs] —— 从发请求到**第一个事件**。这一截模型还没开始吐东西，全是排队与预填充。
 * - [elapsedMs] - [firstEventMs] —— 生成与思考。思考模式下里面绝大部分是思维链。
 *
 * 于是"该治哪一截"变成一个读数，而不是一句判断。
 *
 * 它挂的位置与 [CalcLog] / [ShapeLog] 相同：**是这次动作的属性，不是某一步的属性**，
 * 所以两步共用一份、按顺序记。
 */
data class RequestTime(
    val stage: Stage,
    /** 这一段的第几次尝试（1 起）。**大于 1 就是重试**——一次重试就是一整次模型调用。 */
    val attempt: Int,
    /** 这一次 [Judge.call] 里的第几个来回（1 起）。**大于 1 就是查询手多要的那一轮**。 */
    val round: Int,
    /** 从发请求到收完。 */
    val elapsedMs: Long,
    /** 从发请求到**第一个事件**。 */
    val firstEventMs: Long,
    /** 这一轮模型调了哪些手。空列表 = 什么都没调（它只说了话）。 */
    val tools: List<String>,
    /** 端点报的用量。**思考 token 是唯一能把"等"拆开的量**，见 [reasoningTokens]。 */
    val usage: Usage? = null,
) {

    /** 生成与思考那一截。 */
    val thinkingMs: Long get() = (elapsedMs - firstEventMs).coerceAtLeast(0)

    /**
     * 思维链 token。
     *
     * **为什么必须记它。** 实测（§14.22）发现 [firstEventMs] 与 [thinkingMs] 这个二分法在这条
     * 端点上**名不副实**：23 次调用加起来"生成"只有 63 ms，也就是整条回复是**攒到最后一次吐出来**的
     * （流式确实开着，`bodyAsChannel` 逐块读，是服务端这么发的）。于是"等 9088 ms"里同时装着
     * 排队、预填充**和整条思维链的生成时间**，那个二分法分不开它。
     *
     * 能分开它的是这个数：思维链 token 除以墙钟，就是模型在思考时的实际吞吐。12 秒的那一次
     * 若报着 1400 个思维 token，那这 12 秒就是**模型在想**，治法在提示词；若报着 30 个，那
     * 12 秒就是**在排队**，治法在端点。同一个 12 秒，两种治法，靠这一个数分开。
     */
    val reasoningTokens: Int get() = usage?.completionTokensDetails?.reasoningTokens ?: 0

    /** 这一轮吐出来的总 token（含思维链）。 */
    val completionTokens: Int get() = usage?.completionTokens ?: 0

    val promptTokens: Int get() = usage?.promptTokens ?: 0

    /** 前缀缓存命中的 prompt token。**命中率是省钱那一半，与延迟无关。** */
    val cacheHitTokens: Int get() = usage?.promptCacheHitTokens ?: 0

    /** 每秒钟吐出多少个 token（含思维链）。**低于 20 就说明瓶颈不在模型吞吐上。** */
    val tokensPerSecond: Double
        get() = if (elapsedMs <= 0) 0.0 else completionTokens * 1000.0 / elapsedMs

    /**
     * 这一轮是**重试多要的**，不是查询多要的。
     *
     * 两者都会让一次判定多烧一整次调用，但治法相反：重试要治的可能是提示词（模型第一次没照做），
     * 查询多要的那一轮要治的是"它为什么需要多看一眼"。
     */
    val isRetry: Boolean get() = attempt > 1 && round == 1

    /** 这一轮是查询手多要的。 */
    val isQueryRound: Boolean get() = round > 1
}

/**
 * 一次判定的时间账。见 [RequestTime] 里那段理由。
 *
 * 记的位置是 [Judge.call]——**每一次模型调用恰好记一条**，别的层都不记。这样"这一轮发了几次
 * 请求"与"这一轮记了几条"永远是同一个数，两边不会各说各话。
 */
class TimingLog {

    private val records = mutableListOf<RequestTime>()

    /** 按发生顺序。 */
    val all: List<RequestTime> get() = records

    val requests: Int get() = records.size
    val totalMs: Long get() = records.sumOf { it.elapsedMs }

    /** 排队与预填充那一截的合计。 */
    val waitingMs: Long get() = records.sumOf { it.firstEventMs }

    /** 生成与思考那一截的合计。 */
    val thinkingMs: Long get() = records.sumOf { it.thinkingMs }

    val retries: Int get() = records.count { it.isRetry }

    /** 查询手多要的那几轮。 */
    val queryRounds: Int get() = records.count { it.isQueryRound }

    /** 最慢的那一次。尾部延迟就是这个数，中位数藏不住它。 */
    val slowest: RequestTime? get() = records.maxByOrNull { it.elapsedMs }

    /** 思维链 token 合计。**这个数大就说明"慢"是模型在想。** */
    val reasoningTokens: Int get() = records.sumOf { it.reasoningTokens }

    val completionTokens: Int get() = records.sumOf { it.completionTokens }

    val promptTokens: Int get() = records.sumOf { it.promptTokens }

    val cacheHitTokens: Int get() = records.sumOf { it.cacheHitTokens }

    /**
     * 全部调用的实际吞吐（token/秒，含思维链）。
     *
     * **它是"瓶颈在不在模型这边"的判据**：读到一个极低的数（比如个位数），说明时间花在排队
     * 或预填充上，跟模型吐字多快无关；读到一个正常的数（几十），说明时间就是被思维链本身吃掉的。
     */
    val tokensPerSecond: Double
        get() = if (totalMs <= 0) 0.0 else completionTokens * 1000.0 / totalMs

    fun of(stage: Stage): List<RequestTime> = records.filter { it.stage == stage }

    fun totalMs(stage: Stage): Long = of(stage).sumOf { it.elapsedMs }

    internal fun record(
        stage: Stage,
        attempt: Int,
        round: Int,
        elapsedMs: Long,
        firstEventMs: Long,
        tools: List<String>,
        usage: Usage? = null,
    ) {
        records += RequestTime(stage, attempt, round, elapsedMs, firstEventMs, tools, usage)
    }

    internal fun clear() = records.clear()

    /**
     * 写成几行。探针与失败日志共用一份，免得两边各写一套对不上的说法。
     */
    fun report(): String = buildString {
        if (records.isEmpty()) {
            append("（一次模型调用都没有）")
            return@buildString
        }
        val align = of(Stage.ALIGN)
        val effect = of(Stage.EFFECT)
        append("${requests} 次调用，合计 $totalMs ms")
        append("（排队 $waitingMs + 生成 $thinkingMs）")
        append("；重试 $retries 次、查询多要 $queryRounds 轮")
        append('\n')
        append(
            "  输入 $promptTokens token（缓存命中 $cacheHitTokens）" +
                "　输出 $completionTokens token（其中思维链 $reasoningTokens）" +
                "　实测吞吐 ${(tokensPerSecond * 10).toInt() / 10.0} token/s",
        )
        append('\n')
        append(
            "  第一步 ${align.size} 次 ${align.sumOf { it.elapsedMs }} ms" +
                "　第二步 ${effect.size} 次 ${effect.sumOf { it.elapsedMs }} ms",
        )
        slowest?.let { append("\n  最慢的一次：${it.stage} 第 ${it.attempt} 次尝试，${it.elapsedMs} ms") }
        for (r in records) {
            append("\n  · ${pad(r.stage.name, 6)} 尝试 ${r.attempt} 第 ${r.round} 来回")
            append("　等 ${pad(r.firstEventMs.toString(), 6)} 生成 ${pad(r.thinkingMs.toString(), 6)}")
            append("　共 ${pad(r.elapsedMs.toString(), 6)} ms")
            append("　思 ${pad(r.reasoningTokens.toString(), 5)} 出 ${pad(r.completionTokens.toString(), 5)}")
            append("  ${r.tools.joinToString("+").ifEmpty { "（只说了话）" }}")
        }
    }

    private fun pad(text: String, width: Int) = text.padEnd(width)
}
