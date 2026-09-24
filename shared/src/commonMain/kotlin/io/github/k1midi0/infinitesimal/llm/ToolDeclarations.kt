package io.github.k1midi0.infinitesimal.llm

/**
 * 声明一只"手"。
 *
 * ## 为什么参数一律是 [Pair]
 *
 * 因为顺序就是它出现在提示词里的顺序，而**顺序本身是设计面**（§2.4）：先给 id、再给要改的东西，
 * 模型照着读下来比跳着读稳。用 `Map` 就把这层意思丢了。
 *
 * ## 为什么 `optional` 是显式的一张名单
 *
 * 默认全部必填。理由在 §1.3 那条官方警告上：**模型会臆造出 schema 里没有的参数**，而漏掉一个必填
 * 参数比多给一个好查得多——多给的那个会被当成一次工具失败退回去，而"少给了"如果不校验，就会变成
 * 一个默认值悄悄生效。所以想可选，就得在这里点名。
 */
fun tool(
    name: String,
    description: String,
    vararg properties: Pair<String, PropertySchema>,
    optional: List<String> = emptyList(),
): ToolDefinition {
    val unknown = optional - properties.map { it.first }.toSet()
    require(unknown.isEmpty()) {
        "工具 $name 把不存在的参数标成了可选：${unknown.joinToString("、")}"
    }
    return ToolDefinition(
        function = FunctionSpec(
            name = name,
            description = description,
            parameters = JsonSchema(
                properties = properties.toMap(),
                required = properties.map { it.first } - optional.toSet(),
            ),
        ),
    )
}

fun text(name: String, description: String) = name to PropertySchema("string", description)

fun int(name: String, description: String) = name to PropertySchema("integer", description)
