package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.llm.FunctionSpec
import io.github.k1midi0.infinitesimal.llm.JsonSchema
import io.github.k1midi0.infinitesimal.llm.PropertySchema
import io.github.k1midi0.infinitesimal.llm.ToolDefinition

/**
 * 判定系统交给模型的那几只"手"。
 *
 * **每一只都是"提议改世界"或"提议不改"。** 模型不能直接写字数——它只能调工具，就像说话必须是一个
 * 动作（§1.2）。
 *
 * 参数刻意保持扁平（只有 string / integer），因为移植过来的 [JsonSchema] 不支持嵌套对象与数组。
 * 需要复数的东西一律"一条一个"：多处受影响就多调几次 `affect`，多个属性就多调几次 `set_attribute`
 * ——而一次提交本来就是多条后果一起裁、一起提交的（§4.1）。
 */
object JudgeTools {

    const val MOVE = "move"
    const val REPARENT = "reparent"
    const val SET_ATTRIBUTE = "set_attribute"
    const val CHANGE_APPEARANCE = "change_appearance"
    const val DESTROY = "destroy"
    const val SPAWN = "spawn"
    const val REPLACE = "replace"
    const val AFFECT = "affect"
    const val REJECT = "reject"

    private fun tool(name: String, description: String, vararg properties: Pair<String, PropertySchema>) =
        ToolDefinition(
            function = FunctionSpec(
                name = name,
                description = description,
                parameters = JsonSchema(
                    properties = properties.toMap(),
                    required = properties.map { it.first },
                ),
            ),
        )

    private fun int(name: String, description: String) = name to PropertySchema("integer", description)
    private fun text(name: String, description: String) = name to PropertySchema("string", description)

    val ALL: List<ToolDefinition> = listOf(
        tool(
            MOVE,
            "把一个东西挪到新的体素坐标。**只讲它自身的位置变化，不改变它的外表**，所以不会让它重新被描述。",
            text("id", "要移动的东西的 id"),
            int("x", "新的 x"), int("y", "新的 y"), int("z", "新的 z"),
        ),
        tool(
            REPARENT,
            "改一个东西挂在谁下面（杯子从桌上到了玩家手里）。**不改变它的外表**。",
            text("id", "要改归属的东西的 id"),
            text("parent", "新的上位对象 id；空字符串表示不挂在任何东西下"),
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
            text("parent", "挂在哪个东西下面；空字符串表示不挂在任何东西下"),
        ),
        tool(
            REPLACE,
            "这个东西**彻底变了，已经是另一个东西**（桌子被劈成两半、杯子碎了）。必须给它一个**新的 id**——旧的会从世界里消失。",
            text("old_id", "原来那个东西的 id"),
            text("new_id", "新东西的 id，必须与 old_id 不同，且在世界里唯一"),
            text("kind", "新东西的种类"),
            text("label", "新东西的名字"),
            int("x", "体素 x"), int("y", "体素 y"), int("z", "体素 z"),
            text("parent", "挂在哪个东西下面；空字符串表示不挂在任何东西下"),
        ),
        tool(
            AFFECT,
            "记下这次动作**影响到了哪个角色**。每影响一个就调一次。玩家自己不用记。",
            text("actor", "被影响到的角色的 id"),
        ),
        tool(
            REJECT,
            "这次动作在当前情境下**不成立**（够不着、东西不在手里、话说不出口）。调用它就必须给出理由，" +
                "而理由会**原样告诉玩家**，所以要写得像这个世界在说话，不要写成系统提示。" +
                "调用 reject 之后就不要再调任何改变世界的工具。",
            text("reason", "一句话，向玩家解释为什么这次动作不成立"),
        ),
    )
}
