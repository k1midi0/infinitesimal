package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * 一种材质（附录 A.15）。
 *
 * ## 它不是预设的一种
 *
 * 预设库（附录 A.14）管的是**工厂**——照着一句要求造出一样东西，缓存键是那句要求。材质是一张**按
 * id 查的表**：地形格里存的就是它的 id，所以"这一格是什么"永远只有一个答案。两者并列，不互相嵌套。
 *
 * ## 它和 [Entity] 共享两样东西
 *
 * - [attributes] 与 [Entity.attributes] 是**同一套**：判定能读它，也能改它（踩过的地面被烧焦）。
 * - [rulebook] 与 [Entity.rulebook] 是**同一套**，而它的默认优先级是
 *   [RulebookDefaults.MATERIAL] = 15——**排在世界 10 与角色 20 之间**。世界规则最泛（这个世界的火会
 *   烧起来），材质比某个角色更基本（石头不会被火点着），而具体到某个人身上的规则最该赢。
 *
 * ## 显示是呈现，不是事实
 *
 * [display] 与 [color] 属于 §2 的第三层。字符**只用 ASCII**：中文在等宽字体里占两列，`%3s` 对不齐，
 * 而网格的价值全在对齐上（[io.github.k1midi0.infinitesimal.spatial.RegionView] 那条理由与此逐字
 * 相同）。[color] 存 `0xAARRGGBB` 的 Int 而不是 Compose 的 `Color`——`world/` 不依赖界面。
 *
 * ## 可破坏是一等字段，不是属性表里的一条
 *
 * 世界底那一层岩层靠它拦住挖掘（A.15），而"能不能挖"是**代码要判的事**，不是模型要读的一句话。
 * 放进 [attributes] 就等于让一次挖掘要先经过提示词。
 */
@Serializable
data class Material(
    val id: String,
    /** 给人看的名字，例如「木地板」。 */
    val name: String,
    /** 网格里一个字符。**只用 ASCII**——中文占两列，会把整张网格推歪。 */
    val display: Char,
    /** 俯视图上的颜色，`0xAARRGGBB`。 */
    val color: Int,
    /** 能不能被挖掉。**世界底那层岩层是 `false`。** */
    val breakable: Boolean = true,
    val attributes: Map<String, String> = emptyMap(),
    val rulebook: Rulebook = Rulebook(),
)

/**
 * 材质表：**按 id 查的一张表**（附录 A.15）。
 *
 * 地形格里存的只是 id。表本身是**只读数据**——改它等于改全世界，而这类改动没有一次动作该有。
 */
@Serializable
data class MaterialTable(
    val all: List<Material> = Defaults,
) {

    private val index: Map<String, Material> = all.associateBy { it.id }

    fun byId(id: String): Material? = index[id]

    /** 名字。查不到的 id **原样吐回去**，不变成空串——空串在日志里读起来像"这里什么都没有"。 */
    fun name(id: String): String = index[id]?.name ?: id

    fun isBreakable(id: String): Boolean = index[id]?.breakable ?: false

    /**
     * 网格里怎么画。
     *
     * 不认识的 id 退成 `?`——**不静默变成空格**，那会让"不认识的材质"与"没有地形"长得一模一样，
     * 而这两件事要修的地方完全不同。
     */
    fun display(id: String): Char = index[id]?.display ?: '?'

    /** 一次返回**全部**问题，空列表 = 合格（与 [VoxelShape.problems] 同一条纪律）。 */
    fun problems(): List<String> = buildList {
        val seen = mutableSetOf<String>()
        for (m in all) {
            if (m.id.isBlank()) {
                add("有一种材质的 id 是空的")
            } else if (!seen.add(m.id)) {
                add("材质 id「${m.id}」出现了两次")
            }
            if (m.name.isBlank()) add("材质「${m.id}」没有名字")
        }
    }

    companion object {

        /**
         * 开局那七种（附录 A.15）。
         *
         * **"空气"不在这张表里**——它不是一种材质，而是"这一格什么都没有"。挖去就是把格子清掉，而
         * 稀疏存天然表达这件事：不在表里的格就是空的。
         */
        val Defaults: List<Material> = listOf(
            Material("soil", "土", ',', 0xFF7A5B3A.toInt()),
            Material("stone", "石", '#', 0xFF8A8A8A.toInt()),
            Material("bedrock", "岩层", 'X', 0xFF4A4A52.toInt(), breakable = false),
            Material("wood", "木地板", '_', 0xFFB07B4A.toInt()),
            Material("grass", "草", '"', 0xFF4E8B3A.toInt()),
            Material("water", "水", '~', 0xFF2F6FB5.toInt()),
            Material("road", "路面", '=', 0xFF9A9A8C.toInt()),
        )
    }
}
