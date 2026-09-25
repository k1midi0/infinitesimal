package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.generate.TerrainGenerator
import io.github.k1midi0.infinitesimal.judge.Consequence
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.TerrainCell
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * 生成器探针（A.16、§14.30）。
 *
 * 两支：一支**离线**，只把这次会发出去的那两段文字打出来——提示词是这一层最该被人读一遍的东西，
 * 而它在一次真实调用里是看不见的；一支**联机**，真叫一次生成。
 *
 * 跑法：
 *
 *     .\gradlew.bat :shared:jvmTest --tests "*TerrainGenProbe*" --rerun `
 *         -PinfinitesimalLiveKeyFile="C:\Users\20758\Desktop\测试key.txt"
 *
 * stdout 从 `shared/build/test-results/jvmTest/TEST-*TerrainGenProbe*.xml` 的 `system-out` 里读。
 */
class TerrainGenProbe {

    /** 硬性圈（半径 4 块）东边紧挨着的那两块。开局那片地覆盖到 chunk 5。 */
    private val east = listOf(ChunkKey(6, 0), ChunkKey(6, 1))

    private val requirement =
        "这里是从灰渡镇往东出镇之后那一段：官道继续往东，路两边先是稀疏的橡树，越往东越密，" +
            "地面是踩实了的土和草，路边有几块半埋在土里的石头。往南那一片地势略低一点，也更湿。"

    private fun world(): World = World.from(
        WorldSnapshot(
            StartingWorld.tavern(),
            StartingWorld.rules(),
            terrain = StartingWorld.ground,
            zoning = StartingWorld.zoning(),
        ),
    )

    // ── 离线：提示词长什么样 ───────────────────────────────────────────────────────

    @Test
    fun printThePrompt() {
        val world = world()
        val generator = TerrainGenerator(ProbeSupport.configOf("离线用不到密钥"))

        println("=== 生成器会发出去的东西（不用密钥）===")
        println("硬性圈里已经有地形的 chunk：${world.terrain.generatedChunks().size} 块")
        println("要长的那两块现在有没有地形：" + east.joinToString("、") { "(${it.x},${it.y})=" + world.terrain.isGenerated(it) })
        println()
        println(generator.preview(world, TerrainGenerator.Request(requirement, east)))
    }

    // ── 联机：真叫一次 ─────────────────────────────────────────────────────────────

    @Test
    fun growForReal() {
        val apiKey = ProbeSupport.keyOrNull()?.trim()
        if (apiKey.isNullOrBlank()) {
            println("=== 没有给 -PinfinitesimalLiveKeyFile，跳过联机 ===")
            return
        }

        val world = world()
        val before = world.terrain.runCount
        println("=== 生成器联机探针 ===")
        println("长之前：地形 ${world.terrain.runCount} 段、已有地形的 chunk ${world.terrain.generatedChunks().size} 块")

        val generator = TerrainGenerator(
            ProbeSupport.configOf(apiKey),
            effort = ProbeSupport.effortFromCommandLine() ?: ReasoningEffort.HIGH,
        )

        val mark = TimeSource.Monotonic.markNow()
        val result = runBlocking {
            generator.grow(world, TerrainGenerator.Request(requirement, east))
        }
        val elapsed = mark.elapsedNow().inWholeMilliseconds

        println()
        println("结果：$result")
        println("耗时 ${elapsed} ms；请求 ${generator.requestCount} 次；finish_reason=${generator.lastFinishReason}")
        println("用量：${generator.lastUsage}")
        println()
        println("地形：$before 段 → ${world.terrain.runCount} 段")
        println("已标记有地形的 chunk：${world.terrain.generatedChunks().size} 块")
        east.forEach { println("  (${it.x},${it.y}) -> ${world.terrain.isGenerated(it)}") }

        if (result is TerrainGenerator.Growth.Grown) {
            println()
            println("写了 ${result.consequences.size} 条后果：")
            result.consequences.take(12).forEach { println("  ${describe(it)}") }
            if (result.consequences.size > 12) println("  ……还有 ${result.consequences.size - 12} 条")

            println()
            println("几条探针列（看得出地面高度与分层）：")
            listOf(100 to 5, 104 to 8, 100 to 20, 108 to 18).forEach { (x, y) ->
                println("  ($x,$y)：${world.terrain.runsAt(x, y).joinToString(" → ") { "${it.material} ${it.from}..${it.to}" }}")
            }
            println()
            println("放上去的东西：")
            world.all().filter { it.kind !in setOf("地点", "角色", "家具", "器具", "门窗") }
                .take(12).forEach { println("  ${it.kind}「${it.label}」(${it.x},${it.y},${it.z})") }
        }
    }

    private fun describe(c: Consequence): String = when (c) {
        is Consequence.FillTerrain ->
            "铺 ${box(c.box)} ← ${c.material}"
        is Consequence.DigTerrain ->
            "挖 ${box(c.box)}"
        is Consequence.ReplaceTerrain ->
            "换 ${box(c.box)}：${c.from} → ${c.to}"
        is Consequence.Spawn ->
            "放 ${c.entity.kind}「${c.entity.label}」(${c.entity.x},${c.entity.y},${c.entity.z}) 形状 ${c.entity.shape?.cellCount ?: 0} 格"
        else -> c.toString()
    }

    private fun box(b: io.github.k1midi0.infinitesimal.world.TerrainBox) =
        "x ${b.minX}..${b.maxX} y ${b.minY}..${b.maxY} z ${b.minZ}..${b.maxZ}"
}
