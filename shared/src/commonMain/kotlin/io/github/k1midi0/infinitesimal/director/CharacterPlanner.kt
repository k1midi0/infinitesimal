package io.github.k1midi0.infinitesimal.director

import io.github.k1midi0.infinitesimal.world.Beat
import io.github.k1midi0.infinitesimal.world.CHARACTER_KIND
import io.github.k1midi0.infinitesimal.world.CharacterCard
import io.github.k1midi0.infinitesimal.world.Cycle
import io.github.k1midi0.infinitesimal.world.Entity
import io.github.k1midi0.infinitesimal.world.World
import kotlin.math.abs

/**
 * 导演想放出来的一个人：**它是谁、从哪儿开始**（§8.5、§14.46）。
 *
 * 与 [CycleWrite] 同一种形状：**这是一份提议，不是事实**——校验与落库在 [CharacterPlanner]。
 *
 * ## 一个字段就分开了两档人
 *
 * [beats] 空 = **剧情角色**：它不会自己走动，只在被点到名的时候才有一个模型去演它（§3.2.1）。
 * [beats] 有 = **氛围角色**：代码按这张表把它挪来挪去，平时一次模型调用都没有——那正是氛围 NPC
 * 「平时根本不是 AI」的全部来源。
 *
 * 类型不单独存一个字段：它**由结构决定**（有循环就是氛围组），多存一个字段只会多一处能写岔的
 * 地方（§14.2 同一条）。
 */
data class CharacterWrite(
    val id: String,
    val label: String,
    val cards: List<CharacterCard>,
    /** 它一开局站在哪儿（厘米）。**有 [beats] 时这一处必须就是第一格**，否则它一出现就在走错路。 */
    val at: CharacterPoint,
    /** 它的作息。空 = 剧情角色（没有循环）。 */
    val beats: List<Beat> = emptyList(),
)

/**
 * 世界上的一处位置（厘米）。
 *
 * 它**不是** [Beat]：一格有"第几分钟"，而它没有——导演交 `at` 时手上没有、也不该有一个分钟数。
 * 用同一格在两者之间来回换，会凭空造出一个"第 0 分钟"的时刻，而那个时刻会被下游当成真的读到
 * （§14.2：写限制落在数据模型上，不落提示词）。
 */
data class CharacterPoint(val x: Int, val y: Int, val z: Int)

/** 放一个人读回来的东西：**要么全读出来了，要么是一条读不懂的理由**（理由里带着那段原文）。 */
sealed interface CharacterParse {

    data class Parsed(val write: CharacterWrite) : CharacterParse

    data class Failed(val why: String) : CharacterParse
}

/**
 * 把导演交的那几段文本读成一个人（§14.46）。
 *
 * 与 [CycleText] 同一条理由：导演的手只能收字符串（端点那层的 `JsonSchema` 不支持嵌套对象与数组），
 * 而**读不懂就把原文退回去**——§1.3：静默丢弃等于死循环。
 *
 * 卡那一段一行一张：`身份｜你是……`。**行首那一段是这张卡的标题**（[CharacterCard.title]）——
 * 标题不进提示词（它只给人看），所以写不出标题的行整行当正文收下。
 */
object CharacterText {

    private val separators = charArrayOf('|', '｜')

    /** 读「x,y,z」（厘米）。与 [CycleText] 认同一批写法：全角逗号、括号、空格都行。 */
    fun parsePoint(text: String): Triple<Int, Int, Int>? {
        val parts = text.removePrefix("(").removeSuffix(")").split(',', '，', ' ')
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size != 3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return Triple(nums[0], nums[1], nums[2])
    }

    fun parseCards(text: String): List<CharacterCard> = text.lines().mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty()) return@mapNotNull null
        val parts = line.split(*separators).map { it.trim() }
        val (title, content) = if (parts.size >= 2 && parts[0].isNotEmpty()) {
            parts[0] to parts.drop(1).joinToString("｜").trim()
        } else {
            "身份" to line
        }
        if (content.isEmpty()) null else CharacterCard(title, content)
    }
}

/**
 * 把一个人**一次性校验、一次性落库**（§8.5、§14.46）。
 *
 * 与 [CyclePlanner] 一样分成两个函数：模型那一头只看得到"有没有过"，而 [apply] 只在校验通过之后被
 * 调用（`require`）。这样工具那一层能在**真正落库之前**知道成败——「看着像成了、其实没动」正是
 * §1.5 第三条要防的那种失败。
 *
 * ## 校验什么
 *
 *  1. id 是空的；
 *  2. **世界里已经有这个 id**——只新建，不改旧的。要改一个活着的人，那是 [CyclePlanner] 或者
 *     以后那条"人设追加"的路（§12 第 46 项）；
 *  3. 没有名字、一张卡都没有（没有卡的角色在判定那边不是人，§3.5）；
 *  4. 开局那一格**看不见**（[visibleTo]）——看不见就是说那个坐标只能是编的；
 *  5. [Cycle.problems] 那几条，以及**作息里每一格都得看得见**；
 *  6. 有作息时，[CharacterWrite.at] 必须就是**第一格**。
 *
 * **没有一条是"这个人合不合常理"**——那是导演自己的活；代码去判它就得读懂那句话，于是就会开始
 * 静默地判错（A.6、A.7 同一条纪律）。
 */
object CharacterPlanner {

    /** 这一批人有什么问题。空列表 = 都能放。一次报全，不报第一条就停（理由同 [TimelinePlanner]）。 */
    fun problems(world: World, writes: List<CharacterWrite>, visible: Set<String>): List<String> = buildList {
        val repeated = writes.groupBy { it.id }.filterValues { it.size > 1 }.keys
        for (id in repeated) {
            add("「$id」在这一批里出现了不止一次。一个 id 只放一个人。")
        }

        for (write in writes) {
            val who = write.id.trim()
            if (who.isEmpty()) {
                add("有一个人没起 id。id 是你以后叫得出它的那个名字（例如 铁匠_格里姆）。")
                continue
            }
            if (world.byId(who) != null) {
                add(
                    "世界上已经有 id 是「$who」的东西了。**这只手只放新人**——想改它现在的日子，" +
                        "用 write_cycle；想改它自己是另一个人。",
                )
                continue
            }
            if (write.label.isBlank()) {
                add("「$who」没有名字。label 是给人看的那个名字（例如「铁匠格里姆」）。")
            }
            if (write.cards.isEmpty()) {
                add("「$who」一张卡都没有。没有卡的角色在判定那边不是人——至少写一张身份卡。")
            }
            // **"这片地上该长点东西"不是这只手的活**（§14.48）。实测跑出来的：导演把枯灌木、碎石堆、
            // 干草丛、枯树、风化石板一个一个放进世界，每个都带 `kind="地形"` 或者一个植物的名字——它把
            // 这只手当成了摆道具的工具。那不是"一个人写错了参数"，而是**用错了地方**，所以这里要拦的
            // 是那件事本身，而理由必须指出该走哪条路。
            val landmark = sceneDressing(write.label)
            if (write.cards.isNotEmpty() && landmark != null) {
                add(
                    "「${write.label}」看着是**这一片地上的东西**，不是一个人（$landmark）。" +
                        "这只手只放**有名字的人**——一片荒地上的碎石、枯草、枯树该由 `grow_terrain` 铺地时" +
                        "顺手长出来（它的 `placements` 就是点名放地标的）；" +
                        "要摆一件死物，走那条路。",
                )
            }
            if (!world.covered(write.at, visible)) {
                add(
                    "「${write.label}」要站的地方 (${write.at.x}, ${write.at.y}, ${write.at.z}) " +
                        "不在你这一轮看得见的范围里——那个坐标只能是你编的。抄你眼前那段清单里的位置。",
                )
                continue
            }

            if (write.beats.isEmpty()) continue

            val cycle = Cycle(beats = write.beats)
            for (problem in cycle.problems()) add("「${write.label}」的作息：$problem")

            val first = write.beats.minByOrNull { it.atMinute }
            if (first != null && far(first, write.at) > SAME_SPOT_CM) {
                add(
                    "「${write.label}」开局站在 (${write.at.x}, ${write.at.y}, ${write.at.z})，" +
                        "而它作息的第一格在 (${first.x}, ${first.y}, ${first.z})——" +
                        "那样它一出现就站在走错的那一格上。两处写成同一个位置。",
                )
            }
            for (beat in write.beats) {
                if (!world.covered(beat, visible)) {
                    add(
                        "「${write.label}」作息里第 ${beat.atMinute} 分钟那一格在 " +
                            "(${beat.x}, ${beat.y}, ${beat.z})，那不在你这一轮看得见的范围里。" +
                            "作息是代码查的——写进你看不见的地方，等于让它走到一个你没看过的地方去。",
                    )
                }
            }
        }
    }

    /**
     * 落进世界。
     *
     * `background` **由代码从它站的地方推**（[deriveBackground]），不由导演写：新角色身上没有任何
     * 形状，渲染它的人只能靠这一句，而"它站在哪、旁边那个地方是什么"是代码手上现成的、比模型编一句
     * 更准的东西。
     *
     * 有作息的人**出生那一刻就按第一格站着**——它此刻的相位就在第一格上，位置与作息一致。
     */
    fun apply(world: World, writes: List<CharacterWrite>) {
        for (write in writes) {
            require(world.byId(write.id) == null) {
                "CharacterPlanner.apply 收到了世界上已经有的 id ${write.id}"
            }
            world.put(
                Entity(
                    id = write.id.trim(),
                    kind = CHARACTER_KIND,
                    label = write.label.trim(),
                    x = write.at.x,
                    y = write.at.y,
                    z = write.at.z,
                    cards = write.cards,
                    cycle = Cycle(beats = write.beats),
                    background = deriveBackground(world, write.at, write.label.trim()),
                ),
            )
        }
    }

    /**
     * 它站在那里的时候，身边是什么地方、什么东西——**照抄那一句，不编**（[Entity.background]）。
     *
     * 挑最近的那一个：先比三轴里最大的那个差（与 `nearEnough` 同一条判据），一样近时按 id 排
     * ——**同一份世界每次跑出同一句**，否则提示词每回合都不一样，缓存前缀就白丢了。
     *
     * 一个都够不着时**明说没有**（`null`），而不是留给渲染器一个空串：那样"还没建模"与"这里什么都
     * 没有"会在提示词里长得一模一样。
     */
    private fun deriveBackground(world: World, at: CharacterPoint, label: String): String? {
        // **最近的那个说得出话的**，不是"最近的那个"：紧挨着它站的可能是玩家、是另一个人、是一只
        // 酒杯——那些身上没有可抄的一句（`background` 与 `sentences` 都是空的），而它旁边五米内
        // 那间屋子有。挑第一个能读出字来的，才是"它站在那里的时候，身边是什么地方"。
        val near = world.all()
            .filter { far(it, at) <= NEARBY_CM }
            .sortedWith(compareBy({ far(it, at) }, { it.id }))
            .firstNotNullOfOrNull { entity ->
                val what = entity.background?.trim()?.takeIf { it.isNotEmpty() }
                    ?: entity.sentences.joinToString("").trim().takeIf { it.isNotEmpty() }
                what
            }
            ?: return null
        return "$near\n$label 就在这里。"
    }

    /** 三轴里差得最远的那个数（厘米）。与 `nearEnough` 同一读法。 */
    private fun far(entity: Entity, at: CharacterPoint): Int = maxOf(
        abs(entity.x - at.x),
        abs(entity.y - at.y),
        abs(entity.z - at.z),
    )

    private fun far(a: Beat, b: CharacterPoint): Int = maxOf(abs(a.x - b.x), abs(a.y - b.y), abs(a.z - b.z))

    /**
     * 这个名字看着是**这一片地上的东西**吗——是的话，说出是哪一类（不是的话返回 null）。
     *
     * ## 为什么这条判据存在，而它不是"代码去判内容合不合理"
     *
     * §14.46 说过这里一条都不判"这个人合不合常理"。这一条不同的地方在于**它判的不是内容，是用法**：
     * 实测（§14.48）里导演拿这只手放了"枯灌木 / 碎石堆 / 干草丛 / 枯树 / 风化石板"——十五件全是死物，
     * 而每一件都带着卡（所以"一张卡都没有"那道门放它过去了）。那不是参数写错，是把手用错了地方，而它
     * 的坏法很实在：**一个死物落进角色表里**，之后每一个"这一片里有谁"的判据都会把一块石头当成一个人
     * （判定按"有没有卡"取人，§3.5）。
     *
     * 判据刻意做**窄**：只看那些**成片出现的自然物**的词，不做"像不像人名"那种判断——后者会开始猜，
     * 而「张石头」是一个人的名字。
     */
    private fun sceneDressing(label: String): String? {
        for (pattern in DRESSING) {
            if (pattern.containsMatchIn(label)) return "它说的是「${pattern.pattern}」这一类东西"
        }
        return null
    }

    /**
     * 那些词：成片的自然物、地表的一部分。**每一类都短**，因为长一点就会开始咬到人名
     * （「石头」是名字里常见的字，所以只认「碎石堆」「风化石板」这种成片的说法，不认单个「石」）。
     */
    private val DRESSING = listOf(
        Regex("灌木|草丛|杂草|苔藓|藤蔓"),
        Regex("碎石|石块|石板|岩体|砾石|砂砾"),
        Regex("枯树|树桩|树皮|树根"),
        Regex("泥坑|水洼|积水|土堆|沙丘|积雪"),
        Regex("残骸|废墟|断墙|瓦砾|栅栏|篱笆"),
    )

    /** 多近算"就在它旁边"：见文件末尾 [NEAR_CM] 那段注释。 */
    private const val NEARBY_CM = NEAR_CM

    /** 两格。与 [io.github.k1midi0.infinitesimal.world.ARRIVE_TOLERANCE_CM] 收窄的理由一样。 */
    private const val SAME_SPOT_CM = 100
}

/**
 * 这一处位置在不在"我这一轮看得见的地方"里。
 *
 * ## 判据是距离，不是"那个坐标上正好站着一个实体"
 *
 * 坐标是厘米，而清单里印的是每样东西**自己的**位置——"站在柜台后面"与"柜台"本来就是两处坐标。要求
 * 逐字相等，会把每一个正常的位置都判成编的。
 *
 * ## 为什么五米，而不是去问那一片的凸包
 *
 * 因为**同一件事别处已经有了一条五米的判据**：[io.github.k1midi0.infinitesimal.world.refreshCyclesToClock]
 * 里"[它在不在这一格附近]"用的是 `nearEnough` 那五米，而 §14.45 之后"到点就位"只处理看得见的人。
 * 两处取同一个数，于是"导演写下去的位置"与"代码到点认不认那个位置"不会各说各话。
 *
 * 它**宽而不严**：可能放行一个刚好在凸包之外、但身边确实有东西的位置。那一边错得起——位置差几十厘米
 * 在这套判据里本来就是同一处（`SAME_SPOT_CM` 那一档）；反过来严了，会把一个正经的位置判成"编的"，
 * 而那条错它修不动（它会以为是自己抄错了坐标）。
 */
private fun World.covered(point: CharacterPoint, visible: Set<String>): Boolean =
    all().any { entity ->
        entity.id in visible && maxOf(
            abs(entity.x - point.x),
            abs(entity.y - point.y),
            abs(entity.z - point.z),
        ) <= NEAR_CM
    }

/** 作息里的一格也要落在看得见的地方（§14.44 那条门在 §14.46 上照样成立）。 */
private fun World.covered(beat: Beat, visible: Set<String>): Boolean =
    covered(CharacterPoint(beat.x, beat.y, beat.z), visible)

private const val NEAR_CM = 500
