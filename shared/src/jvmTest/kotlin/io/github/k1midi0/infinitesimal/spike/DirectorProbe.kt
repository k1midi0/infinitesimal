package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.director.Arrangement
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.world.TimelineEntry
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * 导演排时间轴的联机探针（§8）。
 *
 * 离线那 18 支测试验的是接线（参数写错能不能改回来、不设 `max_tokens`、assistant 带没带
 * `reasoning_content`）。**它们一个字都不能证明排出来的东西合不合用**——那只能真跑。
 *
 * 这一支要回答的是四件事：
 *  1. 它排出来的是不是**这个世界里的事**（"酒馆的矮人"这种世界根本还没有的人算不算数）；
 *  2. 它排的**跨度**有多大（提示词说的是"接下来一两天"）；
 *  3. 一次排几件、要几个来回；
 *  4. 已经排过之后再来一次，它是**接着排**还是把上一次的重排一遍。
 *
 * 跑法：
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*DirectorProbe*" --rerun `
 *     -PinfinitesimalLiveKeyFile="C:\Users\20758\Desktop\测试key.txt"
 * ```
 * stdout 从 `shared/build/test-results/jvmTest/TEST-*DirectorProbe*.xml` 的 `system-out` 读。
 */
class DirectorProbe {

    private fun opening(): World =
        World.from(WorldSnapshot(StartingWorld.tavern(), StartingWorld.rules()))

    private class Run(
        val label: String,
        val result: Arrangement,
        val requests: Int,
        val elapsedMs: Long,
    )

    @Test
    fun whatTheDirectorSchedules() = runBlocking {
        val key = ProbeSupport.keyOrNull()
        if (key == null) {
            println("[跳过] 没给 -PinfinitesimalLiveKeyFile，这一支不跑。")
            return@runBlocking
        }

        val runs = mutableListOf<Run>()

        // 场景一：开局，时间轴空着。跑三次看方差。
        repeat(3) { i ->
            val world = opening()
            val director = Director(ProbeSupport.configOf(key))
            val mark = TimeSource.Monotonic.markNow()
            val result = director.arrange(world, "player")
            runs += Run("甲${i + 1} 开局", result, director.requestCount, mark.elapsedNow().inWholeMilliseconds)
        }

        // 场景二：轴上已经有两件事。它该接着排，而不是把上一次的重排一遍。
        val seeded = opening()
        seeded.timeline.put(
            TimelineEntry("lamps_burn_low", io.github.k1midi0.infinitesimal.world.WorldTime.of(2, 3), "四盏油灯里的油烧得差不多了"),
        )
        seeded.timeline.put(
            TimelineEntry("cellar_door_drifts_open", io.github.k1midi0.infinitesimal.world.WorldTime.of(1, 23), "地窖门自己晃开了一条缝"),
        )
        repeat(2) { i ->
            val world = World.from(seeded.snapshot())
            val director = Director(ProbeSupport.configOf(key))
            val mark = TimeSource.Monotonic.markNow()
            val result = director.arrange(world, "player")
            runs += Run("乙${i + 1} 已排过两件", result, director.requestCount, mark.elapsedNow().inWholeMilliseconds)
        }

        println()
        println("═══════ 导演排时间轴 ═══════")
        for (run in runs) {
            println()
            println("── ${run.label}｜请求 ${run.requests} 次｜${run.elapsedMs} ms")
            when (val r = run.result) {
                is Arrangement.Done -> {
                    println("   排上 ${r.entries.size} 件（第 ${r.attempts} 次尝试）")
                    r.entries.sortedBy { it.dueAt.minutes }.forEach {
                        println("   · ${it.dueAt}　${it.what}　[id=${it.id}]")
                    }
                    val span = r.entries.maxOfOrNull { it.dueAt.minutes }?.minus(World.from(WorldSnapshot(StartingWorld.tavern())).clock.minutes)
                    if (span != null) println("   最远一件在 ${span / 60} 小时之后")
                }

                is Arrangement.FailedProtocol -> {
                    println("   没排成（试了 ${r.attempts} 次）")
                    r.problems.forEach { println("   ! $it") }
                }
            }
        }

        println()
        println("── 合计")
        val done = runs.count { it.result is Arrangement.Done }
        println("   ${runs.size} 次里排成 $done 次")
        println("   请求数 ${runs.joinToString("、") { it.requests.toString() }}")
        println("   耗时 ${runs.joinToString("、") { it.elapsedMs.toString() }} ms（最慢 ${runs.maxOf { it.elapsedMs }} ms）")
    }
}
