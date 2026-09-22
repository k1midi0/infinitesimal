package io.github.k1midi0.infinitesimal.tools

import io.github.k1midi0.infinitesimal.llm.JsonSchema
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 一套工具的性质，写在每一份说明的最前面。
 *
 * 这一条钉住的是「它落到了每一个工具上」，而不是那句话的措辞：抄进每一份说明里的做法会在下一次
 * 加工具时漏掉一处，而漏掉不会报错——模型只会以为那个工具不属于它。
 */
class ToolDefinitionNoteTest {

    private class Fake(override val name: String) : Tool {
        override val description: String = "一个假工具。"
        override val parameters: JsonSchema = JsonSchema(properties = linkedMapOf(), required = emptyList())
        override suspend fun execute(arguments: JsonObject): ToolResult = ToolResult.Success("")
    }

    @Test
    fun everyToolSaysTheSetIsItsOwn() {
        val definition = Fake("fake").toDefinition()

        assertTrue(
            definition.function.description.startsWith("这些工具是你自己的"),
            "说明前面没有那一句：${definition.function.description}",
        )
        assertTrue(
            definition.function.description.contains("一个假工具。"),
            "那一句把原说明顶掉了",
        )
    }
}