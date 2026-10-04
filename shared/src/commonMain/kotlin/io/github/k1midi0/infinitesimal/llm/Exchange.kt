package io.github.k1midi0.infinitesimal.llm

import kotlinx.serialization.Serializable

/**
 * **一次调用的原始记录**（§14.56）：这一趟发出去的那一整串消息、它当时看得见哪几只工具、回答与思考
 * 的原话、它调了哪几只、端点回报的 token，以及这一趟花了多久。
 *
 * ## 它为什么存在
 *
 * 一局游戏跑起来之后，"它当时看见了什么"是**唯一查不出来的东西**——提示词是每一处在自己那一层现拼
 * 的（判定的框、NPC 的记忆窗口、导演那两档、哨兵那一问），拼完就散了；而所有"它怎么没做到"的问题
 * （它没读剧本池、它没写笔记、它把火当成没事）都要靠那一段原文才能回答。所以这一条记录**不是日志的
 * 装饰，是唯一的事后证据**。
 *
 * ## 它不参与任何判定
 *
 * **它是诊断，不是世界规则**（与 §37 那条"世界规则不许藏在代码里"同一条纪律的反面）：没有任何一处
 * 逻辑读它，装不装它对这个世界的演化一点影响都没有。默认没装（[LlmTrace.sink] 是 null），而装它的
 * 地方是入口那一层（桌面 App），所以测试与探针都不受影响。
 *
 * ## 一份记录里刻意没有的东西
 *
 * - **密钥**：它由 [LlmClient] 拿着，这一条记录从不碰它——日志文件会跟着人走，密钥不该跟着走。
 * - **附图的数据 URL**：图片在发出去那一刻才展开成 base64（`ChatMessage.toWire`），这里抄的是**应用
 *   那一层**的消息（只带文件名），所以一条记录不会被一张图撑成几兆。
 * - **工具的完整定义**：只记名字（[tools]）。"它看得见哪几只手"是问题，"那几只手的 JSON Schema 长
 *   什么样"是仓库里查得到的东西。
 */
@Serializable
data class Exchange(
    /** 墙钟起点（毫秒）。用它把调用与"第几格"对上。 */
    val startedAtMs: Long,
    val elapsedMs: Long,
    /** 落的是哪一个缓存池（`SystemId.userId`，如 `inf-judge`）；没配就是空串。**它是"这一段是谁花的钱"的唯一线索。** */
    val pool: String,
    val model: String,
    /** `thinking.type` 的原样（`disabled` / `enabled`），它才是端点读的那一格。 */
    val thinking: String,
    /**
     * 思考强度；思考关着时这里是 null（规则在 `ThinkingRequest.of`）。
     *
     * **它必须有默认值**：那份 trace 文件是紧凑写的（null 不写出去），而"这一格没出现"在解码时需要的
     * 正是一个默认值——少了它，**每一趟思考关着的调用都写得出、读不回来**（§14.56 那一支测试逮到的
     * 就是这个：写文件与读文件是两条路，只有把文件读回来才算验过）。
     */
    val effort: String? = null,    /** 这一趟发出去的整串消息，**应用那一层的样子**（附图还只是文件名）。 */
    val messages: List<ChatMessage>,
    /** 这一趟它看得见哪几只工具（只记名字）。不发 tools 的请求是空表。 */
    val tools: List<String>,
    /** 回答里的自由文本。 */
    val content: String,
    /** 思考过程。没有就是空串（它不保证出现，见 `ChatMessage.reasoningContent` 那段）。 */
    val reasoning: String,
    /** 这一趟它调了哪几只、参数原文。 */
    val calls: List<ToolCall>,
    /**
     * 端点回报的 token 账。流没走完就断了的时候是 null——那也是"这一趟花了钱而账不全"的真实样子。
     */
    val usage: Usage? = null,
    /** 失败时那一句话（配置没配好、端点拒绝、流断在中途）。成功时是 null。 */
    val failure: String? = null,
)

/**
 * **抄一份所有调用**的那个开关（§14.56）。
 *
 * ## 为什么是一个进程级的开关，而不是每个客户端的构造参数
 *
 * 因为一个 `GameSession` 会造出**七八个** `LlmClient`（判定两个、NPC、导演、哨兵、生成、建模、渲染），
 * 而它们各自在各自的便捷构造器里现造（`Judge(config)`、`Director(config)`…）。要让"每一次调用都被抄
 * 一份"落在那条路上，就得把这一格参数穿过七八个构造器——为一个诊断付那样的代价不划算，而且以后每添一
 * 个模型段都要记得再穿一次（§14.55 那个"数手的句子连着漏了两刀"就是这类病）。
 *
 * 一个可设的观察者只有一处代价：**它是全局的**，所以有两条纪律：
 *
 * 1. **默认是 null**，而且不许在产品路径上默认装它——没装时 [LlmClient] 走的就是原来那条路。
 * 2. **装了就负责卸**（[uninstall]）：测试里装过一次而忘了卸，后面每一条测试的调用都会往那个文件里写。
 *    装它的地方是入口（桌面 App），测试只在它自己那一支里装、在 finally 里卸。
 *
 * 抄出去的东西**绝不许反向影响这一轮**：观察者抛异常时 [LlmClient] 把它吞掉（见那里的注释）——一个
 * 诊断把游戏弄坏，比没有诊断糟得多。
 */
object LlmTrace {

    @Volatile
    var sink: ((Exchange) -> Unit)? = null
        private set

    fun install(sink: (Exchange) -> Unit) {
        this.sink = sink
    }

    fun uninstall() {
        sink = null
    }
}
