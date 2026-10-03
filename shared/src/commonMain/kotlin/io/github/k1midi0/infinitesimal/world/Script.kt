package io.github.k1midi0.infinitesimal.world

import kotlinx.serialization.Serializable

/**
 * **一份剧本处在哪一步**（§8.4）。
 *
 * 五态，而**"中断"与"完结"的差别是"以后还会不会发生"**，不是"谁结束了它"：
 *
 * | 例子 | 事情 | 记成 |
 * |---|---|---|
 * | 哥布林里的混血儿 | 玩家跑去救法师了，据点还在 | [PAUSED] |
 * | 结识魔法师队友 | 玩家把法师补了几刀、收走了财宝 | [DONE] |
 *
 * （早先还有一态"放弃"，2026-09-26 去掉；"结束"改名"完结"。）
 *
 * **谁改它**：只有思考档（§8.5 那条权限划分：快速档唯一能写的是批注）。代码一条都不判——"玩家
 * 已经进来了"是语义判断，而代码能看到的只有坐标与事件流。
 */
@Serializable
enum class ScriptState {
    /** 写好了，但舞台还没搭（地图、NPC、时间轴都还没按它生成）。 */
    UNARRANGED,

    /** 舞台搭好了，等着玩家撞上来。 */
    ARRANGED,

    /** 玩家已经进来了，正在演。 */
    RUNNING,

    /** 暂停——玩家跑去干别的了，**以后可能接回来**。 */
    PAUSED,

    /** 终态——这条线不会再发生。 */
    DONE,
    ;

    /** 给人看的那几个字（提示词与答复共用一处，于是两处不会各说各话）。 */
    val label: String
        get() = when (this) {
            UNARRANGED -> "未安排"
            ARRANGED -> "已安排"
            RUNNING -> "运行"
            PAUSED -> "中断"
            DONE -> "完结"
        }

    companion object {
        /** 从模型写来的那几个字（或者枚举名）读回去。**读不懂就是 null**，由调用方驳回。 */
        fun of(text: String): ScriptState? {
            val t = text.trim()
            return entries.firstOrNull { it.label == t }
                ?: entries.firstOrNull { it.name.equals(t, ignoreCase = true) }
        }
    }
}

/**
 * **一条批注**（§8.4）：只追加、永不改写的一条记录。
 *
 * 与剧本**正文分开存**，理由是 §1.5：混在一个字段里，"这条到底是改了、还是发生过"就又变成一个读不
 * 出来的状态。分工因此是——**改正文 = 改计划**（随时可改），**写批注 = 记发生了什么**（只追不改）。
 */
@Serializable
data class ScriptNote(
    /** 写这条批注时的世界时刻。**由代码取**，不由模型写（它不知道现在是几点，而这件事只有一个答案）。 */
    val at: WorldTime,
    val text: String,
)

/**
 * **一份剧本**（§8.4）：一条完整的剧情，**用自然语言写**。
 *
 * ## 为什么正文与摘要分开
 *
 * 池子里每份只露**标题 + 摘要 + 状态**给导演查阅，**正文要打开才看**（[body] 因此**不进任何提示词**，
 * 它只在导演调 `look_script` 打开那一份时读一次）。这一条决定的是成本：一局八条线、每条两千字的正文
 * 如果常驻提示词，那一份上下文就全被剧情说明书占满了。
 *
 * ## 它不是"舞台戏"
 *
 * 原文：「**剧本是给导演安排环境用的，不是舞台戏。** 它只用来丰富玩家的探索波折，而**探索才是主体**」
 * ——导演照着它设计环境（公告上贴着"圣剑拔出的日子到了"、而圣剑只认勇者），玩家看见公告**自己去
 * 拔**，也可能根本不拔、直接走掉。走了就是偏离，导演改剧本。
 *
 * ## 它与"这个剧本搭出来的东西"**不挂钩**
 *
 * 没有一份"它用到的地块/实体/时间轴事件"的名单（那要维护一致性：那块地后来被挖了怎么办）。剧本是
 * 给导演读的自然语言 + 一个它自己维护的状态，而舞台是它照着自己排出来的地形与时间轴。
 */
@Serializable
data class Script(
    /** 代号：导演自己起（与时间轴事件的 id 同一条纪律——同一个 id 再写一次是改主意）。 */
    val id: String,
    /** 一句话的标题。 */
    val title: String,
    /** 摘要：**这一份里唯一常驻提示词的那段话**。 */
    val summary: String,
    /** 正文：自然语言写的完整剧情。**不进提示词**，要打开才看。 */
    val body: String,
    val state: ScriptState = ScriptState.UNARRANGED,
    /** 批注：只追加（§8.4）。 */
    val notes: List<ScriptNote> = emptyList(),
)

/** 把池子列成给导演看的那一份清单（标题 + 摘要 + 状态）。**正文不在里面**，那是 `look_script` 的事。 */
fun List<Script>.listing(): String =
    if (isEmpty()) {
        "（池子里一份剧本都没有）"
    } else {
        joinToString("\n") { "- ${it.id}｜${it.title}〔${it.state.label}〕\n    ${it.summary.trim()}" }
    }
