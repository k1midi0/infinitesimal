package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.npc.Npc
import io.github.k1midi0.infinitesimal.session.GameSession
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.ChunkView
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.HARD_RADIUS_CHUNKS
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * **对话系统的联机验收**（§5、§14.36）。
 *
 * 离线测试用假端点钉住了因果的形状（谁唤醒谁、深度是 1、NPC 的「做」真的落库）。这里要量的是假端点
 * 永远量不出来的那几样：
 *
 * 1. **判定真的会把"听得见的人"报出来吗。** 这一刀在 `targetingPrompt` 里新加了一条规矩，而它到底
 *    有没有生效，只有一个真模型能回答。
 * 2. **矮人像不像一个人。** 它会不会答非所问、会不会自说自话地宣布自己做成了什么（规则 3）。
 * 3. **一次搭话到底几次调用、几秒。** 这是这一层最该记的账单——NPC 走的判定链路，而判定是两到三次
 *    调用起（§14.26）。
 *
 * 跑法：
 *
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*NpcProbe*" --rerun `
 *     -PinfinitesimalLiveKeyFile="C:\Users\20758\Desktop\测试key.txt"
 * ```
 *
 * 没有密钥时它只打那张**离线**的图（矮人自己看见的那一片），不联网。
 */
class NpcProbe {

    private val originOf: (World) -> ChunkOrigin = { world ->
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))
    }

    @Test
    fun conversation() {
        val key = ProbeSupport.keyOrNull()

        // ── 离线：矮人自己看见的那一片。**不需要密钥**，而它正是这一刀最该先看一眼的东西。 ──
        //
        // **用 `StartingWorld.snapshot()`，不要手写 `WorldSnapshot(tavern())`。** 后者看着是对的，
        // 而它漏掉规则书、地形、区划三样——`NpcProbe` 第一版就是这么写的，于是矮人眼里的整片地
        // 都写着"缺"（那三个字是 `ChunkView` 的"硬圈里还没有地形"）。
        val tavernownly = World.from(StartingWorld.snapshot())
        val dwarf = tavernownly.byId("dwarf")
        println("════════════════════════════════════════════════════════════")
        println("矮人自己看见的那一片（半径 $HARD_RADIUS_CHUNKS 块 = ${HARD_RADIUS_CHUNKS * 8} 米）")
        println("════════════════════════════════════════════════════════════")
        val here = tavernownly.chunkOfEntity("dwarf")
        if (dwarf == null || here == null) {
            println("（世界里没有矮人）")
        } else {
            println(ChunkView.render(tavernownly, originOf(tavernownly), here, radius = HARD_RADIUS_CHUNKS))
            println()
            println("矮人在 (${dwarf.x}, ${dwarf.y}, ${dwarf.z}) 厘米；人设 ${dwarf.cards.size} 张卡。")
        }
        println()

        if (key == null) {
            println("（没有提供 -PinfinitesimalLiveKeyFile，跳过联机那几场）")
            return
        }

        val config = ProbeSupport.configOf(key)

        // ── 联机 ────────────────────────────────────────────────────────────────────
        scene("甲 打个招呼（不勾任何人）", config, said = "你好啊。", acted = "")
        scene("乙 一个不太友善的请求", config, said = "老头，把你那杯酒给我。", acted = "")
        scene("丙 叫它过来（看它会不会自己动手）", config, said = "你过来坐这儿。", acted = "")
        scene("丁 说一句 + 擦桌子", config, said = "你慢慢喝。", acted = "我把桌子擦了擦。", pickTable = true)
        scene(
            "戊 空屋子（世界里没有别人）",
            config,
            said = "有人在吗？",
            acted = "",
            seed = StartingWorld.tavern().filter { it.id != "dwarf" },
        )
    }

    /**
     * 一场：新世界 → 新会话 → 走一轮 → 把这一轮的日志与账单打出来。
     *
     * **每一场都换一个世界。**判定的玩家状态是厚的（属性、规则书），上一场留下的痕迹会变成下一场的
     * 前提——§14.35 那次"端着酒杯还能劈桌子"就是这么栽的。探针之间不该互相污染。
     */
    private fun scene(
        title: String,
        config: EndpointConfig,
        said: String,
        acted: String,
        pickTable: Boolean = false,
        seed: List<Entity> = StartingWorld.tavern(),
    ) = runBlocking {
        println("────────────────────────────────────────────────────────────")
        println(title)
        println("────────────────────────────────────────────────────────────")

        val npc = Npc(config, PresetLibrary())
        val session = GameSession(
            config,
            seed = seed,
            judge = Judge(config),
            npc = npc,
        )
        if (pickTable) session.click("oak_table")

        val mark = TimeSource.Monotonic.markNow()
        val outcome = runCatching { session.runTurn(said, acted) }
        val elapsed = mark.elapsedNow().inWholeMilliseconds

        val failure = outcome.exceptionOrNull()
        if (failure != null) {
            println("！这一场没跑起来：${failure::class.simpleName}: ${failure.message}")
            println()
            return@runBlocking
        }

        session.log.forEach { println("  ${it.kind}｜${it.text}") }

        println()
        println(
            "  账单：${elapsed} ms；总请求 ${session.requestCount} 次" +
                "（其中角色自己的 ${npc.requestCount} 次、它调判定 ${npc.judgeRequestCount} 次、会话判定 ${session.requestCount - npc.requestCount - npc.judgeRequestCount} 次）",
        )
        println("  气泡：${session.speech.joinToString("；") { "${if (it.whoId == "player") "玩家" else it.whoId}「${it.text}」" }}")
        println()
    }
}
