package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * 人设的一张卡（§3.5）。
 *
 * **一张卡 = 一个命名字段**，不是"角色卡"这个整体。标题说清它是什么（身份、口癖、交互范围……），
 * 内容是人写进去的那些字。拼进请求时**只有内容进去，标题不进**——标题是给写它的人看的，模型不需要
 * 知道这一段被归类为"身份"还是"口癖"。
 *
 * 形状抄的是 Pulsus（`D:\fatfish\Pulsus\shared\src\commonMain\kotlin\io\github\k1midi0\pulsus\config\CharacterCard.kt`）。
 * **"可拓展属性"就是这个形状的意思**：想加一项不是加一个字段，是加一张卡，自己起个名字。
 *
 * ## 它为什么不在 [Entity.attributes] 里
 *
 * `attributes` 是**世界状态**——模型能通过 `SetAttribute` 改它（「两手空着」）。角色卡是人设，
 * **判定改不到它**。这与 §2 的「事实 / 记录 / 呈现」是同一个分法：混在一起之后，「我的臂展是多少」
 * 与「我手里有没有东西」就变成了同一类东西，而只有后者该被一次动作改动。
 *
 * ## 为什么是一个字符串，而不是一个数值
 *
 * 因为「有些故事会写明确的交互范围，有些没有」，而写下来的时候是**用话说**的。距离在**写卡 /
 * 导入卡那一步**就已经换算成格（基准见附录 A.12：1 格 = 1 米），卡里存的是「你伸得着大约一格以内
 * 的东西」这样的字——**判定不做算术**，它只读。这与 §5.2「动作入库时由快速模型第三人称化」是同
 * 一种东西：入库时改写一次，之后所有人都读同一份。
 */
@Serializable
data class CharacterCard(
    /** 给人看的分类名。**不进提示词。** */
    val title: String,
    val content: String,
)

/**
 * 这一页卡拼成一段。
 *
 * 空内容不参与拼接：一张刚加上、还没写字的卡不该在提示词里留下几个空行。标题同样不进——理由写在
 * [CharacterCard] 上。
 */
fun List<CharacterCard>.rendered(): String =
    map { it.content.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n\n")
