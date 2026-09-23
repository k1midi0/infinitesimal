package io.github.k1midi0.infinitesimal.render

/**
 * 渲染输出的抽查：有没有写到周边或位置关系（§7.6）。
 *
 * **它不是可靠的判定办法。** 词表永远不全，而"桌子对着吧台"完全可以用词表里没有的说法讲出来。
 * 真正起作用的是**输入里不给周边信息**（见 [Renderer.contextOf]）——没有素材，就写不出来。
 *
 * 它只有两个正当用途：开发期验证"裁掉输入之后输出真的干净了"，以及落库前的最后一道抽查。
 * 误报可以接受，所以有歧义的词（"立在""中央"这类既可能指物体自身、也可能指关系的）一律不收。
 */
object RenderGuards {

    private val RELATION_WORDS = listOf(
        "旁边", "边上", "对面", "挨着", "靠着", "朝着", "对着", "紧挨",
        "周围", "四周", "附近", "贴着", "搁在", "放在", "摆在",
        "一侧", "另一侧", "两旁",
    )

    fun check(text: String): List<String> =
        RELATION_WORDS.filter { text.contains(it) }.map { "疑似写了周边或位置关系：「$it」" }

    fun isClean(text: String): Boolean = check(text).isEmpty()
}
