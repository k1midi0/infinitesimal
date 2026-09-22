package io.github.k1midi0.infinitesimal.tools

import io.github.k1midi0.infinitesimal.llm.JsonSchema
import io.github.k1midi0.infinitesimal.llm.ToolDefinition
import io.github.k1midi0.infinitesimal.llm.FunctionSpec
import kotlinx.serialization.json.JsonObject

/**
 * A capability the model can invoke.
 *
 * Arguments arrive as the parsed JSON object that the schema describes. They have
 * already been checked against that schema, so an implementation can read the
 * fields it declared without defending against missing ones.
 */
interface Tool {
    /** Unique across the registry. This is what the model calls. */
    val name: String

    /** Read by the model to decide when to use the tool. */
    val description: String

    val parameters: JsonSchema

    /**
     * Whether calling this tool on its own ends the turn.
     *
     * The dialogue tool sets this. Saying something is the whole of what that turn
     * was for, so asking the model to carry on afterwards would spend a second
     * request per utterance to be told there is nothing more to do.
     *
     * It is expressed here, on the tool, rather than as a special case in the loop,
     * so the loop does not have to know what a dialogue tool is.
     */
    val terminatesTurn: Boolean get() = false

    /**
     * 这一次调用最多等多久，毫秒。null 表示不限。
     *
     * 原先循环给所有工具套同一个上限。那对一次文件读取是对的，对一次等人回答是错的：三十秒
     * 之后循环会把这次调用掐断，写下一句「执行超过 N 毫秒，已中断」，而对方可能正在打字。
     *
     * 所以上限归工具自己说。默认仍是原来那个数——大多数工具确实是「要么马上好，要么就是坏了」。
     */
    val timeoutMillis: Long? get() = DEFAULT_TIMEOUT_MILLIS

    suspend fun execute(arguments: JsonObject): ToolResult

    companion object {
        /**
         * 没有自己声明的工具能等多久。
         *
         * 三十秒。它买的是「卡住」与「在干活」之间的分辨力，而不是任何一次具体操作的耗时——
         * 需要更久的工具应当自己写一个数，或者写 null 说它不限。
         */
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}

/**
 * The outcome of running a tool.
 *
 * A failure is a value, not an exception. It is handed back to the model as the
 * tool result so the model can try something else, which it cannot do if the loop
 * has already unwound.
 */
sealed interface ToolResult {

    data class Success(val content: String) : ToolResult

    data class Failure(val reason: String) : ToolResult

    companion object {
        fun success(content: String): ToolResult = Success(content)

        fun failure(reason: String): ToolResult = Failure(reason)
    }
}

/**
 * 每一份工具说明前面都会带上的那一句。
 *
 * 它说的是这一整套工具的性质，不是某一个工具的性质，所以它放在所有说明都会经过的那一处，而不是
 * 抄进每一份说明里。抄一遍就要改十几处，而漏掉的那一处不会报错。
 *
 * 这句话是真的，而且是构造上真的：`ToolRegistry.allowedIn` 交给模型的是一份子集注册表，用不了
 * 的工具根本不在请求里。所以「列在这里的每一个都可以直接用」不是一句鼓励，是它此刻的处境。
 *
 * 顺带说清另一面：看不见的工具不是被藏起来的，是不适用的。不写这一句的话，「这一套有多全」
 * 就只能靠它自己去猜。
 */
private const val TOOLS_ARE_YOURS =
    "这些工具是你自己的，此刻列在这里的每一个都可以直接用，想用就用，不必先征求同意。" +
        "你没有看见的工具是你此刻用不上的，不是被藏起来的。\n\n"

/** The wire form of this tool, as sent to the endpoint. */
fun Tool.toDefinition(): ToolDefinition = ToolDefinition(
    function = FunctionSpec(
        name = name,
        description = TOOLS_ARE_YOURS + description,
        parameters = parameters,
    ),
)
