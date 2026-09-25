package io.github.k1midi0.infinitesimal.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.director.Arrangement
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.judge.Consequence
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.judge.TargetAlignment
import io.github.k1midi0.infinitesimal.render.DetailInserters
import io.github.k1midi0.infinitesimal.render.Renderer
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.TerrainSnapshot
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import io.github.k1midi0.infinitesimal.world.ZoningSnapshot
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

/** 玩家自己的 id。开局世界和判定都用它。 */
const val PLAYER = "player"

/** 日志里的一行。[Kind] 只用来决定颜色，不参与任何逻辑。 */
data class LogLine(val kind: Kind, val text: String) {
    enum class Kind { SAY, ACT, OK, REJECT, PROBLEM, NOTE, ERROR }
}

/**
 * 「对齐完了、还没算后果」这一小段中间状态（§4.7）。
 *
 * 它攥着 [Judge.Turn]——那里面是这次动作的扩大预算。**预算是这一次动作的属性，不是某一步的
 * 属性**：第一步扩过之后，玩家确认完再跑第二步时，拿到的是已经大了的那个框（§4.6）。
 */
class PendingAlignment internal constructor(
    /** 玩家当初那句话。**「改动作」时界面可以拿它回填输入框。** */
    val intention: String,
    /** 玩家当初勾的那些。 */
    val picked: List<String>,
    /** 判定对齐出来的那些。**与 [picked] 不同，这是它存在的理由。** */
    val targets: List<String>,
    internal val turn: Judge.Turn,
)

/**
 * 一轮游戏的全部状态。
 *
 * **界面不认识 [World]，只认识这里暴露出来的那几个状态。** 世界本身不是可观察的（它是一个普通
 * Map），所以每次改完都重新取一份快照推给界面——这是这一层存在的主要理由。
 *
 * 这个类也是**唯一**知道"一轮要按什么顺序调哪些东西"的地方：
 *
 * ```
 * 玩家提交 → 说（暂无接收方）→ 做 → 判定两步 → 落库 → 被改动的描述作废
 * ```
 *
 * 它不做的事：**不自动观察被改动的物体**。描述作废之后等着玩家下次看它（§7.1）——自动重渲染会让
 * 一次"劈桌子"变成七次渲染，而那七次里有六次玩家根本不会去看。
 */
class GameSession(
    private val config: EndpointConfig,
    seed: List<Entity> = StartingWorld.tavern(),
    /** 世界自己的规则书（§12 第 12 项）。与 [seed] 分开，因为它的作用域本来就不一样。 */
    rules: Rulebook = StartingWorld.rules(),
    /** 地形（附录 A.15）。与 [seed] 一样是**内容**，默认就是开局那一圈地。 */
    ground: TerrainSnapshot = StartingWorld.ground,
    /** 区划（附录 A.16）。生成器读它，决定一片地方该长成什么样；**它是意图，不是事实**。 */
    zoning: ZoningSnapshot = StartingWorld.zoning(),
    /**
     * 预设库（附录 A.14）。**它挂在会话上**，因为它是活的：一局里造出来的东西会进生成那个包，
     * 而判定、生成器、导演该看**同一份**——三处各拿一份的话，"刚才造过的那张桌子"对另一处就不存在，
     * 而症状是同一件东西被反复建模。
     *
     * 默认是一个**不含出厂包的**空库：那是给测试与探针用的兜底。真实入口（[desktopApp] 的 `Main`）
     * 在读 [io.github.k1midi0.infinitesimal.world.BuiltinPresets] 之后把它传进来。
     */
    presets: PresetLibrary = PresetLibrary(),
) {
    val world: World = World.from(
        WorldSnapshot(seed, rules, terrain = ground, zoning = zoning),
    )
    private val renderer = Renderer(config, world)
    private val judge = Judge(config, presets = presets)

    /**
     * 导演（§8）。**它自己把请求落到 [SystemId.DIRECTOR] 那个缓存池**，所以传进去的还是原配置。
     *
     * 它与判定共用同一个客户端配置，但用户 id 不同——那是"同一个模型、两套提示词、两个缓存池"，
     * 不是两个模型（§10.1）。
     */
    private val director = Director(config)

    /** 装配证据。**FIM 默认关闭**（§14.4：五次里只有两次可用），这里也用它。 */
    private val inserter = DetailInserters.of(config)

    // ── 界面读的状态 ────────────────────────────────────────────────────────────────

    var entities by mutableStateOf(world.all())
        private set

    /** 玩家勾选的、他认为这次动作作用的东西。**这只是猜测，判定第一步会修正它。** */
    var selection by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 观察窗口正在看的东西。和 [selection] 是两件事。 */
    var focused by mutableStateOf<String?>(PLAYER)
        private set

    /** 已经固化下来的描述。**不在表里 = 还没看过它**，这一条是界面唯一要判的。 */
    var observations by mutableStateOf<Map<String, Renderer.Observation>>(emptyMap())
        private set

    var log by mutableStateOf<List<LogLine>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var status by mutableStateOf("")
        private set

    /**
     * 第一步对齐完了、在等玩家确认的那一次（§4.7）。**不是 null 就是有东西在等。**
     *
     * 它存在的理由是：世界**一个字都还没动**。判定第一步只会说"这次动作指向哪些东西"，改世界是
     * 第二步的事——所以停在这里是免费的，没有要回滚的东西。
     */
    var pending by mutableStateOf<PendingAlignment?>(null)
        private set

    val focusedEntity: Entity? get() = focused?.let { world.byId(it) }
    val focusedObservation: Renderer.Observation? get() = focused?.let { observations[it] }

    /**
     * 世界现在是什么时候（§8.1）。界面直接显示它。
     *
     * 它必须是一个**推给界面的状态**，不能靠界面去问 [World]——世界不是可观察的，界面每天亮着的那
     * 行字就会一直停在开局的那一刻。
     */
    var clock by mutableStateOf(world.clock)
        private set

    /**
     * 时间轴改动了几次。**它没有别的用途，就是给界面一个会变的信号。**
     *
     * 原因是 `world.timeline` 不是可观察状态：[nextStop] 每次读的是它当下的样子，而 Compose 只会在它
     * 观察到的状态变化时重算。少了这个计数器，导演刚排完事，按钮上写的还是「没有可等的」。
     */
    var timelineRevision by mutableStateOf(0)
        private set

    /**
     * 时间轴上最早会因为到期而必须停下的时刻，没有就是 `null`。
     *
     * 界面用它决定"等到下一件事"那个按钮能不能按。**按下之后会发生什么，全看这一条**——没有可等的
     * 时候推进时间是凭空造出一段没人看见的时间（见 [FastForward.runToNext]）。
     */
    val nextStop: WorldTime?
        get() {
            timelineRevision // 订阅：时间轴一动，这一条就要重算
            return FastForward.nextStop(world)
        }

    /** 整场跑掉的模型调用。固化命中不会让它增加——这是"固化生效"唯一的客观证据。 */
    val requestCount: Int get() = renderer.requestCount + judge.requestCount + director.requestCount

    /**
     * 这一局的预设库（附录 A.14）。
     *
     * **存档要带走生成包**——它是世界内容，不是加速表（用户定的：产物是与出厂预设同一层级的东西）。
     * 现在它挂在那一个判定器上，而判定器是每局造一个，两者寿命正好对上；等存档落地，该由这里持有
     * 再把库传进判定器。
     */
    val presets: PresetLibrary get() = judge.presets

    // ── 界面调的动作 ────────────────────────────────────────────────────────────────

    /** 点一下：既切换勾选，又把它送到观察窗口。 */
    fun click(id: String) {
        focused = id
        selection = if (id in selection) selection - id else selection + id
    }

    /** 只看，不动勾选。地图上点一下用这个，列表里点一下用 [click]。 */
    fun focus(id: String) {
        focused = id
    }

    fun clearSelection() {
        selection = emptySet()
    }

    /**
     * 观察窗口里那个"看它一眼"。
     *
     * 已经固化过的东西会**立刻**返回（0 请求 0 耗时），所以这个按钮可以放心连点。
     * 只有描述作废之后（被判定改过）它才会真的再烧一次渲染。
     */
    suspend fun observeFocusedNow() {
        val id = focused ?: return
        if (world.byId(id) == null) {
            say(LogLine.Kind.ERROR, "这个东西已经不在世界里了。")
            return
        }
        val observation = renderer.observe(id)
        observations = observations + (id to observation)
        // 固化命中要说出来，否则"没花钱"和"没反应"长得一样。
        val cost = if (observation.fromCache) "（固化命中，没花一次请求）" else "（${observation.elapsedMs} ms）"
        say(LogLine.Kind.NOTE, "看过了${labelOf(id)}：${observation.sentences.size} 句 $cost")
    }

    fun observeFocused(scope: CoroutineScope) = run(scope, "看它一眼…") { observeFocusedNow() }

    /**
     * 提交一轮。
     *
     * 说的那一半**目前没有接收方**——按 §5.1 它该走范围广播，但那还没写。这里把这件事明说出来，
     * 而不是假装说了一句话。
     */
    suspend fun runTurn(said: String, acted: String) {
        if (said.isNotBlank()) {
            say(LogLine.Kind.SAY, "你说：「${said.trim()}」")
            say(LogLine.Kind.NOTE, "（§5 的对话系统还没写，这句话现在传不到任何人耳朵里）")
        }
        if (acted.isNotBlank()) judgeAndCommit(acted.trim())
    }

    fun submit(scope: CoroutineScope, said: String, acted: String) {
        if (said.isBlank() && acted.isBlank()) return
        run(scope, "跑一轮…") { runTurn(said, acted) }
    }

    // ── 纠正预览的两个按钮（§4.7） ─────────────────────────────────────────────────

    /**
     * 「应用」：接受判定纠正后的指向，接着跑第二步。
     *
     * **这是玩家唯一说"对，就是这些"的地方**，而它之后世界才会动。
     */
    fun applyPending(scope: CoroutineScope) {
        val p = pending ?: return
        run(scope, "算后果…") {
            pending = null
            runEffects(p.turn, p.targets)
        }
    }

    /** 挂起版：给 spike 直接 await 用（否则测试只能 sleep 猜）。 */
    suspend fun applyPendingNow() {
        val p = pending ?: return
        pending = null
        runEffects(p.turn, p.targets)
    }

    /**
     * 「改动作」：把这一次丢掉。
     *
     * **没有要回滚的东西**——第一步不改世界（§4.6），所以停下来的这段是免费的。
     */
    fun dismissPending() {
        if (pending == null) return
        pending = null
        say(LogLine.Kind.NOTE, "（这一次丢掉了；世界一个字都没动过）")
        selection = emptySet()
    }

    /**
     * 「等到下一件事」（§8.1）。**宿主能力，不是世界观里的动作**（§4.5）——时间是玩家身上挂着的那
     * 一层东西，和存档、读档、查看器同类。
     *
     * 它**只停一次**：走到最早到期的那一刻就交回控制权。要接着等就再按一次。理由在
     * [FastForward.run] 上——停下来意味着"该有人看一眼了"。
     */
    fun waitUntilNext(scope: CoroutineScope) {
        run(scope, "等…") { waitUntilNextNow() }
    }

    /** 挂起版：给 spike 直接 await 用。 */
    suspend fun waitUntilNextNow() {
        val stop = FastForward.runToNext(world)
        clock = world.clock
        timelineRevision++

        if (stop == null) {
            say(LogLine.Kind.NOTE, "（时间轴上没有将来要发生的事，等不到什么）")
            return
        }

        say(LogLine.Kind.NOTE, "（你等到 ${stop.at}）")
        stop.happened.forEach { say(LogLine.Kind.NOTE, "· ${it.what}") }
        // 到期的那些**已经从时间轴上取走**（时间轴是计划，不是记录）。它们该进记录层——**记录层还
        // 没有**（§1.5），所以此刻它们只落在日志里，这也是"刚才那段时间里发生过什么"唯一的去处。
    }

    /**
     * 「让导演排一排」（§8）。**宿主能力，不是世界观里的动作**——和上面的等待同类。
     *
     * 现在它是手动按的，因为导演的**自动触发**（时间轴到期 / 剧情跑偏，§8.2）与**任务队列**
     * （§8.3.2：全局一个、串行、界面要显示"世界中…"）都还没做。按一次排在前面，比先做队列再拿它当
     * 第一个消费者要省事：队列那条路要解决的问题（并发写、积压、没有提示）现在都还不存在。
     */
    fun arrange(scope: CoroutineScope) {
        run(scope, "导演在安排将来的事…") { arrangeNow() }
    }

    /** 挂起版：给 spike 直接 await 用。 */
    suspend fun arrangeNow() {
        when (val result = director.arrange(world, PLAYER)) {
            is Arrangement.Done -> {
                timelineRevision++
                say(LogLine.Kind.NOTE, "（导演排下了 ${result.entries.size} 件事）")
                result.entries.sortedBy { it.dueAt.minutes }.forEach {
                    say(LogLine.Kind.NOTE, "· ${it.dueAt}　${it.what}")
                }
            }

            is Arrangement.FailedProtocol -> {
                // **协议内的失败就说自己是协议内的失败**（§1.5 第三条）：导演没排成不是"世界拒绝了"，
                // 是这一层出了问题，两件事不能用同一句话呈现。
                say(
                    LogLine.Kind.PROBLEM,
                    "（导演没排成：${result.problems.joinToString("；")}）",
                )
            }
        }
    }

    /**
     * 把一次动作放到协程里，并保证三件事：**同一时刻只有一个动作**、界面上的"跑着"一定会被清掉、
     * 世界改完之后一定推一份新快照给界面。
     *
     * 挂在挂起版外面，是为了让 spike 能直接 await 这些动作——否则测试里"提交一轮"只能靠 sleep 等，
     * 而 sleep 多久都是猜的。
     */
    private fun run(scope: CoroutineScope, what: String, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = what
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                say(LogLine.Kind.ERROR, "出错了：${t.message}")
            } finally {
                entities = world.all()
                clock = world.clock
                timelineRevision++
                busy = false
                status = ""
            }
        }
    }

    // ── 一轮的内部 ──────────────────────────────────────────────────────────────────

    private suspend fun judgeAndCommit(intention: String) {
        say(LogLine.Kind.ACT, "你做：$intention")

        // **标签要先抓下来。** 判定落库之后被 Destroy 的东西就不在世界里了，那时候再查
        // 名字只能拿到 id。日志里出现一串 id 是给开发者看的，不是给玩家看的。
        val labels = world.all().associate { it.id to it.label }
        fun name(id: String) = labels[id] ?: world.byId(id)?.label ?: id

        val picked = selection.toList()
        say(
            LogLine.Kind.NOTE,
            if (picked.isEmpty()) "（你什么都没勾）" else "（你勾的是：${picked.joinToString("、") { name(it) }}）",
        )

        status = "判定中…"
        val turn = judge.beginTurn(
            world,
            JudgeRequest(
                actorId = PLAYER,
                actorLabel = "你",
                intention = intention,
                selected = picked,
                centers = picked,
            ),
        )
        val alignment = try {
            // **只跑第一步。** 它不改世界，所以走到哪里停下都不需要回滚（§4.7）。
            turn.align()
        } catch (t: Throwable) {
            say(LogLine.Kind.ERROR, "判定没跑起来：${t.message}")
            return
        }

        when (alignment) {
            is TargetAlignment.Aligned ->
                if (alignment.targets.toSet() != picked.toSet()) {
                    pending = PendingAlignment(intention, picked, alignment.targets, turn)
                    say(
                        LogLine.Kind.NOTE,
                        "对齐到：${alignment.targets.joinToString("、") { name(it) }}" +
                            "　← 和你勾的不一样（世界还没动）",
                    )
                    status = ""
                    return
                } else {
                    runEffects(turn, alignment.targets)
                }

            is TargetAlignment.Rejected -> {
                // 这是给玩家看的那一层：**不出现任何系统词**。
                say(LogLine.Kind.REJECT, "「${alignment.reason}」")
                say(
                    LogLine.Kind.NOTE,
                    "（在第一步拦下的：**你指向的东西对不上**——改一下勾选就能重来；世界没动）",
                )
            }

            is TargetAlignment.Malformed -> {
                say(LogLine.Kind.PROBLEM, "协议内失败——按设计不给玩家看，这里显示是为了调试：")
                alignment.problems.forEach { say(LogLine.Kind.NOTE, "   · $it") }
                say(LogLine.Kind.NOTE, "   （这一次动作当作没发生；世界没动）")
            }
        }
        selection = emptySet()
    }

    /**
     * 第二步：列后果、校验、提交。**这是唯一改世界的地方。**
     *
     * 两个入口共用它：对齐结果与勾选一致时直接跑，玩家点「应用」时跑（§4.7）。所以它只认
     * [targets]，不关心那个集合是模型自己给的还是玩家确认过的。
     */
    private suspend fun runEffects(turn: Judge.Turn, targets: List<String>) {
        // 同一个理由：标签要在落库之前抓。
        val labels = world.all().associate { it.id to it.label }
        fun name(id: String) = labels[id] ?: world.byId(id)?.label ?: id

        status = "算后果…"
        val mark = TimeSource.Monotonic.markNow()
        val judgment = try {
            turn.finish(targets)
        } catch (t: Throwable) {
            say(LogLine.Kind.ERROR, "判定没跑起来：${t.message}")
            return
        }
        val elapsed = mark.elapsedNow().inWholeMilliseconds

        when (judgment) {
            is Judgment.Applied -> {
                // **这里不再说「对齐到」**：两条入口都已经说过了——一致时根本没什么可说，不一致时
                // 那句「对齐到：…　← 和你勾的不一样（世界还没动）」在预览那一步就写进日志了。
                say(
                    LogLine.Kind.OK,
                    "成立——${judgment.consequences.size} 条后果，尝试 ${judgment.attempts} 次，${elapsed} ms",
                )
                judgment.consequences.forEach { say(LogLine.Kind.NOTE, "   · ${describe(it, ::name)}") }

                // **描述作废**，不是重新渲染（§7.1）。把它们从表里剔掉，界面上它们就退回"还没看过"。
                // 下一次观察才会真的再烧一次——而且只烧玩家真的去看了的那几个。
                //
                // **只看 [Consequence.EntityConsequence]。** 地形那几条后果没有 `targetId`，也不该
                // 让任何东西的描述作废：描述只讲物体自身（§7.6），脚下的土被挖掉不改变杯子长什么样
                // ——见 `Consequence.TerrainConsequence` 的注释。
                val stale = judgment.consequences
                    .filterIsInstance<Consequence.EntityConsequence>()
                    .map { it.targetId }
                    .toSet() + judgment.affected
                observations = observations - stale
                stale.forEach { if (world.byId(it) != null) say(LogLine.Kind.NOTE, "   ${name(it)}的描述作废了，下次看它才会重渲染") }
            }

            is Judgment.RejectedWorld -> {
                // 这是给玩家看的那一层：**不出现任何系统词**。
                say(LogLine.Kind.REJECT, "「${judgment.reason}」")
                val which = when (judgment.stage) {
                    io.github.k1midi0.infinitesimal.judge.Stage.ALIGN ->
                        "在第一步拦下的：**你指向的东西对不上**——改一下勾选就能重来"
                    io.github.k1midi0.infinitesimal.judge.Stage.EFFECT ->
                        "在第二步拦下的：动作本身不成立，这条路走不通"
                }
                say(LogLine.Kind.NOTE, "（$which；${elapsed} ms）")
            }

            is Judgment.Nothing -> {
                // 第一步一个目标都没报 = 这次动作不指向也不牵涉任何东西。**第二步根本没有跑**，
                // 所以这里既没有驳回的理由、也没有后果可以报。
                say(LogLine.Kind.NOTE, "（这一次动作没有落在任何东西上；世界没动，第二步没有跑）")
            }

            is Judgment.Unchanged -> {
                // 动作成立，但世界没动。**这不是驳回、也不是失败**——所以既不用 REJECT 的颜色，
                // 也不写 PROBLEM。
                say(LogLine.Kind.OK, "成立——没有改变世界（${elapsed} ms）")
                say(LogLine.Kind.NOTE, "   · ${judgment.note}")
            }

            is Judgment.FailedProtocol -> {
                say(LogLine.Kind.PROBLEM, "协议内失败——按设计不给玩家看，这里显示是为了调试：")
                judgment.problems.forEach { say(LogLine.Kind.NOTE, "   · $it") }
                say(LogLine.Kind.NOTE, "   （这一次动作当作没发生；世界没动）")
            }
        }
        selection = emptySet()
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────────────

    private fun labelOf(id: String): String = world.byId(id)?.label ?: id

    private fun say(kind: LogLine.Kind, text: String) {
        log = log + LogLine(kind, text)
    }

    fun clearLog() {
        log = emptyList()
    }

    private fun describe(c: Consequence, name: (String) -> String): String = when (c) {
        is Consequence.Move -> "${name(c.id)} 挪到了 (${c.x}, ${c.y}, ${c.z}) 厘米"
        is Consequence.SetAttribute -> "${name(c.id)} 的「${c.key}」变成「${c.value}」"
        is Consequence.ChangeAppearance -> "${name(c.id)} 只是外表变了（描述作废）"
        is Consequence.Destroy -> "${name(c.id)} 从世界里消失了"
        is Consequence.Spawn -> "出现了「${c.entity.label}」"
        is Consequence.Replace -> "${name(c.oldId)} 变成了「${c.entity.label}」（另一个东西）"
        is Consequence.SetShape -> "${name(c.id)} 有了形状（占 ${c.shape.cellCount} 个格）"
        // ── 地形（第八类，附录 A.15）────────────────────────────────────────────────
        // 玩家的日志里不出现"地形格"这种系统词？**这里出现是有意的**：地形改动没有"一样东西"可以
        // 指代，玩家能对上的只有位置。等界面能画地形之后，这一行应该换成坐标。
        is Consequence.FillTerrain ->
            "这一片地上铺了${materialName(c.material)}（框里 ${c.box.cellCount} 个格）"
        is Consequence.DigTerrain -> "这一片地被挖空了（框里 ${c.box.cellCount} 个格）"
        is Consequence.ReplaceTerrain ->
            "这一片里的${materialName(c.from)}变成了${materialName(c.to)}（框里 ${c.box.cellCount} 个格）"
    }

    /** 材质 id → 给人看的名字。表里找不到就原样回那个 id——**不编一个名字出来**。 */
    private fun materialName(id: String): String = world.materials.byId(id)?.name ?: id

    /** 给界面显示"这是什么、在哪"。**位置只来自坐标**——关系不存成事实（附录 A.5）。 */
    fun subtitleOf(entity: Entity): String =
        "${entity.kind}｜(${entity.x}, ${entity.y}, ${entity.z}) 厘米"
}
