package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.llm.FunctionSpec
import io.github.k1midi0.infinitesimal.llm.JsonSchema
import io.github.k1midi0.infinitesimal.llm.PropertySchema
import io.github.k1midi0.infinitesimal.llm.ToolDefinition

/**
 * 判定系统交给模型的那几只"手"。
 *
 * **分成两组，对应两步。** 第一步只对齐"这次动作作用于哪些东西"，所以它只有两只手；第二步才谈后果。
 * 两组不混用：第一步拿到后果工具，它就会顺手把第二步的活干了——而那正是"两步"要避免的事。
 *
 * 每只手都是"提议改世界"或"提议不改"。模型不能直接写字数——它只能调工具，就像说话必须是一个动作
 * （§1.2）。
 *
 * 参数刻意保持扁平（只有 string / integer），因为移植过来的 [JsonSchema] 不支持嵌套对象与数组。
 * 需要复数的东西一律"一条一个"：多个目标就多调几次 `target`，多个属性就多调几次 `set_attribute`
 * ——而一次提交本来就是多条后果一起裁、一起提交的（§4.1）。
 */
object JudgeTools {

    // ── 第一步：对齐指向对象 ──────────────────────────────────────────────────────

    const val TARGET = "target"
    const val REJECT_TARGETS = "reject_targets"

    /**
     * **两步共用的一只查询手**：把看得到的那一片朝外扩一圈。
     *
     * 它不是"提议改世界"，所以两组工具都带着它。它是这次判定里**唯一一只会让这一轮接着往下走**的
     * 手——其余的手一被调用，这一轮就收工（见 [Judge.call]）。
     */
    const val WIDEN_VIEW = "widen_view"

    /**
     * **两步共用的第二只查询手**：算一个式子（§12 第 12 项）。
     *
     * 与 [WIDEN_VIEW] 同类：它不是"提议改世界"，调用它这一轮就接着往下走，算完把结果回给模型。
     * 规则书与属性里那些数都能当变量（`oak_table.硬度`），但算术不许模型心算——**它算错是看不出来的**。
     */
    const val CALC = "calc"

    /**
     * **两步共用的第三只查询手**：给一样东西造形状（附录 A.9）。
     *
     * 与 [WIDEN_VIEW]、[CALC] 同类：不提议改世界，调用了这一轮就接着往下走，结果灌回来再问一次。
     *
     * 它在这里的位置是被用户那一句定下来的：**物品本身就有建模，只有生成新物品时才需要建模，谁需要
     * 谁调用。** 所以判定用它的场合很窄——**这一步刚让世界多出一个新东西**（桌子被劈成两半、树上多了
     * 个果子），而那个新东西还没有形状。它也是导演将来会调的同一只手。
     */
    const val MODEL_SHAPE = "model_shape"

    // ── 第二步：列出后果 ──────────────────────────────────────────────────────────

    const val MOVE = "move"
    const val SET_ATTRIBUTE = "set_attribute"
    const val CHANGE_APPEARANCE = "change_appearance"
    const val DESTROY = "destroy"
    const val SPAWN = "spawn"
    const val REPLACE = "replace"
    const val AFFECT = "affect"

    /**
     * **「做成了，但世界没有因此改变」是一只显式的手**，不是"什么都别调"。
     *
     * 加它之前，这一步要求至少一条后果，否则算协议内失败并重试一次；于是「我低头理了理衣襟」
     * 这类动作每次都要白烧一整轮才硬造出一条后果来（§14.18 量到中位 6669 ms，是对照场景的
     * 2.5 倍）。它同时把日志里的「没改变」与「协议失败」分开了——那两件事原先长得一样。
     */
    const val NO_CHANGE = "no_change"

    const val REJECT = "reject"

    /**
     * 造一只工具。
     *
     * `optional` 里的名字**不进 `required`**。这是后加的一个口子：原先这里把所有属性一股脑塞进
     * `required`，于是"可选参数"根本表达不出来。`widen_view` 的 `direction` 就是第一个真正可选的
     * 参数——填了只往那边看，不填是整体看远一圈，两种都是合法的调用。
     */
    private fun tool(
        name: String,
        description: String,
        vararg properties: Pair<String, PropertySchema>,
        optional: List<String> = emptyList(),
    ) = ToolDefinition(
        function = FunctionSpec(
            name = name,
            description = description,
            parameters = JsonSchema(
                properties = properties.toMap(),
                required = properties.map { it.first } - optional.toSet(),
            ),
        ),
    )

    private fun int(name: String, description: String) = name to PropertySchema("integer", description)
    private fun text(name: String, description: String) = name to PropertySchema("string", description)

    private val MODEL = tool(
        MODEL_SHAPE,
        "给一样东西造形状。**只在你这一步让世界多出一个新东西、而它还没有形状时才用**" +
            "（桌子被劈成两半、树上结了个果子）。形状由另一个专管画图的模型画，它看不到你看到的这一切——" +
            "所以 brief 要写成一句**能独立读懂**的要求，把东西是什么、多大、什么样子说清楚，" +
            "例如「被从中间劈开的橡木方桌的一半，断面粗糙」。它会给你造好的形状的尺寸与格数，然后你接着干。" +
            "旧的、本来就有的东西不必造——它们本来就有形状。",
        text("id", "要造形状的那样东西的 id。可以是这一步刚 spawn / replace 出来的"),
        text("brief", "一句能独立读懂的要求，说清要造的是什么、多大、什么样子"),
    )

    private val WIDEN = tool(
        WIDEN_VIEW,
        "你现在手上这一片范围不够用的时候调它（要看的东西不在这片里、或者你把一样东西打到片外去了）。" +
            "它会扩完**立刻**给你一份新的范围，然后你接着干原来的活。" +
            "如果你知道要看的那边是哪个方向，就填 direction——只往那边看一档，射线只多几条；" +
            "不填 direction 是四面八方一起再看远一圈，射线要多三倍多。拿不准在哪边时才不填。" +
            "**不调就是「现在这些够了」**——随手调它只会白多花一趟，而那一趟里你什么也没做。",
        text(
            "direction",
            "只把这一个方向再看远一档。可填：东、东南、南、西南、西、西北、北、东北、上、下。" +
                "不填这个参数表示四面八方一起再看远一圈。",
        ),
        optional = listOf("direction"),
    )

    private val CALC_TOOL = tool(
        CALC,
        "算一个式子。**要算数就调它，不要心算**——心算出来的数字错了，下游那句话照样通顺，谁都不会发现。" +
            "变量名从这一步给你的【可以算的数】里原样取（形如 `oak_table.硬度`），" +
            "只能用 + - * / ^ 和括号（`^` 是幂，`^0.5` 就是开方）。" +
            "它会把你写的式子和结果一起还给你，然后你接着干原来的活。",
        text("expression", "要算的式子，例如 (player.力量 * 2 - oak_table.硬度) / 3"),
    )

    /** 第一步能用的手：**只有这几只**。 */
    val TARGETING: List<ToolDefinition> = listOf(
        tool(
            TARGET,
            "报出一个这次动作**实际作用到**的东西。每确定一个就调一次。",
            text("id", "那个东西的 id"),
        ),
        tool(
            REJECT_TARGETS,
            "只有当你**完全看不出这次动作要对什么做**、或者被选中的东西与动作毫无关系且无法推断时才用它。" +
                "如果这个动作确实不作用在任何东西上（例如「我环顾四周」「我打了个哈欠」），那是一个 target 都不报，" +
                "而**不是**驳回。理由会原样告诉玩家，所以要写成这个世界在说话。" +
                "**理由必须限定在你这一片里**：「这一片里没有扫帚」，不是「没有扫帚」——你看不到的那部分是" +
                "另一件事，别替它下结论。",
            text("reason", "一句话，向玩家解释为什么看不出这次动作指着什么"),
        ),
        WIDEN,
        CALC_TOOL,
    )

    /** 第二步能用的手。 */
    val EFFECTS: List<ToolDefinition> = listOf(
        tool(
            MOVE,
            "把一个东西挪到新的体素坐标。**只讲它自身的位置变化，不改变它的外表**，所以不会让它重新被描述。",
            text("id", "要移动的东西的 id"),
            int("x", "新的 x"), int("y", "新的 y"), int("z", "新的 z"),
        ),
        tool(
            SET_ATTRIBUTE,
            "改一个结构化属性（杯子里的酒没了、门开了）。**这算外表变化**：它已有的描述会作废并被重新描述。",
            text("id", "要改的东西的 id"),
            text("key", "属性名"), text("value", "属性值"),
        ),
        tool(
            CHANGE_APPEARANCE,
            "这个东西**只是看起来不一样了**（被擦干净、蒙上灰、被翻倒），但**仍然是同一个东西**。它的描述会作废并被重新描述。",
            text("id", "要变化的东西的 id"),
        ),
        tool(
            DESTROY,
            "这个东西被摧毁、消失了。",
            text("id", "要摧毁的东西的 id"),
        ),
        tool(
            SPAWN,
            "凭空出现一个新东西（桌上多了一只杯子、镇上被补写出一位木匠）。它的属性可以随后用 set_attribute 补。",
            text("id", "新东西的 id，必须在整个世界范围内唯一"),
            text("kind", "种类，例如 家具 / 器具 / 角色 / 地点"),
            text("label", "给人看的名字，例如 木桌"),
            int("x", "体素 x"), int("y", "体素 y"), int("z", "体素 z"),
        ),
        tool(
            REPLACE,
            "这个东西**彻底变了，已经是另一个东西**（桌子被劈成两半、杯子碎了）。必须给它一个**新的 id**——旧的会从世界里消失。",
            text("old_id", "原来那个东西的 id"),
            text("new_id", "新东西的 id，必须与 old_id 不同，且在世界里唯一"),
            text("kind", "新东西的种类"),
            text("label", "新东西的名字"),
            int("x", "体素 x"), int("y", "体素 y"), int("z", "体素 z"),
        ),
        tool(
            AFFECT,
            "记下这次动作**影响到了哪个角色**。每影响一个就调一次。玩家自己不用记。",
            text("actor", "被影响到的角色的 id"),
        ),
        tool(
            NO_CHANGE,
            "这次动作**确实做成了，但世界没有因此改变**——没有东西移动、没有属性变化、没有东西出现或消失。" +
                "（例如「我理了理衣襟」「我环顾四周」。）**这是合法的一步，不是失败。**" +
                "它必须单独出现：只要真有一样东西的状态变了，就必须用别的手列出来，不能用它糊过去。" +
                "调用它时给一句说明：这次动作做成了什么、以及为什么没有留下任何改变。",
            text("note", "一句话，说明这次动作做成了什么、为什么没有留下任何改变"),
        ),
        tool(
            REJECT,
            "这次动作在当前情境下**不成立**（够不着、东西不在手里、话说不出口）。调用它就必须给出理由，" +
                "而理由会**原样告诉玩家**，所以要写得像这个世界在说话，不要写成系统提示。" +
                "调用 reject 之后就不要再调任何改变世界的工具。",
            text("reason", "一句话，向玩家解释为什么这次动作不成立"),
        ),
        WIDEN,
        CALC_TOOL,
        MODEL,
    )
}
