package io.github.k1midi0.infinitesimal.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.judge.Consequence
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.judge.TargetAlignment
import io.github.k1midi0.infinitesimal.render.DetailInserters
import io.github.k1midi0.infinitesimal.render.Renderer
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
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
) {
    val world: World = World.from(WorldSnapshot(seed, rules))
    private val renderer = Renderer(config, world)
    private val judge = Judge(config)

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

    /** 整场跑掉的模型调用。固化命中不会让它增加——这是"固化生效"唯一的客观证据。 */
    val requestCount: Int get() = renderer.requestCount + judge.requestCount

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
                val stale = judgment.consequences.map { it.targetId }.toSet() + judgment.affected
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
        is Consequence.Move -> "${name(c.id)} 挪到了 (${c.x}, ${c.y}) 层 ${c.z}"
        is Consequence.Reparent ->
            if (c.parent == null) "${name(c.id)} 现在不挂在任何东西下面了"
            else "${name(c.id)} 现在归到「${name(c.parent)}」下面"
        is Consequence.SetAttribute -> "${name(c.id)} 的「${c.key}」变成「${c.value}」"
        is Consequence.ChangeAppearance -> "${name(c.id)} 只是外表变了（描述作废）"
        is Consequence.Destroy -> "${name(c.id)} 从世界里消失了"
        is Consequence.Spawn -> "出现了「${c.entity.label}」"
        is Consequence.Replace -> "${name(c.oldId)} 变成了「${c.entity.label}」（另一个东西）"
    }

    /** 给界面显示"这是什么、在哪"。 */
    fun subtitleOf(entity: Entity): String {
        val place = entity.parent?.let { world.byId(it)?.label }
        return buildString {
            append(entity.kind)
            if (place != null) append("｜在「$place」")
            append("｜(${entity.x}, ${entity.y}) 层 ${entity.z}")
        }
    }
}
