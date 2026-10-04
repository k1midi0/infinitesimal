package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.judge.Scripted
import io.github.k1midi0.infinitesimal.judge.multiTurn
import io.github.k1midi0.infinitesimal.judge.stop
import io.github.k1midi0.infinitesimal.judge.turn
import io.github.k1midi0.infinitesimal.llm.LlmClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * **拆分那一段**（§14.55、§8.4）：把一段故事文本拆成概括性的参考剧本。
 *
 * 假端点验的不是模型有多会拆（那是 prompt 的事，联机才看得见），而是**这一层的接线与几条硬规矩**：
 * 三段要写全、摘要与骨架超没超上限、同一个代号撞了怎么办、以及**空手是合法的答案**——一段写景的文字
 * 里一条线都没有，那不是失败。
 */
class ScriptDigesterTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun digester(script: Scripted) = ScriptDigester(LlmClient(config, script.client))

    private fun digest(script: Scripted, text: String = "有一件事发生了。", taken: Set<String> = emptySet()) =
        runBlocking {
            digester(script).digest(text = text, from = "某本小说.txt", label = "（第 1 段）", taken = taken)
        }

    private fun add(title: String, summary: String = "一句话的概括", body: String = "处境与角色位。") =
        """{"title":"$title","summary":"$summary","body":"$body"}"""

    /** 一轮里交两条，交完就停手——两条都该留下，而它们的代号就是标题。 */
    @Test
    fun twoReferencesInOneRoundAreBothKept() {
        val script = Scripted(
            listOf(
                multiTurn(
                    Triple("c1", "add_reference", add("一笔到期的账")),
                    Triple("c2", "add_reference", add("翻上来的东西")),
                ),
                stop("拆完了。"),
            ),
        )

        val result = digest(script)

        assertTrue(result is ScriptDigester.Result.Digested, "实得 $result")
        assertEquals(
            listOf("一笔到期的账", "翻上来的东西"),
            (result as ScriptDigester.Result.Digested).scripts.map { it.id },
        )
        assertEquals("某本小说.txt", result.scripts.first().from, "出处要原样记进每一条")
    }

    /**
     * **一个工具都不调是合法的答案。**
     *
     * 这是它与建模那层的差别：建模总要交一份形状，而"这一段里没有一条线"是诚实的结果——一段写景、
     * 写心情、写回忆的文字里本来就没有事。把它判成失败会逼着模型凑数。
     */
    @Test
    fun silenceIsALegalAnswer() {
        val script = Scripted(listOf(stop("这一段只是在写风景。")))

        val result = digest(script)

        assertTrue(result is ScriptDigester.Result.Digested, "实得 $result")
        assertTrue((result as ScriptDigester.Result.Digested).scripts.isEmpty())
    }

    /** 概括太长**当场退回**，而理由要说清上限是多少、以及它该写到哪儿去（正文里）。 */
    @Test
    fun aTooLongSummaryIsRefusedAndTheModelGetsToFixIt() {
        val long = "概".repeat(ScriptPlanner.MAX_SUMMARY_CHARS + 1)
        val script = Scripted(
            listOf(
                turn("c1", "add_reference", add("一笔到期的账", summary = long)),
                turn("c2", "add_reference", add("一笔到期的账", summary = "改短了")),
                stop(),
            ),
        )

        val result = digest(script)

        assertTrue(result is ScriptDigester.Result.Digested, "实得 $result")
        assertEquals(1, (result as ScriptDigester.Result.Digested).scripts.size)
        val answer = script.bodies[1]
        assertTrue("概括太长" in answer, "要告诉它错在哪儿：\n$answer")
        assertTrue("${ScriptPlanner.MAX_SUMMARY_CHARS}" in answer, "还要给出那个数：\n$answer")
    }

    /** 三段缺一段（没有骨架）也要退回，而且**不能悄悄存下一条半成品**。 */
    @Test
    fun aReferenceWithoutABodyIsRefused() {
        val script = Scripted(
            listOf(
                turn("c1", "add_reference", """{"title":"有一件事","summary":"一句话"}"""),
                stop(),
            ),
        )

        val result = digest(script)

        assertTrue(result is ScriptDigester.Result.Failed, "实得 $result")
        assertTrue((result as ScriptDigester.Result.Failed).problems.any { "没有骨架" in it }, "$result")
    }

    /**
     * **代号撞了不驳回，而是加一个后缀并且说出来。**
     *
     * 一部长篇里同一件事换个角度讲两遍是常事，为这个把这一条退回去重写不值得；但"静默地改一个名字"是
     * 另一回事——模型要知道它交的那一条现在叫什么，否则它下一次还会写同一个标题。
     */
    @Test
    fun aDuplicateTitleGetsASuffixedIdAndIsToldSo() {
        val script = Scripted(
            listOf(
                turn("c1", "add_reference", add("一笔到期的账")),
                stop(),
            ),
        )

        val result = digest(script, taken = setOf("一笔到期的账"))

        assertEquals(
            listOf("一笔到期的账-2"),
            (result as ScriptDigester.Result.Digested).scripts.map { it.id },
        )
        val answer = script.bodies[1]
        assertTrue("一笔到期的账-2" in answer, "要告诉它新代号：\n$answer")
        assertTrue("已经有一条叫" in answer, "还要说清为什么改名：\n$answer")
    }

    /** 已经有的代号要写进这一次的请求里（它才知道哪些别拆第二遍）。 */
    @Test
    fun theCodesAlreadyInTheLibraryAreNamedInThePrompt() {
        val script = Scripted(listOf(stop()))

        digest(script, taken = setOf("一笔到期的账", "翻上来的东西"))

        val body = script.bodies.first()
        assertTrue("一笔到期的账" in body, "库里已有的要告诉它：\n$body")
        assertTrue("别再拆一遍" in body, body)
    }

    /** 叫一只不存在的工具：退回去告诉它有哪些，而不是把整轮炸掉。 */
    @Test
    fun anUnknownToolIsAnsweredWithTheListOfRealOnes() {
        val script = Scripted(
            listOf(
                turn("c1", "把这一段写成一首诗", "{}"),
                turn("c2", "add_reference", add("有一件事")),
                stop(),
            ),
        )

        val result = digest(script)

        assertTrue(result is ScriptDigester.Result.Digested, "实得 $result")
        assertTrue("没有" in script.bodies[1] && "add_reference" in script.bodies[1], script.bodies[1])
    }

    /**
     * **system 里那条最重要的规矩要在**：抛弃角色背景、人名、地名。
     *
     * 它不是建议——参考剧本要被填进**任何一个**世界，而"卡尔·莫恩是个被逐出师门的铁匠"只有那一个
     * 世界能用。这一支钉的就是这句话别在日后被改没了。
     */
    @Test
    fun theSystemTellsItToThrowTheNamesAway() {
        val script = Scripted(listOf(stop()))

        digest(script)

        val system = script.bodies.first()
        assertTrue("不许出现具体的人名、地名" in system, system)
        assertTrue("处境与角色位" in system, system)
        assertTrue("填进任意一个世界" in system, system)
    }
}
