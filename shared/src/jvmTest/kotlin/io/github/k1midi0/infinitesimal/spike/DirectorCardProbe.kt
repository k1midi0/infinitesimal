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

    /** 会话那一侧拼的是同一个形状（`GameSession.wakeTheDirectorAboutTurningActive`）。 */
    private fun reasonFor(world: World, name: String, id: String, heard: String): String = buildString {
        append("$name（id 是「$id」）刚刚从**氛围角色变成了活跃角色**：")
        append("有人跟它说上话了，于是它被从自己的日子上拿了下来（§3.2.1）。\n")
        append("跟它说的是：「$heard」\n")
        append("由你决定要不要给它**补背景**。**先判该不该补**：只有你能说出一件接下来确实会问它的事")
        append("（问路、托付一条支线、要它知道更多）才补；说不出来就**什么都别写**——那是合法的结果。\n")
        append("**决定要补之后**，再看是哪一种形状：它需要**知道更多**（这一带有什么、它认识谁）")
        append("→ **添一张新的**；它需要**与原来不同**（要它信任谁、要它改掉某一条脾气、要它跟某人")
        append("翻脸）→ **改写与那件事冲突的那一张**（标题照着它现在写的那一条抄）。")
        append("**别在冲突的那一条旁边另添一张**——两张卡互相打架，它每次醒来都要读一遍两个说法。\n")
        append("**别写它这一趟经历了什么**——那些事本来就在它自己的上下文里。")
        append("**该不该补判不出来，就什么都别写。**")
    }

    private suspend fun oneRun(label: String, heard: String, apiKey: String) {
        val config = configOf(apiKey)
        val world = scene(listOf(Triple("player", heard, "")))
        val before = world.byId("dwarf")!!.cards

        val director = Director(
            LlmClient(config.copy(userId = io.github.k1midi0.infinitesimal.config.SystemId.DIRECTOR)),
            TerrainGenerator(LlmClient(config)),
            effort = ReasoningEffort.HIGH,
        )

        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val result = director.wake(
            world,
            Waking(
                Wake.Called(reasonFor(world, "酒馆里的矮人", "dwarf", heard), aboutId = "dwarf"),
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
    fun whatItWritesWhenSomebodyTurnsActive() = runBlocking {
        val apiKey = key
        if (apiKey == null) {
            println("[跳过] 没给 -PinfinitesimalLiveKeyFile，这一支不跑。")
            return@runBlocking
        }

        // **对照一：世界接下来要问它一件它现在答不上来的事。** 玩家刚在问路，而它那四张卡
        // （身份/性格/交互范围/口癖）里一个字都没有"这一带有什么"。**期望它补一层背景。**
        val askedTheWay = "这镇上哪儿还有酒馆？"
        // **对照二：一次普通搭话，它那几张卡够用。** 期望它什么都不写——这一档是
        // 2026-09-28 第一遍跑出来的**假阳性**（当时理由里只有一份"将来可能会问它什么"的泛泛清单，
        // 于是它两次都补）：现在理由里带上了"跟它说的是什么"，这一档就是那条修正的判据。
        val smallTalk = "你喝什么呢？"

        // **对照三：世界要问的是一件与它现在那张「身份」冲突的事。** 它的身份写着"替醉猫的老板看场子
        // 换酒喝"，而现在要它担得起"打过铁"——**添一张新卡会与身份那张互相矛盾**，正确的形状是
        // **改写身份那一张**（或者把这一层并进它）。这一档要看的就是改写那条路真的走得到。
        val askForTheSmith = "镇上人都说你打过铁——你这双手不像只会端酒的。"

        // **对照四：世界要问的是一件与它现在那张「性格」直接冲突的事。** 性格写着"不熟的人搭话，你先
        // 看他一眼，再决定理不理"——而现在它得**信任这个玩家**。添一张新卡会与性格那张互相矛盾，
        // 所以这一档是**改写**那条路唯一走得通的地方（对照三那一遍它添了两张，因为那一层与身份不冲突）。
        val toBeTrusted = "我替你把那件事扛下来了——往后你不用防着我。"

        repeat(runs) { i ->
            oneRun("对照一 · 它被问了一件答不上来的事（第 ${i + 1} 遍）", askedTheWay, apiKey)
            oneRun("对照二 · 一次普通搭话（第 ${i + 1} 遍）", smallTalk, apiKey)
            oneRun("对照三 · 加一层与身份不冲突的过去（第 ${i + 1} 遍）", askForTheSmith, apiKey)
            oneRun("对照四 · 要它改掉性格里写着的那一条（第 ${i + 1} 遍）", toBeTrusted, apiKey)
        }

        println(
            "【怎么读】对照一要补出**背景**（它凭什么知道这一带的事——它是谁、它知道什么），" +
                "对照二**什么都不写**才是对的。两边都写成「它跟玩家聊了两句」那种流水账，" +
                "就说明这一只手的提示词还得改。",
        )
    }
}
