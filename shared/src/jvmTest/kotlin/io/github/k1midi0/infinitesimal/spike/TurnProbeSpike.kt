package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.session.GameSession
import io.github.k1midi0.infinitesimal.session.LogLine
import io.github.k1midi0.infinitesimal.session.PLAYER
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 整个 [GameSession] 的联机验收——**界面看不见，但一轮的接线可以这么验**。
 *
 * 它压的是界面调用的那几条路（[GameSession.observeFocusedNow] / [GameSession.runTurn]），而不是
 * 判定或渲染的内部。验收的是三件事：
 *
 * 1. **固化真的省钱**：同一个东西看第二眼必须是 0 请求。
 * 2. **对齐真的会修正勾选**：什么都不勾，"桌上那杯麦酒"也该落到 `wine_cup` 上。
 * 3. **描述作废真的会传导到界面**：劈掉桌子之后，`observations` 里不能还留着它的描述。
 *
 * 跑法：`-PinfinitesimalLiveKeyFile=<路径> --tests "*TurnProbeSpike*"`
 */
class TurnProbeSpike {

    @Test
    fun wholeTurn() = runBlocking {
        val apiKey = ProbeSupport.keyOrNull()
        if (apiKey == null) {
            println("[一轮探针未执行] 未提供 -PinfinitesimalLiveKeyFile=<密钥文件路径>")
            return@runBlocking
        }

        val session = GameSession(ProbeSupport.configOf(apiKey))
        println()
        println("═".repeat(100))
        println("整轮验收：看第一眼 → 看第二眼（该是固化） → 说+做（什么都不勾） → 劈掉看过的东西")
        println("═".repeat(100))
        println("开局世界里：${session.entities.joinToString("、") { it.label }}")
        assertEquals(0, session.requestCount, "开局不该有任何模型调用")

        // ── ① 第一眼 ────────────────────────────────────────────────────────────────

        session.observeFocusedNow()
        val first = requireNotNull(session.focusedObservation) { "看完了却没有观察结果" }
        assertTrue(!first.fromCache, "第一眼不可能命中固化")
        assertTrue(first.sentences.isNotEmpty(), "第一眼该拿到句子")
        val afterFirst = session.requestCount
        assertTrue(afterFirst >= 1, "第一眼至少花一次请求，实际 $afterFirst")
        println()
        println("▸ ① 看了「${session.focusedEntity?.label}」：${first.sentences.size} 句，${first.elapsedMs} ms，"
            + "花了 $afterFirst 次请求")

        // ── ② 第二眼：必须白拿 ──────────────────────────────────────────────────────

        session.observeFocusedNow()
        val second = requireNotNull(session.focusedObservation)
        assertTrue(second.fromCache, "已经固化过的东西，第二眼必须命中固化")
        assertEquals(afterFirst, session.requestCount, "固化命中的观察**不该**增加请求数")
        assertEquals(0, second.elapsedMs, "固化命中该是 0 耗时")
        println("▸ ② 又看了一眼：${second.sentences.size} 句，0 ms，请求数仍是 ${session.requestCount} ← 固化生效")

        // ── ③ 说 + 做，什么都不勾 ───────────────────────────────────────────────────

        session.clearSelection()
        session.runTurn(said = "这酒怎么这么淡？", acted = "我拿起桌上那杯麦酒")

        assertTrue(
            session.log.any { it.kind == LogLine.Kind.OK },
            "这一轮该成立。日志：\n" + session.log.joinToString("\n") { "  [${it.kind}] ${it.text}" },
        )
        assertTrue(
            session.log.any { it.text.contains("对齐到") },
            "玩家什么都没勾，第一步**必须**补出对象，日志里该有「对齐到」",
        )
        assertTrue(session.selection.isEmpty(), "跑完一轮该清空勾选")
        println("▸ ③ 说了一句话 + 拿起麦酒（什么都没勾）——日志里出现了「对齐到」")

        // ── ④ 看过的东西被毁掉：描述要作废 ──────────────────────────────────────────

        session.focus("oak_table")
        session.observeFocusedNow()
        assertTrue("oak_table" in session.observations, "先得看过它")
        val beforeSplit = session.requestCount
        assertTrue(beforeSplit >= 2)

        session.clearSelection()
        session.runTurn(said = "", acted = "我举起腰间那把短柄斧，把这张木桌从中间劈成两半。")

        assertTrue(
            session.world.byId("oak_table") == null,
            "桌子该没了。世界里现在有：${session.world.all().joinToString("、") { it.id }}",
        )
        assertTrue(
            "oak_table" !in session.observations,
            "桌子都没了，界面里不该还留着它的描述",
        )
        println("▸ ④ 劈了桌子（之前看过它）：oak_table 没了，它的描述也从界面状态里作废了")

        // ── 落下来的日志 ────────────────────────────────────────────────────────────

        println()
        println("─".repeat(100))
        println("界面上「发生了什么」那一栏，从头到尾会长这样：")
        println("─".repeat(100))
        session.log.forEach { println("  [${it.kind.name.padEnd(7)}] ${it.text}") }
        println("─".repeat(100))
        println("整场跑掉 ${session.requestCount} 次模型调用｜世界里还剩 ${session.world.all().size} 样东西")
        println("═".repeat(100))
    }
}
