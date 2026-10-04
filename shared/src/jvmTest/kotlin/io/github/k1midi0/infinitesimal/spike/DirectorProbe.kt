package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.director.Awakening
import io.github.k1midi0.infinitesimal.director.CycleText
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.director.Wake
import io.github.k1midi0.infinitesimal.director.Waking
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Cycle
import io.github.k1midi0.infinitesimal.world.TimelineEntry
import io.github.k1midi0.infinitesimal.world.World
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * 导演排时间轴的联机探针（§8）。
 *
 * 离线那一批测试验的是接线（参数写错能不能改回来、不设 `max_tokens`、assistant 带没带
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
 *     -PinfinitesimalLiveKeyFile="<密钥文件>"
 * ```
 * stdout 从 `shared/build/test-results/jvmTest/TEST-*DirectorProbe*.xml` 的 `system-out` 读。
 */
class DirectorProbe {

    /**
     * 开局世界。
     *
     * **四样必须一起传**（规则书 + 地形 + 区划 + 实体）：`WorldSnapshot` 的那几项默认是空的，少传一样
     * 就得到一片没有地形、没有依据的世界，而"身边的坐标"与那张地图都会跟着不对（§14.35 逮到过两次
     * 同一类）。所以这里用 `StartingWorld.snapshot()`，不再手写两份。
     */
    private fun opening(): World = World.from(StartingWorld.snapshot())

    private fun arranging(world: World) = Waking(
        Wake.Arrange,
        "player",
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0)),
    )

    private class Run(
        val label: String,
        val result: Awakening,
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
            val result = director.wake(world, arranging(world))
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
            val result = director.wake(world, arranging(world))
            runs += Run("乙${i + 1} 已排过两件", result, director.requestCount, mark.elapsedNow().inWholeMilliseconds)
        }

        println()
        println("═══════ 导演排时间轴 ═══════")
        for (run in runs) {
            println()
            println("── ${run.label}｜请求 ${run.requests} 次｜${run.elapsedMs} ms")
            when (val r = run.result) {
                is Awakening.Done -> {
                    println("   排上 ${r.scheduled.size} 件（第 ${r.attempts} 次尝试）")
                    r.scheduled.sortedBy { it.dueAt.minutes }.forEach {
                        println("   · ${it.dueAt}　${it.what}　[id=${it.id}]")
                    }
                    val now = opening().clock.minutes
                    val span = r.scheduled.maxOfOrNull { it.dueAt.minutes }?.minus(now)
                    if (span != null) println("   最远一件在 ${span / 60} 小时之后")
                }

                is Awakening.FailedProtocol -> {
                    println("   没排成（试了 ${r.attempts} 次）")
                    r.problems.forEach { println("   ! $it") }
                }
            }
        }

        println()
        println("── 合计")
        val done = runs.count { it.result is Awakening.Done }
        println("   ${runs.size} 次里排成 $done 次")
        println("   请求数 ${runs.joinToString("、") { it.requests.toString() }}")
        println("   耗时 ${runs.joinToString("、") { it.elapsedMs.toString() }} ms（最慢 ${runs.maxOf { it.elapsedMs }} ms）")
    }

    // ── 写作息（§14.44） ────────────────────────────────────────────────────────────

    /**
     * **导演能不能给一个角色写出一份立得住的作息**（§14.44）。
     *
     * 离线那一批（`DirectorCycleTest`）验的是接线与那五道门——参数写错能不能改回来、驳回时世界有没有
     * 动过、看不见的人能不能写。**它们一个字都不能证明它写出来的作息合不合用**，与这一层当初那句理由
     * 一模一样。这一支要回答的是四件事：
     *
     *  1. 它**愿不愿意**写（唤醒的理由是"排一排时间轴"，写作息完全靠它自己想到）；
     *  2. 它写出来的是不是**这个世界里的事**——那几个坐标在它看得见的那一片里对不对得上；
     *  3. 一圈多长、有没有把"打烊那一格到次日开门"那一整段接上；
     *  4. 一次要几个来回。
     */
    @Test
    fun whatTheDirectorWritesAsACycle() = runBlocking {
        val key = ProbeSupport.keyOrNull()
        if (key == null) {
            println("[跳过] 没给 -PinfinitesimalLiveKeyFile，这一支不跑。")
            return@runBlocking
        }

        val runs = mutableListOf<Run>()
        // 它写进去的那一份：拿"矮人身上的作息"当读数，因为那是世界状态，不是它说了什么。
        val written = mutableListOf<Cycle>()

        repeat(3) { i ->
            val world = opening()
            val director = Director(ProbeSupport.configOf(key))
            val mark = TimeSource.Monotonic.markNow()
            val result = director.wake(world, arranging(world))
            runs += Run("写作息${i + 1}", result, director.requestCount, mark.elapsedNow().inWholeMilliseconds)
            written += world.byId("dwarf")?.cycle ?: Cycle()
        }

        println()
        println("═══════ 导演写作息 ═══════")
        for ((i, cycle) in written.withIndex()) {
            println()
            println("── 第 ${i + 1} 次｜请求 ${runs[i].requests} 次｜${runs[i].elapsedMs} ms")
            if (cycle.isEmpty) {
                println("   它没写。")
                continue
            }
            println("   一圈 ${cycle.periodMinutes} 分钟（${cycle.periodMinutes / 60} 小时 ${cycle.periodMinutes % 60} 分）")
            println("   起算于 ${cycle.bornAt}")
            cycle.beats.forEach { println("   · ${CycleText.render(it)}") }
            // **立不立得住由代码判**，不由它说。这一行是这一支最要紧的读数。
            val problems = cycle.problems()
            println(if (problems.isEmpty()) "   ✓ 立得住" else "   ✗ 立不住：$problems")
        }

        println()
        println("── 合计")
        println("   ${written.size} 次里写成了 ${written.count { it.beats.isNotEmpty() }} 次")
        println("   立得住 ${written.count { it.problems().isEmpty() && it.beats.isNotEmpty() }} 次")
        println("   请求数 ${runs.joinToString("、") { it.requests.toString() }}")
        println("   耗时 ${runs.joinToString("、") { it.elapsedMs.toString() }} ms（最慢 ${runs.maxOf { it.elapsedMs }} ms）")
    }
}
