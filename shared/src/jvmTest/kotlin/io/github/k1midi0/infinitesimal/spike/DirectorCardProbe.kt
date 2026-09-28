package io.github.k1midi0.infinitesimal.spike

import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ReasoningEffort
import io.github.k1midi0.infinitesimal.config.ThinkingMode
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.director.DirectorTools
import io.github.k1midi0.infinitesimal.director.Wake
import io.github.k1midi0.infinitesimal.director.Waking
import io.github.k1midi0.infinitesimal.director.titlesWithSize
import io.github.k1midi0.infinitesimal.generate.TerrainGenerator
import io.github.k1midi0.infinitesimal.llm.LlmClient
import io.github.k1midi0.infinitesimal.session.StartingWorld
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.EventKind
import io.github.k1midi0.infinitesimal.world.Spot
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * **人设追加那只手的联机读数**（§14.52）。
 *
 * 这一支只回答一个问题，而它是这一刀唯一值得量的东西：**它写出来的是"设定"，还是"流水账"？**
 *
 * - 设定 = "你替那个过路人挡了一刀，从此不再信他嘴里说的'没事'"——那是人设，下次醒来它会带着它。
 * - 流水账 = "你去了地窖，搬了一箱酒"——那不是人设，是记录层已经有了的东西被抄了一遍。
 *
 * ## 两个对照
 *
 *  - `REAL_EVENT`：这一趟真的改变了它（替玩家挡了一刀）。**期望它写一张卡或者改写一张。**
 *  - `SMALL_ERRAND`：这一趟什么都没改变它（下地窖搬了箱酒回来）。**期望它什么都不写**——那是
 *    `Wake.Called` 的 `unmet` 为空所允许的合法结果，也是 §14.52 那句"不是给它记流水账"的判据。
 *
 * 它**不驱动会话**：理由那一段（"这一趟里与它有关的事"）由探针自己按 `GameSession.tripLog` 同一个
 * 形状拼出来——这里要量的是导演那一侧，而会话那一侧已经有单测钉住（它交没交、交得对不对）。
 *
 * 跑法（与别的探头一样，没给密钥就跳过）：
 * ```
 * .\gradlew.bat :shared:jvmTest --tests "*DirectorCardProbe*" --rerun ^
 *   -PinfinitesimalLiveKeyFile="C:\Users\20758\Desktop\测试key.txt" -PinfinitesimalCardRuns=1
 * ```
 */
class DirectorCardProbe {

    /** 每一档跑几遍。默认 1 遍——思考档一次几十秒。 */
    private val runs: Int = System.getProperty("infinitesimalCardRuns")?.toIntOrNull() ?: 1

    private val key = ProbeSupport.keyOrNull()

    private fun configOf(apiKey: String) = EndpointConfig(
        baseUrl = "https://api.deepseek.com",
        apiKey = apiKey,
        model = "deepseek-flash",
        thinking = ThinkingMode.Always,
    )

    private fun origin(world: World) =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity("player") ?: ChunkKey(0, 0))

    /**
     * 搭一个"杜林刚脱轨了一趟"的世界：它被叫住（`active`），而记录层里躺着这一趟发生的事。
     *
     * 事件用 `world.log.append(...)` 直接写进去——那与真实游戏里判定/说话写的**同一个函数**，
     * 所以导演读到的东西与真跑时一致。
     */
    private fun scene(trip: List<Triple<String, String, String>>): World {
        val world = World.from(StartingWorld.snapshot())
        val since = WorldTime.of(1, 18)
        val dwarf = world.byId("dwarf")!!
        world.put(
            dwarf.copy(
                active = io.github.k1midi0.infinitesimal.world.Active(
                    since = since,
                    target = dwarf.cycle.beatAt(since),
                ),
            ),
        )
        world.clock = WorldTime.of(1, 21)

        trip.forEachIndexed { i, (who, text, effects) ->
            world.log.append(
                at = since + (i + 1) * 15L,
                kind = if (who == "dwarf") EventKind.ACT else EventKind.SAY,
                actorId = who,
                actorLabel = if (who == "dwarf") "酒馆里的矮人" else "你",
                text = text,
                effects = if (effects.isEmpty()) emptyList() else listOf(effects),
                addressedTo = listOf("dwarf"),
                spot = Spot(1000, 200, 12800),
            )
        }
        return world
    }

    /** 会话那一侧拼的是同一个形状（`GameSession.tripLog`）：开头一句 + 每行一条 + 收尾一句。 */
    private fun reasonFor(world: World, name: String, id: String, since: WorldTime): String = buildString {
        append("「$name」（id 是「$id」）刚刚从它自己日子外的那一趟回来了（那一趟是从 $since 开始的）。\n")
        append("这一趟里与它有关的事（从记录里取的最近 8 条）：\n")
        world.log.all().filter { it.at >= since }.takeLast(8).forEach { event ->
            append("- ").append(event.at).append("　")
            append(if (event.actorId == id) "它：" else "${event.actorLabel}：").append(event.text)
            if (event.effects.isNotEmpty()) append("（后果：").append(event.effects.joinToString("；")).append("）")
            append("\n")
        }
        append("\n把它这一趟的经历写进它的人设——用 `${DirectorTools.WRITE_CARD}`。")
        append("它此刻回到的那一格是：「${world.byId(id)?.cycle?.beatAt(world.clock)?.state}」。")
        append("这一趟什么都没改变它的话，什么都不写也是对的。")
    }

    private suspend fun oneRun(label: String, trip: List<Triple<String, String, String>>, apiKey: String) {
        val config = configOf(apiKey)
        val world = scene(trip)
        val before = world.byId("dwarf")!!.cards
        val since = world.byId("dwarf")!!.active!!.since

        val director = Director(
            LlmClient(config.copy(userId = io.github.k1midi0.infinitesimal.config.SystemId.DIRECTOR)),
            TerrainGenerator(LlmClient(config)),
            effort = ReasoningEffort.HIGH,
        )

        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val result = director.wake(
            world,
            Waking(
                Wake.Called(reasonFor(world, "酒馆里的矮人", "dwarf", since), returnedId = "dwarf"),
                "player",
                origin(world),
            ),
        )
        val elapsed = mark.elapsedNow().inWholeSeconds

        val after = world.byId("dwarf")!!.cards
        println("── $label（${elapsed} 秒，请求 ${director.requestCount} 次，结果 $result）")
        println("   写前：${before.titlesWithSize()}")
        println("   写后：${after.titlesWithSize()}")
        val added = after.filter { card -> before.none { it.title == card.title } }
        val rewritten = after.filter { card -> before.any { it.title == card.title && it.content != card.content } }
        when {
            added.isEmpty() && rewritten.isEmpty() -> println("   读数：**什么都没写**")
            else -> {
                added.forEach { println("   添了「${it.title}」：${it.content}") }
                rewritten.forEach { println("   改写「${it.title}」→ ${it.content}") }
            }
        }
    }

    @Test
    fun whatItWritesWhenSomebodyComesBack() = runBlocking {
        val apiKey = key
        if (apiKey == null) {
            println("[跳过] 没给 -PinfinitesimalLiveKeyFile，这一支不跑。")
            return@runBlocking
        }

        val realEvent = listOf(
            Triple("player", "算我欠你一次。", ""),
            Triple("dwarf", "我替他挡了那一下。", "左臂上撕开一道口子，血流到袖口"),
            Triple("player", "先别管柜台上那摊东西了。", ""),
        )
        val smallErrand = listOf(
            Triple("player", "酒还有吗？", ""),
            Triple("dwarf", "下去搬一箱上来。", "从地窖搬了一箱麦酒上来"),
            Triple("player", "谢了。", ""),
        )

        repeat(runs) { i ->
            oneRun("对照一 · 这一趟真的改变了它（第 ${i + 1} 遍）", realEvent, apiKey)
            oneRun("对照二 · 这一趟什么都没改变它（第 ${i + 1} 遍）", smallErrand, apiKey)
        }

        println(
            "【怎么读】对照一要写出**设定**（它对人对事的立场变了），对照二**什么都不写**才是对的" +
                "——两边都写成'它下了地窖/它挡了一刀'那种流水账，就说明这一只手的提示词还得改。",
        )
    }
}
