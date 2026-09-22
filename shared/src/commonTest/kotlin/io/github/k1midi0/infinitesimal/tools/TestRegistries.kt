package io.github.k1midi0.infinitesimal.tools

/**
 * Registries for tests, decided the same way every time.
 *
 * The shipped policy is a list of the eight tools the application actually has, and tests
 * build registries out of fakes it has never heard of. Registration refuses a tool nobody
 * decided about — a property worth keeping, since it is what stops a permission being
 * granted by forgetting — so the tests have to decide, and they decide here rather than in
 * each file.
 *
 * Allowing everything in both situations is the right default for a test: the policy is
 * not what most of these tests are about, and a tool quietly missing from a registry would
 * look like a bug in the code under test. The tests that are about permissions build their
 * own policy and pass it in.
 */
fun allowAllPolicy(tools: List<Tool>): ToolPolicy = ToolPolicy(
    tools.map { tool ->
        ToolRule(
            toolName = tool.name,
            attended = ToolDecision.Allow,
            unattended = ToolDecision.Allow,
        )
    },
)

fun testRegistry(tools: List<Tool>): ToolRegistry = ToolRegistry(tools, allowAllPolicy(tools))

fun testRegistry(vararg tools: Tool): ToolRegistry = testRegistry(tools.toList())

fun testRegistry(tools: List<Tool>, policy: ToolPolicy): ToolRegistry =
    ToolRegistry(tools, policy)
