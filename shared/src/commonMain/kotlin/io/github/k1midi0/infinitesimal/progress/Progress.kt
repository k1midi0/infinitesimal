package io.github.k1midi0.infinitesimal.progress

/**
 * **"现在在做什么"那一行字**（§14.58）。
 *
 * ## 它为什么是一条通道，而不是每个类的一个构造参数
 *
 * 一趟长地形 + 建模是这样一层套一层的：`GameSession` → 导演 → `TerrainGenerator` → `ShapeModeler`，
 * 而"正在建模第几件"这件事只有最里面那一层知道。要把它送到界面那一行字上，就得让这一格参数穿过
 * 四五个构造器——而以后每添一个会干长活的段都要记得再穿一次（§14.55 那个"数手的句子连着漏了两刀"
 * 就是这类病的证据）。所以与 [io.github.k1midi0.infinitesimal.llm.LlmTrace] 同一个形状、同一个代价：
 *
 * 1. **默认没装**（[sink] 是 null）——没装时 [report] 什么都不做，测试与探针一个字都不多写；
 * 2. **装它的地方是入口**（桌面 App），它拿得到会话，于是知道该把这行字交给谁；
 * 3. **装了就负责卸**——测试里装在它自己那一支、卸在 `finally`。
 *
 * ## 它不是世界规则，也不许影响任何判断
 *
 * 报告的人只管说一句人话；**没有任何一处逻辑读它**，装不装它对这个世界的演化一点影响都没有。
 * [report] 的实现里什么都不抛——一个进度把这一轮弄坏，比没有进度糟得多。
 *
 * ## 它和账（[io.github.k1midi0.infinitesimal.save.PlayLog]）是两样东西
 *
 * 账是**事后**读的（一局一份文件）；这一行是**当场**给人看的（它会被下一句覆盖）。同一个事实常常
 * 两条都值得说一次，但它们的寿命与读者完全不同，所以不共用一个通道。
 */
object Progress {

    @Volatile
    var sink: ((String) -> Unit)? = null
        private set

    /** 装一个听的人。**装了要卸**（[uninstall]）。 */
    fun install(sink: (String) -> Unit) {
        this.sink = sink
    }

    fun uninstall() {
        sink = null
    }

    /** 说一句"现在在做什么"。没装听的人时它什么都不做；听的人自己出问题也不许往回抛。 */
    fun report(text: String) {
        val listener = sink ?: return
        try {
            listener(text)
        } catch (cause: Throwable) {
            // 与账那一条同一个理由：一行状态字不许把这一轮弄坏。
            println("[状态那一行没送出去] ${cause.message ?: cause::class.simpleName}")
        }
    }
}
