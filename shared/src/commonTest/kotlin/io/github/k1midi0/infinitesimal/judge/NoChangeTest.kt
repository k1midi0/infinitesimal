package io.github.k1midi0.infinitesimal.judge

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * 「做成了，但世界没有因此改变」这条路（§14.19）。
 *
 * 它是加 `no_change` 之前最贵的一处：第二步要求至少一条后果，于是「我低头理了理衣襟」每次都要
 * **白烧一整轮**去硬造一条后果出来——中位 6669 ms，是对照场景的 2.5 倍（§14.18）。
 *
 * 三条要钉住的，各对应一种会悄悄坏掉的坏法：
 *
 *  1. 它是一条**合法的结果**，不是失败——世界没动，但也没有被驳回。
 *  2. 它**不能被拿来糊弄**：真有东西变了，就不能再声明"世界没有改变"。
 *  3. 原来那句「只说了影响谁，没有说要发生什么」的重试说明**要指出这条路**——不然模型还是只能
 *     硬造后果，这个工具等于白加。
 */
class NoChangeTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    private fun tavern(): World = World.from(WorldSnapshot(StartingWorld.tavern()))

    private fun request(intention: String) = JudgeRequest(
        actorId = "player",
        actorLabel = "你",
        intention = intention,
        selected = emptyList(),
        centers = emptyList(),
    )

    private val note = "你低头把衣襟理平了；身上没有任何东西因此挪动或改变。"

    @Test
    fun doingItWithoutChangingAnythingIsAValidOutcome() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", JudgeTools.TARGET, """{"id":"player"}"""),
                turn("c2", JudgeTools.NO_CHANGE, """{"note":"$note"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, script.client))
        val world = tavern()
        val before = world.snapshot()

        val judgment = judge.judgeAndCommit(world, request("我低头理了理衣襟"))

        assertEquals(Judgment.Unchanged(listOf("player"), note, 1), judgment)
        assertEquals(before, world.snapshot(), "「没有改变」不该动世界")
        // **两次请求，一次重试都没有。** 这正是这个工具要买的那件事。
        assertEquals(2, judge.requestCount, "对齐一次、后果一次")
    }

    @Test
    fun noChangeCannotRideAlongWithRealConsequences() = runBlocking {
        val script = Scripted(
            listOf(
                turn("c1", JudgeTools.TARGET, """{"id":"oak_table"}"""),
                multiTurn(
                    Triple("c2", JudgeTools.NO_CHANGE, """{"note":"什么也没有发生。"}"""),
                    Triple("c3", JudgeTools.CHANGE_APPEARANCE, """{"id":"oak_table"}"""),
                ),
            ),
        )
        // 只给一次机会：重试会拿到同一帧，重试下去只是把同一条结论重说一遍。
        val judge = Judge(LlmClient(config, script.client), maxAttempts = 1)
        val world = tavern()
        val before = world.snapshot()

        val judgment = judge.judgeAndCommit(world, request("我擦了擦桌子"))

        assertTrue(judgment is Judgment.FailedProtocol, "实际：$judgment")
        val problems = (judgment as Judgment.FailedProtocol).problems
        assertTrue(problems.any { it.contains("互斥") }, "实际：$problems")
        assertEquals(before, world.snapshot(), "协议内失败不该动世界")
    }

    @Test
    fun theRetryNotePointsAtNoChange() = runBlocking {
        // `affect` 是唯一一种"有产物、但没有一条后果"的组合。以前这里只会说「只说了影响谁，
        // 没有说要发生什么」——模型唯一的出路就是硬造一条后果。
        val script = Scripted(
            listOf(
                turn("c1", JudgeTools.TARGET, """{"id":"player"}"""),
                turn("c2", JudgeTools.AFFECT, """{"actor":"oil_lamp"}"""),
            ),
        )
        val judge = Judge(LlmClient(config, script.client), maxAttempts = 1)

        val judgment = judge.judgeAndCommit(tavern(), request("我环顾四周"))

        assertTrue(judgment is Judgment.FailedProtocol, "实际：$judgment")
        val problems = (judgment as Judgment.FailedProtocol).problems
        assertTrue(
            problems.any { it.contains(JudgeTools.NO_CHANGE) },
            "重试说明必须指出 no_change 这条路，实际：$problems",
        )
    }
}
