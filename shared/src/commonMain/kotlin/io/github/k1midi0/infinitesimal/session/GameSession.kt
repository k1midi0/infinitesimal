package io.github.k1midi0.infinitesimal.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.k1midi0.infinitesimal.config.EndpointConfig
import io.github.k1midi0.infinitesimal.config.ScheduleSettings
import io.github.k1midi0.infinitesimal.director.Awakening
import io.github.k1midi0.infinitesimal.director.Director
import io.github.k1midi0.infinitesimal.director.DirectorTools
import io.github.k1midi0.infinitesimal.director.titlesWithSize
import io.github.k1midi0.infinitesimal.director.Wake
import io.github.k1midi0.infinitesimal.director.Waking
import io.github.k1midi0.infinitesimal.judge.Consequence
import io.github.k1midi0.infinitesimal.judge.Judge
import io.github.k1midi0.infinitesimal.judge.JudgeRequest
import io.github.k1midi0.infinitesimal.judge.Judgment
import io.github.k1midi0.infinitesimal.judge.TargetAlignment
import io.github.k1midi0.infinitesimal.judge.describeConsequence
import io.github.k1midi0.infinitesimal.npc.Conversations
import io.github.k1midi0.infinitesimal.npc.Heard
import io.github.k1midi0.infinitesimal.npc.Npc
import io.github.k1midi0.infinitesimal.npc.Reaction
import io.github.k1midi0.infinitesimal.npc.Spoken
import io.github.k1midi0.infinitesimal.npc.Waking as NpcWaking
import io.github.k1midi0.infinitesimal.render.DetailInserters
import io.github.k1midi0.infinitesimal.render.Renderer
import io.github.k1midi0.infinitesimal.save.LastSave
import io.github.k1midi0.infinitesimal.save.SaveFile
import io.github.k1midi0.infinitesimal.save.SaveGame
import io.github.k1midi0.infinitesimal.schedule.Scheduler
import io.github.k1midi0.infinitesimal.schedule.Scheduling
import io.github.k1midi0.infinitesimal.schedule.SentryJudge
import io.github.k1midi0.infinitesimal.spatial.ChunkOrigin
import io.github.k1midi0.infinitesimal.spatial.audience
import io.github.k1midi0.infinitesimal.spatial.chunkOfEntity
import io.github.k1midi0.infinitesimal.spatial.requiredChunks
import io.github.k1midi0.infinitesimal.world.Active
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.ChunkKey
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.EventKind
import io.github.k1midi0.infinitesimal.world.Placement
import io.github.k1midi0.infinitesimal.world.PresetLibrary
import io.github.k1midi0.infinitesimal.world.Rulebook
import io.github.k1midi0.infinitesimal.world.Spot
import io.github.k1midi0.infinitesimal.world.TerrainSnapshot
import io.github.k1midi0.infinitesimal.world.World
import io.github.k1midi0.infinitesimal.world.WorldSnapshot
import io.github.k1midi0.infinitesimal.world.ZoningSnapshot
import io.github.k1midi0.infinitesimal.world.WorldTime
import io.github.k1midi0.infinitesimal.world.speakersAmong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
     * **开局的发言/行动顺序**（§5.4、§14.51）。
     *
     * 它与 [seed] / [rules] / [ground] / [zoning] 是同一类东西——开局那份**内容**——所以默认值也取
     * 开局那一份。在这一条之前，会话按 seed 造世界时**从来没有把它带进去**，于是真实游戏里那个名单
     * 永远是空的：顺序退化成"按 id 排"，而"玩家在顺序里排第几"根本不存在。
     *
     * 名单里没有的 id 无害（`speakersAmong` 会过掉不在世界上的）；反过来，世界上的角色没排进名单就排
     * 在最后（按 id）——那正是"世界不必先给人排座次才能跑"那条。
     */
    speakQueue: List<String> = StartingWorld.speakQueue(),
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
    /**
     * **到点就位**（§14.45）。默认按 [config] 现造一个（它的请求落在 `SystemId.SENTRY` 那个池）；
     * **测试可以塞一个假的进来**——它是第四个会碰端点的人，而"一轮问几次"正是要与不要量的那一个数。
     */
    /**
     * **到点就位**（§14.45）。默认按 [config] 现造一个（它的请求落在 `SystemId.SENTRY` 那个池）；
     * **测试可以塞一个假的进来**——它是第四个会碰端点的人，而"一轮问几次"正是要与不要量的那一个数。
     *
     * `null` = **还没造**（§14.50）。它默认是 null 而不是 `Scheduler(config)`，理由只有一条：那个默认
     * 值要读 [activeTooLongMinutes]，而它在参数表里排在后面——Kotlin 的默认值从左往右求值，前一个参数
     * 的默认值**引用不到**后一个。于是这里存 null，真要用的时候在 [catchUpCycles] 里现造一次。
     */
    private val scheduler: Scheduling? = null,
    /**
     * **作息那一层的旋钮**（§14.50）。现在只有一格：**脱离自己的日子超过这么多世界分钟，就该让导演重看
     * 它那一格**——那是设计文档 §3.2.2 那条兜底（"目标永远达不到"）。
     *
     * 它只喂给 [scheduler] 那个默认实现（`Scheduler(config, settings.activeTooLongMinutes)`）；测试塞了
     * 自己的 scheduler 时它不起作用（那正是"注入点覆盖默认"的意思）。
     */
    private val scheduleSettings: ScheduleSettings = ScheduleSettings(),
    /**
     * **整个世界**（§14.49）。给读档用：那是一份已经长好、走过一段的世界，而不是 [seed] / [rules] /
     * [ground] / [zoning] 那四样开局内容。
     *
     * 它默认 null，于是老的那条路（"用开局内容现造一个世界"）一个字都不用改。给了它就**以它为准**，
     * 那四个参数一律不看——两条路各造一个世界再挑一个，会让"我看的这一份"与"它实际在写的那一份"
     * 有机会不是同一个。
     *
     * 名字刻意不叫 `world`：那样它会遮住下面那个属性，而这个类里几十处 `world.xxx` 会全部指向那一个
     * 可空参数（编译期就能看出来，但看出来的方式是三十处 `Only safe calls are allowed`）。
     */
    startingWorld: World? = null,
) {
    val world: World = startingWorld ?: World.from(
        WorldSnapshot(seed, rules, terrain = ground, zoning = zoning, speakQueue = speakQueue),
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
     *
     * ## 它从**上一次提交之后**起算（§14.49）
     *
     * 初值不是 0 而是 `world.log.latestSeq`：读档回来的世界里装着过去几百条事件，而 `0` 的意思是
     * "上回合从第 0 条算起"——那会让每一个角色的记忆窗口一次塞进**整局的历史**。
     */
    private var turnStartSeq = world.log.latestSeq

    /**
     * **上一格**的起点事件编号（§14.51）。
     *
     * 用它的地方只有一处：一格开始时给参与者的那条总线（`Heard.Tick`）。**它要比 [turnStartSeq] 早
     * 一格**——因为用户定的规矩是"一回合内说的话，也放进事件流，下一回合周围的角色可以查看"。只装本格
     * 的话，上一格说过的话对这个角色**不存在**：`world.bus` 是按 seq 截的，而上一格的 seq 已经落在截断
     * 之前了。
     */
    private var previousTurnStartSeq = world.log.latestSeq

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
     * 一件**必须让玩家看见、但没法只写进日志**的事。
     *
     * 原本**只有"硬圈里那块地长不出来"会置它**。§14.49 起多两类：**存档写成功**（玩家按了那个按钮，
     * 总得有个回音）与**存档失败**（那句理由必须看得见，否则"我明明存了"会变成一个查不出的问题）。
     *
     * 别的失败都只写日志：那是玩家自己那一轮没成，他本来就盯着日志。而这一条不一样——它是**世界少了
     * 一块地**，而玩家可能正站在那个缺口边上，看到的是"这里什么都没有"。界面把日志盖掉之后，这条是
     * 唯一还看得见的东西。
     */
    var notice by mutableStateOf<String?>(null)
        private set

    /** 从外面报一件事——入口那一层用它播报"存档写好了/没写成"（§14.49）。 */
    fun announce(message: String) {
        notice = message
    }

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
     * 它挂在那一个判定器上，而判定器是每局造一个，两者寿命正好对上；§14.49 之后，读档那一边由
     * [restore] 把这一份库**装回**新的判定器，于是"刚才造过的那张桌子"跨过存档仍然只有一份。
     */
    val presets: PresetLibrary get() = judge.presets

    /**
     * **每个角色的记忆**（§6.1、§14.49）。
     *
     * 它一直在 [npc] 手里，而那个字段是私有的。这一层把它露出来是因为**存档要交出去、读回来要装回去**
     * ——而"谁写过话"这件事只有测试与探针问得出（`Conversations` 没有别的入口）。露一个只读的属性，
     * 比让存档那一边拿着 `Npc` 整个人走要好：换一个扮演角色的实现时，这一条不用跟着改。
     */
    val conversations: Conversations get() = npc.conversations

    // ── 存档（§14.49） ──────────────────────────────────────────────────────────────

    /**
     * 这一局正在写向哪一个文件。**没有选定就什么都不落盘**（用户定的形状：单档、玩家自己选的那个文件）。
     *
     * 它只由 [saveTo] 设置；自动存档读它，而在它是 null 的时候**静默跳过**——"还没选文件"是一件正常的
     * 事（一局刚开始），不是错误。
     */
    private var savePath: String? = null

    /** 现在这一局在存哪一个文件（界面要显示它）。 */
    val savingTo: String? get() = savePath

    /** 正在写盘。自动存档与手动存档共用它——两处同时写同一个文件是自找的。 */
    private var saveInFlight = false

    /**
     * 把这一局整份交出去（§14.49）。
     *
     * **它是一个纯读操作**：世界、对话、生成包各拍一份快照，不碰 `writes` 那把锁。理由在 [run] 的
     * `finally` 那一段里——它是在回合**结束**时调的，那时没有写世界的活儿在跑。
     */
    fun toSaveGame(game: String = ""): SaveGame = SaveGame(
        game = game,
        savedAt = System.currentTimeMillis(),
        world = world.snapshot(),
        conversations = npc.conversations.snapshot(),
        presets = presets.generatedPresets(),
    )

    /**
     * 存到 [path]：**先选定这个文件，再写一份**。
     *
     * 这是"存"那一个按钮的全部行为。选定之后，每一回合结束的自动存档都写向它（[autoSave]）。
     *
     * 写不进去会**抛**（磁盘满、没有权限），而界面那一侧把它翻成 [notice] 里的一句话——存档悄悄失败
     * 比报一个错糟得多。
     */
    suspend fun saveTo(path: String, game: String = "") {
        val snapshot = toSaveGame(game)
        savePath = path
        SaveFile.save(path, snapshot)
        rememberThisFile(path)
    }

    /**
     * **把这一局绑到某个文件上，但先不写**（§14.49）。
     *
     * 读档那一条路用它：那份存档**已经在磁盘上了**，而读者刚刚从它里面读出来——立刻回写一遍等于把
     * 读到的字节原样写回去，只为了让"每一回合自动存向哪个文件"这件事有个值。所以它只记路径。
     */
    fun autoSaveTo(path: String) {
        savePath = path
    }

    /** 记下"上次玩的是这一份"，给主界面的「继续」用。它失败不该影响存档本身。 */
    private suspend fun rememberThisFile(path: String) {
        try {
            LastSave.remember(path)
        } catch (cause: Throwable) {
            // 记不住只是"下次要重新选一次文件"，而存档已经写好了——那件事不该变成一个错误。
        }
    }

    /**
     * 回合末尾那一次自动存档（§14.49）。
     *
     * **不挡人**：它起一个协程，把快照**先拍下来**（在界面线程上，纯数据），然后写盘落在 IO 线程上。
     * 快照必须在启动协程**之前**拍，否则写的就是之后的某一刻——而那时候玩家已经在下一回合里动了，
     * 存下来的会是两个回合混在一起的世界。
     *
     * 没选过文件、或者上一次还没写完，就什么都不做。
     */
    private fun autoSave(scope: CoroutineScope, game: String) {
        val path = savePath ?: return
        if (saveInFlight) return
        val snapshot = toSaveGame(game)
        saveInFlight = true
        scope.launch {
            try {
                SaveFile.save(path, snapshot)
            } catch (cause: Throwable) {
                notice = "自动存档没写成：${cause.message ?: cause::class.simpleName}（这一局的进度还在，但它没落盘）"
            } finally {
                saveInFlight = false
            }
        }
    }

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
        try {
            runTurnOnce(said, acted, scope)
        } finally {
            // **一回合的账结清了，现在存**（§14.49）。
            //
            // 挂在这里、而不是挂在 `run` 那一个包装上，是因为**回合有三条入口**：`submit`（`run` 里跑
            // 的那个）、探针直接调的 [runTurn]、以及纠正预览切开之后的另一半。挂在包装上只会覆盖第一条。
            //
            // 快照在启动协程**之前**拍（[autoSave] 里那条理由：之后拍就会把下一回合的东西混进来）。
            autoSave(scope ?: fallbackScope, said.ifBlank { acted })
        }
    }

    /**
     * 探针**不给** scope 时用的那一个（§14.49）。
     *
     * 在探针里存盘没有意义（它跑一次就结束），所以这个 scope 只负责"别让 `scope.launch` 因为 scope 为
     * null 而炸掉"。它是 JVM 上的一个普通后台协程，没有人会去等它。
     */
    private val fallbackScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    private suspend fun runTurnOnce(said: String, acted: String, scope: CoroutineScope?) {
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

        // **到点了：还在走循环的人挪到它此刻该在的那一格上**（§14.44）。放在这里——这一回合读世界的
        // 每一处（判定、渲染、唤醒）之前——而不是"到点那一瞬间"：快进会跳过时刻，没有任何代码在那一瞬
        // 看着，所以正确的做法是每轮开头对一遍。§14.45 起它还要处理"到点却进不去"的人。
        catchUpCycles()

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

        // **时钟往前推过了，所以对一遍循环**（§14.44、§14.45）。它与 `runTurn` 开头那一处是同一个
        // 动作，两处都要有：一处在"时间自己走了"之后，一处在"玩家动了"之前。
        catchUpCycles()

        if (stop == null) {
            say(LogLine.Kind.NOTE, "（时间轴上没有将来要发生的事，等不到什么）")
            return
        }

        say(LogLine.Kind.NOTE, "（你等到 ${stop.at}）")
        takeDue(stop)
    }

    /**
     * 把一次快进里**到期的那几件事**写进日志、写进记录层、投递给这一片的人（§14.37）。
     *
     * 到期的那些**已经从时间轴上取走**（时间轴是计划，不是记录），所以这里是它们唯一的落点。
     *
     * 一条局限写在明处：**这是一次世界级的事，而它的受众只按玩家身边那一片算**。远处一个同样该知道
     * "酒馆打烊了"的角色收不到——等角色多起来、时间轴上有跨地点的事时，这里要重新想。
     */
    private fun takeDue(stop: Stop) {
        stop.happened.forEach { say(LogLine.Kind.NOTE, "· ${it.what}") }
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

    // ── 说话：即时问答，不等回合（§14.51） ────────────────────────────────────────────

    /** 说一句话。**它不推进时间、不占名额**——说完对象立刻回你。 */
    fun sayNow(scope: CoroutineScope, text: String) {
        run(scope, "说一句…") { sayNowNow(text, scope) }
    }

    /**
     * 即时说话（§14.51，用户裁定）。
     *
     * ## 为什么它不走回合
     *
     * > 对于说话，直接唤醒被指向的说话对象，直接问答，不等回合提交。
     *
     * 所以这里**不改时钟**、不推进任何东西：判定第一步找出听者（那是一次模型调用），那句话进记录层，
     * 被指向的人当场醒来回答。想说几句就说几句，世界停在原处——"说话不过轮次"在代码里就是这一行。
     *
     * ## 它为什么仍然要过判定第一步
     *
     * 因为"这句话是说给谁听的"是**语义判断**，代码做不了（§3.2.3 那条：代码近似出来的知识分布会静默
     * 地错）。用户也确认了这一条保留。代价写在明处：一句闲聊 = 一次判定调用 + 被指向的人各一次唤醒。
     */
    suspend fun sayNowNow(text: String, scope: CoroutineScope? = null) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return

        // **气泡只存在当前这一场对话**（§5.3）：说了新的一句，上一批气泡就该退场。
        speech = emptyList()
        say(LogLine.Kind.SAY, "你说：「$spoken」")
        speech = listOf(Spoken(PLAYER, spoken))

        val addressed = addressedBy(spoken)
        recordSpeech(spoken, addressed)
        hearAndReact(spoken, addressed, scope)
        entities = world.all()
    }

    // ── 一回合 = 一格（§14.51） ──────────────────────────────────────────────────────

    /**
     * **这一格会自己行动的那些人，按发言队列的顺序**（§14.51）。
     *
     * 三类人在里面：**玩家**、**活跃的角色**（被拉出循环的那些）、**剧情角色**（没有循环的那些）。
     * **氛围角色不在里面**——他有循环、又没被叫住，于是由代码按时钟挪，"这一格要做什么"这个问题根本
     * 不该问他。§3.2.1 那句「氛围 NPC 平时根本不是 AI」在代码里就是这一行。
     *
     * 顺序与"同时被点到名的人依次开口"共用同一个队列（§5.4）：一个人在这两处的位置是同一个位置，而
     * 玩家也有自己的那一格（`StartingWorld` 把他的 id 也排进了名单）。
     */
    private fun roundOrder(): List<String> = world.speakersAmong(
        world.all()
            .filter { it.kind == CHARACTER_KIND && (it.active != null || it.cycle.isEmpty) }
            .map { it.id },
    )

    /** 「下一轮」：**过一格**（§14.51）。 */
    fun nextTurn(scope: CoroutineScope, acted: String) {
        run(scope, "过一格…") { nextTurnNow(acted, scope) }
    }

    /** 一格有多久，给界面印在按钮上（§14.51）。**它由配置算出来，不是写死的那句话。** */
    val tickLabel: String
        get() = scheduleSettings.minutesPerTurn.let { if (it % 60 == 0L) "${it / 60} 小时" else "$it 分钟" }

    /**
     * 一格的完整流程（§14.51）：
     *
     *  1. 钉下这一格的事件起点，清掉上一格的气泡。
     *  2. 到点就位（这一格开始时谁该在哪）。
     *  3. **按顺序让参与的人各自行动**：轮到玩家就是他准备的那件事（判定两步），轮到 NPC 就是一次
     *     `Heard.Tick` 的唤醒。**后来者看得见前面这一格做了什么**——因为每一步都当场落库。
     *  4. 走一格（时钟往前十五分钟；时间轴上有事到期就停在那一刻并报出来）。
     *  5. 补硬圈、让快速档看一眼（**一格一次**，不是每人一次）。
     *  6. 存档。
     *
     * ## 为什么"每步当场落库"就是用户要的"统一提交"
     *
     * 这一格里所有事件的时刻都是**同一个** `world.clock`——时钟只在第 4 步动。于是"统一提交"落到事实
     * 上就是"这一格发生的事共用一个世界时刻"，而不需要真去做快照与批量 apply（那是 §12 第 42 项给自己
     * 留的另一条路，它要买的是并行）。
     *
     * ## 为什么玩家也在那个 `for` 里
     *
     * 因为用户定的：**玩家也是角色的一员，也有自己在顺序里的位置**。所以轮到他的时候才跑他的动作——
     * 他前面那些人这一格做了什么，他做决定时是看得见的（与"后来者看得见"同一条规则）。
     */
    suspend fun nextTurnNow(acted: String, scope: CoroutineScope? = null) {
        previousTurnStartSeq = turnStartSeq
        turnStartSeq = world.log.latestSeq + 1
        speech = emptyList()

        catchUpCycles()

        for (id in roundOrder()) {
            if (id == PLAYER) {
                val done = acted.trim()
                if (done.isNotEmpty()) {
                    // **判定不带刚说的话**（用户裁定，§14.51）：语境在听者的对话历史里，而判定那一栏
                    // 判的是这次动作指向谁、改了什么。
                    judgeAndCommit(done, "", scope, atTurnEnd = false)
                }
            } else {
                tickFor(id, scope)
            }
        }

        val stop = advanceSlot()
        closeGap()
        if (scope != null) scope.launch { watch(roundNarrative(stop)) }
        autoSave(scope ?: fallbackScope, acted)
    }

    /** 轮到某个 NPC 的"这一格"：一次唤醒，一个 [Heard.Tick]。 */
    private suspend fun tickFor(id: String, scope: CoroutineScope?) {
        val task = startTask("${labelOf(id)}在过它这一格…")
        val reaction = try {
            writes.withLock {
                npc.wake(
                    world,
                    NpcWaking(
                        id,
                        // **它看得到上一格加上本格到目前为止的事**（用户定的："一回合内说的话，也放进
                        // 事件流，下一回合周围的角色可以查看"）。只用本格起点的话，上一格说过的话对它
                        // 不存在——`world.bus` 是按 seq 截的。
                        Heard.Tick,
                        origin,
                        sinceSeq = previousTurnStartSeq,
                    ),
                )
            }
        } catch (t: Throwable) {
            say(LogLine.Kind.ERROR, "${labelOf(id)}没醒过来：${t.message}")
            null
        } finally {
            endTask(task)
        }
        if (reaction != null) {
            announce(id, reaction)
            // 它这一格里自己回去了（§14.52 的更正：回归**不**叫人设追加，只写那一行日志）。
            settleArrival(id, reaction)
        }
        entities = world.all()
    }

    /** 一格给导演快速档看的那一句。 */
    private fun roundNarrative(stop: Stop): String = buildList {
        add("这一格（${world.clock}）里：")
        if (speech.isEmpty()) {
            add("没人在你眼前说过什么。")
        } else {
            add(speech.joinToString("；") { "${labelOf(it.whoId)}说：「${it.text}」" })
        }
        if (stop.happened.isNotEmpty()) add("到期的事：${stop.happened.joinToString("；") { it.what }}。")
    }.joinToString(" ")

    /**
     * 走一格（§14.51）：世界钟往前 [ScheduleSettings.minutesPerTurn] 分钟，**但时间轴上有事要到期时
     * 停在那一刻**（[FastForward.run] 的语义：停下来意味着该有人看一眼了）。
     *
     * 它顺带把"时钟动了"这件事做完：对一遍循环（§14.44、§14.45）、把界面状态同步过去。
     */
    suspend fun advanceSlot(): Stop {
        val stop = FastForward.run(world, world.clock + scheduleSettings.minutesPerTurn)
        clock = world.clock
        timelineRevision++
        takeDue(stop)
        catchUpCycles()
        entities = world.all()
        return stop
    }

    /** **过 N 格**（§14.51）：只推时间，不叫任何人、不叫导演。 */
    fun passSlots(scope: CoroutineScope, count: Int) {
        run(scope, "过 $count 格…") { passSlotsNow(count, scope) }
    }

    /**
     * 推 [count] 格。**零模型调用**——没有谁被叫醒，那几格是"没人看着的时间"（与"推格期间导演一次都
     * 不看"是同一条）。
     *
     * **碰上第一件到期的事就停下**（用户定的，§14.51）：与 [FastForward.run] 那句"停下来意味着该有人
     * 看一眼了"是同一条理由。剩下的格子留给玩家——他得先看一眼发生了什么，再决定要不要接着过。
     */
    suspend fun passSlotsNow(count: Int, scope: CoroutineScope? = null) {
        if (count <= 0) return
        var left = count
        while (left > 0) {
            val stop = advanceSlot()
            left--
            if (stop.interrupted) {
                say(LogLine.Kind.NOTE, "（走到 ${stop.at} 就停下了——这一格里有事到期；还剩 $left 格没走）")
                break
            }
        }
        autoSave(scope ?: fallbackScope, "过了 $count 格")
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
                // 这一回合的账已经结清了，现在才是"存下来的世界"与"我看到的那个世界"一致的时刻
                // （§14.49）。它在 finally 的末尾：前面那些一旦抛出去，这里就不该再动。
                autoSave(scope, what)
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

    // ── 到点就位（§14.45） ──────────────────────────────────────────────────────────

    /**
     * **到点了，对一遍循环上的人**：能进的进，进不去的变活跃、并且惊动导演。
     *
     * ## 顺序，以及每一步为什么要在这儿
     *
     *  1. [Scheduler.catchUp] 先跑（**不拿锁**）：它自己会先做那半个免费的判据（地形），再对有变化的
     *     那些人各问一句哨兵。这一段可能跑几秒，而它一个字都还没写进世界——所以不该占着写锁。
     *  2. 拿到结果之后**在一把锁里**做两件事：把"进不去"的人放下来（变活跃），再叫导演。合成一次
     *     临界区，是因为"它已经脱轨、而导演还没看见它"这个中间态不该被另一个写入者插进来（§14.33 的
     *     保守版：会写世界的工作一次只跑一个）。
     *  3. 导演那一次是**同步**的（与 `closeGap` 那条硬圈缺口同一种做法）：玩家这一回合接下来要读世界，
     *     而一个刚刚卡住的人必须已经有一份能走的作息，否则这一回合读到的就是一个正在脱轨的人。
     *
     * ## "变活跃"是谁的事
     *
     * 不是 [Scheduler] 的：哨兵只回答"这一格现在做得了吗"，而"做不了"之后**把它从循环上拿下来、放到
     * 那一格的坐标上、再把这件事写进日志并告诉导演**——这一串是会话的活（它才知道玩家看得见什么、
     * 导演在哪儿、日志写哪一行）。
     *
     * ## 它落在实体上的四个字段
     *
     * `active = Active(since = 现在, target = 那一格)`。那个 `target` **只作归因**（§14.45 改了回归
     * 的判据，见 `Active.canGo`），而它此前在生产代码里从来没有被设过——于是"回到循环上"那条路在真
     * 游戏里一直是死的。这一句就是那条路活过来的地方。
     *
     * ## 两路合流：到点进不去，与**脱轨太久**（§14.50）
     *
     * 第二路是设计文档 §3.2.2 那条兜底（"目标永远达不到——玩家把它锁起来、带走了"）。它与第一路**共用
     * 下游**（都会让人变活跃、都会叫导演改作息），但**上游完全不同**：第一路是"它到了那一格却没进得去"
     * （于是要把它放到那儿），第二路是"它早就该回去而回不去"（于是**什么都不动**——它本来就被锁在别处，
     * 把它挪到酒馆柜台后面正是它做不到的那件事）。
     *
     * 分开的地方是**"进这一段时它是不是已经活跃"**：`world/longAbsent` 报出来的那一格必然来自一个活跃
     * 的人，而 `refreshCyclesToClock` 报出来的必然来自一个不活跃的。那一读**在锁内**做（外面读会有一个
     * 窗口让它过期，而这一层全部写世界的东西都在同一把锁里）。
     */
    private suspend fun catchUpCycles() {
        val caught = (scheduler ?: Scheduler(SentryJudge(config), scheduleSettings.activeTooLongMinutes))
            .catchUp(world)

        // **一遍进得去的人先就位**（§14.45）。刷新自己只报不写（理由见 `Placement` 的注释），所以
        // 真正落坐标的是这里。放在临界区之外也可以：那时候还没有任何人被判"卡住"，而这一步只是把
        // 基线铺到事实上——但它与下面那段必须**共用同一个判断结果**，所以一起写在这儿。
        for (placement in caught.moved) {
            val who = world.byId(placement.id) ?: continue
            world.put(who.copy(x = placement.beat.x, y = placement.beat.y, z = placement.beat.z))
        }

        if (caught.stuck.isEmpty()) return

        val task = startTask("有人到点了却进不去，导演正在改这一片…")
        try {
            writes.withLock {
                /** 已经脱轨（不是这一轮刚被拿下来）的那批——他们**不动位置**，而且只为"新发现"叫导演。 */
                val alreadyAbsent = mutableSetOf<String>()
                val stuck = caught.stuck.mapNotNull { placement ->
                    val who = world.byId(placement.id) ?: return@mapNotNull null
                    if (who.active == null) {
                        val now = Active(since = world.clock, target = placement.beat)
                        // **放到那一格的坐标上再变活跃**（§14.45）：它这一趟真的走到了门口。
                        world.put(
                            who.copy(
                                x = placement.beat.x,
                                y = placement.beat.y,
                                z = placement.beat.z,
                                active = now,
                            ),
                        )
                    } else {
                        alreadyAbsent += who.id
                    }
                    placement
                }
                if (stuck.isEmpty()) return@withLock

                // **只为该叫的那批叫导演。**
                //
                //  - 到点却进不去的人**每一次都值得报**（那是刚发生的一件事，而它此刻正站在门口）。
                //  - **脱轨太久的人，每一趟脱轨只报一次**——那一趟由 `Active.since` 认出来（它本来就是
                //    为归因存在的，§14.50 起它同时是这条闸的键）。用"人"当键是不够的：同一次脱轨里
                //    `catchUpCycles` 会被调两遍（`runTurnOnce` 开头与 `waitUntilNextNow` 里），而一个
                //    被关着的人会每一轮都再报一遍——那是钱在烧、世界一动不动。
                //
                // 那一行会话日志跟着同一个名单走：不然它会每一轮重复一遍。
                val asked = stuck.filter { placement ->
                    placement.id !in alreadyAbsent || wasNotAsked(world.byId(placement.id))
                }
                if (asked.isEmpty()) return@withLock
                asked.forEach { forgotten += markerOf(world.byId(it.id)) }

                asked.forEach { placement ->
                    val name = world.byId(placement.id)?.label ?: placement.id
                    say(
                        LogLine.Kind.NOTE,
                        if (placement.id in alreadyAbsent) {
                            "（$name 离开自己的日子太久了，此刻停在 (${placement.beat.x}, " +
                                "${placement.beat.y}, ${placement.beat.z}) 之外的什么地方——导演要重看它那一格）"
                        } else {
                            "（$name 到了「${placement.beat.state}」该在的地方，却没能进去）"
                        },
                    )
                }

                val why = asked.map { it.reason ?: "" }
                // **它此刻那一份作息的出生时刻**：这是"它改没改"唯一读得出来的东西（理由见 `Wake.Stuck`）。
                val was = buildMap {
                    for (placement in asked) {
                        val born = world.byId(placement.id)?.cycle?.bornAt ?: continue
                        put(placement.id, born)
                    }
                }
                val awakening = director.wake(
                    world,
                    Waking(
                        Wake.Stuck(required = asked.map { it.id }, why = why, was = was),
                        PLAYER,
                        origin,
                    ),
                )
                // **失败要说出来**（§1.5 第三条：理由不撒谎）。导演沉默两次也一样是失败——那个人还
                // 卡在那儿，而"日志里什么都没写"会让它看着像被处理过了。
                if (awakening is Awakening.FailedProtocol) {
                    say(
                        LogLine.Kind.PROBLEM,
                        "这一次没改成他们的作息：" + awakening.problems.joinToString("；"),
                    )
                }
            }
        } catch (t: Throwable) {
            say(LogLine.Kind.ERROR, "改作息没跑起来：${t.message}")
        } finally {
            endTask(task)
        }
    }

    /**
     * 这一局里**已经为"脱轨太久"叫过导演**的那些趟（§14.50）。
     *
     * 键是**人 + 那一趟的起点**（`id@分钟数`），不是人：同一次脱轨只报一次，而同一趟里 [catchUpCycles]
     * 会被调两遍（`runTurnOnce` 开头与 `waitUntilNextNow` 里）。它**不进世界状态、不进存档**——读档之后
     * 重新数一遍是对的（那时 `since` 与 `target` 都还在实体上，判据立刻又成立）。
     *
     * **失败不还账**：导演没改成的话，那一次报告仍然发生过，而"下一轮再报一次"只会让它一遍遍重新烧钱
     * ——世界里的困境没变，重问一遍不会得到不同的答案（`Wake.Stuck` 那一段话本来就说清了它要做什么）。
     */
    private val forgotten = mutableSetOf<String>()

    private fun markerOf(who: Entity?): String =
        "${who?.id}@${who?.active?.since?.minutes ?: -1}"

    /** 这一趟要不要为它叫一次导演。 */
    private fun wasNotAsked(who: Entity?): Boolean = markerOf(who) !in forgotten

    // ── 一轮的内部 ──────────────────────────────────────────────────────────────────

    private suspend fun judgeAndCommit(
        intention: String,
        said: String,
        scope: CoroutineScope?,
        /**
         * 这一次动作是不是**这一轮的末尾**（§14.51）。
         *
         * `true`（默认）= 走完它就收尾（被指向的人反应、补硬圈、让导演看一眼）——那是"一次提交就是一轮"
         * 那条老路的形状。`false` = 这一轮还没完：玩家只是队列表里的某一位，后面还有人要行动，所以收尾
         * 那三件事由 [nextTurnNow] 在**所有人过完之后**统一做一次。
         */
        atTurnEnd: Boolean = true,
    ) {
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
                    runEffects(turn, alignment.targets, intention, said, addressed, scope, atTurnEnd)
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
        /** 见 [judgeAndCommit] 那一条：`false` = 这一轮还没完，收尾留到所有人过完。 */
        atTurnEnd: Boolean = true,
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
        if (atTurnEnd) afterTurn(intention, said, addressed, scope)
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
        // **按发言的顺序来**（`world/Talk.kt`）：同时被点到名的那些人依次开口，一个说完下一个。
        //
        // 顺序是一个**队列**，不是每人一个优先值：插队时后面的人自动顺延，所以"两个 NPC 优先值相同"
        // 这件事在设计上就不存在。没排过队的人排在最后（按 id），于是世界不必先给人排座次才能跑。
        val speakers = world.speakersAmong(addressed)
        // **一次点到两个人以上时，把顺序写出来**（§5.4）。它不只是给人看的：顺序是代码排的，而排错了
        // （插队那条路、或者名单被别处改了）症状是"某个人怎么老先说"——那种事没有一行日志就查不出。
        if (speakers.size > 1) {
            say(LogLine.Kind.NOTE, "（这一轮依次开口：${speakers.joinToString("、", transform = ::labelOf)}）")
        }
        for (id in speakers) {
            val task = startTask("${labelOf(id)}在回应…")
            // **这一下是不是"氛围角色变成活跃角色"的那一刻**（§3.2.1、§14.52）：`active` 本来是空的，
            // 就是它——那一瞬间要同步叫一次思考档导演，由它决定要不要给这个人补背景。要在唤醒**之前**
            // 读，因为下面那一段就会把它标成活跃。
            val turning = world.byId(id)?.active == null
            val reaction = try {
                writes.withLock {
                    // **被指向之后，它就不再走自己那个循环了**（§3.2.1、§14.51 落成代码的那一句）。
                    //
                    // 原文是「被指向之后，氛围 NPC 转化为活跃氛围 NPC……但保留它的状态循环」，而在这之前
                    // 那一格从来没做过：说话会把它叫醒，但它下一轮照样被作息挪走——**说着说着人去后厨了**。
                    //
                    // 这一条现在更要紧：时间开始走了（一格十五分钟），所以"说着说着人走了"从"可能"变成了
                    // "一定会"。变活跃之后它就留在原地，直到它自己用 `arrive` 回去（§14.43 那条路），
                    // 或者 §14.50 那条超时把人交给导演。
                    val current = world.byId(id)
                    if (current != null && current.active == null) {
                        world.put(
                            current.copy(
                                active = Active(
                                    since = world.clock,
                                    target = current.cycle.beatAt(world.clock),
                                ),
                            ),
                        )
                    }
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
            // **它自己回去了**：只写那一行日志（回归不触发人设追加）。
            settleArrival(id, reaction)
            // **而"变成活跃角色"那一下要叫导演**（§3.2.1 那句"转化的同时直接唤起思考档导演"，从写下那天
            // 起就没有落成代码）。放在反应之后：玩家先看到它答话，而导演补的背景是下一次用它。
            if (turning) wakeTheDirectorAboutTurningActive(id, heard = said)
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
     * **它自己回到了循环上**（§3.2.1、§14.43）。
     *
     * 它只做一件事：把那一行日志写出来，并把 §14.50 那笔账还掉。**它不叫导演**——回归不触发人设追加。
     *
     * 那一条是这一刀做出来之后**用户更正过的**（§14.52）：人设追加**不是"把这一趟的经历写进去"**——
     * 历史本来就在角色自己的上下文里（对话历史 + 事件总线）。它要的是**补上它本来该有、而开局那张卡
     * 没写的那一层背景**：被问到哪里有酒馆、要托付它一条支线、要把它升成剧情角色时，原来那份简单的
     * 设定就不够了（"它其实是传奇铁匠的传人"）。而那件事发生在**氛围角色变成活跃角色**的那一刻。
     */
    private fun settleArrival(id: String, reaction: Reaction) {
        if (!reaction.arrived) return
        say(LogLine.Kind.OK, "（${labelOf(id)} 回到了自己的日子上）")
        // **那一趟的账还回去**（§14.50）：已经回到循环上了，于是"为这一趟脱轨叫过导演"这件事作数完
        // 了。不还的话，它下一次再被带走时就不会再有人替它叫导演——而那是同一个人的第二趟。
        forgotten.removeAll { it.startsWith("$id@") }
    }

    /**
     * **一个氛围角色变成了活跃角色**（§3.2.1、§14.52）：那一下同步叫一次思考档导演。
     *
     * §3.2.1 早写着「转化的同时**直接唤起思考档导演**，由导演统筹三件事」——而那一句从来没有落成代码
     * （§14.45 只做了"到点却进不去"那条路，而那是"被世界推着脱轨"，不是"被指向"）。这一刀补的是
     * **被指向**这条最常发生的路。导演被叫起来之后**自己决定**要不要给这个人补背景（用户的话：
     * "氛围 npc 转为活跃 npc 时本身就会唤醒思考导演，导演自己就能决定"）。
     *
     * ## 导演在这一刻要统筹的三件事（§3.2.1）
     *
     *  ① **人设追加**（补背景——"被问到哪里有酒馆"、"要托付它一条支线"时的那一层）；② 归宿（升剧情
     *  角色，还是事件完了回循环）；③ 是否开一条支线/主线。**现在只有第 1 件有手**（`write_card`），
     *  第 2、3 件还没有入口。
     *
     * ## 代价，以及为什么它不会重复
     *
     *  每一次"第一次跟某个人说上话"都多一次思考档调用（实测 6–11 秒）。它**只在转化那一下**发生：
     *  那个人下一格再开口时它已经是活跃角色了，`heard` 那条路不会再走这一次。
     */
    private suspend fun wakeTheDirectorAboutTurningActive(id: String, heard: String) {
        val name = labelOf(id)
        val task = startTask("$name 从氛围角色变成了活跃角色，导演在看它要不要补背景…")
        val before = world.byId(id)?.cards.orEmpty()
        try {
            writes.withLock {
                director.wake(
                    world,
                    Waking(
                        Wake.Called(
                            why = "$name（id 是「$id」）刚刚从**氛围角色变成了活跃角色**：" +
                                "有人跟它说上话了，于是它被从自己的日子上拿了下来（§3.2.1）。\n" +
                                // **触发它的那句话要原样带上**（§14.52 的联机读数逼出来的）：只给一份
                                // "将来可能会问它什么"的泛泛清单时，一次普通搭话（"你喝什么呢？"）它也
                                // 会补两张卡——而"这次为什么叫它"才是它该据以判断的那件事。
                                // **形状必须落在理由里，而这是这一刀量出来的通道矛盾**（§14.52 六遍读数）：
                                // 只在 `SYSTEM` 第 14 条写着两种形状时，改写**一次都没发生**（理由是最
                                // operative 的那一段）；而一旦理由里描述了"怎么写"，一次普通搭话又开始
                                // 补卡。两个数字都记在文档里：这个配置下**改写 2/2 成、小事也 2/2 补**。
                                "**先判该不该补**：只有你能说出一件接下来确实会问它的事（问路、托付" +
                                "一条支线、要它知道更多）才补；说不出来就**什么都别写**——那是合法的" +
                                "结果。\n" +
                                "**决定要补之后**，再看是哪一种形状：它需要**知道更多**（这一带有" +
                                "什么、它认识谁）→ **添一张新的**；它需要**与原来不同**（要它信任谁、" +
                                "要它改掉某一条脾气、要它跟某人翻脸）→ **改写与那件事冲突的那一张**" +
                                "（标题照着它现在写的那一条抄）。**别在冲突的那一条旁边另添一张**——" +
                                "两张卡互相打架，它每次醒来都要读一遍两个说法。\n" +
                                "**别写它这一趟经历了什么**——那些事本来就在它自己的上下文里。" +
                                "**该不该补判不出来，就什么都别写。**",
                            aboutId = id,
                        ),
                        PLAYER,
                        origin,
                    ),
                )
            }
        } catch (t: Throwable) {
            say(LogLine.Kind.ERROR, "背景没补成：${t.message}")
        } finally {
            endTask(task)
        }

        // **看得见的证据**：卡变没变，代码读得出来（与 §14.42 那些读数同一种做法）。不写这一行的话，
        // "这一次转化到底在它人设上留了什么"只能靠翻提示词才知道。
        val after = world.byId(id)?.cards.orEmpty()
        if (after != before) {
            val added = after.filter { card -> before.none { it.title.trim() == card.title.trim() } }
            val rewritten = after.filter { card -> before.any { it.title.trim() == card.title.trim() && it.content != card.content } }
            val what = buildList {
                if (added.isNotEmpty()) add("补了 ${added.joinToString("、") { "「${it.title}」" }}")
                if (rewritten.isNotEmpty()) add("改写了 ${rewritten.joinToString("、") { "「${it.title}」" }}")
            }.joinToString("，")
            say(LogLine.Kind.OK, "（它的人设$what——现在 ${after.size} 张：${after.titlesWithSize()}）")
        }
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

    companion object {

        /**
         * **读档**：拿一份存档造成一个会话（§14.49）。
         *
         * ## 为什么是"造一个新会话"，而不是"把世界塞进旧的那个"
         *
         * 因为 `world` 是 `val`（[GameSession] 里那一行），而会话里还有一大片跟着世界一起派生出来的东西
         * （渲染器、区块原点、时间轴修订号）。造一个新的让它们一起重新派生，比"就地重置"少十几处能漏的
         * 地方。而构造它**没有副作用**：不读盘、不发请求，只是把几个客户端造出来。
         *
         * ## 生成包：**同一个库要交到每一个看它的地方**
         *
         * [presets] 先装回存档里那一份，然后**显式**交给判定、导演、角色三处。
         *
         * 这里是踩过一次的地方：第一版写的是 `judge = Judge(config)`，于是那个判定器用的是**它自己那份
         * 空库**，而 `GameSession.presets` 正是 `judge.presets`——`installPresets` 装好的那一份**谁都没
         * 在用**，症状是"读回来的这一局生成包是空的，而存档里明明有"（一个只在真存过一次之后才看得见的
         * 静默错位）。
         *
         * ## 对话那一份在造完之后装回去
         *
         * `npc.conversations` 默认是空的，而**摘要是重算不出来的**。
         *
         * 后面那几个参数是给测试注入假客户端的。默认值故意与构造器那几行的写法**逐字一样**——两处写法
         * 一旦漂开，就是上面那个错位的同一种。
         */
        fun restore(
            config: EndpointConfig,
            save: SaveGame,
            presets: PresetLibrary = PresetLibrary(),
            scheduler: Scheduling = Scheduler(config),
            scheduleSettings: ScheduleSettings = ScheduleSettings(),
            npc: Npc = Npc(config, presets),
            judge: Judge = Judge(config, presets = presets),
            director: Director = Director(config, presets),
        ): GameSession {
            SaveFile.installPresets(presets, save)
            val session = GameSession(
                config = config,
                presets = presets,
                startingWorld = World.from(save.world),
                judge = judge,
                director = director,
                npc = npc,
                scheduler = scheduler,
                scheduleSettings = scheduleSettings,
            )
            session.conversations.restore(save.conversations)
            return session
        }
    }
}
