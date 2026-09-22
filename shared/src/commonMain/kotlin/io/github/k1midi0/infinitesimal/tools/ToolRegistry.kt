package io.github.k1midi0.infinitesimal.tools

import io.github.k1midi0.infinitesimal.llm.ToolDefinition

/**
 * The tools available to a turn.
 *
 * Registration checks each schema, so a malformed tool fails at startup rather than
 * when the model happens to call it. It also checks that the policy has decided about
 * every tool, for the same reason and more strongly: a tool nobody decided about
 * would otherwise be a permission granted by forgetting, and forgetting is not a
 * decision anyone made.
 */
class ToolRegistry(
    tools: List<Tool> = emptyList(),
    private val policy: ToolPolicy,
) {

    private val toolsByName: Map<String, Tool>

    init {
        val duplicates = tools.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "工具名重复：${duplicates.joinToString("、")}"
        }
        for (tool in tools) {
            ToolArguments.validateSchema(tool.name, tool.parameters)
            requireNotNull(policy.ruleFor(tool.name)) {
                "工具 ${tool.name} 没有登记在权限表里。" +
                    "加一个工具就必须同时决定它能不能在无人时跑，否则「忘了填」就成了放行。"
            }
        }
        toolsByName = tools.associateBy { it.name }
    }

    val size: Int get() = toolsByName.size

    fun find(name: String): Tool? = toolsByName[name]

    /** Names in a stable order, for messages that list what is available. */
    fun names(): List<String> = toolsByName.keys.sorted()

    fun definitions(): List<ToolDefinition> =
        toolsByName.values.map { it.toDefinition() }

    fun decisionFor(name: String, situation: ToolSituation): ToolDecision =
        policy.decide(name, situation)

    /**
     * The same registry with the tools this situation does not allow taken out.
     *
     * They are removed rather than refused on call. A tool the character may not use
     * should not appear in the request at all: offering it and then rejecting the
     * call spends a request to tell the model something it could have been told by
     * the tool list, and leaves a refusal in the conversation for the rest of the
     * day. The policy is carried over unchanged, so a tool that was not registered
     * here still cannot be added later without a decision.
     */
    fun allowedIn(situation: ToolSituation): ToolRegistry = ToolRegistry(
        tools = toolsByName.values.filter { decisionFor(it.name, situation) == ToolDecision.Allow },
        policy = policy,
    )

    /** Every tool's decision in one situation, for the log and for review. */
    fun decisions(situation: ToolSituation): Map<String, ToolDecision> =
        names().associateWith { decisionFor(it, situation) }
}
