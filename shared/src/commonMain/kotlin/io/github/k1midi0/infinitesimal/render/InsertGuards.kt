package io.github.k1midi0.infinitesimal.render

import io.github.k1midi0.infinitesimal.text.SentenceSegmenter

/**
 * 插入的守卫（§7.2）。
 *
 * 它是"句子列表"这个数据模型的配套：既然写操作被限制成"插一句"，那就要能判出"它插的到底是不是
 * 一句"。没有这一层，模型完全可以声明"我要插一句"，然后把整段重写塞进那个字段。
 *
 * 它同时是 §14.4 那条的落点。实测里 FIM 曾经把端点的**安全审核标记**当成补全结果返回：
 *
 * ```
 * <ds_safety>[用户未成年]否
 * [分类]其他
 * [判定]用户请求为木桌补充细节，内容属于一般家具描写……
 * ```
 *
 * 而它落在 `finish_reason=stop` 上，**看起来完全正常**。那段文本 94 字且含多个句末标点，会被
 * "含 N 个句子"拦下——这就是守卫存在的理由。
 */
object InsertGuards {

    /**
     * 一句话的长度上限。
     *
     * 它不是审美判断，是**结构判断**：超过这个长度基本可以断定模型把一整段塞了进来。中文一句三四十
     * 字是常态，120 留了三倍余量。
     */
    private const val MAX_CHARS = 120

    fun check(existing: List<String>, sentence: String): List<String> = buildList {
        val text = sentence.trim()

        if (text.isEmpty()) {
            // 实测里 FIM 真的会返回空——不是报错，是 `finish_reason=stop` 配一个空字符串。
            add("空句子")
            return@buildList
        }
        if (text.length > MAX_CHARS) {
            add("长度 ${text.length} 字，超过 $MAX_CHARS（像是把一整段塞进来了）")
        }
        if (SentenceSegmenter.split(text).size > 1) {
            add("含多个句子，要求一次只插一句")
        }
        if (existing.any { it.trim() == text }) {
            add("与已有句子完全重复")
        }
    }

    fun isClean(existing: List<String>, sentence: String): Boolean = check(existing, sentence).isEmpty()
}
