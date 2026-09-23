package io.github.k1midi0.infinitesimal.render

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 细化路径的装配与回退——**不需要网络**，所以它属于常规测试，而不是探针。
 *
 * 这里验的是 §14 那几条实测结论的**形状**：默认只有工具路、FIM 走前面、坏了要能回退、坏产出不能
 * 落库。真正打端点的那份验收在 `spike/ObserveFreezeSpike`。
 */
class DetailInserterTest {

    private val config = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = "test-key",
        model = "deepseek-flash",
        thinking = ThinkingMode.Never,
    )

    private val baseline = listOf("第一句。", "第二句。")

    @Test
    fun defaultHasOnlyTheToolPath() {
        val inserter = DetailInserters.of(config)
        assertEquals(listOf("工具插入"), inserter.paths, "默认必须只有工具路——FIM 实测五次里两次可用")
    }

    @Test
    fun fimIsOptIn() {
        val inserter = DetailInserters.of(config, useFim = true)
        assertEquals(listOf("FIM 插入", "工具插入"), inserter.paths, "打开 FIM 时它在前，工具在后兜底")
    }

    /** 假的写入器：验的是 [DetailInserter] 的行为，不是网络。 */
    private class FakeWriter(
        override val name: String,
        private val sentence: String,
        private val afterIndex: Int = 0,
        private val explode: Boolean = false,
    ) : SentenceWriter {
        var calls = 0
            private set

        override suspend fun propose(sentences: List<String>, context: String, hint: Int): Proposal {
            calls++
            if (explode) error("故意抛错")
            return Proposal(afterIndex, sentence, name, elapsedMs = 0)
        }
    }

    @Test
    fun fallsBackWhenThePrimaryProducesNothing() = runTest {
        val primary = FakeWriter("主路", sentence = "")
        val fallback = FakeWriter("兜底", sentence = "补的一句。", afterIndex = 1)

        val outcome = DetailInserter(primary, fallback).insert(baseline, "上下文", hint = 1)

        assertEquals(1, primary.calls, "主路要被试过")
        assertEquals(1, fallback.calls, "主路不成，兜底要接住")
        assertEquals("兜底", outcome.writer)
        assertEquals(listOf("第一句。", "补的一句。", "第二句。"), outcome.sentences)
        assertTrue(outcome.attempts.any { it.startsWith("主路：") && it.contains("空句子") })
    }

    @Test
    fun fallsBackWhenThePrimaryThrows() = runTest {
        val fallback = FakeWriter("兜底", sentence = "补的一句。", afterIndex = 0)
        val outcome = DetailInserter(FakeWriter("主路", "", explode = true), fallback)
            .insert(baseline, "上下文", hint = 0)

        assertEquals("兜底", outcome.writer)
        assertTrue(outcome.attempts.any { it.contains("抛错") }, "抛错要被记下来，而不是吞掉")
    }

    @Test
    fun nothingIsWrittenWhenEveryPathFails() = runTest {
        val outcome = DetailInserter(FakeWriter("主路", ""), FakeWriter("兜底", ""))
            .insert(baseline, "上下文", hint = 0)

        assertNull(outcome.sentences, "两条路都没产出时，世界状态必须一点不动")
        assertNull(outcome.writer)
    }

    @Test
    fun theOnlyPathIsEnough() = runTest {
        // 没有 FIM 能力的环境：primary 为 null，工具是唯一的路。**这不是降级，是默认。**
        val fallback = FakeWriter("工具插入", sentence = "补的一句。", afterIndex = 0)
        val outcome = DetailInserter(null, fallback).insert(baseline, "上下文", hint = 0)

        assertEquals(1, fallback.calls)
        assertEquals("工具插入", outcome.writer)
    }

    @Test
    fun anOversizedSentenceIsRejected() = runTest {
        // 模型完全可能声明"我要插一句"，然后把一整段塞进那个字段。
        val paragraph = "这是一整段话。它有很多句。整整三段。".repeat(3)
        val outcome = DetailInserter(null, FakeWriter("工具插入", paragraph)).insert(baseline, "上下文", hint = 0)

        assertNull(outcome.sentences, "过长的产出必须被拦下，不落库")
        assertNotNull(outcome.attempts.firstOrNull { it.contains("超过") || it.contains("多个句子") })
    }

    @Test
    fun theSafetyMarkerLeakIsCaught() = runTest {
        // §14.4 实录：FIM 原样返回了端点的审核文本，而 finish_reason 是 stop。
        val leaked = """
            <ds_safety>[用户未成年]否
            [分类]其他
            [判定]用户请求为木桌补充细节，内容属于一般家具描写，未涉及政治人物、敏感事件或分裂主张。
            [规则]无</ds_safety>Safe
        """.trimIndent()
        val outcome = DetailInserter(null, FakeWriter("FIM 插入", leaked)).insert(baseline, "上下文", hint = 0)

        assertNull(outcome.sentences, "那段审核文本绝不能变成玩家读到的一段世界描述")
    }

    @Test
    fun aDuplicateSentenceIsRejected() = runTest {
        val outcome = DetailInserter(null, FakeWriter("工具插入", "第一句。")).insert(baseline, "上下文", hint = 0)
        assertNull(outcome.sentences)
        assertTrue(outcome.attempts.any { it.contains("重复") })
    }

    @Test
    fun anOutOfRangeIndexIsClamped() = runTest {
        val outcome = DetailInserter(null, FakeWriter("工具插入", "补的一句。", afterIndex = 99))
            .insert(baseline, "上下文", hint = 0)

        assertEquals(
            listOf("第一句。", "第二句。", "补的一句。"),
            outcome.sentences,
            "越界的下标要被夹回范围内，而不是抛错或者插到别处",
        )
    }
}
