package io.github.k1midi0.infinitesimal.save

import io.github.k1midi0.infinitesimal.image.appendTextToFile
import io.github.k1midi0.infinitesimal.llm.Exchange
import io.github.k1midi0.infinitesimal.llm.Usage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * **一局的账**（§14.56）：与存档同名同目录的两份文件。
 *
 * ```
 * world.infsave.json        存档（世界快照 + 对话 + 生成包）
 * world.infsave.log         给人读的账：每一段一行、每一趟调用一行、世界里发生的事
 * world.infsave.trace.jsonl 一行一趟调用，**装完整提示词与回答**
 * ```
 *
 * ## 为什么账要跟着存档走
 *
 * 因为**这一局的读数只有对着这一局才读得懂**：账里写着"第 1 天 18:15"、"dwarf 到了 (1000,200)"，
 * 而离开那份存档这些东西无处可对。用户定的形状就是这一条：与存档同目录、同名前缀，一局一份、下一局
 * 不会盖掉上一局。
 *
 * ## 两份文件的分工
 *
 * 人读那一份**一眼能看完一局**（几十行到几百行）；机器那一份是**唯一的原始证据**——提示词是每一处
 * 在自己那一层现拼的，拼完就散了，所以"它当时看见了什么"只有这里查得到。两份分开是因为它们的读者
 * 不同：`.log` 是拿去读的，`.trace.jsonl` 是拿去 grep 的（一行一个 JSON 对象，`\n` 与 `"` 都被转义，
 * 所以一行确实是一行）。
 *
 * ## 一条写入纪律：**单写者**
 *
 * 调用它的地方多而杂（界面那一侧写事件、导演在后台写、哨兵在另一个协程里写），而追加写不是线程安全
 * 的。所以这里**不直接写文件**：所有的行进一个无界队列，由一个协程按顺序取出来追加。于是
 *  - 调用方可以**随时、从任何线程**写（[line] / [section] / [exchange] 都不是挂起函数）；
 *  - 顺序就是发生的顺序（无界队列不丢东西，也不因为满了而乱序）；
 *  - 阻塞的 IO 只在那一个协程里发生，界面线程一次都不碰磁盘。
 *
 * 写失败**不抛到调用方**（磁盘满、目录没了）：账写不成不该把这一局弄坏——它只是把那一行丢掉，并在
 * 控制台留一句。这与存档那条路正好相反（存档失败要说出来，因为它丢的是进度）。
 *
 * ## 一个真踩过的坑：那个写者**一直活着**
 *
 * 它等的是"队列被关掉"，所以**谁把这份账挂在"等活干完"的那个 job 下面，谁就会永远等不完**——
 * `settle()` 那种"把 children 全 join 掉"的写法会卡在这儿（§14.56 那一支测试第一次就是这么挂住的：
 * 七分钟没回来）。所以要等账落盘就用 [flush]，**不要等它的协程结束**；在入口那一层它挂在界面的
 * composition 上，跟着这一局一起结束。
 */
class PlayLog internal constructor(
    /** 人读的那一份的完整路径。 */
    val logPath: String,
    /** 原始证据那一份的完整路径。 */
    val tracePath: String,
    scope: CoroutineScope,
) {

    private sealed interface Entry {
        class Line(val text: String) : Entry
        class Section(val title: String) : Entry
        class Call(val exchange: Exchange) : Entry
        class Settle(val ack: CompletableDeferred<Unit>) : Entry
    }

    private val queue = Channel<Entry>(Channel.UNLIMITED)

    /** 到此刻为止的总账。只有写者那一个协程碰它。 */
    private var totalCalls = 0
    private var totalCost = 0.0
    private var openSection: SectionTally? = null

    init {
        scope.launch(Dispatchers.IO) {
            for (entry in queue) {
                try {
                    when (entry) {
                        is Entry.Line -> append(logPath, entry.text + "\n")
                        is Entry.Section -> {
                            settle()
                            openSection = SectionTally()
                            append(logPath, "\n── ${entry.title} ──\n")
                        }

                        is Entry.Call -> record(entry.exchange)

                        is Entry.Settle -> {
                            settle()
                            append(logPath, "（到此刻合计：$totalCalls 次调用、约 ${cost(totalCost)}）\n")
                            entry.ack.complete(Unit)
                        }
                    }
                } catch (cause: Throwable) {
                    // 丢一行账，不弄坏这一局。
                    println("[账写不下去了] ${cause.message ?: cause::class.simpleName}")
                }
            }
        }
    }

    /** 世界里发生的一件事（玩家看得见的那一栏就是按这个顺序长出来的）。 */
    fun line(text: String) {
        queue.trySend(Entry.Line(text))
    }

    /**
     * 开一段。**开新段会把上一段结算掉**（那一段花了多少次调用、多少 token、多少钱）。
     *
     * 会话在每一次"玩家按下什么"的开头调它一次（§14.56），于是账里的分段就是玩家眼里的分段。
     */
    fun section(title: String) {
        queue.trySend(Entry.Section(title))
    }

    /** 抄一趟调用。它是 [io.github.k1midi0.infinitesimal.llm.LlmTrace] 装上去的那一只观察者。 */
    fun exchange(exchange: Exchange) {
        queue.trySend(Entry.Call(exchange))
    }

    /**
     * 等这一批账真的落到盘上。**写者已经死了就等不到**——所以它有超时，不会把退出流程挂住。
     */
    suspend fun flush() {
        val ack = CompletableDeferred<Unit>()
        if (queue.trySend(Entry.Settle(ack)).isFailure) return
        withTimeoutOrNull(FLUSH_TIMEOUT_MS) { ack.await() }
    }

    private fun record(exchange: Exchange) {
        totalCalls++
        val tally = openSection
        val usage = exchange.usage

        val tokens = usage?.let {
            "提示 ${it.promptTokens}（命中 ${it.promptCacheHitTokens} / 未命中 ${missOf(it)}）｜输出 ${it.completionTokens}"
        } ?: "（没回 token）"

        val what = if (exchange.failure != null) {
            "**失败**：${exchange.failure}"
        } else if (exchange.calls.isEmpty()) {
            "（没有工具调用）"
        } else {
            exchange.calls.joinToString("、") { it.function.name }
        }

        append(
            logPath,
            "· #$totalCalls｜${exchange.pool.ifEmpty { "（没配池）" }}｜${exchange.elapsedMs} ms｜$tokens｜$what\n",
        )
        append(tracePath, JSON.encodeToString(Exchange.serializer(), exchange) + "\n")

        val cost = costOf(usage)
        totalCost += cost
        tally?.let {
            it.calls++
            it.millis += exchange.elapsedMs
            it.promptTokens += usage?.promptTokens ?: 0
            it.hitTokens += usage?.promptCacheHitTokens ?: 0
            it.completionTokens += usage?.completionTokens ?: 0
            it.cost += cost
        }
    }

    /** 把开着的那一段结算成一行。没有开着的段就什么都不写。 */
    private fun settle() {
        val tally = openSection ?: return
        openSection = null
        append(
            logPath,
            "（这一段：${tally.calls} 次调用、${tally.millis / 1000.0} 秒、" +
                "提示 ${tally.promptTokens}（命中 ${tally.hitTokens}）、输出 ${tally.completionTokens}、" +
                "约 ${cost(tally.cost)}）\n",
        )
    }

    private fun append(path: String, text: String) {
        appendTextToFile(path, text)
    }

    private class SectionTally {
        var calls = 0
        var millis = 0L
        var promptTokens = 0
        var hitTokens = 0
        var completionTokens = 0
        var cost = 0.0
    }

    companion object {

        /** 人读那一份的后缀。 */
        const val LOG_SUFFIX: String = ".log"

        /** 原始证据那一份的后缀。 */
        const val TRACE_SUFFIX: String = ".trace.jsonl"

        /**
         * 与存档同名同目录的那一份账。
         *
         * 存档名以 `.json` 结尾时把它去掉（`world.infsave.json` → `world.infsave.log`）——不然那两个
         * 文件会变成 `world.infsave.json.log`，看起来像另一份东西的附属品。
         */
        fun beside(savePath: String, scope: CoroutineScope): PlayLog {
            val base = savePath.removeSuffix(".json")
            return PlayLog(base + LOG_SUFFIX, base + TRACE_SUFFIX, scope)
        }

        /**
         * 端点报的两格命中/未命中，缺的时候补出来。
         *
         * **它不假装精确**：两格都缺（有的响应不带这两个字段）时按"全部未命中"算，那是**偏贵**的那一
         * 边——读数用来做决定，宁可估高。
         */
        internal fun missOf(usage: Usage): Int = when {
            usage.promptCacheMissTokens > 0 -> usage.promptCacheMissTokens
            usage.promptCacheHitTokens > 0 -> (usage.promptTokens - usage.promptCacheHitTokens).coerceAtLeast(0)
            else -> usage.promptTokens
        }

        /**
         * 这一趟大约花了多少钱（元）。**按高峰价**（§10.2）：命中 0.04 / 未命中 2 / 输出 8（元每百万）。
         *
         * 它是一个估算：真正的账单在端点那一侧，而这里要回答的只是"这一局是不是几十块"。
         */
        internal fun costOf(usage: Usage?): Double {
            if (usage == null) return 0.0
            return (usage.promptCacheHitTokens * HIT_YUAN + missOf(usage) * MISS_YUAN +
                usage.completionTokens * OUT_YUAN) / 1_000_000.0
        }

        /** 一分钱以下不显示成 0.00 元——那会让"花了多少"看起来像没花。 */
        internal fun cost(yuan: Double): String = if (yuan < 0.01) {
            "${(yuan * 1000).roundToInt() / 10.0} 厘"
        } else {
            "${(yuan * 100).roundToInt() / 100.0} 元"
        }

        private const val HIT_YUAN = 0.04
        private const val MISS_YUAN = 2.0
        private const val OUT_YUAN = 8.0

        private const val FLUSH_TIMEOUT_MS = 5_000L

        private val JSON = Json { encodeDefaults = true; explicitNulls = false }
    }
}
