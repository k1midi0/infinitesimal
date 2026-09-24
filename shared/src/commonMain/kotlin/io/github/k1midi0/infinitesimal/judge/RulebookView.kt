package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.RuleEntry
import io.github.k1midi0.infinitesimal.world.RulebookDefaults
import io.github.k1midi0.infinitesimal.world.World

/**
 * 规则书与属性进判定提示词时的形状（§12 第 12 项）。
 *
 * 判定拿到两样东西，它们**是同一种东西的两个部分**：**规则书**（依据，只读）与**属性词条**
 * （状态，判定能改）。放在同一个块里、按来源分片，是因为它们回答的是同一个问题——"这样东西是什么、
 * 它受什么约束"——拆成两块只会让模型在两个地方找同一件事。
 *
 * 三条定下来的，各自对应一处不显然的地方：
 *
 *  1. **规则按优先级排成片，片头标出来源与那个数字。** 排序与分片都是代码干的，**判断留给模型**
 *     ——代码一旦开始裁冲突，就得读懂句子，而规则书里一条都不该被解析。片头把数字写出来，是为了
 *     让模型在驳回理由里能点名自己采信了哪一条（那是它必须做的事，见 `Judge`）。
 *  2. **世界与发起者那一份进共用前缀，指向的东西那一份进第二步。** 共用前缀必须两步逐字相同，
 *     否则第二步的缓存整段作废（§8）；而第一步还不知道要指向谁，所以它只能在自己那一段里。
 *  3. **空片整片不出现。** 没有规则书的世界不该多出一行「（空）」——那是拿噪声换真实内容的位置。
 *
 * 默认优先级是"越具体越优先"：世界 10 → 角色 20 → 物品 30（[RulebookDefaults]）。每份与每条都能
 * 覆盖它。**数字大的先算。**
 */
object RulebookView {

    /** 一份规则书在提示词里的一个"片"。 */
    private class Fragment(
        val title: String,
        val priority: Int,
        val entries: List<RuleEntry>,
        /** 这个实体的属性词条。**它没有优先级**——它是状态，不是依据。 */
        val attributes: Map<String, String> = emptyMap(),
    )

    /**
     * 世界那份 + 发起者那份。**放进共用前缀**，所以两步拿到的是逐字相同的一段。
     *
     * 没有规则书、也没有属性时返回空串，调用方不必判断。
     */
    fun forPrefix(world: World, actorId: String): String {
        val fragments = buildList {
            add(
                Fragment(
                    title = "世界规则",
                    priority = world.rulebook.priority ?: RulebookDefaults.WORLD,
                    entries = world.rulebook.entries,
                ),
            )
            world.byId(actorId)?.let { add(fragmentOf(it)) }
        }
        return render(fragments, "【规则与属性】（数字大的规则先算；同一条上有冲突时，以数字大的那条为准）")
    }

    /**
     * 这次动作指向的那些东西各自的规则书与属性。**只能放在第二步自己那一段里**——第一步还不知道
     * 要指向谁。
     *
     * 发起者自己跳过：它的那份已经在共用前缀里了。
     */
    fun forTargets(world: World, actorId: String, targetIds: List<String>): String {
        val fragments = targetIds
            .filter { it != actorId }
            .distinct()
            .mapNotNull { world.byId(it) }
            .map { fragmentOf(it) }
        return render(fragments, "【这次动作指向的东西的规则与属性】")
    }

    private fun fragmentOf(entity: Entity): Fragment = Fragment(
        title = "「${entity.label}」（id=${entity.id}）",
        priority = entity.rulebook.priority ?: RulebookDefaults.defaultPriorityOf(entity),
        entries = entity.rulebook.entries,
        attributes = entity.attributes,
    )

    private fun render(fragments: List<Fragment>, header: String): String {
        val useful = fragments.filter { f -> f.entries.any { !it.isEmpty } || f.attributes.isNotEmpty() }
        if (useful.isEmpty()) return ""
        return buildString {
            appendLine(header)
            // **数字大的先算。** `sortedByDescending` 是稳定排序，所以同优先级的两片保持调用方给的
            // 顺序（世界总在发起者前面）——那让输出在多次运行之间可比。
            for (f in useful.sortedByDescending { it.priority }) {
                appendLine("▸ ${f.title}（优先级 ${f.priority}）")
                for (e in f.entries.filter { !it.isEmpty }.sortedByDescending { it.priority ?: f.priority }) {
                    appendLine("  · ${e.text}${valuesOf(e)}")
                }
                if (f.attributes.isNotEmpty()) {
                    // **按名字排序**，不按来源 Map 的顺序：属性会被动作改动，顺序要是跟着插入序走，
                    // 同一份状态在两次序列化之后读起来就不一样了。
                    appendLine("  属性：" + f.attributes.entries.sortedBy { it.key }.joinToString("，") { "${it.key}=${it.value}" })
                }
            }
        }
    }

    private fun valuesOf(entry: RuleEntry): String =
        if (entry.values.isEmpty()) {
            ""
        } else {
            "［" + entry.values.entries.joinToString("；") { "${it.key}=${it.value}" } + "］"
        }
}
