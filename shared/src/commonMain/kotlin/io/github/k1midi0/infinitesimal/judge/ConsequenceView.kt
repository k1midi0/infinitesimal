package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.MaterialTable

/**
 * 一条后果**给人看的一句话**。
 *
 * 抽成共用的纯函数，是因为它现在有**三个**消费者，而它们必须说同一件事：
 *
 *  1. 玩家的日志（`GameSession` 里那几行「· …」）
 *  2. **记录层**（`Event.effects`）——同一次提交的那几条，`Event.effects` 收的就是这份文字的同一份
 *  3. AI 角色自己的动作（`Npc`，它也要往记录层写一条）
 *
 * 三处各写一遍之后，迟早会有一处先漂开，而症状是"记录里说的和玩家看到的不一样"——那种错没有任何
 * 东西会报。
 *
 * @param name id → 给人看的名字。调用方自己决定怎么取（玩家那一轮要先抓一份标签，因为 `Destroy`
 *   之后名字就查不到了），所以它是一只函数而不是一张表。
 * @param materials 材质表：地形那三条要用它把材质 id 翻成"土""岩层"这种词。
 */
internal fun describeConsequence(
    c: Consequence,
    name: (String) -> String,
    materials: MaterialTable,
): String {
    fun material(id: String): String = materials.byId(id)?.name ?: id

    return when (c) {
        is Consequence.Move -> "${name(c.id)} 挪到了 (${c.x}, ${c.y}, ${c.z}) 厘米"
        is Consequence.SetAttribute -> "${name(c.id)} 的「${c.key}」变成「${c.value}」"
        is Consequence.ChangeAppearance -> "${name(c.id)} 只是外表变了（描述作废）"
        is Consequence.Destroy -> "${name(c.id)} 从世界里消失了"
        is Consequence.Spawn -> "出现了「${c.entity.label}」"
        is Consequence.Replace -> "${name(c.oldId)} 变成了「${c.entity.label}」（另一个东西）"
        is Consequence.SetShape -> "${name(c.id)} 有了形状（占 ${c.shape.cellCount} 个格）"
        // ── 地形（第八类，附录 A.15）────────────────────────────────────────────────
        // 玩家的日志里出现"格"这种系统词？**这里出现是有意的**：地形改动没有"一样东西"可以指代，
        // 玩家能对上的只有位置。
        is Consequence.FillTerrain -> "这一片地上铺了${material(c.material)}（框里 ${c.box.cellCount} 个格）"
        is Consequence.DigTerrain -> "这一片地被挖空了（框里 ${c.box.cellCount} 个格）"
        is Consequence.ReplaceTerrain ->
            "这一片里的${material(c.from)}变成了${material(c.to)}（框里 ${c.box.cellCount} 个格）"
    }
}
