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
 * 它压的是界面调用的那几条路（[GameSession.observeFocusedNow] / [GameSession.runTurn] /
 * [GameSession.applyPendingNow] / [GameSession.dismissPending]），而不是判定或渲染的内部。验收的是
 * 四件事：
 *
 * 1. **固化真的省钱**：同一个东西看第二眼必须是 0 请求。
 * 2. **对齐真的会修正勾选**：什么都不勾，"桌上那杯麦酒"第一步会落到 `wine_cup` 上。
 * 3. **纠正预览真的停在改世界之前**（§4.7）：停在预览上时世界一个字没动，点「改动作」之后
 *    也一个字没动，点「应用」才动。
 * 4. **描述作废真的会传导到界面**：劈掉桌子之后，`observations` 里不能还留着它的描述。
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

        // ── ③ 说 + 做，什么都不勾：**这一次会停在纠正预览上** ───────────────────────

        session.clearSelection()
        session.runTurn(said = "这酒怎么这么淡？", acted = "我拿起桌上那杯麦酒")

        val pending = requireNotNull(session.pending) {
            "什么都没勾，第一步对齐出来的东西与勾选必然不一样，该停在预览上。日志：\n" +
                session.log.joinToString("\n") { "  [${it.kind}] ${it.text}" }
        }
        assertTrue(pending.targets.isNotEmpty(), "该补出对象来")
        assertEquals(emptyList(), pending.picked, "玩家什么都没勾")
        // **停在这里世界必须一个字都没动。** 这是整条预览能成立的前提：第一步只对齐，不改世界。
        // 酒杯还在 80 厘米的桌面上（关系不存成事实，位置就是坐标——附录 A.5）。
        assertEquals(80, session.world.byId("wine_cup")?.z, "预览期间酒杯还该在桌面上")
        assertTrue(
            session.log.none { it.kind == LogLine.Kind.OK },
            "还没应用，不该有「成立」那一行：\n" + session.log.joinToString("\n") { "  [${it.kind}] ${it.text}" },
        )
        println("▸ ③a 停在纠正预览上：判定认为作用在 ${pending.targets.joinToString("、")}——世界还没动")

        session.applyPendingNow()
        assertTrue(session.pending == null, "应用完就不该还挂着")
        assertTrue(
            session.log.any { it.kind == LogLine.Kind.OK },
            "应用之后这一轮该成立。日志：\n" + session.log.joinToString("\n") { "  [${it.kind}] ${it.text}" },
        )
        assertTrue(session.selection.isEmpty(), "跑完一轮该清空勾选")
        println("▸ ③b 点了「应用」：第二步跑完，世界动了")

        // ── ④ 看过的东西被毁掉：先证明预览拦得住，再放行 ────────────────────────────

        session.focus("oak_table")
        session.observeFocusedNow()
        assertTrue("oak_table" in session.observations, "先得看过它")
        val beforeSplit = session.requestCount
        assertTrue(beforeSplit >= 2)

        val split = "我举起腰间那把短柄斧，把这张木桌从中间劈成两半。"

        session.clearSelection()
        session.runTurn(said = "", acted = split)
        requireNotNull(session.pending) { "劈桌子也什么都没勾，该停在预览上" }
        session.dismissPending()
        assertTrue(session.world.byId("oak_table") != null, "「改动作」之后桌子必须还在")
        assertTrue("oak_table" in session.observations, "描述也不该被作废")
        println("▸ ④a 停在预览上、点「改动作」：桌子还在，描述也还在 ← **停下来是免费的**")

        session.clearSelection()
        session.runTurn(said = "", acted = split)
        requireNotNull(session.pending) { "第二次同样该停在预览上" }
        session.applyPendingNow()

        assertTrue(
            session.world.byId("oak_table") == null,
            "桌子该没了。世界里现在有：${session.world.all().joinToString("、") { it.id }}",
        )
        assertTrue(
            "oak_table" !in session.observations,
            "桌子都没了，界面里不该还留着它的描述",
        )
        println("▸ ④b 这次点了「应用」：oak_table 没了，它的描述也从界面状态里作废了")

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
