package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.spatial.Region
import io.github.k1midi0.infinitesimal.spatial.RegionBuilder
import io.github.k1midi0.infinitesimal.spatial.RegionView
import io.github.k1midi0.infinitesimal.world.World

/**
 * 一次判定里，「看大一点」这件事的全部状态。
 *
 * ## 为什么是一个对象而不是一个字符串
 *
 * 因为这一片空间有**两个投影**：给模型看的那一份文字（[text]），和给代码用的那一份几何
 * （[region]）。早先的做法是调用方拼好一段 `scene` 字符串递给判定，那样代码手里就只剩文字了
 * ——而"模型的坐标是编的"这件事，正是没有几何可用的时候发生的（附录 A）。
 *
 * ## 预算是两步共享的
 *
 * 第一步扩过之后，第二步拿到的是已经大了的那个框。扩的是"这一次动作看得见多远"，那是这一次动作的
 * 属性，不是某一步的属性。
 *
 * **不封顶。** 所以这里只有一个计数器，没有上限。跑几次、每步扩了几次，由 [widenings] 报出来——
 * 没有上限的东西必须能被观测，否则"它会不会一直扩"永远是个猜。
 */
class ViewBudget(
    private val world: World,
    private val actorId: String,
    centers: List<String>,
) {
    /** 发起者**永远**是中心之一：框里不含发起者的话，它拿到的坐标范围里就没有它自己。 */
    private val centers: List<String> = (listOf(actorId) + centers).distinct()

    /** 这一次判定里，模型一共要求扩了几次。 */
    var widenings: Int = 0
        private set

    fun region(): Region = RegionBuilder.of(world, centers, widenings)

    fun text(): String = RegionView.render(world, region(), actorId)

    /**
     * 模型要看得更远。
     *
     * 返回的是**新的那一片的文字**，直接当工具结果灌回去——模型不需要知道扩了多少格，就像它不需要
     * 知道框是怎么算出来的一样（它对坐标本来就没有参照系，给它一个填数字的口子，等于把同一个问题
     * 换个地方再犯一次）。
     */
    fun widen(): String {
        widenings++
        return text()
    }

    override fun toString(): String = "看大 $widenings 次｜${region()}"
}
