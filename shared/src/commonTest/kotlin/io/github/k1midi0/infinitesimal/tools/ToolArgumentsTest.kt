package io.github.k1midi0.infinitesimal.tools

import io.github.k1midi0.infinitesimal.llm.JsonSchema
import io.github.k1midi0.infinitesimal.llm.PropertySchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolArgumentsTest {

    private val schema = JsonSchema(
        properties = linkedMapOf(
            "text" to PropertySchema("string", "要说的话"),
            "times" to PropertySchema("integer", "次数"),
        ),
        required = listOf("text"),
    )

    private fun reasonOf(raw: String): String {
        val result = ToolArguments.parse(raw, schema)
        assertTrue(result is ToolArguments.Result.Invalid, "预期被拒绝，实际通过：$raw")
        return result.reason
    }

    @Test
    fun acceptsArgumentsThatMatchTheSchema() {
        val result = ToolArguments.parse("""{"text":"你好","times":2}""", schema)

        assertTrue(result is ToolArguments.Result.Valid)
        assertEquals(2, result.arguments.size)
    }

    @Test
    fun acceptsOmittedOptionalArguments() {
        val result = ToolArguments.parse("""{"text":"你好"}""", schema)

        assertTrue(result is ToolArguments.Result.Valid)
    }

    @Test
    fun rejectsMalformedJson() {
        assertTrue(reasonOf("{not json").contains("不是合法的 JSON"))
    }

    @Test
    fun rejectsArgumentsThatAreNotAnObject() {
        assertTrue(reasonOf("""["你好"]""").contains("必须是一个 JSON 对象"))
    }

    @Test
    fun rejectsAMissingRequiredArgument() {
        assertTrue(reasonOf("""{"times":1}""").contains("缺少必需的参数：text"))
    }

    @Test
    fun treatsAnExplicitNullAsMissing() {
        assertTrue(reasonOf("""{"text":null}""").contains("缺少必需的参数：text"))
    }

    @Test
    fun rejectsAWrongType() {
        assertTrue(reasonOf("""{"text":123}""").contains("应当是 string"))
    }

    @Test
    fun rejectsANonIntegerWhereAnIntegerIsDeclared() {
        assertTrue(reasonOf("""{"text":"你好","times":1.5}""").contains("应当是 integer"))
    }

    @Test
    fun rejectsAnUndeclaredArgument() {
        assertTrue(reasonOf("""{"text":"你好","mood":"开心"}""").contains("未声明的参数：mood"))
    }

    @Test
    fun rejectsBlankArguments() {
        assertEquals("没有提供参数", reasonOf(""))
    }

    /**
     * An unsupported type is a mistake in our own schema, so it must fail where
     * the author sees it rather than being reported to the model, which cannot act
     * on it.
     */
    @Test
    fun refusesToRegisterASchemaWithAnUnsupportedType() {
        val broken = JsonSchema(
            properties = linkedMapOf("payload" to PropertySchema("funky", "说不清")),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            ToolArguments.validateSchema("broken_tool", broken)
        }

        assertTrue(error.message!!.contains("不支持的类型 funky"))
        assertTrue(error.message!!.contains("broken_tool"))
    }

    @Test
    fun refusesASchemaThatRequiresAnUndeclaredArgument() {
        val broken = JsonSchema(required = listOf("ghost"))

        val error = assertFailsWith<IllegalArgumentException> {
            ToolArguments.validateSchema("broken_tool", broken)
        }

        assertTrue(error.message!!.contains("ghost"))
    }
}
