package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.director.Awakening
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.director.Wake
import io.github.k1midi0.infinitesimal.director.Waking
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import kotlin.test.Test
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking

/**
 * **导演真的会放一个人出来吗，放出来的是个什么样的人**（§14.46）。
 *
 * 离线那一批验的是接线（id 重名被驳、位置必须在看得见的地方、作息第一格必须就是它站的地方）。它
 * **一个字都不能证明模型会写什么**——人设像不像话、坐标是不是照着清单抄的、作息会不会又编。
 *
 * 三个对照，各跑三遍（每一遍都用一份**新的开局世界**，好让读数能横向比）：
 *
 *  1. **平常的傍晚**（开局世界，什么要求都没加）→ 它**没有必须放人的理由**：放也好、不放也好，这一
 *     支要读的是"它会不会无故往世界里塞人"（对照 §14.33 那条成本纪律）。
 *  2. **赶集日的傍晚**（同一份世界，但明说酒馆里挤着赶集的人、而世界上一共只有两个活人）→ 这时候
 *     **该放人**了。要读的是它放出来的人：有的身份、站在哪儿、要不要作息。
 *  3. **玩家点名要一个还没有的人**（`Wake.Called`，理由里带着玩家那句话）→ 最该放人的那一档。
 *
 * 跑法：
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*DirectorSpawnProbe*" --rerun `
 *     -PinfinitesimalLiveKeyFile="C:\Users\20758\Desktop\测试key.txt"
 * ```
 * stdout 从 `shared/build/test-results/jvmTest/TEST-*DirectorSpawnProbe*.xml` 的 `system-out` 读。
 * 整支探针要真联网，很慢——单独跑它一支，别与别的探针一起。
 */
class DirectorSpawnProbe {

    private class Run(
        val label: String,
        val world: World,
        val before: Set<String>,
        val result: Awakening,
        val requests: Int,
        val elapsedMs: Long,
    ) {
        /** 这一次新出现在世界里的人。 */
        fun newcomers(): List<Entity> = world.all().filter { it.id !in before }
    }

    /**
     * ① 那一档用**酒馆里的开局世界**（`StartingWorld.snapshot()`），不是空的荒原。
     *
     * 第一版用的是 `ProbeSupport.tavernSeed()`（实体都在 (1200,300) 那一带）却把玩家放在 (1200, 300,
     * GROUND)——而 `tavernSeed()` 是**另一份夹具**、坐标与 `StartingWorld` 对不上，于是玩家实际站在一片
     * **没有地形、没有东西的空地**上。读数里那十五件"枯灌木 / 碎石堆 / 干草丛"就是这么来的：它站在一片
     * 空地上，被要求"该有点东西了"，于是拿这只手去铺地。**探针的夹具错了，读数就整个没意义**（与
     * §14.45 那条教训同一类）。
     *
     * [crowded] ＝ 把"这一带该有人"写进世界里（赶集日那一档）。
     */
    private fun opening(crowded: Boolean): World {
        val snapshot = StartingWorld.snapshot()
        if (!crowded) return World.from(snapshot)
        return World.from(
            snapshot.copy(
                entities = snapshot.entities.map { entity ->
                    if (entity.id == "tavern_hall") {
                        entity.copy(
                            background = entity.background.orEmpty() + "\n" +
                                "今天是赶集日，镇上的人比往常多得多——大厅里挤着好些外地来的贩子与车夫，" +
                                "桌子早就不够坐了，还有人在门口站着。",
                        )
                    } else {
                        entity
                    }
                },
            ),
        )
    }

    /**
     * ④ 一档：**一片还没有长东西的空地**（"这一带该有点东西了"那一档）。
     *
     * 世界里的东西一件都不带上（`speakQueue` 也清掉），玩家站在空地上。这一档要读的是**它会不会拿这只
     * 手去铺地**——铺地是 `grow_terrain` 的活，而这一支就是要逮那件事。
     */
    private fun barrenLand(): World = World.from(
        WorldSnapshot(
            entities = listOf(
                Entity(
                    id = "player", kind = CHARACTER_KIND, label = "你",
                    x = 1200, y = 300, z = ProbeSupport.GROUND,
                ),
            ),
        ),
    )

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    private fun arranging(world: World) = Waking(Wake.Arrange, "player", origin(world))

    /** 一个**必然**要放人的局：玩家开口问一个世界里还没有的人。 */
    private fun calledUp(world: World) = Waking(
        Wake.Called("玩家问你：「铁匠铺的格里姆今天开门没有？」——而这一带还没有一个铁匠。"),
        "player",
        origin(world),
    )

    private suspend fun run(label: String, world: World, waking: Waking, director: Director): Run {
        val before = world.all().map { it.id }.toSet()
        val mark = TimeSource.Monotonic.markNow()
        val result = director.wake(world, waking)
        return Run(label, world, before, result, director.requestCount, mark.elapsedNow().inWholeMilliseconds)
    }

    private fun report(run: Run) {
        println()
        println("── ${run.label}｜请求 ${run.requests} 次｜${run.elapsedMs} ms")
        when (val r = run.result) {
            is Awakening.Done -> println("   干完了：排了 ${r.scheduled.size} 件，试了 ${r.attempts} 次")
            is Awakening.FailedProtocol -> {
                println("   协议内失败（试了 ${r.attempts} 次）：")
                r.problems.forEach { println("   ! $it") }
            }
        }

        val people = run.newcomers()
        if (people.isEmpty()) {
            println("   **它一个人都没放。**")
            return
        }
        val dressing = people.filter { it.label.isSceneDressing() }
        for (who in people) {
            println(
                "   · ${if (who in dressing) "**放的是一件地上的东西**" else "放了一个人"}：" +
                    "${who.label}（id=${who.id}）站在 (${who.x}, ${who.y}, ${who.z})",
            )
            who.cards.forEach { println("       卡「${it.title}」：${it.content}") }
            if (who.cycle.isEmpty) {
                println("       没有作息（剧情角色：待在原地，被叫到才动）")
            } else {
                println("       有作息，一圈 ${who.cycle.periodMinutes} 分钟，${who.cycle.beats.size} 格：")
                who.cycle.beats.forEach { beat ->
                    println("         - ${beat.atMinute}｜${beat.x},${beat.y},${beat.z}｜${beat.state}")
                }
            }
            println("       背景（代码推的）：${who.background?.replace("\n", " / ")}")
        }
        if (dressing.isNotEmpty()) {
            println("   **这一遍里 ${dressing.size}/${people.size} 件是地上的东西**——这只手被当成了摆道具。")
        }
    }

    /**
     * 看着像"这一片地上的东西"吗。
     *
     * **判据刻意与产品里那道门（`CharacterPlanner`）用同一批词**，但这里是**读数用的**：探针要是靠
     * "产品拦住了没有"才知道自己看到了什么，那它就把被测的那一层当成了眼睛。
     */
    private fun String.isSceneDressing(): Boolean = listOf(
        "灌木", "草丛", "杂草", "苔藓", "藤蔓",
        "碎石", "石块", "石板", "岩体", "砾石", "砂砾",
        "枯树", "树桩", "树皮", "树根",
        "泥坑", "水洼", "积水", "土堆", "沙丘", "积雪",
        "残骸", "废墟", "断墙", "瓦砾", "栅栏", "篱笆",
    ).any { it in this }

    /**
     * 三档一起跑（每档 [timesOf] 遍），印成一张表。
     *
     * **它很慢，而且实测会卡**：第一次跑它时，第一档第二遍卡在一次**不返回的调用**上（CPU 只烧了
     * 143 秒、对外连接为零，70 分钟不落地）——§14.44 那次 961 秒的长停顿是同一类。所以下面三支一格
     * 一支测试：**先单跑一格拿读数，再决定要不要整支跑。**
     */
    @Test
    fun whatComesOutWhenTheDirectorDecidesSomeoneShouldExist() = runBlocking {
        val times = timesOf()
        val plain = runCase(PLAIN, times) { world -> arranging(world) }
        val crowded = runCase(CROWDED, times) { world -> arranging(world) }
        val pointed = runCase(POINTED, times) { world -> calledUp(world) }
        if (plain.isEmpty() && crowded.isEmpty() && pointed.isEmpty()) {
            println("[跳过] 没给 -PinfinitesimalLiveKeyFile，这一支不跑。")
            return@runBlocking
        }

        println("═".repeat(100))
        println("导演放人：它会放谁出来（每档 $times 遍）")
        println("═".repeat(100))
        (plain + crowded + pointed).forEach(::report)

        println()
        println("── 合计")
        println("   ① 平常的傍晚：放了 ${plain.sumOf { it.newcomers().size }} 个人（$times 遍）")
        println("   ② 赶集日：放了 ${crowded.sumOf { it.newcomers().size }} 个人（$times 遍）")
        println("   ③ 玩家点名：放了 ${pointed.sumOf { it.newcomers().size }} 个人（$times 遍）")
        val all = plain + crowded + pointed
        println("   请求数 ${all.joinToString("、") { it.requests.toString() }}")
        println("   耗时 ${all.joinToString("、") { it.elapsedMs.toString() }} ms（最慢 ${all.maxOf { it.elapsedMs }} ms）")
        println()
        println("读数只有人能读：① 与 ②③ 的差别就是「它会不会无故塞人」与「该有人时它写不写得出来」。")
    }

    /**
     * 一档：跑 [times] 遍，一遍一份新的世界、一个新的导演。
     *
     * [waking] 是**最后一个参数**，于是调用处写得出尾随 lambda——它与 `times` 之间夹一个函数类型时，
     * 编译器会去给 `times` 找函数（第一版就是这么报错的：`No value passed for parameter 'waking'`）。
     */
    private suspend fun runCase(
        scenario: String,
        times: Int,
        waking: (World) -> Waking,
    ): List<Run> {
        val key = ProbeSupport.keyOrNull() ?: return emptyList()
        val label = when (scenario) {
            PLAIN -> "① 平常的傍晚"
            CROWDED -> "② 赶集日的傍晚"
            EMPTY -> "④ 一片空地"
            else -> "③ 玩家点名要一个还没有的人"
        }
        return (1..times).map { i ->
            val world = if (scenario == EMPTY) barrenLand() else opening(crowded = scenario == CROWDED)
            run("$label 第${i}遍", world, waking(world), Director(ProbeSupport.configOf(key)))
        }
    }

    /** 三档各一支测试，**这样才能单跑一格看读数**（整支跑会卡在一次不返回的调用上）。 */
    @Test
    fun plainEvening() = runBlocking {
        reportAll("① 平常的傍晚", runCase(PLAIN, timesOf()) { world -> arranging(world) })
    }

    @Test
    fun marketDay() = runBlocking {
        reportAll("② 赶集日的傍晚", runCase(CROWDED, timesOf()) { world -> arranging(world) })
    }

    @Test
    fun thePlayerAsksForSomeoneWhoDoesNotExistYet() = runBlocking {
        reportAll("③ 玩家点名要一个还没有的人", runCase(POINTED, timesOf()) { world -> calledUp(world) })
    }

    /**
     * ④ **一片还没有东西的空地**：这一档读的是"它会不会拿这只手去铺地"。
     *
     * 它正是第一版读数里那十五件灌木的来源——不过那一版是**夹具错了**（玩家被放在一片空地上，而探针
     * 以为它在酒馆里）。这一档是**故意的**：空地是真的，要看它在"该有点东西了"的时候会不会用错手。
     */
    @Test
    fun aBarePatchOfLandDoesNotGetDressedWithPeople() = runBlocking {
        reportAll("④ 一片空地", runCase(EMPTY, timesOf()) { world -> arranging(world) })
    }

    private fun timesOf() =
        System.getProperty("infinitesimal.spawnRuns")?.trim()?.toIntOrNull()?.coerceIn(1, 5) ?: 3

    private fun reportAll(title: String, runs: List<Run>) {
        if (runs.isEmpty()) {
            println("[跳过] 没给 -PinfinitesimalLiveKeyFile，这一支不跑。")
            return
        }
        println("═".repeat(100))
        println("导演放人：$title（${runs.size} 遍）")
        println("═".repeat(100))
        runs.forEach(::report)
        println()
        val dressing = runs.sumOf { run -> run.newcomers().count { it.label.isSceneDressing() } }
        val people = runs.sumOf { it.newcomers().size } - dressing
        println("── 合计：放了 $people 个人、$dressing 件地上的东西（${runs.size} 遍）")
        println("   耗时 ${runs.joinToString("、") { it.elapsedMs.toString() }} ms")
    }

    private companion object {
        const val PLAIN = "plain"
        const val CROWDED = "crowded"
        const val POINTED = "pointed"
        const val EMPTY = "empty"
    }
}
