package io.github.k1midi0.infinitesimal.judge

/**
 * 这一次判定里 `distance` 被问过哪些对。**与 [CalcLog] / [ShapeLog] 同一个位置、同一条理由**：
 * 它是这次动作的属性，不是某一步的属性，所以两步共用一份、按顺序记。
 *
 * 留着它是为了能回答一个具体的诊断问题：**"它到底问没问"**。判「够不够得着」而不问距离，是实测里
 * 最贵的那种错——它会自己拿清单里的坐标推式子，为此烧掉四千多个思维 token（§14.24）。账上有一对
 * 还是零对，一眼就能分开；而"这一轮慢了"本身分不开。
 */
class DistanceLog {

    private val asked = mutableListOf<String>()
    private val answered = mutableListOf<String>()

    /** 按调用顺序，形如 `player→wine_cup`。空列表 = 一次都没问。 */
    val pairs: List<String> get() = asked

    /**
     * 问过的那些答案的原文，按顺序。
     *
     * **第二步要把它们带上**——同一次动作的两步看的是同一个世界，第一步问过的，第二步没有理由再问
     * 一遍。实测里那正是一次纯粹的浪费：同一次判定里 `player→wine_cup` 被问了两遍，多烧一整次往返
     * （§14.25）。两步共用一份 [DistanceLog] 本来就是这个意思，只是原先只记了"问过什么"、没记答案。
     */
    val answers: List<String> get() = answered

    internal fun record(a: String, b: String, answer: String) {
        asked += "$a→$b"
        answered += answer
    }
}
