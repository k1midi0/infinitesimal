package io.github.k1midi0.infinitesimal.tools

import io.github.k1midi0.infinitesimal.llm.JsonSchema
import io.github.k1midi0.infinitesimal.llm.PropertySchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turns what the model sent into arguments a tool can trust, or into a message
 * explaining why it cannot.
 *
 * The model's arguments are not trusted, so every call is checked against the
 * schema the tool declared. A rejection is returned as text rather than thrown:
 * it becomes the tool result, and the model gets a chance to send something valid
 * instead of the whole turn collapsing.
 */
object ToolArguments {

    /** Types a schema may declare. Anything else is a mistake in our own code. */
    val SUPPORTED_TYPES = setOf("string", "integer", "number", "boolean", "object", "array")

    sealed interface Result {
        data class Valid(val arguments: JsonObject) : Result

        data class Invalid(val reason: String) : Result
    }

    /**
     * Checks a declared schema, at registration rather than at call time.
     *
     * An unsupported type cannot be reported to the model in any useful way — the
     * model did not write the schema. Failing here puts the message in front of
     * the person who did.
     */
    fun validateSchema(toolName: String, schema: JsonSchema) {
        for ((property, definition) in schema.properties) {
            require(definition.type in SUPPORTED_TYPES) {
                "工具 $toolName 的参数 $property 声明了不支持的类型 ${definition.type}，" +
                    "支持的类型为 ${SUPPORTED_TYPES.joinToString("、")}"
            }
        }
        val declared = schema.properties.keys
        val undeclared = schema.required.filterNot { it in declared }
        require(undeclared.isEmpty()) {
            "工具 $toolName 把未声明的参数列为必需：${undeclared.joinToString("、")}"
        }
    }

    fun parse(raw: String, schema: JsonSchema): Result {
        if (raw.isBlank()) return Result.Invalid("没有提供参数")

        val element = try {
            Json.parseToJsonElement(raw)
        } catch (cause: Throwable) {
            return Result.Invalid("参数不是合法的 JSON：${cause.message ?: cause.toString()}")
        }

        val arguments = element as? JsonObject
            ?: return Result.Invalid("参数必须是一个 JSON 对象，实际是 ${describe(element)}")

        val missing = schema.required.filter { key ->
            val value = arguments[key]
            value == null || value is JsonNull
        }
        if (missing.isNotEmpty()) {
            return Result.Invalid("缺少必需的参数：${missing.joinToString("、")}")
        }

        for ((key, value) in arguments) {
            if (value is JsonNull) continue
            val definition = schema.properties[key]
                ?: return Result.Invalid("出现了未声明的参数：$key")
            if (!matchesType(value, definition.type)) {
                return Result.Invalid("参数 $key 应当是 ${definition.type}，实际是 ${describe(value)}")
            }
        }

        return Result.Valid(arguments)
    }

    private fun matchesType(value: JsonElement, expected: String): Boolean = when (expected) {
        "string" -> value is JsonPrimitive && value.isString
        "integer" -> value is JsonPrimitive && !value.isString && value.content.toLongOrNull() != null
        "number" -> value is JsonPrimitive && !value.isString && value.content.toDoubleOrNull() != null
        "boolean" -> value is JsonPrimitive && !value.isString &&
            (value.content == "true" || value.content == "false")
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        else -> false
    }

    private fun describe(value: JsonElement): String = when (value) {
        is JsonObject -> "对象"
        is JsonArray -> "数组"
        is JsonNull -> "空值"
        is JsonPrimitive -> if (value.isString) "字符串" else "数字或布尔值"
    }
}
