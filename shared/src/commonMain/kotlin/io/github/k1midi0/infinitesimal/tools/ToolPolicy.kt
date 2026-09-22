package io.github.k1midi0.infinitesimal.tools

/** Whether a tool may run. There is no third answer, and that is the point. */
enum class ToolDecision { Allow, Deny }

/**
 * Who, if anyone, is around to be asked.
 *
 * This is a property of the turn rather than of the tool, which is why the decision
 * takes two arguments. A tool that is fine when somebody is watching may be a
 * different question when nobody is, and a permission system that cannot tell those
 * apart has to answer for the harder case always — which is how a background round
 * ends up waiting sixty seconds for a person who is asleep.
 */
enum class ToolSituation {
    /** The person is at the application and could answer if asked. */
    Attended,

    /** Nobody is there: a heartbeat, or a reminder while the application is away. */
    Unattended,
}

/** What one tool is allowed to do, in both situations, decided in advance. */
data class ToolRule(
    val toolName: String,
    val attended: ToolDecision,
    val unattended: ToolDecision,
) {
    fun decide(situation: ToolSituation): ToolDecision = when (situation) {
        ToolSituation.Attended -> attended
        ToolSituation.Unattended -> unattended
    }
}

/**
 * The permission table.
 *
 * A table rather than a flag on each tool, because the question worth being able to
 * answer is "what can this thing do when nobody is watching" — and that is a question
 * about a list, which is read at a glance, not about eight files that each have to be
 * opened.
 *
 * Every answer is decided here and now. Nothing in this class can suspend, and
 * nothing in it can consult a person: at three in the morning there is nobody to
 * consult, and a decision that cannot be reached then is not a decision. This is the
 * mistake the previous host project made — it modelled "ask" as a state that blocked,
 * so every tool nobody had configured waited out a sixty-second timeout and then
 * failed.
 */
class ToolPolicy(rules: List<ToolRule>) {

    private val byName: Map<String, ToolRule> = rules.associateBy { it.toolName }

    init {
        val duplicates = rules.groupBy { it.toolName }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "工具权限表里有重复的条目：${duplicates.joinToString("、")}"
        }
    }

    /** The decision for a tool that is known to be registered. */
    fun decide(toolName: String, situation: ToolSituation): ToolDecision =
        ruleFor(toolName)?.decide(situation)
            ?: error("工具权限表里没有 $toolName。这应当在注册时就被拦下，而不是等到要跑它。")

    /** Null when the tool was never decided about. */
    fun ruleFor(toolName: String): ToolRule? = byName[toolName]

    val size: Int get() = byName.size
}
