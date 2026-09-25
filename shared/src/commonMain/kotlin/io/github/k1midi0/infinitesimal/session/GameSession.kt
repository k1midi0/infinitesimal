package io.github.k1midi0.infinitesimal.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.director.Awakening
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.director.Wake
import io.github.k1midi0.infinitesimal.director.Waking
import io.github.k1midi0.infinitesimal.judge.Consequence
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.judge.TargetAlignment
import io.github.k1midi0.infinitesimal.judge.describeConsequence
import io.github.k1midi0.infinitesimal.npc.Heard
import io.github.k1midi0.infinitesimal.npc.Npc
import io.github.k1midi0.infinitesimal.npc.Reaction
import io.github.k1midi0.infinitesimal.npc.Spoken
import io.github.k1midi0.infinitesimal.npc.Waking as NpcWaking
import io.github.k1midi0.infinitesimal.render.DetailInserters
import io.github.k1midi0.infinitesimal.render.Renderer
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.audience
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.spatial.requiredChunks
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.EventKind
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.Spot
import io.github.k1midi0.infinitesimal.world.TerrainSnapshot
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import io.github.k1midi0.infinitesimal.world.ZoningSnapshot
import io.github.k1midi0.infinitesimal.world.WorldTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** 玩家自己的 id。开局世界和判定都用它。 */
const val PLAYER = "player"

/** 日志里的一行。[Kind] 只用来决定颜色，不参与任何逻辑。 */
data class LogLine(val kind: Kind, val text: String) {
    enum class Kind { SAY, ACT, OK, REJECT, PROBLEM, NOTE, ERROR }
}

/**
 * 任务清单上的一条（§8.3.2、§14.33）。
 *
 * 它是一份**并行**清单上的一行，而不是"当前状态"那一个字符串：判定在跑、地形在后台长、导演在看着
 * ——这三件事可以同时成立，而一个字符串只能显示其中一个。用户对这一条的要求是原话级别的：
 * 「工作进程是一份并行任务清单（不是一个字符串）」。
 *
 * [text] 是可变的，因为**一件事会往前走**：判定那一行先是「判定中…」，接着变成「算后果…」。让调用点
 * 各自起一条新任务会把这件事显示成两件事同时在跑，那是假的信息。
 */
class Task internal constructor(val id: Long, text: String) {
    var text by mutableStateOf(text)
        internal set

    override fun equals(other: Any?): Boolean = other is Task && other.id == id

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = text
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
    /**
     * 同一个回合里说的那句话（§5）。
     *
     * **它跟着这一小段中间状态一起等。** 说话的听者是由判定裁的（§14.36），而判定要在动作落库之后
     * 才跑——所以玩家点「应用」之前，那句话还没有交给任何人。丢掉这一行，"等一下再应用"就会让那
     * 句话凭空消失，而它明明已经写在日志里了。
     */
    val said: String,
    /**
     * **这句话是说给谁听的**（§14.37）。第一步给的，与 [targets] 分开。
     *
     * 它与 [said] 一起等：玩家点「应用」之后才轮到这些人被唤醒。**它不是"谁听得见"**——旁边的人照样
     * 收得到这件事（记录层的投递，代码做的），只是不会因此醒过来。
     */
    val addressed: List<String>,
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
    /**
     * 判定（§4）。默认按 [config] 与 [presets] 现造一个；**测试可以塞一个假的进来**——它在这条路上
     * 是第一个真的去碰端点的人，不注入就只能拿真 key 跑。
     */
    private val judge: Judge = Judge(config, presets = presets),
    /**
     * 导演（§8）。默认按 [config] 与 [presets] 现造一个；**测试可以塞一个假的进来**——它在会话里
     * 有三条触发路径（手动按钮、硬圈缺口、每回合那一看），没有注入点就只能靠真端点验。
     */
    private val director: Director = Director(config, presets),
    /**
     * 扮演 AI 角色（§3.2、§5）。默认按 [config] 与 [presets] 现造一个；**测试可以塞一个假的进来**
     * ——它是这一层第三个会碰端点的人，不注入就只能拿真 key 跑（§14.35 踩过一次）。
     */
    private val npc: Npc = Npc(config, presets),
) {
    val world: World = World.from(
        WorldSnapshot(seed, rules, terrain = ground, zoning = zoning),
    )
    private val renderer = Renderer(config, world)

    /**
     * 导演（§8）。**它自己把请求落到 [SystemId.DIRECTOR] 那个缓存池**，所以传进去的还是原配置。
     *
     * 它与判定共用同一个客户端配置，但用户 id 不同——那是"同一个模型、两套提示词、两个缓存池"，
     * 不是两个模型（§10.1）。预设库**必须递给它**：导演那只 `grow_terrain` 会走到生成器，而生成器
     * 放东西时看的是同一份库（A.14）。
     */

    /**
     * **写世界的那把锁。**
     *
     * 世界是一张普通的 Map——它没有内部同步，两个写入者交错跑就会丢改动。而这里现在真的有两个：
     * 玩家那一轮（判定第二步）与后台那一看（导演可能一路走到生成地形，一次几十秒）。
     *
     * 锁只圈住**会写世界的那一次调用**，不圈判定第一步、不圈渲染——那些只读。圈得再宽一点（把整个
     * 回合包进去）会让后台生成把玩家的下一次判定一起卡住。
     *
     * **这一条是 §14.33 那套"不重合才并行"的保守版**：那里定的是两块地不重合就允许并行，而并行要求
     * 生成先把后果算在一个快照上、之后再提交。现在生成是就地提交的，所以先用一把锁把它串起来——
     * 串起来永远是对的，只是慢；等 §12 第 40 项那个账单量出来，再决定值不值得拆。
     */
    private val writes = Mutex()

    /**
     * 这一回合的头一条事件编号（§6.1）。由 [runTurn] 一次提交开始时钉下，随后交给每一个被唤醒的角色当
     * 事件总线的起点。
     *
     * 见 [runTurn] 里那段注释：它是字段而不是参数，是因为**纠正预览会把一回合从中间切开**。
     */
    private var turnStartSeq = 0L

    /**
     * 这一回合的起点事件编号，**只读**（§6.1）。
     *
     * 探针与将来的界面要看得见"上回合从哪儿算"——它是事件总线的左边界，而总线上那些 `eN` 编号正是
     * 角色提示词里印出来的东西。把它暴露出来是为了让那种读数不必去猜。
     */
    val turnStart: Long get() = turnStartSeq

    /**
     * **区块坐标的显示原点**，整局固定（[ChunkOrigin]）。
     *
     * 它在会话里算一次、之后只读：玩家会走，而数轴上的数字一旦跟着走，之前给导演看过的坐标就全部
     * 换了意思。开局玩家那一块减一，于是玩家开局显示在 (1,1)——这一条是用户定的（§14.33）。
     */
    private val origin: ChunkOrigin =
        ChunkOrigin.atPlayerStart(world.chunkOfEntity(PLAYER) ?: ChunkKey(0, 0))

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
     * **现在真正在跑的每一件事，一件事一行**（§8.3.2、§14.33）。
     *
     * 它和 [status] 是两样东西：`status` 说的是玩家这一次动作走到哪一步了，`tasks` 说的是**这个世界
     * 上此刻有几件事在跑**——判定在跑、地形在后台长、导演在看着，这三件可以同时成立。用户对这一条的
     * 要求是原话级别的：「工作进程是一份并行任务清单（不是一个字符串）」。
     */
    var tasks by mutableStateOf<List<Task>>(emptyList())
        private set

    /**
     * 一件**必须让玩家看见、但没法只写进日志**的事。**只有"硬圈里那块地长不出来"会置它。**
     *
     * 别的失败都只写日志：那是玩家自己那一轮没成，他本来就盯着日志。而这一条不一样——它是**世界少了
     * 一块地**，而玩家可能正站在那个缺口边上，看到的是"这里什么都没有"。界面把日志盖掉之后，这条是
     * 唯一还看得见的东西。
     */
    var notice by mutableStateOf<String?>(null)
        private set

    fun dismissNotice() {
        notice = null
    }

    /**
     * 第一步对齐完了、在等玩家确认的那一次（§4.7）。**不是 null 就是有东西在等。**
     *
     * 它存在的理由是：世界**一个字都还没动**。判定第一步只会说"这次动作指向哪些东西"，改世界是
     * 第二步的事——所以停在这里是免费的，没有要回滚的东西。
     */
    var pending by mutableStateOf<PendingAlignment?>(null)
        private set

    /**
     * **这一回合里谁说了什么**（§5.3）。
     *
     * §5.3 的原话是「气泡**仅存在当前回合**」——所以它不是一个累积的东西，每一轮开头清空，界面照着
     * 它在对应的角色头上画气泡。玩家自己那句也在里面（[PLAYER]），于是"谁在跟谁说话"在一张图上是
     * 看得出来的。
     *
     * **它不进 [World]。** 世界是事实，而这一条按定义就是要消失的；放进世界就等于给了它一个任期，
     * 而"说过的话什么时候过期"马上会变成第二个需要裁的问题。§1.5 的记录永不改写说的是另一件事
     * ——那是**记录**，这一条连记录都还不是。
     */
    var speech by mutableStateOf<List<Spoken>>(emptyList())
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
    val requestCount: Int
        get() = renderer.requestCount + judge.requestCount + director.requestCount +
            npc.requestCount + npc.judgeRequestCount

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
     * 提交一轮：说一句话、做一个动作，两个都可以有，也都可以空着。
     *
     * ## 说话的听者由判定裁，不由代码按半径算（§14.36）
     *
     * 用户对这一条的裁定是原话级别的：**「这种程度的动作应该被判定系统修正为指向声音范围内的目标」**。
     * 于是"谁听得见"这个问题落在判定第一步身上——它手里已经有空间框，报出在场的人是它本来就在做的
     * 事（`targetingPrompt` 里那条「我喊了一嗓子——旁边那个人也算」）。代码这边**不划任何半径**：
     * 多一条几何规则藏在代码里，就多一处与世界不一致而没人看得见的地方（第 7 项也因此还没定）。
     *
     * ## 为什么说话不走纠正预览（§4.7）
     *
     * 预览拦的是"**判定会改错世界**"。而说一句话**改不了任何东西**——它是这个回合里唯一一件不需要
     * 玩家过目的动作。所以对齐结果与玩家勾的不一样时，这里不挂 pending，直接把判定报出来的那些人
     * 当作听者。这是"预览存在理由"的自然推论，不是一处例外。
     */
    suspend fun runTurn(said: String, acted: String, scope: CoroutineScope? = null) {
        val spoken = said.trim()
        val done = acted.trim()

        // **这一回合的起点**（§6.1、§14.39）：事件总线只装上回合，而"上回合从哪儿开始"需要一个不随回合
        // 漂移的刻度。
        //
        // **`+ 1` 不是可有可无的。** 它是这一回合**第一条**事件的编号，而 `latestSeq` 是**上一回合最后一
        // 条**的编号——少了这个加号，上一回合的收尾（往往正是角色自己刚说的那句）会再出现一次在本回合的
        // 总线上。探针第一次跑就把这个差一打出来了：第 2 轮的总线里赫然躺着第 1 轮矮人那句话。
        //
        // 它读在**任何事件落库之前**，所以这一回合产生的事件编号都 ≥ 它。
        //
        // 它是字段而不是参数，理由与别处的状态不同：**纠正预览会把一回合从中间切开**（§4.7）——玩家点
        // 「应用」时，`runEffects` 与 `afterTurn` 是另一次调用进去的，而那一回合的起点必须是同一个数。
        // 参数传不过那条断口。
        turnStartSeq = world.log.latestSeq + 1

        // §5.3：气泡只存在当前回合。**在动作之前清**，于是这一轮说的话不会混着上一轮的。
        speech = emptyList()

        if (spoken.isNotEmpty()) {
            say(LogLine.Kind.SAY, "你说：「$spoken」")
            speech = listOf(Spoken(PLAYER, spoken))
        }

        if (done.isNotEmpty()) {
            judgeAndCommit(done, spoken, scope)
        } else if (spoken.isNotEmpty()) {
            // 只说话、不做事。**这一轮照样要过第一步**——要判的是「这句话指向谁」，那是语义判断，代码
            // 做不了（§14.37）。而它的产物只有一栏：听者。说话不产生后果，所以没有第二步。
            val addressed = addressedBy(spoken)
            recordSpeech(spoken, addressed)
            afterTurn("", spoken, addressed, scope)
        }
    }

    fun submit(scope: CoroutineScope, said: String, acted: String) {
        if (said.isBlank() && acted.isBlank()) return
        run(scope, "跑一轮…") { runTurn(said, acted, scope) }
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
            runEffects(p.turn, p.targets, p.intention, p.said, p.addressed, scope)
        }
    }

    /** 挂起版：给 spike 直接 await 用（否则测试只能 sleep 猜）。 */
    suspend fun applyPendingNow(scope: CoroutineScope? = null) {
        val p = pending ?: return
        pending = null
        runEffects(p.turn, p.targets, p.intention, p.said, p.addressed, scope)
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
        // 到期的那些**已经从时间轴上取走**（时间轴是计划，不是记录），所以这里就是它们唯一的落点。
        //
        // **记录层现在有了**（§14.37），于是 §12 第 20 项那个缺口在这里合上：它们进事件流，append-only，
        // 过了这一局也还在。投递范围与别处同一把尺子——以玩家为中心的那一片。
        //
        // 一条局限写在明处：**这是一次世界级的事，而它的受众只按玩家身边那一片算**。远处一个同样该
        // 知道"酒馆打烊了"的角色收不到——等角色多起来、时间轴上有跨地点的事时，这里要重新想。
        stop.happened.forEach { item ->
            world.log.append(
                at = stop.at,
                kind = EventKind.CLOCK,
                actorId = null,
                actorLabel = "",
                text = item.what,
                to = world.audience(PLAYER),
            )
        }
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
        val result = writes.withLock { director.wake(world, Waking(Wake.Arrange, PLAYER, origin)) }
        timelineRevision++
        when (result) {
            is Awakening.Done -> {
                say(LogLine.Kind.NOTE, "（导演排下了 ${result.scheduled.size} 件事）")
                result.scheduled.sortedBy { it.dueAt.minutes }.forEach {
                    say(LogLine.Kind.NOTE, "· ${it.dueAt}　${it.what}")
                }
            }

            is Awakening.FailedProtocol -> {
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
        val mine = startTask(what)
        foreground = mine
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                say(LogLine.Kind.ERROR, "出错了：${t.message}")
            } finally {
                if (foreground === mine) foreground = null
                endTask(mine)
                entities = world.all()
                clock = world.clock
                timelineRevision++
                busy = false
                status = ""
            }
        }
    }

    // ── 任务清单（§8.3.2） ──────────────────────────────────────────────────────────

    private var nextTask = 0L

    /**
     * 玩家这一次动作那一行。**它是 [tasks] 里的一行，只是会话记着它**，好让中途换一句说明。
     *
     * spike 直接 `await` 那些挂起版时它一定是 null——那正是"只改 [status]"那条退路存在的原因。
     */
    private var foreground: Task? = null

    private fun startTask(text: String): Task {
        val task = Task(nextTask++, text)
        tasks = tasks + task
        return task
    }

    /**
     * 收掉一行。**调用方必须在 `finally` 里收**——漏一次，界面上就永久留着一件"正在跑"的事，
     * 而它其实早就没了。
     */
    private fun endTask(task: Task) {
        tasks = tasks - task
    }

    /** 把当前这一步往前推一句。没有前台任务时（spike 直接 await）只改 [status]。 */
    private fun phase(text: String) {
        status = text
        foreground?.text = text
    }

    // ── 一轮的内部 ──────────────────────────────────────────────────────────────────

    private suspend fun judgeAndCommit(intention: String, said: String, scope: CoroutineScope?) {
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

        phase("判定中…")
        val turn = judge.beginTurn(
            world,
            JudgeRequest(
                actorId = PLAYER,
                actorLabel = "你",
                intention = intention,
                selected = picked,
                centers = picked,
                // **把话一起递进去，于是说话与动作共用这一次第一步**（§14.37）。早先它不在这里，
                // 说话要单独再跑一次对齐去找听者——"说+做"的一轮因此花两次调用，而两次之间还多出
                // 一处会漂开的地方。
                said = said,
            ),
        )
        val alignment = try {
            // **只跑第一步。** 它不改世界，所以走到哪里停下都不需要回滚（§4.7）。
            turn.align()
        } catch (t: Throwable) {
            say(LogLine.Kind.ERROR, "判定没跑起来：${t.message}")
            return
        }

        // **说话那件事与动作走哪条路无关，所以记录写在分岔之前。**
        //
        // 这是被测试逼出来的一条：第一版把 `recordSpeech` 写在 `Aligned` 那一支里，于是"说了句话 +
        // 做了个指向不存在的东西的动作"这一轮里，**那句话连同它的记录一起消失了**——而它明明已经说
        // 出口了。动作对不对齐得上，改变不了"他说过这句话"。
        //
        // 而对齐没成功时 `address` 那一栏没有答案，所以**没有人被指向**：这一片里的人照样收得到
        // （投递），只是没人因此醒过来（唤醒）。
        val addressed = (alignment as? TargetAlignment.Aligned)
            ?.let { charactersAmong(it.addressed) }
            ?: emptyList()
        recordSpeech(said, addressed)

        when (alignment) {
            is TargetAlignment.Aligned -> {
                if (alignment.targets.toSet() != picked.toSet()) {
                    pending = PendingAlignment(intention, said, addressed, picked, alignment.targets, turn)
                    say(
                        LogLine.Kind.NOTE,
                        "对齐到：${alignment.targets.joinToString("、") { name(it) }}" +
                            "　← 和你勾的不一样（世界还没动）",
                    )
                    status = ""
                    return
                } else {
                    runEffects(turn, alignment.targets, intention, said, addressed, scope)
                }
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
    private suspend fun runEffects(
        turn: Judge.Turn,
        targets: List<String>,
        intention: String,
        said: String,
        addressed: List<String>,
        scope: CoroutineScope?,
    ) {
        // 同一个理由：标签要在落库之前抓。
        val labels = world.all().associate { it.id to it.label }
        fun name(id: String) = labels[id] ?: world.byId(id)?.label ?: id

        phase("算后果…")
        val mark = TimeSource.Monotonic.markNow()
        val judgment = try {
            // **锁圈住的是这一步。** 它是唯一改世界的地方，而这一刻可能有另一件事在写（后台那一看
            // 走到的生成地形）。圈在 [finish] 外面而不是里面，是因为提交就发生在它内部。
            writes.withLock { turn.finish(targets) }
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
                val stale = staleOf(judgment)
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
        // **记录层**（§6.1）：这一次动作真的落地了才写。
        //
        // 驳回的、协议失败的、没落在任何东西上的**都不记**——它们不是世界里发生过的事，而是一次没有
        // 落地的尝试。记进去只会让别人的窗口里充满「某人试过、没成」，那不是记录，是噪声。
        //
        // 后果那几条**和这条记录绑在同一个字段上**（`Event.effects`）：§6.1 那条「一次提交产生的全部
        // 后果不可切开」就落在那里——裁窗口时不会出现"看到杯子掉在地上却没看到桌子被掀"。
        when (judgment) {
            is Judgment.Applied -> recordAction(
                intention,
                judgment.consequences.map { describe(it, ::name) },
                judgment.affected,
                addressed,
            )

            is Judgment.Unchanged -> recordAction(intention, emptyList(), emptyList(), addressed)
            else -> Unit
        }

        selection = emptySet()
        afterTurn(intention, said, addressed, scope)
    }

    /**
     * 一次提交之后，哪些东西的描述作废了（§7.1）。
     *
     * **只看 [Consequence.EntityConsequence]。** 地形那几条后果没有 `targetId`，也不该让任何东西的
     * 描述作废：描述只讲物体自身（§7.6），脚下的土被挖掉不改变杯子长什么样——见
     * `Consequence.TerrainConsequence` 的注释。
     *
     * 抽成一个函数，是因为**现在有两条会改世界的路**：玩家那一轮与 AI 角色那一轮。两处各算一遍
     * 之后，迟早会有一处先漂开，而症状是"某个东西的描述该作废却没作废"——那种错没有任何东西会报。
     */
    private fun staleOf(judgment: Judgment.Applied): Set<String> =
        judgment.consequences
            .filterIsInstance<Consequence.EntityConsequence>()
            .map { it.targetId }
            .toSet() + judgment.affected

    // ── 回合之后那两条触发路径（§14.33） ────────────────────────────────────────────

    /**
     * 一轮落库之后要走的两件事。**它们的性质正好相反，所以顺序不能换。**
     *
     * 1. **硬圈缺口**：由**代码**发现，同步叫思考档，**玩家等**。玩家可能正站在那个缺口边上，那是
     *    一个"世界少了一块"的错，不是一次可以稍后再说的调度。
     * 2. **缓冲圈**：由**快速档**每回合看一眼，后台跑，**不挡玩家**。它觉得这一带还不用长，那是
     *    合法的结果——用户对这一条的原话是「什么都不做是合法结果」。
     */
    private suspend fun afterTurn(
        intention: String,
        said: String,
        addressed: List<String>,
        scope: CoroutineScope?,
    ) {
        // **顺序是有理由的，不能换。**
        //
        // 说与做都落完之后，先让**被指向的人反应**（§5）——它们是这一轮的直接后果，而下一件事（补地形）
        // 是世界的账，跟这一轮无关。反过来写的话，玩家会先看到"这一带还缺几块地"，再看到矮人答话，
        // 而事实发生的顺序正好相反。
        val replies = hearAndReact(said, addressed, scope)

        closeGap()
        // scope 为 null = 调用方是 spike 的挂起入口，它自己拿着协程；那一看就不起（否则一次
        // runBlocking 会被一条后台协程拖着不结束）。
        if (scope != null) scope.launch { watch(narrative(intention, said, replies)) }
    }

    /**
     * 把这一轮发生的事说成一句给导演听的话。
     *
     * **导演快速档看的就是这一句**（§8.5）——它不看日志、不看世界，只看"这一回合出了什么事"，
     * 然后判断要不要把思考档叫起来。所以 NPC 的答话必须写进去：一场变味的对话正是导演该注意到的东西。
     */
    private fun narrative(intention: String, said: String, replies: List<Spoken>): String = buildList {
        if (said.isNotEmpty()) add("玩家说：「$said」")
        replies.forEach { add("${labelOf(it.whoId)}说：「${it.text}」") }
        if (intention.isNotEmpty()) add("玩家做：$intention")
    }.joinToString("；")

    // ── 说话与听见（§5） ────────────────────────────────────────────────────────────

    /**
     * 「这句话说给谁听」——**由判定的第一步裁**（§14.37）。
     *
     * 它走的是与「做」完全相同的第一步：同一个 `beginTurn`、同一份空间框、同一段提示词。唯一的区别
     * 是**跑完第一步就丢掉那个 `Turn`**：说话没有第二步，它不产生后果。
     *
     * **它只在"只说话、不做事"时才被调到。** 说了又做的那种一轮里，第一步是 `judgeAndCommit` 跑的，
     * 而这一栏已经从同一份 `Aligned` 里拿到了——"说话与动作共用一次第一步"正是 §14.37 要买的东西：
     * 早先说话要单独再跑一次对齐去找听者（一次真金白银的调用）。
     *
     * 驳回在这里不是失败。判定说"这一片里没有你点的那个东西"时，那句话**照样说出去了**——只是没人
     * 被指向。这个区别在界面上是看得出来的：日志里有「你说：…」，后面跟着「（没有人应声）」。
     */
    private suspend fun addressedBy(spoken: String): List<String> {
        val picked = selection.toList()
        phase("这句话是说给谁听的…")

        val turn = judge.beginTurn(
            world,
            JudgeRequest(
                actorId = PLAYER,
                actorLabel = "你",
                // **写成一次动作，而不是一串引文。** 判定的第一步读的是"这次动作指向谁"，而它认得的
                // 动作形状就是这种第一人称的句子。
                intention = "我开口说：「$spoken」",
                selected = picked,
                centers = picked,
                said = spoken,
            ),
        )

        val alignment = try {
            turn.align()
        } catch (t: Throwable) {
            say(LogLine.Kind.ERROR, "判定没跑起来：${t.message}")
            return emptyList()
        }

        return when (alignment) {
            is TargetAlignment.Aligned -> charactersAmong(alignment.addressed)

            is TargetAlignment.Rejected -> {
                say(LogLine.Kind.REJECT, "「${alignment.reason}」")
                emptyList()
            }

            is TargetAlignment.Malformed -> {
                say(LogLine.Kind.PROBLEM, "协议内失败——按设计不给玩家看，这里显示是为了调试：")
                alignment.problems.forEach { say(LogLine.Kind.NOTE, "   · $it") }
                emptyList()
            }
        }
    }

    /** 从一堆 id 里挑出**真在世界上、且真是角色的**那些——模型偶尔会报一个不存在的东西。 */
    private fun charactersAmong(ids: List<String>): List<String> =
        ids.filter { it != PLAYER && isCharacter(it) }.distinct()

    // ── 记录层（§6.1） ──────────────────────────────────────────────────────────────

    /**
     * 把这一轮**说的话**写进记录层，并投递给这一片里的人（§14.37）。
     *
     * **投递不等于唤醒。** 隔壁桌收到了"他刚才说过那句话"，那进了它的记忆；而醒过来回应，只有被指向
     * 的人才会。§3.2 那句「**投递总是发生，唤醒才是例外**」在代码里就是这一行与 [hearAndReact] 的
     * 差别——而氛围 NPC 能有成百个而成本零增量，全部来源也是它。
     *
     * 它记的是**原话**（用户的裁定：「结构化一行，原话原样，代码拼」）。不加工是有理由的：一份由模型
     * 转述过的历史，与"它当时到底听到了哪一句"是两个问题，而后者才是记录该回答的那个。
     */
    private fun recordSpeech(spoken: String, addressed: List<String>) {
        if (spoken.isEmpty()) return
        world.log.append(
            at = world.clock,
            kind = EventKind.SAY,
            actorId = PLAYER,
            actorLabel = labelOf(PLAYER),
            text = spoken,
            // **被指向的给全文，其余给标题**（§6.1）：这一栏就是那个判断的输入。
            addressedTo = addressed,
            spot = world.byId(PLAYER)?.let { Spot(it.x, it.y, it.z) },
            to = world.audience(PLAYER, addressed),
        )
    }

    /**
     * 把这一次**动作**写进记录层。**只有真的落地了的那种才写**——调用点负责判。
     *
     * [effects] 就是日志里那几条「· …」，**同一份文字**、不另写一遍：两处各写一遍之后迟早会有一处
     * 先漂开，而症状是"记录里说的和玩家看到的不一样"，那种错没有任何东西会报。
     */
    private fun recordAction(
        intention: String,
        effects: List<String>,
        affected: Collection<String>,
        addressed: List<String>,
    ) {
        if (intention.isEmpty()) return
        world.log.append(
            at = world.clock,
            kind = EventKind.ACT,
            actorId = PLAYER,
            actorLabel = labelOf(PLAYER),
            text = intention,
            effects = effects,
            // 一次动作「指向谁」= **它作用到谁、波及到谁**。被波及的角色拿全文，其余只拿标题。
            addressedTo = (addressed + affected).distinct(),
            spot = world.byId(PLAYER)?.let { Spot(it.x, it.y, it.z) },
            to = world.audience(PLAYER, addressed, affected),
        )
    }

    /**
     * **谁是"一个角色"。** 判据是**有没有人设卡**，不是 `kind == "角色"`。
     *
     * `Entity.cards` 上那段注释写着这条分工：角色就是物体的一种，而"多一个 `kind` 判断只会多一处会
     * 写错的地方"。物品背景、规则书优先级那边用的也是同一个判据（§12 第 12 项）。
     */
    private fun isCharacter(id: String): Boolean = world.byId(id)?.cards?.isNotEmpty() == true

    /**
     * 听见的人**逐个**反应。**玩家等**（§14.36）。
     *
     * ## 为什么等
     *
     * 不等的话，玩家会在矮人还没答话时又说一句——而那一句的前提还没发生。这与 §4.6 那条「一次动作
     * 一定要有指向的对象」是同一个道理：**前提没落地就接受下一步，世界会开始自相矛盾。** 界面上已经
     * 有那份并行任务清单（§14.35），"矮人在回应…"天然就是清单上的一行，等待因此不需要新造一个转圈。
     *
     * ## 为什么必须串行
     *
     * 每个角色那一次唤醒**会一路走到判定并提交**（它的「做」走的是同一条链路）。两个角色同时提交就
     * 是两处同时改世界——现在只有一把 [writes] 锁，串行是它的直接结果。将来要并行，得先把"在快照上
     * 算后果、之后再提交"做出来（§12 第 42 项）。
     *
     * ## 深度钉在 1
     *
     * **NPC 说的话不再唤醒别人。** 它会成环——两个矮人互相接话可以一直接下去，而每一轮都是一次真金
     * 白银的调用，且**没有一条自然的终止条件**：人聊天本来就可以一直聊。
     *
     * 早先这里的理由写的是「历史还不累积，一段只装着一句话的上下文接不出有意义的三轮对话」（§12 第
     * 38 项），并注明"等 §6 的滚动窗口做出来，这一条该和它一起重新想"。**窗口做出来了（§14.37），
     * 而结论没变**：现在挡着它的是成环与成本，不再是上下文太薄。要放开，得先有一条轮次上限，而那件事
     * 没人真正需要——一个不接别人话的矮人看起来一点也不坏。
     */
    private suspend fun hearAndReact(
        said: String,
        addressed: List<String>,
        scope: CoroutineScope?,
    ): List<Spoken> {
        if (said.isEmpty()) return emptyList()

        if (addressed.isEmpty()) {
            say(LogLine.Kind.NOTE, "（没有人应声——这一片里没有别人）")
            return emptyList()
        }

        val replies = mutableListOf<Spoken>()
        for (id in addressed) {
            val task = startTask("${labelOf(id)}在回应…")
            val reaction = try {
                writes.withLock {
                    npc.wake(
                        world,
                        NpcWaking(
                            id,
                            Heard.Spoken(PLAYER, "你", said),
                            origin,
                            // **总线只装上回合**：把这一回合的起点交给它，它才筛得出"刚刚发生了什么"。
                            sinceSeq = turnStartSeq,
                        ),
                    )
                }
            } catch (t: Throwable) {
                say(LogLine.Kind.ERROR, "${labelOf(id)}没醒过来：${t.message}")
                null
            } finally {
                endTask(task)
            }

            if (reaction == null) continue
            replies += announce(id, reaction)
        }

        // 它们可能改了世界（自己的「做」走过判定并提交）。
        entities = world.all()
        return replies
    }

    /** 把一个角色的反应写进日志与气泡，并把结果还回去。 */
    private fun announce(id: String, reaction: Reaction): List<Spoken> {
        val name = labelOf(id)

        reaction.problems.forEach { say(LogLine.Kind.PROBLEM, "$name 那边有点不对劲：$it") }

        reaction.said.forEach { say(LogLine.Kind.SAY, "$name 说：「${it.text}」") }
        speech = speech + reaction.said

        reaction.acted.forEach { judgment ->
            val labels = world.all().associate { it.id to it.label }
            fun nameOf(target: String) = labels[target] ?: world.byId(target)?.label ?: target

            when (judgment) {
                is Judgment.Applied -> {
                    say(LogLine.Kind.OK, "$name 做了一件事——${judgment.consequences.size} 条后果")
                    judgment.consequences.forEach { say(LogLine.Kind.NOTE, "   · ${describe(it, ::nameOf)}") }
                    observations = observations - staleOf(judgment)
                }

                // **驳回原样显示。** 它不是 NPC 的失败，是这个世界说"不成"——而这句话对玩家同样
                // 有信息量（他刚看见有人想做一件事，而世界没让它发生）。
                is Judgment.RejectedWorld -> say(LogLine.Kind.REJECT, "$name：「${judgment.reason}」")
                is Judgment.Unchanged -> say(LogLine.Kind.OK, "$name 做了一件事——没有改变世界")
                is Judgment.Nothing -> say(LogLine.Kind.NOTE, "（$name 那个动作没有落在任何东西上）")
                is Judgment.FailedProtocol ->
                    say(LogLine.Kind.PROBLEM, "$name 的动作判定读不懂（试了 ${judgment.attempts} 次）")
            }
        }

        if (reaction.silent) say(LogLine.Kind.NOTE, "（$name 没什么反应）")
        return reaction.said
    }

    /**
     * 硬圈里缺的块，**由代码算，不由模型说**。
     *
     * 判据是 [requiredChunks]：以玩家所在区块为心、半径 [io.github.k1midi0.infinitesimal.world.HARD_RADIUS_CHUNKS]
     * 的圆盘里，[io.github.k1midi0.infinitesimal.world.Terrain.isGenerated] 还是 false 的那些。玩家每
     * 跨过一块，这个圆盘就露出一道月牙——而"跨过一块"是 8 米，走路一回合就到。
     */
    private suspend fun closeGap() {
        val player = world.chunkOfEntity(PLAYER) ?: return
        val required = requiredChunks(world, player)
        if (required.isEmpty()) return

        val task = startTask("正在生成地形（${required.size} 块）…")
        val result = try {
            writes.withLock {
                director.wake(world, Waking(Wake.HardGap(required), PLAYER, origin))
            }
        } catch (t: Throwable) {
            say(LogLine.Kind.ERROR, "补地形没跑起来：${t.message}")
            null
        } finally {
            endTask(task)
        }

        when (result) {
            null -> notice = "你走到了一块还没有地形的地方，而它没能长出来。"
            is Awakening.Done -> say(
                LogLine.Kind.NOTE,
                "（这一带还缺 ${required.size} 块地，长出来了 ${result.chunks.size} 块）",
            )

            is Awakening.FailedProtocol -> {
                // 这一条**必须弹到界面上**：它不是一个"这一次没成"的动作，是世界少了一块地，而玩家
                // 正站在那儿看着空无一物。日志会被后面几十行盖掉。
                say(
                    LogLine.Kind.ERROR,
                    "硬圈里那几块地没能长出来（试了 ${result.attempts} 次）：${result.problems.joinToString("；")}",
                )
                notice = "你走到了一块还没有地形的地方，它试了 ${result.attempts} 次都没长出来。"
            }
        }
    }

    /**
     * 快速档每回合的那一看。**它在后台，不挡玩家**——`busy` 一动不动，界面照常能用。
     *
     * 它拿到的是这一回合发生了什么（[turn]）以及"离内圈最近的空地还有几块远"（导演自己算）。想动手
     * 就把思考档叫起来，而思考档可能一路走到生成地形——那一次几十秒，所以它更不能挡玩家。
     *
     * 失败只写日志：**快速档看不成不是世界出了事**，它本来就是一个"看着"的角色。
     */
    private suspend fun watch(turn: String) {
        val task = startTask("导演在看着…")
        try {
            writes.withLock {
                director.wake(world, Waking(Wake.Watching(turn), PLAYER, origin))
            }
        } catch (t: Throwable) {
            say(LogLine.Kind.ERROR, "导演没醒过来：${t.message}")
        } finally {
            endTask(task)
            // 它可能改了地形、也可能排下了事——两条都要推给界面。
            entities = world.all()
            timelineRevision++
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────────────

    private fun labelOf(id: String): String = world.byId(id)?.label ?: id

    private fun say(kind: LogLine.Kind, text: String) {
        log = log + LogLine(kind, text)
    }

    fun clearLog() {
        log = emptyList()
    }

    /**
     * 一条后果给人看的一句话。**实现搬到了 `judge/ConsequenceView.kt`**——它现在有三个消费者
     * （玩家日志、记录层、AI 角色自己的动作），三处各写一遍迟早会漂开。
     */
    private fun describe(c: Consequence, name: (String) -> String): String =
        describeConsequence(c, name, world.materials)

    /** 给界面显示"这是什么、在哪"。**位置只来自坐标**——关系不存成事实（附录 A.5）。 */
    fun subtitleOf(entity: Entity): String =
        "${entity.kind}｜(${entity.x}, ${entity.y}, ${entity.z}) 厘米"
}
